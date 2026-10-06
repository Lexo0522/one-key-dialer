package platform

import (
	"errors"
	"fmt"
	"runtime"
	"strings"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

// 本文件用 wlanapi 原生 API 管理 WiFi(扫描/连接/断开/状态),与 ras.go 的
// rasapi32 同一套手工封包方式。应用以普通用户权限运行,因此配置文件一律
// 写「每用户」档案(WlanSetProfile dwFlags=1),全程不需要管理员权限。

var (
	modwlanapi                      = windows.NewLazySystemDLL("wlanapi.dll")
	procWlanOpenHandle              = modwlanapi.NewProc("WlanOpenHandle")
	procWlanCloseHandle             = modwlanapi.NewProc("WlanCloseHandle")
	procWlanEnumInterfaces          = modwlanapi.NewProc("WlanEnumInterfaces")
	procWlanFreeMemory              = modwlanapi.NewProc("WlanFreeMemory")
	procWlanGetAvailableNetworkList = modwlanapi.NewProc("WlanGetAvailableNetworkList")
	procWlanScan                    = modwlanapi.NewProc("WlanScan")
	procWlanConnect                 = modwlanapi.NewProc("WlanConnect")
	procWlanDisconnect              = modwlanapi.NewProc("WlanDisconnect")
	procWlanSetProfile              = modwlanapi.NewProc("WlanSetProfile")
	procWlanQueryInterface          = modwlanapi.NewProc("WlanQueryInterface")
)

// DOT11_AUTH_ALGORITHM(仅列出会遇到的取值)。
const (
	dot11AuthOpen    = 1
	dot11AuthShared  = 2
	dot11AuthWPA     = 3 // WPA-Enterprise
	dot11AuthWPAPSK  = 4
	dot11AuthRSNA    = 6  // WPA2-Enterprise
	dot11AuthRSNAPSK = 7  // WPA2-PSK
	dot11AuthWPA3    = 11 // WPA3-Enterprise
	dot11AuthWPA3SAE = 12 // WPA3-SAE
)

// WLAN_AVAILABLE_NETWORK.dwFlags 位。
const (
	wlanAvailNetConnected  = 0x1
	wlanAvailNetHasProfile = 0x2
)

// WLAN_INTERFACE_STATE(部分)。
const (
	wlanIfaceStateConnected      = 1
	wlanIfaceStateDisconnecting  = 3
	wlanIfaceStateDisconnected   = 4
	wlanIfaceStateAssociating    = 5
	wlanIfaceStateDiscovering    = 6
	wlanIfaceStateAuthenticating = 7
)

// wlan_intf_opcode(WLAN_INTF_OPCODE,autoconf 段)。
const wlanIntfOpcodeInterfaceState = 4

// WlanNetwork 一条扫描结果(前端视图)。
type WlanNetwork struct {
	Ssid          string `json:"ssid"`
	SignalQuality int    `json:"signalQuality"` // 0-100,近似百分比
	Secured       bool   `json:"secured"`
	Connected     bool   `json:"connected"`
	HasProfile    bool   `json:"hasProfile"`
	Auth          string `json:"auth"`
}

// WlanState 无线网卡当前状态。Phase: idle/connecting/connected/disconnecting。
type WlanState struct {
	Connected     bool   `json:"connected"`
	Ssid          string `json:"ssid"`
	SignalQuality int    `json:"signalQuality"`
	Phase         string `json:"phase"`
	// Err 非空表示状态查询本身失败(区别于"确实未连接")。
	Err string `json:"err,omitempty"`
}

// wlanInterfaceInfo 与 C 的 WLAN_INTERFACE_INFO 布局一致(GUID 16 +
// WCHAR[256] 512 + 枚举 4 = 532 字节,x86/x64 相同)。
type wlanInterfaceInfo struct {
	Guid  windows.GUID
	Descr [256]uint16
	State uint32
}

// wlanAvailableNetwork 与 C 的 WLAN_AVAILABLE_NETWORK 布局一致,
// 全部成员按 4 字节对齐,总长 628 字节(x86/x64 相同)。
type wlanAvailableNetwork struct {
	ProfileName            [256]uint16
	SsidLength             uint32
	Ssid                   [32]byte
	BssType                uint32
	NumberOfBssids         uint32
	NetworkConnectable     uint32
	NotConnectableReason   uint32
	NumberOfPhyTypes       uint32
	PhyTypes               [8]uint32
	MorePhyTypes           uint32
	SignalQuality          uint32
	SecurityEnabled        uint32
	DefaultAuthAlgorithm   uint32
	DefaultCipherAlgorithm uint32
	Flags                  uint32
	Reserved               uint32
}

// wlanConnectionParameters 与 C 的 WLAN_CONNECTION_PARAMETERS 布局一致:
// x64 上 Mode 后有 4 字节填充(指针 8 字节对齐),x86 上紧排。
type wlanConnectionParameters struct {
	Mode             uint32  // WLAN_CONNECTION_MODE
	Profile          uintptr // LPCWSTR
	Dot11Ssid        uintptr
	DesiredBssidList uintptr
	BssType          uint32 // DOT11_BSS_TYPE,1 = infrastructure
	Flags            uint32
}

// 两个 API 返回列表的元素跨度(unsafe.Sizeof 是编译期常量)。
const (
	wlanInterfaceInfoSize    = unsafe.Sizeof(wlanInterfaceInfo{})
	wlanAvailableNetworkSize = unsafe.Sizeof(wlanAvailableNetwork{})
)

func init() {
	if unsafe.Sizeof(wlanInterfaceInfo{}) != 532 {
		panic("wlan: WLAN_INTERFACE_INFO layout mismatch")
	}
	if unsafe.Sizeof(wlanAvailableNetwork{}) != 628 {
		panic("wlan: WLAN_AVAILABLE_NETWORK layout mismatch")
	}
	want := uintptr(24)
	if unsafe.Sizeof(uintptr(0)) == 8 {
		want = 40
	}
	if unsafe.Sizeof(wlanConnectionParameters{}) != want {
		panic("wlan: WLAN_CONNECTION_PARAMETERS layout mismatch")
	}
}

// WlanAvailable 判断本机是否有可用的无线网卡(WLAN 服务在跑且能枚举接口)。
func WlanAvailable() bool {
	_, _, done, err := wlanOpen()
	if err != nil {
		return false
	}
	done()
	return true
}

// WlanScanList 触发一次刷新扫描并返回网络列表(内部短暂等待让缓存更新)。
func WlanScanList() ([]WlanNetwork, error) {
	h, guid, done, err := wlanOpen()
	if err != nil {
		return nil, err
	}
	defer done()
	// WlanScan 只是触发异步扫描,等一小段再取列表,拿到的结果明显更新。
	// 返回值必须检查:扫描没触发成功时(无线服务未跑/句柄失效)后面的等待
	// 拿到的只是上一轮的陈旧缓存,界面会表现成"点了扫描但列表纹丝不动"。
	r1, _, _ := procWlanScan.Call(h, uintptr(unsafe.Pointer(&guid)), 0, 0, 0)
	if r1 != 0 {
		return nil, wlanErr("WlanScan", uint32(r1))
	}
	time.Sleep(1200 * time.Millisecond)
	nets, err := wlanListDetails(h, guid)
	if err != nil {
		return nil, err
	}
	out := make([]WlanNetwork, 0, len(nets))
	for _, d := range nets {
		out = append(out, d.net)
	}
	return out, nil
}

// WlanCurrent 返回当前连接的 SSID/信号与连接阶段。
func WlanCurrent() (WlanState, error) {
	h, guid, done, err := wlanOpen()
	if err != nil {
		return WlanState{}, err
	}
	defer done()
	return wlanCurrentOn(h, guid), nil
}

// WlanDisconnect 断开当前无线连接。
func WlanDisconnect() error {
	h, guid, done, err := wlanOpen()
	if err != nil {
		return err
	}
	defer done()
	r1, _, _ := procWlanDisconnect.Call(h, uintptr(unsafe.Pointer(&guid)), 0)
	if r1 != 0 {
		return wlanErr("WlanDisconnect", uint32(r1))
	}
	return nil
}

// WlanConnect 连接指定网络。已有配置且未提供新密码时直接沿用;
// 否则按网络的认证类型生成配置并写入(每用户,覆盖同名),再发起连接,
// 轮询确认最多 15 秒。开放网络忽略 password。
func WlanConnect(ssid, password string) error {
	ssid = strings.TrimSpace(ssid)
	if ssid == "" {
		return errors.New("wlan: empty ssid")
	}
	h, guid, done, err := wlanOpen()
	if err != nil {
		return err
	}
	defer done()

	nets, lerr := wlanListDetails(h, guid)
	if lerr != nil {
		return lerr
	}
	var known *wlanNetDetail
	for i := range nets {
		if nets[i].net.Ssid == ssid {
			known = &nets[i]
			break
		}
	}

	if known != nil && known.net.HasProfile && (password == "" || !known.net.Secured) {
		// 已有配置(含 Windows 自己保存的):直接沿用,不覆盖
		return wlanConnectProfile(h, guid, ssid)
	}

	profileXml, perr := wlanProfileFor(known, ssid, password)
	if perr != nil {
		return perr
	}
	xmlPtr, err := windows.UTF16PtrFromString(profileXml)
	if err != nil {
		return err
	}
	// dwFlags=1 → 每用户档案,普通用户即可写入;strAllUserProfileSecurity 必须为 NULL
	var reason uint32
	r1, _, _ := procWlanSetProfile.Call(h, uintptr(unsafe.Pointer(&guid)), 1,
		uintptr(unsafe.Pointer(xmlPtr)), 0, 1, 0, uintptr(unsafe.Pointer(&reason)))
	if r1 != 0 {
		return wlanErr("WlanSetProfile", uint32(r1))
	}
	return wlanConnectProfile(h, guid, ssid)
}

// wlanProfileFor 为目标网络选择配置类型并生成 XML。
func wlanProfileFor(known *wlanNetDetail, ssid, password string) (string, error) {
	if known == nil {
		// 扫描列表里不可见(刚消失/隐藏网络):有密码按 WPA2-PSK 兜底,无密码按开放
		if password != "" {
			return buildWlanProfileXml(ssid, "WPA2PSK", "AES", password), nil
		}
		return buildWlanProfileXml(ssid, "open", "none", ""), nil
	}
	if !known.net.Secured {
		return buildWlanProfileXml(ssid, "open", "none", ""), nil
	}
	switch known.auth {
	case dot11AuthWPAPSK:
		if password == "" {
			return "", errors.New("wlan: password required")
		}
		return buildWlanProfileXml(ssid, "WPA", "TKIP", password), nil
	case dot11AuthRSNAPSK:
		if password == "" {
			return "", errors.New("wlan: password required")
		}
		return buildWlanProfileXml(ssid, "WPA2PSK", "AES", password), nil
	case dot11AuthWPA3SAE:
		if password == "" {
			return "", errors.New("wlan: password required")
		}
		return buildWlanProfileXml(ssid, "SAE", "AES", password), nil
	case dot11AuthOpen, dot11AuthShared:
		return "", errors.New("wlan: wep unsupported")
	default:
		// WPA / RSNA / WPA3 均为 802.1X 企业认证,需要证书/域凭据,不在简易连接范围
		return "", errors.New("wlan: enterprise unsupported")
	}
}

// wlanConnectProfile 用名为 ssid 的配置发起连接并轮询确认。
func wlanConnectProfile(h uintptr, guid windows.GUID, ssid string) error {
	name, err := windows.UTF16PtrFromString(ssid)
	if err != nil {
		return err
	}
	params := wlanConnectionParameters{
		Mode:    0, // wlan_connection_mode_profile
		Profile: uintptr(unsafe.Pointer(name)),
		BssType: 1,
		Flags:   0,
	}
	r1, _, _ := procWlanConnect.Call(h, uintptr(unsafe.Pointer(&guid)),
		uintptr(unsafe.Pointer(&params)), 0)
	runtime.KeepAlive(name)
	if r1 != 0 {
		return wlanErr("WlanConnect", uint32(r1))
	}
	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		time.Sleep(500 * time.Millisecond)
		st, serr := wlanIfaceState(h, guid)
		if serr != nil {
			// 查询本身失败时立刻收工:句柄/服务异常下继续轮询只会白等到超时,
			// 而报出去的错误会与真实原因不符。
			return serr
		}
		switch st {
		case wlanIfaceStateConnected:
			if cur := wlanCurrentOn(h, guid); cur.Ssid == ssid {
				return nil
			}
		}
	}
	return errors.New("wlan: connect timeout")
}

