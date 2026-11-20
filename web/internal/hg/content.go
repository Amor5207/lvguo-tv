package hg

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/sha512"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"lvguo/internal/hgplay"
	"lvguo/internal/sign"
)

const (
	PathCellChange = "/reading/bookapi/bookmall/cell/change/v1/"
	PathBookmall   = "/reading/bookapi/bookmall/tab/v1/"
	PathAlbum      = "/novel/player/album_detail/v1/"
	DefaultCell    = "7470092475068071998"

	BoardHot       = "ranklist_hot_play_sc"
	BoardRecommend = "ranklist_hot_sc"
	BoardNew       = "ranklist_new_rank_sc"
)

// Series 是榜单 / 搜索 / 收藏里的一部剧。
type Series struct {
	ID       string `json:"id"`
	Title    string `json:"title"`
	Cover    string `json:"cover"`
	Episodes int    `json:"episodes"`
	Plays    int64  `json:"plays"`
	Intro    string `json:"intro,omitempty"`
	Category string `json:"category,omitempty"`
	FirstVid string `json:"firstVid,omitempty"`
	Rank     int    `json:"rank,omitempty"`
}

type Episode struct {
	Index    int    `json:"index"`
	Title    string `json:"title"`
	Vid      string `json:"vid"`
	Duration int    `json:"duration"`
	Cover    string `json:"cover,omitempty"`
}

type Detail struct {
	ID       string    `json:"id"`
	Title    string    `json:"title"`
	Cover    string    `json:"cover"`
	Intro    string    `json:"intro"`
	Count    int       `json:"count"`
	Rating   string    `json:"rating,omitempty"`
	Heat     string    `json:"heat,omitempty"`
	Tag      string    `json:"tag,omitempty"`
	Plays    int64     `json:"plays"`
	Actors   []string  `json:"actors"`
	Episodes []Episode `json:"episodes"`

	MultiSeason bool   `json:"multiSeason,omitempty"`
	SeasonIndex int    `json:"seasonIndex,omitempty"`
	AlbumID     string `json:"-"` // 同系列（季）归属，用于拉取整季列表
}

// Gear 是一个清晰度档位。
type Gear struct {
	Definition string   `json:"definition"`
	Codec      string   `json:"codec"`
	Width      int      `json:"width"`
	Height     int      `json:"height"`
	Size       int64    `json:"size"`
	MainURL    string   `json:"-"`
	BackupURL  string   `json:"-"`
	BackupURLs []string `json:"-"`
	Encrypted  bool     `json:"-"`
	SpadeA     string   `json:"-"`
}

func (g Gear) ShortSide() int {
	s := g.Width
	if g.Height < s || s == 0 {
		s = g.Height
	}
	return s
}

// Content 提供榜单、分类、搜索、详情、播放地址。
type Content struct {
	C         *Client
	mu        sync.Mutex
	cache     map[string]cacheItem
	playLoads map[string]*playModelLoad
	// 分类选择器是全站共享数据，取一次长期缓存
	selector map[string]any
}

type cacheItem struct {
	at  time.Time
	ttl time.Duration
	v   any
}

func NewContent(c *Client) *Content {
	return &Content{C: c, cache: map[string]cacheItem{}}
}

func (c *Content) get(key string) (any, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	it, ok := c.cache[key]
	if !ok || time.Since(it.at) > it.ttl {
		delete(c.cache, key)
		return nil, false
	}
	return it.v, true
}

func (c *Content) trimCacheLocked() {
	if c.cache == nil {
		c.cache = map[string]cacheItem{}
		return
	}
	if len(c.cache) <= 2000 {
		return
	}
	now := time.Now()
	for k, it := range c.cache {
		if now.Sub(it.at) > it.ttl {
			delete(c.cache, k)
		}
	}
	if len(c.cache) > 2000 {
		c.cache = map[string]cacheItem{}
	}
}

func (c *Content) put(key string, v any, ttl time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.trimCacheLocked()
	c.cache[key] = cacheItem{time.Now(), ttl, v}
}

// ------------------------------------------------------------ 榜单

type Page struct {
	Items   []Series `json:"items"`
	Next    int      `json:"next"`
	More    bool     `json:"more"`
	Session string   `json:"session"`
}

var categoryNameRe = regexp.MustCompile(`"name"\s*:\s*"([^"]*)"`)

func categoryNames(raw string) string {
	raw = strings.TrimSpace(raw)
	if !strings.HasPrefix(raw, "[") {
		return raw
	}
	var rows []any
	if json.Unmarshal([]byte(raw), &rows) != nil {
		return ""
	}
	var names []string
	for _, r := range rows {
		n := ""
		if o := obj(r); o != nil {
			n = strings.TrimSpace(str(o["name"]))
		} else {
			n = strings.TrimSpace(str(r))
		}
		if n != "" && n != "null" {
			names = append(names, n)
		}
	}
	return strings.Join(names, ",")
}

// collect 递归收集节点里的剧（榜单响应嵌套很深）。
func collect(v any, out *[]Series, seen map[string]bool) {
	switch x := v.(type) {
	case []any:
		for _, e := range x {
			collect(e, out, seen)
		}
	case map[string]any:
		sid := str(x["series_id"])
		title := nz(str(x["series_title"]), nz(str(x["series_name"]), str(x["title"])))
		if sid != "" && sid != "0" && title != "" && !seen[sid] {
			seen[sid] = true
			*out = append(*out, Series{
				ID: sid, Title: title, Cover: nz(str(x["cover"]), str(x["series_cover"])),
				FirstVid: str(x["vid"]), Episodes: int(num(x["episode_cnt"])),
				Plays: int64(num(nz(str(x["play_cnt"]), str(x["series_play_cnt"])))),
				Intro: nz(str(x["video_desc"]), str(x["series_intro"])), Category: categoryNames(str(x["category_schema"])),
			})
		}
		for _, child := range x {
			switch child.(type) {
			case []any, map[string]any:
				collect(child, out, seen)
			}
		}
	}
}

func (c *Content) page(cookie, typ, board, filter string, offset int, session string) (*Page, map[string]any, error) {
	if session == "" {
		session = uuid()
	}
	m, err := c.C.Call(cookie, PathCellChange, nil, sign.Params{
		{K: "cell_id", V: DefaultCell}, {K: "tab_type", V: "26"}, {K: "client_req_type", V: "2"}, {K: "client_template", V: "2"},
		{K: "screen_width_px", V: "1350"}, {K: "selected_items", V: typ}, {K: "sub_selected_items", V: board},
		{K: "panel_selected_items", V: filter}, {K: "offset", V: strconv.Itoa(offset)}, {K: "session_uuid", V: session}})
	if err != nil {
		return nil, nil, err
	}
	cell := obj(obj(m["data"])["cell_view"])
	if m["_empty"] != nil || int(num(m["code"])) != 0 || cell == nil {
		return nil, nil, errors.New(nz(str(m["message"]), "榜单加载失败"))
	}
	p := &Page{Next: int(num(cell["next_offset"])), Session: nz(str(cell["session_id"]), session)}
	if p.Next == 0 {
		p.Next = offset
	}
	p.More = cell["has_more"] == true && p.Next > offset
	collect(cell["cell_data"], &p.Items, map[string]bool{})
	for i := range p.Items {
		p.Items[i].Rank = offset + i + 1
	}
	sel := obj(cell["cell_selector"])
	if sel != nil {
		c.mu.Lock()
		c.selector = sel
		c.mu.Unlock()
	}
	return p, sel, nil
}

