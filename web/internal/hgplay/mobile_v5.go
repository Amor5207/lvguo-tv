// SPDX-License-Identifier: GPL-3.0-only
// Mobile v5 route adapted from N3urda/hongguoTV, commit 18c37c2.
package hgplay

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
	"time"
)

const mobileV5Endpoint = "https://api5-normal-sinfonlinea.fqnovel.com/novel/player/multi_video_model/player/v1"

func PlayMobileV5(ctx context.Context, vid string) (map[string]any, error) {
	if !validID(vid) {
		return nil, errors.New("视频编号无效")
	}
	d, err := tabIdentity(ctx)
	if err != nil {
		return nil, err
	}
	return requestMobileV5(ctx, vid, playbackConfig.snapshot().V5, d, nil)
}
func requestMobileV5(ctx context.Context, vid string, cfg PlaybackRouteConfig, d miniReelDevice, client *http.Client) (map[string]any, error) {
	if !validID(vid) || cfg.NeedAll == nil {
		return nil, errors.New("播放参数无效")
	}
	payload := fmt.Sprintf(`{"biz_param":{"detail_page_version":0,"device_level":%d,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":%t,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":%d},"mixed_video_id_map":{"1004":["%s"]},"dr_scene":"player"}`, cfg.DeviceLevel, *cfg.NeedAll, cfg.VideoPlatform, vid)
	var b bytes.Buffer
	z := gzip.NewWriter(&b)
	if _, err := z.Write([]byte(payload)); err != nil {
		return nil, err
	}
	if err := z.Close(); err != nil {
		return nil, err
	}
	p := sign.Params{{K: "aid", V: "8662"}, {K: "app_name", V: "novelread"}, {K: "version_code", V: "74332"}, {K: "version_name", V: "7.4.3.32"}, {K: "channel", V: "vivo_8662_64"}, {K: "device_platform", V: "android"}, {K: "device_type", V: "PGT-AN10"}, {K: "os_version", V: "12"}, {K: "update_version_code", V: "74332"}, {K: "iid", V: d.installID}, {K: "cdid", V: d.cdid}, {K: "device_id", V: d.deviceID}, {K: "_rticket", V: "0"}}
	signer, err := sign.NewPlaybackSigner(d.deviceID, "7.4.3.32")
	if err != nil {
		return nil, err
	}
	signed, err := signer.SignMobileV5(cfg.Endpoint, p, b.Bytes(), time.Now().UnixMilli(), nil)
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
		client = &http.Client{Timeout: 10 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("v5 HTTP %d", resp.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, playMaxJSONBytes+1))
	if err != nil {
		return nil, err
	}
	if len(bytes.TrimSpace(body)) == 0 {
		return nil, errors.New("v5 返回空响应")
	}
	if len(body) > playMaxJSONBytes {
		return nil, errors.New("v5 响应过大")
	}
	var root map[string]any
	dec := json.NewDecoder(bytes.NewReader(body))
	dec.UseNumber()
	if dec.Decode(&root) != nil {
		return nil, errors.New("v5 响应格式异常")
	}
	for _, key := range []string{"code", "Code"} {
		if code, ok := root[key]; ok && fmt.Sprint(code) != "0" {
			return nil, fmt.Errorf("v5 错误码 %v", code)
		}
	}
	data, _ := root["data"].(map[string]any)
	if nested, ok := data["1004"].(map[string]any); ok {
		data = nested
	}
	node, _ := data[vid].(map[string]any)
	if node == nil {
		return nil, errors.New("v5 缺少本集播放模型")
	}
	return map[string]any{"code": 0, "data": map[string]any{vid: node}}, nil
}
