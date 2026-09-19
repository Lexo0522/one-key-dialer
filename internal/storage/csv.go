package storage

import (
	"os"
	"strings"

	"github.com/Lexo0522/one-key-dialer/internal/model"
	"github.com/Lexo0522/one-key-dialer/internal/util"
)

// CsvLayout CSV 布局。
type CsvLayout int

const (
	// LayoutUnknown 未知（无表头的旧文件）
	LayoutUnknown CsvLayout = iota
	// LayoutSafe3 name,username,remark
	LayoutSafe3
	// LayoutWithPassword4 name,username,password,remark
	LayoutWithPassword4
)

// DetectCsvHeaderLayout 识别导出表头；无法识别按数据行处理。
func DetectCsvHeaderLayout(line string) CsvLayout {
	if line == "" {
		return LayoutUnknown
	}
	lower := strings.ToLower(line)
	looksHeader := strings.Contains(lower, "昵称") || strings.Contains(lower, "name") ||
		strings.Contains(lower, "账号") || strings.Contains(lower, "username")
	if !looksHeader {
		return LayoutUnknown
	}
	if strings.Contains(lower, "密码") || strings.Contains(lower, "password") {
		return LayoutWithPassword4
	}
	return LayoutSafe3
}

// ToCsvLineSafe 导出不含密码的行（安全默认）。
func ToCsvLineSafe(a *model.Account) string {
	if a == nil {
		return ""
	}
	return ToCsvLine([]string{a.Name, a.Username, a.Remark})
}

// ToCsvLineWithPassword 导出含密码的行（调用方必须先警示用户）。
func ToCsvLineWithPassword(a *model.Account) string {
	if a == nil {
		return ""
	}
	return ToCsvLine([]string{a.Name, a.Username, a.Password(), a.Remark})
}

// ToCsvLine 拼接一行 CSV（按需加引号）。
func ToCsvLine(values []string) string {
	var sb strings.Builder
	for i, v := range values {
		if i > 0 {
			sb.WriteByte(',')
		}
		sb.WriteString(EscapeCsv(v))
	}
	return sb.String()
}

// EscapeCsv 转义 CSV 单元格。
func EscapeCsv(value string) string {
	if value == "" {
		return ""
	}
	needQuote := strings.ContainsAny(value, ",\"\n\r")
	if !needQuote {
		return value
	}
	return `"` + strings.ReplaceAll(value, `"`, `""`) + `"`
}

// ParseCsvLine 解析一行 CSV。
func ParseCsvLine(line string) []string {
	values := []string{}
	var cur strings.Builder
	inQuotes := false
	for i := 0; i < len(line); i++ {
		ch := line[i]
		if inQuotes {
			if ch == '"' {
				if i+1 < len(line) && line[i+1] == '"' {
					cur.WriteByte('"')
					i++
				} else {
					inQuotes = false
				}
			} else {
				cur.WriteByte(ch)
			}
			continue
		}
		switch ch {
		case '"':
			inQuotes = true
		case ',':
			values = append(values, cur.String())
			cur.Reset()
		default:
			cur.WriteByte(ch)
		}
	}
	values = append(values, cur.String())
	return values
}

// AccountFromCsvParts 由列切片构造账号。
// 3 列时第 3 列视为备注（永不作为密码）。
func AccountFromCsvParts(parts []string, layout CsvLayout) *model.Account {
	if len(parts) < 2 {
		return model.NewAccount("", "", "", "")
	}
	if layout == LayoutWithPassword4 || len(parts) >= 4 {
		remark := ""
		if len(parts) >= 4 {
			remark = parts[3]
		}
		pass := ""
		if len(parts) >= 3 {
			pass = parts[2]
		}
		return model.NewAccount(parts[0], parts[1], pass, remark)
	}
	if layout == LayoutSafe3 || len(parts) == 3 {
		return model.NewAccount(parts[0], parts[1], "", parts[2])
	}
	return model.NewAccount(parts[0], parts[1], "", "")
}

// LoadCsv 导入账号 CSV（首行是表头则跳过）。
func LoadCsv(path string) ([]*model.Account, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	text := string(data)
	text = strings.TrimPrefix(text, "\ufeff")
	lines := strings.Split(text, "\n")

	out := []*model.Account{}
	layout := LayoutUnknown
	first := true
	for _, line := range lines {
		line = strings.TrimRight(line, "\r")
		if first {
			first = false
			if l := DetectCsvHeaderLayout(line); l != LayoutUnknown {
				layout = l
				continue
			}
		}
		parts := ParseCsvLine(line)
		if len(parts) < 2 {
			continue
		}
		out = append(out, AccountFromCsvParts(parts, layout))
	}
	return out, nil
}

// SaveCsv 导出账号 CSV。
func SaveCsv(path string, accounts []*model.Account, withPassword bool) error {
	var sb strings.Builder
	if withPassword {
		sb.WriteString(ToCsvLine([]string{"昵称", "账号", "密码", "备注"}))
		sb.WriteByte('\n')
		for _, a := range accounts {
			sb.WriteString(ToCsvLineWithPassword(a))
			sb.WriteByte('\n')
		}
	} else {
		sb.WriteString(ToCsvLine([]string{"昵称", "账号", "备注"}))
		sb.WriteByte('\n')
		for _, a := range accounts {
			sb.WriteString(ToCsvLineSafe(a))
			sb.WriteByte('\n')
		}
	}
	return util.WriteAtomicUTF8(path, sb.String())
}

// ExportHistoryCsv 导出历史记录 CSV（表头固定六列）。
func ExportHistoryCsv(path string, records []model.HistoryRecord) error {
	var sb strings.Builder
	sb.WriteString("时间,操作,账号,结果,连接时长,流量总和\n")
	for _, r := range records {
		sb.WriteString(ToCsvLine(r.Slice()))
		sb.WriteByte('\n')
	}
	return util.WriteAtomicUTF8(path, sb.String())
}
