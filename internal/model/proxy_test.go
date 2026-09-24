package model

import (
	"encoding/json"
	"testing"
)

func TestSettingsProxyJSONRoundTrip(t *testing.T) {
	s := DefaultSettings()
	s.ProxyEnabled = true
	s.ProxyType = "socks5"
	s.ProxyHost = "127.0.0.1"
	s.ProxyPort = "1080"
	s.ProxyBypass = "localhost;192.168.*"

	data, err := json.Marshal(s)
	if err != nil {
		t.Fatalf("Marshal: %v", err)
	}
	var back Settings
	if err := json.Unmarshal(data, &back); err != nil {
		t.Fatalf("Unmarshal: %v", err)
	}
	back = back.Normalize()
	if !back.ProxyEnabled || back.ProxyType != ProxyTypeSOCKS5 ||
		back.ProxyHost != "127.0.0.1" || back.ProxyPort != "1080" ||
		back.ProxyBypass != "localhost;192.168.*" {
		t.Fatalf("代理字段往返丢失: %+v", back)
	}

	// 旧版 settings.json（无代理字段）必须兼容：默认关闭
	var legacy Settings
	if err := json.Unmarshal([]byte(`{"intervalSeconds":30,"uiTheme":"dark"}`), &legacy); err != nil {
		t.Fatalf("Unmarshal legacy: %v", err)
	}
	legacy = legacy.Normalize()
	if legacy.ProxyConfig().Enabled {
		t.Fatal("旧版设置反序列化后代理应为关闭")
	}
	if legacy.ProxyType != ProxyTypeHTTP {
		t.Fatalf("旧版设置代理类型应回退 http, got %q", legacy.ProxyType)
	}
}

func TestNormalizeProxyFields(t *testing.T) {
	cases := []struct {
		name    string
		in      Settings
		wantTyp string
		wantHst string
		wantPrt string
		wantByp string
	}{
		{
			name:    "零值回退默认类型",
			in:      Settings{},
			wantTyp: ProxyTypeHTTP, wantHst: "", wantPrt: DefaultProxyPortHTTP, wantByp: "",
		},
		{
			name:    "非法类型回退 http",
			in:      Settings{ProxyType: "socks4"},
			wantTyp: ProxyTypeHTTP, wantHst: "", wantPrt: DefaultProxyPortHTTP, wantByp: "",
		},
		{
			name:    "socks5 默认端口 1080",
			in:      Settings{ProxyType: "SOCKS5", ProxyHost: " 10.0.0.2 "},
			wantTyp: ProxyTypeSOCKS5, wantHst: "10.0.0.2", wantPrt: DefaultProxyPortSOCKS5, wantByp: "",
		},
		{
			name:    "https 默认端口 443",
			in:      Settings{ProxyType: "https", ProxyHost: "proxy.example.com"},
			wantTyp: ProxyTypeHTTPS, wantHst: "proxy.example.com", wantPrt: DefaultProxyPortHTTPS, wantByp: "",
		},
		{
			name:    "剥离误粘 scheme 与斜杠并拆出端口",
			in:      Settings{ProxyHost: " http://127.0.0.1:7890/ "},
			wantTyp: ProxyTypeHTTP, wantHst: "127.0.0.1", wantPrt: "7890", wantByp: "",
		},
		{
			name:    "端口栏为空时从地址拆出数字端口",
			in:      Settings{ProxyHost: "127.0.0.1:7890"},
			wantTyp: ProxyTypeHTTP, wantHst: "127.0.0.1", wantPrt: "7890", wantByp: "",
		},
		{
			name:    "IPv6 地址不被误拆",
			in:      Settings{ProxyHost: "::1", ProxyPort: "1080"},
			wantTyp: ProxyTypeHTTP, wantHst: "::1", wantPrt: "1080", wantByp: "",
		},
		{
			name:    "非法端口回退类型默认",
			in:      Settings{ProxyType: "socks5", ProxyHost: "h", ProxyPort: "abc"},
			wantTyp: ProxyTypeSOCKS5, wantHst: "h", wantPrt: DefaultProxyPortSOCKS5, wantByp: "",
		},
		{
			name:    "越界端口回退类型默认",
			in:      Settings{ProxyHost: "h", ProxyPort: "70000"},
			wantTyp: ProxyTypeHTTP, wantHst: "h", wantPrt: DefaultProxyPortHTTP, wantByp: "",
		},
		{
			name:    "绕过列表混用逗号分号并规范化",
			in:      Settings{ProxyBypass: " localhost, 192.168.* ;;<local> , localhost "},
			wantTyp: ProxyTypeHTTP, wantHst: "", wantPrt: DefaultProxyPortHTTP,
			wantByp: "localhost;192.168.*;<local>",
		},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got := c.in.Normalize()
			if got.ProxyType != c.wantTyp || got.ProxyHost != c.wantHst ||
				got.ProxyPort != c.wantPrt || got.ProxyBypass != c.wantByp {
				t.Fatalf("Normalize() = %q/%q/%q/%q, want %q/%q/%q/%q",
					got.ProxyType, got.ProxyHost, got.ProxyPort, got.ProxyBypass,
					c.wantTyp, c.wantHst, c.wantPrt, c.wantByp)
			}
		})
	}
}

