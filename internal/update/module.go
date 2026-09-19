package update

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/Lexo0522/one-key-dialer/internal/i18n"
	"github.com/Lexo0522/one-key-dialer/internal/model"
	"github.com/Lexo0522/one-key-dialer/internal/platform"
)

// 常量（与旧版 UpdateModule 一一对应）
const (
	MaxChecksumManifestChars = 1024 * 1024
	PartFileMaxAge           = 7 * 24 * time.Hour
	ProgressReportBytes      = 256 * 1024
	CopyBufferBytes          = 64 * 1024
	SanitizeMaxLen           = 180
	BigAssetBytes            = 5_000_000
	WatchdogPollInterval     = 500 * time.Millisecond
	CheckRetrySleep          = time.Second
	WaitForExitLoopCount     = 30
	StagedDirPrefix          = "staged-"
	ApplyScriptName          = "apply_update.bat"
	WritabilityProbeFile     = "ppoe_update_probe.tmp"
)

var sha256SumLine = regexp.MustCompile(`(?i)^([0-9a-f]{64}) {2}([^\r\n]+)$`)

// FailureKind 失败分类（日志"原因="处显示）。
type FailureKind int

const (
	KindConnectTimeout FailureKind = iota
	KindTimeout
	KindStall
	KindNetwork
	KindHTTPStatus
	KindVersionMissing
	KindParse
	KindHashMismatch
	KindCancelled
	KindUnknown
)

// Label 返回中文标签。
func (k FailureKind) Label() string {
	switch k {
	case KindConnectTimeout:
		return "连接超时"
	case KindTimeout:
		return "响应超时"
	case KindStall:
		return "下载停滞超时"
	case KindNetwork:
		return "网络错误"
	case KindHTTPStatus:
		return "HTTP 错误"
	case KindVersionMissing:
		return "版本不存在"
	case KindParse:
		return "响应解析失败"
	case KindHashMismatch:
		return "哈希校验失败"
	case KindCancelled:
		return "已取消"
	}
	return "未知错误"
}

// 错误类型
var (
	ErrCancelled     = errors.New(i18n.T("update.downloadCanceled"))
	ErrNoManifest    = errors.New(i18n.T("update.noManifestReject"))
	ErrMissingAsset  = errors.New(i18n.T("update.missingAsset"))
	ErrNoPackageFile = errors.New(i18n.T("update.noPackageFile"))
	ErrNoScript      = errors.New(i18n.T("update.noScript"))
	ErrAllLineFailed = errors.New(i18n.T("update.allDownloadFail"))
)

// HashMismatchError 哈希校验失败。
type HashMismatchError struct{ Expected, Actual string }

func (e *HashMismatchError) Error() string {
	return i18n.Tf("update.hashMismatch", e.Expected, e.Actual)
}

// StallTimeoutError 下载停滞超时。
type StallTimeoutError struct {
	Seconds int
	Cause   error
}

func (e *StallTimeoutError) Error() string {
	return i18n.Tf("update.stalled", e.Seconds)
}
func (e *StallTimeoutError) Unwrap() error { return e.Cause }

// HTTPStatusError 非 2xx 响应。
type HTTPStatusError struct {
	Code    int
	Message string
}

func (e *HTTPStatusError) Error() string { return e.Message }

// Asset 一个 Release 资产。
type Asset struct {
	Name               string `json:"name"`
	BrowserDownloadURL string `json:"browser_download_url"`
	SizeBytes          int64  `json:"size"`
}

// LowerName 返回小写文件名。
func (a Asset) LowerName() string { return strings.ToLower(a.Name) }

// IsZip / IsMsi / IsExe 判断类型。
func (a Asset) IsZip() bool { return strings.HasSuffix(a.LowerName(), ".zip") }
func (a Asset) IsMsi() bool { return strings.HasSuffix(a.LowerName(), ".msi") }
func (a Asset) IsExe() bool { return strings.HasSuffix(a.LowerName(), ".exe") }

// IsChecksumManifest 判断是否为 SHA256SUMS.txt。
func (a Asset) IsChecksumManifest() bool { return a.LowerName() == "sha256sums.txt" }

// Release 一个发布版本。
type Release struct {
	TagName string  `json:"tag_name"`
	HTMLURL string  `json:"html_url"`
	Body    string  `json:"body"`
	Assets  []Asset `json:"assets"`
}

// IsHTTPSURL 判断 URL 是否为 https 且 host 非空。
func IsHTTPSURL(raw string) bool {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil {
		return false
	}
	return strings.EqualFold(u.Scheme, "https") && u.Host != ""
}

