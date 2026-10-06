// Package update 实现主备双线路在线更新：串行降级、断路器、资产评分、
// SHA256SUMS 校验、断点续传与更新脚本生成。
package update

import (
	_ "embed"
	"net/url"
	"os"
	"strconv"
	"strings"

	"github.com/Lexo0522/one-key-dialer/internal/i18n"
	"github.com/Lexo0522/one-key-dialer/internal/model"
)

//go:embed update.properties
var embeddedProps string

// OverrideFileName 数据目录下的外部覆盖文件名。
const OverrideFileName = "update.properties"

// allowedUpdateAPIHosts 更新源 API 的域名白名单。
//
// 背景：自动更新会下载并执行安装包，是权限最高的动作；而 SHA256SUMS.txt
// 与安装包来自同一个更新源，源被劫持时哈希校验形同虚设；数据目录下的
// update.properties 又可被任何以当前用户身份运行的进程改写。
// 因此 source.*.api 只允许指向官方发布域名：内置默认值与外部覆盖一视
// 同仁，命中白名单之外的一律拒绝并回退代码默认值，同时记日志警告。
//
// 后续强化方向：安装前校验安装包的 Authenticode 签名并钉死作者证书指纹。
var allowedUpdateAPIHosts = map[string]struct{}{
	"api.github.com": {},
	"gitee.com":      {},
}

// isAllowedUpdateAPIHost 检查更新源 API 地址是否为 https 且域名在白名单内。
func isAllowedUpdateAPIHost(rawURL string) bool {
	u, err := url.Parse(strings.TrimSpace(rawURL))
	if err != nil || u.Host == "" {
		return false
	}
	if !strings.EqualFold(u.Scheme, "https") {
		return false
	}
	_, ok := allowedUpdateAPIHosts[strings.ToLower(u.Hostname())]
	return ok
}

// Source 一条更新线路的配置。
type Source struct {
	ID                      string
	Enabled                 bool
	DisplayName             string
	API                     string
	CheckTimeoutMs          int
	CheckAttempts           int
	DownloadAttempts        int
	DownloadHeaderTimeoutMs int
	StallTimeoutMs          int
	BreakerThreshold        int
	BreakerCooldownMs       int64
}

// Config 更新线路总配置。
type Config struct {
	Sources          []*Source
	ConnectTimeoutMs int
}

// defaultSource 返回线路的代码兜底默认值。
func defaultSource(id string) *Source {
	s := &Source{
		ID:                      id,
		Enabled:                 true,
		DisplayName:             id,
		CheckTimeoutMs:          8000,
		CheckAttempts:           1,
		DownloadAttempts:        3,
		DownloadHeaderTimeoutMs: 60000,
		StallTimeoutMs:          60000,
		BreakerThreshold:        3,
		BreakerCooldownMs:       600000,
	}
	switch strings.ToLower(id) {
	case "gitee":
		s.DisplayName = "Gitee"
		s.API = model.GiteeReleasesAPI
		s.CheckTimeoutMs = 8000
		s.CheckAttempts = 2
		s.DownloadAttempts = 2
		s.DownloadHeaderTimeoutMs = 30000
		s.StallTimeoutMs = 30000
		s.BreakerThreshold = 2
	case "github":
		s.DisplayName = "GitHub"
		s.API = model.ReleasesAPI
		s.CheckTimeoutMs = 12000
		s.CheckAttempts = 1
		s.DownloadAttempts = 3
		s.DownloadHeaderTimeoutMs = 60000
		s.StallTimeoutMs = 60000
		s.BreakerThreshold = 3
	}
	return s
}

// Load 加载配置：内置资源 → 数据目录外部覆盖（逐 key）→ 代码兜底。
// 任一层失败都降级而不抛异常，绝不阻塞启动。
func Load(overridePath string, warn func(string)) *Config {
	props := parseProperties(embeddedProps)
	if props == nil {
		warn(i18n.T("update.configMissing"))
		props = map[string]string{}
	}
	if overridePath != "" {
		if data, err := os.ReadFile(overridePath); err == nil {
			for k, v := range parseProperties(string(data)) {
				props[k] = v
			}
		} else if !os.IsNotExist(err) {
			warn(i18n.Tf("update.overrideFailed", overridePath, err.Error()))
		}
	}
	return fromProperties(props, warn)
}

