package main

import (
	"context"
	"errors"
	"fmt"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/Lexo0522/one-key-dialer/internal/i18n"
	"github.com/Lexo0522/one-key-dialer/internal/ipc"
	"github.com/Lexo0522/one-key-dialer/internal/model"
	"github.com/Lexo0522/one-key-dialer/internal/platform"
	"github.com/Lexo0522/one-key-dialer/internal/service"
	"github.com/Lexo0522/one-key-dialer/internal/storage"
	"github.com/Lexo0522/one-key-dialer/internal/update"
)

// 事件名（前后端通信契约）
const (
	EvtLog      = "app:log"
	EvtStatus   = "app:status"
	EvtSpeed    = "app:speed"
	EvtUptime   = "app:uptime"
	EvtSettings = "app:settings"
	EvtUpdate   = "app:update"
	EvtNotify   = "app:notify"
	EvtLang     = "app:lang"
	EvtWifi     = "app:wifi"
	EvtDial     = "app:dial"
	EvtDiag     = "app:diag"
)

// 更新阶段标识：随 app:update 事件下发，前端据此决定弹窗形态与可用操作。
// 没有阶段号时，前端无法区分「下载」与「解压安装」，会把后者的进度当成下载进度渲染。
const (
	UpdateStageCheck    = "check"
	UpdateStageDownload = "download"
	UpdateStagePrepare  = "prepare"
	UpdateStageInstall  = "install"
)

// App 是 Wails 绑定门面：持有全部服务并把状态变化推送到前端。
type App struct {
	ctx context.Context

	dataDir   string
	logSvc    *service.LogService
	exec      *service.BackgroundExecutor
	settings  *service.SettingsManager
	autoStart *service.StartupService

	ras       *platform.RasModule
	orch      *service.DialOrchestrator
	lifecycle *service.DialLifecycle

	reconnect *service.AutoReconnectService
	monitor   *service.NetworkMonitorService
	diag      *service.Diagnostics
	sampler   *service.TrafficSampler

	// WiFi 与门户自动认证
	wifiSvc      *service.WifiService
	portalSvc    *service.PortalAuthService
	portalStore  *storage.PortalStore
	wifiPskStore *storage.WifiPskStore
	portalCredMu sync.Mutex
	portalCred   *model.PortalCredential
	wifiPskSsid  string
	wifiPsk      []byte

	// 宽带拨号凭据（broadband.json，DPAPI 保护）
	broadbandStore *storage.BroadbandStore
	bbCredMu       sync.Mutex
	bbCred         *model.BroadbandCredential

	updater *update.Module

	mu               sync.Mutex
	online           bool
	connectTimeMs    int64
	sysOnline        bool // 系统已联网(非本应用拨号,任意出口)——流量统计的放行依据
	sysConnectTimeMs int64
	sessionDown      int64
	sessionUp        int64
	downSpeed        int64
	upSpeed          int64
	pendingUser      string
	pendingPass      []byte

	updateMu   sync.Mutex
	updateBusy bool
	lastCheck  *update.CheckResult
	pendingPkg *update.VerifiedPackage
	cancelDl   *update.Cancel

	// langStop 关闭后终止系统语言轮询（构造时创建，shutdown 时关闭）。
	langStop chan struct{}

	// sysProbeStop 关闭后终止系统直连在线探测循环（构造时创建，shutdown 时关闭）。
	sysProbeStop chan struct{}

	// ipcMu 保护 ipcSrv 的读写:emit 高频运行期读 与 shutdown 置 nil 并发。
	ipcMu sync.Mutex
	// ipcSrv 代理进程的命名管道服务端（UI 进程按需接入）。
	ipcSrv *ipc.Server
	// memStop 关闭后终止周期性内存归还（构造时创建，shutdown 时关闭）。
	memStop chan struct{}

	// shutdownOnce 保证停机序列只执行一次:托盘退出与 UI 转发的
	// ExitProgram 可能并发触发,重复 shutdown 会 close 已关闭的通道。
	shutdownOnce sync.Once

	// showMu 保护「显示窗口」的自愈状态:sys:show 回执与兜底拉起。
	showMu      sync.Mutex
	lastShowAck time.Time // UI 最近一次窗口浮现回执(含新 UI 启动回执)
	lastShowReq time.Time // 最近一次「显示窗口」请求时刻
	lastSpawnAt time.Time // 最近一次兜底拉起 UI 时刻(间隔闸防拉起风暴)
	showRound   int       // 当前请求已推进的自愈轮次

	// 诊断与拨号结果结构化回显（app_diag.go）
	diagBusy      atomic.Bool
	dialWaitersMu sync.Mutex
	dialWaiters   []chan DialResultPayload
}