// Rank 榜单一页。前几页是公共高频数据（首页主视觉、首页热播榜、榜单首屏），短缓存避免重复请求上游。
func (c *Content) Rank(cookie, board string, offset int, session string) (*Page, error) {
	cacheKey := ""
	if offset >= 0 && offset <= 30 {
		cacheKey = "rank:" + board + ":" + strconv.Itoa(offset)
		if v, ok := c.get(cacheKey); ok {
			cp := *(v.(*Page))
			cp.Items = append([]Series(nil), cp.Items...)
			return &cp, nil
		}
	}
	p, _, err := c.page(cookie, "all", board, "", offset, session)
	if err == nil && p != nil && len(p.Items) > 0 && cacheKey != "" {
		cp := *p
		cp.Items = append([]Series(nil), p.Items...)
		c.put(cacheKey, &cp, 3*time.Minute)
	}
	return p, err
}

// ForYou 红果「找剧」推荐流：首屏取 bookmall，之后走签名版 cell/change（按账号口味）。
// 推荐流用的是红果「推荐」频道的专用栏目（tab_type=16），不是书城首页的榜单栏目：
// 内容按账号个性化，每次请求都会变。栏目与方案 ID 是固定值（同 aston314/hongguo-tv）。
const (
	feedCellID = "7294257141911650341"
	feedPlanID = "7294257258253254693"
)

// 推荐频道：与红果 App 首页顶部的频道一致。
var feedChannels = map[string]bool{"16": true, "36": true, "39": true}

// FeedChannel 是首页推荐区可切换的一个频道。
type FeedChannel struct {
	ID    string `json:"id"`
	Label string `json:"label"`
}

var feedLabels = [][2]string{{"16", "推荐"}, {"36", "漫剧"}, {"39", "真人剧"}}

// FeedChannels 返回当前账号实际开放的推荐频道（红果按账号下发，不是每个账号都有全部频道）。结果缓存 30 分钟。
func (c *Content) FeedChannels(a *Account) []FeedChannel {
	key := "feedch:" + a.U.Jar["sessionid"]
	if v, ok := c.get(key); ok {
		return v.([]FeedChannel)
	}
	out := []FeedChannel{{ID: "16", Label: "推荐"}}
	m, err := a.Signed(AppHost, PathBookmall, sign.Params{
		{K: "bottom_tab_type", V: "7"}, {K: "landing_bottom_tab_type", V: "7"}, {K: "need_personal_recommend", V: "1"},
		{K: "device_level", V: "3"}, {K: "screen_width_px", V: "1078"}, {K: "is_horizontal_screen", V: "false"},
		{K: "tab_type", V: "16"}, {K: "offset", V: "0"}, {K: "session_id", V: ""}, {K: "client_req_type", V: "1"}}, nil)
	if err != nil {
		return out // 失败不缓存，下次再试
	}
	have := map[string]bool{}
	for _, tv := range arr(obj(m["data"])["tab_item"]) {
		have[str(obj(tv)["tab_type"])] = true
	}
	for _, kv := range feedLabels[1:] {
		if have[kv[0]] {
			out = append(out, FeedChannel{ID: kv[0], Label: kv[1]})
		}
	}
	c.put(key, out, 30*time.Minute)
	return out
}

// ForYou 返回一页推荐流。channel：16 推荐 / 36 漫剧 / 39 真人剧。
// cursor 是上一页返回的翻页标识（「栏目ID.方案ID」），首页传空串。
func (c *Content) ForYou(a *Account, channel string, offset int, cursor, session string) (*Page, string, string, error) {
	if !feedChannels[channel] {
		channel = "16"
	}
	if offset < 0 {
		offset = 0
	}
	cell, plan := feedCellID, feedPlanID
	if channel != "16" {
		cell, plan = "", ""
		if k := strings.IndexByte(cursor, '.'); k > 0 && digits.MatchString(cursor[:k]) && digits.MatchString(cursor[k+1:]) {
			cell, plan = cursor[:k], cursor[k+1:]
		}
	}
	if cell == "" {
		// 非默认频道的第一页走 bookmall/tab：拿到首屏内容和该频道的栏目 / 方案 ID，之后用 cell/change 从 0 翻页
		m, err := a.Signed(AppHost, PathBookmall, sign.Params{
			{K: "bottom_tab_type", V: "7"}, {K: "landing_bottom_tab_type", V: "7"}, {K: "need_personal_recommend", V: "1"},
			{K: "device_level", V: "3"}, {K: "screen_width_px", V: "1078"}, {K: "is_horizontal_screen", V: "false"},
			{K: "disable_digg_stat", V: "false"}, {K: "auth_aweme", V: "true"}, {K: "auth_backward", V: "true"},
			{K: "has_video_cache", V: "false"}, {K: "last_tab_type", V: "0"}, {K: "last_tab_index", V: "0"}, {K: "tab_index", V: "0"},
			{K: "req_rank_category_id", V: "0"}, {K: "video_type_preferences_str", V: "[]"}, {K: "tab_type", V: channel},
			{K: "offset", V: "0"}, {K: "session_id", V: ""}, {K: "client_req_type", V: "1"}}, nil)
		if err != nil {
			return nil, "", "", err
		}
		for _, tv := range arr(obj(m["data"])["tab_item"]) {
			t := obj(tv)
			if str(t["tab_type"]) != channel {
				continue
			}
			p := &Page{Session: str(t["session_id"])}
			cells := arr(t["cell_data"])
			collect(cells, &p.Items, map[string]bool{})
			p.Items = dramasOnly(p.Items)
			for _, cv := range cells {
				cl := obj(cv)
				if id := nz(str(cl["cell_id_str"]), str(cl["cell_id"])); id != "0" && digits.MatchString(id) {
					cell = id
					break
				}
			}
			plan = str(t["bookstore_id"])
			if cell == "" || !digits.MatchString(plan) {
				return p, "", p.Session, nil
			}
			p.Next, p.More = 0, true
			return p, cell + "." + plan, p.Session, nil
		}
		return nil, "", "", errors.New("推荐加载失败")
	}
	m, err := a.Signed(AppHost, PathCellChange, sign.Params{
		{K: "bottom_tab_type", V: "7"}, {K: "client_req_type", V: "2"}, {K: "client_template", V: "7"},
		{K: "change_type", V: "0"}, {K: "category_id", V: "0"}, {K: "cell_sub_id", V: "0"}, {K: "limit", V: "0"},
		{K: "need_personal_recommend", V: "1"}, {K: "video_type_preferences_str", V: "[]"},
		{K: "cell_id", V: cell}, {K: "plan_id", V: plan}, {K: "tab_type", V: channel},
		{K: "offset", V: strconv.Itoa(offset)}, {K: "session_id", V: session}, {K: "filter_ids", V: ""}}, nil)
	if err != nil {
		return nil, "", "", err
	}
	d := obj(m["data"])
	cv := obj(d["cell_view"])
	if int(num(m["code"])) != 0 || cv == nil {
		return nil, "", "", errors.New("推荐加载失败")
	}
	p := &Page{Session: nz(str(d["session_id"]), session)}
	collect(cv["cell_data"], &p.Items, map[string]bool{})
	p.Items = dramasOnly(p.Items)
	p.Next = int(num(d["next_offset"]))
	p.More = d["has_more"] == true && p.Next > offset && len(p.Items) > 0
	return p, cell + "." + plan, p.Session, nil
}

