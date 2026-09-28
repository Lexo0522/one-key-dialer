package i18n

import (
	"regexp"
	"sort"
	"testing"
)

// 语言优先级：显式覆盖 > 系统探测；"" / auto / system 与非法值均回到跟随系统。
func TestLangOverrideAndAuto(t *testing.T) {
	t.Cleanup(func() { SetLang("") })

	SetLang(EN)
	if got := Lang(); got != EN {
		t.Fatalf("Lang() = %q, want %q", got, EN)
	}
	if IsAuto() {
		t.Fatal("IsAuto() = true, want false after explicit override")
	}

	SetLang(ZH)
	if got := Lang(); got != ZH {
		t.Fatalf("Lang() = %q, want %q", got, ZH)
	}

	SetLang("")
	if !IsAuto() {
		t.Fatal("IsAuto() = false, want true after SetLang(\"\")")
	}

	SetLang("klingon")
	if !IsAuto() {
		t.Fatal("IsAuto() = false, want true for unknown language")
	}
	if got := Lang(); got != ZH && got != EN {
		t.Fatalf("Lang() = %q, want zh or en", got)
	}
}

// 跟随模式下 RefreshSystemLang 生效；显式覆盖时探测结果不得改写生效语言。
func TestRefreshSystemLangKeepsOverride(t *testing.T) {
	t.Cleanup(func() { SetLang("") })

	SetLang(EN)
	_ = RefreshSystemLang()
	if got := Lang(); got != EN {
		t.Fatalf("Lang() = %q, want override to survive refresh", EN)
	}
	if got := SystemLang(); got != ZH && got != EN {
		t.Fatalf("SystemLang() = %q, want zh or en", got)
	}
}

// 缺失 key 返回 key 本身（与旧版 ResourceBundle 行为一致）。
func TestTMissingKey(t *testing.T) {
	const key = "no.such.key"
	if got := T(key); got != key {
		t.Fatalf("T(%q) = %q, want the key itself", key, got)
	}
}

var placeholderPattern = regexp.MustCompile(`\{(\d+)\}`)

func placeholderIndexes(s string) []int {
	seen := map[int]bool{}
	var out []int
	for _, m := range placeholderPattern.FindAllStringSubmatch(s, -1) {
		var idx int
		for i := 0; i < len(m[1]); i++ {
			idx = idx*10 + int(m[1][i]-'0')
		}
		if !seen[idx] {
			seen[idx] = true
			out = append(out, idx)
		}
	}
	sort.Ints(out)
	return out
}

func sameIndexes(a, b []int) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

// 英文表必须与中文表同键。缺 key 时 T 会静默回退到中文，
// 结果就是英文界面上按钮是英文、状态与错误提示是中文。
func TestEnHasEveryZhKey(t *testing.T) {
	var missing []string
	for k, v := range zh {
		if ev, ok := en[k]; !ok || ev == "" || v == "" {
			missing = append(missing, k)
		}
	}
	sort.Strings(missing)
	if len(missing) > 0 {
		t.Fatalf("en is missing %d key(s) defined in zh: %v", len(missing), missing)
	}

	var extra []string
	for k := range en {
		if _, ok := zh[k]; !ok {
			extra = append(extra, k)
		}
	}
	sort.Strings(extra)
	if len(extra) > 0 {
		t.Fatalf("en has %d key(s) with no zh counterpart: %v", len(extra), extra)
	}
}

// 两种语言的占位符下标必须一致，否则 Tf 在某一侧会漏参数或抛错。
func TestPlaceholderParity(t *testing.T) {
	var bad []string
	for k, zv := range zh {
		ev, ok := en[k]
		if !ok {
			continue
		}
		zi, ei := placeholderIndexes(zv), placeholderIndexes(ev)
		if !sameIndexes(zi, ei) {
			bad = append(bad, k)
		}
	}
	sort.Strings(bad)
	if len(bad) > 0 {
		t.Fatalf("placeholder mismatch between zh and en for %d key(s): %v", len(bad), bad)
	}
}
