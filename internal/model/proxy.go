package model

import (
	"net"
	"net/url"
	"strconv"
	"strings"
)

// 代理类型。SOCKS5 由 Go 标准库 http.Transport 原生支持，
// 无需引入额外依赖。
const (
	ProxyTypeHTTP   = "http"
	ProxyTypeHTTPS  = "https"
	ProxyTypeSOCKS5 = "socks5"
)

// 各类型默认端口：地址栏留空端口时按类型回退。
const (
	DefaultProxyPortHTTP   = "80"
	DefaultProxyPortHTTPS  = "443"
	DefaultProxyPortSOCKS5 = "1080"
)

// ProxyConfig 归一化后的代理配置。
// 作用范围严格限定为本应用自身的 HTTP 出口（在线更新检查/下载、
// HTTP 模式外网探测），不修改系统代理设置，不影响其他程序。
type ProxyConfig struct {
	Enabled bool
	Type    string // http | https | socks5
	Host    string
	Port    string
	Bypass  []string
}

// ProxyConfigOf 从设置快照提取代理配置；内部再做一次归一化，
// 未经 Normalize 的输入（测试、旧数据）同样安全。
// 启用但地址为空视为未配置（不下发代理）。
func (s Settings) ProxyConfig() ProxyConfig {
	typ := NormalizeProxyType(s.ProxyType)
	host := normalizeProxyHost(s.ProxyHost)
	port := normalizeProxyPort(s.ProxyPort, typ)
	return ProxyConfig{
		Enabled: s.ProxyEnabled && host != "",
		Type:    typ,
		Host:    host,
		Port:    port,
		Bypass:  splitProxyBypass(s.ProxyBypass),
	}
}

// ProxyURL 返回代理 URL；未启用或主机为空时返回 nil。
func (c ProxyConfig) ProxyURL() *url.URL {
	if !c.Enabled || c.Host == "" {
		return nil
	}
	return &url.URL{Scheme: c.Type, Host: net.JoinHostPort(c.Host, c.Port)}
}

// Summary 返回 "socks5 127.0.0.1:1080" 形式的摘要（日志/诊断用）。
func (c ProxyConfig) Summary() string {
	if !c.Enabled || c.Host == "" {
		return "off"
	}
	return c.Type + " " + net.JoinHostPort(c.Host, c.Port)
}

// NormalizeProxyType 归一代理类型，非法值回退 http。
func NormalizeProxyType(typ string) string {
	switch strings.ToLower(strings.TrimSpace(typ)) {
	case ProxyTypeHTTPS:
		return ProxyTypeHTTPS
	case ProxyTypeSOCKS5:
		return ProxyTypeSOCKS5
	default:
		return ProxyTypeHTTP
	}
}

// normalizeProxyHost 清理地址：去空白、剥离误粘的 scheme 前缀与结尾斜杠。
func normalizeProxyHost(host string) string {
	h := strings.TrimSpace(host)
	if h == "" {
		return ""
	}
	if i := strings.Index(h, "://"); i >= 0 {
		h = h[i+3:]
	}
	return strings.Trim(h, "/")
}

// normalizeProxyPort 校验端口：合法数字原样保留，空值/非法值按类型回退默认端口。
func normalizeProxyPort(port, typ string) string {
	p := strings.TrimSpace(port)
	if p != "" {
		if n, err := strconv.Atoi(p); err == nil && n >= 1 && n <= 65535 {
			return strconv.Itoa(n)
		}
	}
	switch NormalizeProxyType(typ) {
	case ProxyTypeHTTPS:
		return DefaultProxyPortHTTPS
	case ProxyTypeSOCKS5:
		return DefaultProxyPortSOCKS5
	default:
		return DefaultProxyPortHTTP
	}
}

// splitProxyBypass 拆分绕过列表：逗号/分号均可，去空白、去空项、去重。
func splitProxyBypass(list string) []string {
	fields := strings.FieldsFunc(list, func(r rune) bool {
		return r == ',' || r == ';'
	})
	out := make([]string, 0, len(fields))
	seen := make(map[string]bool, len(fields))
	for _, f := range fields {
		f = strings.TrimSpace(f)
		if f == "" || seen[f] {
			continue
		}
		seen[f] = true
		out = append(out, f)
	}
	return out
}

// joinProxyBypass 规范化连接为分号分隔（与 Windows ProxyOverride 风格一致）。
func joinProxyBypass(items []string) string {
	return strings.Join(items, ";")
}

// splitProxyEndpoint 处理地址栏误粘 "host:port"（或带 scheme）的情况：
// 端口栏为空时从地址中拆出数字端口。用于 Settings.Normalize 的跨字段修正。
func splitProxyEndpoint(host, port string) (string, string) {
	h := normalizeProxyHost(host)
	p := strings.TrimSpace(port)
	if p == "" && h != "" {
		if nh, np, err := net.SplitHostPort(h); err == nil && isAllDigits(np) {
			return nh, np
		}
	}
	return h, p
}

func isAllDigits(s string) bool {
	if s == "" {
		return false
	}
	for _, r := range s {
		if r < '0' || r > '9' {
			return false
		}
	}
	return true
}
