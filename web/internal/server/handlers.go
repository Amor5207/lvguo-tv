package server

import (
	"encoding/base64"
	"errors"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"lvguo/internal/hg"
)

type view = map[string]any

func series(in []hg.Series) []hg.Series {
	out := make([]hg.Series, len(in))
	for i, s := range in {
		s.Cover = imgURL(s.Cover)
		out[i] = s
	}
	return out
}

func page(p *hg.Page) *hg.Page {
	cp := *p
	cp.Items = series(p.Items)
	return &cp
}

func entries(in []hg.Entry) []hg.Entry {
	out := make([]hg.Entry, len(in))
	for i, e := range in {
		e.Cover = imgURL(e.Cover)
		out[i] = e
	}
	return out
}

func (s *Server) cookie(ss *sess) string {
	if ss.acct.U.LoggedIn() {
		return ss.acct.U.Cookie()
	}
	return ""
}

func (s *Server) auth(ss *sess) error {
	if !ss.acct.U.LoggedIn() {
		return need()
	}
	return nil
}

// ------------------------------------------------------------ 账号

func (s *Server) me(ss *sess, w http.ResponseWriter, r *http.Request) (any, error) {
	if !ss.acct.U.LoggedIn() {
		if s.gate != nil {
			if m := s.gate.guestMember(r); m != nil {
				return view{"loggedIn": false, "guest": true, "guestKey": m.GuestID, "browseOnly": m.BrowseOnly}, nil
			}
		}
		return view{"loggedIn": false}, nil
	}
	if s.dev {
		return view{"loggedIn": true, "daysLeft": 30, "profile": hg.Profile{UID: "1", Name: "绿果用户", Signature: "界面调试账号", WatchMs: 86400000, Days: 42}}, nil
	}
	p, err := ss.acct.Profile()
	if err != nil {
		return nil, err
	}
	p.Avatar, p.Cover = imgURL(p.Avatar), imgURL(p.Cover)
	if time.Since(ss.lastBeat) > 6*time.Hour {
		ss.lastBeat = time.Now()
		go func() { _ = ss.acct.Heartbeat(); s.persist(ss) }()
	}
	days := -1
	if at := ss.acct.U.ExpiresAt(); !at.IsZero() {
		days = int(time.Until(at).Hours() / 24)
	}
	hasGuest := false
	if s.gate != nil {
		hasGuest = s.gate.guestBoundTo(r, p.UID)
	}
	return view{"loggedIn": true, "profile": p, "daysLeft": days, "hasGuest": hasGuest}, nil
}

func (s *Server) loginStart(ss *sess, w http.ResponseWriter, r *http.Request) (any, error) {
	if !s.limiter.allow("qr:"+clientIP(r), 6, 12) {
		return nil, &httpErr{429, "二维码获取太频繁，请稍后再试"}
	}
	qr, err := hg.NewQR()
	if err != nil {
		return nil, err
	}
	s.mu.Lock()
	ss.qr = qr
	s.mu.Unlock()
	return view{"qr": "data:image/png;base64," + base64.StdEncoding.EncodeToString(qr.Image), "expiresAt": qr.ExpiresAt.UnixMilli(), "open": qr.OpenURL()}, nil
}

func (s *Server) loginPoll(ss *sess, w http.ResponseWriter, r *http.Request) (any, error) {
	s.mu.Lock()
	qr := ss.qr
	s.mu.Unlock()
	if qr == nil {
		return view{"state": "expired"}, nil
	}
	state, jar, err := qr.Poll()
	if err != nil {
		return nil, err
	}
	if state == hg.QRConfirmed {
		ss.acct.SetDev(s.ownDevice(ss.id))
		ss.acct.U.Set(jar)
		s.mu.Lock()
		ss.qr = nil
		s.mu.Unlock()
	}
	return view{"state": string(state)}, nil
}

func (s *Server) logout(ss *sess, w http.ResponseWriter, r *http.Request) (any, error) {
	if ss.acct.U.LoggedIn() {
		s.uploadAndReport(ss) // 退出前把本地还没上传的播放记录交上去
		ss.acct.LogoutRemote()
	}
	s.expire(ss)
	return view{"ok": true}, nil
}

// ------------------------------------------------------------ 内容

