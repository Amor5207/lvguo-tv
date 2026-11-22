package lvguoplay

// 手机号 + 验证码登录（passport），含红果风控要求的上行短信二次验证。
// 流程与签名同 TV 端 HgAccount，协议参照 aston314/hongguo-tv 的 passport_client。

import (
	"bytes"
	"context"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"sync"
	"time"
)

// PhoneLogin 是一次手机号登录会话：同一台注册设备、同一组 cookie 贯穿发码、登录与二次验证。
type PhoneLogin struct {
	mu    sync.Mutex
	c     *passportClient
	phone string // +86 开头
	code  string
	mfa   *PhoneMFA
}

// PhoneMFA 是上行短信验证的要求：用登录手机向 Number 发送 Content。
type PhoneMFA struct {
	Number, Content                   string
	encryptUID, codeKey, logID, scene string
	started                           time.Time
}

// ErrMFAPending 表示服务端还没收到用户发出的验证短信。
var ErrMFAPending = errors.New("正在等待短信验证")

// MFAError contains only the stage, numeric status and elapsed time; no identity,
// verification code, ticket, SMS recipient or content is logged.
type MFAError struct {
	Stage, Code string
	Age         time.Duration
	Err         error
}

func (e *MFAError) Error() string { return e.Err.Error() }
func (e *MFAError) Unwrap() error { return e.Err }

var numericErrorCode = regexp.MustCompile(`^[0-9]{1,10}$`)

func mfaError(stage string, m *PhoneMFA, response map[string]any, err error) error {
	code := loginErrCode(response)
	if !numericErrorCode.MatchString(code) {
		code = "unknown"
	}
	var age time.Duration
	if m != nil && !m.started.IsZero() {
		age = time.Since(m.started).Round(time.Second)
	}
	return &MFAError{Stage: stage, Code: code, Age: age, Err: err}
}
func loginSuccess(response map[string]any) bool {
	code := loginErrCode(response)
	return asString(response["message"]) == "success" && (code == "" || code == "0")
}
func expiredMFA(response map[string]any) bool {
	d := loginData(response)
	desc := asString(d["description"])
	return strings.Contains(desc, "过期") || strings.Contains(desc, "失效") || strings.Contains(desc, "长时间未操作")
}

// MFARequired 表示登录需要先完成上行短信验证。
type MFARequired struct{ MFA *PhoneMFA }

func (e *MFARequired) Error() string { return "为了账号安全，需要完成短信验证" }

var phoneDigits = regexp.MustCompile(`\D`)
var smsCodeRE = regexp.MustCompile(`^[0-9]{4,8}$`)
var privateDigitsRE = regexp.MustCompile(`[0-9]{7,}`)

// NormalizeCN 把输入归一成 +86 手机号；不是大陆手机号返回空串。
func NormalizeCN(raw string) string {
	s := phoneDigits.ReplaceAllString(raw, "")
	if strings.HasPrefix(s, "86") && len(s) == 13 {
		s = s[2:]
	}
	if len(s) != 11 || s[0] != '1' {
		return ""
	}
	return "+86" + s
}

// NewPhoneLogin 注册一台新设备，准备好登录会话。
func NewPhoneLogin(ctx context.Context) (*PhoneLogin, error) {
	d, err := newPassportDevice()
	if err != nil {
		return nil, err
	}
	c := newPassportClient(accountSession{Device: d, Cookies: map[string]string{}})
	if err := c.registerDevice(ctx); err != nil {
		return nil, errors.New("设备注册失败，请稍后重试")
	}
	return &PhoneLogin{c: c}, nil
}

func xor5(s string) string {
	b := []byte(s)
	for i := range b {
		b[i] ^= 5
	}
	return hex.EncodeToString(b)
}

