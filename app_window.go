package main

import (
	"path/filepath"
	"strconv"
	"time"

	"github.com/Lexo0522/one-key-dialer/internal/platform"
)

// app_window.go：显示窗口自愈链。
//
// ShowWindow → showStep / showVerify 按轮次推进：先对在线 UI 广播 sys:show
// 并等待回执，确认无回执后兜底拉起新 UI 进程。自愈状态字段（showMu /
// lastShowAck / lastShowReq / lastSpawnAt / showRound）仍在 App 结构体（app.go）中。

// 「显示窗口」自愈参数。整体时序:前 showBroadcastRounds 轮对在线 UI 广播
// sys:show(每轮等 showAckWait 确认回执),仍无回执说明连接是垂死 UI 的
// 僵尸连接,之后进入拉起轮(每轮等 showSpawnWait 给新 UI 等互斥体+启动
// 的时间),直到回执到达或 showMaxRounds 轮预算耗尽。回执以 DOM 就绪为准,
// UI 进程连接在先、窗口可用在后:拉起轮里接入未满 showBootGrace 的客户端
// 视为正在启动,给宽限复查而不重复拉起;接入已满宽限仍无回执的视为
// WebView2 初始化卡死,继续拉起,预算耗尽时清场终结并做最后一次兜底。
const (
	showBroadcastRounds = 3                       // 广播轮数,之后不再信任在线连接
	showMaxRounds       = 6                       // 单次请求总轮次上限,防无限循环
	showAckWait         = 800 * time.Millisecond  // 广播轮后的回执等待
	showSpawnWait       = 4 * time.Second         // 拉起轮后的回执等待(等互斥体+启动+DOM)
	showSpawnGap        = 2 * time.Second         // 两次拉起最小间隔,防拉起风暴/递归
	showBootGrace       = 8 * time.Second         // 接入客户端的启动宽限,超宽限无回执视为卡死
	showGraceWait       = 1500 * time.Millisecond // 启动宽限轮的复查间隔
)

// ShowWindow 显示主窗口（代理模式）:UI 进程在线时唤出既有窗口,并做
// 有界回执确认;无回执说明连接陈旧或窗口已死,兜底拉起新 UI 进程。
// 单实例互斥体保证兜底拉起只会 focus 既有窗口,不会开出第二个,
// 因此任何情况下「点一次显示窗口」必定有窗口浮现(或有界放弃后可重试)。
func (a *App) ShowWindow() {
	a.showMu.Lock()
	a.lastShowReq = time.Now()
	a.showRound = 0
	a.showMu.Unlock()
	a.showStep()
}

// FocusWindow 被单实例互斥体弹走的 UI 进程经 IPC 的落点:仅向在线 UI
// 广播唤窗,不拉起、不重置自愈轮次。重置轮次会让每个被弹走的进程刷新
// 重试预算,僵尸 UI 占住互斥体时演变成无限拉起风暴;拉起由发起方
// ShowWindow 的自愈链按预算驱动,这里只负责把已经活着的窗口唤到前台。
func (a *App) FocusWindow() {
	if srv := a.ipcSnapshot(); srv != nil && srv.ClientCount() > 0 {
		srv.Broadcast(SysEventShow, nil)
	}
}

// showStep 自愈链的一次推进:回执已到则收工;预算内先广播后拉起。
// 由 ShowWindow 同步发起首轮,其后由 showVerify 经 exec.Schedule 驱动。
func (a *App) showStep() {
	a.showMu.Lock()
	req := a.lastShowReq
	if !a.lastShowAck.Before(req) {
		// 回执时刻不早于请求时刻:窗口已浮现,收工
		a.showMu.Unlock()
		return
	}
	round := a.showRound
	a.showRound++
	a.showMu.Unlock()

	var wait time.Duration
	if round < showBroadcastRounds {
		if srv := a.ipcSnapshot(); srv != nil && srv.ClientCount() > 0 {
			srv.Broadcast(SysEventShow, nil)
			// 广播的 writeTo 对断链同步报错并剔除连接:若剔除后归零,
			// 说明在线列表全是死连接,不必再等回执,下轮直接拉起。
			if srv.ClientCount() == 0 {
				a.spawnUI()
				wait = showSpawnWait
			} else {
				wait = showAckWait
			}
		} else {
			a.spawnUI()
			wait = showSpawnWait
		}
	} else if round < showMaxRounds {
		srv := a.ipcSnapshot()
		stale := 0
		if srv != nil {
			stale = len(srv.StaleClientPIDs(showBootGrace))
		}
		switch {
		case srv == nil || srv.ClientCount() == 0 || stale > 0:
			// 无客户端,或接入已久仍无回执(WebView2 卡死):拉起
			a.spawnUI()
			wait = showSpawnWait
		default:
			// 有客户端但接入未满启动宽限:连接先于窗口创建,它可能
			// 正在启动,再给一次宽限复查,不重复拉起徒增进程抖动。
			wait = showGraceWait
		}
	} else {
		a.logSvc.Warning("show window: no ack after retries")
		a.killWedgedUI()
		a.spawnUI() // 清场后再兜底一次;间隔闸未过时跳过,由下次点击接管
		return
	}
	a.exec.Schedule(wait, a.showVerify)
}

