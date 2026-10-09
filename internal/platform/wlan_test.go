package platform

import (
	"encoding/binary"
	"runtime"
	"strings"
	"testing"
	"unsafe"
)

func TestBuildWlanProfileXmlOpen(t *testing.T) {
	xml := buildWlanProfileXml("Campus-WiFi", "open", "none", "")
	if !strings.Contains(xml, "<authentication>open</authentication>") {
		t.Fatalf("open auth missing: %s", xml)
	}
	if !strings.Contains(xml, "<encryption>none</encryption>") {
		t.Fatalf("open cipher missing: %s", xml)
	}
	if strings.Contains(xml, "sharedKey") {
		t.Fatalf("open profile must not contain sharedKey: %s", xml)
	}
	if !strings.Contains(xml, "<name>Campus-WiFi</name>") {
		t.Fatalf("ssid name missing: %s", xml)
	}
}

func TestBuildWlanProfileXmlWpa2(t *testing.T) {
	xml := buildWlanProfileXml("Lab-5G", "WPA2PSK", "AES", "secret123")
	for _, want := range []string{
		"<authentication>WPA2PSK</authentication>",
		"<encryption>AES</encryption>",
		"<keyType>passPhrase</keyType>",
		"<protected>false</protected>",
		"<keyMaterial>secret123</keyMaterial>",
	} {
		if !strings.Contains(xml, want) {
			t.Fatalf("want %q in: %s", want, xml)
		}
	}
}

func TestBuildWlanProfileXmlEscape(t *testing.T) {
	xml := buildWlanProfileXml(`a<b>&"c"`, "WPA2PSK", "AES", `p&w<x>`)
	if strings.Contains(xml, `a<b>`) || strings.Contains(xml, `p&w<x>`) {
		t.Fatalf("raw xml-sensitive characters leaked: %s", xml)
	}
	if !strings.Contains(xml, `a&lt;b&gt;&amp;&quot;c&quot;`) {
		t.Fatalf("ssid not escaped: %s", xml)
	}
	if !strings.Contains(xml, `<keyMaterial>p&amp;w&lt;x&gt;</keyMaterial>`) {
		t.Fatalf("password not escaped: %s", xml)
	}
}

func TestWlanProfileForEnterpriseRejected(t *testing.T) {
	// 802.1X 企业网络必须给出明确错误,而不是生成一份必然失败的配置
	_, err := wlanProfileFor(&wlanNetDetail{
		net:  WlanNetwork{Ssid: "Campus-1X", Secured: true},
		auth: dot11AuthRSNA,
	}, "Campus-1X", "whatever")
	if err == nil || !strings.Contains(err.Error(), "enterprise") {
		t.Fatalf("want enterprise unsupported error, got %v", err)
	}
}

func TestWlanProfileForKnownTypes(t *testing.T) {
	cases := []struct {
		auth uint32
		want string
	}{
		{dot11AuthWPAPSK, "<authentication>WPA</authentication>"},
		{dot11AuthRSNAPSK, "<authentication>WPA2PSK</authentication>"},
		{dot11AuthWPA3SAE, "<authentication>SAE</authentication>"},
	}
	for _, c := range cases {
		xml, err := wlanProfileFor(&wlanNetDetail{
			net:  WlanNetwork{Ssid: "n", Secured: true},
			auth: c.auth,
		}, "n", "pw")
		if err != nil {
			t.Fatalf("auth %d: %v", c.auth, err)
		}
		if !strings.Contains(xml, c.want) {
			t.Fatalf("auth %d: want %q in %s", c.auth, c.want, xml)
		}
	}
}

func TestWlanProfileForOpenIgnoresPassword(t *testing.T) {
	xml, err := wlanProfileFor(&wlanNetDetail{
		net: WlanNetwork{Ssid: "open-net", Secured: false},
	}, "open-net", "ignored")
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(xml, "sharedKey") || strings.Contains(xml, "ignored") {
		t.Fatalf("open network must not embed a password: %s", xml)
	}
}

