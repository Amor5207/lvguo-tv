package server

// 邀请制访问（服务器带宽有限）：
//   - 邀请码与红果账号一对一：一个码只能绑定一个红果账号，一个账号只能绑定一个码（管理员也一样）。
//     码一经绑定即用完，不能换绑到其他账号。
//   - 访问资格只看「当前登录的红果账号是否已绑定有效邀请码」，不限设备：任何设备登录该红果账号即可进入；
//     退出红果、换成未受邀的账号、码被作废都会立即失去访问权限。
//   - 角色跟着邀请码走：owner（超管）> admin（管理员）> member（成员）。
//     超管：生成任意角色的码、管理全部码与黑名单；管理员：只能生成与管理成员码。
//   - 接口保护：除 /api/invite/* 与红果扫码登录外，所有 /api、/media、图片代理都要求已受邀，否则 401；
//     页面一律返回邀请页。/admin 只对管理员及以上开放。
//   - 防暴力试码：校验必须带浏览器指纹；同一指纹连续输错 maxFails 次即拉黑（直到管理员解除），
//     另有按 IP 的频率限制兜底。邀请码 8 位、31 个字符，空间约 8.5×10¹¹，配合拉黑无法枚举。
// 仅在环境变量 LVGUO_INVITE=1 时启用（本机调试默认不开）。

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"crypto/rand"
)

const (
	maxFails = 5 // 同一浏览器指纹连续输错次数上限，超过即拉黑

	roleOwner  = "owner"
	roleAdmin  = "admin"
	roleMember = "member"
)

type gateInvite struct {
	Code         string `json:"code"`
	Note         string `json:"note,omitempty"`
	Role         string `json:"role,omitempty"`
	Created      int64  `json:"created"`
	CreatedBy    string `json:"createdBy,omitempty"`
	UID          string `json:"uid,omitempty"`     // 绑定的红果账号
	UIDName      string `json:"uidName,omitempty"` // 绑定时的红果昵称
	LinkedAt     int64  `json:"linkedAt,omitempty"`
	Seen         int64  `json:"seen,omitempty"` // 最近访问
	GuestAllowed bool   `json:"guestAllowed,omitempty"`
	BrowseOnly   bool   `json:"browseOnly,omitempty"`
	Guest        bool   `json:"-"`
	GuestID      string `json:"guestId,omitempty"`
	GuestUID     string `json:"guestUid,omitempty"`
	// 旧版字段，读取时迁移后丢弃
	Admin bool `json:"admin,omitempty"`
}

type gateBlock struct {
	FP     string `json:"fp"`
	Label  string `json:"label,omitempty"`
	IP     string `json:"ip,omitempty"`
	Fails  int    `json:"fails"`
	At     int64  `json:"at"`
	Reason string `json:"reason,omitempty"`
}

type gateDB struct {
	Invites   map[string]*gateInvite `json:"invites"`
	Blocked   map[string]*gateBlock  `json:"blocked,omitempty"` // 按浏览器指纹拉黑
	MaxOnline *int                   `json:"maxOnline,omitempty"`
}

const onlineWindow = 2 * time.Minute

type ipFailState struct {
	count int
	until time.Time
	at    time.Time
}

type onlineSlot struct {
	code string
	at   time.Time
}

type gate struct {
	on            bool
	dir           string
	mu            sync.Mutex
	db            gateDB
	fails         map[string]int                           // 指纹 → 连续输错次数（成功即清零）
	ipFails       map[string]ipFailState                   // IP → 连续输错与临时封禁
	identity      func(*http.Request) (string, string)     // 当前请求登录的红果账号（uid, 昵称）
	ensureSession func(http.ResponseWriter, *http.Request) // 登录前建立唯一浏览器会话
	ipHits        map[string][]time.Time                   // IP → 最近的校验尝试
	online        map[string]onlineSlot                    // 在线访客 key → 邀请码与最近活跃时间
	nowFn         func() time.Time
	guestKey      []byte
}

func newGate(dataDir string) *gate {
	g := &gate{on: os.Getenv("LVGUO_INVITE") == "1", dir: dataDir, fails: map[string]int{}, ipFails: map[string]ipFailState{}, ipHits: map[string][]time.Time{}, online: map[string]onlineSlot{}}
	g.db = gateDB{Invites: map[string]*gateInvite{}, Blocked: map[string]*gateBlock{}}
	if !g.on {
		return g
	}
	keyPath := filepath.Join(dataDir, "guest-key")
	if key, err := os.ReadFile(keyPath); err == nil && len(key) == 32 {
		g.guestKey = key
	} else {
		key := make([]byte, 32)
		if _, err := rand.Read(key); err == nil {
			if err = os.WriteFile(keyPath, key, 0o600); err == nil {
				g.guestKey = key
			}
		}
	}
	if b, err := os.ReadFile(g.file()); err == nil {
		_ = json.Unmarshal(b, &g.db)
	}
	if g.db.MaxOnline == nil {
		if v, err := strconv.Atoi(strings.TrimSpace(os.Getenv("LVGUO_MAX_ONLINE"))); err == nil && v >= 0 {
			g.db.MaxOnline = &v
		}
	}
	if g.db.Invites == nil {
		g.db.Invites = map[string]*gateInvite{}
	}
	if g.db.Blocked == nil {
		g.db.Blocked = map[string]*gateBlock{}
	}
	for _, inv := range g.db.Invites { // 旧数据迁移
		if inv.Role == "" {
			inv.Role = roleMember
			if inv.Admin {
				inv.Role = roleAdmin
			}
		}
		inv.Admin = false
	}
	return g
}

