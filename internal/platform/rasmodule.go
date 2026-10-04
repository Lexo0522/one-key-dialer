package platform

import (
	"os"
	"path/filepath"
	"sync"

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
	code, ok := RasDial(m.connectionName, m.phonebookFile,
		[]byte(creds.Username), creds.PasswordBytes())
	if !ok {
		return -1, "RasDial API unavailable"
	}
	// activeConn 只反映真实结果:拨号成功置位,失败不再乐观前置
	if code == 0 {
		m.mu.Lock()
		m.activeConn = m.connectionName
		m.mu.Unlock()
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

// CurrentDevice 返回当前生效设备，优先级：显式偏好 → 本连接段 → 电话簿首个 PPPoE → 兜底默认。
// 永不返回 nil，保证上层始终有可展示的默认值。
func (m *RasModule) CurrentDevice() *DeviceHint {
	if h := m.PreferredDevice(); h != nil && h.Port != "" {
		return h
	}
	if content := m.readPbkContent(); content != "" {
		if h := FindSectionDevice(content, m.connectionName); h != nil {
			return h
		}
		if h := FindPppoeDeviceHint(content); h != nil {
			return h
		}
	}
	d := DefaultDevice
	return &d
}

// readPbkContent 读取电话簿文本；任意失败返回 ""。
func (m *RasModule) readPbkContent() string {
	if m.phonebookFile == "" {
		return ""
	}
	if _, err := os.Stat(m.phonebookFile); err != nil {
		return ""
	}
	content, _, err := ReadPbk(m.phonebookFile)
	if err != nil {
		return ""
	}
	return content
}

// ListDeviceOptions 列出可选的 PPPoE 设备。
// 列表必定包含「当前生效设备」与「兜底默认设备」，避免下拉框出现空选。
func (m *RasModule) ListDeviceOptions() []DeviceHint {
	seen := map[string]bool{}
	var out []DeviceHint
	if content := m.readPbkContent(); content != "" {
		for _, h := range CollectPppoeDevices(content) {
			key := h.Port + "|" + h.Device
			if !seen[key] {
				seen[key] = true
				out = append(out, h)
			}
		}
	}
	appendHint := func(h DeviceHint) {
		key := h.Port + "|" + h.Device
		if !seen[key] {
			seen[key] = true
			out = append(out, h)
		}
	}
	// 当前生效设备优先入列，保证下拉框有固定选中项
	if cur := m.CurrentDevice(); cur != nil {
		appendHint(*cur)
	}
	appendHint(DefaultDevice)
	return out
}

func endsWithNewline(s string) bool {
	return len(s) > 0 && (s[len(s)-1] == '\n' || s[len(s)-1] == '\r')
}

func copyFile(src, dst string) error {
	data, err := os.ReadFile(src)
	if err != nil {
		return err
	}
	return os.WriteFile(dst, data, 0o600)
}
