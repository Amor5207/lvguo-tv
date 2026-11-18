package hg

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math/rand"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"lvguo/internal/sign"

	"lvguo/internal/hgplay"
)

// Account 把一个登录态 User 与内容通道 Client 绑在一起，提供账号读写。
type Account struct {
	C *Client
	U *User
	// Dev 是这个登录会话自己的设备身份。账号接口与个性化推荐必须用它：
	// 上游按设备做个性化，全站共用一台设备会让别人的推荐带上你的观看口味。
	Dev   *DeviceStore
	devMu sync.RWMutex
	// Playback 使用 TV 同款独立匿名身份，与账号设备和登录 Cookie 隔离。
	Playback *PlaybackDeviceSession
}

// SetDev 并发安全地替换当前账号会话关联的设备存储。
func (a *Account) SetDev(d *DeviceStore) {
	a.devMu.Lock()
	a.Dev = d
	a.devMu.Unlock()
}

// HasDev 判断是否已设置独立设备存储。
func (a *Account) HasDev() bool {
	a.devMu.RLock()
	has := a.Dev != nil
	a.devMu.RUnlock()
	return has
}

func (a *Account) devices() *DeviceStore {
	a.devMu.RLock()
	d := a.Dev
	a.devMu.RUnlock()
	if d != nil {
		return d
	}
	return a.C.Devices
}

func (a *Account) playbackSession() *PlaybackDeviceSession {
	if a.Playback != nil {
		return a.Playback
	}
	return defaultPlaybackSession()
}

// RefreshPlaybackSession replaces the anonymous registered identity used by
// media playback after CDN and source refreshes have both failed.
func (a *Account) RefreshPlaybackSession() error {
	_, err := a.playbackSession().rotate(nil)
	return err
}

// ErrSessionExpired 表示服务端确认登录已失效。
var ErrSessionExpired = errors.New("登录已失效，请重新扫码登录")

// emptyResponseError preserves the upstream status for local diagnostics while
// keeping the user-facing message stable and free of response contents.
type emptyResponseError struct {
	status int
}

func (e *emptyResponseError) Error() string { return "服务器没有返回数据，请稍后重试" }

func (a *Account) params() (sign.Params, error) {
	dev, err := a.devices().Get()
	if err != nil {
		return nil, err
	}
	p := sign.Params{
		{K: "aid", V: "8662"}, {K: "app_name", V: "novelread"}, {K: "device_platform", V: "android"},
		{K: "device_type", V: "25053RT47C"}, {K: "device_brand", V: "Redmi"}, {K: "channel", V: "update_64"},
		{K: "version_code", V: "71932"}, {K: "version_name", V: versionName}, {K: "manifest_version_code", V: "71932"},
		{K: "update_version_code", V: "71932"}, {K: "pv_player", V: "71932"}, {K: "os", V: "android"},
		{K: "os_version", V: "16"}, {K: "os_api", V: "36"}, {K: "ac", V: "wifi"}, {K: "language", V: "zh"},
		{K: "resolution", V: "1280*2772"}, {K: "dpi", V: "520"}, {K: "host_abi", V: "arm64-v8a"}, {K: "ssmix", V: "a"},
		{K: "dragon_device_type", V: "phone"}, {K: "compliance_status", V: "0"}, {K: "need_personal_recommend", V: "1"},
		{K: "player_so_load", V: "1"}, {K: "is_android_pad_screen", V: "0"},
		{K: "device_id", V: dev.ID}, {K: "iid", V: dev.IID},
	}
	// APK 的 AppLog 公共参数还带 cdid/openudid/clientudid；必须与注册时发出的值一致，缺失或不一致时取流回空包。
	for _, kv := range []sign.Param{{K: "cdid", V: dev.CDID}, {K: "openudid", V: dev.OpenUDID}, {K: "clientudid", V: dev.ClientUDID}} {
		if kv.V != "" {
			p = append(p, kv)
		}
	}
	return p, nil
}

