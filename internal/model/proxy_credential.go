package model

// ProxyCredential 代理认证凭据。密码以内存字节切片保存，替换或销毁时清零。
type ProxyCredential struct {
	Username string
	password []byte
}

func NewProxyCredential(username, password string) *ProxyCredential {
	c := &ProxyCredential{Username: username}
	c.SetPassword(password)
	return c
}

func (c *ProxyCredential) SetPassword(v string) {
	c.ClearPassword()
	if v != "" {
		c.password = []byte(v)
	}
}

func (c *ProxyCredential) SetPasswordBytes(v []byte) {
	c.ClearPassword()
	if len(v) > 0 {
		c.password = append([]byte(nil), v...)
	}
}

func (c *ProxyCredential) Password() string { return string(c.password) }

func (c *ProxyCredential) CopyPassword() []byte { return append([]byte(nil), c.password...) }

func (c *ProxyCredential) HasPassword() bool { return len(c.password) > 0 }

func (c *ProxyCredential) ClearPassword() {
	ClearBytes(c.password)
	c.password = nil
}