// NewApp 构造应用门面。
func NewApp() *App {
	return &App{langStop: make(chan struct{}), memStop: make(chan struct{}), sysProbeStop: make(chan struct{})}
}

// ============================ 生命周期 ============================

// startup 装配全部服务（代理进程模式:无 Wails 窗口,由 main 在拉起代理时调用）。
func (a *App) startup(ctx context.Context) {
	a.ctx = ctx
	a.dataDir = platform.DataDir()

	warn := func(msg string) { a.logSvc.Warning(msg) }

	a.logSvc = service.NewLogService(filepath.Join(a.dataDir, "pppoe_log.txt"))
	a.logSvc.AttachLineSink(func(line service.LogLine) { a.emit(EvtLog, line) })

	a.exec = service.NewBackgroundExecutor()
	a.exec.SetErrorReporter(func(err error) {
		a.logSvc.Error(i18n.T("task.error") + ": " + err.Error())
	})

	settingsStore := &storage.SettingsStore{File: filepath.Join(a.dataDir, "settings.json")}
	a.settings = service.NewSettingsManager(settingsStore, a.exec, warn)

	a.broadbandStore = storage.NewBroadbandStore(filepath.Join(a.dataDir, "broadband.json"), storage.DpapiSecretProtector{})

	a.autoStart = service.NewStartupService(a.logSvc)
	a.sampler = service.NewTrafficSampler(warn)

	a.ras = platform.NewRasModule(model.ConnectionName, platform.PhonebookFile())
	a.lifecycle = &service.DialLifecycle{}

	// 1) 设置 → 2) 凭据 → 3) 服务 → 4) 自检
	loaded := a.settings.LoadFromDisk()
	a.applyProxySettings(loaded)
	a.loadBroadband()

	// 恢复上次选中的 PPPoE 设备；未保存过则保持自动探测
	if loaded.PppoeDeviceSet() {
		a.ras.SetPreferredDevice(&platform.DeviceHint{
			Port:         loaded.PppoePort,
			Device:       loaded.PppoeDevice,
			FromExisting: true,
		})
	}

	a.orch = service.NewDialOrchestrator(a.ras, dialView{a}, dialEnv{a}, a.lifecycle)

	a.reconnect = service.NewAutoReconnectService(
		func() bool { return a.lifecycle.IsBusy() },
		func() bool { return a.onlineProbe() },
		func() { a.orch.DialAuto() },
		func() { a.logSvc.Success(i18n.T("reconnect.detected") + " -> " + i18n.T("notify.recovered.body")) },
		func() {},
		a.logSvc)

	a.monitor = service.NewNetworkMonitorService(
		func() bool { return a.effectiveOnline() },
		func() (int64, int64) { return a.sampler.Sample() },
		func() int64 { return a.effectiveConnectTimeMs() },
		func(s service.SpeedSample) {
			a.mu.Lock()
			a.downSpeed, a.upSpeed = s.DownBytesPerSec, s.UpBytesPerSec
			a.sessionDown += s.DownDelta
			a.sessionUp += s.UpDelta
			a.mu.Unlock()
			a.emit(EvtSpeed, SpeedPayload{Down: s.DownBytesPerSec, Up: s.UpBytesPerSec})
		},
		func() { a.emit(EvtSpeed, SpeedPayload{Down: 0, Up: 0}) },
		a.refreshTray,
		func(seconds int64) { a.emit(EvtUptime, seconds) })

	a.diag = service.NewDiagnostics(a.ras, a.logSvc)

	// WiFi 与门户自动认证:独立凭据(portal.json)与首选 WiFi 密码(wifi.json)均 DPAPI 保护
	a.portalStore = storage.NewPortalStore(filepath.Join(a.dataDir, "portal.json"), storage.DpapiSecretProtector{})
	a.wifiPskStore = storage.NewWifiPskStore(filepath.Join(a.dataDir, "wifi.json"), storage.DpapiSecretProtector{})
	a.loadPortalCredential()
	a.loadWifiPsk()
	a.wifiSvc = service.NewWifiService(a.logSvc, func() { a.emit(EvtWifi, a.wifiStatus()) }, a.wifiPskFor)
	a.portalSvc = service.NewPortalAuthService(
		func() bool { return a.lifecycle.IsBusy() },
		func() service.PortalDetect { return service.DetectPortal(a.probeConfig()) },
		a.performPortalAuth,
		a.logSvc)
	if !platform.WlanAvailable() {
		a.logSvc.Info(i18n.T("wifi.hwUnavailable"))
	}

	cfg := update.Load(filepath.Join(a.dataDir, update.OverrideFileName), warn)
	a.updater = update.NewModule(platform.UpdatesDir(), cfg, func(msg string) { a.logSvc.Info(msg) },
		func() model.ProxyConfig { return a.settings.Current().ProxyConfig() })

	// 启动横幅
	a.logSvc.Success(i18n.Tf("log.appStarted", model.Display()))
	if autoStartLaunch {
		a.logSvc.Info(i18n.T("log.autostartLaunch"))
	}
	a.logSvc.Info(i18n.T("log.author"))
	a.logSvc.Info(i18n.T("log.repo"))

	a.exec.Submit(func() {
		service.NewStartupSelfCheck(a.logSvc, a.dataDir).Run()
		s := a.settings.Current()
		a.autoStart.EnsureHealthy(s.AutoStart)
		a.emit(EvtSettings, s)
	})

	a.monitor.Start()
	a.startSysProbe()
	a.startLangWatcher()
	if a.settings.Current().AutoReconnect {
		a.reconnect.Start(a.settings.Current().IntervalSeconds, true)
	}
	if loaded.PortalAuthEnabled {
		a.portalSvc.Start()
	}
	a.wifiSvc.Configure(loaded.WifiAutoConnect, loaded.WifiPreferredSsid)

	// 命名管道服务端:UI 进程按需接入;先于一切 UI 拉起动作,也先于托盘
	// ——重复实例在这里判定后直接退出,根本不创建托盘,消除「边建托盘
	// 边退出」的乱序窗口(stopTray 面对一个尚未装配的托盘)。
	handler := ipc.NewDispatcher(a).Handler()
	srv, err := ipc.NewServer(handler, func(int) { a.refreshTray() })
	if err != nil {
		if errors.Is(err, ipc.ErrAlreadyRunning) {
			// 已有代理实例:本进程不重复装配,直接退出（由 main 兜底）
			warn("ipc: agent already running")
			a.ExitProgram()
			return
		}
		a.logSvc.Error(i18n.Tf("log.appStarted", "ipc: "+err.Error()))
	} else {
		a.setIpcSrv(srv)
	}

	// 托盘
	initTray(a)

	// 启动静默检查更新
	if a.settings.Current().UpdateCheckEnabled {
		a.exec.Schedule(5*time.Second, func() { a.doCheckUpdate(false) })
	}

	// 代理进程内存紧致化:小堆 + 低 GC 目标 + 周期归还,
	// 保证托盘待机时私有工作集维持在 ~20MB 量级
	startMemoryKeeper(a.memStop, func() { a.logSvc.Flush() })

	// 代理模式没有窗口展示日志,启动段落立即落盘一次,
	// 保证「起不来/连不上」类问题在日志文件里可追溯
	a.logSvc.Flush()

	// 自启动且未勾选「启动最小化」:登录后自动拉起一次主窗口
	if autoStartLaunch && !a.settings.Current().StartMinimized {
		a.exec.Schedule(1*time.Second, a.ShowWindow)
	}
}

