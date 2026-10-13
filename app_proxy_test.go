package main

import (
	"encoding/json"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Lexo0522/one-key-dialer/internal/model"
	"github.com/Lexo0522/one-key-dialer/internal/service"
	"github.com/Lexo0522/one-key-dialer/internal/storage"
)

func TestAppProxyConfigAddsCredentialsOnlyWhenEnabled(t *testing.T) {
	settings := service.NewSettingsManager(&storage.SettingsStore{File: filepath.Join(t.TempDir(), "settings.json")}, nil, nil)
	settings.Update(model.Settings{
		ProxyEnabled: true, ProxyAuthEnabled: true, ProxyType: "http",
		ProxyHost: "127.0.0.1", ProxyPort: "8080",
	})
	cred := model.NewProxyCredential("proxy-user", "private-password")
	defer cred.ClearPassword()
	a := &App{settings: settings, proxyCred: cred}

	got := a.proxyConfig()
	if !got.AuthEnabled || got.Username != "proxy-user" || got.Password != "private-password" {
		t.Fatalf("authenticated proxy config = %+v", got)
	}
	if strings.Contains(got.Summary(), "private-password") {
		t.Fatal("proxy summary contains the password")
	}

	snap, err := json.Marshal(settings.Current())
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(snap), "private-password") || strings.Contains(string(snap), "proxy-user") {
		t.Fatalf("settings snapshot contains credentials: %s", snap)
	}

	next := settings.Current()
	next.ProxyAuthEnabled = false
	settings.Update(next)
	got = a.proxyConfig()
	if got.AuthEnabled || got.Username != "" || got.Password != "" {
		t.Fatalf("disabled authentication should not expose credentials in proxy config: %+v", got)
	}
}
