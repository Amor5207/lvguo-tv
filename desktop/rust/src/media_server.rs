use crate::cenc::{decrypt_mp4, spade_to_key};
use crate::upstream::content::{pick_gear, Content, Gear};
use reqwest::Client;
use std::collections::{HashMap, VecDeque};
use std::sync::Arc;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{watch, Mutex};
use url::form_urlencoded;

const MAX_CACHE_ENTRIES: usize = 6;
const MAX_CACHE_BYTES: usize = 160 * 1024 * 1024;

#[derive(Clone)]
struct CachedMedia {
    data: Arc<Vec<u8>>,
}

struct MediaCache {
    entries: HashMap<String, CachedMedia>,
    order: VecDeque<String>,
    total_bytes: usize,
    inflight: HashMap<String, watch::Sender<bool>>,
}

impl MediaCache {
    fn new() -> Self {
        Self {
            entries: HashMap::new(),
            order: VecDeque::new(),
            total_bytes: 0,
            inflight: HashMap::new(),
        }
    }

    fn get(&mut self, key: &str) -> Option<Arc<Vec<u8>>> {
        if let Some(item) = self.entries.get(key) {
            let data = item.data.clone();
            self.order.retain(|k| k != key);
            self.order.push_back(key.to_string());
            return Some(data);
        }
        None
    }

    fn put(&mut self, key: String, data: Arc<Vec<u8>>) {
        let len = data.len();
        if len > MAX_CACHE_BYTES {
            return;
        }
        if let Some(old) = self.entries.remove(&key) {
            self.total_bytes = self.total_bytes.saturating_sub(old.data.len());
            self.order.retain(|k| k != &key);
        }
        while self.entries.len() >= MAX_CACHE_ENTRIES
            || (self.total_bytes + len > MAX_CACHE_BYTES && !self.order.is_empty())
        {
            if let Some(evict_key) = self.order.pop_front() {
                if let Some(old) = self.entries.remove(&evict_key) {
                    self.total_bytes = self.total_bytes.saturating_sub(old.data.len());
                }
            } else {
                break;
            }
        }
        self.total_bytes += len;
        self.entries.insert(key.clone(), CachedMedia { data });
        self.order.push_back(key);
    }
}

pub struct MediaServer {
    base_url: String,
    stop_tx: watch::Sender<bool>,
    content: Arc<Content>,
    http: Client,
    cache: Arc<Mutex<MediaCache>>,
}

impl MediaServer {
    pub fn start(
        content: Arc<Content>,
        http: Client,
        rt: &tokio::runtime::Handle,
    ) -> Result<Arc<Self>, String> {
        let std_listener = std::net::TcpListener::bind("127.0.0.1:0")
            .map_err(|e| format!("本地媒体服务绑定失败: {e}"))?;
        std_listener
            .set_nonblocking(true)
            .map_err(|e| format!("本地媒体服务设置非阻塞失败: {e}"))?;
        let addr = std_listener
            .local_addr()
            .map_err(|e| format!("获取本地媒体端口失败: {e}"))?;

        let listener = rt
            .block_on(async move { TcpListener::from_std(std_listener) })
            .map_err(|e| format!("创建异步监听失败: {e}"))?;

        let (stop_tx, mut stop_rx) = watch::channel(false);
        let cache = Arc::new(Mutex::new(MediaCache::new()));

        let loop_content = content.clone();
        let loop_http = http.clone();
        let loop_cache = cache.clone();
        rt.spawn(async move {
            loop {
                tokio::select! {
                    _ = stop_rx.changed() => {
                        if *stop_rx.borrow() {
                            break;
                        }
                    }
                    accepted = listener.accept() => {
                        match accepted {
                            Ok((stream, _)) => {
                                let _ = stream.set_nodelay(true);
                                let content_c = loop_content.clone();
                                let http_c = loop_http.clone();
                                let cache_c = loop_cache.clone();
                                tokio::spawn(async move {
                                    let _ = handle_conn(stream, content_c, http_c, cache_c).await;
                                });
                            }
                            Err(_) => {
                                tokio::time::sleep(std::time::Duration::from_millis(15)).await;
                            }
                        }
                    }
                }
            }
        });

        Ok(Arc::new(Self {
            base_url: format!("http://{addr}"),
            stop_tx,
            content,
            http,
            cache,
        }))
    }

    /// Preloads and decrypts an episode MP4 into the RAM MediaCache in the background.
    pub fn prefetch(&self, vid: String, quality: String, rt: &tokio::runtime::Handle) {
        if vid.trim().is_empty() {
            return;
        }
        let content = self.content.clone();
        let http = self.http.clone();
        let cache = self.cache.clone();
        rt.spawn(async move {
            let _ = load_or_fetch_media(&vid, &quality, &content, &http, &cache).await;
        });
    }

    pub fn base_url(&self) -> String {
        self.base_url.clone()
    }

    pub fn media_url(&self, vid: &str, quality: &str) -> String {
        if quality.trim().is_empty() {
            format!("{}/media/{vid}.mp4", self.base_url)
        } else {
            let encoded: String = form_urlencoded::Serializer::new(String::new())
                .append_pair("q", quality.trim())
                .finish();
            format!("{}/media/{vid}.mp4?{encoded}", self.base_url)
        }
    }

