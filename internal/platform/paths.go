// Package platform 封装 Windows 相关能力：数据目录、ACL、DPAPI、注册表、RAS。
package platform

import (
	"os"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"
	"unsafe"

	"github.com/Lexo0522/one-key-dialer/internal/model"
	"golang.org/x/sys/windows/registry"
)

// AppDataFolder 回退数据目录名。
// 内部标识（数据目录/管道名/互斥体/注册表值名）均沿用历史拼写 PPoEDialer，
// 与 exe 改名解耦：改这些会带来数据迁移与新旧版本共存问题，无用户可见收益。
const AppDataFolder = "PPoEDialer"

var (
	dataDirOnce sync.Once
	dataDir     string
	installDir  string
)

// InstallDir 返回程序安装目录（打包版为 exe 所在目录）。
//
// 两个不能当安装目录的 exe：
//   - 临时目录下的产物。Wails 的 GenerateBindings（pkg/commands/bindings/
//     bindings.go）会把本项目编译成 %TEMP%\wailsbindings.exe 来生成 TS 绑定，
//     那是本程序的完整二进制。若据此把安装目录定成 %TEMP%，更新脚本会把新版
//     exe 写进 %TEMP% 并从那启动，之后开机自启动也指向 %TEMP%，本机从此被
//     一个会被磁盘清理掉的副本接管。
//   - go build 产物（go.exe / main.exe 等）。
//
// 二者一律回退到工作目录；工作目录仍不可信时由调用方各自的兜底处理。
func InstallDir() string {
	if installDir != "" {
		return installDir
	}
	dir := ""
	if exe, err := os.Executable(); err == nil {
		abs, aerr := filepath.Abs(exe)
		if aerr == nil {
			if d, ok := installDirFromExe(abs); ok {
				dir = d
			}
		}
	}
	if dir == "" {
		if wd, err := os.Getwd(); err == nil {
			dir = wd
		}
	}
	installDir = dir
	return dir
}

// installDirFromExe 判断一个可执行路径能否作为安装目录。
// 返回 false 表示这不是程序本体所在的安装目录（调用方应另找兜底）。
func installDirFromExe(exe string) (string, bool) {
	if exe == "" {
		return "", false
	}
	name := strings.ToLower(filepath.Base(exe))
	switch {
	case name == "go.exe":
		// go build 产物,不是安装目录
		return "", false
	case isUnderTemp(filepath.Dir(exe)):
		// Wails bindings 临时构建产物,不是安装目录
		return "", false
	case strings.HasSuffix(name, ".exe"):
		return filepath.Dir(exe), true
	}
	return "", false
}

// isEphemeralExePath 判断一个可执行路径是否处于"一次性、随时可能消失"的位置：
// 临时目录下，或是源头构建产物（go build / Wails bindings 的临时 exe 都在这里）。
//
// 这类路径不允许作为"已安装程序"行事:
//   - 不能注册开机自启动。指向 %TEMP% 的 Run 值会在磁盘清理后变成死链,
//     用户看到的是"开机自启失效"而程序毫无线索。
//   - 不能执行自我更新。把新版写进 %TEMP% 等于把安装位置搬到那里,
//     此后每次启动都是那个随时会被删掉的副本。
//
// 对 Wails 而言这不是理论风险:GenerateBindings 会把本项目编译成
// %TEMP%\wailsbindings.exe,那是本程序的完整二进制(见 wails v2
// pkg/commands/bindings/bindings.go),一旦被误判为安装位置,上述两种
// 后果都会真实发生。
func isEphemeralExePath(exe string) bool {
	if exe == "" {
		return true
	}
	name := strings.ToLower(filepath.Base(exe))
	if name == "go.exe" {
		return true
	}
	return isUnderTemp(filepath.Dir(exe))
}

// DataDir 解析可写数据目录：程序目录（可写）→ 开发态工作目录 → %APPDATA%\PPoEDialer。
func DataDir() string {
	dataDirOnce.Do(func() {
		dataDir = resolveDataDir()
	})
	return dataDir
}

