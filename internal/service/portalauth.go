package service

import (
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/Lexo0522/one-key-dialer/internal/i18n"
	"github.com/Lexo0522/one-key-dialer/internal/model"
	"github.com/Lexo0522/one-key-dialer/internal/proxy"
)

// ============================ 认证请求构造 ============================

// PortalAuthConfig 门户认证请求描述（由设置归一而来，不含凭据）。
type PortalAuthConfig struct {
	LoginUrl    string      // 登录地址，支持 {portal} 占位符
	Method      string      // GET / POST
	Body        string      // 请求体模板
	Headers     [][2]string // 附加请求头
	SuccessHint string      // 响应包含该字符串视为成功（可空）
	Proxy       model.ProxyConfig
}

// PortalAuthOutcome 一次门户认证请求的执行结果。
type PortalAuthOutcome struct {
	Success bool
	// Status HTTP 响应状态码;0 表示请求本身失败。
	Status int
	// Detail 技术明细（状态码/响应片段），供日志与测试回显；不含密码。
	Detail string
}

// portalTemplateRepl 的占位符集合：
//   - {username} / {password}: 原样插入
//   - {username:enc} / {password:enc}: URL 转义后插入（表单/查询参数用）
//   - {portal}: 检测到的门户地址（本身是 URL,原样插入）
//
// 注意 Replacer 按"目标串中出现顺序"匹配,同一位置的候选按参数顺序取先命中者,
// 因此 :enc 变体必须排在裸占位符之前。
func RenderPortalTemplate(tpl, username, password, portalURL string) string {
	r := strings.NewReplacer(
		"{username:enc}", url.QueryEscape(username),
		"{password:enc}", url.QueryEscape(password),
		"{username}", username,
		"{password}", password,
		"{portal}", portalURL,
	)
	return r.Replace(tpl)
}

// ParsePortalHeaders 解析设置里的请求头文本（每行一个 "Key: Value"）。
func ParsePortalHeaders(text string) [][2]string {
	out := [][2]string{}
	for _, line := range strings.Split(text, "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		k, v, ok := strings.Cut(line, ":")
		if !ok {
			continue
		}
		k = strings.TrimSpace(k)
		v = strings.TrimSpace(v)
		if k == "" {
			continue
		}
		out = append(out, [2]string{k, v})
	}
	return out
}

