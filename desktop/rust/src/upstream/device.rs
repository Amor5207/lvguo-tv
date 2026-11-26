use crate::sign::{md5_bytes, sign_v3, APP_UA};
use aes::cipher::{block_padding::Pkcs7, BlockEncryptMut, KeyIvInit};
use aes::Aes128;
use flate2::write::GzEncoder;
use flate2::Compression;
use rand::RngCore;
use reqwest::header::{HeaderMap, HeaderName, HeaderValue, COOKIE, SET_COOKIE, USER_AGENT};
use reqwest::{Client, Method};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha512};
use std::collections::HashMap;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use uuid::Uuid;

type Aes128CbcEnc = cbc::Encryptor<Aes128>;

pub const CONTENT_HOST: &str = "api5-normal-sinfonlinea.fqnovel.com";
pub const CONTENT_HOST2: &str = "api3-normal-sinfonlinea.fqnovel.com";
pub const APP_HOST: &str = "https://api5-normal-sinfonlinea.fqnovel.com";
pub const CONTENT_UA: &str = "com.phoenix.read/71932 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3;tt-ok/3.12.13.20)";
pub const VERSION_NAME: &str = "7.1.9.32";

const DEVICE_REGISTER_URL: &str = "https://log5-applog-lf.fqnovel.com/service/2/device_register/?tt_data=a&cronet_version=8d40f833_2026-03-03&ttnet_version=4.2.243.31-douyin&use_store_region_cookie=1";
const KDF_SALT_HEX: &str = "4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58";
const HEARTBEAT_INTERVAL: Duration = Duration::from_secs(6 * 3600);

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Device {
    pub id: String,
    pub iid: String,
    pub cdid: String,
    pub openudid: String,
    pub clientudid: String,
    #[serde(default)]
    pub region: String,
    #[serde(default)]
    pub egdi: String,
    #[serde(default)]
    pub last_config_time: i64,
}

#[derive(Debug, Clone)]
pub struct HttpResp {
    pub status: u16,
    pub body: Vec<u8>,
    pub cookies: Vec<String>,
    pub location: String,
}

impl HttpResp {
    pub fn json(&self) -> Result<serde_json::Value, String> {
        if self.body.iter().all(|b| b.is_ascii_whitespace()) {
            return Err("empty body".to_string());
        }
        serde_json::from_slice(&self.body).map_err(|e| e.to_string())
    }
}

pub fn build_http_client(timeout_secs: u64) -> Client {
    Client::builder()
        .timeout(Duration::from_secs(timeout_secs))
        .connect_timeout(Duration::from_secs(10))
        .redirect(reqwest::redirect::Policy::none())
        .pool_max_idle_per_host(32)
        .build()
        .expect("upstream reqwest client")
}

pub async fn do_request(
    client: &Client,
    method: &str,
    url: &str,
    headers: &[(String, String)],
    body: Option<&[u8]>,
) -> Result<HttpResp, String> {
    let m = match method.to_uppercase().as_str() {
        "POST" => Method::POST,
        "PUT" => Method::PUT,
        "DELETE" => Method::DELETE,
        _ => Method::GET,
    };
    let mut builder = client.request(m, url);
    let mut hmap = HeaderMap::new();
    for (k, v) in headers {
        if k.eq_ignore_ascii_case("Host") {
            continue;
        }
        if let (Ok(hn), Ok(hv)) = (
            HeaderName::from_bytes(k.as_bytes()),
            HeaderValue::from_str(v),
        ) {
            hmap.insert(hn, hv);
        }
    }
    builder = builder.headers(hmap);
    if let Some(b) = body {
        builder = builder.body(b.to_vec());
    }
    let res = builder.send().await.map_err(|e| format!("网络请求失败: {e}"))?;
    let status = res.status().as_u16();
    let location = res
        .headers()
        .get(reqwest::header::LOCATION)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .to_string();
    let cookies: Vec<String> = res
        .headers()
        .get_all(SET_COOKIE)
        .iter()
        .filter_map(|v| v.to_str().ok().map(|s| s.to_string()))
        .collect();
    let data = res.bytes().await.map_err(|e| format!("读取响应失败: {e}"))?;
    Ok(HttpResp {
        status,
        body: data.to_vec(),
        cookies,
        location,
    })
}

