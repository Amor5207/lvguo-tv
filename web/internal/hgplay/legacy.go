package hgplay

// Legacy route: signed POST to /novel/player/multi_video_model/v1/. Upstream
// serves this older path for registered anonymous devices while the newer
// player/v1 variant answers 200 with an empty body.
import (
	"bytes"
	"compress/gzip"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"lvguo/internal/sign"
	"net/http"
	"sync"
	"time"
)

const legacyMobileEndpoint = "https://api5-normal-sinfonlinea.fqnovel.com/novel/player/multi_video_model/v1/"

var errLegacyModelEmpty = errors.New("legacy 返回空响应")

var modelIdentity struct {
	sync.Mutex
	device      miniReelDevice
	expires     time.Time
	lastRefresh time.Time
}

func playbackIdentity(ctx context.Context) (miniReelDevice, error) {
	modelIdentity.Lock()
	defer modelIdentity.Unlock()
	if validID(modelIdentity.device.deviceID) && time.Now().Before(modelIdentity.expires) {
		return modelIdentity.device, nil
	}
	// The player endpoints silently return an empty body for devices
	// registered with the account-style passport payload; the single-episode
	// profile is accepted immediately (verified 2026-11-20).
	d, err := registerMiniReelSingleDeviceAt(ctx, miniReelSingleDeviceBase, nil)
	if err != nil {
		return miniReelDevice{}, err
	}
	modelIdentity.device, modelIdentity.expires = d, time.Now().Add(2*time.Hour)
	return d, nil
}

func PlayLegacy(ctx context.Context, vid string) (map[string]any, error) {
	if !validID(vid) {
		return nil, errors.New("视频编号无效")
	}
	d, err := playbackIdentity(ctx)
	if err != nil {
		return nil, err
	}
	model, err := requestLegacy(ctx, vid, playbackConfig.snapshot().Legacy, d, nil)
	if !errors.Is(err, errLegacyModelEmpty) || ctx.Err() != nil {
		return model, err
	}
	// An expired anonymous identity can return HTTP 200 with an empty body.
	// Refresh at most once per ten minutes to avoid a registration storm.
	modelIdentity.Lock()
	refresh := modelIdentity.device.deviceID == d.deviceID && time.Since(modelIdentity.lastRefresh) >= 10*time.Minute
	if refresh {
		modelIdentity.device = miniReelDevice{}
		modelIdentity.expires = time.Time{}
		modelIdentity.lastRefresh = time.Now()
	}
	modelIdentity.Unlock()
	if !refresh {
		return nil, err
	}
	d, err = playbackIdentity(ctx)
	if err != nil {
		return nil, err
	}
	return requestLegacy(ctx, vid, playbackConfig.snapshot().Legacy, d, nil)
}

func requestLegacy(ctx context.Context, vid string, cfg PlaybackRouteConfig, d miniReelDevice, client *http.Client) (map[string]any, error) {
	if !validID(vid) || cfg.NeedAll == nil {
		return nil, errors.New("播放参数无效")
	}
	// 2026-11-20 起服务端把 dr_scene 从路径移进请求体：不带它的旧格式仍回 200，但内容为空。
	payload := fmt.Sprintf(`{"biz_param":{"detail_page_version":0,"device_level":%d,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":%t,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":%d},"mixed_video_id_map":{"1004":["%s"]},"dr_scene":"player"}`, cfg.DeviceLevel, *cfg.NeedAll, cfg.VideoPlatform, vid)
	var b bytes.Buffer
	z := gzip.NewWriter(&b)
	if _, err := z.Write([]byte(payload)); err != nil {
		return nil, err
	}
	if err := z.Close(); err != nil {
		return nil, err
	}
	// 参数顺序与 7.4.3.32 客户端一致（2026-11-20 抓包）；服务端校验签名时会回看这份查询串。
	p := sign.Params{
		{K: "device_platform", V: "android"}, {K: "os", V: "android"}, {K: "ssmix", V: "a"}, {K: "_rticket", V: ""},
		{K: "cdid", V: d.cdid}, {K: "channel", V: "xiaomi_8662_64"}, {K: "aid", V: "8662"}, {K: "app_name", V: "novelread"},
		{K: "version_code", V: "74332"}, {K: "version_name", V: "7.4.3.32"}, {K: "manifest_version_code", V: "74332"},
		{K: "update_version_code", V: "74332"}, {K: "resolution", V: "1200*2670"}, {K: "dpi", V: "460"},
		{K: "device_type", V: "23127PN0CC"}, {K: "device_brand", V: "Xiaomi"}, {K: "language", V: "zh"},
		{K: "os_api", V: "34"}, {K: "os_version", V: "14"}, {K: "ac", V: "wifi"}, {K: "host_abi", V: "arm64-v8a"},
		{K: "dragon_device_type", V: "phone"}, {K: "pv_player", V: "74332"}, {K: "compliance_status", V: "0"},
		{K: "need_personal_recommend", V: "1"}, {K: "player_so_load", V: "1"}, {K: "is_android_pad_screen", V: "0"},
		{K: "iid", V: d.installID}, {K: "device_id", V: d.deviceID},
	}
	signer, err := sign.NewPlaybackSigner(d.deviceID, "7.4.3.32")
	if err != nil {
		return nil, err
	}
	ticket := time.Now().UnixMilli()
	signed, err := signer.SignMobileV5(cfg.Endpoint, p, b.Bytes(), ticket, nil)
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, signed.URL, bytes.NewReader(b.Bytes()))
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", "com.phoenix.read/74332 (Linux; U; Android 14; zh_CN; 23127PN0CC; Build/UKQ1.230804.001; Cronet/TTNetVersion:8d40f833 2026-03-03 QuicVersion:21ac1950 2025-11-18)")
	req.Header.Set("Accept", "application/json; charset=utf-8,application/x-protobuf")
	req.Header.Set("Content-Encoding", "gzip")
	req.Header.Set("x-xs-from-web", "0")
	req.Header.Set("x-ss-dp", "8662")
	req.Header.Set("lc", "101")
	req.Header.Set("x-reading-request", fmt.Sprintf("%d-%d", ticket, time.Now().UnixNano()&0x7fffffff))
	if d.cookie != "" {
		req.Header.Set("Cookie", d.cookie)
	}
	for _, h := range signed.Headers {
		req.Header.Set(h[0], h[1])
	}
	if client == nil {
		client = &http.Client{Timeout: 10 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("播放接口 HTTP %d", resp.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, playMaxJSONBytes+1))
	if err != nil {
		return nil, err
	}
	if len(bytes.TrimSpace(body)) == 0 {
		return nil, errLegacyModelEmpty
	}
	if len(body) > playMaxJSONBytes {
		return nil, errors.New("播放响应过大")
	}
	var root map[string]any
	dec := json.NewDecoder(bytes.NewReader(body))
	dec.UseNumber()
	if dec.Decode(&root) != nil {
		return nil, errors.New("播放响应格式异常")
	}
	for _, key := range []string{"code", "Code"} {
		if code, ok := root[key]; ok && fmt.Sprint(code) != "0" {
			return nil, fmt.Errorf("播放错误码 %v", code)
		}
	}
	data, _ := root["data"].(map[string]any)
	if nested, ok := data["1004"].(map[string]any); ok {
		data = nested
	}
	node, _ := data[vid].(map[string]any)
	if node == nil {
		return nil, errors.New("缺少本集播放模型")
	}
	return map[string]any{"code": 0, "data": map[string]any{vid: node}}, nil
}
