package platform

import (
	"os/exec"
	"syscall"
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