pub fn tt_encrypt(data: &[u8]) -> Result<Vec<u8>, String> {
    let mut rnd = [0u8; 32];
    rand::thread_rng().fill_bytes(&mut rnd);
    let s1 = Sha512::digest(&rnd);
    let salt = hex::decode(KDF_SALT_HEX).map_err(|e| e.to_string())?;
    let mut k_in = Vec::with_capacity(64 + salt.len());
    k_in.extend_from_slice(&s1);
    k_in.extend_from_slice(&salt);
    let derived = Sha512::digest(&k_in);

    let dh = Sha512::digest(data);
    let msg_len = 64 + data.len();
    let mut buf = vec![0u8; msg_len + 16];
    buf[..64].copy_from_slice(&dh);
    buf[64..msg_len].copy_from_slice(data);

    let enc = Aes128CbcEnc::new_from_slices(&derived[..16], &derived[16..32])
        .map_err(|e| e.to_string())?
        .encrypt_padded_mut::<Pkcs7>(&mut buf, msg_len)
        .map_err(|e| e.to_string())?;

    let mut out = Vec::with_capacity(6 + 32 + enc.len());
    out.extend_from_slice(&[0x74, 0x63, 0x05, 0x10, 0x00, 0x00]);
    out.extend_from_slice(&rnd);
    out.extend_from_slice(enc);
    Ok(out)
}

pub fn passport_tt_encrypt(plain: &[u8], seed: &[u8; 32]) -> Result<Vec<u8>, String> {
    let mut gz = GzEncoder::new(Vec::new(), Compression::best());
    gz.write_all(plain).map_err(|e| e.to_string())?;
    let mut z = gz.finish().map_err(|e| e.to_string())?;
    if z.len() >= 10 {
        z[..10].copy_from_slice(&[0x1f, 0x8b, 8, 0, 0, 0, 0, 0, 0, 0]);
    }
    let s1 = Sha512::digest(seed);
    let salt = hex::decode(KDF_SALT_HEX).map_err(|e| e.to_string())?;
    let mut k_in = Vec::with_capacity(64 + salt.len());
    k_in.extend_from_slice(&s1);
    k_in.extend_from_slice(&salt);
    let derived = Sha512::digest(&k_in);

    let hash = Sha512::digest(&z);
    let msg_len = 64 + z.len();
    let mut buf = vec![0u8; msg_len + 16];
    buf[..64].copy_from_slice(&hash);
    buf[64..msg_len].copy_from_slice(&z);
    let enc = Aes128CbcEnc::new_from_slices(&derived[..16], &derived[16..32])
        .map_err(|e| e.to_string())?
        .encrypt_padded_mut::<Pkcs7>(&mut buf, msg_len)
        .map_err(|e| e.to_string())?;

    let mut out = Vec::with_capacity(6 + 32 + enc.len());
    out.extend_from_slice(&[0x74, 0x63, 5, 0x10, 0, 0]);
    out.extend_from_slice(seed);
    out.extend_from_slice(enc);
    Ok(out)
}

pub fn passport_tt_decrypt(b: &[u8]) -> Result<Vec<u8>, String> {
    use aes::cipher::{BlockDecryptMut, KeyIvInit as _};
    type Aes128CbcDec = cbc::Decryptor<Aes128>;
    if b.len() < 38 + 80 || b[..6] != [0x74, 0x63, 5, 0x10, 0, 0] || (b.len() - 38) % 16 != 0 {
        return Err("设备注册加密响应无效".to_string());
    }
    let seed = &b[6..38];
    let s1 = Sha512::digest(seed);
    let salt = hex::decode(KDF_SALT_HEX).map_err(|e| e.to_string())?;
    let mut k_in = Vec::with_capacity(64 + salt.len());
    k_in.extend_from_slice(&s1);
    k_in.extend_from_slice(&salt);
    let derived = Sha512::digest(&k_in);

    let mut buf = b[38..].to_vec();
    let p = Aes128CbcDec::new_from_slices(&derived[..16], &derived[16..32])
        .map_err(|e| e.to_string())?
        .decrypt_padded_mut::<Pkcs7>(&mut buf)
        .map_err(|_| "设备注册加密响应无效".to_string())?;
    if p.len() < 64 {
        return Err("设备注册加密响应无效".to_string());
    }
    let hash = Sha512::digest(&p[64..]);
    if hash.as_slice() != &p[..64] {
        return Err("设备注册响应校验失败".to_string());
    }
    let mut dec = flate2::read::GzDecoder::new(&p[64..]);
    let mut out = Vec::new();
    std::io::Read::read_to_end(&mut dec, &mut out)
        .map_err(|_| "设备注册响应解压失败".to_string())?;
    Ok(out)
}

