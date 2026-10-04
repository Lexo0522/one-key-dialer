package platform

import (
	"context"
	"encoding/binary"
	"net"
	"net/netip"
	"strings"
	"syscall"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

// 本文件用 iphlpapi 原生 API 替代 cmd /c netstat -e 与 ping.exe 子进程:
// 每 3 秒拉起一轮 cmd.exe + netstat.exe + conhost 的驻留抖动是常驻内存
// 与 CPU 的持续损耗,原生调用一次 syscall 即可完成。

var (
	modiphlpapi         = windows.NewLazySystemDLL("iphlpapi.dll")
	procGetIfTable      = modiphlpapi.NewProc("GetIfTable")
	procIcmpCreateFile  = modiphlpapi.NewProc("IcmpCreateFile")
	procIcmpSendEcho    = modiphlpapi.NewProc("IcmpSendEcho")
	procIcmpCloseHandle = modiphlpapi.NewProc("IcmpCloseHandle")
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
	procGetIfTable.Call(0, uintptr(unsafe.Pointer(&size)), 0)
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

// EthLink 物理以太网口的插线状态。
type EthLink struct {
	Descr     string
	Up        bool
	SpeedMbps uint64
}

// EthernetLinks 列出物理以太网口(IF_TYPE_ETHERNET_CSMACD)及插线状态。
// OperStatus == OPERATIONAL 视为已插线;RAS 电话簿里的 WAN Miniport 端口
// 与物理网卡没有系统级映射,插线状态只能作为选卡参考独立展示。
// 失败返回 nil。
func EthernetLinks() []EthLink {
	buf, num, ok := getIfTableBytes()
	if !ok {
		return nil
	}
	var out []EthLink
	for i := 0; i < num; i++ {
		off := 4 + i*int(mibIfRowSize)
		if off+int(mibIfRowSize) > len(buf) {
			break
		}
		row := (*mibIfRow)(unsafe.Pointer(&buf[off]))
		if row.Type != ifTypeEthernet {
			continue
		}
		descrLen := row.DescrLen
		if descrLen > uint32(len(row.Descr)) {
			descrLen = uint32(len(row.Descr))
		}
		descr := strings.TrimSpace(string(row.Descr[:descrLen]))
		if descr == "" {
			continue
		}
		out = append(out, EthLink{
			Descr:     descr,
			Up:        row.OperStatus == ifOperOperat,
			SpeedMbps: uint64(row.Speed) / 1_000_000,
		})
	}
	return out
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
	defer procIcmpCloseHandle.Call(h)

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