// shutdown 有序停机:摘托盘 → 断 IPC → 持久化 → 停服务 → 清内存密码。
// 幂等:托盘退出与 UI 转发的 ExitProgram 可能并发触发,只执行一次。
func (a *App) shutdown(ctx context.Context) {
	a.shutdownOnce.Do(func() { a.doShutdown(ctx) })
}

// doShutdown shutdown 的实际执行体(shutdownOnce 保护)。
func (a *App) doShutdown(ctx context.Context) {
	// 各阶段耗时留痕:停机卡顿时日志可定位是托盘、管道还是服务收尾。
	t0 := time.Now()
	mark := func(name string, at time.Time) string {
		return fmt.Sprintf("%s=%dms", name, time.Since(at).Milliseconds())
	}

	// 最先摘托盘图标并断开管道。二者都必须赶在 ExitProgram 的 8 秒硬退出
	// 之前完成:图标删除正常路径要经托盘消息循环异步落地(见 stopTray,
	// 消息循环失效时由兜底直删保证),此前排到停机末尾,拨号/服务收尾一旦
	// 拖满硬杀时限,进程会被杀在 NIM_DELETE 之前,任务栏残留进程已消失、
	// 右键无响应的幽灵图标;管道同理,晚一秒断开,UI 进程就多挂一秒。
	stopAt := time.Now()
	stopTray()
	markTray := mark("tray", stopAt)

	ipcAt := time.Now()
	if srv := a.takeIpcSrv(); srv != nil {
		srv.Close() // 断开 UI 客户端,它们会自行退出
	}
	markIpc := mark("ipc", ipcAt)

	// 先停语言轮询与内存看护，避免停机期间继续运行
	if a.langStop != nil {
		close(a.langStop)
		a.langStop = nil
	}
	if a.memStop != nil {
		close(a.memStop)
		a.memStop = nil
	}
	if a.sysProbeStop != nil {
		close(a.sysProbeStop)
		a.sysProbeStop = nil
	}
	a.settings.FlushPending()
	a.logSvc.Flush()

	setAt := time.Now()
	a.reconnect.Stop()
	a.portalSvc.Stop()
	a.wifiSvc.Stop()
	a.monitor.Stop()
	markServices := mark("services", setAt)

	orchAt := time.Now()
	a.orch.Shutdown(3 * time.Second)
	markOrch := mark("orch", orchAt)

	execAt := time.Now()
	a.exec.Shutdown(2 * time.Second)
	markExec := mark("exec", execAt)

	a.bbCredMu.Lock()
	a.bbCred.ClearPassword()
	a.portalCredMu.Lock()
	a.portalCred.ClearPassword()
	model.ClearBytes(a.wifiPsk)
	a.wifiPsk = nil
	a.portalCredMu.Unlock()
	a.clearPendingPassword()

	a.logSvc.Info(fmt.Sprintf("停机完成: %s %s %s %s %s 总计=%dms",
		markTray, markIpc, markServices, markOrch, markExec, time.Since(t0).Milliseconds()))
	a.logSvc.Flush()
}