const guestCookie = "lv_guest"

func (g *gate) guestValue(code, id string) string {
	if len(g.guestKey) != 32 {
		return ""
	}
	payload := base64.RawURLEncoding.EncodeToString([]byte(code + ":" + id))
	mac := hmac.New(sha256.New, g.guestKey)
	_, _ = mac.Write([]byte(payload))
	return payload + "." + base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
}

func (g *gate) guestCode(r *http.Request) (string, string) {
	c, err := r.Cookie(guestCookie)
	if err != nil {
		return "", ""
	}
	parts := strings.Split(c.Value, ".")
	if len(parts) != 2 {
		return "", ""
	}
	b, err := base64.RawURLEncoding.DecodeString(parts[0])
	if err != nil {
		return "", ""
	}
	pair := strings.Split(string(b), ":")
	if len(pair) != 2 || pair[0] == "" || pair[1] == "" || !hmac.Equal([]byte(c.Value), []byte(g.guestValue(pair[0], pair[1]))) {
		return "", ""
	}
	return pair[0], pair[1]
}

func (g *gate) guestMember(r *http.Request) *gateInvite {
	code, id := g.guestCode(r)
	if code == "" {
		return nil
	}
	g.mu.Lock()
	defer g.mu.Unlock()
	inv := g.db.Invites[code]
	if inv == nil || (!inv.GuestAllowed && !inv.BrowseOnly) || inv.GuestID != id {
		return nil
	}
	if now := time.Now().Unix(); now-inv.Seen > 3600 {
		inv.Seen = now
		_ = g.saveLocked()
	}
	cp := *inv
	cp.Guest = true
	cp.Role = roleMember
	cp.UID = ""
	cp.UIDName = ""
	return &cp
}

func (g *gate) guestBoundTo(r *http.Request, uid string) bool {
	code, id := g.guestCode(r)
	if code == "" || uid == "" {
		return false
	}
	g.mu.Lock()
	defer g.mu.Unlock()
	inv := g.db.Invites[code]
	return inv != nil && inv.GuestAllowed && !inv.BrowseOnly && inv.GuestID == id && (inv.UID == uid || inv.GuestUID == uid)
}

func roleRank(r string) int {
	switch r {
	case roleOwner:
		return 3
	case roleAdmin:
		return 2
	}
	return 1
}

// canManage：me 能否管理角色为 target 的码。超管管所有，管理员只管成员。
func canManage(me, target string) bool {
	if me == roleOwner {
		return true
	}
	return me == roleAdmin && target == roleMember
}

func (g *gate) file() string { return filepath.Join(g.dir, "gate.json") }

func (g *gate) saveLocked() error {
	if g.dir == "" {
		return nil
	}
	b, err := json.MarshalIndent(g.db, "", " ")
	if err != nil {
		return err
	}
	tmp := g.file() + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, g.file())
}

// codeByUID 找红果账号已绑定的邀请码（调用方持锁）。
func (g *gate) codeByUID(uid string) *gateInvite {
	if uid == "" {
		return nil
	}
	for _, inv := range g.db.Invites {
		if inv.UID == uid {
			return inv
		}
	}
	return nil
}

// member 返回当前请求登录的红果账号所绑定的邀请码（快照）；未登录或未受邀返回 nil。
func (g *gate) member(r *http.Request) *gateInvite {
	uid := ""
	if g.identity != nil {
		uid, _ = g.identity(r)
	} // 可能查上游（有缓存），不能持锁
	if uid == "" {
		return g.guestMember(r)
	}
	g.mu.Lock()
	inv := g.codeByUID(uid)
	if inv == nil {
		g.mu.Unlock()
		return g.guestMember(r)
	}
	if now := time.Now().Unix(); now-inv.Seen > 3600 {
		inv.Seen = now
		g.saveLocked()
	}
	cp := *inv
	g.mu.Unlock()
	return &cp
}

func (g *gate) now() time.Time {
	if g.nowFn != nil {
		return g.nowFn()
	}
	return time.Now()
}

func (g *gate) maxOnline() int {
	if g.db.MaxOnline == nil || *g.db.MaxOnline <= 0 {
		return 0
	}
	return *g.db.MaxOnline
}

func (g *gate) pruneOnlineLocked(now time.Time) {
	if g.online == nil {
		g.online = map[string]onlineSlot{}
		return
	}
	for k, s := range g.online {
		if now.Sub(s.at) > onlineWindow {
			delete(g.online, k)
		}
	}
}

func (g *gate) codeOnlineLocked(code string) int {
	n := 0
	for _, s := range g.online {
		if s.code == code {
			n++
		}
	}
	return n
}

