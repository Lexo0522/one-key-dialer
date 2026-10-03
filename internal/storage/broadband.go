package storage

import (
	"github.com/Lexo0522/one-key-dialer/internal/model"
)

// SchemaVersionBroadband broadband.json 的文档版本。
const SchemaVersionBroadband = 1

// BroadbandStore 宽带拨号凭据 JSON 文档（broadband.json，DPAPI 保护）。
type BroadbandStore struct {
	File      string
	protector SecretProtector
}

// NewBroadbandStore 构造宽带凭据存储。
func NewBroadbandStore(file string, protector SecretProtector) *BroadbandStore {
	return &BroadbandStore{File: file, protector: protector}
}

type broadbandRecord struct {
	Username          string `json:"username"`
	PasswordProtected string `json:"passwordProtected"`
}

type broadbandDocument struct {
	Data broadbandRecord `json:"data"`
}

// Load 加载宽带凭据；文件不存在返回空凭据（非 nil、无密码）。
func (s *BroadbandStore) Load() (*model.BroadbandCredential, error) {
	raw, err := ReadEnvelope(s.File, SchemaVersionBroadband)
	if err != nil {
		return nil, err
	}
	if raw == nil {
		return &model.BroadbandCredential{}, nil
	}
	var doc broadbandDocument
	if err := DecodeInto(raw, &doc); err != nil {
		return nil, err
	}
	cred := &model.BroadbandCredential{Username: doc.Data.Username}
	plain, ok := s.protector.Unprotect(doc.Data.PasswordProtected)
	if !ok {
		// 损坏的 blob 或保护不可用：失败关闭，内存里不放任何秘密
		return cred, nil
	}
	cred.SetPasswordBytes(plain)
	model.ClearBytes(plain)
	return cred, nil
}

// Save 保存宽带凭据；保护不可用时该密码不落盘。
func (s *BroadbandStore) Save(cred *model.BroadbandCredential) error {
	row := broadbandRecord{Username: cred.Username}
	pw := cred.CopyPassword()
	if len(pw) > 0 {
		row.PasswordProtected = s.protector.Protect(pw)
	}
	model.ClearBytes(pw)
	EnsureDir(s.File)
	return WriteEnvelope(s.File, SchemaVersionBroadband, &broadbandDocument{Data: row})
}