// domReady 前端就绪回调：留空实现是必要的——Wails 的 OnDomReady 需要一个
// 方法引用，而本项目不需要在 DOM 就绪时做额外初始化（Bootstrap 已按需拉数据）。
func (a *App) domReady(ctx context.Context) {}

// ============================ 前端入口 ============================

// Bootstrap 返回首帧所需的全部状态。
func (a *App) Bootstrap() AppState {
	s := a.settings.Current()
	return AppState{
		Version:          model.Version(),
		DisplayVersion:   model.Display(),
		Settings:         s,
		Broadband:        a.broadbandView(),
		Online:           a.isOnline(),
		SysOnline:        a.isSysOnline(),
		Logs:             a.logSvc.Snapshot(),
		AutoStartEnabled: a.autoStart.IsEnabled(),
		Theme:            a.resolvedTheme(),
		Lang:             i18n.Lang(),
		DataDir:          a.dataDir,
		UpdatesDir:       platform.UpdatesDir(),
	}
}

// ============================ 界面语言 ============================

// SetUILang 设置界面语言："" / "auto" / "system" 表示跟随系统，zh / en 为显式覆盖。
// 只作用于当前进程（托盘菜单、通知、日志等后端文案），不写入配置文件；
// 前端自身文案由前端同步切换。返回生效语言。
func (a *App) SetUILang(lang string) string {
	i18n.SetLang(lang)
	refreshTrayLabels()
	a.emit(EvtLang, a.langPayload())
	return i18n.Lang()
}

