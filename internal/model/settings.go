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
	ProbeMode              string `json:"probeMode"`
	ProbeHost              string `json:"probeHost"`
	ProbeHttpUrl           string `json:"probeHttpUrl"`
	ProbeAttempts          int    `json:"probeAttempts"`
	ProbeDelayMs           int    `json:"probeDelayMs"`
	DisconnectOnNoInternet bool   `json:"disconnectOnNoInternet"`
	UpdateCheckEnabled     bool   `json:"updateCheckEnabled"`
	UITheme                string `json:"uiTheme"`
	// PPPoE 拨号设备（写入 RAS 电话簿的 PreferredPort / PreferredDevice）。
	// 旧版 settings.json 无这两个字段，空串即"自动探测"，向后兼容。
	PppoePort   string `json:"pppoePort"`
	PppoeDevice string `json:"pppoeDevice"`

	// 代理（仅本应用自身 HTTP 出口：更新检查/下载、HTTP 模式外网探测）。
	// 旧版 settings.json 无这些字段，零值即"未启用"，向后兼容。
	ProxyEnabled bool   `json:"proxyEnabled"`
	ProxyType    string `json:"proxyType"`
	ProxyHost    string `json:"proxyHost"`
	ProxyPort    string `json:"proxyPort"`
	ProxyBypass  string `json:"proxyBypass"`

	// 低内存渲染:UI 进程禁用 GPU 合成(CPU 软渲染),少一个 GPU 子进程,
	// 打开窗口期间再省 ~20-40MB。旧版无此字段,零值即"关闭",向后兼容。
	LowMemRender bool `json:"lowMemRender"`

	// WiFi 与门户自动认证。旧版 settings.json 无这些字段,零值即"未启用",
	// 向后兼容;全部在 WiFi 页配置。
	WifiAutoConnect   bool   `json:"wifiAutoConnect"`   // 断网/开机后自动连接首选 WiFi
	WifiPreferredSsid string `json:"wifiPreferredSsid"` // 首选 WiFi 的 SSID
	PortalAuthEnabled bool   `json:"portalAuthEnabled"` // 启用门户自动认证
	PortalLoginUrl    string `json:"portalLoginUrl"`    // 登录请求地址,支持 {portal} 占位符
	PortalMethod      string `json:"portalMethod"`      // GET / POST
	PortalBody        string `json:"portalBody"`        // 请求体模板,支持 {username} {password} {portal}
	PortalHeaders     string `json:"portalHeaders"`     // 附加请求头,每行一个 "Key: Value"
	PortalSuccessHint string `json:"portalSuccessHint"` // 响应包含该字符串视为成功(可空)

	// 首页网站测速的自定义测试点。空列表 = 前端使用内置默认站点；
	// 保存过就完全以前端提交的列表为准（可增删内置站点）。
	// 旧版 settings.json 无此字段，nil 即"默认站点"，向后兼容。
	SpeedSites []SpeedSite `json:"speedSites"`
}

// SpeedSite 自定义测速点（Clash 同款「自建测试点」）：展示名 + 完整 URL。
type SpeedSite struct {
	Name string `json:"name"`
	Url  string `json:"url"`
}

// MaxSpeedSites 测试点数量上限（与 service 侧单次测速上限一致）。
const MaxSpeedSites = 12

// DefaultSettings 返回全部默认值（与旧版 Builder 默认值一致）。
func DefaultSettings() Settings {
	return Settings{
		IntervalSeconds:        30,
		AutoReconnect:          false,
		AutoStart:              false,
		StartMinimized:         false,
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
	s.PppoePort = strings.TrimSpace(s.PppoePort)
	s.PppoeDevice = strings.TrimSpace(s.PppoeDevice)
	host, port := splitProxyEndpoint(s.ProxyHost, s.ProxyPort)
	s.ProxyType = NormalizeProxyType(s.ProxyType)
	s.ProxyHost = host
	s.ProxyPort = normalizeProxyPort(port, s.ProxyType)
	s.ProxyBypass = joinProxyBypass(splitProxyBypass(s.ProxyBypass))
	s.WifiPreferredSsid = strings.TrimSpace(s.WifiPreferredSsid)
	s.PortalLoginUrl = strings.TrimSpace(s.PortalLoginUrl)
	s.PortalMethod = NormalizePortalMethod(s.PortalMethod)
	s.PortalBody = strings.TrimSpace(s.PortalBody)
	s.PortalHeaders = strings.TrimSpace(s.PortalHeaders)
	s.PortalSuccessHint = strings.TrimSpace(s.PortalSuccessHint)
	s.SpeedSites = NormalizeSpeedSites(s.SpeedSites)
	return s
}

// NormalizeSpeedSites 清洗自定义测试点：去空白、去无效 URL、去重（按 URL）、截断到上限。
func NormalizeSpeedSites(sites []SpeedSite) []SpeedSite {
	if len(sites) == 0 {
		return nil
	}
	out := make([]SpeedSite, 0, len(sites))
	seen := make(map[string]bool, len(sites))
	for _, s := range sites {
		s.Name = strings.TrimSpace(s.Name)
		s.Url = strings.TrimSpace(s.Url)
		if !isProbeURL(s.Url) || seen[s.Url] {
			continue
		}
		seen[s.Url] = true
		out = append(out, s)
		if len(out) >= MaxSpeedSites {
			break
		}
	}
	if len(out) == 0 {
		return nil
	}
	return out
}

// isProbeURL 只接受 http/https 测试地址。
func isProbeURL(u string) bool {
	lower := strings.ToLower(u)
	return strings.HasPrefix(lower, "http://") || strings.HasPrefix(lower, "https://")
}

// PppoeDeviceSet 判断是否保存了显式的 PPPoE 设备选择（端口与设备名都非空）。
// 未设置时由后端自动探测，行为与旧版本一致。
func (s Settings) PppoeDeviceSet() bool {
	return s.PppoePort != "" && s.PppoeDevice != ""
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

// 门户认证请求方法。
const (
	PortalMethodGet  = "GET"
	PortalMethodPost = "POST"
)

// NormalizePortalMethod 归一门户请求方法，非法值/空值回退 POST。
func NormalizePortalMethod(method string) string {
	switch strings.ToUpper(strings.TrimSpace(method)) {
	case PortalMethodGet:
		return PortalMethodGet
	case PortalMethodPost:
		return PortalMethodPost
	}
	return PortalMethodPost
}

// WifiAutoConnectSet 判断自动连接 WiFi 是否具备完整条件（开关 + 首选 SSID）。
func (s Settings) WifiAutoConnectSet() bool {
	return s.WifiAutoConnect && strings.TrimSpace(s.WifiPreferredSsid) != ""
}
