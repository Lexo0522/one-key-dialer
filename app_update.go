package main

import (
	"errors"
	"strings"

	"github.com/Lexo0522/one-key-dialer/internal/i18n"
	"github.com/Lexo0522/one-key-dialer/internal/model"
	"github.com/Lexo0522/one-key-dialer/internal/platform"
	"github.com/Lexo0522/one-key-dialer/internal/update"
)

// app_update.go：在线更新（检查 / 下载 / 安装）行为。
//
// 更新状态机字段（updateMu / updateBusy / lastCheck / pendingPkg / cancelDl）
// 仍定义在 App 结构体（app.go）中；这里只放行为。

// ============================ 在线更新 ============================

// CheckUpdate 检查更新（结果通过 app:update 事件返回）。
func (a *App) CheckUpdate(interactive bool) {
	a.exec.Submit(func() { a.doCheckUpdate(interactive) })
}

func (a *App) doCheckUpdate(interactive bool) {
	if !a.updateMu.TryLock() {
		a.emit(EvtUpdate, UpdatePayload{Kind: "error", Stage: UpdateStageCheck, Message: i18n.T("update.busy")})
		return
	}
	defer a.updateMu.Unlock()
	a.emit(EvtUpdate, UpdatePayload{Kind: "checking", Stage: UpdateStageCheck, Message: i18n.T("update.checking")})

	result := a.updater.Check(model.Version())
	a.lastCheck = &result

	writable := platform.IsDirWritable(platform.InstallDir())
	canInstall := update.HasInstallableAsset(&result, writable)
	assetName := ""
	var assetSize int64
	if result.Release != nil {
		if p := result.Release.PreferredWindowsAsset(writable); p != nil {
			assetName = p.Name
			assetSize = p.SizeBytes
		}
	}
	// 检查态由 checking/result 这一对事件收敛，两条都得发。无新版时这个
	// result 对托盘没有意义，但对界面是复位信号，不能只对有新版才发。
	payload := UpdatePayload{
		Kind:            "result",
		Stage:           UpdateStageCheck,
		Message:         result.Message,
		Body:            result.Notes,
		UpdateAvailable: result.UpdateAvailable,
		CanInstall:      canInstall,
		AssetName:       assetName,
		AssetSize:       assetSize,
		ReleaseURL:      result.ReleaseURL,
		Interactive:     interactive,
	}
	// 标题本地化为「发现新版本」：不给的话对话框会落到通用的「更新」二字上。
	// 发布者写的说明走 result.Notes（已放入 Body 字段），由对话框正文区展示。
	if result.UpdateAvailable {
		payload.Title = i18n.T("update.newVersion")
	}
	if !result.SourceOK {
		a.logSvc.Warning(result.Message)
	} else if result.UpdateAvailable {
		a.logSvc.Warning(strings.ReplaceAll(result.Message, "\n", " "))
	} else {
		a.logSvc.Success(result.Message)
	}
	// 静默检查同样要把 result 推给前端：界面的「正在检查更新…」完全由
	// checking → result 这对事件收敛，缺了后半截按钮就永远停在转圈态。
	// 无新版时该结果没有 UI 价值，frontend 对 updateAvailable=false 的
	// 非交互结果只复位状态、不弹提示（见 store.js applyUpdatePayload）。
	if !interactive {
		payload.Tray = !a.windowVisible()
	}
	a.emit(EvtUpdate, payload)
}

