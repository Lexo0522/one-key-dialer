package service

import (
	"errors"
	"fmt"
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

	mu            sync.Mutex
	scanCache     []platform.WlanNetwork
	scanAt        time.Time
	connecting    bool
	disconnecting bool
	autoSsid      string
	cancel        chan struct{}
	wg            sync.WaitGroup
	autoRunning   bool
	// watchGen 过渡态兜底轮询的代次:新动作受理时递增,旧轮询据此让位
	watchGen int
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
// 注意返回的 Phase 描述的是 OS 接口的**瞬时阶段**，用于展示；它不代表
// 本应用正在进行连接/断开流程——存在网卡正重连（AP 漫游/信号抖动）而用户
// 什么都没做的情况。按钮可用性由 IsBusy() 判定，语义完全不同。
func (s *WifiService) Status() (platform.WlanState, error) {
	return platform.WlanCurrent()
}

// IsBusy 本应用是否正在进行连接/断开流程。
// 这是按钮可用性的唯一依据：只有用户（或自动连接）在本应用内发起的动作
// 才应该锁定界面。OS 侧自发处于 associating/authenticating（网卡重连、
// 漫游）时界面必须保持可操作，否则用户点不了任何按钮。
func (s *WifiService) IsBusy() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.connecting || s.disconnecting
}

// Connect 连接指定网络(阻塞,最长约 20 秒,调用方负责放后台)。
// 密码为空且已有配置时沿用既有配置。
func (s *WifiService) Connect(ssid, password string) error {
	s.mu.Lock()
	if s.connecting || s.disconnecting {
		s.mu.Unlock()
		return errWifiBusy
	}
	s.connecting = true
	s.mu.Unlock()
	// 受理即上报：此刻 OS 接口可能还没进入连接态，靠 busy 置位让前端
	// 立刻显示「连接中」并锁定按钮，而不是等 OS 状态翻转
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
	// OS 离开过渡态可能晚于本函数收尾,兜底轮询防止前端把「连接中」钉死
	s.watchTransition()
	return err
}

// Disconnect 断开当前无线连接。
func (s *WifiService) Disconnect() error {
	s.mu.Lock()
	if s.disconnecting {
		s.mu.Unlock()
		return nil
	}
	s.disconnecting = true
	s.mu.Unlock()
	s.notify()

	err := platform.WlanDisconnect()
	if err != nil {
		s.logger.Error(i18n.Tf("wifi.disconnectFailed", err.Error()))
	} else {
		s.logger.Info(i18n.T("wifi.disconnected"))
	}
	s.mu.Lock()
	s.disconnecting = false
	s.mu.Unlock()
	s.notify()
	s.watchTransition()
	return err
}

// watchTransition 收尾后的短暂状态跟进：OS 接口可能滞后数十秒才离开
// associating/authenticating/disconnecting（AP 慢速拒绝、驱动迟滞）。
// 按钮可用性已由 busy 表达，不受其影响；此轮仅让界面显示的 SSID/信号/
// 阶段追上真实稳态。状态稳定或超时后收工；期间有新动作受理则让位。
func (s *WifiService) watchTransition() {
	seed, err := s.Status()
	last := ""
	if err == nil {
		last = fmt.Sprintf("%s|%s|%d|%v", seed.Phase, seed.Ssid, seed.SignalQuality, seed.Connected)
	}
	s.mu.Lock()
	s.watchGen++
	gen := s.watchGen
	s.mu.Unlock()
	go func() {
		deadline := time.Now().Add(45 * time.Second)
		for {
			time.Sleep(500 * time.Millisecond)
			s.mu.Lock()
			active := s.connecting || s.disconnecting
			takenOver := s.watchGen != gen
			s.mu.Unlock()
			if takenOver {
				return
			}
			if active {
				// 主流程仍在跑:它收尾时会自行上报并再次进入跟进
				continue
			}
			st, err := s.Status()
			if err != nil {
				if time.Now().After(deadline) {
					return
				}
				continue
			}
			key := fmt.Sprintf("%s|%s|%d|%v", st.Phase, st.Ssid, st.SignalQuality, st.Connected)
			if key != last {
				last = key
				s.notify()
			}
			if st.Phase != "connecting" && st.Phase != "disconnecting" {
				return
			}
			if time.Now().After(deadline) {
				return
			}
		}
	}()
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
			// NewTimer + Reset 而不是每次 time.After：后者的 timer 要等
			// 下一次触发才被回收，循环里反复新建等于每轮漏一个。Stop() 后
			// 这里立刻退出，不会留下仍在跑的 WlanConnect。
			delay := 2 * time.Second
			timer := time.NewTimer(delay)
			defer timer.Stop()
			for {
				select {
				case <-cancel:
					return
				case <-timer.C:
				}
				cont, next := s.autoTick(cancel)
				if !cont {
					return
				}
				delay = next
				timer.Reset(delay)
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
	// 置位前再确认自动连接没被停掉。WlanCurrent/WlanScanList 各要跑一两秒,
	// 这期间用户可能已在设置里关掉自动连接(或调了 Stop);此时若照旧进入
	// WlanConnect,会占着 connecting 挡住用户自己的手动连接,最坏白等 20 秒。
	select {
	case <-cancel:
		return false, 0
	default:
	}
	s.mu.Lock()
	busy := s.connecting || s.disconnecting
	s.mu.Unlock()
	if busy {
		return true, autoConnectInterval
	}

	psk := ""
	if s.pskFor != nil {
		psk = s.pskFor(ssid)
	}
	s.logger.Info(i18n.Tf("wifi.autoConnectTry", ssid))
	// 复用 Connect:统一的受理上报、结果日志与过渡态兜底;
	// 竞态下被手动连接抢先时返回 busy,静默跳过等下一轮
	_ = s.Connect(ssid, psk)
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
