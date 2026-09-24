package service

import (
	"net/http"
	"net/http/httptest"
	"net/url"
	"sync/atomic"
	"testing"

	"github.com/Lexo0522/one-key-dialer/internal/model"
)

// TestHTTPReachableGoesThroughProxy：HTTP 探测必须经设置内的代理发出。
// 本地假代理记录命中次数；若探测绕过代理直连，假代理计数为 0 即失败。
func TestHTTPReachableGoesThroughProxy(t *testing.T) {
	var viaProxy atomic.Int64
	proxySrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		viaProxy.Add(1)
		w.WriteHeader(http.StatusNoContent)
	}))
	defer proxySrv.Close()

	pu, err := url.Parse(proxySrv.URL)
	if err != nil {
		t.Fatal(err)
	}
	cfg := model.ProbeConfig{
		Mode:          model.ProbeModeHTTP,
		HTTPUrl:       "http://detect.example.com/generate_204",
		Attempts:      1,
		HTTPTimeoutMs: 2000,
		Proxy: model.ProxyConfig{
			Enabled: true, Type: "http", Host: pu.Hostname(), Port: pu.Port(),
		},
	}
	if !QuickCheck(cfg) {
		t.Fatal("经代理探测应判定可达（假代理返回 204）")
	}
	if viaProxy.Load() != 1 {
		t.Fatalf("探测请求未经过代理：viaProxy=%d", viaProxy.Load())
	}
}

// TestHTTPReachableFailsWhenProxyDead：代理指向不可达端口时探测必须判不通，
// 反向证明请求确实走了代理而不是被静默直连。
func TestHTTPReachableFailsWhenProxyDead(t *testing.T) {
	cfg := model.ProbeConfig{
		Mode:          model.ProbeModeHTTP,
		HTTPUrl:       "http://detect.example.com/generate_204",
		Attempts:      1,
		HTTPTimeoutMs: 1500,
		Proxy: model.ProxyConfig{
			Enabled: true, Type: "http", Host: "127.0.0.1", Port: "1",
		},
	}
	if QuickCheck(cfg) {
		t.Fatal("代理不可达时探测应判定不通")
	}
}

// TestHTTPReachableBypassesListedHost：命中绕过列表的主机不经代理。
func TestHTTPReachableBypassesListedHost(t *testing.T) {
	var viaProxy atomic.Int64
	proxySrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		viaProxy.Add(1)
		w.WriteHeader(http.StatusNoContent)
	}))
	defer proxySrv.Close()
	origin := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	defer origin.Close()

	pu, _ := url.Parse(proxySrv.URL)
	ou, _ := url.Parse(origin.URL)
	cfg := model.ProbeConfig{
		Mode:          model.ProbeModeHTTP,
		HTTPUrl:       "http://" + ou.Host + "/generate_204",
		Attempts:      1,
		HTTPTimeoutMs: 2000,
		Proxy: model.ProxyConfig{
			Enabled: true, Type: "http", Host: pu.Hostname(), Port: pu.Port(),
			Bypass: []string{"127.0.0.1"},
		},
	}
	if !QuickCheck(cfg) {
		t.Fatal("绕过代理直连源站应判定可达")
	}
	if viaProxy.Load() != 0 {
		t.Fatalf("绕过主机错误地经过了代理：viaProxy=%d", viaProxy.Load())
	}
}
