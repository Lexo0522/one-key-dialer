package model

import "testing"

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
