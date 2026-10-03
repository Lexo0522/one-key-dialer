package service

import (
	"github.com/Lexo0522/one-key-dialer/internal/i18n"
	"github.com/Lexo0522/one-key-dialer/internal/platform"
)

// Diagnostics PPPoE 设备管理（电话簿设备列举 / 选择 / 重写提示）。
type Diagnostics struct {
	ras    *platform.RasModule
	logger *LogService
}

// NewDiagnostics 构造设备管理器。
func NewDiagnostics(ras *platform.RasModule, logger *LogService) *Diagnostics {
	return &Diagnostics{ras: ras, logger: logger}
}

// ListDevices 列出可选 PPPoE 设备。
func (d *Diagnostics) ListDevices() []platform.DeviceHint {
	if d.ras == nil {
		return nil
	}
	return d.ras.ListDeviceOptions()
}

// CurrentDevice 返回当前生效的 PPPoE 设备（永不为空，供界面回显）。
func (d *Diagnostics) CurrentDevice() *platform.DeviceHint {
	if d.ras == nil {
		return nil
	}
	return d.ras.CurrentDevice()
}

// ApplyDevice 记住设备选择；rewrite 为 true 时立即重写电话簿。
// 返回给用户的提示文本。
func (d *Diagnostics) ApplyDevice(hint *platform.DeviceHint, rewrite bool) string {
	if d.ras == nil || hint == nil {
		return i18n.T("diag.unchanged")
	}
	d.ras.SetPreferredDevice(hint)
	if !rewrite {
		return i18n.Tf("diag.remembered", hint.Device, hint.Port)
	}
	if d.ras.RewriteEntry() {
		return i18n.Tf("diag.rewritten", hint.Device, hint.Port)
	}
	if d.logger != nil {
		d.logger.Warning(i18n.T("diag.rewriteFail"))
	}
	return i18n.T("diag.rewriteFail")
}
