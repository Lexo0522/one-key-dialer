package service

import (
	"fmt"
	"sort"
	"strings"

	"github.com/Lexo0522/one-key-dialer/internal/i18n"
	"github.com/Lexo0522/one-key-dialer/internal/model"
)

// StatsSummary 统计汇总结果。
type StatsSummary struct {
	TotalOps     int
	DialAttempts int
	DialSuccess  int
	DialFail     int
	Disconnects  int
	TopErrors    []ErrorCount
	ReportText   string
}

// ErrorCount 一种失败结果的出现次数。
type ErrorCount struct {
	Result string
	Count  int
}

// Summarize 汇总历史记录。
func Summarize(records []model.HistoryRecord) StatsSummary {
	total, dialAttempts, dialSuccess, dialFail, disconnects := 0, 0, 0, 0, 0
	fails := map[string]int{}

	for _, row := range records {
		total++
		op := row.Operation
		result := row.Result
		switch {
		case strings.Contains(op, "拨号"):
			dialAttempts++
			if isSuccessResult(result) {
				dialSuccess++
			} else {
				dialFail++
				key := result
				if strings.TrimSpace(key) == "" {
					key = i18n.T("store.unknownFailure")
				}
				fails[key]++
			}
		case strings.Contains(op, "断开"):
			disconnects++
		}
	}

	var sb strings.Builder
	sb.WriteString("===== 拨号统计 =====\n")
	sb.WriteString("历史条目: " + fmt.Sprint(total) + "\n")
	sb.WriteString("拨号次数: " + fmt.Sprint(dialAttempts) + "\n")
	sb.WriteString("拨号成功: " + fmt.Sprint(dialSuccess) + "\n")
	sb.WriteString("拨号失败: " + fmt.Sprint(dialFail) + "\n")
	if dialAttempts > 0 {
		sb.WriteString(fmt.Sprintf("成功率: %.1f%%\n", 100.0*float64(dialSuccess)/float64(dialAttempts)))
	} else {
		sb.WriteString("成功率: --\n")
	}
	sb.WriteString("断开次数: " + fmt.Sprint(disconnects) + "\n")
	if len(fails) > 0 {
		sb.WriteString("常见结果:\n")
		counts := make([]ErrorCount, 0, len(fails))
		for k, v := range fails {
			counts = append(counts, ErrorCount{Result: k, Count: v})
		}
		sort.Slice(counts, func(i, j int) bool { return counts[i].Count > counts[j].Count })
		if len(counts) > 5 {
			counts = counts[:5]
		}
		for _, c := range counts {
			sb.WriteString("  · " + c.Result + " ×" + fmt.Sprint(c.Count) + "\n")
		}
	}
	return StatsSummary{
		TotalOps:     total,
		DialAttempts: dialAttempts,
		DialSuccess:  dialSuccess,
		DialFail:     dialFail,
		Disconnects:  disconnects,
		TopErrors:    countsOrNil(fails),
		ReportText:   sb.String(),
	}
}

func isSuccessResult(result string) bool {
	r := strings.TrimSpace(result)
	if r == "" {
		return false
	}
	if strings.Contains(r, i18n.T("outcome.fail")) {
		return false
	}
	if strings.Contains(r, i18n.T("outcome.rasNoInternet")) {
		return false
	}
	return strings.HasPrefix(r, i18n.T("outcome.success"))
}

func countsOrNil(fails map[string]int) []ErrorCount {
	if len(fails) == 0 {
		return nil
	}
	out := make([]ErrorCount, 0, len(fails))
	for k, v := range fails {
		out = append(out, ErrorCount{Result: k, Count: v})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Count > out[j].Count })
	return out
}
