// Package server 是绿果TV 的 HTTP 服务：每个浏览器一个独立登录会话，
// 提供 JSON 接口、解密后的视频流，以及按浏览器标识切换的 PC / 手机页面。
package server

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"io/fs"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"lvguo/internal/lvguo"
	"lvguo/internal/lvguoplay"
	"lvguo/internal/media"
)

const cookieName = "lv_sid"

// Config 是服务配置。
type Config struct {
	DataDir string
	UI      fs.FS // 根下有 pc/ mobile/ shared/
	Version string
}

type sess struct {
	id         string
	acct       *lvguo.Account
	qr         *lvguo.QR
	ahead      *fyAhead              // 后台预取的下一批推荐
	phone      *lvguoplay.PhoneLogin // 手机号登录进行中的会话（发码 → 登录 → 二次验证）
	forYou     struct{ cell, session string }
	lastBeat   time.Time
	lastActive time.Time
	lastSaved  string
	lib        *library // 本地播放记录库（library.go），懒加载
	uid        string   // 红果账号 id 缓存（邀请制核对用），随 cookie 变化失效
	uidName    string
	uidJar     string
	profile    *lvguo.Profile
	profileAt  time.Time
	favs       []lvguo.Entry
	favsAt     time.Time
	favsJar    string
	guestCode  string
}

// Server 持有全部会话与内容缓存。
type Server struct {
	cfg     Config
	client  *lvguo.Client
	content *lvguo.Content
	media   *media.Cache
	// player 是专门取播放地址的匿名身份：独立设备、永不带账号 cookie。
	// 红果对账号风控时，账号用过的设备取流全部空包（换新设备一带账号也立刻失效）；
	// 匿名的新设备不受影响。所以取流与账号彻底分开。
	player *lvguo.Account
	gate   *gate // 邀请制访问（LVGUO_INVITE=1 时启用）

	mu             sync.Mutex
	sessionKey     []byte
	sessions       map[string]*sess
	guestDevices   map[string]*lvguo.DeviceStore
	guestLibraries map[string]*library
	guestLatest    func(*sess, []string) (map[string]int64, error) // optional test seam
	limiter        *limiter
	lastPing       atomic.Int64
	dev            bool // LVGUO_DEV=1：界面调试用的假登录态，不访问任何账号数据
}

func New(cfg Config) (*Server, error) {
	if err := os.MkdirAll(cfg.DataDir, 0o700); err != nil {
		return nil, err
	}
	sessKey, err := loadSessionKey(cfg.DataDir)
	if err != nil {
		return nil, err
	}
	c := &lvguo.Client{Devices: lvguo.NewDeviceStore(cfg.DataDir)}
	s := &Server{cfg: cfg, client: c, content: lvguo.NewContent(c), media: media.NewCache(512 << 20),
		sessionKey: sessKey, sessions: map[string]*sess{}, limiter: newLimiter(), dev: os.Getenv("LVGUO_DEV") == "1"}
	playDev := lvguo.NewDeviceStore(filepath.Join(cfg.DataDir, "player"))
	s.player = &lvguo.Account{C: &lvguo.Client{Devices: playDev}, U: lvguo.NewUser(), Dev: playDev}
	s.gate = newGate(cfg.DataDir)
	s.gate.identity = s.hongguoIdentity
	s.gate.ensureSession = func(w http.ResponseWriter, r *http.Request) { s.session(w, r) }
	s.load()
	go s.uploadLoop()
	go func() { // 预热设备注册，首个请求不用等
		if _, err := c.Devices.Get(); err != nil {
			log.Printf("设备注册: %v", err)
		}
	}()
	return s, nil
}

// ------------------------------------------------------------ 会话持久化

func (s *Server) file() string { return filepath.Join(s.cfg.DataDir, "sessions.json") }

