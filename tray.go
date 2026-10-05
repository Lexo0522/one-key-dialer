package main

import (
	_ "embed"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"fyne.io/systray"
	"github.com/Lexo0522/one-key-dialer/internal/i18n"
	"github.com/Lexo0522/one-key-dialer/internal/platform"
	"github.com/Lexo0522/one-key-dialer/internal/util"
)

//go:embed build/windows/icon.ico
var trayIcon []byte

// fyne.io/systray v1.12.2(getlantern/systray 的维护 fork,模块规范路径
// fyne.io/systray)内部创建托盘窗口时使用的类名与 Shell_NotifyIcon 的
// uID(见其 systray_windows.go 的 initInstance)。stopTray 的兜底直删
// 依赖这两个值定位图标;升级库版本时必须一并核对。
const (
	trayWinClass = "SystrayClass"
	trayIconID   = 100
)

var (
	trayMu      sync.Mutex
	trayApp     *App
	trayStarted bool
	trayReady   bool
	mItemShow   *systray.MenuItem
	mItemDial   *systray.MenuItem
	mItemHangup *systray.MenuItem
	mItemUpdate *systray.MenuItem
	mItemExit   *systray.MenuItem

	// trayReadyCh 在 onTrayReady(图标与菜单装配完成)时关闭,
	// 供 stopTray 有界等待托盘就绪后再投递退出。
	trayReadyCh = make(chan struct{})

	// trayExitCh 由托盘消息循环在图标删除完成后关闭(onTrayExit 在
	// WM_DESTROY 的 nid.delete 之后回调),供 stopTray 有界等待落定。
	trayExitCh   = make(chan struct{})
	trayExitOnce sync.Once

	// trayQuitReq 退出请求标志:退出可能先于托盘就绪(重复实例自退),
	// 此时 systray.Quit 的 PostMessage 会因窗口尚未创建而丢失、quitOnce
	// 已消耗,消息循环永远等不到 WM_CLOSE。置位后由 onTrayReady 自行 Quit。
	trayQuitReq atomic.Bool

	// refreshTray 的变更检测缓存(trayMu 保护):状态无变化时跳过
	// SetTooltip/Enable/Disable,把跨线程菜单操作压到真实变化时才发生。
	lastTooltip string
	lastOnline  bool
	lastBusy    bool
)

// initTray 启动托盘（独立 goroutine 中运行消息循环）。
func initTray(a *App) {
	trayMu.Lock()
	trayApp = a
	trayStarted = true
	trayMu.Unlock()
	go func() {
		// Win32 消息循环线程亲和性:托盘窗口在哪个线程创建,GetMessageW
		// 就必须在哪个线程泵。Go 调度器可能在阻塞唤醒后把本 goroutine 挪到
		// 其它 OS 线程,一旦挪动,托盘窗口的消息永远无人处理——右键菜单
		// 弹不出、菜单点击全部失灵,正是「幽灵图标」的首要根因。systray
		// 库只在自己的 init(主 goroutine)里 LockOSThread,消息循环跑在
		// 本 goroutine 上必须自行锁定,与 notify_windows.go 气泡窗口一致。
		runtime.LockOSThread()
		systray.Run(onTrayReady, onTrayExit)
	}()
}