// dramasOnly 去掉推荐流里混入的非剧集内容（公益提示、运营位等：没有封面或没有集数）。
func dramasOnly(in []Series) []Series {
	out := in[:0]
	for _, s := range in {
		if s.Cover != "" && s.Episodes > 0 {
			out = append(out, s)
		}
	}
	return out
}

// ------------------------------------------------------------ 分类

type Option struct {
	ID    string `json:"id"`
	Label string `json:"label"`
}
type Group struct {
	Name  string   `json:"name"`
	Items []Option `json:"items"`
}
type Board struct {
	ID     string  `json:"id"`
	Label  string  `json:"label"`
	Groups []Group `json:"groups"`
}
type CategoryOptions struct {
	Types  []Option `json:"types"`
	Boards []Board  `json:"boards"`
}

func (c *Content) CategoryOptions(cookie, typ string) (*CategoryOptions, error) {
	c.mu.Lock()
	sel := c.selector
	c.mu.Unlock()
	if sel == nil || obj(sel["outer_row"]) == nil {
		if _, _, err := c.page(cookie, "all", BoardRecommend, "", 0, ""); err != nil {
			return nil, err
		}
		c.mu.Lock()
		sel = c.selector
		c.mu.Unlock()
	}
	out := &CategoryOptions{Types: []Option{}, Boards: []Board{}}
	allowed := map[string]bool{"all": true, "human": true, "comic_series_rank": true, "ai_playlet": true}
	for _, it := range arr(obj(sel["outer_row"])["items"]) {
		item := obj(it)
		id := str(item["selector_item_id"])
		if item == nil || !allowed[id] {
			continue
		}
		out.Types = append(out.Types, Option{id, str(item["show_name"])})
		if id != typ {
			continue
		}
		for _, b := range arr(obj(obj(item["sub_cell_selector"])["outer_row"])["items"]) {
			bo := obj(b)
			if bo == nil {
				continue
			}
			board := Board{ID: str(bo["selector_item_id"]), Label: str(bo["show_name"])}
			for _, g := range arr(obj(bo["panel_selector"])["inner_rows"]) {
				go_ := obj(g)
				if go_ == nil {
					continue
				}
				grp := Group{Name: str(go_["row_name"])}
				for _, o := range arr(go_["items"]) {
					op := obj(o)
					if op != nil && str(op["selector_item_id"]) != "" {
						grp.Items = append(grp.Items, Option{str(op["selector_item_id"]), str(op["show_name"])})
					}
				}
				board.Groups = append(board.Groups, grp)
			}
			out.Boards = append(out.Boards, board)
		}
	}
	return out, nil
}

func matchesCategory(raw, label string) bool {
	if raw == "" {
		return false
	}
	names := []string{label}
	switch label {
	case "历史古代", "古装":
		names = []string{"古代", "古风", "古装"}
	case "校园":
		names = []string{"校园", "青春"}
	}
	for _, c := range names {
		if strings.Contains(raw, c) {
			return true
		}
	}
	return false
}

// CategoryPage 分类页：服务端只认一个锚点条件，其余条件按返回的题材元数据过滤。
func (c *Content) CategoryPage(cookie, typ, board string, selected map[string]string, offset int, session string) (*Page, error) {
	opts, err := c.CategoryOptions(cookie, typ)
	if err != nil {
		return nil, err
	}
	var cur *Board
	for i := range opts.Boards {
		if opts.Boards[i].ID == board {
			cur = &opts.Boards[i]
		}
	}
	if cur == nil {
		if len(opts.Boards) == 0 {
			return nil, errors.New("分类暂不可用")
		}
		cur = &opts.Boards[0]
		board = cur.ID
	}
	type cond struct{ id, label string }
	var conds []cond
	for _, g := range cur.Groups {
		want := selected[g.Name]
		for _, it := range g.Items {
			if want != "" && it.ID == want {
				conds = append(conds, cond{it.ID, it.Label})
			}
		}
	}
	anchor := ""
	for _, cd := range conds {
		anchor = cd.id
	}
	for _, cd := range conds {
		if strings.HasPrefix(cd.id, "gender_") {
			anchor = cd.id
		}
	}
	out := &Page{Next: offset, Session: session}
	seen := map[string]bool{}
	for pages := 0; pages < 4; pages++ {
		part, _, err := c.page(cookie, typ, board, anchor, out.Next, out.Session)
		if err != nil {
			if len(out.Items) > 0 {
				break
			}
			return nil, err
		}
		out.Next, out.Session, out.More = part.Next, part.Session, part.More
		for _, s := range part.Items {
			okm := true
			for _, cd := range conds {
				if strings.HasPrefix(cd.id, "gender_") {
					continue
				}
				if !matchesCategory(s.Category, cd.label) {
					okm = false
					break
				}
			}
			if okm && !seen[s.ID] {
				seen[s.ID] = true
				out.Items = append(out.Items, s)
			}
		}
		if !out.More || len(out.Items) >= 12 {
			break
		}
	}
	return out, nil
}

// ------------------------------------------------------------ 详情

func albumBody(id string, batch []string) map[string]any {
	if batch == nil {
		batch = []string{id}
	}
	ids := make([]json.Number, len(batch))
	for i, s := range batch {
		ids[i] = json.Number(s)
	}
	return map[string]any{
		"album_id": id, "series_ids": ids, "need_video_detail_info": true,
		"biz_param": map[string]any{"video_platform": 1024, "need_all_video_definition": true, "need_mp4_align": true,
			"use_os_player": false, "device_level": DeviceLevelH265, "screen_width_px": "1350", "source": 7, "video_id_type": 1},
	}
}