func fromProperties(props map[string]string, warn func(string)) *Config {
	cfg := &Config{ConnectTimeoutMs: clampInt(props["update.connectTimeoutMs"], 5000, 1000, 120000)}

	ids := splitList(props["update.sources"])
	if len(ids) == 0 {
		ids = []string{"gitee", "github"}
	}
	seen := map[string]bool{}
	order := []string{}
	for _, raw := range ids {
		id := strings.ToLower(strings.TrimSpace(raw))
		if id == "" || seen[id] {
			continue
		}
		seen[id] = true
		order = append(order, id)
	}

	for _, id := range order {
		s := defaultSource(id)
		p := "source." + id + "."
		if v, ok := props[p+"enabled"]; ok {
			s.Enabled = strings.EqualFold(strings.TrimSpace(v), "true")
		}
		if v := trimOr(props[p+"displayName"]); v != "" {
			s.DisplayName = v
		}
		if v := trimOr(props[p+"api"]); v != "" {
			if !isAllowedUpdateAPIHost(v) {
				// 非白名单域名：拒绝该覆盖并回退代码默认值，同时警告。
				// 注意这也会拦截内置 update.properties 里的非法值，
				// 属于 fail-closed，宁可更新不可用也不去未知域名拉包。
				if warn != nil {
					warn(i18n.Tf("update.apiHostBlocked", id, v))
				}
				v = defaultSource(id).API
				if v == "" || !isAllowedUpdateAPIHost(v) {
					// 未知线路没有可回退的官方地址：直接跳过该线路
					continue
				}
			}
			s.API = v
		} else {
			if warn != nil {
				warn(i18n.Tf("update.lineNoApi", id))
			}
			continue
		}
		s.CheckTimeoutMs = clampInt(props[p+"checkTimeoutMs"], s.CheckTimeoutMs, 1000, 120000)
		s.CheckAttempts = clampInt(props[p+"checkAttempts"], s.CheckAttempts, 1, 5)
		s.DownloadAttempts = clampInt(props[p+"downloadAttempts"], s.DownloadAttempts, 1, 10)
		s.DownloadHeaderTimeoutMs = clampInt(props[p+"downloadHeaderTimeoutMs"], s.DownloadHeaderTimeoutMs, 5000, 600000)
		s.StallTimeoutMs = clampInt(props[p+"stallTimeoutMs"], s.StallTimeoutMs, 5000, 1800000)
		s.BreakerThreshold = clampInt(props[p+"breakerThreshold"], s.BreakerThreshold, 1, 10)
		s.BreakerCooldownMs = int64(clampInt(props[p+"breakerCooldownMs"], int(s.BreakerCooldownMs), 10000, 86400000))
		cfg.Sources = append(cfg.Sources, s)
	}

	if len(cfg.Sources) == 0 {
		if warn != nil {
			warn(i18n.T("update.noLines"))
		}
		cfg.Sources = []*Source{defaultSource("gitee"), defaultSource("github")}
	}
	return cfg
}

// Enabled 返回启用且顺序固定的线路链。
func (c *Config) Enabled() []*Source {
	out := make([]*Source, 0, len(c.Sources))
	for _, s := range c.Sources {
		if s.Enabled && strings.TrimSpace(s.API) != "" {
			out = append(out, s)
		}
	}
	return out
}

// ChainNames 返回线路展示名的顺序串，例如 "Gitee → GitHub"。
func (c *Config) ChainNames() string {
	names := make([]string, 0, len(c.Sources))
	for _, s := range c.Enabled() {
		names = append(names, s.DisplayName)
	}
	return strings.Join(names, " → ")
}

func parseProperties(text string) map[string]string {
	out := map[string]string{}
	for _, raw := range strings.Split(text, "\n") {
		line := strings.TrimSpace(raw)
		if line == "" || strings.HasPrefix(line, "#") || strings.HasPrefix(line, "//") {
			continue
		}
		if strings.HasPrefix(line, "[") {
			continue
		}
		idx := strings.Index(line, "=")
		if idx <= 0 {
			continue
		}
		out[strings.TrimSpace(line[:idx])] = strings.TrimSpace(line[idx+1:])
	}
	return out
}

func splitList(v string) []string {
	var out []string
	for _, part := range strings.Split(v, ",") {
		p := strings.TrimSpace(part)
		if p != "" {
			out = append(out, p)
		}
	}
	return out
}

func trimOr(v string) string { return strings.TrimSpace(v) }

func clampInt(raw string, def, lo, hi int) int {
	s := strings.TrimSpace(raw)
	if s == "" {
		return def
	}
	v, err := strconv.Atoi(s)
	if err != nil {
		return def
	}
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}
