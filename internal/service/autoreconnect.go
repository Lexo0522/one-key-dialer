package service

import (
	"sync"
	"time"

	"github.com/Lexo0522/one-key-dialer/internal/i18n"
)

// AutoReconnectService 周期性连通性监控 + 断网自动拨号。
type AutoReconnectService struct {
	isBusy             func() bool
	checkNetworkStatus func() bool
	performDial        func()
	onNetworkRecovered func()
	onNetworkLost      func()
	logger             *LogService

	mu               sync.Mutex
	cancel           chan struct{}
	wg               sync.WaitGroup
	enabled          bool
	intervalSeconds  int
	needImmediate    bool
	dialPending      bool
	failedDialStreak int
	lastOnline       bool
	hasSample        bool
}

// NewAutoReconnectService 构造自动重连服务。
func NewAutoReconnectService(isBusy func() bool, checkNetworkStatus func() bool,
	performDial func(), onNetworkRecovered, onNetworkLost func(), logger *LogService) *AutoReconnectService {
	return &AutoReconnectService{
		isBusy:             isBusy,
		checkNetworkStatus: checkNetworkStatus,
		performDial:        performDial,
		onNetworkRecovered: onNetworkRecovered,
		onNetworkLost:      onNetworkLost,
		logger:             logger,
		intervalSeconds:    30,
	}
}

// ClampIntervalSeconds 钳制间隔，最小 5 秒。
func ClampIntervalSeconds(v int) int {
	if v < 5 {
		return 5
	}
	return v
}

// ShouldAttemptReconnectDial 判断是否应发起重连拨号。
func ShouldAttemptReconnectDial(online, busy bool) bool { return !online && !busy }

// RetryDelaySeconds 拨号后等待：首次 5 秒，按连续失败次数翻倍，
// 上限为基准间隔的 10 倍——避免错误密码高频冲击认证服务器。
func RetryDelaySeconds(failedStreak, baseIntervalSeconds int) int64 {
	base := int64(baseIntervalSeconds)
	if base < 1 {
		base = 1
	}
	cap := 10 * base
	shift := failedStreak
	if shift < 0 {
		shift = 0
	}
	if shift > 10 {
		shift = 10
	}
	delay := int64(5) << shift
	if delay > cap {
		delay = cap
	}
	if delay < 5 {
		delay = 5
	}
	return delay
}

// IsRunning 是否正在运行。
func (s *AutoReconnectService) IsRunning() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.enabled
}

// Start 启动监控。
func (s *AutoReconnectService) Start(intervalSeconds int, dialImmediately bool) {
	s.mu.Lock()
	if s.enabled {
		s.mu.Unlock()
		return
	}
	s.stopLocked()
	safe := ClampIntervalSeconds(intervalSeconds)
	s.intervalSeconds = safe
	s.needImmediate = dialImmediately
	s.dialPending = false
	s.failedDialStreak = 0
	s.hasSample = false
	s.lastOnline = false
	s.enabled = true
	s.cancel = make(chan struct{})
	cancel := s.cancel
	s.mu.Unlock()

	s.logger.Info(i18n.Tf("reconnect.start", safe))
	s.wg.Add(1)
	go func() {
		defer s.wg.Done()
		firstDelay := 500 * time.Millisecond
		if dialImmediately {
			firstDelay = 0
		}
		delay := firstDelay
		for {
			select {
			case <-cancel:
				return
			case <-time.After(delay):
			}
			ok, next := s.tick(cancel)
			if !ok {
				return
			}
			delay = next
		}
	}()
}

// Stop 停止监控（不阻塞）。
func (s *AutoReconnectService) Stop() {
	s.mu.Lock()
	s.stopLocked()
	s.mu.Unlock()
	s.logger.Warning(i18n.T("reconnect.stop"))
}

func (s *AutoReconnectService) stopLocked() {
	if s.cancel != nil {
		close(s.cancel)
		s.cancel = nil
	}
	s.enabled = false
}

// tick 执行一次检查；返回 (是否继续, 下次延迟)。
func (s *AutoReconnectService) tick(cancel chan struct{}) (bool, time.Duration) {
	s.mu.Lock()
	if !s.enabled {
		s.mu.Unlock()
		return false, 0
	}
	nextDelaySec := int64(s.intervalSeconds)
	s.mu.Unlock()

	done := func(cont bool) (bool, time.Duration) {
		return cont, time.Duration(nextDelaySec) * time.Second
	}

	online := s.checkNetworkStatus()
	busy := s.isBusy()

	s.mu.Lock()
	// 先结算上一次拨号的结果
	if s.dialPending {
		s.dialPending = false
		if online {
			s.failedDialStreak = 0
		} else {
			s.failedDialStreak++
			streak := s.failedDialStreak
			s.mu.Unlock()
			s.logger.Warning(i18n.Tf("reconnect.streak", streak))
			s.mu.Lock()
		}
	}

	if s.hasSample {
		if online && !s.lastOnline {
			s.failedDialStreak = 0
			s.mu.Unlock()
			if s.onNetworkRecovered != nil {
				s.onNetworkRecovered()
			}
			s.mu.Lock()
		} else if !online && s.lastOnline && !busy {
			s.mu.Unlock()
			if s.onNetworkLost != nil {
				s.onNetworkLost()
			}
			s.mu.Lock()
		}
	}
	s.lastOnline = online
	s.hasSample = true

	if ShouldAttemptReconnectDial(online, busy) {
		immediate := s.needImmediate
		s.needImmediate = false
		streak := s.failedDialStreak
		base := s.intervalSeconds
		s.mu.Unlock()
		if immediate {
			s.logger.Info(i18n.T("reconnect.immediate"))
		} else if streak == 0 {
			s.logger.Warning(i18n.T("reconnect.detected"))
		}
		s.performDial()
		nextDelaySec = RetryDelaySeconds(streak, base)
		s.mu.Lock()
		s.dialPending = true
		s.mu.Unlock()
		return done(true)
	}

	s.needImmediate = false
	if online {
		s.failedDialStreak = 0
	}
	s.mu.Unlock()
	return done(true)
}