func (s *Server) load() {
	b, err := os.ReadFile(s.file())
	if err != nil {
		return
	}
	m, migrated, err := decryptSessionStore(s.sessionKey, b)
	if err != nil {
		log.Printf("会话加载失败（密钥不匹配或文件损坏）: %v", err)
		return
	}
	for id, jar := range m {
		u := lvguo.NewUser()
		u.Set(jar)
		ss := &sess{id: id, acct: &lvguo.Account{C: s.client, U: u}, lastActive: time.Now(), lastSaved: u.Cookie()}
		ss.acct.SetDev(s.ownDevice(id))
		s.sessions[id] = ss
	}
	if migrated && len(s.sessions) > 0 {
		s.saveLocked()
	}
}

func (s *Server) saveLocked() {
	m := map[string]map[string]string{}
	for id, ss := range s.sessions {
		if ss.acct.U.LoggedIn() {
			m[id] = ss.acct.U.Snapshot()
		}
	}
	b, err := encryptSessionStore(s.sessionKey, m)
	if err != nil {
		log.Printf("会话加密保存失败: %v", err)
		return
	}
	tmp := s.file() + ".tmp"
	if os.WriteFile(tmp, b, 0o600) == nil {
		_ = os.Rename(tmp, s.file())
	}
}

// ownDevice 是登录会话独占的设备身份（首次使用时向上游注册一次，之后落盘复用）。
func (s *Server) ownDevice(id string) *lvguo.DeviceStore {
	h := sha256.Sum256([]byte(id))
	return lvguo.NewDeviceStore(filepath.Join(s.cfg.DataDir, "devices", hex.EncodeToString(h[:8])))
}

// guestDevice is called while holding s.mu so all browsers sharing one code
// also share the same registered anonymous device and its lock.
func (s *Server) guestDevice(code string) *lvguo.DeviceStore {
	if s.guestDevices == nil {
		s.guestDevices = make(map[string]*lvguo.DeviceStore)
	}
	if d := s.guestDevices[code]; d != nil {
		return d
	}
	h := sha256.Sum256([]byte(code))
	d := lvguo.NewDeviceStore(filepath.Join(s.cfg.DataDir, "guest-devices", hex.EncodeToString(h[:8])))
	s.guestDevices[code] = d
	return d
}

