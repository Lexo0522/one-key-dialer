package service

import (
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/Lexo0522/one-key-dialer/internal/model"
	"github.com/Lexo0522/one-key-dialer/internal/proxy"
)

// PortalDetect 一次认证门户检测结果。
type PortalDetect struct {
	// Portal 是否被门户拦截（需要认证）。
	Portal bool `json:"portal"`
	// PortalURL 捕获到的门户地址（302 的 Location，已补全为绝对地址）。
	PortalURL string `json:"portalUrl"`
	// Status 探测响应状态码；0 表示请求本身失败。
	Status int `json:"status"`
	// Detail 人类可读的判定依据。
	Detail string `json:"detail"`
	// Error 探测请求本身失败（超时/DNS/连接被拒）时为错误原文。
	// 此时 Portal==false 表示"探测未完成"，而非"网络无需认证"：
	// 调用方必须据此与真正的无门户结果区分，否则超时会被误报成网络正常。
	Error string `json:"error,omitempty"`
	// ProbeURL 实际探测用的地址（配置地址非 http 时换兜底地址）。
	ProbeURL string `json:"probeUrl,omitempty"`
}

// portalProbeFallbackURL 配置的探测地址不是 http 时使用的兜底探测地址。
// 门户只能拦截明文 HTTP,https 探测地址看不到重定向;选国内可达的
// generate_204 家族地址,保证"在线=204,被拦=非204"的判定语义。
const portalProbeFallbackURL = "http://connect.rom.miui.com/generate_204"

// portalProbeRetries 首轮探测失败后的追加尝试地址。
// 首轮失败不代表网络不通：可能是配置的探测地址在本地网络解析/连通
// 异常（海外域名尤其如此），换一批国内可达的明文 generate_204 地址
// 复测，避免把"探测请求超时"误判成"网络无需认证"。
// 顺序即优先级：先国内厂商地址，再微软的连通性检测地址。
var portalProbeRetries = []string{
	"http://connect.rom.miui.com/generate_204",
	"http://conn1.oppomobile.com/generate_204",
	"http://wifi.vivo.com.cn/generate_204",
	"http://www.msftconnecttest.com/connecttest.txt",
	"http://edge-http.microsoft.com/captiveportal/generate_204",
}

// portalProbeRetryBudget 首轮失败后整段重试链的总预算。
// 手动测试同步执行且时限约 10 秒（首轮 + 复验），重试链只能分到 5 秒；
// 单次探测超时取 min(HTTPTimeoutMs, 剩余预算)，保证不会把管道拖超时。
const portalProbeRetryBudgetMs = 5000

// DetectPortal 请求探测 URL（禁止跟随重定向），判定当前网络是否被
// 校园网认证门户拦截：
//   - 30x 且带 Location → 拦截，Location 即门户地址；
//   - 探测地址属于 generate_204 家族但返回 200 → 拦截页（响应体替换了 204）；
//   - 204 / 其他 2xx → 未拦截；
//   - 请求本身失败 → 先用备选明文地址重试，全部失败时 Error 记录最后
//     一次错误、Portal=false，表示"探测未完成"而非"无门户"。
//
// 复用探测配置的代理出口；Transport 由 proxy 包缓存，判定逻辑与常规探测一致。
func DetectPortal(cfg model.ProbeConfig) PortalDetect {
	first := detectPortalOnce(cfg)
	if first.Error == "" {
		return first
	}
	// 首轮失败：在总预算内逐个尝试备选地址（跳过与已试相同的）。
	deadline := time.Now().Add(portalProbeRetryBudgetMs * time.Millisecond)
	tried := first.ProbeURL
	for _, next := range portalProbeRetries {
		if next == "" || next == tried {
			continue
		}
		alt := cfg
		alt.HTTPTimeoutMs = remainingMs(deadline, cfg.HTTPTimeoutMs)
		if alt.HTTPTimeoutMs <= 0 {
			break
		}
		alt.HTTPUrl = next
		d := detectPortalOnce(alt)
		first = mergeRetry(first, d)
		if d.Error == "" {
			return d
		}
		tried = d.ProbeURL
	}
	return first
}

// mergeRetry 合并首轮与重试结果：成功即返回重试结果，失败则保留
// 首轮的身份信息（探测地址/状态）只更新错误明细，便于回显"试过哪些地址"。
func mergeRetry(first, d PortalDetect) PortalDetect {
	if d.Error == "" {
		return d
	}
	first.Detail = d.Detail
	first.Error = d.Error
	return first
}

// remainingMs 返回从当前时刻到截止时间的剩余毫秒与调用方超时中的较小值。
func remainingMs(deadline time.Time, timeoutMs int) int {
	left := time.Until(deadline).Milliseconds()
	if timeoutMs > 0 && int64(timeoutMs) < left {
		left = int64(timeoutMs)
	}
	return int(left)
}

// detectPortalOnce 对单个地址执行一次门户探测（无重试）。
func detectPortalOnce(cfg model.ProbeConfig) PortalDetect {
	target := strings.TrimSpace(cfg.HTTPUrl)
	if target == "" {
		target = model.DefaultProbeHTTPURL
	}
	if !strings.HasPrefix(target, "http://") {
		// https 请求不会被门户重定向,换明文地址才能探测到拦截
		target = portalProbeFallbackURL
	}
	// 独立 client 包住缓存的 Transport:绝不能改共享 Client 的 CheckRedirect
	client := &http.Client{
		Transport: proxy.TransportFor(cfg.Proxy),
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	timeout := cfg.HTTPTimeoutMs
	if timeout < 800 {
		timeout = 1500
	}
	client.Timeout = time.Duration(timeout) * time.Millisecond

	req, err := http.NewRequest(http.MethodGet, target, nil)
	if err != nil {
		return PortalDetect{Detail: "bad probe url", Error: "bad probe url", ProbeURL: target}
	}
	req.Header.Set("User-Agent", model.UserAgent())
	resp, err := client.Do(req)
	if err != nil {
		return PortalDetect{Detail: err.Error(), Error: err.Error(), ProbeURL: target}
	}
	defer resp.Body.Close()
	// 读完少量字节让连接归还连接池；拦截页通常很小
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 8<<10))

	detect := PortalDetect{Status: resp.StatusCode, ProbeURL: target}
	if resp.StatusCode >= 300 && resp.StatusCode < 400 {
		loc := strings.TrimSpace(resp.Header.Get("Location"))
		if loc == "" {
			detect.Detail = "redirect without location"
			return detect
		}
		portalURL := resolveReference(target, loc)
		detect.Portal = true
		detect.PortalURL = portalURL
		detect.Detail = "redirect " + resp.Status
		return detect
	}
	if resp.StatusCode == http.StatusOK && isGenerate204(target) {
		// generate_204 在线时应返回 204;返回 200 说明响应被门户替换
		detect.Portal = true
		detect.PortalURL = target
		detect.Detail = "expected 204 got 200"
		return detect
	}
	detect.Detail = resp.Status
	return detect
}

// resolveReference 把可能为相对路径的 Location 补全为绝对地址。
func resolveReference(base, loc string) string {
	ref, err := url.Parse(loc)
	if err != nil {
		return loc
	}
	if ref.IsAbs() {
		return loc
	}
	baseURL, err := url.Parse(base)
	if err != nil {
		return loc
	}
	return baseURL.ResolveReference(ref).String()
}

// isGenerate204 判断探测地址是否属于 generate_204 家族（在线时返回 204）。
func isGenerate204(target string) bool {
	return strings.Contains(target, "generate_204")
}
