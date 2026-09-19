package i18n

import "strconv"

// Tf 取文案并按 {0} {1} … 占位符顺序替换。
func Tf(key string, args ...any) string {
	s := T(key)
	for i, a := range args {
		s = replace(s, "{"+strconv.Itoa(i)+"}", toString(a))
	}
	return s
}

func replace(s, old, new string) string {
	if old == "" || s == "" {
		return s
	}
	out := make([]byte, 0, len(s)+16)
	for {
		i := indexOf(s, old)
		if i < 0 {
			break
		}
		out = append(out, s[:i]...)
		out = append(out, new...)
		s = s[i+len(old):]
	}
	return string(out) + s
}

func indexOf(s, sub string) int {
	if len(sub) > len(s) {
		return -1
	}
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return i
		}
	}
	return -1
}

func toString(a any) string {
	switch v := a.(type) {
	case string:
		return v
	case int:
		return strconv.Itoa(v)
	case int64:
		return strconv.FormatInt(v, 10)
	case int32:
		return strconv.FormatInt(int64(v), 10)
	case uint32:
		return strconv.FormatUint(uint64(v), 10)
	case uint64:
		return strconv.FormatUint(v, 10)
	case float64:
		return strconv.FormatFloat(v, 'f', -1, 64)
	case bool:
		return strconv.FormatBool(v)
	case error:
		return v.Error()
	default:
		return ""
	}
}
