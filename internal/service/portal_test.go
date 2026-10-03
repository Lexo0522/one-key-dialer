package service

import (
	"net/http"
	"net/http/httptest"
	"testing"

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
}
