package platform

import (
	"context"
	"encoding/binary"
	"net"
	"net/netip"
	"strings"
	"syscall"
	"time"
	"unicode/utf16"
	"unsafe"

	"golang.org/x/sys/windows"
)

// 本文件用 iphlpapi 原生 API 替代 cmd /c netstat -e 与 ping.exe 子进程:
// 每 3 秒拉起一轮 cmd.exe + netstat.exe + conhost 的驻留抖动是常驻内存
// 与 CPU 的持续损耗,原生调用一次 syscall 即可完成。

var (
	modiphlpapi          = windows.NewLazySystemDLL("iphlpapi.dll")
	procGetIfTable       = modiphlpapi.NewProc("GetIfTable")
	procGetIfTable2      = modiphlpapi.NewProc("GetIfTable2")
	procFreeMibTable     = modiphlpapi.NewProc("FreeMibTable")
	procGetBestInterface = modiphlpapi.NewProc("GetBestInterface")
	procIcmpCreateFile   = modiphlpapi.NewProc("IcmpCreateFile")
	procIcmpSendEcho     = modiphlpapi.NewProc("IcmpSendEcho")
	procIcmpCloseHandle  = modiphlpapi.NewProc("IcmpCloseHandle")
)

// mibIfRow 与 Win32 MIB_IFROW 布局一致(ifdef.h,MAX_INTERFACE_NAME_LEN=256、
// MAX_PHYS_ADDR_LEN=8、MAXLEN_IFDESCR=256,x86/x64 通用,总长 860 字节)。
// 只取 InOctets/OutOctets 两项。注意 OutOctets 在偏移 576:InOctets 之后
// 还有 5 个入向包计数器,不能按 netstat -e 的列序紧排——错排会读到
// InUcastPkts(Win10+ 上常为 0),上行速度将恒为 0。
type mibIfRow struct {
	Name            [256]uint16
	Index           uint32
	Type            uint32
	Mtu             uint32
	Speed           uint32
	PhysAddrLen     uint32
	PhysAddr        [8]byte
	AdminStatus     uint32
	OperStatus      uint32
	LastChange      uint32
	InOctets        uint32
	InUcastPkts     uint32
	InNUcastPkts    uint32
	InDiscards      uint32
	InErrors        uint32
	InUnknownProtos uint32
	OutOctets       uint32
	OutUcastPkts    uint32
	OutNUcastPkts   uint32
	OutDiscards     uint32
	OutErrors       uint32
	OutQLen         uint32
	DescrLen        uint32
	Descr           [256]byte
}

const mibIfRowSize = unsafe.Sizeof(mibIfRow{})

const (
	ifTypeEthernet = 6 // IF_TYPE_ETHERNET_CSMACD
	ifOperOperat   = 1 // IF_OPER_STATUS_OPERATIONAL
)

// getIfTableBytes 调用 GetIfTable 返回原始缓冲与接口行数;失败 ok=false。
func getIfTableBytes() ([]byte, int, bool) {
	var size uint32
	// 第一次调用传空缓冲,取所需大小(返回 ERROR_INSUFFICIENT_BUFFER)
	_, _, _ = procGetIfTable.Call(0, uintptr(unsafe.Pointer(&size)), 0)
	if size < uint32(4+mibIfRowSize) || size > 16<<20 {
		return nil, 0, false
	}
	buf := make([]byte, size)
	r1, _, _ := procGetIfTable.Call(uintptr(unsafe.Pointer(&buf[0])),
		uintptr(unsafe.Pointer(&size)), 0)
	if r1 != 0 {
		return nil, 0, false
	}
	num := int(binary.LittleEndian.Uint32(buf))
	return buf, num, true
}

// HostTrafficCounters 返回主机聚合流量计数器 [receivedBytes, sentBytes]。
// 计数器为 32 位(与 netstat -e 相同),约 4GiB 回绕一次,由上层增量逻辑兜底。
// 失败返回 ok=false。
func HostTrafficCounters() (recv, sent int64, ok bool) {
	buf, num, ok := getIfTableBytes()
	if !ok {
		return 0, 0, false
	}
	for i := 0; i < num; i++ {
		off := 4 + i*int(mibIfRowSize)
		if off+int(mibIfRowSize) > len(buf) {
			break
		}
		row := (*mibIfRow)(unsafe.Pointer(&buf[off]))
		recv += int64(row.InOctets)
		sent += int64(row.OutOctets)
	}
	return recv, sent, true
}

