// Package lvguo 是红果接口的 Go 实现：设备注册、内容接口、账号接口、扫码登录、播放地址。
// 协议细节移植自 Android 端 net/ 目录，行为保持一致。
package lvguo

import (
	"bytes"
	"compress/gzip"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/md5"
	"crypto/rand"
	"crypto/sha512"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	mrand "math/rand"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"lvguo/internal/sign"
)

const (
	ContentHost  = "api5-normal-sinfonlinea.fqnovel.com"
	ContentHost2 = "api3-normal-sinfonlinea.fqnovel.com"
	AppHost      = "https://api5-normal-sinfonlinea.fqnovel.com"
	// DeviceLevelH265 >=4 时全部档位下发标准 H.265；否则低档位是字节私有 ByteVC2，浏览器解不了。
	DeviceLevelH265 = 4
	ContentUA       = "com.phoenix.read/71932 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3;tt-ok/3.12.13.20)"
	versionName     = "7.1.9.32"
)

var httpc = &http.Client{
	Timeout:       25 * time.Second,
	CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	Transport: &http.Transport{
		Proxy:               http.ProxyFromEnvironment,
		MaxIdleConns:        128,
		MaxIdleConnsPerHost: 32,
		IdleConnTimeout:     90 * time.Second,
	},
}

// Resp 是未经解析的响应，保留 Set-Cookie 与 Location。
type Resp struct {
	Status   int
	Body     []byte
	Cookies  []string
	Location string
}

func (r *Resp) JSON() (map[string]any, error) {
	if len(bytes.TrimSpace(r.Body)) == 0 {
		return nil, errEmpty
	}
	var m map[string]any
	dec := json.NewDecoder(bytes.NewReader(r.Body))
	dec.UseNumber()
	if err := dec.Decode(&m); err != nil {
		return nil, err
	}
	return m, nil
}

var errEmpty = errors.New("empty body")

func doRequest(method, u string, headers [][2]string, body []byte) (*Resp, error) {
	return doRequestContext(context.Background(), method, u, headers, body)
}

func doRequestContext(ctx context.Context, method, u string, headers [][2]string, body []byte) (*Resp, error) {
	var rd io.Reader
	if body != nil {
		rd = bytes.NewReader(body)
	}
	req, err := http.NewRequestWithContext(ctx, method, u, rd)
	if err != nil {
		return nil, err
	}
	for _, h := range headers {
		if strings.EqualFold(h[0], "Host") {
			req.Host = h[1]
			continue
		}
		req.Header.Set(h[0], h[1])
	}
	res, err := httpc.Do(req)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	data, err := io.ReadAll(io.LimitReader(res.Body, 32<<20))
	if err != nil {
		return nil, err
	}
	return &Resp{Status: res.StatusCode, Body: data, Cookies: res.Header.Values("Set-Cookie"), Location: res.Header.Get("Location")}, nil
}

// ------------------------------------------------------------ 设备身份

// Device 是服务端注册过的设备对。红果的内容与账号接口只认同一次 device_register 得到的 device_id + install_id。
type Device struct {
	ID, IID string
	// CDID 必须与注册时发给 device_register 的 cdid 完全一致；查询里的 cdid 用它，不能用 device_id。
	CDID string
	// OpenUDID / ClientUDID 同样在注册时生成并持久化，查询时必须带上同一组值。
	OpenUDID, ClientUDID string
	// Region 是红果注册时按出口 IP 给设备打的「商店地区」（Set-Cookie store-region），取流只认大陆
	Region string
	// EGDI 是 device_register 返回的 klink_egdi 标识，供完整公共参数拼接使用。
	EGDI string
}

type DeviceStore struct {
	path      string
	mu        sync.Mutex
	dev       Device
	activated bool
	last      time.Time // 上次成功注册或心跳的时间，对应 APK 的 last_config_time
	fail      time.Time
}

// heartbeatInterval 与红果 APK 的 DeviceRegisterThread 一致：有效身份每 6 小时用同一对 ID 心跳一次。
const heartbeatInterval = 6 * time.Hour

