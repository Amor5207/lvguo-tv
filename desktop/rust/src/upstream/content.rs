use crate::hotupdate::HotUpdateManager;
use crate::sign::passport::protocol_random;
use crate::sign::v5::PlaybackSigner;
use crate::sign::{encode_params, set_param, Params};
use crate::upstream::account::{check_ok, Account};
use crate::upstream::device::{
    build_http_client, do_request, first_str, img_url, is_digits, jnum, jstr, now_ms, nz,
    passport_tt_decrypt, passport_tt_encrypt, DeviceStore, APP_HOST, CONTENT_HOST, CONTENT_HOST2,
    CONTENT_UA,
};
use aes::cipher::{block_padding::Pkcs7, BlockDecryptMut, KeyIvInit};
use aes::Aes128;
use base64::engine::general_purpose::{STANDARD, STANDARD_NO_PAD, URL_SAFE, URL_SAFE_NO_PAD};
use base64::Engine;
use flate2::write::GzEncoder;
use flate2::Compression;
use regex::Regex;
use reqwest::Client;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha512};
use std::collections::{HashMap, HashSet};
use std::io::Write;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use uuid::Uuid;

type Aes128CbcDec = cbc::Decryptor<Aes128>;

pub const DEFAULT_CELL: &str = "7470092475068071998";
pub const BOARD_HOT: &str = "ranklist_hot_play_sc";
pub const BOARD_RECOMMEND: &str = "ranklist_hot_sc";
const FEED_CELL_ID: &str = "7294257141911650341";
const FEED_PLAN_ID: &str = "7294257258253254693";
const PATH_CELL_CHANGE: &str = "/reading/bookapi/bookmall/cell/change/v1/";
const PATH_BOOKMALL: &str = "/reading/bookapi/bookmall/tab/v1/";
const WEB_SEARCH_BASE: &str = "https://hongguoduanju.com";
const MINI_REEL_SINGLE_DEVICE_BASE: &str = "https://log5-applog-lq.fqnovel.com";
const MINI_REEL_SINGLE_UA: &str = "com.phoenix.read/73932 (Linux; U; Android 14; zh_CN; 23127PN0CC; Build/UKQ1.230804.001; Cronet/TTNetVersion:8d40f833 2026-03-03 QuicVersion:21ac1950 2025-11-18)";
const VIDEO_TAB_ENDPOINT: &str =
    "https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/video_tab/multi_video_model/v1/";
const VIDEO_TAB_UA: &str = "com.phoenix.read/73932 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)";
const LEGACY_UA: &str = "com.phoenix.read/73932 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)";

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Series {
    pub id: String,
    pub title: String,
    pub cover: String,
    pub episodes: i32,
    pub plays: i64,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub intro: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub category: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub first_vid: String,
    #[serde(default, skip_serializing_if = "is_zero_i32")]
    pub rank: i32,
}