func newID() string {
	b := make([]byte, 24)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

// session 取（或创建）当前浏览器的会话。
func (s *Server) session(w http.ResponseWriter, r *http.Request) *sess {
	recoverGuest := ""
	var guest *gateInvite
	if s.gate != nil {
		guest = s.gate.guestMember(r)
	}
	if c, err := r.Cookie(cookieName); err == nil && regexp.MustCompile(`^[0-9a-f]{48}$`).MatchString(c.Value) && guest != nil {
		recoverGuest = c.Value
	}
	now := time.Now()
	s.mu.Lock()
	defer s.mu.Unlock()
	if len(s.sessions) > 1024 {
		for sid, existing := range s.sessions {
			if !existing.acct.U.LoggedIn() && !existing.lastActive.IsZero() && now.Sub(existing.lastActive) > 24*time.Hour {
				delete(s.sessions, sid)
			}
		}
	}
	if c, err := r.Cookie(cookieName); err == nil {
		if ss := s.sessions[c.Value]; ss != nil {
			ss.lastActive = now
			if guest != nil && !ss.acct.U.LoggedIn() {
				key := guest.GuestID
				if guest.BrowseOnly {
					key += ":" + ss.id
				}
				if ss.guestCode != key || !ss.acct.HasDev() {
					ss.guestCode = key
					ss.acct.SetDev(s.guestDevice(key))
				}
			}
			return ss
		}
	}
	id := newID()
	if recoverGuest != "" {
		id = recoverGuest
	}
	ss := &sess{id: id, acct: &lvguo.Account{C: s.client, U: lvguo.NewUser()}, lastActive: now}
	if guest != nil {
		key := guest.GuestID
		if guest.BrowseOnly {
			key += ":" + id
		}
		ss.guestCode = key
		ss.acct.SetDev(s.guestDevice(key))
	} else {
		ss.acct.SetDev(s.ownDevice(id))
	}
	if s.dev {
		ss.acct.U.Set(map[string]string{"sessionid": "dev"})
	}
	s.sessions[id] = ss
	http.SetCookie(w, &http.Cookie{Name: cookieName, Value: id, Path: "/", HttpOnly: true, SameSite: http.SameSiteLaxMode,
		Secure: r.Header.Get("X-Forwarded-Proto") == "https" || r.TLS != nil, MaxAge: 60 * 60 * 24 * 365})
	return ss
}

// rotateSession 在登录成功时轮换浏览器 lv_sid，防止会话固定（Session Fixation）攻击。
func (s *Server) rotateSession(w http.ResponseWriter, r *http.Request, ss *sess) {
	if ss == nil {
		return
	}
	nextID := newID()
	s.mu.Lock()
	oldID := ss.id
	if oldID != "" && s.sessions[oldID] == ss {
		delete(s.sessions, oldID)
	}
	ss.id = nextID
	s.sessions[nextID] = ss
	s.mu.Unlock()
	http.SetCookie(w, &http.Cookie{Name: cookieName, Value: nextID, Path: "/", HttpOnly: true, SameSite: http.SameSiteLaxMode,
		Secure: r.Header.Get("X-Forwarded-Proto") == "https" || r.TLS != nil, MaxAge: 60 * 60 * 24 * 365})
}

// persist 在会话 cookie 有变化（登录、续期）时落盘。
func (s *Server) persist(ss *sess) {
	cur := ss.acct.U.Cookie()
	s.mu.Lock()
	defer s.mu.Unlock()
	if cur != ss.lastSaved {
		ss.lastSaved = cur
		s.saveLocked()
	}
}

// ------------------------------------------------------------ 路由

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	// A browser-cache marker for browse-only authorization. The marker contains
	// no account or viewing data and is never stored on the server.
	mux.HandleFunc("/shared/browse-proof", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			http.Error(w, "method", http.StatusMethodNotAllowed)
			return
		}
		w.Header().Set("Cache-Control", "private, max-age=31536000, immutable")
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		_, _ = w.Write([]byte("ok"))
	})
	api := func(path string, h func(*sess, http.ResponseWriter, *http.Request) (any, error)) {
		mux.HandleFunc(path, func(w http.ResponseWriter, r *http.Request) {
			if r.Method == http.MethodPost && !sameOrigin(r) {
				writeJSON(w, http.StatusForbidden, map[string]any{"error": "forbidden"})
				return
			}
			ss := s.session(w, r)
			if !s.limiter.allow(clientIP(r), 40, 80) {
				writeJSON(w, 429, map[string]any{"error": "请求太频繁，请稍后再试"})
				return
			}
			v, err := h(ss, w, r)
			s.persist(ss)
			if err != nil {
				code := 502
				var he *httpErr
				switch {
				case errors.As(err, &he):
					code = he.code
				case errors.Is(err, lvguo.ErrSessionExpired):
					code = 401
					s.expire(ss)
				}
				writeJSON(w, code, map[string]any{"error": err.Error()})
				return
			}
			writeJSON(w, 200, v)
		})
	}
	api("/api/me", s.me)
	api("/api/login/start", s.loginStart)
	api("/api/login/poll", s.loginPoll)
	api("/api/login/phone/send", s.phoneSend)
	api("/api/login/phone/login", s.phoneLogin)
	api("/api/login/phone/verify", s.phoneVerify)
	api("/api/logout", s.logout)
	api("/api/home", s.home)
	api("/api/foryou", s.forYou)
	api("/api/rank", s.rank)
	api("/api/category/options", s.categoryOptions)
	api("/api/category", s.category)
	api("/api/search", s.search)
	api("/api/suggest", s.suggest)
	api("/api/detail", s.detail)
	api("/api/seasons", s.seasons)
	api("/api/history", s.history)
	api("/api/history/delete", s.historyDelete)
	api("/api/favorites", s.favorites)
	api("/api/favorite", s.favorite)
	api("/api/reservations", s.reservations)
	api("/api/reservation", s.reservation)
	api("/api/progress", s.progress)
	api("/api/guest/import", s.importGuestProgress)
	api("/api/comments", s.comments)
	api("/api/play", s.play)
	mux.HandleFunc("/api/invite/me", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{})
	})
	mux.HandleFunc("/media/", s.serveMedia)
	mux.HandleFunc("/api/tv/model", s.tvModel)
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, "lvguo-tv") })
	mux.HandleFunc("/api/ping", func(w http.ResponseWriter, r *http.Request) {
		s.lastPing.Store(time.Now().UnixNano())
		w.Header().Set("Cache-Control", "no-store")
		w.WriteHeader(http.StatusNoContent)
	})
	mux.HandleFunc("/", s.pages)
	return gzipMiddleware(secure(s.gate.wrap(mux, s.serveFile)))
}

