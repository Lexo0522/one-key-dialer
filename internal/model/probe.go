package model

import (
	"fmt"
	"time"
)

// 探测模式与内置默认值。
// 探测设置曾开放 UI 配置（probeMode/probeHost 等五个 settings 字段），
// UI 移除后字段已废弃删除;本项目不做兼容迁移,配置固定用以下默认值,
// 仅代理出口仍跟随设置。
const (
	ProbeModeICMP = "icmp"
	ProbeModeHTTP = "http"
	ProbeModeAuto = "auto"

	DefaultProbeHost    = "223.5.5.5"
	DefaultProbeHTTPURL = "http://wifi.vivo.com.cn/generate_204"
	// DefaultProbeHTTPURL 用国内厂商的 generate_204 地址，不用 Google 的
	// connectivitycheck.gstatic.com：本程序面向国内校园网/宽带环境，
	// gstatic 在多数校园网里解析或连通都不稳，会把正常拨号判成外网不通。
	// vivo/小米/OPPO 三家的连通性检测地址同为"在线=204、被拦=非204"语义，
	// 门户探测与联网判定都依赖这一语义（见 service.DetectPortal）。
	DefaultProbeAttempts = 3
	DefaultProbeDelayMs  = 1000
	DefaultHTTPTimeoutMs = 2500
)

// ProbeConfig 探测配置（对应旧版 ConnectivityConfirm.Config）。
type ProbeConfig struct {
	Mode          string
	Host          string
	HTTPUrl       string
	Attempts      int
	DelayMs       int
	HTTPTimeoutMs int
	Proxy         ProxyConfig
}

// ProbeConfigFromSettings 由设置快照构造探测配置。
// 探测目标等项固定为内置默认值,仅代理出口跟随设置。
func ProbeConfigFromSettings(s Settings) ProbeConfig {
	return ProbeConfig{
		Mode:          ProbeModeAuto,
		Host:          DefaultProbeHost,
		HTTPUrl:       DefaultProbeHTTPURL,
		Attempts:      DefaultProbeAttempts,
		DelayMs:       DefaultProbeDelayMs,
		HTTPTimeoutMs: DefaultHTTPTimeoutMs,
		Proxy:         s.ProxyConfig(),
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

// ProbeSummary 返回 "mode=%s host=%s http=%s attempts=%d delayMs=%d" 摘要；
// 启用代理时追加 "proxy=<type host:port>"，便于确认探测实际走的口子。
func (c ProbeConfig) Summary() string {
	base := fmt.Sprintf("mode=%s host=%s http=%s attempts=%d delayMs=%d",
		c.Mode, c.Host, c.HTTPUrl, c.Attempts, c.DelayMs)
	if c.Proxy.Enabled && c.Proxy.Host != "" {
		base += " proxy=" + c.Proxy.Summary()
	}
	return base
}
