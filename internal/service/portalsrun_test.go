package service

import (
	"encoding/base64"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"

	"github.com/Lexo0522/one-key-dialer/internal/model"
)

// 期望值由公开参考实现(iskoldt-X/SRUN-authenticator)的算法直接计算生成,
// 用于钉死 Go 移植,防止算术细节偏差。
func TestSrunProtocolVectors(t *testing.T) {
	const (
		user = "20210001"
		pass = "Passw0rd123"
		ip   = "10.0.0.1"
		acid = "1"
		tok  = "0123456789abcdef"
	)
	wantJSON := `{"username":"20210001","password":"Passw0rd123","ip":"10.0.0.1","acid":"1","enc_ver":"srun_bx1"}`
	wantInfo := "{SRBX1}mApj96WtRQPeT6iTgSPZqfdmLLBxzs580+sLB5GSvEptA0kMsVANGh2s78D55kIx6uj4YDwc0dv2fLWIcun6aupn394mfRCag2Fg75p3YD0RzOOTntspVFvKGsC8UKh5z8RRyS=="
	wantHmd5 := "6177caaf19cf55d2b65b85f9bed394c4"
	wantSum := "f457b60c302dc7a94ec3ca6672a65630162d2d89"

	if got := srunInfoJSON(user, pass, ip, acid); got != wantJSON {
		t.Fatalf("info JSON 不符:\n got %s\nwant %s", got, wantJSON)
	}
	if got := "{SRBX1}" + srunBase64(srunXEncode(wantJSON, tok)); got != wantInfo {
		t.Fatalf("info 编码不符:\n got %s\nwant %s", got, wantInfo)
	}
	if got := srunHmd5(tok, pass); got != wantHmd5 {
		t.Fatalf("hmd5 不符: got %s want %s", got, wantHmd5)
	}
	if got := srunChksum(tok, user, wantHmd5, acid, ip, wantInfo); got != wantSum {
		t.Fatalf("chksum 不符: got %s want %s", got, wantSum)
	}

	// 第二组:含特殊字符(&)与多字节 acid,验证 JSON 转义与不同 token。
	const (
		user2 = "user@cmcc"
		pass2 = "p&w~x"
		ip2   = "172.16.20.30"
		acid2 = "3"
		tok2  = "d41d8cd98f00b204e9800998ecf8427e"
	)
	wantJSON2 := `{"username":"user@cmcc","password":"p&w~x","ip":"172.16.20.30","acid":"3","enc_ver":"srun_bx1"}`
	wantInfo2 := "{SRBX1}BONPjkW9afKdjcrSQdZhW/rlh/6lzXuwvf52TQ3ba4AlpJ92oUJNVmLDlMNYtPlHsJXkzH6VsLZkX4TJLrBSANhvKN1xqrfyQd2dQNCTF2FYEDANcAI4SLqqNAxIXCh3mHYbqS=="
	wantHmd52 := "90f45c12cd05da802f4f3867873b4afc"
	wantSum2 := "919fdf3797065d49312ef01346cabf86b225ee2a"

	if got := srunInfoJSON(user2, pass2, ip2, acid2); got != wantJSON2 {
		t.Fatalf("info JSON 2 不符:\n got %s\nwant %s", got, wantJSON2)
	}
	if got := "{SRBX1}" + srunBase64(srunXEncode(wantJSON2, tok2)); got != wantInfo2 {
		t.Fatalf("info 编码 2 不符:\n got %s\nwant %s", got, wantInfo2)
	}
	if got := srunHmd5(tok2, pass2); got != wantHmd52 {
		t.Fatalf("hmd5 2 不符: got %s want %s", got, wantHmd52)
	}
	if got := srunChksum(tok2, user2, wantHmd52, acid2, ip2, wantInfo2); got != wantSum2 {
		t.Fatalf("chksum 2 不符: got %s want %s", got, wantSum2)
	}
}