func (s *Server) home(ss *sess, w http.ResponseWriter, r *http.Request) (any, error) {
	out := view{"continue": []hg.Entry{}}
	// 观看历史与推荐流互不依赖，并发取，首页少等一个来回
	var hist []hg.Entry
	var herr error
	done := make(chan struct{})
	go func() {
		defer close(done)
		if ss.acct.U.LoggedIn() && !s.dev {
			hist, herr = s.cloudHistory(ss, 40)
		} else if s.gate != nil {
			if m := s.gate.guestMember(r); m != nil && !m.BrowseOnly {
				hist = s.guestHistory(m.GuestID, s.cookie(ss), 40)
			}
		}
	}()
	chDone := make(chan []hg.FeedChannel, 1)
	go func() { chDone <- s.content.FeedChannels(ss.acct) }()
	p, cell, sessID, err := s.content.ForYou(ss.acct, "16", 0, "", "")
	<-done
	channels := <-chDone
	if herr == hg.ErrSessionExpired {
		return nil, herr
	}
	if herr == nil {
		var rest []hg.Entry
		rest = append(rest, hist...)
		if len(rest) > 12 {
			rest = rest[:12]
		}
		if rest != nil {
			out["continue"] = entries(rest)
		}
	}
	if err != nil {
		return nil, err
	}
	out["forYou"], out["cell"], out["session"] = page(p), cell, sessID
	out["channels"] = channels
	s.readAhead(ss, "16", &fyResult{p, cell, sessID}) // 首页一出来就在后台备好下一批
	return out, nil
}

// fyResult 是一批推荐（已凑够两行）及其续取游标。
type fyResult struct {
	p             *hg.Page
	cell, session string
}

// fyAhead 是提前在后台取好的「下一批」：上游一次请求要 1 秒左右、凑一批要两三秒，
// 用户往下滚或换下一部时直接拿现成的，不用再等。
type fyAhead struct {
	key  string
	done chan struct{}
	res  *fyResult
	err  error
}

func fyKey(ch string, offset int, cursor, session string) string {
	if ch == "" {
		ch = "16"
	}
	return ch + "|" + strconv.Itoa(offset) + "|" + cursor + "|" + session
}

// readAhead 在后台取 res 之后的下一批（每个会话只保留一份）。
func (s *Server) readAhead(ss *sess, ch string, res *fyResult) {
	if res == nil || !res.p.More || res.cell == "" {
		return
	}
	a := &fyAhead{key: fyKey(ch, res.p.Next, res.cell, res.session), done: make(chan struct{})}
	s.mu.Lock()
	if ss.ahead != nil && ss.ahead.key == a.key {
		s.mu.Unlock()
		return
	}
	ss.ahead = a
	s.mu.Unlock()
	go func() {
		a.res, a.err = s.forYouBatch(ss, ch, res.p.Next, res.cell, res.session)
		close(a.done)
	}()
}

func (s *Server) forYou(ss *sess, w http.ResponseWriter, r *http.Request) (any, error) {
	ch, offset, cursor, session := q(r, "channel"), qi(r, "offset"), q(r, "cell"), q(r, "session")
	var res *fyResult
	s.mu.Lock()
	a := ss.ahead
	s.mu.Unlock()
	if a != nil && a.key == fyKey(ch, offset, cursor, session) {
		select {
		case <-a.done:
		case <-time.After(8 * time.Second):
		case <-r.Context().Done():
			return nil, r.Context().Err()
		}
		select {
		case <-a.done:
			if a.err == nil {
				res = a.res
			}
		default:
		}
	}
	if res == nil {
		var err error
		if res, err = s.forYouBatch(ss, ch, offset, cursor, session); err != nil {
			return nil, err
		}
	}
	s.readAhead(ss, ch, res)
	return view{"page": page(res.p), "cell": res.cell, "session": res.session}, nil
}