func (a *Account) parse(r *Resp) (map[string]any, error) {
	a.U.Absorb(r.Cookies)
	m, err := r.JSON()
	if err != nil {
		if err == errEmpty {
			return nil, &emptyResponseError{status: r.Status}
		}
		return nil, err
	}
	code := int(num(m["code"]))
	if code == 0 {
		code = int(num(m["Code"]))
	}
	in := obj(m["data"])
	name := str(in["name"])
	if name == "" {
		name = str(in["error_name"])
	}
	if strings.Contains(str(m["message"]), "session_expired") || strings.Contains(name, "session_expired") || code == 100004 {
		return nil, ErrSessionExpired
	}
	return m, nil
}

func (a *Account) headers() [][2]string {
	return [][2]string{{"User-Agent", sign.AppUA}, {"Accept", "application/json; charset=utf-8"}, {"Cookie", a.U.Cookie()}}
}

// get 普通族 GET。
func (a *Account) get(path string, extra sign.Params) (map[string]any, error) {
	q, err := a.params()
	if err != nil {
		return nil, err
	}
	for _, e := range extra {
		q = q.Set(e.K, e.V)
	}
	r, err := doRequest("GET", AppHost+path+"?"+sign.EncodeParams(q), a.headers(), nil)
	if err != nil {
		return nil, err
	}
	return a.parse(r)
}

// post 普通族 POST。
func (a *Account) post(path string, body map[string]any) (map[string]any, error) {
	q, err := a.params()
	if err != nil {
		return nil, err
	}
	raw, _ := json.Marshal(body)
	h := append(a.headers(), [2]string{"x-ss-stub", strings.ToUpper(sign.Hex(sign.MD5(raw)))}, [2]string{"Content-Type", "application/json; charset=UTF-8"})
	r, err := doRequest("POST", AppHost+path+"?"+sign.EncodeParams(q), h, raw)
	if err != nil {
		return nil, err
	}
	return a.parse(r)
}

// Signed 签名族 GET/POST（body 为 nil 即 GET）。
func (a *Account) Signed(host, path string, extra sign.Params, body map[string]any) (map[string]any, error) {
	q, err := a.params()
	if err != nil {
		return nil, err
	}
	for _, e := range extra {
		q = q.Set(e.K, e.V)
	}
	var raw []byte
	if body != nil {
		raw, _ = json.Marshal(body)
	}
	dev, _ := a.devices().Get()
	var version string
	for _, e := range q {
		if e.K == "version_name" {
			version = e.V
		}
	}
	s, err := sign.Sign(host+path, q, raw, dev.ID, version, time.Now().UnixMilli(), rand.Float64)
	if err != nil {
		return nil, err
	}
	// UA 必须与查询里的 version 一致（APK 7.1.9.32）；sign 包的 AppUA 保留与 Java 对齐的基准值。
	for i := range s.Headers {
		if strings.EqualFold(s.Headers[i][0], "User-Agent") {
			ua := strings.Replace(s.Headers[i][1], "/72232 ", "/"+appVersionCode+" ", 1)
			s.Headers[i][1] = strings.Replace(ua, "/71332 ", "/"+appVersionCode+" ", 1) // 取流 POST 用的 VideoUA
		}
	}
	h := append(append([][2]string{}, s.Headers...), [2]string{"Cookie", a.U.Cookie()}, [2]string{"X-Xs-From-Web", "0"})
	method := "GET"
	if raw != nil {
		method = "POST"
	}
	r, err := doRequest(method, s.URL, h, raw)
	if err != nil {
		return nil, err
	}
	return a.parse(r)
}

