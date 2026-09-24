package update

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync/atomic"
	"time"

	"github.com/Lexo0522/one-key-dialer/internal/i18n"
	"github.com/Lexo0522/one-key-dialer/internal/model"
	"github.com/Lexo0522/one-key-dialer/internal/platform"
)

var sanitizePattern = regexp.MustCompile(`[\\/:*?"<>|]`)

// SanitizeFileName 清理非法文件名字符并限长。
func SanitizeFileName(name string) string {
	if strings.TrimSpace(name) == "" {
		return "update.bin"
	}
	n := sanitizePattern.ReplaceAllString(name, "_")
	if len(n) > SanitizeMaxLen {
		n = n[:SanitizeMaxLen]
	}
	return n
}

// ExpectedSha256 从 SHA256SUMS.txt 内容中取指定文件的哈希。
// 任一行格式不符、重复或缺失都返回错误（严格校验）。
func ExpectedSha256(manifest, assetName string) (string, error) {
	if strings.TrimSpace(manifest) == "" || assetName == "" {
		return "", errors.New(i18n.T("update.manifestNoTarget"))
	}
	var expected string
	for _, raw := range strings.Split(manifest, "\n") {
		line := strings.TrimRight(raw, "\r")
		if strings.TrimSpace(line) == "" {
			continue
		}
		m := sha256SumLine.FindStringSubmatch(line)
		if m == nil {
			return "", errors.New(i18n.T("update.manifestFormat"))
		}
		if m[2] == assetName {
			if expected != "" {
				return "", errors.New(i18n.T("update.manifestDup"))
			}
			expected = strings.ToLower(m[1])
		}
	}
	if expected == "" {
		return "", errors.New(i18n.Tf("update.manifestMissing", assetName))
	}
	return expected, nil
}

// DownloadWithFailover 串行尝试各线路下载；主线路失败才降级。
// 取消不降级、不记熔断。
func (m *Module) DownloadWithFailover(result CheckResult, progress Progress, cancel *Cancel) (*VerifiedPackage, error) {
	if result.Release == nil {
		return nil, ErrMissingAsset
	}
	writable := platform.IsDirWritable(platform.InstallDir())
	asset := result.Release.PreferredWindowsAsset(writable)
	if asset == nil {
		return nil, ErrMissingAsset
	}
	chain := m.orderedChain(result.SourceID)
	current := model.Version()

	var lastErr error
	for _, src := range chain {
		release := result.Release
		target := asset
		if src.ID != result.SourceID {
			if cancel != nil && cancel.IsCancelled() {
				return nil, ErrCancelled
			}
			m.logf("update.switchLine", src.DisplayName)
			if m.breakerOpen(src) {
				m.logf("update.breakerSkip", src.DisplayName, m.breakerRemainingMs(src)/1000)
				continue
			}
			alt := m.checkWithRetry(src, current)
			if !alt.SourceOK {
				m.recordFailure(src)
				lastErr = errors.New(i18n.Tf("update.lineCheckFailed", src.DisplayName))
				continue
			}
			m.recordSuccess(src)
			switch {
			case result.LatestTag != "" && model.CompareNumeric(alt.LatestTag, result.LatestTag) < 0:
				m.logf("update.versionConflict", result.SourceName, result.LatestTag, src.DisplayName, alt.LatestTag)
				return nil, errors.New(i18n.Tf("update.backupLower", alt.LatestTag, result.LatestTag))
			case model.CompareNumeric(current, alt.LatestTag) >= 0:
				m.logf("update.lineNotNewer", src.DisplayName, alt.LatestTag, model.Display())
				return nil, errors.New(i18n.T("update.backupNotNewer"))
			case model.CompareNumeric(alt.LatestTag, result.LatestTag) > 0:
				m.logf("update.versionConflict", result.SourceName, result.LatestTag, src.DisplayName, alt.LatestTag)
			}
			release = alt.Release
			target = alt.Release.PreferredWindowsAsset(writable)
			if target == nil || release.ChecksumManifest() == nil {
				m.logf("update.lineNoPackage", src.DisplayName)
				lastErr = errors.New(i18n.Tf("update.noPackageLine", src.DisplayName))
				continue
			}
		}
		pkg, err := m.downloadFrom(src, release, target, progress, cancel)
		if err == nil {
			m.recordSuccess(src)
			m.logf("update.downloadOk", src.DisplayName, release.TagName, target.Name, pkg.File)
			return pkg, nil
		}
		if isCancelled(err) {
			return nil, err
		}
		m.recordFailure(src)
		m.logf("update.downloadFail", src.DisplayName, classifyError(err).Label(), release.TagName)
		lastErr = err
	}
	if lastErr != nil {
		return nil, fmt.Errorf("%s；%s", i18n.T("update.allDownloadFail"), lastErr.Error())
	}
	return nil, ErrAllLineFailed
}