func (g *gate) onlineVisitorKey(r *http.Request, code string, guest bool) string {
	if c, err := r.Cookie(cookieName); err == nil && c.Value != "" {
		return "s:" + c.Value
	}
	if !guest && code != "" {
		return "u:" + code
	}
	return "g:" + code + ":" + clientIP(r)
}

// enterOnline 按邀请码检查并登记同时在线名额；已占名额或管理员直接放行。
func (g *gate) enterOnline(r *http.Request, m *gateInvite) (ok bool, cur int, cap int) {
	if m == nil || roleRank(m.Role) >= 2 {
		return true, 0, 0
	}
	key := g.onlineVisitorKey(r, m.Code, m.Guest)
	now := g.now()
	g.mu.Lock()
	defer g.mu.Unlock()
	g.pruneOnlineLocked(now)
	cap = g.maxOnline()
	cur = g.codeOnlineLocked(m.Code)
	if _, exists := g.online[key]; exists {
		g.online[key] = onlineSlot{code: m.Code, at: now}
		return true, cur, cap
	}
	if cap > 0 && cur >= cap {
		return false, cur, cap
	}
	g.online[key] = onlineSlot{code: m.Code, at: now}
	return true, cur + 1, cap
}

func (g *gate) leaveOnline(r *http.Request) {
	c, err := r.Cookie(cookieName)
	if err != nil || c.Value == "" {
		return
	}
	g.mu.Lock()
	delete(g.online, "s:"+c.Value)
	g.mu.Unlock()
}

// ------------------------------------------------------------ 拦截（接口保护）

func (g *gate) wrap(next http.Handler, serve func(http.ResponseWriter, *http.Request, string)) http.Handler {
	if !g.on {
		return next
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		p := r.URL.Path
		switch {
		case strings.HasPrefix(p, "/api/invite/"):
			g.handleAPI(w, r)
			return
		case p == "/api/login/start", p == "/api/login/poll", p == "/api/logout", strings.HasPrefix(p, "/api/login/phone/"):
			next.ServeHTTP(w, r) // 邀请页需要红果扫码登录
			return
		case p == "/api/tv/model":
			next.ServeHTTP(w, r) // 电视端备用取流通道，自带 HMAC 鉴权
			return
		case strings.HasPrefix(p, "/shared/"), p == "/healthz", p == "/favicon.ico":
			next.ServeHTTP(w, r) // 邀请页自身要用的静态资源（不含任何内容数据）
			return
		}
		// 网页版只给电脑用：手机访问任何页面都只显示「请在电脑上打开」（接口仍按受邀身份校验）
		if IsMobile(r.UserAgent()) && !strings.HasPrefix(p, "/api/") && !strings.HasPrefix(p, "/media/") && !strings.HasPrefix(p, "/novel-") {
			w.Header().Set("Cache-Control", "no-store")
			w.Header().Set("Vary", "User-Agent")
			serve(w, r, "shared/mobile.html")
			return
		}
		m := g.member(r)
		// 登录入口：已受邀直接回首页，否则显示邀请 / 登录页
		if p == "/login" {
			w.Header().Set("Cache-Control", "no-store")
			if m != nil && !m.Guest {
				http.Redirect(w, r, "/", http.StatusFound)
				return
			}
			serve(w, r, "shared/invite.html")
			return
		}
		if p == "/admin" || p == "/admin/" {
			w.Header().Set("Cache-Control", "no-store")
			switch {
			case m == nil:
				serve(w, r, "shared/invite.html")
			case roleRank(m.Role) < 2:
				http.Redirect(w, r, "/", http.StatusFound)
			default:
				serve(w, r, "shared/admin.html")
			}
			return
		}
		if m != nil {
			if ok, cur, cap := g.enterOnline(r, m); !ok {
				if strings.HasPrefix(p, "/api/") || strings.HasPrefix(p, "/media/") || strings.HasPrefix(p, "/novel-") {
					writeJSON(w, http.StatusTooManyRequests, map[string]any{"error": "online_limit", "message": "当前同时在线人数已满（" + strconv.Itoa(cur) + "/" + strconv.Itoa(cap) + "），请稍后再试", "online": cur, "maxOnline": cap})
					return
				}
				w.Header().Set("Content-Type", "text/html; charset=utf-8")
				w.Header().Set("Cache-Control", "no-store")
				w.WriteHeader(http.StatusTooManyRequests)
				_, _ = io.WriteString(w, "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>绿果WEB · 稍候访问</title><style>body{margin:0;min-height:100vh;display:grid;place-items:center;font:15px/1.6 system-ui,-apple-system,sans-serif;background:#f7f8fa;color:#111}.card{max-width:420px;padding:28px;border-radius:16px;background:#fff;box-shadow:0 10px 30px rgba(0,0,0,.08);text-align:center}h2{margin:0 0 10px;font-size:20px}p{margin:0 0 18px;color:#555}button{height:38px;padding:0 18px;border:0;border-radius:10px;background:#16a34a;color:#fff;font-weight:600;cursor:pointer}</style></head><body><div class=\"card\"><h2>当前同时在线人数已满</h2><p>在线名额已满（"+strconv.Itoa(cur)+" / "+strconv.Itoa(cap)+"），请稍后刷新再试。</p><button onclick=\"location.reload()\">刷新重试</button></div></body></html>")
				return
			}
			next.ServeHTTP(w, r)
			return
		}
		if strings.HasPrefix(p, "/api/") || strings.HasPrefix(p, "/media/") || strings.HasPrefix(p, "/novel-") {
			writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "invite", "message": "登录后可用"})
			return
		}
		w.Header().Set("Cache-Control", "no-store")
		serve(w, r, "shared/invite.html")
	})
}

