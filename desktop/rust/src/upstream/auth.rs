use crate::sign::passport::{protocol_random, sign_passport_request_version};
use crate::sign::{b64_decode, encode_params, set_param, Params};
use crate::upstream::device::{
    build_http_client, do_request, jstr, now_ms, passport_tt_decrypt, passport_tt_encrypt, Device,
};
use regex::Regex;
use reqwest::Client;
use serde::Serialize;
use std::collections::HashMap;
use std::time::{Duration, Instant};
use uuid::Uuid;

const QR_BASE: &str = "https://hongguoduanju.com";
const WEB_UA: &str = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36";
const PASSPORT_API: &str = "https://api5-normal-lq.fqnovel.com";
const PASSPORT_DEVICE_API: &str = "https://log.snssdk.com";
const DEFAULT_HONGGUO_UA: &str = "com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)";

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct QrStartResult {
    pub qr: String,
    pub expires_at: i64,
    pub open: String,
}

#[derive(Debug, Clone)]
pub struct QrSession {
    jar: HashMap<String, String>,
    token: String,
    http: Client,
}

impl QrSession {
    pub fn token(&self) -> &str {
        &self.token
    }
    fn qr_common() -> Params {
        vec![
            ("aid".into(), "513755".into()),
            ("passport_jssdk_version".into(), "3.4.10".into()),
            ("passport_jssdk_type".into(), "normal".into()),
            ("account_sdk_source".into(), "web".into()),
            ("is_from_ttaccountsdk".into(), "1".into()),
            ("language".into(), "zh".into()),
            ("next".into(), format!("{QR_BASE}/")),
        ]
    }

    fn cookie_string(&self) -> String {
        let mut keys: Vec<&String> = self.jar.keys().collect();
        keys.sort();
        keys.into_iter()
            .map(|k| format!("{k}={}", self.jar[k]))
            .collect::<Vec<_>>()
            .join("; ")
    }

    fn headers(&self) -> Vec<(String, String)> {
        let mut h = vec![
            ("User-Agent".into(), WEB_UA.into()),
            ("Referer".into(), format!("{QR_BASE}/")),
            ("Origin".into(), QR_BASE.into()),
            ("Accept".into(), "application/json, text/javascript".into()),
        ];
        if !self.jar.is_empty() {
            h.push(("Cookie".into(), self.cookie_string()));
        }
        h
    }

    fn absorb(&mut self, cookies: &[String]) {
        for c in cookies {
            if let Some(first) = c.split(';').next() {
                if let Some((k, v)) = first.split_once('=') {
                    let k = k.trim();
                    let v = v.trim();
                    if !k.is_empty() && !v.is_empty() && !v.eq_ignore_ascii_case("deleted") {
                        self.jar.insert(k.to_string(), v.to_string());
                    }
                }
            }
        }
    }

    async fn get_json(&mut self, path: &str, p: &[(String, String)]) -> Result<serde_json::Value, String> {
        let u = format!("{QR_BASE}{path}?{}", encode_params(p));
        let r = do_request(&self.http, "GET", &u, &self.headers(), None).await?;
        self.absorb(&r.cookies);
        if r.status != 200 {
            return Err(format!("登录服务暂时不可用（HTTP {}）", r.status));
        }
        r.json().map_err(|_| "二维码服务响应异常".into())
    }

    pub async fn start() -> Result<(Self, QrStartResult), String> {
        let mut sess = Self {
            jar: HashMap::new(),
            token: String::new(),
            http: build_http_client(20),
        };
        let m = sess.get_json("/passport/web/get_qrcode/", &Self::qr_common()).await?;
        if jstr(&m["message"]) != "success" || !m["data"].is_object() {
            return Err("获取二维码失败，请重试".into());
        }
        let token = jstr(&m["data"]["token"]);
        let mut qr = jstr(&m["data"]["qrcode"]);
        if token.is_empty() || qr.is_empty() {
            return Err("二维码数据不完整，请重试".into());
        }
        if !qr.starts_with("data:") {
            qr = format!("data:image/png;base64,{qr}");
        }
        sess.token = token.clone();
        let exp = m["data"]["expire_time"].as_i64().unwrap_or(0);
        let now = now_ms();
        let expires_at = if exp > 1_000_000_000 {
            let ms = exp * 1000;
            if ms > now + 30_000 {
                ms
            } else {
                now + 120_000
            }
        } else if exp > 30 {
            now + exp * 1000
        } else {
            now + 120_000
        };
        let open = format!(
            "https://reading.snssdk.com/reading_offline/novelread/page/scan-code.html?custom_brightness=1&hide_nav_bar=1&hide_status_bar=1&need_custom_brightness=1&next_url=https%3A%2F%2Freading.snssdk.com%2Freading_offline%2Fnovelread%2Fpage%2Fscan-code.html&qr_source_aid=513755&token={token}&version_code=5.8.3"
        );
        Ok((
            sess,
            QrStartResult {
                qr,
                expires_at,
                open,
            },
        ))
    }

