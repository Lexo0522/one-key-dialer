package main

import (
	"strings"
	"time"

	"github.com/Lexo0522/one-key-dialer/internal/i18n"
	"github.com/Lexo0522/one-key-dialer/internal/model"
	"github.com/Lexo0522/one-key-dialer/internal/platform"
	"github.com/Lexo0522/one-key-dialer/internal/service"
)

// 诊断与连接详情:宽带页的一键诊断（含离线试拨）、PPP 链路统计、
// 物理网口插线状态、拨号结果结构化回显与凭据清除。

// dialWaitTimeout 诊断试拨等待拨号结果的上限，与前端拨号 outcome 超时一致。
const dialWaitTimeout = 90 * time.Second

// DialResultPayload 一次拨号尝试的最终结果（app:dial 事件负载）。
type DialResultPayload struct {
	Ok     bool   `json:"ok"`
	Code   int    `json:"code"`
	Detail string `json:"detail"`
	At     int64  `json:"at"`
}

// DiagStepPayload 一键诊断的步骤事件（app:diag 事件负载）。
// Phase: step 逐条推送 / done 收尾汇总（Detail 为多行明细）。
type DiagStepPayload struct {
	Phase  string `json:"phase"`
	Index  int    `json:"index"`
	Ok     bool   `json:"ok"`
	Text   string `json:"text"`
	Detail string `json:"detail,omitempty"`
}

// PppStatsDTO PPP 连接详情视图。
// Available=false 表示系统查询能力不可用（句柄枚举失败等），前端按降级展示。
type PppStatsDTO struct {
	Available   bool   `json:"available"`
	Connected   bool   `json:"connected"`
	LocalIP     string `json:"localIp"`
	ServerIP    string `json:"serverIp"`
	Bps         int64  `json:"bps"`
	BytesUp     int64  `json:"bytesUp"`
	BytesDown   int64  `json:"bytesDown"`
	ErrTotal    int64  `json:"errTotal"`
	DurationSec int64  `json:"durationSec"`
}

// EthLinkDTO 物理网口插线状态行。
type EthLinkDTO struct {
	Descr     string `json:"descr"`
	Up        bool   `json:"up"`
	SpeedMbps int64  `json:"speedMbps"`
}

// ---------- 拨号结果等待器 ----------
// 诊断试拨需要拿到一次拨号尝试的最终结果；OnDialFinished 触发时向全部
// 等待者非阻塞投递，等待方自行超时，绝不阻塞拨号链路。

func (a *App) registerDialWaiter() chan DialResultPayload {
	ch := make(chan DialResultPayload, 1)
	a.dialWaitersMu.Lock()
	a.dialWaiters = append(a.dialWaiters, ch)
	a.dialWaitersMu.Unlock()
	return ch
}

func (a *App) unregisterDialWaiter(ch chan DialResultPayload) {
	a.dialWaitersMu.Lock()
	defer a.dialWaitersMu.Unlock()
	for i, w := range a.dialWaiters {
		if w == ch {
			a.dialWaiters = append(a.dialWaiters[:i], a.dialWaiters[i+1:]...)
			break
		}
	}
}

func (a *App) fanOutDialResult(p DialResultPayload) {
	a.dialWaitersMu.Lock()
	defer a.dialWaitersMu.Unlock()
	for _, ch := range a.dialWaiters {
		select {
		case ch <- p:
		default:
		}
	}
}

// ---------- 清除凭据 ----------

// ClearBroadband 清空已保存的宽带凭据:内存密码零清后整体置空,
// 落盘空信封（broadband.json 中不再有账号与密码）。
func (a *App) ClearBroadband() bool {
	a.bbCredMu.Lock()
	a.bbCred.ClearPassword()
	a.bbCred = &model.BroadbandCredential{}
	a.bbCredMu.Unlock()
	a.exec.Submit(func() {
		if err := a.broadbandStore.Save(&model.BroadbandCredential{}); err != nil {
			a.logSvc.Error(i18n.Tf("broadband.clearFailed", err.Error()))
		} else {
			a.logSvc.Info(i18n.T("broadband.cleared"))
		}
	})
	return true
}

// ---------- 连接详情 / 网口状态 ----------

// PppStats 返回当前 PPP 连接的链路级详情（协商速率、收发字节、错包、时长、IP）。
// 未连接或系统查询能力异常时 Available=false。
func (a *App) PppStats() PppStatsDTO {
	st := platform.ConnectionStats(model.ConnectionName)
	if st == nil {
		return PppStatsDTO{}
	}
	return PppStatsDTO{
		Available:   true,
		Connected:   st.Connected,
		LocalIP:     st.LocalIP,
		ServerIP:    st.ServerIP,
		Bps:         int64(st.Bps),
		BytesUp:     int64(st.BytesUp),
		BytesDown:   int64(st.BytesDown),
		ErrTotal:    int64(st.ErrTotal),
		DurationSec: int64(st.DurationSec),
	}
}

// EthLinks 列出物理以太网口的插线状态（供设备卡与诊断步骤展示）。
func (a *App) EthLinks() []EthLinkDTO {
	links := platform.EthernetLinks()
	out := make([]EthLinkDTO, 0, len(links))
	for _, l := range links {
		out = append(out, EthLinkDTO{Descr: l.Descr, Up: l.Up, SpeedMbps: int64(l.SpeedMbps)})
	}
	return out
}

// ---------- 一键诊断 ----------