// PlaybackSigned mirrors the TV app's dedicated playback request shape. It is
// intentionally separate from Signed: playback uses the 7.3.9.32 profile,
// omits ts from the query, and signs the registered device identity in Medusa.
func (a *Account) PlaybackSigned(host, path string, extra sign.Params, body []byte) (map[string]any, error) {
	profile, err := a.playbackSession().get()
	if err != nil {
		return nil, err
	}
	q := append(sign.Params{}, profile.values...)
	q = q.Set("version_code", "73932").Set("version_name", sign.PlaybackVersion).
		Set("manifest_version_code", "73932").Set("update_version_code", "73932").Set("pv_player", "73932")
	for _, e := range extra {
		q = q.Set(e.K, e.V)
	}
	signed, err := profile.signer.SignPlayback(strings.TrimRight(host, "/")+path, q, body, time.Now().UnixMilli(), rand.Float64)
	if err != nil {
		return nil, err
	}
	headers := append([][2]string{}, signed.Headers...)
	headers = append(headers, [2]string{"Accept-Language", "zh-CN,zh;q=0.9"})
	if profile.cookie != "" {
		headers = append(headers, [2]string{"Cookie", profile.cookie})
	}
	method := "GET"
	if body != nil {
		method = "POST"
	}
	r, err := doRequest(method, signed.URL, headers, body)
	if err != nil {
		return nil, err
	}
	if r.Status < 200 || r.Status >= 300 {
		return nil, fmt.Errorf("播放信息 HTTP %d", r.Status)
	}
	m, err := r.JSON()
	if err == errEmpty {
		return nil, &emptyResponseError{status: r.Status}
	}
	return m, err
}

const appVersionCode = "71932"

func ok(m map[string]any, what string) error {
	code := int(num(m["code"]))
	if code == 0 {
		code = int(num(m["Code"]))
	}
	if code != 0 {
		msg := str(m["message"])
		if msg != "" && !strings.EqualFold(msg, "success") {
			if len([]rune(msg)) > 30 {
				msg = string([]rune(msg)[:30])
			}
			msg = "：" + msg
		} else {
			msg = ""
		}
		return fmt.Errorf("%s未完成（%d）%s", what, code, msg)
	}
	return nil
}

// ------------------------------------------------------------ 资料与心跳

type Profile struct {
	UID       string `json:"uid"`
	Name      string `json:"name"`
	Avatar    string `json:"avatar"`
	Cover     string `json:"cover"`
	Signature string `json:"signature"`
	VIP       bool   `json:"vip"`
	WatchMs   int64  `json:"watchMs"`
	Days      int    `json:"activeDays"`
}

func (a *Account) Profile() (*Profile, error) {
	m, err := a.get("/reading/user/info/v1/", nil)
	if err != nil {
		return nil, err
	}
	d := obj(m["data"])
	if d == nil || strings.TrimSpace(str(d["biz_user_id"])) == "" {
		return nil, ErrSessionExpired
	}
	vip := obj(d["vip_info"])
	return &Profile{
		UID: str(d["biz_user_id"]), Name: str(d["user_name"]),
		Avatar: nz(str(d["expand_avatar_url"]), str(d["avatar_url"])), Cover: str(d["profile_cover_url"]),
		Signature: str(d["description"]), VIP: str(vip["is_vip"]) == "1",
		WatchMs: int64(num(d["video_consume_duration"])), Days: int(num(d["active_day"])),
	}, nil
}

// Heartbeat 会话心跳：服务端在需要时回写新的 sid_guard / sessionid。
func (a *Account) Heartbeat() error {
	q, err := a.params()
	if err != nil {
		return err
	}
	h := append(a.headers(), [2]string{"Content-Type", "application/x-www-form-urlencoded"})
	r, err := doRequest("POST", AppHost+"/passport/token/beat/v2/?"+sign.EncodeParams(q), h, []byte("scene=normal"))
	if err != nil {
		return err
	}
	_, err = a.parse(r)
	return err
}

func (a *Account) LogoutRemote() {
	q, err := a.params()
	if err != nil {
		return
	}
	body := []byte("{}")
	h := [][2]string{{"User-Agent", sign.AppUA}, {"Cookie", a.U.Cookie()}, {"Content-Type", "application/json; charset=UTF-8"},
		{"x-ss-stub", strings.ToUpper(sign.Hex(sign.MD5(body)))}}
	_, _ = doRequest("POST", AppHost+"/passport/user/logout/?"+sign.EncodeParams(q), h, body)
}

// ------------------------------------------------------------ 云端条目

// Entry 是历史 / 收藏 / 预约通用的云端条目。
type Entry struct {
	SeriesID     string `json:"seriesId"`
	Title        string `json:"title"`
	Cover        string `json:"cover"`
	Vid          string `json:"vid,omitempty"`
	EpisodeIndex int    `json:"episodeIndex"`
	EpisodeCount int    `json:"episodeCount"`
	PositionMs   int    `json:"positionMs"`
	Time         int64  `json:"time"`
}

