package model

// PortalCredential 校园网门户认证凭据（与拨号账号相互独立）。
// 密码以 []byte 保存并在替换/销毁时清零，语义同 Account。
type PortalCredential struct {
	Username string

	password []byte
}

// NewPortalCredential 构造门户认证凭据。
func NewPortalCredential(username, password string) *PortalCredential {
	c := &PortalCredential{Username: username}
	c.SetPassword(password)
	return c
}

// SetPassword 覆盖式设置密码（旧值先清零）。
func (c *PortalCredential) SetPassword(v string) {
	c.ClearPassword()
	if v == "" {
		c.password = nil
		return
	}
	c.password = []byte(v)
}

// SetPasswordBytes 覆盖式设置密码，入参由调用方负责清零。
func (c *PortalCredential) SetPasswordBytes(v []byte) {
	c.ClearPassword()
	if len(v) == 0 {
		c.password = nil
		return
	}
	c.password = make([]byte, len(v))
	copy(c.password, v)
}

// Password 返回明文密码（调用方不得记录到日志）。
func (c *PortalCredential) Password() string {
	if len(c.password) == 0 {
		return ""
	}
	return string(c.password)
}

// CopyPassword 返回密码副本，调用方用后应调用 ClearBytes。
func (c *PortalCredential) CopyPassword() []byte {
	if len(c.password) == 0 {
		return nil
	}
	out := make([]byte, len(c.password))
	copy(out, c.password)
	return out
}

// HasPassword 判断是否已设置密码。
func (c *PortalCredential) HasPassword() bool {
	return len(c.password) > 0
}

// ClearPassword 清零并释放密码。
func (c *PortalCredential) ClearPassword() {
	ClearBytes(c.password)
	c.password = nil
}
