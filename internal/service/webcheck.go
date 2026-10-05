package service

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

// 网站测速与公网 IP 查询（Clash Verge 首页同款卡片的数据源）。
// 两者都走直连（不经设置内的代理）：测速要反映本机网络到网站的真实延迟，
// IP 信息要显示 PPPoE 线路的出口归属而非代理出口。
// 测速站点列表由前端配置（设置 speedSites，支持自建测试点）并随请求传入。

// MaxSpeedSites 单次测速的站点数上限，防止列表被塞爆拖垮界面。
const MaxSpeedSites = 12

// SiteLatency 单站点测速结果；LatencyMs 为 -1 表示失败或超时。
type SiteLatency struct {
	Url       string `json:"url"`
	LatencyMs int64  `json:"latencyMs"`
}

// webcheckClient 直连 HTTP 客户端：Proxy 显式置 nil（不走系统环境变量），
// 每次调用即建连，测得的是完整的 DNS+TCP+TLS+首字节延迟。
var webcheckClient = &http.Client{
	Transport: &http.Transport{
		Proxy:                 nil,
		TLSHandshakeTimeout:   5 * time.Second,
		ResponseHeaderTimeout: 5 * time.Second,
		IdleConnTimeout:       30 * time.Second,
	},
	Timeout: 6 * time.Second,
}

// TestSiteLatency 并发测每个 URL 的延迟（入参来自前端测试点配置）。
// 忽略空串与非 http(s) 地址、去重、截断到 MaxSpeedSites；
// 结果按入参顺序返回，整体不超过约 4.5 秒（低于 IPC 20 秒管道超时）。
func TestSiteLatency(urls []string) []SiteLatency {
	seen := make(map[string]bool, len(urls))
	targets := make([]string, 0, len(urls))
	for _, u := range urls {
		u = strings.TrimSpace(u)
		if !isHTTPOrHTTPS(u) || seen[u] {
			continue
		}
		seen[u] = true
		targets = append(targets, u)
		if len(targets) >= MaxSpeedSites {
			break
		}
	}

	out := make([]SiteLatency, len(targets))
	var wg sync.WaitGroup
	for i, u := range targets {
		out[i].Url = u
		out[i].LatencyMs = -1
		wg.Add(1)
		go func(i int, u string) {
			defer wg.Done()
			out[i].LatencyMs = measureLatency(u)
		}(i, u)
	}
	wg.Wait()
	return out
}

func isHTTPOrHTTPS(u string) bool {
	lower := strings.ToLower(u)
	return strings.HasPrefix(lower, "http://") || strings.HasPrefix(lower, "https://")
}

// measureLatency HEAD 请求计到响应首字节；被拒（405 等 4xx）退回 GET。
func measureLatency(url string) int64 {
	start := time.Now()
	ok := probeOnce(http.MethodHead, url)
	if !ok {
		ok = probeOnce(http.MethodGet, url)
	}
	if !ok {
		return -1
	}
	return time.Since(start).Milliseconds()
}

func probeOnce(method, url string) bool {
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, method, url, nil)
	if err != nil {
		return false
	}
	// 部分站点对非常见 UA 拒答，用浏览器 UA 避免误判为不可达
	req.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
	resp, err := webcheckClient.Do(req)
	if err != nil {
		return false
	}
	io.Copy(io.Discard, io.LimitReader(resp.Body, 1))
	resp.Body.Close()
	return resp.StatusCode < 500
}

// IPInfo 公网出口 IP 与归属信息；查询失败时对应字段留空。
type IPInfo struct {
	Ip         string `json:"ip"`
	Country    string `json:"country"`
	RegionName string `json:"regionName"`
	City       string `json:"city"`
	Isp        string `json:"isp"`
	As         string `json:"as"`
	Timezone   string `json:"timezone"` // IANA 时区名，如 Asia/Shanghai
	// LocalIp 本机在默认路由上的内网地址（UDP connect 技巧，不发包）
	LocalIp string `json:"localIp"`
}

// FetchIPInfo 查询公网出口 IP 与运营商归属（直连）。
// 主源 ip-api.com（中文、免费），失败回退 ipinfo.io（英文）；
// 两个源各 4 秒超时，整体不超过约 8 秒。
func FetchIPInfo() IPInfo {
	info := IPInfo{LocalIp: defaultLocalIP()}
	if raw := fetchJSON("http://ip-api.com/json/?lang=zh-CN&fields=status,country,regionName,city,isp,as,timezone,query"); raw != nil {
		var r struct {
			Status     string `json:"status"`
			Country    string `json:"country"`
			RegionName string `json:"regionName"`
			City       string `json:"city"`
			Isp        string `json:"isp"`
			As         string `json:"as"`
			Timezone   string `json:"timezone"`
			Query      string `json:"query"`
		}
		if json.Unmarshal(raw, &r) == nil && r.Status == "success" && r.Query != "" {
			info.Ip, info.Country, info.RegionName = r.Query, r.Country, r.RegionName
			info.City, info.Isp, info.As = r.City, r.Isp, r.As
			info.Timezone = r.Timezone
			return info
		}
	}
	// 回退源：ipinfo.io（字段 ip/city/region/country/org/timezone，英文）
	if raw := fetchJSON("https://ipinfo.io/json"); raw != nil {
		var r struct {
			Ip       string `json:"ip"`
			City     string `json:"city"`
			Region   string `json:"region"`
			Country  string `json:"country"`
			Org      string `json:"org"`
			Timezone string `json:"timezone"`
		}
		if json.Unmarshal(raw, &r) == nil && r.Ip != "" {
			info.Ip, info.City, info.RegionName = r.Ip, r.City, r.Region
			info.Country = r.Country
			info.Isp, info.As = splitOrg(r.Org)
			info.Timezone = r.Timezone
			return info
		}
	}
	return info
}

// splitOrg 把 ipinfo 的 "AS4134 Chinanet" 拆成 AS 号与运营商名。
func splitOrg(org string) (as, isp string) {
	fields := strings.SplitN(strings.TrimSpace(org), " ", 2)
	if len(fields) > 0 && strings.HasPrefix(fields[0], "AS") {
		if _, err := strconv.Atoi(fields[0][2:]); err == nil {
			as = fields[0]
		}
	}
	if len(fields) > 1 {
		isp = fields[1]
	} else if as == "" {
		isp = strings.TrimSpace(org)
	}
	return as, isp
}

// defaultLocalIP 用 UDP connect 取默认路由上的本机地址（不实际发包）。
func defaultLocalIP() string {
	conn, err := net.DialTimeout("udp", "223.5.5.5:53", time.Second)
	if err != nil {
		return ""
	}
	defer conn.Close()
	addr, ok := conn.LocalAddr().(*net.UDPAddr)
	if !ok {
		return ""
	}
	return addr.IP.String()
}

func fetchJSON(url string) []byte {
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil
	}
	req.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/124.0 Safari/537.36")
	resp, err := webcheckClient.Do(req)
	if err != nil {
		return nil
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil
	}
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 8<<10))
	if err != nil {
		return nil
	}
	if len(raw) == 0 {
		return nil
	}
	return raw
}
