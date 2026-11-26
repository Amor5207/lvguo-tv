use crate::downloader::DownloadManager;
use crate::hotupdate::HotUpdateManager;
use crate::media_server::MediaServer;
use crate::models::{AddDownloadRequest, DownloadActionRequest, DownloadSettings};
use crate::upstream::account::{Account, LocalProgressRow};
use crate::upstream::auth::{PhoneLogin, PhoneLoginOutcome, QrSession};
use crate::upstream::content::Content;
use crate::upstream::device::{
    build_http_client, now_secs, DeviceStore, UserJar,
};
use once_cell::sync::OnceCell;
use reqwest::Client;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::ffi::{CStr, CString};
use std::os::raw::c_char;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use tokio::runtime::Runtime;
use uuid::Uuid;

struct AppCore {
    rt: Runtime,
    #[allow(dead_code)]
    media_rt: Runtime,
    dl_rt: Runtime,
    data_dir: PathBuf,
    is_player: bool,
    #[allow(dead_code)]
    http: Client,
    devices: Arc<DeviceStore>,
    user: Arc<UserJar>,
    hot: Arc<HotUpdateManager>,
    account: Arc<Account>,
    content: Arc<Content>,
    downloader: Arc<DownloadManager>,
    media_server: Arc<MediaServer>,
    danmaku_tx: tokio::sync::mpsc::UnboundedSender<(String, String)>,
    qr_sessions: tokio::sync::Mutex<HashMap<String, QrSession>>,
    phone_sessions: tokio::sync::Mutex<HashMap<String, PhoneLogin>>,
    last_heartbeat_at: Mutex<i64>,
}

static CORE: OnceCell<Mutex<Option<Arc<AppCore>>>> = OnceCell::new();

fn core_slot() -> &'static Mutex<Option<Arc<AppCore>>> {
    CORE.get_or_init(|| Mutex::new(None))
}

fn current_core() -> Result<Arc<AppCore>, String> {
    core_slot()
        .lock()
        .unwrap()
        .clone()
        .ok_or_else(|| "核心尚未初始化".to_string())
}

fn cstr_to_string(ptr: *const c_char) -> String {
    if ptr.is_null() {
        return String::new();
    }
    unsafe { CStr::from_ptr(ptr).to_string_lossy().into_owned() }
}

