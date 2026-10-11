package platform

import (
	"os"
	"path/filepath"
	"strings"
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

// fakeProgramFiles 把三个 Program Files 环境变量指到一个真实可写的临时目录。
// 这样「目录可写、但位于系统固定安装位置」这一分支才被测得到——它正是提权
// 运行 Program Files 安装时的形态，也是数据目录漂移的根因。
func fakeProgramFiles(t *testing.T) string {
	t.Helper()
	root := t.TempDir()
	t.Setenv("ProgramFiles", root)
	t.Setenv("ProgramFiles(x86)", filepath.Join(root, "x86"))
	t.Setenv("ProgramW6432", root)
	return root
}

// TestIsUnderProgramFiles 固定安装位置判定：三个根及其子目录都算，前缀相似
// 但不是子目录的不算（C:\Program FilesXYZ 这种边界最容易出错）。
func TestIsUnderProgramFiles(t *testing.T) {
	pf := os.Getenv("ProgramFiles")
	pf86 := os.Getenv("ProgramFiles(x86)")
	if pf == "" {
		t.Skip("ProgramFiles env var not set")
	}
	cases := []struct {
		name string
		dir  string
		want bool
	}{
		{"Program Files 根本身", pf, true},
		{"Program Files 子目录", filepath.Join(pf, "PPPoEDialer"), true},
		{"Program Files (x86) 子目录", filepath.Join(pf86, "PPPoEDialer"), true},
		{"前缀相似但不是子目录", pf + "XYZ", false},
		{"用户目录", filepath.Join(t.TempDir(), "PPPoEDialer"), false},
		{"temp", os.TempDir(), false},
	}
	for _, c := range cases {
		if got := isUnderProgramFiles(c.dir); got != c.want {
			t.Errorf("%s: isUnderProgramFiles(%q) = %v, want %v", c.name, c.dir, got, c.want)
		}
	}

	// 环境变量全空时回退字面量，判定不能整体失效
	t.Setenv("ProgramFiles", "")
	t.Setenv("ProgramFiles(x86)", "")
	t.Setenv("ProgramW6432", "")
	if !isUnderProgramFiles(`C:\Program Files\PPPoEDialer`) {
		t.Error("env vars empty: literal Program Files fallback not matched")
	}
	if isUnderProgramFiles(`C:\Users\me\PPPoEDialer`) {
		t.Error("env vars empty: user dir wrongly matched")
	}
}

// TestPortableDataDir 数据目录规则：可写的用户目录可以，系统安装位置（即使
// 可写）、temp、bin 都不可以。
func TestPortableDataDir(t *testing.T) {
	// 不能用 t.TempDir()：它在 %TEMP% 下，本来就被规则排除。用当前工作目录
	// 造一个真实可写、又不属于任何排除位置的目录，测完删掉。
	userDir, err := os.MkdirTemp(".", "portable-dir-")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(userDir) })
	if got, ok := portableDataDir(userDir); !ok || got != userDir {
		t.Errorf("portableDataDir(%q) = (%q, %v), want (%q, true)", userDir, got, ok, userDir)
	}
	if _, ok := portableDataDir(os.TempDir()); ok {
		t.Error("portableDataDir(temp) = ok, want false")
	}
	// bin 目录同样真实可写，只因为名字是构建产物目录而被排除
	binDir := filepath.Join(userDir, "bin")
	if err := os.MkdirAll(binDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if _, ok := portableDataDir(binDir); ok {
		t.Error("portableDataDir(bin) = ok, want false")
	}
	if _, ok := portableDataDir(""); ok {
		t.Error("portableDataDir(\"\") = ok, want false")
	}

	// 关键分支：目录可写、但位于（伪造的）Program Files —— 必须拒绝。
	// 这正是提权运行 MSI 安装版时的形态，旧规则在这里把数据写进了系统目录。
	pf := fakeProgramFiles(t)
	installed := filepath.Join(pf, "PPPoEDialer")
	if err := os.MkdirAll(installed, 0o755); err != nil {
		t.Fatal(err)
	}
	if _, ok := portableDataDir(installed); ok {
		t.Errorf("portableDataDir(%q) = ok, want false: writable system install dir must not host data", installed)
	}
}

// TestSelfUpdatableDir 自更新判定：Program Files 恒为 false 且不因目录可写
// 而改变——否则提权会话会把 MSI 安装误判成便携版，改走 ZIP 覆盖。
func TestSelfUpdatableDir(t *testing.T) {
	pf := fakeProgramFiles(t)
	installed := filepath.Join(pf, "PPPoEDialer")
	if err := os.MkdirAll(installed, 0o755); err != nil {
		t.Fatal(err)
	}
	if selfUpdatableDir(installed) {
		t.Errorf("selfUpdatableDir(%q) = true, want false", installed)
	}
	userDir := t.TempDir()
	if !selfUpdatableDir(userDir) {
		t.Errorf("selfUpdatableDir(%q) = false, want true", userDir)
	}
	if selfUpdatableDir("") {
		t.Error("selfUpdatableDir(\"\") = true, want false")
	}
}

// TestMigrateDataFiles 一次性迁移：目标缺失才拷、目标已有不覆盖、白名单外的
// 文件不碰、目标目录不存在时先建。
func TestMigrateDataFiles(t *testing.T) {
	src := t.TempDir()
	dst := t.TempDir()
	write := func(dir, name, content string) {
		t.Helper()
		if err := os.WriteFile(filepath.Join(dir, name), []byte(content), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	write(src, "settings.json", "from-install-dir")
	write(src, "broadband.json", "enc")
	write(src, "wifi.json", "psk")
	write(src, "pppoe_log.txt", "log") // 白名单外
	// 目标已有一份 settings.json（普通权限会话保存的），不能被冲掉
	write(dst, "settings.json", "from-appdata")

	got := migrateDataFiles(src, dst)
	want := []string{"broadband.json", "wifi.json"}
	if strings.Join(got, ",") != strings.Join(want, ",") {
		t.Errorf("migrateDataFiles = %v, want %v", got, want)
	}
	if data, err := os.ReadFile(filepath.Join(dst, "settings.json")); err != nil || string(data) != "from-appdata" {
		t.Errorf("existing settings.json = %q (err %v), want it untouched", data, err)
	}
	if _, err := os.Stat(filepath.Join(dst, "pppoe_log.txt")); !os.IsNotExist(err) {
		t.Error("pppoe_log.txt was migrated, want it ignored (not user data)")
	}
	if data, err := os.ReadFile(filepath.Join(dst, "broadband.json")); err != nil || string(data) != "enc" {
		t.Errorf("broadband.json = %q (err %v), want it migrated", data, err)
	}

	// 幂等：再跑一次没有可迁的文件
	if again := migrateDataFiles(src, dst); len(again) != 0 {
		t.Errorf("second migrateDataFiles = %v, want empty", again)
	}

	// 目标目录不存在时先创建，再按白名单整体迁入
	fresh := filepath.Join(t.TempDir(), "PPPoEDialer")
	if got := migrateDataFiles(src, fresh); len(got) != 3 {
		t.Errorf("migrateDataFiles into a fresh dir = %v, want all three whitelisted files", got)
	}
}
