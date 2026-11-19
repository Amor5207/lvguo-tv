package hg

import (
	"errors"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"lvguo/internal/sign"
)

// User 是一个浏览器会话对应的红果登录态（cookie 罐）。
type User struct {
	mu  sync.Mutex
	Jar map[string]string `json:"jar"`
	Gen int64             `json:"-"`
}

func NewUser() *User { return &User{Jar: map[string]string{}} }

func (u *User) LoggedIn() bool {
	u.mu.Lock()
	defer u.mu.Unlock()
	return u.Jar["sessionid"] != "" || u.Jar["sessionid_ss"] != ""
}

func (u *User) Cookie() string {
	u.mu.Lock()
	defer u.mu.Unlock()
	return cookieString(u.Jar)
}

func cookieString(jar map[string]string) string {
	keys := make([]string, 0, len(jar))
	for k := range jar {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	parts := make([]string, len(keys))
	for i, k := range keys {
		parts[i] = k + "=" + jar[k]
	}
	return strings.Join(parts, "; ")
}

// Absorb 吸收 Set-Cookie（心跳续期就靠这个把到期时间往后推）。
func (u *User) Absorb(setCookies []string) bool {
	changed := false
	u.mu.Lock()
	defer u.mu.Unlock()
	for _, c := range setCookies {
		first := strings.SplitN(c, ";", 2)[0]
		eq := strings.IndexByte(first, '=')
		if eq <= 0 {
			continue
		}
		k, v := strings.TrimSpace(first[:eq]), strings.TrimSpace(first[eq+1:])
		if v == "" || strings.EqualFold(v, "deleted") {
			continue
		}
		if u.Jar[k] != v {
			u.Jar[k] = v
			changed = true
		}
	}
	return changed
}

func (u *User) Set(jar map[string]string) {
	cp := make(map[string]string, len(jar))
	for k, v := range jar {
		cp[k] = v
	}
	u.mu.Lock()
	u.Jar = cp
	u.mu.Unlock()
}

// Snapshot 返回 cookie 罐的只读副本，避免并发序列化时读写 map 冲突。
func (u *User) Snapshot() map[string]string {
	u.mu.Lock()
	defer u.mu.Unlock()
	cp := make(map[string]string, len(u.Jar))
	for k, v := range u.Jar {
		cp[k] = v
	}
	return cp
}

// ExpiresAt 返回会话到期时间（sid_guard = "sid|签发时间|有效秒数|到期日"）。
func (u *User) ExpiresAt() time.Time {
	u.mu.Lock()
	g := u.Jar["sid_guard"]
	u.mu.Unlock()
	g, _ = url.QueryUnescape(g)
	p := strings.Split(g, "|")
	if len(p) >= 3 {
		a, e1 := strconv.ParseInt(p[1], 10, 64)
		b, e2 := strconv.ParseInt(p[2], 10, 64)
		if e1 == nil && e2 == nil {
			return time.Unix(a+b, 0)
		}
	}
	return time.Time{}
}

// ------------------------------------------------------------ 扫码登录

const qrBase = "https://hongguoduanju.com"
const webUA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

type QRState string

const (
	QRWaiting   QRState = "waiting"
	QRScanned   QRState = "scanned"
	QRConfirmed QRState = "confirmed"
	QRExpired   QRState = "expired"
	QRCanceled  QRState = "canceled"
)

// QR 是一次扫码登录流程（官网通道，约 60 秒有效）。
type QR struct {
	jar       map[string]string
	token     string
	Image     []byte
	ExpiresAt time.Time
}

// OpenURL 返回二维码里编码的同一个红果登录链接（手机上直接打开即可拉起红果 App 确认，无需扫码）。
// 该链接是固定模板，只有 token 变（2026-11-08 解码确认）。
func (q *QR) OpenURL() string {
	if q.token == "" {
		return ""
	}
	return "https://reading.snssdk.com/reading_offline/novelread/page/scan-code.html" +
		"?custom_brightness=1&hide_nav_bar=1&hide_status_bar=1&need_custom_brightness=1" +
		"&next_url=https%3A%2F%2Freading.snssdk.com%2Freading_offline%2Fnovelread%2Fpage%2Fscan-code.html" +
		"&qr_source_aid=513755&token=" + q.token + "&version_code=5.8.3"
}

func qrCommon() sign.Params {
	return sign.Params{{K: "aid", V: "513755"}, {K: "passport_jssdk_version", V: "3.4.10"}, {K: "passport_jssdk_type", V: "normal"},
		{K: "account_sdk_source", V: "web"}, {K: "is_from_ttaccountsdk", V: "1"}, {K: "language", V: "zh"}, {K: "next", V: qrBase + "/"}}
}

func (q *QR) headers() [][2]string {
	h := [][2]string{{"User-Agent", webUA}, {"Referer", qrBase + "/"}, {"Origin", qrBase}, {"Accept", "application/json, text/javascript"}}
	if len(q.jar) > 0 {
		h = append(h, [2]string{"Cookie", cookieString(q.jar)})
	}
	return h
}

func (q *QR) absorb(r *Resp) {
	for _, c := range r.Cookies {
		first := strings.SplitN(c, ";", 2)[0]
		eq := strings.IndexByte(first, '=')
		if eq <= 0 {
			continue
		}
		v := strings.TrimSpace(first[eq+1:])
		if v != "" && !strings.EqualFold(v, "deleted") {
			q.jar[strings.TrimSpace(first[:eq])] = v
		}
	}
}

func (q *QR) json(path string, p sign.Params) (map[string]any, error) {
	r, err := doRequest("GET", qrBase+path+"?"+sign.EncodeParams(p), q.headers(), nil)
	if err != nil {
		return nil, err
	}
	q.absorb(r)
	if r.Status != 200 {
		return nil, errors.New("登录服务暂时不可用（HTTP " + strconv.Itoa(r.Status) + "）")
	}
	return r.JSON()
}

// NewQR 取一张新二维码。
func NewQR() (*QR, error) {
	q := &QR{jar: map[string]string{}}
	m, err := q.json("/passport/web/get_qrcode/", qrCommon())
	if err != nil {
		return nil, err
	}
	in := obj(m["data"])
	if str(m["message"]) != "success" || in == nil {
		return nil, errors.New("获取二维码失败，请重试")
	}
	q.token = str(in["token"])
	qr := str(in["qrcode"])
	if q.token == "" || qr == "" {
		return nil, errors.New("二维码数据不完整，请重试")
	}
	if i := strings.IndexByte(qr, ','); strings.HasPrefix(qr, "data:") && i > 0 {
		qr = qr[i+1:]
	}
	q.Image = sign.B64Decode(qr)
	exp := int64(num(in["expire_time"]))
	now := time.Now()
	switch {
	case exp > 1_000_000_000:
		q.ExpiresAt = time.Unix(exp, 0)
		if !q.ExpiresAt.After(now.Add(30 * time.Second)) {
			q.ExpiresAt = now.Add(120 * time.Second)
		}
	case exp > 30:
		q.ExpiresAt = now.Add(time.Duration(exp) * time.Second)
	default:
		q.ExpiresAt = now.Add(120 * time.Second)
	}
	return q, nil
}

// Poll 轮询一次；确认后跟随跳转拿到 sessionid，返回会话 cookie。
func (q *QR) Poll() (QRState, map[string]string, error) {
	p := append(qrCommon(), sign.Param{K: "token", V: q.token})
	m, err := q.json("/passport/web/check_qrconnect/", p)
	if err != nil {
		return QRWaiting, nil, err
	}
	if str(m["message"]) != "success" {
		return QRWaiting, nil, errors.New("登录状态查询失败")
	}
	in := obj(m["data"])
	switch strings.ToLower(strings.TrimSpace(str(in["status"]))) {
	case "5", "expired", "timeout":
		return QRExpired, nil, nil
	case "4", "canceled", "cancelled", "cancel", "refused", "reject", "rejected":
		return QRCanceled, nil, nil
	case "3", "confirmed", "confirm":
		if red := str(in["redirect_url"]); red != "" {
			if err := q.follow(red); err != nil {
				return QRWaiting, nil, err
			}
		}
		if q.jar["sessionid"] == "" && q.jar["sessionid_ss"] == "" {
			return QRWaiting, nil, errors.New("登录确认未完成，请重新扫码")
		}
		return QRConfirmed, q.jar, nil
	case "2", "scanning", "scanned":
		return QRScanned, nil, nil
	}
	return QRWaiting, nil, nil
}

func (q *QR) follow(value string) error {
	base, _ := url.Parse(qrBase + "/")
	cur, err := base.Parse(value)
	if err != nil {
		return err
	}
	for i := 0; i < 5; i++ {
		host := cur.Hostname()
		if cur.Scheme != "https" || !(host == "hongguoduanju.com" || strings.HasSuffix(host, ".hongguoduanju.com")) {
			return errors.New("登录跳转到了不受支持的站点")
		}
		h := [][2]string{{"User-Agent", webUA}, {"Referer", qrBase + "/"}}
		if len(q.jar) > 0 {
			h = append(h, [2]string{"Cookie", cookieString(q.jar)})
		}
		r, err := doRequest("GET", cur.String(), h, nil)
		if err != nil {
			return err
		}
		q.absorb(r)
		if r.Status >= 300 && r.Status < 400 {
			if r.Location == "" {
				return errors.New("登录跳转缺少地址")
			}
			cur, err = cur.Parse(r.Location)
			if err != nil {
				return err
			}
			continue
		}
		if r.Status >= 400 {
			return errors.New("登录确认失败（HTTP " + strconv.Itoa(r.Status) + "）")
		}
		return nil
	}
	return errors.New("登录跳转次数过多")
}
