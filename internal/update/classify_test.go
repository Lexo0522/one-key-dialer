package update

import (
	"errors"
	"net"
	"net/url"
	"testing"
)

// ---------- classifyError ----------

// TestClassifyError 错误分类：日志"原因="与熔断决策都靠它，分错类会让
// 「版本不存在」被当成网络故障反复重试。
func TestClassifyError(t *testing.T) {
	tests := []struct {
		name string
		err  error
		want FailureKind
	}{
		{"nil 归为未知", nil, KindUnknown},
		{"用户取消", ErrCancelled, KindCancelled},
		{"哈希不符", &HashMismatchError{}, KindHashMismatch},
		{"下载停滞", &StallTimeoutError{}, KindStall},
		{"404 是版本不存在", &HTTPStatusError{Code: 404}, KindVersionMissing},
		{"500 是 HTTP 错误", &HTTPStatusError{Code: 500}, KindHTTPStatus},
		{"普通错误归为未知", errors.New("boom"), KindUnknown},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := classifyError(tt.err); got != tt.want {
				t.Errorf("classifyError(%v) = %v, want %v", tt.err, got, tt.want)
			}
		})
	}
}

// TestClassifyErrorWrapped 包装过的错误必须仍能识别：调用链上普遍套了
// fmt.Errorf("%w")，errors.As 失效会让所有失败都退化成「未知」。
func TestClassifyErrorWrapped(t *testing.T) {
	wrapped := errors.Join(errors.New("ctx"), &HTTPStatusError{Code: 404})
	if got := classifyError(wrapped); got != KindVersionMissing {
		t.Errorf("classifyError(wrapped 404) = %v, want KindVersionMissing", got)
	}
}

// timeoutErr 实现 net.Error 的 Timeout()，用于构造真实的超时错误。
type timeoutErr struct{}

func (timeoutErr) Error() string { return "i/o timeout" }
func (timeoutErr) Timeout() bool { return true }

// TestClassifyErrorOpTimeout net.OpError 带 Timeout() 时算连接超时，
// 与普通网络错误区分开——熔断对前者更敏感（通常是链路不通）。
func TestClassifyErrorOpTimeout(t *testing.T) {
	op := &net.OpError{Op: "dial", Net: "tcp", Err: timeoutErr{}}
	if !op.Timeout() {
		t.Fatal("前置条件失败：构造的 OpError 应判定为超时")
	}
	if got := classifyError(op); got != KindConnectTimeout {
		t.Errorf("classifyError(OpError timeout) = %v, want KindConnectTimeout", got)
	}
}

// TestClassifyErrorOpNonTimeout 非超时的 OpError 归为网络错误。
func TestClassifyErrorOpNonTimeout(t *testing.T) {
	op := &net.OpError{Op: "dial", Net: "tcp", Err: errors.New("connection refused")}
	if got := classifyError(op); got != KindNetwork {
		t.Errorf("classifyError(OpError non-timeout) = %v, want KindNetwork", got)
	}
}

// TestClassifyErrorURLError url.Error 区分超时与普通网络错误。
func TestClassifyErrorURLError(t *testing.T) {
	if got := classifyError(&url.Error{Op: "Get", Err: errors.New("connection refused")}); got != KindNetwork {
		t.Errorf("classifyError(url.Error) = %v, want KindNetwork", got)
	}
}

// ---------- 断路器 ----------

// newBreakerModule 构造一个只带断路器所需字段的 Module，时钟可注入，
// 这样测试不需要真的 sleep 等冷却。返回的 advance 用于手动推进时钟。
func newBreakerModule(nowMs int64) (m *Module, advance func(ms int64)) {
	cur := nowMs
	m = &Module{breaker: map[string]*breakerState{}}
	m.now = func() int64 { return cur }
	return m, func(ms int64) { cur += ms }
}

func testSource() *Source {
	return &Source{
		ID:                "gitee",
		BreakerThreshold:  2,
		BreakerCooldownMs: 60000,
	}
}

