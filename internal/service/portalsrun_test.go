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

// srunJSBytes 按门户页 JS 语义(UTF-16 charCodeAt & 0xff)转字节:
// ASCII 与直接取字节一致;非 ASCII 与旧实现不同,且与门户页一致。
func TestSrunJSBytes(t *testing.T) {
	if got := string(srunJSBytes("abc123")); got != "abc123" {
		t.Fatalf("ASCII 应原样: %q", got)
	}
	// "密" U+5BC6 → 低字节 0xC6;"码" U+7801 → 低字节 0x01
	got := srunJSBytes("密码")
	if len(got) != 2 || got[0] != 0xC6 || got[1] != 0x01 {
		t.Fatalf("中文应取 UTF-16 低字节: % x", got)
	}
	// xEncode 对中文密码的结果必须与门户页 JS 算法一致(见独立 Python 校验):
	// 与旧实现(UTF-8 字节)算出的 info 不同,才是对的。
	j := srunInfoJSON("20210002", "密码123abc", "10.0.0.2", "1")
	info := "{SRBX1}" + srunBase64(srunXEncode(j, "abcdef0123456789"))
	want := "{SRBX1}UgUWThtPvSk25RKWbTGXNq5HeS1SJZrbrt3ZciY7mjxkXknl8+tbWbUTdItykbEUmskre01dRKZnxWjmH7MtU7EM2AWwr0t/P6VnEbxDBgj1HUj7cdMdyMfNIemMyWwCDKBHOv=="
	if info != want {
		t.Fatalf("中文密码 info 与 JS 算法不符:\n got %s\nwant %s", info, want)
	}
}

func TestSrunAlreadyOnline(t *testing.T) {
	yes := []srunAPIResp{
		{Error: "E2901", ErrorMsg: "(Third party 1)already online."},
		{Error: "login_error", ErrorMsg: "E2901: already online"},
		{ErrorMsg: "用户已经在线"},
	}
	for _, r := range yes {
		if !srunAlreadyOnline(r) {
			t.Fatalf("应判定已在线: %+v", r)
		}
	}
	no := []srunAPIResp{
		{Error: "ok"},
		{Error: "password_error", ErrorMsg: "密码错误"},
		{Error: "E2902"},
		{},
	}
	for _, r := range no {
		if srunAlreadyOnline(r) {
			t.Fatalf("不应判定已在线: %+v", r)
		}
	}
}

// 门户返回 already online 时应视为成功(目标已达成),而不是失败重试。
func TestExecutePortalAuthSrunAlreadyOnline(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/cgi-bin/get_challenge" {
			_, _ = w.Write([]byte(`{"error":"ok","challenge":"abcdef0123456789","client_ip":"10.0.0.9"}`))
			return
		}
		_, _ = w.Write([]byte(`{"error":"E2901","ecode":"E2901","error_msg":"(Third party 1)already online."}`))
	}))
	defer srv.Close()

	cfg := PortalAuthConfig{LoginUrl: srv.URL, Method: model.PortalMethodSrun}
	out := ExecutePortalAuth(cfg, "", "u1", "p1")
	if !out.Success {
		t.Fatalf("already online 应视为成功: %+v", out)
	}
}

