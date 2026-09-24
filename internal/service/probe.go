package service

import (
	"context"
	"net/http"
	"strings"
	"time"

	"github.com/Lexo0522/one-key-dialer/internal/model"
	"github.com/Lexo0522/one-key-dialer/internal/proxy"
	"github.com/Lexo0522/one-key-dialer/internal/util"
)

// ConnectivityConfirm 拨号后的外网可达性确认。
// icmp: 只 ping 主机；http: 只请求 URL；auto: 每次尝试先 ICMP，失败再 HTTP。
func ConnectivityConfirm(cfg model.ProbeConfig) model.ProbeOutcome {
	return ConfirmDetailed(cfg, "probe")
}

// ConfirmDetailed 执行探测并返回带耗时的结果。
func ConfirmDetailed(cfg model.ProbeConfig, source string) model.ProbeOutcome {
	start := time.Now()
	ok := confirm(cfg)
	ms := time.Since(start).Milliseconds()
	return model.NewProbeOutcome(ok, ms, cfg, source)
}

// QuickCheck 单次、零延迟的快速检查（周期性监控用）。
func QuickCheck(cfg model.ProbeConfig) bool {
	return confirm(cfg.OneShot())
}

func confirm(cfg model.ProbeConfig) bool {
	// HTTP 出口按设置内的代理配置构造（未启用时回退系统环境变量），
	// Transport 经 proxy 包缓存，同一配置重复探测复用连接池。
	client := proxy.ClientFor(cfg.Proxy)
	for i := 0; i < cfg.Attempts; i++ {
		var ok bool
		switch cfg.Mode {
		case model.ProbeModeICMP:
			ok = icmpReachable(cfg.Host)
		case model.ProbeModeHTTP:
			ok = httpReachable(client, cfg.HTTPUrl, cfg.HTTPTimeoutMs)
		default:
			ok = icmpReachable(cfg.Host) || httpReachable(client, cfg.HTTPUrl, cfg.HTTPTimeoutMs)
		}
		if ok {
			return true
		}
		if i+1 < cfg.Attempts && cfg.DelayMs > 0 {
			time.Sleep(time.Duration(cfg.DelayMs) * time.Millisecond)
		}
	}
	return false
}

// icmpReachable 通过 ping 子进程判断主机可达（与旧版回退路径一致）。
func icmpReachable(host string) bool {
	if host == "" {
		return false
	}
	res, err := util.RunProcess([]string{"ping", "-n", "1", "-w", "1000", host}, 5*time.Second, nil)
	if err != nil {
		return false
	}
	return !res.TimedOut && res.ExitCode == 0
}

// httpReachable 轻量 HTTP(S) 检查：优先 HEAD，被拒则 GET；2xx/3xx 视为可达。
// client 由探测配置中的代理设置构造，请求按绕过列表决定直连或经代理。
func httpReachable(client *http.Client, target string, timeoutMs int) bool {
	if target == "" {
		return false
	}
	timeout := timeoutMs
	if timeout < 500 {
		timeout = 500
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeout)*time.Millisecond)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodHead, strings.TrimSpace(target), nil)
	if err != nil {
		return false
	}
	req.Header.Set("User-Agent", model.UserAgent())
	resp, err := client.Do(req)
	if err == nil {
		code := resp.StatusCode
		resp.Body.Close()
		if code >= 200 && code < 400 {
			return true
		}
	} else {
		return false
	}
	// HEAD 被门户拒绝 → 退回 GET
	ctx2, cancel2 := context.WithTimeout(context.Background(), time.Duration(timeout)*time.Millisecond)
	defer cancel2()
	req2, err := http.NewRequestWithContext(ctx2, http.MethodGet, strings.TrimSpace(target), nil)
	if err != nil {
		return false
	}
	req2.Header.Set("User-Agent", model.UserAgent())
	resp2, err := client.Do(req2)
	if err != nil {
		return false
	}
	defer resp2.Body.Close()
	return resp2.StatusCode >= 200 && resp2.StatusCode < 400
}