// HistorySummaries resolves progress-only series in batches, without fetching
// each series separately. Missing metadata is left out rather than inventing it.
func (c *Content) HistorySummaries(cookie string, ids []string) map[string]Entry {
	out := make(map[string]Entry)
	var missing []string
	for _, id := range ids {
		if v, ok := c.get("history-summary:" + id); ok {
			out[id] = v.(Entry)
			continue
		}
		if v, ok := c.get("detail:" + id); ok {
			d := v.(*Detail)
			out[id] = Entry{SeriesID: id, Title: d.Title, Cover: d.Cover, EpisodeCount: d.Count}
			continue
		}
		if digits.MatchString(id) {
			missing = append(missing, id)
		}
	}
	var mu sync.Mutex
	var wg sync.WaitGroup
	sem := make(chan struct{}, 4)
	for from := 0; from < len(missing); from += 20 {
		batch := append([]string(nil), missing[from:min(from+20, len(missing))]...)
		wg.Add(1)
		go func() {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()
			m, err := c.C.CallRetry(cookie, PathAlbum, albumBody(batch[0], batch), nil)
			if err != nil || int(num(m["code"])) != 0 {
				return
			}
			data := obj(obj(m["data"])["video_detail_data"])
			for _, id := range batch {
				v := obj(obj(data[id])["video_data"])
				if v == nil {
					continue
				}
				e := Entry{SeriesID: id, Title: str(v["series_title"]), Cover: str(v["series_cover"]), EpisodeCount: int(num(v["episode_cnt"]))}
				if e.Title == "" {
					continue
				}
				c.put("history-summary:"+id, e, 6*time.Hour)
				mu.Lock()
				out[id] = e
				mu.Unlock()
			}
		}()
	}
	wg.Wait()
	return out
}

func (c *Content) Detail(cookie, id string) (*Detail, error) {
	if !digits.MatchString(id) {
		return nil, errors.New("剧集编号无效")
	}
	if v, ok := c.get("detail:" + id); ok {
		return v.(*Detail), nil
	}
	m, err := c.C.CallRetry(cookie, PathAlbum, albumBody(id, nil), nil)
	if err != nil {
		return nil, err
	}
	if int(num(m["code"])) != 0 {
		return nil, errors.New(nz(str(m["message"]), "详情不可用"))
	}
	vd := obj(obj(obj(obj(m["data"])["video_detail_data"])[id])["video_data"])
	if vd == nil {
		return nil, errors.New("详情节点为空")
	}
	d := &Detail{ID: id, Title: str(vd["series_title"]), Cover: str(vd["series_cover"]), Intro: str(vd["series_intro"]),
		Count: int(num(vd["episode_cnt"])), Plays: int64(num(vd["series_play_cnt"])), Actors: []string{}, Episodes: []Episode{}}
	if d.Intro == "" {
		d.Intro = str(obj(obj(m["data"])["album_data"])["intro"])
	}
	for _, s := range arr(vd["secondary_infos"]) {
		if o := obj(s); o != nil && int(num(o["data_type"])) == 4 {
			d.Rating = str(o["content"])
			break
		}
	}
	for _, s := range arr(vd["series_sub_title_list"]) {
		t := str(s)
		if strings.HasPrefix(t, "红果热度值") {
			d.Heat = strings.TrimPrefix(t, "红果热度值")
		}
	}
	d.Tag = str(obj(vd["video_tag_info"])["text"])
	// 响应里没有 is_multi_season / season_index：related_album_id 存在即为同系列多季。
	d.AlbumID = str(vd["related_album_id"])
	d.MultiSeason = d.AlbumID != ""
	d.SeasonIndex = seasonNumberFromTitle(d.Title)
	for _, ce := range arr(vd["celebrities"]) {
		if n := str(obj(ce)["nickname"]); n != "" && len(d.Actors) < 8 {
			d.Actors = append(d.Actors, n)
		}
	}
	for i, e := range arr(vd["video_list"]) {
		eo := obj(e)
		if eo == nil || str(eo["vid"]) == "" {
			continue
		}
		idx := int(num(eo["vid_index"]))
		if idx == 0 {
			idx = i + 1
		}
		d.Episodes = append(d.Episodes, Episode{Index: idx, Title: nz(str(eo["title"]), fmt.Sprintf("第%d集", idx)),
			Vid: str(eo["vid"]), Duration: int(num(eo["duration"])), Cover: nz(str(eo["episode_cover"]), str(eo["cover"]))})
	}
	c.put("detail:"+id, d, 10*time.Minute)
	return d, nil
}

// Season 是同系列里的一季。
type Season struct {
	ID       string `json:"id"`
	Title    string `json:"title"`
	Cover    string `json:"cover"`
	Number   int    `json:"number"`
	Episodes int    `json:"episodes"`
	Current  bool   `json:"current,omitempty"`
}

// Seasons 返回该剧所属系列的全部季（按季号排序）；非多季返回空。
func (c *Content) Seasons(cookie, seriesID string) ([]Season, error) {
	if !digits.MatchString(seriesID) {
		return nil, errors.New("剧集编号无效")
	}
	cur, err := c.Detail(cookie, seriesID)
	if err != nil {
		return nil, err
	}
	if !cur.MultiSeason || cur.AlbumID == "" {
		return nil, nil
	}
	if v, ok := c.get("seasons:" + cur.AlbumID); ok {
		return markCurrentSeason(v.([]Season), seriesID), nil
	}
	m, err := c.C.CallRetry(cookie, "/api/novel/origin/album/get_video_list/v1/", nil,
		sign.Params{{K: "album_id", V: cur.AlbumID}, {K: "count", V: "100"}, {K: "offset", V: "0"}})
	if err != nil {
		return nil, err
	}
	ids := []string{}
	seen := map[string]bool{}
	for _, v := range arr(obj(m["data"])["video_data_list"]) {
		id := str(obj(v)["video_id"])
		if digits.MatchString(id) && !seen[id] {
			seen[id] = true
			ids = append(ids, id)
		}
	}
	if !seen[seriesID] {
		ids = append(ids, seriesID)
	}
	// 各季详情并行取（最多 6 路）：季数多的剧串行要十几秒，季列表迟迟出不来
	details := make([]*Detail, len(ids))
	var wg sync.WaitGroup
	gate := make(chan struct{}, 6)
	for i, id := range ids {
		wg.Add(1)
		go func(i int, id string) {
			defer wg.Done()
			gate <- struct{}{}
			defer func() { <-gate }()
			if d, err := c.Detail(cookie, id); err == nil {
				details[i] = d
			}
		}(i, id)
	}
	wg.Wait()
	var out []Season
	for order, id := range ids {
		d := details[order]
		if d == nil || d.AlbumID == "" || d.AlbumID != cur.AlbumID {
			continue
		}
		n := seasonNumberFromTitle(d.Title)
		if n <= 0 {
			n = order + 1 // 标题没写季号时按合集列表顺序
		}
		out = append(out, Season{ID: id, Title: d.Title, Cover: d.Cover, Number: n, Episodes: d.Count})
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Number < out[j].Number })
	if len(out) <= 1 {
		return nil, nil
	}
	c.put("seasons:"+cur.AlbumID, out, 10*time.Minute)
	return markCurrentSeason(out, seriesID), nil
}