func onTrayReady() {
	if trayQuitReq.Load() {
		// 退出请求先于托盘就绪:窗口此刻已创建(stopTray 的 Quit 不再丢失),
		// 立即自行退出,WM_DESTROY 仍会删图标并回调 onTrayExit。
		systray.Quit()
		return
	}
	trayMu.Lock()
	app := trayApp
	trayMu.Unlock()
	if app == nil {
		return
	}
	systray.SetIcon(trayIcon)
	systray.SetTitle(i18n.T("app.title"))
	// 左键单击直接唤起主窗口(Windows 常规托盘交互);右键保持弹出菜单
	// (库的默认行为,SetOnSecondaryTapped 不设置即回退 showMenu)。
	// 回调跑在托盘消息循环线程,必须立即返回,实际动作移交 goroutine。
	systray.SetOnTapped(func() {
		go func() {
			trayMu.Lock()
			a := trayApp
			trayMu.Unlock()
			if a != nil {
				a.ShowWindow()
			}
		}()
	})

	mItemShow = systray.AddMenuItem(i18n.T("tray.showWindow"), "")
	mItemDial = systray.AddMenuItem(i18n.T("home.dial.connect"), "")
	mItemHangup = systray.AddMenuItem(i18n.T("home.dial.disconnect"), "")
	systray.AddSeparator()
	mItemUpdate = systray.AddMenuItem(i18n.T("tray.checkUpdates"), "")
	systray.AddSeparator()
	mItemExit = systray.AddMenuItem(i18n.T("tray.exit"), "")

	go func() {
		for range mItemShow.ClickedCh {
			trayMu.Lock()
			a := trayApp
			trayMu.Unlock()
			if a != nil {
				a.ShowWindow()
			}
		}
	}()
	go func() {
		for range mItemDial.ClickedCh {
			trayMu.Lock()
			a := trayApp
			trayMu.Unlock()
			if a != nil && !a.isOnline() && !a.lifecycle.IsBusy() {
				a.Dial()
			}
		}
	}()
	go func() {
		for range mItemHangup.ClickedCh {
			trayMu.Lock()
			a := trayApp
			trayMu.Unlock()
			if a != nil && a.isOnline() && !a.lifecycle.IsBusy() {
				a.Disconnect()
			}
		}
	}()
	go func() {
		for range mItemUpdate.ClickedCh {
			trayMu.Lock()
			a := trayApp
			trayMu.Unlock()
			if a != nil {
				a.CheckUpdate(true)
			}
		}
	}()
	go func() {
		for range mItemExit.ClickedCh {
			trayMu.Lock()
			a := trayApp
			trayMu.Unlock()
			if a != nil {
				a.ExitProgram()
			}
		}
	}()

	trayMu.Lock()
	trayReady = true
	trayMu.Unlock()
	close(trayReadyCh)
	app.refreshTray()
	go func() {
		ticker := time.NewTicker(3 * time.Second)
		defer ticker.Stop()
		for range ticker.C {
			trayMu.Lock()
			a := trayApp
			trayMu.Unlock()
			if a == nil {
				return
			}
			a.refreshTray()
		}
	}()
}

func onTrayExit() {
	trayMu.Lock()
	trayReady = false
	trayMu.Unlock()
	trayExitOnce.Do(func() { close(trayExitCh) })
}

// stopTray 退出托盘并保证图标被删除后才返回。
//
// 三级防线:
//  1. 就绪等待:退出请求可能先于托盘就绪(重复实例自退),先等装配完成,
//     onTrayReady 看到 trayQuitReq 会立即自行 Quit;
//  2. 优雅退出:systray.Quit 投递 WM_CLOSE,由 WM_DESTROY 里的 NIM_DELETE
//     异步删图标,onTrayExit 随后关闭 trayExitCh;
//  3. 兜底直删:消息循环若已失效或卡死,WM_CLOSE 永不被处理,这里直接
//     对托盘窗口执行 Shell_NotifyIconW(NIM_DELETE)——该调用不依赖消息
//     循环存活,保证 ExitProgram 的硬杀(os.Exit)前图标一定落地删除,
//     不残留「进程已消失、右键无响应」的幽灵图标。
func stopTray() {
	trayMu.Lock()
	started := trayStarted
	trayMu.Unlock()
	if !started {
		// 托盘从未启动(重复实例路径):没有图标可删
		return
	}
	trayQuitReq.Store(true)

	// 等托盘装配完成(至多 1.5s);若消息循环在此期间已自行退出
	// (onTrayReady 见 trayQuitReq 后 Quit),直接放行。
	select {
	case <-trayReadyCh:
	case <-trayExitCh:
	case <-time.After(1500 * time.Millisecond):
	}

	systray.Quit()
	select {
	case <-trayExitCh:
	case <-time.After(time.Second):
		platform.DeleteTrayIcon(trayWinClass, trayIconID)
	}
}

