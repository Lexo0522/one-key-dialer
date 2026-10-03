package main

import (
	"context"
	"errors"
	"path/filepath"
	"strings"
	"sync"
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
	EvtHistory  = "app:history"
	EvtAccounts = "app:accounts"
	EvtSettings = "app:settings"
	EvtDiag     = "app:diag"
	EvtUpdate   = "app:update"
	EvtNotify   = "app:notify"
	EvtLang     = "app:lang"
	EvtWifi     = "app:wifi"
)

// 更新阶段标识：随 app:update 事件下发，前端据此决定弹窗形态与可用操作。
// 没有阶段号时，前端无法区分「下载」与「解压安装」，会把后者的进度当成下载进度渲染。
const (
	UpdateStageCheck    = "check"
	UpdateStageDownload = "download"
	UpdateStagePrepare  = "prepare"
	UpdateStageInstall  = "install"
)

// AccountDTO 前端账号视图（密码仅在用户刚输入或显式导出时回传）。
type AccountDTO struct {
	Name        string `json:"name"`
	Username    string `json:"username"`
	Password    string `json:"password,omitempty"`
	Remark      string `json:"remark"`
	HasPassword bool   `json:"hasPassword"`
}

// AppState 前端首帧需要的全部状态。
type AppState struct {
	Version          string                `json:"version"`
	DisplayVersion   string                `json:"displayVersion"`
	Settings         model.Settings        `json:"settings"`
	Accounts         []AccountDTO          `json:"accounts"`
	CurrentIndex     int                   `json:"currentIndex"`
	Online           bool                  `json:"online"`
	History          []model.HistoryRecord `json:"history"`
	Logs             []service.LogLine     `json:"logs"`
	AutoStartEnabled bool                  `json:"autoStartEnabled"`
	Theme            string                `json:"theme"`
	Lang             string                `json:"lang"`
	DataDir          string                `json:"dataDir"`
	UpdatesDir       string                `json:"updatesDir"`
}

// StatusPayload 连接状态事件负载。
type StatusPayload struct {
	Online bool   `json:"online"`
	Phase  string `json:"phase"`
}

// SpeedPayload 速率事件负载。
type SpeedPayload struct {
	Down int64 `json:"down"`
	Up   int64 `json:"up"`
}

// LangPayload 界面语言状态：生效语言 / 系统语言 / 是否跟随系统。
type LangPayload struct {
	Lang   string `json:"lang"`
	System string `json:"system"`
	Auto   bool   `json:"auto"`
}

// WifiNetworkDTO 前端 WiFi 扫描行。
type WifiNetworkDTO struct {
	Ssid          string `json:"ssid"`
	SignalQuality int    `json:"signalQuality"` // 0-100
	Secured       bool   `json:"secured"`
	Connected     bool   `json:"connected"`
	HasProfile    bool   `json:"hasProfile"`
	Auth          string `json:"auth"`
}

// WifiStatusDTO 前端 WiFi 状态（含可用性与自动连接配置回显）。
// Phase: idle/connecting/connected/disconnecting。
type WifiStatusDTO struct {
	Available     bool   `json:"available"`
	Connected     bool   `json:"connected"`
	Ssid          string `json:"ssid"`
	SignalQuality int    `json:"signalQuality"`
	Phase         string `json:"phase"`
	AutoConnect   bool   `json:"autoConnect"`
	PreferredSsid string `json:"preferredSsid"`
}

// PortalCredentialDTO 门户认证凭据视图（明文密码不出后端）。
type PortalCredentialDTO struct {
	Username    string `json:"username"`
	HasPassword bool   `json:"hasPassword"`
}

// PortalTestResult 手动测试门户认证的结果（Detail 为多行分步明细）。
type PortalTestResult struct {
	Ok     bool   `json:"ok"`
	Detail string `json:"detail"`
}

// UpdatePayload 更新流程事件负载。
// Kind: checking | result | status | progress | canceled | error | done | installing。
// Stage: UpdateStage* 之一，用于区分同一条进度通道上的不同阶段。
type UpdatePayload struct {
	Kind            string `json:"kind"`
	Stage           string `json:"stage,omitempty"`
	Message         string `json:"message"`
	Title           string `json:"title"`
	Body            string `json:"body"`
	Tray            bool   `json:"tray"`
	Downloaded      int64  `json:"downloaded"`
	Total           int64  `json:"total"`
	UpdateAvailable bool   `json:"updateAvailable"`
	CanInstall      bool   `json:"canInstall"`
	AssetName       string `json:"assetName"`
	AssetSize       int64  `json:"assetSize"`
	ReleaseURL      string `json:"releaseUrl"`
	Path            string `json:"path"`
}

