package main

import (
	"context"
	"embed"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"github.com/Lexo0522/one-key-dialer/internal/i18n"
	"github.com/Lexo0522/one-key-dialer/internal/ipc"
	"github.com/Lexo0522/one-key-dialer/internal/model"
	"github.com/Lexo0522/one-key-dialer/internal/platform"
	"github.com/Lexo0522/one-key-dialer/internal/storage"
	"github.com/wailsapp/wails/v2"
	"github.com/wailsapp/wails/v2/pkg/options"
	"github.com/wailsapp/wails/v2/pkg/options/assetserver"
	"github.com/wailsapp/wails/v2/pkg/options/windows"
)

//go:embed all:frontend/dist
var assets embed.FS

// 代理与 UI 常量。
const (
	// uiInstanceMutex UI 进程单实例互斥体名。
	uiInstanceMutex = `Local\PPoEDialerUI`
	// agentWaitTimeout UI 启动时等待代理管道就绪的时长。
	agentWaitTimeout = 8 * time.Second
)

// 默认尺寸按「侧栏 208px + 主内容区不挤压」反推：980 宽时首页四张统计
// 卡片可排满一行、账号表格五列完整展开；760 高保证首页图表与统计卡
// 完整可见。屏幕上放不下时由 clampToWorkArea 收敛，不会超出工作区。
const (
	windowWidth  = 980
	windowHeight = 760
	// 最小尺寸对应「侧栏折叠为图标栏(56px) + 统计卡片两列 + 表格横向滚动」
	// 的下限；低于该宽度前端必然出现明显挤压，故从窗口层面直接限制。
	windowMinWidth  = 640
	windowMinHeight = 600
)

func main() {
	// IPC 活动管道名记录文件(规范名被幽灵管道占用时,代理会改用备选名并记录在此)
	ipc.NameFile = filepath.Join(platform.DataDir(), "ipc.pipe")

	args := os.Args[1:]
	for _, arg := range args {
		switch arg {
		case platform.AgentFlag, platform.AutoStartFlag:
			runAgent(args) // 自启动恒为代理模式
			return
		case platform.UIFlag:
			runUI()
			return
		}
	}
	// 无参数(双击/更新后重启):确保代理在运行,再开窗口
	runUI()
}

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

// runAgent 代理模式:装配全部服务并常驻,不创建任何窗口。
// 退出只能经由托盘「退出」/更新安装(ExitProgram)。
func runAgent(args []string) {
	if ipc.Probe() {
		// 已有存活代理(重复启动/重复自启动):本进程让位退出
		return
	}
	if containsArg(args, platform.AutoStartFlag) {
		// 登录高峰期错峰启动(与旧版行为一致)
		time.Sleep(time.Duration(platform.AutoStartDelayMs) * time.Millisecond)
		setAutoStartLaunch()
	}

	app := NewApp()
	app.startup(context.Background())
	// 装配完成后常驻;退出经由 ExitProgram(内部 os.Exit)
	select {}
}

// runUI UI 模式:确保代理运行 → 连接管道 → 打开 Wails 窗口。
// 窗口关闭即进程退出,内存立刻归还;代理继续驻留托盘。
func runUI() {
	// UI 单实例:已有窗口在跑时,请代理唤出既有窗口,本进程让位
	acquired, release := platform.AcquireSingleInstance(uiInstanceMutex)
	if !acquired {
		focusExistingUI()
		return
	}
	defer release()

	// 代理未运行则拉起;失败只能弹原生框告知(GUI 进程没有托盘可依赖)
	if err := ensureAgent(); err != nil {
		platform.ShowErrorBox(appTitle(), i18n.T("ui.agentUnavailable"))
		return
	}
	client, err := ipc.Dial(agentWaitTimeout)
	if err != nil {
		platform.ShowErrorBox(appTitle(), i18n.T("ui.agentUnavailable"))
		return
	}

	ui := newUIApp(client)
	width, height := clampToWorkArea(windowWidth, windowHeight)

	err = wails.Run(&options.App{
		Title:            appTitle() + " " + model.Display(),
		Width:            width,
		Height:           height,
		MinWidth:         windowMinWidth,
		MinHeight:        windowMinHeight,
		AssetServer:      &assetserver.Options{Assets: assets},
		BackgroundColour: &options.RGBA{R: 248, G: 249, B: 250, A: 1},
		OnStartup:        ui.startup,
		OnBeforeClose:    ui.beforeClose,
		OnShutdown:       ui.shutdown,
		Bind:             []interface{}{ui},
		Windows: &windows.Options{
			// 「低内存渲染」:CPU 软渲染,少一个 GPU 子进程
			WebviewGpuIsDisabled: readLowMemRender(),
		},
	})
	if err != nil {
		println("Error:", err.Error())
	}
	// wails.Run 返回 = 窗口已关闭 = UI 进程结束;代理不受影响
}

// ensureAgent 代理未在运行时拉起一个并等待管道就绪。
func ensureAgent() error {
	if ipc.Probe() {
		return nil
	}
	exe, err := currentExe()
	if err != nil {
		return err
	}
	if err := platform.LaunchDetached([]string{exe, platform.AgentFlag}, dirOf(exe)); err != nil {
		return err
	}
	deadline := time.Now().Add(agentWaitTimeout)
	for time.Now().Before(deadline) {
		if ipc.Probe() {
			return nil
		}
		time.Sleep(150 * time.Millisecond)
	}
	return fmt.Errorf("agent pipe not ready within %s", agentWaitTimeout)
}

// focusExistingUI 已有 UI 实例时经代理唤出其窗口(托盘「显示窗口」的等价操作)。
func focusExistingUI() {
	if !ipc.Probe() {
		return
	}
	client, err := ipc.Dial(2 * time.Second)
	if err != nil {
		return
	}
	_, _ = client.CallTimeout(2*time.Second, "ShowWindow")
	client.Close()
}

// readLowMemRender 窗口创建前直接读 settings.json,决定是否禁用 GPU 渲染。
func readLowMemRender() bool {
	dir := platform.DataDir()
	store := &storage.SettingsStore{File: dir + string(os.PathSeparator) + "settings.json"}
	snap, err := store.Load()
	if err != nil || snap == nil {
		return false
	}
	return snap.LowMemRender
}

func dirOf(exe string) string {
	for i := len(exe) - 1; i >= 0; i-- {
		if exe[i] == '\\' || exe[i] == '/' {
			return exe[:i]
		}
	}
	return "."
}

func containsArg(args []string, flag string) bool {
	for _, a := range args {
		if a == flag {
			return true
		}
	}
	return false
}

// appTitle 窗口标题跟随界面语言（启动时探测一次，运行期切换不改变标题）。
func appTitle() string { return i18n.T("app.title") }
