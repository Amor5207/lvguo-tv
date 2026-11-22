package server

import (
	"context"
	"encoding/base64"
	"errors"
	"log"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"time"
	"unicode"

	qrcode "github.com/skip2/go-qrcode"

	"lvguo/internal/lvguo"
	"lvguo/internal/lvguoplay"
)

// 手机号 + 验证码登录：发码、登录、（红果风控要求时）上行短信二次验证。

func (s *Server) phoneSend(ss *sess, w http.ResponseWriter, r *http.Request) (any, error) {
	var in struct{ Phone string }
	if err := decode(r, &in); err != nil {
		return nil, err
	}
	if lvguoplay.NormalizeCN(in.Phone) == "" {
		return nil, bad("请输入正确的 11 位手机号")
	}
	// 发码会真的给这个号码发短信：按来源 IP 与号码双重限速
	if !s.limiter.allow("sms:"+clientIP(r), 2, 3) || !s.limiter.allow("smsn:"+lvguoplay.NormalizeCN(in.Phone), 1, 1) {
		return nil, &httpErr{429, "获取太频繁，请稍后再试"}
	}
	if s.dev {
		return view{"ok": true}, nil
	}
	ctx, cancel := context.WithTimeout(r.Context(), 40*time.Second)
	defer cancel()
	s.mu.Lock()
	pl := ss.phone
	s.mu.Unlock()
	if pl == nil {
		var err error
		if pl, err = lvguoplay.NewPhoneLogin(ctx); err != nil {
			return nil, err
		}
		s.mu.Lock()
		ss.phone = pl
		s.mu.Unlock()
	}
	if err := pl.SendCode(ctx, in.Phone); err != nil {
		return nil, bad(err.Error())
	}
	return view{"ok": true}, nil
}

// phoneDone 登录成功：会话换成新账号，并沿用登录所用的那台设备。
func (s *Server) phoneDone(w http.ResponseWriter, r *http.Request, ss *sess, jar map[string]string, dev lvguoplay.AccountDevice) view {
	s.rotateSession(w, r, ss)
	store := s.ownDevice(ss.id)
	store.Adopt(lvguo.Device{ID: dev.DeviceID, IID: dev.InstallID, CDID: dev.CDID, OpenUDID: dev.OpenUDID, ClientUDID: dev.ClientUDID})
	ss.acct.SetDev(store)
	ss.acct.U.Set(jar)
	go ss.acct.RefreshSettings()
	s.mu.Lock()
	ss.phone, ss.qr = nil, nil
	s.mu.Unlock()
	return view{"state": "ok"}
}

func (s *Server) phoneResult(w http.ResponseWriter, r *http.Request, ss *sess, jar map[string]string, dev lvguoplay.AccountDevice, err error) (any, error) {
	var need *lvguoplay.MFARequired
	switch {
	case err == nil:
		return s.phoneDone(w, r, ss, jar, dev), nil
	case errors.As(err, &need):
		log.Printf("手机号登录：收到短信验证挑战")
		return view{"state": "mfa", "number": need.MFA.Number, "content": need.MFA.Content, "qr": smsQR(need.MFA.Number, need.MFA.Content)}, nil
	case errors.Is(err, lvguoplay.ErrMFAPending):
		return view{"state": "pending"}, nil
	}
	return nil, bad(err.Error())
}

var smsRecipient = regexp.MustCompile(`^\+?[0-9]{5,20}$`)

// smsQR 生成手机可识别的 HTTPS 入口二维码，短信参数仅放在 URL 片段中。
// 只包含服务端给的收件号码与短信内容，不含登录手机号、验证码或任何凭证。
func smsTarget(number, content string) string {
	number = strings.ReplaceAll(number, " ", "")
	if !smsRecipient.MatchString(number) || content == "" || len(content) > 256 || strings.IndexFunc(content, unicode.IsControl) >= 0 {
		return ""
	}
	params := url.Values{"n": {number}, "m": {content}}
	return "https://lvguo.pengfei.ink/shared/sms.html#" + params.Encode()
}
func smsQR(number, content string) string {
	// URL fragments are not sent to our server as request data.
	target := smsTarget(number, content)
	if target == "" {
		return ""
	}
	png, err := qrcode.Encode(target, qrcode.Medium, 320)
	if err != nil {
		return ""
	}
	return "data:image/png;base64," + base64.StdEncoding.EncodeToString(png)
}

func (s *Server) phoneLogin(ss *sess, w http.ResponseWriter, r *http.Request) (any, error) {
	var in struct{ Code string }
	if err := decode(r, &in); err != nil {
		return nil, err
	}
	if !s.limiter.allow("smsl:"+clientIP(r), 6, 6) {
		return nil, &httpErr{429, "尝试太频繁，请稍后再试"}
	}
	s.mu.Lock()
	pl := ss.phone
	s.mu.Unlock()
	if pl == nil {
		return nil, bad("请先获取验证码")
	}
	ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
	defer cancel()
	jar, dev, err := pl.Login(ctx, in.Code)
	return s.phoneResult(w, r, ss, jar, dev, err)
}

func (s *Server) phoneVerify(ss *sess, w http.ResponseWriter, r *http.Request) (any, error) {
	s.mu.Lock()
	pl := ss.phone
	s.mu.Unlock()
	if pl == nil {
		return nil, bad("验证已结束，请重新获取验证码")
	}
	ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
	defer cancel()
	jar, dev, err := pl.VerifyMFA(ctx)
	switch {
	case err == nil:
		log.Printf("手机号登录：短信验证通过")
	case errors.Is(err, lvguoplay.ErrMFAPending):
		log.Printf("手机号登录：短信验证尚未完成（上游未收到短信）")
	default:
		var upstream *lvguoplay.MFAError
		if errors.As(err, &upstream) {
			log.Printf("手机号登录：短信验证失败 stage=%s code=%s age=%s", upstream.Stage, upstream.Code, upstream.Age)
		} else {
			log.Printf("手机号登录：短信验证请求失败")
		}
	}
	return s.phoneResult(w, r, ss, jar, dev, err)
}
