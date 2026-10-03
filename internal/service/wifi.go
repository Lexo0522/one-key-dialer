package service

import (
	"errors"
	"sync"
	"time"

	"github.com/Lexo0522/one-key-dialer/internal/i18n"
	"github.com/Lexo0522/one-key-dialer/internal/platform"
)

// WifiService WiFi 扫描缓存、连接/断开流程与后台自动连接。
// 连接动作由调用方(App)提交到后台执行器,本服务只负责状态与流程;
// 状态变化经 onChange 回调上报,由 App 转成 app:wifi 事件。
type WifiService struct {
	logger   *LogService
	onChange func()
	pskFor   func(ssid string) string

	mu          sync.Mutex
	scanCache   []platform.WlanNetwork
	scanAt      time.Time
	connecting  bool
	autoSsid    string
	cancel      chan struct{}
	wg          sync.WaitGroup
	autoRunning bool
}

// NewWifiService 构造 WiFi 服务。
// pskFor 由 App 提供:返回给定 SSID 已保存的 PSK(无则空串)。
func NewWifiService(logger *LogService, onChange func(), pskFor func(ssid string) string) *WifiService {
	return &WifiService{logger: logger, onChange: onChange, pskFor: pskFor}
}

// scanCacheInterval 扫描结果缓存时长,防止 UI 频繁刷新拖累 WLAN 服务。
const scanCacheInterval = 3 * time.Second

// autoConnectInterval 自动连接轮询间隔。
const autoConnectInterval = 10 * time.Second

// Available 本机是否有可用无线网卡。
func (s *WifiService) Available() bool { return platform.WlanAvailable() }

// Scan 返回网络列表(force 时触发刷新扫描);缓存期内直接返回上次结果。
func (s *WifiService) Scan(force bool) []platform.WlanNetwork {
	s.mu.Lock()
	cached := s.scanCache
	fresh := time.Since(s.scanAt) < scanCacheInterval
	s.mu.Unlock()
	if !force && fresh && cached != nil {
		return cached
	}
	nets, err := platform.WlanScanList()
	if err != nil {
		s.logger.Warning(i18n.Tf("wifi.scanFailed", err.Error()))
		s.mu.Lock()
		s.scanAt = time.Now() // 失败也计入冷却,避免连续砸 WLAN 服务
		s.mu.Unlock()
		return cached
	}
	s.mu.Lock()
	s.scanCache = nets
	s.scanAt = time.Now()
	s.mu.Unlock()
	return nets
}

// Status 当前无线状态。
func (s *WifiService) Status() platform.WlanState {
	st, err := platform.WlanCurrent()
	if err != nil {
		return platform.WlanState{Phase: "idle"}
	}
	return st
}

// IsConnecting 是否有连接流程进行中。
func (s *WifiService) IsConnecting() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.connecting
}

// Connect 连接指定网络(阻塞,最长约 20 秒,调用方负责放后台)。
// 密码为空且已有配置时沿用既有配置。
func (s *WifiService) Connect(ssid, password string) error {
	s.mu.Lock()
	if s.connecting {
		s.mu.Unlock()
		return errWifiBusy
	}
	s.connecting = true
	s.mu.Unlock()
	s.notify()

	s.logger.Info(i18n.Tf("wifi.connecting", ssid))
	err := platform.WlanConnect(ssid, password)
	if err == nil {
		s.logger.Success(i18n.Tf("wifi.connected", ssid))
	} else {
		s.logger.Error(i18n.Tf("wifi.connectFailed", err.Error()))
	}
	s.mu.Lock()
	s.connecting = false
	s.mu.Unlock()
	s.notify()
	return err
}

// Disconnect 断开当前无线连接。
func (s *WifiService) Disconnect() error {
	err := platform.WlanDisconnect()
	if err != nil {
		s.logger.Error(i18n.Tf("wifi.disconnectFailed", err.Error()))
	} else {
		s.logger.Info(i18n.T("wifi.disconnected"))
	}
	s.notify()
	return err
}

// Configure 更新自动连接配置;具备条件时启动循环,否则停止。
// 循环每轮读取最新 SSID,仅切换首选网络时无需重启。
func (s *WifiService) Configure(enabled bool, ssid string) {
	should := enabled && ssid != ""
	s.mu.Lock()
	was := s.autoRunning
	s.autoSsid = ssid
	if should && !was {
		s.stopLocked()
		s.cancel = make(chan struct{})
		cancel := s.cancel
		s.autoRunning = true
		s.mu.Unlock()

		s.logger.Info(i18n.Tf("wifi.autoConnect.start", ssid))
		s.wg.Add(1)
		go func() {
			defer s.wg.Done()
			delay := 2 * time.Second
			for {
				select {
				case <-cancel:
					return
				case <-time.After(delay):
				}
				cont, next := s.autoTick(cancel)
				if !cont {
					return
				}
				delay = next
			}
		}()
		return
	}
	if !should && was {
		s.stopLocked()
		s.mu.Unlock()
		s.logger.Info(i18n.T("wifi.autoConnect.stop"))
		return
	}
	s.mu.Unlock()
}

// Stop 停止自动连接循环（agent 停机用;不等待进行中的连接）。
func (s *WifiService) Stop() {
	s.mu.Lock()
	was := s.autoRunning
	s.stopLocked()
	s.mu.Unlock()
	if was {
		s.logger.Info(i18n.T("wifi.autoConnect.stop"))
	}
}

func (s *WifiService) stopLocked() {
	if s.cancel != nil {
		close(s.cancel)
		s.cancel = nil
	}
	s.autoRunning = false
}

// autoTick 自动连接一轮;返回 (是否继续, 下次延迟)。
func (s *WifiService) autoTick(cancel chan struct{}) (bool, time.Duration) {
	s.mu.Lock()
	if !s.autoRunning {
		s.mu.Unlock()
		return false, 0
	}
	ssid := s.autoSsid
	connecting := s.connecting
	s.mu.Unlock()
	if ssid == "" || connecting {
		return true, autoConnectInterval
	}

	st, err := platform.WlanCurrent()
	if err != nil {
		// 无线不可用(无网卡/服务未跑):静默空转
		return true, autoConnectInterval
	}
	if st.Connected || st.Phase == "connecting" {
		return true, autoConnectInterval
	}

	nets, err := platform.WlanScanList()
	if err != nil {
		return true, autoConnectInterval
	}
	visible := false
	for _, n := range nets {
		if n.Ssid == ssid {
			visible = true
			break
		}
	}
	if !visible {
		return true, autoConnectInterval
	}
	s.mu.Lock()
	if s.connecting {
		s.mu.Unlock()
		return true, autoConnectInterval
	}
	s.connecting = true
	s.mu.Unlock()
	s.notify()

	psk := ""
	if s.pskFor != nil {
		psk = s.pskFor(ssid)
	}
	s.logger.Info(i18n.Tf("wifi.autoConnectTry", ssid))
	err = platform.WlanConnect(ssid, psk)
	if err == nil {
		s.logger.Success(i18n.Tf("wifi.connected", ssid))
	} else {
		s.logger.Error(i18n.Tf("wifi.connectFailed", err.Error()))
	}
	s.mu.Lock()
	s.connecting = false
	s.mu.Unlock()
	s.notify()
	return true, autoConnectInterval
}

// notify 上报状态变化(panic 兜底由调用方保证回调非 nil)。
func (s *WifiService) notify() {
	if s.onChange != nil {
		s.onChange()
	}
}

// errWifiBusy 已有连接流程进行中。
var errWifiBusy = errors.New("wifi: another connect is in progress")
