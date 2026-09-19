package service

import (
	"sync"
	"time"

	"github.com/Lexo0522/one-key-dialer/internal/i18n"
)

// ScheduleService 分钟对齐的定时拨号/定时断开。
type ScheduleService struct {
	scheduledDialEnabled       func() bool
	scheduledDisconnectEnabled func() bool
	dialHour                   func() int
	dialMinute                 func() int
	disconnectHour             func() int
	disconnectMinute           func() int
	isOnline                   func() bool
	isBusy                     func() bool
	onScheduledDial            func()
	onScheduledDisconnect      func()
	logger                     *LogService

	mu                       sync.Mutex
	cancel                   chan struct{}
	lastDialTriggerEpochMin  int64
	lastDisconnectTriggerMin int64
}

// NewScheduleService 构造定时任务服务。
func NewScheduleService(dialEnabled, discEnabled func() bool,
	dialHour, dialMinute, discHour, discMinute func() int,
	isOnline, isBusy func() bool,
	onDial, onDisconnect func(), logger *LogService) *ScheduleService {
	return &ScheduleService{
		scheduledDialEnabled:       dialEnabled,
		scheduledDisconnectEnabled: discEnabled,
		dialHour:                   dialHour,
		dialMinute:                 dialMinute,
		disconnectHour:             discHour,
		disconnectMinute:           discMinute,
		isOnline:                   isOnline,
		isBusy:                     isBusy,
		onScheduledDial:            onDial,
		onScheduledDisconnect:      onDisconnect,
		logger:                     logger,
		lastDialTriggerEpochMin:    -1,
		lastDisconnectTriggerMin:   -1,
	}
}

// ShouldFireDial 判断定时拨号是否应触发。
func ShouldFireDial(enabled, online, busy, minuteMatches bool, epochMinute, last int64) bool {
	return enabled && !online && !busy && minuteMatches && epochMinute != last
}

// ShouldFireDisconnect 判断定时断开是否应触发。
func ShouldFireDisconnect(enabled, online, busy, minuteMatches bool, epochMinute, last int64) bool {
	return enabled && online && !busy && minuteMatches && epochMinute != last
}

// Restart 重启定时检查（两个开关都关闭时不启动）。
func (s *ScheduleService) Restart() {
	s.mu.Lock()
	s.stopLocked()
	if !s.scheduledDialEnabled() && !s.scheduledDisconnectEnabled() {
		s.mu.Unlock()
		return
	}
	cancel := make(chan struct{})
	s.cancel = cancel
	s.mu.Unlock()

	go func() {
		ticker := time.NewTicker(time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-cancel:
				return
			case <-ticker.C:
				s.tickSafe()
			}
		}
	}()
}

// Stop 停止定时检查。
func (s *ScheduleService) Stop() {
	s.mu.Lock()
	s.stopLocked()
	s.mu.Unlock()
}

func (s *ScheduleService) stopLocked() {
	if s.cancel != nil {
		close(s.cancel)
		s.cancel = nil
	}
}

func (s *ScheduleService) tickSafe() {
	defer func() {
		if r := recover(); r != nil {
			if s.logger != nil {
				s.logger.Warning("定时任务异常: " + toStringPanic(r))
			}
		}
	}()
	now := time.Now()
	epochMinute := now.Unix() / 60
	online := s.isOnline()
	busy := s.isBusy()

	if s.scheduledDialEnabled() {
		h, m := s.dialHour(), s.dialMinute()
		matches := now.Hour() == h && now.Minute() == m
		if ShouldFireDial(true, online, busy, matches, epochMinute, s.lastDialTriggerEpochMin) {
			s.lastDialTriggerEpochMin = epochMinute
			if s.logger != nil {
				s.logger.Info(i18n.T("dial.scheduleDialTrigger"))
			}
			s.onScheduledDial()
		}
	}
	if s.scheduledDisconnectEnabled() {
		h, m := s.disconnectHour(), s.disconnectMinute()
		matches := now.Hour() == h && now.Minute() == m
		if ShouldFireDisconnect(true, online, busy, matches, epochMinute, s.lastDisconnectTriggerMin) {
			s.lastDisconnectTriggerMin = epochMinute
			if s.logger != nil {
				s.logger.Info(i18n.T("dial.scheduleDiscTrigger"))
			}
			s.onScheduledDisconnect()
		}
	}
}