struct DeviceState {
    dev: Device,
    last: Option<Instant>,
    fail: Option<Instant>,
}

pub struct DeviceStore {
    path: PathBuf,
    http: Client,
    state: tokio::sync::Mutex<DeviceState>,
}

impl DeviceStore {
    pub fn new(dir: &Path, http: Client) -> Self {
        let path = dir.join("device.json");
        let mut dev = Device::default();
        let mut last = None;
        if let Ok(raw) = std::fs::read_to_string(&path) {
            if let Ok(d) = serde_json::from_str::<Device>(&raw) {
                if !d.id.is_empty()
                    && !d.iid.is_empty()
                    && d.id != d.iid
                    && !d.cdid.is_empty()
                    && !d.openudid.is_empty()
                    && !d.clientudid.is_empty()
                {
                    if d.last_config_time > 0 {
                        let age = (now_secs() - d.last_config_time).max(0) as u64;
                        last = Instant::now().checked_sub(Duration::from_secs(age));
                    }
                    dev = d;
                }
            }
        }
        Self {
            path,
            http,
            state: tokio::sync::Mutex::new(DeviceState {
                dev,
                last,
                fail: None,
            }),
        }
    }

    pub async fn get(&self) -> Result<Device, String> {
        let mut st = self.state.lock().await;
        if st.dev.id.is_empty() {
            if let Some(f) = st.fail {
                if f.elapsed() < Duration::from_secs(30) {
                    return Err("设备注册暂不可用，请稍后重试".to_string());
                }
            }
            match register_device(&self.http, None).await {
                Ok(d) => {
                    st.fail = None;
                    st.last = Some(Instant::now());
                    st.dev = d.clone();
                    self.save(&st.dev);
                    return Ok(d);
                }
                Err(e) => {
                    st.fail = Some(Instant::now());
                    return Err(e);
                }
            }
        }
        let need_hb = (st.dev.egdi.is_empty()
            || st.last.map(|l| l.elapsed() >= HEARTBEAT_INTERVAL).unwrap_or(true))
            && st
                .fail
                .map(|f| f.elapsed() >= Duration::from_secs(1800))
                .unwrap_or(true);
        if need_hb {
            if let Ok(d) = register_device(&self.http, Some(&st.dev)).await {
                if d.id == st.dev.id && d.iid == st.dev.iid {
                    if !d.egdi.is_empty() {
                        st.dev.egdi = d.egdi;
                    }
                    if !d.region.is_empty() {
                        st.dev.region = d.region;
                    }
                    st.fail = None;
                    st.last = Some(Instant::now());
                    st.dev.last_config_time = now_secs();
                    self.save(&st.dev);
                } else {
                    st.fail = Some(Instant::now());
                }
            } else {
                st.fail = Some(Instant::now());
            }
        }
        Ok(st.dev.clone())
    }

    pub async fn refresh(&self) -> bool {
        let mut st = self.state.lock().await;
        if st.dev.id.is_empty()
            || st.last.map(|l| l.elapsed() < HEARTBEAT_INTERVAL).unwrap_or(false)
        {
            return false;
        }
        match register_device(&self.http, Some(&st.dev)).await {
            Ok(d) if d.id == st.dev.id && d.iid == st.dev.iid => {
                if !d.egdi.is_empty() {
                    st.dev.egdi = d.egdi;
                }
                if !d.region.is_empty() {
                    st.dev.region = d.region;
                }
                st.fail = None;
                st.last = Some(Instant::now());
                st.dev.last_config_time = now_secs();
                self.save(&st.dev);
                true
            }
            _ => {
                st.fail = Some(Instant::now());
                false
            }
        }
    }

