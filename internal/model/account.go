package model

import (
	"bytes"
	"strings"
)

// Account 一行账号。密码以 []byte 保存并在替换/销毁时清零，
// 与旧版 char[] 的语义一致。
type Account struct {
	Name     string `json:"name"`
	Username string `json:"username"`
	Remark   string `json:"remark"`

	password []byte
}

// NewAccount 构造账号行；remark 为空时归一为 ""。
func NewAccount(name, username, password, remark string) *Account {
	a := &Account{Name: name, Username: username, Remark: remark}
	a.SetPassword(password)
	return a
}

// SetPassword 覆盖式设置密码（旧值先清零）。
func (a *Account) SetPassword(v string) {
	a.ClearPassword()
	if v == "" {
		a.password = nil
		return
	}
	a.password = []byte(v)
}

// SetPasswordBytes 覆盖式设置密码，入参由调用方负责清零。
func (a *Account) SetPasswordBytes(v []byte) {
	a.ClearPassword()
	if len(v) == 0 {
		a.password = nil
		return
	}
	a.password = make([]byte, len(v))
	copy(a.password, v)
}

// Password 返回明文密码（调用方不得记录到日志）。
func (a *Account) Password() string {
	if len(a.password) == 0 {
		return ""
	}
	return string(a.password)
}

// CopyPassword 返回密码副本，调用方用后应调用 ClearBytes。
func (a *Account) CopyPassword() []byte {
	if len(a.password) == 0 {
		return nil
	}
	out := make([]byte, len(a.password))
	copy(out, a.password)
	return out
}

// ClearPassword 清零并释放密码。
func (a *Account) ClearPassword() {
	ClearBytes(a.password)
	a.password = nil
}

// IsPasswordEmpty 判断密码是否为空（去除空白后）。
func (a *Account) IsPasswordEmpty() bool {
	return len(bytes.TrimSpace(a.password)) == 0
}

// PasswordEquals 去空白后比较密码。
func (a *Account) PasswordEquals(other string) bool {
	mine := bytes.TrimSpace(a.password)
	theirs := bytes.TrimSpace([]byte(other))
	return bytes.Equal(mine, theirs)
}

// EffectiveName 返回显示名：昵称为空时用账号，都为空时未设置。
func (a *Account) EffectiveName() string {
	if strings.TrimSpace(a.Name) != "" {
		return a.Name
	}
	if strings.TrimSpace(a.Username) != "" {
		return a.Username
	}
	return "未设置"
}

// Label 返回下拉/托盘展示文本 "昵称 (账号)"。
func (a *Account) Label() string {
	return a.Name + " (" + a.Username + ")"
}

// Clone 返回深拷贝（含密码副本）。
func (a *Account) Clone() *Account {
	c := &Account{Name: a.Name, Username: a.Username, Remark: a.Remark}
	c.SetPasswordBytes(a.CopyPassword())
	return c
}

// ClearBytes 清零字节切片。
func ClearBytes(b []byte) {
	for i := range b {
		b[i] = 0
	}
}
