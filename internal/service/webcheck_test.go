package service

import (
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"

	"github.com/Lexo0522/one-key-dialer/internal/model"
)

// TestSplitOrg 解析 ipinfo 的 org 字段："AS4134 Chinanet" → as + isp。
func TestSplitOrg(t *testing.T) {
	tests := []struct {
		name    string
		in      string
		wantAs  string
		wantIsp string
	}{
		{"标准格式", "AS4134 Chinanet", "AS4134", "Chinanet"},
		{"无 AS 前缀整体算运营商", "Chinanet", "", "Chinanet"},
		{"AS 后非数字不算 as", "ASxx Chinanet", "", "ASxx Chinanet"},
		{"AS 号后无运营商名", "AS4134", "AS4134", ""},
		{"空串", "", "", ""},
		{"仅空白", "   ", "", ""},
		{"首尾空白被裁剪", "  AS4134   Chinanet Guangdong  ", "AS4134", "Chinanet Guangdong"},
		{"多个空格分隔取前两段", "AS4134  Chinanet", "AS4134", "Chinanet"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			as, isp := splitOrg(tt.in)
			if as != tt.wantAs {
				t.Errorf("splitOrg(%q) as = %q, want %q", tt.in, as, tt.wantAs)
			}
			if isp != tt.wantIsp {
				t.Errorf("splitOrg(%q) isp = %q, want %q", tt.in, isp, tt.wantIsp)
			}
		})
	}
}

// TestWebcheckClientFor 测速 / IP 查询的出口跟随设置内的代理开关：
// 未启用 = 直连（Transport.Proxy 显式 nil，系统环境变量代理也不读）；
// 启用 = 走 proxy.Func，请求经该代理转发，命中绕过列表的目标仍直连。
func TestWebcheckClientFor(t *testing.T) {
	// 未启用：即使填了代理地址也保持直连
	c := webcheckClientFor(model.ProxyConfig{Type: "http", Host: "10.255.255.1", Port: "8080"})
	tr, ok := c.Transport.(*http.Transport)
	if !ok {
		t.Fatalf("Transport 类型 = %T, want *http.Transport", c.Transport)
	}
	if tr.Proxy != nil {
		t.Fatal("代理未启用时必须直连（Proxy 为 nil）")
	}

	// 启用：请求必须经代理转发，不得直达目标
	var proxyHits int
	var directHits int
	proxySrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		proxyHits++
		w.WriteHeader(http.StatusOK)
	}))
	defer proxySrv.Close()
	pu, err := url.Parse(proxySrv.URL)
	if err != nil {
		t.Fatalf("解析代理地址: %v", err)
	}
	host, port, err := net.SplitHostPort(pu.Host)
	if err != nil {
		t.Fatalf("拆分代理地址: %v", err)
	}
	cfg := model.ProxyConfig{Enabled: true, Type: "http", Host: host, Port: port}

	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		directHits++
		w.WriteHeader(http.StatusOK)
	}))
	defer target.Close()

	resp, err := webcheckClientFor(cfg).Get(target.URL + "/probe")
	if err != nil {
		t.Fatalf("经代理请求失败: %v", err)
	}
	resp.Body.Close()
	if proxyHits != 1 || directHits != 0 {
		t.Fatalf("启用代理后请求应只经过代理：proxyHits=%d directHits=%d", proxyHits, directHits)
	}

	// 命中绕过列表的目标仍直连：代理侧不应再收到请求
	bypassCfg := cfg
	bypassCfg.Bypass = []string{host}
	resp, err = webcheckClientFor(bypassCfg).Get(target.URL + "/probe")
	if err != nil {
		t.Fatalf("绕过目标的请求失败: %v", err)
	}
	resp.Body.Close()
	if proxyHits != 1 || directHits != 1 {
		t.Fatalf("命中绕过列表的请求应直连：proxyHits=%d directHits=%d", proxyHits, directHits)
	}
}