// ------------------------------------------------------------ 邀请码

const inviteAlphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789" // 去掉易混的 0/O、1/I/L

func newInviteCode() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	out := make([]byte, 8)
	for i := range b {
		out[i] = inviteAlphabet[int(b[i])%len(inviteAlphabet)]
	}
	return string(out[:4]) + "-" + string(out[4:])
}

func normalizeCode(s string) string {
	s = strings.ToUpper(strings.TrimSpace(s))
	s = strings.NewReplacer(" ", "", "-", "", "_", "").Replace(s)
	if len(s) == 8 {
		return s[:4] + "-" + s[4:]
	}
	return s
}

var fpRe = regexp.MustCompile(`^[0-9a-f]{32,128}$`)
var inviteCodeRe = regexp.MustCompile(`^[A-Z0-9]{6,32}$`)

func sameOrigin(r *http.Request) bool {
	if sfs := r.Header.Get("Sec-Fetch-Site"); sfs != "" && sfs != "same-origin" && sfs != "none" {
		return false
	}
	if org := r.Header.Get("Origin"); org != "" {
		u, err := url.Parse(org)
		if err != nil || u.Host == "" || !strings.EqualFold(u.Host, r.Host) {
			return false
		}
	}
	return true
}

// deviceLabel 从 UA 粗略得出「系统 · 浏览器」，只用于黑名单里认出是哪台设备。
func deviceLabel(ua string) string {
	osName := "未知系统"
	switch {
	case strings.Contains(ua, "iPhone"), strings.Contains(ua, "iPad"):
		osName = "iOS"
	case strings.Contains(ua, "Windows"):
		osName = "Windows"
	case strings.Contains(ua, "Mac OS X"):
		osName = "macOS"
	case strings.Contains(ua, "Android"):
		osName = "Android"
	case strings.Contains(ua, "Linux"):
		osName = "Linux"
	}
	br := "浏览器"
	switch {
	case strings.Contains(ua, "Edg/"):
		br = "Edge"
	case strings.Contains(ua, "Chrome/"):
		br = "Chrome"
	case strings.Contains(ua, "Firefox/"):
		br = "Firefox"
	case strings.Contains(ua, "Safari/"):
		br = "Safari"
	}
	return osName + " · " + br
}

func (g *gate) handleAPI(w http.ResponseWriter, r *http.Request) {
	switch r.URL.Path {
	case "/api/invite/me":
		m := g.member(r)
		if m == nil {
			writeJSON(w, 200, map[string]any{"invited": false})
			return
		}
		writeJSON(w, 200, map[string]any{"invited": true, "guest": m.Guest, "code": m.Code, "role": m.Role, "admin": !m.Guest && roleRank(m.Role) >= 2,
			"uidName": m.UIDName, "linkedAt": m.LinkedAt})
	case "/api/invite/state":
		g.state(w, r)
	case "/api/invite/check":
		g.check(w, r)
	case "/api/invite/redeem":
		g.redeem(w, r)
	case "/api/invite/guest/clear":
		if r.Method != http.MethodPost {
			writeJSON(w, 405, map[string]any{"error": "method"})
			return
		}
		if !sameOrigin(r) {
			writeJSON(w, 403, map[string]any{"error": "forbidden"})
			return
		}
		g.leaveOnline(r)
		http.SetCookie(w, &http.Cookie{Name: guestCookie, Value: "", Path: "/", HttpOnly: true, SameSite: http.SameSiteLaxMode, Secure: r.Header.Get("X-Forwarded-Proto") == "https" || r.TLS != nil, MaxAge: -1})
		writeJSON(w, 200, map[string]any{"ok": true})
	case "/api/invite/list":
		if r.Method != http.MethodGet {
			writeJSON(w, 405, map[string]any{"error": "method"})
			return
		}
		m := g.member(r)
		if m == nil || roleRank(m.Role) < 2 {
			writeJSON(w, 403, map[string]any{"error": "仅管理员可用"})
			return
		}
		g.handleAdmin(w, r, m)
	case "/api/invite/new", "/api/invite/delete", "/api/invite/unblock", "/api/invite/update", "/api/invite/settings":
		if r.Method != http.MethodPost {
			writeJSON(w, 405, map[string]any{"error": "method"})
			return
		}
		if !sameOrigin(r) {
			writeJSON(w, 403, map[string]any{"error": "forbidden"})
			return
		}
		m := g.member(r)
		if m == nil || roleRank(m.Role) < 2 {
			writeJSON(w, 403, map[string]any{"error": "仅管理员可用"})
			return
		}
		g.handleAdmin(w, r, m)
	default:
		http.NotFound(w, r)
	}
}

