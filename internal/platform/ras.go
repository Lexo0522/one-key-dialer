package platform

import (
	"encoding/binary"
	"os"
	"strings"
	"syscall"
	"time"
	"unicode/utf16"
	"unicode/utf8"
	"unsafe"

	"github.com/Lexo0522/one-key-dialer/internal/util"
	"golang.org/x/text/encoding/simplifiedchinese"
	"golang.org/x/text/transform"
)

// RASDIALPARAMSW 两种布局（x64）。偏移量由旧版 JNA 绑定在真实 Windows 上验证过：
//   - SDK 布局（Win11 24H2 之前）：1968 字节，szUserName@888、szPassword@1402
//   - 24H2 布局（build >= 26100）：2096 字节，dwCallbackId 后插入 146 个不透明字节，
//     szUserName@1034、szPassword@1548，且去掉了 dwIfIndex/guidId
const (
	structSizeSdk     = 1968
	structSize24H2    = 2096
	build24H2         = 26100
	offSize           = 0
	offEntryName      = 4   // [257]uint16
	offPhoneNumber    = 518 // [129]uint16
	offCallbackNumber = 776 // [49]uint16
	offSubEntry       = 876 // uint32
	offCallbackId     = 880 // uint64 (ULONG_PTR)
	offUserNameSdk    = 888 // [257]uint16
	offUserName24H2   = 1034
	offPasswordSdk    = 1402 // [257]uint16
	offPassword24H2   = 1548
	offDomainSdk      = 1916 // [16]uint16
	offDomain24H2     = 2062
	maxEntryName      = 257
	maxUserName       = 257
	maxPassword       = 257
	maxDomain         = 16
)

// disconnectTimeout rasdial /disconnect 的超时。
const disconnectTimeout = 30 * time.Second

var (
	rasapi32            = syscall.NewLazyDLL("rasapi32.dll")
	procRasDialW        = rasapi32.NewProc("RasDialW")
	procRasGetErrorStrW = rasapi32.NewProc("RasGetErrorStringW")
)

// Uses24H2Layout 判断给定 build 是否使用 24H2 布局。
func Uses24H2Layout(build int) bool { return build >= build24H2 }

// RasDial 同步拨号。密码只存在于进程内结构体中，绝不进入命令行。
// 返回 RAS 结果码（0 成功）；原生绑定不可用返回 (0, false)。
func RasDial(entryName, phonebook string, username, password []byte) (int, bool) {
	if procRasDialW.Find() != nil {
		return 0, false
	}
	build := OsBuildNumber()
	size := structSizeSdk
	offUser, offPass, offDomain := offUserNameSdk, offPasswordSdk, offDomainSdk
	if Uses24H2Layout(build) {
		size = structSize24H2
		offUser, offPass, offDomain = offUserName24H2, offPassword24H2, offDomain24H2
	}

	buf := make([]byte, size)
	defer func() {
		for i := range buf {
			buf[i] = 0
		}
	}()
	binary.LittleEndian.PutUint32(buf[offSize:], uint32(size))
	putUTF16(buf[offEntryName:], stringToUTF16(entryName), maxEntryName)
	putUTF16(buf[offUser:], bytesToUTF16(username), maxUserName)
	putUTF16(buf[offPass:], bytesToUTF16(password), maxPassword)
	putUTF16(buf[offDomain:], nil, maxDomain)

	var phonebookPtr uintptr
	var pbUTF16 []uint16
	if phonebook != "" {
		pbUTF16 = utf16.Encode([]rune(phonebook + "\x00"))
		phonebookPtr = uintptr(unsafe.Pointer(&pbUTF16[0]))
	}
	var conn uintptr

	ret, _, _ := procRasDialW.Call(
		0,
		phonebookPtr,
		uintptr(unsafe.Pointer(&buf[0])),
		0,
		0,
		uintptr(unsafe.Pointer(&conn)),
	)
	return int(ret), true
}

// RasErrorText 返回 RAS 错误码的系统文本；无则返回 ""。
func RasErrorText(code int) string {
	if code <= 0 || procRasGetErrorStrW.Find() != nil {
		return ""
	}
	buf := make([]uint16, 512)
	ret, _, _ := procRasGetErrorStrW.Call(
		uintptr(code),
		uintptr(unsafe.Pointer(&buf[0])),
		uintptr(len(buf)),
	)
	if ret != 0 {
		return ""
	}
	end := 0
	for end < len(buf) && buf[end] != 0 {
		end++
	}
	return strings.TrimSpace(string(utf16.Decode(buf[:end])))
}

