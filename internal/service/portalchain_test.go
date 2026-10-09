package service

import (
	"crypto/hmac"
	"crypto/md5"
	"crypto/sha1"
	"encoding/hex"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/Lexo0522/one-key-dialer/internal/i18n"
	"github.com/Lexo0522/one-key-dialer/internal/model"
)

// ============================ 假门户（链路测试夹具） ============================

// fakePortal 一台可编排的校园门户：未认证时把 generate_204 探测 302 到认证页，
// 认证成功后放行（204）。用来把「探测 → 认证 → 复验」整条链路放到真实 HTTP
// 服务上跑通，而不是只测单个函数。
type fakePortal struct {
	mu        sync.Mutex
	authed    bool
	authHits  int
	gotForm   url.Values
	portalURL string // 探测 302 的目标（认证页地址，含 wlanuserip/ac_id 查询串）
}

func (f *fakePortal) handler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/generate_204":
			f.mu.Lock()
			ok := f.authed
			f.mu.Unlock()
			if ok {
				w.WriteHeader(http.StatusNoContent)
				return
			}
			http.Redirect(w, r, f.portalURL, http.StatusFound)
		case "/portal/auth":
			if err := r.ParseForm(); err != nil {
				w.WriteHeader(http.StatusBadRequest)
				return
			}
			f.mu.Lock()
			f.authHits++
			f.gotForm = r.PostForm
			match := r.PostForm.Get("user") == "20210001" && r.PostForm.Get("pass") == "p@ss&w"
			if match {
				f.authed = true
			}
			f.mu.Unlock()
			w.WriteHeader(http.StatusOK)
			if match {
				_, _ = io.WriteString(w, `{"error":"ok"}`)
			} else {
				_, _ = io.WriteString(w, `{"error":"password_wrong"}`)
			}
		default:
			http.NotFound(w, r)
		}
	}
}

func (f *fakePortal) stats() (int, url.Values) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.authHits, f.gotForm
}

// fakeSrun 一台按公开协议校验参数的深澜门户：挑战 + 登录两步都由服务端
// 独立重算 hmd5/chksum，任一环节算错就直接判失败。
type fakeSrun struct {
	mu             sync.Mutex
	challengeQuery url.Values
	loginQuery     url.Values
	loginErr       string
}

func (f *fakeSrun) handler() http.HandlerFunc {
	const token = "0123456789abcdef"
	return func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/cgi-bin/get_challenge":
			f.mu.Lock()
			f.challengeQuery = r.URL.Query()
			f.mu.Unlock()
			_, _ = io.WriteString(w,
				`{"error":"ok","ecode":0,"challenge":"`+token+`","client_ip":"10.0.0.5"}`)
		case "/cgi-bin/srun_portal":
			q := r.URL.Query()
			f.mu.Lock()
			f.loginQuery = q
			f.loginErr = ""
			f.mu.Unlock()

			// 服务端独立重算，而不是复用被测代码里的函数。
			mac := hmac.New(md5.New, []byte(token))
			mac.Write([]byte("Passw0rd123"))
			wantHmd5 := hex.EncodeToString(mac.Sum(nil))
			bad := ""
			if q.Get("password") != "{MD5}"+wantHmd5 {
				bad = "hmd5 与服务端重算值不符"
			}
			raw := token + "20210001" + token + wantHmd5 + token + q.Get("ac_id") +
				token + q.Get("ip") + token + "200" + token + "1" + token + q.Get("info")
			sum := sha1.Sum([]byte(raw))
			if q.Get("chksum") != hex.EncodeToString(sum[:]) {
				bad = "chksum 与服务端重算值不符"
			}
			if !strings.HasPrefix(q.Get("info"), "{SRBX1}") {
				bad = "info 缺少 {SRBX1} 前缀"
			}
			f.mu.Lock()
			f.loginErr = bad
			f.mu.Unlock()
			if bad != "" {
				_, _ = io.WriteString(w, `{"error":"login_failed","error_msg":"`+bad+`"}`)
				return
			}
			_, _ = io.WriteString(w, `{"error":"ok","ecode":0,"echo_msg":"login ok"}`)
		default:
			http.NotFound(w, r)
		}
	}
}

