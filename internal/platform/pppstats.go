package platform

import (
	"encoding/binary"
	"unicode/utf16"
	"unsafe"
)

// PPP 连接详情数据源：RasEnumConnectionsW 按连接名找回句柄 →
// RasGetConnectionStatisticsW 取链路级统计 → RasGetProjectionInfoW 取 PPP
// 协商出的本机/服务器 IP。句柄不落盘：进程重启后重新枚举即可恢复，
// 覆盖「程序重启但系统 PPPoE 连接仍在」的场景。任一环节失败优雅降级。

var (
	procRasEnumConnectionsW         = rasapi32.NewProc("RasEnumConnectionsW")
	procRasGetConnectionStatisticsW = rasapi32.NewProc("RasGetConnectionStatisticsW")
	procRasGetProjectionInfoW       = rasapi32.NewProc("RasGetProjectionInfoW")
)

// RasStatInfo PPP 连接的链路级统计快照。
type RasStatInfo struct {
	Connected   bool
	LocalIP     string
	ServerIP    string
	Bps         uint64 // 链路协商速率（bps）
	BytesUp     uint64 // 本次连接累计发送字节
	BytesDown   uint64 // 本次连接累计接收字节
	ErrTotal    uint64 // CRC/超时/对齐/硬件过冲/帧错/缓冲过冲合计
	DurationSec uint64 // 连接时长（秒）
}

// RASCONN x64 布局：DWORD dwSize@0、HRASCONN@8（8 字节对齐）、
// szEntryName[257]@16、szDeviceType[17]、szDeviceName[129]，其后为
// Win2000/XP 追加字段（szPhonebook/dwSubEntry/guidEntry/dwFlags/luid/
// guidCorrelationId）。dwSize 传 SDK 完整尺寸（sizeof(RASCONN)=1392），
// 系统按最新布局填充；目标平台为 Win10/11，不再做旧版尺寸回退。
const (
	rasconnSizeX64   = 1392
	rasconnOffHandle = 8
	rasconnOffEntry  = 16 // [257]uint16
	rasconnMaxEntry  = 257

	rasStatsSize = 64 // RAS_STATS：16 × DWORD

	rasPppIpSize      = 80 // RASPPPIPW 含 Win2000 追加的 dwOptions/dwServerOptions
	rasPppIpOffLocal  = 8  // szIpAddress[16]
	rasPppIpOffServer = 40 // szServerIpAddress[16]

	raspPppIp = 0x8021 // RASP_PppIp（PPP 协议号）
)

// ConnectionStats 查询指定连接的链路级统计与 PPP 协商 IP。
// 连接不存在或枚举失败返回 nil，调用方按「暂不可用」降级展示。
func ConnectionStats(entryName string) *RasStatInfo {
	h, ok := findRasConnectionHandle(entryName)
	if !ok || h == 0 {
		return nil
	}
	info := &RasStatInfo{Connected: true}
	fillRasStats(h, info)
	fillProjectionIp(h, info)
	return info
}

// findRasConnectionHandle 枚举系统全部 RAS 连接，返回指定连接名的 HRASCONN。
func findRasConnectionHandle(entryName string) (uintptr, bool) {
	if entryName == "" || procRasEnumConnectionsW.Find() != nil {
		return 0, false
	}
	// 缓冲头部 4 字节为元素数占位；每个元素都要写 dwSize 标识布局版本
	const maxConns = 16
	buf := make([]byte, 4+maxConns*rasconnSizeX64)
	for i := 0; i < maxConns; i++ {
		binary.LittleEndian.PutUint32(buf[4+i*rasconnSizeX64:], rasconnSizeX64)
	}
	size := uint32(len(buf))
	var count uint32
	ret, _, _ := procRasEnumConnectionsW.Call(
		uintptr(unsafe.Pointer(&buf[0])),
		uintptr(unsafe.Pointer(&size)),
		uintptr(unsafe.Pointer(&count)),
	)
	if ret != 0 || count == 0 {
		return 0, false
	}
	n := int(count)
	if n > maxConns {
		n = maxConns
	}
	for i := 0; i < n; i++ {
		off := 4 + i*rasconnSizeX64
		if utf16At(buf[off+rasconnOffEntry:], rasconnMaxEntry) == entryName {
			return uintptr(binary.LittleEndian.Uint64(buf[off+rasconnOffHandle:])), true
		}
	}
	return 0, false
}

// fillRasStats 填充链路统计；调用失败保留零值（IP 仍可能已取得）。
func fillRasStats(h uintptr, info *RasStatInfo) {
	if procRasGetConnectionStatisticsW.Find() != nil {
		return
	}
	buf := make([]byte, rasStatsSize)
	binary.LittleEndian.PutUint32(buf, rasStatsSize)
	ret, _, _ := procRasGetConnectionStatisticsW.Call(h, uintptr(unsafe.Pointer(&buf[0])))
	if ret != 0 {
		return
	}
	dw := func(off int) uint32 { return binary.LittleEndian.Uint32(buf[off:]) }
	info.BytesUp = uint64(dw(4))   // dwBytesXmited
	info.BytesDown = uint64(dw(8)) // dwBytesRcvd
	info.ErrTotal = uint64(dw(20)) + uint64(dw(24)) + uint64(dw(28)) +
		uint64(dw(32)) + uint64(dw(36)) + uint64(dw(40))
	info.Bps = uint64(dw(56))                // dwBps
	info.DurationSec = uint64(dw(60)) / 1000 // dwConnectDuration(ms)
}

// fillProjectionIp 填充 PPP 协商的本机/服务器 IP；失败留空。
func fillProjectionIp(h uintptr, info *RasStatInfo) {
	if procRasGetProjectionInfoW.Find() != nil {
		return
	}
	buf := make([]byte, rasPppIpSize)
	binary.LittleEndian.PutUint32(buf, rasPppIpSize)
	size := uint32(rasPppIpSize)
	ret, _, _ := procRasGetProjectionInfoW.Call(h, raspPppIp,
		uintptr(unsafe.Pointer(&buf[0])), uintptr(unsafe.Pointer(&size)))
	if ret != 0 || binary.LittleEndian.Uint32(buf[4:]) != 0 { // dwError != 0
		return
	}
	info.LocalIP = utf16At(buf[rasPppIpOffLocal:], 16)
	info.ServerIP = utf16At(buf[rasPppIpOffServer:], 16)
}

// utf16At 读取缓冲区中以 NUL 结尾的 UTF-16 串，上限 maxChars 个码元。
func utf16At(buf []byte, maxChars int) string {
	n := len(buf) / 2
	if n > maxChars {
		n = maxChars
	}
	u := make([]uint16, 0, n)
	for i := 0; i < n; i++ {
		v := binary.LittleEndian.Uint16(buf[i*2:])
		if v == 0 {
			break
		}
		u = append(u, v)
	}
	return string(utf16.Decode(u))
}
