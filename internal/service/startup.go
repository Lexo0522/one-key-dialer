package service

import (
	"os"
	"path/filepath"
	"strings"

	"github.com/Lexo0522/one-key-dialer/internal/i18n"
	"github.com/Lexo0522/one-key-dialer/internal/platform"
)

// AutoStartValueName 注册表中的 Run 值名。
const AutoStartValueName = "PPoEDialer"

// StartupService 通过 HKCU\...\Run 注册/注销开机自启动（以注册表为准）。
type StartupService struct {
	logger *LogService
}

// NewStartupService 构造自启动服务。
func NewStartupService(logger *LogService) *StartupService {
	return &StartupService{logger: logger}
}

// Enable 注册自启动；失败时上报并回滚 UI 选中态。
func (s *StartupService) Enable() bool {
	exe := platform.CurrentExePath()
	if exe == "" || !fileExists(exe) || isGoBuildExe(exe) {
		s.logger.Error(i18n.T("autostart.noTarget"))
		return false
	}
	cmd := platform.BuildExeRunCommand(exe)
	if err := platform.WriteRunValue(AutoStartValueName, cmd); err != nil {
		s.logger.Error(i18n.Tf("autostart.registerFailed", err.Error()))
		return false
	}
	if !s.IsHealthy() {
		s.logger.Error(i18n.T("autostart.verifyFailed"))
		return false
	}
	s.logger.Success(i18n.T("autostart.registered"))
	s.logger.Info(i18n.Tf("autostart.command", cmd))
	return true
}

// Disable 取消自启动。
func (s *StartupService) Disable() bool {
	if err := platform.DeleteRunValue(AutoStartValueName); err != nil {
		s.logger.Error(i18n.Tf("autostart.unregFailed", err.Error()))
		return false
	}
	s.logger.Success(i18n.T("autostart.unregistered"))
	return true
}

// IsEnabled 读取注册表判断当前是否启用（以注册表为准）。
func (s *StartupService) IsEnabled() bool {
	data := platform.ReadRunValue(AutoStartValueName)
	return data != "" && platform.IsDirectLaunchCommand(data)
}

// IsHealthy 注册表值指向当前可执行文件且文件存在。
func (s *StartupService) IsHealthy() bool {
	data := platform.ReadRunValue(AutoStartValueName)
	if data == "" || !platform.IsDirectLaunchCommand(data) {
		return false
	}
	exe := platform.CurrentExePath()
	if exe == "" || !fileExists(exe) {
		return false
	}
	return strings.Contains(strings.ToLower(data), strings.ToLower(exe))
}

// EnsureHealthy 设置要求自启动但注册表异常时重新注册一次。
func (s *StartupService) EnsureHealthy(wantAutoStart bool) bool {
	if !wantAutoStart {
		return s.IsHealthy()
	}
	if s.IsHealthy() {
		return true
	}
	s.logger.Info(i18n.T("autostart.repairing"))
	s.Enable()
	if s.IsHealthy() {
		s.logger.Success(i18n.T("autostart.repaired"))
		return true
	}
	s.logger.Error(i18n.T("autostart.repairFailed"))
	return false
}

func fileExists(path string) bool {
	info, err := os.Stat(path)
	return err == nil && !info.IsDir()
}

func isGoBuildExe(exe string) bool {
	base := strings.ToLower(filepath.Base(exe))
	return base == "main.exe" || base == "go.exe" || base == "__debug_bin.exe"
}

// StartupSelfCheck 启动自检：外部命令可用性 + 数据目录可写性。
type StartupSelfCheck struct {
	logger  *LogService
	dataDir string
}

// NewStartupSelfCheck 构造启动自检。
func NewStartupSelfCheck(logger *LogService, dataDir string) *StartupSelfCheck {
	return &StartupSelfCheck{logger: logger, dataDir: dataDir}
}

// Run 执行自检（耗时操作应在后台线程调用）。
func (c *StartupSelfCheck) Run() {
	for _, name := range []string{"rasdial", "ping", "reg"} {
		if !commandExists(name) {
			c.logger.Warning(i18n.Tf("selfcheck.missing", name))
		}
	}
	if c.dataDir == "" {
		c.logger.Warning(i18n.Tf("selfcheck.emptyPath", "数据目录"))
		return
	}
	if !platform.IsDirWritable(c.dataDir) {
		c.logger.Warning(i18n.Tf("selfcheck.notWritable", c.dataDir, "probe"))
	}
}

func commandExists(name string) bool {
	path, err := lookPath(name)
	return err == nil && path != ""
}
