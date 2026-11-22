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
	// 登录用户优先用自己的设备与 Cookie 请求播放模型；游客和失败回退仍走匿名身份。
	s.media.Serve(s.content, s.playerFor(ss), w, r)
}