// ============================ 内部辅助 ============================

type wlanNetDetail struct {
	net  WlanNetwork
	auth uint32
}

// wlanOpen 打开客户端句柄并返回第一个无线接口;done() 释放句柄。
func wlanOpen() (h uintptr, guid windows.GUID, done func(), err error) {
	var negotiated uint32
	r1, _, _ := procWlanOpenHandle.Call(2, 0,
		uintptr(unsafe.Pointer(&negotiated)), uintptr(unsafe.Pointer(&h)))
	if r1 != 0 {
		return 0, windows.GUID{}, nil, wlanErr("WlanOpenHandle", uint32(r1))
	}
	cleanup := func() { _, _, _ = procWlanCloseHandle.Call(h, 0) }
	guid, err = wlanFirstInterface(h)
	if err != nil {
		cleanup()
		return 0, windows.GUID{}, nil, err
	}
	return h, guid, cleanup, nil
}

// wlanFirstInterface 枚举无线接口并返回第一个的 GUID。
// 出参一律声明为 unsafe.Pointer 由 API 直接写入,避免 uintptr→指针的
// 转换(go vet unsafeptr 会拦截,且语义上本就是 API 分配的指针)。
func wlanFirstInterface(h uintptr) (windows.GUID, error) {
	var list unsafe.Pointer
	r1, _, _ := procWlanEnumInterfaces.Call(h, 0, uintptr(unsafe.Pointer(&list)))
	if r1 != 0 {
		return windows.GUID{}, wlanErr("WlanEnumInterfaces", uint32(r1))
	}
	if list == nil {
		return windows.GUID{}, errors.New("wlan: no interface list")
	}
	defer func() { _, _, _ = procWlanFreeMemory.Call(uintptr(list)) }()
	// 列表头:DWORD dwNumberOfItems; DWORD dwIndex; 元素紧随其后(偏移 8)
	hdr := (*[2]uint32)(list)
	if hdr[0] < 1 {
		return windows.GUID{}, errors.New("wlan: no wireless interface")
	}
	buf := unsafe.Slice((*byte)(list), 8+wlanInterfaceInfoSize)
	return (*wlanInterfaceInfo)(unsafe.Pointer(&buf[8])).Guid, nil
}

