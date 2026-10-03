package main

import (
	"os"
	"strings"
	"time"

	"github.com/Lexo0522/one-key-dialer/internal/i18n"
	"github.com/Lexo0522/one-key-dialer/internal/model"
	"github.com/Lexo0522/one-key-dialer/internal/platform"
	"github.com/Lexo0522/one-key-dialer/internal/service"
	"github.com/Lexo0522/one-key-dialer/internal/update"
	"github.com/Lexo0522/one-key-dialer/internal/util"
)

// 代理→UI 的系统级控制事件（UI 进程自行消费,不转发给前端）。
const (
	SysEventShow = "sys:show"
	SysEventQuit = "sys:quit"
)

// emit 推送事件:代理模式下经命名管道广播给全部在线 UI 进程;
// 无 UI 接入时为零成本空操作（高频速度/心跳事件自动省流）。
func (a *App) emit(name string, payload any) {
	if a.ipcSrv != nil {
		a.ipcSrv.Broadcast(name, payload)
	}
}

// notify 托盘气泡 + 前端提示（窗口可见时只走日志/前端）。
// tone 透传给前端灵动岛 Toast：info / success / warning / error。
func (a *App) notify(title, message, tone string) {
	a.emit(EvtNotify, map[string]string{"title": title, "body": message, "tone": tone})
	showTrayNotification(title, message)
}

// windowVisible 主窗口是否可见:代理进程以「UI 进程是否接入」为准。
func (a *App) windowVisible() bool {
	return a.uiOnline()
}

func (a *App) isOnline() bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.online
}

// onlineProbe 用探测配置快速判断在线（供自动重连使用）。
func (a *App) onlineProbe() bool {
	if a.isOnline() {
		return true
	}
	return service.QuickCheck(a.probeConfig())
}

// setOnline 更新在线状态并记录会话基准。
func (a *App) setOnline(online bool) {
	a.mu.Lock()
	changed := a.online != online
	a.online = online
	if online {
		if a.connectTimeMs == 0 {
			a.connectTimeMs = time.Now().UnixMilli()
			a.sessionDown, a.sessionUp = 0, 0
		}
	} else {
		a.connectTimeMs = 0
		a.sessionDown, a.sessionUp = 0, 0
	}
	a.mu.Unlock()
	if changed {
		a.emit(EvtStatus, StatusPayload{Online: online})
		a.refreshTray()
	}
}

func (a *App) probeConfig() model.ProbeConfig {
	return model.ProbeConfigFromSettings(a.settings.Current())
}

func (a *App) accountDTOs() []AccountDTO {
	views := a.accounts.Views()
	out := make([]AccountDTO, 0, len(views))
	for _, v := range views {
		out = append(out, AccountDTO{
			Name:        v.Name,
			Username:    v.Username,
			Remark:      v.Remark,
			HasPassword: v.HasPassword,
		})
	}
	return out
}

func (a *App) clampAndEmit() {
	a.accounts.ClampIndexAfterListChange()
	a.emit(EvtAccounts, map[string]any{
		"accounts":     a.accountDTOs(),
		"currentIndex": a.accounts.CurrentIndex(),
	})
	a.refreshTray()
}

func (a *App) applyAutoReconnect() {
	s := a.settings.Current()
	if s.AutoReconnect {
		a.reconnect.Start(s.IntervalSeconds, false)
	} else {
		a.reconnect.Stop()
	}
}

func (a *App) applyPortalAuth() {
	s := a.settings.Current()
	if s.PortalAuthEnabled {
		a.portalSvc.Start()
	} else {
		a.portalSvc.Stop()
	}
}

func (a *App) applyWifiAutoConnect() {
	s := a.settings.Current()
	a.wifiSvc.Configure(s.WifiAutoConnect, s.WifiPreferredSsid)
}

// ---------- WiFi / 门户认证辅助 ----------

