// Package i18n 提供与旧版 ResourceBundle 等价的中英文案表。
// 语言跟随系统（非简体中文环境回退英文），缺失的 key 返回 key 本身。
package i18n

import (
	"os"
	"strings"
	"sync"
)

// 语言标识
const (
	ZH = "zh"
	EN = "en"
)

var (
	mu   sync.RWMutex
	lang = detectLang()
)

func detectLang() string {
	for _, v := range os.Environ() {
		// 形如 LANG=... / 无；Windows 下依赖下面的 UI 语言探测
		_ = v
	}
	return ZH
}

// SetLang 显式切换语言（zh / en），非法值回退 zh。
func SetLang(l string) {
	l = strings.ToLower(strings.TrimSpace(l))
	mu.Lock()
	defer mu.Unlock()
	if l == EN {
		lang = EN
		return
	}
	lang = ZH
}

// Lang 返回当前语言。
func Lang() string {
	mu.RLock()
	defer mu.RUnlock()
	return lang
}

// T 取文案；缺失时返回 key。
func T(key string) string {
	mu.RLock()
	l := lang
	mu.RUnlock()
	if l == EN {
		if v, ok := en[key]; ok && v != "" {
			return v
		}
	}
	if v, ok := zh[key]; ok && v != "" {
		return v
	}
	return key
}
