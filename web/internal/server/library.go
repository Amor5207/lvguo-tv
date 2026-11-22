package server

// 本地播放记录库：和红果 App 一样「本地是真相源 + 脏标记 + 后台批量上传」。
//
// /api/progress 只写这里（标脏、落盘），不直接调用上游。后台上传器每 2 分钟一轮，
// 暂停 / 离开页面后 5 秒再补一轮，把一个会话的所有脏记录合成一次 read_progress/upload
// 和一次 read_history/update。失败的留在本地下轮重试，服务重启也不丢。
// 以前每 5 秒就直接写两三次上游，账号因此被风控，取流接口全部返回空包。

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"log"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"

	"lvguo/internal/lvguo"
)

const (
	uploadInterval = 2 * time.Minute
	uploadSoon     = 15 * time.Second // 上游对观看历史写入有频率限制（过快会被暂时封禁），本地记录即时可见，不必急着传
	uploadBatch    = 20
	historyTTL     = 60 * time.Second
)

// libRow 是一部剧在本地的最新播放记录。RateDone / HistDone 记录当前快照的两个上游写入是否已被受理。
type libRow struct {
	SeriesID string `json:"id"`
	Vid      string `json:"vid"`
	Index    int    `json:"index"`
	Pos      int    `json:"pos"`
	TS       int64  `json:"ts"`
	Watched  int64  `json:"watched,omitempty"` // 累计观看时长（毫秒），随观看历史上报
	Title    string `json:"title,omitempty"`
	Cover    string `json:"cover,omitempty"`
	Count    int    `json:"count,omitempty"`
	Dirty    bool   `json:"dirty"`
	RateDone bool   `json:"rateDone,omitempty"`
	HistDone bool   `json:"histDone,omitempty"`
	// HistV 是观看历史上报方式的版本。旧版（<2）用的签名会被服务端静默丢弃，
	// 当时记下的「已上报」并不成立，加载时一律重新上报一次。
	HistV int `json:"histV,omitempty"`
	// Seen 表示这条记录上传后在云端历史里出现过。只有出现过、之后又消失的才算「在别处被删了」；
	// 从没出现过的（云端生效有延迟，或云端不收这部剧的记录）以本地为准继续显示。
	Seen bool `json:"seen,omitempty"`
	// New snapshots remain visible until cloud confirms them or a newer cloud
	// record supersedes them, matching the TV's pending-progress behavior.
	Awaiting bool `json:"awaiting,omitempty"`
	Deleted  bool `json:"deleted,omitempty"`
}

const histVersion = 2

type library struct {
	path string
	mu   sync.Mutex
	rows map[string]*libRow

	upMu  sync.Mutex // 同一会话的上传串行
	timer *time.Timer

	histMu   sync.Mutex
	histAt   time.Time
	histList []lvguo.Entry
}

func (s *Server) libraryPath(id string) string {
	h := sha256.Sum256([]byte(id))
	return filepath.Join(s.cfg.DataDir, "library", hex.EncodeToString(h[:8])+".json")
}

func (s *Server) guestLibrary(code string) *library {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.guestLibraries == nil {
		s.guestLibraries = make(map[string]*library)
	}
	if l := s.guestLibraries[code]; l != nil {
		return l
	}
	h := sha256.Sum256([]byte(code))
	l := openLibrary(filepath.Join(s.cfg.DataDir, "guest-library", hex.EncodeToString(h[:8])+".json"))
	s.guestLibraries[code] = l
	return l
}

func (l *library) setGuestMetadata(id, title, cover string, count int) error {
	l.mu.Lock()
	defer l.mu.Unlock()
	if r := l.rows[id]; r != nil {
		if title != "" {
			r.Title = title
		}
		if cover != "" {
			r.Cover = cover
		}
		if count > 0 {
			r.Count = count
		}
		return l.saveLocked()
	}
	return errors.New("观看记录不存在")
}

