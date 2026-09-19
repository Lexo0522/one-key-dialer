package service

import (
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/Lexo0522/one-key-dialer/internal/i18n"
	"github.com/Lexo0522/one-key-dialer/internal/util"
)

// counterRow 匹配 netstat -e 的首个“标签 + 两个计数器”数据行。
var counterRow = regexp.MustCompile(`^(.+?)\s+(\d+)\s+(\d+)$`)

// TrafficSampler 通过 netstat -e 采样主机流量计数器。
type TrafficSampler struct {
	onWarn func(string)
	warned bool
}

// NewTrafficSampler 构造采样器。
func NewTrafficSampler(onWarn func(string)) *TrafficSampler {
	return &TrafficSampler{onWarn: onWarn}
}

// Sample 返回 [receivedBytes, sentBytes]；失败返回 {0,0}。
func (t *TrafficSampler) Sample() (int64, int64) {
	res, err := util.RunProcess([]string{"cmd", "/c", "netstat -e"}, 5*time.Second, nil)
	if err != nil {
		t.warnOnce(i18n.Tf("traffic.readFailed", "exec"))
		return 0, 0
	}
	if recv, sent, ok := parseNetstat(res.Output); ok {
		t.warned = false
		return recv, sent
	}
	t.warnOnce(i18n.T("traffic.parseFailed"))
	return 0, 0
}

func (t *TrafficSampler) warnOnce(message string) {
	if t.onWarn == nil || t.warned {
		return
	}
	t.warned = true
	t.onWarn(message)
}

// parseNetstat 解析 netstat -e 的第一个计数器行。
func parseNetstat(output string) (int64, int64, bool) {
	for _, line := range strings.Split(output, "\n") {
		m := counterRow.FindStringSubmatch(strings.TrimSpace(line))
		if m == nil {
			continue
		}
		r, err1 := strconv.ParseInt(m[2], 10, 64)
		s, err2 := strconv.ParseInt(m[3], 10, 64)
		if err1 != nil || err2 != nil {
			continue
		}
		return r, s, true
	}
	return 0, 0, false
}