func (s *Server) forYouBatch(ss *sess, ch string, offset int, cursor, session string) (*fyResult, error) {
	p, cell, sessID, err := s.content.ForYou(ss.acct, ch, offset, cursor, session)
	if err != nil {
		return nil, err
	}
	// 上游一页只给 3~6 部：并行多取几页凑够整屏首屏再返回，避免切频道后立刻触发二次加载。
	// 翻页步长取自上游（漫剧等频道固定 6），后续偏移按步长推算，只在相邻页游标吻合时采用。
	if p.More && cell != "" && len(p.Items) < 18 {
		const step = 6
		type res struct {
			p *hg.Page
			c string
			s string
		}
		offs := []int{p.Next, p.Next + step, p.Next + 2*step}
		got := make([]*res, len(offs))
		var wg sync.WaitGroup
		for i, off := range offs {
			wg.Add(1)
			go func(i, off int) {
				defer wg.Done()
				if n, c2, s2, err := s.content.ForYou(ss.acct, ch, off, cell, sessID); err == nil {
					got[i] = &res{n, c2, s2}
				}
			}(i, off)
		}
		wg.Wait()
		seen := map[string]bool{}
		for _, it := range p.Items {
			seen[it.ID] = true
		}
		for i, g := range got {
			// 后续页的 offset 是推算的：只有前一页的真实游标正好落在推算位置时才采用，否则交由前端续取
			if g == nil || (i > 0 && (got[i-1] == nil || got[i-1].p.Next != offs[i])) {
				break
			}
			for _, it := range g.p.Items {
				if !seen[it.ID] {
					seen[it.ID] = true
					p.Items = append(p.Items, it)
				}
			}
			p.Next, p.More, cell, sessID = g.p.Next, g.p.More, g.c, g.s
		}
	}
	return &fyResult{p, cell, sessID}, nil
}

func (s *Server) rank(ss *sess, w http.ResponseWriter, r *http.Request) (any, error) {
	board := q(r, "board")
	if board == "" {
		board = hg.BoardHot
	}
	p, err := s.content.Rank(s.cookie(ss), board, qi(r, "offset"), q(r, "session"))
	if err != nil {
		return nil, err
	}
	return page(p), nil
}

func (s *Server) categoryOptions(ss *sess, w http.ResponseWriter, r *http.Request) (any, error) {
	typ := q(r, "type")
	if typ == "" {
		typ = "all"
	}
	return s.content.CategoryOptions(s.cookie(ss), typ)
}

func (s *Server) category(ss *sess, w http.ResponseWriter, r *http.Request) (any, error) {
	sel := map[string]string{}
	for _, kv := range r.URL.Query()["sel"] { // sel=分组名:选项id
		if i := strings.Index(kv, ":"); i > 0 {
			sel[kv[:i]] = kv[i+1:]
		}
	}
	typ := q(r, "type")
	if typ == "" {
		typ = "all"
	}
	p, err := s.content.CategoryPage(s.cookie(ss), typ, q(r, "board"), sel, qi(r, "offset"), q(r, "session"))
	if err != nil {
		return nil, err
	}
	return page(p), nil
}

func (s *Server) search(ss *sess, w http.ResponseWriter, r *http.Request) (any, error) {
	res, err := s.content.Search(ss.acct, q(r, "q"), qi(r, "page"))
	if err != nil {
		return nil, err
	}
	cp := *res
	cp.Items = series(res.Items)
	return cp, nil
}

func (s *Server) suggest(ss *sess, w http.ResponseWriter, r *http.Request) (any, error) {
	return view{"items": s.content.Suggest(ss.acct, q(r, "q"))}, nil
}

func (s *Server) detail(ss *sess, w http.ResponseWriter, r *http.Request) (any, error) {
	d, err := s.content.Detail(s.cookie(ss), q(r, "id"))
	if err != nil {
		return nil, err
	}
	cp := *d
	cp.Cover = imgURL(d.Cover)
	cp.Episodes = make([]hg.Episode, len(d.Episodes))
	for i, e := range d.Episodes {
		e.Cover = imgURL(e.Cover)
		cp.Episodes[i] = e
	}
	out := view{"detail": cp}
	// lite=1：首页推荐懒加载剧集信息，不查收藏 / 历史 / 预约，省掉 4 次上游请求
	if ss.acct.U.LoggedIn() && !s.dev && q(r, "lite") != "1" {
		var fav []hg.Entry
		var h []hg.Entry
		var reservation *hg.Reservation
		var wg sync.WaitGroup
		wg.Add(3)
		go func() { defer wg.Done(); fav, _ = ss.acct.Favorites() }()
		go func() { defer wg.Done(); h, _ = s.cloudHistory(ss, 500) }()
		go func() {
			defer wg.Done()
			for _, online := range []bool{false, true} {
				if rs, err := ss.acct.Reservations(online); err == nil {
					for _, rv := range rs {
						if rv.SeriesID == d.ID {
							found := rv
							reservation = &found
							return
						}
					}
				}
			}
		}()
		wg.Wait()
		{
			for _, f := range fav {
				if f.SeriesID == d.ID {
					out["favorite"] = true
				}
			}
		}
		{
			for _, e := range h {
				if e.SeriesID == d.ID {
					out["progress"] = e
					break
				}
			}
		}
		if reservation != nil {
			out["reserved"] = true
			out["reservation"] = *reservation
		}
	}
	return out, nil
}

