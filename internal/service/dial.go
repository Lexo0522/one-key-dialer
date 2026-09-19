package service

import (
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/Lexo0522/one-key-dialer/internal/i18n"
	"github.com/Lexo0522/one-key-dialer/internal/model"
	"github.com/Lexo0522/one-key-dialer/internal/util"
)

// 拨号阶段（对应 onDialPhase）
const (
	PhaseDialing       = "dialing"
	PhaseDisconnecting = "disconnecting"
)

// DialPort RAS 边界（由 platform.RasModule 实现）。阻塞调用，勿在 UI 回调中直接调用。
type DialPort interface {
	ConnectionName() string
	Connect(creds *model.DialCredentials) (int, string)
	Disconnect() (int, error)
}

// DialResult 拨号结果。
type DialResult struct {
	Code   int
	Output string
}

// IsSuccess 判断拨号是否成功。
func (r DialResult) IsSuccess() bool { return r.Code == 0 }

// DialView 编排器面向 UI 的回调集合。
type DialView interface {
	Log(level Level, message string)
	Notify(title, message string)
	OnDialPhase(phase string)
	OnConnectionState(online bool)
	CaptureCredentials() *model.DialCredentials
	ValidateInput(interactive bool) bool
}

// DialEnvironment 编排器读取的环境状态与持久化钩子。
type DialEnvironment interface {
	IsOnline() bool
	ConnectTimeMillis() int64
	SessionTrafficBytes() int64
	CurrentAccountName() string
	ProbeConfig() model.ProbeConfig
	DisconnectOnNoInternet() bool
	AddHistory(operation, account, result, duration, traffic string)
	PersistAfterSuccess()
	RecordProbeOutcome(outcome model.ProbeOutcome)
}

// DialLifecycle 拨号/断开的互斥状态机。
type DialLifecycle struct {
	busy int32
}

// TryBeginDial 尝试进入拨号态。
func (l *DialLifecycle) TryBeginDial() bool { return atomic.CompareAndSwapInt32(&l.busy, 0, 1) }

// TryBeginDisconnect 尝试进入断开态。
func (l *DialLifecycle) TryBeginDisconnect() bool { return atomic.CompareAndSwapInt32(&l.busy, 0, 2) }

// End 结束当前操作。
func (l *DialLifecycle) End() { atomic.StoreInt32(&l.busy, 0) }

// IsBusy 是否正在处理连接操作。
func (l *DialLifecycle) IsBusy() bool { return atomic.LoadInt32(&l.busy) != 0 }

// DialStats 拨号计数（成功/总次数）。
type DialStats struct {
	total   int64
	success int64
}

// Total 总拨号次数。
func (s *DialStats) Total() int64 { return atomic.LoadInt64(&s.total) }

// Success 成功拨号次数。
func (s *DialStats) Success() int64 { return atomic.LoadInt64(&s.success) }

func (s *DialStats) incTotal()   { atomic.AddInt64(&s.total, 1) }
func (s *DialStats) incSuccess() { atomic.AddInt64(&s.success, 1) }

// DialOrchestrator 单线程拨号/断开队列：预检、连接生命周期、拨号后外网确认、
// 重拨编排、历史、统计与通知顺序都在这里收敛。
type DialOrchestrator struct {
	port      DialPort
	view      DialView
	env       DialEnvironment
	lifecycle *DialLifecycle
	stats     *DialStats

	jobs         chan func()
	once         sync.Once
	shuttingDown atomic.Bool
	wg           sync.WaitGroup
}

// NewDialOrchestrator 构造编排器；工作协程在首次拨号时惰性启动。
func NewDialOrchestrator(port DialPort, view DialView, env DialEnvironment,
	lifecycle *DialLifecycle, stats *DialStats) *DialOrchestrator {
	return &DialOrchestrator{
		port:      port,
		view:      view,
		env:       env,
		lifecycle: lifecycle,
		stats:     stats,
	}
}