func NewDeviceStore(dir string) *DeviceStore {
	d := &DeviceStore{path: filepath.Join(dir, "device.json")}
	b, err := os.ReadFile(d.path)
	if err != nil {
		return d
	}
	var m map[string]any
	if json.Unmarshal(b, &m) != nil {
		return d
	}
	str := func(k string) string { v, _ := m[k].(string); return v }
	if str("id") == "" || str("iid") == "" || str("id") == str("iid") || str("cdid") == "" || str("openudid") == "" || str("clientudid") == "" {
		return d
	}
	d.dev = Device{ID: str("id"), IID: str("iid"), CDID: str("cdid"), OpenUDID: str("openudid"), ClientUDID: str("clientudid"), Region: str("region"), EGDI: str("egdi")}
	if ts, ok := m["last_config_time"].(float64); ok && ts > 0 {
		d.last = time.Unix(int64(ts), 0)
	} else if st, err := os.Stat(d.path); err == nil {
		d.last = st.ModTime()
	}
	return d
}

// Get 返回设备对。一个设备只注册一次：之后只做 6 小时心跳，绝不为同一台设备再次新注册。
// 只有本地完全没有设备对时才注册。
func (d *DeviceStore) Get() (Device, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.dev.ID == "" {
		return d.registerLocked()
	}
	if !d.activated {
		d.activated = true
		go activateRegisteredDevice(d.dev.ID, d.dev.IID)
	}
	if (d.dev.EGDI == "" || time.Since(d.last) >= heartbeatInterval) && (d.fail.IsZero() || time.Since(d.fail) >= 30*time.Minute) {
		d.heartbeatLocked() // 心跳失败不影响当前设备对的使用，30 分钟后再试
	}
	return d.dev, nil
}

// Refresh 只做心跳（同一对 ID），6 小时内最多一次；不会重新注册一台新设备。
func (d *DeviceStore) Refresh() bool {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.dev.ID == "" || time.Since(d.last) < heartbeatInterval {
		return false
	}
	return d.heartbeatLocked()
}

func (d *DeviceStore) heartbeatLocked() bool {
	prev := d.dev
	dev, err := registerDevice(&prev)
	if err != nil {
		d.fail = time.Now()
		return false
	}
	if dev.ID != prev.ID || dev.IID != prev.IID {
		// 心跳返回了不同的 ID：保留原设备对，避免 ID 与本地保存的 cdid 等失配。
		d.fail = time.Now()
		return false
	}
	if dev.EGDI != "" {
		d.dev.EGDI = dev.EGDI
	}
	if dev.Region != "" {
		d.dev.Region = dev.Region
	}
	d.fail = time.Time{}
	d.last = time.Now()
	d.save()
	return true
}

// Adopt 把一台已注册的设备定为本会话的设备（手机号登录时会话与登录所用设备绑定，之后的账号写请求必须沿用它）。
func (d *DeviceStore) Adopt(dev Device) {
	if dev.ID == "" || dev.IID == "" {
		return
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	if dev.Region == "" {
		dev.Region = d.dev.Region
	}
	d.dev, d.last, d.fail = dev, time.Now(), time.Time{}
	d.save()
}

func (d *DeviceStore) save() {
	b, _ := json.Marshal(map[string]any{"id": d.dev.ID, "iid": d.dev.IID, "cdid": d.dev.CDID, "openudid": d.dev.OpenUDID,
		"clientudid": d.dev.ClientUDID, "region": d.dev.Region, "egdi": d.dev.EGDI, "last_config_time": d.last.Unix()})
	_ = os.MkdirAll(filepath.Dir(d.path), 0o755)
	_ = os.WriteFile(d.path, b, 0o600)
}

// registerLocked 只在本地没有设备对时调用（首次）。
func (d *DeviceStore) registerLocked() (Device, error) {
	if !d.fail.IsZero() && time.Since(d.fail) < 30*time.Second {
		return Device{}, errors.New("设备注册暂不可用，请稍后重试")
	}
	dev, err := registerDevice(nil)
	if err != nil {
		d.fail = time.Now()
		return Device{}, err
	}
	d.fail = time.Time{}
	d.dev = dev
	d.last = time.Now()
	d.save()
	return dev, nil
}

const deviceRegisterURL = "https://log5-applog-lf.fqnovel.com/service/2/device_register/" +
	"?tt_data=a&cronet_version=8d40f833_2026-03-03&ttnet_version=4.2.243.31-douyin&use_store_region_cookie=1"

var kdfSalt = sign.Unhex("4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524" +
	"f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58")

func uuid() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	b[6] = b[6]&0x0f | 0x40
	b[8] = b[8]&0x3f | 0x80
	h := hex.EncodeToString(b)
	return h[:8] + "-" + h[8:12] + "-" + h[12:16] + "-" + h[16:20] + "-" + h[20:]
}