func (m *Module) orderedChain(primaryID string) []*Source {
	all := m.cfg.Enabled()
	if primaryID == "" {
		return all
	}
	out := make([]*Source, 0, len(all))
	for _, s := range all {
		if s.ID == primaryID {
			out = append(out, s)
		}
	}
	for _, s := range all {
		if s.ID != primaryID {
			out = append(out, s)
		}
	}
	return out
}

// downloadFrom 从指定线路下载并校验安装包（支持断点续传 + 停滞看门狗）。
func (m *Module) downloadFrom(src *Source, release *Release, asset *Asset,
	progress Progress, cancel *Cancel) (*VerifiedPackage, error) {
	if release == nil || asset == nil {
		return nil, ErrMissingAsset
	}
	maxAttempts := 3
	stallTimeoutMs := 60000
	manifestTimeout := 20000
	if src != nil {
		maxAttempts = src.DownloadAttempts
		stallTimeoutMs = src.StallTimeoutMs
		manifestTimeout = src.CheckTimeoutMs
	}

	pruneStaleUpdateFiles(m.updatesDir)
	safeName := SanitizeFileName(asset.Name)
	outPath := filepath.Join(m.updatesDir, safeName)
	part := outPath + ".part"

	if !IsHTTPSURL(asset.BrowserDownloadURL) {
		return nil, errors.New(i18n.T("update.assetNeedHttps"))
	}
	if progress != nil {
		progress.OnStatus(i18n.T("update.downloadManifest"))
	}
	expected, err := m.fetchExpectedSha256(release, asset, manifestTimeout, cancel)
	if err != nil {
		return nil, err
	}
	if progress != nil {
		progress.OnStatus(i18n.Tf("update.downloadingName", asset.Name))
	}

	stallTimeout := time.Duration(stallTimeoutMs) * time.Millisecond
	var lastErr error
	for attempt := 1; attempt <= maxAttempts; attempt++ {
		if cancel != nil && cancel.IsCancelled() {
			_ = os.Remove(part)
			return nil, ErrCancelled
		}
		err := m.downloadAttempt(asset, part, src, stallTimeout, progress, cancel)
		if err == nil {
			lastErr = nil
			break
		}
		lastErr = err
		if isCancelled(err) {
			_ = os.Remove(part)
			return nil, err
		}
		var st *StallTimeoutError
		if errors.As(err, &st) {
			break
		}
		// 只有盘上已有字节时才值得重试
		resumable := false
		if info, serr := os.Stat(part); serr == nil && info.Size() > 0 {
			resumable = true
		}
		if attempt < maxAttempts && resumable {
			if progress != nil {
				progress.OnStatus(i18n.Tf("update.resuming", attempt, maxAttempts-1))
			}
			if !sleepBeforeRetry(attempt, cancel) {
				_ = os.Remove(part)
				return nil, ErrCancelled
			}
			continue
		}
		break
	}
	if lastErr != nil {
		return nil, lastErr
	}

	if progress != nil {
		progress.OnStatus(i18n.T("update.verifying"))
	}
	actual, err := sha256File(part)
	if err != nil {
		_ = os.Remove(part)
		return nil, err
	}
	if !strings.EqualFold(actual, expected) {
		_ = os.Remove(part) // 坏字节绝不留下
		return nil, &HashMismatchError{Expected: expected, Actual: actual}
	}
	if err := os.Rename(part, outPath); err != nil {
		return nil, err
	}
	if progress != nil {
		progress.OnStatus(i18n.Tf("update.downloadDone", outPath))
	}
	if src != nil {
		m.logf("update.hashOk", actual, src.DisplayName, asset.Name)
	}
	return &VerifiedPackage{File: outPath, Asset: asset, Release: release}, nil
}

