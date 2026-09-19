package platform

import (
	"os"
	"path/filepath"
	"strings"

	"golang.org/x/sys/windows/registry"
)

// RunKeyPath HKCU 自启动注册表路径（REG_SZ）。
const RunKeyPath = `Software\Microsoft\Windows\CurrentVersion\Run`

// AutoStartFlag 追加到 Run 命令行后的标记，用于区分登录启动。
const AutoStartFlag = "--autostart"

// AutoStartDelayMs 登录启动后的 UI 延迟。
const AutoStartDelayMs = 3000

// QuoteWinArg 为 Windows 命令行加引号。
func QuoteWinArg(path string) string {
	if path == "" {
		return `""`
	}
	p := strings.ReplaceAll(path, `"`, `\"`)
	return `"` + p + `"`
}

// BuildExeRunCommand 构造 "C:\...\PPoEDialer.exe" --autostart。
func BuildExeRunCommand(exePath string) string {
	return QuoteWinArg(exePath) + " " + AutoStartFlag
}

// IsDirectLaunchCommand 判断 Run 值是否为直接启动命令（排除 wscript/cscript）。
func IsDirectLaunchCommand(cmd string) bool {
	c := strings.TrimSpace(cmd)
	if c == "" {
		return false
	}
	lower := strings.ToLower(c)
	if strings.Contains(lower, "wscript") || strings.Contains(lower, "cscript") {
		return false
	}
	if strings.Contains(lower, "javaw") && strings.Contains(lower, "-jar") {
		return true
	}
	return strings.Contains(lower, ".exe")
}

// ArgsContainAutoStart 判断命令行参数是否含自启动标记。
func ArgsContainAutoStart(args []string) bool {
	for _, a := range args {
		if a == AutoStartFlag {
			return true
		}
	}
	return false
}

// ReadRunValue 读取 Run 项取值；不存在返回 ""。
func ReadRunValue(valueName string) string {
	key, err := registry.OpenKey(registry.CURRENT_USER, RunKeyPath, registry.READ)
	if err != nil {
		return ""
	}
	defer key.Close()
	v, _, err := key.GetStringValue(valueName)
	if err != nil {
		return ""
	}
	return v
}

// WriteRunValue 写入 Run 项。
func WriteRunValue(valueName, value string) error {
	key, _, err := registry.CreateKey(registry.CURRENT_USER, RunKeyPath, registry.WRITE)
	if err != nil {
		return err
	}
	defer key.Close()
	return key.SetStringValue(valueName, value)
}

// DeleteRunValue 删除 Run 项。
func DeleteRunValue(valueName string) error {
	key, err := registry.OpenKey(registry.CURRENT_USER, RunKeyPath, registry.WRITE)
	if err != nil {
		return err
	}
	defer key.Close()
	return key.DeleteValue(valueName)
}

// CurrentExePath 返回当前进程的可执行文件绝对路径。
func CurrentExePath() string {
	exe, err := os.Executable()
	if err != nil {
		return ""
	}
	abs, err := filepath.Abs(exe)
	if err != nil {
		return exe
	}
	return abs
}
