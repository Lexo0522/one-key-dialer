package service

import (
	"crypto/hmac"
	"crypto/md5"
	"crypto/sha1"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"

	"github.com/Lexo0522/one-key-dialer/internal/i18n"
	"github.com/Lexo0522/one-key-dialer/internal/model"
)

// ============================ 深澜(Srun)门户协议 ============================
//
// 标准深澜登录分两步:
//  1. GET {base}/cgi-bin/get_challenge?username=..&ip=.. 取动态 challenge(即 token);
//  2. 以 token 为密钥计算 info(xEncode + 自定义 base64)、hmd5(HMAC-MD5)、
//     chksum(SHA1) 后,GET {base}/cgi-bin/srun_portal?action=login&.. 提交。
//
// 算法按公开参考实现校准(iskoldt-X/SRUN-authenticator、Mmx233/BitSrunLoginGo,
// 两者逐位一致),并用参考实现生成的向量钉死在 portalsrun_test.go。
// 各校部署存在变体(如 chksum 用 md5 的旧版),失败时以响应 error/error_msg 提示,
// 登录地址与 ac_id 均可由用户改写兜底。

const (
	srunBase64Alpha = "LVoJPiCN2R8G90yg+hmFHuacZ1OWMnrsSTXkYpUq/3dlbfKwv6xztjI7DeBE45QA"
	srunEncVer      = "srun_bx1"
	srunN           = "200"
	srunType        = "1"
)

// srunBase64 门户页自带 jquery.base64 的 Go 等价实现:
// 标准分组,但使用深澜自定义字母表,余数位补 '='。
func srunBase64(data []byte) string {
	var b strings.Builder
	for i := 0; i < len(data); i += 3 {
		rem := len(data) - i
		a := uint32(data[i]) << 16
		if rem >= 2 {
			a |= uint32(data[i+1]) << 8
		}
		if rem >= 3 {
			a |= uint32(data[i+2])
		}
		b.WriteByte(srunBase64Alpha[(a>>18)&63])
		b.WriteByte(srunBase64Alpha[(a>>12)&63])
		if rem >= 2 {
			b.WriteByte(srunBase64Alpha[(a>>6)&63])
		} else {
			b.WriteByte('=')
		}
		if rem >= 3 {
			b.WriteByte(srunBase64Alpha[a&63])
		} else {
			b.WriteByte('=')
		}
	}
	return b.String()
}

// srunOrdat 取 msg 第 idx 字节,越界返回 0(对应门户页 ordat)。
func srunOrdat(msg string, idx int) uint32 {
	if idx < len(msg) {
		return uint32(msg[idx])
	}
	return 0
}

// srunSencode 门户页 s():字符串按小端 4 字节一组打包,includeLength 时末尾追加原始长度。
func srunSencode(msg string, includeLength bool) []uint32 {
	length := len(msg)
	words := make([]uint32, (length+3)/4)
	for i := 0; i < length; i++ {
		words[i>>2] |= srunOrdat(msg, i) << (uint(i&3) * 8)
	}
	if includeLength {
		words = append(words, uint32(length))
	}
	return words
}

// srunLencode 门户页 lencode(key=false):每组还原为 4 字节小端。
func srunLencode(words []uint32) []byte {
	out := make([]byte, 0, len(words)*4)
	for _, w := range words {
		out = append(out, byte(w), byte(w>>8), byte(w>>16), byte(w>>24))
	}
	return out
}

// srunXEncode 门户页 xEncode 的 Go 移植:TEA 变体,以 key 为轮密钥加密 content。
// 参考实现中的 0x...|0x... 常量是 JS 的 32 位回绕掩码,Go 的 uint32 溢出天然等价。
func srunXEncode(content, key string) []byte {
	if content == "" {
		return nil
	}
	pwd := srunSencode(content, true)
	pwdk := srunSencode(key, false)
	for len(pwdk) < 4 {
		pwdk = append(pwdk, 0)
	}
	n := len(pwd) - 1
	z := pwd[n]
	delta := uint32(0x86014019 | 0x183639A0) // 0x9E3779B9(TEA 黄金比例常量)
	q := 6 + 52/(n+1)
	for d := uint32(0); q > 0; q-- {
		d += delta
		e := (d >> 2) & 3
		for p := 0; p < n; p++ {
			y := pwd[p+1]
			m := (z>>5 ^ y<<2) + ((y>>3 ^ z<<4) ^ (d ^ y)) + (pwdk[uint32(p&3)^e] ^ z)
			pwd[p] += m
			z = pwd[p]
		}
		y := pwd[0]
		m := (z>>5 ^ y<<2) + ((y>>3 ^ z<<4) ^ (d ^ y)) + (pwdk[uint32(n&3)^e] ^ z)
		pwd[n] += m
		z = pwd[n]
	}
	return srunLencode(pwd)
}

