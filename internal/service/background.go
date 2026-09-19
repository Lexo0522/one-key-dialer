// Package service 承载应用业务逻辑：拨号编排、自动重连、定时任务、
// 网络监控、历史/统计、设置与账号、开机自启、诊断。
package service

import (
	"context"
	"sync"
	"time"
)

// Task 是一个已排期的后台任务句柄。
type Task struct {
	id     int
	cancel context.CancelFunc
}

// Cancel 取消任务（不等待）。
func (t *Task) Cancel() {
	if t != nil && t.cancel != nil {
		t.cancel()
	}
}

// BackgroundExecutor 共享后台调度器：周期性 tick 用 goroutine + ticker，
// 长诊断任务走独立的单 worker 队列，避免 60s 的 ping/tracert 饿死 1s 的监控 tick。
type BackgroundExecutor struct {
	root    context.Context
	cancel  context.CancelFunc
	wg      sync.WaitGroup
	mu      sync.Mutex
	nextID  int
	longCh  chan func()
	longOn  sync.Once
	errSink func(error)
	done    bool
}

// NewBackgroundExecutor 构造调度器。
func NewBackgroundExecutor() *BackgroundExecutor {
	ctx, cancel := context.WithCancel(context.Background())
	return &BackgroundExecutor{root: ctx, cancel: cancel}
}

// SetErrorReporter 安装意外任务失败的上报钩子（绝不静默吞掉）。
func (b *BackgroundExecutor) SetErrorReporter(reporter func(error)) {
	b.errSink = reporter
}

func (b *BackgroundExecutor) nextTask() (*Task, context.Context) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.nextID++
	ctx, cancel := context.WithCancel(b.root)
	return &Task{id: b.nextID, cancel: cancel}, ctx
}

// Schedule 延迟一次性执行。
func (b *BackgroundExecutor) Schedule(delay time.Duration, fn func()) *Task {
	t, ctx := b.nextTask()
	b.wg.Add(1)
	go func() {
		defer b.wg.Done()
		defer t.cancel()
		timer := time.NewTimer(delay)
		defer timer.Stop()
		select {
		case <-ctx.Done():
			return
		case <-timer.C:
			b.runGuarded(fn)
		}
	}()
	return t
}

// ScheduleAtFixedRate 固定频率周期执行（initial 后每 period 一次）。
func (b *BackgroundExecutor) ScheduleAtFixedRate(initial, period time.Duration, fn func()) *Task {
	t, ctx := b.nextTask()
	b.wg.Add(1)
	go func() {
		defer b.wg.Done()
		defer t.cancel()
		timer := time.NewTimer(initial)
		defer timer.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-timer.C:
				b.runGuarded(fn)
				timer.Reset(period)
			}
		}
	}()
	return t
}

// Submit 提交短任务。
func (b *BackgroundExecutor) Submit(fn func()) {
	b.wg.Add(1)
	go func() {
		defer b.wg.Done()
		b.runGuarded(fn)
	}()
}

// SubmitLong 提交长任务（诊断）：独立单 worker 队列。
func (b *BackgroundExecutor) SubmitLong(fn func()) {
	b.longOn.Do(func() {
		b.longCh = make(chan func(), 64)
		go func() {
			for job := range b.longCh {
				func() {
					defer func() {
						if r := recover(); r != nil {
							b.report(errFromRecover(r))
						}
					}()
					job()
				}()
			}
		}()
	})
	b.mu.Lock()
	if b.done {
		b.mu.Unlock()
		return
	}
	b.mu.Unlock()
	select {
	case b.longCh <- fn:
	default:
		// 队列满时退化为直接起 goroutine，保证不丢任务
		b.Submit(fn)
	}
}

func (b *BackgroundExecutor) runGuarded(fn func()) {
	defer func() {
		if r := recover(); r != nil {
			b.report(errFromRecover(r))
		}
	}()
	fn()
}

func (b *BackgroundExecutor) report(err error) {
	if b.errSink != nil && err != nil {
		func() {
			defer func() { _ = recover() }()
			b.errSink(err)
		}()
	}
}

// Shutdown 停止接收新任务并等待既有任务收尾（最多 wait）。
func (b *BackgroundExecutor) Shutdown(wait time.Duration) {
	b.mu.Lock()
	b.done = true
	b.mu.Unlock()
	b.cancel()
	finished := make(chan struct{})
	go func() {
		b.wg.Wait()
		close(finished)
	}()
	select {
	case <-finished:
	case <-time.After(wait):
	}
	if b.longCh != nil {
		// 不关闭 channel：避免向已关闭通道发送 panic，交给进程退出回收
	}
}

func errFromRecover(r any) error {
	if e, ok := r.(error); ok {
		return e
	}
	return &panicError{v: r}
}

type panicError struct{ v any }

func (e *panicError) Error() string { return "panic: " + toStringPanic(e.v) }

func toStringPanic(v any) string {
	if s, ok := v.(string); ok {
		return s
	}
	return "unknown"
}
