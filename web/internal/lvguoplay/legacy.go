package lvguoplay

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

const legacyMobileEndpoint = "https://api5-normal-sinfonlinea.fqnovel.com/novel/player/multi_video_model/player/v1/"

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
	return PlayMultiModel(ctx, vid, "player")
}

// PlayMultiModel 按动态配置指定的 dr_scene 路径走纯 Go V5 签名取流。
// drScene 非空时请求 /novel/player/multi_video_model/<drScene>/v1/，为空时请求 /v1/。
func PlayMultiModel(ctx context.Context, vid, drScene string) (map[string]any, error) {
	if !validID(vid) {
		return nil, errors.New("视频编号无效")
	}
	d, err := playbackIdentity(ctx)
	if err != nil {
		return nil, err
	}
	cfg := playbackConfig.snapshot().Legacy
	if drScene != "" {
		cfg.Endpoint = "https://api5-normal-sinfonlinea.fqnovel.com/novel/player/multi_video_model/" + drScene + "/v1/"
	} else {
		cfg.Endpoint = "https://api5-normal-sinfonlinea.fqnovel.com/novel/player/multi_video_model/v1/"
	}
	model, err := requestLegacy(ctx, vid, cfg, d, nil)
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
	return requestLegacy(ctx, vid, cfg, d, nil)
}

func requestLegacy(ctx context.Context, vid string, cfg PlaybackRouteConfig, d miniReelDevice, client *http.Client) (map[string]any, error) {
	if !validID(vid) || cfg.NeedAll == nil {
		return nil, errors.New("播放参数无效")
	}
	endpoint := cfg.Endpoint
	if endpoint == "" {
		endpoint = legacyMobileEndpoint
	}
	payload := fmt.Sprintf(`{"biz_param":{"detail_page_version":0,"device_level":%d,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":%t,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":%d},"mixed_video_id_map":{"1004":["%s"]},"video_id":"%s","content_type":1}`, cfg.DeviceLevel, *cfg.NeedAll, cfg.VideoPlatform, vid, vid)
	var b bytes.Buffer
	z := gzip.NewWriter(&b)
	if _, err := z.Write([]byte(payload)); err != nil {
		return nil, err
	}
	if err := z.Close(); err != nil {
		return nil, err
	}
	p := sign.Params{
		{K: "aid", V: "8662"}, {K: "app_name", V: "novelread"}, {K: "version_code", V: "74332"}, {K: "version_name", V: "7.4.3.32"},
		{K: "channel", V: "vivo_8662_64"}, {K: "device_platform", V: "android"}, {K: "device_type", V: "PGT-AN10"}, {K: "os_version", V: "12"},
		{K: "update_version_code", V: "74332"}, {K: "iid", V: d.installID}, {K: "cdid", V: d.cdid}, {K: "device_id", V: d.deviceID}, {K: "_rticket", V: "0"},
	}
	signer, err := sign.NewPlaybackSigner(d.deviceID, "7.4.3.32")
	if err != nil {
		return nil, err
	}
	ticket := time.Now().UnixMilli()
	signed, err := signer.SignMobileV5(endpoint, p, b.Bytes(), ticket, nil)
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, signed.URL, bytes.NewReader(b.Bytes()))
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", "com.phoenix.read/73932 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)")
	req.Header.Set("Accept", "application/json")
	req.Header.Set("Content-Encoding", "gzip")
	for _, h := range signed.Headers {
		req.Header.Set(h[0], h[1])
	}
	if client == nil {
		client = videoTabHTTPClient
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