    pub async fn poll(&mut self) -> Result<(String, Option<HashMap<String, String>>), String> {
        let mut p = Self::qr_common();
        p.push(("token".into(), self.token.clone()));
        let m = self.get_json("/passport/web/check_qrconnect/", &p).await?;
        if jstr(&m["message"]) != "success" {
            return Err("登录状态查询失败".into());
        }
        let st = jstr(&m["data"]["status"]).trim().to_ascii_lowercase();
        match st.as_str() {
            "5" | "expired" | "timeout" => Ok(("expired".into(), None)),
            "4" | "canceled" | "cancelled" | "cancel" | "refused" | "reject" | "rejected" => {
                Ok(("canceled".into(), None))
            }
            "3" | "confirmed" | "confirm" => {
                let red = jstr(&m["data"]["redirect_url"]);
                if !red.is_empty() {
                    self.follow(&red).await?;
                }
                let ok = self.jar.get("sessionid").map(|s| !s.is_empty()).unwrap_or(false)
                    || self.jar.get("sessionid_ss").map(|s| !s.is_empty()).unwrap_or(false);
                if !ok {
                    return Err("登录确认未完成，请重新扫码".into());
                }
                Ok(("confirmed".into(), Some(self.jar.clone())))
            }
            "2" | "scanning" | "scanned" => Ok(("scanned".into(), None)),
            _ => Ok(("waiting".into(), None)),
        }
    }

    async fn follow(&mut self, value: &str) -> Result<(), String> {
        let base = url::Url::parse(&format!("{QR_BASE}/")).map_err(|e| e.to_string())?;
        let mut cur = base.join(value).map_err(|e| e.to_string())?;
        for _ in 0..5 {
            let host = cur.host_str().unwrap_or("");
            if cur.scheme() != "https"
                || !(host == "hongguoduanju.com" || host.ends_with(".hongguoduanju.com"))
            {
                return Err("登录跳转到了不受支持的站点".into());
            }
            let mut h = vec![
                ("User-Agent".into(), WEB_UA.into()),
                ("Referer".into(), format!("{QR_BASE}/")),
            ];
            if !self.jar.is_empty() {
                h.push(("Cookie".into(), self.cookie_string()));
            }
            let r = do_request(&self.http, "GET", cur.as_str(), &h, None).await?;
            self.absorb(&r.cookies);
            if r.status >= 300 && r.status < 400 {
                if r.location.is_empty() {
                    return Err("登录跳转缺少地址".into());
                }
                cur = cur.join(&r.location).map_err(|e| e.to_string())?;
                continue;
            }
            if r.status >= 400 {
                return Err(format!("登录确认失败（HTTP {}）", r.status));
            }
            return Ok(());
        }
        Err("登录跳转次数过多".into())
    }
}

#[derive(Debug, Clone)]
pub struct PhoneMfa {
    pub number: String,
    pub content: String,
    encrypt_uid: String,
    code_key: String,
    log_id: String,
    #[allow(dead_code)]
    started: Instant,
}

pub struct PhoneLogin {
    http: Client,
    device: Device,
    cookies: HashMap<String, String>,
    token: String,
    phone: String,
    code: String,
    mfa: Option<PhoneMfa>,
}

pub fn normalize_cn_phone(raw: &str) -> Option<String> {
    let mut digits: String = raw.chars().filter(|c| c.is_ascii_digit()).collect();
    if digits.starts_with("86") && digits.len() == 13 {
        digits = digits[2..].to_string();
    }
    if digits.len() == 11 && digits.starts_with('1') {
        Some(format!("+86{digits}"))
    } else {
        None
    }
}

fn xor5(s: &str) -> String {
    let b: Vec<u8> = s.bytes().map(|c| c ^ 5).collect();
    hex::encode(b)
}