// wlanListDetails 返回去重后的网络明细(同一 SSID 可能因多认证类型出现多行)。
func wlanListDetails(h uintptr, guid windows.GUID) ([]wlanNetDetail, error) {
	var listPtr unsafe.Pointer
	r1, _, _ := procWlanGetAvailableNetworkList.Call(h, uintptr(unsafe.Pointer(&guid)),
		3, 0, uintptr(unsafe.Pointer(&listPtr))) // flags=3: 含 ad hoc 与隐藏
	if r1 != 0 {
		return nil, wlanErr("WlanGetAvailableNetworkList", uint32(r1))
	}
	if listPtr == nil {
		return nil, nil
	}
	defer func() { _, _, _ = procWlanFreeMemory.Call(uintptr(listPtr)) }()
	hdr := (*[2]uint32)(listPtr)
	n := int(hdr[0])
	if n <= 0 {
		return nil, nil
	}
	buf := unsafe.Slice((*byte)(listPtr), 8+n*int(wlanAvailableNetworkSize))
	out := make([]wlanNetDetail, 0, n)
	bySsid := map[string]int{}
	for i := 0; i < n; i++ {
		row := (*wlanAvailableNetwork)(unsafe.Pointer(&buf[8+i*int(wlanAvailableNetworkSize)]))
		if row.SsidLength == 0 || int(row.SsidLength) > len(row.Ssid) {
			continue
		}
		ssid := string(row.Ssid[:row.SsidLength])
		item := wlanNetDetail{
			net: WlanNetwork{
				Ssid:          ssid,
				SignalQuality: int(row.SignalQuality),
				Secured:       row.SecurityEnabled != 0,
				Connected:     row.Flags&wlanAvailNetConnected != 0,
				HasProfile:    row.Flags&wlanAvailNetHasProfile != 0,
				Auth:          wlanAuthLabel(row.DefaultAuthAlgorithm),
			},
			auth: row.DefaultAuthAlgorithm,
		}
		if idx, ok := bySsid[ssid]; ok {
			// 保留更有价值的一行:已连接 > 已有配置 > 信号更强
			if score(item.net) > score(out[idx].net) {
				out[idx] = item
			}
			continue
		}
		bySsid[ssid] = len(out)
		out = append(out, item)
	}
	return out, nil
}

