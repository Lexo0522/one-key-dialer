package service

import (
	"os"
	"path/filepath"
	"strings"

	"github.com/Lexo0522/one-key-dialer/internal/i18n"
	"github.com/Lexo0522/one-key-dialer/internal/platform"
)

// AutoStartValueName 注册表中的 Run 值名。
// 刻意沿用历史拼写（未随 exe 改名为 PPPoEDialer）：值名不变可保证
// 存量安装原地更新数据、不会产生新旧两条自启动项；指向的命令行
// 由 EnsureHealthy 在新版启动时自动改写到当前 exe。
const AutoStartValueName = "PPoEDialer"

// StartupService 通过 HKCU\...\Run 注册/注销开机自启动（以注册表为准）。
type StartupService struct {
	logger *LogService

	// 注册表与本体路径经字段注入：测试用假实现替换，不碰真实 HKCU。
	readRun      func(valueName string) string
	writeRun     func(valueName, value string) error
	deleteRun    func(valueName string) error
	exePath      func() string
	ephemeralExe func() bool
}

// NewStartupService 构造自启动服务（默认绑定真实注册表与进程路径）。
func NewStartupService(logger *LogService) *StartupService {
	s := &StartupService{logger: logger}
	s.readRun = platform.ReadRunValue
	s.writeRun = platform.WriteRunValue
	s.deleteRun = platform.DeleteRunValue
	s.exePath = platform.CurrentExePath
	s.ephemeralExe = platform.IsEphemeralExePath
	return s
}

// Enable 注册自启动；失败时上报并回滚 UI 选中态。
func (s *StartupService) Enable() bool {
	// 临时目录/构建产物里的本体不许登记自启动:指向 %TEMP% 的 Run 值在磁盘
	// 清理后就是死链,用户只会看到"开机自启失效",程序侧毫无线索。
	if s.ephemeralExe() {
		s.logger.Error(i18n.T("autostart.ephemeralPath"))
		return false
	}
	exe := s.exePath()
	if exe == "" || !fileExists(exe) || isGoBuildExe(exe) {
		s.logger.Error(i18n.T("autostart.noTarget"))
		return false
	}
	cmd := platform.BuildExeRunCommand(exe)
	if err := s.writeRun(AutoStartValueName, cmd); err != nil {
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
	if err := s.deleteRun(AutoStartValueName); err != nil {
		s.logger.Error(i18n.Tf("autostart.unregFailed", err.Error()))
		return false
	}
	s.logger.Success(i18n.T("autostart.unregistered"))
	return true
}

// IsEnabled 读取注册表判断当前是否启用（以注册表为准）。
func (s *StartupService) IsEnabled() bool {
	data := s.readRun(AutoStartValueName)
	return data != "" && platform.IsDirectLaunchCommand(data)
}

// IsHealthy 注册表值指向当前可执行文件且文件存在。
func (s *StartupService) IsHealthy() bool {
	data := s.readRun(AutoStartValueName)
	if data == "" || !platform.IsDirectLaunchCommand(data) {
		return false
	}
	exe := s.exePath()
	if exe == "" || !fileExists(exe) {
		return false
	}
	return strings.Contains(strings.ToLower(data), strings.ToLower(exe))
}

// EnsureHealthy 设置要求自启动但注册表异常时重新注册一次。
//
// 本体位于临时目录/构建产物时直接返回：这时 IsHealthy 会把 temp 路径和注册表
// 里的 temp 路径互相比对而判"健康",自愈链就此失明——它每隔一次启动都"修好"
// 一个错误的目标。宁可在这里承认不健康,也不要把 %TEMP% 写进 Run 值。
func (s *StartupService) EnsureHealthy(wantAutoStart bool) bool {
	if !wantAutoStart {
		return s.IsHealthy()
	}
	if s.ephemeralExe() {
		s.logger.Error(i18n.T("autostart.ephemeralPath"))
		return false
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