func maskUID(uid string) string {
	if len(uid) <= 6 {
		return uid
	}
	return uid[:3] + "****" + uid[len(uid)-3:]
}

func (g *gate) readCodeFP(r *http.Request) (string, string) {
	var in struct{ Code, FP string }
	_ = json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&in)
	fp := strings.ToLower(strings.TrimSpace(in.FP))
	if !fpRe.MatchString(fp) {
		fp = ""
	}
	return normalizeCode(in.Code), fp
}

// failLocked 记一次无效邀请码；超过上限按浏览器指纹拉黑，并累加 IP 错误次数防止伪造指纹爆破。调用方持锁。
func (g *gate) failLocked(r *http.Request, fp string) (string, bool) {
	if len(g.fails) > 2048 {
		g.fails = map[string]int{}
	}
	now := time.Now()
	ip := clientIP(r)
	if ip != "" {
		if g.ipFails == nil {
			g.ipFails = map[string]ipFailState{}
		}
		if len(g.ipFails) > 2048 {
			for k, v := range g.ipFails {
				if now.Sub(v.at) > 30*time.Minute && (v.until.IsZero() || now.After(v.until)) {
					delete(g.ipFails, k)
				}
			}
		}
		st := g.ipFails[ip]
		if now.Sub(st.at) > 15*time.Minute {
			st = ipFailState{}
		}
		st.count++
		st.at = now
		if st.count >= maxFails*3 {
			st.until = now.Add(15 * time.Minute)
		}
		g.ipFails[ip] = st
	}
	g.fails[fp]++
	left := maxFails - g.fails[fp]
	if left <= 0 {
		g.db.Blocked[fp] = &gateBlock{FP: fp, Label: deviceLabel(r.UserAgent()), IP: ip, Fails: g.fails[fp], At: now.Unix(), Reason: "连续输入无效邀请码"}
		delete(g.fails, fp)
		_ = g.saveLocked()
		return "尝试次数过多，当前浏览器已被限制访问", true
	}
	return "邀请码无效，还可尝试 " + strconv.Itoa(left) + " 次", false
}

func (g *gate) ipPenaltyLocked(ip string) bool {
	if ip == "" || g.ipFails == nil {
		return false
	}
	st, ok := g.ipFails[ip]
	if !ok {
		return false
	}
	now := time.Now()
	if !st.until.IsZero() && now.Before(st.until) {
		return true
	}
	if !st.until.IsZero() && !now.Before(st.until) {
		delete(g.ipFails, ip)
	}
	return false
}

const blockedMsg = "当前浏览器已被限制访问"

// state：邀请页轮询 / 扫码登录后调用。红果账号已绑定邀请码 → 直接放行。
func (g *gate) state(w http.ResponseWriter, r *http.Request) {
	// Establish the browser session before showing login choices. Otherwise a
	// QR request and phone verification started together can each create a
	// different session and race to replace the cookie.
	if g.ensureSession != nil {
		g.ensureSession(w, r)
	}
	uid, name := "", ""
	if g.identity != nil {
		uid, name = g.identity(r)
	}
	if uid == "" {
		guest := g.guestMember(r)
		if guest != nil {
			writeJSON(w, 200, map[string]any{"access": false, "hongguo": false, "guest": true, "browseOnly": guest.BrowseOnly, "code": guest.Code})
			return
		}
		writeJSON(w, 200, map[string]any{"access": false, "hongguo": false})
		return
	}
	g.mu.Lock()
	inv := g.codeByUID(uid)
	g.mu.Unlock()
	if inv == nil {
		guest := g.guestMember(r)
		if guest != nil {
			writeJSON(w, 200, map[string]any{"access": false, "hongguo": true, "guest": true, "browseOnly": guest.BrowseOnly, "code": guest.Code, "name": name})
			return
		}
		writeJSON(w, 200, map[string]any{"access": false, "hongguo": true, "name": name, "needCode": true})
		return
	}
	writeJSON(w, 200, map[string]any{"access": true, "hongguo": true, "name": name})
}