// ExecutePortalAuth 执行一次门户认证请求。
// loginUrl/body 先做模板替换;响应判定:2xx/3xx 且（未配置提示词或包含提示词）。
func ExecutePortalAuth(cfg PortalAuthConfig, portalURL, username, password string) PortalAuthOutcome {
	fail := func(detail string) PortalAuthOutcome {
		return PortalAuthOutcome{Success: false, Detail: detail}
	}
	loginUrl := strings.TrimSpace(cfg.LoginUrl)
	if loginUrl == "" {
		// 未配置登录地址:退化为直接 GET 门户页(部分门户访问即触发)
		if strings.TrimSpace(portalURL) == "" {
			return fail(i18n.T("portal.noLoginUrl"))
		}
		loginUrl = portalURL
	}
	loginUrl = RenderPortalTemplate(loginUrl, username, password, portalURL)
	if _, err := url.Parse(loginUrl); err != nil {
		return fail("bad login url")
	}

	method := model.NormalizePortalMethod(cfg.Method)
	body := ""
	if method == model.PortalMethodPost {
		body = RenderPortalTemplate(cfg.Body, username, password, portalURL)
	}
	req, err := http.NewRequest(method, loginUrl, strings.NewReader(body))
	if err != nil {
		return fail("bad request: " + err.Error())
	}
	req.Header.Set("User-Agent", model.UserAgent())
	hasContentType := false
	for _, h := range cfg.Headers {
		if strings.EqualFold(h[0], "Content-Type") {
			hasContentType = true
		}
		req.Header.Set(h[0], h[1])
	}
	if method == model.PortalMethodPost && body != "" && !hasContentType {
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	}

	client := &http.Client{Transport: proxy.TransportFor(cfg.Proxy)}
	client.Timeout = 8 * time.Second
	resp, err := client.Do(req)
	if err != nil {
		return fail(err.Error())
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
	snippet := firstLine(string(raw), 240)

	outcome := PortalAuthOutcome{Status: resp.StatusCode}
	outcome.Detail = resp.Status
	if snippet != "" {
		outcome.Detail += " | " + snippet
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 400 {
		return outcome
	}
	if hint := strings.TrimSpace(cfg.SuccessHint); hint != "" {
		outcome.Success = strings.Contains(string(raw), hint)
		return outcome
	}
	// 未配置提示词:2xx 即认为已提交,由调用方复验门户是否消失
	outcome.Success = true
	return outcome
}

// firstLine 返回文本的首个非空行,超长截断。
func firstLine(s string, limit int) string {
	s = strings.TrimSpace(strings.ReplaceAll(s, "\r", ""))
	if idx := strings.IndexAny(s, "\n"); idx >= 0 {
		s = s[:idx]
	}
	if len(s) > limit {
		s = s[:limit]
	}
	return s
}

// ============================ 自动认证循环 ============================

// PortalAuthService 周期性门户检测 + 自动认证。
// 基准间隔 10 秒;认证失败按指数退避（复用自动重连的退避曲线）,
// 避免错误凭据高频冲击认证服务器。
type PortalAuthService struct {
	isBusy      func() bool
	detect      func() PortalDetect
	performAuth func(portalURL string) PortalAuthOutcome
	logger      *LogService

	mu            sync.Mutex
	cancel        chan struct{}
	wg            sync.WaitGroup
	enabled       bool
	failStreak    int
	portalLatched bool
}

// NewPortalAuthService 构造门户自动认证服务。
func NewPortalAuthService(isBusy func() bool, detect func() PortalDetect,
	performAuth func(portalURL string) PortalAuthOutcome, logger *LogService) *PortalAuthService {
	return &PortalAuthService{isBusy: isBusy, detect: detect, performAuth: performAuth, logger: logger}
}

// portalBaseIntervalSeconds 检测基准间隔。
const portalBaseIntervalSeconds = 10

// IsRunning 是否正在运行。
func (s *PortalAuthService) IsRunning() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.enabled
}

// Start 启动检测循环。
func (s *PortalAuthService) Start() {
	s.mu.Lock()
	if s.enabled {
		s.mu.Unlock()
		return
	}
	s.stopLocked()
	s.failStreak = 0
	s.portalLatched = false
	s.enabled = true
	s.cancel = make(chan struct{})
	cancel := s.cancel
	s.mu.Unlock()

	s.logger.Info(i18n.T("portal.authStart"))
	s.wg.Add(1)
	go func() {
		defer s.wg.Done()
		delay := 2 * time.Second
		for {
			select {
			case <-cancel:
				return
			case <-time.After(delay):
			}
			cont, next := s.tick(cancel)
			if !cont {
				return
			}
			delay = next
		}
	}()
}

// Stop 停止检测循环（不阻塞）。
func (s *PortalAuthService) Stop() {
	s.mu.Lock()
	was := s.enabled
	s.stopLocked()
	s.mu.Unlock()
	if was {
		s.logger.Info(i18n.T("portal.authStop"))
	}
}

func (s *PortalAuthService) stopLocked() {
	if s.cancel != nil {
		close(s.cancel)
		s.cancel = nil
	}
	s.enabled = false
}

// nextDelay 依据连续失败次数计算下次检测延迟(秒)。
func (s *PortalAuthService) nextDelay() time.Duration {
	secs := RetryDelaySeconds(s.failStreak, portalBaseIntervalSeconds)
	return time.Duration(secs) * time.Second
}

// tick 执行一轮检测;返回 (是否继续, 下次延迟)。
func (s *PortalAuthService) tick(cancel chan struct{}) (bool, time.Duration) {
	s.mu.Lock()
	if !s.enabled {
		s.mu.Unlock()
		return false, 0
	}
	s.mu.Unlock()

	d := s.detect()
	if !d.Portal {
		s.mu.Lock()
		s.failStreak = 0
		s.portalLatched = false
		s.mu.Unlock()
		return true, time.Duration(portalBaseIntervalSeconds) * time.Second
	}
	if s.isBusy() {
		// 拨号流程进行中,等下一轮
		return true, time.Duration(portalBaseIntervalSeconds) * time.Second
	}

	s.mu.Lock()
	first := !s.portalLatched
	s.portalLatched = true
	s.mu.Unlock()
	if first {
		s.logger.Warning(i18n.Tf("portal.detected", d.PortalURL))
	}

	out := s.performAuth(d.PortalURL)
	if out.Success {
		s.mu.Lock()
		s.failStreak = 0
		s.mu.Unlock()
		s.logger.Success(i18n.T("portal.authOk"))
		return true, time.Duration(portalBaseIntervalSeconds) * time.Second
	}
	s.mu.Lock()
	s.failStreak++
	streak := s.failStreak
	s.mu.Unlock()
	if streak > 1 {
		s.logger.Warning(i18n.Tf("portal.streak", streak))
	}
	s.logger.Error(i18n.Tf("portal.authFailed", out.Detail))
	return true, s.nextDelay()
}
