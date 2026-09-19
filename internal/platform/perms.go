package platform

import (
	"os/exec"
	"time"
)

// RestrictToOwner 收紧文件 ACL 为 所有者 + SYSTEM + Administrators。
// 与旧版一致：失败静默忽略（best-effort）。
func RestrictToOwner(path string) {
	cmd := exec.Command("icacls", path,
		"/inheritance:r",
		"/grant:r", "*S-1-3-4:F",
		"/grant:r", "SYSTEM:F",
		"/grant:r", "*S-1-5-32-544:F")
	cmd.SysProcAttr = hiddenProcAttr()
	_ = cmd.Start()
	done := make(chan struct{})
	go func() {
		_ = cmd.Wait()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(15 * time.Second):
		if cmd.Process != nil {
			_ = cmd.Process.Kill()
		}
	}
}
