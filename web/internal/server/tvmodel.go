package server

// TV 备用取流通道：电视端本地签名取流失败时（红果服务端调整了签名校验），改从这里取播放模型。
// 服务端的签名可以独立修复并立即生效，电视不用等发版。只返回播放模型（几十 KB 的 JSON），不代理视频。
// 鉴权：电视端用共享密钥对 vid|ts 做 HMAC-SHA256；密钥来自环境变量 LVGUO_TV_KEY，未配置则接口关闭。

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"os"
	"strconv"
	"time"

	"lvguo/internal/hgplay"
)

func (s *Server) tvModel(w http.ResponseWriter, r *http.Request) {
	key := os.Getenv("LVGUO_TV_KEY")
	if key == "" {
		http.NotFound(w, r)
		return
	}
	if !s.limiter.allow("tvmodel:"+clientIP(r), 60, 30) {
		writeJSON(w, http.StatusTooManyRequests, map[string]any{"error": "rate"})
		return
	}
	q := r.URL.Query()
	vid, ts, sig := q.Get("vid"), q.Get("ts"), q.Get("sig")
	sec, _ := strconv.ParseInt(ts, 10, 64)
	if !digits.MatchString(vid) || sec == 0 || abs64(time.Now().Unix()-sec) > 300 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "bad request"})
		return
	}
	m := hmac.New(sha256.New, []byte(key))
	m.Write([]byte(vid + "|" + ts))
	if !hmac.Equal([]byte(hex.EncodeToString(m.Sum(nil))), []byte(sig)) {
		writeJSON(w, http.StatusForbidden, map[string]any{"error": "forbidden"})
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 25*time.Second)
	defer cancel()
	envelope, err := hgplay.PlayMobileV5(ctx, vid)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]any{"error": err.Error()})
		return
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(envelope)
}

func abs64(v int64) int64 {
	if v < 0 {
		return -v
	}
	return v
}