func (f *fakeSrun) queries() (url.Values, url.Values, string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.challengeQuery, f.loginQuery, f.loginErr
}

// ============================ 端到端链路 ============================

// 完整跑一遍：302 探测 → 模板渲染提交 → 复验放行。
func TestPortalChainDetectAuthVerify(t *testing.T) {
	fp := &fakePortal{}
	srv := httptest.NewServer(fp.handler())
	defer srv.Close()
	fp.portalURL = srv.URL + "/portal/login?wlanuserip=10.0.0.5&ac_id=1"

	cfg := portalProbeCfg(srv.URL + "/generate_204")

	// 第一段：未认证时探测必须抓到门户地址。
	d := DetectPortal(cfg)
	if d.Error != "" || !d.Portal {
		t.Fatalf("应探测到门户: %+v", d)
	}
	if d.PortalURL != fp.portalURL {
		t.Fatalf("门户地址不符:\n got %s\nwant %s", d.PortalURL, fp.portalURL)
	}

	// 第二段：模板占位符在真实服务器上渲染并提交。
	authCfg := PortalAuthConfig{
		LoginUrl:    "{portalbase}auth",
		Method:      model.PortalMethodPost,
		Body:        "user={username:enc}&pass={password:enc}&queryString={queryenc}",
		SuccessHint: `"error":"ok"`,
	}
	out := ExecutePortalAuth(authCfg, d.PortalURL, "20210001", "p@ss&w")
	if !out.Success {
		t.Fatalf("认证应成功: %+v", out)
	}
	if !out.Verified {
		t.Fatalf("命中成功提示词应视为已验证: %+v", out)
	}
	hits, form := fp.stats()
	if hits != 1 {
		t.Fatalf("认证请求应只发一次, got %d", hits)
	}
	if form.Get("user") != "20210001" {
		t.Fatalf("username 未按模板提交: %q", form.Get("user"))
	}
	if form.Get("pass") != "p@ss&w" {
		t.Fatalf("password 未按 :enc 变体提交: %q", form.Get("pass"))
	}
	if q, err := url.ParseQuery(form.Get("queryString")); err != nil || q.Get("wlanuserip") != "10.0.0.5" {
		t.Fatalf("queryString 未带上门户查询串: %q", form.Get("queryString"))
	}

	// 第三段：复验——门户已放行。
	d2 := DetectPortal(cfg)
	if d2.Error != "" || d2.Portal {
		t.Fatalf("认证后复验应放行: %+v", d2)
	}
}

// 凭据错误时：提交被判失败，且门户仍然拦着（复验不能放行）。
func TestPortalChainWrongCredsStayBehindPortal(t *testing.T) {
	fp := &fakePortal{}
	srv := httptest.NewServer(fp.handler())
	defer srv.Close()
	fp.portalURL = srv.URL + "/portal/login?wlanuserip=10.0.0.5"
	cfg := portalProbeCfg(srv.URL + "/generate_204")

	d := DetectPortal(cfg)
	if !d.Portal {
		t.Fatalf("应探测到门户: %+v", d)
	}
	out := ExecutePortalAuth(PortalAuthConfig{
		LoginUrl:    "{portalbase}auth",
		Method:      model.PortalMethodPost,
		Body:        "user={username:enc}&pass={password:enc}",
		SuccessHint: `"error":"ok"`,
	}, d.PortalURL, "20210001", "wrong")
	if out.Success {
		t.Fatalf("提示词未命中等价于失败: %+v", out)
	}
	if d2 := DetectPortal(cfg); !d2.Portal || d2.Error != "" {
		t.Fatalf("凭据错误时门户应仍在: %+v", d2)
	}
}