func (s *Server) guestHistory(code, cookie string, limit int) []lvguo.Entry {
	l := s.guestLibrary(code)
	l.mu.Lock()
	rows := make([]libRow, 0, len(l.rows))
	for _, r := range l.rows {
		if !r.Deleted {
			rows = append(rows, *r)
		}
	}
	l.mu.Unlock()
	var missing []string
	for _, r := range rows {
		if r.Title == "" {
			missing = append(missing, r.SeriesID)
		}
	}
	summaries := s.content.HistorySummaries(cookie, missing)
	out := make([]lvguo.Entry, 0, len(rows))
	for _, r := range rows {
		e := lvguo.Entry{SeriesID: r.SeriesID, Vid: r.Vid, EpisodeIndex: r.Index, PositionMs: r.Pos, Time: r.TS, Title: r.Title, Cover: r.Cover, EpisodeCount: r.Count}
		if e.Title == "" {
			if d, ok := summaries[r.SeriesID]; ok {
				e.Title, e.Cover, e.EpisodeCount = d.Title, d.Cover, d.EpisodeCount
			}
		}
		out = append(out, e)
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Time > out[j].Time })
	if len(out) > limit {
		out = out[:limit]
	}
	return out
}

func openLibrary(path string) *library {
	l := &library{path: path, rows: map[string]*libRow{}}
	if b, err := os.ReadFile(path); err == nil {
		var rows []*libRow
		if json.Unmarshal(b, &rows) == nil {
			for _, r := range rows {
				if r != nil && r.SeriesID != "" {
					if r.HistV < histVersion && r.Vid != "" {
						r.HistDone, r.Dirty = false, true
					}
					l.rows[r.SeriesID] = r
				}
			}
		}
	}
	return l
}

// lib 取会话的本地库（懒加载）。
func (s *Server) lib(ss *sess) *library {
	s.mu.Lock()
	defer s.mu.Unlock()
	if ss.lib == nil {
		ss.lib = openLibrary(s.libraryPath(ss.id))
	}
	return ss.lib
}

func (l *library) saveLocked() error {
	rows := make([]*libRow, 0, len(l.rows))
	for _, r := range l.rows {
		rows = append(rows, r)
	}
	sort.Slice(rows, func(i, j int) bool { return rows[i].TS > rows[j].TS })
	if len(rows) > 500 { // 只留最近 500 部；未上传的不丢
		keep := rows[:500]
		for _, r := range rows[500:] {
			if r.Dirty || r.Deleted || (r.Awaiting && !r.Seen) {
				keep = append(keep, r)
			} else {
				delete(l.rows, r.SeriesID)
			}
		}
		rows = keep
	}
	b, err := json.Marshal(rows)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(l.path), 0o755); err != nil {
		return err
	}
	tmp := l.path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, l.path)
}

// put 记下一次播放位置。位置或集数变了才标脏。
func (l *library) put(id, vid string, index, pos int) {
	l.putAt(id, vid, index, pos, time.Now().UnixMilli(), false)
}

// Imported guest progress keeps its original watch time and cannot replace a
// newer local snapshot from the authenticated account.
func (l *library) putAt(id, vid string, index, pos int, at int64, imported bool) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	r := l.rows[id]
	if r == nil {
		r = &libRow{SeriesID: id}
		l.rows[id] = r
	}
	if imported && r.TS >= at {
		return false
	}
	if !imported && r.Vid == vid && r.Index == index && r.Pos == pos && r.TS > 0 {
		return false // 位置没变：不重复标脏，避免已上传的快照再传一次
	}
	now := at
	// 前端播放时每 15 秒上报一次：间隔在 20 秒内视为连续观看，计入累计时长。
	// 观看历史要带真实的累计时长——手机 App 的记录都有；一直报 0 的记录服务端受理后不会出现在历史里。
	if d := now - r.TS; r.TS > 0 && d > 0 && d <= 20_000 {
		r.Watched += d
	}
	r.Vid, r.Index, r.Pos, r.TS = vid, index, pos, now
	r.Dirty, r.RateDone, r.HistDone, r.Seen = true, false, false, false
	r.Awaiting = true
	r.Deleted = false
	l.saveLocked()
	return true
}

