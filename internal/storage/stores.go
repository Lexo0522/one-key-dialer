package storage

import (
	"github.com/Lexo0522/one-key-dialer/internal/model"
)

// SchemaVersionAccounts accounts.json 的文档版本。
const SchemaVersionAccounts = 1

// AccountStore 账号 JSON 文档。
type AccountStore struct {
	File      string
	protector SecretProtector
}

// NewAccountStore 构造账号存储。
func NewAccountStore(file string, protector SecretProtector) *AccountStore {
	return &AccountStore{File: file, protector: protector}
}

type accountRecord struct {
	Name              string `json:"name"`
	Username          string `json:"username"`
	PasswordProtected string `json:"passwordProtected"`
	Remark            string `json:"remark"`
}

type accountDocument struct {
	Data []*accountRecord `json:"data"`
}

// LoadResult 加载结果：账号行 + 无法恢复的密码数量。
type LoadResult struct {
	Accounts       []*model.Account
	DroppedSecrets int
}

// Load 加载账号；文件不存在返回 nil。
func (s *AccountStore) Load() (*LoadResult, error) {
	raw, err := ReadEnvelope(s.File, SchemaVersionAccounts)
	if err != nil {
		return nil, err
	}
	if raw == nil {
		return nil, nil
	}
	var doc accountDocument
	if err := DecodeInto(raw, &doc); err != nil {
		return nil, err
	}
	out := make([]*model.Account, 0, len(doc.Data))
	dropped := 0
	for _, r := range doc.Data {
		if r == nil {
			continue
		}
		a := &model.Account{Name: r.Name, Username: r.Username, Remark: r.Remark}
		plain, ok := s.protector.Unprotect(r.PasswordProtected)
		if !ok {
			// 损坏的 blob 或保护不可用：失败关闭，内存里不放任何秘密
			dropped++
		} else {
			a.SetPasswordBytes(plain)
			model.ClearBytes(plain)
			plain = nil
		}
		out = append(out, a)
	}
	return &LoadResult{Accounts: out, DroppedSecrets: dropped}, nil
}

// Save 保存账号；保护不可用时该密码不落盘。
func (s *AccountStore) Save(accounts []*model.Account) error {
	rows := make([]*accountRecord, 0, len(accounts))
	for _, a := range accounts {
		if a == nil {
			continue
		}
		pw := a.CopyPassword()
		blob := ""
		if len(pw) > 0 {
			blob = s.protector.Protect(pw)
		}
		model.ClearBytes(pw)
		rows = append(rows, &accountRecord{
			Name:              a.Name,
			Username:          a.Username,
			PasswordProtected: blob,
			Remark:            a.Remark,
		})
	}
	EnsureDir(s.File)
	return WriteEnvelope(s.File, SchemaVersionAccounts, &accountDocument{Data: rows})
}

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

// HistoryStore 历史 JSON 文档。
type HistoryStore struct {
	File string
}

// SchemaVersionHistory history.json 的文档版本。
const SchemaVersionHistory = 1

// historyRow history.json 的单条记录行。
type historyRow struct {
	Time      string `json:"time"`
	Operation string `json:"operation"`
	Account   string `json:"account"`
	Result    string `json:"result"`
	Duration  string `json:"duration"`
	Traffic   string `json:"traffic"`
}

// Load 加载历史；文件不存在返回 nil。
// data 节点必须是裸数组；其他形态视为数据损坏，返回解析错误。
func (s *HistoryStore) Load() ([]model.HistoryRecord, error) {
	raw, err := ReadEnvelope(s.File, SchemaVersionHistory)
	if err != nil {
		return nil, err
	}
	if raw == nil {
		return nil, nil
	}
	var rows []historyRow
	if err := DecodeInto(raw, &rows); err != nil {
		return nil, err
	}
	out := make([]model.HistoryRecord, 0, len(rows))
	for _, r := range rows {
		out = append(out, model.HistoryRecord{
			Time:      r.Time,
			Operation: r.Operation,
			Account:   r.Account,
			Result:    r.Result,
			Duration:  r.Duration,
			Traffic:   r.Traffic,
		})
	}
	return out, nil
}

// Save 保存历史。
func (s *HistoryStore) Save(records []model.HistoryRecord) error {
	EnsureDir(s.File)
	rows := make([]historyRow, 0, len(records))
	for _, r := range records {
		rows = append(rows, historyRow{Time: r.Time, Operation: r.Operation, Account: r.Account,
			Result: r.Result, Duration: r.Duration, Traffic: r.Traffic})
	}
	return WriteEnvelope(s.File, SchemaVersionHistory, rows)
}
