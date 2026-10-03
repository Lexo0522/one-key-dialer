package model

import (
	"encoding/json"
	"testing"
)

// 旧版 settings.json 无 WiFi/门户字段:零值必须兼容且 Normalize 不引入意外默认。
func TestSettingsWifiLegacyJSON(t *testing.T) {
	var s Settings
	if err := json.Unmarshal([]byte(`{"intervalSeconds":30}`), &s); err != nil {
		t.Fatal(err)
	}
	s = s.Normalize()
	if s.WifiAutoConnect || s.WifiPreferredSsid != "" || s.PortalAuthEnabled || s.PortalLoginUrl != "" {
		t.Fatalf("旧版设置不应带出 WiFi/门户状态: %+v", s)
	}
}

func TestSettingsWifiNormalize(t *testing.T) {
	s := DefaultSettings()
	s.WifiAutoConnect = true
	s.WifiPreferredSsid = "  Campus-5G  "
	s.PortalAuthEnabled = true
	s.PortalLoginUrl = " http://10.1.1.55/login "
	s.PortalMethod = " post "
	s.PortalBody = " u={username:enc}&p={password:enc} "
	s.PortalSuccessHint = " login_ok "
	out := s.Normalize()
	if out.WifiPreferredSsid != "Campus-5G" {
		t.Fatalf("SSID 未去空白: %q", out.WifiPreferredSsid)
	}
	if out.PortalMethod != PortalMethodPost {
		t.Fatalf("方法未归一: %q", out.PortalMethod)
	}
	if out.PortalLoginUrl != "http://10.1.1.55/login" || out.PortalSuccessHint != "login_ok" {
		t.Fatalf("门户字段未去空白: %q %q", out.PortalLoginUrl, out.PortalSuccessHint)
	}
	if !out.WifiAutoConnectSet() {
		t.Fatal("开关+SSID 齐备时应判定 WifiAutoConnectSet")
	}
	out.WifiPreferredSsid = ""
	if out.WifiAutoConnectSet() {
		t.Fatal("缺 SSID 时不应判定 WifiAutoConnectSet")
	}
}

func TestNormalizePortalMethod(t *testing.T) {
	if NormalizePortalMethod("") != PortalMethodPost {
		t.Fatal("空值应回退 POST")
	}
	if NormalizePortalMethod("get") != PortalMethodGet {
		t.Fatal("get 应归一为 GET")
	}
	if NormalizePortalMethod("DELETE") != PortalMethodPost {
		t.Fatal("非法值应回退 POST")
	}
}