fn is_zero_i32(v: &i32) -> bool {
    *v == 0
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Episode {
    pub index: i32,
    pub title: String,
    pub vid: String,
    pub duration: i32,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub cover: String,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Detail {
    pub id: String,
    pub title: String,
    pub cover: String,
    pub intro: String,
    pub count: i32,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub rating: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub heat: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub tag: String,
    pub plays: i64,
    pub actors: Vec<String>,
    pub episodes: Vec<Episode>,
    #[serde(default)]
    pub multi_season: bool,
    #[serde(default)]
    pub season_index: i32,
    #[serde(skip)]
    pub album_id: String,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Page {
    pub items: Vec<Series>,
    pub next: i32,
    pub more: bool,
    pub session: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FeedChannel {
    pub id: String,
    pub label: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CatOption {
    pub id: String,
    pub label: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CatGroup {
    pub name: String,
    pub items: Vec<CatOption>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CatBoard {
    pub id: String,
    pub label: String,
    pub groups: Vec<CatGroup>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CategoryOptions {
    pub types: Vec<CatOption>,
    pub boards: Vec<CatBoard>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Season {
    pub id: String,
    pub title: String,
    pub cover: String,
    pub number: i32,
    pub episodes: i32,
    #[serde(default)]
    pub current: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SearchResult {
    pub items: Vec<Series>,
    pub total: i32,
    pub more: bool,
    pub page: i32,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Gear {
    pub definition: String,
    pub codec: String,
    pub width: i32,
    pub height: i32,
    pub size: i64,
    #[serde(skip)]
    pub main_url: String,
    #[serde(skip)]
    pub backup_url: String,
    #[serde(skip)]
    pub backup_urls: Vec<String>,
    #[serde(skip)]
    pub encrypted: bool,
    #[serde(skip)]
    pub spade_a: String,
}

impl Gear {
    pub fn short_side(&self) -> i32 {
        if self.width == 0 {
            self.height
        } else if self.height == 0 {
            self.width
        } else {
            self.width.min(self.height)
        }
    }
}

#[derive(Clone)]
struct MiniReelDevice {
    device_id: String,
    install_id: String,
    cdid: String,
    #[allow(dead_code)]
    openudid: String,
    egdi: String,
}

struct CacheItem {
    at: Instant,
    ttl: Duration,
    val: CacheVal,
}

#[derive(Clone)]
enum CacheVal {
    Page(Page),
    Channels(Vec<FeedChannel>),
    Detail(Detail),
    Seasons(Vec<Season>),
    Gears(Vec<Gear>),
}

pub struct Content {
    pub http: Client,
    pub devices: Arc<DeviceStore>,
    pub hot: Arc<HotUpdateManager>,
    cache: Mutex<HashMap<String, CacheItem>>,
    selector: Mutex<Option<Value>>,
    playback_identity: tokio::sync::Mutex<Option<(MiniReelDevice, Instant)>>,
    last_route: Mutex<Option<(String, Instant)>>,
}

impl Content {
    pub fn new(http: Client, devices: Arc<DeviceStore>, hot: Arc<HotUpdateManager>) -> Arc<Self> {
        Arc::new(Self {
            http,
            devices,
            hot,
            cache: Mutex::new(HashMap::new()),
            selector: Mutex::new(None),
            playback_identity: tokio::sync::Mutex::new(None),
            last_route: Mutex::new(None),
        })
    }

    fn get_cached(&self, key: &str) -> Option<CacheVal> {
        let mut g = self.cache.lock().unwrap();
        if let Some(it) = g.get(key) {
            if it.at.elapsed() <= it.ttl {
                return Some(it.val.clone());
            }
        }
        g.remove(key);
        None
    }

    fn put_cached(&self, key: String, val: CacheVal, ttl: Duration) {
        let mut g = self.cache.lock().unwrap();
        if g.len() > 1500 {
            g.retain(|_, v| v.at.elapsed() <= v.ttl);
            if g.len() > 1500 {
                g.clear();
            }
        }
        g.insert(
            key,
            CacheItem {
                at: Instant::now(),
                ttl,
                val,
            },
        );
    }

    pub fn forget_play(&self, vid: &str) {
        let suffix = format!("|{vid}");
        let mut g = self.cache.lock().unwrap();
        g.retain(|k, _| !(k.starts_with("play|") && k.ends_with(&suffix)));
    }

    async fn call_raw(&self, cookie: &str, path: &str, body: Option<&Value>, extra: &[(String, String)]) -> Result<Value, String> {
        let mut last = None;
        let mut last_err = None;
        for attempt in 0..2 {
            let host = if attempt == 0 { CONTENT_HOST } else { CONTENT_HOST2 };
            let dev = self.devices.get().await?;
            let mut q = vec![
                ("aid".into(), "8662".into()), ("app_id".into(), "8662".into()),
                ("device_platform".into(), "android".into()), ("platform".into(), "android".into()),
                ("version_code".into(), "71932".into()), ("update_version_code".into(), "71932".into()),
                ("manifest_version_code".into(), "71932".into()), ("version_name".into(), "7.1.9.32".into()),
                ("device_type".into(), "25053RT47C".into()), ("device_brand".into(), "Redmi".into()),
                ("cdid".into(), dev.cdid), ("openudid".into(), dev.openudid),
                ("clientudid".into(), dev.clientudid), ("device_id".into(), dev.id), ("iid".into(), dev.iid),
                ("os".into(), "android".into()), ("os_version".into(), "16".into()),
                ("os_api".into(), "36".into()), ("language".into(), "zh".into()),
                ("ac".into(), "wifi".into()), ("ssmix".into(), "a".into()),
            ];
            for (k, v) in extra {
                set_param(&mut q, k, v);
            }
            let now = now_ms().to_string();
            set_param(&mut q, "_rticket", &now);
            let u = format!("https://{host}{path}?{}", encode_params(&q));
            let data = body.map(|b| serde_json::to_vec(b).unwrap_or_default());
            let method = if data.is_some() { "POST" } else { "GET" };
            let mut h = vec![
                ("User-Agent".into(), CONTENT_UA.into()),
                ("X-Xs-From-Web".into(), "0".into()),
                ("x-ss-req-ticket".into(), now),
                ("sdk-version".into(), "2".into()),
            ];
            if !cookie.is_empty() {
                h.push(("Cookie".into(), cookie.to_string()));
            }
            let stub_bytes = data.as_deref().unwrap_or(b"");
            h.push(("X-SS-STUB".into(), hex::encode_upper(crate::sign::md5_bytes(stub_bytes))));
            if data.is_some() {
                h.push(("Content-Type".into(), "application/json; charset=utf-8".into()));
            }
            match do_request(&self.http, method, &u, &h, data.as_deref()).await {
                Ok(r) => {
                    if let Ok(m) = r.json() {
                        if r.status < 500 && jnum(&m["code"]) as i64 != 101116 {
                            return Ok(m);
                        }
                        last = Some(m);
                    }
                }
                Err(e) => last_err = Some(e),
            }
        }
        if let Some(m) = last {
            Ok(m)
        } else {
            Err(last_err.unwrap_or_else(|| "网络请求未完成".into()))
        }
    }

    async fn call_dynamic_api(
        &self,
        cookie: &str,
        name: &str,
        extra_query: &[(String, String)],
        extra_body: Option<&Value>,
    ) -> Result<Value, String> {
        let cfg = self
            .hot
            .api_config(name)
            .ok_or_else(|| format!("未配置接口策略: {name}"))?;
        let mut method = cfg.method.trim().to_ascii_uppercase();
        if method.is_empty() {
            method = if extra_body.is_some() || cfg.body.is_some() {
                "POST".into()
            } else {
                "GET".into()
            };
        }
        let sign_mode = cfg.sign.trim().to_ascii_lowercase();
        let mut merged_q = extra_query.to_vec();
        for (k, v) in &cfg.query {
            set_param(&mut merged_q, k, v);
        }
        let merged_body = merge_json(cfg.body.as_ref(), extra_body);
        if sign_mode == "none" {
            return self
                .call_raw(cookie, &cfg.path, merged_body.as_ref(), &merged_q)
                .await;
        }

        let dev = self.devices.get().await?;
        let signer = PlaybackSigner::new(&dev.id, "7.4.3.32")?;
        let raw = if method == "POST" {
            merged_body
                .as_ref()
                .map(|b| serde_json::to_vec(b).map_err(|e| e.to_string()))
                .transpose()?
        } else {
            None
        };

        let mut hosts = vec![cfg.host.clone()];
        if !cfg.backup_host.is_empty() && cfg.backup_host != cfg.host {
            hosts.push(cfg.backup_host.clone());
        }
        let mut last = None;
        let mut last_status = 0u16;
        let mut last_err = None;
        for host in hosts {
            let mut p = vec![
                ("device_platform".into(), "android".into()), ("os".into(), "android".into()),
                ("ssmix".into(), "a".into()), ("_rticket".into(), "0".into()),
                ("cdid".into(), dev.cdid.clone()), ("channel".into(), "vivo_8662_64".into()),
                ("aid".into(), "8662".into()), ("app_name".into(), "novelread".into()),
                ("version_code".into(), "74332".into()), ("version_name".into(), "7.4.3.32".into()),
                ("manifest_version_code".into(), "74332".into()), ("update_version_code".into(), "74332".into()),
                ("resolution".into(), "1920*1080".into()), ("dpi".into(), "280".into()),
                ("device_type".into(), "PGT-AN10".into()), ("device_brand".into(), "HONOR".into()),
                ("language".into(), "zh".into()), ("os_api".into(), "31".into()), ("os_version".into(), "12".into()),
                ("ac".into(), "wifi".into()), ("host_abi".into(), "arm64-v8a".into()),
                ("dragon_device_type".into(), "phone".into()), ("pv_player".into(), "74332".into()),
                ("compliance_status".into(), "0".into()), ("need_personal_recommend".into(), "1".into()),
                ("player_so_load".into(), "1".into()), ("is_android_pad_screen".into(), "0".into()),
                ("rom_version".into(), "V417IR+release-keys".into()),
                ("iid".into(), dev.iid.clone()), ("device_id".into(), dev.id.clone()),
            ];
            if !dev.egdi.is_empty() {
                set_param(&mut p, "klink_egdi", &dev.egdi);
            }
            for (k, v) in &merged_q {
                set_param(&mut p, k, v);
            }
            let endpoint = format!("https://{host}{}", cfg.path);
            let signed = signer.sign_mobile_v5(&endpoint, &p, raw.as_deref(), now_ms(), None)?;
            let mut h = vec![
                ("User-Agent".into(), LEGACY_UA.into()),
                ("Accept".into(), "application/json".into()),
            ];
            for (k, v) in signed.headers {
                if raw.is_none()
                    && (k.eq_ignore_ascii_case("Content-Type") || k.eq_ignore_ascii_case("x-ss-stub"))
                {
                    continue;
                }
                h.push((k, v));
            }
            if !cookie.is_empty() {
                h.push(("Cookie".into(), cookie.to_string()));
            }
            match do_request(&self.http, &method, &signed.url, &h, raw.as_deref()).await {
                Ok(r) => {
                    last_status = r.status;
                    match r.json() {
                        Ok(m) => {
                            if r.status < 500 && jnum(&m["code"]) as i64 != 101116 {
                                return Ok(m);
                            }
                            last = Some(m);
                        }
                        Err(e) => last_err = Some(e),
                    }
                }
                Err(e) => last_err = Some(e),
            }
        }
        if let Some(m) = last {
            return Ok(m);
        }
        if last_status == 200 {
            return Err("片源服务暂时拒绝了请求，请稍后再试".into());
        }
        Err(last_err.unwrap_or_else(|| "网络请求未完成".into()))
    }

    async fn page(
        &self,
        cookie: &str,
        typ: &str,
        board: &str,
        filter: &str,
        offset: i32,
        session: &str,
    ) -> Result<Page, String> {
        let sess = if session.is_empty() {
            Uuid::new_v4().to_string()
        } else {
            session.to_string()
        };
        let q = vec![
            ("cell_id".into(), DEFAULT_CELL.into()),
            ("tab_type".into(), "26".into()),
            ("client_req_type".into(), "2".into()),
            ("client_template".into(), "2".into()),
            ("screen_width_px".into(), "1350".into()),
            ("selected_items".into(), typ.into()),
            ("sub_selected_items".into(), board.into()),
            ("panel_selected_items".into(), filter.into()),
            ("offset".into(), offset.to_string()),
            ("session_uuid".into(), sess.clone()),
            ("session_id".into(), sess.clone()),
        ];
        let m = self.call_dynamic_api(cookie, "rank", &q, None).await?;
        let cell = &m["data"]["cell_view"];
        if jnum(&m["code"]) as i64 != 0 || !cell.is_object() {
            let msg = jstr(&m["message"]);
            return Err(if msg.is_empty() { "榜单加载失败".into() } else { msg });
        }
        let mut next = jnum(&cell["next_offset"]) as i32;
        if next == 0 {
            next = offset;
        }
        let more = cell["has_more"].as_bool() == Some(true) && next > offset;
        let out_sess = nz(jstr(&cell["session_id"]), sess);
        let mut items = Vec::new();
        let mut seen = HashSet::new();
        collect_series(&cell["cell_data"], &mut items, &mut seen);
        for (i, it) in items.iter_mut().enumerate() {
            it.rank = offset + (i as i32) + 1;
        }
        if cell["cell_selector"].is_object() {
            *self.selector.lock().unwrap() = Some(cell["cell_selector"].clone());
        }
        Ok(Page {
            items,
            next,
            more,
            session: out_sess,
        })
    }

    pub async fn rank(&self, cookie: &str, board: &str, offset: i32, session: &str) -> Result<Page, String> {
        let board = if board.is_empty() { BOARD_HOT } else { board };
        let cache_key = if offset == 0 {
            Some(format!("rank:{board}:0"))
        } else if offset > 0 && offset <= 30 && !session.is_empty() {
            Some(format!("rank:{board}:{offset}:{session}"))
        } else {
            None
        };
        if let Some(k) = &cache_key {
            if let Some(CacheVal::Page(p)) = self.get_cached(k) {
                return Ok(p);
            }
        }
        let p = self.page(cookie, "all", board, "", offset, session).await?;
        if !p.items.is_empty() {
            if let Some(k) = cache_key {
                self.put_cached(k, CacheVal::Page(p.clone()), Duration::from_secs(180));
            }
        }
        Ok(p)
    }

    pub async fn feed_channels(&self, acct: &Account) -> Vec<FeedChannel> {
        let key = format!("feedch:{}", if acct.user.logged_in() { "u" } else { "anon" });
        if let Some(CacheVal::Channels(v)) = self.get_cached(&key) {
            return v;
        }
        let mut out = vec![FeedChannel {
            id: "16".into(),
            label: "推荐".into(),
        }];
        let q = vec![
            ("bottom_tab_type".into(), "7".into()),
            ("landing_bottom_tab_type".into(), "7".into()),
            ("need_personal_recommend".into(), "1".into()),
            ("device_level".into(), "3".into()),
            ("screen_width_px".into(), "1078".into()),
            ("is_horizontal_screen".into(), "false".into()),
            ("tab_type".into(), "16".into()),
            ("offset".into(), "0".into()),
            ("session_id".into(), String::new()),
            ("client_req_type".into(), "1".into()),
        ];
        let Ok(m) = acct.signed(APP_HOST, PATH_BOOKMALL, &q, None).await else {
            return out;
        };
        let mut have = HashSet::new();
        if let Some(arr) = m["data"]["tab_item"].as_array() {
            for tv in arr {
                have.insert(jstr(&tv["tab_type"]));
            }
        }
        for (id, label) in [("36", "漫剧"), ("39", "真人剧")] {
            if have.contains(id) {
                out.push(FeedChannel {
                    id: id.into(),
                    label: label.into(),
                });
            }
        }
        self.put_cached(key, CacheVal::Channels(out.clone()), Duration::from_secs(1800));
        out
    }

    pub async fn for_you(
        &self,
        acct: &Account,
        channel: &str,
        offset: i32,
        cursor: &str,
        session: &str,
    ) -> Result<(Page, String, String), String> {
        let ch = if matches!(channel, "16" | "36" | "39") {
            channel
        } else {
            "16"
        };
        let off = offset.max(0);
        let (mut cell, mut plan) = (FEED_CELL_ID.to_string(), FEED_PLAN_ID.to_string());
        if ch != "16" {
            cell.clear();
            plan.clear();
            if let Some((a, b)) = cursor.split_once('.') {
                if is_digits(a) && is_digits(b) {
                    cell = a.to_string();
                    plan = b.to_string();
                }
            }
        }

        if cell.is_empty() {
            let q = vec![
                ("bottom_tab_type".into(), "7".into()),
                ("landing_bottom_tab_type".into(), "7".into()),
                ("need_personal_recommend".into(), "1".into()),
                ("device_level".into(), "3".into()),
                ("screen_width_px".into(), "1078".into()),
                ("is_horizontal_screen".into(), "false".into()),
                ("disable_digg_stat".into(), "false".into()),
                ("auth_aweme".into(), "true".into()),
                ("auth_backward".into(), "true".into()),
                ("has_video_cache".into(), "false".into()),
                ("last_tab_type".into(), "0".into()),
                ("last_tab_index".into(), "0".into()),
                ("tab_index".into(), "0".into()),
                ("req_rank_category_id".into(), "0".into()),
                ("video_type_preferences_str".into(), "[]".into()),
                ("tab_type".into(), ch.into()),
                ("offset".into(), "0".into()),
                ("session_id".into(), String::new()),
                ("client_req_type".into(), "1".into()),
            ];
            let m = acct.signed(APP_HOST, PATH_BOOKMALL, &q, None).await?;
            if let Some(tabs) = m["data"]["tab_item"].as_array() {
                for t in tabs {
                    if jstr(&t["tab_type"]) != ch {
                        continue;
                    }
                    let sess = jstr(&t["session_id"]);
                    let mut items = Vec::new();
                    let mut seen = HashSet::new();
                    collect_series(&t["cell_data"], &mut items, &mut seen);
                    items.retain(|s| !s.cover.is_empty() && s.episodes > 0);
                    if let Some(cells) = t["cell_data"].as_array() {
                        for cl in cells {
                            let id = nz(jstr(&cl["cell_id_str"]), jstr(&cl["cell_id"]));
                            if id != "0" && is_digits(&id) {
                                cell = id;
                                break;
                            }
                        }
                    }
                    plan = jstr(&t["bookstore_id"]);
                    if cell.is_empty() || !is_digits(&plan) {
                        return Ok((
                            Page {
                                items,
                                next: 0,
                                more: false,
                                session: sess.clone(),
                            },
                            String::new(),
                            sess,
                        ));
                    }
                    return Ok((
                        Page {
                            items,
                            next: 0,
                            more: true,
                            session: sess.clone(),
                        },
                        format!("{cell}.{plan}"),
                        sess,
                    ));
                }
            }
            return Err("推荐加载失败".into());
        }

        let q = vec![
            ("bottom_tab_type".into(), "7".into()),
            ("client_req_type".into(), "2".into()),
            ("client_template".into(), "7".into()),
            ("change_type".into(), "0".into()),
            ("category_id".into(), "0".into()),
            ("cell_sub_id".into(), "0".into()),
            ("limit".into(), "0".into()),
            ("need_personal_recommend".into(), "1".into()),
            ("video_type_preferences_str".into(), "[]".into()),
            ("cell_id".into(), cell.clone()),
            ("plan_id".into(), plan.clone()),
            ("tab_type".into(), ch.into()),
            ("offset".into(), off.to_string()),
            ("session_id".into(), session.into()),
            ("filter_ids".into(), String::new()),
        ];
        let m = acct.signed(APP_HOST, PATH_CELL_CHANGE, &q, None).await?;
        let d = &m["data"];
        let cv = &d["cell_view"];
        if jnum(&m["code"]) as i64 != 0 || !cv.is_object() {
            return Err("推荐加载失败".into());
        }
        let out_sess = nz(jstr(&d["session_id"]), session.to_string());
        let mut items = Vec::new();
        let mut seen = HashSet::new();
        collect_series(&cv["cell_data"], &mut items, &mut seen);
        items.retain(|s| !s.cover.is_empty() && s.episodes > 0);
        let next = jnum(&d["next_offset"]) as i32;
        let more = d["has_more"].as_bool() == Some(true) && next > off && !items.is_empty();
        Ok((
            Page {
                items,
                next,
                more,
                session: out_sess.clone(),
            },
            format!("{cell}.{plan}"),
            out_sess,
        ))
    }

    pub async fn category_options(&self, cookie: &str, typ: &str) -> Result<CategoryOptions, String> {
        let typ = if typ.is_empty() { "all" } else { typ };
        let need_fetch = {
            let g = self.selector.lock().unwrap();
            g.as_ref().map(|s| !s["outer_row"].is_object()).unwrap_or(true)
        };
        if need_fetch {
            let _ = self.page(cookie, "all", BOARD_RECOMMEND, "", 0, "").await?;
        }
        let sel = self
            .selector
            .lock()
            .unwrap()
            .clone()
            .unwrap_or(Value::Null);
        let mut out = CategoryOptions {
            types: Vec::new(),
            boards: Vec::new(),
        };
        if let Some(items) = sel["outer_row"]["items"].as_array() {
            for item in items {
                let id = jstr(&item["selector_item_id"]);
                if !matches!(id.as_str(), "all" | "human" | "comic_series_rank" | "ai_playlet") {
                    continue;
                }
                out.types.push(CatOption {
                    id: id.clone(),
                    label: jstr(&item["show_name"]),
                });
                if id != typ {
                    continue;
                }
                if let Some(bs) = item["sub_cell_selector"]["outer_row"]["items"].as_array() {
                    for bo in bs {
                        let mut board = CatBoard {
                            id: jstr(&bo["selector_item_id"]),
                            label: jstr(&bo["show_name"]),
                            groups: Vec::new(),
                        };
                        if let Some(rows) = bo["panel_selector"]["inner_rows"].as_array() {
                            for go in rows {
                                let mut grp = CatGroup {
                                    name: jstr(&go["row_name"]),
                                    items: Vec::new(),
                                };
                                if let Some(opts) = go["items"].as_array() {
                                    for op in opts {
                                        let oid = jstr(&op["selector_item_id"]);
                                        if !oid.is_empty() {
                                            grp.items.push(CatOption {
                                                id: oid,
                                                label: jstr(&op["show_name"]),
                                            });
                                        }
                                    }
                                }
                                board.groups.push(grp);
                            }
                        }
                        out.boards.push(board);
                    }
                }
            }
        }
        Ok(out)
    }

    pub async fn category_page(
        &self,
        cookie: &str,
        typ: &str,
        board: &str,
        selected: &HashMap<String, String>,
        offset: i32,
        session: &str,
    ) -> Result<Page, String> {
        let typ = if typ.is_empty() { "all" } else { typ };
        let opts = self.category_options(cookie, typ).await?;
        let cur = opts
            .boards
            .iter()
            .find(|b| b.id == board)
            .or_else(|| opts.boards.first())
            .ok_or_else(|| "分类暂不可用".to_string())?;
        let board_id = cur.id.clone();

        let mut conds: Vec<(String, String)> = Vec::new();
        for g in &cur.groups {
            if let Some(want) = selected.get(&g.name) {
                for it in &g.items {
                    if !want.is_empty() && &it.id == want {
                        conds.push((it.id.clone(), it.label.clone()));
                    }
                }
            }
        }
        let mut anchor = String::new();
        for (id, _) in &conds {
            anchor = id.clone();
        }
        for (id, _) in &conds {
            if id.starts_with("gender_") {
                anchor = id.clone();
            }
        }

        let mut out = Page {
            items: Vec::new(),
            next: offset,
            more: false,
            session: session.to_string(),
        };
        let mut seen = HashSet::new();
        for _ in 0..4 {
            let part = match self
                .page(cookie, typ, &board_id, &anchor, out.next, &out.session)
                .await
            {
                Ok(p) => p,
                Err(e) => {
                    if !out.items.is_empty() {
                        break;
                    }
                    return Err(e);
                }
            };
            out.next = part.next;
            out.session = part.session;
            out.more = part.more;
            for s in part.items {
                let okm = conds.iter().all(|(id, label)| {
                    id.starts_with("gender_") || matches_category(&s.category, label)
                });
                if okm && seen.insert(s.id.clone()) {
                    out.items.push(s);
                }
            }
            if !out.more || out.items.len() >= 12 {
                break;
            }
        }
        Ok(out)
    }

    pub async fn detail(&self, cookie: &str, id: &str) -> Result<Detail, String> {
        if !is_digits(id) {
            return Err("剧集编号无效".into());
        }
        let cache_key = format!("detail:{id}");
        if let Some(CacheVal::Detail(d)) = self.get_cached(&cache_key) {
            return Ok(d);
        }
        let body = serde_json::json!({ "series_id": id });
        let m = self
            .call_dynamic_api(cookie, "detail", &[], Some(&body))
            .await?;
        if jnum(&m["code"]) as i64 != 0 {
            return Err(nz(jstr(&m["message"]), "详情不可用".into()));
        }
        let data = &m["data"];
        let vd = if data["video_data"].is_object() {
            &data["video_data"]
        } else if data[id]["video_data"].is_object() {
            &data[id]["video_data"]
        } else if data["video_detail_data"][id]["video_data"].is_object() {
            &data["video_detail_data"][id]["video_data"]
        } else {
            return Err("详情节点为空".into());
        };

        let title = jstr(&vd["series_title"]);
        let mut intro = jstr(&vd["series_intro"]);
        if intro.is_empty() {
            intro = jstr(&m["data"]["album_data"]["intro"]);
        }
        let mut rating = String::new();
        if let Some(arr) = vd["secondary_infos"].as_array() {
            for s in arr {
                if jnum(&s["data_type"]) as i32 == 4 {
                    rating = jstr(&s["content"]);
                    break;
                }
            }
        }
        let mut heat = String::new();
        if let Some(arr) = vd["series_sub_title_list"].as_array() {
            for s in arr {
                let t = jstr(s);
                if let Some(rest) = t.strip_prefix("红果热度值") {
                    heat = rest.to_string();
                }
            }
        }
        let mut actors = Vec::new();
        if let Some(arr) = vd["celebrities"].as_array() {
            for ce in arr {
                let n = jstr(&ce["nickname"]);
                if !n.is_empty() && actors.len() < 8 {
                    actors.push(n);
                }
            }
        }
        let mut episodes = Vec::new();
        if let Some(arr) = vd["video_list"].as_array() {
            for (i, eo) in arr.iter().enumerate() {
                let vid = jstr(&eo["vid"]);
                if vid.is_empty() {
                    continue;
                }
                let mut idx = jnum(&eo["vid_index"]) as i32;
                if idx == 0 {
                    idx = (i + 1) as i32;
                }
                let ep_title = nz(jstr(&eo["title"]), format!("第{idx}集"));
                let ep_cov = img_url(&nz(jstr(&eo["episode_cover"]), jstr(&eo["cover"])));
                episodes.push(Episode {
                    index: idx,
                    title: ep_title,
                    vid,
                    duration: jnum(&eo["duration"]) as i32,
                    cover: ep_cov,
                });
            }
        }
        let album_id = jstr(&vd["related_album_id"]);
        let d = Detail {
            id: id.to_string(),
            title: title.clone(),
            cover: img_url(&jstr(&vd["series_cover"])),
            intro,
            count: jnum(&vd["episode_cnt"]) as i32,
            rating,
            heat,
            tag: jstr(&vd["video_tag_info"]["text"]),
            plays: jnum(&vd["series_play_cnt"]) as i64,
            actors,
            episodes,
            multi_season: !album_id.is_empty(),
            season_index: explicit_season_number(&title),
            album_id,
        };
        self.put_cached(cache_key, CacheVal::Detail(d.clone()), Duration::from_secs(600));
        Ok(d)
    }

    pub async fn seasons(&self, cookie: &str, series_id: &str, quick: bool) -> Result<Vec<Season>, String> {
        let cur = self.detail(cookie, series_id).await?;
        if !cur.multi_season || cur.album_id.is_empty() {
            return Ok(Vec::new());
        }
        if let Some(CacheVal::Seasons(list)) = self.get_cached(&format!("seasons:{}", cur.album_id)) {
            return Ok(mark_current_season(list, series_id));
        }
        if quick {
            if let Some(CacheVal::Seasons(list)) =
                self.get_cached(&format!("seasons_quick:{}", cur.album_id))
            {
                return Ok(mark_current_season(list, series_id));
            }
        }
        let q = vec![
            ("album_id".into(), cur.album_id.clone()),
            ("count".into(), "100".into()),
            ("offset".into(), "0".into()),
        ];
        let m = self.call_dynamic_api(cookie, "album_videos", &q, None).await?;
        let videos = m["data"]["video_data_list"]
            .as_array()
            .cloned()
            .unwrap_or_default();
        if videos.is_empty() {
            return Ok(Vec::new());
        }

        if quick {
            let mut order: Vec<String> = Vec::new();
            let mut by_title: HashMap<String, (String, String, String, bool)> = HashMap::new();
            for v in &videos {
                let common = &v["common"];
                if !common.is_object() {
                    continue;
                }
                let id = jstr(&v["video_id"]);
                let title = jstr(&common["title"]).trim().to_string();
                if !is_digits(&id) || title.is_empty() {
                    continue;
                }
                let real = jnum(&common["duration"]) > 0.0;
                let cov = img_url(&jstr(&common["cover"]));
                let key = normalize_season_title(&title);
                if let Some(old) = by_title.get_mut(&key) {
                    if real && !old.3 {
                        *old = (id, title, cov, true);
                    }
                } else {
                    by_title.insert(key.clone(), (id, title, cov, real));
                    order.push(key);
                }
            }
            let expected = cur.season_index;
            let cur_pos = order
                .iter()
                .position(|k| by_title[k].0 == series_id)
                .map(|p| p as i32)
                .unwrap_or(-1);
            if expected == 0 || (cur_pos >= 0 && cur_pos + 1 == expected) {
                let mut out = Vec::new();
                for k in order {
                    let (id, title, cov, real) = &by_title[&k];
                    if !*real && !title.contains('季') {
                        continue;
                    }
                    let mut n = explicit_season_number(title);
                    if id == series_id && expected > 0 {
                        n = expected;
                    }
                    let eps = if id == series_id && !cur.episodes.is_empty() {
                        cur.episodes.len() as i32
                    } else if *real {
                        1
                    } else {
                        0
                    };
                    out.push(Season {
                        id: id.clone(),
                        title: title.clone(),
                        cover: cov.clone(),
                        number: n,
                        episodes: eps,
                        current: false,
                    });
                }
                assign_season_numbers(&mut out);
                out.sort_by_key(|s| s.number);
                if out.len() <= 1 {
                    return Ok(Vec::new());
                }
                self.put_cached(
                    format!("seasons_quick:{}", cur.album_id),
                    CacheVal::Seasons(out.clone()),
                    Duration::from_secs(600),
                );
                return Ok(mark_current_season(out, series_id));
            }
        }

        // Full batch fetch fallback
        let mut ids = Vec::new();
        let mut seen = HashSet::new();
        for v in &videos {
            let id = jstr(&v["video_id"]);
            if is_digits(&id) && seen.insert(id.clone()) {
                ids.push(id);
            }
        }
        if seen.insert(series_id.to_string()) {
            ids.push(series_id.to_string());
        }
        let mut out = Vec::new();
        for chunk in ids.chunks(21) {
            let body = serde_json::json!({ "series_id": chunk.join(",") });
            if let Ok(bm) = self
                .call_dynamic_api(cookie, "detail_batch", &[], Some(&body))
                .await
            {
                let mut data = &bm["data"];
                if data["video_detail_data"].is_object() {
                    data = &data["video_detail_data"];
                }
                if jnum(&bm["code"]) as i64 == 0 && data.is_object() {
                    for id in chunk {
                        let vd = &data[id]["video_data"];
                        if !vd.is_object() {
                            continue;
                        }
                        if jstr(&vd["related_album_id"]) != cur.album_id {
                            continue;
                        }
                        let title = jstr(&vd["series_title"]);
                        out.push(Season {
                            id: id.clone(),
                            title: title.clone(),
                            cover: img_url(&jstr(&vd["series_cover"])),
                            number: explicit_season_number(&title),
                            episodes: jnum(&vd["episode_cnt"]) as i32,
                            current: false,
                        });
                    }
                }
            }
        }
        assign_season_numbers(&mut out);
        out.sort_by_key(|s| s.number);
        if out.len() <= 1 {
            return Ok(Vec::new());
        }
        self.put_cached(
            format!("seasons:{}", cur.album_id),
            CacheVal::Seasons(out.clone()),
            Duration::from_secs(600),
        );
        Ok(mark_current_season(out, series_id))
    }

    pub async fn comments(&self, cookie: &str, id: &str, ep_vid: &str, offset: i32) -> Result<Value, String> {
        let mut q = vec![
            ("book_id".into(), id.into()),
            ("group_id".into(), id.into()),
        ];
        let mut kind = "book";
        if !ep_vid.is_empty() {
            q.push(("item_id".into(), ep_vid.into()));
            set_param(&mut q, "group_id", ep_vid);
            kind = "item";
        }
        q.push(("offset".into(), offset.to_string()));
        q.push(("count".into(), "20".into()));
        q.push(("sort".into(), "1".into()));
        let m = self
            .call_dynamic_api(cookie, &format!("comments_{kind}"), &q, None)
            .await?;
        let d = &m["data"];
        if jnum(&m["code"]) as i64 != 0 || !d.is_object() {
            return Err("评论加载未完成".into());
        }
        let items = extract_comment_rows(d);
        Ok(serde_json::json!({
            "items": items,
            "total": d["total"]
        }))
    }

    pub async fn search(&self, acct: &Account, query: &str, page: i32) -> Result<SearchResult, String> {
        let query = query.trim();
        if query.is_empty() || query.chars().count() > 100 {
            return Err("请输入剧名，最多100字".into());
        }
        let page = page.max(1);
        if page == 1 {
            if let Ok(mut r) = self.app_search(acct, query).await {
                if !r.items.is_empty() {
                    if r.items.len() < 12 {
                        if let Ok(wr) = self.web_search(query, 1).await {
                            let mut seen: HashSet<String> =
                                r.items.iter().map(|s| s.id.clone()).collect();
                            for s in wr.items {
                                if seen.insert(s.id.clone()) {
                                    r.items.push(s);
                                }
                            }
                            if wr.more {
                                r.more = true;
                            }
                        }
                    }
                    return Ok(r);
                }
            }
        }
        self.web_search(query, page).await
    }

    async fn app_search(&self, acct: &Account, query: &str) -> Result<SearchResult, String> {
        let login = if acct.user.logged_in() { "1" } else { "0" };
        let q = vec![
            ("version_code".into(), "73532".into()),
            ("version_name".into(), "7.3.5.32".into()),
            ("manifest_version_code".into(), "73532".into()),
            ("update_version_code".into(), "73532".into()),
            ("pv_player".into(), "73532".into()),
            ("query".into(), query.into()),
            ("count".into(), "0".into()),
            ("tab_type".into(), "11".into()),
            ("offset".into(), "0".into()),
            ("bookshelf_search_plan".into(), "4".into()),
            ("search_source".into(), "0".into()),
            ("use_correct".into(), "true".into()),
            ("user_is_login".into(), login.into()),
        ];
        let m = acct
            .signed(APP_HOST, "/reading/bookapi/search/tab/v1/", &q, None)
            .await?;
        if jnum(&m["code"]) as i64 != 0 {
            return Err("搜索服务暂不可用".into());
        }
        if let Some(tabs) = m["search_tabs"].as_array() {
            for tab in tabs {
                if jnum(&tab["tab_type"]) as i32 != 11 {
                    continue;
                }
                let mut items = Vec::new();
                let mut seen = HashSet::new();
                if let Some(rows) = tab["data"].as_array() {
                    for row in rows {
                        collect_app_cell(row, &mut items, &mut seen);
                    }
                }
                return Ok(SearchResult {
                    items,
                    total: jnum(&tab["expected_num"]) as i32,
                    more: false,
                    page: 1,
                });
            }
        }
        Err("搜索响应缺少结果".into())
    }

    async fn web_search(&self, query: &str, page: i32) -> Result<SearchResult, String> {
        let encoded_q: String = url::form_urlencoded::byte_serialize(query.as_bytes()).collect();
        let mut path = format!("/api/search/result?query={encoded_q}");
        if page > 1 {
            path.push_str(&format!("&page={page}"));
        }
        let u = format!("{WEB_SEARCH_BASE}{path}");
        let h = vec![
            ("User-Agent".into(), "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36".into()),
            ("Referer".into(), format!("{WEB_SEARCH_BASE}/")),
            ("Accept".into(), "application/json".into()),
            ("Accept-Language".into(), "zh-CN,zh;q=0.9".into()),
        ];
        let r = do_request(&self.http, "GET", &u, &h, None).await?;
        if r.status != 200 {
            return Err("搜索服务暂不可用，请重试".into());
        }
        let data = r.json().map_err(|_| "搜索服务响应异常，请重试".to_string())?;
        let mut items = Vec::new();
        let mut seen = HashSet::new();
        if let Some(rows) = data["searchList"].as_array() {
            for row in rows {
                if !row["video_data"].is_null() {
                    collect_series(&row["video_data"], &mut items, &mut seen);
                }
            }
        }
        let mut total = jnum(&data["totalCount"]) as i32;
        let mut more = data["hasMore"].as_bool() == Some(true);
        if page == 1 && !items.is_empty() {
            let key = query.replace(' ', "").to_lowercase();
            let hit = items.iter().any(|s| {
                s.title.replace(' ', "").to_lowercase().contains(&key)
                    || s.intro.replace(' ', "").to_lowercase().contains(&key)
            });
            if !hit {
                items.clear();
                total = 0;
                more = false;
            }
        }
        Ok(SearchResult {
            items,
            total,
            more,
            page,
        })
    }

    pub async fn suggest(&self, acct: &Account, query: &str) -> Vec<String> {
        let query = query.trim();
        let mut out = Vec::new();
        if query.is_empty() || query.chars().count() > 50 {
            return out;
        }
        let login = if acct.user.logged_in() { "1" } else { "0" };
        let q = vec![
            ("version_code".into(), "72232".into()),
            ("version_name".into(), "7.2.2.32".into()),
            ("manifest_version_code".into(), "72232".into()),
            ("update_version_code".into(), "72232".into()),
            ("pv_player".into(), "72232".into()),
            ("q".into(), query.into()),
            ("count".into(), "0".into()),
            ("tab_type".into(), "11".into()),
            ("bookshelf_search_plan".into(), "4".into()),
            ("search_source".into(), "0".into()),
            ("bookstore_tab".into(), "0".into()),
            ("user_is_login".into(), login.into()),
        ];
        let Ok(m) = acct
            .signed(APP_HOST, "/reading/bookapi/search/suggest/v1/", &q, None)
            .await
        else {
            return out;
        };
        if jnum(&m["code"]) as i64 != 0 {
            return out;
        }
        let mut seen = HashSet::new();
        if let Some(arr) = m["data"]["query_result_v2"].as_array() {
            for r in arr {
                if jnum(&r["show_type"]) as i32 == 2 {
                    continue;
                }
                let n = jstr(&r["name"]).trim().to_string();
                if !n.is_empty() && seen.insert(n.clone()) {
                    out.push(n);
                    if out.len() >= 10 {
                        break;
                    }
                }
            }
        }
        out
    }

    async fn get_playback_identity(&self, force_refresh: bool) -> Result<MiniReelDevice, String> {
        let mut g = self.playback_identity.lock().await;
        if !force_refresh {
            if let Some((dev, exp)) = &*g {
                if Instant::now() < *exp {
                    return Ok(dev.clone());
                }
            }
        }
        let dev = register_mini_reel_device(&self.http).await?;
        *g = Some((dev.clone(), Instant::now() + Duration::from_secs(7200)));
        Ok(dev)
    }

    pub async fn play(&self, vid: &str, fresh: bool) -> Result<Vec<Gear>, String> {
        if !is_digits(vid) {
            return Err("视频编号无效".into());
        }
        if fresh {
            self.forget_play(vid);
        } else if let Some(CacheVal::Gears(g)) = self.get_cached(&format!("play|{vid}")) {
            return Ok(g);
        }

        // Check dynamically hot-loaded algorithm module first
        if let Some(algo) = self.hot.active_algo() {
            let req = serde_json::json!({ "vid": vid, "fresh": fresh }).to_string();
            if let Some(resp_str) = algo.invoke_resolve_play(&req) {
                if let Ok(v) = serde_json::from_str::<serde_json::Value>(&resp_str) {
                    if v["ok"].as_bool() == Some(true) {
                        if let Ok(gears) = parse_play_gears(&self.http, &v, vid).await {
                            if has_compatible_gear(&gears) {
                                self.put_cached(
                                    format!("play|{vid}"),
                                    CacheVal::Gears(gears.clone()),
                                    Duration::from_secs(60),
                                );
                                return Ok(gears);
                            }
                        }
                    }
                }
            }
        }

        let mut routes = self.hot.routes();
        if let Some((fav, at)) = self.last_route.lock().unwrap().clone() {
            if at.elapsed() < Duration::from_secs(600) && routes.contains(&fav) {
                routes.retain(|r| r != &fav);
                routes.insert(0, fav);
            }
        }

        let mut last_err = None;
        for route in routes {
            let res = match route.as_str() {
                "dynamic" => self.play_multi_model(vid, "player").await,
                "legacy" => self.play_multi_model(vid, "").await,
                "videotab" => self.play_video_tab(vid).await,
                _ => continue,
            };
            match res {
                Ok(env) => {
                    match parse_play_gears(&self.http, &env, vid).await {
                        Ok(gears) if has_compatible_gear(&gears) => {
                            *self.last_route.lock().unwrap() = Some((route, Instant::now()));
                            self.put_cached(
                                format!("play|{vid}"),
                                CacheVal::Gears(gears.clone()),
                                Duration::from_secs(60),
                            );
                            return Ok(gears);
                        }
                        Ok(_) => last_err = Some("没有可用播放源".to_string()),
                        Err(e) => last_err = Some(e),
                    }
                }
                Err(e) => last_err = Some(e),
            }
        }

        Err(last_err.unwrap_or_else(|| "本集暂时无法播放，请稍后重试".into()))
    }

    async fn play_multi_model(&self, vid: &str, dr_scene: &str) -> Result<Value, String> {
        let mut d = self.get_playback_identity(false).await?;
        let cfg = self.hot.snapshot().legacy;
        let endpoint = if !dr_scene.is_empty() {
            format!("https://api5-normal-sinfonlinea.fqnovel.com/novel/player/multi_video_model/{dr_scene}/v1/")
        } else if !cfg.endpoint.trim().is_empty() {
            cfg.endpoint.clone()
        } else {
            "https://api5-normal-sinfonlinea.fqnovel.com/novel/player/multi_video_model/v1/".to_string()
        };
        for attempt in 0..2 {
            let need_all = cfg.need_all.unwrap_or(true);
            let payload = format!(
                r#"{{"biz_param":{{"detail_page_version":0,"device_level":{},"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":{},"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":{}}},"dr_scene":"player","mixed_video_id_map":{{"1004":["{vid}"]}},"video_id":"{vid}","content_type":1}}"#,
                cfg.device_level, need_all, cfg.video_platform
            );
            let mut gz = GzEncoder::new(Vec::new(), Compression::default());
            gz.write_all(payload.as_bytes()).map_err(|e| e.to_string())?;
            let gz_bytes = gz.finish().map_err(|e| e.to_string())?;

            let p = mini_reel_v5_query(&d);
            let signer = PlaybackSigner::new(&d.device_id, "7.4.3.32")?;
            let signed = signer.sign_mobile_v5(&endpoint, &p, Some(&gz_bytes), now_ms(), None)?;
            let mut h = vec![
                ("User-Agent".into(), LEGACY_UA.into()),
                ("Accept".into(), "application/json".into()),
                ("Content-Encoding".into(), "gzip".into()),
            ];
            h.extend(signed.headers);
            let r = do_request(&self.http, "POST", &signed.url, &h, Some(&gz_bytes)).await?;
            if r.status != 200 {
                return Err(format!("播放接口 HTTP {}", r.status));
            }
            if r.body.iter().all(|b| b.is_ascii_whitespace()) {
                if attempt == 0 {
                    d = self.get_playback_identity(true).await?;
                    continue;
                }
                return Err("legacy 返回空响应".into());
            }
            let root = r.json().map_err(|_| "播放响应格式异常".to_string())?;
            if let Some(code) = root.get("code").or_else(|| root.get("Code")) {
                if jstr(code) != "0" {
                    return Err(format!("播放错误码 {}", jstr(code)));
                }
            }
            let mut data = &root["data"];
            if data["1004"].is_object() {
                data = &data["1004"];
            }
            let node = &data[vid];
            if !node.is_object() {
                return Err("缺少本集播放模型".into());
            }
            return Ok(serde_json::json!({ "code": 0, "data": { vid: node } }));
        }
        Err("播放模型获取失败".into())
    }

    async fn play_video_tab(&self, vid: &str) -> Result<Value, String> {
        let d = self.get_playback_identity(false).await?;
        let mut p = mini_reel_v5_query(&d);
        p.extend([
            ("video_id".into(), vid.into()),
            ("video_id_type".into(), "0".into()),
            ("device_level".into(), "1".into()),
            ("need_all_video_definition".into(), "1".into()),
            ("need_mp4_align".into(), "0".into()),
            ("use_os_player".into(), "0".into()),
            ("video_platform".into(), "1024".into()),
            ("source".into(), "0".into()),
            ("from_video_id".into(), String::new()),
        ]);
        let signer = PlaybackSigner::new(&d.device_id, "7.4.3.32")?;
        let signed = signer.sign_mobile_v5(VIDEO_TAB_ENDPOINT, &p, None, now_ms(), None)?;
        let mut h = vec![
            ("User-Agent".into(), VIDEO_TAB_UA.into()),
            ("Accept".into(), "application/json".into()),
        ];
        for (k, v) in signed.headers {
            if k.eq_ignore_ascii_case("Content-Type") || k.eq_ignore_ascii_case("x-ss-stub") {
                continue;
            }
            h.push((k, v));
        }
        let r = do_request(&self.http, "GET", &signed.url, &h, None).await?;
        if r.status != 200 {
            return Err(format!("video_tab HTTP {}", r.status));
        }
        let root = r.json().map_err(|_| "video_tab 响应格式异常".to_string())?;
        if let Some(code) = root.get("code") {
            if jstr(code) != "0" {
                return Err(format!("video_tab 错误码 {}", jstr(code)));
            }
        }
        let node = &root["data"]["video_model_data"][vid];
        if !node.is_object() || node["video_model"].is_null() {
            return Err("video_tab 缺少本集播放模型".into());
        }
        Ok(serde_json::json!({ "code": 0, "data": { vid: node } }))
    }
}

fn mini_reel_v5_query(d: &MiniReelDevice) -> Params {
    let mut p = vec![
        ("device_platform".into(), "android".into()),
        ("os".into(), "android".into()),
        ("ssmix".into(), "a".into()),
        ("cdid".into(), d.cdid.clone()),
        ("channel".into(), "vivo_8662_64".into()),
        ("aid".into(), "8662".into()),
        ("app_name".into(), "novelread".into()),
        ("version_code".into(), "74332".into()),
        ("version_name".into(), "7.4.3.32".into()),
        ("manifest_version_code".into(), "74332".into()),
        ("update_version_code".into(), "74332".into()),
        ("resolution".into(), "1920*1080".into()),
        ("dpi".into(), "280".into()),
        ("device_type".into(), "PGT-AN10".into()),
        ("device_brand".into(), "HONOR".into()),
        ("language".into(), "zh".into()),
        ("os_api".into(), "31".into()),
        ("os_version".into(), "12".into()),
        ("ac".into(), "wifi".into()),
        ("host_abi".into(), "arm64-v8a".into()),
        ("dragon_device_type".into(), "phone".into()),
        ("pv_player".into(), "74332".into()),
        ("compliance_status".into(), "0".into()),
        ("need_personal_recommend".into(), "1".into()),
        ("player_so_load".into(), "1".into()),
        ("is_android_pad_screen".into(), "0".into()),
        ("rom_version".into(), "V417IR+release-keys".into()),
        ("iid".into(), d.install_id.clone()),
        ("device_id".into(), d.device_id.clone()),
    ];
    if !d.egdi.is_empty() {
        set_param(&mut p, "klink_egdi", &d.egdi);
    }
    p
}

async fn register_mini_reel_device(http: &Client) -> Result<MiniReelDevice, String> {
    let cdid = Uuid::new_v4().to_string();
    let clientudid = Uuid::new_v4().to_string();
    let openudid = Uuid::new_v4().simple().to_string()[..16].to_string();
    let stamp = now_ms();
    let payload = serde_json::to_vec(&serde_json::json!({
        "magic_tag": "ss_app_log",
        "header": {
            "aid": 8662, "access": "wifi", "app_version": "7.3.9.32", "app_version_minor": "7.3.9.32", "channel": "xiaomi_8662_64",
            "device_brand": "Xiaomi", "device_manufacturer": "Xiaomi", "device_model": "23127PN0CC", "device_category": "phone", "device_platform": "android",
            "os": "Android", "os_api": 34, "os_version": "14", "density_dpi": 460, "display_density": "xxxhdpi", "resolution": "2670x1200",
            "language": "zh", "timezone": 8, "tz_name": "Asia/Shanghai", "tz_offset": 28800, "region": "CN", "package": "com.phoenix.read",
            "version_code": 73932, "manifest_version_code": 73932, "update_version_code": 73932, "apk_first_install_time": stamp,
            "cdid": cdid, "clientudid": clientudid, "openudid": openudid, "cpu_abi": "arm64-v8a", "display_name": "红果免费短剧",
            "sdk_version": "3.7.2", "sdk_target_version": 29, "sdk_flavor": "china", "guest_mode": 0, "is_system_app": 0, "not_request_sender": 0, "oaid_may_support": true,
            "ipv6_list": [], "sim_serial_number": [], "device_id": "0", "install_id": "0", "req_id": clientudid,
            "oaid": {"req_id": cdid, "hw_id_version_code": "0", "take_ms": "6", "is_track_limited": "false", "query_times": "1", "id": cdid, "time": (stamp - 200).to_string()},
            "custom": {"is_android_fold": 0, "filter_warn": 0, "web_ua": "", "is_android_pad": 0, "client_ipv4": ""},
        },
        "_gen_time": stamp,
    })).map_err(|e| e.to_string())?;

    let mut seed = [0u8; 32];
    rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut seed);
    let body = passport_tt_encrypt(&payload, &seed)?;

    let ts = (stamp / 1000) as u32;
    let q: Vec<(String, String)> = vec![
        ("channel".into(), "xiaomi_8662_64".into()), ("cdid".into(), cdid.clone()), ("openudid".into(), openudid.clone()),
        ("version_code".into(), "73932".into()), ("version_name".into(), "7.3.9.32".into()),
        ("device_platform".into(), "android".into()), ("os".into(), "android".into()), ("ssmix".into(), "a".into()),
        ("device_type".into(), "23127PN0CC".into()), ("device_brand".into(), "Xiaomi".into()),
        ("language".into(), "zh".into()), ("os_api".into(), "34".into()), ("os_version".into(), "14".into()),
        ("manifest_version_code".into(), "73932".into()), ("resolution".into(), "1200*2670".into()),
        ("dpi".into(), "460".into()), ("update_version_code".into(), "73932".into()), ("host_abi".into(), "arm64-v8a".into()),
        ("dragon_device_type".into(), "phone".into()), ("pv_player".into(), "73932".into()),
        ("compliance_status".into(), "0".into()), ("need_personal_recommend".into(), "1".into()),
        ("player_so_load".into(), "1".into()), ("is_android_pad_screen".into(), "0".into()),
        ("tt_data".into(), "a".into()), ("ts".into(), ts.to_string()), ("_rticket".into(), stamp.to_string()),
        ("package".into(), "com.phoenix.read".into()),
    ];
    let query_enc = {
        let mut sorted = q;
        sorted.sort_by(|a, b| a.0.cmp(&b.0));
        let mut ser = url::form_urlencoded::Serializer::new(String::new());
        for (k, v) in &sorted {
            ser.append_pair(k, v);
        }
        ser.finish()
    };
    let nonce = protocol_random() as u16;
    let stub = hex::encode(crate::sign::md5_bytes(&body));
    let h = vec![
        ("User-Agent".into(), MINI_REEL_SINGLE_UA.into()),
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
    let u = format!("{MINI_REEL_SINGLE_DEVICE_BASE}/service/2/device_register/?{query_enc}");
    let r = do_request(http, "POST", &u, &h, Some(&body)).await?;
    if r.status != 200 {
        return Err(format!("播放设备注册返回 HTTP {}", r.status));
    }
    let raw_body = if r.body.starts_with(&[0x74, 0x63, 5, 0x10, 0, 0]) {
        passport_tt_decrypt(&r.body)?
    } else {
        r.body
    };
    let p: Value = serde_json::from_slice(&raw_body).map_err(|_| "播放设备注册响应无效".to_string())?;
    let did = first_str(&p, &["device_id_str", "device_id"]);
    let iid = first_str(&p, &["install_id_str", "install_id"]);
    if did.is_empty() || iid.is_empty() || did == "0" || iid == "0" {
        return Err("播放设备注册未返回有效身份".into());
    }
    let egdi = first_str(&p, &["klink_egdi", "egdi"]);
    let act_u = format!(
        "https://log5-applog-lf.fqnovel.com/service/2/app_alert_check/?aid=8662&app_name=novelread&channel=xiaomi_8662_64&device_id={did}&iid={iid}&device_platform=android&os=android&version_code=73932&version_name=7.3.9.32&_rticket={}",
        now_ms()
    );
    let _ = do_request(http, "GET", &act_u, &[("User-Agent".into(), MINI_REEL_SINGLE_UA.into())], None).await;
    Ok(MiniReelDevice {
        device_id: did,
        install_id: iid,
        cdid,
        openudid,
        egdi,
    })
}

async fn parse_play_gears(http: &Client, response: &Value, vid: &str) -> Result<Vec<Gear>, String> {
    let mut data = &response["data"];
    let vmd = object_or_json(&data["video_model_data"]);
    let data_owned;
    if let Some(m) = vmd {
        data_owned = m;
        data = &data_owned;
    }
    let node = &data[vid];
    let mut model = play_model(&node["video_model"]);
    if model.is_none() && !node["video_list"].is_null() {
        model = node.as_object().map(|_| node.clone());
    }
    if model.is_none() {
        model = play_model(&data["video_model"]);
    }
    if model.is_none() && !data["video_list"].is_null() {
        model = data.as_object().map(|_| data.clone());
    }
    let model = model.ok_or_else(|| "播放节点为空".to_string())?;
    let direct = parse_play_gear_rows(&model, &[]);
    let mut fallback_gears = Vec::new();
    let fb_api = extract_fallback_api(&model["fallback_api"]);
    if !fb_api.is_empty() {
        if let Ok((inner, seed)) = fetch_compatible_fallback(http, &fb_api).await {
            fallback_gears = parse_play_gear_rows(&inner, &seed);
        } else if direct.is_empty() {
            return Err("播放 fallback 取源失败".into());
        }
    }
    let mut out = merge_play_gears(direct, fallback_gears);
    if has_compatible_gear(&out) {
        out.retain(|g| !is_bytevc2_codec(&g.codec));
    }
    if out.is_empty() {
        return Err("播放模型没有直连视频列表".into());
    }
    Ok(out)
}

fn object_or_json(v: &Value) -> Option<Value> {
    if v.is_object() {
        return Some(v.clone());
    }
    if let Some(s) = v.as_str() {
        if let Ok(parsed) = serde_json::from_str::<Value>(s) {
            if parsed.is_object() {
                return Some(parsed);
            }
        }
    }
    None
}

fn play_model(v: &Value) -> Option<Value> {
    object_or_json(v)
}

fn play_rows(v: &Value) -> Vec<Value> {
    match v {
        Value::Array(a) => a.clone(),
        Value::Object(m) => m.values().cloned().collect(),
        Value::String(s) => serde_json::from_str::<Value>(s)
            .ok()
            .map(|p| play_rows(&p))
            .unwrap_or_default(),
        _ => Vec::new(),
    }
}

fn parse_play_gear_rows(model: &Value, seed: &[u8]) -> Vec<Gear> {
    let mut out = Vec::new();
    for item in play_rows(&model["video_list"]) {
        let Some(vo) = object_or_json(&item) else {
            continue;
        };
        let meta = object_or_json(&vo["video_meta"]).unwrap_or_else(|| vo.clone());
        let enc = object_or_json(&vo["encrypt_info"]).unwrap_or(Value::Null);
        let def = nz(jstr(&meta["definition"]), jstr(&vo["quality_desc"]));
        let codec = nz(jstr(&meta["codec_type"]), jstr(&vo["codec_type"]));
        let mut width = jnum(&meta["vwidth"]) as i32;
        if width == 0 {
            width = jnum(&vo["width"]) as i32;
        }
        if width == 0 {
            width = jnum(&vo["vwidth"]) as i32;
        }
        let mut height = jnum(&meta["vheight"]) as i32;
        if height == 0 {
            height = jnum(&vo["height"]) as i32;
        }
        if height == 0 {
            height = jnum(&vo["vheight"]) as i32;
        }
        let main_url = play_address(&vo["main_url"], seed);
        if main_url.is_empty() {
            continue;
        }
        let mut backups = Vec::new();
        for key in [
            "backup_url",
            "backup_url_1",
            "backup_url_2",
            "backup_url_3",
            "BackupPlayUrl",
            "backup_url_list",
        ] {
            for addr in play_addresses(&vo[key], seed) {
                if addr != main_url && !backups.contains(&addr) {
                    backups.push(addr);
                }
            }
        }
        let backup_url = backups.first().cloned().unwrap_or_default();
        let spade_a = nz(jstr(&enc["spade_a"]), jstr(&vo["spade_a"]));
        let encrypted = bool_val(&enc["encrypt"]) || bool_val(&vo["encrypt"]);
        out.push(Gear {
            definition: def,
            codec,
            width,
            height,
            size: jnum(&meta["size"]) as i64,
            main_url,
            backup_url,
            backup_urls: backups,
            encrypted,
            spade_a,
        });
    }
    out
}

fn bool_val(v: &Value) -> bool {
    match v {
        Value::Bool(b) => *b,
        Value::String(s) => s.trim().eq_ignore_ascii_case("true") || s.trim() == "1",
        _ => jnum(v) != 0.0,
    }
}

fn decode_play_b64(value: &str) -> Option<Vec<u8>> {
    let t = value.trim();
    STANDARD
        .decode(t)
        .or_else(|_| STANDARD_NO_PAD.decode(t))
        .or_else(|_| URL_SAFE.decode(t))
        .or_else(|_| URL_SAFE_NO_PAD.decode(t))
        .ok()
}

fn play_address(v: &Value, seed: &[u8]) -> String {
    let raw = match v {
        Value::String(s) => s.trim().to_string(),
        Value::Array(a) => a
            .iter()
            .filter_map(|x| x.as_str().map(|s| s.trim().to_string()))
            .find(|s| !s.is_empty())
            .unwrap_or_default(),
        _ => String::new(),
    };
    if raw.is_empty() || raw.starts_with("https://") || raw.starts_with("http://") {
        return raw;
    }
    let Some(decoded) = decode_play_b64(&raw) else {
        return raw;
    };
    if !seed.is_empty() && decoded.len() >= 5 && decoded[0] == 0xa8 && decoded[2] == 1 && decoded[3] == 0 {
        let constants = hex::decode("4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58").unwrap();
        let first = Sha512::digest(seed);
        let mut mat = Vec::with_capacity(64 + constants.len());
        mat.extend_from_slice(&first);
        mat.extend_from_slice(&constants);
        let second = Sha512::digest(&mat);
        let cipher_len = (decoded.len() - 4) / 16 * 16;
        if cipher_len > 0 {
            if let Ok(dec) = Aes128CbcDec::new_from_slices(&second[..16], &second[16..32]) {
                let mut buf = decoded[4..4 + cipher_len].to_vec();
                if let Ok(plain) = dec.decrypt_padded_mut::<Pkcs7>(&mut buf) {
                    let s = String::from_utf8_lossy(plain).trim_matches('\0').trim().to_string();
                    if s.starts_with("https://") || s.starts_with("http://") {
                        return s;
                    }
                }
            }
        }
    }
    let text = String::from_utf8_lossy(&decoded).trim().to_string();
    if text.starts_with("https://") || text.starts_with("http://") {
        return text;
    }
    raw
}

fn play_addresses(v: &Value, seed: &[u8]) -> Vec<String> {
    let mut out = Vec::new();
    match v {
        Value::String(_) => {
            let a = play_address(v, seed);
            if !a.is_empty() {
                out.push(a);
            }
        }
        Value::Array(arr) => {
            for item in arr {
                for a in play_addresses(item, seed) {
                    if !out.contains(&a) {
                        out.push(a);
                    }
                }
            }
        }
        Value::Object(map) => {
            let mut keys: Vec<&String> = map.keys().collect();
            keys.sort();
            for k in keys {
                for a in play_addresses(&map[k], seed) {
                    if !out.contains(&a) {
                        out.push(a);
                    }
                }
            }
        }
        _ => {}
    }
    out
}

fn extract_fallback_api(v: &Value) -> String {
    match v {
        Value::String(s) => {
            let t = s.trim();
            if let Ok(dec) = serde_json::from_str::<Value>(t) {
                if let Some(m) = dec.as_object() {
                    if let Some(fb) = m.get("fallback_api") {
                        let s2 = extract_fallback_api(fb);
                        if !s2.is_empty() {
                            return s2;
                        }
                    }
                } else if let Some(arr) = dec.as_array() {
                    if let Some(first) = arr.first() {
                        return extract_fallback_api(first);
                    }
                }
            }
            t.to_string()
        }
        Value::Array(arr) => arr.first().map(extract_fallback_api).unwrap_or_default(),
        Value::Object(m) => m.get("fallback_api").map(extract_fallback_api).unwrap_or_default(),
        _ => String::new(),
    }
}

fn fallback_codec_url(raw: &str, codec: &str) -> String {
    let (base, query) = raw.split_once('?').unwrap_or((raw, ""));
    let mut kept: Vec<&str> = query
        .split('&')
        .filter(|part| {
            if part.is_empty() {
                return false;
            }
            let name = part.split_once('=').map(|(k, _)| k).unwrap_or(part);
            name != "codec_type" && name != "force_codec"
        })
        .collect();
    kept.push(codec);
    format!("{base}?{}", kept.join("&"))
}

async fn fetch_compatible_fallback(http: &Client, raw: &str) -> Result<(Value, Vec<u8>), String> {
    let mut last_inner = None;
    let mut last_seed = Vec::new();
    let mut last_err = None;
    for codec in [
        "codec_type=1&force_codec=h264",
        "codec_type=1",
        "codec_type=3",
        "codec_type=0&force_codec=h264",
    ] {
        match fetch_play_fallback(http, &fallback_codec_url(raw, codec)).await {
            Ok((cand, seed)) => {
                let rows = play_rows(&cand["video_list"]);
                if !rows.is_empty() && !only_bytevc2_rows(&rows) {
                    return Ok((cand, seed));
                }
                if !rows.is_empty() {
                    last_inner = Some(cand);
                    last_seed = seed;
                }
            }
            Err(e) => last_err = Some(e),
        }
    }
    if let Ok((inner, seed)) = fetch_play_fallback(http, raw).await {
        if !play_rows(&inner["video_list"]).is_empty() {
            return Ok((inner, seed));
        }
    }
    if let Some(inner) = last_inner {
        return Ok((inner, last_seed));
    }
    Err(last_err.unwrap_or_else(|| "fallback 取源失败".into()))
}

async fn fetch_play_fallback(http: &Client, raw: &str) -> Result<(Value, Vec<u8>), String> {
    let u = url::Url::parse(raw.trim()).map_err(|_| "播放 fallback 地址无效".to_string())?;
    let host = u.host_str().unwrap_or("").to_ascii_lowercase();
    if u.scheme() != "https" || (!host.ends_with(".fqnovel.com") && !host.ends_with(".snssdk.com")) {
        return Err("播放 fallback 域名不受支持".into());
    }
    let r = do_request(
        http,
        "GET",
        u.as_str(),
        &[("User-Agent".into(), CONTENT_UA.into()), ("Accept".into(), "application/json".into())],
        None,
    )
    .await?;
    if r.status != 200 {
        return Err(format!("播放 fallback HTTP {}", r.status));
    }
    let outer = r.json().map_err(|_| "播放 fallback 响应格式异常".to_string())?;
    let inner = if outer["video_info"]["data"].is_object() {
        outer["video_info"]["data"].clone()
    } else if outer["data"].is_object() {
        outer["data"].clone()
    } else {
        return Err("播放 fallback 缺少视频信息".into());
    };
    let enc = jstr(&inner["key_seed"]);
    let seed = if enc.is_empty() {
        Vec::new()
    } else {
        decode_play_b64(&enc).ok_or_else(|| "播放 fallback 密钥格式异常".to_string())?
    };
    Ok((inner, seed))
}

fn only_bytevc2_rows(rows: &[Value]) -> bool {
    if rows.is_empty() {
        return false;
    }
    rows.iter().all(|item| {
        let Some(vo) = object_or_json(item) else {
            return false;
        };
        let codec = nz(jstr(&vo["video_meta"]["codec_type"]), jstr(&vo["codec_type"]));
        is_bytevc2_codec(&codec)
    })
}

fn merge_play_gears(a: Vec<Gear>, b: Vec<Gear>) -> Vec<Gear> {
    let mut index: HashMap<String, usize> = HashMap::new();
    let mut out: Vec<Gear> = Vec::new();
    for gear in a.into_iter().chain(b.into_iter()) {
        let mut key = gear.definition.clone();
        if key.is_empty() {
            key = format!("{}x{}", gear.width, gear.height);
        }
        if key.is_empty() || key == "0x0" {
            key = gear.main_url.clone();
        }
        if let Some(&at) = index.get(&key) {
            if codec_rank(&gear.codec) < codec_rank(&out[at].codec) {
                out[at] = gear;
            }
        } else {
            index.insert(key, out.len());
            out.push(gear);
        }
    }
    out
}

pub fn pick_gear<'a>(gears: &'a [Gear], prefer: &str, limit: i32) -> Option<&'a Gear> {
    let limit = if limit <= 0 { 1080 } else { limit };
    let mut exact: Option<&Gear> = None;
    let mut h264: Option<&Gear> = None;
    let mut best: Option<&Gear> = None;
    for g in gears {
        if is_bytevc2_codec(&g.codec) {
            continue;
        }
        let ss = g.short_side();
        if !prefer.is_empty() && g.definition == prefer {
            if exact.map(|e| codec_rank(&g.codec) < codec_rank(&e.codec)).unwrap_or(true) {
                exact = Some(g);
            }
        }
        if ss <= 0 || ss > limit {
            continue;
        }
        if is_h264_codec(&g.codec) && better_gear(g, h264) {
            h264 = Some(g);
        }
        if better_gear(g, best) {
            best = Some(g);
        }
    }
    exact.or(h264).or(best)
}

fn better_gear(cand: &Gear, cur: Option<&Gear>) -> bool {
    let Some(c) = cur else { return true };
    if cand.short_side() != c.short_side() {
        return cand.short_side() > c.short_side();
    }
    codec_rank(&cand.codec) < codec_rank(&c.codec)
}

fn codec_rank(codec: &str) -> i32 {
    if is_h264_codec(codec) {
        0
    } else if is_h265_codec(codec) {
        1
    } else if is_bytevc2_codec(codec) {
        3
    } else {
        2
    }
}

fn is_h264_codec(c: &str) -> bool {
    let s = c.trim().to_ascii_lowercase();
    s == "h264" || s == "avc" || s == "avc1" || s.contains("h.264")
}

fn is_h265_codec(c: &str) -> bool {
    let s = c.trim().to_ascii_lowercase();
    s == "h265" || s == "hevc" || s.contains("h.265")
}

pub fn is_bytevc2_codec(c: &str) -> bool {
    let s = c.trim().to_ascii_lowercase();
    s.contains("bytevc") || s.contains("bvc") || s.contains("vvc")
}

fn has_compatible_gear(gears: &[Gear]) -> bool {
    gears.iter().any(|g| !g.main_url.is_empty() && !is_bytevc2_codec(&g.codec))
}

fn category_names(raw: &str) -> String {
    let t = raw.trim();
    if !t.starts_with('[') {
        return t.to_string();
    }
    let Ok(rows) = serde_json::from_str::<Vec<Value>>(t) else {
        return String::new();
    };
    let mut names = Vec::new();
    for r in rows {
        let n = if r.is_object() {
            jstr(&r["name"])
        } else {
            jstr(&r)
        };
        let n = n.trim().to_string();
        if !n.is_empty() && n != "null" {
            names.push(n);
        }
    }
    names.join(",")
}

fn collect_series(v: &Value, out: &mut Vec<Series>, seen: &mut HashSet<String>) {
    match v {
        Value::Array(arr) => {
            for e in arr {
                collect_series(e, out, seen);
            }
        }
        Value::Object(map) => {
            let sid = jstr(map.get("series_id").unwrap_or(&Value::Null));
            let title = nz(
                jstr(map.get("series_title").unwrap_or(&Value::Null)),
                nz(
                    jstr(map.get("series_name").unwrap_or(&Value::Null)),
                    jstr(map.get("title").unwrap_or(&Value::Null)),
                ),
            );
            if !sid.is_empty() && sid != "0" && !title.is_empty() && seen.insert(sid.clone()) {
                out.push(Series {
                    id: sid,
                    title,
                    cover: img_url(&nz(
                        jstr(map.get("cover").unwrap_or(&Value::Null)),
                        jstr(map.get("series_cover").unwrap_or(&Value::Null)),
                    )),
                    first_vid: jstr(map.get("vid").unwrap_or(&Value::Null)),
                    episodes: jnum(map.get("episode_cnt").unwrap_or(&Value::Null)) as i32,
                    plays: jnum(&Value::String(nz(
                        jstr(map.get("play_cnt").unwrap_or(&Value::Null)),
                        jstr(map.get("series_play_cnt").unwrap_or(&Value::Null)),
                    ))) as i64,
                    intro: nz(
                        jstr(map.get("video_desc").unwrap_or(&Value::Null)),
                        jstr(map.get("series_intro").unwrap_or(&Value::Null)),
                    ),
                    category: category_names(&jstr(
                        map.get("category_schema").unwrap_or(&Value::Null),
                    )),
                    rank: 0,
                });
            }
            for child in map.values() {
                if child.is_array() || child.is_object() {
                    collect_series(child, out, seen);
                }
            }
        }
        _ => {}
    }
}

fn collect_app_cell(cell: &Value, out: &mut Vec<Series>, seen: &mut HashSet<String>) {
    if !cell.is_object() {
        return;
    }
    let em_re = Regex::new(r"</?em>").unwrap();
    if let Some(arr) = cell["video_data"].as_array() {
        for vo in arr {
            let id = nz(jstr(&vo["series_id"]), jstr(&cell["book_id"]));
            let mut title = nz(jstr(&vo["title"]), jstr(&cell["cell_name"]));
            let hl = jstr(&cell["search_high_light"]["title"]["text"]);
            if !hl.is_empty() {
                title = hl;
            }
            title = em_re.replace_all(&title, "").trim().to_string();
            if id.is_empty() || title.is_empty() || !seen.insert(id.clone()) {
                continue;
            }
            out.push(Series {
                id,
                title,
                cover: img_url(&nz(jstr(&vo["cover"]), jstr(&vo["cover_url"]))),
                intro: jstr(&vo["video_desc"]),
                episodes: jnum(&vo["episode_cnt"]) as i32,
                first_vid: jstr(&vo["vid"]),
                plays: 0,
                category: String::new(),
                rank: 0,
            });
        }
    }
    let raw = jstr(&cell["lynx_data"]);
    if !raw.is_empty() {
        if let Ok(nested) = serde_json::from_str::<Value>(&raw) {
            if let Some(arr) = nested["cell_data"].as_array() {
                for n in arr {
                    collect_app_cell(n, out, seen);
                }
            }
        }
    }
}

fn matches_category(raw: &str, label: &str) -> bool {
    if raw.is_empty() {
        return false;
    }
    match label {
        "历史古代" | "古装" => ["古代", "古风", "古装"].iter().any(|c| raw.contains(c)),
        "校园" => ["校园", "青春"].iter().any(|c| raw.contains(c)),
        _ => raw.contains(label),
    }
}

fn normalize_season_title(s: &str) -> String {
    s.trim()
        .replace('⼀', "一")
        .replace('⼆', "二")
        .replace('⼋', "八")
        .replace('⼗', "十")
        .replace('⻣', "骨")
        .replace('⼑', "刀")
        .replace('⼈', "人")
        .replace('⼤', "大")
        .replace('⼩', "小")
        .replace('⼭', "山")
        .replace('⻛', "风")
        .replace('⻜', "飞")
}

fn explicit_season_number(title: &str) -> i32 {
    let t = normalize_season_title(title);
    let Some(i) = t.find('第') else { return 0 };
    let rest: Vec<char> = t[i + '第'.len_utf8()..].chars().collect();
    let mut n = 0i32;
    let mut end = 0usize;
    for (idx, &r) in rest.iter().enumerate() {
        if r.is_ascii_digit() {
            n = n * 10 + (r as i32 - '0' as i32);
            end = idx + 1;
        } else {
            let v = match r {
                '一' => Some(1),
                '二' | '两' => Some(2),
                '三' => Some(3),
                '四' => Some(4),
                '五' => Some(5),
                '六' => Some(6),
                '七' => Some(7),
                '八' => Some(8),
                '九' => Some(9),
                '十' => Some(10),
                _ => None,
            };
            if let Some(val) = v {
                if r == '十' {
                    n = if n == 0 { 10 } else { n * 10 };
                } else {
                    n += val;
                }
                end = idx + 1;
            } else {
                break;
            }
        }
    }
    if n <= 0 || end >= rest.len() || (rest[end] != '季' && rest[end] != '部') {
        return 0;
    }
    n
}

fn assign_season_numbers(out: &mut [Season]) {
    let mut used = HashSet::new();
    let mut fixed = vec![false; out.len()];
    let mut max_n = 0;
    for (i, s) in out.iter().enumerate() {
        if s.number > 0 && used.insert(s.number) {
            fixed[i] = true;
            max_n = max_n.max(s.number);
        }
    }
    for (i, s) in out.iter_mut().enumerate() {
        if fixed[i] {
            continue;
        }
        let mut cand = (i as i32) + 1;
        if cand <= 0 || used.contains(&cand) {
            cand = max_n + 1;
        }
        while used.contains(&cand) {
            cand += 1;
        }
        s.number = cand;
        used.insert(cand);
        max_n = max_n.max(cand);
    }
}

fn mark_current_season(mut list: Vec<Season>, series_id: &str) -> Vec<Season> {
    for s in &mut list {
        s.current = s.id == series_id;
    }
    list
}

fn merge_json(base: Option<&Value>, over: Option<&Value>) -> Option<Value> {
    match (base, over) {
        (None, None) => None,
        (Some(b), None) => Some(b.clone()),
        (None, Some(o)) => Some(o.clone()),
        (Some(Value::Object(bm)), Some(Value::Object(om))) => {
            let mut out = bm.clone();
            for (k, v) in om {
                let merged = merge_json(out.get(k), Some(v)).unwrap_or_else(|| v.clone());
                out.insert(k.clone(), merged);
            }
            Some(Value::Object(out))
        }
        (Some(_), Some(o)) => Some(o.clone()),
    }
}

fn comment_text(v: &Value) -> String {
    match v {
        Value::String(s) => {
            let t = s.trim();
            if t.starts_with('{') || t.starts_with("[{") {
                if let Ok(j) = serde_json::from_str::<Value>(t) {
                    return comment_text(&j);
                }
            }
            s.clone()
        }
        Value::Array(arr) => arr.iter().map(comment_text).collect::<Vec<_>>().join(""),
        Value::Object(map) => {
            for k in ["text", "content", "description"] {
                if let Some(val) = map.get(k) {
                    let t = comment_text(val);
                    if !t.trim().is_empty() {
                        return t;
                    }
                }
            }
            String::new()
        }
        _ => String::new(),
    }
}

fn extract_comment_rows(d: &Value) -> Vec<Value> {
    let mut rows = Vec::new();
    for k in ["comment", "comments", "comment_list", "list", "data"] {
        if let Some(a) = d[k].as_array() {
            rows = a.clone();
            break;
        }
    }
    let mut out = Vec::new();
    for r in rows {
        let row = if r["comment"].is_object() { &r["comment"] } else { &r };
        let mut text = String::new();
        for k in ["text", "content", "comment_text", "comment_content"] {
            let t = comment_text(&row[k]).trim().to_string();
            if !t.is_empty() {
                text = t;
                break;
            }
        }
        if text.is_empty() {
            continue;
        }
        let user = &row["user_info"];
        out.push(serde_json::json!({
            "text": text,
            "user": jstr(&user["user_name"]),
            "avatar": img_url(&jstr(&user["user_avatar"])),
        }));
    }
    out
}

#[allow(dead_code)]
fn _unused_content_keep() {
    let _ = build_http_client;
    let _ = check_ok;
}
