// Package platform 封装 Windows 相关能力：数据目录、ACL、DPAPI、注册表、RAS。
package platform

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"
	"unsafe"

	"github.com/Lexo0522/one-key-dialer/internal/model"
)

// AppDataFolder 回退数据目录名。
const AppDataFolder = "PPoEDialer"

var (
	dataDirOnce sync.Once
	dataDir     string
	installDir  string
)

// InstallDir 返回程序安装目录（打包版为 exe 所在目录）。
func InstallDir() string {
	if installDir != "" {
		return installDir
	}
	dir := ""
	if exe, err := os.Executable(); err == nil {
		abs, aerr := filepath.Abs(exe)
		if aerr == nil {
			name := strings.ToLower(filepath.Base(abs))
			// java.exe/javaw.exe 之类启动器不算安装目录（旧版同理）
			if strings.HasSuffix(name, ".exe") && name != "go.exe" {
				dir = filepath.Dir(abs)
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

// RelaunchExe 返回重启用的可执行文件路径（更新脚本钉住 PPoEDialer.exe）。
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
func AppsUseLightTheme() bool {
	out, err := exec.Command("reg", "query",
		`HKCU\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize`,
		"/v", "AppsUseLightTheme").Output()
	if err != nil {
		return true
	}
	text := strings.ToLower(string(out))
	idx := strings.Index(text, "0x")
	if idx < 0 {
		return true
	}
	end := idx + 2
	for end < len(text) && isHexDigit(text[end]) {
		end++
	}
	if end == idx+2 {
		return true
	}
	v := 0
	for i := idx + 2; i < end; i++ {
		v = v*16 + hexVal(text[i])
	}
	return v != 0
}

func isHexDigit(c byte) bool {
	return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
}

func hexVal(c byte) int {
	switch {
	case c >= '0' && c <= '9':
		return int(c - '0')
	case c >= 'a' && c <= 'f':
		return int(c-'a') + 10
	}
	return 0
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