// ScoreAsset 资产打分：名称相关度 + 平台 + 便携标识 - 调试产物 + 体积。
func ScoreAsset(a Asset) int {
	n := a.LowerName()
	s := 0
	if strings.Contains(n, "ppoe") || strings.Contains(n, "pppoe") ||
		strings.Contains(n, "one-key") || strings.Contains(n, "dialer") {
		s += 100
	}
	if strings.Contains(n, "win") || strings.Contains(n, "windows") {
		s += 20
	}
	if strings.Contains(n, "portable") || strings.Contains(n, "app-image") || strings.Contains(n, "appimage") {
		s += 15
	}
	if strings.Contains(n, "debug") || strings.Contains(n, "sources") || strings.Contains(n, "src") {
		s -= 50
	}
	if a.SizeBytes > BigAssetBytes {
		s += 5
	}
	return s
}

// ChecksumManifest 返回 SHA256SUMS.txt 资产；出现多个时视为不可用。
func (r *Release) ChecksumManifest() *Asset {
	var found *Asset
	for i := range r.Assets {
		a := r.Assets[i]
		if a.BrowserDownloadURL == "" || a.Name == "" || !IsHTTPSURL(a.BrowserDownloadURL) {
			continue
		}
		if a.IsChecksumManifest() {
			if found != nil {
				return nil
			}
			found = &r.Assets[i]
		}
	}
	return found
}

// PreferredWindowsAsset 按安装方式选择安装包：
// 便携版（安装目录可写）优先 zip；Program Files 安装版只能用 msi / exe。
func (r *Release) PreferredWindowsAsset(installDirWritable bool) *Asset {
	var bestZip, bestMsi, bestExe *Asset
	var scoreZip, scoreMsi, scoreExe = -1 << 30, -1 << 30, -1 << 30
	for i := range r.Assets {
		a := r.Assets[i]
		if a.BrowserDownloadURL == "" || a.Name == "" || !IsHTTPSURL(a.BrowserDownloadURL) {
			continue
		}
		s := ScoreAsset(a)
		switch {
		case a.IsZip():
			if s > scoreZip {
				scoreZip, bestZip = s, &r.Assets[i]
			}
		case a.IsMsi():
			if s > scoreMsi {
				scoreMsi, bestMsi = s, &r.Assets[i]
			}
		case a.IsExe():
			if s > scoreExe {
				scoreExe, bestExe = s, &r.Assets[i]
			}
		}
	}
	if installDirWritable {
		if bestZip != nil {
			return bestZip
		}
		if bestMsi != nil {
			return bestMsi
		}
		return bestExe
	}
	if bestMsi != nil {
		return bestMsi
	}
	return bestExe
}

// CheckResult 一次检查的结果。
type CheckResult struct {
	UpdateAvailable bool     `json:"updateAvailable"`
	SourceOK        bool     `json:"sourceOk"`
	LatestTag       string   `json:"latestTag"`
	Message         string   `json:"message"`
	ReleaseURL      string   `json:"releaseUrl"`
	SourceID        string   `json:"sourceId"`
	SourceName      string   `json:"sourceName"`
	Release         *Release `json:"release"`

	err error
}

// HasInstallableAsset 是否存在可自动安装的包（含校验清单）。
func HasInstallableAsset(result *CheckResult, installDirWritable bool) bool {
	return result != nil && result.Release != nil &&
		result.Release.PreferredWindowsAsset(installDirWritable) != nil &&
		result.Release.ChecksumManifest() != nil
}

// Progress 下载/准备进度回调。
type Progress interface {
	OnProgress(downloaded, total int64)
	OnStatus(message string)
}

// VerifiedPackage 已通过哈希校验的安装包。
type VerifiedPackage struct {
	File    string
	Asset   *Asset
	Release *Release
}

// PreparedUpdate 已准备好的更新（脚本 + 类型）。
type PreparedUpdate struct {
	ApplyScript string
	Kind        string
}

// Cancel 取消信号。
type Cancel struct{ flag atomic.Bool }

// NewCancel 构造取消信号。
func NewCancel() *Cancel { return &Cancel{} }

// Cancel 触发取消。
func (c *Cancel) Cancel() { c.flag.Store(true) }

// IsCancelled 是否已取消。
func (c *Cancel) IsCancelled() bool { return c.flag.Load() }

