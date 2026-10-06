package platform

import (
	"errors"
	"syscall"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	modkernel32      = windows.NewLazySystemDLL("kernel32.dll")
	procCreateMutexW = modkernel32.NewProc("CreateMutexW")
	moduser32        = windows.NewLazySystemDLL("user32.dll")
	procMessageBoxW  = moduser32.NewProc("MessageBoxW")
)

// AppModeFlags 进程模式命令行标记:同一 exe,两种运行形态。
const (
	// AgentFlag 代理模式:纯 Go 常驻(托盘+拨号+全部服务),无 WebView。
	AgentFlag = "--agent"
	// UIFlag UI 模式:Wails 窗口,经命名管道调用代理。
	UIFlag = "--ui"
)

// errorAlreadyExists CreateMutexW:命名互斥体已被占用。
const errorAlreadyExists = syscall.Errno(183)

// AcquireSingleInstance 获取命名互斥体(单实例标记)。
// 已被占用时在 wait 时限内轮询等待持有者退出后重试,超时返回 acquired=false;
// wait <= 0 表示一次尝试、不等待,立即返回结果。
// UI 进程必须用等待语义:上一实例关闭后的收尾窗口期(WebView2 销毁可达
// 数秒)内拉起的新实例,若被互斥体瞬间弹走,只能经 FocusWindow 空转退出,
// 窗口永远出不来;等待旧实例退出后自然接位,拉起一次即成功。
func AcquireSingleInstance(name string, wait time.Duration) (acquired bool, release func()) {
	namePtr, err := windows.UTF16PtrFromString(name)
	if err != nil {
		return true, func() {} // 名称非法时按可获取处理,不阻塞启动
	}
	deadline := time.Now().Add(wait)
	for {
		handle, _, callErr := procCreateMutexW.Call(0, 0, uintptr(unsafe.Pointer(namePtr)))
		if handle == 0 {
			// 真失败(极少):按可获取处理,不阻塞启动
			return true, func() {}
		}
		if !errors.Is(callErr, errorAlreadyExists) {
			return true, func() { _ = windows.CloseHandle(windows.Handle(handle)) }
		}
		_ = windows.CloseHandle(windows.Handle(handle))
		if wait <= 0 || !time.Now().Before(deadline) {
			return false, func() {}
		}
		time.Sleep(100 * time.Millisecond)
	}
}

// ShowErrorBox 弹一个原生错误框(UI 模式启动失败、无托盘可依赖时使用)。
func ShowErrorBox(title, text string) {
	titlePtr, err1 := windows.UTF16PtrFromString(title)
	textPtr, err2 := windows.UTF16PtrFromString(text)
	if err1 != nil || err2 != nil {
		return
	}
	// 返回值是用户点了哪个按钮，本函数不关心，只看调用是否送达。
	_, _, _ = procMessageBoxW.Call(0,
		uintptr(unsafe.Pointer(textPtr)), uintptr(unsafe.Pointer(titlePtr)), 0x10 /*MB_ICONERROR*/)
}
