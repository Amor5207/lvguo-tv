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
type PlaybackConfig struct {
	Schema   int                 `json:"schema"`
	Revision int                 `json:"revision"`
	Routes   []string            `json:"routes"`
	Legacy   PlaybackRouteConfig `json:"legacy"`
	Single   PlaybackRouteConfig `json:"single"`
}

func defaultPlaybackConfig() PlaybackConfig {
	yes := true
	return PlaybackConfig{Schema: 1, Revision: 0, Routes: []string{"dynamic", "videotab", "legacy"}, Legacy: PlaybackRouteConfig{Endpoint: legacyMobileEndpoint, DeviceLevel: 1, VideoPlatform: 1024, NeedAll: &yes}, Single: PlaybackRouteConfig{Endpoint: miniReelSingleEndpoint, VideoPlatform: 3, NeedAll: &yes}}
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
