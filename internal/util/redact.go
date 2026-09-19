package util

import (
	"regexp"
	"strings"
)

// scrubPattern 匹配 password/passwd/pwd/pass 后跟 = 或 : 的值。
var scrubPattern = regexp.MustCompile(`(?i)(password|passwd|pwd|pass)\s*[=:]\s*\S+`)

// ScrubLogLine 尽力脱敏日志中的密码片段。
func ScrubLogLine(message string) string {
	if message == "" {
		return message
	}
	return scrubPattern.ReplaceAllString(message, "${1}=***")
}

// MaskAccount 遮罩账号：仅保留末 keepTail 位。
func MaskAccount(username string, keepTail int) string {
	if username == "" {
		return ""
	}
	keep := keepTail
	if keep < 0 {
		keep = 0
	}
	if len(username) <= keep {
		return strings.Repeat("*", len(username))
	}
	head := len(username) - keep
	return strings.Repeat("*", head) + username[head:]
}

// MaskSecret 全遮罩。
func MaskSecret(secret string) string {
	if secret == "" {
		return ""
	}
	n := 8
	if len(secret) < n {
		n = len(secret)
	}
	return strings.Repeat("*", n)
}