// GetUILang 返回当前语言状态快照。
func (a *App) GetUILang() LangPayload { return a.langPayload() }

// langPayload 组装语言状态：生效语言 / 系统语言 / 是否跟随系统。
func (a *App) langPayload() LangPayload {
	return LangPayload{
		Lang:   i18n.Lang(),
		System: i18n.SystemLang(),
		Auto:   i18n.IsAuto(),
	}
}

// startLangWatcher 轮询系统语言：跟随模式下自动切换后端文案并通知前端，
// 使「系统切语言 → 界面跟着变」在运行期生效（无需重启）。
// Windows 会在显示语言变化时改写 GetUserDefaultUILanguage，故用轻量轮询探测；
// 显式覆盖语言时探测结果不生效，仅更新缓存。
func (a *App) startLangWatcher() {
	stop := a.langStop
	if stop == nil {
		return
	}
	go func() {
		ticker := time.NewTicker(2 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-stop:
				return
			case <-ticker.C:
				if !i18n.RefreshSystemLang() {
					continue
				}
				refreshTrayLabels()
				a.emit(EvtLang, a.langPayload())
			}
		}
	}()
}

// ============================ 设置 ============================

// SaveSettings 保存设置并重启相关服务。
func (a *App) SaveSettings(next model.Settings) {
	prev := a.settings.Current()
	a.settings.Update(next)
	a.emit(EvtSettings, a.settings.Current())

	if next.UITheme != prev.UITheme {
		// 主题热切换由前端完成，这里仅提示
		a.logSvc.Info(i18n.T("theme.liveHint"))
	}
	if next.AutoReconnect != prev.AutoReconnect || next.IntervalSeconds != prev.IntervalSeconds {
		a.applyAutoReconnect()
	}
	if proxySectionChanged(prev, next) {
		a.applyProxySettings(next)
	}
	if next.PortalAuthEnabled != prev.PortalAuthEnabled {
		a.applyPortalAuth()
	}
	if next.WifiAutoConnect != prev.WifiAutoConnect || next.WifiPreferredSsid != prev.WifiPreferredSsid {
		a.applyWifiAutoConnect()
	}
}

// applyProxySettings 记录代理出口状态（仅本应用 HTTP 请求生效，
// 不改系统设置）；后续探测/更新在发起请求时按当前设置实时取用。
func (a *App) applyProxySettings(s model.Settings) {
	pc := s.ProxyConfig()
	if pc.Enabled {
		a.logSvc.Info(i18n.Tf("proxy.enabled", pc.Summary()))
	} else {
		a.logSvc.Info(i18n.T("proxy.disabled"))
	}
}

