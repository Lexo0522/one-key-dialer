package service

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/Lexo0522/one-key-dialer/internal/i18n"
	"github.com/Lexo0522/one-key-dialer/internal/model"
)

func TestRenderPortalTemplate(t *testing.T) {
	out := RenderPortalTemplate(
		"http://p/login?user={username:enc}&pass={password:enc}&url={portal}",
		"2021 001", "p@ss&w", "http://10.1.1.55/back",
	)
	want := "http://p/login?user=2021+001&pass=p%40ss%26w&url=http://10.1.1.55/back"
	if out != want {
		t.Fatalf("enc 占位符替换错误:\n got %s\nwant %s", out, want)
	}

	raw := RenderPortalTemplate(`{"username":"{username}","password":"{password}"}`, "u1", "p1", "")
	if raw != `{"username":"u1","password":"p1"}` {
		t.Fatalf("裸占位符替换错误: %s", raw)
	}
}

func TestParsePortalHeaders(t *testing.T) {
	h := ParsePortalHeaders("Content-Type: application/json\nReferer:http://p/a\n\n  \nbroken-line")
	if len(h) != 2 {
		t.Fatalf("应解析出 2 个合法请求头: %v", h)
	}
	if h[0][0] != "Content-Type" || h[0][1] != "application/json" {
		t.Fatalf("头 1 解析错误: %v", h[0])
	}
	if h[1][0] != "Referer" || h[1][1] != "http://p/a" {
		t.Fatalf("头 2 解析错误(冒号后无空格): %v", h[1])
	}
}

// ExecutePortalAuth:POST 表单模板应发送 URL 编码后的凭据,成功提示词命中即成功。
func TestExecutePortalAuthFormPost(t *testing.T) {
	var gotBody, gotCT string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		gotBody = string(b)
		gotCT = r.Header.Get("Content-Type")
		_, _ = w.Write([]byte(`{"error":"ok"}`))
	}))
	defer srv.Close()

	cfg := PortalAuthConfig{
		LoginUrl:    srv.URL + "/login",
		Method:      model.PortalMethodPost,
		Body:        "username={username:enc}&password={password:enc}",
		SuccessHint: `"error":"ok"`,
	}
	out := ExecutePortalAuth(cfg, "", "user1", "pw&1")
	if !out.Success {
		t.Fatalf("命中成功提示词应判定成功: %+v", out)
	}
	if gotBody != "username=user1&password=pw%261" {
		t.Fatalf("表单体未做 URL 编码: %q", gotBody)
	}
	if !strings.HasPrefix(gotCT, "application/x-www-form-urlencoded") {
		t.Fatalf("未设置默认表单 Content-Type: %q", gotCT)
	}
}

// 成功提示词未命中必须判失败,即使状态码是 200。
func TestExecutePortalAuthHintMissFails(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"error":"password_wrong"}`))
	}))
	defer srv.Close()

	cfg := PortalAuthConfig{
		LoginUrl:    srv.URL + "/login",
		Method:      model.PortalMethodPost,
		Body:        "u={username}",
		SuccessHint: `"error":"ok"`,
	}
	out := ExecutePortalAuth(cfg, "", "user1", "bad")
	if out.Success {
		t.Fatalf("提示词未命中不应判定成功: %+v", out)
	}
}

// 未配置登录地址时退化访问门户地址本身;无门户地址则失败。
func TestExecutePortalAuthFallsBackToPortalURL(t *testing.T) {
	var hits int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits++
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	out := ExecutePortalAuth(PortalAuthConfig{Method: "GET"}, srv.URL+"/portal", "u", "p")
	if !out.Success || hits != 1 {
		t.Fatalf("应退化访问门户地址: %+v hits=%d", out, hits)
	}

	out = ExecutePortalAuth(PortalAuthConfig{Method: "GET"}, "", "u", "p")
	if out.Success || out.Detail != i18n.T("portal.noLoginUrl") {
		t.Fatalf("无登录地址且无门户地址应失败: %+v", out)
	}
}