fn passport_query(dev: &Device) -> Params {
    let mut q = vec![
        ("ac".into(), "wifi".into()),
        ("aid".into(), "8662".into()),
        ("app_name".into(), "novelread".into()),
        ("channel".into(), "update_64".into()),
        ("device_brand".into(), "Redmi".into()),
        ("device_id".into(), dev.id.clone()),
        ("device_platform".into(), "android".into()),
        ("device_type".into(), "25053RT47C".into()),
        ("dpi".into(), "520".into()),
        ("iid".into(), dev.iid.clone()),
        ("language".into(), "zh".into()),
        ("manifest_version_code".into(), "73532".into()),
        ("os".into(), "android".into()),
        ("os_api".into(), "36".into()),
        ("os_version".into(), "16".into()),
        ("resolution".into(), "1280*2772".into()),
        ("ssmix".into(), "a".into()),
        ("update_version_code".into(), "73532".into()),
        ("version_code".into(), "73532".into()),
        ("version_name".into(), "7.3.5.32".into()),
    ];
    if !dev.cdid.is_empty() {
        q.push(("cdid".into(), dev.cdid.clone()));
    }
    if !dev.openudid.is_empty() {
        q.push(("openudid".into(), dev.openudid.clone()));
    }
    q
}

fn encode_form(pairs: &[(&str, &str)]) -> String {
    let mut ser = url::form_urlencoded::Serializer::new(String::new());
    for (k, v) in pairs {
        ser.append_pair(k, v);
    }
    ser.finish()
}

fn login_err_code(p: &serde_json::Value) -> String {
    if let Some(d) = p.get("data").and_then(|v| v.as_object()) {
        if let Some(x) = d.get("error_code") {
            return jstr(x);
        }
    }
    jstr(&p["error_code"])
}

fn login_success(p: &serde_json::Value) -> bool {
    let code = login_err_code(p);
    jstr(&p["message"]) == "success" && (code.is_empty() || code == "0")
}

fn login_failure(p: &serde_json::Value, fallback: &str) -> String {
    let mut msg = p
        .get("data")
        .and_then(|d| d.get("description"))
        .map(jstr)
        .unwrap_or_default();
    if msg.is_empty() {
        msg = jstr(&p["description"]);
    }
    if msg.contains('\r') || msg.contains('\n') || msg.contains("http") || msg.chars().count() > 120 {
        msg.clear();
    }
    if msg.is_empty() {
        fallback.to_string()
    } else {
        let re = Regex::new(r"[0-9]{7,}").unwrap();
        re.replace_all(&msg, "***").to_string()
    }
}

impl PhoneLogin {
    pub async fn new() -> Result<Self, String> {
        let http = build_http_client(25);
        let cdid = Uuid::new_v4().to_string();
        let clientudid = Uuid::new_v4().to_string();
        let openudid = Uuid::new_v4().simple().to_string()[..16].to_string();
        let created = now_ms();

        let payload = serde_json::to_vec(&serde_json::json!({
            "magic_tag": "ss_app_log",
            "header": {
                "aid": 8662, "access": "wifi", "app_version": "7.3.5.32", "app_version_minor": "", "channel": "update_64",
                "device_brand": "Redmi", "device_manufacturer": "Redmi", "device_model": "25053RT47C", "device_category": "phone", "device_platform": "android",
                "os": "Android", "os_api": 36, "os_version": "16", "density_dpi": 520, "display_density": "xxxhdpi", "resolution": "2772x1280",
                "language": "zh", "timezone": 8, "tz_name": "Asia/Shanghai", "tz_offset": 28800, "region": "CN", "package": "com.phoenix.read",
                "version_code": 73532, "manifest_version_code": 73532, "update_version_code": 73532, "apk_first_install_time": created,
                "cdid": cdid, "clientudid": clientudid, "openudid": openudid, "cpu_abi": "arm64-v8a", "display_name": "红果短剧",
                "sdk_version": "3.7.2", "sdk_target_version": 29, "sdk_flavor": "china", "guest_mode": 0, "is_system_app": 0, "not_request_sender": 0, "oaid_may_support": true,
                "ipv6_list": [], "sim_serial_number": [],
                "custom": {"is_android_fold": 0, "filter_warn": 0, "web_ua": "", "is_android_pad": 0, "client_ipv4": ""},
            },
            "_gen_time": created,
        })).map_err(|e| e.to_string())?;

        let mut seed = [0u8; 32];
        rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut seed);
        let body = passport_tt_encrypt(&payload, &seed)?;

