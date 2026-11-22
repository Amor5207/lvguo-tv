// SPDX-License-Identifier: GPL-3.0-only
// Ported from hongguoTV kotlin-v0.1.26 PlaybackDeviceSession + DeviceRegister.
package lvguo

import (
	"bytes"
	"compress/gzip"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"

	"lvguo/internal/sign"
)

// PlaybackDeviceSession is the anonymous, registered identity used only for
// playback. It is process-shared like PlaybackDeviceSession.shared in TV.
type PlaybackDeviceSession struct {
	mu      sync.Mutex
	profile *playbackIdentity
}

type playbackIdentity struct {
	deviceID string
	values   sign.Params
	cookie   string
	signer   *sign.PlaybackSigner
}

var sharedPlaybackDeviceSession = &PlaybackDeviceSession{}

func (s *PlaybackDeviceSession) get() (*playbackIdentity, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.profile != nil {
		return s.profile, nil
	}
	device, err := registerPlaybackDevice()
	if err != nil {
		return nil, err
	}
	values := playbackVendorParams().Set("device_id", device.deviceID).Set("iid", device.installID).Set("cdid", uuid())
	signer, err := sign.NewPlaybackSigner(device.deviceID, sign.PlaybackVersion)
	if err != nil {
		return nil, err
	}
	s.profile = &playbackIdentity{deviceID: device.deviceID, values: values, cookie: device.cookie, signer: signer}
	return s.profile, nil
}

// rotate replaces only the identity that exhausted its routes. Concurrent
// callers reuse a replacement already installed by another request.
func (s *PlaybackDeviceSession) rotate(expected *playbackIdentity) (*playbackIdentity, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.profile != expected && s.profile != nil {
		return s.profile, nil
	}
	device, err := registerPlaybackDevice()
	if err != nil {
		return nil, err
	}
	values := playbackVendorParams().Set("device_id", device.deviceID).Set("iid", device.installID).Set("cdid", uuid())
	signer, err := sign.NewPlaybackSigner(device.deviceID, sign.PlaybackVersion)
	if err != nil {
		return nil, err
	}
	s.profile = &playbackIdentity{deviceID: device.deviceID, values: values, cookie: device.cookie, signer: signer}
	return s.profile, nil
}

func playbackVendorParams() sign.Params {
	return sign.Params{
		{K: "iid", V: "1905892595382586"}, {K: "device_id", V: "1905892595378490"}, {K: "ac", V: "wifi"},
		{K: "channel", V: "update_64"}, {K: "aid", V: "8662"}, {K: "app_name", V: "novelread"},
		{K: "version_code", V: "71332"}, {K: "version_name", V: "7.1.3.32"}, {K: "device_platform", V: "android"},
		{K: "os", V: "android"}, {K: "ssmix", V: "a"}, {K: "device_type", V: "25053RT47C"},
		{K: "device_brand", V: "Redmi"}, {K: "language", V: "zh"}, {K: "os_api", V: "36"}, {K: "os_version", V: "16"},
		{K: "manifest_version_code", V: "71332"}, {K: "resolution", V: "1280*2772"}, {K: "dpi", V: "520"},
		{K: "update_version_code", V: "71332"}, {K: "host_abi", V: "arm64-v8a"}, {K: "dragon_device_type", V: "phone"},
		{K: "pv_player", V: "71332"}, {K: "compliance_status", V: "0"}, {K: "need_personal_recommend", V: "1"},
		{K: "player_so_load", V: "1"}, {K: "is_android_pad_screen", V: "0"},
	}
}

type registeredPlaybackDevice struct {
	deviceID, installID, cookie string
}

