package main

import (
	"os"
	"strings"
	"time"

	"github.com/Lexo0522/one-key-dialer/internal/i18n"
	"github.com/Lexo0522/one-key-dialer/internal/model"
	"github.com/Lexo0522/one-key-dialer/internal/platform"
	"github.com/Lexo0522/one-key-dialer/internal/service"
)

// 代理→UI 的系统级控制事件（UI 进程自行消费,不转发给前端）。
const (
	SysEventShow = "sys:show"
	SysEventQuit = "sys:quit"
)

// emit 推送事件:代理模式下经命名管道广播给全部在线 UI 进程;
// 无 UI 接入时为零成本空操作（高频速度/心跳事件自动省流）。
// ipcSrv 经锁保护快照获取,与 shutdown 置 nil 并发安全。
func (a *App) emit(name string, payload any) {
	if srv := a.ipcSnapshot(); srv != nil {
		srv.Broadcast(name, payload)
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
		// 拨号接管后直连态失效：静默清除，探测循环不会再误标记
		a.sysOnline = false
		a.sysConnectTimeMs = 0
	} else {
		a.connectTimeMs = 0
		a.sessionDown, a.sessionUp = 0, 0
	}
	a.mu.Unlock()
	if changed {
		a.emit(EvtStatus, a.statusPayload())
		a.refreshTray()
	}
}

// statusPayload 组装连接状态事件负载（含系统直连在线口径）。
func (a *App) statusPayload() StatusPayload {
	a.mu.Lock()
	defer a.mu.Unlock()
	return StatusPayload{Online: a.online, SysOnline: a.sysOnline}
}

func (a *App) probeConfig() model.ProbeConfig {
	return model.ProbeConfigFromSettings(a.settings.Current())
}

// directOnlineWired 系统是否已通过"网口直连"联网：外网可达，且默认路由
// 出口为物理网口（网线直连，如家庭宽带由路由器拨号后 DHCP 分配）。
// WiFi/VPN 等其它出口不算——此时用户可能仍想走网口拨号，不能据此免检凭据。
// 仅拨号预检使用。
func (a *App) directOnlineWired() bool {
	cfg := a.probeConfig()
	if !service.QuickCheck(cfg) {
		return false
	}
	return platform.RoutedViaPhysicalNIC(cfg.Host)
}

// directOnline 系统是否已联网（任意出口）：外网可达即算。
// 用于流量统计的有效在线判定——WiFi 联网同样是真实联网，
// 用户此时在上网，速率与会话流量必须记录。
// 与 directOnlineWired 的区别仅在"出口是否必须是物理网口"。
func (a *App) directOnline() bool {
	return service.QuickCheck(a.probeConfig())
}

// sysProbeInterval 系统直连在线的低频探测周期。
const sysProbeInterval = 15 * time.Second

// setSysOnline 更新"系统已联网"状态（仅在本应用未拨号在线时有意义）：
// 变化时记录/清除会话起点、写日志并回推前端。拨号在线时一律视为否。
func (a *App) setSysOnline(v bool) {
	if a.isOnline() {
		v = false
	}
	a.mu.Lock()
	changed := a.sysOnline != v
	a.sysOnline = v
	if v {
		if a.sysConnectTimeMs == 0 {
			a.sysConnectTimeMs = time.Now().UnixMilli()
		}
	} else {
		a.sysConnectTimeMs = 0
	}
	a.mu.Unlock()
	if !changed {
		return
	}
	if v {
		a.logSvc.Info(i18n.T("sys.directOnline"))
	} else {
		a.logSvc.Info(i18n.T("sys.directOffline"))
	}
	a.emit(EvtStatus, a.statusPayload())
}

