package storage

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"github.com/Lexo0522/one-key-dialer/internal/model"
)

// TestSettingsStoreAutoInstallDefault 覆盖「旧版 settings.json 缺 autoInstallUpdate」
// 的兼容：缺键按默认开启补齐，显式关闭则被尊重。
func TestSettingsStoreAutoInstallDefault(t *testing.T) {
	file := filepath.Join(t.TempDir(), "settings.json")
	store := &SettingsStore{File: file}

	// 手工写一份旧版信封：只有老字段，没有 autoInstallUpdate
	legacy := map[string]any{
		"schemaVersion": SchemaVersionSettings,
		"data": map[string]any{
			"intervalSeconds":    30,
			"updateCheckEnabled": true,
		},
	}
	raw, err := json.MarshalIndent(legacy, "", "  ")
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(file, raw, 0o600); err != nil {
		t.Fatal(err)
	}

	snap, err := store.Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if !snap.AutoInstallUpdate {
		t.Fatal("旧配置缺 autoInstallUpdate 时应按默认开启补齐")
	}

	// 用户显式关闭后必须被保留
	if err := store.Save(model.Settings{AutoInstallUpdate: false}); err != nil {
		t.Fatalf("Save: %v", err)
	}
	snap, err = store.Load()
	if err != nil {
		t.Fatal(err)
	}
	if snap.AutoInstallUpdate {
		t.Fatal("显式关闭 autoInstallUpdate 后被错误重置为开启")
	}
}