func registerPlaybackDevice() (registeredPlaybackDevice, error) {
	profile := playbackVendorParams()
	values := make(map[string]string, len(profile))
	for _, item := range profile {
		values[item.K] = item.V
	}
	now := time.Now().UnixMilli()
	header := map[string]any{
		"display_name": "红果免费短剧", "update_version_code": 73932, "manifest_version_code": 73932,
		"app_version_minor": "7.3.9.32", "aid": 8662, "channel": values["channel"], "package": "com.phoenix.read",
		"app_version": "7.3.9.32", "version_code": 73932, "sdk_version": "3.7.0-rc.34-fanqie-xiaoshuo",
		"sdk_target_version": 29, "git_hash": "3a88f4e", "os": "Android", "os_version": values["os_version"],
		"os_api": 36, "device_model": values["device_type"], "device_brand": values["device_brand"],
		"device_manufacturer": values["device_brand"], "cpu_abi": values["host_abi"], "release_build": "b5e187f",
		"density_dpi": 520, "display_density": "mdpi", "resolution": strings.ReplaceAll(values["resolution"], "*", "x"),
		"language": "zh", "timezone": 8, "access": "wifi", "not_request_sender": 0, "carrier": "CHINA MOBILE",
		"mcc_mnc": "46000", "rom": "eng.root.20231116.160340", "rom_version": "W528JS release-keys",
		"cdid": uuid(), "sig_hash": "56a962410c494bbaf0b58dba20cae56f", "openudid": strings.ReplaceAll(uuid(), "-", "")[:16],
		"clientudid": uuid(), "ipv6_list": []any{}, "region": "CN", "tz_name": "Asia/Shanghai", "tz_offset": 28800,
		"sim_region": "cn", "sim_serial_number": []any{}, "oaid_may_support": false, "req_id": uuid(),
		"device_platform": "android", "custom": map[string]any{"account_region_src": "fallback", "host_bit": 64,
			"account_region": "cn", "dragon_device_type": values["dragon_device_type"]},
		"pre_installed_channel": "", "apk_first_install_time": now, "is_system_app": 0, "sdk_flavor": "china", "guest_mode": 0,
	}
	payload, err := json.Marshal(map[string]any{"magic_tag": "ss_app_log", "header": header, "_gen_time": now})
	if err != nil {
		return registeredPlaybackDevice{}, err
	}
	var compressed bytes.Buffer
	zw := gzip.NewWriter(&compressed)
	if _, err = zw.Write(payload); err != nil {
		return registeredPlaybackDevice{}, err
	}
	if err = zw.Close(); err != nil {
		return registeredPlaybackDevice{}, err
	}
	body, err := ttEncrypt(compressed.Bytes())
	if err != nil {
		return registeredPlaybackDevice{}, err
	}
	headers := [][2]string{
		{"User-Agent", sign.AppUA}, {"Content-Type", "application/octet-stream;tt-data=a"}, {"log-encode-type", "gzip"},
		{"x-ss-dp", "8662"}, {"x-ss-req-ticket", fmt.Sprint(now)}, {"x-vc-bdturing-sdk-version", "4.0.3.cn"},
		{"lc", "101"}, {"sdk-version", "2"}, {"passport-sdk-version", "5051452"},
		{"x-neptune", "-8|50:51:59:30:40:47:49:39"}, {"x-ss-stub", strings.ToUpper(sign.Hex(sign.MD5(body)))},
	}
	response, err := doRequest("POST", deviceRegisterURL, headers, body)
	if err != nil {
		return registeredPlaybackDevice{}, err
	}
	if response.Status != 200 {
		return registeredPlaybackDevice{}, fmt.Errorf("匿名播放设备注册 HTTP %d", response.Status)
	}
	jsonBody, err := response.JSON()
	if err != nil {
		return registeredPlaybackDevice{}, errors.New("匿名播放设备注册响应格式异常")
	}
	deviceID := firstStr(jsonBody, "device_id_str", "device_id")
	installID := firstStr(jsonBody, "install_id_str", "install_id")
	if deviceID == "" || installID == "" || deviceID == "0" || installID == "0" {
		return registeredPlaybackDevice{}, errors.New("匿名播放设备注册被拒绝")
	}
	if !validPlaybackID(deviceID) || !validPlaybackID(installID) {
		return registeredPlaybackDevice{}, errors.New("匿名播放设备注册身份格式异常")
	}
	cookie := collectPlaybackCookies(response.Cookies)
	return registeredPlaybackDevice{deviceID: deviceID, installID: installID, cookie: cookie}, nil
}

func validPlaybackID(value string) bool {
	if len(value) == 0 || len(value) > 30 || value[0] < '1' || value[0] > '9' {
		return false
	}
	for i := 1; i < len(value); i++ {
		if value[i] < '0' || value[i] > '9' {
			return false
		}
	}
	return true
}

func newPlaybackSessionForTest(deviceID, installID, cdid, cookie string) *PlaybackDeviceSession {
	values := playbackVendorParams().Set("device_id", deviceID).Set("iid", installID).Set("cdid", cdid)
	signer, _ := sign.NewPlaybackSigner(deviceID, sign.PlaybackVersion)
	return &PlaybackDeviceSession{profile: &playbackIdentity{deviceID: deviceID, values: values, cookie: cookie, signer: signer}}
}

func defaultPlaybackSession() *PlaybackDeviceSession { return sharedPlaybackDeviceSession }
