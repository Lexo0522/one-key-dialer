package service

import (
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/Lexo0522/one-key-dialer/internal/util"
)

// 日志级别
type Level int

const (
	LevelInfo Level = iota
	LevelSuccess
	LevelWarning
	LevelError
)

// String 返回级别名（用于事件负载）。
func (l Level) String() string {
	switch l {
	case LevelSuccess:
		return "success"
	case LevelWarning:
		return "warning"
	case LevelError:
		return "error"
	}
	return "info"
}

// MaxLogLines 内存日志上限（超出从顶部裁掉）。
const MaxLogLines = 500

// LogLine 一行日志。
type LogLine struct {
	Time    string `json:"time"`
	Level   string `json:"level"`
	Message string `json:"message"`
}

// LogService 彩色 UI 日志 + 追加写文件缓冲。
type LogService struct {
	file string

	mu       sync.Mutex
	lines    []LogLine
	buffer   strings.Builder
	flushing sync.Mutex

	onLine func(LogLine)
}

// NewLogService 构造日志服务。
func NewLogService(file string) *LogService {
	return &LogService{file: file, lines: make([]LogLine, 0, MaxLogLines)}
}

// AttachLineSink 安装新日志行的回调（用于推送到前端）。
func (l *LogService) AttachLineSink(sink func(LogLine)) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.onLine = sink
}

// Log 记录一行日志（先脱敏，再入内存与文件缓冲）。
func (l *LogService) Log(level Level, message string) {
	safe := util.ScrubLogLine(message)
	line := LogLine{Time: time.Now().Format("15:04:05"), Level: level.String(), Message: safe}

	l.mu.Lock()
	l.lines = append(l.lines, line)
	if len(l.lines) > MaxLogLines {
		l.lines = l.lines[len(l.lines)-MaxLogLines:]
	}
	sink := l.onLine
	l.mu.Unlock()

	l.appendToFile("[" + line.Time + "] " + safe + "\n")

	if sink != nil {
		func() {
			defer func() { _ = recover() }()
			sink(line)
		}()
	}
}

// Info / Success / Warning / Error 便捷方法。
func (l *LogService) Info(msg string)    { l.Log(LevelInfo, msg) }
func (l *LogService) Success(msg string) { l.Log(LevelSuccess, msg) }
func (l *LogService) Warning(msg string) { l.Log(LevelWarning, msg) }
func (l *LogService) Error(msg string)   { l.Log(LevelError, msg) }

// Snapshot 返回当前内存日志副本（前端首帧拉取）。
func (l *LogService) Snapshot() []LogLine {
	l.mu.Lock()
	defer l.mu.Unlock()
	out := make([]LogLine, len(l.lines))
	copy(out, l.lines)
	return out
}

func (l *LogService) appendToFile(text string) {
	l.flushing.Lock()
	l.buffer.WriteString(text)
	l.flushing.Unlock()
}

// Flush 把缓冲写入日志文件。
func (l *LogService) Flush() {
	l.flushing.Lock()
	if l.buffer.Len() == 0 {
		l.flushing.Unlock()
		return
	}
	content := l.buffer.String()
	l.buffer.Reset()
	l.flushing.Unlock()

	path := l.file
	if path == "" {
		path = "pppoe_log.txt"
	}
	l.rotateIfNeeded(path)
	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		return
	}
	defer f.Close()
	_, _ = f.WriteString(content)
}

// 日志轮转参数：托盘常驻是 99% 的运行形态，日志文件只追加不轮转会
// 无限膨胀。超限时逐级后移（.1 → .2 → .3），最旧的丢弃。
const (
	logMaxBytes   = 5 << 20 // 5MB
	logMaxBackups = 3
)

// rotateIfNeeded 日志超限时轮转。best-effort：任何一步失败都不阻塞本次写入；
// 备份文件经 os.Rename 产生，权限与原文件一致（0o600）。
func (l *LogService) rotateIfNeeded(path string) {
	info, err := os.Stat(path)
	if err != nil || info.Size() < logMaxBytes {
		return
	}
	_ = os.Remove(path + "." + strconv.Itoa(logMaxBackups))
	for i := logMaxBackups - 1; i >= 1; i-- {
		oldName := path + "." + strconv.Itoa(i)
		newName := path + "." + strconv.Itoa(i+1)
		_ = os.Rename(oldName, newName) // 不存在时忽略
	}
	_ = os.Rename(path, path+".1")
}
