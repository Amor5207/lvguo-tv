package lvguoplay

// The single-episode protocol is deliberately isolated from account requests
// and the legacy POST's profile.
import (
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"strconv"
)

const miniReelSinglePath = "/novel/player/video_model/v1/"
const miniReelSingleEndpoint = "https://api5-normal-sinfonlinea.fqnovel.com" + miniReelSinglePath
const miniReelSingleDeviceBase = "https://log5-applog-lq.fqnovel.com"
const miniReelSingleUA = "com.phoenix.read/73932 (Linux; U; Android 14; zh_CN; 23127PN0CC; Build/UKQ1.230804.001; Cronet/TTNetVersion:8d40f833 2026-03-03 QuicVersion:21ac1950 2025-11-18)"

func miniReelSingleDeviceQuery(d miniReelDevice) url.Values {
	return url.Values{
		"iid": {d.installID}, "device_id": {d.deviceID}, "ac": {"wifi"}, "channel": {"xiaomi_8662_64"},
		"aid": {"8662"}, "app_name": {"novelread"}, "cdid": {d.cdid}, "version_code": {"73932"}, "version_name": {"7.3.9.32"},
		"device_platform": {"android"}, "os": {"android"}, "ssmix": {"a"}, "device_type": {"23127PN0CC"}, "device_brand": {"Xiaomi"},
		"language": {"zh"}, "os_api": {"34"}, "os_version": {"14"}, "manifest_version_code": {"73932"},
		"resolution": {"1200*2670"}, "dpi": {"460"}, "update_version_code": {"73932"}, "host_abi": {"arm64-v8a"},
		"dragon_device_type": {"phone"}, "pv_player": {"73932"}, "compliance_status": {"0"}, "need_personal_recommend": {"1"},
		"player_so_load": {"1"}, "is_android_pad_screen": {"0"}, "openudid": {d.openUDID},
	}
}

func miniReelSingleDevicePayload(d *passportDevice, stamp int64) []byte {
	var p map[string]any
	_ = json.Unmarshal(d.payload(stamp), &p)
	h := p["header"].(map[string]any)
	for k, v := range map[string]any{"display_name": "红果免费短剧", "app_version": "7.3.9.32", "app_version_minor": "7.3.9.32", "version_code": 73932, "manifest_version_code": 73932, "update_version_code": 73932, "channel": "xiaomi_8662_64", "device_brand": "Xiaomi", "device_manufacturer": "Xiaomi", "device_model": "23127PN0CC", "os_api": 34, "os_version": "14", "density_dpi": 460, "resolution": "2670x1200", "device_id": "0", "install_id": "0", "req_id": d.ClientUDID} {
		h[k] = v
	}
	h["oaid"] = map[string]any{"req_id": d.CDID, "hw_id_version_code": "0", "take_ms": "6", "is_track_limited": "false", "query_times": "1", "id": d.CDID, "time": strconv.FormatInt(stamp-200, 10)}
	b, _ := json.Marshal(p)
	return b
}

func registerMiniReelSingleDevice(ctx context.Context) (miniReelDevice, error) {
	return registerMiniReelSingleDeviceAt(ctx, miniReelSingleDeviceBase, nil)
}
func registerMiniReelSingleDeviceAt(ctx context.Context, base string, httpClient *http.Client) (miniReelDevice, error) {
	if err := ctx.Err(); err != nil {
		return miniReelDevice{}, err
	}
	d, err := newPassportDevice()
	if err != nil {
		return miniReelDevice{}, err
	}
	c := newPassportClient(accountSession{Device: d, Cookies: map[string]string{}})
	c.deviceBase = base
	if httpClient != nil {
		c.http = httpClient
	}
	ctx, cancel := context.WithTimeout(ctx, miniReelDeviceTimeout)
	defer cancel()
	if err = c.registerDeviceProfile(ctx, true); err != nil {
		return miniReelDevice{}, err
	}
	return miniReelDevice{deviceID: c.session.DeviceID, installID: c.session.InstallID, cdid: d.CDID, openUDID: d.OpenUDID, cookie: miniReelPlaybackCookieHeader(c.session.Cookies)}, nil
}