    pub fn shutdown(&self) {
        let _ = self.stop_tx.send(true);
    }
}

async fn handle_conn(
    stream: TcpStream,
    content: Arc<Content>,
    http: Client,
    cache: Arc<Mutex<MediaCache>>,
) -> Result<(), String> {
    let (reader, mut writer) = stream.into_split();
    let mut buf_reader = BufReader::new(reader);

    let mut request_line = String::new();
    let n = buf_reader
        .read_line(&mut request_line)
        .await
        .map_err(|e| e.to_string())?;
    if n == 0 {
        return Ok(());
    }

    let mut range_header: Option<String> = None;
    loop {
        let mut line = String::new();
        let bytes = buf_reader
            .read_line(&mut line)
            .await
            .map_err(|e| e.to_string())?;
        if bytes == 0 || line == "\r\n" || line == "\n" {
            break;
        }
        if let Some((k, v)) = line.split_once(':') {
            if k.trim().eq_ignore_ascii_case("range") {
                range_header = Some(v.trim().to_string());
            }
        }
    }

    let parts: Vec<&str> = request_line.split_whitespace().collect();
    if parts.len() < 2 {
        write_simple_resp(&mut writer, 400, "Bad Request").await;
        return Ok(());
    }
    let method = parts[0];
    let target = parts[1];
    if method != "GET" && method != "HEAD" {
        write_simple_resp(&mut writer, 405, "Method Not Allowed").await;
        return Ok(());
    }

    let (path, query) = target.split_once('?').unwrap_or((target, ""));
    let Some(vid) = parse_media_vid(path) else {
        write_simple_resp(&mut writer, 404, "Not Found").await;
        return Ok(());
    };

    let mut q_pref = String::new();
    for (k, v) in form_urlencoded::parse(query.as_bytes()) {
        if k == "q" {
            q_pref = v.into_owned();
        }
    }

    let data = match load_or_fetch_media(&vid, &q_pref, &content, &http, &cache).await {
        Ok(d) => d,
        Err(err) => {
            write_simple_resp(&mut writer, 502, &err).await;
            return Ok(());
        }
    };

    let total = data.len();
    if total == 0 {
        write_simple_resp(&mut writer, 502, "Empty media payload").await;
        return Ok(());
    }

    if let Some(rng) = range_header {
        if let Some((start, end)) = parse_byte_range(&rng, total) {
            let len = end - start + 1;
            let header = format!(
                "HTTP/1.1 206 Partial Content\r\nAccept-Ranges: bytes\r\nContent-Type: video/mp4\r\nContent-Range: bytes {start}-{end}/{total}\r\nContent-Length: {len}\r\nConnection: close\r\n\r\n"
            );
            let _ = writer.write_all(header.as_bytes()).await;
            if method == "GET" {
                let _ = writer.write_all(&data[start..=end]).await;
            }
            let _ = writer.flush().await;
            return Ok(());
        } else {
            let header = format!(
                "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */{total}\r\nConnection: close\r\n\r\n"
            );
            let _ = writer.write_all(header.as_bytes()).await;
            let _ = writer.flush().await;
            return Ok(());
        }
    }

    let header = format!(
        "HTTP/1.1 200 OK\r\nAccept-Ranges: bytes\r\nContent-Type: video/mp4\r\nContent-Length: {total}\r\nConnection: close\r\n\r\n"
    );
    let _ = writer.write_all(header.as_bytes()).await;
    if method == "GET" {
        let _ = writer.write_all(&data).await;
    }
    let _ = writer.flush().await;
    Ok(())
}

fn parse_media_vid(path: &str) -> Option<String> {
    let rest = path.strip_prefix("/media/")?;
    let vid = rest.strip_suffix(".mp4").unwrap_or(rest);
    if vid.is_empty() || !vid.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    Some(vid.to_string())
}

fn parse_byte_range(header: &str, total: usize) -> Option<(usize, usize)> {
    let spec = header.trim().strip_prefix("bytes=")?;
    let first = spec.split(',').next()?.trim();
    let (s_str, e_str) = first.split_once('-')?;
    if s_str.is_empty() {
        let suffix: usize = e_str.trim().parse().ok()?;
        if suffix == 0 || total == 0 {
            return None;
        }
        let start = total.saturating_sub(suffix);
        return Some((start, total - 1));
    }
    let start: usize = s_str.trim().parse().ok()?;
    if start >= total {
        return None;
    }
    let end = if e_str.trim().is_empty() {
        total - 1
    } else {
        e_str.trim().parse::<usize>().ok()?.min(total - 1)
    };
    if start > end {
        return None;
    }
    Some((start, end))
}

async fn write_simple_resp(writer: &mut tokio::net::tcp::OwnedWriteHalf, status: u16, msg: &str) {
    let reason = match status {
        400 => "Bad Request",
        404 => "Not Found",
        405 => "Method Not Allowed",
        _ => "Bad Gateway",
    };
    let body = msg.as_bytes();
    let hdr = format!(
        "HTTP/1.1 {status} {reason}\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        body.len()
    );
    let _ = writer.write_all(hdr.as_bytes()).await;
    let _ = writer.write_all(body).await;
    let _ = writer.flush().await;
}