// Module 在线更新引擎。
type Module struct {
	updatesDir string
	cfg        *Config
	log        func(message string)
	now        func() int64

	mu      sync.Mutex
	breaker map[string]*breakerState
	client  *http.Client
}

type breakerState struct {
	openUntilMs      int64
	consecutiveFails int
}

// NewModule 构造更新引擎。
func NewModule(updatesDir string, cfg *Config, log func(string)) *Module {
	return &Module{
		updatesDir: updatesDir,
		cfg:        cfg,
		log:        log,
		now:        func() int64 { return time.Now().UnixMilli() },
		breaker:    map[string]*breakerState{},
		client:     newHTTPClient(),
	}
}

func newHTTPClient() *http.Client {
	return &http.Client{
		Transport: &http.Transport{
			Proxy: http.ProxyFromEnvironment,
		},
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			// 禁止 HTTPS → HTTP 降级
			if len(via) > 0 && strings.EqualFold(via[0].URL.Scheme, "https") &&
				!strings.EqualFold(req.URL.Scheme, "https") {
				return errors.New("refusing HTTPS to HTTP downgrade redirect")
			}
			if len(via) >= 20 {
				return errors.New("too many redirects")
			}
			return nil
		},
	}
}

func (m *Module) logf(key string, args ...any) {
	if m.log != nil {
		m.log(i18n.Tf(key, args...))
	}
}

// ---------- 断路器 ----------

func (m *Module) breakerOpen(src *Source) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	st := m.breaker[src.ID]
	return st != nil && st.openUntilMs > m.now()
}

func (m *Module) breakerRemainingMs(src *Source) int64 {
	m.mu.Lock()
	defer m.mu.Unlock()
	st := m.breaker[src.ID]
	if st == nil {
		return 0
	}
	remain := st.openUntilMs - m.now()
	if remain < 0 {
		return 0
	}
	return remain
}

func (m *Module) recordSuccess(src *Source) {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.breaker, src.ID)
}

func (m *Module) recordFailure(src *Source) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	st := m.breaker[src.ID]
	if st == nil {
		st = &breakerState{}
		m.breaker[src.ID] = st
	}
	now := m.now()
	if st.openUntilMs > 0 && now >= st.openUntilMs {
		// 半开探测失败：立刻重新进入完整冷却
		st.openUntilMs = now + src.BreakerCooldownMs
		st.consecutiveFails = 0
		return true
	}
	st.consecutiveFails++
	if st.consecutiveFails >= src.BreakerThreshold {
		st.openUntilMs = now + src.BreakerCooldownMs
		st.consecutiveFails = 0
		return true
	}
	return false
}

// ---------- 检查 ----------

// Check 串行遍历线路链：主线路健康回答"无更新"即为终局答案，不查备用。
func (m *Module) Check(currentVersion string) CheckResult {
	current := currentVersion
	if strings.TrimSpace(current) == "" {
		current = model.Version()
	}
	chain := m.cfg.Enabled()
	if len(chain) == 0 {
		return CheckResult{SourceOK: false, Message: i18n.T("update.noSources")}
	}
	m.logf("update.checkStart", model.Display(), m.cfg.ChainNames())

	var last *CheckResult
	for _, src := range chain {
		if m.breakerOpen(src) {
			m.logf("update.breakerSkip", src.DisplayName, m.breakerRemainingMs(src)/1000)
			continue
		}
		result := m.checkWithRetry(src, current)
		if result.SourceOK {
			m.recordSuccess(src)
			return result
		}
		if m.recordFailure(src) {
			m.logf("update.breakerOpen", src.DisplayName, src.BreakerCooldownMs/1000)
		}
		last = &result
	}
	if last == nil {
		return CheckResult{SourceOK: false, Message: i18n.T("update.allBreaker")}
	}
	return *last
}

func (m *Module) checkWithRetry(src *Source, current string) CheckResult {
	attempts := src.CheckAttempts
	if attempts < 1 {
		attempts = 1
	}
	var last CheckResult
	for attempt := 1; attempt <= attempts; attempt++ {
		start := time.Now()
		result := m.checkOnce(src, current)
		elapsed := time.Since(start).Milliseconds()
		if result.SourceOK {
			m.logf("update.lineOk", src.DisplayName, elapsed, result.LatestTag)
			return result
		}
		m.logf("update.lineFail", src.DisplayName, attempt, attempts, classifyError(result.err).Label(), elapsed)
		last = result
		if attempt < attempts {
			time.Sleep(CheckRetrySleep)
		}
	}
	return last
}

