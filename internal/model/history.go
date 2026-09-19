package model

import (
	"time"
)

// HistoryTimeLayout 历史时间格式。
const HistoryTimeLayout = "2006-01-02 15:04:05"

// MaxHistoryRecords 内存与磁盘上限。
const MaxHistoryRecords = 1000

// HistoryRecord 一行拨号历史。
type HistoryRecord struct {
	Time      string `json:"time"`
	Operation string `json:"operation"`
	Account   string `json:"account"`
	Result    string `json:"result"`
	Duration  string `json:"duration"`
	Traffic   string `json:"traffic"`
}

// NewHistoryRecord 以当前时间构造一行历史。
func NewHistoryRecord(operation, account, result, duration, traffic string) HistoryRecord {
	return HistoryRecord{
		Time:      time.Now().Format(HistoryTimeLayout),
		Operation: operation,
		Account:   account,
		Result:    result,
		Duration:  duration,
		Traffic:   traffic,
	}
}

// Slice 转为导出用的列切片。
func (r HistoryRecord) Slice() []string {
	return []string{r.Time, r.Operation, r.Account, r.Result, r.Duration, r.Traffic}
}

// HistoryFromSlice 由列切片还原（缺列补空）。
func HistoryFromSlice(row []string) HistoryRecord {
	get := func(i int) string {
		if i < len(row) && row[i] != "" {
			return row[i]
		}
		return ""
	}
	return HistoryRecord{
		Time:      get(0),
		Operation: get(1),
		Account:   get(2),
		Result:    get(3),
		Duration:  get(4),
		Traffic:   get(5),
	}
}

// 操作名常量（与旧版 DialOrchestrator 一致）。
const (
	OpUserDial           = "拨号"
	OpAutoDial           = "自动拨号"
	OpUserDisconnect     = "断开"
	OpScheduleDisconnect = "定时断开"
)
