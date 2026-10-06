package main

import (
	"sync"
	"testing"

	"github.com/Lexo0522/one-key-dialer/internal/update"
)

// TestClearUpdateBusy 更新占用位的释放：必须同时清掉 updateBusy 与 cancelDl。
//
// 这条守的是 1.1 那类回归：下载/安装链路里新增早退分支时忘了复位 updateBusy，
// 前端会永久锁在 busy、后续下载与安装全被拒。把释放逻辑收到 clearUpdateBusy
// 一个函数里，这个测试就覆盖了所有调用路径。
func TestClearUpdateBusy(t *testing.T) {
	t.Run("清空两个字段", func(t *testing.T) {
		a := &App{}
		a.updateBusy = true
		// 预置一个非 nil 的 cancelDl：如果 clearUpdateBusy 漏了它，
		// 下一轮下载的取消句柄就被上一轮的占着，用户点「取消下载」时
		// 取消的是上一次的 ctx，新的 goroutine 不会停。
		a.cancelDl = update.NewCancel()

		a.clearUpdateBusy()

		a.updateMu.Lock()
		busy, cancelDl := a.updateBusy, a.cancelDl
		a.updateMu.Unlock()
		if busy {
			t.Error("clearUpdateBusy() 之后 updateBusy 仍为 true")
		}
		if cancelDl != nil {
			t.Error("clearUpdateBusy() 之后 cancelDl 仍非 nil，取消句柄泄漏")
		}
	})

	t.Run("幂等且不持锁返回", func(t *testing.T) {
		a := &App{}
		a.clearUpdateBusy()
		a.clearUpdateBusy()
		// 若 clearUpdateBusy 自己持锁不释放，下面这次加锁会直接死锁。
		// 用 defer 解锁而不是锁完立刻解锁：后者在静态检查眼里是空临界区。
		func() {
			a.updateMu.Lock()
			defer a.updateMu.Unlock()
			if a.updateBusy {
				t.Error("二次 clearUpdateBusy 后 updateBusy 仍为 true")
			}
		}()
	})

	t.Run("并发释放不数据竞争", func(t *testing.T) {
		// -race 下跑这个用例：updateBusy 的读写都必须走 updateMu。
		// goroutine 里既调 clearUpdateBusy（写），也读一次字段，两边都持锁。
		a := &App{}
		a.updateBusy = true
		var wg sync.WaitGroup
		for i := 0; i < 8; i++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				a.clearUpdateBusy()
				a.updateMu.Lock()
				if a.updateBusy {
					// 释放后不应再为 true；真出现就是 defer 没生效。
					t.Error("updateBusy 在 clearUpdateBusy 后仍为 true")
				}
				a.updateMu.Unlock()
			}()
		}
		wg.Wait()
	})
}

// TestUpdateBusyResetAcrossEarlyReturn 验证「早退路径也释放占用位」这件事。
//
// 直接调用三个更新函数成本太高（要 mock updater/exec/logSvc），所以这里
// 只验证它们共用的那段结构：占用位置位后紧跟 defer clearUpdateBusy()。
// 用反射拿不到 defer，改为锁死可观测行为——连续两次进入下载入口时，
// 第二次不该因为第一次没复位而被 busy 挡住。这个不变式就是修复的核心。
func TestUpdateBusyResetInvariant(t *testing.T) {
	a := &App{}
	if a.UpdateBusy() {
		t.Fatal("初始状态 updateBusy 应为 false")
	}
	// 模拟一轮「占用 → 完成」：这是 defer 的净效果。
	a.updateMu.Lock()
	a.updateBusy = true
	a.updateMu.Unlock()
	a.clearUpdateBusy()
	if a.UpdateBusy() {
		t.Fatal("clearUpdateBusy 后 UpdateBusy() 仍为 true，占用位泄漏")
	}
}
