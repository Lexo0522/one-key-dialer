package proxy

import (
	"bufio"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/Lexo0522/one-key-dialer/internal/model"
)

func TestFuncBypassAndFallback(t *testing.T) {
	cfg := model.ProxyConfig{
		Enabled: true, Type: "http", Host: "10.0.0.9", Port: "7890",
		Bypass: []string{"localhost", "*.internal.corp", "192.168.0.0/16", "<local>", "10.1.*.*"},
	}
	fn := Func(cfg)

	hit := []string{"example.com", "8.8.8.8", "223.5.5.5"}
	for _, h := range hit {
		u, err := fn(&http.Request{URL: &url.URL{Scheme: "http", Host: h}})
		if err != nil {
			t.Fatalf("Func(%s) error: %v", h, err)
		}
		if u == nil || u.Host != "10.0.0.9:7890" {
			t.Fatalf("Func(%s) = %v, want 10.0.0.9:7890", h, u)
		}
	}

	bypassed := []string{"localhost", "a.internal.corp", "192.168.1.5", "127.0.0.1", "10.1.2.3", "172.16.0.9", "10.2.3.4"}
	for _, h := range bypassed {
		u, err := fn(&http.Request{URL: &url.URL{Scheme: "http", Host: h + ":8080"}})
		if err != nil {
			t.Fatalf("Func(%s) error: %v", h, err)
		}
		if u != nil {
			t.Fatalf("Func(%s) = %v, want nil（命中绕过直连）", h, u)
		}
	}

	// 未启用 → 回退环境变量语义：无 HTTP_PROXY 时返回 nil
	off := Func(model.ProxyConfig{})
	if u, err := off(&http.Request{URL: &url.URL{Scheme: "http", Host: "example.com"}}); err != nil || u != nil {
		t.Fatalf("未启用代理应回退环境变量（当前无环境变量时为 nil）: %v %v", u, err)
	}

	// 启用但地址为空 → 同样回退
	noHost := Func(model.ProxyConfig{Enabled: true})
	if u, err := noHost(&http.Request{URL: &url.URL{Scheme: "http", Host: "example.com"}}); err != nil || u != nil {
		t.Fatalf("启用但无地址应回退环境变量: %v %v", u, err)
	}
}

func TestTransportForCaching(t *testing.T) {
	cfgA := model.ProxyConfig{Enabled: true, Type: "http", Host: "a", Port: "80"}
	cfgB := model.ProxyConfig{Enabled: true, Type: "http", Host: "b", Port: "80"}
	t1 := TransportFor(cfgA)
	t2 := TransportFor(cfgA)
	if t1 != t2 {
		t.Fatal("相同配置应复用同一 Transport（连接池）")
	}
	t3 := TransportFor(cfgB)
	if t1 == t3 {
		t.Fatal("不同配置应返回不同 Transport")
	}
	cfgC := cfgA
	cfgC.AuthEnabled = true
	cfgC.Username = "user"
	cfgC.Password = "first"
	cfgD := cfgC
	cfgD.Password = "second"
	if TransportFor(cfgC) == TransportFor(cfgD) {
		t.Fatal("different proxy credentials must use different Transports")
	}
}

func TestHTTPProxyAuthentication(t *testing.T) {
	const username, password = "proxy-user", "p@ss:word"
	var auth string
	proxySrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		auth = r.Header.Get("Proxy-Authorization")
		w.WriteHeader(http.StatusNoContent)
	}))
	defer proxySrv.Close()
	pu, _ := url.Parse(proxySrv.URL)
	cfg := model.ProxyConfig{
		Enabled: true, AuthEnabled: true, Type: "http",
		Host: pu.Hostname(), Port: pu.Port(), Username: username, Password: password,
	}
	resp, err := ClientFor(cfg).Get("http://origin.example/test")
	if err != nil {
		t.Fatalf("authenticated proxy request: %v", err)
	}
	resp.Body.Close()
	want := "Basic " + base64.StdEncoding.EncodeToString([]byte(username+":"+password))
	if auth != want {
		t.Fatalf("Proxy-Authorization = %q, want %q", auth, want)
	}
}