// srunBase64 与标准 base64 仅字母表不同:逐字符映射后必须一致。
func TestSrunBase64AlphabetMap(t *testing.T) {
	const std = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
	for _, in := range []string{"", "a", "ab", "abc", "abcd", "hello world", "\x01\x02\x03\x04\x05"} {
		want := base64.StdEncoding.EncodeToString([]byte(in))
		want = strings.Map(func(r rune) rune {
			if idx := strings.IndexByte(std, byte(r)); idx >= 0 {
				return rune(srunBase64Alpha[idx])
			}
			return r // '=' 保持
		}, want)
		if got := srunBase64([]byte(in)); got != want {
			t.Fatalf("srunBase64(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestStripJSONP(t *testing.T) {
	if got := stripJSONP(`cb({"a":1})`); got != `{"a":1}` {
		t.Fatalf("JSONP 未剥离: %s", got)
	}
	if got := stripJSONP(` jQuery_123({"error":"ok"}) `); got != `{"error":"ok"}` {
		t.Fatalf("带空格 JSONP 未剥离: %s", got)
	}
	if got := stripJSONP(`{"error":"ok"}`); got != `{"error":"ok"}` {
		t.Fatalf("纯 JSON 不应改变: %s", got)
	}
}

// 两步登录全流程:校验请求参数与成功/失败判定。
func TestExecutePortalAuthSrun(t *testing.T) {
	var gotChallengeQuery, gotLoginQuery string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/cgi-bin/get_challenge":
			gotChallengeQuery = r.URL.RawQuery
			_, _ = w.Write([]byte(`{"error":"ok","ecode":0,"challenge":"0123456789abcdef","client_ip":"10.0.0.1"}`))
		case "/cgi-bin/srun_portal":
			gotLoginQuery = r.URL.RawQuery
			_, _ = w.Write([]byte(`{"error":"ok","ecode":0,"echo_msg":"login ok","res":"ok"}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	cfg := PortalAuthConfig{
		LoginUrl: srv.URL, // 根地址,末尾无斜杠
		Method:   model.PortalMethodSrun,
	}
	portal := "http://1.2.3.4:8080/eportal/index.jsp?wlanuserip=10.0.0.1&ac_id=3"
	out := ExecutePortalAuth(cfg, portal, "20210001", "Passw0rd123")
	if !out.Success {
		t.Fatalf("srun 登录应成功: %+v", out)
	}
	if !strings.Contains(gotChallengeQuery, "username=20210001") || !strings.Contains(gotChallengeQuery, "ip=10.0.0.1") {
		t.Fatalf("get_challenge 参数缺失: %s", gotChallengeQuery)
	}
	vs, err := url.ParseQuery(gotLoginQuery)
	if err != nil {
		t.Fatal(err)
	}
	if vs.Get("action") != "login" || vs.Get("username") != "20210001" ||
		vs.Get("ac_id") != "3" || vs.Get("ip") != "10.0.0.1" ||
		vs.Get("n") != "200" || vs.Get("type") != "1" {
		t.Fatalf("srun_portal 参数缺失: %s", gotLoginQuery)
	}
	if !strings.HasPrefix(vs.Get("password"), "{MD5}") {
		t.Fatalf("password 字段应带 {MD5} 前缀: %s", vs.Get("password"))
	}
	if !strings.HasPrefix(vs.Get("info"), "{SRBX1}") {
		t.Fatalf("info 字段应带 {SRBX1} 前缀: %s", vs.Get("info"))
	}
	if len(vs.Get("chksum")) != 40 {
		t.Fatalf("chksum 应为 40 位 SHA1 十六进制: %s", vs.Get("chksum"))
	}

	// 完整校验:用同一套参数重算 chksum,服务端拿到的 info/chksum 必须自洽。
	if got := srunChksum("0123456789abcdef", "20210001",
		strings.TrimPrefix(vs.Get("password"), "{MD5}"), vs.Get("ac_id"), vs.Get("ip"), vs.Get("info")); got != vs.Get("chksum") {
		t.Fatalf("chksum 与参数不自洽: got %s want %s", got, vs.Get("chksum"))
	}
}

// 门户拒绝认证(error != ok)必须判失败,并带出错误信息。
func TestExecutePortalAuthSrunRejected(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/cgi-bin/get_challenge" {
			_, _ = w.Write([]byte(`{"error":"ok","challenge":"abcdef0123456789","client_ip":"10.0.0.9"}`))
			return
		}
		_, _ = w.Write([]byte(`{"error":"password_error","error_msg":"密码错误","ecode":0}`))
	}))
	defer srv.Close()

	cfg := PortalAuthConfig{LoginUrl: srv.URL, Method: model.PortalMethodSrun}
	out := ExecutePortalAuth(cfg, "", "u1", "bad")
	if out.Success {
		t.Fatalf("门户拒绝时不应判定成功: %+v", out)
	}
	if !strings.Contains(out.Detail, "password_error") || !strings.Contains(out.Detail, "密码错误") {
		t.Fatalf("失败明细应带出错误信息: %+v", out)
	}
}

// 登录地址粘贴完整接口地址时应容错剥离;challenge 阶段报错要透出。
func TestExecutePortalAuthSrunBaseUrlAndErrors(t *testing.T) {
	var challengeHits int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		challengeHits++
		// 返回 JSONP 包装,验证剥壳逻辑。
		_, _ = w.Write([]byte(`cb({"error":"fail","ecode":1,"error_msg":"E2531: Request failed"})`))
	}))
	defer srv.Close()

	cfg := PortalAuthConfig{
		LoginUrl: srv.URL + "/cgi-bin/srun_portal.php/",
		Method:   model.PortalMethodSrun,
	}
	out := ExecutePortalAuth(cfg, "", "u1", "p1")
	if out.Success {
		t.Fatal("challenge 失败不应判定成功")
	}
	if !strings.Contains(out.Detail, "E2531") {
		t.Fatalf("失败明细应带出错误码: %+v", out)
	}
	if challengeHits != 1 {
		t.Fatalf("应恰好请求一次 challenge: %d", challengeHits)
	}

	// 登录地址为空且无门户地址 → 未配置登录地址。
	out = ExecutePortalAuth(PortalAuthConfig{Method: model.PortalMethodSrun}, "", "u1", "p1")
	if out.Success || !strings.Contains(out.Detail, "未配置登录地址") && !strings.Contains(out.Detail, "Login URL") {
		t.Fatalf("无登录地址应失败: %+v", out)
	}
}
