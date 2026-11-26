use crate::upstream::device::{build_http_client, now_secs};
use reqwest::Client;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{HashMap, HashSet};
use std::ffi::{CStr, CString};
use std::os::raw::c_char;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, RwLock};
use std::time::Duration;

pub const APP_VERSION: &str = "2.1.0";
pub const GITHUB_RELEASES_API: &str =
    "https://api.github.com/repos/Amor5207/lvguo-tv/releases/latest";
pub const GITHUB_RELEASES_PAGE: &str = "https://github.com/Amor5207/lvguo-tv/releases/latest";
pub const DEFAULT_APP_UPDATE_URL: &str = "https://update.pengfei.ink/api/v1/desktop/check";
pub const DEFAULT_PLAYBACK_CONFIG_URL: &str = "https://update.pengfei.ink/api/v1/playback-config";
pub const DEFAULT_MODULE_MANIFEST_URL: &str = "https://update.pengfei.ink/api/v1/desktop-module";
pub const LEGACY_MOBILE_ENDPOINT: &str =
    "https://api5-normal-sinfonlinea.fqnovel.com/novel/player/multi_video_model/v1/";
pub const MINI_REEL_SINGLE_ENDPOINT: &str =
    "https://api5-normal-sinfonlinea.fqnovel.com/novel/player/video_model/v1/";

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PlaybackRouteConfig {
    pub endpoint: String,
    #[serde(default)]
    pub device_level: i32,
    #[serde(default)]
    pub video_platform: i32,
    #[serde(default)]
    pub need_all: Option<bool>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ApiEndpointConfig {
    pub method: String,
    pub host: String,
    #[serde(default)]
    pub backup_host: String,
    pub path: String,
    #[serde(default)]
    pub sign: String,
    #[serde(default)]
    pub query: HashMap<String, String>,
    #[serde(default)]
    pub body: Option<serde_json::Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PlaybackConfig {
    pub schema: i32,
    pub revision: i32,
    pub routes: Vec<String>,
    pub legacy: PlaybackRouteConfig,
    pub single: PlaybackRouteConfig,
    #[serde(default)]
    pub apis: HashMap<String, ApiEndpointConfig>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ModuleArtifact {
    pub url: String,
    pub sha256: String,
    #[serde(default)]
    pub size: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AppReleaseInfo {
    #[serde(default)]
    pub version: String,
    #[serde(default)]
    pub changelog: String,
    #[serde(default)]
    pub windows_x64: Option<ModuleArtifact>,
    #[serde(default)]
    pub macos_universal: Option<ModuleArtifact>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ModuleManifest {
    #[serde(default)]
    pub schema: i32,
    #[serde(default)]
    pub revision: i32,
    #[serde(default)]
    pub min_core_version: String,
    #[serde(default)]
    pub notes: String,
    #[serde(default)]
    pub windows_x86_64: Option<ModuleArtifact>,
    #[serde(default)]
    pub windows_aarch64: Option<ModuleArtifact>,
    #[serde(default)]
    pub macos_arm64: Option<ModuleArtifact>,
    #[serde(default)]
    pub macos_x86_64: Option<ModuleArtifact>,
    #[serde(default)]
    pub linux_x86_64: Option<ModuleArtifact>,
    #[serde(default)]
    pub linux_aarch64: Option<ModuleArtifact>,
    #[serde(default)]
    pub app: Option<AppReleaseInfo>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HotUpdateStatus {
    pub config_url: String,
    pub config_revision: i32,
    pub routes: Vec<String>,
    pub module_manifest_url: String,
    pub active_module_revision: i32,
    pub active_module_path: String,
    pub latest_module_revision: i32,
    pub latest_app_version: String,
    pub latest_app_url: String,
    pub latest_app_sha256: String,
    pub latest_app_changelog: String,
    pub last_check_at: i64,
    pub last_error: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AppUpdateCheckResult {
    pub ok: bool,
    pub has_update: bool,
    pub current_version: String,
    pub latest_version: String,
    pub notes: String,
    pub release_url: String,
    pub download_url: String,
    pub direct_download_url: String,
    pub asset_name: String,
    pub asset_size: u64,
}

pub fn default_api_configs() -> HashMap<String, ApiEndpointConfig> {
    let primary = "api5-normal-sinfonlinea.fqnovel.com".to_string();
    let backup = "api3-normal-sinfonlinea.fqnovel.com".to_string();
    let mut m = HashMap::new();
    m.insert(
        "detail".into(),
        ApiEndpointConfig {
            method: "POST".into(),
            host: primary.clone(),
            backup_host: backup.clone(),
            path: "/novel/player/video_detail/v1/".into(),
            sign: "v5".into(),
            query: HashMap::new(),
            body: Some(serde_json::json!({
                "biz_param": {
                    "caller_scene": "single_col", "detail_page_version": 0, "from_video_id": "",
                    "need_all_video_definition": false, "need_mp4_align": false, "source": 4, "video_id_type": 1
                }
            })),
        },
    );
    m.insert(
        "detail_batch".into(),
        ApiEndpointConfig {
            method: "POST".into(),
            host: primary.clone(),
            backup_host: backup.clone(),
            path: "/novel/player/multi_video_detail/player/v1".into(),
            sign: "v5".into(),
            query: HashMap::new(),
            body: Some(serde_json::json!({
                "dr_scene": "player",
                "biz_param": {
                    "detail_page_version": 0, "need_all_video_definition": false, "need_mp4_align": false,
                    "source": 7, "use_os_player": false, "use_server_dns": false
                }
            })),
        },
    );
    let mut rank_q = HashMap::new();
    rank_q.insert("tab_type".into(), "26".into());
    rank_q.insert("client_req_type".into(), "2".into());
    rank_q.insert("client_template".into(), "2".into());
    rank_q.insert("screen_width_px".into(), "1350".into());
    m.insert(
        "rank".into(),
        ApiEndpointConfig {
            method: "GET".into(),
            host: primary.clone(),
            backup_host: backup.clone(),
            path: "/reading/bookapi/bookmall/cell/change/v1/".into(),
            sign: "v5".into(),
            query: rank_q,
            body: None,
        },
    );
    m.insert(
        "album_videos".into(),
        ApiEndpointConfig {
            method: "GET".into(),
            host: primary.clone(),
            backup_host: backup.clone(),
            path: "/api/novel/origin/album/get_video_list/v1/".into(),
            sign: "v5".into(),
            query: HashMap::new(),
            body: None,
        },
    );
    m.insert(
        "comments_book".into(),
        ApiEndpointConfig {
            method: "GET".into(),
            host: primary.clone(),
            backup_host: backup.clone(),
            path: "/reading/ugc/novel_comment/book/v1/".into(),
            sign: "v5".into(),
            query: HashMap::new(),
            body: None,
        },
    );
    m.insert(
        "comments_item".into(),
        ApiEndpointConfig {
            method: "GET".into(),
            host: primary,
            backup_host: backup,
            path: "/reading/ugc/novel_comment/item/v1/".into(),
            sign: "v5".into(),
            query: HashMap::new(),
            body: None,
        },
    );
    m
}

pub fn default_playback_config() -> PlaybackConfig {
    PlaybackConfig {
        schema: 1,
        revision: 0,
        routes: vec!["dynamic".into(), "videotab".into(), "legacy".into()],
        legacy: PlaybackRouteConfig {
            endpoint: LEGACY_MOBILE_ENDPOINT.into(),
            device_level: 1,
            video_platform: 1024,
            need_all: Some(true),
        },
        single: PlaybackRouteConfig {
            endpoint: MINI_REEL_SINGLE_ENDPOINT.into(),
            device_level: 0,
            video_platform: 3,
            need_all: Some(true),
        },
        apis: default_api_configs(),
    }
}

fn valid_api_host(host: &str) -> bool {
    let h = host.trim().to_ascii_lowercase();
    if h.is_empty()
        || h.chars()
            .any(|c| matches!(c, '/' | ':' | '?' | '#' | '@' | '\\' | ' ' | '\t' | '\r' | '\n'))
    {
        return false;
    }
    (h.ends_with(".fqnovel.com") && h != ".fqnovel.com")
        || (h.ends_with(".snssdk.com") && h != ".snssdk.com")
}

fn valid_api_path(p: &str) -> bool {
    p.len() >= 2
        && p.len() <= 160
        && p.starts_with('/')
        && !p.contains("..")
        && !p
            .chars()
            .any(|c| matches!(c, '?' | '#' | '\\' | ' ' | '\t' | '\r' | '\n'))
}

fn valid_playback_endpoint(raw: &str, expected_path: &str) -> bool {
    let Ok(u) = url::Url::parse(raw) else {
        return false;
    };
    if u.scheme() != "https" || !u.username().is_empty() || u.query().is_some() || u.fragment().is_some() || u.port().is_some() {
        return false;
    }
    let path = u.path();
    if path != expected_path
        && !(expected_path == "/novel/player/multi_video_model/player/v1/"
            && path == "/novel/player/multi_video_model/v1/")
    {
        return false;
    }
    let Some(host) = u.host_str() else {
        return false;
    };
    let h = host.to_ascii_lowercase();
    h.ends_with(".fqnovel.com") && h != ".fqnovel.com"
}

impl ApiEndpointConfig {
    pub fn validate(&self) -> Result<(), String> {
        let m = self.method.trim().to_ascii_uppercase();
        if m != "GET" && m != "POST" {
            return Err("不支持的接口方法".into());
        }
        if !valid_api_host(&self.host)
            || (!self.backup_host.is_empty() && !valid_api_host(&self.backup_host))
        {
            return Err("不支持的接口域名".into());
        }
        if !valid_api_path(&self.path) {
            return Err("不支持的接口路径".into());
        }
        let s = self.sign.trim().to_ascii_lowercase();
        if !s.is_empty() && s != "v5" && s != "minireel" && s != "none" {
            return Err("不支持的接口签名策略".into());
        }
        Ok(())
    }
}

impl PlaybackConfig {
    pub fn validate(&self) -> Result<(), String> {
        if self.schema != 1
            || self.revision < 0
            || self.routes.is_empty()
            || self.routes.len() > 4
        {
            return Err("无效的播放配置版本或路线".into());
        }
        let mut seen = HashSet::new();
        for r in &self.routes {
            if !matches!(r.as_str(), "dynamic" | "videotab" | "legacy" | "single") || !seen.insert(r)
            {
                return Err("不支持的播放路线".into());
            }
        }
        if !valid_playback_endpoint(
            &self.legacy.endpoint,
            "/novel/player/multi_video_model/player/v1/",
        ) || !valid_playback_endpoint(&self.single.endpoint, "/novel/player/video_model/v1/")
        {
            return Err("不支持的播放接口地址".into());
        }
        if !(0..=5).contains(&self.legacy.device_level)
            || !(1..=10000).contains(&self.legacy.video_platform)
            || self.legacy.need_all.is_none()
        {
            return Err("legacy 播放参数超出允许范围".into());
        }
        if !(1..=10000).contains(&self.single.video_platform) || self.single.need_all.is_none() {
            return Err("单集播放参数超出允许范围".into());
        }
        for api in self.apis.values() {
            api.validate()?;
        }
        Ok(())
    }
}

type FnApiVersion = unsafe extern "C" fn() -> u32;
type FnModuleInvoke = unsafe extern "C" fn(*const c_char) -> *mut c_char;
type FnFreeString = unsafe extern "C" fn(*mut c_char);

pub struct LoadedAlgoModule {
    _lib: libloading::Library,
    pub revision: i32,
    pub path: String,
    sign_fn: Option<FnModuleInvoke>,
    resolve_play_fn: Option<FnModuleInvoke>,
    free_fn: FnFreeString,
}

unsafe impl Send for LoadedAlgoModule {}
unsafe impl Sync for LoadedAlgoModule {}

impl LoadedAlgoModule {
    pub fn load(path: &Path, revision: i32) -> Result<Self, String> {
        unsafe {
            let lib =
                libloading::Library::new(path).map_err(|e| format!("加载算法模块失败: {e}"))?;
            let api_ver: libloading::Symbol<FnApiVersion> = lib
                .get(b"lvguo_module_api_version\0")
                .map_err(|e| format!("算法模块缺少版本导出: {e}"))?;
            let ver = api_ver();
            if ver != 1 {
                return Err(format!("不支持的算法模块 ABI 版本: {ver}"));
            }
            let free_sym: libloading::Symbol<FnFreeString> = lib
                .get(b"lvguo_module_free_string\0")
                .map_err(|e| format!("算法模块缺少释放函数: {e}"))?;
            let free_fn = *free_sym;
            let sign_fn = lib
                .get::<FnModuleInvoke>(b"lvguo_module_sign\0")
                .ok()
                .map(|s| *s);
            let resolve_play_fn = lib
                .get::<FnModuleInvoke>(b"lvguo_module_resolve_play\0")
                .ok()
                .map(|s| *s);
            Ok(Self {
                _lib: lib,
                revision,
                path: path.to_string_lossy().to_string(),
                sign_fn,
                resolve_play_fn,
                free_fn,
            })
        }
    }

    pub fn invoke_sign(&self, req_json: &str) -> Option<String> {
        let f = self.sign_fn?;
        self.call_json(f, req_json)
    }

    pub fn invoke_resolve_play(&self, req_json: &str) -> Option<String> {
        let f = self.resolve_play_fn?;
        self.call_json(f, req_json)
    }

    fn call_json(&self, f: FnModuleInvoke, input: &str) -> Option<String> {
        let c_in = CString::new(input).ok()?;
        unsafe {
            let ptr = f(c_in.as_ptr());
            if ptr.is_null() {
                return None;
            }
            let out = CStr::from_ptr(ptr).to_string_lossy().into_owned();
            (self.free_fn)(ptr);
            Some(out)
        }
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct HotUpdateMeta {
    #[serde(default)]
    config_url: String,
    #[serde(default)]
    module_manifest_url: String,
    #[serde(default)]
    active_module_revision: i32,
    #[serde(default)]
    active_module_path: String,
}

pub struct HotUpdateManager {
    data_dir: PathBuf,
    http: Client,
    config: RwLock<PlaybackConfig>,
    meta: Mutex<HotUpdateMeta>,
    active_module: RwLock<Option<Arc<LoadedAlgoModule>>>,
    last_manifest: Mutex<Option<ModuleManifest>>,
    last_check_at: Mutex<i64>,
    last_error: Mutex<String>,
}

impl HotUpdateManager {
    pub fn new(data_dir: &Path) -> Arc<Self> {
        let http = build_http_client(8);
        let cfg_path = Self::config_path(data_dir);
        let mut current = default_playback_config();
        if let Ok(b) = std::fs::read(&cfg_path) {
            if b.len() <= 16384 {
                if let Ok(c) = serde_json::from_slice::<PlaybackConfig>(&b) {
                    if c.validate().is_ok() {
                        current = c;
                    }
                }
            }
        }
        let mut meta: HotUpdateMeta = std::fs::read_to_string(Self::meta_path(data_dir))
            .ok()
            .and_then(|s| serde_json::from_str(&s).ok())
            .unwrap_or_default();
        if meta.config_url.trim().is_empty() {
            meta.config_url = DEFAULT_PLAYBACK_CONFIG_URL.to_string();
        }
        if meta.module_manifest_url.trim().is_empty() {
            meta.module_manifest_url = DEFAULT_MODULE_MANIFEST_URL.to_string();
        }

        let mut loaded_mod = None;
        if !meta.active_module_path.is_empty() && Path::new(&meta.active_module_path).is_file() {
            if let Ok(m) =
                LoadedAlgoModule::load(Path::new(&meta.active_module_path), meta.active_module_revision)
            {
                loaded_mod = Some(Arc::new(m));
            } else {
                meta.active_module_revision = 0;
                meta.active_module_path.clear();
            }
        }

        Arc::new(Self {
            data_dir: data_dir.to_path_buf(),
            http,
            config: RwLock::new(current),
            meta: Mutex::new(meta),
            active_module: RwLock::new(loaded_mod),
            last_manifest: Mutex::new(None),
            last_check_at: Mutex::new(0),
            last_error: Mutex::new(String::new()),
        })
    }

    pub fn start_background(self: &Arc<Self>, rt: &tokio::runtime::Handle) {
        let mgr = self.clone();
        rt.spawn(async move {
            let _ = mgr.check_updates(false).await;
            let mut interval = tokio::time::interval(Duration::from_secs(300));
            interval.tick().await;
            loop {
                interval.tick().await;
                let _ = mgr.check_updates(false).await;
            }
        });
    }

    fn config_path(dir: &Path) -> PathBuf {
        dir.join("playback-config-v1.json")
    }

    fn meta_path(dir: &Path) -> PathBuf {
        dir.join("hotupdate_meta.json")
    }

    pub fn snapshot(&self) -> PlaybackConfig {
        self.config.read().unwrap().clone()
    }

    pub fn routes(&self) -> Vec<String> {
        self.config.read().unwrap().routes.clone()
    }

    pub fn api_config(&self, name: &str) -> Option<ApiEndpointConfig> {
        let cfg = self.config.read().unwrap();
        if let Some(a) = cfg.apis.get(name) {
            if a.validate().is_ok() {
                return Some(a.clone());
            }
        }
        default_api_configs().remove(name)
    }

    pub fn active_algo(&self) -> Option<Arc<LoadedAlgoModule>> {
        self.active_module.read().unwrap().clone()
    }

    pub fn set_urls(&self, config_url: Option<&str>, manifest_url: Option<&str>) {
        let mut meta = self.meta.lock().unwrap();
        if let Some(u) = config_url {
            let t = u.trim();
            meta.config_url = if t.is_empty() {
                DEFAULT_PLAYBACK_CONFIG_URL.into()
            } else {
                t.into()
            };
        }
        if let Some(u) = manifest_url {
            let t = u.trim();
            meta.module_manifest_url = if t.is_empty() {
                DEFAULT_MODULE_MANIFEST_URL.into()
            } else {
                t.into()
            };
        }
        self.save_meta_locked(&meta);
    }

    pub fn rollback_module(&self) -> HotUpdateStatus {
        *self.active_module.write().unwrap() = None;
        {
            let mut meta = self.meta.lock().unwrap();
            meta.active_module_revision = 0;
            meta.active_module_path.clear();
            self.save_meta_locked(&meta);
        }
        *self.last_error.lock().unwrap() = String::new();
        self.status()
    }

    pub fn status(&self) -> HotUpdateStatus {
        let cfg = self.config.read().unwrap();
        let meta = self.meta.lock().unwrap();
        let manifest = self.last_manifest.lock().unwrap().clone();
        let last_check_at = *self.last_check_at.lock().unwrap();
        let last_error = self.last_error.lock().unwrap().clone();

        let (latest_rev, app_ver, app_url, app_sha, app_notes) = if let Some(m) = manifest {
            let (av, au, asha, ach) = if let Some(app) = m.app {
                let art = if cfg!(target_os = "windows") {
                    app.windows_x64
                } else {
                    app.macos_universal
                };
                (
                    app.version,
                    art.as_ref().map(|a| a.url.clone()).unwrap_or_default(),
                    art.as_ref().map(|a| a.sha256.clone()).unwrap_or_default(),
                    app.changelog,
                )
            } else {
                (String::new(), String::new(), String::new(), String::new())
            };
            (m.revision, av, au, asha, ach)
        } else {
            (0, String::new(), String::new(), String::new(), String::new())
        };

        HotUpdateStatus {
            config_url: meta.config_url.clone(),
            config_revision: cfg.revision,
            routes: cfg.routes.clone(),
            module_manifest_url: meta.module_manifest_url.clone(),
            active_module_revision: meta.active_module_revision,
            active_module_path: meta.active_module_path.clone(),
            latest_module_revision: latest_rev,
            latest_app_version: app_ver,
            latest_app_url: app_url,
            latest_app_sha256: app_sha,
            latest_app_changelog: app_notes,
            last_check_at,
            last_error,
        }
    }

    pub async fn check_updates(&self, include_module: bool) -> Result<HotUpdateStatus, String> {
        let (cfg_url, mod_url) = {
            let m = self.meta.lock().unwrap();
            (m.config_url.clone(), m.module_manifest_url.clone())
        };
        let mut errs = Vec::new();
        if let Err(e) = self.refresh_playback_config(&cfg_url).await {
            errs.push(e);
        }
        if include_module || !mod_url.is_empty() {
            if let Err(e) = self.refresh_module_manifest(&mod_url, include_module).await {
                errs.push(e);
            }
        }
        *self.last_check_at.lock().unwrap() = now_secs();
        let err_str = errs.join("; ");
        *self.last_error.lock().unwrap() = err_str.clone();
        if errs.len() == 2 && include_module {
            return Err(err_str);
        }
        Ok(self.status())
    }

    pub async fn refresh_playback_config(&self, endpoint: &str) -> Result<(), String> {
        if endpoint.trim().is_empty() {
            return Ok(());
        }
        let resp = self
            .http
            .get(endpoint)
            .header("Cache-Control", "no-cache")
            .header("Accept", "application/json")
            .send()
            .await
            .map_err(|e| format!("配置拉取失败: {e}"))?;
        if !resp.status().is_success() {
            return Err(format!("配置服务返回 HTTP {}", resp.status().as_u16()));
        }
        let bytes = resp
            .bytes()
            .await
            .map_err(|e| format!("读取配置失败: {e}"))?;
        if bytes.len() > 16384 {
            return Err("播放配置过大".into());
        }
        let next: PlaybackConfig =
            serde_json::from_slice(&bytes).map_err(|e| format!("配置格式错误: {e}"))?;
        next.validate()?;

        let mut cur = self.config.write().unwrap();
        if next.revision < cur.revision {
            return Err("忽略过期配置".into());
        }
        let path = Self::config_path(&self.data_dir);
        if let Some(p) = path.parent() {
            let _ = std::fs::create_dir_all(p);
        }
        let tmp = PathBuf::from(format!("{}.tmp", path.to_string_lossy()));
        std::fs::write(&tmp, &bytes).map_err(|e| e.to_string())?;
        std::fs::rename(&tmp, &path).map_err(|e| e.to_string())?;
        *cur = next;
        Ok(())
    }

    pub async fn check_app_update(&self) -> AppUpdateCheckResult {
        // Silently sync playback config & algorithm module in the background; never block app check.
        let _ = self.check_updates(true).await;

        let mut out = AppUpdateCheckResult {
            ok: false,
            has_update: false,
            current_version: APP_VERSION.to_string(),
            latest_version: String::new(),
            notes: String::new(),
            release_url: GITHUB_RELEASES_PAGE.to_string(),
            download_url: String::new(),
            direct_download_url: String::new(),
            asset_name: String::new(),
            asset_size: 0,
        };

        // Prefer our update server (proxies GitHub Releases without storing installers on disk).
        let server_url = format!(
            "{}?version={}&os={}&arch={}",
            DEFAULT_APP_UPDATE_URL,
            APP_VERSION,
            std::env::consts::OS,
            std::env::consts::ARCH
        );
        if let Ok(resp) = self
            .http
            .get(&server_url)
            .timeout(Duration::from_secs(8))
            .header("User-Agent", format!("LvguoPC/{APP_VERSION}"))
            .header("Accept", "application/json")
            .send()
            .await
        {
            if resp.status().is_success() {
                if let Ok(val) = resp.json::<serde_json::Value>().await {
                    if val.get("ok").and_then(|v| v.as_bool()) == Some(true) {
                        let latest = val
                            .get("latestVersion")
                            .and_then(|v| v.as_str())
                            .unwrap_or("")
                            .trim()
                            .trim_start_matches(|c| c == 'v' || c == 'V')
                            .trim()
                            .to_string();
                        out.ok = true;
                        out.latest_version = latest.clone();
                        out.notes = val
                            .get("notes")
                            .and_then(|v| v.as_str())
                            .unwrap_or("")
                            .trim()
                            .to_string();
                        out.release_url = val
                            .get("releaseUrl")
                            .and_then(|v| v.as_str())
                            .map(|s| s.trim())
                            .filter(|s| !s.is_empty())
                            .unwrap_or(GITHUB_RELEASES_PAGE)
                            .to_string();
                        out.has_update =
                            !latest.is_empty() && is_newer_version(&latest, APP_VERSION);
                        if out.has_update {
                            out.download_url = val
                                .get("downloadUrl")
                                .and_then(|v| v.as_str())
                                .unwrap_or("")
                                .trim()
                                .to_string();
                            out.direct_download_url = val
                                .get("directDownloadUrl")
                                .and_then(|v| v.as_str())
                                .unwrap_or("")
                                .trim()
                                .to_string();
                            out.asset_name = val
                                .get("assetName")
                                .and_then(|v| v.as_str())
                                .unwrap_or("")
                                .trim()
                                .to_string();
                            out.asset_size =
                                val.get("assetSize").and_then(|v| v.as_u64()).unwrap_or(0);
                            if out.download_url.is_empty() {
                                out.download_url = out.direct_download_url.clone();
                            }
                        }
                        return out;
                    }
                }
            }
        }

        // Fallback directly to GitHub Releases API if the update proxy is unreachable.
        let resp = match self
            .http
            .get(GITHUB_RELEASES_API)
            .timeout(Duration::from_secs(8))
            .header("User-Agent", format!("LvguoPC/{APP_VERSION}"))
            .header("Accept", "application/vnd.github+json")
            .send()
            .await
        {
            Ok(r) => r,
            Err(_) => return out,
        };

        if !resp.status().is_success() {
            return out;
        }

        let val: serde_json::Value = match resp.json().await {
            Ok(v) => v,
            Err(_) => return out,
        };

        let tag = val
            .get("tag_name")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .trim();
        let latest = tag.trim_start_matches(|c| c == 'v' || c == 'V').trim();
        let notes = val
            .get("body")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .trim()
            .to_string();
        let html_url = val
            .get("html_url")
            .and_then(|v| v.as_str())
            .map(|s| s.trim())
            .filter(|s| !s.is_empty())
            .unwrap_or(GITHUB_RELEASES_PAGE)
            .to_string();

        out.ok = true;
        out.latest_version = latest.to_string();
        out.notes = notes;
        out.release_url = html_url;
        out.has_update = !latest.is_empty() && is_newer_version(latest, APP_VERSION);

        if out.has_update {
            if let Some(assets) = val.get("assets").and_then(|v| v.as_array()) {
                if let Some((dl_url, name, size)) = select_github_asset(assets) {
                    out.download_url = dl_url.clone();
                    out.direct_download_url = dl_url;
                    out.asset_name = name;
                    out.asset_size = size;
                }
            }
        }

        out
    }

    pub async fn refresh_module_manifest(
        &self,
        manifest_url: &str,
        download_binary: bool,
    ) -> Result<(), String> {
        if manifest_url.trim().is_empty() {
            return Ok(());
        }
        let resp = self
            .http
            .get(manifest_url)
            .header("Cache-Control", "no-cache")
            .header("Accept", "application/json")
            .send()
            .await
            .map_err(|e| format!("模块清单拉取失败: {e}"))?;
        if !resp.status().is_success() {
            return Err(format!("模块清单返回 HTTP {}", resp.status().as_u16()));
        }
        let manifest: ModuleManifest = resp
            .json()
            .await
            .map_err(|e| format!("模块清单解析失败: {e}"))?;
        *self.last_manifest.lock().unwrap() = Some(manifest.clone());

        let cur_rev = self.meta.lock().unwrap().active_module_revision;
        if !download_binary && manifest.revision <= cur_rev {
            return Ok(());
        }
        let artifact = select_platform_artifact(&manifest);
        if let Some(art) = artifact {
            if manifest.revision > cur_rev && !art.url.is_empty() && !art.sha256.is_empty() {
                self.download_and_activate_module(manifest.revision, art)
                    .await?;
            }
        }
        Ok(())
    }

    async fn download_and_activate_module(
        &self,
        revision: i32,
        art: &ModuleArtifact,
    ) -> Result<(), String> {
        let resp = self
            .http
            .get(&art.url)
            .send()
            .await
            .map_err(|e| format!("下载算法模块失败: {e}"))?;
        if !resp.status().is_success() {
            return Err(format!("下载算法模块返回 HTTP {}", resp.status().as_u16()));
        }
        let bytes = resp
            .bytes()
            .await
            .map_err(|e| format!("读取算法模块失败: {e}"))?;
        if !verify_sha256(&bytes, &art.sha256) {
            return Err("算法模块 SHA256 校验失败".into());
        }

        let mod_dir = self.data_dir.join("modules").join(format!("v{revision}"));
        std::fs::create_dir_all(&mod_dir).map_err(|e| e.to_string())?;
        let filename = if cfg!(target_os = "windows") {
            "lvguo_algo.dll"
        } else if cfg!(target_os = "macos") {
            "liblvguo_algo.dylib"
        } else {
            "liblvguo_algo.so"
        };
        let target_path = mod_dir.join(filename);
        let tmp = mod_dir.join(format!("{filename}.tmp"));
        std::fs::write(&tmp, &bytes).map_err(|e| e.to_string())?;
        std::fs::rename(&tmp, &target_path).map_err(|e| e.to_string())?;

        let loaded = Arc::new(LoadedAlgoModule::load(&target_path, revision)?);
        *self.active_module.write().unwrap() = Some(loaded);
        let mut meta = self.meta.lock().unwrap();
        meta.active_module_revision = revision;
        meta.active_module_path = target_path.to_string_lossy().to_string();
        self.save_meta_locked(&meta);
        Ok(())
    }

    fn save_meta_locked(&self, meta: &HotUpdateMeta) {
        let p = Self::meta_path(&self.data_dir);
        if let Some(parent) = p.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        if let Ok(json) = serde_json::to_string_pretty(meta) {
            let tmp = PathBuf::from(format!("{}.tmp", p.to_string_lossy()));
            if std::fs::write(&tmp, json).is_ok() {
                let _ = std::fs::rename(&tmp, &p);
            }
        }
    }
}

fn select_platform_artifact(m: &ModuleManifest) -> Option<&ModuleArtifact> {
    #[cfg(all(target_os = "windows", target_arch = "x86_64"))]
    {
        return m.windows_x86_64.as_ref();
    }
    #[cfg(all(target_os = "windows", target_arch = "aarch64"))]
    {
        return m.windows_aarch64.as_ref();
    }
    #[cfg(all(target_os = "macos", target_arch = "aarch64"))]
    {
        return m.macos_arm64.as_ref();
    }
    #[cfg(all(target_os = "macos", target_arch = "x86_64"))]
    {
        return m.macos_x86_64.as_ref();
    }
    #[cfg(all(target_os = "linux", target_arch = "x86_64"))]
    {
        return m.linux_x86_64.as_ref();
    }
    #[cfg(all(target_os = "linux", target_arch = "aarch64"))]
    {
        return m.linux_aarch64.as_ref();
    }
    #[allow(unreachable_code)]
    None
}

pub fn verify_sha256(bytes: &[u8], expected_hex: &str) -> bool {
    let actual = hex::encode(Sha256::digest(bytes));
    actual.eq_ignore_ascii_case(expected_hex.trim())
}

fn parse_version_parts(raw: &str) -> Option<Vec<u64>> {
    let clean = raw
        .trim()
        .trim_start_matches(|c| c == 'v' || c == 'V')
        .split(['-', '+'])
        .next()
        .unwrap_or("")
        .trim();
    if clean.is_empty() {
        return None;
    }
    let mut parts = Vec::new();
    for seg in clean.split('.') {
        let num: u64 = seg.parse().ok()?;
        parts.push(num);
    }
    Some(parts)
}

pub fn is_newer_version(candidate: &str, current: &str) -> bool {
    let Some(next) = parse_version_parts(candidate) else {
        return false;
    };
    let Some(cur) = parse_version_parts(current) else {
        return false;
    };
    let len = next.len().max(cur.len());
    for i in 0..len {
        let n = *next.get(i).unwrap_or(&0);
        let c = *cur.get(i).unwrap_or(&0);
        if n != c {
            return n > c;
        }
    }
    false
}

pub fn select_github_asset_for_platform(
    assets: &[serde_json::Value],
    os: &str,
    arch: &str,
) -> Option<(String, String, u64)> {
    let candidates: Vec<(String, String, String, u64)> = assets
        .iter()
        .filter_map(|a| {
            let url = a
                .get("browser_download_url")
                .and_then(|v| v.as_str())
                .map(|s| s.trim())
                .filter(|s| !s.is_empty())?
                .to_string();
            let name = a
                .get("name")
                .and_then(|v| v.as_str())
                .map(|s| s.trim())
                .filter(|s| !s.is_empty())?
                .to_string();
            let size = a.get("size").and_then(|v| v.as_u64()).unwrap_or(0);
            Some((url, name.clone(), name.to_ascii_lowercase(), size))
        })
        .collect();

    let pick = |predicate: &dyn Fn(&str) -> bool| -> Option<(String, String, u64)> {
        candidates
            .iter()
            .find(|(_, _, lower, _)| predicate(lower))
            .map(|(u, n, _, s)| (u.clone(), n.clone(), *s))
    };

    match (os, arch) {
        ("windows", "x86_64") => pick(&|n| n.ends_with(".exe") && (n.contains("win-x64") || n.contains("x64")))
            .or_else(|| pick(&|n| n.ends_with(".exe") && (n.contains("setup") || n.contains("lvguopc")))),
        ("windows", "aarch64") => {
            pick(&|n| n.ends_with(".exe") && (n.contains("win-arm64") || n.contains("arm64")))
        }
        ("macos", "aarch64") => pick(&|n| n.ends_with(".dmg") && (n.contains("arm64") || n.contains("universal")))
            .or_else(|| pick(&|n| n.ends_with(".dmg"))),
        ("macos", "x86_64") => pick(&|n| n.ends_with(".dmg") && (n.contains("x64") || n.contains("universal")))
            .or_else(|| pick(&|n| n.ends_with(".dmg"))),
        ("linux", "x86_64") => pick(&|n| n.ends_with(".deb") && (n.contains("x64") || n.contains("amd64")))
            .or_else(|| pick(&|n| n.ends_with(".deb"))),
        ("linux", "aarch64") => pick(&|n| n.ends_with(".deb") && n.contains("arm64"))
            .or_else(|| pick(&|n| n.ends_with(".deb"))),
        _ => None,
    }
}

pub fn select_github_asset(assets: &[serde_json::Value]) -> Option<(String, String, u64)> {
    select_github_asset_for_platform(assets, std::env::consts::OS, std::env::consts::ARCH)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_playback_config_validation() {
        let cfg = default_playback_config();
        assert!(cfg.validate().is_ok());

        let mut bad = cfg.clone();
        bad.legacy.endpoint = "https://evil.example.com/novel/player/multi_video_model/player/v1/".into();
        assert!(bad.validate().is_err());

        let mut bad_api = cfg;
        bad_api.apis.get_mut("detail").unwrap().host = "evil.example.com".into();
        assert!(bad_api.validate().is_err());
    }

    #[test]
    fn test_sha256_verification() {
        let data = b"lvguo-algo-payload";
        let digest = hex::encode(Sha256::digest(data));
        assert!(verify_sha256(data, &digest));
        assert!(verify_sha256(data, &digest.to_uppercase()));
        assert!(!verify_sha256(data, "0000"));
    }

    #[test]
    fn test_is_newer_version() {
        assert!(is_newer_version("v2.0.12", "2.0.11"));
        assert!(is_newer_version("2.1.0", "2.0.11"));
        assert!(is_newer_version("3.0", "2.9.9"));
        assert!(!is_newer_version("2.0.11", "2.0.11"));
        assert!(!is_newer_version("v2.0.10", "2.0.11"));
        assert!(!is_newer_version("2.0.11-beta1", "2.0.11"));
        assert!(!is_newer_version("", "2.0.11"));
    }

    #[test]
    fn test_select_github_asset() {
        let apk_only = vec![serde_json::json!({
            "name": "lvguo-tv-2.0.12.apk",
            "browser_download_url": "https://example.com/lvguo-tv-2.0.12.apk",
            "size": 123456
        })];
        assert!(select_github_asset_for_platform(&apk_only, "windows", "x86_64").is_none());

        let desktop_assets = vec![
            serde_json::json!({
                "name": "lvguo-tv-2.0.12.apk",
                "browser_download_url": "https://example.com/app.apk",
                "size": 100
            }),
            serde_json::json!({
                "name": "LvguoPC-Setup-2.0.12-win-x64.exe",
                "browser_download_url": "https://example.com/LvguoPC-Setup-2.0.12-win-x64.exe",
                "size": 26000000
            }),
            serde_json::json!({
                "name": "LvguoPC-2.0.12-macos-arm64.dmg",
                "browser_download_url": "https://example.com/LvguoPC-2.0.12-macos-arm64.dmg",
                "size": 30000000
            }),
            serde_json::json!({
                "name": "lvguo-pc-2.0.12-linux-amd64.deb",
                "browser_download_url": "https://example.com/lvguo-pc-2.0.12-linux-amd64.deb",
                "size": 28000000
            }),
        ];
        let win = select_github_asset_for_platform(&desktop_assets, "windows", "x86_64").unwrap();
        assert_eq!(win.1, "LvguoPC-Setup-2.0.12-win-x64.exe");
        let mac = select_github_asset_for_platform(&desktop_assets, "macos", "aarch64").unwrap();
        assert_eq!(mac.1, "LvguoPC-2.0.12-macos-arm64.dmg");
        let linux = select_github_asset_for_platform(&desktop_assets, "linux", "x86_64").unwrap();
        assert_eq!(linux.1, "lvguo-pc-2.0.12-linux-amd64.deb");
    }
}
