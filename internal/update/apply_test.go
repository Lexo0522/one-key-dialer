package update

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"

	"github.com/Lexo0522/one-key-dialer/internal/model"
)

// createNoWindow 与 platform.hiddenProcAttr 的 CreationFlags 一致。
const createNoWindow = 0x08000000

// TestZipApplyScriptRefusesTempInstallDir zip 更新脚本必须拒绝把安装包
// 写进临时目录。
//
// 背景：InstallDir 曾把 Wails 生成绑定时编译出的 %TEMP%\wailsbindings.exe
// 当作程序本体，使 DST 变成 %TEMP%，新版 exe 被写进 %TEMP% 并从那里启动，
// 开机自启动也指向那——本机被一个会被磁盘清理掉的副本接管。这里是脚本层的
// 兜底：即使 DST 因任何原因异常，也绝不往 %TEMP% 写。
func TestZipApplyScriptRefusesTempInstallDir(t *testing.T) {
	dir := t.TempDir()
	m := &Module{updatesDir: dir}
	script, err := m.writeZipApplyScript(filepath.Join(os.TempDir(), "staged-x"), t.TempDir(), nil)
	if err != nil {
		t.Fatalf("writeZipApplyScript: %v", err)
	}
	data, err := os.ReadFile(script)
	if err != nil {
		t.Fatalf("read script: %v", err)
	}
	body := string(data)

	// findstr /I /C: 是字面量匹配；find /I 的模式串是正则，含 \ 与 . 都会失配
	if !strings.Contains(body, "findstr /I /C:") {
		t.Errorf("script lacks findstr guard:\n%s", body)
	}
	if !strings.Contains(body, "Refusing to install into a temporary directory.") {
		t.Errorf("script lacks temp refusal message:\n%s", body)
	}
	// 正常安装目录不应触发拒绝
	okScript, err := m.writeZipApplyScript(filepath.Join(dir, "payload"),
		filepath.Join(`C:\Program Files\PPPoEDialer`), nil)
	if err != nil {
		t.Fatalf("writeZipApplyScript(ok): %v", err)
	}
	okData, err := os.ReadFile(okScript)
	if err != nil {
		t.Fatalf("read ok script: %v", err)
	}
	if !strings.Contains(string(okData), "xcopy") {
		t.Errorf("normal install script missing xcopy:\n%s", okData)
	}
}

// TestFindPayloadRoot 覆盖发布 zip 的两种布局：exe 平铺在根（旧版
// Compress-Archive 单文件打包）与根下唯一文件夹（现行固定名 PPPoEDialer
// 文件夹）。更新端两种都必须能定位负载根，且不依赖文件夹名。
func TestFindPayloadRoot(t *testing.T) {
	t.Run("flat exe at root", func(t *testing.T) {
		staged := t.TempDir()
		if err := os.WriteFile(filepath.Join(staged, model.AppName), []byte("x"), 0o644); err != nil {
			t.Fatalf("write exe: %v", err)
		}
		if got := findPayloadRoot(staged); got != staged {
			t.Fatalf("flat layout: payload root = %q, want %q", got, staged)
		}
	})
	t.Run("single wrapping folder", func(t *testing.T) {
		staged := t.TempDir()
		inner := filepath.Join(staged, "PPPoEDialer")
		if err := os.MkdirAll(inner, 0o755); err != nil {
			t.Fatalf("mkdir: %v", err)
		}
		if err := os.WriteFile(filepath.Join(inner, model.AppName), []byte("x"), 0o644); err != nil {
			t.Fatalf("write exe: %v", err)
		}
		if got := findPayloadRoot(staged); got != inner {
			t.Fatalf("wrapped layout: payload root = %q, want %q", got, inner)
		}
	})
}

// TestWaitForAppExitUsesConsoleFreeSleep 等待循环必须用不依赖控制台的休眠原语。
//
// 背景是一次真实故障：更新脚本由 platform.LaunchDetached 以 CREATE_NO_WINDOW +
// stdin=NUL 启动，timeout 在这个环境下根本不睡（要么立刻报「不支持输入重定向」
// 退出，要么一直挂着）。于是「最多等 30 秒」实际 0.5 秒就跑完，安装器在应用
// 进程还活着时就上手覆盖 exe——xcopy / msiexec 撞上文件占用，更新静默失败，
// 而程序已经退出、再也不会被重启。用户侧看到的就是：点完安装，程序退出，
// 然后什么都不发生。
func TestWaitForAppExitUsesConsoleFreeSleep(t *testing.T) {
	m := &Module{updatesDir: t.TempDir()}
	script, err := m.writeZipApplyScript(filepath.Join(t.TempDir(), "payload"),
		t.TempDir(), []int{4242})
	if err != nil {
		t.Fatalf("writeZipApplyScript: %v", err)
	}
	body, err := os.ReadFile(script)
	if err != nil {
		t.Fatalf("read script: %v", err)
	}
	s := string(body)

	if strings.Contains(s, "timeout") {
		t.Errorf("等待循环仍在使用 timeout：无控制台环境下它不会休眠，等待形同虚设:\n%s", s)
	}
	if !strings.Contains(s, WaitSleepCmd) {
		t.Errorf("等待循环缺少无控制台休眠原语 %q:\n%s", WaitSleepCmd, s)
	}
	// 每个 PID 一段独立循环；循环里探测与休眠都得在
	if got := strings.Count(s, "for /L"); got != 1 {
		t.Errorf("for /L 循环数 = %d, want 1（每个等待 PID 一段）:\n%s", got, s)
	}
	if !strings.Contains(s, `tasklist /FI "PID eq 4242"`) {
		t.Errorf("等待循环缺少 PID 探测:\n%s", s)
	}
}

// TestWaitSleepCmdSleepsWithoutConsole 休眠原语必须在与 LaunchDetached 一致的
// 环境下真的睡够一秒。
//
// 上面那条只验证脚本文本，挡不住有人把 WaitSleepCmd 换成另一个「看起来在睡」
// 的命令；这条直接在真实运行条件下执行它，是那次故障唯一的运行时证据。
func TestWaitSleepCmdSleepsWithoutConsole(t *testing.T) {
	if testing.Short() {
		t.Skip("short 模式跳过外部进程探针")
	}
	dir := t.TempDir()
	bat := filepath.Join(dir, "sleep.bat")
	if err := os.WriteFile(bat, []byte("@echo off\r\n"+WaitSleepCmd+"\r\n"), 0o644); err != nil {
		t.Fatalf("write bat: %v", err)
	}
	// 三件套必须与 platform.LaunchDetached 完全一致：cmd /c、
	// CREATE_NO_WINDOW、不设 Stdin（Go 的 exec 会给子进程接 NUL）。
	// 缺任何一件，timeout 之流就会表现正常，测试再也抓不到故障环境。
	cmd := exec.Command("cmd.exe", "/c", bat)
	cmd.Dir = dir
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: createNoWindow}
	start := time.Now()
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("%q 执行失败: %v out=%q", WaitSleepCmd, err, out)
	}
	if elapsed := time.Since(start); elapsed < 800*time.Millisecond {
		t.Errorf("%q 只用了 %v：在无控制台环境下没有真正休眠，等待循环会瞬间跑完",
			WaitSleepCmd, elapsed)
	}
}
