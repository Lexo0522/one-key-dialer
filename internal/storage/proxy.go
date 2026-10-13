package storage

import (
	"errors"

	"github.com/Lexo0522/one-key-dialer/internal/model"
)

const SchemaVersionProxy = 1

// ProxyStore 保存代理认证账号和 DPAPI 保护后的密码。
type ProxyStore struct {
	File      string
	protector SecretProtector
}

func NewProxyStore(file string, protector SecretProtector) *ProxyStore {
	return &ProxyStore{File: file, protector: protector}
}

type proxyRecord struct {
	Username          string `json:"username"`
	PasswordProtected string `json:"passwordProtected"`
}

type proxyDocument struct {
	Data proxyRecord `json:"data"`
}

func (s *ProxyStore) Load() (*model.ProxyCredential, error) {
	raw, err := ReadEnvelope(s.File, SchemaVersionProxy)
	if err != nil {
		return nil, err
	}
	cred := &model.ProxyCredential{}
	if raw == nil {
		return cred, nil
	}
	var doc proxyDocument
	if err := DecodeInto(raw, &doc); err != nil {
		return nil, err
	}
	cred.Username = doc.Data.Username
	plain, ok := s.protector.Unprotect(doc.Data.PasswordProtected)
	if !ok {
		return cred, nil
	}
	cred.SetPasswordBytes(plain)
	model.ClearBytes(plain)
	return cred, nil
}

func (s *ProxyStore) Save(cred *model.ProxyCredential) error {
	row := proxyRecord{Username: cred.Username}
	pw := cred.CopyPassword()
	if len(pw) > 0 {
		row.PasswordProtected = s.protector.Protect(pw)
		model.ClearBytes(pw)
		if row.PasswordProtected == "" {
			return errors.New("protect proxy password failed")
		}
	}
	model.ClearBytes(pw)
	EnsureDir(s.File)
	return WriteEnvelope(s.File, SchemaVersionProxy, &proxyDocument{Data: row})
}
