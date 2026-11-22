package lvguoplay

// 本文件为从 aston314/hongguo-tv 搬运的签名/设备注册代码补齐最小依赖：
// 端点常量、设备/会话结构、若干 helper，以及把该项目的播放诊断简化为无副作用的 stub。
// account/登录相关代码不搬入——本包只负责「注册匿名设备 + 取 video_model 播放地址」。

import (
	crand "crypto/rand"
	"encoding/binary"
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
)

const (
	passportAPI           = "https://api5-normal-lq.fqnovel.com"
	miniReelAPIBase       = "https://api5-normal-lq.fqnovel.com"
	miniReelAPIPath       = "/novel/player/multi_video_model/v1/"
	miniReelDeviceTimeout = 8 * time.Second
	defaultHongguoUA      = "com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"
)

var errPassportEmptyResponse = errors.New("上游响应为空")

type miniReelDevice struct {
	deviceID  string
	installID string
	cdid      string
	cookie    string
	openUDID  string
}

type accountSession struct {
	DeviceID   string            `json:"device_id"`
	InstallID  string            `json:"install_id"`
	Cookies    map[string]string `json:"cookies"`
	Token      string            `json:"token,omitempty"`
	Device     *passportDevice   `json:"device,omitempty"`
	Registered bool              `json:"registered,omitempty"`
}

type passportClient struct {
	uid        string
	http       *http.Client
	base       string
	deviceBase string
	session    accountSession
}

func cloneAccountSession(session accountSession) accountSession {
	c := session
	c.Cookies = map[string]string{}
	for k, v := range session.Cookies {
		c.Cookies[k] = v
	}
	if session.Device != nil {
		d := *session.Device
		c.Device = &d
	}
	return c
}

func newPassportClient(session accountSession) *passportClient {
	c := cloneAccountSession(session)
	if c.Registered {
		c.Cookies["install_id"] = c.InstallID
	}
	return &passportClient{http: &http.Client{Timeout: 25 * time.Second, CheckRedirect: func(r *http.Request, via []*http.Request) error { return http.ErrUseLastResponse }}, base: passportAPI, deviceBase: passportDeviceAPI, session: c}
}

func passportQuery(s accountSession) url.Values {
	q := url.Values{"ac": {"wifi"}, "aid": {"8662"}, "app_name": {"novelread"}, "channel": {"update_64"}, "device_brand": {"Redmi"}, "device_id": {s.DeviceID}, "device_platform": {"android"}, "device_type": {"25053RT47C"}, "dpi": {"520"}, "iid": {s.InstallID}, "language": {"zh"}, "manifest_version_code": {"73532"}, "os": {"android"}, "os_api": {"36"}, "os_version": {"16"}, "resolution": {"1280*2772"}, "ssmix": {"a"}, "update_version_code": {"73532"}, "version_code": {"73532"}, "version_name": {"7.3.5.32"}}
	if s.Device != nil {
		q.Set("cdid", s.Device.CDID)
		q.Set("openudid", s.Device.OpenUDID)
	}
	return q
}

// ---- helpers（原样搬运）----

func asMap(value any) (map[string]any, bool) {
	result, ok := value.(map[string]any)
	return result, ok
}

func asString(value any) string {
	switch typed := value.(type) {
	case string:
		return strings.TrimSpace(typed)
	case json.Number:
		return typed.String()
	case float64:
		return strconv.FormatInt(int64(typed), 10)
	case int:
		return strconv.Itoa(typed)
	default:
		return ""
	}
}

func firstNonEmpty(values ...any) string {
	for _, value := range values {
		if text := asString(value); text != "" {
			return text
		}
	}
	return ""
}

var seriesIDPattern = regexp.MustCompile(`^[0-9]{1,32}$`)

func validID(value string) bool { return seriesIDPattern.MatchString(value) }

func miniReelPlaybackCookieHeader(values map[string]string) string {
	if len(values) == 0 {
		return ""
	}
	allowed := map[string]bool{"install_id": true, "ttreq": true, "store-region": true, "store-region-src": true}
	keys := make([]string, 0, len(values))
	for key, value := range values {
		if allowed[key] && strings.TrimSpace(value) != "" {
			keys = append(keys, key)
		}
	}
	sort.Strings(keys)
	parts := make([]string, 0, len(keys))
	for _, key := range keys {
		parts = append(parts, key+"="+values[key])
	}
	return strings.Join(parts, "; ")
}

func miniReelRandomID() string {
	buf := make([]byte, 8)
	if _, err := crand.Read(buf); err != nil {
		return strconv.FormatInt(time.Now().UnixNano(), 10)
	}
	return strconv.FormatUint(binary.BigEndian.Uint64(buf)%8000000000000000000+1000000000000000000, 10)
}

// ---- 播放诊断：简化为无副作用 stub（仅保留签名所需字段）----

type playbackPreparationDiagnostic struct {
	Code          string
	Stage         string
	HTTPStatus    *int
	NetworkReason string
	ResponseBytes *int
	BusinessCode  string
	Attempt       int
}

func finishPlaybackPreparation(err error, _ playbackPreparationDiagnostic, _ time.Time) error {
	return err
}

func playbackNetworkReason(_ error) string { return "" }