// RasDisconnect 通过 rasdial.exe 断开指定连接，返回退出码。
func RasDisconnect(connectionName string) (int, error) {
	res, err := util.RunProcess([]string{"rasdial", connectionName, "/disconnect"}, disconnectTimeout, nil)
	if err != nil {
		return -1, err
	}
	return res.ExitCode, nil
}

func putUTF16(dst []byte, src []uint16, maxChars int) {
	if len(src) > maxChars-1 {
		src = src[:maxChars-1]
	}
	for i, v := range src {
		binary.LittleEndian.PutUint16(dst[i*2:], v)
	}
}

func stringToUTF16(s string) []uint16 { return utf16.Encode([]rune(s)) }

func bytesToUTF16(b []byte) []uint16 { return utf16.Encode([]rune(string(b))) }

// ==================== 电话簿 ====================

// PbkStatus 电话簿快照状态。
type PbkStatus struct {
	File        string
	Exists      bool
	HasEntry    bool
	Charset     string
	LastPort    string
	LastDevice  string
	LastWrite   string
	LastWriteMs int64
}

// DeviceHint PPPoE 设备提示（端口 + 设备名）。
type DeviceHint struct {
	Port         string
	Device       string
	FromExisting bool
}

// IsValidConnectionName 校验 RAS 连接名。
func IsValidConnectionName(name string) bool {
	if name == "" || len(name) > 64 {
		return false
	}
	for _, r := range name {
		ok := (r >= 'A' && r <= 'Z') || (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') || r == '_' || r == '-'
		if !ok {
			return false
		}
	}
	return true
}

// ReadPbk 读取电话簿文本并按 BOM/启发式判定编码。
func ReadPbk(path string) (string, string, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return "", "", err
	}
	if len(data) >= 2 {
		if data[0] == 0xFF && data[1] == 0xFE {
			return decodeUTF16LE(data[2:]), "UTF-16LE", nil
		}
		if data[0] == 0xFE && data[1] == 0xFF {
			return decodeUTF16BE(data[2:]), "UTF-16BE", nil
		}
	}
	if len(data) >= 3 && data[0] == 0xEF && data[1] == 0xBB && data[2] == 0xBF {
		return string(data[3:]), "UTF-8", nil
	}
	limit := len(data)
	if limit > 256 {
		limit = 256
	}
	zeros := 0
	for i := 0; i < limit; i++ {
		if data[i] == 0 {
			zeros++
		}
	}
	if limit > 0 && zeros > limit/4 {
		return decodeUTF16LE(data), "UTF-16LE", nil
	}
	if utf8Valid(data) {
		return string(data), "UTF-8", nil
	}
	if out, _, e := transform.Bytes(simplifiedchinese.GBK.NewDecoder(), data); e == nil {
		return string(out), "GBK", nil
	}
	return string(data), "UTF-8", nil
}

// WritePbk 按指定编码写回电话簿（原子替换）。
func WritePbk(path, content, charset string) error {
	var data []byte
	switch charset {
	case "UTF-16LE":
		buf := make([]byte, 0, len(content)*2+2)
		buf = append(buf, 0xFF, 0xFE)
		for _, u := range utf16.Encode([]rune(content)) {
			buf = binary.LittleEndian.AppendUint16(buf, u)
		}
		data = buf
	case "UTF-16BE":
		buf := make([]byte, 0, len(content)*2+2)
		buf = append(buf, 0xFE, 0xFF)
		for _, u := range utf16.Encode([]rune(content)) {
			buf = binary.BigEndian.AppendUint16(buf, u)
		}
		data = buf
	case "UTF-8":
		buf := make([]byte, 0, len(content)+3)
		buf = append(buf, 0xEF, 0xBB, 0xBF)
		buf = append(buf, content...)
		data = buf
	case "GBK":
		out, _, err := transform.Bytes(simplifiedchinese.GBK.NewEncoder(), []byte(content))
		if err != nil {
			return err
		}
		data = out
	default:
		data = []byte(content)
	}
	return util.WriteAtomic(path, data)
}

// ContentContainsSection 判断电话簿是否含指定连接段。
func ContentContainsSection(content, connName string) bool {
	if content == "" || connName == "" {
		return false
	}
	target := "[" + connName + "]"
	for _, line := range splitLines(content) {
		if strings.TrimSpace(line) == target {
			return true
		}
	}
	return false
}

