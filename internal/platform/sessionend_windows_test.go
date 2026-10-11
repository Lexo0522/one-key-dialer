package platform

import (
	"os"
	"syscall"
	"testing"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

var procSendMessageW = user32n.NewProc("SendMessageW")
var procGetWindowThreadProcessIdW = user32n.NewProc("GetWindowThreadProcessId")

// IsWindow 没有 A/W 后缀（只收一个句柄的函数都这样），直接按原名导出。
var procIsWindowW = user32n.NewProc("IsWindow")

// sessionEndWindowAlive 复核窗口是否仍然存活。
//
// EnumWindows 找到窗口与发送消息之间，窗口可能已被销毁——消息循环线程退出时
// Windows 会回收它创建的窗口。此时 SendMessage 静默返回 0，「窗口死了」与
// 「回调没跑」是两种完全不同的故障，必须分开报，否则下次复现时依然无从下手。
func sessionEndWindowAlive(hwnd windows.HWND) bool {
	r, _, _ := procIsWindowW.Call(uintptr(hwnd))
	return r != 0
}

// findSessionEndWindow 用 EnumWindows + GetClassName 按类名找窗口。
// 重启管理器找"占用待更新文件的应用程序"用的正是这套机制，所以「找得到」
// 这件事本身就是被测行为——消息专用窗口（HWND_MESSAGE）是枚举不到的。
func findSessionEndWindow() windows.HWND {
	self := uintptr(os.Getpid())
	var found windows.HWND
	cb := syscall.NewCallback(func(hwnd, lparam uintptr) uintptr {
		var buf [256]uint16
		n, _, _ := procGetClassNameW.Call(hwnd, uintptr(unsafe.Pointer(&buf[0])), 256)
		if n > 0 && windows.UTF16ToString(buf[:n]) == "PPPoEDialerSessionEndClass" {
			// 只认本进程创建的窗口。EnumWindows 枚举的是整个会话的顶层窗口，
			// 而压测会并行跑多个用同一个类名的子进程：不按属主进程过滤就会
			// 把消息发给别人家的窗口，回调自然不触发，测出一堆假故障。
			var pid uint32
			procGetWindowThreadProcessIdW.Call(hwnd, uintptr(unsafe.Pointer(&pid)))
			if uintptr(pid) != self {
				return 1
			}
			found = windows.HWND(hwnd)
			return 0 // 找到了，停止枚举
		}
		return 1
	})
	// 5 秒：CI 并行跑多个包的测试二进制时窗口线程可能被调度饿着，
	// 3 秒在满载机器上会把「起得慢」误报成「窗口没挂上」。
	deadline := time.Now().Add(5 * time.Second)
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
	if !sessionEndWindowAlive(hwnd) {
		t.Fatal("窗口刚枚举到就已失效：消息循环线程可能已经退出（见 startSessionEndWindow 的诊断输出）")
	}

	// WM_QUERYENDSESSION 必须答复"可以关"。返回 0 会被当成拒绝，
	// 重启管理器只会放弃或强杀，装/卸照样卡。
	if r, _, _ := procSendMessageW.Call(uintptr(hwnd), wmQueryEndSession, 0, 0); r == 0 {
		t.Error("WM_QUERYENDSESSION 未答复 TRUE：重启管理器会认为应用拒绝关闭")
	}

	// WM_ENDSESSION 必须触发回调（真实场景里回调走有序停机，进程随即退出，
	// 安装器才拿得到 exe 的文件句柄）。
	procSendMessageW.Call(uintptr(hwnd), wmEndSession, 1, 0)
	// 发完立即复核一次：把「WM_ENDSESSION 本身弄死了窗口」与「消息循环事后
	// 自己退出」两种成因分开。前者说明窗口过程对会话结束消息的处理有问题，
	// 后者才是线程生命周期问题。
	if !sessionEndWindowAlive(hwnd) {
		t.Error("WM_ENDSESSION 一发完窗口就失效：窗口过程对会话结束消息的处理有问题")
	}
	select {
	case <-fired:
	// 10 秒：这条守的是「重启管理器能否关掉本进程」，不是延迟指标。
	// 满载机器上回调 goroutine 被调度饿着几百毫秒是常态，3 秒会把环境问题
	// 报成代码问题；真正该区分的是窗口死没死，见下面的存活复核。
	case <-time.After(10 * time.Second):
		if sessionEndWindowAlive(hwnd) {
			t.Error("WM_ENDSESSION 后 10 秒内回调未触发且窗口仍存活：问题在窗口过程或调度，不在窗口生命周期")
		} else {
			t.Error("WM_ENDSESSION 前窗口已失效：消息循环线程退出导致 Windows 回收了窗口（见 startSessionEndWindow 的诊断输出）")
		}
	}
}
