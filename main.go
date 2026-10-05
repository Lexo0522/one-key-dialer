package main

import (
	"context"
	"embed"
	"fmt"
	"log"
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
	// uiMutexWait 获取单实例互斥体的有界等待:上一实例关闭后的收尾
	// 窗口期(WebView2 销毁)内拉起的新实例等旧实例退出后自然接位。
	uiMutexWait = 4 * time.Second
	// uiBootTimeout 启动看门狗:DOM 就绪的硬期限,超时视为 WebView2
	// 初始化永久卡死,自尽把恢复权交回代理的自愈链。
	uiBootTimeout = 20 * time.Second
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
	// UI 进程没有控制台,go-webview2 经标准 log 输出的诊断信息(典型:
	// 「WebView2 环境创建失败」——上一实例关闭后数秒内重启,其浏览器
	// 进程尚未释放用户数据目录锁)默认丢弃不可见。重定向到数据目录
	// ui_log.txt,「拉起失败/卡死」类问题才有追溯依据。
	if f, err := os.OpenFile(filepath.Join(platform.DataDir(), "ui_log.txt"),
		os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600); err == nil {
		defer f.Close()
		log.SetOutput(f)
	}

	// UI 单实例:已有窗口在跑时,请代理唤出既有窗口,本进程让位。
	// 互斥体被占时先区分两种情形:代理处有 UI 接入 → 健康 UI 在跑,
	// 立即让位唤窗;无接入 → 持有者是关闭后仍在收尾/卡死的旧实例
	// (管道已断,代理已把它剔除),有界等待其退出后自然接位,而不是
	// 被弹走空转一次。
	acquired, release := platform.AcquireSingleInstance(uiInstanceMutex, 0)
	if !acquired {
		if uiVisibleViaProxy() {
			focusExistingUI()
			return
		}
		acquired, release = platform.AcquireSingleInstance(uiInstanceMutex, uiMutexWait)
		if !acquired {
			focusExistingUI()
			return
		}
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

	// 启动看门狗:WebView2 环境创建与上一实例的浏览器进程回收竞争时可能
	// 永久卡死——窗口出不来,本进程却占着单实例互斥体和管道连接,把代理
	// 自愈链的后续拉起全部弹走。硬期限:DOM 一直未就绪则自尽,把恢复权
	// 交回代理的下一次拉起。
	go func() {
		select {
		case <-ui.bootDone:
		case <-time.After(uiBootTimeout):
			log.Printf("[UI] boot watchdog: dom ready not reached in %s, exiting", uiBootTimeout)
			os.Exit(1)
		}
	}()

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
		OnDomReady:       ui.domReady,
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
// 调 FocusWindow 而非 ShowWindow:本进程刚被互斥体弹走,说明确有实例存在,
// 代理只需广播唤窗;若调 ShowWindow 会重置自愈轮次并可能再拉起,极端情况下
// 「拉起→弹走→再拉起」递归成拉起风暴。
func focusExistingUI() {
	if !ipc.Probe() {
		return
	}
	client, err := ipc.Dial(2 * time.Second)
	if err != nil {
		return
	}
	_, _ = client.CallTimeout(2*time.Second, "FocusWindow")
	client.Close()
}

// uiVisibleViaProxy 经代理查询是否有 UI 进程接入(有接入即有窗口)。
// 用于区分互斥体的持有者是「健康 UI」还是「已断管道的垂死/卡死实例」。
func uiVisibleViaProxy() bool {
	if !ipc.Probe() {
		return false
	}
	client, err := ipc.Dial(2 * time.Second)
	if err != nil {
		return false
	}
	defer client.Close()
	raw, err := client.CallTimeout(2*time.Second, "IsWindowVisible")
	if err != nil {
		return false
	}
	return string(raw) == "true"
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
