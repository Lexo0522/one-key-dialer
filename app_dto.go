package main

// app_dto.go：Wails 绑定门面的全部数据传输对象（DTO）。
//
// 前后端事件 / 方法签名里出现的可序列化结构体集中在此；行为实现保留在
// app.go / app_diag.go / app_ui.go / app_internal.go 中。

// BroadbandCredentialDTO 宽带拨号凭据视图（明文密码不出后端）。
type BroadbandCredentialDTO struct {
	Username    string `json:"username"`
	HasPassword bool   `json:"hasPassword"`
}

// AppState 前端首帧需要的全部状态。
type AppState struct {
	Version          string                 `json:"version"`
	DisplayVersion   string                 `json:"displayVersion"`
	Settings         model.Settings         `json:"settings"`
	Broadband        BroadbandCredentialDTO `json:"broadband"`
	Online           bool                   `json:"online"`
	SysOnline        bool                   `json:"sysOnline"`
	Logs             []service.LogLine      `json:"logs"`
	AutoStartEnabled bool                   `json:"autoStartEnabled"`
	Theme            string                 `json:"theme"`
	Lang             string                 `json:"lang"`
	DataDir          string                 `json:"dataDir"`
	UpdatesDir       string                 `json:"updatesDir"`
}

// StatusPayload 连接状态事件负载。
// SysOnline 表示系统已通过网口直连联网（非本应用拨号），流量监控按
// Online ∨ SysOnline 的"有效在线"口径放行。
type StatusPayload struct {
	Online    bool   `json:"online"`
	SysOnline bool   `json:"sysOnline,omitempty"`
	Phase     string `json:"phase"`
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

// SiteLatencyDTO 网站测速单站点结果；LatencyMs 为 -1 表示失败或超时。
// 站点列表由前端配置（settings.speedSites），按 URL 对应回结果。
type SiteLatencyDTO struct {
	Url       string `json:"url"`
	LatencyMs int64  `json:"latencyMs"`
}

// IPInfoDTO 公网出口 IP 与归属信息（直连查询，不含代理出口）。
type IPInfoDTO struct {
	Ip         string `json:"ip"`
	Country    string `json:"country"`
	RegionName string `json:"regionName"`
	City       string `json:"city"`
	Isp        string `json:"isp"`
	As         string `json:"as"`
	Timezone   string `json:"timezone"`
	LocalIp    string `json:"localIp"`
}

// UpdatePayload 更新流程事件负载。
// Kind: checking | result | status | progress | canceled | error | done | installing。
// Stage: UpdateStage* 之一，用于区分同一条进度通道上的不同阶段。
// Interactive 标记本次检查由用户主动发起（托盘菜单 / 设置页按钮）：前端据此
// 决定「无新版」结果是否弹出提示——静默检查只需要它复位「正在检查更新…」。
type UpdatePayload struct {
	Kind            string `json:"kind"`
	Stage           string `json:"stage,omitempty"`
	Message         string `json:"message"`
	Title           string `json:"title"`
	Body            string `json:"body"`
	Tray            bool   `json:"tray"`
	Interactive     bool   `json:"interactive,omitempty"`
	Downloaded      int64  `json:"downloaded"`
	Total           int64  `json:"total"`
	UpdateAvailable bool   `json:"updateAvailable"`
	CanInstall      bool   `json:"canInstall"`
	AssetName       string `json:"assetName"`
	AssetSize       int64  `json:"assetSize"`
	ReleaseURL      string `json:"releaseUrl"`
	Path            string `json:"path"`
}


// DeviceOption 可选择的 PPPoE 设备。
type DeviceOption struct {
	Port     string `json:"port"`
	Device   string `json:"device"`
	Existing bool   `json:"existing"`
	Default  bool   `json:"default"`
	Current  bool   `json:"current"`
}
