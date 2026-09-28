// Package i18n 提供与旧版 ResourceBundle 等价的中英文案表。
//
// 语言有两种模式：
//   - 跟随系统（默认）：中文语系走中文，其余走英文；系统语言在程序运行期间发生变化时，
//     由调用方周期性调用 RefreshSystemLang 触发切换。
//   - 显式覆盖：SetLang("zh" / "en") 固定语言，不再跟随系统。
//
// 缺失的 key 返回 key 本身。
package i18n

import (
	"strings"
	"sync"
)

// 语言标识
const (
	ZH = "zh"
	EN = "en"
)

var (
	mu       sync.RWMutex
	override string // 显式覆盖："" 表示跟随系统
	system   string // 最近一次探测到的系统语言
)

func init() {
	system = detectSystemLang()
}

// DetectSystemLang 立即探测当前系统语言（zh / en），不改动内部状态。
func DetectSystemLang() string {
	l := detectSystemLang()
	if l != EN {
		return ZH
	}
	return EN
}

// SystemLang 返回最近一次探测到的系统语言（缓存值，不重新探测）。
func SystemLang() string {
	mu.RLock()
	defer mu.RUnlock()
	return system
}

// RefreshSystemLang 重新探测系统语言并刷新缓存，返回是否发生变化。
// 跟随系统模式下该变化即为生效语言的变化。
func RefreshSystemLang() bool {
	now := DetectSystemLang()
	mu.Lock()
	defer mu.Unlock()
	changed := now != system
	system = now
	return changed
}

// SetLang 设置语言："" / "auto" / "system" 表示跟随系统，zh / en 为显式覆盖，
// 其余非法值同样回退到跟随系统。
func SetLang(l string) {
	mu.Lock()
	defer mu.Unlock()
	override = normalizeOverride(l)
}

// IsAuto 当前是否处于跟随系统模式。
func IsAuto() bool {
	mu.RLock()
	defer mu.RUnlock()
	return override == ""
}

// Lang 返回当前生效语言：显式覆盖优先，否则跟随系统探测结果。
func Lang() string {
	mu.RLock()
	o, s := override, system
	mu.RUnlock()
	if o == EN || o == ZH {
		return o
	}
	if s == EN {
		return EN
	}
	return ZH
}

// T 取文案；缺失时返回 key。
func T(key string) string {
	if Lang() == EN {
		if v, ok := en[key]; ok && v != "" {
			return v
		}
	}
	if v, ok := zh[key]; ok && v != "" {
		return v
	}
	return key
}

// normalizeOverride 归一化覆盖值：空 / auto / system / follow 均表示跟随系统。
func normalizeOverride(l string) string {
	switch strings.ToLower(strings.TrimSpace(l)) {
	case "", "auto", "system", "follow", "default":
		return ""
	case EN:
		return EN
	case ZH:
		return ZH
	}
	return ""
}