// TestBreakerOpensAfterThreshold 连续失败达到阈值后熔断，冷却期内拒绝请求。
func TestBreakerOpensAfterThreshold(t *testing.T) {
	m, _ := newBreakerModule(1_000_000)
	src := testSource()

	if m.breakerOpen(src) {
		t.Fatal("初始状态不应熔断")
	}
	if m.recordFailure(src) {
		t.Error("第一次失败还未到阈值，不应熔断")
	}
	if m.breakerOpen(src) {
		t.Error("一次失败后不应熔断")
	}
	if !m.recordFailure(src) {
		t.Error("第二次失败达到阈值，应熔断")
	}
	if !m.breakerOpen(src) {
		t.Error("熔断后 breakerOpen 应为 true")
	}
}

// TestBreakerCooldownExpiry 冷却结束后重新放行。
func TestBreakerCooldownExpiry(t *testing.T) {
	m, advance := newBreakerModule(0)
	src := testSource()

	m.recordFailure(src)
	m.recordFailure(src)
	if !m.breakerOpen(src) {
		t.Fatal("达到阈值后应熔断")
	}
	advance(59_999)
	if !m.breakerOpen(src) {
		t.Error("冷却未结束，仍应熔断")
	}
	advance(2)
	if m.breakerOpen(src) {
		t.Error("冷却已结束，不应继续熔断")
	}
}

// TestBreakerHalfOpenFailureResetsCooldown 半开探测失败要重新进入完整冷却，
// 否则一条一直坏的线路会被密集重试。
func TestBreakerHalfOpenFailureResetsCooldown(t *testing.T) {
	m, advance := newBreakerModule(0)
	src := testSource()

	m.recordFailure(src)
	m.recordFailure(src)
	advance(60_000) // 冷却结束，进入半开

	// 半开期第一次失败：应重置为完整冷却，而不是只累加一次计数。
	if !m.recordFailure(src) {
		t.Error("半开探测失败应重新熔断")
	}
	if !m.breakerOpen(src) {
		t.Error("半开失败后应立即重新熔断")
	}
	// 冷却按完整 cooldown 重新计时：此刻往前 59s 仍在冷却内，说明重置的
	// 是「从现在起算满一轮」，而不是接着原终点往下走。
	advance(59_000)
	if !m.breakerOpen(src) {
		t.Error("半开失败后的冷却应按完整 cooldown 重新计时")
	}
	advance(1_500)
	if m.breakerOpen(src) {
		t.Error("完整冷却结束后应放行")
	}
}

// TestBreakerSuccessClears 成功后熔断状态清除，线路立即可用。
func TestBreakerSuccessClears(t *testing.T) {
	m, _ := newBreakerModule(0)
	src := testSource()

	m.recordFailure(src)
	m.recordFailure(src)
	if !m.breakerOpen(src) {
		t.Fatal("前置条件：应已熔断")
	}
	m.recordSuccess(src)
	if m.breakerOpen(src) {
		t.Error("成功后应清除熔断状态")
	}
	if m.breakerRemainingMs(src) != 0 {
		t.Errorf("清除后剩余冷却应为 0，得到 %d", m.breakerRemainingMs(src))
	}
}

// TestBreakerRemainingMs 剩余冷却时间非负且单调收缩。
func TestBreakerRemainingMs(t *testing.T) {
	m, advance := newBreakerModule(0)
	src := testSource()

	if got := m.breakerRemainingMs(src); got != 0 {
		t.Errorf("未熔断时剩余冷却应为 0，得到 %d", got)
	}
	m.recordFailure(src)
	m.recordFailure(src)
	advance(10_000)
	if got := m.breakerRemainingMs(src); got != 50_000 {
		t.Errorf("剩余冷却 = %d, want 50000", got)
	}
	advance(100_000)
	if got := m.breakerRemainingMs(src); got != 0 {
		t.Errorf("冷却结束后剩余应为 0，得到 %d", got)
	}
}