// interface_state 查询必须只回 4 字节(单个 WLAN_INTERFACE_STATE)。
// 回归守卫:wlanIntfOpcodeInterfaceState 曾误填 4,实际打到另一个查询项,
// 拿回 772 字节统计块,首 4 字节被当成状态,连接轮询永远等不到 connected,
// 于是连上了也报 "wlan: connect timeout"。无网卡的机器(CI)跳过。
func TestWlanIfaceStateQuerySize(t *testing.T) {
	if runtime.GOOS != "windows" {
		t.Skip("windows only")
	}
	h, guid, done, err := wlanOpen()
	if err != nil {
		t.Skipf("no wireless interface: %v", err)
	}
	defer done()
	var written uint32
	var data unsafe.Pointer
	r1, _, _ := procWlanQueryInterface.Call(h, uintptr(unsafe.Pointer(&guid)),
		wlanIntfOpcodeInterfaceState, 0, uintptr(unsafe.Pointer(&written)),
		uintptr(unsafe.Pointer(&data)), 0)
	if r1 != 0 || data == nil {
		t.Skipf("WlanQueryInterface(interface_state) unavailable: r1=%d", r1)
	}
	defer func() { _, _, _ = procWlanFreeMemory.Call(uintptr(data)) }()
	if written != 4 {
		t.Fatalf("interface_state returned %d bytes, want 4 (opcode drifted?)", written)
	}
}

// WLAN_CONNECTION_ATTRIBUTES 布局锁定。这块结构是"当前连到哪个网络"的
// 权威来源,偏移错了会静默读到别的字段(信号、认证算法都在附近),
// 表现为连接确认偶发误判。数值来自真机(Win11 26100)抓包核对:
// BSSID/信号/认证算法与 netsh wlan show interfaces 逐项一致。
func TestWlanConnectionAttributesLayout(t *testing.T) {
	row := wlanConnectionAttributes{}
	if got := unsafe.Sizeof(row); got != 604 {
		t.Fatalf("WLAN_CONNECTION_ATTRIBUTES size = %d, want 604", got)
	}
	for _, c := range []struct {
		name string
		off  uintptr
		want uintptr
	}{
		{"isState", unsafe.Offsetof(row.isState), 0},
		{"profileName", unsafe.Offsetof(row.profileName), 8},
		{"dot11SsidLength", unsafe.Offsetof(row.dot11SsidLength), 520},
		{"dot11Ssid", unsafe.Offsetof(row.dot11Ssid), 524},
		{"dot11Bssid", unsafe.Offsetof(row.dot11Bssid), 560},
		{"wlanSignalQuality", unsafe.Offsetof(row.wlanSignalQuality), 576},
		{"dot11AuthAlgorithm", unsafe.Offsetof(row.dot11AuthAlgorithm), 596},
	} {
		if c.off != c.want {
			t.Fatalf("%s offset = %d, want %d", c.name, c.off, c.want)
		}
	}
}

// 用真机抓到的 604 字节原始块喂解析器:必须取出 SSID;长度不符、
// 含不可打印字符、全零(未连接)的块都必须被拒绝,让调用方回退扫描列表。
func TestParseWlanCurrentSsid(t *testing.T) {
	buf := make([]byte, 604)
	binary.LittleEndian.PutUint32(buf[0:], wlanIfaceStateConnected) // isState
	copy(buf[8:], []byte{'X', 0, 'C', 0, 'U', 0})                   // profileName 内联 UTF-16
	binary.LittleEndian.PutUint32(buf[520:], 3)                     // uSSIDLength
	copy(buf[524:], []byte("XCU"))                                  // ucSSID
	copy(buf[560:], []byte{0xf8, 0x6e, 0xee, 0xb5, 0x05, 0x11})     // BSSID
	binary.LittleEndian.PutUint32(buf[576:], 83)                    // signal
	binary.LittleEndian.PutUint32(buf[596:], dot11AuthOpen)         // auth

	if got := parseWlanCurrentSsid(buf); got != "XCU" {
		t.Fatalf("parseWlanCurrentSsid = %q, want XCU", got)
	}
	if got := parseWlanCurrentSsid(buf[:603]); got != "" {
		t.Fatalf("truncated blob must not parse, got %q", got)
	}
	bad := make([]byte, len(buf))
	copy(bad, buf)
	bad[524] = 0x01 // SSID 首字节不可打印
	if got := parseWlanCurrentSsid(bad); got != "" {
		t.Fatalf("non-printable ssid must not parse, got %q", got)
	}
	if got := parseWlanCurrentSsid(make([]byte, 604)); got != "" {
		t.Fatalf("zeroed blob (disconnected) must not parse, got %q", got)
	}
}
