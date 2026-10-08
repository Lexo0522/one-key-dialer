package service

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/Lexo0522/one-key-dialer/internal/model"
)

func portalProbeCfg(url string) model.ProbeConfig {
	return model.ProbeConfig{
		Mode:          model.ProbeModeHTTP,
		HTTPUrl:       url,
		Attempts:      1,
		HTTPTimeoutMs: 2000,
	}
}

// 在线时 generate_204 返回 204:不应误判门户。
func TestDetectPortalClean(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	defer srv.Close()

	d := DetectPortal(portalProbeCfg(srv.URL + "/generate_204"))
	if d.Portal {
		t.Fatalf("204 不应判定为门户: %+v", d)
	}
}

// 302 重定向到其他主机:判定门户并捕获 Location。
func TestDetectPortalRedirect(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "http://10.1.1.55/srun_portal_pc?ac_id=1", http.StatusFound)
	}))
	defer srv.Close()

	d := DetectPortal(portalProbeCfg(srv.URL + "/generate_204"))
	if !d.Portal {
		t.Fatalf("302 应判定为门户: %+v", d)
	}
	if d.PortalURL != "http://10.1.1.55/srun_portal_pc?ac_id=1" {
		t.Fatalf("门户地址捕获错误: %q", d.PortalURL)
	}
}

// 相对路径 Location 必须补全为绝对地址。
func TestDetectPortalRelativeLocation(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "/login.html?backurl=%2Fok", http.StatusFound)
	}))
	defer srv.Close()

	d := DetectPortal(portalProbeCfg(srv.URL + "/generate_204"))
	if !d.Portal {
		t.Fatalf("302 应判定为门户: %+v", d)
	}
	if d.PortalURL != srv.URL+"/login.html?backurl=%2Fok" {
		t.Fatalf("相对 Location 未补全: %q", d.PortalURL)
	}
}

// generate_204 家族返回 200:视为拦截页(响应被门户替换)。
func TestDetectPortalIntercepted200(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("<html><script>location.href='http://portal/login'</script></html>"))
	}))
	defer srv.Close()

	d := DetectPortal(portalProbeCfg(srv.URL + "/generate_204"))
	if !d.Portal {
		t.Fatalf("generate_204 返回 200 应判定为门户: %+v", d)
	}
}

// 非 generate_204 的探测地址返回 200 属正常在线,不能误判(用户可自配探测 URL)。
func TestDetectPortalPlain200NotPortal(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	}))
	defer srv.Close()

	d := DetectPortal(portalProbeCfg(srv.URL + "/connecttest.txt"))
	if d.Portal {
		t.Fatalf("普通 200 不应判定为门户: %+v", d)
	}
	if d.Error != "" {
		t.Fatalf("200 响应不应带 Error: %+v", d)
	}
}

// 探测请求超时必须带 Error（探测未完成），而不能当成"网络无需认证"：
// 曾经的 bug 是把超时折叠成 Detail，Portal=false 被误读成无门户。
func TestDetectPortalTimeoutIsErrorNotNoPortal(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// 持连接不响应：让 client.Timeout 触发。
		<-r.Context().Done()
	}))
	defer srv.Close()

	cfg := portalProbeCfg(srv.URL + "/generate_204")
	cfg.HTTPTimeoutMs = 400
	// 备选地址同样不可达（本地保留网段几乎必然超时），确保走完整重试链。
	d := detectPortalOnce(cfg)
	if d.Error == "" {
		t.Fatalf("超时探测必须返回 Error: %+v", d)
	}
	if d.Portal {
		t.Fatalf("超时不应判定为门户: %+v", d)
	}
	if d.ProbeURL != srv.URL+"/generate_204" {
		t.Fatalf("ProbeURL 应回显探测地址: %+v", d)
	}
}

// 全部地址连不上时 DetectPortal 仍返回 Error，且带最后尝试的地址。
func TestDetectPortalAllRetriesFail(t *testing.T) {
	dead := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		<-r.Context().Done()
	}))
	deadURL := dead.URL + "/generate_204"
	dead.Close() // 关掉后变成连接被拒：失败快且确定

	orig := portalProbeRetries
	portalProbeRetries = []string{deadURL, deadURL}
	defer func() { portalProbeRetries = orig }()

	cfg := portalProbeCfg(deadURL)
	d := DetectPortal(cfg)
	if d.Error == "" {
		t.Fatalf("所有地址失败必须返回 Error: %+v", d)
	}
	if d.Portal {
		t.Fatalf("失败不应判定为门户: %+v", d)
	}
	if d.ProbeURL != deadURL {
		t.Fatalf("ProbeURL 应回显探测地址: %+v", d)
	}
}

