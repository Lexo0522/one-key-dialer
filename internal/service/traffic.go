package service

import (
	"github.com/Lexo0522/one-key-dialer/internal/i18n"
	"github.com/Lexo0522/one-key-dialer/internal/platform"
)

// TrafficSampler 通过 iphlpapi(GetIfTable)原生采样主机流量计数器,
// 不再拉起 cmd /c netstat -e 子进程(省去每轮 cmd.exe+netstat.exe+conhost 抖动)。
type TrafficSampler struct {
	onWarn func(string)
	warned bool
}

// NewTrafficSampler 构造采样器。
func NewTrafficSampler(onWarn func(string)) *TrafficSampler {
	return &TrafficSampler{onWarn: onWarn}
}

// Sample 返回 [receivedBytes, sentBytes]；失败返回 {0,0}。
// 计数器为 32 位,回绕由监控层的增量重置逻辑兜底(与旧 netstat 路径一致)。
func (t *TrafficSampler) Sample() (int64, int64) {
	recv, sent, ok := platform.HostTrafficCounters()
	if !ok {
		t.warnOnce(i18n.Tf("traffic.readFailed", "iphlpapi"))
		return 0, 0
	}
	t.warned = false
	return recv, sent
}

func (t *TrafficSampler) warnOnce(message string) {
	if t.onWarn == nil || t.warned {
		return
	}
	t.warned = true
	t.onWarn(message)
}