func sleepBeforeRetry(attempt int, cancel *Cancel) bool {
	deadline := time.Now().Add(time.Duration(attempt) * time.Second)
	for time.Now().Before(deadline) {
		if cancel != nil && cancel.IsCancelled() {
			return false
		}
		time.Sleep(200 * time.Millisecond)
	}
	return true
}

func (m *Module) downloadAttempt(asset *Asset, part string, src *Source,
	stallTimeout time.Duration, progress Progress, cancel *Cancel) error {
	var onDisk int64
	if info, err := os.Stat(part); err == nil {
		onDisk = info.Size()
	}
	headersTimeout := 60000
	if src != nil {
		headersTimeout = src.DownloadHeaderTimeoutMs
	}
	ctx, cancelCtx := context.WithTimeout(context.Background(),
		time.Duration(headersTimeout)*time.Millisecond)
	defer cancelCtx()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, asset.BrowserDownloadURL, nil)
	if err != nil {
		return err
	}
	req.Header.Set("User-Agent", model.UserAgent())
	req.Header.Set("Accept", "application/octet-stream")
	if onDisk > 0 {
		req.Header.Set("Range", "bytes="+strconv.FormatInt(onDisk, 10)+"-")
	}
	resp, err := m.httpClient().Do(req)
	if err != nil {
		if ctx.Err() != nil {
			return errors.New(i18n.T("update.hardTimeout"))
		}
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode == 416 {
		if asset.SizeBytes > 0 && onDisk == asset.SizeBytes {
			return nil
		}
		_ = os.Remove(part)
		return errors.New(i18n.T("update.rangeReset"))
	}
	if resp.StatusCode/100 != 2 {
		return &HTTPStatusError{Code: resp.StatusCode,
			Message: i18n.Tf("update.downloadHttp", resp.StatusCode)}
	}
	resumed := onDisk > 0 && resp.StatusCode == 206
	downloaded := int64(0)
	if resumed {
		downloaded = onDisk
	}

	flags := os.O_CREATE | os.O_WRONLY
	if resumed {
		flags |= os.O_APPEND
	} else {
		flags |= os.O_TRUNC
	}
	f, err := os.OpenFile(part, flags, 0o600)
	if err != nil {
		return err
	}
	defer f.Close()

	total := asset.SizeBytes
	if total <= 0 {
		total = resp.ContentLength
		if resumed {
			total += downloaded
		}
	}

	// 停滞看门狗：超过 stallTimeout 无字节流入就关流中止
	var lastDataNanos int64
	atomic.StoreInt64(&lastDataNanos, time.Now().UnixNano())
	var stalled int32
	watchDone := make(chan struct{})
	go func() {
		ticker := time.NewTicker(WatchdogPollInterval)
		defer ticker.Stop()
		for {
			select {
			case <-watchDone:
				return
			case <-ticker.C:
			}
			if cancel != nil && cancel.IsCancelled() {
				_ = resp.Body.Close()
				return
			}
			last := time.Unix(0, atomic.LoadInt64(&lastDataNanos))
			if time.Since(last) > stallTimeout {
				atomic.StoreInt32(&stalled, 1)
				_ = resp.Body.Close()
				return
			}
		}
	}()
	defer close(watchDone)

	buf := make([]byte, CopyBufferBytes)
	lastReport := downloaded
	for {
		if cancel != nil && cancel.IsCancelled() {
			return ErrCancelled
		}
		n, rerr := resp.Body.Read(buf)
		if n > 0 {
			if _, werr := f.Write(buf[:n]); werr != nil {
				return werr
			}
			downloaded += int64(n)
			atomic.StoreInt64(&lastDataNanos, time.Now().UnixNano())
			if downloaded-lastReport >= ProgressReportBytes || (total > 0 && downloaded == total) {
				if progress != nil {
					progress.OnProgress(downloaded, total)
				}
				lastReport = downloaded
			}
		}
		if rerr == io.EOF {
			break
		}
		if rerr != nil {
			if atomic.LoadInt32(&stalled) != 0 {
				return &StallTimeoutError{Seconds: int(stallTimeout / time.Second), Cause: rerr}
			}
			if cancel != nil && cancel.IsCancelled() {
				return ErrCancelled
			}
			return rerr
		}
	}
	if err := f.Sync(); err != nil {
		return err
	}
	if atomic.LoadInt32(&stalled) != 0 {
		return &StallTimeoutError{Seconds: int(stallTimeout / time.Second)}
	}
	if progress != nil {
		progress.OnProgress(downloaded, total)
	}
	return nil
}

