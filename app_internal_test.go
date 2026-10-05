package main

import (
	"testing"

	"github.com/Lexo0522/one-key-dialer/internal/i18n"
)

// TestPrecheckFailure 预检文案判定：直连在线（家庭宽带免拨号）场景不得
// 再报凭据缺失；凭据齐全或已在本应用拨号在线时维持原行为。
func TestPrecheckFailure(t *testing.T) {
	yes := func() bool { return true }
	no := func() bool { return false }

	tests := []struct {
		name         string
		online       bool
		username     string
		password     []byte
		directOnline func() bool
		want         string
	}{
		{"本应用已拨号在线", true, "", nil, nil, i18n.T("precheck.alreadyOnline")},
		{"凭据齐全直接通过", false, "20230001", []byte("pass"), no, ""},
		{"凭据齐全不受直连探测影响", false, "20230001", []byte("pass"), yes, ""},
		{"账号密码缺失且网口直连联网", false, "", nil, yes, i18n.T("precheck.systemOnline")},
		{"仅密码缺失且网口直连联网", false, "20230001", nil, yes, i18n.T("precheck.systemOnline")},
		{"账号缺失且不联网报账号为空", false, "   ", nil, no, i18n.T("precheck.emptyUsername")},
		{"密码缺失且不联网报密码为空", false, "20230001", []byte("  "), no, i18n.T("precheck.emptyPassword")},
		{"探测闭包为空时保守报账号为空", false, "", nil, nil, i18n.T("precheck.emptyUsername")},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := precheckFailure(tt.online, tt.username, tt.password, tt.directOnline)
			if got != tt.want {
				t.Fatalf("precheckFailure() = %q, want %q", got, tt.want)
			}
		})
	}
}

// TestIsPrecheckOnlineNote 在线类提示走 info 通道，其余视为失败。
func TestIsPrecheckOnlineNote(t *testing.T) {
	for _, msg := range []string{
		i18n.T("precheck.alreadyOnline"),
		i18n.T("precheck.systemOnline"),
	} {
		if !isPrecheckOnlineNote(msg) {
			t.Errorf("isPrecheckOnlineNote(%q) = false, want true", msg)
		}
	}
	for _, msg := range []string{
		i18n.T("precheck.emptyUsername"),
		i18n.T("precheck.emptyPassword"),
		"",
	} {
		if isPrecheckOnlineNote(msg) {
			t.Errorf("isPrecheckOnlineNote(%q) = true, want false", msg)
		}
	}
}
