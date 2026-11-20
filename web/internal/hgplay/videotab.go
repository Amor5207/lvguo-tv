package hgplay

// video_tab route: bodyless signed GET served by the same upstream when the
// V5 player POST answers 200 with an empty body (observed 2026-11-20).
import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"time"

	"lvguo/internal/sign"
)

const videoTabEndpoint = "https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/video_tab/multi_video_model/v1/"

const videoTabUA = "com.phoenix.read/73932 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"

func PlayVideoTab(ctx context.Context, vid string) (map[string]any, error) {
	if !validID(vid) {
		return nil, errors.New("视频编号无效")
	}
	d, err := playbackIdentity(ctx)
	if err != nil {
		return nil, err
	}
	p := sign.Params{
		{K: "aid", V: "8662"}, {K: "app_name", V: "novelread"},
		{K: "version_code", V: "74332"}, {K: "version_name", V: "7.4.3.32"},
		{K: "channel", V: "vivo_8662_64"}, {K: "device_platform", V: "android"},
		{K: "device_type", V: "PGT-AN10"}, {K: "os_version", V: "12"},
		{K: "update_version_code", V: "74332"},
		{K: "iid", V: d.installID}, {K: "cdid", V: d.cdid}, {K: "device_id", V: d.deviceID},
		{K: "video_id", V: vid}, {K: "video_id_type", V: "0"},
		{K: "device_level", V: "1"}, {K: "need_all_video_definition", V: "1"},
		{K: "need_mp4_align", V: "0"}, {K: "use_os_player", V: "0"},
		{K: "video_platform", V: "1024"}, {K: "source", V: "0"}, {K: "from_video_id", V: ""},
	}
	signer, err := sign.NewPlaybackSigner(d.deviceID, "7.4.3.32")
	if err != nil {
		return nil, err
	}
	signed, err := signer.SignMobileV5(videoTabEndpoint, p, nil, time.Now().UnixMilli(), nil)
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, signed.URL, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", videoTabUA)
	req.Header.Set("Accept", "application/json")
	for _, h := range signed.Headers {
		if h[0] == "Content-Type" || h[0] == "x-ss-stub" {
			continue
		}
		req.Header.Set(h[0], h[1])
	}
	client := &http.Client{Timeout: 12 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("video_tab HTTP %d", resp.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, playMaxJSONBytes+1))
	if err != nil {
		return nil, err
	}
	if len(body) > playMaxJSONBytes {
		return nil, errors.New("video_tab 响应过大")
	}
	var root map[string]any
	dec := json.NewDecoder(bytes.NewReader(body))
	dec.UseNumber()
	if dec.Decode(&root) != nil {
		return nil, errors.New("video_tab 响应格式异常")
	}
	if code := root["code"]; code != nil && fmt.Sprint(code) != "0" {
		return nil, fmt.Errorf("video_tab 错误码 %v", code)
	}
	data, _ := root["data"].(map[string]any)
	models, _ := data["video_model_data"].(map[string]any)
	node, _ := models[vid].(map[string]any)
	if node == nil || node["video_model"] == nil {
		return nil, errors.New("video_tab 缺少本集播放模型")
	}
	return map[string]any{"code": 0, "data": map[string]any{vid: node}}, nil
}
