package service

import "testing"

// TestClampIntervalSeconds 重连间隔下限钳制。
func TestClampIntervalSeconds(t *testing.T) {
	tests := []struct {
		in, want int
	}{
		{0, 5},
		{1, 5},
		{4, 5},
		{5, 5},
		{6, 6},
		{30, 30},
		{3600, 3600},
		{-7, 5}, // 负数同样钳到下限，不能让退避算出负 delay
	}
	for _, tt := range tests {
		if got := ClampIntervalSeconds(tt.in); got != tt.want {
			t.Errorf("ClampIntervalSeconds(%d) = %d, want %d", tt.in, got, tt.want)
		}
	}
}

// TestShouldAttemptReconnectDial 只有「不在线且不忙」才允许发起重连。
func TestShouldAttemptReconnectDial(t *testing.T) {
	tests := []struct {
		online, busy, want bool
	}{
		{false, false, true}, // 断网空闲 → 该拨
		{false, true, false}, // 已有拨号在跑 → 不重复发起
		{true, false, false}, // 在线 → 不需要
		{true, true, false},  // 在线且忙 → 不需要
	}
	for _, tt := range tests {
		if got := ShouldAttemptReconnectDial(tt.online, tt.busy); got != tt.want {
			t.Errorf("ShouldAttemptReconnectDial(%v, %v) = %v, want %v",
				tt.online, tt.busy, got, tt.want)
		}
	}
}

// TestRetryDelaySeconds 退避：首次 5 秒、连续失败翻倍、上限为基准 10 倍。
// 上限是关键：否则错误密码会让客户端以固定间隔高频冲击认证服务器。
func TestRetryDelaySeconds(t *testing.T) {
	tests := []struct {
		name   string
		streak int
		base   int
		want   int64
	}{
		{"首次 5 秒", 0, 30, 5},
		{"第二次 10 秒", 1, 30, 10},
		{"第三次 20 秒", 2, 30, 20},
		{"第四次 40 秒", 3, 30, 40},
		{"封顶为基准 10 倍", 10, 30, 300},
		{"远超上限仍封顶", 50, 30, 300},
		{"负 streak 当首次", -3, 30, 5},
		{"基准为 0 时按 1 算下限 5", 0, 0, 5},
		{"小基准 5 时上限 50", 10, 5, 50},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := RetryDelaySeconds(tt.streak, tt.base); got != tt.want {
				t.Errorf("RetryDelaySeconds(%d, %d) = %d, want %d",
					tt.streak, tt.base, got, tt.want)
			}
		})
	}
}
