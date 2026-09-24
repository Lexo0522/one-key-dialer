// Package proxy 提供应用自身 HTTP 出口的代理支持。
//
// 作用范围严格限定为本程序的 HTTP 请求（在线更新检查/下载、HTTP 模式
// 外网探测）：按设置构造 http.Transport（http / https / socks5，SOCKS5
// 由 Go 标准库原生支持），并支持绕过列表（精确主机、*.example.com 后缀、
// 通配符、CIDR、<local> 私网/环回）。本包不写入、不读取任何系统代理
// 设置，未启用代理时回退系统环境变量（HTTP_PROXY/HTTPS_PROXY 等），
// 与改造前行为保持一致。
package proxy

import (
	"net"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"sync"

	"github.com/Lexo0522/one-key-dialer/internal/model"
)

// Func 返回 http.Transport.Proxy 函数。
//
// 规则：
//   - 设置内启用且地址非空 → 请求主机命中绕过列表时直连（返回 nil），
//     否则返回配置的代理 URL；
//   - 未启用（或地址为空）→ 回退 http.ProxyFromEnvironment。
func Func(cfg model.ProxyConfig) func(*http.Request) (*url.URL, error) {
	proxyURL := cfg.ProxyURL()
	if proxyURL == nil {
		return http.ProxyFromEnvironment
	}
	bypass := newBypass(cfg.Bypass)
	return func(req *http.Request) (*url.URL, error) {
		if bypass.match(req.URL.Hostname()) {
			return nil, nil
		}
		return proxyURL, nil
	}
}

// TransportFor 返回按代理配置缓存的 *http.Transport：相同配置复用同一
// Transport 以保留连接池，配置变更（换代理/改绕过）自然换 Key。
func TransportFor(cfg model.ProxyConfig) *http.Transport {
	key := cfgKey(cfg)
	cacheMu.Lock()
	defer cacheMu.Unlock()
	if t, ok := cache[key]; ok {
		return t
	}
	t := &http.Transport{Proxy: Func(cfg)}
	if len(cache) >= maxCacheEntries {
		cache = make(map[string]*http.Transport, maxCacheEntries)
	}
	cache[key] = t
	return t
}

// ClientFor 返回使用该代理配置的 HTTP 客户端（Transport 走缓存）。
// 调用方仍可自行设置 Timeout / CheckRedirect。
func ClientFor(cfg model.ProxyConfig) *http.Client {
	return &http.Client{Transport: TransportFor(cfg)}
}

const maxCacheEntries = 8

var (
	cacheMu sync.Mutex
	cache   = make(map[string]*http.Transport, maxCacheEntries)
)

func cfgKey(cfg model.ProxyConfig) string {
	return strings.Join([]string{
		boolKey(cfg.Enabled), cfg.Type, cfg.Host, cfg.Port,
		strings.Join(cfg.Bypass, ","),
	}, "|")
}

func boolKey(b bool) string {
	if b {
		return "1"
	}
	return "0"
}

// ---------- 绕过列表 ----------

type matcher func(host string) bool

type bypass struct{ matchers []matcher }

func newBypass(list []string) *bypass {
	b := &bypass{}
	for _, raw := range list {
		p := strings.ToLower(strings.TrimSpace(raw))
		if p == "" {
			continue
		}
		if m := compilePattern(p); m != nil {
			b.matchers = append(b.matchers, m)
		}
	}
	return b
}

func (b *bypass) match(host string) bool {
	if b == nil || len(b.matchers) == 0 {
		return false
	}
	h := strings.ToLower(strings.TrimSpace(host))
	if h == "" {
		return false
	}
	for _, m := range b.matchers {
		if m(h) {
			return true
		}
	}
	return false
}

// compilePattern 把单条绕过规则编译为匹配函数；无法识别的规则忽略。
func compilePattern(p string) matcher {
	switch {
	case p == "<local>":
		return isLocal
	case strings.Contains(p, "/"):
		// CIDR：仅当主机是 IP 时参与判断
		if _, cidr, err := net.ParseCIDR(p); err == nil {
			return func(host string) bool {
				ip := net.ParseIP(host)
				return ip != nil && cidr.Contains(ip)
			}
		}
		return nil
	case strings.HasPrefix(p, "*."):
		suffix := p[1:] // ".example.com"
		return func(host string) bool { return strings.HasSuffix(host, suffix) }
	case strings.HasPrefix(p, "."):
		return func(host string) bool { return strings.HasSuffix(host, p) }
	case strings.Contains(p, "*"):
		re, err := wildcardRegexp(p)
		if err != nil {
			return nil
		}
		return func(host string) bool { return re.MatchString(host) }
	default:
		return func(host string) bool { return host == p }
	}
}

func wildcardRegexp(pattern string) (*regexp.Regexp, error) {
	var sb strings.Builder
	sb.WriteString("(?i)^")
	for i, part := range strings.Split(pattern, "*") {
		if i > 0 {
			sb.WriteString(".*")
		}
		sb.WriteString(regexp.QuoteMeta(part))
	}
	sb.WriteString("$")
	return regexp.Compile(sb.String())
}

// isLocal <local> 语义：localhost、环回、私网、链路本地地址直连。
func isLocal(host string) bool {
	if host == "localhost" {
		return true
	}
	ip := net.ParseIP(host)
	if ip == nil {
		return false
	}
	return ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast()
}
