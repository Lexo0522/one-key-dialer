package platform

import (
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/Lexo0522/one-key-dialer/internal/model"
)

// DefaultDevice 兜底 PPPoE 设备。
var DefaultDevice = DeviceHint{Port: "PPPoE5-0", Device: "WAN Miniport (PPPOE)"}

// RasModule 是唯一的 Windows RAS 模块：电话簿准备、设备选择、原生 RasDialW
// 拨号、rasdial.exe 断开、活动连接跟踪都在这里收敛。
type RasModule struct {
	connectionName string
	phonebookFile  string

	mu              sync.Mutex
	activeConn      string
	preferredDevice *DeviceHint
}

// NewRasModule 构造 RAS 模块。
func NewRasModule(connectionName, phonebookFile string) *RasModule {
	return &RasModule{connectionName: connectionName, phonebookFile: phonebookFile}
}

// ConnectionName 返回管理的连接名。
func (m *RasModule) ConnectionName() string { return m.connectionName }

// SetPreferredDevice 设置写入电话簿时使用的设备；nil 表示自动探测。
func (m *RasModule) SetPreferredDevice(hint *DeviceHint) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.preferredDevice = hint
}

// PreferredDevice 返回当前偏好设备。
func (m *RasModule) PreferredDevice() *DeviceHint {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.preferredDevice == nil {
		return nil
	}
	h := *m.preferredDevice
	return &h
}

// Connect 阻塞拨号。密码只经进程内结构体传递。
func (m *RasModule) Connect(creds *model.DialCredentials) (int, string) {
	if creds == nil {
		return -1, "empty credentials"
	}
	if !IsValidConnectionName(m.connectionName) {
		return -1, "invalid connection name"
	}
	if !m.EnsureEntry() {
		return -1, "ensure connection failed"
	}
	m.mu.Lock()
	m.activeConn = m.connectionName
	m.mu.Unlock()

	code, ok := RasDial(m.connectionName, m.phonebookFile,
		[]byte(creds.Username), creds.PasswordBytes())
	if !ok {
		return -1, "RasDial API unavailable"
	}
	if code == 0 {
		return code, "RasDial API"
	}
	return code, RasErrorText(code)
}

// Disconnect 断开活动连接，返回退出码。
func (m *RasModule) Disconnect() (int, error) {
	m.mu.Lock()
	target := m.activeConn
	m.mu.Unlock()
	if target == "" {
		target = m.connectionName
	}
	if !IsValidConnectionName(target) {
		return -1, nil
	}
	code, err := RasDisconnect(target)
	if err == nil && code == 0 {
		m.mu.Lock()
		if m.activeConn == target {
			m.activeConn = ""
		}
		m.mu.Unlock()
	}
	return code, err
}

// HasEntry 判断电话簿是否已有连接段。
func (m *RasModule) HasEntry() bool {
	if m.phonebookFile == "" {
		return false
	}
	if _, err := os.Stat(m.phonebookFile); err != nil {
		return false
	}
	content, _, err := ReadPbk(m.phonebookFile)
	if err != nil {
		return false
	}
	return ContentContainsSection(content, m.connectionName)
}

// EnsureEntry 确保连接段存在；缺失时原子重写创建。
func (m *RasModule) EnsureEntry() bool {
	if !IsValidConnectionName(m.connectionName) || m.phonebookFile == "" {
		return false
	}
	if m.HasEntry() {
		return true
	}
	dir := filepath.Dir(m.phonebookFile)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return false
	}

	var existing, charset string
	if _, err := os.Stat(m.phonebookFile); err == nil {
		existing, charset, err = ReadPbk(m.phonebookFile)
		if err != nil {
			return false
		}
		_ = copyFile(m.phonebookFile, filepath.Join(dir, "rasphone.pbk.bak"))
	} else {
		charset = "UTF-8"
	}

	without := RemoveSection(existing, m.connectionName)
	hint := m.PreferredDevice()
	if hint == nil {
		hint = FindPppoeDeviceHint(without)
	}
	if hint == nil {
		d := DefaultDevice
		hint = &d
	}

	merged := without
	if merged != "" && !endsWithNewline(merged) {
		merged += "\n"
	}
	merged += BuildPhoneBookEntry(m.connectionName, hint.Port, hint.Device)
	if err := WritePbk(m.phonebookFile, merged, charset); err != nil {
		return false
	}
	return m.HasEntry()
}

