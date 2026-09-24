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
	"github.com/wailsapp/wails/v2/pkg/runtime"
)

// emit 推送事件（context 未就绪时静默丢弃）。
func (a *App) emit(name string, payload any) {
	if a.ctx == nil {
		return
	}
	runtime.EventsEmit(a.ctx, name, payload)
}

// notify 托盘气泡 + 前端提示（窗口可见时只走日志/前端）。
// tone 透传给前端灵动岛 Toast：info / success / warning / error。
func (a *App) notify(title, message, tone string) {
	a.emit(EvtNotify, map[string]string{"title": title, "body": message, "tone": tone})
	showTrayNotification(title, message)
}

// windowVisible 主窗口是否可见。
func (a *App) windowVisible() bool {
	if a.ctx == nil {
		return false
	}
	return windowShown
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

type updateProgress struct{ a *App }

func (p updateProgress) OnProgress(downloaded, total int64) {
	p.a.emit(EvtUpdate, UpdatePayload{Kind: "progress", Downloaded: downloaded, Total: total})
}

func (p updateProgress) OnStatus(message string) {
	p.a.logSvc.Info(message)
	p.a.emit(EvtUpdate, UpdatePayload{Kind: "status", Message: message})
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
