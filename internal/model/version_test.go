package model

import (
	"os"
	"strings"
	"testing"
)

// Display 必须始终只带一个 v 前缀：注入值自带 v / V 时不能拼成 vv1.x.x。
func TestDisplaySingleVPrefix(t *testing.T) {
	orig := version
	t.Cleanup(func() { version = orig })

	cases := map[string]string{
		"1.1.11":  "v1.1.11",
		"v1.1.11": "v1.1.11",
		"V1.1.11": "v1.1.11",
	}
	for in, want := range cases {
		version = in
		if got := Display(); got != want {
			t.Fatalf("Display() with version=%q = %q, want %q", in, got, want)
		}
	}
}

// -ldflags 的 -X 直接改写包变量、不经 SetVersion 的非空保护：空串注入时
// Version() 必须收敛到兜底值，否则界面版本号空白、更新比较也失去基准。
func TestVersionNeverEmpty(t *testing.T) {
	orig := version
	t.Cleanup(func() { version = orig })

	for _, injected := range []string{"", "   ", "\t\n"} {
		version = injected
		if got := Version(); got != fallbackVersion {
			t.Fatalf("Version() with version=%q = %q, want fallback %q",
				injected, got, fallbackVersion)
		}
		// Display / UserAgent 同样不能出现空版本或裸 "v"
		if got := Display(); got != "v"+fallbackVersion {
			t.Fatalf("Display() with version=%q = %q", injected, got)
		}
		if got := UserAgent(); !strings.HasSuffix(got, "/"+fallbackVersion) {
			t.Fatalf("UserAgent() with version=%q = %q", injected, got)
		}
	}
}

// 兜底值必须与 version.txt、wails.json 的 productVersion 一致：
// 三处漂移会让「界面显示的版本」与「exe 属性页」对不上，
// 且影响更新比较（doCheckUpdate 传 model.Version() 作当前版本）。
func TestFallbackMatchesVersionTxt(t *testing.T) {
	data, err := os.ReadFile("../../version.txt")
	if err != nil {
		t.Skipf("version.txt not readable: %v", err)
	}
	want := strings.TrimSpace(string(data))
	if want == "" {
		t.Fatal("version.txt is empty")
	}
	if fallbackVersion != want {
		t.Fatalf("fallbackVersion = %q, but version.txt = %q — keep them in sync",
			fallbackVersion, want)
	}
	if version != want {
		t.Fatalf("package var version = %q, but version.txt = %q — keep them in sync",
			version, want)
	}
}

// StripV 只去掉首字母 v / V，且不破坏过短字符串。
func TestStripV(t *testing.T) {
	cases := map[string]string{
		"v1.2.3": "1.2.3",
		"V1.2.3": "1.2.3",
		"1.2.3":  "1.2.3",
		"v":      "v",
		"":       "",
	}
	for in, want := range cases {
		if got := StripV(in); got != want {
			t.Fatalf("StripV(%q) = %q, want %q", in, got, want)
		}
	}
}
