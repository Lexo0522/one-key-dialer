package service

import (
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/Lexo0522/one-key-dialer/internal/i18n"
	"github.com/Lexo0522/one-key-dialer/internal/model"
)

// 拨号阶段（对应 onDialPhase）
const (
	PhaseDialing       = "dialing"
	PhaseDisconnecting = "disconnecting"
)

// 通知语气（透传给前端灵动岛 Toast 的 tone 字段）
const (
	ToneInfo    = "info"
	ToneSuccess = "success"
	ToneWarning = "warning"
	ToneError   = "error"
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
	Notify(title, message, tone string)
	OnDialPhase(phase string)
	OnConnectionState(online bool)
	// OnDialFinished 一次拨号尝试的最终结果（成功 / RAS 通但无外网 / 拨号失败），
	// 供界面做结构化回显，不必到日志里翻明细。
	OnDialFinished(ok bool, code int, detail string)
	CaptureCredentials() *model.DialCredentials
	ValidateInput(interactive bool) bool
}

// DialEnvironment 编排器读取的环境状态与持久化钩子。
type DialEnvironment interface {
	IsOnline() bool
	ConnectTimeMillis() int64
	SessionTrafficBytes() int64
	ProbeConfig() model.ProbeConfig
	DisconnectOnNoInternet() bool
	PersistAfterSuccess()
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

// DialOrchestrator 单线程拨号/断开队列：预检、连接生命周期、拨号后外网确认、
// 重拨编排与通知顺序都在这里收敛。
type DialOrchestrator struct {
	port      DialPort
	view      DialView
	env       DialEnvironment
	lifecycle *DialLifecycle

	jobs         chan func()
	once         sync.Once
	shuttingDown atomic.Bool
	wg           sync.WaitGroup
}

// NewDialOrchestrator 构造编排器；工作协程在首次拨号时惰性启动。
func NewDialOrchestrator(port DialPort, view DialView, env DialEnvironment,
	lifecycle *DialLifecycle) *DialOrchestrator {
	return &DialOrchestrator{
		port:      port,
		view:      view,
		env:       env,
		lifecycle: lifecycle,
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

// DialUser 用户手动拨号。返回 false 表示未受理（忙/预检失败/凭据缺失），
// 前端据此刻复位按钮的 busy 状态——此路径不会发出任何拨号阶段事件。
func (o *DialOrchestrator) DialUser() bool {
	if o.lifecycle.IsBusy() {
		o.view.Log(LevelWarning, i18n.T("dial.busy"))
		return false
	}
	if !o.view.ValidateInput(true) {
		return false
	}
	creds := o.view.CaptureCredentials()
	if creds == nil {
		return false
	}
	o.enqueue(func() { o.runDial(creds, true, true) })
	return true
}

// DisconnectUser 用户手动断开。返回 false 表示未受理（忙）。
func (o *DialOrchestrator) DisconnectUser() bool {
	if o.lifecycle.IsBusy() {
		o.view.Log(LevelWarning, i18n.T("dial.busy"))
		return false
	}
	o.view.Log(LevelInfo, i18n.T("dial.disconnecting"))
	o.enqueue(o.runDisconnectUser)
	return true
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
		code, output := o.port.Connect(creds)
		o.handleDialResult(DialResult{Code: code, Output: output}, false)
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

func (o *DialOrchestrator) runDial(creds *model.DialCredentials, saveAfterSuccess, togglePhase bool) {
	if o.shuttingDown.Load() {
		creds.Clear()
		return
	}
	if !o.lifecycle.TryBeginDial() {
		creds.Clear()
		o.view.Log(LevelWarning, i18n.T("dial.busy"))
		// 竞态兜底：前端已进入 busy 态但拨号被拒，补发复位事件
		o.view.OnDialPhase("")
		return
	}
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
		o.view.OnDialFinished(false, code, i18n.Tf("dial.dialError", "dial failed"))
		return
	}
	o.handleDialResult(DialResult{Code: code, Output: output}, saveAfterSuccess)
}

func (o *DialOrchestrator) runDisconnectUser() {
	if !o.lifecycle.TryBeginDisconnect() {
		// 竞态兜底：前端已进入 busy 态但断开被拒，补发复位事件
		o.view.OnDialPhase("")
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
	o.view.OnConnectionState(false)
	if code == 0 {
		o.view.Log(LevelSuccess, i18n.T("dial.disconnected"))
	} else {
		o.view.Log(LevelWarning, i18n.T("dial.disconnectDone"))
	}
	o.view.Notify(i18n.T("notify.disconnected.title"), i18n.T("notify.disconnected.body"), ToneInfo)
}

// handleDialResult 通知顺序：状态 → 日志 → 通知 → 持久化。
func (o *DialOrchestrator) handleDialResult(result DialResult, saveAfterSuccess bool) {
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

		if netOk {
			o.view.OnConnectionState(true)
			o.view.Log(LevelSuccess, i18n.T("dial.success"))
			o.view.Notify(i18n.T("notify.connected.title"), i18n.T("notify.connected.body"), ToneSuccess)
			o.view.OnDialFinished(true, 0, i18n.Tf("dial.probe", outcome.ShortLine()))
			if saveAfterSuccess {
				o.env.PersistAfterSuccess()
			}
			return
		}

		o.view.OnConnectionState(false)
		noNet := i18n.Tf("dial.rasNoInternet", model.OutcomeRasNoInternet(), outcome.ShortLine())
		o.view.Log(LevelWarning, noNet)
		o.view.OnDialFinished(false, 0, noNet)
		if o.env.DisconnectOnNoInternet() {
			code, err := o.port.Disconnect()
			switch {
			case err == nil && code == 0:
				o.view.Notify(i18n.T("notify.noNet.title"), i18n.T("notify.noNet.policyDone"), ToneWarning)
			case err != nil:
				o.view.Log(LevelWarning, i18n.Tf("dial.policyDisconnectError", "exec", err.Error()))
				o.view.Notify(i18n.T("notify.noNet.title"), i18n.T("notify.noNet.policyError"), ToneWarning)
			default:
				o.view.Log(LevelWarning, i18n.Tf("dial.policyDisconnectFailed", code))
				o.view.Notify(i18n.T("notify.noNet.title"), i18n.T("notify.noNet.policyFailed"), ToneWarning)
			}
		} else {
			o.view.Notify(i18n.T("notify.noNet.title"), i18n.T("notify.noNet.retry"), ToneWarning)
		}
		return
	}

	o.view.OnConnectionState(false)
	detail := DescribeFailure(&result)
	o.view.Log(LevelError, i18n.Tf("dial.failed", result.Code))
	o.view.Log(LevelWarning, "  "+detail)
	o.view.Notify(i18n.T("notify.failed.title"), detail, ToneError)
	o.view.OnDialFinished(false, result.Code, detail)
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