        let stamp = now_ms();
        let ts = (stamp / 1000) as u32;
        let tmp_dev = Device {
            id: String::new(),
            iid: String::new(),
            cdid: cdid.clone(),
            openudid: openudid.clone(),
            clientudid: clientudid.clone(),
            region: String::new(),
            egdi: String::new(),
            last_config_time: 0,
        };
        let mut q: Params = passport_query(&tmp_dev)
            .into_iter()
            .filter(|(k, _)| k != "device_id" && k != "iid")
            .collect();
        set_param(&mut q, "tt_data", "a");
        set_param(&mut q, "ts", &ts.to_string());
        set_param(&mut q, "_rticket", &stamp.to_string());
        set_param(&mut q, "package", "com.phoenix.read");

        let query_enc = form_encode_sorted(&q);
        let nonce = protocol_random() as u16;
        let stub = hex::encode(crate::sign::md5_bytes(&body));
        let headers = vec![
            ("User-Agent".into(), DEFAULT_HONGGUO_UA.into()),
            ("Content-Type".into(), "application/octet-stream;tt-data=a".into()),
            ("Accept".into(), "application/json; charset=utf-8,application/x-protobuf".into()),
            ("Content-Encoding".into(), "gzip".into()),
            ("X-Tt-Request-Tag".into(), "t=0;n=1".into()),
            ("Log-Encode-Type".into(), "gzip".into()),
            ("Lc".into(), "102".into()),
            ("Sdk-Version".into(), "2".into()),
            ("Passport-Sdk-Version".into(), "60755".into()),
            ("Passport-Sdk-Settings".into(), "device_transfer_s_0,device_transfer_ab_1".into()),
            ("X-SS-DP".into(), "8662".into()),
            ("X-Vc-Bdturing-Sdk-Version".into(), "4.0.3.cn".into()),
            ("X-Tt-Store-Region".into(), "cn".into()),
            ("X-Tt-Store-Region-Src".into(), "did".into()),
            ("X-Khronos".into(), ts.to_string()),
            ("X-Argus".into(), crate::sign::b64_encode(&crate::sign::le32(ts))),
            ("X-SS-Req-Ticket".into(), stamp.to_string()),
            ("X-SS-STUB".into(), stub),
            ("X-Gorgon".into(), crate::sign::passport::passport_gorgon(&query_enc, &body, ts, nonce)),
        ];
        let u = format!("{PASSPORT_DEVICE_API}/service/2/device_register/?{query_enc}");
        let r = do_request(&http, "POST", &u, &headers, Some(&body)).await?;
        if r.status != 200 {
            return Err("设备注册失败，请稍后重试".into());
        }
        let raw_body = if r.body.starts_with(&[0x74, 0x63, 5, 0x10, 0, 0]) {
            passport_tt_decrypt(&r.body)?
        } else {
            r.body
        };
        let p: serde_json::Value =
            serde_json::from_slice(&raw_body).map_err(|_| "设备注册响应无效".to_string())?;
        let did = crate::upstream::device::first_str(&p, &["device_id_str", "device_id"]);
        let iid = crate::upstream::device::first_str(&p, &["install_id_str", "install_id"]);
        if did.is_empty() || iid.is_empty() || did == "0" || iid == "0" {
            return Err("设备注册未返回有效身份".into());
        }
        let mut cookies = HashMap::new();
        for c in &r.cookies {
            if let Some(first) = c.split(';').next() {
                if let Some((k, v)) = first.split_once('=') {
                    let k = k.trim();
                    let v = v.trim();
                    if matches!(k, "store-region" | "store-region-src" | "ttreq" | "odin_tt") && !v.is_empty() {
                        cookies.insert(k.to_string(), v.to_string());
                    }
                }
            }
        }
        cookies.insert("install_id".into(), iid.clone());
        let egdi = crate::upstream::device::first_str(&p, &["klink_egdi", "egdi"]);
        let region = cookies.get("store-region").cloned().unwrap_or_default();
        Ok(Self {
            http,
            device: Device {
                id: did,
                iid,
                cdid,
                openudid,
                clientudid,
                region,
                egdi,
                last_config_time: crate::upstream::device::now_secs(),
            },
            cookies,
            token: String::new(),
            phone: String::new(),
            code: String::new(),
            mfa: None,
        })
    }

    async fn post(&mut self, path: &str, form: &[(&str, &str)]) -> Result<serde_json::Value, String> {
        let body = encode_form(form).into_bytes();
        let stamp = now_ms();
        let mut q = passport_query(&self.device);
        let (raw, signed_hdrs) =
            sign_passport_request_version(&mut q, &body, stamp, &self.device.id, "7.3.5.32")?;
        let mut h: Vec<(String, String)> = signed_hdrs.into_iter().collect();
        let region = self
            .cookies
            .get("store-region")
            .filter(|s| !s.is_empty())
            .cloned()
            .unwrap_or_else(|| "cn".into());
        h.push(("User-Agent".into(), DEFAULT_HONGGUO_UA.into()));
        h.push(("Accept".into(), "application/json".into()));
        h.push(("X-XS-From-Web".into(), "0".into()));
        h.push(("Sdk-Version".into(), "2".into()));
        h.push(("Passport-Sdk-Version".into(), "5051452".into()));
        h.push(("X-Vc-Bdturing-Sdk-Version".into(), "4.0.3.cn".into()));
        h.push(("Lc".into(), "101".into()));
        h.push(("X-SS-DP".into(), "8662".into()));
        h.push(("X-Tt-Store-Region".into(), region));
        h.push(("X-Tt-Store-Region-Src".into(), "did".into()));
        h.push(("Content-Type".into(), "application/x-www-form-urlencoded; charset=UTF-8".into()));
        if !self.token.is_empty() {
            h.push(("X-Tt-Token".into(), self.token.clone()));
        }
        if !self.cookies.is_empty() {
            let ck = self
                .cookies
                .iter()
                .map(|(k, v)| format!("{k}={v}"))
                .collect::<Vec<_>>()
                .join("; ");
            h.push(("Cookie".into(), ck));
        }

        let u = format!("{PASSPORT_API}{path}?{raw}");
        let r = do_request(&self.http, "POST", &u, &h, Some(&body)).await?;
        if r.status != 200 {
            return Err(format!("红果账号服务暂不可用（{}）", r.status));
        }
        for c in &r.cookies {
            if let Some(first) = c.split(';').next() {
                if let Some((k, v)) = first.split_once('=') {
                    let k = k.trim();
                    let v = v.trim();
                    if v.eq_ignore_ascii_case("deleted") {
                        self.cookies.remove(k);
                    } else if !k.is_empty() {
                        self.cookies.insert(k.to_string(), v.to_string());
                    }
                }
            }
        }
        r.json().map_err(|_| "红果账号服务返回异常".into())
    }

    pub async fn send_code(&mut self, phone: &str) -> Result<(), String> {
        let n = normalize_cn_phone(phone).ok_or_else(|| "请输入正确的 11 位手机号".to_string())?;
        let mob = xor5(&n);
        let typ = xor5("24");
        let r = self
            .post(
                "/passport/mobile/send_code/v1/",
                &[
                    ("mobile", &mob),
                    ("account_sdk_source", "app"),
                    ("passport_support_flow", "captcha,verify"),
                    ("mix_mode", "1"),
                    ("auto_read", "0"),
                    ("unbind_exist", "34"),
                    ("type", &typ),
                ],
            )
            .await?;
        if !login_success(&r) {
            return Err(login_failure(&r, "验证码发送失败"));
        }
        self.phone = n;
        self.code.clear();
        self.mfa = None;
        Ok(())
    }

    pub async fn login(&mut self, code: &str) -> Result<PhoneLoginOutcome, String> {
        if self.phone.is_empty() {
            return Err("请先获取验证码".into());
        }
        let code = code.trim();
        if !(4..=8).contains(&code.len()) || !code.bytes().all(|b| b.is_ascii_digit()) {
            return Err("请输入短信里的验证码".into());
        }
        self.code = code.to_string();
        let mob = xor5(&self.phone);
        let cd = xor5(&self.code);
        let r = self
            .post(
                "/passport/mobile/sms_login/",
                &[
                    ("mobile", &mob),
                    ("account_sdk_source", "app"),
                    ("passport_support_flow", "captcha,verify"),
                    ("mix_mode", "1"),
                    ("code", &cd),
                ],
            )
            .await?;
        if !login_success(&r) {
            if let Some(m) = parse_phone_mfa(&r["data"]) {
                let number = m.number.clone();
                let content = m.content.clone();
                self.mfa = Some(m);
                return Ok(PhoneLoginOutcome::Mfa { number, content });
            }
            return Err(login_failure(&r, "登录失败，请重试"));
        }
        Ok(PhoneLoginOutcome::Ok {
            cookies: self.cookies.clone(),
            device: self.device.clone(),
        })
    }

    pub async fn verify_mfa(&mut self) -> Result<PhoneLoginOutcome, String> {
        let m = self
            .mfa
            .clone()
            .ok_or_else(|| "验证已结束，请重新获取验证码".to_string())?;
        let biz = serde_json::json!({
            "passport_mfa_retry_tag": "1",
            "sms_code_key": m.code_key
        })
        .to_string();
        let r = self
            .post(
                "/passport/upsms/verify/",
                &[
                    ("encrypt_uid", &m.encrypt_uid),
                    ("verify_ticket", ""),
                    ("verify_scene", "sms_login"),
                    ("verify_reason", "ato"),
                    ("copywriting_key", "sms_login"),
                    ("ies_safety_diversion_tag", "mfa"),
                    ("new_verify_flow", ""),
                    ("new_authn_sdk_version", "1.1.31"),
                    ("new_authn_sdk_verify_reason", "ato"),
                    ("new_authn_sdk_verify_scene", "sms_login"),
                    ("request_tag_from", "h5"),
                    ("biz_params", &biz),
                    ("new_authn_sdk_log_id", &m.log_id),
                ],
            )
            .await?;
        if login_err_code(&r) == "1045" {
            return Ok(PhoneLoginOutcome::Pending);
        }
        if !login_success(&r) {
            return Err(login_failure(&r, "短信验证未通过"));
        }
        let d = &r["data"];
        let mut ticket = jstr(&d["ticket"]);
        if ticket.is_empty() {
            ticket = jstr(&d["verify_ticket"]);
        }
        if ticket.is_empty() {
            return Err("验证响应缺少登录票据".into());
        }
        let mob = xor5(&self.phone);
        let cd = xor5(&self.code);
        let ck = xor5(&m.code_key);
        let lr = self
            .post(
                "/passport/mobile/sms_login/",
                &[
                    ("mobile", &mob),
                    ("account_sdk_source", "app"),
                    ("passport_support_flow", "captcha,verify"),
                    ("mix_mode", "1"),
                    ("code", &cd),
                    ("passport_mfa_retry_tag", "1"),
                    ("sms_code_key", &ck),
                    ("verify_ticket", &ticket),
                ],
            )
            .await?;
        if !login_success(&lr) {
            return Err(login_failure(&lr, "验证后登录失败，请重试"));
        }
        self.mfa = None;
        Ok(PhoneLoginOutcome::Ok {
            cookies: self.cookies.clone(),
            device: self.device.clone(),
        })
    }
}