// startSysProbe 低频探测"系统是否已联网"（任意出口：网口直连 / WiFi / VPN），
// 状态经 EvtStatus 回推前端，作为流量统计的有效在线依据。探测含 ICMP/HTTP，
// 离线时单次最长约 3.5s，独立协程运行，与监控的 1s 采样互不阻塞。转离线需
// 连续 2 次探测失败：单次偶发丢包不应清空正在记录的统计会话。
func (a *App) startSysProbe() {
	go func() {
		ticker := time.NewTicker(sysProbeInterval)
		defer ticker.Stop()
		a.setSysOnline(a.directOnline()) // 启动先探测一次，尽快建立状态
		misses := 0
		for {
			select {
			case <-a.sysProbeStop:
				return
			case <-ticker.C:
			}
			if a.isOnline() {
				misses = 0
				a.setSysOnline(false) // 拨号在线时直连态无意义（内部幂等）
				continue
			}
			if a.directOnline() {
				misses = 0
				a.setSysOnline(true)
				continue
			}
			if !a.isSysOnline() {
				continue
			}
			misses++
			if misses >= 2 {
				misses = 0
				a.setSysOnline(false)
			}
		}
	}()
}

// isSysOnline 返回当前"系统直连在线"标志（不触发探测）。
func (a *App) isSysOnline() bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.sysOnline
}

// effectiveOnline 有效在线：本应用拨号在线，或系统网口直连在线。
// 流量监控按此口径放行——直连场景同样记录速率与会话流量。
func (a *App) effectiveOnline() bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.online || a.sysOnline
}

// effectiveConnectTimeMs 有效会话起点：优先本应用拨号时刻，其次直连检测时刻。
func (a *App) effectiveConnectTimeMs() int64 {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.connectTimeMs > 0 {
		return a.connectTimeMs
	}
	return a.sysConnectTimeMs
}

// ---------- 宽带凭据 ----------

// loadBroadband 启动时加载宽带拨号凭据。
// startup 先于任何 RPC 执行，且 Load 对缺文件/失败均返回非 nil，
// 因此 a.bbCred 自此全程非 nil，各处无需判空。
func (a *App) loadBroadband() {
	cred, err := a.broadbandStore.Load()
	if err != nil {
		a.logSvc.Warning(i18n.Tf("broadband.loadFailed", err.Error()))
		cred = &model.BroadbandCredential{}
	}
	a.bbCred = cred
}

// broadbandView 组装前端宽带凭据视图。
func (a *App) broadbandView() BroadbandCredentialDTO {
	a.bbCredMu.Lock()
	defer a.bbCredMu.Unlock()
	return BroadbandCredentialDTO{Username: a.bbCred.Username, HasPassword: a.bbCred.HasPassword()}
}

// broadbandUsername 返回已保存的宽带账号（未设置时为空串）。
func (a *App) broadbandUsername() string {
	a.bbCredMu.Lock()
	defer a.bbCredMu.Unlock()
	return strings.TrimSpace(a.bbCred.Username)
}

