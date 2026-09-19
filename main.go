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
	windowWidth     = 580
	windowHeight    = 700
	windowMinWidth  = 520
	windowMinHeight = 560
)

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
	app := NewApp()

	err := wails.Run(&options.App{
		Title:            appTitle() + " " + model.Display(),
		Width:            windowWidth,
		Height:           windowHeight,
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
