package service

import (
	"sync"

	"github.com/Lexo0522/one-key-dialer/internal/i18n"
	"github.com/Lexo0522/one-key-dialer/internal/model"
	"github.com/Lexo0522/one-key-dialer/internal/storage"
)

// HistoryService 内存拨号历史 + 脏标记 + 落盘。
type HistoryService struct {
	store  *storage.HistoryStore
	onWarn func(string)
	onAdd  func(model.HistoryRecord)

	mu            sync.Mutex
	records       []model.HistoryRecord
	dirty         bool
	diskLoadTried bool
}

// NewHistoryService 构造历史服务。
func NewHistoryService(store *storage.HistoryStore, onWarn func(string)) *HistoryService {
	return &HistoryService{
		store:   store,
		onWarn:  onWarn,
		records: make([]model.HistoryRecord, 0, model.MaxHistoryRecords),
	}
}

// AttachAddSink 安装新增记录的回调（推送到前端表格）。
func (h *HistoryService) AttachAddSink(sink func(model.HistoryRecord)) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.onAdd = sink
}

// EnsureLoaded 首次改动/绑定前从磁盘加载一次；可重复调用。
func (h *HistoryService) EnsureLoaded() {
	h.mu.Lock()
	if h.diskLoadTried {
		h.mu.Unlock()
		return
	}
	h.diskLoadTried = true
	h.mu.Unlock()
	h.loadFromDisk()
}

func (h *HistoryService) loadFromDisk() {
	stored, err := h.store.Load()
	h.mu.Lock()
	defer h.mu.Unlock()
	if err != nil {
		if h.onWarn != nil {
			h.onWarn(i18n.Tf("store.historyLoadFailed", err.Error()))
		}
		return
	}
	h.records = h.records[:0]
	if stored != nil {
		h.records = append(h.records, stored...)
	}
	if len(h.records) > model.MaxHistoryRecords {
		h.records = h.records[:model.MaxHistoryRecords]
	}
}

// Records 返回内存记录副本（最新在前）。
func (h *HistoryService) Records() []model.HistoryRecord {
	h.EnsureLoaded()
	h.mu.Lock()
	defer h.mu.Unlock()
	out := make([]model.HistoryRecord, len(h.records))
	copy(out, h.records)
	return out
}

// Add 追加一条历史（插到最前），并置脏。
func (h *HistoryService) Add(operation, account, result, duration, traffic string) {
	h.EnsureLoaded()
	record := model.NewHistoryRecord(operation, account, result, duration, traffic)

	h.mu.Lock()
	h.records = append([]model.HistoryRecord{record}, h.records...)
	if len(h.records) > model.MaxHistoryRecords {
		h.records = h.records[:model.MaxHistoryRecords]
	}
	h.dirty = true
	sink := h.onAdd
	h.mu.Unlock()

	if sink != nil {
		func() {
			defer func() { _ = recover() }()
			sink(record)
		}()
	}
}

// Clear 清空历史并立即落盘。
func (h *HistoryService) Clear() {
	h.EnsureLoaded()
	h.mu.Lock()
	h.records = h.records[:0]
	h.dirty = true
	h.mu.Unlock()
	h.SaveIfDirty()
}

// Save 落盘。
func (h *HistoryService) Save() bool {
	h.EnsureLoaded()
	h.mu.Lock()
	snapshot := make([]model.HistoryRecord, len(h.records))
	copy(snapshot, h.records)
	h.mu.Unlock()
	if err := h.store.Save(snapshot); err != nil {
		if h.onWarn != nil {
			h.onWarn(i18n.Tf("store.historySaveFailed", err.Error()))
		}
		return false
	}
	return true
}

// SaveIfDirty 仅在脏时落盘；返回是否尝试过写入。
func (h *HistoryService) SaveIfDirty() bool {
	h.mu.Lock()
	if !h.dirty {
		h.mu.Unlock()
		return false
	}
	h.dirty = false
	h.mu.Unlock()
	if !h.Save() {
		// 写入失败必须保持脏，便于退出/重试时保留记录
		h.mu.Lock()
		h.dirty = true
		h.mu.Unlock()
	}
	return true
}

// IsDirty 返回脏标记。
func (h *HistoryService) IsDirty() bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.dirty
}

// Export 导出到 CSV 文件。
func (h *HistoryService) Export(path string) error {
	h.EnsureLoaded()
	h.mu.Lock()
	snapshot := make([]model.HistoryRecord, len(h.records))
	copy(snapshot, h.records)
	h.mu.Unlock()
	return storage.ExportHistoryCsv(path, snapshot)
}
