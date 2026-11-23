package lvguoplay

// Playback configuration changes route selection and supported request knobs;
// signing code and secrets remain local. Invalid or unavailable remote config
// never replaces the last known good configuration.
import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const playbackConfigURL = "https://update.pengfei.ink/api/v1/playback-config"

type PlaybackRouteConfig struct {
	Endpoint      string `json:"endpoint"`
	DeviceLevel   int    `json:"device_level,omitempty"`
	VideoPlatform int    `json:"video_platform,omitempty"`
	NeedAll       *bool  `json:"need_all,omitempty"`
}
type APIEndpointConfig struct {
	Method     string            `json:"method"`
	Host       string            `json:"host"`
	BackupHost string            `json:"backup_host,omitempty"`
	Path       string            `json:"path"`
	Sign       string            `json:"sign"`
	Query      map[string]string `json:"query,omitempty"`
	Body       map[string]any    `json:"body,omitempty"`
}

type PlaybackConfig struct {
	Schema   int                          `json:"schema"`
	Revision int                          `json:"revision"`
	Routes   []string                     `json:"routes"`
	Legacy   PlaybackRouteConfig          `json:"legacy"`
	Single   PlaybackRouteConfig          `json:"single"`
	APIs     map[string]APIEndpointConfig `json:"apis,omitempty"`
}

func defaultAPIConfigs() map[string]APIEndpointConfig {
	const primary, backup = "api5-normal-sinfonlinea.fqnovel.com", "api3-normal-sinfonlinea.fqnovel.com"
	return map[string]APIEndpointConfig{
		"detail": {
			Method: "POST", Host: primary, BackupHost: backup, Path: "/novel/player/video_detail/v1/", Sign: "v5",
			Body: map[string]any{
				"biz_param": map[string]any{
					"caller_scene": "single_col", "detail_page_version": 0, "from_video_id": "",
					"need_all_video_definition": false, "need_mp4_align": false, "source": 4, "video_id_type": 1,
				},
			},
		},
		"detail_batch": {
			Method: "POST", Host: primary, BackupHost: backup, Path: "/novel/player/multi_video_detail/player/v1", Sign: "v5",
			Body: map[string]any{
				"dr_scene": "player",
				"biz_param": map[string]any{
					"detail_page_version": 0, "need_all_video_definition": false, "need_mp4_align": false,
					"source": 7, "use_os_player": false, "use_server_dns": false,
				},
			},
		},
		"rank": {
			Method: "GET", Host: primary, BackupHost: backup, Path: "/reading/bookapi/bookmall/cell/change/v1/", Sign: "v5",
			Query: map[string]string{"tab_type": "26", "client_req_type": "2", "client_template": "2", "screen_width_px": "1350"},
		},
		"album_videos": {
			Method: "GET", Host: primary, BackupHost: backup, Path: "/api/novel/origin/album/get_video_list/v1/", Sign: "v5",
		},
		"comments_book": {
			Method: "GET", Host: primary, BackupHost: backup, Path: "/reading/ugc/novel_comment/book/v1/", Sign: "v5",
		},
		"comments_item": {
			Method: "GET", Host: primary, BackupHost: backup, Path: "/reading/ugc/novel_comment/item/v1/", Sign: "v5",
		},
	}
}

func defaultPlaybackConfig() PlaybackConfig {
	yes := true
	return PlaybackConfig{Schema: 1, Revision: 0, Routes: []string{"dynamic", "videotab", "legacy"}, Legacy: PlaybackRouteConfig{Endpoint: legacyMobileEndpoint, DeviceLevel: 1, VideoPlatform: 1024, NeedAll: &yes}, Single: PlaybackRouteConfig{Endpoint: miniReelSingleEndpoint, VideoPlatform: 3, NeedAll: &yes}, APIs: defaultAPIConfigs()}
}

