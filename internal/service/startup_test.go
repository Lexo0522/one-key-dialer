package service

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Lexo0522/one-key-dialer/internal/platform"
)

// newFakeStartup 构造一个不碰真实注册表的 StartupService,
// 并把 exe 路径指向 dir 下的 AppName。
func newFakeStartup(t *testing.T, dir, runValue string, ephemeral bool, writeErr error) (*StartupService, *[]string) {
	t.Helper()
	logger := NewLogService(filepath.Join(t.TempDir(), "log.txt"))
	s := NewStartupService(logger)

	exe := filepath.Join(dir, "PPPoEDialer.exe")
	// exe 必须真实存在:IsHealthy 用 fileExists 校验
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(exe, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}

	reg := map[string]string{AutoStartValueName: runValue}
	var written []string
	s.exePath = func() string { return exe }
	s.ephemeralExe = func() bool { return ephemeral }
	s.readRun = func(n string) string { return reg[n] }
	s.writeRun = func(n, v string) error {
		if writeErr != nil {
			return writeErr
		}
		reg[n] = v
		written = append(written, v)
		return nil
	}
	s.deleteRun = func(n string) error { reg[n] = ""; return nil }
	return s, &written
}

// TestEnsureHealthyRefusesTempInstall 本体位于临时目录/构建产物时,
// EnsureHealthy 不得重新注册,尤其不许把 %TEMP% 写进 Run 值。
//
// 历史故障:InstallDir 曾把 Wails 生成绑定时编译出的 %TEMP%\wailsbindings.exe
// 当作程序本体,更新脚本把新版写进 %TEMP% 并从那里启动,开机自启动随之指向
// temp。此后 IsHealthy 拿 temp 路径与注册表里的 temp 路径互相比对、判"健康",
// 自愈链失明——它每次启动都"修好"一个错误的目标,日志反复出现
// 「检测到开机自启动配置异常，正在重新注册」。
func TestEnsureHealthyRefusesTempInstall(t *testing.T) {
	for _, c := range []struct {
		name      string
		wantOK    bool
		wantWrite int
	}{
		{"注册表已指向 temp 本体(自愈链失明的形态)", false, 0},
	} {
		t.Run(c.name, func(t *testing.T) {
			tmp := t.TempDir()
			// 注册表里已经是 temp 下的本体 —— 旧逻辑会判"健康"从而什么都不做
			stale := platform.BuildExeRunCommand(filepath.Join(tmp, "PPPoEDialer.exe"))
			s, written := newFakeStartup(t, tmp, stale, true, nil)

			if ok := s.EnsureHealthy(true); ok {
				t.Error("EnsureHealthy(true) = true, want false：temp 本体不该算健康")
			}
			if len(*written) != c.wantWrite {
				t.Errorf("写入 Run 值 %d 次, want %d: %v", len(*written), c.wantWrite, *written)
			}
		})
	}
}

// TestEnableRefusesTempInstall Enable 同样不许把 temp 路径写进注册表。
func TestEnableRefusesTempInstall(t *testing.T) {
	tmp := t.TempDir()
	s, written := newFakeStartup(t, tmp, "", true, nil)
	if s.Enable() {
		t.Error("Enable() = true, want false")
	}
	if len(*written) != 0 {
		t.Errorf("Run 值被写入 %v, want 空", *written)
	}
}

// TestEnsureHealthyRepairsPermanentInstall 正常安装目录下的重新注册行为不变。
func TestEnsureHealthyRepairsPermanentInstall(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "PPPoEDialer")
	correct := platform.BuildExeRunCommand(filepath.Join(dir, "PPPoEDialer.exe"))

	// 注册表里指向一个不存在的旧路径 → 应当重写成当前本体
	s, written := newFakeStartup(t, dir, `"C:\Program Files\Old\PPPoEDialer.exe" --autostart`, false, nil)
	if !s.EnsureHealthy(true) {
		t.Fatal("EnsureHealthy(true) = false, want true")
	}
	if len(*written) != 1 {
		t.Fatalf("写入 Run 值 %d 次, want 1", len(*written))
	}
	if !strings.EqualFold((*written)[0], correct) {
		t.Errorf("写入 %q, want %q", (*written)[0], correct)
	}

	// 注册表已正确 → 不该再写
	s2, written2 := newFakeStartup(t, dir, correct, false, nil)
	if !s2.EnsureHealthy(true) {
		t.Fatal("EnsureHealthy(true) 第二次 = false, want true")
	}
	if len(*written2) != 0 {
		t.Errorf("健康时仍写入 %v, want 空", *written2)
	}

	// 写入失败 → 报告失败
	s3, _ := newFakeStartup(t, dir, `"C:\gone.exe"`, false, errors.New("access denied"))
	if s3.EnsureHealthy(true) {
		t.Error("写注册表失败时 EnsureHealthy = true, want false")
	}
}

// TestEnsureHealthyCleansStaleRunWhenDisabled 未勾选开机自启时，指向已不存在
// 程序的死链必须被清掉。
//
// 两类来源：卸载 MSI / 删掉便携版目录后 Run 值仍在；以及改装到别处后 Run 值
// 还指着旧路径。死链每次开机都被 Windows 静默尝试启动，用户侧毫无线索；而
// wantAutoStart=true 的自愈分支只在设置要求自启动时才改写，设置关闭时死链
// 会一直留着。注意清理走 deleteRun 而非 writeRun：不改写、只摘除。
func TestEnsureHealthyCleansStaleRunWhenDisabled(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "PPPoEDialer")
	s, written := newFakeStartup(t, dir, `"C:\gone.exe" --autostart`, false, nil)
	if s.EnsureHealthy(false) {
		t.Error("wantAutoStart=false 时返回 true, want false(死链不算健康)")
	}
	if len(*written) != 0 {
		t.Errorf("清理死链时写入了 Run 值 %v, want 空(应删除而非改写)", *written)
	}
	if got := s.readRun(AutoStartValueName); got != "" {
		t.Errorf("死链未被清理, Run 值仍是 %q", got)
	}
}

// TestEnsureHealthyKeepsLiveEntryWhenDisabled 目标仍然存在的条目一律不动，
// 即使它指向别的路径：宁可留一条暂时没用到的死链，也不能删错用户的东西。
func TestEnsureHealthyKeepsLiveEntryWhenDisabled(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "PPPoEDialer")
	other := filepath.Join(t.TempDir(), "PPPoEDialer.exe")
	if err := os.WriteFile(other, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	s, written := newFakeStartup(t, dir, platform.BuildExeRunCommand(other), false, nil)
	if s.EnsureHealthy(false) {
		t.Error("wantAutoStart=false 时返回 true, want false(指向的不是当前本体)")
	}
	if len(*written) != 0 {
		t.Errorf("未勾选时仍写入 %v, want 空", *written)
	}
	if got := s.readRun(AutoStartValueName); got == "" {
		t.Error("指向存在文件的条目被误删")
	}
}

// TestEnsureHealthyIgnoresUnparsableRun 解析不出目标的 Run 值一律不碰：
// 相对路径的目标存在性无从谈起，删了就是删错。
func TestEnsureHealthyIgnoresUnparsableRun(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "PPPoEDialer")
	s, written := newFakeStartup(t, dir, `PPPoEDialer.exe --autostart`, false, nil)
	s.EnsureHealthy(false)
	if len(*written) != 0 {
		t.Errorf("不可解析的 Run 值被写入 %v, want 空", *written)
	}
	if got := s.readRun(AutoStartValueName); got == "" {
		t.Error("不可解析的 Run 值被误删")
	}
}
