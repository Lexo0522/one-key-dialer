package platform

import (
	"os"
	"path/filepath"
	"testing"
)

// TestIsEphemeralExePath 临时位置/构建产物判为 ephemeral，正常安装路径不判。
func TestIsEphemeralExePath(t *testing.T) {
	tmp := os.TempDir()
	cases := []struct {
		name string
		exe  string
		want bool
	}{
		{"临时目录下的本体", filepath.Join(tmp, "PPPoEDialer.exe"), true},
		{"Wails bindings 产物", filepath.Join(tmp, "wailsbindings.exe"), true},
		{"temp 的子目录", filepath.Join(tmp, "staged-1", "PPPoEDialer.exe"), true},
		{"go build 产物", filepath.Join(`C:\src`, "go.exe"), true},
		{"空路径", "", true},
		{"正常安装目录", filepath.Join(`C:\Program Files\PPPoEDialer`, "PPPoEDialer.exe"), false},
		{"便携目录", filepath.Join(`D:\Tools`, "PPPoEDialer.exe"), false},
	}
	for _, c := range cases {
		if got := isEphemeralExePath(c.exe); got != c.want {
			t.Errorf("%s: isEphemeralExePath(%q) = %v, want %v", c.name, c.exe, got, c.want)
		}
	}
}

// TestInstallDirFromExe 安装目录判定必须排除两类路径：
//
//   - %TEMP% 下的产物。Wails 的 GenerateBindings（v2 pkg/commands/bindings/
//     bindings.go）会把本项目编译成 %TEMP%\wailsbindings.exe 来生成 TS 绑定，
//     那是本程序的完整二进制。判定不严时更新脚本会把新版 exe 写进 %TEMP% 并从
//     那里启动，开机自启动随之指向 %TEMP%，一个会被磁盘清理掉的副本就此接管
//     本机（真实故障：日志出现「检测到开机自启动配置异常，正在重新注册」）。
//   - go build 产物。
func TestInstallDirFromExe(t *testing.T) {
	tmp := os.TempDir()
	cases := []struct {
		name    string
		exe     string
		wantDir string
		wantOK  bool
	}{
		{"wails bindings 产物", filepath.Join(tmp, "wailsbindings.exe"), "", false},
		{"temp 下改名后的本体", filepath.Join(tmp, "PPPoEDialer.exe"), "", false},
		{"go build 产物", filepath.Join(`C:\src`, "go.exe"), "", false},
		{"正常安装目录", filepath.Join(`C:\Program Files\PPPoEDialer`, "PPPoEDialer.exe"),
			filepath.Join(`C:\Program Files\PPPoEDialer`), true},
		{"便携安装目录", filepath.Join(`D:\Tools`, "PPPoEDialer.exe"), filepath.Join(`D:\Tools`), true},
		{"空路径", "", "", false},
	}
	for _, c := range cases {
		gotDir, gotOK := installDirFromExe(c.exe)
		if gotDir != c.wantDir || gotOK != c.wantOK {
			t.Errorf("%s: installDirFromExe(%q) = (%q, %v), want (%q, %v)",
				c.name, c.exe, gotDir, gotOK, c.wantDir, c.wantOK)
		}
	}
}

// TestIsUnderTemp 临时目录判定覆盖「等于 temp」与「temp 的子目录」两种形态。
func TestIsUnderTemp(t *testing.T) {
	tmp := os.TempDir()
	under := filepath.Join(tmp, "staged-1")
	if !isUnderTemp(under) {
		t.Errorf("isUnderTemp(%q) = false, want true", under)
	}
	// 前后分隔符差异不应影响判定
	withSep := tmp + string(os.PathSeparator)
	if !isUnderTemp(withSep) {
		t.Errorf("isUnderTemp(%q) = false, want true", withSep)
	}
	if isUnderTemp(`C:\Program Files`) {
		t.Error("isUnderTemp(Program Files) = true, want false")
	}
}