fn to_c_ptr(val: Value) -> *mut c_char {
    let text = val.to_string();
    match CString::new(text) {
        Ok(c) => c.into_raw(),
        Err(_) => CString::new(r#"{"ok":false,"error":"编码响应失败"}"#)
            .unwrap()
            .into_raw(),
    }
}

fn ok_json(data: Value) -> Value {
    json!({
        "ok": true,
        "data": data,
    })
}

fn err_json(msg: &str) -> Value {
    json!({
        "ok": false,
        "error": msg,
    })
}

#[no_mangle]
pub extern "C" fn lvguo_init(data_dir_ptr: *const c_char) -> *mut c_char {
    let raw_dir = cstr_to_string(data_dir_ptr);
    let data_dir = if raw_dir.trim().is_empty() {
        dirs::data_local_dir()
            .unwrap_or_else(std::env::temp_dir)
            .join("LvguoTV")
    } else {
        PathBuf::from(raw_dir.trim())
    };
    if let Err(e) = std::fs::create_dir_all(&data_dir) {
        return to_c_ptr(err_json(&format!("创建数据目录失败: {e}")));
    }

    {
        let guard = core_slot().lock().unwrap();
        if let Some(existing) = guard.as_ref() {
            return to_c_ptr(ok_json(json!({
                "mediaBaseUrl": existing.media_server.base_url(),
                "dataDir": existing.data_dir.to_string_lossy(),
            })));
        }
    }

    let rt = match tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .worker_threads(2)
        .thread_name("lvguo-ui")
        .build()
    {
        Ok(rt) => rt,
        Err(e) => return to_c_ptr(err_json(&format!("启动 UI 运行时失败: {e}"))),
    };
    let media_rt = match tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .worker_threads(2)
        .thread_name("lvguo-media")
        .build()
    {
        Ok(rt) => rt,
        Err(e) => return to_c_ptr(err_json(&format!("启动播放运行时失败: {e}"))),
    };
    let dl_rt = match tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .worker_threads(2)
        .max_blocking_threads(2)
        .thread_name("lvguo-download")
        .build()
    {
        Ok(rt) => rt,
        Err(e) => return to_c_ptr(err_json(&format!("启动下载运行时失败: {e}"))),
    };

    let http = build_http_client(30);
    let devices = Arc::new(DeviceStore::new(&data_dir, http.clone()));
    let user = Arc::new(UserJar::new(&data_dir));
    let hot = HotUpdateManager::new(&data_dir);
    hot.start_background(rt.handle());

    let account = Account::new(
        &data_dir,
        http.clone(),
        devices.clone(),
        user.clone(),
        hot.clone(),
    );
    let is_player = std::env::args().any(|a| a == "--player");
    let content = Content::new(http.clone(), devices.clone(), hot.clone());
    let downloader = DownloadManager::new_with_mode(
        data_dir.clone(),
        content.clone(),
        dl_rt.handle(),
        !is_player,
    );
    let media_server = match MediaServer::start(content.clone(), http.clone(), media_rt.handle()) {
        Ok(srv) => srv,
        Err(e) => return to_c_ptr(err_json(&e)),
    };
    let media_base = media_server.base_url();

    let (danmaku_tx, mut danmaku_rx) = tokio::sync::mpsc::unbounded_channel::<(String, String)>();
    if !is_player {
        let dm_content = content.clone();
        let dm_user = user.clone();
        let dm_dir = data_dir.clone();
        dl_rt.spawn(async move {
            while let Some((series_id, vid)) = danmaku_rx.recv().await {
                if vid.is_empty() {
                    continue;
                }
                let cache_path = crate::downloader::danmaku_cache_path(&dm_dir, &vid);
                if cache_path.is_file() {
                    continue;
                }
                let cookie = if dm_user.logged_in() {
                    dm_user.cookie_string()
                } else {
                    String::new()
                };
                if let Ok(val) = dm_content.comments(&cookie, &series_id, &vid, 0).await {
                    if let Some(parent) = cache_path.parent() {
                        let _ = std::fs::create_dir_all(parent);
                    }
                    if let Ok(bytes) = serde_json::to_vec(&val) {
                        let tmp = PathBuf::from(format!("{}.tmp", cache_path.to_string_lossy()));
                        if std::fs::write(&tmp, &bytes).is_ok() {
                            let _ = std::fs::rename(&tmp, &cache_path);
                        }
                    }
                }
                tokio::time::sleep(std::time::Duration::from_millis(150)).await;
            }
        });
    }

    let core = Arc::new(AppCore {
        rt,
        media_rt,
        dl_rt,
        data_dir: data_dir.clone(),
        is_player,
        http,
        devices,
        user,
        hot,
        account,
        content,
        downloader,
        media_server,
        danmaku_tx,
        qr_sessions: tokio::sync::Mutex::new(HashMap::new()),
        phone_sessions: tokio::sync::Mutex::new(HashMap::new()),
        last_heartbeat_at: Mutex::new(0),
    });

    *core_slot().lock().unwrap() = Some(core);

    to_c_ptr(ok_json(json!({
        "mediaBaseUrl": media_base,
        "dataDir": data_dir.to_string_lossy(),
    })))
}

#[no_mangle]
pub extern "C" fn lvguo_shutdown() {
    let old = core_slot().lock().unwrap().take();
    if let Some(core) = old {
        core.media_server.shutdown();
        core.downloader.shutdown();
        drop(core);
    }
}

#[no_mangle]
pub extern "C" fn lvguo_media_base_url() -> *mut c_char {
    match current_core() {
        Ok(core) => {
            CString::new(core.media_server.base_url())
                .unwrap_or_else(|_| CString::new("").unwrap())
                .into_raw()
        }
        Err(_) => CString::new("").unwrap().into_raw(),
    }
}

#[no_mangle]
pub extern "C" fn lvguo_call(
    action_ptr: *const c_char,
    params_json_ptr: *const c_char,
) -> *mut c_char {
    let action = cstr_to_string(action_ptr);
    let raw_params = cstr_to_string(params_json_ptr);
    let params: Value = if raw_params.trim().is_empty() {
        json!({})
    } else {
        match serde_json::from_str(&raw_params) {
            Ok(v) => v,
            Err(e) => return to_c_ptr(err_json(&format!("参数 JSON 无效: {e}"))),
        }
    };

    let core = match current_core() {
        Ok(c) => c,
        Err(e) => return to_c_ptr(err_json(&e)),
    };

    let result = core.rt.block_on(core.dispatch(action.trim(), params));
    match result {
        Ok(data) => to_c_ptr(ok_json(data)),
        Err(msg) => to_c_ptr(err_json(&msg)),
    }
}

#[no_mangle]
pub extern "C" fn lvguo_free_string(ptr: *mut c_char) {
    if !ptr.is_null() {
        unsafe {
            let _ = CString::from_raw(ptr);
        }
    }
}

impl AppCore {
    fn cookie(&self) -> String {
        if self.user.logged_in() {
            self.user.cookie_string()
        } else {
            String::new()
        }
    }

    async fn dispatch(self: &Arc<Self>, action: &str, params: Value) -> Result<Value, String> {
        match action {
            // ---------------- 账号与登录 ----------------
            "me" => self.act_me().await,
            "login.qr.start" => self.act_qr_start().await,
            "login.qr.poll" => self.act_qr_poll(&params).await,
            "login.phone.send" => self.act_phone_send(&params).await,
            "login.phone.login" => self.act_phone_login(&params).await,
            "login.phone.verify" => self.act_phone_verify(&params).await,
            "logout" => self.act_logout().await,

            // ---------------- 内容浏览与播放 ----------------
            "home" => self.act_home().await,
            "foryou" => self.act_for_you(&params).await,
            "rank" => self.act_rank(&params).await,
            "category.options" => self.act_category_options(&params).await,
            "category.list" => self.act_category_list(&params).await,
            "search" => self.act_search(&params).await,
            "suggest" => self.act_suggest(&params).await,
            "detail" => self.act_detail(&params).await,
            "seasons" => self.act_seasons(&params).await,
            "comments" => self.act_comments(&params).await,
            "play" => self.act_play(&params).await,

            // ---------------- 历史 / 收藏 / 预约 / 进度 ----------------
            "history.list" => self.act_history_list(&params).await,
            "history.delete" => self.act_history_delete(&params).await,
            "favorites.list" => self.act_favorites_list().await,
            "favorite.set" => self.act_favorite_set(&params).await,
            "reservations.list" => self.act_reservations_list(&params).await,
            "reservation.set" => self.act_reservation_set(&params).await,
            "progress.report" => self.act_progress_report(&params).await,

            // ---------------- 下载与离线 ----------------
            "download.list" => self.act_download_list(),
            "download.summary" => self.act_download_summary(),
            "download.add" => self.act_download_add(&params),
            "download.add_batch" => self.act_download_add_batch(&params),
            "download.delete_batch" => self.act_download_delete_batch(&params),
            "download.action" => self.act_download_action(&params),
            "download.settings.get" => Ok(serde_json::to_value(self.downloader.settings()).unwrap()),
            "download.settings.set" => self.act_download_settings_set(&params),
            "download.local" => self.act_download_local(&params),
            "download.open" => self.act_download_open(&params),

            // ---------------- 热更新与算法模块 ----------------
            "hotupdate.status" => Ok(serde_json::to_value(self.hot.status()).unwrap()),
            "hotupdate.check" => {
                let include_module = params["includeModule"].as_bool().unwrap_or(true);
                let st = self.hot.check_updates(include_module).await?;
                Ok(serde_json::to_value(st).unwrap())
            }
            "hotupdate.set_urls" => {
                let cfg_url = params["configUrl"].as_str();
                let mod_url = params["moduleManifestUrl"].as_str();
                self.hot.set_urls(cfg_url, mod_url);
                Ok(serde_json::to_value(self.hot.status()).unwrap())
            }
            "hotupdate.rollback" => Ok(serde_json::to_value(self.hot.rollback_module()).unwrap()),
            "app.update.check" => Ok(serde_json::to_value(self.hot.check_app_update().await).unwrap()),

            _ => Err(format!("未知指令: {action}")),
        }
    }

    async fn act_me(self: &Arc<Self>) -> Result<Value, String> {
        if !self.user.logged_in() {
            return Ok(json!({ "loggedIn": false }));
        }
        let profile = self.account.profile().await?;
        let now = now_secs();
        let need_hb = {
            let mut last = self.last_heartbeat_at.lock().unwrap();
            if now - *last > 6 * 3600 {
                *last = now;
                true
            } else {
                false
            }
        };
        if need_hb {
            let acct = self.account.clone();
            self.rt.spawn(async move {
                let _ = acct.heartbeat().await;
            });
        }
        Ok(json!({
            "loggedIn": true,
            "profile": profile,
            "daysLeft": self.user.days_left(),
        }))
    }

    async fn act_qr_start(&self) -> Result<Value, String> {
        let (sess, res) = QrSession::start().await?;
        let token = sess.token().to_string();
        let data_url = res.qr;
        let mut map = self.qr_sessions.lock().await;
        if map.len() > 8 {
            map.clear();
        }
        map.insert(token.clone(), sess);
        Ok(json!({
            "token": token,
            "qr": data_url,
            "expiresAt": res.expires_at,
            "openUrl": res.open,
        }))
    }

    async fn act_qr_poll(&self, params: &Value) -> Result<Value, String> {
        let token = pstr(params, "token");
        let mut map = self.qr_sessions.lock().await;
        let sess = if token.is_empty() {
            map.values_mut().next()
        } else {
            map.get_mut(&token)
        };
        let Some(sess) = sess else {
            return Ok(json!({ "state": "expired" }));
        };
        let (state, jar) = sess.poll().await?;
        if state == "confirmed" {
            if let Some(cookies) = jar {
                self.user.set(cookies);
            }
            if !token.is_empty() {
                map.remove(&token);
            } else {
                map.clear();
            }
        }
        Ok(json!({ "state": state }))
    }

    async fn act_phone_send(&self, params: &Value) -> Result<Value, String> {
        let phone = pstr(params, "phone");
        let existing_id = pstr(params, "sessionId");
        let mut map = self.phone_sessions.lock().await;
        let sid = if !existing_id.is_empty() && map.contains_key(&existing_id) {
            existing_id
        } else {
            let new_id = Uuid::new_v4().simple().to_string();
            let sess = PhoneLogin::new().await?;
            if map.len() > 8 {
                map.clear();
            }
            map.insert(new_id.clone(), sess);
            new_id
        };
        let sess = map.get_mut(&sid).unwrap();
        sess.send_code(&phone).await?;
        Ok(json!({ "sessionId": sid }))
    }

    async fn act_phone_login(&self, params: &Value) -> Result<Value, String> {
        let sid = pstr(params, "sessionId");
        let code = pstr(params, "code");
        let outcome = {
            let mut map = self.phone_sessions.lock().await;
            let sess = map
                .get_mut(&sid)
                .ok_or_else(|| "验证会话不存在或已过期，请重新发送验证码".to_string())?;
            sess.login(&code).await?
        };
        self.finish_phone_outcome(&sid, outcome).await
    }

    async fn act_phone_verify(&self, params: &Value) -> Result<Value, String> {
        let sid = pstr(params, "sessionId");
        let outcome = {
            let mut map = self.phone_sessions.lock().await;
            let sess = map
                .get_mut(&sid)
                .ok_or_else(|| "验证会话不存在或已过期".to_string())?;
            sess.verify_mfa().await?
        };
        self.finish_phone_outcome(&sid, outcome).await
    }

    async fn finish_phone_outcome(
        &self,
        sid: &str,
        outcome: PhoneLoginOutcome,
    ) -> Result<Value, String> {
        match outcome {
            PhoneLoginOutcome::Ok { cookies, device } => {
                self.devices.adopt(device).await;
                self.user.set(cookies);
                self.phone_sessions.lock().await.remove(sid);
                Ok(json!({ "status": "ok" }))
            }
            PhoneLoginOutcome::Mfa { number, content } => Ok(json!({
                "status": "mfa",
                "number": number,
                "content": content,
            })),
            PhoneLoginOutcome::Pending => Ok(json!({ "status": "pending" })),
        }
    }

    async fn act_logout(&self) -> Result<Value, String> {
        if self.user.logged_in() {
            self.account.flush_dirty_progress().await;
            self.account.logout_remote().await;
        }
        self.user.clear();
        self.account.clear_local_library();
        Ok(json!({ "ok": true }))
    }

    async fn act_home(&self) -> Result<Value, String> {
        let logged = self.user.logged_in();
        let (hist_res, channels, fy_res) = tokio::join!(
            async {
                if logged {
                    self.account.history(12).await
                } else {
                    Ok(self.account.local_history(12))
                }
            },
            self.content.feed_channels(&self.account),
            self.content.for_you(&self.account, "16", 0, "", "")
        );
        let cont = hist_res.unwrap_or_else(|_| self.account.local_history(12));
        let (page, cell, sess) = fy_res?;
        Ok(json!({
            "continue": cont,
            "forYou": page,
            "cell": cell,
            "session": sess,
            "channels": channels,
        }))
    }

    async fn act_for_you(&self, params: &Value) -> Result<Value, String> {
        let ch = pstr(params, "channel");
        let offset = params["offset"].as_i64().unwrap_or(0) as i32;
        let cell = pstr(params, "cell");
        let session = pstr(params, "session");
        let (page, next_cell, next_sess) = self
            .content
            .for_you(&self.account, &ch, offset, &cell, &session)
            .await?;
        Ok(json!({
            "page": page,
            "cell": next_cell,
            "session": next_sess,
        }))
    }

    async fn act_rank(&self, params: &Value) -> Result<Value, String> {
        let board = pstr(params, "board");
        let offset = params["offset"].as_i64().unwrap_or(0) as i32;
        let session = pstr(params, "session");
        let page = self
            .content
            .rank(&self.cookie(), &board, offset, &session)
            .await?;
        Ok(serde_json::to_value(page).unwrap())
    }

    async fn act_category_options(&self, params: &Value) -> Result<Value, String> {
        let typ = pstr(params, "type");
        let opts = self
            .content
            .category_options(&self.cookie(), &typ)
            .await?;
        Ok(serde_json::to_value(opts).unwrap())
    }

    async fn act_category_list(&self, params: &Value) -> Result<Value, String> {
        let typ = pstr(params, "type");
        let board = pstr(params, "board");
        let offset = params["offset"].as_i64().unwrap_or(0) as i32;
        let session = pstr(params, "session");
        let mut selected = HashMap::new();
        if let Some(obj) = params["selected"].as_object() {
            for (k, v) in obj {
                if let Some(s) = v.as_str() {
                    selected.insert(k.clone(), s.to_string());
                }
            }
        }
        let page = self
            .content
            .category_page(&self.cookie(), &typ, &board, &selected, offset, &session)
            .await?;
        Ok(serde_json::to_value(page).unwrap())
    }

    async fn act_search(&self, params: &Value) -> Result<Value, String> {
        let q = pstr(params, "q");
        let page = params["page"].as_i64().unwrap_or(1) as i32;
        let res = self.content.search(&self.account, &q, page).await?;
        Ok(serde_json::to_value(res).unwrap())
    }

    async fn act_suggest(&self, params: &Value) -> Result<Value, String> {
        let q = pstr(params, "q");
        let items = self.content.suggest(&self.account, &q).await;
        Ok(json!({ "items": items }))
    }

    async fn act_detail(&self, params: &Value) -> Result<Value, String> {
        let id = pstr(params, "id");
        let lite = params["lite"].as_bool().unwrap_or(false);
        let cookie = self.cookie();
        let detail = self.content.detail(&cookie, &id).await?;
        let mut out = json!({ "detail": detail });

        if !lite {
            if self.user.logged_in() {
                let (favs_res, hist_res) =
                    tokio::join!(self.account.favorites(), self.account.history(200));
                if let Ok(favs) = favs_res {
                    if favs.iter().any(|f| f.series_id == id) {
                        out["favorite"] = json!(true);
                    }
                }
                if let Ok(hist) = hist_res {
                    if let Some(p) = hist.into_iter().find(|h| h.series_id == id) {
                        out["progress"] = serde_json::to_value(p).unwrap();
                    }
                }
                if detail.episodes.is_empty() {
                    let (r0, r1) = tokio::join!(
                        self.account.reservations(false),
                        self.account.reservations(true)
                    );
                    let found = r0
                        .ok()
                        .and_then(|v| v.into_iter().find(|r| r.series_id == id))
                        .or_else(|| {
                            r1.ok()
                                .and_then(|v| v.into_iter().find(|r| r.series_id == id))
                        });
                    if let Some(rv) = found {
                        out["reserved"] = json!(true);
                        out["reservation"] = serde_json::to_value(rv).unwrap();
                    }
                }
            } else if let Some(p) = self
                .account
                .local_history(200)
                .into_iter()
                .find(|h| h.series_id == id)
            {
                out["progress"] = serde_json::to_value(p).unwrap();
            }
        }
        Ok(out)
    }

    async fn act_seasons(&self, params: &Value) -> Result<Value, String> {
        let id = pstr(params, "id");
        let quick = params["quick"].as_bool().unwrap_or(false);
        let list = self.content.seasons(&self.cookie(), &id, quick).await?;
        Ok(json!({ "items": list }))
    }

    async fn act_comments(&self, params: &Value) -> Result<Value, String> {
        let id = pstr(params, "id");
        let vid = pstr(params, "vid");
        let offset = params["offset"].as_i64().unwrap_or(0) as i32;
        let cache_path = if offset == 0 && !vid.is_empty() {
            Some(crate::downloader::danmaku_cache_path(&self.data_dir, &vid))
        } else {
            None
        };
        if let Some(ref cp) = cache_path {
            if self.downloader.get_local_path(&vid).is_some() && cp.is_file() {
                if let Ok(raw) = std::fs::read_to_string(cp) {
                    if let Ok(val) = serde_json::from_str::<Value>(&raw) {
                        return Ok(val);
                    }
                }
            }
        }
        match self.content.comments(&self.cookie(), &id, &vid, offset).await {
            Ok(val) => Ok(val),
            Err(err) => {
                if let Some(ref cp) = cache_path {
                    if cp.is_file() {
                        if let Ok(raw) = std::fs::read_to_string(cp) {
                            if let Ok(val) = serde_json::from_str::<Value>(&raw) {
                                return Ok(val);
                            }
                        }
                    }
                }
                Err(err)
            }
        }
    }

    async fn act_play(&self, params: &Value) -> Result<Value, String> {
        let vid = pstr(params, "vid");
        let quality = pstr(params, "q");
        let fresh = params["fresh"].as_bool().unwrap_or(false);
        let local_file = self.downloader.get_local_path(&vid);
        if !fresh {
            if let Some(ref path) = local_file {
                return Ok(json!({
                    "gears": [],
                    "definition": if quality.is_empty() { "本地" } else { quality.as_str() },
                    "width": 0,
                    "height": 0,
                    "mediaUrl": "",
                    "localFile": path,
                }));
            }
        }
        let gears = self.content.play(&vid, fresh).await?;
        let picked = crate::upstream::content::pick_gear(&gears, &quality, 1080);
        let active_def = picked
            .map(|g| g.definition.clone())
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| quality.clone());
        let width = picked.map(|g| g.width).unwrap_or(0);
        let height = picked.map(|g| g.height).unwrap_or(0);
        let media_url = self.media_server.media_url(&vid, &active_def);
        if params["prefetch"].as_bool() == Some(true) {
            self.media_server
                .prefetch(vid.clone(), active_def.clone(), self.media_rt.handle());
        }
        Ok(json!({
            "gears": gears,
            "definition": active_def,
            "width": width,
            "height": height,
            "mediaUrl": media_url,
            "localFile": local_file,
        }))
    }

    async fn act_history_list(&self, params: &Value) -> Result<Value, String> {
        let limit = params["limit"].as_u64().unwrap_or(100) as usize;
        let local = self.account.local_history(limit);
        let items = if self.user.logged_in() {
            match self.account.history(limit).await {
                Ok(remote) => {
                    let norm_ms = |t: i64| if t > 10_000_000_000 { t } else { t * 1000 };
                    let mut merged: HashMap<String, crate::upstream::account::Entry> = HashMap::new();
                    for item in remote {
                        merged.insert(item.series_id.clone(), item);
                    }
                    for item in local {
                        let replace = match merged.get(&item.series_id) {
                            Some(cur) => norm_ms(item.time) > norm_ms(cur.time),
                            None => true,
                        };
                        if replace {
                            merged.insert(item.series_id.clone(), item);
                        }
                    }
                    let mut out: Vec<_> = merged.into_values().collect();
                    out.sort_by(|a, b| norm_ms(b.time).cmp(&norm_ms(a.time)));
                    out.truncate(limit);
                    out
                }
                Err(_) => local,
            }
        } else {
            local
        };
        Ok(json!({ "items": items }))
    }

    async fn act_history_delete(&self, params: &Value) -> Result<Value, String> {
        let series_id = pstr(params, "seriesId");
        self.account.forget_local_history(&series_id);
        if self.user.logged_in() {
            self.account.delete_history(&series_id).await?;
        }
        Ok(json!({ "ok": true }))
    }

    async fn act_favorites_list(&self) -> Result<Value, String> {
        if !self.user.logged_in() {
            return Ok(json!({ "items": [] }));
        }
        let items = self.account.favorites().await?;
        Ok(json!({ "items": items }))
    }

    async fn act_favorite_set(&self, params: &Value) -> Result<Value, String> {
        if !self.user.logged_in() {
            return Err("请先登录后再收藏".into());
        }
        let series_id = pstr(params, "seriesId");
        let add = params["add"].as_bool().unwrap_or(true);
        self.account.set_favorite(&series_id, add).await?;
        Ok(json!({ "ok": true }))
    }

    async fn act_reservations_list(&self, params: &Value) -> Result<Value, String> {
        if !self.user.logged_in() {
            return Ok(json!({ "items": [] }));
        }
        let online = params["online"].as_bool().unwrap_or(false);
        let items = self.account.reservations(online).await?;
        Ok(json!({ "items": items }))
    }

    async fn act_reservation_set(&self, params: &Value) -> Result<Value, String> {
        if !self.user.logged_in() {
            return Err("请先登录后再预约".into());
        }
        let series_id = pstr(params, "seriesId");
        let add = params["add"].as_bool().unwrap_or(true);
        self.account.set_reservation(&series_id, add).await?;
        Ok(json!({ "ok": true }))
    }

    async fn act_progress_report(&self, params: &Value) -> Result<Value, String> {
        let row = LocalProgressRow {
            series_id: pstr(params, "seriesId"),
            vid: pstr(params, "vid"),
            index: params["index"].as_i64().unwrap_or(1) as i32,
            pos: params["positionMs"].as_i64().unwrap_or(0) as i32,
            title: pstr(params, "title"),
            cover: pstr(params, "cover"),
            episode_count: params["episodeCount"].as_i64().unwrap_or(0) as i32,
            ts: crate::upstream::device::now_ms(),
            dirty: !self.user.logged_in(),
            deleted: false,
        };
        self.account.save_local_progress(row.clone());
        if !self.user.logged_in() {
            return Ok(json!({ "ok": true, "localOnly": true }));
        }
        match self
            .account
            .report_progress(
                &row.series_id,
                &row.vid,
                row.index,
                row.pos,
                &row.title,
                &row.cover,
                row.episode_count,
                true,
            )
            .await
        {
            Ok(()) => Ok(json!({ "ok": true })),
            Err(_) => Ok(json!({ "ok": true, "localOnly": true })),
        }
    }

    fn queue_danmaku_cache(&self, series_id: &str, vid: &str) {
        if self.is_player || vid.trim().is_empty() {
            return;
        }
        let _ = self
            .danmaku_tx
            .send((series_id.to_string(), vid.to_string()));
    }

    fn act_download_list(&self) -> Result<Value, String> {
        if !self.is_player {
            let ingested = self.downloader.ingest_inbox(self.dl_rt.handle());
            for t in &ingested {
                self.queue_danmaku_cache(&t.series_id, &t.vid);
            }
        }
        Ok(json!({ "items": self.downloader.list_slim() }))
    }

    fn act_download_summary(&self) -> Result<Value, String> {
        if !self.is_player {
            let ingested = self.downloader.ingest_inbox(self.dl_rt.handle());
            for t in &ingested {
                self.queue_danmaku_cache(&t.series_id, &t.vid);
            }
        }
        Ok(json!({ "active": self.downloader.active_count() }))
    }

    fn act_download_add(&self, params: &Value) -> Result<Value, String> {
        let req: AddDownloadRequest =
            serde_json::from_value(params.clone()).map_err(|e| format!("下载参数无效: {e}"))?;
        let task = self.downloader.add(req, self.dl_rt.handle())?;
        self.queue_danmaku_cache(&task.series_id, &task.vid);
        Ok(serde_json::to_value(task).unwrap())
    }

    fn act_download_add_batch(&self, params: &Value) -> Result<Value, String> {
        let raw_items = params
            .get("items")
            .cloned()
            .unwrap_or_else(|| json!([]));
        let reqs: Vec<AddDownloadRequest> =
            serde_json::from_value(raw_items).map_err(|e| format!("批量下载参数无效: {e}"))?;
        let tasks = self.downloader.add_batch(reqs, self.dl_rt.handle())?;
        for t in &tasks {
            self.queue_danmaku_cache(&t.series_id, &t.vid);
        }
        Ok(json!({
            "count": tasks.len(),
            "items": tasks,
        }))
    }

    fn act_download_delete_batch(&self, params: &Value) -> Result<Value, String> {
        let raw_ids = params.get("ids").cloned().unwrap_or_else(|| json!([]));
        let ids: Vec<String> =
            serde_json::from_value(raw_ids).map_err(|e| format!("批量删除参数无效: {e}"))?;
        let remove_file = params["removeFile"].as_bool().unwrap_or(false);
        let deleted = self
            .downloader
            .delete_batch(&ids, remove_file, self.dl_rt.handle())?;
        Ok(json!({
            "deleted": deleted,
            "items": self.downloader.list_slim(),
        }))
    }

    fn act_download_action(&self, params: &Value) -> Result<Value, String> {
        let req: DownloadActionRequest =
            serde_json::from_value(params.clone()).map_err(|e| format!("操作参数无效: {e}"))?;
        match req.action.as_str() {
            "pause" => self.downloader.pause(&req.id)?,
            "resume" | "retry" => self.downloader.resume(&req.id, self.dl_rt.handle())?,
            "cancel" => self.downloader.cancel(&req.id)?,
            "delete" => self.downloader.delete(&req.id, req.remove_file)?,
            "pause_all" => self.downloader.pause_all()?,
            "resume_all" => self.downloader.resume_all(self.dl_rt.handle())?,
            "clear_completed" => self.downloader.clear_completed()?,
            other => return Err(format!("不支持的下载操作: {other}")),
        }
        Ok(json!({ "items": self.downloader.list_slim() }))
    }

    fn act_download_settings_set(&self, params: &Value) -> Result<Value, String> {
        let next: DownloadSettings =
            serde_json::from_value(params.clone()).map_err(|e| format!("下载设置无效: {e}"))?;
        let saved = self.downloader.update_settings(next, self.dl_rt.handle())?;
        Ok(serde_json::to_value(saved).unwrap())
    }

    fn act_download_local(&self, params: &Value) -> Result<Value, String> {
        let vid = pstr(params, "vid");
        let path = self.downloader.get_local_path(&vid);
        Ok(json!({ "path": path }))
    }

    fn act_download_open(&self, params: &Value) -> Result<Value, String> {
        let id = pstr(params, "id");
        self.downloader.open_location(&id)?;
        Ok(json!({ "ok": true }))
    }
}

fn pstr(v: &Value, key: &str) -> String {
    v.get(key)
        .and_then(|x| x.as_str())
        .unwrap_or("")
        .trim()
        .to_string()
}
