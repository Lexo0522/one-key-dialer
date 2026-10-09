package main

import (
	"testing"

	"github.com/Lexo0522/one-key-dialer/internal/i18n"
	"github.com/Lexo0522/one-key-dialer/internal/service"
)

// portalTestVerdict 的四种组合：只有"提交成功 + 复验无门户"才算通过。
// 复验探测失败（Error 非空）时门户是否放行未知，绝不能报通过——
// 探测超时被当成"门户已放行"是早年误报测试通过的那个 bug。
func TestPortalTestVerdict(t *testing.T) {
	okOut := service.PortalAuthOutcome{Success: true, Status: 200}
	badOut := service.PortalAuthOutcome{Success: false, Status: 200, Detail: "hint miss"}
	clear := service.PortalDetect{Status: 204}
	still := service.PortalDetect{Portal: true, PortalURL: "http://10.1.1.55/x"}
	probeErr := service.PortalDetect{Error: "i/o timeout", Detail: "i/o timeout"}

	tests := []struct {
		name    string
		out     service.PortalAuthOutcome
		re      service.PortalDetect
		wantOK  bool
		wantMsg string
	}{
		{"提交成功且门户消失", okOut, clear, true, i18n.T("portal.testOk")},
		{"提交失败", badOut, clear, false, i18n.T("portal.testFailed")},
		{"提交失败但门户已消失", badOut, still, false, i18n.T("portal.testFailed")},
		{"提交成功但门户仍在", okOut, still, false, i18n.T("portal.testFailed")},
		{"复验探测失败", okOut, probeErr, false, i18n.T("portal.testVerifyFailed")},
		{"提交失败且复验探测失败", badOut, probeErr, false, i18n.T("portal.testFailed")},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			ok, msg := portalTestVerdict(tt.out, tt.re)
			if ok != tt.wantOK || msg != tt.wantMsg {
				t.Fatalf("portalTestVerdict() = (%v, %q), want (%v, %q)", ok, msg, tt.wantOK, tt.wantMsg)
			}
		})
	}
}
