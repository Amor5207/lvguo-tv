package server

import (
	"net/http"
	"os"
)

func (s *Server) serveMedia(w http.ResponseWriter, r *http.Request) {
	ss := s.session(w, r)
	if !s.limiter.allow("media:"+clientIP(r), 180, 120) {
		http.Error(w, "请求太频繁", http.StatusTooManyRequests)
		return
	}
	if p := os.Getenv("LVGUO_DEV_MEDIA"); s.dev && p != "" { // 界面调试：用本地样片代替真实视频流
		http.ServeFile(w, r, p)
		return
	}
	// 默认不登录即可播放：取流走匿名设备签名（internal/hgplay），不需要账号。
	_ = ss
	s.media.Serve(s.content, s.player, w, r) // 取流用匿名身份，不带账号
}
