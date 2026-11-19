// Package media 下载并解密整集视频（CENC），按总字节数缓存，并提供支持 Range 的 HTTP 处理器。
// 网页版服务端与 Windows 原生版共用：原生版把它挂在本机回环地址，交给播放组件读取。
package media

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"regexp"
	"strings"
	"sync"
	"time"

	"lvguo/internal/cenc"
	"lvguo/internal/hg"
	"lvguo/internal/nativestream"
)

const (
	streamCacheMax = 16
	streamCacheTTL = 8 * time.Minute
)

type entry struct {
	done chan struct{}
	data []byte
	err  error
	at   time.Time
}

type streamEntry struct {
	done chan struct{}
	src  *nativestream.Source
	err  error
	at   time.Time
	exp  time.Time
}

// Cache 保存「下载并解密后的整集 mp4」及流式解密句柄，按总字节数/数量淘汰最久未用的。
type Cache struct {
	mu      sync.Mutex
	max     int64
	size    int64
	items   map[string]*entry
	smu     sync.Mutex
	streams map[string]*streamEntry
	client  *http.Client
}

func newStreamHTTPClient() *http.Client {
	return &http.Client{
		Timeout: 20 * time.Second,
		Transport: &http.Transport{
			Proxy:               http.ProxyFromEnvironment,
			MaxIdleConns:        120,
			MaxIdleConnsPerHost: 32,
			IdleConnTimeout:     90 * time.Second,
		},
	}
}

var defaultStreamClient = newStreamHTTPClient()

func NewCache(maxBytes int64) *Cache {
	return &Cache{
		max:     maxBytes,
		items:   map[string]*entry{},
		streams: map[string]*streamEntry{},
		client:  newStreamHTTPClient(),
	}
}

// HasPlayable reports only fully downloaded, successfully decrypted cache entries.
func (m *Cache) HasPlayable(key string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	e := m.items[key]
	if e == nil {
		return false
	}
	select {
	case <-e.done:
		return e.err == nil && len(e.data) > 0
	default:
		return false
	}
}

// Forget 淘汰指定 vid 的所有流式句柄与缓存分片，供播放失败恢复（fresh=1）强制重取新源。
func (m *Cache) Forget(vid string) {
	if vid == "" {
		return
	}
	prefix := vid + "|"
	var closed []*nativestream.Source
	m.smu.Lock()
	for k, e := range m.streams {
		if strings.HasPrefix(k, prefix) {
			delete(m.streams, k)
			select {
			case <-e.done:
				if e.src != nil {
					closed = append(closed, e.src)
				}
			default:
			}
		}
	}
	m.smu.Unlock()
	for _, s := range closed {
		s.Close()
	}
	m.mu.Lock()
	for k, e := range m.items {
		if strings.HasPrefix(k, prefix) {
			select {
			case <-e.done:
				m.size -= int64(len(e.data))
				delete(m.items, k)
			default:
			}
		}
	}
	m.mu.Unlock()
}

func (m *Cache) Get(key string, load func() ([]byte, error)) ([]byte, error) {
	m.mu.Lock()
	e, ok := m.items[key]
	if ok {
		e.at = time.Now()
		m.mu.Unlock()
		<-e.done
		return e.data, e.err
	}
	e = &entry{done: make(chan struct{}), at: time.Now()}
	m.items[key] = e
	m.mu.Unlock()

	data, err := load()
	m.mu.Lock()
	e.data, e.err = data, err
	close(e.done)
	if err != nil {
		delete(m.items, key)
	} else {
		m.size += int64(len(data))
		for m.size > m.max {
			var oldK string
			var old *entry
			for k, v := range m.items {
				select {
				case <-v.done:
				default:
					continue
				}
				if k != key && (old == nil || v.at.Before(old.at)) {
					oldK, old = k, v
				}
			}
			if old == nil {
				break
			}
			m.size -= int64(len(old.data))
			delete(m.items, oldK)
		}
	}
	m.mu.Unlock()
	return data, err
}