// registerDevice 发送 device_register。prev 为 nil 时是首次注册；否则是心跳：沿用同一对 ID 与 install-scoped 标识，
// 并像 APK 一样把 device_id / install_id 放进 header。
func registerDevice(prev *Device) (Device, error) {
	now := time.Now().UnixMilli()
	cdid, openudid, clientudid := uuid(), strings.ReplaceAll(uuid(), "-", "")[:16], uuid()
	if prev != nil {
		cdid, openudid, clientudid = prev.CDID, prev.OpenUDID, prev.ClientUDID
	}
	header := map[string]any{
		"display_name": "红果免费短剧", "update_version_code": 71932, "manifest_version_code": 71932,
		"app_version_minor": "", "aid": 8662, "channel": "update_64", "package": "com.phoenix.read",
		"app_version": "7.1.9.32", "version_code": 71932, "sdk_version": "3.7.0-rc.34-fanqie-xiaoshuo",
		"sdk_target_version": 29, "git_hash": "3a88f4e", "os": "Android", "os_version": "16", "os_api": 36,
		"device_model": "25053RT47C", "device_brand": "Redmi", "device_manufacturer": "Xiaomi", "cpu_abi": "arm64-v8a",
		"release_build": "b5e187f", "density_dpi": 520, "display_density": "mdpi", "resolution": "1280x2772",
		"language": "zh", "timezone": 8, "access": "wifi", "not_request_sender": 0, "carrier": "CHINA MOBILE",
		"mcc_mnc": "46000", "rom": "eng.root.20231116.160340", "rom_version": "W528JS release-keys",
		"cdid": cdid, "sig_hash": "56a962410c494bbaf0b58dba20cae56f",
		"openudid": openudid, "clientudid": clientudid, "ipv6_list": []any{},
		"region": "CN", "tz_name": "Asia/Shanghai", "tz_offset": 28800, "sim_region": "cn",
		"sim_serial_number": []any{}, "oaid_may_support": false, "req_id": uuid(), "device_platform": "android",
		"custom":                map[string]any{"account_region_src": "fallback", "host_bit": 64, "account_region": "cn", "dragon_device_type": "phone"},
		"pre_installed_channel": "", "apk_first_install_time": now, "is_system_app": 0, "sdk_flavor": "china", "guest_mode": 0,
	}
	if prev != nil {
		header["device_id"], header["install_id"] = prev.ID, prev.IID
	}
	payload, _ := json.Marshal(map[string]any{"magic_tag": "ss_app_log", "header": header, "_gen_time": now})
	var gz bytes.Buffer
	w := gzip.NewWriter(&gz)
	_, _ = w.Write(payload)
	_ = w.Close()
	body, err := ttEncrypt(gz.Bytes())
	if err != nil {
		return Device{}, err
	}
	h := [][2]string{
		{"User-Agent", sign.AppUA}, {"Content-Type", "application/octet-stream;tt-data=a"}, {"log-encode-type", "gzip"},
		{"x-ss-dp", "8662"}, {"x-ss-req-ticket", strconv.FormatInt(now, 10)}, {"x-vc-bdturing-sdk-version", "4.0.3.cn"},
		{"lc", "101"}, {"sdk-version", "2"}, {"passport-sdk-version", "5051452"},
		{"x-neptune", "-8|50:51:59:30:40:47:49:39"}, {"x-ss-stub", strings.ToUpper(sign.Hex(sign.MD5(body)))},
	}
	r, err := doRequest("POST", deviceRegisterURL, h, body)
	if err != nil {
		return Device{}, err
	}
	m, err := r.JSON()
	if err != nil {
		return Device{}, errors.New("设备注册被拒绝")
	}
	dev := firstStr(m, "device_id_str", "device_id")
	iid := firstStr(m, "install_id_str", "install_id")
	if dev == "" || iid == "" || dev == "0" {
		return Device{}, errors.New("设备注册被拒绝")
	}
	region := ""
	for _, c := range r.Cookies {
		if strings.HasPrefix(c, "store-region=") {
			region = strings.TrimPrefix(strings.SplitN(c, ";", 2)[0], "store-region=")
		}
	}
	egdi := firstStr(m, "klink_egdi", "egdi")
	activateRegisteredDevice(dev, iid)
	return Device{ID: dev, IID: iid, CDID: cdid, OpenUDID: openudid, ClientUDID: clientudid, Region: region, EGDI: egdi}, nil
}

