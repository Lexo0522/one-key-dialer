package service

import (
	"strings"
	"sync"

	"github.com/Lexo0522/one-key-dialer/internal/i18n"
	"github.com/Lexo0522/one-key-dialer/internal/model"
	"github.com/Lexo0522/one-key-dialer/internal/storage"
)

// AccountSession 多账号列表、选中索引与持久化。
// 凭据以一次性副本交给拨号编排器，任何地方都不缓存密码。
type AccountSession struct {
	store    *storage.AccountStore
	onWarn   func(string)
	onError  func(string)
	executor *BackgroundExecutor

	mu           sync.Mutex
	accounts     []*model.Account
	currentIndex int
	dirty        bool
	saveGen      uint64
}

// NewAccountSession 构造账号会话。
func NewAccountSession(store *storage.AccountStore, executor *BackgroundExecutor,
	onWarn, onError func(string)) *AccountSession {
	return &AccountSession{store: store, executor: executor, onWarn: onWarn, onError: onError}
}

// Accounts 返回账号列表快照（不含密码，供 UI 展示用）。
func (s *AccountSession) Accounts() []*model.Account {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]*model.Account, 0, len(s.accounts))
	for _, a := range s.accounts {
		out = append(out, &model.Account{Name: a.Name, Username: a.Username, Remark: a.Remark})
	}
	return out
}

// CurrentIndex 返回当前选中索引。
func (s *AccountSession) CurrentIndex() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.currentIndex
}

// SetCurrentIndex 设置选中索引（越界归零）。
func (s *AccountSession) SetCurrentIndex(index int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if len(s.accounts) == 0 {
		s.currentIndex = 0
		return
	}
	if index < 0 || index >= len(s.accounts) {
		s.currentIndex = 0
		return
	}
	s.currentIndex = index
}

// CurrentOrNil 返回当前账号（可能为 nil）。
func (s *AccountSession) CurrentOrNil() *model.Account {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.currentIndex < 0 || s.currentIndex >= len(s.accounts) {
		return nil
	}
	return s.accounts[s.currentIndex]
}

// CurrentName 返回当前账号名（无则"未命名账号"）。
func (s *AccountSession) CurrentName() string {
	if a := s.CurrentOrNil(); a != nil {
		if strings.TrimSpace(a.Name) != "" {
			return a.Name
		}
		return a.Username
	}
	return i18n.T("account.unnamed")
}

// Load 从磁盘加载账号；失败时用空列表且不覆盖原文件。
func (s *AccountSession) Load(startIndex int) {
	result, err := s.store.Load()
	s.mu.Lock()
	s.accounts = s.accounts[:0]
	if err != nil {
		s.mu.Unlock()
		if s.onError != nil {
			s.onError(i18n.Tf("store.accountsLoadFailed", err.Error()))
		}
		s.mu.Lock()
		s.ensureDefaultLocked()
		s.currentIndex = 0
		s.mu.Unlock()
		return
	}
	if result != nil {
		s.accounts = append(s.accounts, result.Accounts...)
		dropped := result.DroppedSecrets
		s.ensureDefaultLocked()
		s.currentIndex = startIndex
		if s.currentIndex < 0 || s.currentIndex >= len(s.accounts) {
			s.currentIndex = 0
		}
		s.mu.Unlock()
		if dropped > 0 && s.onError != nil {
			s.onError(i18n.Tf("store.droppedSecrets", dropped))
		}
		return
	}
	s.ensureDefaultLocked()
	s.currentIndex = 0
	s.mu.Unlock()
}

func (s *AccountSession) ensureDefaultLocked() {
	if len(s.accounts) == 0 {
		s.accounts = append(s.accounts, model.NewAccount(i18n.T("account.default"), "", "", ""))
	}
}

// Save 同步保存（关闭/切换账号路径）。
func (s *AccountSession) Save() bool {
	snapshot := s.copyForPersistence()
	return s.saveSnapshot(snapshot)
}

// SaveInBackground 后台保存；只有最新的快照允许写入。
func (s *AccountSession) SaveInBackground() {
	snapshot := s.copyForPersistence()
	s.mu.Lock()
	s.saveGen++
	gen := s.saveGen
	s.dirty = true
	exec := s.executor
	s.mu.Unlock()

	run := func() {
		s.mu.Lock()
		ok := gen == s.saveGen
		s.mu.Unlock()
		if !ok {
			clearSnapshot(snapshot)
			return
		}
		if s.saveSnapshot(snapshot) {
			s.mu.Lock()
			s.dirty = false
			s.mu.Unlock()
		}
	}
	if exec == nil {
		run()
		return
	}
	exec.Submit(run)
}

