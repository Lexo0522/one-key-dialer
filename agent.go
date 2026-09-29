package main

import (
	"os"
	"runtime/debug"
	"time"
)

// autoStartLaunch 标记本次代理是否由 Windows 自启动拉起。
var autoStartLaunch bool

func setAutoStartLaunch() { autoStartLaunch = true }

// 代理进程内存紧致化:
// 托盘常驻是本应用 99% 时间的形态,目标是私有工作集 ~20MB 量级。
// - GC 目标降到 40%(小堆更激进回收)与 32MiB 软上限(超限自动加压);
// - 启动装配完成与每 2 分钟归还一次内存给 OS,避免 RSS 只涨不降。
// 同时每轮触发一次日志落盘(代理没有窗口,日志是唯一的排障现场)。
// UI 进程不需要(Wails/WebView2 的内存远大于 Go 堆,且生命周期短)。
func startMemoryKeeper(stop chan struct{}, onTick func()) {
	debug.SetGCPercent(40)
	debug.SetMemoryLimit(32 << 20)
	debug.FreeOSMemory()
	go func() {
		ticker := time.NewTicker(2 * time.Minute)
		defer ticker.Stop()
		for {
			select {
			case <-stop:
				return
			case <-ticker.C:
				debug.FreeOSMemory()
				if onTick != nil {
					onTick()
				}
			}
		}
	}()
}

// currentExe 当前进程可执行文件路径(拉起 UI 进程用)。
func currentExe() (string, error) {
	return os.Executable()
}
