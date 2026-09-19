package service

import (
	"strings"
	"time"

	"github.com/Lexo0522/one-key-dialer/internal/i18n"
	"github.com/Lexo0522/one-key-dialer/internal/model"
	"github.com/Lexo0522/one-key-dialer/internal/platform"
	"github.com/Lexo0522/one-key-dialer/internal/util"
)

// diagTimeout 诊断子进程超时。
const diagTimeout = 60 * time.Second

// separator 诊断输出分隔线（39 个 ═）。
const separator = "═══════════════════════════════════════"

// Diagnostics 网络诊断执行器。
type Diagnostics struct {
	ras     *platform.RasModule
	env     func() DiagContext
	onLine  func(string)
	logger  *LogService
	running bool
}

// DiagContext 诊断需要的运行时上下文。
type DiagContext struct {
	Online          bool
	Account         string
	Nickname        string
	ConnectTimeMs   int64
	DownBytes       int64
	UpBytes         int64
	DownSpeed       string
	UpSpeed         string
	ProbeConfig     model.ProbeConfig
	LastProbeDetail string
}

// NewDiagnostics 构造诊断执行器。
func NewDiagnostics(ras *platform.RasModule, env func() DiagContext, onLine func(string), logger *LogService) *Diagnostics {
	return &Diagnostics{ras: ras, env: env, onLine: onLine, logger: logger}
}

// IsRunning 是否有诊断任务在执行。
func (d *Diagnostics) IsRunning() bool { return d.running }

func (d *Diagnostics) emit(line string) {
	if d.onLine != nil {
		d.onLine(line)
	}
}

// RunCommand 执行一条命令并流式输出（带头尾分隔线）。
func (d *Diagnostics) RunCommand(display string, argv []string) {
	d.emit(separator)
	d.emit(i18n.Tf("diag.exec", display))
	d.emit(separator)
	d.emit("")
	res, err := util.RunProcess(argv, diagTimeout, func(line string) {
		d.emit("  " + line)
	})
	d.emit("")
	d.emit(separator)
	if err != nil {
		d.emit(i18n.Tf("diag.failed", err.Error()))
	} else if res.TimedOut {
		d.emit(i18n.T("diag.timeout"))
	} else {
		d.emit(i18n.T("diag.finished"))
	}
	d.emit(separator)
}

// Ping 执行 ping 测试。
func (d *Diagnostics) Ping() {
	d.RunCommand("ping -n 4 223.5.5.5", []string{"ping", "-n", "4", "223.5.5.5"})
}

// IPConfig 执行 ipconfig /all。
func (d *Diagnostics) IPConfig() {
	d.RunCommand("ipconfig /all", []string{"ipconfig", "/all"})
}

// TraceRoute 执行 tracert。
func (d *Diagnostics) TraceRoute() {
	d.RunCommand("tracert -d 223.5.5.5", []string{"tracert", "-d", "223.5.5.5"})
}

// FlushDNS 执行 ipconfig /flushdns。
func (d *Diagnostics) FlushDNS() {
	d.RunCommand("ipconfig /flushdns", []string{"ipconfig", "/flushdns"})
}

// ConnectionReport 生成连接状态报告（含适配器与连通性测试）。
func (d *Diagnostics) ConnectionReport() {
	ctx := d.env()
	d.emit(separator)
	d.emit("          网络连接状态报告")
	d.emit(separator)
	d.emit("")
	d.emit("【基本状态】")
	if ctx.Online {
		d.emit("  连接状态: ● " + i18n.T("status.connected"))
	} else {
		d.emit("  连接状态: ○ " + i18n.T("status.disconnected"))
	}
	if ctx.Account != "" {
		d.emit("  当前账号: " + ctx.Account)
	}
	if ctx.Nickname != "" {
		d.emit("  昵称: " + ctx.Nickname)
	}
	if ctx.Online && ctx.ConnectTimeMs > 0 {
		sec := (time.Now().UnixMilli() - ctx.ConnectTimeMs) / 1000
		d.emit("  连接时长: " + util.FormatDuration(sec))
	}
	d.emit("")
	d.emit("【流量统计】")
	d.emit("  ↓ 下行: " + util.FormatBytes(ctx.DownBytes))
	d.emit("  ↑ 上行: " + util.FormatBytes(ctx.UpBytes))
	d.emit("  总 计: " + util.FormatBytes(ctx.DownBytes+ctx.UpBytes))
	d.emit("")
	d.emit("【当前速度】")
	d.emit("  ↓ 下行: " + ctx.DownSpeed)
	d.emit("  ↑ 上行: " + ctx.UpSpeed)
	d.emit("")
	d.emit(separator)
	d.emit("")

	d.emit("【网络适配器】")
	adapters, err := listIPv4Adapters()
	if err != nil {
		d.emit("  " + i18n.Tf("diag.adapterErr", "exec", err.Error()))
	} else if len(adapters) == 0 {
		d.emit("  " + i18n.T("diag.noAdapter"))
	} else {
		for _, a := range adapters {
			d.emit("  " + a.Name)
			for _, ip := range a.IPv4 {
				d.emit("    IPv4: " + ip)
			}
		}
	}
	d.emit("")

	d.emit("【连通性测试】")
	res, err := util.RunProcess([]string{"ping", "-n", "2", "223.5.5.5"}, diagTimeout, func(line string) {
		if strings.TrimSpace(line) != "" {
			d.emit("  " + line)
		}
	})
	if err != nil {
		d.emit("  " + i18n.Tf("diag.failed", err.Error()))
	} else if res.TimedOut {
		d.emit("  " + i18n.T("diag.pingTimeout"))
	} else {
		if strings.TrimSpace(res.Output) == "" {
			d.emit("  " + i18n.T("diag.pingEmpty"))
		} else if res.ExitCode == 0 {
			d.emit("  " + i18n.T("diag.pingOk"))
		} else {
			d.emit("  " + i18n.T("diag.pingBad"))
		}
	}
	d.emit("")
	d.emit(separator)
	d.emit("报告生成时间: " + time.Now().Format("2006-01-02 15:04:05"))
}