// ------------------------------------------------------------ 我的

func (s *Server) history(ss *sess, w http.ResponseWriter, r *http.Request) (any, error) {
	if !ss.acct.U.LoggedIn() && s.gate != nil {
		if m := s.gate.guestMember(r); m != nil {
			if m.BrowseOnly {
				return view{"items": []hg.Entry{}}, nil
			}
			return view{"items": entries(s.guestHistory(m.GuestID, s.cookie(ss), 300))}, nil
		}
	}
	if err := s.auth(ss); err != nil {
		return nil, err
	}
	if s.dev {
		return view{"items": []hg.Entry{}}, nil
	}
	h, err := s.cloudHistory(ss, 300, q(r, "fresh") == "1")
	if err != nil {
		return nil, err
	}
	return view{"items": entries(h)}, nil
}

func (s *Server) historyDelete(ss *sess, w http.ResponseWriter, r *http.Request) (any, error) {
	if !ss.acct.U.LoggedIn() && s.gate != nil {
		if m := s.gate.guestMember(r); m != nil {
			if m.BrowseOnly {
				return nil, &httpErr{403, "仅浏览记录保存在浏览器中"}
			}
			var in struct{ ID string }
			if err := decode(r, &in); err != nil {
				return nil, err
			}
			if !digits.MatchString(in.ID) {
				return nil, bad("参数无效")
			}
			s.guestLibrary(m.GuestID).forget(in.ID)
			return view{"ok": true}, nil
		}
	}
	if err := s.auth(ss); err != nil {
		return nil, err
	}
	var in struct{ ID string }
	if err := decode(r, &in); err != nil {
		return nil, err
	}
	if !digits.MatchString(in.ID) {
		return nil, bad("参数无效")
	}
	if err := ss.acct.DeleteHistory(in.ID); err != nil {
		return nil, err
	}
	s.lib(ss).forget(in.ID)
	return view{"ok": true}, nil
}

func (s *Server) favorites(ss *sess, w http.ResponseWriter, r *http.Request) (any, error) {
	if err := s.auth(ss); err != nil {
		return nil, err
	}
	if s.dev {
		return view{"items": []hg.Entry{}}, nil
	}
	f, err := ss.acct.Favorites()
	if err != nil {
		return nil, err
	}
	sort.SliceStable(f, func(i, j int) bool { return f[i].Time > f[j].Time })
	ss.acct.ApplyProgress(f)
	return view{"items": entries(f)}, nil
}

func (s *Server) favorite(ss *sess, w http.ResponseWriter, r *http.Request) (any, error) {
	if err := s.auth(ss); err != nil {
		return nil, err
	}
	var in struct {
		ID  string `json:"id"`
		Add bool   `json:"add"`
	}
	if err := decode(r, &in); err != nil {
		return nil, err
	}
	if !digits.MatchString(in.ID) {
		return nil, bad("参数无效")
	}
	if s.dev {
		return view{"ok": true, "favorite": in.Add}, nil
	}
	if err := ss.acct.SetFavorite(in.ID, in.Add); err != nil {
		return nil, err
	}
	return view{"ok": true, "favorite": in.Add}, nil
}