var digits = regexp.MustCompile(`^\d+$`)

// History 读取观看历史（手机红果 App「观看历史」同源），按时间倒序。
func (a *Account) History(limit int) ([]Entry, error) {
	var out []Entry
	offset := 0
	for len(out) < limit {
		m, err := a.get("/reading/bookapi/read_history/list/v1/", sign.Params{
			{K: "book_type", V: "2"}, {K: "limit", V: "500"}, {K: "last_min_read_timestamp_ms", V: "0"},
			{K: "offset", V: strconv.Itoa(offset)}, {K: "full_field", V: "false"}, {K: "is_first_load", V: "false"},
			{K: "query_soft_deleted", V: "false"}})
		if err != nil {
			return nil, err
		}
		if err := ok(m, "读取观看历史"); err != nil {
			return nil, err
		}
		blk := obj(m["data"])
		if blk == nil || blk["has_more"] == nil {
			return nil, errors.New("观看历史分页状态不完整，请重试")
		}
		rows := arr(blk["data_list"])
		for _, r := range rows {
			row := obj(r)
			if row == nil || row["is_delete"] == true {
				continue
			}
			sid := str(row["book_id_str"])
			if sid == "" || sid == "null" {
				sid = str(row["book_id"])
			}
			if !digits.MatchString(sid) {
				continue
			}
			e := Entry{SeriesID: sid, Title: str(row["book_name"]), Cover: str(row["thumb_url"]),
				EpisodeIndex: int(num(row["vid_index"])) - 1, PositionMs: int(num(row["current_play_position"])),
				EpisodeCount: int(num(row["episode_cnt"])), Time: int64(num(row["read_timestamp_ms"]))}
			if e.EpisodeIndex < 0 {
				e.EpisodeIndex = 0
			}
			if v := int64(num(row["vid"])); v > 0 {
				e.Vid = str(row["vid"])
			}
			out = append(out, e)
		}
		more := blk["has_more"] == true
		next := int(num(blk["next_offset"]))
		if !more {
			break
		}
		if next <= offset {
			return nil, errors.New("观看历史分页不完整，请重试")
		}
		offset = next
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Time > out[j].Time })
	if len(out) > limit {
		out = out[:limit]
	}
	return out, nil
}

// ProgressList returns the account's recent video progress, including records
// which have not yet appeared in read_history (for example TV playback).
func (a *Account) ProgressList() ([]Entry, error) {
	m, err := a.get("/reading/bookapi/read_progress/list/v1/", sign.Params{{K: "book_types", V: "2"}})
	if err != nil {
		return nil, err
	}
	if err := ok(m, "读取播放进度"); err != nil {
		return nil, err
	}
	rows := arr(m["video_data"])
	out := make([]Entry, 0, len(rows))
	for _, v := range rows {
		r := obj(v)
		if r == nil {
			continue
		}
		id, vid := str(r["series_id"]), str(r["vid"])
		at := int64(num(r["last_watch_time"]))
		if at > 0 && at < 100000000000 {
			at *= 1000
		}
		pos := int(num(r["current_play_time"]))
		if !digits.MatchString(id) || !digits.MatchString(vid) || at <= 0 || pos <= 0 {
			continue
		}
		out = append(out, Entry{SeriesID: id, Vid: vid, EpisodeIndex: max(0, int(num(r["vid_index"]))-1), PositionMs: pos, EpisodeCount: int(num(r["episode_count"])), Time: at})
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Time > out[j].Time })
	return out, nil
}

