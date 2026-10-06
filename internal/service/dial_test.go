package service

import (
	"sync/atomic"
	"testing"
	"time"

	"github.com/Lexo0522/one-key-dialer/internal/i18n"
	"github.com/Lexo0522/one-key-dialer/internal/model"
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

// ---------- DialAuto 生命周期回归测试 ----------

// fakeDialPort 记录 Connect 调用次数的拨号桩：固定返回 691（失败），
// 走 handleDialResult 的失败分支，不触发任何真实网络探测。
type fakeDialPort struct{ calls atomic.Int64 }

func (p *fakeDialPort) ConnectionName() string { return "fake-pppoe" }

func (p *fakeDialPort) Connect(_ *model.DialCredentials) (int, string) {
	p.calls.Add(1)
	return 691, ""
}

func (p *fakeDialPort) Disconnect() (int, error) { return 0, nil }

// stubDialView 空实现的 DialView：预检恒通过，每次返回全新的凭据。
type stubDialView struct{}

func (v *stubDialView) Log(Level, string) {}
func (v *stubDialView) Notify(string, string, string) {}
func (v *stubDialView) OnDialPhase(string) {}
func (v *stubDialView) OnConnectionState(bool) {}
func (v *stubDialView) OnDialFinished(bool, int, string) {}
func (v *stubDialView) ValidateInput(bool) bool { return true }
func (v *stubDialView) CaptureCredentials() *model.DialCredentials {
	return model.NewDialCredentials("user", []byte("pass"))
}

// stubDialEnv 空实现的 DialEnvironment：恒离线，不重连。
type stubDialEnv struct{}

func (e *stubDialEnv) IsOnline() bool { return false }
func (e *stubDialEnv) ConnectTimeMillis() int64 { return 0 }
func (e *stubDialEnv) SessionTrafficBytes() int64 { return 0 }
func (e *stubDialEnv) ProbeConfig() model.ProbeConfig { return model.ProbeConfig{} }
func (e *stubDialEnv) DisconnectOnNoInternet() bool { return false }
func (e *stubDialEnv) PersistAfterSuccess() {}

// waitForJob 往串行队列尾部追加一个哨兵：哨兵执行时，前面的 DialAuto
// 任务（含全部 defer）必已完成。
func waitForJob(t *testing.T, o *DialOrchestrator) {
	t.Helper()
	done := make(chan struct{})
	o.enqueue(func() { close(done) })
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("拨号队列任务未在 5 秒内执行")
	}
}

func newDialAutoFixture() (*DialOrchestrator, *fakeDialPort, *DialLifecycle) {
	port := &fakeDialPort{}
	lc := &DialLifecycle{}
	o := NewDialOrchestrator(port, &stubDialView{}, &stubDialEnv{}, lc)
	return o, port, lc
}

// TestDialAutoReleasesLifecycle DialAuto 执行后 lifecycle 必须复位。
// 回归：历史上 DialAuto 漏掉了 End()/creds.Clear()，一次自动拨号后
// IsBusy() 永久为 true，后续拨号/重连/诊断试拨全部被静默拒绝。
func TestDialAutoReleasesLifecycle(t *testing.T) {
	o, port, lc := newDialAutoFixture()
	defer o.Shutdown(time.Second)

	o.DialAuto()
	waitForJob(t, o)

	if got := port.calls.Load(); got != 1 {
		t.Fatalf("Connect 调用次数 = %d, want 1", got)
	}
	if lc.IsBusy() {
		t.Fatal("DialAuto 结束后 lifecycle 仍为 busy：拨号互斥泄漏")
	}
}

// TestDialAutoTwiceSecondAccepted 连续两次 DialAuto，第二次必须被受理。
// 若生命周期泄漏，第二次会被 TryBeginDial 静默拒绝，Connect 只调用一次。
func TestDialAutoTwiceSecondAccepted(t *testing.T) {
	o, port, lc := newDialAutoFixture()
	defer o.Shutdown(time.Second)

	o.DialAuto()
	o.DialAuto()
	waitForJob(t, o)

	if got := port.calls.Load(); got != 2 {
		t.Fatalf("连续两次 DialAuto 后 Connect 调用次数 = %d, want 2（第二次可能被静默拒绝）", got)
	}
	if lc.IsBusy() {
		t.Fatal("两次 DialAuto 结束后 lifecycle 仍为 busy：拨号互斥泄漏")
	}
}