func validAPIHost(host string) bool {
	h := strings.ToLower(strings.TrimSpace(host))
	if h == "" || strings.ContainsAny(h, "/:?#@\\ \t\r\n") {
		return false
	}
	return (strings.HasSuffix(h, ".fqnovel.com") && h != ".fqnovel.com") ||
		(strings.HasSuffix(h, ".snssdk.com") && h != ".snssdk.com")
}

func validAPIPath(p string) bool {
	if len(p) < 2 || len(p) > 160 || p[0] != '/' || strings.Contains(p, "..") || strings.ContainsAny(p, "?#\\ \t\r\n") {
		return false
	}
	return true
}

func (a APIEndpointConfig) validate() error {
	m := strings.ToUpper(strings.TrimSpace(a.Method))
	if m != "GET" && m != "POST" {
		return errors.New("不支持的接口方法")
	}
	if !validAPIHost(a.Host) || (a.BackupHost != "" && !validAPIHost(a.BackupHost)) {
		return errors.New("不支持的接口域名")
	}
	if !validAPIPath(a.Path) {
		return errors.New("不支持的接口路径")
	}
	s := strings.ToLower(strings.TrimSpace(a.Sign))
	if s != "" && s != "v5" && s != "minireel" && s != "none" {
		return errors.New("不支持的接口签名策略")
	}
	return nil
}

func cloneAnyMap(in map[string]any) map[string]any {
	if in == nil {
		return nil
	}
	out := make(map[string]any, len(in))
	for k, v := range in {
		if sub, ok := v.(map[string]any); ok {
			out[k] = cloneAnyMap(sub)
		} else {
			out[k] = v
		}
	}
	return out
}

func cloneAPIEndpoint(a APIEndpointConfig) APIEndpointConfig {
	cp := a
	if a.Query != nil {
		cp.Query = make(map[string]string, len(a.Query))
		for k, v := range a.Query {
			cp.Query[k] = v
		}
	}
	cp.Body = cloneAnyMap(a.Body)
	return cp
}
func validPlaybackEndpoint(raw, expectedPath string) bool {
	u, e := url.Parse(raw)
	if e != nil || u.Scheme != "https" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || u.Port() != "" {
		return false
	}
	if u.Path != expectedPath && !(expectedPath == "/novel/player/multi_video_model/player/v1/" && u.Path == "/novel/player/multi_video_model/v1/") {
		return false
	}
	host := strings.ToLower(u.Hostname())
	return strings.HasSuffix(host, ".fqnovel.com") && host != ".fqnovel.com"
}
func (c PlaybackConfig) Validate() error {
	if c.Schema != 1 || c.Revision < 0 || len(c.Routes) < 1 || len(c.Routes) > 4 {
		return errors.New("无效的播放配置版本或路线")
	}
	seen := map[string]bool{}
	for _, r := range c.Routes {
		if (r != "dynamic" && r != "videotab" && r != "legacy" && r != "single") || seen[r] {
			return errors.New("不支持的播放路线")
		}
		seen[r] = true
	}
	if !validPlaybackEndpoint(c.Legacy.Endpoint, "/novel/player/multi_video_model/player/v1/") || !validPlaybackEndpoint(c.Single.Endpoint, "/novel/player/video_model/v1/") {
		return errors.New("不支持的播放接口地址")
	}
	if c.Legacy.DeviceLevel < 0 || c.Legacy.DeviceLevel > 5 || c.Legacy.VideoPlatform < 1 || c.Legacy.VideoPlatform > 10000 || c.Legacy.NeedAll == nil {
		return errors.New("legacy 播放参数超出允许范围")
	}
	if c.Single.VideoPlatform < 1 || c.Single.VideoPlatform > 10000 || c.Single.NeedAll == nil {
		return errors.New("单集播放参数超出允许范围")
	}
	for _, api := range c.APIs {
		if err := api.validate(); err != nil {
			return err
		}
	}
	return nil
}