    pub async fn adopt(&self, mut dev: Device) {
        if dev.id.is_empty() || dev.iid.is_empty() {
            return;
        }
        let mut st = self.state.lock().await;
        if dev.region.is_empty() {
            dev.region = st.dev.region.clone();
        }
        dev.last_config_time = now_secs();
        st.dev = dev.clone();
        st.last = Some(Instant::now());
        st.fail = None;
        self.save(&st.dev);
    }

    pub async fn reset(&self) {
        let mut st = self.state.lock().await;
        st.dev = Device::default();
        st.last = None;
        st.fail = None;
        let _ = std::fs::remove_file(&self.path);
    }

    fn save(&self, d: &Device) {
        if let Some(parent) = self.path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        if let Ok(json) = serde_json::to_string_pretty(d) {
            let tmp = PathBuf::from(format!("{}.tmp", self.path.to_string_lossy()));
            if std::fs::write(&tmp, json).is_ok() {
                let _ = std::fs::rename(&tmp, &self.path);
            }
        }
    }
}

pub fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64
}

pub fn now_secs() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs() as i64
}

pub async fn register_device(http: &Client, prev: Option<&Device>) -> Result<Device, String> {
    let now = now_ms();
    let (cdid, openudid, clientudid) = match prev {
        Some(p) => (p.cdid.clone(), p.openudid.clone(), p.clientudid.clone()),
        None => (
            Uuid::new_v4().to_string(),
            Uuid::new_v4().simple().to_string()[..16].to_string(),
            Uuid::new_v4().to_string(),
        ),
    };
    let mut header = serde_json::json!({
        "display_name": "红果免费短剧", "update_version_code": 71932, "manifest_version_code": 71932,
        "app_version_minor": "", "aid": 8662, "channel": "update_64", "package": "com.phoenix.read",
        "app_version": "7.1.9.32", "version_code": 71932, "sdk_version": "3.7.0-rc.34-fanqie-xiaoshuo",
        "sdk_target_version": 29, "git_hash": "3a88f4e", "os": "Android", "os_version": "16", "os_api": 36,
        "device_model": "25053RT47C", "device_brand": "Redmi", "device_manufacturer": "Xiaomi", "cpu_abi": "arm64-v8a",
        "release_build": "b5e187f", "density_dpi": 520, "display_density": "mdpi", "resolution": "1280x2772",
        "language": "zh", "timezone": 8, "access": "wifi", "not_request_sender": 0, "carrier": "CHINA MOBILE",
        "mcc_mnc": "46000", "rom": "eng.root.20231116.160340", "rom_version": "W528JS release-keys",
        "cdid": cdid, "sig_hash": "56a962410c494bbaf0b58dba20cae56f",
        "openudid": openudid, "clientudid": clientudid, "ipv6_list": [],
        "region": "CN", "tz_name": "Asia/Shanghai", "tz_offset": 28800, "sim_region": "cn",
        "sim_serial_number": [], "oaid_may_support": false, "req_id": Uuid::new_v4().to_string(), "device_platform": "android",
        "custom": {"account_region_src": "fallback", "host_bit": 64, "account_region": "cn", "dragon_device_type": "phone"},
        "pre_installed_channel": "", "apk_first_install_time": now, "is_system_app": 0, "sdk_flavor": "china", "guest_mode": 0,
    });
    if let Some(p) = prev {
        header["device_id"] = serde_json::Value::String(p.id.clone());
        header["install_id"] = serde_json::Value::String(p.iid.clone());
    }
    let payload = serde_json::to_vec(&serde_json::json!({
        "magic_tag": "ss_app_log",
        "header": header,
        "_gen_time": now,
    }))
    .map_err(|e| e.to_string())?;

    let mut gz = GzEncoder::new(Vec::new(), Compression::default());
    gz.write_all(&payload).map_err(|e| e.to_string())?;
    let gz_bytes = gz.finish().map_err(|e| e.to_string())?;
    let body = tt_encrypt(&gz_bytes)?;

    let headers = vec![
        ("User-Agent".into(), APP_UA.into()),
        ("Content-Type".into(), "application/octet-stream;tt-data=a".into()),
        ("log-encode-type".into(), "gzip".into()),
        ("x-ss-dp".into(), "8662".into()),
        ("x-ss-req-ticket".into(), now.to_string()),
        ("x-vc-bdturing-sdk-version".into(), "4.0.3.cn".into()),
        ("lc".into(), "101".into()),
        ("sdk-version".into(), "2".into()),
        ("passport-sdk-version".into(), "5051452".into()),
        ("x-neptune".into(), "-8|50:51:59:30:40:47:49:39".into()),
        ("x-ss-stub".into(), hex::encode_upper(md5_bytes(&body))),
    ];

    let r = do_request(http, "POST", DEVICE_REGISTER_URL, &headers, Some(&body)).await?;
    let m = r.json().map_err(|_| "设备注册被拒绝".to_string())?;
    let dev = first_str(&m, &["device_id_str", "device_id"]);
    let iid = first_str(&m, &["install_id_str", "install_id"]);
    if dev.is_empty() || iid.is_empty() || dev == "0" {
        return Err("设备注册被拒绝".to_string());
    }
    let mut region = String::new();
    for c in &r.cookies {
        if let Some(rest) = c.split(';').next() {
            if let Some(val) = rest.strip_prefix("store-region=") {
                region = val.to_string();
            }
        }
    }
    let egdi = first_str(&m, &["klink_egdi", "egdi"]);
    activate_registered_device(http, &dev, &iid).await;
    Ok(Device {
        id: dev,
        iid,
        cdid,
        openudid,
        clientudid,
        region,
        egdi,
        last_config_time: now_secs(),
    })
}

