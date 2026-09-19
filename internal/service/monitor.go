package service

import (
	"sync"
	"time"
)

// SpeedSample 一次速率采样。
type SpeedSample struct {
	DownBytesPerSec int64
	UpBytesPerSec   int64
	DownDelta       int64
	UpDelta         int64
}

// NetworkMonitorService 流量 / 在线时长 / 托盘提示的周期采样。
type NetworkMonitorService struct {
	isOnline         func() bool
	trafficSupplier  func() (int64, int64)
	connectTime      func() int64
	onSpeedSample    func(SpeedSample)
	onSpeedUnavail   func()
	onTooltipRefresh func()
	onUptimeTick     func(seconds int64)

	mu     sync.Mutex
	cancel chan struct{}
}

// NewNetworkMonitorService 构造网络监控服务。
func NewNetworkMonitorService(isOnline func() bool, trafficSupplier func() (int64, int64),
	connectTime func() int64, onSpeedSample func(SpeedSample), onSpeedUnavail func(),
	onTooltipRefresh func(), onUptimeTick func(int64)) *NetworkMonitorService {
	return &NetworkMonitorService{
		isOnline:         isOnline,
		trafficSupplier:  trafficSupplier,
		connectTime:      connectTime,
		onSpeedSample:    onSpeedSample,
		onSpeedUnavail:   onSpeedUnavail,
		onTooltipRefresh: onTooltipRefresh,
		onUptimeTick:     onUptimeTick,
	}
}

// Start 启动 1 秒 tick。
func (m *NetworkMonitorService) Start() {
	m.mu.Lock()
	if m.cancel != nil {
		m.mu.Unlock()
		return
	}
	cancel := make(chan struct{})
	m.cancel = cancel
	m.mu.Unlock()

	go func() {
		ticker := time.NewTicker(time.Second)
		defer ticker.Stop()
		var (
			lastReceived, lastSent int64
			lastSampleTick         int64
			firstSample            = true
			lastOnline             = false
			failCount              int
			tick                   int64
		)
		for {
			select {
			case <-cancel:
				return
			case <-ticker.C:
			}
			func() {
				defer func() {
					if r := recover(); r != nil {
						if m.onSpeedUnavail != nil {
							m.onSpeedUnavail()
						}
					}
				}()
				online := m.isOnline()
				if online && !lastOnline {
					firstSample = true
					failCount = 0
				}
				lastOnline = online

				// 离线时降低采样频率，减少 netstat 与前端刷新开销
				interval := int64(3)
				if !online {
					interval = 30
				}
				if tick%interval == 0 {
					recv, sent := m.trafficSupplier()
					if recv > 0 || sent > 0 {
						if firstSample || recv < lastReceived || sent < lastSent {
							lastReceived, lastSent = recv, sent
							lastSampleTick = tick
							firstSample = false
						} else {
							elapsed := tick - lastSampleTick
							if elapsed < 1 {
								elapsed = 1
							}
							dl := recv - lastReceived
							ul := sent - lastSent
							if m.onSpeedSample != nil {
								m.onSpeedSample(SpeedSample{
									DownBytesPerSec: dl / elapsed,
									UpBytesPerSec:   ul / elapsed,
									DownDelta:       dl,
									UpDelta:         ul,
								})
							}
							lastReceived, lastSent = recv, sent
							lastSampleTick = tick
						}
						failCount = 0
					} else {
						failCount++
						if failCount > 3 && online && m.onSpeedUnavail != nil {
							m.onSpeedUnavail()
						}
					}
				}

				if tick%3 == 0 && m.onTooltipRefresh != nil {
					m.onTooltipRefresh()
				}
				conn := m.connectTime()
				if m.onUptimeTick != nil {
					if online && conn > 0 {
						m.onUptimeTick((time.Now().UnixMilli() - conn) / 1000)
					} else {
						m.onUptimeTick(0)
					}
				}
				tick++
			}()
		}
	}()
}

// Stop 停止采样。
func (m *NetworkMonitorService) Stop() {
	m.mu.Lock()
	if m.cancel != nil {
		close(m.cancel)
		m.cancel = nil
	}
	m.mu.Unlock()
}
