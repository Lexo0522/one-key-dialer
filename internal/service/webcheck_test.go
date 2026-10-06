package service

import "testing"

// TestSplitOrg 解析 ipinfo 的 org 字段："AS4134 Chinanet" → as + isp。
func TestSplitOrg(t *testing.T) {
	tests := []struct {
		name    string
		in      string
		wantAs  string
		wantIsp string
	}{
		{"标准格式", "AS4134 Chinanet", "AS4134", "Chinanet"},
		{"无 AS 前缀整体算运营商", "Chinanet", "", "Chinanet"},
		{"AS 后非数字不算 as", "ASxx Chinanet", "", "ASxx Chinanet"},
		{"AS 号后无运营商名", "AS4134", "AS4134", ""},
		{"空串", "", "", ""},
		{"仅空白", "   ", "", ""},
		{"首尾空白被裁剪", "  AS4134   Chinanet Guangdong  ", "AS4134", "Chinanet Guangdong"},
		{"多个空格分隔取前两段", "AS4134  Chinanet", "AS4134", "Chinanet"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			as, isp := splitOrg(tt.in)
			if as != tt.wantAs {
				t.Errorf("splitOrg(%q) as = %q, want %q", tt.in, as, tt.wantAs)
			}
			if isp != tt.wantIsp {
				t.Errorf("splitOrg(%q) isp = %q, want %q", tt.in, isp, tt.wantIsp)
			}
		})
	}
}