func TestHTTPSProxyAuthentication(t *testing.T) {
	const username, password = "proxy-user", "secret"
	var auth string
	proxySrv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		auth = r.Header.Get("Proxy-Authorization")
		w.WriteHeader(http.StatusProxyAuthRequired)
	}))
	defer proxySrv.Close()
	pu, _ := url.Parse(proxySrv.URL)
	rootCAs := x509.NewCertPool()
	rootCAs.AddCert(proxySrv.Certificate())
	cfg := model.ProxyConfig{
		Enabled: true, AuthEnabled: true, Type: "https",
		Host: pu.Hostname(), Port: pu.Port(), Username: username, Password: password,
	}
	transport := &http.Transport{Proxy: Func(cfg), TLSClientConfig: &tls.Config{RootCAs: rootCAs}}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport}
	_, _ = client.Get("https://origin.example/test") // 407 is expected; inspect its auth header.
	want := "Basic " + base64.StdEncoding.EncodeToString([]byte(username+":"+password))
	if auth != want {
		t.Fatalf("Proxy-Authorization = %q, want %q", auth, want)
	}
}

func TestSOCKS5ProxyAuthentication(t *testing.T) {
	const username, password = "socks-user", "socks-secret"
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	serverErr := make(chan error, 1)
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			serverErr <- err
			return
		}
		defer conn.Close()
		if _, err = io.CopyN(io.Discard, conn, 1); err != nil {
			serverErr <- err
			return
		}
		var nmethods [1]byte
		if _, err = io.ReadFull(conn, nmethods[:]); err != nil {
			serverErr <- err
			return
		}
		methods := make([]byte, int(nmethods[0]))
		if _, err = io.ReadFull(conn, methods); err != nil {
			serverErr <- err
			return
		}
		if _, err = conn.Write([]byte{5, 2}); err != nil {
			serverErr <- err
			return
		}
		var authHead [2]byte
		if _, err = io.ReadFull(conn, authHead[:]); err != nil {
			serverErr <- err
			return
		}
		user := make([]byte, int(authHead[1]))
		if _, err = io.ReadFull(conn, user); err != nil {
			serverErr <- err
			return
		}
		var passLen [1]byte
		if _, err = io.ReadFull(conn, passLen[:]); err != nil {
			serverErr <- err
			return
		}
		pass := make([]byte, int(passLen[0]))
		if _, err = io.ReadFull(conn, pass); err != nil {
			serverErr <- err
			return
		}
		if string(user) != username || string(pass) != password {
			serverErr <- fmt.Errorf("SOCKS5 credentials = %q/%q", user, pass)
			return
		}
		if _, err = conn.Write([]byte{1, 0}); err != nil {
			serverErr <- err
			return
		}
		var request [4]byte
		if _, err = io.ReadFull(conn, request[:]); err != nil {
			serverErr <- err
			return
		}
		switch request[3] {
		case 1:
			_, err = io.CopyN(io.Discard, conn, 6)
		case 3:
			var size [1]byte
			_, err = io.ReadFull(conn, size[:])
			if err == nil {
				_, err = io.CopyN(io.Discard, conn, int64(size[0])+2)
			}
		case 4:
			_, err = io.CopyN(io.Discard, conn, 18)
		default:
			err = fmt.Errorf("unexpected SOCKS5 address type %d", request[3])
		}
		if err != nil {
			serverErr <- err
			return
		}
		if _, err = conn.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0}); err != nil {
			serverErr <- err
			return
		}
		if _, err = http.ReadRequest(bufio.NewReader(conn)); err != nil {
			serverErr <- err
			return
		}
		_, err = io.WriteString(conn, "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n")
		serverErr <- err
	}()

	cfg := model.ProxyConfig{
		Enabled: true, AuthEnabled: true, Type: "socks5",
		Host: "127.0.0.1", Port: fmt.Sprint(listener.Addr().(*net.TCPAddr).Port),
		Username: username, Password: password,
	}
	resp, reqErr := ClientFor(cfg).Get("http://origin.example/test")
	if reqErr != nil {
		t.Fatalf("SOCKS5 authenticated request: %v", reqErr)
	}
	resp.Body.Close()
	if err := <-serverErr; err != nil {
		t.Fatalf("SOCKS5 proxy: %v", err)
	}
}

