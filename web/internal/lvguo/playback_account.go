package lvguo

// Logged-in playback: the official V2 request shape, signed by the native
// runtime with the account's own device and cookie. The dynamic settings only
// decide whether dr_scene is enabled, which moves it into the body/path.

import (
	"bytes"
	"compress/gzip"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"

	"lvguo/internal/lvguosigner"
	"lvguo/internal/sign"
)

const (
	playbackVersionCode = "74532"
	playbackVersionName = "7.4.5.32"
	playbackUserAgent   = "com.phoenix.read/74532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:8d40f833 2026-03-03 QuicVersion:21ac1950 2025-11-18)"
)

var playbackHosts = []string{
	"https://api5-normal-sinfonlinea.fqnovel.com",
	"https://api5-normal-sinfonlineb.fqnovel.com",
}

func playbackEndpoint(host, drScene string) string {
	if drScene == "" || !validDrScene(drScene) {
		return strings.TrimRight(host, "/") + "/novel/player/multi_video_model/v1/"
	}
	return strings.TrimRight(host, "/") + "/novel/player/multi_video_model/" + drScene + "/v1/"
}

func accountPlaybackQuery(dev Device) sign.Params {
	now := strconv.FormatInt(time.Now().UnixMilli(), 10)
	return sign.Params{
		{K: "device_platform", V: "android"}, {K: "os", V: "android"}, {K: "ssmix", V: "a"}, {K: "_rticket", V: now},
		{K: "cdid", V: dev.CDID}, {K: "channel", V: "update_64"}, {K: "aid", V: "8662"}, {K: "app_name", V: "novelread"},
		{K: "version_code", V: playbackVersionCode}, {K: "version_name", V: playbackVersionName},
		{K: "manifest_version_code", V: playbackVersionCode}, {K: "update_version_code", V: playbackVersionCode},
		{K: "resolution", V: "1280*2772"}, {K: "dpi", V: "520"}, {K: "device_type", V: "25053RT47C"},
		{K: "device_brand", V: "Redmi"}, {K: "language", V: "zh"}, {K: "os_api", V: "36"},
		{K: "os_version", V: "16"}, {K: "ac", V: "wifi"}, {K: "host_abi", V: "arm64-v8a"},
		{K: "dragon_device_type", V: "phone"}, {K: "pv_player", V: playbackVersionCode},
		{K: "compliance_status", V: "0"}, {K: "need_personal_recommend", V: "1"},
		{K: "player_so_load", V: "1"}, {K: "is_android_pad_screen", V: "0"},
		{K: "iid", V: dev.IID}, {K: "device_id", V: dev.ID},
	}
}

func playbackCommonParam() map[string]any {
	return map[string]any{
		"app_language": "zh", "app_region": "CN", "carrier_region": "CN", "carrier_region_v2": "CN",
		"compliance_status": 0, "current_region": "CN", "device_dpi": 520, "language": "zh",
		"mcc_mnc": "46000", "region": "CN", "sys_language": "zh", "sys_region": "CN",
		"time_zone": "Asia/Shanghai", "ui_language": "zh", "user_language": "zh",
		"User-Agent": playbackUserAgent, "X-Xs-From-Web": false,
	}
}

func accountPlaybackBody(vid, drScene string, settings *DynamicSettings) map[string]any {
	biz := map[string]any{
		"detail_page_version": 0, "device_level": 3, "disable_digg_stat": false,
		"disable_video_relate_book": false,
		"need_all_video_definition": settings.NeedAllVideoDefinition(),
		"need_mp4_align":            settings.NeedMP4Align(),
		"use_os_player":             false, "use_server_dns": false,
		"video_platform": 1024,
	}
	if useDNS, scene := settings.ColdStartServerDNS(); useDNS {
		biz["use_server_dns"] = true
		biz["use_server_dns_scene"] = scene
	}
	body := map[string]any{
		"biz_param":          biz,
		"mixed_video_id_map": map[string]any{"1004": []string{vid}},
		"NovelCommonParam":   playbackCommonParam(),
	}
	if drScene != "" {
		body["dr_scene"] = drScene
	}
	return body
}

// PlaybackV2 asks upstream for a logged-in playback model. Callers should
// treat an empty response as a route failure and fall back to the anonymous
// routes, since upstream can silently reject a device at any time.
func (a *Account) PlaybackV2(ctx context.Context, vid, drScene string) (map[string]any, error) {
	if a == nil || a.U == nil || !a.U.LoggedIn() {
		return nil, errors.New("播放需要登录")
	}
	if !digits.MatchString(vid) {
		return nil, errors.New("视频编号无效")
	}
	dev, err := a.devices().Get()
	if err != nil {
		return nil, err
	}
	signer := lvguosigner.Default()
	if !signer.Enabled() {
		return nil, errors.New("native signer disabled")
	}
	payload, err := json.Marshal(accountPlaybackBody(vid, drScene, a.DynamicSettings()))
	if err != nil {
		return nil, err
	}
	var compressed bytes.Buffer
	zw := gzip.NewWriter(&compressed)
	if _, err := zw.Write(payload); err != nil {
		return nil, err
	}
	if err := zw.Close(); err != nil {
		return nil, err
	}
	body := compressed.Bytes()
	q := accountPlaybackQuery(dev)
	query := sign.EncodeParams(q)
	if ctx == nil {
		ctx = context.Background()
	}
	var lastErr error
	for _, host := range playbackHosts {
		u := playbackEndpoint(host, drScene) + "?" + query
		reqCtx, cancel := context.WithTimeout(ctx, 6*time.Second)
		h, err := signer.Sign(reqCtx, u, body)
		if err != nil {
			cancel()
			lastErr = err
			continue
		}
		headers := [][2]string{
			{"User-Agent", playbackUserAgent},
			{"Accept", "application/json; charset=utf-8,application/x-protobuf"},
			{"Accept-Language", "zh-CN,zh;q=0.9"},
			{"Content-Encoding", "gzip"},
			{"Content-Type", "application/json"},
			{"x-xs-from-web", "0"}, {"x-ss-dp", "8662"}, {"lc", "101"},
			{"x-ss-stub", strings.ToUpper(sign.Hex(sign.MD5(body)))},
			{"Cookie", a.U.Cookie()},
		}
		for k, values := range h {
			if len(values) > 0 {
				headers = append(headers, [2]string{k, values[0]})
			}
		}
		resp, err := doRequestContext(reqCtx, http.MethodPost, u, headers, body)
		cancel()
		if err != nil {
			lastErr = err
			continue
		}
		if resp.Status != http.StatusOK {
			lastErr = fmt.Errorf("播放信息 HTTP %d", resp.Status)
			continue
		}
		if len(bytes.TrimSpace(resp.Body)) == 0 {
			lastErr = &emptyResponseError{status: resp.Status}
			continue
		}
		root, err := resp.JSON()
		if err != nil {
			lastErr = err
			continue
		}
		return root, nil
	}
	if lastErr == nil {
		lastErr = errors.New("播放请求未完成")
	}
	return nil, lastErr
}

// PlaybackQueryForTest exposes the exact signed query order to tests.
func PlaybackQueryForTest(dev Device) string { return sign.EncodeParams(accountPlaybackQuery(dev)) }

// PlaybackBodyForTest exposes the exact body fields to tests.
func PlaybackBodyForTest(vid, drScene string) map[string]any {
	return accountPlaybackBody(vid, drScene, nil)
}
