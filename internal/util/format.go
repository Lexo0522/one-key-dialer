// Package util 提供与旧版 util 包等价的格式化、脱敏、原子写与子进程封装。
package util

import (
	"fmt"
	"strconv"
)

// FormatSpeed 返回速率文案：B/s / x.x KB/s / x.x MB/s。
func FormatSpeed(bytesPerSec int64) string {
	if bytesPerSec > 1048576 {
		return fmt.Sprintf("%.1f MB/s", float64(bytesPerSec)/1048576.0)
	}
	if bytesPerSec > 1024 {
		return fmt.Sprintf("%.1f KB/s", float64(bytesPerSec)/1024.0)
	}
	return strconv.FormatInt(bytesPerSec, 10) + " B/s"
}

// FormatBytes 返回字节数文案：B / KB / MB / GB。
func FormatBytes(bytes int64) string {
	if bytes > 1073741824 {
		return fmt.Sprintf("%.2f GB", float64(bytes)/1073741824.0)
	}
	if bytes > 1048576 {
		return fmt.Sprintf("%.1f MB", float64(bytes)/1048576.0)
	}
	if bytes > 1024 {
		return fmt.Sprintf("%.1f KB", float64(bytes)/1024.0)
	}
	return strconv.FormatInt(bytes, 10) + " B"
}

// FormatDuration 返回 HH:MM:SS（补零）。
func FormatDuration(totalSeconds int64) string {
	h := totalSeconds / 3600
	m := (totalSeconds % 3600) / 60
	s := totalSeconds % 60
	return fmt.Sprintf("%02d:%02d:%02d", h, m, s)
}

// FormatSize 更新模块用的体积文案（B / x.x KB / x.x MB）。
func FormatSize(bytes int64) string {
	if bytes < 1024 {
		return strconv.FormatInt(bytes, 10) + " B"
	}
	if bytes < 1048576 {
		return fmt.Sprintf("%.1f KB", float64(bytes)/1024.0)
	}
	return fmt.Sprintf("%.1f MB", float64(bytes)/1048576.0)
}