// RewriteEntry 强制重写连接段（用户更换设备后）。
func (m *RasModule) RewriteEntry() bool {
	if m.phonebookFile == "" {
		return false
	}
	if _, err := os.Stat(m.phonebookFile); err == nil {
		existing, charset, err := ReadPbk(m.phonebookFile)
		if err != nil {
			return false
		}
		without := RemoveSection(existing, m.connectionName)
		if err := WritePbk(m.phonebookFile, without, charset); err != nil {
			return false
		}
	}
	return m.EnsureEntry()
}

// SnapshotStatus 返回电话簿状态快照。
func (m *RasModule) SnapshotStatus() *PbkStatus {
	if m.phonebookFile == "" {
		return &PbkStatus{Charset: "-", LastWrite: "APPDATA 不可用"}
	}
	st := &PbkStatus{File: m.phonebookFile, Charset: "-"}
	if _, err := os.Stat(m.phonebookFile); err == nil {
		st.Exists = true
	} else {
		st.LastWrite = "尚未写入"
		return st
	}
	content, charset, err := ReadPbk(m.phonebookFile)
	st.Charset = charset
	if err != nil {
		st.LastWrite = "读取失败: " + err.Error()
		st.LastWriteMs = time.Now().UnixMilli()
		return st
	}
	st.HasEntry = ContentContainsSection(content, m.connectionName)
	if hint := FindPppoeDeviceHint(content); hint != nil {
		st.LastPort = hint.Port
		st.LastDevice = hint.Device
	}
	st.LastWrite = "尚未写入"
	return st
}

// ListDeviceOptions 列出可选的 PPPoE 设备（含兜底默认值）。
func (m *RasModule) ListDeviceOptions() []DeviceHint {
	seen := map[string]bool{}
	var out []DeviceHint
	if m.phonebookFile != "" {
		if _, err := os.Stat(m.phonebookFile); err == nil {
			if content, _, err := ReadPbk(m.phonebookFile); err == nil {
				for _, h := range CollectPppoeDevices(content) {
					key := h.Port + "|" + h.Device
					if !seen[key] {
						seen[key] = true
						out = append(out, h)
					}
				}
			}
		}
	}
	key := DefaultDevice.Port + "|" + DefaultDevice.Device
	if !seen[key] {
		out = append(out, DefaultDevice)
	}
	return out
}

// FormatStatus 生成一行状态文本（诊断 / 日志）。
func FormatStatus(st *PbkStatus) string {
	if st == nil {
		return "(无电话簿状态)"
	}
	var sb strings.Builder
	sb.WriteString("pbk=")
	if st.File != "" {
		sb.WriteString(st.File)
	} else {
		sb.WriteString("(null)")
	}
	sb.WriteString(" exists=" + boolStr(st.Exists))
	sb.WriteString(" hasEntry=" + boolStr(st.HasEntry))
	sb.WriteString(" charset=" + st.Charset)
	if st.LastPort != "" {
		sb.WriteString(" port=" + st.LastPort)
	}
	if st.LastDevice != "" {
		sb.WriteString(" device=" + st.LastDevice)
	}
	if st.LastWrite != "" {
		sb.WriteString(" lastWrite=" + st.LastWrite)
	}
	return sb.String()
}

func endsWithNewline(s string) bool {
	return len(s) > 0 && (s[len(s)-1] == '\n' || s[len(s)-1] == '\r')
}

func boolStr(v bool) string {
	if v {
		return "true"
	}
	return "false"
}

func copyFile(src, dst string) error {
	data, err := os.ReadFile(src)
	if err != nil {
		return err
	}
	return os.WriteFile(dst, data, 0o600)
}