// forget 删除历史时一并丢掉本地记录，避免被上传器再写回去。
func (l *library) forget(id string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	// Keep a local deletion marker so the separate progress list cannot
	// immediately resurrect a record deleted in this browser session.
	l.rows[id] = &libRow{SeriesID: id, TS: time.Now().UnixMilli(), Seen: true, Deleted: true, HistV: histVersion}
	l.saveLocked()
	l.invalidateHistory()
}

func (l *library) dirty() []libRow {
	l.mu.Lock()
	defer l.mu.Unlock()
	var out []libRow
	for _, r := range l.rows {
		if r.Dirty {
			out = append(out, *r)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].TS > out[j].TS })
	return out
}

// mark 标记某个快照的一个上游写入已被受理；快照之后又有新位置就不动（下一轮重报）。
func (l *library) mark(rows []libRow, rate bool) {
	l.mu.Lock()
	defer l.mu.Unlock()
	for _, snap := range rows {
		r := l.rows[snap.SeriesID]
		if r == nil || r.TS != snap.TS {
			continue
		}
		if rate {
			r.RateDone = true
		} else {
			r.HistDone, r.HistV = true, histVersion
		}
		if r.RateDone && r.HistDone {
			r.Dirty = false
		}
	}
	l.saveLocked()
}

func (l *library) invalidateHistory() {
	l.histMu.Lock()
	l.histAt = time.Time{}
	l.histMu.Unlock()
}

func chunks(rows []libRow) [][]libRow {
	var out [][]libRow
	for from := 0; from < len(rows); from += uploadBatch {
		to := min(len(rows), from+uploadBatch)
		out = append(out, rows[from:to])
	}
	return out
}

func toProgress(rows []libRow) []lvguo.ProgressRow {
	out := make([]lvguo.ProgressRow, len(rows))
	for i, r := range rows {
		out[i] = lvguo.ProgressRow{SeriesID: r.SeriesID, Vid: r.Vid, Index: r.Index, Pos: r.Pos, TS: r.TS, Watched: r.Watched}
	}
	return out
}

// upload 把一个会话的脏记录批量上报。返回登录失效错误时调用方负责让会话过期。
func (s *Server) upload(ss *sess) error {
	if !ss.acct.U.LoggedIn() || s.dev {
		return nil
	}
	l := s.lib(ss)
	l.upMu.Lock()
	defer l.upMu.Unlock()
	rows := l.dirty()
	if len(rows) == 0 {
		return nil
	}
	var rate, hist []libRow
	for _, r := range rows {
		if !r.RateDone {
			rate = append(rate, r)
		}
		if !r.HistDone {
			hist = append(hist, r)
		}
	}
	err := uploadPending(hist, rate, ss.acct.ReportHistoryBatch, ss.acct.UploadProgressBatch, l.mark)
	l.invalidateHistory()
	return err
}

// Attempt both independent endpoints; retain failed snapshots for retry.
func uploadPending(hist, rate []libRow, reportHistory, uploadProgress func([]lvguo.ProgressRow) error, mark func([]libRow, bool)) error {
	var firstErr error
	for _, c := range chunks(hist) {
		if err := reportHistory(toProgress(c)); err != nil {
			if errors.Is(err, lvguo.ErrSessionExpired) {
				return err
			}
			firstErr = err
			break
		}
		mark(c, false)
	}
	for _, c := range chunks(rate) {
		if err := uploadProgress(toProgress(c)); err != nil {
			if errors.Is(err, lvguo.ErrSessionExpired) {
				return err
			}
			if firstErr == nil {
				firstErr = err
			}
			break
		}
		mark(c, true)
	}
	return firstErr
}

func (s *Server) uploadAndReport(ss *sess) {
	if err := s.upload(ss); err != nil {
		if errors.Is(err, lvguo.ErrSessionExpired) {
			s.expire(ss)
			return
		}
		log.Printf("播放记录上传未完成（下轮重试）: %v", err)
	}
}

// uploadSoon 暂停 / 离开页面后预约一轮上传；连续多次触发只跑一轮。
func (s *Server) scheduleUpload(ss *sess) {
	l := s.lib(ss)
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.timer != nil {
		return
	}
	l.timer = time.AfterFunc(uploadSoon, func() {
		l.mu.Lock()
		l.timer = nil
		l.mu.Unlock()
		s.uploadAndReport(ss)
	})
}

