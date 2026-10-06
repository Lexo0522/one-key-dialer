package service

import (
	"testing"

	"github.com/Lexo0522/one-key-dialer/internal/i18n"
)

// TestDescribeFailure RAS 错误码到处理建议的映射。
// 断言绑 i18n key 的取值而不是字面量，文案改写时测试同步反映意图。
func TestDescribeFailure(t *testing.T) {
	tests := []struct {
		name string
		in   *DialResult
		want string
	}{
		{"nil 结果", nil, i18n.T("ras.null")},
		{"code 0 视为成功", &DialResult{Code: 0}, i18n.T("ras.0")},
		{"691 账号密码错", &DialResult{Code: 691}, i18n.T("ras.691")},
		{"619 对端断开", &DialResult{Code: 619}, i18n.T("ras.619")},
		{"678 服务器无响应", &DialResult{Code: 678}, i18n.T("ras.678")},
		{"651 无网络或码表缺失", &DialResult{Code: 651}, i18n.T("ras.651")},
		{"code 非 0 但 output 里有码", &DialResult{Code: -1, Output: "Error 691: 拒绝"}, i18n.T("ras.691")},
		{"code -1 且无 output 特征", &DialResult{Code: -1, Output: "unknown"}, i18n.T("ras.-1")},
		{"未知错误码走兜底", &DialResult{Code: 12345}, i18n.Tf("ras.other", 12345)},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := DescribeFailure(tt.in); got != tt.want {
				t.Errorf("DescribeFailure(%+v) = %q, want %q", tt.in, got, tt.want)
			}
		})
	}
}

// TestDescribeFailureNoFalseMatch 码表匹配不能靠子串里的任意数字撞中。
// 619 这类两位数在 "port 80" / "0x62" 附近容易误命中。
func TestDescribeFailureNoFalseMatch(t *testing.T) {
	in := &DialResult{Code: 700, Output: "dialed on port 80, mac 00:1a:2b"}
	got := DescribeFailure(in)
	want := i18n.Tf("ras.other", 700)
	if got != want {
		t.Errorf("DescribeFailure() = %q, want %q (子串误命中)", got, want)
	}
}