// srun_portal 404 时回退 srun_portal.php(部分部署只有 .php 接口)。
func TestExecutePortalAuthSrunPhpFallback(t *testing.T) {
	var hits []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits = append(hits, r.URL.Path)
		switch r.URL.Path {
		case "/cgi-bin/get_challenge":
			_, _ = w.Write([]byte(`{"error":"ok","challenge":"0123456789abcdef","client_ip":"10.0.0.1"}`))
		case "/cgi-bin/srun_portal":
			http.NotFound(w, r)
		case "/cgi-bin/srun_portal.php":
			_, _ = w.Write([]byte(`{"error":"ok","ecode":0,"echo_msg":"login ok"}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	cfg := PortalAuthConfig{LoginUrl: srv.URL, Method: model.PortalMethodSrun}
	out := ExecutePortalAuth(cfg, "", "u1", "p1")
	if !out.Success {
		t.Fatalf("应回退 .php 接口并成功: %+v, hits=%v", out, hits)
	}
	foundPortal, foundPhp := false, false
	for _, h := range hits {
		if h == "/cgi-bin/srun_portal" {
			foundPortal = true
		}
		if h == "/cgi-bin/srun_portal.php" {
			foundPhp = true
		}
	}
	if !foundPortal || !foundPhp {
		t.Fatalf("应先试 srun_portal 再回退 .php, hits=%v", hits)
	}
}

// 两步请求都应带 callback/_ 参数,与真实门户页行为一致。
func TestExecutePortalAuthSrunCallbackParams(t *testing.T) {
	var challengeQuery, loginQuery string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/cgi-bin/get_challenge":
			challengeQuery = r.URL.RawQuery
			_, _ = w.Write([]byte(`{"error":"ok","challenge":"0123456789abcdef","client_ip":"10.0.0.1"}`))
		case "/cgi-bin/srun_portal":
			loginQuery = r.URL.RawQuery
			_, _ = w.Write([]byte(`{"error":"ok"}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	cfg := PortalAuthConfig{LoginUrl: srv.URL, Method: model.PortalMethodSrun}
	if out := ExecutePortalAuth(cfg, "", "u1", "p1"); !out.Success {
		t.Fatalf("应成功: %+v", out)
	}
	for name, q := range map[string]string{"challenge": challengeQuery, "login": loginQuery} {
		vs, err := url.ParseQuery(q)
		if err != nil {
			t.Fatal(err)
		}
		if !strings.HasPrefix(vs.Get("callback"), "jQuery") || vs.Get("_") == "" {
			t.Fatalf("%s 请求应带 callback/_ 参数: %s", name, q)
		}
	}
}

// srunBaseUrl 归一:粘贴完整接口地址/门户页地址/带参地址都要能归一到主机根。
func TestSrunBaseUrlNormalization(t *testing.T) {
	cases := []struct{ in, want string }{
		{"http://10.1.1.55", "http://10.1.1.55"},
		{"http://10.1.1.55/", "http://10.1.1.55"},
		{"http://10.1.1.55/cgi-bin/srun_portal", "http://10.1.1.55"},
		{"http://10.1.1.55/cgi-bin/srun_portal.php/", "http://10.1.1.55"},
		{"http://10.1.1.55/cgi-bin/get_challenge", "http://10.1.1.55"},
		// 用户粘贴门户页地址(含查询串):退到主机根,避免把页面路径拼进接口地址
		{"http://10.1.1.55/eportal/index.jsp?wlanuserip=10.0.0.5", "http://10.1.1.55"},
		{"https://portal.example.edu.cn:8443/srun/index.html", "https://portal.example.edu.cn:8443"},
		{"http://10.1.1.55/eportal/", "http://10.1.1.55"}, // 显式目录也退到主机根(cgi-bin 挂主机根是常态)
	}
	for _, c := range cases {
		if got := srunBaseUrl(c.in, "u", "p", ""); got != c.want {
			t.Errorf("srunBaseUrl(%q) = %q, want %q", c.in, got, c.want)
		}
	}
	// 登录地址为空时由门户地址派生 portalbase
	if got := srunBaseUrl("", "u", "p", "http://1.2.3.4:8080/eportal/index.jsp?wlanuserip=10.0.0.1&ac_id=3"); got != "http://1.2.3.4:8080/eportal" {
		t.Errorf("空登录地址应派生 portalbase, got %q", got)
	}
}

// stripJSONP 兼容 jQuery 尾部分号。
func TestStripJSONPSemicolon(t *testing.T) {
	if got := stripJSONP(`jQuery1124_123({"error":"ok"});`); got != `{"error":"ok"}` {
		t.Fatalf("尾部分号未剥离: %s", got)
	}
}

// 登录地址查询串里的 ac_id 覆盖门户派生的 ac_id。
func TestExecutePortalAuthSrunAcidOverride(t *testing.T) {
	var gotAcID string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/cgi-bin/get_challenge" {
			_, _ = w.Write([]byte(`{"error":"ok","challenge":"0123456789abcdef","client_ip":"10.0.0.1"}`))
			return
		}
		vs, _ := url.ParseQuery(r.URL.RawQuery)
		gotAcID = vs.Get("ac_id")
		_, _ = w.Write([]byte(`{"error":"ok"}`))
	}))
	defer srv.Close()

	// 门户地址里 ac_id=1,登录地址 ?ac_id=8 应覆盖
	cfg := PortalAuthConfig{LoginUrl: srv.URL + "?ac_id=8", Method: model.PortalMethodSrun}
	portal := "http://9.9.9.9/eportal/index.jsp?wlanuserip=10.0.0.9&ac_id=1"
	if out := ExecutePortalAuth(cfg, portal, "u1", "p1"); !out.Success {
		t.Fatalf("应成功: %+v", out)
	}
	if gotAcID != "8" {
		t.Fatalf("ac_id 应被登录地址覆盖为 8, got %s", gotAcID)
	}
}
