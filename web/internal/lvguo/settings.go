package lvguo

// Upstream settings are what tell the official client whether to use the
// dr_scene playback shape. They are fetched with the logged-in account device
// and the native signer, then cached on disk so a network failure never
// invalidates the last known good route.

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"lvguo/internal/lvguosigner"
	"lvguo/internal/sign"
)

const (
	settingsVersionCode = "74532"
	settingsVersionName = "7.4.5.32"
	settingsTTL         = 15 * time.Minute
	settingsRetry       = 2 * time.Minute
	settingsMaxBytes    = 4 << 20
)

var settingsHosts = []string{
	"https://is.snssdk.com",
	"https://ib.snssdk.com",
	"https://api5-normal-sinfonlinea.fqnovel.com",
	"https://api5-normal-sinfonlineb.fqnovel.com",
}

type DynamicSettings struct {
	raw     map[string]any
	fetched time.Time
}

// DrScene follows video_player_api_dr_scene. The client defaults this config
// to disabled, so an unavailable setting falls back to the legacy V1 shape.
func (s *DynamicSettings) DrScene() string {
	m := s.object("video_player_api_dr_scene")
	if m == nil {
		return ""
	}
	if enabled, ok := m["enable"].(bool); ok && !enabled {
		return ""
	}
	scene := str(m["dr_scene"])
	if scene == "" {
		scene = "default"
	}
	if !validDrScene(scene) {
		return "default"
	}
	return scene
}

// NeedAllVideoDefinition mirrors SeriesResolutionABValue.request_all_definition.
// The client constructor defaults it to true when series_resolution_v635 is absent.
func (s *DynamicSettings) NeedAllVideoDefinition() bool {
	return s.boolDefault(true, "series_resolution_v635", "request_all_definition")
}

func (s *DynamicSettings) NeedMP4Align() bool {
	return s.boolDefault(false, "series_resolution_v635", "request_mp4_align")
}

// ColdStartServerDNS mirrors VideoIpDirectABValue. During the cold stage the
// client asks upstream for IP-direct URLs.
func (s *DynamicSettings) ColdStartServerDNS() (bool, string) {
	m := s.object("video_ip_direct_v699")
	if m == nil {
		return false, ""
	}
	enabled, _ := m["enable_cold_stage"].(bool)
	if !enabled {
		return false, ""
	}
	stage := int64(num(m["cold_stage_time"]))
	if stage < 0 {
		return false, ""
	}
	if stage > 0 {
		elapsed := time.Since(processStart)
		if elapsed <= 0 || elapsed > time.Duration(stage)*time.Millisecond {
			return false, ""
		}
	}
	return true, "cold_start_stage"
}

// processStart stands in for the client's app-start timestamp: the config is
// an absolute cold-stage window, not a per-request flag.
var processStart = time.Now()

func (s *DynamicSettings) object(path ...string) map[string]any {
	if s == nil || s.raw == nil {
		return nil
	}
	var cur any = s.raw
	for _, key := range path {
		m := obj(cur)
		if m == nil {
			return nil
		}
		cur = m[key]
	}
	return obj(decodeSettingValue(cur))
}

func (s *DynamicSettings) boolDefault(def bool, path ...string) bool {
	if s == nil || s.raw == nil {
		return def
	}
	var cur any = s.raw
	for _, key := range path {
		m := obj(cur)
		if m == nil {
			return def
		}
		cur = m[key]
	}
	cur = decodeSettingValue(cur)
	if b, ok := cur.(bool); ok {
		return b
	}
	return def
}

// validDrScene keeps a remote setting from changing the playback path shape.
func validDrScene(scene string) bool {
	if scene == "" {
		return true
	}
	if len(scene) > 32 {
		return false
	}
	for i := 0; i < len(scene); i++ {
		c := scene[i]
		if (c < 'a' || c > 'z') && (c < '0' || c > '9') && c != '_' {
			return false
		}
	}
	return true
}

func (s *DynamicSettings) Bool(path ...string) bool {
	if s == nil || s.raw == nil {
		return false
	}
	var cur any = s.raw
	for _, key := range path {
		m := obj(cur)
		if m == nil {
			return false
		}
		cur = m[key]
	}
	cur = decodeSettingValue(cur)
	if b, ok := cur.(bool); ok {
		return b
	}
	return false
}

func decodeSettingValue(value any) any {
	text, ok := value.(string)
	if !ok || !strings.HasPrefix(strings.TrimSpace(text), "{") {
		return value
	}
	var decoded any
	if json.Unmarshal([]byte(text), &decoded) == nil {
		return decoded
	}
	return value
}