func (m *Module) fetchExpectedSha256(release *Release, asset *Asset, timeoutMs int, cancel *Cancel) (string, error) {
	if cancel != nil && cancel.IsCancelled() {
		return "", ErrCancelled
	}
	manifest := release.ChecksumManifest()
	if manifest == nil {
		return "", ErrNoManifest
	}
	if !IsHTTPSURL(manifest.BrowserDownloadURL) {
		return "", errors.New(i18n.T("update.manifestHttps"))
	}
	body, status, err := m.get(manifest.BrowserDownloadURL, timeoutMs)
	if err != nil {
		return "", err
	}
	if status < 200 || status >= 300 {
		return "", &HTTPStatusError{Code: status, Message: i18n.Tf("update.manifestHttp", status)}
	}
	if strings.TrimSpace(body) == "" || len(body) > MaxChecksumManifestChars {
		return "", errors.New(i18n.T("update.manifestInvalid"))
	}
	if asset == nil {
		return "", errors.New(i18n.T("update.manifestNoTarget"))
	}
	return ExpectedSha256(body, asset.Name)
}

// pruneStaleUpdateFiles 清理超过 7 天的 .part 与历史 staged 目录（best-effort）。
func pruneStaleUpdateFiles(dir string) {
	if dir == "" {
		return
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		return
	}
	now := time.Now()
	for _, e := range entries {
		name := e.Name()
		if e.IsDir() {
			if strings.HasPrefix(strings.ToLower(name), StagedDirPrefix) {
				_ = os.RemoveAll(filepath.Join(dir, name))
			}
			continue
		}
		if strings.HasSuffix(strings.ToLower(name), ".part") {
			if info, err := e.Info(); err == nil && now.Sub(info.ModTime()) > PartFileMaxAge {
				_ = os.Remove(filepath.Join(dir, name))
			}
		}
	}
}

func sha256File(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	buf := make([]byte, CopyBufferBytes)
	for {
		n, rerr := f.Read(buf)
		if n > 0 {
			h.Write(buf[:n])
		}
		if rerr == io.EOF {
			break
		}
		if rerr != nil {
			return "", rerr
		}
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

func isCancelled(err error) bool {
	if err == nil {
		return false
	}
	return errors.Is(err, ErrCancelled)
}

// classifyError 把错误映射到失败分类（日志与熔断决策用）。
func classifyError(err error) FailureKind {
	if err == nil {
		return KindUnknown
	}
	if errors.Is(err, ErrCancelled) {
		return KindCancelled
	}
	var hm *HashMismatchError
	if errors.As(err, &hm) {
		return KindHashMismatch
	}
	var st *StallTimeoutError
	if errors.As(err, &st) {
		return KindStall
	}
	var hs *HTTPStatusError
	if errors.As(err, &hs) {
		if hs.Code == 404 {
			return KindVersionMissing
		}
		return KindHTTPStatus
	}
	var urlErr *url.Error
	if errors.As(err, &urlErr) {
		if urlErr.Timeout() {
			return KindConnectTimeout
		}
		return KindNetwork
	}
	var opErr *net.OpError
	if errors.As(err, &opErr) {
		if opErr.Timeout() {
			return KindConnectTimeout
		}
		return KindNetwork
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return KindTimeout
	}
	return KindUnknown
}
