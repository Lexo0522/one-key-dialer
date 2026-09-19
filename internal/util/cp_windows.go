//go:build windows

package util

import "syscall"

// ansiCodePage 返回 Windows ANSI 代码页（936 = 简体中文 GBK）。
func ansiCodePage() int {
	kernel32 := syscall.NewLazyDLL("kernel32.dll")
	proc := kernel32.NewProc("GetACP")
	v, _, _ := proc.Call()
	return int(v)
}
