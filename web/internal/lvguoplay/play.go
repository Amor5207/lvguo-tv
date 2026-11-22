package lvguoplay

// Play 是本包对外的唯一入口：注册匿名设备 → 用纯 Go 签名请求 video_model →
// 返回上游原始 JSON（由调用方按已有逻辑解析为播放档位）。
// 签名/设备注册实现搬运自 aston314/hongguo-tv（GPL-3.0），已实测可取到可播 CDN 直链。

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"time"
)

// 单集 video_model 端点（与设备注册用的 UA/版本保持一致）。
const playMaxJSONBytes = 8 << 20

var errVideoModelEmpty = errors.New("video_model 返回空响应")

// Play 注册一台匿名设备并取 vid 的 video_model 原始响应。
// 每次调用使用独立匿名设备，不绑定账号、不带登录 cookie。
func Play(ctx context.Context, vid string) (map[string]any, error) {
	if !validID(vid) {
		return nil, errors.New("视频编号无效")
	}
	device, err := registerMiniReelSingleDevice(ctx)
	if err != nil {
		return nil, err
	}
	return requestVideoModel(ctx, vid, device, nil)
}

// PlayWithClient 同 Play，但允许注入 http.Client（便于测试/复用连接）。
func PlayWithClient(ctx context.Context, vid string, client *http.Client) (map[string]any, error) {
	if !validID(vid) {
		return nil, errors.New("视频编号无效")
	}
	device, err := registerMiniReelSingleDeviceAt(ctx, miniReelSingleDeviceBase, client)
	if err != nil {
		return nil, err
	}
	return requestVideoModel(ctx, vid, device, client)
}

func requestVideoModel(ctx context.Context, vid string, device miniReelDevice, client *http.Client) (map[string]any, error) {
	configured := playbackConfig.snapshot().Single
	body, _ := json.Marshal(map[string]any{
		"video_id":  vid,
		"biz_param": map[string]any{"video_id_type": 0, "need_all_video_definition": *configured.NeedAll, "video_platform": configured.VideoPlatform},
	})
	stamp := time.Now().UnixMilli()
	q := miniReelSingleDeviceQuery(device)
	query, headers, err := miniReelSignPlaybackRequest(q, body, stamp, device.deviceID)
	if err != nil {
		return nil, errors.New("video_model 签名失败")
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, configured.Endpoint+"?"+query, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	request.Header = headers
	request.Header.Set("User-Agent", miniReelSingleUA)
	request.Header.Set("Content-Type", "application/json; charset=UTF-8")
	request.Header.Set("Accept", "application/json; charset=utf-8,application/x-protobuf")
	request.Header.Set("Passport-Sdk-Version", "50561")
	request.Header.Set("X-Vc-Bdturing-Sdk-Version", "3.7.2.cn")
	request.Header.Set("X-Tt-Request-Tag", "t=0;n=0")
	request.Header.Set("X-SS-DP", "8662")
	request.Header.Set("Lc", "101")
	request.Header.Set("Cookie", device.cookie)
	nonce, _ := protocolRandom()
	request.Header.Set("X-Reading-Request", fmt.Sprintf("%d-%d", stamp, nonce&0x7fffffff))

	httpClient := client
	if httpClient == nil {
		httpClient = videoTabHTTPClient
	}
	resp, err := httpClient.Do(request)
	if err != nil {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("video_model HTTP %d", resp.StatusCode)
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, playMaxJSONBytes+1))
	if err != nil {
		return nil, err
	}
	if len(bytes.TrimSpace(data)) == 0 {
		return nil, errVideoModelEmpty
	}
	var envelope map[string]any
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.UseNumber()
	if dec.Decode(&envelope) != nil || envelope == nil {
		return nil, errors.New("video_model 响应不是合法 JSON")
	}
	return envelope, nil
}
