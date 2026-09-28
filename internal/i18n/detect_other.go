//go:build !windows

package i18n

import (
	"os"
	"strings"
)

// 区域环境变量优先级（POSIX 惯例）：LC_ALL > LC_MESSAGES > LANG。
var localeEnvKeys = []string{"LC_ALL", "LC_MESSAGES", "LANG"}

// detectSystemLang 非 Windows 分支：按区域环境变量判定语言。
// zh*（含 zh_CN / zh_TW / zh_HK）归入中文，其余归入英文；
// 变量缺失或为 C / POSIX 时保持历史默认（中文）。
func detectSystemLang() string {
	for _, key := range localeEnvKeys {
		v := strings.TrimSpace(os.Getenv(key))
		if v == "" {
			continue
		}
		// 形如 zh_CN.UTF-8 / en_US.UTF-8，去掉编码与修饰符后只看语言段
		base := v
		if i := strings.IndexByte(base, '.'); i >= 0 {
			base = base[:i]
		}
		if i := strings.IndexByte(base, '@'); i >= 0 {
			base = base[:i]
		}
		base = strings.ToLower(strings.TrimSpace(base))
		// C / POSIX 表示「无区域设置」，不参与判定
		if base == "" || base == "c" || base == "posix" {
			continue
		}
		if strings.HasPrefix(base, "zh") {
			return ZH
		}
		return EN
	}
	return ZH
}