func markCurrentSeason(seasons []Season, seriesID string) []Season {
	out := make([]Season, len(seasons))
	for i, s := range seasons {
		s.Current = s.ID == seriesID
		out[i] = s
	}
	return out
}

// ------------------------------------------------------------ 播放

// Play 取单集各清晰度的播放地址。
// 取流走 internal/hgplay：纯 Go 签名（搬运自 aston314/hongguo-tv，含 Gorgon/Helios/Medusa），
// 注册匿名设备请求 video_model，已实测可取到可播 CDN 直链，不需要 native/模拟器。
// 每次调用使用独立匿名设备；失败重试一次（换新设备）。
// Share anonymous playback models between source discovery and media fetching.
type playModelLoad struct {
	done  chan struct{}
	gears []Gear
	err   error
}

func (c *Content) Play(a *Account, vid string) ([]Gear, error) {
	if !digits.MatchString(vid) {
		return nil, errors.New("视频编号无效")
	}
	return c.cachedPlay(vid, func() ([]Gear, error) { return c.loadPlay(a, vid) })
}
func (c *Content) ForgetPlay(vid string) {
	c.mu.Lock()
	delete(c.cache, "play|"+vid)
	c.mu.Unlock()
}
func (c *Content) cachedPlay(vid string, load func() ([]Gear, error)) ([]Gear, error) {
	key := "play|" + vid
	c.mu.Lock()
	if hit, ok := c.cache[key]; ok && time.Since(hit.at) < hit.ttl {
		c.mu.Unlock()
		return hit.v.([]Gear), nil
	}
	if c.playLoads == nil {
		c.playLoads = make(map[string]*playModelLoad)
	}
	if pending := c.playLoads[vid]; pending != nil {
		c.mu.Unlock()
		<-pending.done
		return pending.gears, pending.err
	}
	pending := &playModelLoad{done: make(chan struct{})}
	c.playLoads[vid] = pending
	c.mu.Unlock()
	gears, err := load()
	c.mu.Lock()
	if err == nil && len(gears) > 0 {
		c.trimCacheLocked()
		c.cache[key] = cacheItem{at: time.Now(), ttl: 60 * time.Second, v: gears}
	}
	pending.gears, pending.err = gears, err
	delete(c.playLoads, vid)
	close(pending.done)
	c.mu.Unlock()
	return gears, err
}

func (c *Content) loadPlay(a *Account, vid string) ([]Gear, error) {
	if !digits.MatchString(vid) {
		return nil, errors.New("视频编号无效")
	}
	var last error
	for _, route := range hgplay.PlaybackRoutes() {
		attempts, timeout := 1, 12*time.Second
		if route == "single" {
			attempts, timeout = 2, 30*time.Second
		}
		for attempt := 0; attempt < attempts; attempt++ {
			ctx, cancel := context.WithTimeout(context.Background(), timeout)
			var envelope map[string]any
			var err error
			if route == "legacy" {
				envelope, err = hgplay.PlayLegacy(ctx, vid)
			} else {
				envelope, err = hgplay.Play(ctx, vid)
			}
			cancel()
			if err != nil {
				last = err
				continue
			}
			if code := playbackCode(envelope); code != 0 {
				last = fmt.Errorf("取播放信息失败（%d）", code)
				continue
			}
			gears, err := parsePlayGears(envelope, vid)
			if err == nil && hasCompatiblePlayGear(gears) {
				return gears, nil
			}
			if err != nil {
				last = err
			} else {
				last = errors.New("没有可用播放源")
			}
		}
	}

	if last == nil {
		last = errors.New("没有可用播放源")
	}
	return nil, last
}

func playbackCode(response map[string]any) int {
	if value, ok := response["Code"]; ok {
		return int(num(value))
	}
	if value, ok := response["code"]; ok {
		return int(num(value))
	}
	return -1
}

func parsePlayGears(response map[string]any, vid string) ([]Gear, error) {
	data := obj(response["data"])
	node := obj(data[vid])
	model := playModel(node["video_model"])
	if len(model) == 0 && node["video_list"] != nil {
		model = node
	}
	if len(model) == 0 {
		model = playModel(data["video_model"])
	}
	if len(model) == 0 && data["video_list"] != nil {
		model = data
	}
	if len(model) == 0 {
		if str(data["fallback_api"]) != "" || str(node["fallback_api"]) != "" {
			return nil, errors.New("播放模型要求 fallback_api 取源")
		}
		return nil, errors.New("播放节点为空")
	}

	var seed []byte
	if fallback := extractPlayFallbackAPI(model["fallback_api"]); fallback != "" {
		inner, keySeed, err := fetchCompatiblePlayFallback(fallback)
		if err != nil {
			if !hasPlayRows(model["video_list"]) {
				return nil, err
			}
		} else {
			merged := make(map[string]any, len(model)+len(inner))
			for key, value := range model {
				merged[key] = value
			}
			for key, value := range inner {
				merged[key] = value
			}
			model, seed = merged, keySeed
		}
	}

	var rows []any
	switch list := model["video_list"].(type) {
	case []any:
		rows = list
	case map[string]any:
		for _, row := range list {
			rows = append(rows, row)
		}
	}
	var out []Gear
	for _, value := range rows {
		vo := obj(value)
		if vo == nil {
			continue
		}
		meta, enc := obj(vo["video_meta"]), obj(vo["encrypt_info"])
		if len(meta) == 0 {
			meta = vo
		}
		definition := str(meta["definition"])
		if definition == "" {
			definition = str(vo["quality_desc"])
		}
		codec := str(meta["codec_type"])
		if codec == "" {
			codec = str(vo["codec_type"])
		}
		width, height := int(num(meta["vwidth"])), int(num(meta["vheight"]))
		if width == 0 {
			width = int(num(vo["width"]))
		}
		if height == 0 {
			height = int(num(vo["height"]))
		}
		if width == 0 {
			width = int(num(vo["vwidth"]))
		}
		if height == 0 {
			height = int(num(vo["vheight"]))
		}
		mainURL := playAddress(vo["main_url"], seed)
		backups := []string{}
		for _, key := range []string{"backup_url", "backup_url_1", "backup_url_2", "backup_url_3", "BackupPlayUrl", "backup_url_list"} {
			backups = appendUniqueURLs(backups, playAddresses(vo[key], seed)...)
		}
		if mainURL != "" {
			filtered := backups[:0]
			for _, address := range backups {
				if address != mainURL {
					filtered = append(filtered, address)
				}
			}
			backups = filtered
		}
		backupURL := ""
		if len(backups) > 0 {
			backupURL = backups[0]
		}
		spadeA := str(enc["spade_a"])
		if spadeA == "" {
			spadeA = str(vo["spade_a"])
		}
		encrypted := enc["encrypt"] == true || vo["encrypt"] == true
		gear := Gear{Definition: definition, Codec: codec, Width: width, Height: height, Size: int64(num(meta["size"])),
			MainURL: mainURL, BackupURL: backupURL, BackupURLs: backups, Encrypted: encrypted, SpadeA: spadeA}
		if gear.MainURL != "" {
			out = append(out, gear)
		}
	}
	if len(out) == 0 {
		if str(model["fallback_api"]) != "" {
			return nil, errors.New("播放模型要求 fallback_api 取源")
		}
		return nil, errors.New("播放模型没有直连视频列表")
	}
	return out, nil
}