// RemoveSection 删除指定连接段，行尾统一为 \n。
func RemoveSection(content, connName string) string {
	if content == "" {
		return ""
	}
	lines := splitLines(content)
	var sb strings.Builder
	skipping := false
	target := "[" + connName + "]"
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if len(trimmed) >= 2 && trimmed[0] == '[' && trimmed[len(trimmed)-1] == ']' {
			skipping = trimmed == target
			if skipping {
				continue
			}
		}
		if !skipping {
			if sb.Len() > 0 {
				sb.WriteByte('\n')
			}
			sb.WriteString(line)
		}
	}
	out := sb.String()
	for strings.HasSuffix(out, "\n\n\n") {
		out = out[:len(out)-1]
	}
	return out
}

// FindPppoeDeviceHint 从电话簿中寻找 PPPoE 端口/设备提示。
func FindPppoeDeviceHint(content string) *DeviceHint {
	if content == "" {
		return nil
	}
	var port, device string
	for _, line := range splitLines(content) {
		t := strings.TrimSpace(line)
		upper := strings.ToUpper(t)
		switch {
		case strings.HasPrefix(t, "PreferredPort=") && strings.Contains(upper, "PPPOE"):
			port = strings.TrimSpace(strings.TrimPrefix(t, "PreferredPort="))
		case strings.HasPrefix(t, "PreferredDevice=") && strings.Contains(upper, "PPPOE"):
			device = strings.TrimSpace(strings.TrimPrefix(t, "PreferredDevice="))
		case strings.HasPrefix(t, "Port=") && strings.Contains(upper, "PPPOE") && port == "":
			port = strings.TrimSpace(strings.TrimPrefix(t, "Port="))
		case strings.HasPrefix(t, "Device=") && strings.Contains(upper, "PPPOE") && device == "":
			device = strings.TrimSpace(strings.TrimPrefix(t, "Device="))
		}
		if port != "" && device != "" {
			return &DeviceHint{Port: port, Device: device, FromExisting: true}
		}
	}
	if port != "" {
		d := device
		if d == "" {
			d = "WAN Miniport (PPPOE)"
		}
		return &DeviceHint{Port: port, Device: d, FromExisting: true}
	}
	return nil
}

// CollectPppoeDevices 收集电话簿中所有形似 PPPoE 的 Port/Device 组合（去重）。
func CollectPppoeDevices(content string) []DeviceHint {
	var out []DeviceHint
	seen := map[string]bool{}
	if content == "" {
		return out
	}
	var port, device string
	flush := func() {
		if port != "" && device != "" && looksPppoe(port, device) {
			key := port + "|" + device
			if !seen[key] {
				seen[key] = true
				out = append(out, DeviceHint{Port: port, Device: device, FromExisting: true})
			}
		}
		port, device = "", ""
	}
	for _, line := range splitLines(content) {
		t := strings.TrimSpace(line)
		if strings.HasPrefix(t, "[") {
			flush()
			continue
		}
		switch {
		case strings.HasPrefix(t, "PreferredPort="):
			port = strings.TrimSpace(strings.TrimPrefix(t, "PreferredPort="))
		case strings.HasPrefix(t, "PreferredDevice="):
			device = strings.TrimSpace(strings.TrimPrefix(t, "PreferredDevice="))
		case strings.HasPrefix(t, "Port=") && port == "":
			port = strings.TrimSpace(strings.TrimPrefix(t, "Port="))
		case strings.HasPrefix(t, "Device=") && device == "":
			device = strings.TrimSpace(strings.TrimPrefix(t, "Device="))
		}
	}
	flush()
	return out
}

func looksPppoe(port, device string) bool {
	p := strings.ToUpper(port)
	d := strings.ToUpper(device)
	return strings.Contains(p, "PPPOE") || strings.Contains(d, "PPPOE")
}

func splitLines(s string) []string {
	s = strings.ReplaceAll(s, "\r\n", "\n")
	s = strings.ReplaceAll(s, "\r", "\n")
	return strings.Split(s, "\n")
}

func decodeUTF16LE(b []byte) string {
	if len(b) < 2 {
		return ""
	}
	u := make([]uint16, len(b)/2)
	for i := range u {
		u[i] = binary.LittleEndian.Uint16(b[i*2:])
	}
	return string(utf16.Decode(u))
}

func decodeUTF16BE(b []byte) string {
	if len(b) < 2 {
		return ""
	}
	u := make([]uint16, len(b)/2)
	for i := range u {
		u[i] = binary.BigEndian.Uint16(b[i*2:])
	}
	return string(utf16.Decode(u))
}

func utf8Valid(b []byte) bool { return utf8.Valid(b) }
