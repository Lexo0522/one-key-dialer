package main

import (
	"context"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/Lexo0522/one-key-dialer/internal/i18n"
	"github.com/Lexo0522/one-key-dialer/internal/model"
	"github.com/Lexo0522/one-key-dialer/internal/platform"
	"github.com/Lexo0522/one-key-dialer/internal/service"
	"github.com/Lexo0522/one-key-dialer/internal/storage"
	"github.com/Lexo0522/one-key-dialer/internal/update"
	"github.com/wailsapp/wails/v2/pkg/runtime"
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

// UpdatePayload 更新流程事件负载。
type UpdatePayload struct {
	Kind            string `json:"kind"` // checking | result | status | progress | error | done
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
	schedule  *service.ScheduleService
	monitor   *service.NetworkMonitorService
	diag      *service.Diagnostics
	sampler   *service.TrafficSampler

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
}

// NewApp 构造应用门面。
func NewApp() *App { return &App{} }

// ============================ 生命周期 ============================

// startup 在 Wails 启动回调里装配全部服务。
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
	a.accounts.Load(loaded.AccountIndex)

	a.orch = service.NewDialOrchestrator(a.ras, dialView{a}, dialEnv{a}, a.lifecycle, a.stats)

	a.reconnect = service.NewAutoReconnectService(
		func() bool { return a.lifecycle.IsBusy() },
		func() bool { return a.onlineProbe() },
		func() { a.orch.DialAuto() },
		func() { a.logSvc.Success(i18n.T("reconnect.detected") + " -> " + i18n.T("notify.recovered.body")) },
		func() {},
		a.logSvc)

	a.schedule = service.NewScheduleService(
		func() bool { return a.settings.Current().ScheduledDial },
		func() bool { return a.settings.Current().ScheduledDisconnect },
		func() int { return a.settings.Current().ScheduledDialHour },
		func() int { return a.settings.Current().ScheduledDialMinute },
		func() int { return a.settings.Current().ScheduledDisconnectHour },
		func() int { return a.settings.Current().ScheduledDisconnectMinute },
		func() bool { return a.isOnline() },
		func() bool { return a.lifecycle.IsBusy() },
		func() { a.orch.DialAuto() },
		func() { a.orch.DisconnectScheduled() },
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

	cfg := update.Load(filepath.Join(a.dataDir, update.OverrideFileName), warn)
	a.updater = update.NewModule(platform.UpdatesDir(), cfg, func(msg string) { a.logSvc.Info(msg) })

	// 启动横幅
	a.logSvc.Success(i18n.Tf("log.appStarted", model.Display()))
	if autoStartLaunch {
		a.logSvc.Info(i18n.T("log.autostartLaunch"))
	}
	a.logSvc.Info(i18n.T("log.author"))
	a.logSvc.Info(i18n.T("log.repo"))

	a.exec.Submit(func() {
		service.NewStartupSelfCheck(a.logSvc, a.dataDir).Run()
		cfgProbe := a.probeConfig()
		a.logSvc.Info(i18n.Tf("selfcheck.probeConfig", cfgProbe.Summary()))
		s := a.settings.Current()
		a.autoStart.EnsureHealthy(s.AutoStart)
		a.emit(EvtSettings, s)
	})

	a.monitor.Start()
	a.schedule.Restart()
	if a.settings.Current().AutoReconnect {
		a.reconnect.Start(a.settings.Current().IntervalSeconds, true)
	}

	// 托盘
	initTray(a)

	// 启动静默检查更新
	if a.settings.Current().UpdateCheckEnabled {
		a.exec.Schedule(5*time.Second, func() { a.doCheckUpdate(false) })
	}
}

// shutdown 有序停机：持久化 → 停服务 → 清内存密码 → 退托盘。
func (a *App) shutdown(ctx context.Context) {
	a.settings.FlushPending()
	a.accounts.Save()
	a.historySvc.SaveIfDirty()
	a.logSvc.Flush()

	a.reconnect.Stop()
	a.schedule.Stop()
	a.monitor.Stop()
	a.orch.Shutdown(3 * time.Second)
	a.exec.Shutdown(2 * time.Second)

	a.accounts.ClearPasswordsInMemory()
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
	if next.ScheduledDial != prev.ScheduledDial ||
		next.ScheduledDisconnect != prev.ScheduledDisconnect ||
		next.ScheduledDialHour != prev.ScheduledDialHour ||
		next.ScheduledDialMinute != prev.ScheduledDialMinute ||
		next.ScheduledDisconnectHour != prev.ScheduledDisconnectHour ||
		next.ScheduledDisconnectMinute != prev.ScheduledDisconnectMinute {
		a.schedule.Restart()
	}
	if next.AutoReconnect != prev.AutoReconnect || next.IntervalSeconds != prev.IntervalSeconds {
		a.applyAutoReconnect()
	}
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

// ExportAccounts 导出账号 CSV；withPassword 为 true 时含明文密码。
func (a *App) ExportAccounts(withPassword bool) string {
	path, err := runtime.SaveFileDialog(a.ctx, runtime.SaveDialogOptions{
		Title: i18n.T("export.title"),
		DefaultFilename: map[bool]string{true: "pppoe_accounts_export_WITH_PASSWORDS.csv",
			false: "pppoe_accounts_export.csv"}[withPassword],
		Filters: []runtime.FileFilter{{DisplayName: "CSV", Pattern: "*.csv"}},
	})
	if err != nil || path == "" {
		return ""
	}
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
		return ""
	}
	a.logSvc.Success(i18n.T("export.ok"))
	return path
}

// ImportAccounts 从 CSV 追加导入账号。
func (a *App) ImportAccounts() int {
	path, err := runtime.OpenFileDialog(a.ctx, runtime.OpenDialogOptions{
		Title:   i18n.T("import.title"),
		Filters: []runtime.FileFilter{{DisplayName: "CSV", Pattern: "*.csv"}},
	})
	if err != nil || path == "" {
		return 0
	}
	imported, err := storage.LoadCsv(path)
	if err != nil {
		a.logSvc.Error(i18n.Tf("import.failed", err.Error()))
		return 0
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

// ExportHistory 导出历史 CSV。
func (a *App) ExportHistory() string {
	path, err := runtime.SaveFileDialog(a.ctx, runtime.SaveDialogOptions{
		Title:           i18n.T("history.exportTitle"),
		DefaultFilename: "pppoe_history_export.csv",
		Filters:         []runtime.FileFilter{{DisplayName: "CSV", Pattern: "*.csv"}},
	})
	if err != nil || path == "" {
		return ""
	}
	if err := a.historySvc.Export(path); err != nil {
		a.logSvc.Error(i18n.Tf("history.exportFailed", err.Error()))
		return ""
	}
	a.logSvc.Success(i18n.Tf("history.exported", path))
	return path
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
}

// DiagListDevices 列出可选 PPPoE 设备。
func (a *App) DiagListDevices() []DeviceOption {
	hints := a.diag.ListDevices()
	out := make([]DeviceOption, 0, len(hints))
	for _, h := range hints {
		out = append(out, DeviceOption{
			Port:     h.Port,
			Device:   h.Device,
			Existing: h.FromExisting,
			Default:  !h.FromExisting,
		})
	}
	return out
}

// DiagSelectDevice 选择 PPPoE 设备；rewrite 为 true 时立即重写电话簿。
func (a *App) DiagSelectDevice(port, device string, rewrite bool) string {
	hint := &platform.DeviceHint{Port: port, Device: device, FromExisting: true}
	return a.diag.ApplyDevice(hint, rewrite)
}

// DiagRewritePhonebook 强制重写 RAS 电话簿条目。
func (a *App) DiagRewritePhonebook() string { return a.diag.RewritePhonebook() }

// DiagClear 通知前端清空输出区。
func (a *App) DiagClear() {}

// ============================ 在线更新 ============================

// CheckUpdate 检查更新（结果通过 app:update 事件返回）。
func (a *App) CheckUpdate(interactive bool) {
	a.exec.Submit(func() { a.doCheckUpdate(interactive) })
}

func (a *App) doCheckUpdate(interactive bool) {
	if !a.updateMu.TryLock() {
		a.emit(EvtUpdate, UpdatePayload{Kind: "error", Message: i18n.T("update.busy")})
		return
	}
	defer a.updateMu.Unlock()
	a.emit(EvtUpdate, UpdatePayload{Kind: "checking", Message: i18n.T("update.checking")})

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
		a.emit(EvtUpdate, UpdatePayload{Kind: "error", Message: i18n.T("update.downloadBusy")})
		return
	}
	a.updateBusy = true
	result := a.lastCheck
	cancel := update.NewCancel()
	a.cancelDl = cancel
	a.updateMu.Unlock()

	progress := updateProgress{a}
	a.exec.SubmitLong(func() {
		pkg, err := a.updater.DownloadWithFailover(*result, progress, cancel)
		a.updateMu.Lock()
		a.updateBusy = false
		a.cancelDl = nil
		a.updateMu.Unlock()
		if err != nil {
			a.logSvc.Error(i18n.Tf("update.downloadFailed", err.Error()))
			a.emit(EvtUpdate, UpdatePayload{Kind: "error", Message: i18n.Tf("update.downloadErrDlg", err.Error())})
			return
		}
		a.updateMu.Lock()
		a.pendingPkg = pkg
		a.updateMu.Unlock()
		a.logSvc.Success(i18n.Tf("update.verified", pkg.File))
		a.emit(EvtUpdate, UpdatePayload{Kind: "done", Path: pkg.File})
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
	pkg := a.pendingPkg
	a.updateBusy = true
	a.updateMu.Unlock()
	if pkg == nil {
		a.emit(EvtUpdate, UpdatePayload{Kind: "error", Message: i18n.T("update.noPackageFile")})
		a.updateMu.Lock()
		a.updateBusy = false
		a.updateMu.Unlock()
		return
	}
	progress := updateProgress{a}
	a.exec.SubmitLong(func() {
		prepared, err := a.updater.Prepare(pkg, progress)
		a.updateMu.Lock()
		a.updateBusy = false
		a.updateMu.Unlock()
		if err != nil {
			a.logSvc.Error(i18n.Tf("update.prepareFailed", err.Error()))
			a.emit(EvtUpdate, UpdatePayload{Kind: "error", Message: i18n.Tf("update.prepareFailed", err.Error())})
			return
		}
		a.logSvc.Info(i18n.Tf("update.applying", prepared.ApplyScript))
		a.flushBeforeUpdate()
		if !a.updater.LaunchInstall(prepared) {
			a.logSvc.Error(i18n.T("update.launchFailed"))
			a.emit(EvtUpdate, UpdatePayload{Kind: "error", Message: i18n.T("update.launchFailedDlg")})
			return
		}
		a.emit(EvtUpdate, UpdatePayload{Kind: "installing"})
		a.ExitProgram()
	})
}

// OpenReleasePage 在默认浏览器中打开发布页。
func (a *App) OpenReleasePage(url string) {
	if url == "" {
		url = model.GitHubURL + "/releases/latest"
	}
	runtime.BrowserOpenURL(a.ctx, url)
}

// ============================ 窗口 / 退出 ============================

// ShowWindow 显示主窗口。
func (a *App) ShowWindow() {
	if a.ctx == nil {
		return
	}
	runtime.WindowShow(a.ctx)
	runtime.WindowUnminimise(a.ctx)
}

// HideWindow 隐藏主窗口（最小化到托盘）。
func (a *App) HideWindow() {
	if a.ctx == nil {
		return
	}
	runtime.WindowHide(a.ctx)
}

// IsWindowVisible 主窗口是否可见。
func (a *App) IsWindowVisible() bool { return a.windowVisible() }

// ExitProgram 有序退出（托盘「退出」与更新安装前调用）。
func (a *App) ExitProgram() {
	a.exec.Submit(func() {
		a.shutdown(a.ctx)
		osExit(0)
	})
}