async fn activate_registered_device(http: &Client, did: &str, iid: &str) {
    if did.is_empty() || iid.is_empty() {
        return;
    }
    let u = format!(
        "https://log5-applog-lf.fqnovel.com/service/2/app_alert_check/?aid=8662&app_name=novelread&channel=update_64&device_id={did}&iid={iid}&device_platform=android&os=android&version_code=71932&version_name=7.1.9.32&_rticket={}",
        now_ms()
    );
    let _ = do_request(http, "GET", &u, &[("User-Agent".into(), APP_UA.into())], None).await;
}

pub fn jstr(v: &serde_json::Value) -> String {
    match v {
        serde_json::Value::String(s) => s.clone(),
        serde_json::Value::Number(n) => {
            if let Some(i) = n.as_i64() {
                i.to_string()
            } else if let Some(u) = n.as_u64() {
                u.to_string()
            } else {
                n.to_string()
            }
        }
        serde_json::Value::Bool(b) => b.to_string(),
        _ => String::new(),
    }
}

pub fn jnum(v: &serde_json::Value) -> f64 {
    match v {
        serde_json::Value::Number(n) => n.as_f64().unwrap_or(0.0),
        serde_json::Value::String(s) => s.parse::<f64>().unwrap_or(0.0),
        serde_json::Value::Bool(b) => {
            if *b {
                1.0
            } else {
                0.0
            }
        }
        _ => 0.0,
    }
}

pub fn first_str(m: &serde_json::Value, keys: &[&str]) -> String {
    for k in keys {
        let s = jstr(&m[*k]);
        if !s.is_empty() {
            return s;
        }
    }
    String::new()
}

pub fn nz(a: String, b: String) -> String {
    if !a.is_empty() {
        a
    } else {
        b
    }
}

pub fn img_url(in_url: &str) -> String {
    if in_url.is_empty() {
        return String::new();
    }
    let mut url_str = in_url.to_string();
    if url_str.starts_with("http://") {
        if let Ok(u) = url::Url::parse(&url_str) {
            if let Some(h) = u.host_str() {
                if h.ends_with(".fqnovelpic.com")
                    || h.ends_with(".byteimg.com")
                    || h.ends_with(".snssdk.com")
                    || h.ends_with(".pstatp.com")
                {
                    url_str = format!("https://{}", &url_str[7..]);
                }
            }
        }
    }
    if let Ok(u) = url::Url::parse(&url_str) {
        if let Some(h) = u.host_str() {
            if h.ends_with(".fqnovelpic.com") || h.ends_with(".byteimg.com") {
                for seg in ["/novel-pic/", "/novel-static/"] {
                    if let Some(i) = u.path().find(seg) {
                        let rest = &u.path()[i + seg.len()..];
                        if let Some(id) = rest.split(['~', '.']).next() {
                            if id.len() == 32 && id.bytes().all(|b| b.is_ascii_hexdigit()) {
                                return format!(
                                    "https://p3-novel.byteimg.com/img{seg}{id}~tplv-noop.jpeg"
                                );
                            }
                        }
                    }
                }
            }
        }
    }
    url_str
}