// srunInfoJSON 与门户页一致的 info 明文:固定字段序、无空格紧凑 JSON。
func srunInfoJSON(username, password, ip, acid string) string {
	type infoStruct struct {
		Username string `json:"username"`
		Password string `json:"password"`
		IP       string `json:"ip"`
		Acid     string `json:"acid"`
		EncVer   string `json:"enc_ver"`
	}
	var b strings.Builder
	enc := json.NewEncoder(&b)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(infoStruct{username, password, ip, acid, srunEncVer}); err != nil {
		return ""
	}
	return strings.TrimRight(b.String(), "\n")
}

// srunHmd5 = hex(HMAC-MD5(key=challenge, msg=password)),门户页 hmd5。
func srunHmd5(token, password string) string {
	m := hmac.New(md5.New, []byte(token))
	m.Write([]byte(password))
	return hex.EncodeToString(m.Sum(nil))
}

// srunChksum = hex(SHA1(token+username+token+hmd5+token+acid+token+ip+
// token+n+token+type+token+info)),门户页 chksum。
func srunChksum(token, username, hmd5, acid, ip, info string) string {
	var b strings.Builder
	b.WriteString(token)
	b.WriteString(username)
	b.WriteString(token)
	b.WriteString(hmd5)
	b.WriteString(token)
	b.WriteString(acid)
	b.WriteString(token)
	b.WriteString(ip)
	b.WriteString(token)
	b.WriteString(srunN)
	b.WriteString(token)
	b.WriteString(srunType)
	b.WriteString(token)
	b.WriteString(info)
	sum := sha1.Sum([]byte(b.String()))
	return hex.EncodeToString(sum[:])
}

// srunAPIResp get_challenge / srun_portal 响应的公共字段。
type srunAPIResp struct {
	Error     string `json:"error"`
	Ecode     int    `json:"ecode"`
	Challenge string `json:"challenge"`
	ClientIP  string `json:"client_ip"`
	EchoMsg   string `json:"echo_msg"`
	ErrorMsg  string `json:"error_msg"`
	Res       string `json:"res"`
}

// srunErrText 汇总 srun 响应中的错误信息,便于日志与测试回显。
func srunErrText(r srunAPIResp) string {
	parts := make([]string, 0, 3)
	if r.Error != "" {
		parts = append(parts, "error="+r.Error)
	}
	if r.ErrorMsg != "" {
		parts = append(parts, "error_msg="+r.ErrorMsg)
	}
	if r.EchoMsg != "" {
		parts = append(parts, "echo_msg="+r.EchoMsg)
	}
	if len(parts) == 0 {
		return "(empty)"
	}
	return strings.Join(parts, " ")
}

// stripJSONP 剥掉 "cb({...})" 的 JSONP 包装;非包装原样返回。
func stripJSONP(text string) string {
	text = strings.TrimSpace(text)
	if open := strings.IndexByte(text, '('); open >= 0 && strings.HasSuffix(text, ")") {
		return text[open+1 : len(text)-1]
	}
	return text
}

// srunBaseUrl 归一深澜门户根地址:渲染模板后剥离已知 cgi-bin 接口后缀
// (用户可能粘贴完整接口地址或带尾斜杠)。
func srunBaseUrl(loginUrl, username, password, portalURL string) string {
	base := strings.TrimSpace(loginUrl)
	if base == "" {
		base = derivePortalParams(portalURL).portalBase
	} else {
		base = RenderPortalTemplate(base, username, password, portalURL)
	}
	base = strings.TrimRight(base, "/")
	for _, suffix := range []string{
		"/cgi-bin/srun_portal.php", "/cgi-bin/srun_portal",
		"/cgi-bin/get_challenge", "/cgi-bin/rad_user_info",
	} {
		base = strings.TrimSuffix(base, suffix)
	}
	return base
}