// LatestWatchTimes queries both history and playback progress for the specific
// series being imported, including entries outside the visible history page.
func (a *Account) LatestWatchTimes(ids []string) (map[string]int64, error) {
	out := make(map[string]int64, len(ids))
	for from := 0; from < len(ids); from += 30 {
		batch := ids[from:min(from+30, len(ids))]
		offset := 0
		for {
			m, err := a.get("/reading/bookapi/read_history/list/v1/", sign.Params{{K: "book_type", V: "2"}, {K: "limit", V: "0"}, {K: "last_min_read_timestamp_ms", V: "0"}, {K: "offset", V: strconv.Itoa(offset)}, {K: "full_field", V: "false"}, {K: "is_first_load", V: "false"}, {K: "query_soft_deleted", V: "false"}, {K: "book_ids", V: strings.Join(batch, ",")}})
			if err != nil {
				return nil, err
			}
			if err = ok(m, "读取观看历史"); err != nil {
				return nil, err
			}
			data := obj(m["data"])
			if data == nil || data["has_more"] == nil {
				return nil, errors.New("观看历史响应不完整")
			}
			for _, v := range arr(data["data_list"]) {
				r := obj(v)
				if r == nil || r["is_delete"] == true {
					continue
				}
				id := str(r["book_id_str"])
				if !digits.MatchString(id) {
					id = str(r["book_id"])
				}
				t := int64(num(r["read_timestamp_ms"]))
				if t > out[id] {
					out[id] = t
				}
			}
			if data["has_more"] != true {
				break
			}
			next := int(num(data["next_offset"]))
			if next <= offset {
				return nil, errors.New("观看历史分页不完整")
			}
			offset = next
		}
		bookIDs := make([]any, 0, len(batch))
		for _, id := range batch {
			if n, err := strconv.ParseInt(id, 10, 64); err == nil {
				bookIDs = append(bookIDs, n)
			}
		}
		if len(bookIDs) == 0 {
			continue
		}
		m, err := a.post("/reading/bookapi/read_progress/get/v1/", map[string]any{"book_ids": map[string]any{"2": bookIDs}})
		if err != nil {
			return nil, err
		}
		if err = ok(m, "读取播放进度"); err != nil {
			return nil, err
		}
		for _, v := range arr(m["video_data"]) {
			r := obj(v)
			if r == nil {
				continue
			}
			id := str(r["series_id"])
			t := int64(num(r["last_watch_time"]))
			if t > 0 && t < 100000000000 {
				t *= 1000
			}
			if t > out[id] {
				out[id] = t
			}
		}
	}
	return out, nil
}

func (a *Account) Favorites() ([]Entry, error) {
	m, err := a.get("/reading/bookapi/bookshelf/video/info/v1/", sign.Params{{K: "offset", V: "0"}, {K: "count", V: "0"}})
	if err != nil {
		return nil, err
	}
	if err := ok(m, "读取收藏"); err != nil {
		return nil, err
	}
	var out []Entry
	for _, r := range arr(obj(m["data"])["video_shelf_info"]) {
		row := obj(r)
		if row == nil {
			continue
		}
		v := obj(row["video_data"])
		if v == nil {
			v = row
		}
		sid := nz(str(v["series_id"]), nz(str(row["series_id"]), str(row["book_id"])))
		if !digits.MatchString(sid) {
			continue
		}
		t := int64(num(row["collect_time"]))
		if t < 10_000_000_000 {
			t *= 1000
		}
		e := Entry{SeriesID: sid, Title: nz(str(row["series_title"]), str(v["title"])),
			Cover:        nz(str(row["series_cover_url"]), nz(str(v["cover_url"]), nz(str(v["cover"]), str(v["thumb_url"])))),
			EpisodeCount: int(num(v["episode_cnt"])), Time: t, EpisodeIndex: int(num(v["vid_index"])) - 1}
		if e.EpisodeIndex < 0 {
			e.EpisodeIndex = 0
		}
		if num(v["vid"]) > 0 {
			e.Vid = str(v["vid"])
		}
		out = append(out, e)
	}
	return out, nil
}