// PhonebookReport 输出探测配置与电话簿状态。
func (d *Diagnostics) PhonebookReport() {
	ctx := d.env()
	d.emit(separator)
	d.emit("     电话簿 / 外网探测配置")
	d.emit(separator)
	d.emit("")
	d.emit("【探测】")
	summary := ctx.ProbeConfig.Summary()
	if ctx.LastProbeDetail != "" {
		summary += " | last=[" + ctx.LastProbeDetail + "]"
	}
	d.emit("  " + summary)
	d.emit("")
	d.emit("【RAS 电话簿】")
	if d.ras == nil {
		d.emit("  " + i18n.T("diag.noPhonebook"))
	} else {
		d.emit("  " + platform.FormatStatus(d.ras.SnapshotStatus()))
	}
	d.emit("")
	d.emit(separator)
}

// ListDevices 列出可选 PPPoE 设备。
func (d *Diagnostics) ListDevices() []platform.DeviceHint {
	if d.ras == nil {
		return nil
	}
	return d.ras.ListDeviceOptions()
}

// ApplyDevice 记住设备选择；rewrite 为 true 时立即重写电话簿。
// 返回给用户的提示文本。
func (d *Diagnostics) ApplyDevice(hint *platform.DeviceHint, rewrite bool) string {
	if d.ras == nil || hint == nil {
		return i18n.T("diag.unchanged")
	}
	d.ras.SetPreferredDevice(hint)
	if !rewrite {
		return i18n.Tf("diag.remembered", hint.Device, hint.Port)
	}
	if d.ras.RewriteEntry() {
		return i18n.Tf("diag.rewritten", hint.Device, hint.Port)
	}
	if d.logger != nil {
		d.logger.Warning(i18n.T("diag.rewriteFail"))
	}
	return i18n.T("diag.rewriteFail")
}

// RewritePhonebook 删除并重建 RAS 连接条目。
func (d *Diagnostics) RewritePhonebook() string {
	if d.ras == nil {
		return i18n.T("diag.rewriteFail")
	}
	if d.ras.RewriteEntry() {
		if h := d.ras.PreferredDevice(); h != nil {
			return i18n.Tf("diag.rewritten", h.Device, h.Port)
		}
		return i18n.Tf("diag.rewritten", "-", "-")
	}
	return i18n.T("diag.rewriteFail")
}

// AdapterInfo 一个网络适配器的 IPv4 信息。
type AdapterInfo struct {
	Name string
	IPv4 []string
}

// listIPv4Adapters 通过 ipconfig 解析已启用适配器的 IPv4 地址。
func listIPv4Adapters() ([]AdapterInfo, error) {
	res, err := util.RunProcess([]string{"ipconfig", "/all"}, diagTimeout, nil)
	if err != nil {
		return nil, err
	}
	var out []AdapterInfo
	current := ""
	for _, raw := range splitDiagLines(res.Output) {
		line := strings.TrimRight(raw, "\r")
		if line == "" {
			continue
		}
		if !strings.HasPrefix(line, " ") && !strings.HasPrefix(line, "\t") {
			if idx := strings.Index(line, ":"); idx > 0 {
				current = strings.TrimSpace(line[:idx])
			} else {
				current = strings.TrimSpace(line)
			}
			continue
		}
		t := strings.TrimSpace(line)
		lower := strings.ToLower(t)
		if strings.HasPrefix(lower, "ipv4") || strings.Contains(t, "IPv4 地址") || strings.Contains(t, "IP Address") {
			idx := strings.LastIndex(t, ":")
			if idx < 0 {
				continue
			}
			ip := strings.TrimSpace(t[idx+1:])
			ip = strings.TrimSuffix(ip, "(首选)")
			ip = strings.TrimSuffix(ip, "(Preferred)")
			ip = strings.TrimSpace(ip)
			if ip == "" {
				continue
			}
			if current == "" {
				current = "adapter"
			}
			found := false
			for i := range out {
				if out[i].Name == current {
					out[i].IPv4 = append(out[i].IPv4, ip)
					found = true
					break
				}
			}
			if !found {
				out = append(out, AdapterInfo{Name: current, IPv4: []string{ip}})
			}
		}
	}
	return out, nil
}

func splitDiagLines(s string) []string {
	s = strings.ReplaceAll(s, "\r\n", "\n")
	return strings.Split(s, "\n")
}
