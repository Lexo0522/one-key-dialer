//go:build windows

package platform

import (
	"runtime"
	"sync"
	"sync/atomic"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

// 会话结束消息。关机/重启，以及 Windows Installer 用重启管理器关闭
// 「占用待更新文件的应用程序」时，发的就是这一对。
const (
	wmQueryEndSession = 0x0011
	wmEndSession      = 0x0016
)

var procGetMessageW = user32n.NewProc("GetMessageW")

var (
	// sessionEndFn 当前生效的停机回调。做成可替换的：调用方（测试里
	// -count>1、或将来有别处也想挂）后调用的覆盖先前的，窗口仍只有一个。
	sessionEndFn atomic.Value // func()
)

// sessionEndOnce 保证一个进程只挂一个会话结束窗口：重复调用（测试里
// -count>1，或将来有别处也想挂）不再重复注册窗口类、重复建窗口。
var sessionEndOnce sync.Once

// StartSessionEndWindow 起一个隐藏的顶层窗口并泵消息，让本进程能够响应
// WM_QUERYENDSESSION / WM_ENDSESSION，从而被 Windows Installer 的重启管理器
// （以及系统关机）正常关闭。
//
// 为什么必须有：代理进程是常驻托盘进程，重启管理器关应用只认「有顶层窗口的
// 进程」——它向这些窗口问一句 WM_QUERYENDSESSION，再正式通知 WM_ENDSESSION。
// 托盘窗口（systray 的 SystrayClass）确实是个隐藏顶层窗口，重启管理器找得到
// 也发得到，可它的窗口过程不处理 WM_ENDSESSION，进程就是不退；重启管理器
// 等不到进程退出，只能放弃。于是卸载/升级时代理一直占着 PPPoEDialer.exe，
// 安装器卡在「收集信息」直到超时——用户侧看到的就是「卸载卡住、升级卡住」。
// （UI 进程有 WebView2 窗口，关得掉，所以卡住的总是代理。）
//
// 补上这个窗口后：WM_QUERYENDSESSION 答复"可以关"，WM_ENDSESSION 时回调
// onEndSession 走有序停机，进程干净退出，安装器随即拿到文件句柄。
//
// 窗口刻意不用 HWND_MESSAGE：消息专用窗口 EnumWindows 枚举不到，重启管理器
// 也就看不见，补了也白补。这里要的是一个普通隐藏顶层窗口（WS_OVERLAPPED、
// 不置 WS_VISIBLE）——与 systray 的托盘窗口同类，DeleteTrayIcon 正是用
// EnumWindows 找到它的。
//
// onEndSession 在独立 goroutine 里被调用：停机要走关托盘/断管道/停服务，
// 不能堵住消息循环。调用方需自行保证幂等。
func StartSessionEndWindow(onEndSession func()) {
	if runtime.GOOS != "windows" || onEndSession == nil {
		return
	}
	sessionEndFn.Store(onEndSession)
	sessionEndOnce.Do(func() { startSessionEndWindow() })
}

func startSessionEndWindow() {
	if procRegisterClassExW.Find() != nil || procCreateWindowExW.Find() != nil ||
		procGetMessageW.Find() != nil || procDispatchMessageW.Find() != nil {
		return
	}

	// 类名指针必须活到窗口创建，不能用临时变量。
	const className = "PPPoEDialerSessionEndClass"
	classUTF16 := windows.StringToUTF16Ptr(className)

	wndProc := syscall.NewCallback(func(hwnd, msg, wparam, lparam uintptr) uintptr {
		switch msg {
		case wmQueryEndSession:
			// 答复"可以结束会话"。返回 0 会被当成拒绝，重启管理器只能
			// 等超时或放弃，装/卸照样卡住。
			return 1
		case wmEndSession:
			// wparam 非 0 表示会话真的要结束（0 只是被查询后取消）。
			if wparam != 0 {
				if fn, ok := sessionEndFn.Load().(func()); ok && fn != nil {
					go fn()
				}
			}
			return 0
		}
		r, _, _ := procDefWindowProcW.Call(hwnd, msg, wparam, lparam)
		return r
	})

	hInst, _, _ := procGetModuleHandleW.Call(0)
	var wc wndClassEx
	wc.CbSize = uint32(unsafe.Sizeof(wc))
	wc.LpfnWndProc = wndProc
	wc.HInstance = windows.Handle(hInst)
	wc.LpszClassName = classUTF16
	if ret, _, _ := procRegisterClassExW.Call(uintptr(unsafe.Pointer(&wc))); ret == 0 {
		return
	}

	go func() {
		defer func() { _ = recover() }()
		// 建窗口与泵消息必须落在同一个 OS 线程上：Windows 的窗口归属于创建
		// 它的线程，消息也只进那个线程的队列。若在调用方线程建窗口、另起
		// goroutine 泵消息，两条线程各忙各的，SendMessage 发出去永远等不到
		// 应答——那比不挂窗口更糟（调用方会被挂死）。
		// notify_windows.go 的气泡窗口也是这个写法。
		runtime.LockOSThread()
		defer runtime.UnlockOSThread()

		// Style 用 WS_OVERLAPPED(0) 且不置 WS_VISIBLE：一个看不见的普通
		// 顶层窗口，EnumWindows 照样能枚举到（DeleteTrayIcon 找托盘窗口同理）。
		hwnd, _, _ := procCreateWindowExW.Call(
			0,
			uintptr(unsafe.Pointer(classUTF16)),
			0,
			0, // dwStyle = WS_OVERLAPPED，不置 WS_VISIBLE
			0, 0, 0, 0,
			0, // hWndParent = 桌面，顶层窗口
			0, hInst, 0)
		if hwnd == 0 {
			return
		}

		var msg struct {
			HWnd    windows.Handle
			Message uint32
			WParam  uintptr
			LParam  uintptr
			Time    uint32
			Pt      [2]int32
		}
		for {
			r, _, _ := procGetMessageW.Call(uintptr(unsafe.Pointer(&msg)), hwnd, 0, 0)
			// 0 = WM_QUIT，-1 = 出错；两者都不该继续循环。
			if r == 0 || r == ^uintptr(0) {
				return
			}
			procTranslateMessage.Call(uintptr(unsafe.Pointer(&msg)))
			procDispatchMessageW.Call(uintptr(unsafe.Pointer(&msg)))
		}
	}()
}
