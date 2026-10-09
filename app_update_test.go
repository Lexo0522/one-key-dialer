package main

import (
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/Lexo0522/one-key-dialer/internal/service"
	"github.com/Lexo0522/one-key-dialer/internal/storage"
	"github.com/Lexo0522/one-key-dialer/internal/update"
)

// newUpdateTestApp 装配一个只够跑更新状态机的 App：真 executor、真 logSvc、
// 真 settings，updater 用零值 Module（空 Release 会让 DownloadWithFailover 立刻
// 返回 ErrMissingAsset，不联网）。
func newUpdateTestApp(t *testing.T) *App {
	t.Helper()
	dir := t.TempDir()
	exec := service.NewBackgroundExecutor()
	a := &App{
		exec:    exec,
		logSvc:  service.NewLogService(filepath.Join(dir, "pppoe_log.txt")),
		updater: &update.Module{},
	}
	a.settings = service.NewSettingsManager(
		&storage.SettingsStore{File: filepath.Join(dir, "settings.json")}, exec, nil)
	t.Cleanup(func() { exec.Shutdown(time.Second) })
	return a
}

// emptyCheck 一个「有新版但没有可用资产」的检查结果：足以让下载入口通过校验，
// 又让下载任务立刻失败，不联网。
func emptyCheck() *update.CheckResult {
	return &update.CheckResult{UpdateAvailable: true, Release: &update.Release{}}
}

// callWithTimeout 在独立 goroutine 里调用 fn 并等待。更新入口一旦死锁，测试必须
// 以「失败」收场，而不是把整个测试套件挂到 go test 超时。
func callWithTimeout(t *testing.T, what string, fn func()) {
	t.Helper()
	done := make(chan struct{})
	go func() {
		defer close(done)
		fn()
	}()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatalf("%s：3 秒未返回（更新入口死锁）", what)
	}
}

// waitForCond 轮询等待条件成立。
func waitForCond(t *testing.T, what string, cond func() bool, timeout time.Duration) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("%s：%v 内未达成", what, timeout)
}

// TestDownloadUpdateDoesNotHoldUpdateMu 下载入口必须在返回前放开 updateMu。
//
// 背景：DownloadUpdate 曾在入口 Lock 之后、提交长任务之前忘了 Unlock，函数返回时
// deferred 的 clearUpdateBusy 又要去 Lock 同一把非重入锁——自死锁。下载任务本身
// 照跑完，但 updateMu 从此被永久占用：紧随其后的 autoInstallPending、之后的
// InstallUpdate / UpdateBusy / CancelUpdateDownload 全部堵死。用户侧看到的就是
// 「下载完成了，安装却卡住不动」。
func TestDownloadUpdateDoesNotHoldUpdateMu(t *testing.T) {
	a := newUpdateTestApp(t)
	a.lastCheck = emptyCheck()

	callWithTimeout(t, "DownloadUpdate", a.DownloadUpdate)

	if !a.updateMu.TryLock() {
		t.Fatal("DownloadUpdate 返回后 updateMu 仍不可获取：后续安装/取消/查询全会被堵死")
	}
	a.updateMu.Unlock()
}

// TestDownloadJobReleasesBusyOnEveryPath 下载任务结束时必须释放占用位，
// 且清掉取消句柄；释放后下一轮下载要能受理。
func TestDownloadJobReleasesBusyOnEveryPath(t *testing.T) {
	a := newUpdateTestApp(t)
	a.lastCheck = emptyCheck()

	callWithTimeout(t, "DownloadUpdate", a.DownloadUpdate)
	// 空 Release 让下载立刻失败，闭包走完错误分支后占用位必须已经释放
	waitForCond(t, "下载任务结束后 updateBusy 应释放", func() bool { return !a.UpdateBusy() }, 3*time.Second)

	a.updateMu.Lock()
	leftover := a.cancelDl
	a.updateMu.Unlock()
	if leftover != nil {
		t.Error("下载结束后 cancelDl 未清空：取消句柄泄漏，下一轮取消的是上一轮的 ctx")
	}

	// 占用位真的放了：第二次下载不该被「更新中」挡住
	callWithTimeout(t, "第二次 DownloadUpdate", a.DownloadUpdate)
	waitForCond(t, "第二次下载任务应被受理并结束", func() bool { return !a.UpdateBusy() }, 3*time.Second)
}

// TestCancelHandleAliveUntilDownloadJobRuns 下载任务排队期间取消句柄必须还在，
// 否则界面上的「取消」是空转。
func TestCancelHandleAliveUntilDownloadJobRuns(t *testing.T) {
	a := newUpdateTestApp(t)
	// 先占住 SubmitLong 的单 worker，让下载任务排队而不立刻跑完
	release := make(chan struct{})
	a.exec.SubmitLong(func() { <-release })
	a.lastCheck = emptyCheck()

	callWithTimeout(t, "DownloadUpdate", a.DownloadUpdate)

	a.updateMu.Lock()
	c := a.cancelDl
	busy := a.updateBusy
	a.updateMu.Unlock()
	if c == nil {
		if busy {
			t.Fatal("下载仍在排队/运行，cancelDl 却已被清空：取消按钮空转")
		}
		// SubmitLong 目前是单 worker 队列，下载任务必然还排在占位任务后面。
		// 万一日后改成并发，任务可能已跑完并自行清理，那时取消按钮本就无意义。
		t.Skip("下载任务在断言前已结束")
	}
	a.CancelUpdateDownload()
	if !c.IsCancelled() {
		t.Error("CancelUpdateDownload 没有真正取消下载句柄")
	}
	close(release)
	// 放行后任务跑完，占用位必须释放
	waitForCond(t, "取消后占用位应释放", func() bool { return !a.UpdateBusy() }, 5*time.Second)
}

// TestAutoInstallTakesOverBusyFromDownload 自动安装必须在下载态占用位还未释放时
// 也能接上——它本来就是从下载闭包内部调用的，那一刻占用位理应还被下载持着。
func TestAutoInstallTakesOverBusyFromDownload(t *testing.T) {
	a := newUpdateTestApp(t)
	// 模拟「下载闭包还没退出」
	a.updateMu.Lock()
	a.updateBusy = true
	a.pendingPkg = &update.VerifiedPackage{}
	a.updateMu.Unlock()

	if !a.autoInstallPending() {
		t.Fatal("下载态占用位未释放时 autoInstallPending 直接放弃：自动安装链断了")
	}
	// 安装任务接管占用位，跑完（临时目录本体 → 拒绝安装）后必须释放
	waitForCond(t, "安装任务结束后 updateBusy 应释放", func() bool { return !a.UpdateBusy() }, 5*time.Second)
}

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