// Fetch 取单集指定清晰度的播放地址，下载并解密。
func Fetch(c *hg.Content, a *hg.Account, vid, def string) ([]byte, error) {
	attempt := 0
	return fetchWithRetries(vid, def, func() ([]hg.Gear, error) {
		if attempt > 0 {
			c.ForgetPlay(vid)
		}
		attempt++
		return c.Play(a, vid)
	}, a.RefreshPlaybackSession, download)
}

type modelLoader func() ([]hg.Gear, error)
type profileRefresher func() error
type mediaDownloader func(string) ([]byte, error)

// fetchWithRetries keeps the requested vid and definition fixed throughout
// recovery. Each round obtains a fresh model, tries every mirror, and after two
// failed rounds registers a fresh anonymous playback identity.
func fetchWithRetries(vid, def string, load modelLoader, rotate profileRefresher, get mediaDownloader) ([]byte, error) {
	var last error
	var selected hg.Gear
	for round := 0; round < 3; round++ {
		if round == 2 && rotate != nil {
			if err := rotate(); err != nil {
				last = err
			}
		}
		gears, err := load()
		if err != nil {
			last = err
			continue
		}
		g := selectGear(gears, def)
		if g == nil {
			last = errors.New("没有可播放的清晰度")
			continue
		}
		selected = *g
		urls := []string{g.MainURL}
		urls = append(urls, g.BackupURLs...)
		if g.BackupURL != "" {
			urls = append(urls, g.BackupURL)
		}
		urls = uniqueURLs(urls)
		for _, u := range urls {
			if !strings.HasPrefix(u, "https://") {
				continue
			}
			for attempt := 0; attempt < 2; attempt++ {
				data, downloadErr := get(u)
				if downloadErr == nil {
					if !selected.Encrypted {
						return data, nil
					}
					key := cenc.SpadeToKey(selected.SpadeA)
					if key == nil {
						last = errors.New("视频缺少解密密钥")
						break
					}
					if decryptErr := cenc.Decrypt(data, key); decryptErr != nil {
						last = decryptErr
						break
					}
					return data, nil
				}
				last = downloadErr
				if expiredMediaURL(downloadErr) {
					break
				}
				if attempt == 0 {
					time.Sleep(300 * time.Millisecond)
				}
			}
		}
		if round < 2 {
			time.Sleep(time.Duration(round+1) * 500 * time.Millisecond)
		}
	}
	if last == nil {
		last = errors.New("没有可用播放地址")
	}
	return nil, fmt.Errorf("视频暂时无法播放：%v", last)
}

func selectGear(gears []hg.Gear, definition string) *hg.Gear {
	for i := range gears {
		if gears[i].Definition == definition {
			return &gears[i]
		}
	}
	return hg.PickGear(gears, "", 1080)
}

func uniqueURLs(values []string) []string {
	seen := map[string]bool{}
	out := make([]string, 0, len(values))
	for _, value := range values {
		value = strings.TrimSpace(value)
		if value != "" && !seen[value] {
			seen[value] = true
			out = append(out, value)
		}
	}
	return out
}

func expiredMediaURL(err error) bool {
	if err == nil {
		return false
	}
	m := err.Error()
	return strings.Contains(m, "HTTP 401") || strings.Contains(m, "HTTP 403") ||
		strings.Contains(m, "HTTP 404") || strings.Contains(m, "HTTP 410")
}

func download(u string) ([]byte, error) {
	req, _ := http.NewRequest("GET", u, nil)
	req.Header.Set("User-Agent", hg.ContentUA)
	res, err := (&http.Client{Timeout: 3 * time.Minute}).Do(req)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	if res.StatusCode != 200 {
		return nil, fmt.Errorf("HTTP %d", res.StatusCode)
	}
	return io.ReadAll(io.LimitReader(res.Body, 200<<20))
}

// Path 是视频地址的形式：/media/<vid>.mp4?q=<清晰度>
var Path = regexp.MustCompile(`^/media/(\d{6,24})\.mp4$`)