// showVerify 广播/拉起/宽限后的确认推进:回执未到则继续 showStep,直到
// 回执到达或轮次预算耗尽。用户再次点击 ShowWindow 会重置轮次重新武装。
func (a *App) showVerify() {
	a.showMu.Lock()
	acked := !a.lastShowAck.Before(a.lastShowReq)
	a.showMu.Unlock()
	if acked {
		return
	}
	a.showStep()
}

// killWedgedUI 终结接入超过启动宽限仍无回执的 UI 进程。这类进程的
// WebView2 初始化已永久卡死(用户数据目录锁竞争),占着单实例互斥体,
// 后续拉起全部被弹走,只能清场。PID 来自 hello 握手,KillOwnProcess
// 内部核对进程映像路径,杜绝 PID 复用误杀。
func (a *App) killWedgedUI() {
	srv := a.ipcSnapshot()
	if srv == nil {
		return
	}
	for _, pid := range srv.StaleClientPIDs(showBootGrace) {
		if platform.KillOwnProcess(pid) {
			a.logSvc.Warning("killed wedged ui process " + strconv.Itoa(pid))
		}
	}
}

// ReportWindowShown UI 进程唤出窗口后的回执(经管道反射暴露,前端不感知)。
// 回执在 UI 侧以 DOM 就绪为前提发出,是「窗口可用」的诚实信号。
func (a *App) ReportWindowShown() {
	a.showMu.Lock()
	a.lastShowAck = time.Now()
	a.showMu.Unlock()
}

// HideWindow 代理模式无窗口可隐藏:窗口归属 UI 进程（空操作,仅为方法面完整）。
func (a *App) HideWindow() {}

// IsWindowVisible UI 进程是否在线（有窗口即视为可见）。
func (a *App) IsWindowVisible() bool { return a.windowVisible() }

// ExitProgram 有序退出（托盘「退出」与更新安装前调用）。
// 关机路径绝不允许挂死:给 shutdown 8 秒硬超时(优雅路径托盘至多 2.5 秒
// + 服务收尾 5 秒,留余量),超时(托盘/管道/RAS 任一环节卡住)也保证进程
// 一定退出;托盘图标已由 stopTray 的兜底直删先行保证删除。
func (a *App) ExitProgram() {
	go func() {
		done := make(chan struct{})
		go func() {
			defer close(done)
			defer func() { _ = recover() }()
			a.shutdown(a.ctx)
		}()
		select {
		case <-done:
		case <-time.After(8 * time.Second):
		}
		osExit(0)
	}()
}

// uiOnline 是否有 UI 进程接入。
func (a *App) uiOnline() bool {
	srv := a.ipcSnapshot()
	return srv != nil && srv.ClientCount() > 0
}

// uiWaitPIDs 更新脚本需要等待退出的全部进程:代理自身 + 接入中的 UI 进程。
func (a *App) uiWaitPIDs() []int {
	srv := a.ipcSnapshot()
	if srv == nil {
		return nil
	}
	return srv.ClientPIDs()
}

// spawnUI 按需拉起 UI 进程（同目录同一 exe,无参数即 UI 模式）。
// 带最小间隔闸:被拉起的进程若被单实例互斥体弹走,会经 FocusWindow
// 回到代理;没有间隔闸时「拉起→弹走→再拉起」会递归成拉起风暴
// (僵尸 UI 占住互斥体的场景下每秒可弹起数十个进程)。
func (a *App) spawnUI() {
	a.showMu.Lock()
	if time.Since(a.lastSpawnAt) < showSpawnGap {
		a.showMu.Unlock()
		return
	}
	a.lastSpawnAt = time.Now()
	a.showMu.Unlock()
	exe, err := currentExe()
	if err != nil {
		a.logSvc.Error("spawn ui: " + err.Error())
		return
	}
	a.logSvc.Info("launch ui: " + exe)
	// 必须走 LaunchGUI:LaunchDetached 的 CREATE_NO_WINDOW 会让
	// UI 进程的主窗口保持隐藏,窗口永远显示不出来。
	if err := platform.LaunchGUI([]string{exe}, filepath.Dir(exe)); err != nil {
		a.logSvc.Error("spawn ui: " + err.Error())
	}
}
