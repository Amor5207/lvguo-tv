package hgplay

// Independent video_tab route adapted from aston314/hongguo-tv. The existing
// single-model signer and its device profile are deliberately unchanged.
import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"sync"
	"time"
)

const videoTabEndpoint = "https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/video_tab/multi_video_model/v1/"

var errVideoTabEmpty = errors.New("video_tab 返回空响应")

const videoTabUA = "com.phoenix.read/71532 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)"

var videoTabIdentity struct {
	sync.Mutex
	device      miniReelDevice
	expires     time.Time
	lastRefresh time.Time
}

func tabIdentity(ctx context.Context) (miniReelDevice, error) {
	videoTabIdentity.Lock()
	defer videoTabIdentity.Unlock()
	if validID(videoTabIdentity.device.deviceID) && time.Now().Before(videoTabIdentity.expires) {
		return videoTabIdentity.device, nil
	}
	dev, err := newPassportDevice()
	if err != nil {
		return miniReelDevice{}, err
	}
	c := newPassportClient(accountSession{Device: dev, Cookies: map[string]string{}})
	reg, stop := context.WithTimeout(ctx, miniReelDeviceTimeout)
	defer stop()
	if err = c.registerDevice(reg); err != nil {
		return miniReelDevice{}, err
	}
	d := miniReelDevice{deviceID: c.session.DeviceID, installID: c.session.InstallID, cdid: dev.CDID, cookie: miniReelPlaybackCookieHeader(c.session.Cookies)}
	videoTabIdentity.device, videoTabIdentity.expires = d, time.Now().Add(2*time.Hour)
	return d, nil
}
func PlayVideoTab(ctx context.Context, vid string) (map[string]any, error) {
	if !validID(vid) {
		return nil, errors.New("视频编号无效")
	}
	d, err := tabIdentity(ctx)
	if err != nil {
		return nil, err
	}
	model, err := requestVideoTab(ctx, vid, playbackConfig.snapshot().VideoTab.Endpoint, d, nil)
	if !errors.Is(err, errVideoTabEmpty) || ctx.Err() != nil {
		return model, err
	}
	// An expired anonymous identity can return HTTP 200 with an empty body.
	// Refresh at most once per ten minutes to avoid a registration storm.
	videoTabIdentity.Lock()
	refresh := videoTabIdentity.device.deviceID == d.deviceID && time.Since(videoTabIdentity.lastRefresh) >= 10*time.Minute
	if refresh {
		videoTabIdentity.device = miniReelDevice{}
		videoTabIdentity.expires = time.Time{}
		videoTabIdentity.lastRefresh = time.Now()
	}
	videoTabIdentity.Unlock()
	if !refresh {
		return nil, err
	}
	d, err = tabIdentity(ctx)
	if err != nil {
		return nil, err
	}
	return requestVideoTab(ctx, vid, playbackConfig.snapshot().VideoTab.Endpoint, d, nil)
}
func requestVideoTab(ctx context.Context, vid, endpoint string, d miniReelDevice, client *http.Client) (map[string]any, error) {
	q := url.Values{
		"_rticket": {strconv.FormatInt(time.Now().UnixMilli(), 10)}, "ac": {"wifi"}, "aid": {"8662"}, "app_name": {"novelread"}, "channel": {"update_64"}, "cdid": {d.cdid}, "device_brand": {"Redmi"}, "device_id": {d.deviceID}, "device_platform": {"android"}, "device_type": {"25053RT47C"}, "dpi": {"520"}, "dragon_device_type": {"phone"}, "host_abi": {"arm64-v8a"}, "iid": {d.installID}, "is_android_pad_screen": {"0"}, "language": {"zh"}, "manifest_version_code": {"71332"}, "need_personal_recommend": {"1"}, "os": {"android"}, "os_api": {"36"}, "os_version": {"16"}, "resolution": {"1280*2772"}, "player_so_load": {"1"}, "pv_player": {"71332"}, "ssmix": {"a"}, "update_version_code": {"71332"}, "version_code": {"71332"}, "version_name": {"7.1.3.32"}, "compliance_status": {"0"}, "video_id": {vid}, "video_id_type": {"0"}, "device_level": {"1"}, "need_all_video_definition": {"1"}, "need_mp4_align": {"0"}, "use_os_player": {"0"}, "video_platform": {"1024"}, "source": {"0"}, "from_video_id": {""}}
	configured := playbackConfig.snapshot().VideoTab
	q.Set("device_level", strconv.Itoa(configured.DeviceLevel))
	q.Set("video_platform", strconv.Itoa(configured.VideoPlatform))
	if configured.NeedAll != nil && !*configured.NeedAll {
		q.Set("need_all_video_definition", "0")
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint+"?"+q.Encode(), nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", videoTabUA)
	req.Header.Set("Accept", "application/json; charset=utf-8")
	if d.cookie != "" {
		req.Header.Set("Cookie", d.cookie)
	}
	if client == nil {
		client = &http.Client{Timeout: 12 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("video_tab HTTP %d", resp.StatusCode)
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, playMaxJSONBytes+1))
	if err != nil {
		return nil, err
	}
	if len(bytes.TrimSpace(data)) == 0 {
		return nil, errVideoTabEmpty
	}
	if len(data) > playMaxJSONBytes {
		return nil, errors.New("video_tab 响应过大")
	}
	var root map[string]any
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.UseNumber()
	if decoder.Decode(&root) != nil {
		return nil, errors.New("video_tab 响应格式异常")
	}
	if code, ok := root["code"]; ok && fmt.Sprint(code) != "0" {
		return nil, fmt.Errorf("video_tab 错误码 %v", code)
	}
	blk, _ := root["data"].(map[string]any)
	rows, _ := blk["video_model_data"].(map[string]any)
	if rows == nil {
		rows = blk
	}
	node, _ := rows[vid].(map[string]any)
	if node == nil {
		return nil, errors.New("video_tab 缺少本集播放模型")
	}
	return map[string]any{"code": 0, "data": map[string]any{vid: node}}, nil
}