// checkOnce 单次检查（无重试、不碰熔断）。
func (m *Module) checkOnce(src *Source, current string) CheckResult {
	out := CheckResult{SourceID: src.ID, SourceName: src.DisplayName}
	if !IsHTTPSURL(src.API) {
		out.Message = i18n.T("update.badUrl")
		if !strings.HasPrefix(strings.ToLower(src.API), "http") {
			out.Message = i18n.T("update.badUrl")
		} else {
			out.Message = i18n.T("update.needHttps")
		}
		out.err = errors.New(out.Message)
		return out
	}
	body, status, err := m.get(src.API, src.CheckTimeoutMs)
	if err != nil {
		out.err = err
		out.Message = failedCheckErr(src.DisplayName, err)
		return out
	}
	if status == 404 {
		out.err = &HTTPStatusError{Code: 404, Message: i18n.T("update.notFound404")}
		out.Message = failedCheckErr(src.DisplayName, out.err)
		return out
	}
	if status == 403 {
		out.err = &HTTPStatusError{Code: 403, Message: i18n.T("update.http403")}
		out.Message = failedCheckErr(src.DisplayName, out.err)
		return out
	}
	if status < 200 || status >= 300 {
		out.err = &HTTPStatusError{Code: status, Message: i18n.Tf("update.http", status)}
		out.Message = failedCheckErr(src.DisplayName, out.err)
		return out
	}
	if strings.TrimSpace(body) == "" {
		out.err = errors.New(i18n.T("update.emptyResponse"))
		out.Message = failedCheckErr(src.DisplayName, out.err)
		return out
	}
	var rel Release
	if err := json.Unmarshal([]byte(body), &rel); err != nil {
		out.err = err
		out.Message = failedCheckErr(src.DisplayName, err)
		return out
	}
	if strings.TrimSpace(rel.TagName) == "" {
		out.err = errors.New(i18n.T("update.noTag"))
		out.Message = failedCheckErr(src.DisplayName, out.err)
		return out
	}

	out.SourceOK = true
	out.LatestTag = rel.TagName
	out.ReleaseURL = rel.HTMLURL
	out.Release = &rel
	if model.CompareNumeric(current, rel.TagName) >= 0 {
		out.UpdateAvailable = false
		out.Message = i18n.Tf("update.upToDate", model.Display(), src.DisplayName)
		return out
	}
	out.UpdateAvailable = true
	writable := platform.IsDirWritable(platform.InstallDir())
	msg := i18n.Tf("update.found", rel.TagName, model.Display(), src.DisplayName)
	preferred := rel.PreferredWindowsAsset(writable)
	manifest := rel.ChecksumManifest()
	switch {
	case preferred != nil && manifest != nil:
		msg += i18n.Tf("update.canDownload", preferred.Name)
		if !writable {
			msg += i18n.T("update.msiNote")
		}
	case preferred != nil:
		msg += i18n.T("update.noManifest")
	case rel.PreferredWindowsAsset(true) != nil:
		msg += i18n.T("update.cannotApply")
	default:
		msg += i18n.T("update.noMatch")
	}
	out.Message = msg
	return out
}

func failedCheck(name, detail string) string {
	if name != "" {
		return i18n.Tf("update.checkFailedNamed", name, detail)
	}
	return i18n.Tf("update.checkFailed", detail)
}

func failedCheckErr(name string, err error) string {
	detail := err.Error()
	if strings.TrimSpace(detail) == "" {
		detail = fmt.Sprintf("%T", err)
	}
	return failedCheck(name, detail)
}

// ---------- HTTP ----------

func (m *Module) get(rawURL string, timeoutMs int) (string, int, error) {
	timeout := time.Duration(timeoutMs) * time.Millisecond
	if timeout < time.Second {
		timeout = time.Second
	}
	// 硬超时覆盖 DNS + 连接 + TLS + 响应
	hard := 30 * time.Second
	if timeout+10*time.Second > hard {
		hard = timeout + 10*time.Second
	}
	ctx, cancel := context.WithTimeout(context.Background(), hard)
	defer cancel()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return "", 0, err
	}
	req.Header.Set("User-Agent", model.UserAgent())
	resp, err := m.client.Do(req)
	if err != nil {
		if ctx.Err() != nil {
			return "", 0, errors.New(i18n.T("update.hardTimeout"))
		}
		return "", 0, err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, MaxChecksumManifestChars+4096))
	if err != nil {
		return "", resp.StatusCode, err
	}
	return string(data), resp.StatusCode, nil
}