func hasPlayRows(value any) bool {
	switch rows := value.(type) {
	case []any:
		return len(rows) > 0
	case map[string]any:
		return len(rows) > 0
	default:
		return false
	}
}

func isByteVC2Codec(codec string) bool {
	c := strings.ToLower(strings.TrimSpace(codec))
	return strings.Contains(c, "bytevc2") || strings.Contains(c, "bvc2")
}

func hasCompatiblePlayGear(gears []Gear) bool {
	for _, g := range gears {
		if g.MainURL != "" && !isByteVC2Codec(g.Codec) {
			return true
		}
	}
	return false
}

func hasByteVC2Rows(value any) bool {
	var rows []any
	switch list := value.(type) {
	case []any:
		rows = list
	case map[string]any:
		for _, r := range list {
			rows = append(rows, r)
		}
	}
	for _, item := range rows {
		vo := obj(item)
		if vo == nil {
			continue
		}
		meta := obj(vo["video_meta"])
		codec := str(meta["codec_type"])
		if codec == "" {
			codec = str(vo["codec_type"])
		}
		if isByteVC2Codec(codec) {
			return true
		}
	}
	return false
}

func onlyByteVC2Rows(value any) bool {
	var rows []any
	switch list := value.(type) {
	case []any:
		rows = list
	case map[string]any:
		for _, r := range list {
			rows = append(rows, r)
		}
	}
	if len(rows) == 0 {
		return false
	}
	for _, item := range rows {
		vo := obj(item)
		if vo == nil {
			continue
		}
		meta := obj(vo["video_meta"])
		codec := str(meta["codec_type"])
		if codec == "" {
			codec = str(vo["codec_type"])
		}
		if !isByteVC2Codec(codec) {
			return false
		}
	}
	return true
}

func extractPlayFallbackAPI(value any) string {
	switch typed := value.(type) {
	case string:
		text := strings.TrimSpace(typed)
		var decoded any
		if json.Unmarshal([]byte(text), &decoded) == nil {
			if nested, ok := decoded.(map[string]any); ok {
				if fb := extractPlayFallbackAPI(nested["fallback_api"]); fb != "" {
					return fb
				}
			} else if list, ok := decoded.([]any); ok && len(list) > 0 {
				return extractPlayFallbackAPI(list[0])
			}
		}
		return text
	case []any:
		if len(typed) > 0 {
			return extractPlayFallbackAPI(typed[0])
		}
	case map[string]any:
		return extractPlayFallbackAPI(typed["fallback_api"])
	}
	return ""
}

func fallbackCodecURL(raw, codec string) string {
	base, query, _ := strings.Cut(raw, "?")
	kept := make([]string, 0)
	for _, part := range strings.Split(query, "&") {
		if part == "" {
			continue
		}
		name, _, _ := strings.Cut(part, "=")
		decoded, _ := url.QueryUnescape(name)
		if decoded != "codec_type" && decoded != "force_codec" {
			kept = append(kept, part)
		}
	}
	if query == "" {
		kept = nil
	}
	return base + "?" + strings.Join(append(kept, codec), "&")
}

func fetchCompatiblePlayFallback(raw string) (map[string]any, []byte, error) {
	var lastInner map[string]any
	var lastSeed []byte
	var lastErr error
	for _, codec := range []string{"codec_type=1", "codec_type=3", "codec_type=0&force_codec=h264"} {
		candidate, cSeed, cErr := fetchPlayFallback(fallbackCodecURL(raw, codec))
		if cErr == nil && hasPlayRows(candidate["video_list"]) && !onlyByteVC2Rows(candidate["video_list"]) {
			return candidate, cSeed, nil
		}
		if cErr != nil {
			lastErr = cErr
		} else if hasPlayRows(candidate["video_list"]) {
			lastInner, lastSeed = candidate, cSeed
		}
	}
	inner, seed, err := fetchPlayFallback(raw)
	if err == nil && hasPlayRows(inner["video_list"]) {
		return inner, seed, nil
	}
	if lastInner != nil {
		return lastInner, lastSeed, nil
	}
	if err != nil {
		return nil, nil, err
	}
	return nil, nil, lastErr
}

func fetchPlayFallback(raw string) (map[string]any, []byte, error) {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || u.Scheme != "https" || u.User != nil || (u.Port() != "" && u.Port() != "443") {
		return nil, nil, errors.New("播放 fallback 地址无效")
	}
	host := strings.ToLower(u.Hostname())
	allowed := false
	for _, suffix := range []string{"fqnovel.com", "snssdk.com"} {
		if host == suffix || strings.HasSuffix(host, "."+suffix) {
			allowed = true
			break
		}
	}
	if !allowed {
		return nil, nil, errors.New("播放 fallback 域名不受支持")
	}
	resp, err := doRequest(http.MethodGet, u.String(), [][2]string{{"User-Agent", ContentUA}, {"Accept", "application/json"}}, nil)
	if err != nil {
		return nil, nil, err
	}
	if resp.Status != http.StatusOK {
		return nil, nil, fmt.Errorf("播放 fallback HTTP %d", resp.Status)
	}
	outer := map[string]any{}
	if err := json.Unmarshal(resp.Body, &outer); err != nil {
		return nil, nil, errors.New("播放 fallback 响应格式异常")
	}
	inner := obj(obj(outer["video_info"])["data"])
	if len(inner) == 0 {
		inner = obj(outer["data"])
	}
	if len(inner) == 0 {
		return nil, nil, errors.New("播放 fallback 缺少视频信息")
	}
	var seed []byte
	if encoded := strings.TrimSpace(str(inner["key_seed"])); encoded != "" {
		seed, err = decodePlayBase64(encoded)
		if err != nil {
			return nil, nil, errors.New("播放 fallback 密钥格式异常")
		}
	}
	return inner, seed, nil
}