// mibIfRow2 与 Win32 MIB_IF_ROW2 布局一致（netioapi.h，x64 步长 1352 字节）。
// 字段偏移经真实机器 GetIfTable2 缓冲区逐字段实测校准：InterfaceGuid 按
// 4 字节对齐紧跟 InterfaceIndex（偏移 12）；PhysicalAddress 之后还有
// PermanentPhysicalAddress[32] 与 MTU 等本功能未用的 60 字节。
// Flags 位域（实测口径）：bit0 HardwareInterface、bit1 EndPointInterface、
// bit2 ConnectorPresent —— 物理网卡=0b101，Hyper-V 扩展适配器=0b010，
// 虚拟交换机 VNIC=0，WAN Miniport=0b10000，区分度完整。
// 接口类型编码在 NET_LUID 高 16 位。
type mibIfRow2 struct {
	InterfaceLuid   uint64      // 0
	InterfaceIndex  uint32      // 8
	InterfaceGuid   [16]byte    // 12
	Alias           [257]uint16 // 28
	Description     [257]uint16 // 542
	PhysAddrLength  uint32      // 1056
	PhysicalAddress [32]byte    // 1060
	_               [60]byte    // 1092..1151 PermanentPhysicalAddress[32] + MTU 等
	Flags           uint32      // 1152
	OperStatus      uint32      // 1156（1 = IF_OPER_STATUS_OPERATIONAL）
	_               uint32      // 1160 AdminStatus
	_               uint32      // 1164 MediaConnectState
	_               [16]byte    // 1168 NetworkGuid
	_               uint32      // 1184 ConnectionType
	_               uint32      // 1188 TunnelType
	Speed           uint64      // 1192（bps）
	_               [19]uint64  // 1200..1351 收发计数器占位
}

const mibIfRow2Size = unsafe.Sizeof(mibIfRow2{})

// 这些偏移全部是手工校准出来的：注释写 1152，字段就靠 [60]byte 占位凑到那儿。
// 一旦有人在中间插字段或改占位长度，偏移会整体错位而编译毫无怨言——
// 表现是物理网卡判定整体失效（Flags 读到了别的字段），或 Speed 读出个
// 荒诞值。所以把注释里的数字变成运行期断言：错就在启动时 panic。
const (
	wantMibIfRow2Size     = 1352
	offMibFlags           = 1152
	offMibOperStatus      = 1156
	offMibSpeed           = 1192
	offMibPhysicalAddress = 1060
	offMibAlias           = 28
	offMibDescription     = 542
)

func init() {
	var row mibIfRow2
	if mibIfRow2Size != wantMibIfRow2Size {
		panic("iphlpapi: MIB_IF_ROW2 stride changed (expected 1352 bytes)")
	}
	checks := []struct {
		name string
		got  uintptr
		want uintptr
	}{
		{"Alias", unsafe.Offsetof(row.Alias), offMibAlias},
		{"Description", unsafe.Offsetof(row.Description), offMibDescription},
		{"PhysAddrLength", unsafe.Offsetof(row.PhysAddrLength), 1056},
		{"PhysicalAddress", unsafe.Offsetof(row.PhysicalAddress), offMibPhysicalAddress},
		{"Flags", unsafe.Offsetof(row.Flags), offMibFlags},
		{"OperStatus", unsafe.Offsetof(row.OperStatus), offMibOperStatus},
		{"Speed", unsafe.Offsetof(row.Speed), offMibSpeed},
	}
	for _, c := range checks {
		if c.got != c.want {
			panic("iphlpapi: MIB_IF_ROW2 field " + c.name + " offset mismatch")
		}
	}
}

const (
	flagHardwareInterface = 1 << 0
	flagConnectorPresent  = 1 << 2
)

// EthLink 物理以太网口的插线状态。
type EthLink struct {
	Descr     string
	Up        bool
	SpeedMbps uint64
}

