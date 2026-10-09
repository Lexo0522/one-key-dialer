package platform

import (
	"os"
	"os/exec"
	"strings"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	procOpenProcess                = modkernel32.NewProc("OpenProcess")
	procQueryFullProcessImageNameW = modkernel32.NewProc("QueryFullProcessImageNameW")
	procTerminateProcess           = modkernel32.NewProc("TerminateProcess")
)

// hiddenProcAttr 返回隐藏控制台窗口的进程属性（CREATE_NO_WINDOW）。
func hiddenProcAttr() *syscall.SysProcAttr {
	return &syscall.SysProcAttr{
		HideWindow:    true,
		CreationFlags: 0x08000000, // CREATE_NO_WINDOW
	}
}

// LaunchDetached 以隐藏控制台的方式启动进程，且立即返回（不等待结束）。
// 用于启动 apply_update.bat：脚本必须在父进程退出后继续跑完。
func LaunchDetached(command []string, workDir string) error {
	if len(command) == 0 {
		return exec.ErrNotFound
	}
	cmd := exec.Command(command[0], command[1:]...)
	cmd.Dir = workDir
	cmd.SysProcAttr = hiddenProcAttr()
	return cmd.Start()
}

// LaunchGUI 启动一个 GUI 子进程（UI 进程），立即返回（不等待结束）。
// 不能复用 LaunchDetached：CREATE_NO_WINDOW 会把子进程 STARTUPINFO 的
// wShowWindow 置为 SW_HIDE，其主窗口会一直处于隐藏状态（表现为
// 托盘「显示窗口」拉不出窗口）。
func LaunchGUI(command []string, workDir string) error {
	if len(command) == 0 {
		return exec.ErrNotFound
	}
	cmd := exec.Command(command[0], command[1:]...)
	cmd.Dir = workDir
	return cmd.Start()
}

// killOwnProcess 终结一个属于本程序的可疑 UI 进程。
// PID 来自 IPC hello 握手,但终结前必须核对进程映像路径与当前进程一致:
// 从握手到终结之间 PID 可能已经退出并被系统复用,绝不能凭 PID 杀进程。
// 不是本程序的映像、或无法打开/终结时返回 false。
func KillOwnProcess(pid int) bool {
	const (
		processQueryLimitedInformation = 0x1000
		processTerminate               = 0x0001
	)
	if pid <= 0 {
		return false
	}
	h, _, _ := procOpenProcess.Call(
		processQueryLimitedInformation|processTerminate,
		0, uintptr(pid))
	if h == 0 {
		return false
	}
	defer func() { _ = windows.CloseHandle(windows.Handle(h)) }()

	self, err := os.Executable()
	if err != nil {
		return false
	}
	var buf [1024]uint16
	n := uint32(len(buf))
	r, _, _ := procQueryFullProcessImageNameW.Call(
		h, 0, uintptr(unsafe.Pointer(&buf[0])), uintptr(unsafe.Pointer(&n)))
	if r == 0 || n == 0 {
		return false
	}
	if !strings.EqualFold(windows.UTF16ToString(buf[:n]), self) {
		return false
	}
	ret, _, _ := procTerminateProcess.Call(h, 1)
	return ret != 0
}