func decodePlayBase64(value string) ([]byte, error) {
	value = strings.TrimSpace(value)
	for _, enc := range []*base64.Encoding{base64.StdEncoding, base64.RawStdEncoding, base64.URLEncoding, base64.RawURLEncoding} {
		if decoded, err := enc.DecodeString(value); err == nil {
			return decoded, nil
		}
	}
	return nil, errors.New("invalid base64")
}

func playAddress(value any, seed []byte) string {
	var raw string
	switch typed := value.(type) {
	case string:
		raw = strings.TrimSpace(typed)
	case []any:
		for _, candidate := range typed {
			if text, ok := candidate.(string); ok && strings.TrimSpace(text) != "" {
				raw = strings.TrimSpace(text)
				break
			}
		}
	}
	if strings.HasPrefix(raw, "https://") || strings.HasPrefix(raw, "http://") || raw == "" {
		return raw
	}
	decoded, err := decodePlayBase64(raw)
	if err != nil {
		return raw
	}
	if len(seed) > 0 && len(decoded) >= 5 && decoded[0] == 0xa8 && decoded[2] == 1 && decoded[3] == 0 {
		const constantsHex = "4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58"
		constants, _ := hex.DecodeString(constantsHex)
		first := sha512.Sum512(seed)
		material := append(append([]byte(nil), first[:]...), constants...)
		second := sha512.Sum512(material)
		cipherLen := (len(decoded) - 4) / aes.BlockSize * aes.BlockSize
		if cipherLen > 0 {
			block, err := aes.NewCipher(second[:aes.BlockSize])
			if err == nil {
				plain := make([]byte, cipherLen)
				cipher.NewCBCDecrypter(block, second[aes.BlockSize:2*aes.BlockSize]).CryptBlocks(plain, decoded[4:4+cipherLen])
				end := len(plain)
				if pad := int(plain[end-1]); pad > 0 && pad <= aes.BlockSize && pad <= end {
					end -= pad
				}
				for end > 0 && plain[end-1] == 0 {
					end--
				}
				urlText := strings.TrimSpace(string(bytes.TrimSpace(plain[:end])))
				if strings.HasPrefix(urlText, "https://") || strings.HasPrefix(urlText, "http://") {
					return urlText
				}
			}
		}
	}
	decodedText := strings.TrimSpace(string(decoded))
	if strings.HasPrefix(decodedText, "https://") || strings.HasPrefix(decodedText, "http://") {
		return decodedText
	}
	return raw
}

// playAddresses decodes every mirror in the several response shapes emitted by
// video_model. Keep URL order stable because the first mirror is preferred.
func playAddresses(value any, seed []byte) []string {
	var out []string
	switch typed := value.(type) {
	case string:
		if address := playAddress(typed, seed); address != "" {
			out = appendUniqueURLs(out, address)
		}
	case []any:
		for _, candidate := range typed {
			out = appendUniqueURLs(out, playAddresses(candidate, seed)...)
		}
	case map[string]any:
		keys := make([]string, 0, len(typed))
		for key := range typed {
			keys = append(keys, key)
		}
		sort.Strings(keys)
		for _, key := range keys {
			out = appendUniqueURLs(out, playAddresses(typed[key], seed)...)
		}
	}
	return out
}

func appendUniqueURLs(dst []string, candidates ...string) []string {
	seen := make(map[string]bool, len(dst))
	for _, value := range dst {
		seen[value] = true
	}
	for _, value := range candidates {
		value = strings.TrimSpace(value)
		if value != "" && !seen[value] {
			dst = append(dst, value)
			seen[value] = true
		}
	}
	return dst
}

func playModel(value any) map[string]any {
	if model := obj(value); model != nil {
		return model
	}
	if raw := str(value); raw != "" {
		model := map[string]any{}
		if json.Unmarshal([]byte(raw), &model) == nil {
			return model
		}
	}
	return nil
}

// PickGear 按偏好清晰度挑档；没有则取不超过上限的最高档。
func PickGear(gears []Gear, prefer string, limit int) *Gear {
	if limit <= 0 {
		limit = 1080
	}
	var best *Gear
	for i := range gears {
		g := &gears[i]
		ss := g.ShortSide()
		if ss <= 0 || ss > limit || strings.Contains(strings.ToLower(g.Codec), "bvc2") || strings.Contains(strings.ToLower(g.Codec), "bytevc2") {
			continue
		}
		if prefer != "" && g.Definition == prefer {
			return g
		}
		if best == nil || ss > best.ShortSide() {
			best = g
		}
	}
	return best
}

// ------------------------------------------------------------ 评论（弹幕来源）

func (c *Content) Comments(cookie, id, episodeID string, offset int) (map[string]any, error) {
	q := sign.Params{{K: "book_id", V: id}, {K: "group_id", V: id}}
	kind := "book"
	if episodeID != "" {
		q = append(q, sign.Param{K: "item_id", V: episodeID})
		q = q.Set("group_id", episodeID)
		kind = "item"
	}
	q = append(q, sign.Param{K: "offset", V: strconv.Itoa(offset)}, sign.Param{K: "count", V: "20"}, sign.Param{K: "sort", V: "1"})
	m, err := c.C.Call(cookie, "/reading/ugc/novel_comment/"+kind+"/v1/", nil, q)
	if err != nil {
		return nil, err
	}
	d := obj(m["data"])
	if m["_empty"] != nil || int(num(m["code"])) != 0 || d == nil {
		return nil, errors.New("评论加载未完成")
	}
	return d, nil
}

// ------------------------------------------------------------ 搜索

const webSearchBase = "https://hongguoduanju.com"

func webGet(path string) ([]byte, error) {
	var last error
	for i := 0; i < 2; i++ {
		r, err := doRequest("GET", webSearchBase+path, [][2]string{
			{"User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"},
			{"Referer", webSearchBase + "/"}, {"Accept", "application/json"}, {"Accept-Language", "zh-CN,zh;q=0.9"}}, nil)
		if err != nil {
			last = err
			continue
		}
		if r.Status != 200 {
			last = errors.New("搜索服务暂不可用，请重试")
			continue
		}
		return r.Body, nil
	}
	return nil, last
}

type SearchResult struct {
	Items []Series `json:"items"`
	Total int      `json:"total"`
	More  bool     `json:"more"`
	Page  int      `json:"page"`
}

// Search 优先走 App 搜索接口（签名族），失败回落官网搜索。
func (c *Content) Search(a *Account, query string, page int) (*SearchResult, error) {
	query = strings.TrimSpace(query)
	if query == "" || len([]rune(query)) > 100 {
		return nil, errors.New("请输入剧名，最多100字")
	}
	if page < 1 {
		page = 1
	}
	if page == 1 {
		if r, err := c.appSearch(a, query); err == nil && len(r.Items) > 0 {
			// app 搜索结果偏少时，再并入官网搜索去重补充，提升长尾召回（搜不出来的多是这类）
			if len(r.Items) < 12 {
				if wr, werr := c.webSearch(query, 1); werr == nil && len(wr.Items) > 0 {
					seen := map[string]bool{}
					for _, s := range r.Items {
						seen[s.ID] = true
					}
					for _, s := range wr.Items {
						if !seen[s.ID] {
							seen[s.ID] = true
							r.Items = append(r.Items, s)
						}
					}
					if wr.More {
						r.More = true
					}
				}
			}
			return r, nil
		}
	}
	return c.webSearch(query, page)
}

