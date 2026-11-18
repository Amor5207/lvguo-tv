// 绿果TV：红果短剧第三方客户端网页版服务端。
package main

import (
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"time"

	"lvguo/internal/hgplay"
	"lvguo/internal/server"
	"lvguo/ui"
)

var version = "dev"

func main() {
	addr := flag.String("addr", "127.0.0.1:8780", "监听地址")
	data := flag.String("data", defaultData(), "数据目录（设备身份、登录会话）")
	open := flag.Bool("open", defaultOpen(), "启动后用浏览器应用窗口打开")
	flag.Parse()

	if *open && alreadyRunning(*addr) { // 已经有一个在运行：只把窗口再打开一次
		openApp("http://"+*addr+"/pc", *data)
		return
	}
	hgplay.ConfigurePlayback(*data)
	srv, err := server.New(server.Config{DataDir: *data, UI: ui.FS(), Version: version})
	if err != nil {
		log.Fatal(err)
	}
	ln, err := net.Listen("tcp", *addr)
	if err != nil && *open { // 桌面版端口被占用时换一个
		ln, err = net.Listen("tcp", "127.0.0.1:0")
	}
	if err != nil {
		log.Fatal(err)
	}
	url := "http://" + ln.Addr().String() + "/pc"
	fmt.Println("绿果TV", version, "已启动：", url)
	if *open {
		go func() { time.Sleep(300 * time.Millisecond); openApp(url, *data) }()
		srv.WatchWindow(func() { os.Exit(0) })
	}
	hs := &http.Server{Handler: srv.Handler(), ReadHeaderTimeout: 10 * time.Second, IdleTimeout: 2 * time.Minute}
	log.Fatal(hs.Serve(ln))
}

func defaultData() string {
	if d, err := os.UserConfigDir(); err == nil && os.Getenv("LVGUO_DATA") == "" {
		return filepath.Join(d, "LvguoTV")
	}
	if d := os.Getenv("LVGUO_DATA"); d != "" {
		return d
	}
	return "./data"
}

// alreadyRunning 判断 addr 上是不是已经有绿果TV 在运行。
func alreadyRunning(addr string) bool {
	c := &http.Client{Timeout: 800 * time.Millisecond}
	res, err := c.Get("http://" + addr + "/healthz")
	if err != nil {
		return false
	}
	defer res.Body.Close()
	b := make([]byte, 16)
	n, _ := res.Body.Read(b)
	return string(b[:n]) == "lvguo-tv"
}
