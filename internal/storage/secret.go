package storage

import (
	"encoding/base64"

	"github.com/Lexo0522/one-key-dialer/internal/platform"
)

// BlobPrefix accounts.json 中 DPAPI blob 的前缀标记。
const BlobPrefix = "DPAPI1:"

// SecretProtector 密码保护抽象。
type SecretProtector interface {
	Protect(plain []byte) string
	Unprotect(blob string) ([]byte, bool)
}

// DpapiSecretProtector 生产实现：Windows DPAPI（CurrentUser）。
// DPAPI 不可用时直接不持久化，绝不写明文。
type DpapiSecretProtector struct{}

// Protect 加密；不可用返回 ""。
func (DpapiSecretProtector) Protect(plain []byte) string {
	if len(plain) == 0 {
		return ""
	}
	protected := platform.DPAPIProtect(plain)
	if len(protected) == 0 {
		return ""
	}
	return BlobPrefix + base64.StdEncoding.EncodeToString(protected)
}

// Unprotect 解密；失败或格式不符返回 false。
func (DpapiSecretProtector) Unprotect(blob string) ([]byte, bool) {
	if blob == "" {
		return []byte{}, true
	}
	if len(blob) < len(BlobPrefix) || blob[:len(BlobPrefix)] != BlobPrefix {
		return nil, false
	}
	protected, err := base64.StdEncoding.DecodeString(blob[len(BlobPrefix):])
	if err != nil {
		return nil, false
	}
	plain := platform.DPAPIUnprotect(protected)
	if plain == nil {
		return nil, false
	}
	return plain, true
}