// proxySectionChanged 判断两份设置的代理段是否发生变化。
func proxySectionChanged(a, b model.Settings) bool {
	return a.ProxyEnabled != b.ProxyEnabled ||
		a.ProxyType != b.ProxyType ||
		a.ProxyHost != b.ProxyHost ||
		a.ProxyPort != b.ProxyPort ||
		a.ProxyBypass != b.ProxyBypass
}

// SetAutoStart 注册 / 注销开机自启（以注册表为准）。
func (a *App) SetAutoStart(enabled bool) bool {
	ok := false
	if enabled {
		ok = a.autoStart.Enable()
	} else {
		ok = a.autoStart.Disable()
	}
	s := a.settings.Current()
	s.AutoStart = a.autoStart.IsEnabled()
	a.settings.Update(s)
	a.emit(EvtSettings, s)
	return ok
}

// ============================ 宽带账号 ============================

// GetBroadband 返回宽带拨号凭据视图（不含明文密码）。
func (a *App) GetBroadband() BroadbandCredentialDTO { return a.broadbandView() }

// SaveBroadband 保存宽带拨号凭据（DPAPI 落盘 broadband.json）。
// 同一账号且未填新密码时沿用旧密码。
func (a *App) SaveBroadband(username, password string) bool {
	username = strings.TrimSpace(username)
	a.bbCredMu.Lock()
	cred := model.NewBroadbandCredential(username, password)
	if username == a.bbCred.Username && password == "" && a.bbCred.HasPassword() {
		cred.SetPassword(a.bbCred.Password())
	}
	// 替换前清零旧凭据的密码字节：直接覆盖指针会让旧密码留在堆上直到 GC。
	// SetPassword 复制的是新密码，与旧对象的字节数组无关，清零旧对象是安全的。
	a.bbCred.ClearPassword()
	a.bbCred = cred
	a.bbCredMu.Unlock()
	a.exec.Submit(func() {
		if err := a.broadbandStore.Save(cred); err != nil {
			msg := i18n.Tf("broadband.saveFailed", err.Error())
			a.logSvc.Error(msg)
			// 落盘失败必须让用户看见：之前只记日志，前端无感知，
			// 用户会误以为已保存成功，下次启动凭据丢失还找不到原因。
			a.notify(i18n.T("broadband.saveFailedTitle"), msg, service.ToneError)
		} else {
			a.logSvc.Info(i18n.T("broadband.saved"))
		}
	})
	return true
}

// Dial 用已保存的宽带凭据拨号（密码全程不出后端）。
// 凭据未设置时交由预检层提示，返回 false 表示拨号未受理。
func (a *App) Dial() bool {
	username, password := a.broadbandCreds()
	a.setPending(username, string(password))
	model.ClearBytes(password)
	return a.orch.DialUser()
}

// ============================ 拨号 ============================

// Disconnect 用户断开。返回 false 表示未受理（忙）。
func (a *App) Disconnect() bool { return a.orch.DisconnectUser() }

// DiagListDevices 列出可选 PPPoE 设备，并标出当前生效项（current），
// 供设置页下拉框回显固定值。
func (a *App) DiagListDevices() []DeviceOption {
	hints := a.diag.ListDevices()
	cur := a.diag.CurrentDevice()
	out := make([]DeviceOption, 0, len(hints))
	for _, h := range hints {
		out = append(out, DeviceOption{
			Port:     h.Port,
			Device:   h.Device,
			Existing: h.FromExisting,
			Default:  h.Port == platform.DefaultDevice.Port && h.Device == platform.DefaultDevice.Device,
			Current:  cur != nil && h.Port == cur.Port && h.Device == cur.Device,
		})
	}
	// 兜底：极端情况下列表为空时给出内置默认设备并标记为当前项
	if len(out) == 0 {
		d := platform.DefaultDevice
		out = append(out, DeviceOption{Port: d.Port, Device: d.Device, Default: true, Current: true})
	}
	return out
}

