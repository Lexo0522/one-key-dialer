package platform

import (
	"errors"
	"syscall"
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
// 已被占用时返回 acquired=false;调用方用完应调用 release。
func AcquireSingleInstance(name string) (acquired bool, release func()) {
	namePtr, err := windows.UTF16PtrFromString(name)
	if err != nil {
		return true, func() {} // 名称非法时按可获取处理,不阻塞启动
	}
	handle, _, callErr := procCreateMutexW.Call(0, 0, uintptr(unsafe.Pointer(namePtr)))
	if handle == 0 {
		// 真失败(极少):按可获取处理,不阻塞启动
		return true, func() {}
	}
	if errors.Is(callErr, errorAlreadyExists) {
		windows.CloseHandle(windows.Handle(handle))
		return false, func() {}
	}
	return true, func() { windows.CloseHandle(windows.Handle(handle)) }
}

// ShowErrorBox 弹一个原生错误框(UI 模式启动失败、无托盘可依赖时使用)。
func ShowErrorBox(title, text string) {
	titlePtr, err1 := windows.UTF16PtrFromString(title)
	textPtr, err2 := windows.UTF16PtrFromString(text)
	if err1 != nil || err2 != nil {
		return
	}
	procMessageBoxW.Call(0,
		uintptr(unsafe.Pointer(textPtr)), uintptr(unsafe.Pointer(titlePtr)), 0x10 /*MB_ICONERROR*/)
}
