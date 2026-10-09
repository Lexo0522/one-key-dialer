package platform

import (
	"syscall"
	"testing"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

var procSendMessageW = user32n.NewProc("SendMessageW")

// findSessionEndWindow 用 EnumWindows + GetClassName 按类名找窗口。
// 重启管理器找"占用待更新文件的应用程序"用的正是这套机制，所以「找得到」
// 这件事本身就是被测行为——消息专用窗口（HWND_MESSAGE）是枚举不到的。
func findSessionEndWindow() windows.HWND {
	var found windows.HWND
	cb := syscall.NewCallback(func(hwnd, lparam uintptr) uintptr {
		var buf [256]uint16
		n, _, _ := procGetClassNameW.Call(hwnd, uintptr(unsafe.Pointer(&buf[0])), 256)
		if n > 0 && windows.UTF16ToString(buf[:n]) == "PPPoEDialerSessionEndClass" {
			found = windows.HWND(hwnd)
			return 0 // 找到了，停止枚举
		}
		return 1
	})
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		found = 0
		procEnumWindows.Call(cb, 0)
		if found != 0 {
			return found
		}
		time.Sleep(20 * time.Millisecond)
	}
	return 0
}

// TestSessionEndWindowIsShutdownable 验证会话结束窗口真的能被重启管理器
// 用来关掉本进程。
//
// 这条守的是一个真实故障：代理进程没有任何会响应 WM_ENDSESSION 的顶层窗口，
// 卸载/升级时它一直占着 PPPoEDialer.exe，Windows Installer 的重启管理器
// 关不掉它，安装器卡在「收集信息」直到超时——用户侧就是"卸载卡住"。
func TestSessionEndWindowIsShutdownable(t *testing.T) {
	fired := make(chan struct{}, 4)
	StartSessionEndWindow(func() { fired <- struct{}{} })

	hwnd := findSessionEndWindow()
	if hwnd == 0 {
		t.Fatal("EnumWindows 找不到会话结束窗口：重启管理器同样找不到，挂了也白挂")
	}

	// WM_QUERYENDSESSION 必须答复"可以关"。返回 0 会被当成拒绝，
	// 重启管理器只会放弃或强杀，装/卸照样卡。
	if r, _, _ := procSendMessageW.Call(uintptr(hwnd), wmQueryEndSession, 0, 0); r == 0 {
		t.Error("WM_QUERYENDSESSION 未答复 TRUE：重启管理器会认为应用拒绝关闭")
	}

	// WM_ENDSESSION 必须触发回调（真实场景里回调走有序停机，进程随即退出，
	// 安装器才拿得到 exe 的文件句柄）。
	procSendMessageW.Call(uintptr(hwnd), wmEndSession, 1, 0)
	select {
	case <-fired:
	case <-time.After(3 * time.Second):
		t.Error("WM_ENDSESSION 后回调未触发：进程仍会占着 exe，安装器照样卡住")
	}
}