// 200 劫持页链路：探测地址本身不是门户，必须从劫持页里提取真正的门户地址，
// 再走深澜两步协议完成认证。
func TestPortalChainHijackPageToSrun(t *testing.T) {
	srun := &fakeSrun{}
	portalSrv := httptest.NewServer(srun.handler())
	defer portalSrv.Close()

	probe := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = io.WriteString(w, `<html><script>window.location="`+portalSrv.URL+
			`/portal/login?wlanuserip=10.0.0.5&ac_id=1";</script></html>`)
	}))
	defer probe.Close()

	cfg := portalProbeCfg(probe.URL + "/generate_204")
	d := DetectPortal(cfg)
	if d.Error != "" || !d.Portal {
		t.Fatalf("generate_204 返回 200 劫持页应判门户: %+v", d)
	}
	wantPortal := portalSrv.URL + "/portal/login?wlanuserip=10.0.0.5&ac_id=1"
	if d.PortalURL != wantPortal {
		t.Fatalf("应从劫持页提取门户地址:\n got %s\nwant %s", d.PortalURL, wantPortal)
	}

	// 预设填法：登录地址写 {portalbase}，srunBaseUrl 会归一到主机根。
	out := ExecutePortalAuth(PortalAuthConfig{
		LoginUrl: "{portalbase}",
		Method:   model.PortalMethodSrun,
	}, d.PortalURL, "20210001", "Passw0rd123")
	if !out.Success {
		t.Fatalf("深澜两步认证应成功: %+v", out)
	}
	if !out.Verified {
		t.Fatalf("协议级 error=ok 应视为已验证: %+v", out)
	}
	cq, lq, loginErr := srun.queries()
	if loginErr != "" {
		t.Fatalf("服务端校验未通过: %s", loginErr)
	}
	if cq.Get("username") != "20210001" || cq.Get("ip") != "10.0.0.5" {
		t.Fatalf("get_challenge 参数不符: %v", cq)
	}
	if lq.Get("ac_id") != "1" || lq.Get("ip") != "10.0.0.5" || lq.Get("action") != "login" {
		t.Fatalf("srun_portal 参数不符: %v", lq)
	}
}

// 未配置成功提示词时，2xx 只代表"已提交"：Outcome 必须标记为未验证，
// 否则自动认证循环会把门户仍在的状态日志成"认证成功"并重置退避。
func TestExecutePortalAuthNoHintIsNotVerified(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// 门户登录页：HTTP 200，但人仍然没认证上。
		_, _ = io.WriteString(w, "<html>please login</html>")
	}))
	defer srv.Close()

	out := ExecutePortalAuth(PortalAuthConfig{
		LoginUrl: srv.URL + "/login",
		Method:   model.PortalMethodGet,
	}, "", "u", "p")
	if !out.Success {
		t.Fatalf("2xx 且无提示词时应判已提交: %+v", out)
	}
	if out.Verified {
		t.Fatalf("未配提示词不得视为已验证: %+v", out)
	}

	hinted := ExecutePortalAuth(PortalAuthConfig{
		LoginUrl:    srv.URL + "/login",
		Method:      model.PortalMethodGet,
		SuccessHint: "please login",
	}, "", "u", "p")
	if !hinted.Success || !hinted.Verified {
		t.Fatalf("命中提示词应视为已验证: %+v", hinted)
	}
}

// ============================ 自动认证循环 ============================

// 门户认证失败时常把表单值回显在响应页里，Detail 会进日志与 UI：
// 密码原文必须在 Detail 里就被抹掉（日志层的脱敏只认 password=xxx 形态）。
func TestExecutePortalAuthMasksPasswordInDetail(t *testing.T) {
	const secret = "Sup3rSecret!"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		// 失败页把提交值原样回显（HTML 形态，ScrubLogLine 认不出来）。
		_, _ = io.WriteString(w, `<html><input name="userId" value="20210001">`+
			`<input name="passwd" value="`+secret+`">密码错误</html>`)
	}))
	defer srv.Close()

	out := ExecutePortalAuth(PortalAuthConfig{
		LoginUrl: srv.URL + "/login",
		Method:   model.PortalMethodGet,
	}, "", "20210001", secret)
	if out.Verified {
		t.Fatalf("未配提示词只算已提交, 不得视为已验证: %+v", out)
	}
	if strings.Contains(out.Detail, secret) {
		t.Fatalf("Detail 里泄漏了密码原文: %s", out.Detail)
	}
	if !strings.Contains(out.Detail, "***") {
		t.Fatalf("Detail 应带脱敏标记: %s", out.Detail)
	}

}