// uploadLoop 是后台兜底：每 2 分钟把所有会话的脏记录各上报一次。没有脏记录的会话不发请求。
func (s *Server) uploadLoop() {
	t := time.NewTicker(uploadInterval)
	defer t.Stop()
	for range t.C {
		s.mu.Lock()
		var list []*sess
		for _, ss := range s.sessions {
			if ss.lib != nil || fileExists(s.libraryPath(ss.id)) {
				list = append(list, ss)
			}
		}
		s.mu.Unlock()
		for _, ss := range list {
			s.uploadAndReport(ss)
		}
	}
}

func fileExists(p string) bool {
	_, err := os.Stat(p)
	return err == nil
}

// historySnapshot coalesces simultaneous refreshes. Explicit page visits bypass the
// short cache; callers waiting on that same refresh share its result.
func (l *library) historySnapshot(fresh bool, load func() ([]lvguo.Entry, error)) ([]lvguo.Entry, error) {
	requested := time.Now()
	l.histMu.Lock()
	defer l.histMu.Unlock()
	if l.histList == nil || time.Since(l.histAt) >= historyTTL || (fresh && l.histAt.Before(requested)) {
		h, err := load()
		if err != nil {
			return nil, err
		}
		l.histList = append([]lvguo.Entry{}, h...)
		l.histAt = time.Now()
	}
	return append([]lvguo.Entry{}, l.histList...), nil
}

// cloudHistory merges fresh cloud progress with newer, pending local playback.
func (s *Server) cloudHistory(ss *sess, limit int, fresh ...bool) ([]lvguo.Entry, error) {
	l := s.lib(ss)
	list, err := l.historySnapshot(len(fresh) > 0 && fresh[0], func() ([]lvguo.Entry, error) {
		var history, progress []lvguo.Entry
		var historyErr, progressErr error
		var wg sync.WaitGroup
		wg.Add(2)
		go func() { defer wg.Done(); history, historyErr = ss.acct.History(300) }()
		go func() { defer wg.Done(); progress, progressErr = ss.acct.ProgressList() }()
		wg.Wait()
		if historyErr != nil {
			return nil, historyErr
		}
		if progressErr != nil {
			return history, nil
		}
		// The upstream soft-delete query synthesizes a fresh deletion timestamp
		// for missing history rows, even when valid progress exists. It cannot
		// distinguish an actual deletion from a TV-only progress record.
		merged := mergeCloudHistory(history, progress)
		var missing []string
		for _, e := range merged {
			if e.Title == "" {
				missing = append(missing, e.SeriesID)
			}
		}
		summaries := s.content.HistorySummaries(s.cookie(ss), missing)
		out := merged[:0]
		for _, e := range merged {
			if e.Title == "" {
				d, ok := summaries[e.SeriesID]
				if !ok {
					continue
				}
				e.Title, e.Cover = d.Title, d.Cover
				if e.EpisodeCount <= 0 {
					e.EpisodeCount = d.EpisodeCount
				}
			}
			out = append(out, e)
		}
		return out, nil
	})
	if err != nil {
		return nil, err
	}
	// Overlay outside histMu: deletion takes the row lock before invalidation.
	out := s.overlay(ss, list)
	if len(out) > limit {
		out = out[:limit]
	}
	return out, nil
}