// score 去重时比较两行扫描结果的价值。
func score(n WlanNetwork) int {
	v := n.SignalQuality
	if n.HasProfile {
		v += 10000
	}
	if n.Connected {
		v += 1000000
	}
	return v
}

// wlanCurrentOn 在已打开的句柄上查询当前连接状态。
func wlanCurrentOn(h uintptr, guid windows.GUID) WlanState {
	st, err := wlanIfaceState(h, guid)
	if err != nil {
		// 查询失败与"确实未连接"必须区分:前者是异常,不能当成 idle 展示给用户。
		return WlanState{Phase: "unknown", Err: err.Error()}
	}
	state := WlanState{Phase: wlanPhaseLabel(st)}
	if st == wlanIfaceStateConnected {
		state.Connected = true
	}
	nets, err := wlanListDetails(h, guid)
	if err == nil {
		for _, d := range nets {
			if d.net.Connected {
				state.Ssid = d.net.Ssid
				state.SignalQuality = d.net.SignalQuality
				state.Connected = true
				break
			}
		}
	}
	return state
}

// wlanIfaceState 查询接口的 WLAN_INTERFACE_STATE。
// 查询失败返回 error 而不是伪装成 disconnected:调用方(pollConnect 的状态轮询)
// 需要区分"查不到"和"确实没连",否则句柄失效会让连接轮询白等满超时。
func wlanIfaceState(h uintptr, guid windows.GUID) (uint32, error) {
	var written uint32
	var data unsafe.Pointer
	r1, _, _ := procWlanQueryInterface.Call(h, uintptr(unsafe.Pointer(&guid)),
		wlanIntfOpcodeInterfaceState, 0, uintptr(unsafe.Pointer(&written)),
		uintptr(unsafe.Pointer(&data)), 0)
	if r1 != 0 {
		return 0, wlanErr("WlanQueryInterface", uint32(r1))
	}
	if data == nil {
		return 0, errors.New("wlan: WlanQueryInterface returned no data")
	}
	defer func() { _, _, _ = procWlanFreeMemory.Call(uintptr(data)) }()
	return *(*uint32)(data), nil
}

