package platform

import (
	"context"
	"errors"
	"os/exec"
	"time"
)

// icaclsAclTimeout ACL 收紧的最长等待。icacls 在本机通常几十毫秒返回，
// 卡住多半是杀软在拦文件句柄，等到超时就放弃。
const icaclsAclTimeout = 15 * time.Second

// RestrictToOwner 收紧文件 ACL 为 所有者 + SYSTEM + Administrators。
//
// best-effort 语义仍保留在调用侧：这里如实返回错误，是否中断写入由调用方决定。
// 之前吞掉返回值的代价是——icacls 超时被 Kill 后 ACL 很可能没施加，而调用方
// 已经认为成功了，含 DPAPI 凭据的 settings.json / broadband.json 就停在
// 继承权限下。宁可让调用方看见失败。
func RestrictToOwner(path string) error {
	ctx, cancel := context.WithTimeout(context.Background(), icaclsAclTimeout)
	defer cancel()
	// CommandContext 在超时时杀进程并负责 Wait，不留僵尸进程；
	// 同时把父子进程一起收掉，避免只在 PATH 找不到 icacls 时静默失败。
	cmd := exec.CommandContext(ctx, "icacls", path,
		"/inheritance:r",
		"/grant:r", "*S-1-3-4:F",
		"/grant:r", "SYSTEM:F",
		"/grant:r", "*S-1-5-32-544:F")
	cmd.SysProcAttr = hiddenProcAttr()
	out, err := cmd.CombinedOutput()
	if err != nil {
		// 退出码非 0 与超时/启动失败都归到这里：统一带一段输出，便于排查
		// 是权限不足、路径不存在还是被安全软件拦截。
		return errors.Join(err, errors.New(string(out)))
	}
	return nil
}