func activateRegisteredDevice(did, iid string) {
	if did == "" || iid == "" {
		return
	}
	u := "https://log5-applog-lf.fqnovel.com/service/2/app_alert_check/?aid=8662&app_name=novelread&channel=update_64" +
		"&device_id=" + did + "&iid=" + iid +
		"&device_platform=android&os=android&version_code=71932&version_name=7.1.9.32" +
		"&_rticket=" + strconv.FormatInt(time.Now().UnixMilli(), 10)
	_, _ = doRequest("GET", u, [][2]string{{"User-Agent", sign.AppUA}}, nil)
}

var playbackCookieNames = []string{"install_id", "ttreq", "store-region", "store-region-src"}

func collectPlaybackCookies(headers []string) string {
	values := make(map[string]string, len(playbackCookieNames))
	order := make([]string, 0, len(playbackCookieNames))
	for _, header := range headers {
		pair := strings.TrimSpace(strings.SplitN(header, ";", 2)[0])
		name, value, ok := strings.Cut(pair, "=")
		if !ok {
			continue
		}
		for _, allowed := range playbackCookieNames {
			if name == allowed {
				if _, exists := values[name]; !exists {
					order = append(order, name)
				}
				values[name] = value
				break
			}
		}
	}
	var out []string
	for _, name := range order {
		if value, ok := values[name]; ok {
			out = append(out, name+"="+value)
		}
	}
	return strings.Join(out, "; ")
}

func ttEncrypt(data []byte) ([]byte, error) {
	rnd := make([]byte, 32)
	_, _ = rand.Read(rnd)
	s1 := sha512.Sum512(rnd)
	derived := sha512.Sum512(append(s1[:], kdfSalt...))
	dh := sha512.Sum512(data)
	body := append(dh[:], data...)
	pad := 16 - len(body)%16
	body = append(body, bytes.Repeat([]byte{byte(pad)}, pad)...)
	blk, err := aes.NewCipher(derived[:16])
	if err != nil {
		return nil, err
	}
	out := make([]byte, len(body))
	cipher.NewCBCEncrypter(blk, derived[16:32]).CryptBlocks(out, body)
	return append(append([]byte{0x74, 0x63, 0x05, 0x10, 0x00, 0x00}, rnd...), out...), nil
}

// ------------------------------------------------------------ 内容接口（HgApi）

type Client struct {
	Devices *DeviceStore
}

// Call 调用内容接口。cookie 为空表示匿名。首选主机失败/空包时换备用主机重试。
func (c *Client) Call(cookie, path string, body map[string]any, extra sign.Params) (map[string]any, error) {
	var last map[string]any
	var lastErr error
	for attempt := 0; attempt < 2; attempt++ {
		host := ContentHost
		if attempt == 1 {
			host = ContentHost2
		}
		m, status, err := c.callOnce(cookie, host, path, body, extra)
		if err != nil {
			lastErr = err
			continue
		}
		last = m
		if _, empty := m["_empty"]; !empty && status < 500 && num(m["code"]) != 101116 {
			return m, nil
		}
	}
	if last != nil {
		return last, nil
	}
	if lastErr == nil {
		lastErr = errors.New("网络请求未完成")
	}
	return nil, lastErr
}

