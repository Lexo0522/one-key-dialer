package storage

import (
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
	normalized := snap.Normalize()
	return &normalized, nil
}

// Save 保存设置。
func (s *SettingsStore) Save(snap model.Settings) error {
	EnsureDir(s.File)
	return WriteEnvelope(s.File, SchemaVersionSettings, snap.Normalize())
}