func wlanPhaseLabel(state uint32) string {
	switch state {
	case wlanIfaceStateConnected:
		return "connected"
	case wlanIfaceStateAssociating, wlanIfaceStateDiscovering, wlanIfaceStateAuthenticating:
		return "connecting"
	case wlanIfaceStateDisconnecting:
		return "disconnecting"
	default:
		return "idle"
	}
}

func wlanAuthLabel(auth uint32) string {
	switch auth {
	case dot11AuthOpen:
		return "Open"
	case dot11AuthShared:
		return "WEP"
	case dot11AuthWPAPSK:
		return "WPA-PSK"
	case dot11AuthRSNAPSK:
		return "WPA2-PSK"
	case dot11AuthWPA3SAE:
		return "WPA3-SAE"
	case dot11AuthWPA, dot11AuthRSNA, dot11AuthWPA3:
		return "802.1X"
	default:
		return fmt.Sprintf("0x%x", auth)
	}
}

// wlanErr 把常见的 Win32 错误码翻成可读文本。
func wlanErr(proc string, code uint32) error {
	hint := ""
	switch code {
	case 1062: // ERROR_SERVICE_NOT_ACTIVE
		hint = "WLAN AutoConfig service is not running"
	case 5: // ERROR_ACCESS_DENIED
		hint = "access denied"
	case 87: // ERROR_INVALID_PARAMETER
		hint = "invalid parameter"
	case 2, 1168: // ERROR_NOT_FOUND
		hint = "not found"
	case 1223: // ERROR_CANCELLED
		hint = "cancelled"
	}
	if hint != "" {
		return fmt.Errorf("%s failed (%d): %s", proc, code, hint)
	}
	return fmt.Errorf("%s failed (%d)", proc, code)
}