func (o *DialOrchestrator) startWorker() {
	o.once.Do(func() {
		o.jobs = make(chan func(), 32)
		o.wg.Add(1)
		go func() {
			defer o.wg.Done()
			for job := range o.jobs {
				func() {
					defer func() {
						if r := recover(); r != nil {
							o.view.Log(LevelError, "拨号队列异常: "+toStringPanic(r))
						}
					}()
					job()
				}()
			}
		}()
	})
}

// enqueue 把拨号/断开工作排入串行队列；队列已关闭时告警。
func (o *DialOrchestrator) enqueue(work func()) {
	if o.shuttingDown.Load() {
		o.view.Log(LevelWarning, i18n.T("dial.queueClosed"))
		return
	}
	o.startWorker()
	select {
	case o.jobs <- work:
	default:
		o.view.Log(LevelWarning, i18n.T("dial.queueClosed"))
	}
}

// DialUser 用户手动拨号。
func (o *DialOrchestrator) DialUser() {
	if o.lifecycle.IsBusy() {
		o.view.Log(LevelWarning, i18n.T("dial.busy"))
		return
	}
	if !o.view.ValidateInput(true) {
		return
	}
	creds := o.view.CaptureCredentials()
	if creds == nil {
		return
	}
	o.enqueue(func() { o.runDial(creds, model.OpUserDial, true, true) })
}

// DisconnectUser 用户手动断开。
func (o *DialOrchestrator) DisconnectUser() {
	if o.lifecycle.IsBusy() {
		o.view.Log(LevelWarning, i18n.T("dial.busy"))
		return
	}
	o.view.Log(LevelInfo, i18n.T("dial.disconnecting"))
	o.enqueue(o.runDisconnectUser)
}

// DialAuto 自动/定时拨号：不阻塞调度线程，失败不重试（由重连策略决定下一次）。
func (o *DialOrchestrator) DialAuto() {
	o.enqueue(func() {
		if o.shuttingDown.Load() {
			return
		}
		if o.env.IsOnline() {
			o.view.Log(LevelInfo, i18n.T("precheck.alreadyOnline"))
			return
		}
		if !o.lifecycle.TryBeginDial() {
			return
		}
		creds := o.captureForBackground()
		if creds == nil {
			return
		}
		o.stats.incTotal()
		code, output := o.port.Connect(creds)
		o.handleDialResult(DialResult{Code: code, Output: output}, model.OpAutoDial, false)
	})
}

// DisconnectScheduled 定时断开。
func (o *DialOrchestrator) DisconnectScheduled() {
	o.enqueue(o.runDisconnectScheduled)
}

// RedialAfterDisconnect 在线切换账号：先断开，成功后用新账号重拨。
func (o *DialOrchestrator) RedialAfterDisconnect() {
	if o.lifecycle.IsBusy() {
		o.view.Log(LevelWarning, i18n.T("dial.switchBusy"))
		return
	}
	o.enqueue(func() {
		if o.shuttingDown.Load() {
			return
		}
		if !o.lifecycle.TryBeginDisconnect() {
			o.view.Log(LevelWarning, i18n.T("dial.switchBusy"))
			return
		}
		var code int
		func() {
			defer func() {
				o.lifecycle.End()
				o.view.OnDialPhase("")
			}()
			o.view.OnDialPhase(PhaseDisconnecting)
			c, err := o.port.Disconnect()
			if err != nil {
				o.view.Log(LevelError, i18n.Tf("dial.disconnectError", err.Error()))
				code = -1
				return
			}
			code = c
		}()
		if code != 0 {
			o.view.Log(LevelWarning, i18n.Tf("dial.switchCancel", code))
			return
		}
		if o.shuttingDown.Load() {
			return
		}
		o.view.OnConnectionState(false)
		o.view.Log(LevelInfo, i18n.T("dial.switchRedial"))

		if !o.lifecycle.TryBeginDial() {
			return
		}
		creds := o.captureForBackground()
		if creds == nil {
			return
		}
		defer func() {
			creds.Clear()
			o.lifecycle.End()
			o.view.OnDialPhase("")
		}()
		o.view.OnDialPhase(PhaseDialing)
		o.stats.incTotal()
		code, output := o.port.Connect(creds)
		o.handleDialResult(DialResult{Code: code, Output: output}, model.OpAutoDial, false)
	})
}