type httpErr struct {
	code int
	msg  string
}

func (e *httpErr) Error() string { return e.msg }
func bad(msg string) error       { return &httpErr{400, msg} }
func need() error                { return &httpErr{401, "请先登录"} }

func (s *Server) expire(ss *sess) {
	ss.acct.U.Set(map[string]string{})
	// 登录失效后本地记录已无法上传，连同缓存一起清掉（下一个账号不能继承）
	_ = os.Remove(s.libraryPath(ss.id))
	s.mu.Lock()
	ss.lib = nil
	ss.profile = nil
	ss.profileAt = time.Time{}
	ss.favs = nil
	ss.favsAt = time.Time{}
	ss.favsJar = ""
	hadDev := ss.acct.HasDev()
	ss.acct.SetDev(nil)
	s.mu.Unlock()
	if hadDev {
		h := sha256.Sum256([]byte(ss.id))
		_ = os.RemoveAll(filepath.Join(s.cfg.DataDir, "devices", hex.EncodeToString(h[:8])))
	}
	s.persist(ss)
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func secure(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("X-Frame-Options", "SAMEORIGIN")
		h.ServeHTTP(w, r)
	})
}

// clientIP 取真实客户端 IP：只有请求来自本机/内网反向代理时才信任 X-Forwarded-For，防止伪造头绕过限流。
func clientIP(r *http.Request) string {
	h, _, _ := net.SplitHostPort(r.RemoteAddr)
	if ip := net.ParseIP(h); ip != nil && (ip.IsLoopback() || ip.IsPrivate()) {
		if f := r.Header.Get("X-Forwarded-For"); f != "" {
			parts := strings.Split(f, ",")
			return strings.TrimSpace(parts[len(parts)-1]) // 最靠近代理的一跳，由反代自己写入
		}
	}
	return h
}

// ------------------------------------------------------------ 页面（按浏览器标识切换）

var mobileRe = regexp.MustCompile(`(?i)(android|iphone|ipod|windows phone|mobile|harmonyos|opera mini|blackberry)`)

// IsMobile 判断是否手机浏览器；iPad 与平板按 PC 处理。
func IsMobile(ua string) bool {
	if strings.Contains(ua, "iPad") || (strings.Contains(ua, "Android") && !strings.Contains(ua, "Mobile")) {
		return false
	}
	return mobileRe.MatchString(ua)
}

func isSPARoute(p string) bool {
	if p == "/pc" || strings.HasPrefix(p, "/pc/") {
		p = strings.TrimPrefix(p, "/pc")
		if p == "" {
			return true
		}
	}
	switch p {
	case "/", "/home", "/feed", "/rank", "/category", "/settings", "/search",
		"/mine", "/mine/history", "/mine/fav", "/mine/reserve":
		return true
	}
	if strings.HasPrefix(p, "/detail/") {
		return digits.MatchString(strings.TrimPrefix(p, "/detail/"))
	}
	return false
}

func (s *Server) pages(w http.ResponseWriter, r *http.Request) {
	p := r.URL.Path
	// 显式指定：/pc 及其子路由强制 PC 界面
	switch {
	case (p == "/pc" || strings.HasPrefix(p, "/pc/")) && isSPARoute(p):
		s.serveFile(w, r, "pc/index.html")
		return
	case p == "/m" || p == "/m/":
		s.serveFile(w, r, "shared/mobile.html")
		return
	case isSPARoute(p):
		w.Header().Set("Vary", "User-Agent")
		w.Header().Set("Cache-Control", "no-store") // 同一地址按 UA 返回不同页面，不能让 CDN 缓存
		if IsMobile(r.UserAgent()) {
			s.serveFile(w, r, "shared/mobile.html") // 网页版只给电脑用：手机只引导去电脑打开
		} else {
			s.serveFile(w, r, "pc/index.html")
		}
		return
	}
	name := strings.TrimPrefix(path.Clean(p), "/")
	if strings.Contains(name, "..") {
		http.NotFound(w, r)
		return
	}
	s.serveFile(w, r, name)
}