// SetFavorite 加入 / 移出收藏（签名族），并回读确认。
func (a *Account) SetFavorite(seriesID string, add bool) error {
	if !digits.MatchString(seriesID) {
		return errors.New("剧集编号无效")
	}
	ts := time.Now().UnixMilli()
	op := 1
	if add {
		op = 0
	}
	body := map[string]any{
		"book_id_list": []string{seriesID}, "is_cancelled": false, "modify_time": []int64{ts},
		"update_bookshelf_video_list": []map[string]any{{"book_id": seriesID, "book_type": 2, "group_name": "",
			"modify_time": ts, "video_shelf_operate_type": op}},
	}
	m, err := a.Signed(AppHost, "/reading/bookapi/bookshelf/video/update/v1/", nil, body)
	if err != nil {
		return err
	}
	what := "加入收藏"
	if !add {
		what = "取消收藏"
	}
	if err := ok(m, what); err != nil {
		return err
	}
	for i := 0; i < 3; i++ {
		if i > 0 {
			time.Sleep(time.Second)
		}
		fav, err := a.Favorites()
		if err != nil {
			return err
		}
		found := false
		for _, f := range fav {
			if f.SeriesID == seriesID {
				found = true
			}
		}
		if found == add {
			return nil
		}
	}
	return errors.New("云端收藏状态未确认，请重试")
}

// ProgressRow 是一条本地播放记录（一部剧当前看到的集与位置）。
type ProgressRow struct {
	SeriesID, Vid string
	Index, Pos    int
	TS            int64 // 观看时间（毫秒）
	Watched       int64 // 累计观看时长（毫秒）
}

// UploadProgressBatch 一次 read_progress/upload 上报多部剧的进度（官方脏队列的批量写法）。
func (a *Account) UploadProgressBatch(rows []ProgressRow) error {
	var books []any
	for _, r := range rows {
		if !digits.MatchString(r.SeriesID) || !digits.MatchString(r.Vid) {
			continue
		}
		books = append(books, map[string]any{
			"book_id": r.SeriesID, "book_type": 2, "item_id": r.Vid, "read_timestamp_ms": r.TS, "current_play_time": max(0, r.Pos),
			"player_cumulative_total_duration": 0, "vid_index": 0, "channel_id": 0, "cur_channel_id": 0,
			"page_index": 0, "page_progress_rate": 0.0, "paragraph_offset": 0, "progress_type": 0, "tone_id": 0,
			"check_timestamp": false, "is_listen_mode": false, "is_local_book": false, "listen_and_read": false,
		})
	}
	if len(books) == 0 {
		return nil
	}
	m, err := a.post("/reading/bookapi/read_progress/upload/v1/", map[string]any{"books": books})
	if err != nil {
		return err
	}
	return ok(m, "同步播放进度")
}

// ReportHistoryBatch 一次 read_history/update 上报多条观看历史。
func (a *Account) ReportHistoryBatch(rows []ProgressRow) error {
	var list []any
	for _, r := range rows {
		sid, e1 := strconv.ParseInt(r.SeriesID, 10, 64)
		v, e2 := strconv.ParseInt(r.Vid, 10, 64)
		if e1 != nil || e2 != nil {
			continue
		}
		list = append(list, map[string]any{"book_id": sid, "book_type": 2, "is_delete": false, "read_timestamp_ms": r.TS,
			"update_timestamp_ms": r.TS, "recent_reads": 0, "tone_id": 0, "current_play_position": max(0, r.Pos),
			"player_accumulate_total_time": historyWatched(r), "vid_index": max(0, r.Index) + 1, "vid": v, "episode_cnt": 0, "duration": 0,
			"series_play_cnt": 0, "retain_video_play_time": 0, "digged_count": 0, "user_digg": false, "is_listen_mode": false,
			"is_multi_season": 0, "season_index": 0, "chapter_index": 0, "is_interactive_game": false,
			"origin_novel_book_id": 0, "meet_guide_comment_tag": false, "user_playlet_comment_flag": false, "use_soft_delete": false})
	}
	if len(list) == 0 {
		return nil
	}
	m, err := a.historyUpdate(map[string]any{"update_datas": list})
	if err != nil {
		return err
	}
	if err := ok(m, "上报观看历史"); err != nil {
		return err
	}
	if fails := arr(obj(m["data"])["update_fail_datas"]); len(fails) > 0 {
		return errors.New("观看历史被服务端拒绝")
	}
	return nil
}

