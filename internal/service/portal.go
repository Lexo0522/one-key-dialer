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
}

// portalProbeFallbackURL 配置的探测地址不是 http 时使用的兜底探测地址。
// 门户只能拦截明文 HTTP,https 探测地址看不到重定向;选国内可达的
// generate_204 家族地址,保证"在线=204,被拦=非204"的判定语义。
const portalProbeFallbackURL = "http://connect.rom.miui.com/generate_204"

// DetectPortal 请求探测 URL（禁止跟随重定向），判定当前网络是否被
// 校园网认证门户拦截：
//   - 30x 且带 Location → 拦截，Location 即门户地址；
//   - 探测地址属于 generate_204 家族但返回 200 → 拦截页（响应体替换了 204）；
//   - 204 / 其他 2xx → 未拦截。
//
// 复用探测配置的代理出口；Transport 由 proxy 包缓存，判定逻辑与常规探测一致。
func DetectPortal(cfg model.ProbeConfig) PortalDetect {
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
		return PortalDetect{Detail: "bad probe url"}
	}
	req.Header.Set("User-Agent", model.UserAgent())
	resp, err := client.Do(req)
	if err != nil {
		return PortalDetect{Detail: err.Error()}
	}
	defer resp.Body.Close()
	// 读完少量字节让连接归还连接池；拦截页通常很小
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 8<<10))

	detect := PortalDetect{Status: resp.StatusCode}
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
