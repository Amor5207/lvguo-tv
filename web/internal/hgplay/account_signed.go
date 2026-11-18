package hgplay

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"time"
)

// AccountDevice 是发起账号请求的设备身份（与登录会话绑定的那台已注册设备）。
type AccountDevice struct {
	DeviceID, InstallID, CDID, OpenUDID string
	ClientUDID                          string // 仅设备档案用（心跳要带同一组值），请求里不发
}

// AccountPost 用完整签名（Gorgon / Helios / Medusa，同播放链路）向账号类接口 POST JSON。
// 旧的手写签名在写接口上会被服务端静默丢弃（返回成功但不落库），写操作必须走这里。
func AccountPost(ctx context.Context, host, path string, dev AccountDevice, cookie string, body any) (map[string]any, error) {
	raw, err := json.Marshal(body)
	if err != nil {
		return nil, err
	}
	q := passportQuery(accountSession{DeviceID: dev.DeviceID, InstallID: dev.InstallID})
	if dev.CDID != "" {
		q.Set("cdid", dev.CDID)
	}
	if dev.OpenUDID != "" {
		q.Set("openudid", dev.OpenUDID)
	}
	stamp := time.Now().UnixMilli()
	query, headers, err := signPassportRequest(q, raw, stamp, dev.DeviceID)
	if err != nil {
		return nil, errors.New("请求签名失败")
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, host+path+"?"+query, bytes.NewReader(raw))
	if err != nil {
		return nil, err
	}
	req.Header = headers
	req.Header.Set("User-Agent", miniReelSingleUA)
	req.Header.Set("Content-Type", "application/json; charset=UTF-8")
	req.Header.Set("Accept", "application/json; charset=utf-8")
	req.Header.Set("Passport-Sdk-Version", "50561")
	req.Header.Set("X-SS-DP", "8662")
	req.Header.Set("Cookie", cookie)
	nonce, _ := protocolRandom()
	req.Header.Set("X-Reading-Request", fmt.Sprintf("%d-%d", stamp, nonce&0x7fffffff))
	client := &http.Client{Timeout: 15 * time.Second, CheckRedirect: func(r *http.Request, via []*http.Request) error { return http.ErrUseLastResponse }}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		return nil, err
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	if len(bytes.TrimSpace(data)) == 0 {
		return nil, errors.New("空响应")
	}
	var out map[string]any
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.UseNumber()
	if dec.Decode(&out) != nil || out == nil {
		return nil, errors.New("响应不是合法 JSON")
	}
	return out, nil
}

var _ = url.Values{}
