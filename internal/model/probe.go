package model

import (
	"fmt"
	"strings"
	"time"
)

// ProbeConfig 探测配置（对应旧版 ConnectivityConfirm.Config）。
type ProbeConfig struct {
	Mode          string
	Host          string
	HTTPUrl       string
	Attempts      int
	DelayMs       int
	HTTPTimeoutMs int
}

// ProbeConfigFromSettings 由设置快照构造探测配置。
func ProbeConfigFromSettings(mode, host, httpURL string, attempts, delayMs int) ProbeConfig {
	return ProbeConfig{
		Mode:          NormalizeProbeMode(mode),
		Host:          orDefault(host, DefaultProbeHost),
		HTTPUrl:       orDefault(httpURL, DefaultProbeHTTPURL),
		Attempts:      maxInt(1, attempts),
		DelayMs:       maxInt(0, delayMs),
		HTTPTimeoutMs: DefaultHTTPTimeoutMs,
	}
}

// OneShot 返回单次、零延迟的副本（周期性监控用）。
func (c ProbeConfig) OneShot() ProbeConfig {
	c.Attempts = 1
	c.DelayMs = 0
	return c
}

// ProbeOutcome 一次探测的结果。
type ProbeOutcome struct {
	OK         bool
	DurationMs int64
	Mode       string
	Host       string
	HTTPUrl    string
	Attempts   int
	Source     string
	AtEpochMs  int64
}

// NewProbeOutcome 构造探测结果。
func NewProbeOutcome(ok bool, durationMs int64, cfg ProbeConfig, source string) ProbeOutcome {
	return ProbeOutcome{
		OK:         ok,
		DurationMs: durationMs,
		Mode:       cfg.Mode,
		Host:       cfg.Host,
		HTTPUrl:    cfg.HTTPUrl,
		Attempts:   cfg.Attempts,
		Source:     source,
		AtEpochMs:  time.Now().UnixMilli(),
	}
}

// ShortLine 返回 "连通 mode=auto 12ms src=post-dial" 形式的短行。
func (o ProbeOutcome) ShortLine() string {
	word := "不通"
	if o.OK {
		word = "连通"
	}
	return fmt.Sprintf("%s mode=%s %dms src=%s", word, o.Mode, o.DurationMs, o.Source)
}

// DetailLine 返回用于诊断页的详细行。
func (o ProbeOutcome) DetailLine() string {
	return fmt.Sprintf("%s | %s | mode=%s host=%s http=%s attempts=%d",
		time.UnixMilli(o.AtEpochMs).Format("2006-01-02 15:04:05"),
		o.ShortLine(), o.Mode, o.Host, o.HTTPUrl, o.Attempts)
}

// ProbeSummary 返回 "mode=%s host=%s http=%s attempts=%d delayMs=%d" 摘要。
func (c ProbeConfig) Summary() string {
	return fmt.Sprintf("mode=%s host=%s http=%s attempts=%d delayMs=%d",
		c.Mode, c.Host, c.HTTPUrl, c.Attempts, c.DelayMs)
}

func orDefault(v, def string) string {
	if strings.TrimSpace(v) == "" {
		return def
	}
	return strings.TrimSpace(v)
}

func maxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}