func (s *Server) progress(ss *sess, w http.ResponseWriter, r *http.Request) (any, error) {
	var guest *gateInvite
	if !ss.acct.U.LoggedIn() && s.gate != nil {
		guest = s.gate.guestMember(r)
	}
	if err := s.auth(ss); err != nil && guest == nil {
		return nil, err
	}
	var in struct {
		ID           string `json:"id"`
		Vid          string `json:"vid"`
		Index        int    `json:"index"`
		Pos          int    `json:"pos"`
		Title        string `json:"title"`
		Cover        string `json:"cover"`
		EpisodeCount int    `json:"episodeCount"`
		// Final 表示暂停 / 换集 / 离开页面：预约几秒后上传，而不是等 2 分钟兜底
		Final bool `json:"final"`
	}
	if err := decode(r, &in); err != nil {
		return nil, err
	}
	if !digits.MatchString(in.ID) {
		return nil, bad("参数无效")
	}
	if s.dev {
		return view{"ok": true}, nil
	}
	if !digits.MatchString(in.ID) || !digits.MatchString(in.Vid) {
		return nil, bad("参数无效")
	}
	if guest != nil {
		if guest.BrowseOnly {
			return nil, &httpErr{403, "仅浏览记录保存在浏览器中"}
		}
		if len(in.Title) > 300 || len(in.Cover) > 4096 {
			return nil, bad("参数无效")
		}
		l := s.guestLibrary(guest.GuestID)
		l.put(in.ID, in.Vid, max(0, in.Index), max(0, in.Pos))
		if err := l.setGuestMetadata(in.ID, in.Title, in.Cover, min(max(in.EpisodeCount, 0), 100000)); err != nil {
			return nil, errors.New("保存临时观看记录失败，请稍后重试")
		}
		return view{"ok": true}, nil
	}
	// 只写本地库（标脏），由后台上传器批量上报；不在这里调用上游
	s.lib(ss).put(in.ID, in.Vid, max(0, in.Index), max(0, in.Pos))
	if in.Final {
		s.scheduleUpload(ss)
	}
	return view{"ok": true}, nil
}

// importGuestProgress merges the invite's temporary viewing history into the
// signed-in account, preserving timestamps and never replacing newer progress.
func (s *Server) importGuestProgress(ss *sess, w http.ResponseWriter, r *http.Request) (any, error) {
	if err := s.auth(ss); err != nil {
		return nil, err
	}
	if s.gate == nil {
		return view{"ok": true, "imported": 0}, nil
	}
	guest := s.gate.guestMember(r)
	if guest == nil {
		return view{"ok": true, "imported": 0}, nil
	}
	if guest.BrowseOnly {
		return nil, &httpErr{403, "仅浏览邀请码不能绑定红果账号"}
	}
	uid := ""
	if s.gate.identity != nil {
		uid, _ = s.gate.identity(r)
	}
	if !s.gate.guestBoundTo(r, uid) {
		return nil, &httpErr{403, "请先绑定此临时身份"}
	}
	l := s.guestLibrary(guest.GuestID)
	l.mu.Lock()
	items := make([]libRow, 0, len(l.rows))
	for _, e := range l.rows {
		if !e.Deleted {
			items = append(items, *e)
		}
	}
	l.mu.Unlock()
	if len(items) == 0 {
		return view{"ok": true, "imported": 0}, nil
	}
	ids := make([]string, 0, len(items))
	for _, e := range items {
		if digits.MatchString(e.SeriesID) {
			ids = append(ids, e.SeriesID)
		}
	}
	var cloud map[string]int64
	var err error
	if s.guestLatest != nil {
		cloud, err = s.guestLatest(ss, ids)
	} else {
		cloud, err = ss.acct.LatestWatchTimes(ids)
	}
	if err != nil {
		return nil, err
	}
	account := s.lib(ss)
	imported := mergeGuestRows(account, items, cloud, time.Now().UnixMilli())
	account.mu.Lock()
	saveErr := account.saveLocked()
	account.mu.Unlock()
	if saveErr != nil {
		return nil, errors.New("无法保存合并后的观看记录，请重试")
	}
	if imported > 0 || len(account.dirty()) > 0 {
		account.invalidateHistory()
		s.scheduleUpload(ss)
	}
	return view{"ok": true, "imported": imported}, nil
}

func mergeGuestRows(account *library, items []libRow, cloud map[string]int64, now int64) int {
	imported := 0
	for _, e := range items {
		if !digits.MatchString(e.SeriesID) || !digits.MatchString(e.Vid) || e.Index < 0 || e.Pos < 0 || e.TS <= 0 || e.TS > now+5*60*1000 {
			continue
		}
		if e.TS <= cloud[e.SeriesID] {
			continue
		}
		if account.putAt(e.SeriesID, e.Vid, e.Index, e.Pos, e.TS, true) {
			_ = account.setGuestMetadata(e.SeriesID, e.Title, e.Cover, e.Count)
			imported++
			cloud[e.SeriesID] = e.TS
		}
	}
	return imported
}

// comments 返回本集评论（弹幕来源）：只取文本与头像。
func (s *Server) comments(ss *sess, w http.ResponseWriter, r *http.Request) (any, error) {
	d, err := s.content.Comments(s.cookie(ss), q(r, "id"), q(r, "ep"), qi(r, "offset"))
	if err != nil {
		return nil, err
	}
	items := []view{}
	for _, c := range commentRows(d) {
		items = append(items, c)
	}
	return view{"items": items, "total": d["total"]}, nil
}