type settingsState struct {
	mu          sync.RWMutex
	path        string
	raw         map[string]any
	fetched     time.Time
	lastAttempt time.Time
	refreshing  bool
}

var dynamicSettings settingsState
var settingsFetchMu sync.Mutex

// ConfigureSettings enables disk persistence. Call before serving requests.
func ConfigureSettings(dataDir string) {
	if dataDir == "" {
		return
	}
	path := filepath.Join(dataDir, "lvguo-settings-v1.json")
	var stored struct {
		Fetched  int64          `json:"fetched"`
		Settings map[string]any `json:"settings"`
	}
	if b, err := os.ReadFile(path); err == nil && len(b) <= settingsMaxBytes {
		if json.Unmarshal(b, &stored) == nil && len(stored.Settings) > 0 {
			dynamicSettings.mu.Lock()
			dynamicSettings.path = path
			dynamicSettings.raw = stored.Settings
			dynamicSettings.fetched = time.Unix(stored.Fetched, 0)
			dynamicSettings.mu.Unlock()
			return
		}
	}
	dynamicSettings.mu.Lock()
	dynamicSettings.path = path
	dynamicSettings.mu.Unlock()
}

func settingsSnapshot() *DynamicSettings {
	dynamicSettings.mu.RLock()
	defer dynamicSettings.mu.RUnlock()
	return &DynamicSettings{raw: dynamicSettings.raw, fetched: dynamicSettings.fetched}
}

func (s *DynamicSettings) fresh() bool {
	return s != nil && s.raw != nil && time.Since(s.fetched) < settingsTTL
}

func settingsBackoff() bool {
	dynamicSettings.mu.RLock()
	defer dynamicSettings.mu.RUnlock()
	return !dynamicSettings.lastAttempt.IsZero() && time.Since(dynamicSettings.lastAttempt) < settingsRetry
}

func storeSettings(raw map[string]any, fetched time.Time) {
	dynamicSettings.mu.Lock()
	dynamicSettings.raw = raw
	dynamicSettings.fetched = fetched
	dynamicSettings.lastAttempt = time.Now()
	path := dynamicSettings.path
	dynamicSettings.mu.Unlock()
	if path == "" {
		return
	}
	b, err := json.Marshal(struct {
		Fetched  int64          `json:"fetched"`
		Settings map[string]any `json:"settings"`
	}{Fetched: fetched.Unix(), Settings: raw})
	if err != nil || len(b) > settingsMaxBytes {
		return
	}
	_ = os.MkdirAll(filepath.Dir(path), 0700)
	tmp := path + ".tmp"
	if os.WriteFile(tmp, b, 0600) == nil {
		_ = os.Rename(tmp, path)
	}
}

// DynamicSettings returns the last good upstream settings and refreshes them
// in the background. Playback must never wait ten seconds for a settings
// request before asking for a video model.
func (a *Account) DynamicSettings() *DynamicSettings {
	snap := settingsSnapshot()
	if snap.fresh() || settingsBackoff() || !startSettingsRefresh() {
		return snap
	}
	go a.refreshSettings(context.Background())
	return snap
}

// RefreshSettings fetches immediately; login handlers can use it to have the
// route ready before the first playback request.
func (a *Account) RefreshSettings() {
	if startSettingsRefresh() {
		a.refreshSettings(context.Background())
	}
}

func startSettingsRefresh() bool {
	dynamicSettings.mu.Lock()
	defer dynamicSettings.mu.Unlock()
	if dynamicSettings.refreshing {
		return false
	}
	dynamicSettings.refreshing = true
	return true
}

func (a *Account) refreshSettings(ctx context.Context) {
	defer func() {
		dynamicSettings.mu.Lock()
		dynamicSettings.refreshing = false
		dynamicSettings.mu.Unlock()
	}()
	settingsFetchMu.Lock()
	defer settingsFetchMu.Unlock()
	snap := settingsSnapshot()
	if snap.fresh() || settingsBackoff() {
		return
	}
	raw, err := a.fetchSettings(ctx)
	dynamicSettings.mu.Lock()
	dynamicSettings.lastAttempt = time.Now()
	dynamicSettings.mu.Unlock()
	if err != nil {
		return
	}
	storeSettings(raw, time.Now())
}

