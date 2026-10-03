package storage

import (
	"github.com/Lexo0522/one-key-dialer/internal/model"
)

// SchemaVersionPortal portal.json 的文档版本。
const SchemaVersionPortal = 1

// PortalStore 门户认证凭据 JSON 文档（portal.json，DPAPI 保护）。
type PortalStore struct {
	File      string
	protector SecretProtector
}

// NewPortalStore 构造门户凭据存储。
func NewPortalStore(file string, protector SecretProtector) *PortalStore {
	return &PortalStore{File: file, protector: protector}
}

type portalRecord struct {
	Username          string `json:"username"`
	PasswordProtected string `json:"passwordProtected"`
}

type portalDocument struct {
	Data portalRecord `json:"data"`
}

// Load 加载门户凭据；文件不存在返回空凭据（非 nil、无密码）。
func (s *PortalStore) Load() (*model.PortalCredential, error) {
	raw, err := ReadEnvelope(s.File, SchemaVersionPortal)
	if err != nil {
		return nil, err
	}
	if raw == nil {
		return &model.PortalCredential{}, nil
	}
	var doc portalDocument
	if err := DecodeInto(raw, &doc); err != nil {
		return nil, err
	}
	cred := &model.PortalCredential{Username: doc.Data.Username}
	plain, ok := s.protector.Unprotect(doc.Data.PasswordProtected)
	if !ok {
		// 损坏的 blob 或保护不可用：失败关闭，内存里不放任何秘密
		return cred, nil
	}
	cred.SetPasswordBytes(plain)
	model.ClearBytes(plain)
	return cred, nil
}

// Save 保存门户凭据；保护不可用时该密码不落盘。
func (s *PortalStore) Save(cred *model.PortalCredential) error {
	row := portalRecord{Username: cred.Username}
	pw := cred.CopyPassword()
	if len(pw) > 0 {
		row.PasswordProtected = s.protector.Protect(pw)
	}
	model.ClearBytes(pw)
	EnsureDir(s.File)
	return WriteEnvelope(s.File, SchemaVersionPortal, &portalDocument{Data: row})
}

// SchemaVersionWifiPsk wifi.json 的文档版本。
const SchemaVersionWifiPsk = 1

// WifiPskStore 首选 WiFi 的 PSK JSON 文档（wifi.json，DPAPI 保护）。
// 用于 agent 后台自动连接时重建/复用配置。
type WifiPskStore struct {
	File      string
	protector SecretProtector
}

// NewWifiPskStore 构造 WiFi PSK 存储。
func NewWifiPskStore(file string, protector SecretProtector) *WifiPskStore {
	return &WifiPskStore{File: file, protector: protector}
}

type wifiPskRecord struct {
	Ssid              string `json:"ssid"`
	PasswordProtected string `json:"passwordProtected"`
}

type wifiPskDocument struct {
	Data wifiPskRecord `json:"data"`
}

// Load 加载已保存的 WiFi PSK；文件不存在返回 ok=false。
func (s *WifiPskStore) Load() (ssid string, psk []byte, ok bool, err error) {
	raw, err := ReadEnvelope(s.File, SchemaVersionWifiPsk)
	if err != nil {
		return "", nil, false, err
	}
	if raw == nil {
		return "", nil, false, nil
	}
	var doc wifiPskDocument
	if err := DecodeInto(raw, &doc); err != nil {
		return "", nil, false, err
	}
	plain, unlocked := s.protector.Unprotect(doc.Data.PasswordProtected)
	if !unlocked {
		// 损坏的 blob 或保护不可用：失败关闭
		return doc.Data.Ssid, nil, false, nil
	}
	return doc.Data.Ssid, plain, len(plain) > 0, nil
}

// Save 保存 WiFi PSK；保护不可用时不落盘。
func (s *WifiPskStore) Save(ssid string, psk []byte) error {
	row := wifiPskRecord{Ssid: ssid}
	if len(psk) > 0 {
		row.PasswordProtected = s.protector.Protect(psk)
	}
	EnsureDir(s.File)
	return WriteEnvelope(s.File, SchemaVersionWifiPsk, &wifiPskDocument{Data: row})
}