// 深澜路径的泄露面在 error_msg：服务端把失败原因连密码一起回显。
func TestExecutePortalAuthSrunMasksPasswordInDetail(t *testing.T) {
	const secret = "Sup3rSecret!"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/cgi-bin/get_challenge":
			_, _ = io.WriteString(w, `{"error":"ok","challenge":"0123456789abcdef","client_ip":"10.0.0.5"}`)
		case "/cgi-bin/srun_portal":
			_, _ = io.WriteString(w, `{"error":"password_wrong","error_msg":"bad password `+secret+`"}`)
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	out := ExecutePortalAuth(PortalAuthConfig{
		LoginUrl: srv.URL,
		Method:   model.PortalMethodSrun,
	}, srv.URL+"/portal/login?wlanuserip=10.0.0.5", "20210001", secret)
	if out.Success {
		t.Fatalf("密码错误应判失败: %+v", out)
	}
	if strings.Contains(out.Detail, secret) {
		t.Fatalf("srun Detail 里泄漏了密码原文: %s", out.Detail)
	}
	if !strings.Contains(out.Detail, "***") {
		t.Fatalf("srun Detail 应带脱敏标记: %s", out.Detail)
	}
}

// arm 打开 enabled 开关，让 tick 可以按单轮驱动（不起 goroutine）。
func arm(s *PortalAuthService) {
	s.mu.Lock()
	s.enabled = true
	s.mu.Unlock()
}

func newLoopSvc(t *testing.T) (*PortalAuthService, *LogService) {
	t.Helper()
	log := NewLogService(filepath.Join(t.TempDir(), "portal.log"))
	svc := &PortalAuthService{logger: log, isBusy: func() bool { return false }}
	return svc, log
}

func logHas(log *LogService, want string) bool {
	for _, l := range log.Snapshot() {
		if strings.Contains(l.Message, want) {
			return true
		}
	}
	return false
}

func portalDetect(url string) PortalDetect {
	return PortalDetect{Portal: true, PortalURL: url}
}

// 门户 + 认证成功：放行日志、基准间隔、失败计数清零。
func TestPortalAuthServiceTickSuccess(t *testing.T) {
	svc, log := newLoopSvc(t)
	var gotURL string
	svc.detect = func() PortalDetect { return portalDetect("http://10.1.1.55/eportal/index.jsp?ac_id=1") }
	svc.performAuth = func(u string) PortalAuthOutcome {
		gotURL = u
		return PortalAuthOutcome{Success: true, Status: 200, Verified: true}
	}
	arm(svc)

	cont, delay := svc.tick(make(chan struct{}))
	if !cont {
		t.Fatal("门户仍在时应继续循环")
	}
	if delay != time.Duration(portalBaseIntervalSeconds)*time.Second {
		t.Fatalf("成功后应回到基准间隔, got %v", delay)
	}
	if gotURL != "http://10.1.1.55/eportal/index.jsp?ac_id=1" {
		t.Fatalf("认证地址应为探测到的门户地址, got %q", gotURL)
	}
	if !logHas(log, i18n.T("portal.authOk")) {
		t.Fatalf("应记录认证成功日志: %v", log.Snapshot())
	}
}

// 连续失败必须按退避曲线拉长间隔并封顶，避免错误凭据高频冲击门户。
func TestPortalAuthServiceTickBackoff(t *testing.T) {
	svc, _ := newLoopSvc(t)
	svc.detect = func() PortalDetect { return portalDetect("http://10.1.1.55/x") }
	svc.performAuth = func(string) PortalAuthOutcome {
		return PortalAuthOutcome{Detail: "password_wrong"}
	}
	arm(svc)

	var got []time.Duration
	for i := 0; i < 7; i++ {
		_, delay := svc.tick(make(chan struct{}))
		got = append(got, delay)
	}
	want := []time.Duration{10, 20, 40, 80, 100, 100, 100}
	for i := range want {
		if got[i] != want[i]*time.Second {
			t.Fatalf("第 %d 次失败后的间隔 = %v, want %v (全部: %v)", i+1, got[i], want[i]*time.Second, got)
		}
	}
}