func (a *Account) settingsQuery(dev Device) sign.Params {
	now := strconv.FormatInt(time.Now().UnixMilli(), 10)
	q := sign.Params{
		{K: "device_platform", V: "android"}, {K: "os", V: "android"}, {K: "ssmix", V: "a"}, {K: "_rticket", V: now},
		{K: "cdid", V: dev.CDID}, {K: "channel", V: "update_64"}, {K: "aid", V: "8662"}, {K: "app_name", V: "novelread"},
		{K: "version_code", V: settingsVersionCode}, {K: "version_name", V: settingsVersionName},
		{K: "manifest_version_code", V: settingsVersionCode}, {K: "update_version_code", V: settingsVersionCode},
		{K: "resolution", V: "1280*2772"}, {K: "dpi", V: "520"}, {K: "device_type", V: "25053RT47C"},
		{K: "device_brand", V: "Redmi"}, {K: "language", V: "zh"}, {K: "os_api", V: "36"},
		{K: "os_version", V: "16"}, {K: "ac", V: "wifi"}, {K: "host_abi", V: "arm64-v8a"},
		{K: "device_id", V: dev.ID}, {K: "iid", V: dev.IID},
	}
	for _, kv := range []sign.Param{{K: "openudid", V: dev.OpenUDID}, {K: "clientudid", V: dev.ClientUDID}} {
		if kv.V != "" {
			q = append(q, kv)
		}
	}
	q = append(q, sign.Param{K: "req_id", V: uuid()})
	if dev.Region != "" {
		q = append(q, sign.Param{K: "store_region", V: dev.Region})
	} else {
		q = append(q, sign.Param{K: "store_region", V: "cn"})
	}
	return append(q, sign.Param{K: "store_region_src", V: "did"}, sign.Param{K: "app", V: "1"})
}

func (a *Account) fetchSettings(ctx context.Context) (map[string]any, error) {
	// 1. 优先使用纯 Go 签名拉取动态配置，不依赖 Docker 原生签名器。
	if a != nil && a.C != nil {
		for _, host := range settingsHosts {
			m, err := a.Signed(host, "/service/settings/v3/", sign.Params{{K: "app", V: "1"}}, nil)
			if err != nil {
				continue
			}
			if settings := obj(obj(m["data"])["settings"]); len(settings) > 0 {
				if dr, ok := settings["video_player_api_dr_scene"]; ok {
					if m := obj(decodeSettingValue(dr)); m == nil {
						delete(settings, "video_player_api_dr_scene")
					}
				}
				return settings, nil
			}
		}
	}
	// 2. 纯 Go 签名失败时，最后才回退 Docker 原生签名器。
	signer := lvguosigner.Default()
	if !signer.Enabled() {
		return nil, errors.New("settings 请求未完成且 native signer 已禁用")
	}
	dev, err := a.devices().Get()
	if err != nil {
		return nil, err
	}
	q := a.settingsQuery(dev)
	ua := fmt.Sprintf("com.phoenix.read/%s (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:8d40f833 2026-03-03 QuicVersion:21ac1950 2025-11-18)", settingsVersionCode)
	var lastErr error
	for _, host := range settingsHosts {
		u := host + "/service/settings/v3/?" + sign.EncodeParams(q)
		if ctx == nil {
			ctx = context.Background()
		}
		reqCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
		h, err := signer.Sign(reqCtx, u, nil)
		if err != nil {
			cancel()
			lastErr = err
			continue
		}
		headers := [][2]string{{"User-Agent", ua}, {"Accept", "application/json"}, {"Accept-Encoding", "identity"}}
		for k, values := range h {
			if len(values) > 0 {
				headers = append(headers, [2]string{k, values[0]})
			}
		}
		if cookie := a.U.Cookie(); cookie != "" {
			headers = append(headers, [2]string{"Cookie", cookie})
		}
		resp, err := doRequestContext(reqCtx, http.MethodGet, u, headers, nil)
		cancel()
		if err != nil {
			lastErr = err
			continue
		}
		if resp.Status != http.StatusOK {
			lastErr = fmt.Errorf("settings HTTP %d", resp.Status)
			continue
		}
		root, err := resp.JSON()
		if err != nil {
			lastErr = err
			continue
		}
		if code := int(num(root["code"])); code != 0 {
			lastErr = fmt.Errorf("settings code %d", code)
			continue
		}
		settings := obj(obj(root["data"])["settings"])
		if settings == nil {
			lastErr = errors.New("settings 为空")
			continue
		}
		if dr, ok := settings["video_player_api_dr_scene"]; ok {
			dr = decodeSettingValue(dr)
			if m := obj(dr); m == nil {
				delete(settings, "video_player_api_dr_scene")
			}
		}
		return settings, nil
	}
	if lastErr == nil {
		lastErr = errors.New("settings 请求未完成")
	}
	return nil, lastErr
}
