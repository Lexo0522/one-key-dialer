//go:build windows

package i18n

import (
	"strings"
	"syscall"

	"golang.org/x/sys/windows/registry"
)

// Windows LANGID：低 10 位为主语言 ID（PRIMARYLANGID）。
const (
	langChinese = 0x04
	primaryMask = 0x3FF
)

// 显示语言注册表位置（Windows 8+）：REG_MULTI_SZ，首项即当前显示语言。
const (
	uiLangKeyPath   = `Control Panel\Desktop`
	uiLangValueName = "PreferredUILanguages"
)

var (
	kernel32                   = syscall.NewLazyDLL("kernel32.dll")
	procGetUserDefaultUILang   = kernel32.NewProc("GetUserDefaultUILanguage")
	procGetSystemDefaultUILang = kernel32.NewProc("GetSystemDefaultUILanguage")
)

// detectSystemLang Windows 分支，按可靠性依次尝试：
//  1. HKCU\Control Panel\Desktop\PreferredUILanguages —— 「设置 → 时间和语言 →
//     显示语言」改动后即时更新，不依赖进程缓存，是运行期探测的首选；
//  2. GetUserDefaultUILanguage —— 用户默认 UI 语言；
//  3. GetSystemDefaultUILanguage —— 系统默认 UI 语言；
//  4. 全部失败时回退中文（保持历史行为）。
func detectSystemLang() string {
	if tag, ok := preferredUILanguage(); ok {
		if l := langFromTag(tag); l != "" {
			return l
		}
	}
	if id, ok := uiLangID(procGetUserDefaultUILang); ok {
		return langFromID(id)
	}
	if id, ok := uiLangID(procGetSystemDefaultUILang); ok {
		return langFromID(id)
	}
	return ZH
}

// preferredUILanguage 读取显示语言首选项，形如 "zh-CN" / "en-US"（BCP-47）。
func preferredUILanguage() (string, bool) {
	key, err := registry.OpenKey(registry.CURRENT_USER, uiLangKeyPath, registry.READ)
	if err != nil {
		return "", false
	}
	defer key.Close()
	vals, _, err := key.GetStringsValue(uiLangValueName)
	if err != nil {
		return "", false
	}
	for _, v := range vals {
		if tag := strings.TrimSpace(v); tag != "" {
			return tag, true
		}
	}
	return "", false
}

// uiLangID 调用 LANGID 型 API；未找到导出或返回 0 视为失败。
func uiLangID(p *syscall.LazyProc) (uint16, bool) {
	if p == nil || p.Find() != nil {
		return 0, false
	}
	ret, _, _ := p.Call()
	if ret == 0 {
		return 0, false
	}
	return uint16(ret), true
}

// langFromTag 由 BCP-47 标签判定语种；无法判定时返回空串交由调用方继续兜底。
func langFromTag(tag string) string {
	tag = strings.ToLower(strings.TrimSpace(tag))
	if tag == "" {
		return ""
	}
	if strings.HasPrefix(tag, "zh") {
		return ZH
	}
	return EN
}

// langFromID 由 LANGID 判定语种。
//
// 说明：本项目只有简体中文与英文两套文案，因此中文语系（含繁体 zh-TW / zh-HK /
// zh-MO）统一归入 zh——对繁体用户而言，简体文案仍优于英文。若后续要求「非简体
// 中文一律回退英文」，把这里的判断收紧到 sublang 级别（简体仅 zh-CN 0x0804 与
// zh-SG 0x1004）即可。
func langFromID(id uint16) string {
	if id&primaryMask == langChinese {
		return ZH
	}
	return EN
}
