package i18n

import "testing"

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
