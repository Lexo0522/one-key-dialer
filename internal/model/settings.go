package model

import "strings"

// 主题取值
const (
	ThemeSystem = "system"
	ThemeLight  = "light"
	ThemeDark   = "dark"
)

// MinIntervalSeconds 自动重连最小间隔。
const MinIntervalSeconds = 5

// 探测模式
const (
	ProbeModeICMP = "icmp"
	ProbeModeHTTP = "http"
	ProbeModeAuto = "auto"
)

// 探测默认值
const (
	DefaultProbeHost     = "223.5.5.5"
	DefaultProbeHTTPURL  = "http://connectivitycheck.gstatic.com/generate_204"
	DefaultProbeAttempts = 3
	DefaultProbeDelayMs  = 1000
	DefaultHTTPTimeoutMs = 2500
)

// Settings 是不可变设置快照的 Go 版载体；JSON 字段名与旧版完全一致，
// 以保证已有 settings.json 可直接读取。
type Settings struct {
	IntervalSeconds        int    `json:"intervalSeconds"`
	AutoReconnect          bool   `json:"autoReconnect"`
	AutoStart              bool   `json:"autoStart"`
	StartMinimized         bool   `json:"startMinimized"`
	AccountIndex           int    `json:"accountIndex"`
	ProbeMode              string `json:"probeMode"`
	ProbeHost              string `json:"probeHost"`
	ProbeHttpUrl           string `json:"probeHttpUrl"`
	ProbeAttempts          int    `json:"probeAttempts"`
	ProbeDelayMs           int    `json:"probeDelayMs"`
	DisconnectOnNoInternet bool   `json:"disconnectOnNoInternet"`
	UpdateCheckEnabled     bool   `json:"updateCheckEnabled"`
	UITheme                string `json:"uiTheme"`

	// 代理（仅本应用自身 HTTP 出口：更新检查/下载、HTTP 模式外网探测）。
	// 旧版 settings.json 无这些字段，零值即"未启用"，向后兼容。
	ProxyEnabled bool   `json:"proxyEnabled"`
	ProxyType    string `json:"proxyType"`
	ProxyHost    string `json:"proxyHost"`
	ProxyPort    string `json:"proxyPort"`
	ProxyBypass  string `json:"proxyBypass"`
}

// DefaultSettings 返回全部默认值（与旧版 Builder 默认值一致）。
func DefaultSettings() Settings {
	return Settings{
		IntervalSeconds:        30,
		AutoReconnect:          false,
		AutoStart:              false,
		StartMinimized:         false,
		AccountIndex:           0,
		ProbeMode:              ProbeModeAuto,
		ProbeHost:              DefaultProbeHost,
		ProbeHttpUrl:           DefaultProbeHTTPURL,
		ProbeAttempts:          DefaultProbeAttempts,
		ProbeDelayMs:           DefaultProbeDelayMs,
		DisconnectOnNoInternet: false,
		UpdateCheckEnabled:     true,
		UITheme:                ThemeSystem,
		ProxyType:              ProxyTypeHTTP,
	}
}

// Normalize 返回钳制后的副本：范围收敛、空白回退、非法模式/主题回退默认。
func (s Settings) Normalize() Settings {
	if s.IntervalSeconds < MinIntervalSeconds {
		s.IntervalSeconds = MinIntervalSeconds
	}
	if s.AccountIndex < 0 {
		s.AccountIndex = 0
	}
	s.ProbeMode = NormalizeProbeMode(s.ProbeMode)
	if strings.TrimSpace(s.ProbeHost) == "" {
		s.ProbeHost = DefaultProbeHost
	} else {
		s.ProbeHost = strings.TrimSpace(s.ProbeHost)
	}
	if strings.TrimSpace(s.ProbeHttpUrl) == "" {
		s.ProbeHttpUrl = DefaultProbeHTTPURL
	} else {
		s.ProbeHttpUrl = strings.TrimSpace(s.ProbeHttpUrl)
	}
	if s.ProbeAttempts < 1 {
		s.ProbeAttempts = 1
	}
	if s.ProbeDelayMs < 0 {
		s.ProbeDelayMs = 0
	}
	s.UITheme = NormalizeTheme(s.UITheme)
	host, port := splitProxyEndpoint(s.ProxyHost, s.ProxyPort)
	s.ProxyType = NormalizeProxyType(s.ProxyType)
	s.ProxyHost = host
	s.ProxyPort = normalizeProxyPort(port, s.ProxyType)
	s.ProxyBypass = joinProxyBypass(splitProxyBypass(s.ProxyBypass))
	return s
}

// WithAccountIndex 返回修改了账号索引的副本。
func (s Settings) WithAccountIndex(index int) Settings {
	if index < 0 {
		index = 0
	}
	s.AccountIndex = index
	return s
}

// NormalizeProbeMode 归一探测模式，非法值回退 auto。
func NormalizeProbeMode(mode string) string {
	switch strings.ToLower(strings.TrimSpace(mode)) {
	case ProbeModeICMP:
		return ProbeModeICMP
	case ProbeModeHTTP:
		return ProbeModeHTTP
	case ProbeModeAuto:
		return ProbeModeAuto
	}
	return ProbeModeAuto
}

// NormalizeTheme 归一主题，非法值回退 system。
func NormalizeTheme(theme string) string {
	switch strings.ToLower(strings.TrimSpace(theme)) {
	case ThemeLight:
		return ThemeLight
	case ThemeDark:
		return ThemeDark
	}
	return ThemeSystem
}
