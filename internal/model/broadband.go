package model

// BroadbandCredential 宽带拨号凭据（校园网一人一号，全局仅此一份）。
// 密码以 []byte 保存并在替换/销毁时清零，语义同 PortalCredential。
type BroadbandCredential struct {
	Username string

	password []byte
}

// NewBroadbandCredential 构造宽带凭据。
func NewBroadbandCredential(username, password string) *BroadbandCredential {
	c := &BroadbandCredential{Username: username}
	c.SetPassword(password)
	return c
}

// SetPassword 覆盖式设置密码（旧值先清零）。
func (c *BroadbandCredential) SetPassword(v string) {
	c.ClearPassword()
	if v == "" {
		c.password = nil
		return
	}
	c.password = []byte(v)
}

// SetPasswordBytes 覆盖式设置密码，入参由调用方负责清零。
func (c *BroadbandCredential) SetPasswordBytes(v []byte) {
	c.ClearPassword()
	if len(v) == 0 {
		c.password = nil
		return
	}
	c.password = make([]byte, len(v))
	copy(c.password, v)
}

// Password 返回明文密码（调用方不得记录到日志）。
func (c *BroadbandCredential) Password() string {
	if len(c.password) == 0 {
		return ""
	}
	return string(c.password)
}

// CopyPassword 返回密码副本，调用方用后应调用 ClearBytes。
func (c *BroadbandCredential) CopyPassword() []byte {
	if len(c.password) == 0 {
		return nil
	}
	out := make([]byte, len(c.password))
	copy(out, c.password)
	return out
}

// HasPassword 判断是否已设置密码。
func (c *BroadbandCredential) HasPassword() bool {
	return len(c.password) > 0
}

// ClearPassword 清零并释放密码。
func (c *BroadbandCredential) ClearPassword() {
	ClearBytes(c.password)
	c.password = nil
}

// ClearBytes 清零字节切片。
func ClearBytes(b []byte) {
	for i := range b {
		b[i] = 0
	}
}