// 首轮地址不可达时，备选明文地址探测到拦截页也算门户。
func TestDetectPortalRetryURLFindsPortal(t *testing.T) {
	first := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		<-r.Context().Done()
	}))
	defer first.Close()
	portalSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("<html>need login</html>"))
	}))
	defer portalSrv.Close()

	// 把首轮指向"黑洞"地址，第二个备选地址指向拦截页。
	cfg := portalProbeCfg(first.URL + "/generate_204")
	cfg.HTTPTimeoutMs = 300
	orig := portalProbeRetries
	portalProbeRetries = []string{portalSrv.URL + "/generate_204"}
	defer func() { portalProbeRetries = orig }()

	d := DetectPortal(cfg)
	if !d.Portal {
		t.Fatalf("备选地址探测到 200 拦截页应判定门户: %+v", d)
	}
	if d.Error != "" {
		t.Fatalf("探测成功不应带 Error: %+v", d)
	}
}

// 首轮地址不可达且 HTTPTimeoutMs 很小时超时应有限；单次探测应尊重该超时。
func TestDetectPortalOnceRespectsTimeout(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		<-r.Context().Done()
	}))
	defer srv.Close()

	cfg := portalProbeCfg(srv.URL + "/generate_204")
	cfg.HTTPTimeoutMs = 400
	start := time.Now()
	_ = detectPortalOnce(cfg)
	if elapsed := time.Since(start); elapsed > 3*time.Second {
		t.Fatalf("单次探测耗时 %v，未遵守 400ms 量级超时", elapsed)
	}
}

// 200 劫持时必须从劫持页正文提取真正的门户地址,而不是把探测地址当门户:
// 曾经的 bug 直接把 generate_204 探测地址填进 PortalURL,srun 流程据此去
// 公网主机请求 /cgi-bin/get_challenge,认证永远失败。
func TestDetectPortalIntercepted200ExtractsPortalURL(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`<html><head><script>window.location="http://10.1.1.55/eportal/index.jsp?wlanuserip=10.0.0.5&ac_id=1";</script></head></html>`))
	}))
	defer srv.Close()

	probeURL := srv.URL + "/generate_204"
	d := DetectPortal(portalProbeCfg(probeURL))
	if !d.Portal {
		t.Fatalf("generate_204 返回 200 应判定为门户: %+v", d)
	}
	want := "http://10.1.1.55/eportal/index.jsp?wlanuserip=10.0.0.5&ac_id=1"
	if d.PortalURL != want {
		t.Fatalf("应从劫持页提取门户地址:\n got %s\nwant %s", d.PortalURL, want)
	}
	if d.PortalURL == probeURL {
		t.Fatalf("PortalURL 绝不能是探测地址本身: %s", d.PortalURL)
	}
}

// 劫持页里找不到门户地址时 PortalURL 置空(而不是回退探测地址),
// 调用方据此提示用户手动配置登录地址。
func TestDetectPortalIntercepted200NoURLInPage(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`<html><body>blocked</body></html>`))
	}))
	defer srv.Close()

	d := DetectPortal(portalProbeCfg(srv.URL + "/generate_204"))
	if !d.Portal {
		t.Fatalf("应判定为门户: %+v", d)
	}
	if d.PortalURL != "" {
		t.Fatalf("提取不到门户地址时 PortalURL 应置空, got %s", d.PortalURL)
	}
}

func TestExtractPortalURLFromPage(t *testing.T) {
	probe := "http://connect.rom.miui.com/generate_204"
	// 含门户特征词的地址优先
	got := extractPortalURLFromPage(
		`<a href="http://tracker.example.com/pixel">x</a><script>location="http://192.168.1.1/eportal/index.jsp?x=1"</script>`, probe)
	if got != "http://192.168.1.1/eportal/index.jsp?x=1" {
		t.Fatalf("应优先门户特征地址, got %s", got)
	}
	// 探测地址本身必须跳过
	got = extractPortalURLFromPage(
		`<p>see http://connect.rom.miui.com/generate_204 for details</p>`, probe)
	if got != "" {
		t.Fatalf("应跳过探测地址本身, got %s", got)
	}
	// 无候选返回空
	if got := extractPortalURLFromPage(`<html>hello</html>`, probe); got != "" {
		t.Fatalf("无候选应返回空, got %s", got)
	}
	// 深澜风格:多个地址时选最像门户的
	got = extractPortalURLFromPage(
		`var u="http://10.10.10.10/srun_portal";var c="http://cdn.example.com/a.js";`, probe)
	if got != "http://10.10.10.10/srun_portal" {
		t.Fatalf("应选中 srun 门户地址, got %s", got)
	}
}

// 门户探测必须直连:即使环境变量里配了不可用的代理,也不能影响探测。
func TestDetectPortalBypassesProxyEnv(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	defer srv.Close()

	t.Setenv("HTTP_PROXY", "http://127.0.0.1:1/")
	t.Setenv("HTTPS_PROXY", "http://127.0.0.1:1/")
	t.Setenv("http_proxy", "http://127.0.0.1:1/")
	t.Setenv("https_proxy", "http://127.0.0.1:1/")

	d := DetectPortal(portalProbeCfg(srv.URL + "/generate_204"))
	if d.Error != "" {
		t.Fatalf("直连探测不应受代理环境变量影响: %+v", d)
	}
	if d.Portal {
		t.Fatalf("204 不应判定为门户: %+v", d)
	}
}
