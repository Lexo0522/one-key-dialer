package storage

import (
	"encoding/base64"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Lexo0522/one-key-dialer/internal/model"
)

type proxyTestProtector struct{}

func (proxyTestProtector) Protect(plain []byte) string {
	return "TEST:" + base64.StdEncoding.EncodeToString(plain)
}

func (proxyTestProtector) Unprotect(blob string) ([]byte, bool) {
	if !strings.HasPrefix(blob, "TEST:") {
		return nil, false
	}
	b, err := base64.StdEncoding.DecodeString(strings.TrimPrefix(blob, "TEST:"))
	return b, err == nil
}

type proxyBrokenProtector struct{}

func (proxyBrokenProtector) Protect([]byte) string           { return "" }
func (proxyBrokenProtector) Unprotect(string) ([]byte, bool) { return nil, false }

func TestProxyStoreProtectsAndLoadsCredential(t *testing.T) {
	file := filepath.Join(t.TempDir(), "proxy.json")
	store := NewProxyStore(file, proxyTestProtector{})
	cred := model.NewProxyCredential("proxy-user", "top-secret")
	if err := store.Save(cred); err != nil {
		t.Fatalf("Save: %v", err)
	}
	cred.ClearPassword()

	raw, err := os.ReadFile(file)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(raw), "top-secret") {
		t.Fatalf("proxy password stored as plaintext: %s", raw)
	}

	loaded, err := store.Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	defer loaded.ClearPassword()
	if loaded.Username != "proxy-user" || loaded.Password() != "top-secret" {
		t.Fatalf("loaded credential = %q/%q", loaded.Username, loaded.Password())
	}
}

func TestProxyStoreFailsClosedWhenProtectionUnavailable(t *testing.T) {
	file := filepath.Join(t.TempDir(), "proxy.json")
	store := NewProxyStore(file, proxyBrokenProtector{})
	cred := model.NewProxyCredential("proxy-user", "top-secret")
	defer cred.ClearPassword()
	if err := store.Save(cred); err == nil {
		t.Fatal("Save should fail when the password cannot be protected")
	}
	if _, err := os.Stat(file); !os.IsNotExist(err) {
		t.Fatalf("failed protection must not write a file, stat err = %v", err)
	}
}

func TestSettingsProxyAuthDefaultsOffAndRoundTrips(t *testing.T) {
	legacy := []byte(`{"intervalSeconds":30,"uiTheme":"dark"}`)
	var settings model.Settings
	if err := json.Unmarshal(legacy, &settings); err != nil {
		t.Fatal(err)
	}
	if settings.Normalize().ProxyAuthEnabled {
		t.Fatal("legacy settings should leave proxy authentication disabled")
	}

	settings.ProxyAuthEnabled = true
	raw, err := json.Marshal(settings)
	if err != nil {
		t.Fatal(err)
	}
	var roundTrip model.Settings
	if err := json.Unmarshal(raw, &roundTrip); err != nil {
		t.Fatal(err)
	}
	if !roundTrip.Normalize().ProxyAuthEnabled {
		t.Fatal("proxy authentication setting did not round trip")
	}
	if strings.Contains(string(raw), "password") || strings.Contains(string(raw), "username") {
		t.Fatalf("proxy credentials must not be part of Settings: %s", raw)
	}
}