func (c *Content) appSearch(a *Account, query string) (*SearchResult, error) {
	login := "0"
	if a.U.LoggedIn() {
		login = "1"
	}
	m, err := a.Signed(AppHost, "/reading/bookapi/search/tab/v1/", sign.Params{
		{K: "version_code", V: "73532"}, {K: "version_name", V: "7.3.5.32"}, {K: "manifest_version_code", V: "73532"},
		{K: "update_version_code", V: "73532"}, {K: "pv_player", V: "73532"}, {K: "query", V: query}, {K: "count", V: "0"},
		{K: "tab_type", V: "11"}, {K: "offset", V: "0"}, {K: "bookshelf_search_plan", V: "4"}, {K: "search_source", V: "0"},
		{K: "use_correct", V: "true"}, {K: "user_is_login", V: login}}, nil)
	if err != nil {
		return nil, err
	}
	if int(num(m["code"])) != 0 {
		return nil, errors.New("搜索服务暂不可用")
	}
	for _, t := range arr(m["search_tabs"]) {
		tab := obj(t)
		if int(num(tab["tab_type"])) != 11 {
			continue
		}
		r := &SearchResult{Page: 1, More: false, Total: int(num(tab["expected_num"])), Items: []Series{}}
		seen := map[string]bool{}
		for _, row := range arr(tab["data"]) {
			collectAppCell(obj(row), &r.Items, seen)
		}
		return r, nil
	}
	return nil, errors.New("搜索响应缺少结果")
}

var emRe = regexp.MustCompile(`</?em>`)

func collectAppCell(cell map[string]any, out *[]Series, seen map[string]bool) {
	if cell == nil {
		return
	}
	for _, v := range arr(cell["video_data"]) {
		vo := obj(v)
		if vo == nil {
			continue
		}
		id := nz(str(vo["series_id"]), str(cell["book_id"]))
		title := nz(str(vo["title"]), str(cell["cell_name"]))
		if hl := obj(obj(cell["search_high_light"])["title"]); hl != nil {
			title = nz(str(hl["text"]), title)
		}
		title = strings.TrimSpace(emRe.ReplaceAllString(title, ""))
		if id == "" || title == "" || seen[id] {
			continue
		}
		seen[id] = true
		*out = append(*out, Series{ID: id, Title: title, Cover: nz(str(vo["cover"]), str(vo["cover_url"])),
			Intro: str(vo["video_desc"]), Episodes: int(num(vo["episode_cnt"])), FirstVid: str(vo["vid"])})
	}
	if raw := str(cell["lynx_data"]); raw != "" {
		var nested map[string]any
		if json.Unmarshal([]byte(raw), &nested) == nil {
			for _, n := range arr(nested["cell_data"]) {
				collectAppCell(obj(n), out, seen)
			}
		}
	}
}

func (c *Content) webSearch(query string, page int) (*SearchResult, error) {
	path := "/api/search/result?query=" + url.QueryEscape(query)
	if page > 1 {
		path += "&page=" + strconv.Itoa(page)
	}
	body, err := webGet(path)
	if err != nil {
		return nil, err
	}
	var data map[string]any
	dec := json.NewDecoder(strings.NewReader(string(body)))
	dec.UseNumber()
	if dec.Decode(&data) != nil {
		return nil, errors.New("搜索服务响应异常，请重试")
	}
	r := &SearchResult{Page: page, Total: int(num(data["totalCount"])), More: data["hasMore"] == true, Items: []Series{}}
	seen := map[string]bool{}
	for _, row := range arr(data["searchList"]) {
		if v := obj(row)["video_data"]; v != nil {
			collect(v, &r.Items, seen)
		}
	}
	// 未知词时上游会返回泛推荐：要求标题含关键词才接受
	key := strings.ToLower(strings.ReplaceAll(query, " ", ""))
	hit := false
	for _, s := range r.Items {
		if strings.Contains(strings.ToLower(strings.ReplaceAll(s.Title, " ", "")), key) {
			hit = true
			break
		}
	}
	if !hit {
		r.Items, r.Total, r.More = []Series{}, 0, false
	}
	return r, nil
}

// Suggest 输入联想。
func (c *Content) Suggest(a *Account, query string) []string {
	query = strings.TrimSpace(query)
	out := []string{}
	if query == "" || len([]rune(query)) > 50 {
		return out
	}
	login := "0"
	if a.U.LoggedIn() {
		login = "1"
	}
	m, err := a.Signed(AppHost, "/reading/bookapi/search/suggest/v1/", sign.Params{
		{K: "version_code", V: "72232"}, {K: "version_name", V: "7.2.2.32"}, {K: "manifest_version_code", V: "72232"},
		{K: "update_version_code", V: "72232"}, {K: "pv_player", V: "72232"}, {K: "q", V: query}, {K: "count", V: "0"},
		{K: "tab_type", V: "11"}, {K: "bookshelf_search_plan", V: "4"}, {K: "search_source", V: "0"}, {K: "bookstore_tab", V: "0"},
		{K: "user_is_login", V: login}}, nil)
	if err != nil || int(num(m["code"])) != 0 {
		return out
	}
	seen := map[string]bool{}
	for _, r := range arr(obj(m["data"])["query_result_v2"]) {
		ro := obj(r)
		if ro == nil || int(num(ro["show_type"])) == 2 {
			continue
		}
		if n := strings.TrimSpace(str(ro["name"])); n != "" && !seen[n] && len(out) < 10 {
			seen[n] = true
			out = append(out, n)
		}
	}
	return out
}

// seasonNumberFromTitle 从标题里解析「第N季」的季号；没有季号的（通常是第一季）返回 1。
func seasonNumberFromTitle(title string) int {
	i := strings.Index(title, "第")
	if i < 0 {
		return 1
	}
	rest := []rune(title[i+len("第"):])
	cn := map[rune]int{'一': 1, '二': 2, '三': 3, '四': 4, '五': 5, '六': 6, '七': 7, '八': 8, '九': 9, '十': 10}
	n := 0
	for _, r := range rest {
		if r >= '0' && r <= '9' {
			n = n*10 + int(r-'0')
		} else if v, ok := cn[r]; ok {
			if r == '十' {
				if n == 0 {
					n = 10
				} else {
					n *= 10
				}
			} else {
				n += v
			}
		} else {
			break
		}
	}
	if n <= 0 {
		return 1
	}
	return n
}
