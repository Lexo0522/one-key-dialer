//go:build windows

package platform

import (
	"runtime"
	"sync"
	"syscall"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

// 气泡通知常量
const (
	nimAdd      = 0x00000000
	nimModify   = 0x00000001
	nimDelete   = 0x00000002
	nifMessage  = 0x00000001
	nifIcon     = 0x00000002
	nifTip      = 0x00000004
	nifInfo     = 0x00000010
	niifInfo    = 0x00000001
	niifWarning = 0x00000002
	niifError   = 0x00000003
	wmApp       = 0x8000
)

type notifyIconData struct {
	CbSize           uint32
	HWnd             windows.Handle
	UID              uint32
	UFlags           uint32
	UCallbackMessage uint32
	HIcon            windows.Handle
	SzTip            [128]uint16
	DwState          uint32
	DwStateMask      uint32
	SzInfo           [256]uint16
	UVersion         uint32
	SzInfoTitle      [64]uint16
	DwInfoFlags      uint32
	GuidItem         [16]byte
	HBalloonIcon     windows.Handle
}

var (
	shell32              = syscall.NewLazyDLL("shell32.dll")
	procShellNotifyIconW = shell32.NewProc("Shell_NotifyIconW")
	user32n              = syscall.NewLazyDLL("user32.dll")
	procRegisterClassExW = user32n.NewProc("RegisterClassExW")
	procCreateWindowExW  = user32n.NewProc("CreateWindowExW")
	procDestroyWindow    = user32n.NewProc("DestroyWindow")
	procPostQuit         = user32n.NewProc("PostQuitMessage")
	procLoadIconMetric   = syscall.NewLazyDLL("comctl32.dll").NewProc("LoadIconMetric")

	notifyOnce sync.Once
	notifyMu   sync.Mutex
)

// utf16Of 将字符串转成 UTF-16 码点序列（不含结尾 0）。
func utf16Of(s string) []uint16 {
	return windows.StringToUTF16(s)
}

// truncateUTF16 按 UTF-16 码点数截断，避免踩坏代理对。
func truncateUTF16(s string, max int) string {
	u := windows.StringToUTF16(s)
	if len(u) <= max {
		return s
	}
	return windows.UTF16ToString(u[:max])
}

// ShowNotification 弹出一次 Windows 托盘气泡通知。
// 独立线程 + 独立隐藏窗口：不干扰 Wails 与托盘各自的消息循环，失败静默。
func ShowNotification(title, message string) {
	if runtime.GOOS != "windows" {
		return
	}
	t, m := truncateUTF16(title, 63), truncateUTF16(message, 255)
	go func() {
		notifyMu.Lock()
		defer notifyMu.Unlock()
		runtime.LockOSThread()
		defer runtime.UnlockOSThread()
		showBalloon(t, m)
	}()
}

func showBalloon(title, message string) {
	if procShellNotifyIconW.Find() != nil || procCreateWindowExW.Find() != nil {
		return
	}
	cls := registerHiddenClass()
	if cls == "" {
		return
	}
	hInst, _, _ := procGetModuleHandleW.Call(0)
	hwnd, _, _ := procCreateWindowExW.Call(
		0,
		uintptr(unsafe.Pointer(windows.StringToUTF16Ptr(cls))),
		uintptr(unsafe.Pointer(windows.StringToUTF16Ptr("PPoEDialerNotify"))),
		0, 0, 0, 0, 0,
		uintptr(^uintptr(2)), // HWND_MESSAGE = -3（仅接收消息，不显示窗口）
		0, hInst, 0)
	if hwnd == 0 {
		return
	}

	var data notifyIconData
	data.CbSize = uint32(unsafe.Sizeof(data))
	data.HWnd = windows.Handle(hwnd)
	data.UID = 1
	data.UFlags = nifMessage | nifIcon | nifTip | nifInfo
	data.UCallbackMessage = wmApp + 1
	data.DwInfoFlags = niifInfo
	copy(data.SzTip[:], utf16Of(truncateUTF16("PPPoEDialer", 127)))
	copy(data.SzInfoTitle[:], utf16Of(title))
	copy(data.SzInfo[:], utf16Of(message))

	ret, _, _ := procShellNotifyIconW.Call(nimAdd, uintptr(unsafe.Pointer(&data)))
	if ret == 0 {
		procDestroyWindow.Call(hwnd)
		return
	}

	// 短暂泵消息让气泡真正显示
	done := time.After(6 * time.Second)
	var msg struct {
		HWnd    windows.Handle
		Message uint32
		WParam  uintptr
		LParam  uintptr
		Time    uint32
		Pt      [2]int32
	}
	for {
		select {
		case <-done:
			procShellNotifyIconW.Call(nimDelete, uintptr(unsafe.Pointer(&data)))
			procDestroyWindow.Call(hwnd)
			return
		default:
		}
		res, _, _ := procPeekMessageW.Call(uintptr(unsafe.Pointer(&msg)), hwnd, 0, 0, 1)
		if res != 0 {
			procTranslateMessage.Call(uintptr(unsafe.Pointer(&msg)))
			procDispatchMessageW.Call(uintptr(unsafe.Pointer(&msg)))
			continue
		}
		time.Sleep(50 * time.Millisecond)
	}
}

var (
	procGetModuleHandleW = syscall.NewLazyDLL("kernel32.dll").NewProc("GetModuleHandleW")
	procPeekMessageW     = user32n.NewProc("PeekMessageW")
	procTranslateMessage = user32n.NewProc("TranslateMessage")
	procDispatchMessageW = user32n.NewProc("DispatchMessageW")
	procDefWindowProcW   = user32n.NewProc("DefWindowProcW")
)

type wndClassEx struct {
	CbSize        uint32
	Style         uint32
	LpfnWndProc   uintptr
	CbClsExtra    int32
	CbWndExtra    int32
	HInstance     windows.Handle
	HIcon         windows.Handle
	HCursor       windows.Handle
	HbrBackground windows.Handle
	LpszMenuName  *uint16
	LpszClassName *uint16
	HIconSm       windows.Handle
}

var wndProcCallback = syscall.NewCallback(func(hwnd, msg, wparam, lparam uintptr) uintptr {
	r, _, _ := procDefWindowProcW.Call(hwnd, msg, wparam, lparam)
	return r
})

func registerHiddenClass() string {
	name := "PPoEDialerNotifyClass"
	hInst, _, _ := procGetModuleHandleW.Call(0)
	var wc wndClassEx
	wc.CbSize = uint32(unsafe.Sizeof(wc))
	wc.LpfnWndProc = wndProcCallback
	wc.HInstance = windows.Handle(hInst)
	wc.LpszClassName = windows.StringToUTF16Ptr(name)
	ret, _, _ := procRegisterClassExW.Call(uintptr(unsafe.Pointer(&wc)))
	if ret == 0 {
		return ""
	}
	return name
}