func TestProxyConfigAndURL(t *testing.T) {
	disabled := Settings{ProxyEnabled: false, ProxyHost: "127.0.0.1", ProxyPort: "7890"}.ProxyConfig()
	if disabled.Enabled || disabled.ProxyURL() != nil {
		t.Fatalf("开关关闭时不应下发代理: %+v", disabled)
	}

	noHost := Settings{ProxyEnabled: true}.ProxyConfig()
	if noHost.Enabled || noHost.ProxyURL() != nil {
		t.Fatalf("启用但无地址应视为未配置: %+v", noHost)
	}

	s := Settings{ProxyEnabled: true, ProxyType: "socks5", ProxyHost: "10.0.0.2"}.ProxyConfig()
	if !s.Enabled {
		t.Fatal("启用且有地址应生效")
	}
	if got := s.ProxyURL().String(); got != "socks5://10.0.0.2:1080" {
		t.Fatalf("ProxyURL() = %q, want socks5://10.0.0.2:1080", got)
	}
	if got := s.Summary(); got != "socks5 10.0.0.2:1080" {
		t.Fatalf("Summary() = %q", got)
	}

	off := ProxyConfig{}.Summary()
	if off != "off" {
		t.Fatalf("未启用 Summary() = %q, want off", off)
	}
}

func TestProbeConfigCarriesProxy(t *testing.T) {
	cfg := ProbeConfigFromSettings(Settings{
		ProbeMode:     "http",
		ProxyEnabled:  true,
		ProxyType:     "http",
		ProxyHost:     "127.0.0.1",
		ProxyPort:     "7890",
		ProbeAttempts: 3,
	})
	if !cfg.Proxy.Enabled || cfg.Proxy.ProxyURL().String() != "http://127.0.0.1:7890" {
		t.Fatalf("探测配置未携带代理: %+v", cfg.Proxy)
	}
	if got := cfg.Summary(); got == "" || !contains(got, "proxy=http 127.0.0.1:7890") {
		t.Fatalf("Summary() = %q, 期望含 proxy=http 127.0.0.1:7890", got)
	}
	if plain := ProbeConfigFromSettings(Settings{}).Summary(); contains(plain, "proxy=") {
		t.Fatalf("未启用代理时摘要不应含 proxy=: %q", plain)
	}
}

func contains(s, sub string) bool {
	return len(s) >= len(sub) && (func() bool {
		for i := 0; i+len(sub) <= len(s); i++ {
			if s[i:i+len(sub)] == sub {
				return true
			}
		}
		return false
	})()
}