// broadbandCreds 返回已保存宽带凭据的副本，密码由调用方负责清零。
func (a *App) broadbandCreds() (string, []byte) {
	a.bbCredMu.Lock()
	defer a.bbCredMu.Unlock()
	return a.bbCred.Username, a.bbCred.CopyPassword()
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
// Phase 描述 OS 接口的瞬时阶段（idle/connecting/connected/…），仅用于展示；
// 按钮可用性由 Busy 表达：只有本应用发起的连接/断开流程才锁定界面。
func (a *App) wifiStatus() WifiStatusDTO {
	s := a.settings.Current()
	dto := WifiStatusDTO{
		Phase:         "idle",
		Busy:          a.wifiSvc.IsBusy(),
		AutoConnect:   s.WifiAutoConnect && s.WifiPreferredSsid != "",
		PreferredSsid: s.WifiPreferredSsid,
	}
	st, err := a.wifiSvc.Status()
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
// startup 先于任何 RPC 执行，且 Load 对缺文件/失败均返回非 nil，
// 因此 a.portalCred 自此全程非 nil，各处无需判空。
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
	username := a.portalCred.Username
	password := a.portalCred.Password()
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
	p := v.a.statusPayload()
	p.Phase = phase
	v.a.emit(EvtStatus, p)
}

func (v dialView) OnConnectionState(online bool) { v.a.setOnline(online) }

// OnDialFinished 结构化拨号结果:广播给前端（宽带页内联回显）并唤醒诊断试拨等待者。
func (v dialView) OnDialFinished(ok bool, code int, detail string) {
	p := DialResultPayload{Ok: ok, Code: code, Detail: detail, At: time.Now().UnixMilli()}
	v.a.emit(EvtDial, p)
	v.a.fanOutDialResult(p)
}

func (v dialView) ValidateInput(interactive bool) bool {
	username, password := v.a.takePending()
	if username == "" && len(password) == 0 {
		username, password = v.a.broadbandCreds()
	}
	defer func() {
		model.ClearBytes(password)
	}()
	if len(password) == 0 {
		savedUser, savedPass := v.a.broadbandCreds()
		if savedUser == username {
			password = savedPass
		} else {
			model.ClearBytes(savedPass)
		}
	}

	// 拨号预检仍以「网口直连」为准：WiFi 联网时用户可能就是想走网口拨号，
	// 不能因为 WiFi 已联网就免检凭据
	failure := precheckFailure(v.a.isOnline(), username, password, v.a.directOnlineWired)
	if failure == "" {
		// 校验通过：把凭据放回待取区，供 CaptureCredentials 使用
		v.a.setPending(username, string(password))
		return true
	}
	if isPrecheckOnlineNote(failure) {
		// 已在线/已直连联网是"无需动作"的提示而非失败
		v.a.logSvc.Log(service.LevelInfo, failure)
		if interactive {
			v.a.emit(EvtNotify, map[string]string{"title": i18n.T("precheck.dialog.noDial"),
				"body": dialogMessage(failure), "tone": service.ToneInfo})
		}
		return false
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
		username, password = v.a.broadbandCreds()
	}
	creds := model.NewDialCredentials(username, password)
	model.ClearBytes(password)
	return creds
}

// precheckFailure 返回预检失败文案，通过返回空串。
// 凭据缺失时先探测系统是否已"网口直连"联网（如家庭宽带免拨号场景）：
// 已直连联网则无需拨号，不应再要求账号；只有确实不联网才报凭据缺失。
func precheckFailure(online bool, username string, password []byte, directOnline func() bool) string {
	if online {
		return i18n.T("precheck.alreadyOnline")
	}
	if strings.TrimSpace(username) != "" && len(strings.TrimSpace(string(password))) > 0 {
		return ""
	}
	if directOnline != nil && directOnline() {
		return i18n.T("precheck.systemOnline")
	}
	if strings.TrimSpace(username) == "" {
		return i18n.T("precheck.emptyUsername")
	}
	return i18n.T("precheck.emptyPassword")
}

// isPrecheckOnlineNote 预检结果是否为"已在线"类提示（无需动作，非失败）。
func isPrecheckOnlineNote(msg string) bool {
	return msg == i18n.T("precheck.alreadyOnline") || msg == i18n.T("precheck.systemOnline")
}

func dialogMessage(logMessage string) string {
	switch logMessage {
	case i18n.T("precheck.emptyUsername"):
		return i18n.T("precheck.dialog.user")
	case i18n.T("precheck.emptyPassword"):
		return i18n.T("precheck.dialog.password")
	case i18n.T("precheck.alreadyOnline"):
		return i18n.T("precheck.dialog.alreadyOn")
	case i18n.T("precheck.systemOnline"):
		return i18n.T("precheck.dialog.systemOn")
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

func (e dialEnv) ProbeConfig() model.ProbeConfig { return e.a.probeConfig() }

func (e dialEnv) DisconnectOnNoInternet() bool {
	return e.a.settings.Current().DisconnectOnNoInternet
}

func (e dialEnv) PersistAfterSuccess() {
	e.a.settings.FlushPending()
}

// osExit 供 ExitProgram 使用（单独封装便于测试替换）。
var osExit = os.Exit