// historyWatched 是随观看历史上报的累计观看时长：有真实累计用累计；
// 旧记录没有累计值时用当前集的播放位置兜底（至少看了这么久），不报 0。
func historyWatched(r ProgressRow) int64 {
	if r.Watched > 0 {
		return r.Watched
	}
	return int64(max(0, r.Pos))
}

// historyUpdate 写 read_history/update，必须用完整签名（Gorgon / Helios / Medusa，同播放链路）。
// 旧的手写签名和不带签名的请求，服务端同样回 code 0，但写入会被静默丢弃——
// 2026-11-11 用真实账号逐项对照确认：同一条记录，旧签名写不进，完整签名立即出现在观看历史里。
func (a *Account) historyUpdate(body map[string]any) (map[string]any, error) {
	dev, err := a.devices().Get()
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	m, err := hgplay.AccountPost(ctx, AppHost, "/reading/bookapi/read_history/update/v1/",
		hgplay.AccountDevice{DeviceID: dev.ID, InstallID: dev.IID, CDID: dev.CDID, OpenUDID: dev.OpenUDID}, a.U.Cookie(), body)
	if err != nil {
		return nil, err
	}
	code := int(num(m["code"]))
	if strings.Contains(str(m["message"]), "session_expired") || code == 100004 {
		return nil, ErrSessionExpired
	}
	return m, nil
}

// DeleteHistory 删除一条观看历史。用普通删除而不是软删除：
// 软删除会在服务端留下撤不掉的删除标记，之后再看这部剧也回不到观看历史里（实测）。
func (a *Account) DeleteHistory(seriesID string) error {
	sid, _ := strconv.ParseInt(seriesID, 10, 64)
	row := map[string]any{"book_id": sid, "book_type": 2, "is_delete": true, "use_soft_delete": false,
		"update_timestamp_ms": time.Now().UnixMilli(), "read_timestamp_ms": time.Now().UnixMilli()}
	m, err := a.historyUpdate(map[string]any{"update_datas": []any{row}})
	if err != nil {
		return err
	}
	return ok(m, "删除观看历史")
}

// ApplyProgress 用「进度库」(read_progress/get，手机 App 记录看到第几集第几秒的地方)
// 覆盖列表里的集数与位置——观看历史列表里的 vid_index 不一定是最新的，TV 端同样这样合并。
func (a *Account) ApplyProgress(list []Entry) {
	var ids []any
	seen := map[string]bool{}
	for _, e := range list {
		if digits.MatchString(e.SeriesID) && !seen[e.SeriesID] {
			seen[e.SeriesID] = true
			if n, err := strconv.ParseInt(e.SeriesID, 10, 64); err == nil {
				ids = append(ids, n)
			}
		}
	}
	for from := 0; from < len(ids); from += 50 {
		to := from + 50
		if to > len(ids) {
			to = len(ids)
		}
		m, err := a.post("/reading/bookapi/read_progress/get/v1/", map[string]any{"book_ids": map[string]any{"2": ids[from:to]}})
		if err != nil || ok(m, "读取播放进度") != nil {
			return
		}
		by := map[string]map[string]any{}
		for _, r := range arr(m["video_data"]) {
			if row := obj(r); row != nil {
				by[str(row["series_id"])] = row
			}
		}
		for i := range list {
			row := by[list[i].SeriesID]
			if row == nil {
				continue
			}
			idx := int(num(row["vid_index"])) - 1
			if idx < 0 {
				idx = 0
			}
			// Progress has its own watch timestamp; the history list may lag behind it.
			watchedAt := int64(num(row["last_watch_time"]))
			if watchedAt > 0 && watchedAt < 100000000000 {
				watchedAt *= 1000
			}
			// A delayed progress response must not replace a newer history entry.
			if watchedAt > 0 && list[i].Time > 0 && watchedAt+999 < list[i].Time {
				continue
			}
			if watchedAt > list[i].Time {
				list[i].Time = watchedAt
			}
			list[i].EpisodeIndex = idx
			list[i].PositionMs = int(num(row["current_play_time"]))
			if c := int(num(row["episode_count"])); c > 0 {
				list[i].EpisodeCount = c
			}
			if v := str(row["vid"]); v != "" && v != "0" {
				list[i].Vid = v
			}
		}
	}
}

