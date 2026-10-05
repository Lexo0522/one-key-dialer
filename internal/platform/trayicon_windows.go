//go:build windows

package platform

import (
	"os"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	procEnumWindows            = user32n.NewProc("EnumWindows")
	procGetClassNameW          = user32n.NewProc("GetClassNameW")
	procGetWindowThreadProcIDs = user32n.NewProc("GetWindowThreadProcessId")
)

// DeleteTrayIcon 直接删除本进程在通知区添加的图标(Shell_NotifyIconW 的
// NIM_DELETE),可在任意 goroutine 调用,不依赖拥有图标的消息循环存活。
//
// 用途:getlantern/systray 的正常删除路径要走托盘消息循环(PostMessage
// WM_CLOSE → WM_DESTROY → nid.delete);消息循环一旦失效或卡死,退出时的
// 硬杀(os.Exit)会残留「进程已消失、右键无响应」的幽灵图标。停机序列在
// 优雅等待超时后调用本函数兜底,保证图标先于进程消亡落地删除。
//
// className/uid 必须与库内部创建托盘窗口时使用的类名和 uID 完全一致
// (getlantern/systray v1.2.2 为 "SystrayClass"/100);升级库版本需核对。
// 同时校验窗口归属本进程 PID:窗口类名仅进程内唯一,系统里可能有其它
// 程序注册了同名类,绝不能误删别人的图标。
func DeleteTrayIcon(className string, uid uint32) bool {
	if procShellNotifyIconW.Find() != nil || procEnumWindows.Find() != nil ||
		procGetClassNameW.Find() != nil || procGetWindowThreadProcIDs.Find() != nil {
		return false
	}
	self := uint32(os.Getpid())

	var target uintptr
	// EnumWindows 回调:返回 0 停止枚举,非 0 继续。
	cb := syscall.NewCallback(func(hwnd, lparam uintptr) uintptr {
		if target != 0 {
			return 1
		}
		var pid uint32
		procGetWindowThreadProcIDs.Call(hwnd, uintptr(unsafe.Pointer(&pid)))
		if pid != self {
			return 1
		}
		buf := make([]uint16, 64)
		n, _, _ := procGetClassNameW.Call(hwnd,
			uintptr(unsafe.Pointer(&buf[0])), uintptr(len(buf)))
		if n == 0 || windows.UTF16ToString(buf[:n]) != className {
			return 1
		}
		target = hwnd
		return 0
	})
	procEnumWindows.Call(cb, 0)
	if target == 0 {
		return false
	}

	// NIM_DELETE 只需要 cbSize/hWnd/uID 三个成员
	data := notifyIconData{
		CbSize: uint32(unsafe.Sizeof(notifyIconData{})),
		HWnd:   windows.Handle(target),
		UID:    uid,
	}
	ret, _, _ := procShellNotifyIconW.Call(nimDelete, uintptr(unsafe.Pointer(&data)))
	return ret != 0
}
