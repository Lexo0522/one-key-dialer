package storage

import (
	"encoding/json"

	"github.com/Lexo0522/one-key-dialer/internal/model"
)

// SettingsStore 设置 JSON 文档。
type SettingsStore struct {
	File string
}

// SchemaVersionSettings settings.json 的文档版本。
const SchemaVersionSettings = 1

// Load 加载设置；文件不存在返回 nil。
func (s *SettingsStore) Load() (*model.Settings, error) {
	raw, err := ReadEnvelope(s.File, SchemaVersionSettings)
	if err != nil {
		return nil, err
	}
	if raw == nil {
		return nil, nil
	}
	var snap model.Settings
	if err := DecodeInto(raw, &snap); err != nil {
		return nil, err
	}
	// 旧版 settings.json 没有 autoInstallUpdate，解码后是零值 false，
	// 与用户主动关闭无法区分。缺键即按默认（开启）补齐，让老用户也一键装完。
	var probe map[string]json.RawMessage
	if err := json.Unmarshal(raw, &probe); err == nil {
		if _, ok := probe["autoInstallUpdate"]; !ok {
			snap.AutoInstallUpdate = model.DefaultSettings().AutoInstallUpdate
		}
	}
	normalized := snap.Normalize()
	return &normalized, nil
}

// Save 保存设置。
func (s *SettingsStore) Save(snap model.Settings) error {
	EnsureDir(s.File)
	return WriteEnvelope(s.File, SchemaVersionSettings, snap.Normalize())
}