func (p *PhoneLogin) post(ctx context.Context, path string, form url.Values) (map[string]any, error) {
	c := p.c
	body := []byte(form.Encode())
	stamp := time.Now().UnixMilli()
	raw, h, err := signPassportRequest(passportQuery(c.session), body, stamp, c.session.DeviceID)
	if err != nil {
		return nil, errors.New("请求签名失败")
	}
	r, err := http.NewRequestWithContext(ctx, http.MethodPost, c.base+path+"?"+raw, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	r.Header = h
	r.Header.Set("User-Agent", defaultHongguoUA)
	r.Header.Set("Accept", "application/json")
	r.Header.Set("X-XS-From-Web", "0")
	r.Header.Set("Sdk-Version", "2")
	r.Header.Set("Passport-Sdk-Version", "5051452")
	r.Header.Set("X-Vc-Bdturing-Sdk-Version", "4.0.3.cn")
	r.Header.Set("Lc", "101")
	r.Header.Set("X-SS-DP", "8662")
	region := c.session.Cookies["store-region"]
	if region == "" {
		region = "cn"
	}
	r.Header.Set("X-Tt-Store-Region", region)
	r.Header.Set("X-Tt-Store-Region-Src", "did")
	r.Header.Set("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
	if c.session.Token != "" {
		r.Header.Set("X-Tt-Token", c.session.Token)
	}
	for k, v := range c.session.Cookies {
		r.AddCookie(&http.Cookie{Name: k, Value: v})
	}
	resp, err := c.http.Do(r)
	if err != nil {
		return nil, errors.New("红果账号服务连接失败，请稍后重试")
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("红果账号服务暂不可用（%d）", resp.StatusCode)
	}
	b, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil || len(bytes.TrimSpace(b)) == 0 {
		return nil, errors.New("红果账号服务没有返回内容")
	}
	var out map[string]any
	dec := json.NewDecoder(bytes.NewReader(b))
	dec.UseNumber()
	if dec.Decode(&out) != nil || out == nil {
		return nil, errors.New("红果账号服务返回异常")
	}
	for _, ck := range resp.Cookies() {
		if ck.MaxAge < 0 {
			delete(c.session.Cookies, ck.Name)
		} else {
			c.session.Cookies[ck.Name] = ck.Value
		}
	}
	if t := resp.Header.Get("X-Tt-Token"); t != "" {
		c.session.Token = t
	}
	return out, nil
}

func loginData(p map[string]any) map[string]any { d, _ := asMap(p["data"]); return d }

func loginErrCode(p map[string]any) string {
	if d := loginData(p); d != nil {
		if x, ok := d["error_code"]; ok {
			return asString(x)
		}
	}
	return asString(p["error_code"])
}

// loginFailure 只把服务端给的简短说明带给界面，不回显链接、票据或长串数字。
func loginFailure(p map[string]any, fallback string) error {
	msg := ""
	if d := loginData(p); d != nil {
		msg = asString(d["description"])
	}
	if msg == "" {
		msg = asString(p["description"])
	}
	if strings.ContainsAny(msg, "\r\n") || strings.Contains(msg, "http") || len([]rune(msg)) > 120 {
		msg = ""
	}
	if msg == "" {
		return errors.New(fallback)
	}
	return errors.New(privateDigitsRE.ReplaceAllString(msg, "***"))
}

func loginForm(phone string) url.Values {
	return url.Values{"mobile": {xor5(phone)}, "account_sdk_source": {"app"}, "passport_support_flow": {"captcha,verify"}, "mix_mode": {"1"}}
}

// SendCode 向手机号发送登录验证码。
func (p *PhoneLogin) SendCode(ctx context.Context, phone string) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	n := NormalizeCN(phone)
	if n == "" {
		return errors.New("请输入正确的 11 位手机号")
	}
	f := loginForm(n)
	f.Set("auto_read", "0")
	f.Set("unbind_exist", "34")
	f.Set("type", xor5("24"))
	r, err := p.post(ctx, "/passport/mobile/send_code/v1/", f)
	if err != nil {
		return err
	}
	if !loginSuccess(r) {
		return loginFailure(r, "验证码发送失败")
	}
	p.phone, p.code, p.mfa = n, "", nil
	return nil
}

func parsePhoneMFA(data map[string]any) *PhoneMFA {
	if data == nil {
		return nil
	}
	m := &PhoneMFA{encryptUID: asString(data["encrypt_uid"]), codeKey: asString(data["sms_code_key"]), scene: asString(data["passport_scene"])}
	if ev, ok := asMap(data["event_params"]); ok {
		m.logID = asString(ev["log_id"])
	}
	ways, _ := data["verify_ways"].([]any)
	for _, v := range ways {
		w, ok := asMap(v)
		if !ok {
			continue
		}
		if asString(w["verify_way"]) == "upsms" || asString(w["sms_content"]) != "" {
			m.Content, m.Number = asString(w["sms_content"]), asString(w["channel_mobile"])
			break
		}
	}
	if m.encryptUID == "" || m.codeKey == "" || m.Number == "" || m.Content == "" {
		return nil
	}
	return m
}

func (p *PhoneLogin) session() (map[string]string, AccountDevice, error) {
	ck := p.c.session.Cookies
	if ck["sessionid"] == "" && ck["sessionid_ss"] == "" {
		return nil, AccountDevice{}, errors.New("登录未返回会话，请重试")
	}
	out := map[string]string{}
	for k, v := range ck {
		out[k] = v
	}
	dev := AccountDevice{DeviceID: p.c.session.DeviceID, InstallID: p.c.session.InstallID}
	if d := p.c.session.Device; d != nil {
		dev.CDID, dev.OpenUDID, dev.ClientUDID = d.CDID, d.OpenUDID, d.ClientUDID
	}
	return out, dev, nil
}

func (p *PhoneLogin) smsLogin(ctx context.Context, ticket string) (map[string]any, error) {
	f := loginForm(p.phone)
	f.Set("code", xor5(p.code))
	if ticket != "" {
		if p.mfa == nil || p.mfa.codeKey == "" {
			return nil, errors.New("验证会话已结束，请重新获取验证码")
		}
		// The original SMS challenge key and the verified ticket are distinct.
		f.Set("passport_mfa_retry_tag", "1")
		f.Set("sms_code_key", xor5(p.mfa.codeKey))
		f.Set("verify_ticket", ticket)
	}
	return p.post(ctx, "/passport/mobile/sms_login/", f)
}

// Login 用验证码登录。成功返回会话 cookie 与登录所用设备；需要二次验证时返回 *MFARequired。
func (p *PhoneLogin) Login(ctx context.Context, code string) (map[string]string, AccountDevice, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.phone == "" {
		return nil, AccountDevice{}, errors.New("请先获取验证码")
	}
	if !smsCodeRE.MatchString(code) {
		return nil, AccountDevice{}, errors.New("请输入短信里的验证码")
	}
	p.code = code
	r, err := p.smsLogin(ctx, "")
	if err != nil {
		return nil, AccountDevice{}, err
	}
	if !loginSuccess(r) {
		if m := parsePhoneMFA(loginData(r)); m != nil {
			m.started = time.Now()
			p.mfa = m
			return nil, AccountDevice{}, &MFARequired{MFA: m}
		}
		return nil, AccountDevice{}, loginFailure(r, "登录失败，请重试")
	}
	return p.session()
}

// VerifyMFA 在用户发出验证短信后调用：服务端还没收到返回 ErrMFAPending；通过则带票据完成登录。
func (p *PhoneLogin) VerifyMFA(ctx context.Context) (map[string]string, AccountDevice, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	m := p.mfa
	if m == nil {
		return nil, AccountDevice{}, errors.New("验证已结束，请重新获取验证码")
	}
	biz, _ := json.Marshal(map[string]string{"passport_mfa_retry_tag": "1", "sms_code_key": m.codeKey})
	f := url.Values{"encrypt_uid": {m.encryptUID}, "verify_ticket": {""}, "verify_scene": {"sms_login"}, "verify_reason": {"ato"},
		"copywriting_key": {"sms_login"}, "ies_safety_diversion_tag": {"mfa"}, "new_verify_flow": {""}, "new_authn_sdk_version": {"1.1.31"},
		"new_authn_sdk_verify_reason": {"ato"}, "new_authn_sdk_verify_scene": {"sms_login"}, "request_tag_from": {"h5"},
		"biz_params": {string(biz)}, "new_authn_sdk_log_id": {m.logID}}
	r, err := p.post(ctx, "/passport/upsms/verify/", f)
	if err != nil {
		return nil, AccountDevice{}, err
	}
	d := loginData(r)
	if loginErrCode(r) == "1045" {
		return nil, AccountDevice{}, ErrMFAPending
	}
	if !loginSuccess(r) {
		if expiredMFA(r) {
			p.mfa = nil
			return nil, AccountDevice{}, mfaError("verify", m, r, errors.New("红果已结束本次短信验证，请重新获取验证码"))
		}
		return nil, AccountDevice{}, mfaError("verify", m, r, loginFailure(r, "短信验证未通过"))
	}
	ticket := asString(d["ticket"])
	if ticket == "" {
		ticket = asString(d["verify_ticket"])
	}
	if ticket == "" {
		return nil, AccountDevice{}, errors.New("验证响应缺少登录票据")
	}
	lr, err := p.smsLogin(ctx, ticket)
	if err != nil {
		return nil, AccountDevice{}, err
	}
	if !loginSuccess(lr) {
		if expiredMFA(lr) {
			p.mfa = nil
			return nil, AccountDevice{}, mfaError("login_after_verify", m, lr, errors.New("红果已结束本次短信验证，请重新获取验证码"))
		}
		return nil, AccountDevice{}, mfaError("login_after_verify", m, lr, loginFailure(lr, "验证后登录失败，请重试"))
	}
	p.mfa = nil
	return p.session()
}
