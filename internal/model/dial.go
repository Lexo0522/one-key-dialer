package model

// DialCredentials 一次性拨号凭据：用完即清零，任何地方都不缓存密码。
type DialCredentials struct {
	Username string
	password []byte
}

// NewDialCredentials 构造凭据（复制密码字节，调用方自行清零入参）。
func NewDialCredentials(username string, password []byte) *DialCredentials {
	c := &DialCredentials{Username: username}
	if len(password) > 0 {
		c.password = make([]byte, len(password))
		copy(c.password, password)
	}
	return c
}

// PasswordBytes 返回密码字节（只读使用）。
func (c *DialCredentials) PasswordBytes() []byte { return c.password }

// Clear 清零凭据。
func (c *DialCredentials) Clear() {
	ClearBytes(c.password)
	c.password = nil
}