// Shutdown 停止接收拨号工作。
func (o *DialOrchestrator) Shutdown(wait time.Duration) {
	o.shuttingDown.Store(true)
	if o.jobs != nil {
		close(o.jobs)
	}
	finished := make(chan struct{})
	go func() {
		o.wg.Wait()
		close(finished)
	}()
	select {
	case <-finished:
	case <-time.After(wait):
	}
}

func (o *DialOrchestrator) captureForBackground() *model.DialCredentials {
	if !o.view.ValidateInput(false) {
		return nil
	}
	return o.view.CaptureCredentials()
}

func (o *DialOrchestrator) runDial(creds *model.DialCredentials, operation string,
	saveAfterSuccess, togglePhase bool) {
	if o.shuttingDown.Load() {
		creds.Clear()
		return
	}
	if !o.lifecycle.TryBeginDial() {
		creds.Clear()
		o.view.Log(LevelWarning, i18n.T("dial.busy"))
		return
	}
	o.stats.incTotal()
	if togglePhase {
		o.view.OnDialPhase(PhaseDialing)
	}
	defer func() {
		creds.Clear()
		o.lifecycle.End()
		if togglePhase {
			o.view.OnDialPhase("")
		}
	}()
	code, output := o.port.Connect(creds)
	if code < 0 && output == "" {
		o.view.Log(LevelError, i18n.Tf("dial.dialError", "dial failed"))
		return
	}
	o.handleDialResult(DialResult{Code: code, Output: output}, operation, saveAfterSuccess)
}

func (o *DialOrchestrator) runDisconnectUser() {
	if !o.lifecycle.TryBeginDisconnect() {
		return
	}
	defer func() {
		o.lifecycle.End()
		o.view.OnDialPhase("")
	}()
	o.view.OnDialPhase(PhaseDisconnecting)

	code, err := o.port.Disconnect()
	if err != nil {
		o.view.Log(LevelError, i18n.Tf("dial.disconnectError", err.Error()))
		return
	}
	duration := "--"
	traffic := "--"
	if conn := o.env.ConnectTimeMillis(); conn > 0 {
		sec := (time.Now().UnixMilli() - conn) / 1000
		duration = util.FormatDuration(sec)
		traffic = util.FormatBytes(o.env.SessionTrafficBytes())
	}
	o.view.OnConnectionState(false)
	result := model.OutcomeDone()
	if code == 0 {
		result = model.OutcomeSuccess()
	}
	o.env.AddHistory(model.OpUserDisconnect, o.env.CurrentAccountName(), result, duration, traffic)
	if code == 0 {
		o.view.Log(LevelSuccess, i18n.T("dial.disconnected"))
	} else {
		o.view.Log(LevelWarning, i18n.T("dial.disconnectDone"))
	}
	o.view.Notify(i18n.T("notify.disconnected.title"), i18n.T("notify.disconnected.body"))
}

func (o *DialOrchestrator) runDisconnectScheduled() {
	if o.shuttingDown.Load() {
		return
	}
	if !o.lifecycle.TryBeginDisconnect() {
		o.view.Log(LevelWarning, i18n.T("dial.scheduleSkip"))
		return
	}
	defer o.lifecycle.End()
	code, err := o.port.Disconnect()
	traffic := util.FormatBytes(o.env.SessionTrafficBytes())
	if err == nil && code == 0 {
		o.view.OnConnectionState(false)
		o.env.AddHistory(model.OpScheduleDisconnect, o.env.CurrentAccountName(),
			model.OutcomeSuccess(), "--", traffic)
		return
	}
	if err != nil {
		o.view.Log(LevelWarning, i18n.Tf("dial.scheduleError", "exec", err.Error()))
	} else {
		o.view.Log(LevelWarning, i18n.Tf("dial.scheduleFailed", code))
	}
	o.env.AddHistory(model.OpScheduleDisconnect, o.env.CurrentAccountName(),
		model.OutcomeFailure(), "--", traffic)
}