func (c *Client) query() (sign.Params, error) {
	dev, err := c.Devices.Get()
	if err != nil {
		return nil, err
	}
	return sign.Params{
		{"aid", "8662"}, {"app_id", "8662"}, {"device_platform", "android"}, {"platform", "android"},
		{"version_code", "71932"}, {"update_version_code", "71932"}, {"manifest_version_code", "71932"},
		{"version_name", "7.1.9.32"}, {"device_type", "25053RT47C"}, {"device_brand", "Redmi"},
		{"cdid", dev.CDID}, {"openudid", dev.OpenUDID}, {"clientudid", dev.ClientUDID}, {"device_id", dev.ID}, {"iid", dev.IID}, {"os", "android"}, {"os_version", "16"},
		{"os_api", "36"}, {"language", "zh"}, {"ac", "wifi"}, {"ssmix", "a"},
	}, nil
}

func (c *Client) callOnce(cookie, host, path string, body map[string]any, extra sign.Params) (map[string]any, int, error) {
	q, err := c.query()
	if err != nil {
		return nil, 0, err
	}
	for _, e := range extra {
		q = q.Set(e.K, e.V)
	}
	now := strconv.FormatInt(time.Now().UnixMilli(), 10)
	q = q.Set("_rticket", now)
	u := "https://" + host + path + "?" + sign.EncodeParams(q)
	var data []byte
	method := "GET"
	if body != nil {
		data, _ = json.Marshal(body)
		method = "POST"
	}
	h := [][2]string{{"User-Agent", ContentUA}, {"X-Xs-From-Web", "0"}, {"x-ss-req-ticket", now}, {"sdk-version", "2"}}
	if cookie != "" {
		h = append(h, [2]string{"Cookie", cookie})
	}
	stub := md5.Sum(data)
	h = append(h, [2]string{"X-SS-STUB", strings.ToUpper(hex.EncodeToString(stub[:]))})
	if data != nil {
		h = append(h, [2]string{"Content-Type", "application/json; charset=utf-8"})
	}
	r, err := doRequest(method, u, h, data)
	if err != nil {
		return nil, 0, err
	}
	m, jerr := r.JSON()
	if jerr != nil {
		return map[string]any{"_empty": true, "_http_status": float64(r.Status)}, r.Status, nil
	}
	if r.Status >= 400 {
		m["_http_status"] = float64(r.Status)
	}
	return m, r.Status, nil
}

// CallRetry 空包（被静默拒绝）时重注册设备再试一次。
func (c *Client) CallRetry(cookie, path string, body map[string]any, extra sign.Params) (map[string]any, error) {
	var last map[string]any
	for attempt := 0; attempt < 3; attempt++ {
		if attempt > 0 {
			time.Sleep(time.Duration(700*attempt) * time.Millisecond)
		}
		m, err := c.Call(cookie, path, body, extra)
		if err != nil {
			return nil, err
		}
		last = m
		if _, empty := m["_empty"]; !empty && len(m) > 0 {
			return m, nil
		}
		if num(m["_http_status"]) == 200 || m["_http_status"] == nil {
			if c.Devices.Refresh() {
				continue
			}
			break
		}
	}
	st := -1
	if last != nil {
		st = int(num(last["_http_status"]))
	}
	if st == 200 {
		return nil, errors.New("片源服务暂时拒绝了本站的请求（服务器出口被限流），请稍后再试")
	}
	return nil, fmt.Errorf("接口无响应(HTTP %d)", st)
}

// ------------------------------------------------------------ JSON 小工具

func num(v any) float64 {
	switch x := v.(type) {
	case float64:
		return x
	case json.Number:
		f, _ := x.Float64()
		return f
	case string:
		f, _ := strconv.ParseFloat(x, 64)
		return f
	case bool:
		if x {
			return 1
		}
	}
	return 0
}
func str(v any) string {
	switch x := v.(type) {
	case string:
		return x
	case float64:
		return strconv.FormatInt(int64(x), 10)
	case bool:
		return strconv.FormatBool(x)
	case json.Number:
		return x.String()
	}
	return ""
}
func obj(v any) map[string]any { m, _ := v.(map[string]any); return m }
func arr(v any) []any          { a, _ := v.([]any); return a }
func firstStr(m map[string]any, keys ...string) string {
	for _, k := range keys {
		if s := str(m[k]); s != "" {
			return s
		}
	}
	return ""
}
func nz(a, b string) string {
	if a != "" {
		return a
	}
	return b
}

var _ = mrand.Float64
var _ = url.QueryEscape