// 探测请求失败 ≠ 无门户：不能清失败计数、不能记"放行"，只按退避等下一轮。
func TestPortalAuthServiceTickProbeErrorKeepsStreak(t *testing.T) {
	svc, log := newLoopSvc(t)
	svc.detect = func() PortalDetect {
		return PortalDetect{Error: "dial tcp 223.5.5.5:53: i/o timeout", Detail: "timeout"}
	}
	authCalls := 0
	svc.performAuth = func(string) PortalAuthOutcome {
		authCalls++
		return PortalAuthOutcome{Success: true, Verified: true}
	}
	arm(svc)

	// 探测失败走的是退避曲线（下限 5s），不是"无门户"的基准 10s。
	if _, delay := svc.tick(make(chan struct{})); delay != 5*time.Second {
		t.Fatalf("探测失败的间隔应为退避曲线起点 5s, got %v", delay)
	}
	if authCalls != 0 {
		t.Fatalf("探测失败不应发起认证, got %d 次", authCalls)
	}
	if logHas(log, i18n.T("portal.authOk")) {
		t.Fatal("探测失败不得记录认证成功")
	}

	// 先失败一次把计数推到 1，再插入一轮探测失败：计数必须保留
	// （探测失败轮保持当前退避 10s，下一轮失败应升到 20s）。
	svc.detect = func() PortalDetect { return portalDetect("http://10.1.1.55/x") }
	svc.performAuth = func(string) PortalAuthOutcome { return PortalAuthOutcome{Detail: "boom"} }
	if _, delay := svc.tick(make(chan struct{})); delay != 10*time.Second {
		t.Fatalf("首次失败间隔应为 10s, got %v", delay)
	}
	svc.detect = func() PortalDetect { return PortalDetect{Error: "timeout"} }
	if _, delay := svc.tick(make(chan struct{})); delay != 10*time.Second {
		t.Fatalf("探测失败轮应保持当前退避 10s, got %v", delay)
	}
	svc.detect = func() PortalDetect { return portalDetect("http://10.1.1.55/x") }
	if _, delay := svc.tick(make(chan struct{})); delay != 20*time.Second {
		t.Fatalf("探测失败不清计数, 第二次失败间隔应为 20s, got %v", delay)
	}
}

// 拨号流程进行中（busy）时跳过本轮认证，但保持基准间隔继续探测。
func TestPortalAuthServiceTickBusySkips(t *testing.T) {
	svc, log := newLoopSvc(t)
	svc.detect = func() PortalDetect { return portalDetect("http://10.1.1.55/x") }
	svc.isBusy = func() bool { return true }
	authCalls := 0
	svc.performAuth = func(string) PortalAuthOutcome {
		authCalls++
		return PortalAuthOutcome{Success: true, Verified: true}
	}
	arm(svc)

	if _, delay := svc.tick(make(chan struct{})); delay != 10*time.Second {
		t.Fatalf("busy 时间隔应为基准 10s, got %v", delay)
	}
	if authCalls != 0 {
		t.Fatalf("busy 时不应发起认证, got %d 次", authCalls)
	}
	if logHas(log, i18n.T("portal.authOk")) {
		t.Fatal("busy 时不应记录认证成功日志")
	}
}

// 无门户时清零失败计数与门锁状态，下一轮失败从 5 秒退避重新开始。
func TestPortalAuthServiceTickNoPortalResets(t *testing.T) {
	svc, _ := newLoopSvc(t)
	svc.detect = func() PortalDetect { return portalDetect("http://10.1.1.55/x") }
	svc.performAuth = func(string) PortalAuthOutcome { return PortalAuthOutcome{Detail: "boom"} }
	arm(svc)

	if _, delay := svc.tick(make(chan struct{})); delay != 10*time.Second {
		t.Fatalf("首次失败间隔应为 10s, got %v", delay)
	}
	svc.detect = func() PortalDetect { return PortalDetect{} }
	if _, delay := svc.tick(make(chan struct{})); delay != 10*time.Second {
		t.Fatalf("无门户时间隔应为基准 10s, got %v", delay)
	}
	svc.detect = func() PortalDetect { return portalDetect("http://10.1.1.55/x") }
	if _, delay := svc.tick(make(chan struct{})); delay != 10*time.Second {
		t.Fatalf("无门户后计数已清零, 失败间隔应回到 10s, got %v", delay)
	}
}