// DownloadUpdate 下载并校验更新包（进度通过 app:update 事件推送）。
func (a *App) DownloadUpdate() {
	a.updateMu.Lock()
	if a.updateBusy {
		a.updateMu.Unlock()
		a.emit(EvtUpdate, UpdatePayload{Kind: "error", Stage: UpdateStageDownload, Message: i18n.T("update.downloadBusy")})
		return
	}
	// 检查结果缺失时不能解引用：宁可拒绝下载也不能让 goroutine panic。
	result := a.lastCheck
	if result == nil || !result.UpdateAvailable || result.Release == nil {
		a.updateMu.Unlock()
		a.emit(EvtUpdate, UpdatePayload{Kind: "error", Stage: UpdateStageDownload, Message: i18n.T("update.noPackage")})
		return
	}
	a.updateBusy = true
	cancel := update.NewCancel()
	a.cancelDl = cancel
	// 锁必须在提交长任务之前放开。占用位已经置上、前置校验也做完了，继续持锁
	// 只会把后续所有更新入口（自动安装 / InstallUpdate / UpdateBusy /
	// CancelUpdateDownload）堵死。早先这里忘了 Unlock，函数返回时 deferred 的
	// clearUpdateBusy 又去 Lock 同一把非重入锁——自死锁，updateMu 从此被永久
	// 占用：下载任务照跑完，紧随其后的 autoInstallPending 却卡在锁上，用户侧
	// 看到的就是「下载完成了，安装却卡住不动」。
	a.updateMu.Unlock()
	progress := updateProgress{a: a, stage: UpdateStageDownload}
	a.exec.SubmitLong(func() {
		// 占用位的释放跟着下载任务本身走：闭包里每个 return 都经过这里，新增
		// 早退分支也不会漏（早先 defer 挂在函数体上，入队即释放，cancelDl 被提
		// 前清空、「取消」按钮空转，updateBusy 也不再真正互斥）。
		// 但「下载完成即自动安装」会把占用位移交给安装任务，那一刻不能再释放，
		// 否则安装途中占用位是空的，第二次安装可以插进来。
		handoff := false
		defer func() {
			if !handoff {
				a.clearUpdateBusy()
			}
		}()
		pkg, err := a.updater.DownloadWithFailover(*result, progress, cancel)
		if err != nil {
			// 用户主动取消不是失败，不能套用「下载失败」的错误文案与红色语气
			if errors.Is(err, update.ErrCancelled) {
				a.logSvc.Info(i18n.T("update.downloadCanceled"))
				a.emit(EvtUpdate, UpdatePayload{Kind: "canceled", Stage: UpdateStageDownload,
					Message: i18n.T("update.downloadCanceled")})
				return
			}
			a.logSvc.Error(i18n.Tf("update.downloadFailed", err.Error()))
			a.emit(EvtUpdate, UpdatePayload{Kind: "error", Stage: UpdateStageDownload,
				Message: i18n.Tf("update.downloadErrDlg", err.Error())})
			return
		}
		a.updateMu.Lock()
		a.pendingPkg = pkg
		a.updateMu.Unlock()
		a.logSvc.Success(i18n.Tf("update.verified", pkg.File))
		a.emit(EvtUpdate, UpdatePayload{Kind: "done", Stage: UpdateStageDownload, Path: pkg.File})
		// 自动安装：下载校验通过后直接接着装，省掉用户再点一次「立即安装」。
		// 关掉时保留原行为——对话框停在「立即安装 / 仅保留」两步确认。
		if a.settings.Current().AutoInstallUpdate {
			handoff = a.autoInstallPending()
		}
	})
}

// autoInstallPending 把「下载完成」直接接上「安装」，供自动安装链路复用。
//
// 调用方是下载闭包，那一刻占用位理应还被下载持着，所以这里既不抢占也不再校验
// updateBusy——早先那样做会在占用位未释放时直接放弃，自动安装整段失效。与
// InstallUpdate 的区别只是入口：不重复校验 pendingPkg（刚下载完必有）。
//
// 返回 true 表示占用位已由安装任务接管，调用方不能再释放。
func (a *App) autoInstallPending() bool {
	a.updateMu.Lock()
	pkg := a.pendingPkg
	a.updateMu.Unlock()
	if pkg == nil {
		return false
	}
	a.submitInstall(pkg)
	return true
}

// submitInstall 提交安装任务。占用位由调用方提前占好（下载态或安装入口各占
// 一次），任务自身退出时经 clearUpdateBusy 释放——与下载任务同一套规则，新增
// 早退分支也不会漏。新调用方务必先占位再进来。
func (a *App) submitInstall(pkg *update.VerifiedPackage) {
	progress := updateProgress{a: a, stage: UpdateStagePrepare}
	a.exec.SubmitLong(func() {
		defer a.clearUpdateBusy()
		a.prepareAndLaunch(pkg, progress)
	})
}

// prepareAndLaunch 安装主体：拒绝临时目录本体 → 生成等进程退出的更新脚本 →
// 落盘 → 启动安装 → 退出程序。自动安装与手动安装共用这一段。
func (a *App) prepareAndLaunch(pkg *update.VerifiedPackage, progress updateProgress) {
	// 本体在临时目录/构建产物里时不安装:更新脚本的 DST 来自 InstallDir,
	// 此时会把新版写进 %TEMP% 并从那里启动,安装位置就此被搬走。宁可拒绝,
	// 也不制造第二个随时会被磁盘清理掉副本。
	if platform.IsEphemeralExePath() {
		a.logSvc.Error(i18n.T("update.ephemeralInstall"))
		a.emit(EvtUpdate, UpdatePayload{Kind: "error", Stage: UpdateStagePrepare,
			Message: i18n.T("update.ephemeralInstall")})
		return
	}
	// 更新脚本需等全部相关进程退出后再覆盖 exe:代理自身 + 接入中的 UI 进程
	waitPIDs := a.uiWaitPIDs()
	prepared, err := a.updater.Prepare(pkg, progress, waitPIDs)
	if err != nil {
		a.logSvc.Error(i18n.Tf("update.prepareFailed", err.Error()))
		a.emit(EvtUpdate, UpdatePayload{Kind: "error", Stage: UpdateStagePrepare,
			Message: i18n.Tf("update.prepareFailed", err.Error())})
		return
	}
	a.quitUIProcesses()
	a.logSvc.Info(i18n.Tf("update.applying", prepared.ApplyScript))
	a.flushBeforeUpdate()
	if !a.updater.LaunchInstall(prepared) {
		a.logSvc.Error(i18n.T("update.launchFailed"))
		a.emit(EvtUpdate, UpdatePayload{Kind: "error", Stage: UpdateStagePrepare,
			Message: i18n.T("update.launchFailedDlg")})
		return
	}
	a.emit(EvtUpdate, UpdatePayload{Kind: "installing", Stage: UpdateStageInstall})
	a.ExitProgram()
}