// showTrayNotification 弹出托盘气泡。
func showTrayNotification(title, message string) {
	trayMu.Lock()
	ready := trayReady
	trayMu.Unlock()
	if !ready {
		return
	}
	platform.ShowNotification(title, message)
}

// refreshTrayLabels 语言切换后重写托盘标题与静态菜单文案。
// tooltip 由 refreshTray 的 3s 轮询负责,这里清掉变更检测缓存让下一次
// 轮询必然重写 tooltip。
func refreshTrayLabels() {
	trayMu.Lock()
	defer trayMu.Unlock()
	if !trayReady {
		return
	}
	systray.SetTitle(i18n.T("app.title"))
	if mItemShow != nil {
		mItemShow.SetTitle(i18n.T("tray.showWindow"))
	}
	if mItemDial != nil {
		mItemDial.SetTitle(i18n.T("home.dial.connect"))
	}
	if mItemHangup != nil {
		mItemHangup.SetTitle(i18n.T("home.dial.disconnect"))
	}
	if mItemUpdate != nil {
		mItemUpdate.SetTitle(i18n.T("tray.checkUpdates"))
	}
	if mItemExit != nil {
		mItemExit.SetTitle(i18n.T("tray.exit"))
	}
	lastTooltip = ""
}

// tooltipMask 把账号掩码收敛到 8 字符内,避免长用户名把 tooltip 顶超 64 字符。
func tooltipMask(username string) string {
	m := util.MaskAccount(username, 2)
	if r := []rune(m); len(r) > 8 {
		m = string(r[len(r)-8:])
	}
	return m
}

// refreshTray 刷新托盘图标、tooltip 与菜单项可用性。
// 全程持 trayMu:菜单项字段读写与 Win32 调用不允许并发交叉;变更检测让
// 3s 轮询在状态无变化时零 Win32 调用,避免与 TrackPopupMenu 的模态循环
// (用户正打开着菜单)高频撞车。
func (a *App) refreshTray() {
	trayMu.Lock()
	defer trayMu.Unlock()
	if !trayReady {
		return
	}
	a.mu.Lock()
	online := a.online
	down := a.sessionDown
	up := a.sessionUp
	a.mu.Unlock()

	// Windows 11 任务栏把托盘 tooltip 截断在 64 字符(含结尾 NUL),
	// 文案必须紧凑:去掉标题与时长,上下行速率并作一行,账号掩码
	// 收敛到 8 字符,保证 ↓/↑ 行永远不会被截掉。
	var sb strings.Builder
	if online {
		sb.WriteString(i18n.T("status.connected"))
		if user := a.broadbandUsername(); user != "" {
			sb.WriteString("\n" + i18n.T("tray.account") + tooltipMask(user))
		}
		a.mu.Lock()
		ds, us := a.downSpeed, a.upSpeed
		a.mu.Unlock()
		sb.WriteString("\n↓ " + util.FormatSpeed(ds) + " ↑ " + util.FormatSpeed(us))
		sb.WriteString("\n" + i18n.T("tray.totalShort") + util.FormatBytes(down+up))
	} else {
		sb.WriteString(i18n.T("status.disconnected"))
	}
	tooltip := sb.String()

	busy := a.lifecycle.IsBusy()
	// tooltip 与菜单可用性分开检测:在线时速率几乎每轮都变(tooltip 跟随
	// 刷新),而菜单可用性只取决于 online/busy——不能让速率跳动连带触发
	// 跨线程菜单操作,那正是与 TrackPopupMenu 模态循环撞车的风险源。
	tooltipChanged := tooltip != lastTooltip
	menuChanged := online != lastOnline || busy != lastBusy
	if !tooltipChanged && !menuChanged {
		return
	}
	lastTooltip, lastOnline, lastBusy = tooltip, online, busy

	if tooltipChanged {
		systray.SetTooltip(tooltip)
	}
	if menuChanged {
		if mItemDial != nil {
			if !online && !busy {
				mItemDial.Enable()
			} else {
				mItemDial.Disable()
			}
		}
		if mItemHangup != nil {
			if online && !busy {
				mItemHangup.Enable()
			} else {
				mItemHangup.Disable()
			}
		}
	}
}