// wifiStatus 组装前端 WiFi 状态视图。
func (a *App) wifiStatus() WifiStatusDTO {
	s := a.settings.Current()
	dto := WifiStatusDTO{
		Phase:         "idle",
		AutoConnect:   s.WifiAutoConnect && s.WifiPreferredSsid != "",
		PreferredSsid: s.WifiPreferredSsid,
	}
	st, err := platform.WlanCurrent()
	if err != nil {
		return dto
	}
	dto.Available = true
	dto.Connected = st.Connected
	dto.Ssid = st.Ssid
	dto.SignalQuality = st.SignalQuality
	dto.Phase = st.Phase
	return dto
}

// wifiPskFor 返回给定 SSID 已保存的 PSK（仅供自动连接使用）。
func (a *App) wifiPskFor(ssid string) string {
	a.portalCredMu.Lock()
	defer a.portalCredMu.Unlock()
	if a.wifiPskSsid != ssid || len(a.wifiPsk) == 0 {
		return ""
	}
	return string(a.wifiPsk)
}

// storeWifiPsk 保存 WiFi PSK:内存即时生效,磁盘后台落盘(DPAPI)。
func (a *App) storeWifiPsk(ssid, psk string) {
	a.portalCredMu.Lock()
	a.wifiPskSsid = ssid
	model.ClearBytes(a.wifiPsk)
	a.wifiPsk = []byte(psk)
	a.portalCredMu.Unlock()
	a.exec.Submit(func() {
		if err := a.wifiPskStore.Save(ssid, []byte(psk)); err != nil {
			a.logSvc.Error(i18n.Tf("wifi.pskSaveFailed", err.Error()))
		} else {
			a.logSvc.Info(i18n.Tf("wifi.pskSaved", ssid))
		}
	})
}

// loadPortalCredential 启动时加载门户认证凭据。
func (a *App) loadPortalCredential() {
	cred, err := a.portalStore.Load()
	if err != nil {
		a.logSvc.Warning(i18n.Tf("portal.credLoadFailed", err.Error()))
		cred = &model.PortalCredential{}
	}
	a.portalCred = cred
}

// loadWifiPsk 启动时加载已保存的 WiFi PSK。
func (a *App) loadWifiPsk() {
	ssid, psk, ok, err := a.wifiPskStore.Load()
	if err != nil {
		a.logSvc.Warning(i18n.Tf("wifi.pskLoadFailed", err.Error()))
		return
	}
	if !ok {
		return
	}
	a.wifiPskSsid = ssid
	a.wifiPsk = psk
}

// performPortalAuth 按当前设置与已存凭据执行一次门户认证请求。
// 供自动认证循环与手动测试共用;成功与否由响应判定 + 复验决定。
func (a *App) performPortalAuth(portalURL string) service.PortalAuthOutcome {
	a.portalCredMu.Lock()
	username, password := "", ""
	if a.portalCred != nil {
		username = a.portalCred.Username
		password = a.portalCred.Password()
	}
	a.portalCredMu.Unlock()
	if strings.TrimSpace(username) == "" || password == "" {
		return service.PortalAuthOutcome{Detail: i18n.T("portal.noCred")}
	}
	s := a.settings.Current()
	cfg := service.PortalAuthConfig{
		LoginUrl:    s.PortalLoginUrl,
		Method:      s.PortalMethod,
		Body:        s.PortalBody,
		Headers:     service.ParsePortalHeaders(s.PortalHeaders),
		SuccessHint: s.PortalSuccessHint,
		Proxy:       s.ProxyConfig(),
	}
	return service.ExecutePortalAuth(cfg, portalURL, username, password)
}

func (a *App) resolvedTheme() string {
	theme := a.settings.Current().UITheme
	if theme != model.ThemeSystem {
		return theme
	}
	if platform.AppsUseLightTheme() {
		return model.ThemeLight
	}
	return model.ThemeDark
}

