// Package storage 负责 settings.json / accounts.json / history.json 的读写，
// 文档结构与旧版完全一致（{"schemaVersion":N,"data":...}）。
package storage

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"

	"github.com/Lexo0522/one-key-dialer/internal/i18n"
	"github.com/Lexo0522/one-key-dialer/internal/platform"
	"github.com/Lexo0522/one-key-dialer/internal/util"
)

// 错误类别
type Kind int

const (
	KindInvalidJSON Kind = iota
	KindUnknownSchema
	KindIO
)

// StorageException 存储层异常。
type StorageException struct {
	Kind    Kind
	Message string
	Cause   error
}

func (e *StorageException) Error() string { return e.Message }
func (e *StorageException) Unwrap() error { return e.Cause }

func invalidJSON(format string, args ...any) *StorageException {
	return &StorageException{Kind: KindInvalidJSON, Message: i18n.Tf("store.parseFailed", fmt.Sprintf(format, args...))}
}

// document 通用信封。
type document struct {
	SchemaVersion int             `json:"schemaVersion"`
	Data          json.RawMessage `json:"data"`
}

// ReadEnvelope 读取信封并返回 data 节点；文件不存在返回 (nil, nil)。
// 非法 JSON / 未知 schemaVersion 抛 StorageException。
func ReadEnvelope(file string, expected int) (json.RawMessage, error) {
	if _, err := os.Stat(file); err != nil {
		if os.IsNotExist(err) {
			return nil, nil
		}
		return nil, &StorageException{Kind: KindIO, Message: i18n.Tf("store.readFailed", file, err.Error()), Cause: err}
	}
	raw, err := os.ReadFile(file)
	if err != nil {
		return nil, &StorageException{Kind: KindIO, Message: i18n.Tf("store.readFailed", file, err.Error()), Cause: err}
	}
	var doc document
	if err := json.Unmarshal(raw, &doc); err != nil {
		return nil, &StorageException{Kind: KindInvalidJSON,
			Message: i18n.Tf("store.parseFailed", file, err.Error()), Cause: err}
	}
	if doc.SchemaVersion == 0 {
		return nil, &StorageException{Kind: KindUnknownSchema, Message: i18n.Tf("store.noSchema", file)}
	}
	if doc.SchemaVersion != expected {
		return nil, &StorageException{Kind: KindUnknownSchema,
			Message: i18n.Tf("store.badSchema", doc.SchemaVersion, expected, file)}
	}
	if len(doc.Data) == 0 {
		return nil, &StorageException{Kind: KindInvalidJSON, Message: i18n.Tf("store.noData", file)}
	}
	return doc.Data, nil
}

// WriteEnvelope 原子写入信封并收紧权限。
func WriteEnvelope(file string, schemaVersion int, payload any) error {
	data, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	root := map[string]any{"schemaVersion": schemaVersion, "data": json.RawMessage(data)}
	out, err := json.MarshalIndent(root, "", "  ")
	if err != nil {
		return err
	}
	if err := util.WriteAtomic(file, out); err != nil {
		return err
	}
	platform.RestrictToOwner(file)
	return nil
}

// DecodeInto 把 data 节点解析到目标结构。
func DecodeInto(raw json.RawMessage, target any) error {
	if err := json.Unmarshal(raw, target); err != nil {
		return &StorageException{Kind: KindInvalidJSON,
			Message: i18n.Tf("store.fieldFailed", "", err.Error()), Cause: err}
	}
	return nil
}

// ErrFileMissing 表示文件尚不存在（首次启动）。
var ErrFileMissing = errors.New("file missing")

// EnsureDir 确保数据目录存在。
func EnsureDir(file string) {
	_ = os.MkdirAll(filepath.Dir(file), 0o755)
}