// DiagRun 提交一次诊断（凭据 → 电话簿 → 网口 → 探测 → 视情况试拨），
// 受理立即返回 true,结果经 app:diag 事件逐条推送;已有诊断在跑返回 false。
func (a *App) DiagRun() bool {
	if !a.diagBusy.CompareAndSwap(false, true) {
		return false
	}
	a.exec.SubmitLong(func() {
		defer a.diagBusy.Store(false)
		a.runDiagnostics()
	})
	return true
}

func (a *App) emitDiagStep(index int, ok bool, text string) {
	a.logSvc.Info("[diag] " + text)
	a.emit(EvtDiag, DiagStepPayload{Phase: "step", Index: index, Ok: ok, Text: text})
}

func (a *App) emitDiagDone(ok bool, detail string) {
	summary := i18n.T("diag.allOk")
	if !ok {
		summary = i18n.T("diag.issuesFound")
	}
	a.emit(EvtDiag, DiagStepPayload{Phase: "done", Ok: ok, Text: summary, Detail: detail})
}

// runDiagnostics 只读检查 + 可选试拨。步骤彼此独立,单步失败不中断后续检查,
// 汇总时统一给出结论,保证一次诊断能发现全部问题。
func (a *App) runDiagnostics() {
	okAll := true
	var lines []string

	// ① 宽带凭据
	cred := a.broadbandView()
	credOk := strings.TrimSpace(cred.Username) != "" && cred.HasPassword
	stepCred := i18n.T("diag.credMissing")
	if credOk {
		stepCred = i18n.Tf("diag.credOk", cred.Username)
	}
	okAll = okAll && credOk
	a.emitDiagStep(1, credOk, stepCred)
	lines = append(lines, stepCred)

	// ② 电话簿条目
	entryOk := a.ras.HasEntry()
	hint := a.ras.CurrentDevice()
	stepEntry := i18n.T("diag.entryMissing")
	if entryOk && hint != nil {
		stepEntry = i18n.Tf("diag.entryOk", hint.Device, hint.Port)
	}
	okAll = okAll && entryOk
	a.emitDiagStep(2, entryOk, stepEntry)
	lines = append(lines, stepEntry)

	// ③ 物理网口链路
	links := a.EthLinks()
	upCount := 0
	for _, l := range links {
		if l.Up {
			upCount++
		}
		lines = append(lines, "  "+ethLinkLine(l))
	}
	linksOk := upCount > 0
	stepLinks := i18n.T("diag.noEthernet")
	if linksOk {
		stepLinks = i18n.Tf("diag.links", upCount, len(links))
	}
	okAll = okAll && linksOk
	a.emitDiagStep(3, linksOk, stepLinks)
	lines = append(lines, stepLinks)

	// ④ 外网连通探测（离线时跳过）
	online := a.isOnline()
	if online {
		outcome := service.ConfirmDetailed(a.probeConfig(), "diag")
		stepProbe := i18n.Tf("diag.probe", outcome.ShortLine())
		okAll = okAll && outcome.OK
		a.emitDiagStep(4, outcome.OK, stepProbe)
		lines = append(lines, stepProbe)
	} else {
		a.emitDiagStep(4, true, i18n.T("diag.probeSkipped"))
		lines = append(lines, i18n.T("diag.probeSkipped"))
	}

	// ⑤ 试拨验证（仅离线 + 凭据齐全 + 拨号器空闲）
	if !online {
		stepDial, dialOk := a.diagTrialDial(credOk)
		okAll = okAll && dialOk
		a.emitDiagStep(5, dialOk, stepDial)
		lines = append(lines, stepDial)
	}

	a.emitDiagDone(okAll, strings.Join(lines, "\n"))
}

// diagTrialDial 真正拨一次号验证凭据。返回 (步骤文本, 是否通过)。
// 拨号结果同时走正常通知/日志链路,这里只等待收尾。
func (a *App) diagTrialDial(credOk bool) (string, bool) {
	if !credOk {
		return i18n.T("diag.dialSkippedNoCred"), false
	}
	if a.lifecycle.IsBusy() {
		// 拨号器忙（手动拨号/自动重连进行中）:等它结束即可,不重复拨
		return i18n.T("diag.dialSkippedBusy"), true
	}

	a.emitDiagStep(0, true, i18n.T("diag.dialing"))
	ch := a.registerDialWaiter()
	defer a.unregisterDialWaiter(ch)
	a.orch.DialAuto()

	deadline := time.Now().Add(dialWaitTimeout)
	for time.Now().Before(deadline) {
		select {
		case p := <-ch:
			if p.Ok {
				outcome := service.ConfirmDetailed(a.probeConfig(), "diag-trial")
				text := i18n.T("diag.dialOk") + " " + i18n.Tf("diag.probe", outcome.ShortLine())
				return text, outcome.OK
			}
			return i18n.Tf("diag.dialFailed", p.Code, p.Detail), false
		case <-time.After(500 * time.Millisecond):
			// DialAuto 在连接已被恢复的场景会直接跳过且不产生结果事件,
			// 轮询在线状态兜底,避免这种竞态拖满 90 秒超时
			if a.isOnline() {
				return i18n.T("diag.dialOk"), true
			}
		}
	}
	return i18n.T("diag.dialTimeout"), false
}

func ethLinkLine(l EthLinkDTO) string {
	state := i18n.T("diag.linkDown")
	if l.Up {
		state = i18n.Tf("diag.linkUp", l.SpeedMbps)
	}
	return l.Descr + " — " + state
}