// ---------- 一次性凭据 ----------

func (a *App) setPending(username, password string) {
	a.mu.Lock()
	a.clearPendingLocked()
	a.pendingUser = strings.TrimSpace(username)
	a.pendingPass = []byte(password)
	a.mu.Unlock()
}

func (a *App) clearPendingPassword() {
	a.mu.Lock()
	a.clearPendingLocked()
	a.mu.Unlock()
}

func (a *App) clearPendingLocked() {
	for i := range a.pendingPass {
		a.pendingPass[i] = 0
	}
	a.pendingPass = nil
	a.pendingUser = ""
}

func (a *App) takePending() (string, []byte) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if len(a.pendingPass) == 0 && a.pendingUser == "" {
		return "", nil
	}
	u, p := a.pendingUser, a.pendingPass
	a.pendingUser, a.pendingPass = "", nil
	return u, p
}

// ---------- DialView 实现 ----------

type dialView struct{ a *App }

func (v dialView) Log(level service.Level, message string) { v.a.logSvc.Log(level, message) }

func (v dialView) Notify(title, message, tone string) {
	if v.a.windowVisible() {
		v.a.emit(EvtNotify, map[string]string{"title": title, "body": message, "tone": tone})
		return
	}
	v.a.notify(title, message, tone)
}

func (v dialView) OnDialPhase(phase string) {
	v.a.emit(EvtStatus, StatusPayload{Online: v.a.isOnline(), Phase: phase})
}

func (v dialView) OnConnectionState(online bool) { v.a.setOnline(online) }

func (v dialView) ValidateInput(interactive bool) bool {
	username, password := v.a.takePending()
	if username == "" && len(password) == 0 {
		if acc := v.a.accounts.CurrentOrNil(); acc != nil {
			username = acc.Username
			password = acc.CopyPassword()
		}
	}
	defer func() {
		for i := range password {
			password[i] = 0
		}
	}()
	if len(password) == 0 {
		if acc := v.a.accounts.CurrentOrNil(); acc != nil && acc.Username == username {
			password = acc.CopyPassword()
		}
	}

	hasAccount := v.a.accounts.CurrentOrNil() != nil
	failure := precheckFailure(v.a.isOnline(), hasAccount, username, password)
	if failure == "" {
		// 校验通过：把凭据放回待取区，供 CaptureCredentials 使用
		v.a.setPending(username, string(password))
		return true
	}
	v.a.logSvc.Log(service.LevelWarning, failure)
	if interactive {
		v.a.emit(EvtNotify, map[string]string{"title": i18n.T("precheck.dialog.default"), "body": dialogMessage(failure), "tone": service.ToneError})
	}
	return false
}

func (v dialView) CaptureCredentials() *model.DialCredentials {
	username, password := v.a.takePending()
	if username == "" && len(password) == 0 {
		if acc := v.a.accounts.CurrentOrNil(); acc != nil {
			username = acc.Username
			password = acc.CopyPassword()
		}
	}
	creds := model.NewDialCredentials(username, password)
	for i := range password {
		password[i] = 0
	}
	return creds
}

func precheckFailure(online, hasAccount bool, username string, password []byte) string {
	if online {
		return i18n.T("precheck.alreadyOnline")
	}
	if !hasAccount {
		return i18n.T("precheck.noAccount")
	}
	if strings.TrimSpace(username) == "" {
		return i18n.T("precheck.emptyUsername")
	}
	if len(strings.TrimSpace(string(password))) == 0 {
		return i18n.T("precheck.emptyPassword")
	}
	return ""
}

func dialogMessage(logMessage string) string {
	switch logMessage {
	case i18n.T("precheck.noAccount"):
		return i18n.T("precheck.dialog.noAccount")
	case i18n.T("precheck.emptyUsername"):
		return i18n.T("precheck.dialog.user")
	case i18n.T("precheck.emptyPassword"):
		return i18n.T("precheck.dialog.password")
	case i18n.T("precheck.alreadyOnline"):
		return i18n.T("precheck.dialog.alreadyOn")
	}
	return i18n.T("precheck.dialog.default")
}