// App 是 Wails 绑定门面：持有全部服务并把状态变化推送到前端。
type App struct {
	ctx context.Context

	dataDir    string
	logSvc     *service.LogService
	exec       *service.BackgroundExecutor
	settings   *service.SettingsManager
	accounts   *service.AccountSession
	historySvc *service.HistoryService
	autoStart  *service.StartupService

	ras       *platform.RasModule
	orch      *service.DialOrchestrator
	lifecycle *service.DialLifecycle
	stats     *service.DialStats

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

	updater *update.Module

	mu            sync.Mutex
	online        bool
	connectTimeMs int64
	sessionDown   int64
	sessionUp     int64
	baseDown      int64
	baseUp        int64
	downSpeed     int64
	upSpeed       int64
	lastProbe     *model.ProbeOutcome
	pendingUser   string
	pendingPass   []byte

	updateMu   sync.Mutex
	updateBusy bool
	lastCheck  *update.CheckResult
	pendingPkg *update.VerifiedPackage
	cancelDl   *update.Cancel

	// langStop 关闭后终止系统语言轮询（构造时创建，shutdown 时关闭）。
	langStop chan struct{}

	// ipcSrv 代理进程的命名管道服务端（UI 进程按需接入）。
	ipcSrv *ipc.Server
	// memStop 关闭后终止周期性内存归还（构造时创建，shutdown 时关闭）。
	memStop chan struct{}
}

// NewApp 构造应用门面。
func NewApp() *App {
	return &App{langStop: make(chan struct{}), memStop: make(chan struct{})}
}

// ============================ 生命周期 ============================

// startup 装配全部服务（代理进程模式:无 Wails 窗口,由 main 在拉起代理时调用）。
func (a *App) startup(ctx context.Context) {
	a.ctx = ctx
	a.dataDir = platform.DataDir()

	warn := func(msg string) { a.logSvc.Warning(msg) }
	errSink := func(msg string) { a.logSvc.Error(msg) }

	a.logSvc = service.NewLogService(filepath.Join(a.dataDir, "pppoe_log.txt"))
	a.logSvc.AttachLineSink(func(line service.LogLine) { a.emit(EvtLog, line) })

	a.exec = service.NewBackgroundExecutor()
	a.exec.SetErrorReporter(func(err error) {
		a.logSvc.Error(i18n.T("task.error") + ": " + err.Error())
	})

	settingsStore := &storage.SettingsStore{File: filepath.Join(a.dataDir, "settings.json")}
	a.settings = service.NewSettingsManager(settingsStore, a.exec, warn)

	accountStore := storage.NewAccountStore(filepath.Join(a.dataDir, "accounts.json"), storage.DpapiSecretProtector{})
	a.accounts = service.NewAccountSession(accountStore, a.exec, warn, errSink)

	historyStore := &storage.HistoryStore{File: filepath.Join(a.dataDir, "history.json")}
	a.historySvc = service.NewHistoryService(historyStore, warn)
	a.historySvc.AttachAddSink(func(r model.HistoryRecord) { a.emit(EvtHistory, r) })

	a.autoStart = service.NewStartupService(a.logSvc)
	a.sampler = service.NewTrafficSampler(warn)

	a.ras = platform.NewRasModule(model.ConnectionName, platform.PhonebookFile())
	a.lifecycle = &service.DialLifecycle{}
	a.stats = &service.DialStats{}

	// 1) 设置 → 2) 账号 → 3) 服务 → 4) 自检
	loaded := a.settings.LoadFromDisk()
	a.applyProxySettings(loaded)
	a.accounts.Load(loaded.AccountIndex)

	// 恢复上次选中的 PPPoE 设备；未保存过则保持自动探测
	if loaded.PppoeDeviceSet() {
		a.ras.SetPreferredDevice(&platform.DeviceHint{
			Port:         loaded.PppoePort,
			Device:       loaded.PppoeDevice,
			FromExisting: true,
		})
	}

	a.orch = service.NewDialOrchestrator(a.ras, dialView{a}, dialEnv{a}, a.lifecycle, a.stats)

	a.reconnect = service.NewAutoReconnectService(
		func() bool { return a.lifecycle.IsBusy() },
		func() bool { return a.onlineProbe() },
		func() { a.orch.DialAuto() },
		func() { a.logSvc.Success(i18n.T("reconnect.detected") + " -> " + i18n.T("notify.recovered.body")) },
		func() {},
		a.logSvc)

	a.monitor = service.NewNetworkMonitorService(
		func() bool { return a.isOnline() },
		func() (int64, int64) { return a.sampler.Sample() },
		func() int64 { return a.connectTimeMs },
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

	a.diag = service.NewDiagnostics(a.ras, a.diagContext, func(line string) { a.emit(EvtDiag, line) }, a.logSvc)

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
		if removed, err := platform.RemoveLegacyAppBinary(); removed {
			if err != nil {
				a.logSvc.Warning("remove legacy binary " + model.LegacyAppName + ": " + err.Error())
			} else {
				a.logSvc.Info("removed legacy binary " + model.LegacyAppName)
			}
		}
		cfgProbe := a.probeConfig()
		a.logSvc.Info(i18n.Tf("selfcheck.probeConfig", cfgProbe.Summary()))
		s := a.settings.Current()
		a.autoStart.EnsureHealthy(s.AutoStart)
		a.emit(EvtSettings, s)
	})

	a.monitor.Start()
	a.startLangWatcher()
	if a.settings.Current().AutoReconnect {
		a.reconnect.Start(a.settings.Current().IntervalSeconds, true)
	}
	if loaded.PortalAuthEnabled {
		a.portalSvc.Start()
	}
	a.wifiSvc.Configure(loaded.WifiAutoConnect, loaded.WifiPreferredSsid)

	// 托盘
	initTray(a)

	// 启动静默检查更新
	if a.settings.Current().UpdateCheckEnabled {
		a.exec.Schedule(5*time.Second, func() { a.doCheckUpdate(false) })
	}

	// 命名管道服务端:UI 进程按需接入;先于一切 UI 拉起动作
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
		a.ipcSrv = srv
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