// quitUIProcesses 广播 sys:quit，让接入中的 UI 进程立即开始退出。
//
// UI 侧对 sys:quit 一直有处理（见 app_ui.go 的 OnEvent 分支），但代理从来
// 没发过：UI 只能等代理进程死亡、管道断开后才开始收尾，而那已经晚于更新
// 脚本动手覆盖 exe 的时刻。早一步广播，UI 的 WebView2 收尾就能与代理的退出
// 重叠，脚本的等待循环才有机会等到「已经退出」而不是「正在退出」。
func (a *App) quitUIProcesses() {
	a.emit(SysEventQuit, nil)
}

// CancelUpdateDownload 取消正在进行的下载。
func (a *App) CancelUpdateDownload() {
	a.updateMu.Lock()
	c := a.cancelDl
	a.updateMu.Unlock()
	if c != nil {
		c.Cancel()
	}
}

// InstallUpdate 准备并启动安装；成功启动后退出程序。
func (a *App) InstallUpdate() {
	a.updateMu.Lock()
	// 准备/安装阶段同样要占位：否则连点两次会生成两个 staged 目录、
	// 覆盖同一个 apply_update.bat 并并行启动两个安装脚本。
	if a.updateBusy {
		a.updateMu.Unlock()
		a.emit(EvtUpdate, UpdatePayload{Kind: "error", Stage: UpdateStagePrepare, Message: i18n.T("update.installBusy")})
		return
	}
	pkg := a.pendingPkg
	if pkg == nil {
		a.updateMu.Unlock()
		a.emit(EvtUpdate, UpdatePayload{Kind: "error", Stage: UpdateStagePrepare, Message: i18n.T("update.noPackageFile")})
		return
	}
	a.updateBusy = true
	// 与 DownloadUpdate 同一条规矩：提交前放开锁，占用位交给任务自身持有。
	a.updateMu.Unlock()
	a.submitInstall(pkg)
}

// OpenReleasePage 在默认浏览器中打开发布页。
func (a *App) OpenReleasePage(url string) {
	if url == "" {
		url = model.GitHubURL + "/releases/latest"
	}
	platform.OpenInBrowser(url)
}

// ---------- 更新进度回调 ----------

// updateProgress 把阶段标识附加到每条状态/进度事件上：下载与解压走的是同一条
// update.Progress 通道，没有阶段号前端就无法分辨，只能一律当下载处理。
type updateProgress struct {
	a     *App
	stage string
}

func (p updateProgress) OnProgress(downloaded, total int64) {
	p.a.emit(EvtUpdate, UpdatePayload{Kind: "progress", Stage: p.stage, Downloaded: downloaded, Total: total})
}

func (p updateProgress) OnStatus(message string) {
	p.a.logSvc.Info(message)
	p.a.emit(EvtUpdate, UpdatePayload{Kind: "status", Stage: p.stage, Message: message})
}

// ---------- 更新占用位与落盘 ----------

func (a *App) flushBeforeUpdate() {
	a.settings.FlushPending()
	a.logSvc.Flush()
}

// updateBusyState 供前端查询更新是否进行中。
func (a *App) UpdateBusy() bool {
	a.updateMu.Lock()
	defer a.updateMu.Unlock()
	return a.updateBusy
}

// clearUpdateBusy 释放更新占用位，由下载/安装链路的 defer 调用。
// 集中在这里是为了让每条 return 路径都必然经过：分散在流程中段手工复位
// 的方式，一旦新增早退分支就会漏掉，把 updateBusy 永久留在 true。
func (a *App) clearUpdateBusy() {
	a.updateMu.Lock()
	a.updateBusy = false
	a.cancelDl = nil
	a.updateMu.Unlock()
}