pub enum PhoneLoginOutcome {
    Ok {
        cookies: HashMap<String, String>,
        device: Device,
    },
    Mfa {
        number: String,
        content: String,
    },
    Pending,
}

fn parse_phone_mfa(data: &serde_json::Value) -> Option<PhoneMfa> {
    if !data.is_object() {
        return None;
    }
    let encrypt_uid = jstr(&data["encrypt_uid"]);
    let code_key = jstr(&data["sms_code_key"]);
    let log_id = jstr(&data["event_params"]["log_id"]);
    let mut number = String::new();
    let mut content = String::new();
    if let Some(ways) = data["verify_ways"].as_array() {
        for w in ways {
            if jstr(&w["verify_way"]) == "upsms" || !jstr(&w["sms_content"]).is_empty() {
                content = jstr(&w["sms_content"]);
                number = jstr(&w["channel_mobile"]);
                break;
            }
        }
    }
    if encrypt_uid.is_empty() || code_key.is_empty() || number.is_empty() || content.is_empty() {
        return None;
    }
    Some(PhoneMfa {
        number,
        content,
        encrypt_uid,
        code_key,
        log_id,
        started: Instant::now(),
    })
}

fn form_encode_sorted(q: &[(String, String)]) -> String {
    let mut sorted = q.to_vec();
    sorted.sort_by(|a, b| a.0.cmp(&b.0));
    let mut ser = url::form_urlencoded::Serializer::new(String::new());
    for (k, v) in sorted {
        ser.append_pair(&k, &v);
    }
    ser.finish()
}

#[allow(dead_code)]
fn _unused_auth_keep() {
    let _ = b64_decode;
    let _ = Duration::from_secs(1);
}