// ---------- DialEnvironment 实现 ----------

type dialEnv struct{ a *App }

func (e dialEnv) IsOnline() bool {
	e.a.mu.Lock()
	defer e.a.mu.Unlock()
	return e.a.online
}

func (e dialEnv) ConnectTimeMillis() int64 {
	e.a.mu.Lock()
	defer e.a.mu.Unlock()
	return e.a.connectTimeMs
}

func (e dialEnv) SessionTrafficBytes() int64 {
	e.a.mu.Lock()
	defer e.a.mu.Unlock()
	return e.a.sessionDown + e.a.sessionUp
}

func (e dialEnv) CurrentAccountName() string { return e.a.accounts.CurrentName() }

func (e dialEnv) ProbeConfig() model.ProbeConfig { return e.a.probeConfig() }

func (e dialEnv) DisconnectOnNoInternet() bool {
	return e.a.settings.Current().DisconnectOnNoInternet
}

func (e dialEnv) AddHistory(operation, account, result, duration, traffic string) {
	e.a.historySvc.Add(operation, account, result, duration, traffic)
}

func (e dialEnv) PersistAfterSuccess() {
	e.a.settings.FlushPending()
	e.a.accounts.SaveInBackground()
}

func (e dialEnv) RecordProbeOutcome(outcome model.ProbeOutcome) {
	e.a.mu.Lock()
	e.a.lastProbe = &outcome
	e.a.mu.Unlock()
}

// ---------- 更新进度回调 ----------

// updateProgress 把阶段标识附加到每条状态/进度事件上：下载与解压走的是同一条
// update.Progress 通道，没有阶段号前端就无法分辨，只能一律当下载处理。
type updateProgress struct {
	a     *App
	stage string
}

func (p updateProgress) OnProgress(downloaded, total int64) {
	p.a.emit(EvtUpdate, UpdatePayload{Kind: "progress", Stage: p.stage, Downloaded: downloaded, Total: total})
}

func (p updateProgress) OnStatus(message string) {
	p.a.logSvc.Info(message)
	p.a.emit(EvtUpdate, UpdatePayload{Kind: "status", Stage: p.stage, Message: message})
}

// ---------- 其它 ----------

func (a *App) diagContext() service.DiagContext {
	a.mu.Lock()
	online := a.online
	conn := a.connectTimeMs
	down := a.sessionDown
	up := a.sessionUp
	downSpeed := a.downSpeed
	upSpeed := a.upSpeed
	last := a.lastProbe
	a.mu.Unlock()

	ctx := service.DiagContext{
		Online:        online,
		ConnectTimeMs: conn,
		DownBytes:     down,
		UpBytes:       up,
		DownSpeed:     util.FormatSpeed(downSpeed),
		UpSpeed:       util.FormatSpeed(upSpeed),
		ProbeConfig:   a.probeConfig(),
	}
	if acc := a.accounts.CurrentOrNil(); acc != nil {
		ctx.Account = acc.Username
		ctx.Nickname = acc.Name
	}
	if last != nil {
		ctx.LastProbeDetail = last.DetailLine()
	}
	return ctx
}

func (a *App) flushBeforeUpdate() {
	a.settings.FlushPending()
	a.accounts.Save()
	a.historySvc.SaveIfDirty()
	a.logSvc.Flush()
}

// osExit 供 ExitProgram 使用（单独封装便于测试替换）。
var osExit = os.Exit

// updateBusyState 供前端查询更新是否进行中。
func (a *App) UpdateBusy() bool {
	a.updateMu.Lock()
	defer a.updateMu.Unlock()
	return a.updateBusy
}

// unused guard
var _ = update.SanitizeFileName