// check：只校验邀请码（不绑定）。用于「先输邀请码」路线的第一步：已被使用的码直接拒绝。
func (g *gate) check(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]any{"error": "method"})
		return
	}
	if !sameOrigin(r) {
		writeJSON(w, 403, map[string]any{"error": "forbidden"})
		return
	}
	ip := clientIP(r)
	if !g.allowIP(ip) {
		writeJSON(w, 429, map[string]any{"error": "尝试过于频繁，请稍后再试"})
		return
	}
	code, fp := g.readCodeFP(r)
	if fp == "" {
		writeJSON(w, 400, map[string]any{"error": "请使用浏览器打开本页面"})
		return
	}
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.ipPenaltyLocked(ip) {
		writeJSON(w, 429, map[string]any{"error": "尝试次数过多，请稍后再试"})
		return
	}
	if g.db.Blocked[fp] != nil {
		writeJSON(w, 403, map[string]any{"error": blockedMsg, "blocked": true})
		return
	}
	inv := g.db.Invites[code]
	if inv == nil {
		msg, blocked := g.failLocked(r, fp)
		writeJSON(w, 400, map[string]any{"error": msg, "blocked": blocked})
		return
	}
	delete(g.fails, fp)
	delete(g.ipFails, ip)
	if inv.GuestAllowed || inv.BrowseOnly {
		// 免登录可绑定码在绑定红果账号后不再向匿名访客签发临时通行凭证
		if inv.GuestAllowed && (inv.UID != "" || inv.GuestUID != "") {
			writeJSON(w, 200, map[string]any{"ok": true, "used": true})
			return
		}
		if inv.GuestID == "" {
			inv.GuestID = newID()
			if err := g.saveLocked(); err != nil {
				inv.GuestID = ""
				writeJSON(w, 503, map[string]any{"error": "暂时无法保存临时身份，请稍后重试"})
				return
			}
		}
		if cap := g.maxOnline(); cap > 0 {
			now := g.now()
			g.pruneOnlineLocked(now)
			key := g.onlineVisitorKey(r, inv.Code, true)
			cur := g.codeOnlineLocked(inv.Code)
			if _, exists := g.online[key]; !exists && cur >= cap {
				writeJSON(w, http.StatusTooManyRequests, map[string]any{"error": "当前该邀请码同时在线人数已满（" + strconv.Itoa(cur) + "/" + strconv.Itoa(cap) + "），请稍后再试", "onlineFull": true})
				return
			}
		}
		value := g.guestValue(inv.Code, inv.GuestID)
		if value == "" {
			writeJSON(w, 503, map[string]any{"error": "免登录暂时不可用，请稍后重试"})
			return
		}
		http.SetCookie(w, &http.Cookie{Name: guestCookie, Value: value, Path: "/", HttpOnly: true, SameSite: http.SameSiteLaxMode, Secure: r.Header.Get("X-Forwarded-Proto") == "https" || r.TLS != nil, MaxAge: 60 * 60 * 24 * 365})
		writeJSON(w, 200, map[string]any{"ok": true, "guest": true, "browseOnly": inv.BrowseOnly})
		return
	}
	writeJSON(w, 200, map[string]any{"ok": true, "used": inv.UID != ""})
}

// redeem：已登录红果后，用邀请码绑定。规则（一对一、一次性）：
//   - 红果账号已绑定过邀请码：直接进入，本次输入的码不被使用；
//   - 邀请码已绑定其他红果账号：拒绝；
//   - 邀请码未使用：与当前红果账号绑定后进入，此后不可换绑。
func (g *gate) redeem(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]any{"error": "method"})
		return
	}
	if !sameOrigin(r) {
		writeJSON(w, 403, map[string]any{"error": "forbidden"})
		return
	}
	ip := clientIP(r)
	if !g.allowIP(ip) {
		writeJSON(w, 429, map[string]any{"error": "尝试过于频繁，请稍后再试"})
		return
	}
	code, fp := g.readCodeFP(r)
	if fp == "" {
		writeJSON(w, 400, map[string]any{"error": "请使用浏览器打开本页面"})
		return
	}
	uid, name := "", ""
	if g.identity != nil {
		uid, name = g.identity(r)
	}
	if uid == "" {
		writeJSON(w, 401, map[string]any{"error": "请先登录红果账号", "needLogin": true})
		return
	}
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.ipPenaltyLocked(ip) {
		writeJSON(w, 429, map[string]any{"error": "尝试次数过多，请稍后再试"})
		return
	}
	if g.db.Blocked[fp] != nil {
		writeJSON(w, 403, map[string]any{"error": blockedMsg, "blocked": true})
		return
	}
	if inv := g.db.Invites[code]; inv != nil && inv.BrowseOnly {
		writeJSON(w, 403, map[string]any{"error": "仅浏览邀请码不能绑定红果账号"})
		return
	}
	if inv := g.db.Invites[code]; inv != nil && inv.GuestAllowed {
		bound := inv.GuestUID
		if bound == "" {
			bound = inv.UID
		}
		if bound != "" && bound != uid {
			writeJSON(w, 409, map[string]any{"error": "该临时身份已绑定其他红果账号"})
			return
		}
		if own := g.codeByUID(uid); own != nil && own.Code != inv.Code {
			before := *inv
			inv.GuestUID = uid
			inv.UIDName = name
			inv.LinkedAt = time.Now().Unix()
			inv.Seen = inv.LinkedAt
			if err := g.saveLocked(); err != nil {
				*inv = before
				writeJSON(w, 503, map[string]any{"error": "暂时无法保存绑定，请重试"})
				return
			}
			delete(g.fails, fp)
			delete(g.ipFails, ip)
			writeJSON(w, 200, map[string]any{"ok": true, "kept": true})
			return
		}
	}
	if own := g.codeByUID(uid); own != nil {
		writeJSON(w, 200, map[string]any{"ok": true, "kept": true})
		return
	}
	inv := g.db.Invites[code]
	if inv == nil {
		msg, blocked := g.failLocked(r, fp)
		writeJSON(w, 400, map[string]any{"error": msg, "blocked": blocked})
		return
	}
	delete(g.fails, fp)
	delete(g.ipFails, ip)
	if inv.UID != "" {
		writeJSON(w, 409, map[string]any{"error": "该邀请码已被使用"})
		return
	}
	now := time.Now().Unix()
	before := *inv
	inv.UID, inv.UIDName, inv.LinkedAt, inv.Seen = uid, name, now, now
	if err := g.saveLocked(); err != nil {
		*inv = before
		writeJSON(w, 503, map[string]any{"error": "暂时无法保存绑定，请重试"})
		return
	}
	writeJSON(w, 200, map[string]any{"ok": true})
}

