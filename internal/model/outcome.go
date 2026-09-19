package model

import (
	"strconv"

	"github.com/Lexo0522/one-key-dialer/internal/i18n"
)

// 拨号结果词（i18n 感知）。
func OutcomeSuccess() string       { return i18n.T("outcome.success") }
func OutcomeFailure() string       { return i18n.T("outcome.fail") }
func OutcomeDone() string          { return i18n.T("outcome.done") }
func OutcomeRasNoInternet() string { return i18n.T("outcome.rasNoInternet") }

// FailureResult 返回 "失败:691" 形式的历史结果文本。
func FailureResult(code int) string {
	return OutcomeFailure() + ":" + strconv.Itoa(code)
}