// handleDialResult 通知顺序：状态 → 计数 → 日志 → 通知 → 历史 → 持久化。
func (o *DialOrchestrator) handleDialResult(result DialResult, operation string, saveAfterSuccess bool) {
	if result.IsSuccess() {
		o.view.Log(LevelInfo, i18n.T("dial.rasConnected"))
		cfg := o.env.ProbeConfig()
		start := time.Now()
		netOk := false
		outcome := func() model.ProbeOutcome {
			defer func() {
				if r := recover(); r != nil {
					o.view.Log(LevelWarning, i18n.Tf("dial.probeError", toStringPanic(r)))
				}
			}()
			return ConfirmDetailed(cfg, "post-dial")
		}()
		if outcome.Source == "" {
			outcome = model.NewProbeOutcome(false, time.Since(start).Milliseconds(), cfg, "post-dial")
		}
		netOk = outcome.OK
		o.view.Log(LevelInfo, i18n.Tf("dial.probe", outcome.ShortLine()))
		o.env.RecordProbeOutcome(outcome)

		if netOk {
			o.view.OnConnectionState(true)
			o.stats.incSuccess()
			o.view.Log(LevelSuccess, i18n.T("dial.success"))
			o.view.Notify(i18n.T("notify.connected.title"), i18n.T("notify.connected.body"))
			o.env.AddHistory(operation, o.env.CurrentAccountName(), model.OutcomeSuccess(), "--", "--")
			if saveAfterSuccess {
				o.env.PersistAfterSuccess()
			}
			return
		}

		o.view.OnConnectionState(false)
		o.view.Log(LevelWarning, i18n.Tf("dial.rasNoInternet", model.OutcomeRasNoInternet(), outcome.ShortLine()))
		disconnected := false
		if o.env.DisconnectOnNoInternet() {
			code, err := o.port.Disconnect()
			switch {
			case err == nil && code == 0:
				disconnected = true
				o.view.Notify(i18n.T("notify.noNet.title"), i18n.T("notify.noNet.policyDone"))
			case err != nil:
				o.view.Log(LevelWarning, i18n.Tf("dial.policyDisconnectError", "exec", err.Error()))
				o.view.Notify(i18n.T("notify.noNet.title"), i18n.T("notify.noNet.policyError"))
			default:
				o.view.Log(LevelWarning, i18n.Tf("dial.policyDisconnectFailed", code))
				o.view.Notify(i18n.T("notify.noNet.title"), i18n.T("notify.noNet.policyFailed"))
			}
		} else {
			o.view.Notify(i18n.T("notify.noNet.title"), i18n.T("notify.noNet.retry"))
		}
		resultText := model.OutcomeRasNoInternet()
		if disconnected {
			resultText += i18n.T("dial.historyNoInternetSuffix")
		}
		o.env.AddHistory(operation, o.env.CurrentAccountName(), resultText, "--", "--")
		return
	}

	o.view.OnConnectionState(false)
	detail := DescribeFailure(&result)
	o.view.Log(LevelError, i18n.Tf("dial.failed", result.Code))
	o.view.Log(LevelWarning, "  "+detail)
	o.view.Notify(i18n.T("notify.failed.title"), detail)
	o.env.AddHistory(operation, o.env.CurrentAccountName(), model.FailureResult(result.Code), "--", "--")
}

// DescribeFailure 把 RAS 错误码映射为中文处理建议。
func DescribeFailure(result *DialResult) string {
	if result == nil {
		return i18n.T("ras.null")
	}
	out := result.Output
	code := result.Code
	if code == 0 {
		return i18n.T("ras.0")
	}
	for _, c := range []int{691, 619, 678, 651, 623, 632, 633, 676, 680, 720, 734, 735, 797} {
		sc := strconv.Itoa(c)
		if code == c || strings.Contains(out, sc) {
			return i18n.T("ras." + sc)
		}
	}
	if code == -1 {
		return i18n.T("ras.-1")
	}
	return i18n.Tf("ras.other", code)
}