// TestRequestActuallyGoesThroughProxy 端到端验证：本地起一个假代理，
// 探测请求必须到达该代理；命中绕过列表的请求不得到达。
func TestRequestActuallyGoesThroughProxy(t *testing.T) {
	var viaProxy, direct atomic.Int64
	proxyAuth := make(chan string, 1)
	var directHasProxyAuth atomic.Bool
	// 假 HTTP 代理：对绝对 URI 形式的普通 HTTP 请求直接应答 204。
	proxySrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		viaProxy.Add(1)
		proxyAuth <- r.Header.Get("Proxy-Authorization")
		w.WriteHeader(http.StatusNoContent)
	}))
	defer proxySrv.Close()

	origin := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		direct.Add(1)
		directHasProxyAuth.Store(r.Header.Get("Proxy-Authorization") != "")
		w.WriteHeader(http.StatusNoContent)
	}))
	defer origin.Close()

	pu, _ := url.Parse(proxySrv.URL)
	ou, _ := url.Parse(origin.URL)
	cfg := model.ProxyConfig{
		Enabled: true, AuthEnabled: true, Type: "http", Username: "proxy-user", Password: "proxy-secret",
		Host: pu.Hostname(), Port: pu.Port(),
		Bypass: []string{"127.0.0.1"},
	}
	client := ClientFor(cfg)

	// 非绕过主机 → 必须经过代理（假代理应答 204，源站不应被直连）
	resp, err := client.Get("http://detect.example.com/generate_204")
	if err != nil {
		t.Fatalf("经代理请求失败: %v", err)
	}
	resp.Body.Close()
	if viaProxy.Load() != 1 {
		t.Fatalf("请求未到达假代理: viaProxy=%d", viaProxy.Load())
	}
	if direct.Load() != 0 {
		t.Fatalf("非绕过请求被直连了: direct=%d", direct.Load())
	}
	wantAuth := "Basic " + base64.StdEncoding.EncodeToString([]byte("proxy-user:proxy-secret"))
	if got := <-proxyAuth; got != wantAuth {
		t.Fatalf("proxy auth = %q, want %q", got, wantAuth)
	}

	// 绕过主机 → 不得经过代理（由源站应答）
	resp2, err := client.Get("http://127.0.0.1:" + ou.Port() + "/generate_204")
	if err != nil {
		t.Fatalf("绕过请求失败: %v", err)
	}
	resp2.Body.Close()
	if viaProxy.Load() != 1 {
		t.Fatalf("绕过请求错误地经过了代理: viaProxy=%d", viaProxy.Load())
	}
	if direct.Load() != 1 {
		t.Fatalf("绕过请求未直连源站: direct=%d", direct.Load())
	}
	if directHasProxyAuth.Load() {
		t.Fatal("bypassed request carried Proxy-Authorization")
	}
}

// TestSocks5URLConstruction 验证 SOCKS5 代理 URL 形态（Go 标准库
// http.Transport 原生支持 socks5 scheme）。
func TestSocks5URLConstruction(t *testing.T) {
	cfg := model.ProxyConfig{Enabled: true, Type: "socks5", Host: "127.0.0.1", Port: "1080"}
	fn := Func(cfg)
	u, err := fn(&http.Request{URL: &url.URL{Scheme: "https", Host: "example.com"}})
	if err != nil {
		t.Fatalf("Func error: %v", err)
	}
	if u == nil || !strings.EqualFold(u.Scheme, "socks5") || u.Host != "127.0.0.1:1080" {
		t.Fatalf("SOCKS5 代理 URL = %v", u)
	}
}