// 提示词没配时 2xx 只是"已提交"：循环必须复验门户真的消失才记成功，
// 门户仍在要按失败计（否则永远显示"认证成功"且退避被重置）。
func TestPortalAuthServiceTickReverifiesUnverifiedSuccess(t *testing.T) {
	svc, log := newLoopSvc(t)
	svc.detect = func() PortalDetect { return portalDetect("http://10.1.1.55/x") }
	svc.performAuth = func(string) PortalAuthOutcome {
		return PortalAuthOutcome{Success: true, Status: 200}
	}
	arm(svc)

	_, delay := svc.tick(make(chan struct{}))
	if logHas(log, i18n.T("portal.authOk")) {
		t.Fatal("未复验不得记录认证成功")
	}
	if delay != 10*time.Second {
		t.Fatalf("门户仍在应按失败退避 10s, got %v", delay)
	}
	if !logHas(log, i18n.Tf("portal.authFailed", "")) {
		t.Fatalf("应记录认证失败日志: %v", log.Snapshot())
	}

	// 复验探测本身失败：结论未知，不谎报成功也不计失败。
	// 首次探测仍看到门户，随后的复验探测才失败——两轮必须区分开。
	detectCalls := 0
	svc.detect = func() PortalDetect {
		detectCalls++
		if detectCalls == 1 {
			return portalDetect("http://10.1.1.55/x")
		}
		return PortalDetect{Error: "timeout"}
	}
	svc.performAuth = func(string) PortalAuthOutcome {
		return PortalAuthOutcome{Success: true, Status: 200}
	}
	if _, delay := svc.tick(make(chan struct{})); delay != 10*time.Second {
		t.Fatalf("复验失败应保持基准间隔, got %v", delay)
	}
	if logHas(log, i18n.T("portal.authOk")) {
		t.Fatal("复验失败不得记录认证成功")
	}
	if !logHas(log, i18n.Tf("portal.verifyFailed", "")) {
		t.Fatalf("应记录复验失败日志: %v", log.Snapshot())
	}
}

// Start/Stop 生命周期：起来后按 2 秒首延时跑轮，Stop 后彻底停。
func TestPortalAuthServiceStartStopLifecycle(t *testing.T) {
	svc, _ := newLoopSvc(t)
	var calls int64
	svc.detect = func() PortalDetect { return portalDetect("http://10.1.1.55/x") }
	svc.performAuth = func(string) PortalAuthOutcome {
		atomic.AddInt64(&calls, 1)
		return PortalAuthOutcome{Success: true, Verified: true}
	}

	svc.Start()
	if !svc.IsRunning() {
		t.Fatal("Start 后应处于运行态")
	}
	deadline := time.Now().Add(5 * time.Second)
	for atomic.LoadInt64(&calls) == 0 && time.Now().Before(deadline) {
		time.Sleep(50 * time.Millisecond)
	}
	if atomic.LoadInt64(&calls) == 0 {
		t.Fatal("启动后应在首延时内跑至少一轮")
	}

	svc.Stop()
	if svc.IsRunning() {
		t.Fatal("Stop 后应退出运行态")
	}
	stopped := atomic.LoadInt64(&calls)
	time.Sleep(1200 * time.Millisecond)
	if got := atomic.LoadInt64(&calls); got != stopped {
		t.Fatalf("Stop 后仍在跑轮: %d -> %d", stopped, got)
	}

	// 重复 Start 不叠加 goroutine。
	svc.Start()
	svc.Start()
	time.Sleep(2500 * time.Millisecond)
	svc.Stop()
	if got := atomic.LoadInt64(&calls) - stopped; got > 2 {
		t.Fatalf("重复 Start 似乎叠加了循环, 多跑了 %d 轮", got)
	}
}