// walkPhysicalEthernetRows 遍历 GetIfTable2 中所有"物理以太网卡"行并逐行回调。
// 口径与 Get-NetAdapter -Physical 一致：HardwareInterface + ConnectorPresent
// 位域同时为真，且 NET_LUID 高 16 位类型为以太网（6）。
// 返回表是否读取成功；读取失败时不回调，由调用方保守处理。
func walkPhysicalEthernetRows(fn func(row *mibIfRow2)) bool {
	if procGetIfTable2.Find() != nil {
		return false
	}
	var tbl unsafe.Pointer
	if r1, _, _ := procGetIfTable2.Call(uintptr(unsafe.Pointer(&tbl))); r1 != 0 || tbl == nil {
		return false
	}
	defer func() { _, _, _ = procFreeMibTable.Call(uintptr(tbl)) }()

	// 表头为两个 ULONG：NumEntries / TotalNumEntries，行紧随其后
	num := int(binary.LittleEndian.Uint32(unsafe.Slice((*byte)(tbl), 8)))
	if num <= 0 || num > 4096 {
		return false
	}
	for i := 0; i < num; i++ {
		row := (*mibIfRow2)(unsafe.Add(tbl, uintptr(8+i*int(mibIfRow2Size))))
		if uint16(row.InterfaceLuid>>48) != ifTypeEthernet {
			continue
		}
		if row.Flags&(flagHardwareInterface|flagConnectorPresent) !=
			flagHardwareInterface|flagConnectorPresent {
			continue
		}
		fn(row)
	}
	return true
}

// EthernetLinks 列出物理以太网口（仅真实网卡）及插线状态，基于 GetIfTable2。
// 旧版 GetIfTable 的 MIB_IFROW 没有物理/虚拟标记，Hyper-V 虚拟交换网卡与
// WAN Miniport 等软件接口的 dwType 同为 6（以太网），会全部混入；且同一张
// 网卡会产生多行。过滤口径见 walkPhysicalEthernetRows；同一物理网卡
// （同描述 + 同 MAC）多行去重合并，链路状态取并集。
func EthernetLinks() []EthLink {
	out := make([]EthLink, 0, 8)
	index := make(map[string]int, 8)
	ok := walkPhysicalEthernetRows(func(row *mibIfRow2) {
		descr := strings.TrimSpace(utf16Nul(row.Description[:]))
		if descr == "" || !printableASCII(descr) {
			return // 步长异常时的防线：宁可少报也不输出乱码
		}
		macLen := row.PhysAddrLength
		if macLen > uint32(len(row.PhysicalAddress)) {
			macLen = uint32(len(row.PhysicalAddress))
		}
		// 同一张物理网卡会以多个 NDIS 过滤层接口出现（描述带不同过滤层后缀），
		// 按 MAC 去重合并；无 MAC 时退回按描述去重
		key := string(row.PhysicalAddress[:macLen])
		if macLen == 0 {
			key = "\x00" + descr
		}
		up := row.OperStatus == ifOperOperat
		speedMbps := row.Speed / 1_000_000
		if at, ok := index[key]; ok {
			if up {
				out[at].Up = true
			}
			if speedMbps > out[at].SpeedMbps {
				out[at].SpeedMbps = speedMbps
			}
			if len(descr) < len(out[at].Descr) {
				out[at].Descr = descr // 裸网卡名优先于过滤层后缀名
			}
			return
		}
		index[key] = len(out)
		out = append(out, EthLink{Descr: descr, Up: up, SpeedMbps: speedMbps})
	})
	if !ok {
		return nil
	}
	return out
}

// RoutedViaPhysicalNIC 判断访问 dst 的最优路由出口是否为物理以太网口。
// 供"免拨号"判定：网线直连（如家庭宽带由路由器拨号后 DHCP 分配）时出口
// 是物理网口；而 WiFi/VPN 出口联网时不影响用户继续走网口拨号，不算直连。
// dst 支持域名（限时解析）。任何不确定——解析失败、路由查询失败、出口
// 非物理网口——一律返回 false，由调用方保守地维持"需要拨号"的原判定。
func RoutedViaPhysicalNIC(dst string) bool {
	ip, ok := resolveIPv4(dst)
	if !ok {
		return false
	}
	var best uint32
	// IPAddr 为网络字节序 DWORD:按小端打包即为 inet_addr 的返回值
	addr := binary.LittleEndian.Uint32(ip[:])
	if r1, _, _ := procGetBestInterface.Call(uintptr(addr),
		uintptr(unsafe.Pointer(&best))); r1 != 0 || best == 0 {
		return false
	}
	found := false
	walkPhysicalEthernetRows(func(row *mibIfRow2) {
		if row.InterfaceIndex == best {
			found = true
		}
	})
	return found
}