func (m *Cache) openStream(c *hg.Content, a *hg.Account, vid, def string) (*nativestream.Source, error) {
	key := vid + "|" + def
	now := time.Now()
	m.smu.Lock()
	if m.streams == nil {
		m.streams = map[string]*streamEntry{}
	}
	if e := m.streams[key]; e != nil {
		select {
		case <-e.done:
			if e.err == nil && e.src != nil && now.Before(e.exp) {
				e.at = now
				m.smu.Unlock()
				return e.src, nil
			}
			if e.src != nil {
				e.src.Close()
			}
			delete(m.streams, key)
		default:
			m.smu.Unlock()
			<-e.done
			if e.err == nil && e.src != nil {
				return e.src, nil
			}
			return nil, e.err
		}
	}
	e := &streamEntry{done: make(chan struct{}), at: now, exp: now.Add(streamCacheTTL)}
	m.streams[key] = e
	m.smu.Unlock()

	src, err := m.loadStream(c, a, vid, def)
	var evicted []*nativestream.Source
	m.smu.Lock()
	e.src, e.err, e.at = src, err, time.Now()
	close(e.done)
	if err != nil || src == nil {
		delete(m.streams, key)
	} else {
		go src.Warm()
		for len(m.streams) > streamCacheMax {
			var oldK string
			var old *streamEntry
			for k, v := range m.streams {
				if k == key {
					continue
				}
				select {
				case <-v.done:
					if old == nil || v.at.Before(old.at) {
						oldK, old = k, v
					}
				default:
				}
			}
			if old == nil {
				break
			}
			delete(m.streams, oldK)
			if old.src != nil {
				evicted = append(evicted, old.src)
			}
		}
	}
	m.smu.Unlock()
	for _, s := range evicted {
		s.Close()
	}
	return src, err
}

func (m *Cache) loadStream(c *hg.Content, a *hg.Account, vid, def string) (*nativestream.Source, error) {
	cl := m.client
	if cl == nil {
		cl = defaultStreamClient
	}
	gears, err := c.Play(a, vid)
	if err != nil {
		return nil, err
	}
	g := selectGear(gears, def)
	if g == nil {
		return nil, errors.New("没有可播放的清晰度")
	}
	var key []byte
	if g.Encrypted {
		key = cenc.SpadeToKey(g.SpadeA)
		if len(key) == 0 {
			return nil, errors.New("视频缺少解密密钥")
		}
	}
	urls := uniqueURLs(append(append([]string{g.MainURL}, g.BackupURLs...), g.BackupURL))
	if src, err := nativestream.OpenURLsWithClient(context.Background(), urls, key, cl); err == nil {
		return src, nil
	}
	return nil, errors.New("视频源打开失败")
}

// WarmStream 提前打开流式解密句柄并预取首片，降低切集/刷剧首帧等待。
func (m *Cache) WarmStream(c *hg.Content, a *hg.Account, vid, def string) {
	if vid == "" || m.HasPlayable(vid+"|"+def) {
		return
	}
	_, _ = m.openStream(c, a, vid, def)
}

// Serve 处理一次 /media/ 请求，支持 Range。
func (m *Cache) Serve(c *hg.Content, a *hg.Account, w http.ResponseWriter, r *http.Request) {
	mm := Path.FindStringSubmatch(r.URL.Path)
	if mm == nil {
		http.Error(w, "地址无效", http.StatusNotFound)
		return
	}
	vid, def := mm[1], r.URL.Query().Get("q")
	// 优先复用（或创建）带块缓存与 CENC 索引的流式句柄，避免每次 Range 请求重下 moov。
	if !m.HasPlayable(vid + "|" + def) {
		if src, err := m.openStream(c, a, vid, def); err == nil && src != nil {
			src.ServeHTTP(w, r)
			return
		}
	}
	data, err := m.Get(vid+"|"+def, func() ([]byte, error) { return Fetch(c, a, vid, def) })
	if err != nil {
		http.Error(w, "视频暂时无法播放", http.StatusBadGateway)
		return
	}
	w.Header().Set("Content-Type", "video/mp4")
	w.Header().Set("Cache-Control", "private, max-age=600")
	http.ServeContent(w, r, vid+".mp4", time.Time{}, bytes.NewReader(data))
}