// Timestamp-only changes in the progress store are common and must not move
// old history to the top. A newer episode or materially different position is
// evidence of playback; locally deleted entries are filtered in overlay.
func mergeCloudHistory(history, progress []lvguo.Entry) []lvguo.Entry {
	out := append([]lvguo.Entry(nil), history...)
	at := make(map[string]int, len(out))
	for i, e := range out {
		if _, ok := at[e.SeriesID]; !ok {
			at[e.SeriesID] = i
		}
	}
	for _, p := range progress {
		if p.Time > time.Now().Add(5*time.Minute).UnixMilli() {
			continue
		}
		if i, ok := at[p.SeriesID]; ok {
			h := &out[i]
			delta := int64(h.PositionMs) - int64(p.PositionMs)
			if p.Time > h.Time && (p.EpisodeIndex != h.EpisodeIndex || (p.Vid != "" && h.Vid != "" && p.Vid != h.Vid) || delta < -5000 || delta > 5000) {
				h.Vid, h.EpisodeIndex, h.PositionMs, h.Time = p.Vid, p.EpisodeIndex, p.PositionMs, p.Time
				if p.EpisodeCount > 0 {
					h.EpisodeCount = p.EpisodeCount
				}
			}
		} else {
			at[p.SeriesID] = len(out)
			out = append(out, p)
		}
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Time > out[j].Time })
	return out
}

func (s *Server) overlay(ss *sess, list []lvguo.Entry) []lvguo.Entry {
	l := s.lib(ss)
	l.mu.Lock()
	local := make([]libRow, 0, len(l.rows))
	for _, r := range l.rows {
		local = append(local, *r)
	}
	l.mu.Unlock()
	deletedLocal := map[string]int64{}
	for _, r := range local {
		if r.Deleted {
			deletedLocal[r.SeriesID] = r.TS
		}
	}
	filtered := list[:0]
	for _, e := range list {
		if ts := deletedLocal[e.SeriesID]; ts == 0 || e.Time > ts {
			filtered = append(filtered, e)
		}
	}
	list = filtered
	at := map[string]int{}
	for i, e := range list {
		if _, ok := at[e.SeriesID]; !ok {
			at[e.SeriesID] = i
		}
	}
	seen, gone := map[string]int64{}, map[string]int64{}
	for _, r := range local {
		if r.Deleted {
			continue
		}
		// Accepted uploads can take time to appear in cloud reads.
		age := time.Since(time.UnixMilli(r.TS))
		pendingCloud := !r.Seen && (r.Awaiting || (age >= 0 && age < 2*time.Minute))
		if i, ok := at[r.SeriesID]; ok {
			cloud := list[i]
			delta := int64(cloud.PositionMs) - int64(r.Pos)
			confirmed := cloud.Time >= r.TS-1000 && cloud.EpisodeIndex == r.Index && (cloud.Vid == "" || cloud.Vid == r.Vid) && delta >= -5000 && delta <= 5000
			if !r.Dirty && !r.Seen && (confirmed || cloud.Time > r.TS+2000) {
				seen[r.SeriesID] = r.TS
			}
			if (r.Dirty || pendingCloud) && r.TS >= cloud.Time && !confirmed {
				list[i].Vid, list[i].EpisodeIndex, list[i].PositionMs, list[i].Time = r.Vid, r.Index, r.Pos, r.TS
			}
			continue
		}
		if !r.Dirty && !pendingCloud {
			if r.Seen {
				gone[r.SeriesID] = r.TS
			}
			// Uploaded local snapshots must not resurrect entries absent from the cloud.
			continue
		}
		e := lvguo.Entry{SeriesID: r.SeriesID, Vid: r.Vid, EpisodeIndex: r.Index, PositionMs: r.Pos, Time: r.TS, Title: r.Title, Cover: r.Cover, EpisodeCount: r.Count}
		if e.Title == "" {
			if d, err := s.content.Detail(s.cookie(ss), r.SeriesID); err == nil && d != nil {
				e.Title, e.Cover, e.EpisodeCount = d.Title, d.Cover, len(d.Episodes)
			}
		}
		list = append(list, e)
	}
	if len(seen)+len(gone) > 0 {
		l.mu.Lock()
		for id, ts := range seen {
			if r := l.rows[id]; r != nil && !r.Dirty && r.TS == ts {
				r.Seen = true
				r.Awaiting = false
			}
		}
		for id, ts := range gone {
			if r := l.rows[id]; r != nil && !r.Dirty && r.Seen && r.TS == ts {
				delete(l.rows, id)
			}
		}
		l.saveLocked()
		l.mu.Unlock()
	}
	sort.SliceStable(list, func(i, j int) bool { return list[i].Time > list[j].Time })
	return list
}