// executeSrunAuth 执行深澜两步登录(get_challenge → srun_portal)。
// 登录地址填门户根地址(如 http://10.1.1.55 或 {portalbase});留空时由门户地址派生。
func executeSrunAuth(cfg PortalAuthConfig, portalURL, username, password string) PortalAuthOutcome {
	fail := func(detail string) PortalAuthOutcome {
		return PortalAuthOutcome{Success: false, Detail: detail}
	}
	base := srunBaseUrl(cfg.LoginUrl, username, password, portalURL)
	if base == "" {
		return fail(i18n.T("portal.noLoginUrl"))
	}
	if u, err := url.Parse(base); err != nil || u.Host == "" {
		return fail("bad login url")
	}
	params := derivePortalParams(portalURL)
	client := portalHTTPClient(cfg)

	httpGet := func(desc, rawURL string) (string, error) {
		req, err := http.NewRequest(http.MethodGet, rawURL, nil)
		if err != nil {
			return "", err
		}
		req.Header.Set("User-Agent", model.UserAgent())
		for _, h := range cfg.Headers {
			req.Header.Set(h[0], h[1])
		}
		resp, err := client.Do(req)
		if err != nil {
			return "", err
		}
		defer resp.Body.Close()
		raw, _ := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
		if resp.StatusCode < 200 || resp.StatusCode >= 400 {
			return "", fmt.Errorf("HTTP %s", resp.Status)
		}
		return string(raw), nil
	}
	parse := func(desc, raw string) (srunAPIResp, PortalAuthOutcome) {
		var r srunAPIResp
		if err := json.Unmarshal([]byte(stripJSONP(raw)), &r); err != nil {
			return r, fail(desc + ": bad json")
		}
		return r, PortalAuthOutcome{}
	}

	// 第一步:get_challenge 取动态 challenge(即 token)。
	challengeDesc := i18n.T("portal.srunChallenge")
	q := url.Values{}
	q.Set("username", username)
	if params.userIP != "" {
		q.Set("ip", params.userIP)
	}
	raw, err := httpGet(challengeDesc, base+"/cgi-bin/get_challenge?"+q.Encode())
	if err != nil {
		return fail(challengeDesc + ": " + err.Error())
	}
	ch, o := parse(challengeDesc, raw)
	if o.Detail != "" {
		return o
	}
	if ch.Error != "ok" || ch.Challenge == "" {
		return fail(i18n.Tf("portal.srunChallengeFailed", srunErrText(ch)))
	}
	token := ch.Challenge
	ip := ch.ClientIP
	if ip == "" {
		ip = params.userIP
	}
	if ip == "" {
		return fail(i18n.Tf("portal.srunChallengeFailed", "no client ip"))
	}

	// 第二步:按门户页算法计算 info/hmd5/chksum 后提交 srun_portal。
	info := "{SRBX1}" + srunBase64(srunXEncode(srunInfoJSON(username, password, ip, params.acid), token))
	hmd5 := srunHmd5(token, password)
	vs := url.Values{}
	vs.Set("action", "login")
	vs.Set("username", username)
	vs.Set("password", "{MD5}"+hmd5)
	vs.Set("ac_id", params.acid)
	vs.Set("ip", ip)
	vs.Set("info", info)
	vs.Set("chksum", srunChksum(token, username, hmd5, params.acid, ip, info))
	vs.Set("n", srunN)
	vs.Set("type", srunType)
	vs.Set("os", "Windows 10")
	vs.Set("name", "Windows")
	vs.Set("double_stack", "0")

	loginDesc := i18n.T("portal.srunLogin")
	raw, err = httpGet(loginDesc, base+"/cgi-bin/srun_portal?"+vs.Encode())
	if err != nil {
		return fail(loginDesc + ": " + err.Error())
	}
	lr, o := parse(loginDesc, raw)
	if o.Detail != "" {
		return o
	}
	outcome := PortalAuthOutcome{Success: lr.Error == "ok"}
	outcome.Detail = loginDesc + ": " + srunErrText(lr)
	if snippet := firstLine(raw, 240); snippet != "" {
		outcome.Detail += " | " + snippet
	}
	if outcome.Success && strings.TrimSpace(cfg.SuccessHint) != "" {
		outcome.Success = strings.Contains(raw, cfg.SuccessHint)
	}
	return outcome
}