// ------------------------------------------------------------ 播放

func (s *Server) play(ss *sess, w http.ResponseWriter, r *http.Request) (any, error) {
	vid := q(r, "vid")
	if q(r, "fresh") == "1" {
		s.content.ForgetPlay(vid)
	}
	gears, err := s.content.Play(s.player, vid)
	if err != nil {
		// A complete decrypted episode remains playable even when the upstream
		// model service is temporarily unavailable; never claim an uncached one.
		definition := q(r, "q")
		if definition != "" && s.media.HasPlayable(vid+"|"+definition) {
			return view{"src": "/media/" + vid + ".mp4?q=" + url.QueryEscape(definition), "definition": definition, "gears": []view{}}, nil
		}
		return nil, publicPlaybackError(err)
	}
	limit, _ := strconv.Atoi(q(r, "max"))
	g := hg.PickGear(gears, q(r, "q"), limit)
	if g == nil {
		return nil, &httpErr{502, "没有可播放的清晰度"}
	}
	list := []view{}
	for _, x := range gears {
		if x.ShortSide() > 0 {
			// 宽高给前端在视频元数据到达前就确定画框横竖
			list = append(list, view{"definition": x.Definition, "codec": x.Codec, "short": x.ShortSide(), "size": x.Size, "width": x.Width, "height": x.Height})
		}
	}
	sort.Slice(list, func(i, j int) bool { return list[i]["short"].(int) < list[j]["short"].(int) })
	// 异步提前打开流式句柄并预热首片，减少随后 <video> 发起 Range 请求的等待。
	go s.media.WarmStream(s.content, s.player, vid, g.Definition)
	// 媒体地址带上选中的清晰度，解密后的整集由 /media 提供（支持 Range 拖动）。
	return view{"src": "/media/" + vid + ".mp4?q=" + url.QueryEscape(g.Definition), "definition": g.Definition, "gears": list}, nil
}

func publicPlaybackError(err error) error {
	if errors.Is(err, hg.ErrSessionExpired) {
		return err
	}
	// Keep route, signature, and fallback details out of the user-facing API.
	return &httpErr{502, "本集暂时无法播放，请稍后重试或选择其他剧集"}
}

func (s *Server) seasons(ss *sess, w http.ResponseWriter, r *http.Request) (any, error) {
	list, err := s.content.Seasons(s.cookie(ss), q(r, "id"))
	if err != nil {
		return nil, err
	}
	out := make([]view, 0, len(list))
	for _, se := range list {
		out = append(out, view{"id": se.ID, "title": se.Title, "cover": imgURL(se.Cover), "number": se.Number, "episodes": se.Episodes, "current": se.Current})
	}
	return view{"seasons": out}, nil
}

func (s *Server) reservations(ss *sess, w http.ResponseWriter, r *http.Request) (any, error) {
	if err := s.auth(ss); err != nil {
		return nil, err
	}
	if s.dev {
		return view{"online": []hg.Reservation{}, "upcoming": []hg.Reservation{}}, nil
	}
	on, errOn := ss.acct.Reservations(true)
	off, errOff := ss.acct.Reservations(false)
	if errOn != nil && errOff != nil {
		return nil, errOn
	}
	fix := func(list []hg.Reservation) []hg.Reservation {
		if list == nil {
			return []hg.Reservation{}
		}
		for i := range list {
			list[i].Cover = imgURL(list[i].Cover)
		}
		return list
	}
	return view{"online": fix(on), "upcoming": fix(off), "partial": errOn != nil || errOff != nil, "limited": len(on) >= 50 || len(off) >= 50}, nil
}

func (s *Server) reservation(ss *sess, w http.ResponseWriter, r *http.Request) (any, error) {
	if err := s.auth(ss); err != nil {
		return nil, err
	}
	var in struct {
		ID  string `json:"id"`
		Add bool   `json:"add"`
	}
	if err := decode(r, &in); err != nil {
		return nil, err
	}
	if !digits.MatchString(in.ID) {
		return nil, bad("参数无效")
	}
	if s.dev {
		return view{"ok": true, "reserved": in.Add}, nil
	}
	if err := ss.acct.SetReservation(in.ID, in.Add); err != nil {
		return nil, err
	}
	return view{"ok": true, "reserved": in.Add}, nil
}