pub fn is_digits(s: &str) -> bool {
    !s.is_empty() && s.bytes().all(|b| b.is_ascii_digit())
}

pub struct UserJar {
    path: PathBuf,
    jar: Mutex<HashMap<String, String>>,
}

impl UserJar {
    pub fn new(data_dir: &Path) -> Self {
        let path = data_dir.join("session.json");
        let jar = std::fs::read_to_string(&path)
            .ok()
            .and_then(|s| serde_json::from_str::<HashMap<String, String>>(&s).ok())
            .unwrap_or_default();
        Self {
            path,
            jar: Mutex::new(jar),
        }
    }

    pub fn logged_in(&self) -> bool {
        let g = self.jar.lock().unwrap();
        g.get("sessionid").map(|s| !s.is_empty()).unwrap_or(false)
            || g.get("sessionid_ss").map(|s| !s.is_empty()).unwrap_or(false)
    }

    pub fn cookie_string(&self) -> String {
        let g = self.jar.lock().unwrap();
        let mut keys: Vec<&String> = g.keys().collect();
        keys.sort();
        keys.into_iter()
            .map(|k| format!("{k}={}", g[k]))
            .collect::<Vec<_>>()
            .join("; ")
    }

    pub fn absorb(&self, set_cookies: &[String]) -> bool {
        let mut changed = false;
        let mut g = self.jar.lock().unwrap();
        for c in set_cookies {
            if let Some(first) = c.split(';').next() {
                if let Some((k, v)) = first.split_once('=') {
                    let k = k.trim();
                    let v = v.trim();
                    if k.is_empty() || v.is_empty() || v.eq_ignore_ascii_case("deleted") {
                        continue;
                    }
                    if g.get(k).map(|x| x.as_str()) != Some(v) {
                        g.insert(k.to_string(), v.to_string());
                        changed = true;
                    }
                }
            }
        }
        if changed {
            self.save_locked(&g);
        }
        changed
    }

    pub fn set(&self, jar: HashMap<String, String>) {
        let mut g = self.jar.lock().unwrap();
        *g = jar;
        self.save_locked(&g);
    }

    pub fn clear(&self) {
        let mut g = self.jar.lock().unwrap();
        g.clear();
        let _ = std::fs::remove_file(&self.path);
    }

    pub fn days_left(&self) -> i32 {
        let g = self.jar.lock().unwrap();
        let raw = match g.get("sid_guard") {
            Some(s) => s.clone(),
            None => return -1,
        };
        drop(g);
        let decoded = url::form_urlencoded::parse(format!("x={raw}").as_bytes())
            .next()
            .map(|(_, v)| v.into_owned())
            .unwrap_or(raw);
        let parts: Vec<&str> = decoded.split('|').collect();
        if parts.len() >= 3 {
            if let (Ok(a), Ok(b)) = (parts[1].parse::<i64>(), parts[2].parse::<i64>()) {
                let rem = (a + b) - now_secs();
                return (rem / 86400) as i32;
            }
        }
        -1
    }

    fn save_locked(&self, g: &HashMap<String, String>) {
        if let Some(parent) = self.path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        if let Ok(json) = serde_json::to_string_pretty(g) {
            let tmp = PathBuf::from(format!("{}.tmp", self.path.to_string_lossy()));
            if std::fs::write(&tmp, json).is_ok() {
                let _ = std::fs::rename(&tmp, &self.path);
            }
        }
    }
}

#[allow(dead_code)]
fn _unused_keep() {
    let _ = sign_v3;
    let _ = COOKIE;
    let _ = USER_AGENT;
}