var assetETagPrefix = strconv.FormatInt(time.Now().Unix(), 36)

func (s *Server) serveFile(w http.ResponseWriter, r *http.Request, name string) {
	f, err := s.cfg.UI.Open(name)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil || st.IsDir() {
		http.NotFound(w, r)
		return
	}
	if w.Header().Get("Cache-Control") != "" {
		// 调用方已指定
	} else if strings.HasSuffix(name, ".html") {
		// 页面与登录 / 邀请状态有关：只许浏览器缓存，CDN 不能存（否则可能把某人的页面给别人）
		w.Header().Set("Cache-Control", "private, no-cache")
	} else if strings.HasSuffix(name, ".js") || strings.HasSuffix(name, ".css") || strings.HasSuffix(name, ".webmanifest") {
		if r.URL.Query().Get("v") == assetETagPrefix {
			w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
		} else {
			w.Header().Set("Cache-Control", "no-cache")
		}
		w.Header().Set("ETag", `"`+assetETagPrefix+`-`+strconv.FormatInt(st.Size(), 36)+`"`)
	} else {
		w.Header().Set("Cache-Control", "public, max-age=3600")
		w.Header().Set("ETag", `"`+assetETagPrefix+`-`+strconv.FormatInt(st.Size(), 36)+`"`)
	}
	if name == "pc/index.html" {
		b, _ := io.ReadAll(f)
		html := string(b)
		v := "?v=" + assetETagPrefix
		for _, asset := range []string{"/shared/tokens.css", "/pc/app.css", "/shared/icons.js", "/shared/core.js", "/pc/app.js"} {
			html = strings.ReplaceAll(html, `"`+asset+`"`, `"`+asset+v+`"`)
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = io.WriteString(w, html)
		return
	}
	rs, ok := f.(io.ReadSeeker)
	if !ok {
		b, _ := io.ReadAll(f)
		http.ServeContent(w, r, name, time.Time{}, strings.NewReader(string(b)))
		return
	}
	http.ServeContent(w, r, name, time.Time{}, rs)
}

// ------------------------------------------------------------ 工具

func q(r *http.Request, k string) string { return r.URL.Query().Get(k) }
func qi(r *http.Request, k string) int {
	n, _ := strconv.Atoi(r.URL.Query().Get(k))
	return n
}

func decode(r *http.Request, v any) error {
	r.Body = http.MaxBytesReader(nil, r.Body, 1<<16)
	if err := json.NewDecoder(r.Body).Decode(v); err != nil {
		return bad("请求格式错误")
	}
	return nil
}

// imgURL 把封面/头像统一成公开 JPEG 版式（浏览器不认 HEIC）。
var hex32 = regexp.MustCompile(`^[a-fA-F0-9]{32}$`)
var digits = regexp.MustCompile(`^\d+$`)

func imgURL(in string) string {
	if in == "" {
		return ""
	}
	if strings.HasPrefix(in, "http://") {
		if u, err := url.Parse(in); err == nil {
			h := u.Hostname()
			if strings.HasSuffix(h, ".fqnovelpic.com") || strings.HasSuffix(h, ".byteimg.com") || strings.HasSuffix(h, ".snssdk.com") || strings.HasSuffix(h, ".pstatp.com") {
				in = "https://" + in[7:]
			}
		}
	}
	u, err := url.Parse(in)
	if err != nil {
		return in
	}
	h := u.Hostname()
	if strings.HasSuffix(h, ".fqnovelpic.com") || strings.HasSuffix(h, ".byteimg.com") {
		for _, seg := range []string{"/novel-pic/", "/novel-static/"} {
			if i := strings.Index(u.Path, seg); i >= 0 {
				id := strings.FieldsFunc(u.Path[i+len(seg):], func(r rune) bool { return r == '~' || r == '.' })
				if len(id) > 0 && hex32.MatchString(id[0]) {
					return "https://p3-novel.byteimg.com/img" + seg + id[0] + "~tplv-noop.jpeg"
				}
			}
		}
	}
	return in
}

var _ = base64.StdEncoding

// WatchWindow 供桌面版使用：页面每 10 秒 ping 一次；窗口关闭、连续 45 秒没有 ping 就调用 quit。
// 第一次 ping 到来前（窗口还没打开）最多等 2 分钟。
func (s *Server) WatchWindow(quit func()) {
	started := time.Now()
	go func() {
		for range time.Tick(5 * time.Second) {
			last := s.lastPing.Load()
			if last == 0 {
				if time.Since(started) > 2*time.Minute {
					quit()
				}
				continue
			}
			if time.Since(time.Unix(0, last)) > 45*time.Second {
				quit()
			}
		}
	}()
}

// hongguoIdentity 返回请求所在会话当前登录的红果账号（uid、昵称）；未登录返回空。
// 结果按会话缓存，登录态（cookie）变化后才重新查询，门禁每个请求都要用，不能每次都打上游。
func (s *Server) hongguoIdentity(r *http.Request) (string, string) {
	c, err := r.Cookie(cookieName)
	if err != nil {
		return "", ""
	}
	s.mu.Lock()
	ss := s.sessions[c.Value]
	s.mu.Unlock()
	if ss == nil || !ss.acct.U.LoggedIn() {
		return "", ""
	}
	jar := ss.acct.U.Cookie()
	s.mu.Lock()
	if ss.uid != "" && ss.uidJar == jar {
		uid, name := ss.uid, ss.uidName
		s.mu.Unlock()
		return uid, name
	}
	s.mu.Unlock()
	if s.dev {
		return "dev", "调试账号"
	}
	p, err := ss.acct.Profile()
	if err != nil || p == nil || p.UID == "" {
		return "", ""
	}
	cp := *p
	s.mu.Lock()
	ss.uid, ss.uidName, ss.uidJar = p.UID, p.Name, jar
	ss.profile, ss.profileAt = &cp, time.Now()
	s.mu.Unlock()
	return p.UID, p.Name
}

func (s *Server) sessionProfile(ss *sess) (*lvguo.Profile, error) {
	jar := ss.acct.U.Cookie()
	s.mu.Lock()
	if ss.profile != nil && ss.uidJar == jar && time.Since(ss.profileAt) < 2*time.Minute {
		cp := *ss.profile
		s.mu.Unlock()
		return &cp, nil
	}
	s.mu.Unlock()
	p, err := ss.acct.Profile()
	if err != nil || p == nil {
		return nil, err
	}
	cp := *p
	s.mu.Lock()
	ss.uid, ss.uidName, ss.uidJar = p.UID, p.Name, jar
	ss.profile, ss.profileAt = &cp, time.Now()
	s.mu.Unlock()
	return p, nil
}

func (s *Server) sessionFavorites(ss *sess, fresh bool) ([]lvguo.Entry, error) {
	jar := ss.acct.U.Cookie()
	s.mu.Lock()
	if !fresh && ss.favs != nil && ss.favsJar == jar && time.Since(ss.favsAt) < 30*time.Second {
		cp := append([]lvguo.Entry(nil), ss.favs...)
		s.mu.Unlock()
		return cp, nil
	}
	s.mu.Unlock()
	f, err := ss.acct.Favorites()
	if err != nil {
		return nil, err
	}
	s.mu.Lock()
	ss.favs = append([]lvguo.Entry(nil), f...)
	ss.favsAt = time.Now()
	ss.favsJar = jar
	s.mu.Unlock()
	return f, nil
}

func (s *Server) invalidateFavorites(ss *sess) {
	s.mu.Lock()
	ss.favs = nil
	ss.favsAt = time.Time{}
	ss.favsJar = ""
	s.mu.Unlock()
}
