package storage

import (
	"path/filepath"
	"strings"
	"testing"

	"github.com/Lexo0522/one-key-dialer/internal/model"
)

// stubProtector 可预测的保护器:加解密在测试内互逆,不依赖 Windows DPAPI。
type stubProtector struct{}

func (stubProtector) Protect(plain []byte) string { return "STUB:" + string(plain) }

func (stubProtector) Unprotect(blob string) ([]byte, bool) {
	if !strings.HasPrefix(blob, "STUB:") {
		return nil, false
	}
	return []byte(strings.TrimPrefix(blob, "STUB:")), true
}

func TestPortalStoreRoundTrip(t *testing.T) {
	file := filepath.Join(t.TempDir(), "portal.json")
	store := NewPortalStore(file, stubProtector{})

	// 文件不存在:返回空凭据而非错误
	cred, err := store.Load()
	if err != nil || cred == nil || cred.Username != "" {
		t.Fatalf("空文件应返回空凭据: %+v err=%v", cred, err)
	}

	if err := store.Save(model.NewPortalCredential("20210001", "pw1")); err != nil {
		t.Fatalf("Save: %v", err)
	}
	cred, err = store.Load()
	if err != nil {
		t.Fatal(err)
	}
	if cred.Username != "20210001" || cred.Password() != "pw1" {
		t.Fatalf("往返不一致: %+v", cred)
	}

	// 覆盖保存
	if err := store.Save(model.NewPortalCredential("20210002", "")); err != nil {
		t.Fatal(err)
	}
	cred, _ = store.Load()
	if cred.Username != "20210002" || cred.HasPassword() {
		t.Fatalf("覆盖保存后应为无密码凭据: %+v", cred)
	}
}

func TestWifiPskStoreRoundTrip(t *testing.T) {
	file := filepath.Join(t.TempDir(), "wifi.json")
	store := NewWifiPskStore(file, stubProtector{})

	ssid, psk, ok, err := store.Load()
	if err != nil || ok {
		t.Fatalf("空文件应 ok=false: ssid=%q ok=%v err=%v", ssid, ok, err)
	}

	if err := store.Save("Campus-5G", []byte("secret-psk")); err != nil {
		t.Fatal(err)
	}
	ssid, psk, ok, err = store.Load()
	if err != nil || !ok {
		t.Fatalf("Load: ok=%v err=%v", ok, err)
	}
	if ssid != "Campus-5G" || string(psk) != "secret-psk" {
		t.Fatalf("往返不一致: ssid=%q psk=%q", ssid, string(psk))
	}

	// 密码损坏(前缀不符):失败关闭,不得返回明文
	if err := store.Save("X", []byte("y")); err != nil {
		t.Fatal(err)
	}
	broken := NewWifiPskStore(file, brokenProtector{})
	_, p2, ok2, err2 := broken.Load()
	if ok2 || len(p2) != 0 || err2 != nil {
		t.Fatalf("损坏 blob 应失败关闭: ok=%v psk=%q err=%v", ok2, string(p2), err2)
	}
}

type brokenProtector struct{}

func (brokenProtector) Protect(plain []byte) string { return "STUB:x" }

func (brokenProtector) Unprotect(blob string) ([]byte, bool) { return nil, false }