func (g *gate) handleAdmin(w http.ResponseWriter, r *http.Request, me *gateInvite) {
	var in struct {
		Code         string
		FP           string
		Note         string
		Role         string
		Count        int
		MaxOnline    *int
		GuestAllowed *bool
		BrowseOnly   *bool
	}
	if r.Method == http.MethodPost {
		if err := json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&in); err != nil && !errors.Is(err, io.EOF) {
			writeJSON(w, 400, map[string]any{"error": "请求格式错误"})
			return
		}
	}
	myRole := me.Role
	g.mu.Lock()
	defer g.mu.Unlock()
	target := g.db.Invites[normalizeCode(in.Code)]
	switch r.URL.Path {
	case "/api/invite/new":
		if in.GuestAllowed != nil && *in.GuestAllowed && in.BrowseOnly != nil && *in.BrowseOnly {
			writeJSON(w, 400, map[string]any{"error": "邀请码类型冲突"})
			return
		}
		role := in.Role
		if role != roleOwner && role != roleAdmin {
			role = roleMember
		}
		if ((in.GuestAllowed != nil && *in.GuestAllowed) || (in.BrowseOnly != nil && *in.BrowseOnly)) && role != roleMember {
			writeJSON(w, 400, map[string]any{"error": "免登录或仅浏览邀请码只能使用成员角色"})
			return
		}
		if !canManage(myRole, role) {
			writeJSON(w, 403, map[string]any{"error": "没有权限生成该角色的邀请码"})
			return
		}
		count := min(max(in.Count, 1), 20)
		custom := normalizeCode(in.Code)
		if strings.TrimSpace(in.Code) != "" && custom == "" {
			writeJSON(w, 400, map[string]any{"error": "邀请码须为 6 至 32 位字母或数字"})
			return
		}
		if custom != "" {
			if !inviteCodeRe.MatchString(strings.ReplaceAll(custom, "-", "")) {
				writeJSON(w, 400, map[string]any{"error": "邀请码须为 6 至 32 位字母或数字"})
				return
			}
			if count != 1 {
				writeJSON(w, 400, map[string]any{"error": "自定义邀请码只能生成一个"})
				return
			}
			if g.db.Invites[custom] != nil {
				writeJSON(w, 409, map[string]any{"error": "邀请码已存在"})
				return
			}
		}
		var made []string
		for len(made) < count {
			c := newInviteCode()
			if custom != "" {
				c = custom
			}
			if g.db.Invites[c] != nil {
				continue
			}
			g.db.Invites[c] = &gateInvite{Code: c, Note: strings.TrimSpace(in.Note), Role: role, Created: time.Now().Unix(), CreatedBy: me.Code, GuestAllowed: in.GuestAllowed != nil && *in.GuestAllowed, BrowseOnly: in.BrowseOnly != nil && *in.BrowseOnly}
			made = append(made, c)
		}
		if err := g.saveLocked(); err != nil {
			for _, c := range made {
				delete(g.db.Invites, c)
			}
			writeJSON(w, 503, map[string]any{"error": "无法保存邀请码，请重试"})
			return
		}
		writeJSON(w, 200, map[string]any{"codes": made})
	case "/api/invite/update": // 改备注 / 类型
		if target == nil || (target.Code != me.Code && !canManage(myRole, target.Role)) {
			writeJSON(w, 403, map[string]any{"error": "没有权限修改这个邀请码"})
			return
		}
		guest, browse := target.GuestAllowed, target.BrowseOnly
		if in.GuestAllowed != nil {
			guest = *in.GuestAllowed
		}
		if in.BrowseOnly != nil {
			browse = *in.BrowseOnly
		}
		typeChanged := guest != target.GuestAllowed || browse != target.BrowseOnly
		if typeChanged && target.Code == me.Code {
			writeJSON(w, 403, map[string]any{"error": "不能修改当前正在使用的邀请码类型"})
			return
		}
		if guest && browse {
			writeJSON(w, 400, map[string]any{"error": "邀请码类型冲突"})
			return
		}
		if typeChanged && (guest || browse) && target.Role != roleMember {
			writeJSON(w, 400, map[string]any{"error": "只有成员邀请码可设为免登录或仅浏览"})
			return
		}
		if browse && (target.UID != "" || target.GuestUID != "" || target.Role != roleMember) {
			writeJSON(w, 409, map[string]any{"error": "只有未绑定的成员邀请码可设为仅浏览"})
			return
		}
		before := *target
		target.Note = strings.TrimSpace(in.Note)
		if in.GuestAllowed != nil {
			target.GuestAllowed = *in.GuestAllowed
		}
		if in.BrowseOnly != nil {
			target.BrowseOnly = *in.BrowseOnly
		}
		if !target.GuestAllowed && !target.BrowseOnly {
			target.GuestID = ""
		}
		if err := g.saveLocked(); err != nil {
			*target = before
			writeJSON(w, 503, map[string]any{"error": "无法保存邀请码，请重试"})
			return
		}
		writeJSON(w, 200, map[string]any{"ok": true})
	case "/api/invite/delete": // 作废：绑定的账号立即失去访问权限，码不可再用
		if target == nil {
			writeJSON(w, 200, map[string]any{"ok": true})
			return
		}
		if target.Code == me.Code || !canManage(myRole, target.Role) {
			writeJSON(w, 403, map[string]any{"error": "没有权限作废这个邀请码"})
			return
		}
		delete(g.db.Invites, target.Code)
		g.saveLocked()
		writeJSON(w, 200, map[string]any{"ok": true})
	case "/api/invite/unblock":
		delete(g.db.Blocked, in.FP)
		g.saveLocked()
		writeJSON(w, 200, map[string]any{"ok": true})
	case "/api/invite/settings":
		if in.MaxOnline == nil {
			writeJSON(w, 400, map[string]any{"error": "缺少参数"})
			return
		}
		v := max(0, min(*in.MaxOnline, 10000))
		prev := g.db.MaxOnline
		g.db.MaxOnline = &v
		if err := g.saveLocked(); err != nil {
			g.db.MaxOnline = prev
			writeJSON(w, 503, map[string]any{"error": "无法保存设置，请重试"})
			return
		}
		g.pruneOnlineLocked(g.now())
		writeJSON(w, 200, map[string]any{"ok": true, "maxOnline": v, "onlineCount": len(g.online)})
	default: // list
		g.pruneOnlineLocked(g.now())
		onlineByCode := map[string]int{}
		for _, s := range g.online {
			if s.code != "" {
				onlineByCode[s.code]++
			}
		}
		inv := make([]*gateInvite, 0, len(g.db.Invites))
		for _, v := range g.db.Invites {
			if myRole != roleOwner && v.Role != roleMember && v.Code != me.Code {
				continue // 管理员只看得到成员码和自己的码
			}
			inv = append(inv, v)
		}
		sort.Slice(inv, func(i, j int) bool {
			if roleRank(inv[i].Role) != roleRank(inv[j].Role) {
				return roleRank(inv[i].Role) > roleRank(inv[j].Role)
			}
			return inv[i].Created > inv[j].Created
		})
		list := make([]map[string]any, 0, len(inv))
		used, active := 0, 0
		week := time.Now().Add(-7 * 24 * time.Hour).Unix()
		for _, v := range inv {
			uid := v.UID
			if uid == "" {
				uid = v.GuestUID
			}
			guestActive := (v.GuestAllowed || v.BrowseOnly) && v.GuestID != ""
			inUse := uid != "" || guestActive
			if inUse {
				used++
				if v.Seen >= week {
					active++
				}
			}
			list = append(list, map[string]any{"code": v.Code, "note": v.Note, "role": v.Role, "created": v.Created, "self": v.Code == me.Code, "guestAllowed": v.GuestAllowed, "browseOnly": v.BrowseOnly,
				"linked": uid != "", "guestUsed": guestActive, "uid": maskUID(uid), "uidName": v.UIDName, "linkedAt": v.LinkedAt, "seen": v.Seen,
				"online": onlineByCode[v.Code], "manage": v.Code != me.Code && canManage(myRole, v.Role)})
		}
		blocked := make([]*gateBlock, 0, len(g.db.Blocked))
		for _, b := range g.db.Blocked {
			blocked = append(blocked, b)
		}
		sort.Slice(blocked, func(i, j int) bool { return blocked[i].At > blocked[j].At })
		writeJSON(w, 200, map[string]any{"invites": list, "used": used, "active": active, "onlineCount": len(g.online), "maxOnline": g.maxOnline(), "blocked": blocked, "role": myRole,
			"me": me.Code, "canCreate": map[string]bool{roleOwner: myRole == roleOwner, roleAdmin: myRole == roleOwner, roleMember: true}})
	}
}

// allowIP 兜底的按 IP 频率限制：10 分钟内最多 20 次尝试，并定期清理过期 IP 记录避免内存无限增长。
func (g *gate) allowIP(ip string) bool {
	g.mu.Lock()
	defer g.mu.Unlock()
	now := time.Now()
	if len(g.ipHits) > 2048 {
		for k, hits := range g.ipHits {
			if len(hits) == 0 || now.Sub(hits[len(hits)-1]) >= 10*time.Minute {
				delete(g.ipHits, k)
			}
		}
	}
	list := g.ipHits[ip][:0]
	for _, t := range g.ipHits[ip] {
		if now.Sub(t) < 10*time.Minute {
			list = append(list, t)
		}
	}
	if len(list) >= 20 {
		g.ipHits[ip] = list
		return false
	}
	g.ipHits[ip] = append(list, now)
	return true
}
