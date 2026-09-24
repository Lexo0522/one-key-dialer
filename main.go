package main

import (
	"context"
	"embed"
	"os"
	"time"

	"github.com/Lexo0522/one-key-dialer/internal/model"
	"github.com/Lexo0522/one-key-dialer/internal/platform"
	"github.com/Lexo0522/one-key-dialer/internal/storage"
	"github.com/wailsapp/wails/v2"
	"github.com/wailsapp/wails/v2/pkg/options"
	"github.com/wailsapp/wails/v2/pkg/options/assetserver"
)

//go:embed all:frontend/dist
var assets embed.FS

// autoStartLaunch 标记本次是否由 Windows 自启动拉起。
var autoStartLaunch bool

const (
	// 默认尺寸按「侧栏 208px + 主内容区不挤压」反推：980 宽时首页四张统计
	// 卡片可排满一行、账号表格五列完整展开；760 高保证首页图表与统计卡
	// 完整可见。屏幕上放不下时由 clampToWorkArea 收敛，不会超出工作区。
	windowWidth  = 980
	windowHeight = 760
	// 最小尺寸对应「侧栏折叠为图标栏(56px) + 统计卡片两列 + 表格横向滚动」
	// 的下限；低于该宽度前端必然出现明显挤压，故从窗口层面直接限制。
	windowMinWidth  = 640
	windowMinHeight = 600
)

// clampToWorkArea 把初始窗口尺寸钳制到屏幕工作区（留 16px 呼吸边距），
// 保证 1366x768 一类小屏上启动时窗口完整可见、底部按钮不被任务栏挡住。
// 工作区获取失败时保持原尺寸。
func clampToWorkArea(w, h int) (int, int) {
	const margin = 16
	if waW, waH := platform.WorkArea(); waW > 0 && waH > 0 {
		if max := waW - margin; w > max {
			w = max
		}
		if max := waH - margin; h > max {
			h = max
		}
	}
	return w, h
}

func main() {
	for _, arg := range os.Args[1:] {
		if arg == platform.AutoStartFlag {
			autoStartLaunch = true
			break
		}
	}
	if autoStartLaunch {
		time.Sleep(time.Duration(platform.AutoStartDelayMs) * time.Millisecond)
	}

	startHidden := readStartMinimized()
	// 窗口可见性跟踪：非最小化启动时窗口可见，通知只走前端 Toast；
	// 否则（最小化启动/关闭到托盘）同时弹托盘气泡
	windowShown = !startHidden
	app := NewApp()

	width, height := clampToWorkArea(windowWidth, windowHeight)

	err := wails.Run(&options.App{
		Title:            appTitle() + " " + model.Display(),
		Width:            width,
		Height:           height,
		MinWidth:         windowMinWidth,
		MinHeight:        windowMinHeight,
		StartHidden:      startHidden,
		AssetServer:      &assetserver.Options{Assets: assets},
		BackgroundColour: &options.RGBA{R: 248, G: 249, B: 250, A: 1},
		OnStartup:        app.startup,
		OnDomReady:       app.domReady,
		OnShutdown:       app.shutdown,
		OnBeforeClose:    app.beforeClose,
		Bind:             []interface{}{app},
	})
	if err != nil {
		println("Error:", err.Error())
	}
}

// beforeClose 主窗口关闭时只隐藏到托盘；真正的退出走托盘「退出」。
func (a *App) beforeClose(ctx context.Context) bool {
	windowShown = false
	if a.ctx == nil {
		a.ctx = ctx
	}
	go a.HideWindow()
	return true
}

func appTitle() string { return "PPPoE校园网拨号工具" }

// readStartMinimized 在窗口创建前直接读 settings.json，决定是否隐藏启动。
func readStartMinimized() bool {
	dir := platform.DataDir()
	store := &storage.SettingsStore{File: dir + string(os.PathSeparator) + "settings.json"}
	snap, err := store.Load()
	if err != nil || snap == nil {
		return false
	}
	return snap.StartMinimized
}
