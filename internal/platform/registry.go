package platform

import (
	"errors"
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

// RunCommandTarget 从 Run 值里解析出它要启动的可执行文件路径。
//
// 只认本程序写入的形态（BuildExeRunCommand 产物：`"C:\path\app.exe" --autostart`）：
// 优先取首尾引号之间的内容，其次取首个空白分隔的 token。解析不出、或不是
// 绝对路径时返回空串——调用方必须把空串当作「无法判断」，绝不能据此删除
// 注册表值（相对路径的目标存在性无从谈起）。
func RunCommandTarget(cmd string) string {
	c := strings.TrimSpace(cmd)
	if c == "" {
		return ""
	}
	var target string
	switch {
	case strings.HasPrefix(c, `"`):
		if end := strings.LastIndex(c, `"`); end > 0 {
			target = c[1:end]
		}
	default:
		// 无引号：取首个空白分隔的 token；整串没有空白时它就是目标
		if i := strings.IndexAny(c, " \t"); i > 0 {
			target = c[:i]
		} else {
			target = c
		}
	}
	target = strings.TrimSpace(target)
	if !filepath.IsAbs(target) {
		return ""
	}
	return target
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
		// 未注册时视为删除成功，避免首次关闭开机自启被误报失败。
		if errors.Is(err, registry.ErrNotExist) {
			return nil
		}
		return err
	}
	defer key.Close()
	if err := key.DeleteValue(valueName); err != nil && !errors.Is(err, registry.ErrNotExist) {
		return err
	}
	return nil
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

// IsEphemeralExePath 当前进程是否处于一次性位置（临时目录 / 构建产物）。
// 见 isEphemeralExePath 的说明：这类进程不应注册自启动或自我更新。
func IsEphemeralExePath() bool {
	return isEphemeralExePath(CurrentExePath())
}
