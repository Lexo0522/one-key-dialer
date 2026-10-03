package platform

import (
	"strings"
	"testing"
)

func TestBuildWlanProfileXmlOpen(t *testing.T) {
	xml := buildWlanProfileXml("Campus-WiFi", "open", "none", "")
	if !strings.Contains(xml, "<authentication>open</authentication>") {
		t.Fatalf("open auth missing: %s", xml)
	}
	if !strings.Contains(xml, "<encryption>none</encryption>") {
		t.Fatalf("open cipher missing: %s", xml)
	}
	if strings.Contains(xml, "sharedKey") {
		t.Fatalf("open profile must not contain sharedKey: %s", xml)
	}
	if !strings.Contains(xml, "<name>Campus-WiFi</name>") {
		t.Fatalf("ssid name missing: %s", xml)
	}
}

func TestBuildWlanProfileXmlWpa2(t *testing.T) {
	xml := buildWlanProfileXml("Lab-5G", "WPA2PSK", "AES", "secret123")
	for _, want := range []string{
		"<authentication>WPA2PSK</authentication>",
		"<encryption>AES</encryption>",
		"<keyType>passPhrase</keyType>",
		"<protected>false</protected>",
		"<keyMaterial>secret123</keyMaterial>",
	} {
		if !strings.Contains(xml, want) {
			t.Fatalf("want %q in: %s", want, xml)
		}
	}
}

func TestBuildWlanProfileXmlEscape(t *testing.T) {
	xml := buildWlanProfileXml(`a<b>&"c"`, "WPA2PSK", "AES", `p&w<x>`)
	if strings.Contains(xml, `a<b>`) || strings.Contains(xml, `p&w<x>`) {
		t.Fatalf("raw xml-sensitive characters leaked: %s", xml)
	}
	if !strings.Contains(xml, `a&lt;b&gt;&amp;&quot;c&quot;`) {
		t.Fatalf("ssid not escaped: %s", xml)
	}
	if !strings.Contains(xml, `<keyMaterial>p&amp;w&lt;x&gt;</keyMaterial>`) {
		t.Fatalf("password not escaped: %s", xml)
	}
}

func TestWlanProfileForEnterpriseRejected(t *testing.T) {
	// 802.1X 企业网络必须给出明确错误,而不是生成一份必然失败的配置
	_, err := wlanProfileFor(&wlanNetDetail{
		net:  WlanNetwork{Ssid: "Campus-1X", Secured: true},
		auth: dot11AuthRSNA,
	}, "Campus-1X", "whatever")
	if err == nil || !strings.Contains(err.Error(), "enterprise") {
		t.Fatalf("want enterprise unsupported error, got %v", err)
	}
}

func TestWlanProfileForKnownTypes(t *testing.T) {
	cases := []struct {
		auth uint32
		want string
	}{
		{dot11AuthWPAPSK, "<authentication>WPA</authentication>"},
		{dot11AuthRSNAPSK, "<authentication>WPA2PSK</authentication>"},
		{dot11AuthWPA3SAE, "<authentication>SAE</authentication>"},
	}
	for _, c := range cases {
		xml, err := wlanProfileFor(&wlanNetDetail{
			net:  WlanNetwork{Ssid: "n", Secured: true},
			auth: c.auth,
		}, "n", "pw")
		if err != nil {
			t.Fatalf("auth %d: %v", c.auth, err)
		}
		if !strings.Contains(xml, c.want) {
			t.Fatalf("auth %d: want %q in %s", c.auth, c.want, xml)
		}
	}
}

func TestWlanProfileForOpenIgnoresPassword(t *testing.T) {
	xml, err := wlanProfileFor(&wlanNetDetail{
		net: WlanNetwork{Ssid: "open-net", Secured: false},
	}, "open-net", "ignored")
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(xml, "sharedKey") || strings.Contains(xml, "ignored") {
		t.Fatalf("open network must not embed a password: %s", xml)
	}
}