// DiagSelectDevice 选择 PPPoE 设备；rewrite 为 true 时立即重写电话簿。
// 选择结果持久化到 settings.json，重启后仍是同一个值。
func (a *App) DiagSelectDevice(port, device string, rewrite bool) string {
	port = strings.TrimSpace(port)
	device = strings.TrimSpace(device)
	hint := &platform.DeviceHint{Port: port, Device: device, FromExisting: true}
	msg := a.diag.ApplyDevice(hint, rewrite)

	s := a.settings.Current()
	if s.PppoePort != port || s.PppoeDevice != device {
		a.logSvc.Info(i18n.Tf("device.saved", device, port))
		s.PppoePort = port
		s.PppoeDevice = device
		a.settings.Update(s)
		a.emit(EvtSettings, a.settings.Current())
	}
	return msg
}

// ============================ WiFi / 门户认证 ============================

// WifiStatus 返回当前无线状态与自动连接配置回显。
func (a *App) WifiStatus() WifiStatusDTO { return a.wifiStatus() }

// WifiAvailable 本机是否有可用无线网卡。
func (a *App) WifiAvailable() bool { return a.wifiSvc.Available() }

// WifiScan 扫描周边网络；force 为 true 时触发刷新扫描（约 1-2 秒），
// 否则 3 秒缓存内直接返回上次结果。
func (a *App) WifiScan(force bool) []WifiNetworkDTO {
	nets := a.wifiSvc.Scan(force)
	out := make([]WifiNetworkDTO, 0, len(nets))
	for _, n := range nets {
		out = append(out, WifiNetworkDTO{
			Ssid:          n.Ssid,
			SignalQuality: n.SignalQuality,
			Secured:       n.Secured,
			Connected:     n.Connected,
			HasProfile:    n.HasProfile,
			Auth:          n.Auth,
		})
	}
	return out
}

// WifiConnect 连接 WiFi（异步：提交后台执行并立即返回受理结果，
// 连接耗时最长约 15 秒，进度与结果经 app:wifi 事件与日志回报）。
// 密码连接成功后保存 PSK（DPAPI），供自动连接复用。
func (a *App) WifiConnect(ssid, password string) bool {
	if strings.TrimSpace(ssid) == "" || !a.wifiSvc.Available() {
		return false
	}
	// 忙态同步拒绝：受理却不执行只会让前端拿着「连接中」空等
	if a.wifiSvc.IsBusy() {
		return false
	}
	// 连接自带 15 秒轮询，走通用并发；不进 SubmitLong 的单工队列，
	// 否则会排在 60 秒级诊断后面，前端长时间看不到任何进展。
	a.exec.Submit(func() {
		if err := a.wifiSvc.Connect(ssid, password); err != nil {
			return
		}
		if password != "" {
			a.storeWifiPsk(ssid, password)
		}
	})
	return true
}

// WifiDisconnect 断开当前无线连接。
func (a *App) WifiDisconnect() bool { return a.wifiSvc.Disconnect() == nil }

// GetPortalCredential 返回门户认证凭据视图（不含明文）。
func (a *App) GetPortalCredential() PortalCredentialDTO {
	a.portalCredMu.Lock()
	defer a.portalCredMu.Unlock()
	return PortalCredentialDTO{Username: a.portalCred.Username, HasPassword: a.portalCred.HasPassword()}
}