// buildWlanProfileXml 生成 WLAN 配置文件 XML。auth/cipher 取值:
// open+none / WPA+TKIP / WPA2PSK+AES / SAE+AES。
func buildWlanProfileXml(ssid, auth, cipher, password string) string {
	var sb strings.Builder
	sb.WriteString("<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n")
	sb.WriteString("<WLANProfile xmlns=\"http://www.microsoft.com/networking/WLAN/profile/v1\">\r\n")
	sb.WriteString("\t<name>" + xmlEscape(ssid) + "</name>\r\n")
	sb.WriteString("\t<SSIDConfig>\r\n\t\t<SSID>\r\n\t\t\t<name>" + xmlEscape(ssid) + "</name>\r\n\t\t</SSID>\r\n\t</SSIDConfig>\r\n")
	sb.WriteString("\t<connectionType>ESS</connectionType>\r\n")
	sb.WriteString("\t<connectionMode>auto</connectionMode>\r\n")
	sb.WriteString("\t<MSM>\r\n\t\t<security>\r\n")
	sb.WriteString("\t\t\t<authEncryption>\r\n")
	sb.WriteString("\t\t\t\t<authentication>" + auth + "</authentication>\r\n")
	sb.WriteString("\t\t\t\t<encryption>" + cipher + "</encryption>\r\n")
	sb.WriteString("\t\t\t\t<useOneX>false</useOneX>\r\n")
	sb.WriteString("\t\t\t</authEncryption>\r\n")
	if password != "" {
		sb.WriteString("\t\t\t<sharedKey>\r\n")
		sb.WriteString("\t\t\t\t<keyType>passPhrase</keyType>\r\n")
		sb.WriteString("\t\t\t\t<protected>false</protected>\r\n")
		sb.WriteString("\t\t\t\t<keyMaterial>" + xmlEscape(password) + "</keyMaterial>\r\n")
		sb.WriteString("\t\t\t</sharedKey>\r\n")
	}
	sb.WriteString("\t\t</security>\r\n\t</MSM>\r\n</WLANProfile>\r\n")
	return sb.String()
}

// xmlEscape 转义 XML 文本节点与属性的五个保留字符。
func xmlEscape(s string) string {
	r := strings.NewReplacer(
		"&", "&amp;",
		"<", "&lt;",
		">", "&gt;",
		"\"", "&quot;",
		"'", "&apos;",
	)
	return r.Replace(s)
}