// ------------------------------------------------------------ 预约（追更）

// SubscribeHost 是预约接口所在的域名（签名族）。
const SubscribeHost = "https://reading.snssdk.com"

// Reservation 是一条预约：已上线的可以直接看，待上线的会显示上线时间。
type Reservation struct {
	SeriesID     string `json:"seriesId"`
	Title        string `json:"title"`
	Cover        string `json:"cover"`
	Online       bool   `json:"online"`
	Desc         string `json:"desc,omitempty"`
	EpisodeCount int    `json:"episodeCount"`
	PublishText  string `json:"publishText,omitempty"`
	Time         int64  `json:"time,omitempty"`
}

// Reservations 读取已上线（online=true）或待上线（false）的预约列表。
func (a *Account) Reservations(online bool) ([]Reservation, error) {
	q := sign.Params{{K: "offset", V: "0"}, {K: "limit", V: "50"}, {K: "is_online", V: strconv.FormatBool(online)},
		{K: "tab_type", V: "13"}, {K: "session_id", V: ""}, {K: "subscribe_order_type", V: "0"},
		{K: "swipe_type", V: "0"}, {K: "need_calendar_schema", V: "false"}, {K: "subscribe_offset", V: "0"}}
	m, err := a.Signed(SubscribeHost, "/reading/user/subscribe/list/v1/", q, nil)
	if err != nil {
		return nil, err
	}
	if err := ok(m, "读取预约"); err != nil {
		return nil, err
	}
	var out []Reservation
	for _, r := range arr(obj(m["data"])["subscribe_items"]) {
		row := obj(r)
		if row == nil {
			continue
		}
		sd := obj(row["subscribe_data"])
		if sd == nil {
			sd = map[string]any{}
		}
		id := nz(str(row["item_id"]), str(sd["series_id"]))
		if !digits.MatchString(id) {
			continue
		}
		on := online
		if b, isBool := row["is_online"].(bool); isBool {
			on = b
		}
		t := int64(num(row["expected_publish_time"]))
		if t == 0 {
			t = int64(num(row["schedule_publish_time"]))
		}
		if t > 0 && t < 10_000_000_000 {
			t *= 1000
		}
		out = append(out, Reservation{SeriesID: id, Title: nz(str(sd["title"]), nz(str(row["name"]), str(row["title"]))),
			Cover: nz(str(sd["cover"]), str(row["cover"])), Online: on, Desc: nz(str(sd["video_desc"]), str(row["item_desc"])),
			EpisodeCount: int(num(sd["episode_cnt"])), PublishText: publishLabel(t, str(row["expected_rec_text"])), Time: t})
	}
	return out, nil
}

var publishZone = time.FixedZone("CST", 8*3600)

// publishLabel 给出待上线剧的上线说明：有预计时间就写时间；没有时间时，官方文案带具体日期才用；
// 「敬请期待」这类空话不显示（返回空串，界面写「待上线」）。
func publishLabel(ms int64, text string) string {
	if ms > 0 {
		t := time.UnixMilli(ms).In(publishZone)
		s := strconv.Itoa(int(t.Month())) + "月" + strconv.Itoa(t.Day()) + "日"
		if t.Hour() != 0 || t.Minute() != 0 {
			s += " " + t.Format("15:04")
		}
		return s + " 上线"
	}
	text = strings.TrimSpace(text)
	if strings.ContainsAny(text, "0123456789") {
		return text
	}
	return ""
}

// SetReservation 预约（add=true）或取消预约。
func (a *Account) SetReservation(seriesID string, add bool) error {
	if !digits.MatchString(seriesID) {
		return errors.New("剧集编号无效")
	}
	op, what := 2, "取消预约"
	if add {
		op, what = 1, "预约"
	}
	m, err := a.Signed(SubscribeHost, "/reading/bookapi/search/uncover_subscribe/v1/", nil,
		map[string]any{"item_id": seriesID, "item_type": 1, "op_type": op, "item_id_str": seriesID})
	if err != nil {
		return err
	}
	return ok(m, what)
}
