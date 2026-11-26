use crate::hotupdate::HotUpdateManager;
use crate::sign::passport::{protocol_random, sign_passport_request_version};
use crate::sign::{encode_params, md5_bytes, rand_f64, set_param, sign_v3, Params, APP_UA};
use crate::upstream::device::{
    do_request, img_url, is_digits, jnum, jstr, now_ms, nz, DeviceStore, HttpResp, UserJar,
    APP_HOST, VERSION_NAME,
};
use chrono::{Datelike, FixedOffset, TimeZone, Timelike};
use reqwest::Client;
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

pub const SUBSCRIBE_HOST: &str = "https://reading.snssdk.com";
const APP_VERSION_CODE: &str = "71932";
const MINI_REEL_SINGLE_UA: &str = "com.phoenix.read/73932 (Linux; U; Android 14; zh_CN; 23127PN0CC; Build/UKQ1.230804.001; Cronet/TTNetVersion:8d40f833 2026-03-03 QuicVersion:21ac1950 2025-11-18)";

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Profile {
    pub uid: String,
    pub name: String,
    pub avatar: String,
    pub cover: String,
    pub signature: String,
    pub vip: bool,
    pub watch_ms: i64,
    pub active_days: i32,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Entry {
    pub series_id: String,
    pub title: String,
    pub cover: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub vid: String,
    pub episode_index: i32,
    pub episode_count: i32,
    pub position_ms: i32,
    pub time: i64,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Reservation {
    pub series_id: String,
    pub title: String,
    pub cover: String,
    pub online: bool,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub desc: String,
    pub episode_count: i32,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub publish_text: String,
    #[serde(default)]
    pub time: i64,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LocalProgressRow {
    pub series_id: String,
    pub vid: String,
    pub index: i32,
    pub pos: i32,
    pub title: String,
    pub cover: String,
    pub episode_count: i32,
    pub ts: i64,
    #[serde(default)]
    pub dirty: bool,
    #[serde(default)]
    pub deleted: bool,
}

pub struct Account {
    pub http: Client,
    pub devices: Arc<DeviceStore>,
    pub user: Arc<UserJar>,
    pub hot: Arc<HotUpdateManager>,
    lib_path: PathBuf,
    lib_rows: Mutex<HashMap<String, LocalProgressRow>>,
}

impl Account {
    pub fn new(
        data_dir: &Path,
        http: Client,
        devices: Arc<DeviceStore>,
        user: Arc<UserJar>,
        hot: Arc<HotUpdateManager>,
    ) -> Arc<Self> {
        let lib_path = data_dir.join("library.json");
        let lib_rows = std::fs::read_to_string(&lib_path)
            .ok()
            .and_then(|s| serde_json::from_str::<HashMap<String, LocalProgressRow>>(&s).ok())
            .unwrap_or_default();
        Arc::new(Self {
            http,
            devices,
            user,
            hot,
            lib_path,
            lib_rows: Mutex::new(lib_rows),
        })
    }

    pub async fn params(&self) -> Result<Params, String> {
        let dev = self.devices.get().await?;
        let mut p = vec![
            ("aid".into(), "8662".into()),
            ("app_name".into(), "novelread".into()),
            ("device_platform".into(), "android".into()),
            ("device_type".into(), "25053RT47C".into()),
            ("device_brand".into(), "Redmi".into()),
            ("channel".into(), "update_64".into()),
            ("version_code".into(), "71932".into()),
            ("version_name".into(), VERSION_NAME.into()),
            ("manifest_version_code".into(), "71932".into()),
            ("update_version_code".into(), "71932".into()),
            ("pv_player".into(), "71932".into()),
            ("os".into(), "android".into()),
            ("os_version".into(), "16".into()),
            ("os_api".into(), "36".into()),
            ("ac".into(), "wifi".into()),
            ("language".into(), "zh".into()),
            ("resolution".into(), "1280*2772".into()),
            ("dpi".into(), "520".into()),
            ("host_abi".into(), "arm64-v8a".into()),
            ("ssmix".into(), "a".into()),
            ("dragon_device_type".into(), "phone".into()),
            ("compliance_status".into(), "0".into()),
            ("need_personal_recommend".into(), "1".into()),
            ("player_so_load".into(), "1".into()),
            ("is_android_pad_screen".into(), "0".into()),
            ("device_id".into(), dev.id),
            ("iid".into(), dev.iid),
        ];
        if !dev.cdid.is_empty() {
            p.push(("cdid".into(), dev.cdid));
        }
        if !dev.openudid.is_empty() {
            p.push(("openudid".into(), dev.openudid));
        }
        if !dev.clientudid.is_empty() {
            p.push(("clientudid".into(), dev.clientudid));
        }
        Ok(p)
    }

    fn headers(&self) -> Vec<(String, String)> {
        vec![
            ("User-Agent".into(), APP_UA.into()),
            ("Accept".into(), "application/json; charset=utf-8".into()),
            ("Cookie".into(), self.user.cookie_string()),
        ]
    }

    fn parse(&self, r: &HttpResp) -> Result<serde_json::Value, String> {
        self.user.absorb(&r.cookies);
        let m = r
            .json()
            .map_err(|_| "服务器没有返回数据，请稍后重试".to_string())?;
        let mut code = jnum(&m["code"]) as i64;
        if code == 0 {
            code = jnum(&m["Code"]) as i64;
        }
        let in_obj = &m["data"];
        let mut name = jstr(&in_obj["name"]);
        if name.is_empty() {
            name = jstr(&in_obj["error_name"]);
        }
        if jstr(&m["message"]).contains("session_expired")
            || name.contains("session_expired")
            || code == 100004
        {
            return Err("SESSION_EXPIRED:登录已失效，请重新登录".into());
        }
        Ok(m)
    }

    pub async fn get(&self, path: &str, extra: &[(String, String)]) -> Result<serde_json::Value, String> {
        let mut q = self.params().await?;
        for (k, v) in extra {
            set_param(&mut q, k, v);
        }
        let u = format!("{APP_HOST}{path}?{}", encode_params(&q));
        let r = do_request(&self.http, "GET", &u, &self.headers(), None).await?;
        self.parse(&r)
    }

    pub async fn post(
        &self,
        path: &str,
        body: &serde_json::Value,
    ) -> Result<serde_json::Value, String> {
        let q = self.params().await?;
        let raw = serde_json::to_vec(body).map_err(|e| e.to_string())?;
        let mut h = self.headers();
        h.push(("x-ss-stub".into(), hex::encode_upper(md5_bytes(&raw))));
        h.push(("Content-Type".into(), "application/json; charset=UTF-8".into()));
        let u = format!("{APP_HOST}{path}?{}", encode_params(&q));
        let r = do_request(&self.http, "POST", &u, &h, Some(&raw)).await?;
        self.parse(&r)
    }

    pub async fn signed(
        &self,
        host: &str,
        path: &str,
        extra: &[(String, String)],
        body: Option<&serde_json::Value>,
    ) -> Result<serde_json::Value, String> {
        let mut q = self.params().await?;
        for (k, v) in extra {
            set_param(&mut q, k, v);
        }
        let raw = match body {
            Some(b) => Some(serde_json::to_vec(b).map_err(|e| e.to_string())?),
            None => None,
        };
        let dev = self.devices.get().await?;
        let version = q
            .iter()
            .find(|(k, _)| k == "version_name")
            .map(|(_, v)| v.as_str())
            .unwrap_or(VERSION_NAME)
            .to_string();

        let base_url = format!("{host}{path}");
        // Try dynamically hot-loaded algorithm module first if present
        let signed = if let Some(algo) = self.hot.active_algo() {
            let req_json = serde_json::json!({
                "mode": "v3",
                "base": base_url,
                "params": q,
                "bodyHex": raw.as_ref().map(hex::encode).unwrap_or_default(),
                "deviceId": dev.id,
                "version": version,
                "nowMs": now_ms(),
            })
            .to_string();
            if let Some(resp_str) = algo.invoke_sign(&req_json) {
                if let Ok(v) = serde_json::from_str::<serde_json::Value>(&resp_str) {
                    if v["ok"].as_bool() == Some(true) {
                        let url_s = jstr(&v["url"]);
                        let mut hdrs = Vec::new();
                        if let Some(arr) = v["headers"].as_array() {
                            for item in arr {
                                if let Some(pair) = item.as_array() {
                                    if pair.len() >= 2 {
                                        hdrs.push((jstr(&pair[0]), jstr(&pair[1])));
                                    }
                                }
                            }
                        }
                        if !url_s.is_empty() && !hdrs.is_empty() {
                            Some(crate::sign::Signed {
                                url: url_s,
                                headers: hdrs,
                            })
                        } else {
                            None
                        }
                    } else {
                        None
                    }
                } else {
                    None
                }
            } else {
                None
            }
        } else {
            None
        };

        let mut s = match signed {
            Some(s) => s,
            None => {
                let mut rng = rand_f64;
                sign_v3(
                    &base_url,
                    &q,
                    raw.as_deref(),
                    &dev.id,
                    &version,
                    now_ms(),
                    &mut rng,
                )?
            }
        };

        for (k, v) in &mut s.headers {
            if k.eq_ignore_ascii_case("User-Agent") {
                *v = v
                    .replacen("/72232 ", &format!("/{APP_VERSION_CODE} "), 1)
                    .replacen("/71332 ", &format!("/{APP_VERSION_CODE} "), 1);
            }
        }
        let ck = self.user.cookie_string();
        if !ck.is_empty() {
            s.headers.push(("Cookie".into(), ck));
        }
        s.headers.push(("X-Xs-From-Web".into(), "0".into()));
        let method = if raw.is_some() { "POST" } else { "GET" };
        let r = do_request(&self.http, method, &s.url, &s.headers, raw.as_deref()).await?;
        self.parse(&r)
    }

    pub async fn profile(&self) -> Result<Profile, String> {
        let m = self.get("/reading/user/info/v1/", &[]).await?;
        let d = &m["data"];
        let uid = jstr(&d["biz_user_id"]);
        if !d.is_object() || uid.trim().is_empty() {
            return Err("SESSION_EXPIRED:登录已失效，请重新登录".into());
        }
        let avatar = img_url(&nz(jstr(&d["expand_avatar_url"]), jstr(&d["avatar_url"])));
        let cover = img_url(&jstr(&d["profile_cover_url"]));
        Ok(Profile {
            uid,
            name: jstr(&d["user_name"]),
            avatar,
            cover,
            signature: jstr(&d["description"]),
            vip: jstr(&d["vip_info"]["is_vip"]) == "1",
            watch_ms: jnum(&d["video_consume_duration"]) as i64,
            active_days: jnum(&d["active_day"]) as i32,
        })
    }

    pub async fn heartbeat(&self) -> Result<(), String> {
        let q = self.params().await?;
        let mut h = self.headers();
        h.push(("Content-Type".into(), "application/x-www-form-urlencoded".into()));
        let u = format!("{APP_HOST}/passport/token/beat/v2/?{}", encode_params(&q));
        let r = do_request(&self.http, "POST", &u, &h, Some(b"scene=normal")).await?;
        let _ = self.parse(&r)?;
        Ok(())
    }

    pub async fn logout_remote(&self) {
        if let Ok(q) = self.params().await {
            let body = b"{}";
            let h = vec![
                ("User-Agent".into(), APP_UA.into()),
                ("Cookie".into(), self.user.cookie_string()),
                ("Content-Type".into(), "application/json; charset=UTF-8".into()),
                ("x-ss-stub".into(), hex::encode_upper(md5_bytes(body))),
            ];
            let u = format!("{APP_HOST}/passport/user/logout/?{}", encode_params(&q));
            let _ = do_request(&self.http, "POST", &u, &h, Some(body)).await;
        }
    }

    pub async fn history(&self, limit: usize) -> Result<Vec<Entry>, String> {
        let mut out = Vec::new();
        let mut offset = 0i64;
        while out.len() < limit {
            let m = self
                .get(
                    "/reading/bookapi/read_history/list/v1/",
                    &[
                        ("book_type".into(), "2".into()),
                        ("limit".into(), "500".into()),
                        ("last_min_read_timestamp_ms".into(), "0".into()),
                        ("offset".into(), offset.to_string()),
                        ("full_field".into(), "false".into()),
                        ("is_first_load".into(), "false".into()),
                        ("query_soft_deleted".into(), "false".into()),
                    ],
                )
                .await?;
            check_ok(&m, "读取观看历史")?;
            let blk = &m["data"];
            if !blk.is_object() || blk.get("has_more").is_none() {
                return Err("观看历史分页状态不完整，请重试".into());
            }
            if let Some(rows) = blk["data_list"].as_array() {
                for row in rows {
                    if row["is_delete"].as_bool() == Some(true) {
                        continue;
                    }
                    let mut sid = jstr(&row["book_id_str"]);
                    if sid.is_empty() || sid == "null" {
                        sid = jstr(&row["book_id"]);
                    }
                    if !is_digits(&sid) {
                        continue;
                    }
                    let ep_idx = (jnum(&row["vid_index"]) as i32 - 1).max(0);
                    let vid_n = jnum(&row["vid"]) as i64;
                    let vid = if vid_n > 0 { jstr(&row["vid"]) } else { String::new() };
                    out.push(Entry {
                        series_id: sid,
                        title: jstr(&row["book_name"]),
                        cover: img_url(&jstr(&row["thumb_url"])),
                        vid,
                        episode_index: ep_idx,
                        episode_count: jnum(&row["episode_cnt"]) as i32,
                        position_ms: jnum(&row["current_play_position"]) as i32,
                        time: jnum(&row["read_timestamp_ms"]) as i64,
                    });
                }
            }
            let more = blk["has_more"].as_bool() == Some(true);
            let next = jnum(&blk["next_offset"]) as i64;
            if !more {
                break;
            }
            if next <= offset {
                return Err("观看历史分页不完整，请重试".into());
            }
            offset = next;
        }
        out.sort_by(|a, b| b.time.cmp(&a.time));
        out.truncate(limit);
        Ok(out)
    }

    pub async fn apply_progress(&self, list: &mut [Entry]) {
        let mut ids: Vec<i64> = Vec::new();
        let mut seen = HashSet::new();
        for e in list.iter() {
            if is_digits(&e.series_id) && seen.insert(e.series_id.clone()) {
                if let Ok(n) = e.series_id.parse::<i64>() {
                    ids.push(n);
                }
            }
        }
        for chunk in ids.chunks(50) {
            let body = serde_json::json!({ "book_ids": { "2": chunk } });
            let Ok(m) = self.post("/reading/bookapi/read_progress/get/v1/", &body).await else {
                return;
            };
            if check_ok(&m, "读取播放进度").is_err() {
                return;
            }
            let mut by: HashMap<String, &serde_json::Value> = HashMap::new();
            if let Some(arr) = m["video_data"].as_array() {
                for row in arr {
                    let sid = jstr(&row["series_id"]);
                    if !sid.is_empty() {
                        by.insert(sid, row);
                    }
                }
            }
            for item in list.iter_mut() {
                let Some(&row) = by.get(&item.series_id) else {
                    continue;
                };
                let idx = (jnum(&row["vid_index"]) as i32 - 1).max(0);
                let mut watched_at = jnum(&row["last_watch_time"]) as i64;
                if watched_at > 0 && watched_at < 100_000_000_000 {
                    watched_at *= 1000;
                }
                if watched_at > 0 && item.time > 0 && watched_at + 999 < item.time {
                    continue;
                }
                if watched_at > item.time {
                    item.time = watched_at;
                }
                item.episode_index = idx;
                item.position_ms = jnum(&row["current_play_time"]) as i32;
                let cnt = jnum(&row["episode_count"]) as i32;
                if cnt > 0 {
                    item.episode_count = cnt;
                }
                let v = jstr(&row["vid"]);
                if !v.is_empty() && v != "0" {
                    item.vid = v;
                }
            }
        }
    }

    pub async fn cloud_history(&self, limit: usize) -> Result<Vec<Entry>, String> {
        let mut h = self.history(limit).await?;
        self.apply_progress(&mut h).await;
        h.sort_by(|a, b| b.time.cmp(&a.time));
        // Merge local dirty rows
        let local: Vec<LocalProgressRow> = {
            let g = self.lib_rows.lock().unwrap();
            g.values().cloned().collect()
        };
        let mut by_id: HashMap<String, Entry> = h
            .into_iter()
            .map(|e| (e.series_id.clone(), e))
            .collect();
        for lr in local {
            if lr.deleted {
                by_id.remove(&lr.series_id);
                continue;
            }
            let replace = match by_id.get(&lr.series_id) {
                None => true,
                Some(cur) => lr.ts >= cur.time,
            };
            if replace {
                let prev = by_id.get(&lr.series_id).cloned().unwrap_or_default();
                by_id.insert(
                    lr.series_id.clone(),
                    Entry {
                        series_id: lr.series_id,
                        title: nz(lr.title, prev.title),
                        cover: img_url(&nz(lr.cover, prev.cover)),
                        vid: lr.vid,
                        episode_index: lr.index,
                        episode_count: if lr.episode_count > 0 {
                            lr.episode_count
                        } else {
                            prev.episode_count
                        },
                        position_ms: lr.pos,
                        time: lr.ts,
                    },
                );
            }
        }
        let mut out: Vec<Entry> = by_id.into_values().collect();
        out.sort_by(|a, b| b.time.cmp(&a.time));
        out.truncate(limit);
        Ok(out)
    }

    pub fn local_history(&self, limit: usize) -> Vec<Entry> {
        let g = self.lib_rows.lock().unwrap();
        let mut list: Vec<Entry> = g
            .values()
            .filter(|r| !r.deleted)
            .map(|r| Entry {
                series_id: r.series_id.clone(),
                title: r.title.clone(),
                cover: img_url(&r.cover),
                vid: r.vid.clone(),
                episode_index: r.index,
                episode_count: r.episode_count,
                position_ms: r.pos,
                time: r.ts,
            })
            .collect();
        list.sort_by(|a, b| b.time.cmp(&a.time));
        list.truncate(limit);
        list
    }

    pub fn save_local_progress(&self, row: LocalProgressRow) {
        let mut g = self.lib_rows.lock().unwrap();
        g.insert(row.series_id.clone(), row);
        self.save_lib_locked(&g);
    }

    pub fn forget_local_history(&self, series_id: &str) {
        let mut g = self.lib_rows.lock().unwrap();
        if let Some(r) = g.get_mut(series_id) {
            r.deleted = true;
            r.dirty = false;
        }
        self.save_lib_locked(&g);
    }

    pub fn clear_local_library(&self) {
        let mut g = self.lib_rows.lock().unwrap();
        g.clear();
        let _ = std::fs::remove_file(&self.lib_path);
    }

    fn save_lib_locked(&self, g: &HashMap<String, LocalProgressRow>) {
        if let Some(parent) = self.lib_path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        if let Ok(json) = serde_json::to_string(g) {
            let tmp = PathBuf::from(format!("{}.tmp", self.lib_path.to_string_lossy()));
            if std::fs::write(&tmp, json).is_ok() {
                let _ = std::fs::rename(&tmp, &self.lib_path);
            }
        }
    }

    pub async fn report_progress(
        &self,
        series_id: &str,
        vid: &str,
        index: i32,
        pos: i32,
        title: &str,
        cover: &str,
        episode_count: i32,
        upload_now: bool,
    ) -> Result<(), String> {
        if !is_digits(series_id) || !is_digits(vid) {
            return Err("参数无效".into());
        }
        let ts = now_ms();
        let row = LocalProgressRow {
            series_id: series_id.to_string(),
            vid: vid.to_string(),
            index: index.max(0),
            pos: pos.max(0),
            title: title.to_string(),
            cover: cover.to_string(),
            episode_count: episode_count.max(0),
            ts,
            dirty: true,
            deleted: false,
        };
        self.save_local_progress(row.clone());
        if upload_now && self.user.logged_in() {
            let _ = self.upload_progress_rows(&[row]).await;
        }
        Ok(())
    }

    pub async fn flush_dirty_progress(&self) {
        if !self.user.logged_in() {
            return;
        }
        let dirty: Vec<LocalProgressRow> = {
            let g = self.lib_rows.lock().unwrap();
            g.values().filter(|r| r.dirty && !r.deleted).cloned().collect()
        };
        if dirty.is_empty() {
            return;
        }
        if self.upload_progress_rows(&dirty).await.is_ok() {
            let mut g = self.lib_rows.lock().unwrap();
            for r in &dirty {
                if let Some(cur) = g.get_mut(&r.series_id) {
                    if cur.ts == r.ts {
                        cur.dirty = false;
                    }
                }
            }
            self.save_lib_locked(&g);
        }
    }

    async fn upload_progress_rows(&self, rows: &[LocalProgressRow]) -> Result<(), String> {
        let mut books = Vec::new();
        let mut hist_list = Vec::new();
        for r in rows {
            if !is_digits(&r.series_id) || !is_digits(&r.vid) {
                continue;
            }
            books.push(serde_json::json!({
                "book_id": r.series_id, "book_type": 2, "item_id": r.vid, "read_timestamp_ms": r.ts, "current_play_time": r.pos.max(0),
                "player_cumulative_total_duration": 0, "vid_index": 0, "channel_id": 0, "cur_channel_id": 0,
                "page_index": 0, "page_progress_rate": 0.0, "paragraph_offset": 0, "progress_type": 0, "tone_id": 0,
                "check_timestamp": false, "is_listen_mode": false, "is_local_book": false, "listen_and_read": false,
            }));
            if let (Ok(sid), Ok(v)) = (r.series_id.parse::<i64>(), r.vid.parse::<i64>()) {
                hist_list.push(serde_json::json!({
                    "book_id": sid, "book_type": 2, "is_delete": false, "read_timestamp_ms": r.ts,
                    "update_timestamp_ms": r.ts, "recent_reads": 0, "tone_id": 0, "current_play_position": r.pos.max(0),
                    "player_accumulate_total_time": r.pos.max(0), "vid_index": r.index.max(0) + 1, "vid": v, "episode_cnt": 0, "duration": 0,
                    "series_play_cnt": 0, "retain_video_play_time": 0, "digged_count": 0, "user_digg": false, "is_listen_mode": false,
                    "is_multi_season": 0, "season_index": 0, "chapter_index": 0, "is_interactive_game": false,
                    "origin_novel_book_id": 0, "meet_guide_comment_tag": false, "user_playlet_comment_flag": false, "use_soft_delete": false
                }));
            }
        }
        if !books.is_empty() {
            let _ = self
                .post("/reading/bookapi/read_progress/upload/v1/", &serde_json::json!({ "books": books }))
                .await;
        }
        if !hist_list.is_empty() {
            let m = self
                .account_post(
                    "/reading/bookapi/read_history/update/v1/",
                    &serde_json::json!({ "update_datas": hist_list }),
                )
                .await?;
            check_ok(&m, "上报观看历史")?;
        }
        Ok(())
    }

    async fn account_post(
        &self,
        path: &str,
        body: &serde_json::Value,
    ) -> Result<serde_json::Value, String> {
        let dev = self.devices.get().await?;
        let raw = serde_json::to_vec(body).map_err(|e| e.to_string())?;
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
        let stamp = now_ms();
        let (query, signed_hdrs) =
            sign_passport_request_version(&mut q, &raw, stamp, &dev.id, "7.3.5.32")?;
        let mut h: Vec<(String, String)> = signed_hdrs.into_iter().collect();
        h.push(("User-Agent".into(), MINI_REEL_SINGLE_UA.into()));
        h.push(("Content-Type".into(), "application/json; charset=UTF-8".into()));
        h.push(("Accept".into(), "application/json; charset=utf-8".into()));
        h.push(("Passport-Sdk-Version".into(), "50561".into()));
        h.push(("X-SS-DP".into(), "8662".into()));
        h.push(("Cookie".into(), self.user.cookie_string()));
        h.push((
            "X-Reading-Request".into(),
            format!("{stamp}-{}", protocol_random() & 0x7fffffff),
        ));
        let u = format!("{APP_HOST}{path}?{query}");
        let r = do_request(&self.http, "POST", &u, &h, Some(&raw)).await?;
        if r.status < 200 || r.status >= 300 {
            return Err(format!("HTTP {}", r.status));
        }
        let m = r.json().map_err(|_| "空响应".to_string())?;
        let code = jnum(&m["code"]) as i64;
        if jstr(&m["message"]).contains("session_expired") || code == 100004 {
            return Err("SESSION_EXPIRED:登录已失效，请重新登录".into());
        }
        Ok(m)
    }

    pub async fn delete_history(&self, series_id: &str) -> Result<(), String> {
        if !is_digits(series_id) {
            return Err("参数无效".into());
        }
        self.forget_local_history(series_id);
        if !self.user.logged_in() {
            return Ok(());
        }
        let sid: i64 = series_id.parse().map_err(|_| "参数无效".to_string())?;
        let now = now_ms();
        let row = serde_json::json!({
            "book_id": sid, "book_type": 2, "is_delete": true, "use_soft_delete": false,
            "update_timestamp_ms": now, "read_timestamp_ms": now
        });
        let m = self
            .account_post(
                "/reading/bookapi/read_history/update/v1/",
                &serde_json::json!({ "update_datas": [row] }),
            )
            .await?;
        check_ok(&m, "删除观看历史")
    }

    pub async fn favorites(&self) -> Result<Vec<Entry>, String> {
        let m = self
            .get(
                "/reading/bookapi/bookshelf/video/info/v1/",
                &[("offset".into(), "0".into()), ("count".into(), "0".into())],
            )
            .await?;
        check_ok(&m, "读取收藏")?;
        let mut out = Vec::new();
        if let Some(arr) = m["data"]["video_shelf_info"].as_array() {
            for row in arr {
                let v = if row["video_data"].is_object() {
                    &row["video_data"]
                } else {
                    row
                };
                let sid = nz(
                    jstr(&v["series_id"]),
                    nz(jstr(&row["series_id"]), jstr(&row["book_id"])),
                );
                if !is_digits(&sid) {
                    continue;
                }
                let mut t = jnum(&row["collect_time"]) as i64;
                if t > 0 && t < 10_000_000_000 {
                    t *= 1000;
                }
                let ep_idx = (jnum(&v["vid_index"]) as i32 - 1).max(0);
                let vid = if jnum(&v["vid"]) > 0.0 {
                    jstr(&v["vid"])
                } else {
                    String::new()
                };
                out.push(Entry {
                    series_id: sid,
                    title: nz(jstr(&row["series_title"]), jstr(&v["title"])),
                    cover: img_url(&nz(
                        jstr(&row["series_cover_url"]),
                        nz(
                            jstr(&v["cover_url"]),
                            nz(jstr(&v["cover"]), jstr(&v["thumb_url"])),
                        ),
                    )),
                    vid,
                    episode_index: ep_idx,
                    episode_count: jnum(&v["episode_cnt"]) as i32,
                    position_ms: 0,
                    time: t,
                });
            }
        }
        out.sort_by(|a, b| b.time.cmp(&a.time));
        self.apply_progress(&mut out).await;
        Ok(out)
    }

    pub async fn set_favorite(&self, series_id: &str, add: bool) -> Result<(), String> {
        if !is_digits(series_id) {
            return Err("剧集编号无效".into());
        }
        let ts = now_ms();
        let op = if add { 0 } else { 1 };
        let body = serde_json::json!({
            "book_id_list": [series_id], "is_cancelled": false, "modify_time": [ts],
            "update_bookshelf_video_list": [{
                "book_id": series_id, "book_type": 2, "group_name": "",
                "modify_time": ts, "video_shelf_operate_type": op
            }],
        });
        let m = self
            .signed(
                APP_HOST,
                "/reading/bookapi/bookshelf/video/update/v1/",
                &[],
                Some(&body),
            )
            .await?;
        let what = if add { "加入收藏" } else { "取消收藏" };
        check_ok(&m, what)?;
        for i in 0..3 {
            if i > 0 {
                tokio::time::sleep(std::time::Duration::from_secs(1)).await;
            }
            if let Ok(fav) = self.favorites().await {
                let found = fav.iter().any(|f| f.series_id == series_id);
                if found == add {
                    return Ok(());
                }
            }
        }
        Err("云端收藏状态未确认，请重试".into())
    }

    pub async fn reservations(&self, online: bool) -> Result<Vec<Reservation>, String> {
        let q = vec![
            ("offset".into(), "0".into()),
            ("limit".into(), "50".into()),
            ("is_online".into(), online.to_string()),
            ("tab_type".into(), "13".into()),
            ("session_id".into(), String::new()),
            ("subscribe_order_type".into(), "0".into()),
            ("swipe_type".into(), "0".into()),
            ("need_calendar_schema".into(), "false".into()),
            ("subscribe_offset".into(), "0".into()),
        ];
        let m = self
            .signed(SUBSCRIBE_HOST, "/reading/user/subscribe/list/v1/", &q, None)
            .await?;
        check_ok(&m, "读取预约")?;
        let mut out = Vec::new();
        if let Some(arr) = m["data"]["subscribe_items"].as_array() {
            for row in arr {
                let sd = &row["subscribe_data"];
                let on = row["is_online"].as_bool().unwrap_or(online);
                let id = if on {
                    nz(jstr(&sd["series_id"]), jstr(&row["item_id"]))
                } else {
                    nz(jstr(&row["item_id"]), jstr(&sd["series_id"]))
                };
                if !is_digits(&id) {
                    continue;
                }
                let mut t = jnum(&row["expected_publish_time"]) as i64;
                if t == 0 {
                    t = jnum(&row["schedule_publish_time"]) as i64;
                }
                if t > 0 && t < 10_000_000_000 {
                    t *= 1000;
                }
                out.push(Reservation {
                    series_id: id,
                    title: nz(
                        jstr(&sd["title"]),
                        nz(jstr(&row["name"]), jstr(&row["title"])),
                    ),
                    cover: img_url(&nz(jstr(&sd["cover"]), jstr(&row["cover"]))),
                    online: on,
                    desc: nz(jstr(&sd["video_desc"]), jstr(&row["item_desc"])),
                    episode_count: jnum(&sd["episode_cnt"]) as i32,
                    publish_text: publish_label(t, &jstr(&row["expected_rec_text"])),
                    time: t,
                });
            }
        }
        Ok(out)
    }

    pub async fn set_reservation(&self, series_id: &str, add: bool) -> Result<(), String> {
        if !is_digits(series_id) {
            return Err("剧集编号无效".into());
        }
        let (op, what) = if add { (1, "预约") } else { (2, "取消预约") };
        let body = serde_json::json!({
            "item_id": series_id, "item_type": 1, "op_type": op, "item_id_str": series_id
        });
        let m = self
            .signed(
                SUBSCRIBE_HOST,
                "/reading/bookapi/search/uncover_subscribe/v1/",
                &[],
                Some(&body),
            )
            .await?;
        check_ok(&m, what)
    }
}

pub fn check_ok(m: &serde_json::Value, what: &str) -> Result<(), String> {
    let mut code = jnum(&m["code"]) as i64;
    if code == 0 {
        code = jnum(&m["Code"]) as i64;
    }
    if code != 0 {
        let mut msg = jstr(&m["message"]);
        if !msg.is_empty() && !msg.eq_ignore_ascii_case("success") {
            let truncated: String = msg.chars().take(30).collect();
            msg = format!("：{truncated}");
        } else {
            msg.clear();
        }
        return Err(format!("{what}未完成（{code}）{msg}"));
    }
    Ok(())
}

fn publish_label(ms: i64, text: &str) -> String {
    if ms > 0 {
        let zone = FixedOffset::east_opt(8 * 3600).unwrap();
        if let Some(dt) = zone.timestamp_millis_opt(ms).single() {
            let mut s = format!("{}月{}日", dt.month(), dt.day());
            if dt.hour() != 0 || dt.minute() != 0 {
                s.push_str(&format!(" {:02}:{:02}", dt.hour(), dt.minute()));
            }
            return format!("{s} 上线");
        }
    }
    let t = text.trim();
    if t.chars().any(|c| c.is_ascii_digit()) {
        t.to_string()
    } else {
        String::new()
    }
}
