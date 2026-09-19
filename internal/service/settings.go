package service

import (
	"sync"
	"time"

	"github.com/Lexo0522/one-key-dialer/internal/i18n"
	"github.com/Lexo0522/one-key-dialer/internal/model"
	"github.com/Lexo0522/one-key-dialer/internal/storage"
)

// SettingsManager 运行时设置快照 + 防抖落盘。
type SettingsManager struct {
	store    *storage.SettingsStore
	onWarn   func(string)
	executor *BackgroundExecutor

	mu      sync.RWMutex
	current model.Settings
	pending *Task
	saving  bool
}

// NewSettingsManager 构造设置管理器。
func NewSettingsManager(store *storage.SettingsStore, executor *BackgroundExecutor, onWarn func(string)) *SettingsManager {
	return &SettingsManager{
		store:    store,
		executor: executor,
		onWarn:   onWarn,
		current:  model.DefaultSettings(),
	}
}

// LoadFromDisk 从磁盘加载；文件不存在用默认值，异常时报告并回退默认值。
func (m *SettingsManager) LoadFromDisk() model.Settings {
	snap, err := m.store.Load()
	if err != nil {
		if m.onWarn != nil {
			m.onWarn(i18n.Tf("store.settingsLoadFailed", err.Error()))
		}
		snap = nil
	}
	if snap == nil {
		snap = ptr(model.DefaultSettings())
	}
	m.mu.Lock()
	m.current = *snap
	cur := m.current
	m.mu.Unlock()
	return cur
}

// Current 返回当前运行时快照。
func (m *SettingsManager) Current() model.Settings {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.current
}

// Update 更新运行时快照并排程防抖落盘（300ms）。
func (m *SettingsManager) Update(next model.Settings) {
	normalized := next.Normalize()
	m.mu.Lock()
	m.current = normalized
	if m.pending != nil {
		m.pending.Cancel()
	}
	if m.executor != nil {
		m.pending = m.executor.Schedule(300*time.Millisecond, func() {
			m.flushLocked()
		})
	}
	m.mu.Unlock()
}

// FlushPending 立即写入挂起的设置（CAS 抢到才写）。
func (m *SettingsManager) FlushPending() {
	m.mu.Lock()
	if m.pending != nil {
		m.pending.Cancel()
		m.pending = nil
	}
	m.flushLocked()
	m.mu.Unlock()
}

// SaveNow 同步保存当前快照。
func (m *SettingsManager) SaveNow() bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.saveLocked()
}

func (m *SettingsManager) flushLocked() {
	if m.saving {
		return
	}
	m.saving = true
	defer func() { m.saving = false }()
	m.saveLocked()
}

func (m *SettingsManager) saveLocked() bool {
	if err := m.store.Save(m.current); err != nil {
		if m.onWarn != nil {
			m.onWarn(i18n.Tf("store.settingsSaveFailed", err.Error()))
		}
		return false
	}
	return true
}

func ptr(v model.Settings) *model.Settings { return &v }