// shutdown 有序停机:持久化 → 停服务 → 停 IPC → 清内存密码 → 退托盘。
func (a *App) shutdown(ctx context.Context) {
	// 先停语言轮询与内存看护，避免停机期间继续运行
	if a.langStop != nil {
		close(a.langStop)
		a.langStop = nil
	}
	if a.memStop != nil {
		close(a.memStop)
		a.memStop = nil
	}
	a.settings.FlushPending()
	a.accounts.Save()
	a.historySvc.SaveIfDirty()
	a.logSvc.Flush()

	a.reconnect.Stop()
	a.portalSvc.Stop()
	a.wifiSvc.Stop()
	a.monitor.Stop()
	a.orch.Shutdown(3 * time.Second)
	a.exec.Shutdown(2 * time.Second)

	if a.ipcSrv != nil {
		a.ipcSrv.Close() // 断开 UI 客户端,它们会自行退出
		a.ipcSrv = nil
	}

	a.accounts.ClearPasswordsInMemory()
	a.portalCredMu.Lock()
	if a.portalCred != nil {
		a.portalCred.ClearPassword()
	}
	model.ClearBytes(a.wifiPsk)
	a.wifiPsk = nil
	a.portalCredMu.Unlock()
	a.clearPendingPassword()
	stopTray()
	a.logSvc.Flush()
}

// domReady 前端就绪回调。
func (a *App) domReady(ctx context.Context) {}

// ============================ 前端入口 ============================