type playbackConfigManager struct {
	mu      sync.RWMutex
	current PlaybackConfig
	path    string
	client  *http.Client
	url     string
}

func newPlaybackConfigManager(path, endpoint string, client *http.Client) *playbackConfigManager {
	m := &playbackConfigManager{current: defaultPlaybackConfig(), path: path, url: endpoint, client: client}
	if b, e := os.ReadFile(path); e == nil && len(b) <= 16384 {
		var c PlaybackConfig
		if json.Unmarshal(b, &c) == nil && c.Validate() == nil {
			m.current = c
		}
	}
	return m
}
func (m *playbackConfigManager) snapshot() PlaybackConfig {
	m.mu.RLock()
	defer m.mu.RUnlock()
	c := m.current
	c.Routes = append([]string(nil), c.Routes...)
	if c.Legacy.NeedAll != nil {
		v := *c.Legacy.NeedAll
		c.Legacy.NeedAll = &v
	}
	return c
}
func (m *playbackConfigManager) refresh() error {
	req, e := http.NewRequest("GET", m.url, nil)
	if e != nil {
		return e
	}
	req.Header.Set("Cache-Control", "no-cache")
	req.Header.Set("Accept", "application/json")
	resp, e := m.client.Do(req)
	if e != nil {
		return e
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return errors.New("配置服务不可用")
	}
	b, e := io.ReadAll(io.LimitReader(resp.Body, 16385))
	if e != nil || len(b) > 16384 {
		return errors.New("播放配置过大或读取失败")
	}
	var next PlaybackConfig
	if e = json.Unmarshal(b, &next); e != nil {
		return e
	}
	if e = next.Validate(); e != nil {
		return e
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if next.Revision < m.current.Revision {
		return errors.New("忽略过期配置")
	}
	if e = os.MkdirAll(filepath.Dir(m.path), 0700); e != nil {
		return e
	}
	tmp := m.path + ".tmp"
	if e = os.WriteFile(tmp, b, 0600); e != nil {
		return e
	}
	if e = os.Rename(tmp, m.path); e != nil {
		_ = os.Remove(tmp)
		return e
	}
	m.current = next
	return nil
}

var playbackConfig = newPlaybackConfigManager("", "", &http.Client{Timeout: 4 * time.Second})
var configurePlaybackOnce sync.Once

// ConfigurePlayback loads disk cache immediately and refreshes independently of playback.
func ConfigurePlayback(dataDir string) {
	configurePlaybackOnce.Do(func() {
		m := newPlaybackConfigManager(filepath.Join(dataDir, "playback-config-v1.json"), playbackConfigURL, &http.Client{Timeout: 4 * time.Second, CheckRedirect: func(r *http.Request, via []*http.Request) error { return http.ErrUseLastResponse }})
		playbackConfig.mu.Lock()
		playbackConfig.current = m.current
		playbackConfig.path = m.path
		playbackConfig.url = m.url
		playbackConfig.client = m.client
		playbackConfig.mu.Unlock()
		go func() {
			_ = playbackConfig.refresh()
			t := time.NewTicker(5 * time.Minute)
			defer t.Stop()
			for range t.C {
				_ = playbackConfig.refresh()
			}
		}()
	})
}
func PlaybackRoutes() []string { return playbackConfig.snapshot().Routes }

// API returns the dynamically configured endpoint strategy for name,
// falling back to the built-in signed default if not overridden remotely.
func API(name string) APIEndpointConfig {
	playbackConfig.mu.RLock()
	cfg, ok := playbackConfig.current.APIs[name]
	playbackConfig.mu.RUnlock()
	if ok && cfg.validate() == nil {
		return cloneAPIEndpoint(cfg)
	}
	defaults := defaultAPIConfigs()
	if def, exists := defaults[name]; exists {
		return cloneAPIEndpoint(def)
	}
	return APIEndpointConfig{}
}