async fn load_or_fetch_media(
    vid: &str,
    quality: &str,
    content: &Arc<Content>,
    http: &Client,
    cache: &Arc<Mutex<MediaCache>>,
) -> Result<Arc<Vec<u8>>, String> {
    let cache_key = format!("{vid}|{}", if quality.is_empty() { "auto" } else { quality });
    loop {
        let mut wait_rx = {
            let mut guard = cache.lock().await;
            if let Some(hit) = guard.get(&cache_key) {
                return Ok(hit);
            }
            if let Some(tx) = guard.inflight.get(&cache_key) {
                Some(tx.subscribe())
            } else {
                let (tx, _rx) = watch::channel(false);
                guard.inflight.insert(cache_key.clone(), tx);
                None
            }
        };

        if let Some(ref mut rx) = wait_rx {
            if !*rx.borrow_and_update() {
                let _ = rx.changed().await;
            }
            continue;
        }
        break;
    }

    let res = fetch_and_decrypt(vid, quality, content, http).await;
    let mut guard = cache.lock().await;
    let tx = guard.inflight.remove(&cache_key);
    let out = match res {
        Ok(bytes) => {
            let arc = Arc::new(bytes);
            guard.put(cache_key, arc.clone());
            Ok(arc)
        }
        Err(e) => Err(e),
    };
    drop(guard);
    if let Some(tx) = tx {
        let _ = tx.send(true);
    }
    out
}

async fn fetch_and_decrypt(
    vid: &str,
    quality: &str,
    content: &Content,
    http: &Client,
) -> Result<Vec<u8>, String> {
    let mut fresh = false;
    for attempt in 0..2 {
        let gears = content.play(vid, fresh).await?;
        let gear = pick_gear(&gears, quality, 1080)
            .cloned()
            .ok_or_else(|| "暂无可用播放清晰度".to_string())?;
        let urls = collect_gear_urls(&gear);
        if urls.is_empty() {
            return Err("播放地址为空".into());
        }

        let mut need_refresh = false;
        let mut last_err = String::new();
        for url in urls {
            match http.get(&url).send().await {
                Ok(resp) => {
                    let code = resp.status().as_u16();
                    if matches!(code, 401 | 403 | 410) {
                        need_refresh = true;
                        last_err = format!("上游返回 HTTP {code}");
                        continue;
                    }
                    if !resp.status().is_success() {
                        last_err = format!("上游返回 HTTP {code}");
                        continue;
                    }
                    let mut raw = resp
                        .bytes()
                        .await
                        .map_err(|e| format!("读取媒体流失败: {e}"))?
                        .to_vec();
                    if gear.encrypted && !gear.spade_a.is_empty() {
                        let key = spade_to_key(&gear.spade_a)
                            .ok_or_else(|| "解析媒体密钥失败".to_string())?;
                        raw = tokio::task::spawn_blocking(move || -> Result<Vec<u8>, String> {
                            decrypt_mp4(&mut raw, &key)?;
                            Ok(raw)
                        })
                        .await
                        .map_err(|e| format!("解密工作线程异常: {e}"))??;
                    }
                    return Ok(raw);
                }
                Err(e) => {
                    last_err = format!("请求媒体失败: {e}");
                }
            }
        }
        if attempt == 0 && need_refresh {
            fresh = true;
            continue;
        }
        if !last_err.is_empty() {
            return Err(last_err);
        }
    }
    Err("获取媒体流失败".into())
}

fn collect_gear_urls(g: &Gear) -> Vec<String> {
    let mut out = Vec::new();
    if !g.main_url.trim().is_empty() {
        out.push(g.main_url.clone());
    }
    if !g.backup_url.trim().is_empty() && !out.contains(&g.backup_url) {
        out.push(g.backup_url.clone());
    }
    for u in &g.backup_urls {
        if !u.trim().is_empty() && !out.contains(u) {
            out.push(u.clone());
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_media_vid_and_range() {
        assert_eq!(parse_media_vid("/media/123456.mp4"), Some("123456".into()));
        assert_eq!(parse_media_vid("/media/bad.mp4"), None);
        assert_eq!(parse_media_vid("/other/123.mp4"), None);

        assert_eq!(parse_byte_range("bytes=0-99", 1000), Some((0, 99)));
        assert_eq!(parse_byte_range("bytes=500-", 1000), Some((500, 999)));
        assert_eq!(parse_byte_range("bytes=-200", 1000), Some((800, 999)));
        assert_eq!(parse_byte_range("bytes=1000-1005", 1000), None);
    }

    #[test]
    fn test_media_cache_put_and_hit() {
        let mut cache = MediaCache::new();
        cache.put("v1|1080p".into(), Arc::new(vec![1, 2, 3, 4]));
        assert_eq!(cache.get("v1|1080p").unwrap().as_slice(), &[1, 2, 3, 4]);
        assert!(cache.get("missing").is_none());
    }
}