func (s *AccountSession) copyForPersistence() []*model.Account {
	s.mu.Lock()
	defer s.mu.Unlock()
	snapshot := make([]*model.Account, 0, len(s.accounts))
	for _, a := range s.accounts {
		snapshot = append(snapshot, a.Clone())
	}
	return snapshot
}

func (s *AccountSession) saveSnapshot(snapshot []*model.Account) bool {
	defer clearSnapshot(snapshot)
	if err := s.store.Save(snapshot); err != nil {
		if s.onError != nil {
			s.onError(i18n.Tf("store.accountsSaveFailed", err.Error()))
		}
		return false
	}
	return true
}

func clearSnapshot(snapshot []*model.Account) {
	for _, a := range snapshot {
		if a != nil {
			a.ClearPassword()
		}
	}
}

// AccountView 账号行的 UI 视图：不含明文密码，但带"是否已设置密码"标志。
type AccountView struct {
	Name        string
	Username    string
	Remark      string
	HasPassword bool
}

// Views 返回 UI 视图快照。密码标志必须在持锁状态下对真实账号计算——
// Accounts() 返回的是不含密码的副本，在副本上判空会永远得到 false。
func (s *AccountSession) Views() []AccountView {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]AccountView, 0, len(s.accounts))
	for _, a := range s.accounts {
		if a == nil {
			out = append(out, AccountView{})
			continue
		}
		out = append(out, AccountView{
			Name:        a.Name,
			Username:    a.Username,
			Remark:      a.Remark,
			HasPassword: !a.IsPasswordEmpty(),
		})
	}
	return out
}

// SnapshotWithPasswords 返回含密码的深拷贝（仅供导入/导出等内部链路使用，
// 调用方必须通过 clearSnapshot 或 ClearPassword 清零）。
func (s *AccountSession) SnapshotWithPasswords() []*model.Account {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]*model.Account, 0, len(s.accounts))
	for _, a := range s.accounts {
		out = append(out, a.Clone())
	}
	return out
}

// PasswordForAccount 返回已保存密码的副本（优先按索引处的同名账号匹配，
// 再按账号名全局匹配），无匹配返回 nil。调用方负责清零。
// 用于 UI 整列替换时继承"未重新输入"的密码：前端快照不含明文，
// 编辑/删除/排序后必须按账号名把旧密码带回来，否则会被空密码覆盖。
func (s *AccountSession) PasswordForAccount(username string, index int) []byte {
	key := strings.TrimSpace(username)
	s.mu.Lock()
	defer s.mu.Unlock()
	if index >= 0 && index < len(s.accounts) {
		if old := s.accounts[index]; old != nil && strings.TrimSpace(old.Username) == key {
			return old.CopyPassword()
		}
	}
	if key == "" {
		return nil
	}
	for _, a := range s.accounts {
		if a != nil && strings.TrimSpace(a.Username) == key {
			return a.CopyPassword()
		}
	}
	return nil
}

// ApplyEdits 用 UI 提供的完整列表替换内存账号（账号管理页保存）。
func (s *AccountSession) ApplyEdits(accounts []*model.Account) {
	s.mu.Lock()
	s.accounts = accounts
	if len(s.accounts) == 0 {
		s.accounts = append(s.accounts, model.NewAccount(i18n.T("account.default"), "", "", ""))
	}
	if s.currentIndex >= len(s.accounts) {
		s.currentIndex = len(s.accounts) - 1
	}
	if s.currentIndex < 0 {
		s.currentIndex = 0
	}
	s.dirty = true
	s.mu.Unlock()
}

// ClampIndexAfterListChange 列表变短后收敛索引。
func (s *AccountSession) ClampIndexAfterListChange() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.currentIndex >= len(s.accounts) {
		s.currentIndex = len(s.accounts) - 1
	}
	if s.currentIndex < 0 {
		s.currentIndex = 0
	}
}

// ClearPasswordsInMemory 清零内存中所有账号密码。
func (s *AccountSession) ClearPasswordsInMemory() {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, a := range s.accounts {
		if a != nil {
			a.ClearPassword()
		}
	}
}

// IsDirty 返回脏标记。
func (s *AccountSession) IsDirty() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.dirty
}
