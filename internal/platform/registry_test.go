package platform

import (
	"path/filepath"
	"testing"
)

// TestRunCommandTarget 从 Run 值解析启动目标：只认本程序写入的形态，
// 解析不出或不是绝对路径时返回空串——调用方把空串当「无法判断」，绝不删注册表。
func TestRunCommandTarget(t *testing.T) {
	cases := []struct {
		name string
		cmd  string
		want string
	}{
		{
			"带空格引号路径加自启动标记",
			`"C:\Program Files\PPPoEDialer\PPPoEDialer.exe" --autostart`,
			`C:\Program Files\PPPoEDialer\PPPoEDialer.exe`,
		},
		{
			"引号路径不带参数",
			`"D:\Tools\PPPoEDialer\PPPoEDialer.exe"`,
			`D:\Tools\PPPoEDialer\PPPoEDialer.exe`,
		},
		{
			"无引号无空格路径",
			`C:\PPPoEDialer\PPPoEDialer.exe --autostart`,
			`C:\PPPoEDialer\PPPoEDialer.exe`,
		},
		{"空串", "", ""},
		{"空白串", "   ", ""},
		{"相对路径", `PPPoEDialer.exe --autostart`, ""},
		{"引号未闭合", `"C:\Program Files\PPPoEDialer\PPPoEDialer.exe`, ""},
		{"垃圾串", `not a path at all`, ""},
	}
	for _, c := range cases {
		if got := RunCommandTarget(c.cmd); got != c.want {
			t.Errorf("%s: RunCommandTarget(%q) = %q, want %q", c.name, c.cmd, got, c.want)
		}
	}
}

// TestBuildExeRunCommandRoundTrip 自己写的值必须能解析回自己：
// RunCommandTarget 与 BuildExeRunCommand 是一对，改一头必须改另一头。
func TestBuildExeRunCommandRoundTrip(t *testing.T) {
	for _, exe := range []string{
		filepath.Join(`C:\Program Files\PPPoEDialer`, "PPPoEDialer.exe"),
		filepath.Join(`D:\Tools\one key dialer`, "PPPoEDialer.exe"),
	} {
		if got := RunCommandTarget(BuildExeRunCommand(exe)); got != exe {
			t.Errorf("round trip: RunCommandTarget(BuildExeRunCommand(%q)) = %q", exe, got)
		}
	}
}