// utf16Nul 解码以 NUL 结尾的 UTF-16 序列。
func utf16Nul(src []uint16) string {
	for i, v := range src {
		if v == 0 {
			return string(utf16.Decode(src[:i]))
		}
	}
	return string(utf16.Decode(src))
}

// printableASCII 判断字符串是否为非空可打印 ASCII（网卡描述来自驱动，均为 ASCII；
// 结构体步长若与系统不符会解出乱码，用此校验兜底）。
func printableASCII(s string) bool {
	if s == "" {
		return false
	}
	for _, r := range s {
		if r < 0x20 || r > 0x7e {
			return false
		}
	}
	return true
}

// IcmpReachable 用 IcmpSendEcho 原生探测主机可达性(与 ping -n 1 -w <timeoutMs> 等价)。
// host 先按 IPv4 字面量解析,失败再走 DNS(2 秒上限,与 ping 解析失败同等处理)。
func IcmpReachable(host string, timeoutMs int) bool {
	ip, ok := resolveIPv4(host)
	if !ok {
		return false
	}
	if timeoutMs < 100 {
		timeoutMs = 1000
	}
	h, _, _ := procIcmpCreateFile.Call()
	if h == 0 || h == ^uintptr(0) {
		return false
	}
	defer func() { _, _, _ = procIcmpCloseHandle.Call(h) }()

	const payloadSize = 32 // 与 ping.exe 默认载荷一致
	req := make([]byte, payloadSize)
	for i := range req {
		req[i] = 'a' + byte(i%26)
	}
	// 回复缓冲:x64 ICMP_ECHO_REPLY 头 40 字节 + 载荷 + IP 选项余量
	reply := make([]byte, 64+payloadSize+8)
	// IPAddr 为网络字节序 DWORD:按小端打包即为 inet_addr 的返回值
	addr := binary.LittleEndian.Uint32(ip[:])
	r1, _, _ := procIcmpSendEcho.Call(h, uintptr(addr),
		uintptr(unsafe.Pointer(&req[0])), payloadSize, 0,
		uintptr(unsafe.Pointer(&reply[0])), uintptr(len(reply)),
		uintptr(timeoutMs))
	if r1 == 0 {
		return false // 0 个回复
	}
	// ICMP_ECHO_REPLY.Status 偏移 4;IP_SUCCESS = 0
	return binary.LittleEndian.Uint32(reply[4:8]) == 0
}

// resolveIPv4 解析主机为 IPv4 四字节;字面量优先,DNS 限时 2 秒。
func resolveIPv4(host string) ([4]byte, bool) {
	var zero [4]byte
	host = strings.TrimSpace(host)
	if host == "" {
		return zero, false
	}
	if ip, err := netip.ParseAddr(host); err == nil {
		if ip.Is4() {
			return ip.As4(), true
		}
		return zero, false
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	addrs, err := net.DefaultResolver.LookupIPAddr(ctx, host)
	if err != nil {
		return zero, false
	}
	for _, a := range addrs {
		if ip := a.IP.To4(); ip != nil {
			var out [4]byte
			copy(out[:], ip)
			return out, true
		}
	}
	return zero, false
}

// OpenInBrowser 用 ShellExecute 打开默认浏览器(代理进程无 Wails 上下文时使用)。
func OpenInBrowser(url string) bool {
	if strings.TrimSpace(url) == "" {
		return false
	}
	utf16Ptr := func(s string) uintptr {
		p, _ := windows.UTF16PtrFromString(s)
		return uintptr(unsafe.Pointer(p))
	}
	r1, _, _ := syscall.Syscall6(procShellExecuteW.Addr(), 6,
		0, utf16Ptr("open"), utf16Ptr(url), 0, 0, windows.SW_SHOWNORMAL)
	return r1 > 32
}

var (
	modshell32        = windows.NewLazySystemDLL("shell32.dll")
	procShellExecuteW = modshell32.NewProc("ShellExecuteW")
)