// Bootstrap 返回首帧所需的全部状态。
func (a *App) Bootstrap() AppState {
	s := a.settings.Current()
	return AppState{
		Version:          model.Version(),
		DisplayVersion:   model.Display(),
		Settings:         s,
		Accounts:         a.accountDTOs(),
		CurrentIndex:     a.accounts.CurrentIndex(),
		Online:           a.isOnline(),
		History:          a.historySvc.Records(),
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

// ============================ 账号 ============================

// GetAccounts 返回账号列表（不含密码）。
func (a *App) GetAccounts() []AccountDTO { return a.accountDTOs() }

// SaveAccounts 用前端提供的完整列表替换账号并落盘。
// 前端快照不含明文密码：某行未填新密码但声明"已保存"（hasPassword）时，
// 按账号名从旧账号继承密码，避免整列替换把已存密码抹掉。
func (a *App) SaveAccounts(rows []AccountDTO) {
	list := make([]*model.Account, 0, len(rows))
	for i, r := range rows {
		acc := model.NewAccount(r.Name, r.Username, r.Password, r.Remark)
		if r.Password == "" && r.HasPassword {
			if pw := a.accounts.PasswordForAccount(r.Username, i); len(pw) > 0 {
				acc.SetPasswordBytes(pw)
				model.ClearBytes(pw)
			}
		}
		list = append(list, acc)
	}
	a.accounts.ApplyEdits(list)
	a.accounts.SaveInBackground()
	a.clampAndEmit()
}

// SwitchAccount 切换当前账号（在线时先断开再用新账号重拨）。
func (a *App) SwitchAccount(index int) {
	prev := a.accounts.CurrentIndex()
	a.accounts.SetCurrentIndex(index)
	a.settings.Update(a.settings.Current().WithAccountIndex(a.accounts.CurrentIndex()))
	a.clampAndEmit()
	if prev == a.accounts.CurrentIndex() {
		return
	}
	a.logSvc.Info(i18n.Tf("account.switched", a.accounts.CurrentName()))
	if a.isOnline() {
		a.orch.RedialAfterDisconnect()
	}
}

// DialCurrentAccount 用当前账号已保存的凭据拨号（密码全程不出后端）。
// 账号未设置密码时交由预检层提示，返回 false 表示拨号未受理。
func (a *App) DialCurrentAccount() bool {
	if acc := a.accounts.CurrentOrNil(); acc != nil {
		a.setPending(acc.Username, acc.Password())
	}
	return a.orch.DialUser()
}

// ExportAccountsTo 导出账号到指定 CSV;withPassword 为 true 时含明文密码。
// 文件对话框由 UI 进程负责,代理只做落盘(pipe 方法)。
func (a *App) ExportAccountsTo(path string, withPassword bool) error {
	var accounts []*model.Account
	if withPassword {
		// 含密码导出必须取带密码快照，用后立即清零
		accounts = a.accounts.SnapshotWithPasswords()
		defer func() {
			for _, acc := range accounts {
				if acc != nil {
					acc.ClearPassword()
				}
			}
		}()
	} else {
		accounts = a.accounts.Accounts()
	}
	if err := storage.SaveCsv(path, accounts, withPassword); err != nil {
		a.logSvc.Error(i18n.Tf("export.failed", err.Error()))
		return err
	}
	a.logSvc.Success(i18n.T("export.ok"))
	return nil
}

// ImportAccountsFrom 从 CSV 追加导入账号（路径由 UI 进程的文件对话框提供）。
// 返回 -1 表示读取失败（前端静默不提示），>=0 为实际导入条数。
func (a *App) ImportAccountsFrom(path string) int {
	imported, err := storage.LoadCsv(path)
	if err != nil {
		a.logSvc.Error(i18n.Tf("import.failed", err.Error()))
		return -1
	}
	// 导入前必须取带密码快照：Accounts() 是无密码快照，
	// 直接整列 ApplyEdits 会把所有已存密码抹掉。
	list := a.accounts.SnapshotWithPasswords()
	list = append(list, imported...)
	a.accounts.ApplyEdits(list)
	a.accounts.SaveInBackground()
	a.clampAndEmit()
	a.logSvc.Success(i18n.T("import.ok"))
	return len(imported)
}

// ============================ 拨号 ============================

// Dial 用户拨号（密码由前端一次性传入，用完即清零）。
// 返回 false 表示拨号未受理（忙/预检失败），前端据此复位按钮状态。
func (a *App) Dial(username, password string) bool {
	a.setPending(username, password)
	return a.orch.DialUser()
}

// Disconnect 用户断开。返回 false 表示未受理（忙）。
func (a *App) Disconnect() bool { return a.orch.DisconnectUser() }

// ============================ 历史 / 统计 ============================

// GetHistory 返回历史记录。
func (a *App) GetHistory() []model.HistoryRecord { return a.historySvc.Records() }

// ClearHistory 清空历史。
func (a *App) ClearHistory() {
	a.historySvc.Clear()
	a.logSvc.Info(i18n.T("history.cleared"))
}

// ExportHistoryTo 导出历史 CSV 到指定路径（路径由 UI 进程的文件对话框提供）。
func (a *App) ExportHistoryTo(path string) error {
	if err := a.historySvc.Export(path); err != nil {
		a.logSvc.Error(i18n.Tf("history.exportFailed", err.Error()))
		return err
	}
	a.logSvc.Success(i18n.Tf("history.exported", path))
	return nil
}

// GetStats 返回统计汇总。
func (a *App) GetStats() service.StatsSummary {
	summary := service.Summarize(a.historySvc.Records())
	a.logSvc.Info(i18n.Tf("stats.refreshed", summary.DialAttempts, summary.DialSuccess))
	return summary
}

// ============================ 网络探测 ============================

// ProbeResult 一次连通测试的结果。
type ProbeResult struct {
	OK    bool   `json:"ok"`
	Line  string `json:"line"`
	Mode  string `json:"mode"`
	Error string `json:"error"`
}

// TestConnectivity 执行一次连通测试（只探测、不拨号）。
func (a *App) TestConnectivity() ProbeResult {
	cfg := a.probeConfig()
	outcome := service.ConfirmDetailed(cfg, "manual-test")
	a.mu.Lock()
	a.lastProbe = &outcome
	a.mu.Unlock()
	return ProbeResult{OK: outcome.OK, Line: outcome.ShortLine(), Mode: cfg.Mode}
}

// GetProbeSummary 返回探测配置摘要。
func (a *App) GetProbeSummary() string { return a.probeConfig().Summary() }

// ============================ 诊断 ============================

// DiagAction 执行一个诊断动作（后台运行，输出通过 app:diag 事件流式推送）。
func (a *App) DiagAction(action string) bool {
	a.exec.SubmitLong(func() {
		switch action {
		case "ping":
			a.diag.Ping()
		case "ipconfig":
			a.diag.IPConfig()
		case "tracert":
			a.diag.TraceRoute()
		case "flushdns":
			a.diag.FlushDNS()
		case "status":
			a.diag.ConnectionReport()
		case "phonebook":
			a.diag.PhonebookReport()
		}
	})
	return true
}

// DeviceOption 可选择的 PPPoE 设备。
type DeviceOption struct {
	Port     string `json:"port"`
	Device   string `json:"device"`
	Existing bool   `json:"existing"`
	Default  bool   `json:"default"`
	Current  bool   `json:"current"`
}

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

// DiagRewritePhonebook 强制重写 RAS 电话簿条目。
func (a *App) DiagRewritePhonebook() string { return a.diag.RewritePhonebook() }

// DiagClear 通知前端清空输出区。
func (a *App) DiagClear() {}

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
	a.exec.SubmitLong(func() {
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
	if a.portalCred == nil {
		return PortalCredentialDTO{}
	}
	return PortalCredentialDTO{Username: a.portalCred.Username, HasPassword: a.portalCred.HasPassword()}
}

// SavePortalCredential 保存门户认证凭据。
// 同一账号且未填新密码时沿用旧密码（与账号页"留空沿用"一致）。
func (a *App) SavePortalCredential(username, password string) bool {
	username = strings.TrimSpace(username)
	a.portalCredMu.Lock()
	cred := model.NewPortalCredential(username, password)
	if a.portalCred != nil && username == a.portalCred.Username && password == "" && a.portalCred.HasPassword() {
		cred.SetPassword(a.portalCred.Password())
	}
	a.portalCred = cred
	a.portalCredMu.Unlock()
	a.exec.Submit(func() {
		if err := a.portalStore.Save(cred); err != nil {
			a.logSvc.Error(i18n.Tf("portal.credSaveFailed", err.Error()))
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

// ============================ 在线更新 ============================

// CheckUpdate 检查更新（结果通过 app:update 事件返回）。
func (a *App) CheckUpdate(interactive bool) {
	a.exec.Submit(func() { a.doCheckUpdate(interactive) })
}

func (a *App) doCheckUpdate(interactive bool) {
	if !a.updateMu.TryLock() {
		a.emit(EvtUpdate, UpdatePayload{Kind: "error", Stage: UpdateStageCheck, Message: i18n.T("update.busy")})
		return
	}
	defer a.updateMu.Unlock()
	a.emit(EvtUpdate, UpdatePayload{Kind: "checking", Stage: UpdateStageCheck, Message: i18n.T("update.checking")})

	result := a.updater.Check(model.Version())
	a.lastCheck = &result

	writable := platform.IsDirWritable(platform.InstallDir())
	canInstall := update.HasInstallableAsset(&result, writable)
	assetName := ""
	var assetSize int64
	if result.Release != nil {
		if p := result.Release.PreferredWindowsAsset(writable); p != nil {
			assetName = p.Name
			assetSize = p.SizeBytes
		}
	}
	payload := UpdatePayload{
		Kind:            "result",
		Stage:           UpdateStageCheck,
		Message:         result.Message,
		UpdateAvailable: result.UpdateAvailable,
		CanInstall:      canInstall,
		AssetName:       assetName,
		AssetSize:       assetSize,
		ReleaseURL:      result.ReleaseURL,
	}
	if !result.SourceOK {
		a.logSvc.Warning(result.Message)
	} else if result.UpdateAvailable {
		a.logSvc.Warning(strings.ReplaceAll(result.Message, "\n", " "))
	} else {
		a.logSvc.Success(result.Message)
	}
	if !interactive {
		if !result.UpdateAvailable {
			return
		}
		payload.Tray = !a.windowVisible()
	}
	a.emit(EvtUpdate, payload)
}

// DownloadUpdate 下载并校验更新包（进度通过 app:update 事件推送）。
func (a *App) DownloadUpdate() {
	a.updateMu.Lock()
	if a.updateBusy {
		a.updateMu.Unlock()
		a.emit(EvtUpdate, UpdatePayload{Kind: "error", Stage: UpdateStageDownload, Message: i18n.T("update.downloadBusy")})
		return
	}
	// 检查结果缺失时不能解引用：宁可拒绝下载也不能让 goroutine panic。
	result := a.lastCheck
	if result == nil || !result.UpdateAvailable || result.Release == nil {
		a.updateMu.Unlock()
		a.emit(EvtUpdate, UpdatePayload{Kind: "error", Stage: UpdateStageDownload, Message: i18n.T("update.noPackage")})
		return
	}
	a.updateBusy = true
	cancel := update.NewCancel()
	a.cancelDl = cancel
	a.updateMu.Unlock()

	progress := updateProgress{a: a, stage: UpdateStageDownload}
	a.exec.SubmitLong(func() {
		pkg, err := a.updater.DownloadWithFailover(*result, progress, cancel)
		a.updateMu.Lock()
		a.updateBusy = false
		a.cancelDl = nil
		a.updateMu.Unlock()
		if err != nil {
			// 用户主动取消不是失败，不能套用「下载失败」的错误文案与红色语气
			if errors.Is(err, update.ErrCancelled) {
				a.logSvc.Info(i18n.T("update.downloadCanceled"))
				a.emit(EvtUpdate, UpdatePayload{Kind: "canceled", Stage: UpdateStageDownload,
					Message: i18n.T("update.downloadCanceled")})
				return
			}
			a.logSvc.Error(i18n.Tf("update.downloadFailed", err.Error()))
			a.emit(EvtUpdate, UpdatePayload{Kind: "error", Stage: UpdateStageDownload,
				Message: i18n.Tf("update.downloadErrDlg", err.Error())})
			return
		}
		a.updateMu.Lock()
		a.pendingPkg = pkg
		a.updateMu.Unlock()
		a.logSvc.Success(i18n.Tf("update.verified", pkg.File))
		a.emit(EvtUpdate, UpdatePayload{Kind: "done", Stage: UpdateStageDownload, Path: pkg.File})
	})
}

// CancelUpdateDownload 取消正在进行的下载。
func (a *App) CancelUpdateDownload() {
	a.updateMu.Lock()
	c := a.cancelDl
	a.updateMu.Unlock()
	if c != nil {
		c.Cancel()
	}
}

// InstallUpdate 准备并启动安装；成功启动后退出程序。
func (a *App) InstallUpdate() {
	a.updateMu.Lock()
	// 准备/安装阶段同样要占位：否则连点两次会生成两个 staged 目录、
	// 覆盖同一个 apply_update.bat 并并行启动两个安装脚本。
	if a.updateBusy {
		a.updateMu.Unlock()
		a.emit(EvtUpdate, UpdatePayload{Kind: "error", Stage: UpdateStagePrepare, Message: i18n.T("update.installBusy")})
		return
	}
	pkg := a.pendingPkg
	if pkg == nil {
		a.updateMu.Unlock()
		a.emit(EvtUpdate, UpdatePayload{Kind: "error", Stage: UpdateStagePrepare, Message: i18n.T("update.noPackageFile")})
		return
	}
	a.updateBusy = true
	a.updateMu.Unlock()

	progress := updateProgress{a: a, stage: UpdateStagePrepare}
	a.exec.SubmitLong(func() {
		// 更新脚本需等全部相关进程退出后再覆盖 exe:代理自身 + 接入中的 UI 进程
		waitPIDs := a.uiWaitPIDs()
		prepared, err := a.updater.Prepare(pkg, progress, waitPIDs)
		a.updateMu.Lock()
		a.updateBusy = false
		a.updateMu.Unlock()
		if err != nil {
			a.logSvc.Error(i18n.Tf("update.prepareFailed", err.Error()))
			a.emit(EvtUpdate, UpdatePayload{Kind: "error", Stage: UpdateStagePrepare,
				Message: i18n.Tf("update.prepareFailed", err.Error())})
			return
		}
		a.logSvc.Info(i18n.Tf("update.applying", prepared.ApplyScript))
		a.flushBeforeUpdate()
		if !a.updater.LaunchInstall(prepared) {
			a.logSvc.Error(i18n.T("update.launchFailed"))
			a.emit(EvtUpdate, UpdatePayload{Kind: "error", Stage: UpdateStagePrepare,
				Message: i18n.T("update.launchFailedDlg")})
			return
		}
		a.emit(EvtUpdate, UpdatePayload{Kind: "installing", Stage: UpdateStageInstall})
		a.ExitProgram()
	})
}

// OpenReleasePage 在默认浏览器中打开发布页。
func (a *App) OpenReleasePage(url string) {
	if url == "" {
		url = model.GitHubURL + "/releases/latest"
	}
	platform.OpenInBrowser(url)
}

// ============================ 窗口 / 退出 ============================

// ShowWindow 显示主窗口（代理模式）:UI 进程在线时唤出既有窗口,
// 否则按需拉起一个新的 UI 进程。窗口的销毁即 UI 进程退出,内存随之释放。
func (a *App) ShowWindow() {
	if a.uiOnline() {
		a.ipcSrv.Broadcast(SysEventShow, nil)
		return
	}
	a.spawnUI()
}

// HideWindow 代理模式无窗口可隐藏:窗口归属 UI 进程（空操作,仅为方法面完整）。
func (a *App) HideWindow() {}

// IsWindowVisible UI 进程是否在线（有窗口即视为可见）。
func (a *App) IsWindowVisible() bool { return a.windowVisible() }

// ExitProgram 有序退出（托盘「退出」与更新安装前调用）。
// 关机路径绝不允许挂死:给 shutdown 5 秒硬超时,超时(托盘/管道/RAS
// 任一环节卡住)也保证进程一定退出,由 OS 回收其余资源。
func (a *App) ExitProgram() {
	go func() {
		done := make(chan struct{})
		go func() {
			defer close(done)
			defer func() { _ = recover() }()
			a.shutdown(a.ctx)
		}()
		select {
		case <-done:
		case <-time.After(5 * time.Second):
		}
		osExit(0)
	}()
}

// uiOnline 是否有 UI 进程接入。
func (a *App) uiOnline() bool { return a.ipcSrv != nil && a.ipcSrv.ClientCount() > 0 }

// uiWaitPIDs 更新脚本需要等待退出的全部进程:代理自身 + 接入中的 UI 进程。
func (a *App) uiWaitPIDs() []int {
	if a.ipcSrv == nil {
		return nil
	}
	return a.ipcSrv.ClientPIDs()
}

// spawnUI 按需拉起 UI 进程（同目录同一 exe,无参数即 UI 模式）。
func (a *App) spawnUI() {
	exe, err := currentExe()
	if err != nil {
		a.logSvc.Error("spawn ui: " + err.Error())
		return
	}
	a.logSvc.Info("launch ui: " + exe)
	// 必须走 LaunchGUI:LaunchDetached 的 CREATE_NO_WINDOW 会让
	// UI 进程的主窗口保持隐藏,窗口永远显示不出来。
	if err := platform.LaunchGUI([]string{exe}, filepath.Dir(exe)); err != nil {
		a.logSvc.Error("spawn ui: " + err.Error())
	}
}
