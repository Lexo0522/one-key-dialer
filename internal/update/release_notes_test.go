package update

import (
	"strings"
	"testing"
	"unicode/utf8"
)

// 渠道正文是第三方自由文本：CRLF、BOM、连续空行都要归一化，
// 否则对话框里会出现双倍行距或首行乱码。
func TestReleaseNotesNormalizes(t *testing.T) {
	cases := []struct {
		name string
		in   string
		want string
	}{
		{"plain", "hello", "hello"},
		{"crlf", "a\r\nb", "a\nb"},
		{"lone-cr", "a\rb", "a\nb"},
		{"bom", "\ufeff说明正文", "说明正文"},
		{"trim", "  spaced  ", "spaced"},
		{"collapse-blank", "a\n\n\n\nb", "a\n\nb"},
		{"keeps-paragraph-break", "para1\n\npara2", "para1\n\npara2"},
		{"empty", "", ""},
		{"only-blank", "\n\n\n", ""},
	}
	for _, c := range cases {
		if got := ReleaseNotes(c.in); got != c.want {
			t.Errorf("%s: ReleaseNotes(%q) = %q, want %q", c.name, c.in, got, c.want)
		}
	}
}

// 超长正文必须按 rune 截断：渠道正文常含中文，按字节切会截出半个
// UTF-8 序列，前端渲染成替换字符。
func TestReleaseNotesTruncatesByRune(t *testing.T) {
	long := strings.Repeat("中", MaxReleaseNotesChars+50)
	got := ReleaseNotes(long)
	if len([]rune(got)) != MaxReleaseNotesChars+len([]rune("\n…")) {
		t.Fatalf("truncated length = %d runes, want %d",
			len([]rune(got)), MaxReleaseNotesChars+len([]rune("\n…")))
	}
	if !strings.HasSuffix(got, "\n…") {
		t.Errorf("truncated body must be marked, got suffix %q", got[len(got)-8:])
	}
	// 截断结果必须能被 utf8.ValidString 判定为合法（无孤立字节）
	if !utf8.ValidString(got) {
		t.Errorf("truncated body is not valid UTF-8")
	}
}

// HTML 标签刻意保留：正文由前端以纯文本渲染（pre-wrap），原样显示
// 而不被执行；清洗会让 GitHub 上常见的 <img> 说明彻底消失。
func TestReleaseNotesKeepsMarkup(t *testing.T) {
	in := "<h3>更新</h3>\n<img src=\"x\">"
	if got := ReleaseNotes(in); got != in {
		t.Fatalf("ReleaseNotes(%q) = %q, want unchanged", in, got)
	}
}
