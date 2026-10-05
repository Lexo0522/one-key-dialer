package update

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

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