// SavePortalCredential 保存门户认证凭据。
// 同一账号且未填新密码时沿用旧密码（与账号页"留空沿用"一致）。
func (a *App) SavePortalCredential(username, password string) bool {
	username = strings.TrimSpace(username)
	a.portalCredMu.Lock()
	cred := model.NewPortalCredential(username, password)
	if username == a.portalCred.Username && password == "" && a.portalCred.HasPassword() {
		cred.SetPassword(a.portalCred.Password())
	}
	a.portalCred = cred
	a.portalCredMu.Unlock()
	a.exec.Submit(func() {
		if err := a.portalStore.Save(cred); err != nil {
			msg := i18n.Tf("portal.credSaveFailed", err.Error())
			a.logSvc.Error(msg)
			// 落盘失败必须让用户看见：之前只记日志，前端无感知，
			// 用户会误以为已保存成功。
			a.notify(i18n.T("portal.credSaveFailedTitle"), msg, service.ToneError)
		} else {
			a.logSvc.Info(i18n.T("portal.credSaved"))
		}
	})
	return true
}

// TestPortalAuth 手动执行一次完整认证流程（检测门户 → 提交 → 复验），
// 返回分步明细供前端回显。同步执行,总耗时约 3-10 秒。
func (a *App) TestPortalAuth() PortalTestResult {
	cfg := a.probeConfig()
	var sb strings.Builder

	d1 := service.DetectPortal(cfg)
	if !d1.Portal {
		sb.WriteString(i18n.T("portal.testNoPortal"))
		sb.WriteString("\nHTTP " + d1.Detail)
		return PortalTestResult{Ok: false, Detail: sb.String()}
	}
	sb.WriteString(i18n.Tf("portal.detected", d1.PortalURL))

	out := a.performPortalAuth(d1.PortalURL)
	sb.WriteString("\n" + i18n.Tf("portal.testSubmit", out.Status))
	if out.Detail != "" {
		sb.WriteString(" | " + out.Detail)
	}

	d2 := service.DetectPortal(cfg)
	if out.Success && !d2.Portal {
		sb.WriteString("\n" + i18n.T("portal.testOk"))
		return PortalTestResult{Ok: true, Detail: sb.String()}
	}
	sb.WriteString("\n" + i18n.T("portal.testFailed"))
	return PortalTestResult{Ok: false, Detail: sb.String()}
}

// ============================ 网站测速 / IP 信息 ============================

// SiteLatencyCheck 批量网站测速（直连、站点间并发，单站 4 秒超时）。
// urls 为前端配置的测试点（settings.speedSites），结果按入参顺序返回。
func (a *App) SiteLatencyCheck(urls []string) []SiteLatencyDTO {
	res := service.TestSiteLatency(urls)
	out := make([]SiteLatencyDTO, 0, len(res))
	for _, r := range res {
		out = append(out, SiteLatencyDTO{Url: r.Url, LatencyMs: r.LatencyMs})
	}
	return out
}

// GetIPInfo 查询公网出口 IP 与运营商归属（直连，主源失败自动回退）。
func (a *App) GetIPInfo() IPInfoDTO {
	info := service.FetchIPInfo()
	return IPInfoDTO{
		Ip:         info.Ip,
		Country:    info.Country,
		RegionName: info.RegionName,
		City:       info.City,
		Isp:        info.Isp,
		As:         info.As,
		Timezone:   info.Timezone,
		LocalIp:    info.LocalIp,
	}
}

// ============================ 窗口 / 退出 ============================

// setIpcSrv 记录管道服务端(startup 一次性写入)。
func (a *App) setIpcSrv(s *ipc.Server) {
	a.ipcMu.Lock()
	a.ipcSrv = s
	a.ipcMu.Unlock()
}

// takeIpcSrv 取走并清空管道服务端(shutdown 用,幂等)。
func (a *App) takeIpcSrv() *ipc.Server {
	a.ipcMu.Lock()
	srv := a.ipcSrv
	a.ipcSrv = nil
	a.ipcMu.Unlock()
	return srv
}

// ipcSnapshot 取当前管道服务端快照(可能为 nil)。
func (a *App) ipcSnapshot() *ipc.Server {
	a.ipcMu.Lock()
	srv := a.ipcSrv
	a.ipcMu.Unlock()
	return srv
}