func resolveDataDir() string {
	if dir := InstallDir(); dir != "" && !isUnderTemp(dir) && strings.ToLower(filepath.Base(dir)) != "bin" && IsDirWritable(dir) {
		return dir
	}
	if wd, err := os.Getwd(); err == nil && isDevRun() && IsDirWritable(wd) {
		return wd
	}
	appData := os.Getenv("APPDATA")
	fallback := ""
	if appData != "" {
		fallback = filepath.Join(appData, AppDataFolder)
	} else {
		home, _ := os.UserHomeDir()
		fallback = filepath.Join(home, AppDataFolder)
	}
	_ = os.MkdirAll(fallback, 0o755)
	return fallback
}

// isDevRun 判断是否为源码态运行（可执行文件位于临时构建目录）。
func isDevRun() bool {
	exe, err := os.Executable()
	if err != nil {
		return false
	}
	return isUnderTemp(filepath.Dir(exe))
}

func isUnderTemp(dir string) bool {
	tmp := strings.ToLower(os.TempDir())
	d := strings.ToLower(strings.TrimSuffix(dir, string(filepath.Separator)))
	return d == tmp || strings.HasPrefix(d, tmp+string(filepath.Separator))
}

// IsDirWritable 通过创建临时探针文件判断目录可写性。
func IsDirWritable(dir string) bool {
	if dir == "" {
		return false
	}
	info, err := os.Stat(dir)
	if err != nil || !info.IsDir() {
		return false
	}
	f, err := os.CreateTemp(dir, "ppoe_probe_*.tmp")
	if err != nil {
		return false
	}
	name := f.Name()
	f.Close()
	_ = os.Remove(name)
	return true
}

// File 返回数据目录下的文件路径。
func File(name string) string {
	return filepath.Join(DataDir(), name)
}

// UpdatesDir 返回更新下载目录 %APPDATA%\PPoEDialer\updates。
func UpdatesDir() string {
	appData := os.Getenv("APPDATA")
	base := ""
	if appData != "" {
		base = filepath.Join(appData, AppDataFolder)
	} else {
		home, _ := os.UserHomeDir()
		base = filepath.Join(home, AppDataFolder)
	}
	dir := filepath.Join(base, "updates")
	_ = os.MkdirAll(dir, 0o755)
	return dir
}

// RelaunchExe 返回重启用的可执行文件路径（更新脚本钉住 PPPoEDialer.exe）。
func RelaunchExe() string {
	return filepath.Join(InstallDir(), model.AppName)
}

// PhonebookFile 返回 RAS 电话簿路径。
func PhonebookFile() string {
	appData := os.Getenv("APPDATA")
	if appData == "" {
		return ""
	}
	return filepath.Join(appData, "Microsoft", "Network", "Connections", "PBK", "rasphone.pbk")
}

// OsBuildNumber 返回 Windows 内部版本号（失败返回 0）。
func OsBuildNumber() int {
	ntdll := syscall.NewLazyDLL("ntdll.dll")
	proc := ntdll.NewProc("RtlGetVersion")
	if proc.Find() != nil {
		return 0
	}
	buf := make([]byte, 284) // sizeof(OSVERSIONINFOW) + slack
	writeUint32(buf, 0, 284)
	ret, _, _ := proc.Call(uintptr(unsafe.Pointer(&buf[0])))
	if ret != 0 {
		return 0
	}
	return int(readUint32(buf, 12)) // dwBuildNumber
}

// AppsUseLightTheme 读取 Windows 应用深浅色设置（0 = 深色）。
// 走注册表 API 而非 reg.exe 子进程：GUI 进程下每次查询都会闪一个 cmd 黑框。
func AppsUseLightTheme() bool {
	key, err := registry.OpenKey(registry.CURRENT_USER,
		`Software\Microsoft\Windows\CurrentVersion\Themes\Personalize`, registry.READ)
	if err != nil {
		return true
	}
	defer key.Close()
	v, _, err := key.GetIntegerValue("AppsUseLightTheme")
	if err != nil {
		return true
	}
	return v != 0
}

func writeUint32(b []byte, off, v int) {
	b[off] = byte(v)
	b[off+1] = byte(v >> 8)
	b[off+2] = byte(v >> 16)
	b[off+3] = byte(v >> 24)
}

func readUint32(b []byte, off int) uint32 {
	return uint32(b[off]) | uint32(b[off+1])<<8 | uint32(b[off+2])<<16 | uint32(b[off+3])<<24
}

// NowMillis 返回当前毫秒时间戳。
func NowMillis() int64 { return time.Now().UnixMilli() }
