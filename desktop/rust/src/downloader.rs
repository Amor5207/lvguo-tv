use crate::cenc::{decrypt_mp4, spade_to_key};
use crate::limiter::RateLimiter;
use crate::models::{
    AddDownloadRequest, ChunkState, DownloadSettings, DownloadTask, TaskStatus,
};
use crate::upstream::content::{pick_gear, Content};
use chrono::Utc;
use futures_util::StreamExt;
use reqwest::header::{ACCEPT_RANGES, CONTENT_LENGTH, CONTENT_RANGE, ETAG, RANGE};
use reqwest::Client;
use std::collections::HashMap;
use std::fs::{File, OpenOptions};
use std::future::Future;
use std::path::{Path, PathBuf};
use std::pin::Pin;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tokio::sync::watch;
use uuid::Uuid;

#[derive(Debug, Clone)]
pub struct ResolvedSource {
    pub urls: Vec<String>,
    pub definition: String,
    pub encrypted: bool,
    pub spade_a: String,
}

impl ResolvedSource {
    pub fn primary_url(&self) -> &str {
        self.urls.first().map(|s| s.as_str()).unwrap_or("")
    }
}

pub trait SourceResolver: Send + Sync {
    fn resolve<'a>(
        &'a self,
        vid: &'a str,
        quality: &'a str,
        fresh: bool,
    ) -> Pin<Box<dyn Future<Output = Result<ResolvedSource, String>> + Send + 'a>>;
}

pub struct UpstreamSourceResolver {
    content: Arc<Content>,
}

impl UpstreamSourceResolver {
    pub fn new(content: Arc<Content>) -> Self {
        Self { content }
    }
}

impl SourceResolver for UpstreamSourceResolver {
    fn resolve<'a>(
        &'a self,
        vid: &'a str,
        quality: &'a str,
        fresh: bool,
    ) -> Pin<Box<dyn Future<Output = Result<ResolvedSource, String>> + Send + 'a>> {
        Box::pin(async move {
            let gears = self.content.play(vid, fresh).await?;
            let gear = pick_gear(&gears, quality, 1080)
                .ok_or_else(|| "暂无可下载清晰度".to_string())?;
            let mut urls = Vec::new();
            if !gear.main_url.trim().is_empty() {
                urls.push(gear.main_url.clone());
            }
            if !gear.backup_url.trim().is_empty() && !urls.contains(&gear.backup_url) {
                urls.push(gear.backup_url.clone());
            }
            for u in &gear.backup_urls {
                if !u.trim().is_empty() && !urls.contains(u) {
                    urls.push(u.clone());
                }
            }
            if urls.is_empty() {
                return Err("下载地址为空".to_string());
            }
            Ok(ResolvedSource {
                urls,
                definition: gear.definition.clone(),
                encrypted: gear.encrypted,
                spade_a: gear.spade_a.clone(),
            })
        })
    }
}

#[derive(Clone)]
struct ActiveRuntime {
    cancel_tx: watch::Sender<bool>,
    pause_requested: Arc<std::sync::atomic::AtomicBool>,
    delete_requested: Arc<std::sync::atomic::AtomicBool>,
    delete_file: Arc<std::sync::atomic::AtomicBool>,
    last_bytes: u64,
    last_tick: Instant,
}

struct Inner {
    data_dir: PathBuf,
    settings: DownloadSettings,
    tasks: HashMap<String, DownloadTask>,
    order: Vec<String>,
    active: HashMap<String, ActiveRuntime>,
    closed: bool,
    worker_enabled: bool,
    last_tasks_mtime: Option<std::time::SystemTime>,
}

pub struct DownloadManager {
    inner: Arc<Mutex<Inner>>,
    limiter: Arc<RateLimiter>,
    client: Client,
    resolver: Arc<dyn SourceResolver>,
    stop_tx: watch::Sender<bool>,
}

impl DownloadManager {
    pub fn new(
        data_dir: PathBuf,
        content: Arc<Content>,
        rt: &tokio::runtime::Handle,
    ) -> Arc<Self> {
        Self::new_with_mode(data_dir, content, rt, true)
    }

    pub fn new_with_mode(
        data_dir: PathBuf,
        content: Arc<Content>,
        rt: &tokio::runtime::Handle,
        worker_enabled: bool,
    ) -> Arc<Self> {
        let resolver = Arc::new(UpstreamSourceResolver::new(content));
        Self::with_resolver_mode(data_dir, resolver, rt, worker_enabled)
    }

    pub fn with_resolver(
        data_dir: PathBuf,
        resolver: Arc<dyn SourceResolver>,
        rt: &tokio::runtime::Handle,
    ) -> Arc<Self> {
        Self::with_resolver_mode(data_dir, resolver, rt, true)
    }

    pub fn with_resolver_mode(
        data_dir: PathBuf,
        resolver: Arc<dyn SourceResolver>,
        rt: &tokio::runtime::Handle,
        worker_enabled: bool,
    ) -> Arc<Self> {
        let _ = std::fs::create_dir_all(&data_dir);
        let mut settings = Self::load_settings(&data_dir);
        settings.normalize(&data_dir);

        let (tasks, order) = Self::load_tasks(
            &data_dir,
            worker_enabled && settings.auto_resume_on_start,
        );
        let last_tasks_mtime = std::fs::metadata(Self::tasks_path(&data_dir))
            .and_then(|m| m.modified())
            .ok();
        let limiter = Arc::new(RateLimiter::new(settings.speed_limit_kbps));
        let client = Client::builder()
            .connect_timeout(Duration::from_secs(15))
            .timeout(Duration::from_secs(60))
            .pool_max_idle_per_host(32)
            .build()
            .expect("downloader reqwest client");

        let (stop_tx, stop_rx) = watch::channel(false);

        let mgr = Arc::new(Self {
            inner: Arc::new(Mutex::new(Inner {
                data_dir,
                settings,
                tasks,
                order,
                active: HashMap::new(),
                closed: false,
                worker_enabled,
                last_tasks_mtime,
            })),
            limiter,
            client,
            resolver,
            stop_tx,
        });

        if worker_enabled {
            Self::spawn_ticker(mgr.clone(), stop_rx, rt);
            let _ = mgr.ingest_inbox(rt);
            Self::schedule(&mgr, rt);
        }
        mgr
    }

    fn spawn_ticker(
        mgr: Arc<Self>,
        mut stop_rx: watch::Receiver<bool>,
        rt: &tokio::runtime::Handle,
    ) {
        let handle = rt.clone();
        rt.spawn(async move {
            let mut speed_tick = tokio::time::interval(Duration::from_millis(500));
            let mut save_tick = tokio::time::interval(Duration::from_secs(4));
            loop {
                tokio::select! {
                    _ = stop_rx.changed() => {
                        if *stop_rx.borrow() {
                            break;
                        }
                    }
                    _ = speed_tick.tick() => {
                        let _ = mgr.ingest_inbox(&handle);
                        let now = Instant::now();
                        let mut guard = mgr.inner.lock().unwrap();
                        let active_ids: Vec<String> = guard.active.keys().cloned().collect();
                        for id in active_ids {
                            let cur_bytes = guard.tasks.get(&id).map(|t| (t.status, t.downloaded_bytes));
                            if let Some((status, bytes)) = cur_bytes {
                                if status != TaskStatus::Downloading {
                                    if let Some(t) = guard.tasks.get_mut(&id) {
                                        t.speed_bytes_per_sec = 0;
                                    }
                                    continue;
                                }
                                if let Some(rt_state) = guard.active.get_mut(&id) {
                                    let dt = now.duration_since(rt_state.last_tick).as_secs_f64();
                                    if dt >= 0.35 {
                                        let delta = bytes.saturating_sub(rt_state.last_bytes);
                                        let inst = (delta as f64 / dt) as u64;
                                        rt_state.last_bytes = bytes;
                                        rt_state.last_tick = now;
                                        if let Some(t) = guard.tasks.get_mut(&id) {
                                            t.speed_bytes_per_sec = if t.speed_bytes_per_sec == 0 {
                                                inst
                                            } else {
                                                (t.speed_bytes_per_sec * 3 + inst * 2) / 5
                                            };
                                        }
                                    }
                                }
                            }
                        }
                    }
                    _ = save_tick.tick() => {
                        let snap = {
                            let guard = mgr.inner.lock().unwrap();
                            if guard.active.is_empty() {
                                None
                            } else {
                                Some(Self::take_snapshot_locked(&guard))
                            }
                        };
                        if let Some((dir, list)) = snap {
                            handle.spawn_blocking(move || {
                                Self::write_tasks_snapshot(&dir, &list);
                            });
                        }
                    }
                }
            }
        });
    }

    pub fn shutdown(&self) {
        let _ = self.stop_tx.send(true);
        let mut guard = self.inner.lock().unwrap();
        guard.closed = true;
        for rt in guard.active.values() {
            let _ = rt.cancel_tx.send(true);
        }
        if guard.worker_enabled {
            Self::save_tasks_locked(&guard);
        }
    }

    fn settings_path(data_dir: &Path) -> PathBuf {
        data_dir.join("download_settings.json")
    }

    fn tasks_path(data_dir: &Path) -> PathBuf {
        data_dir.join("downloads.json")
    }

    fn inbox_dir(data_dir: &Path) -> PathBuf {
        data_dir.join("download_inbox")
    }

    fn refresh_readonly_locked(guard: &mut Inner) {
        if guard.worker_enabled {
            return;
        }
        let path = Self::tasks_path(&guard.data_dir);
        let mtime = std::fs::metadata(&path).and_then(|m| m.modified()).ok();
        if mtime.is_some() && mtime != guard.last_tasks_mtime {
            let (tasks, order) = Self::load_tasks(&guard.data_dir, false);
            guard.tasks = tasks;
            guard.order = order;
            guard.last_tasks_mtime = mtime;
        }
    }

    fn load_settings(data_dir: &Path) -> DownloadSettings {
        let p = Self::settings_path(data_dir);
        if let Ok(raw) = std::fs::read_to_string(p) {
            if let Ok(mut s) = serde_json::from_str::<DownloadSettings>(&raw) {
                s.normalize(data_dir);
                return s;
            }
        }
        DownloadSettings::default_for(data_dir)
    }

    fn save_settings_locked(inner: &Inner) -> Result<(), String> {
        let json = serde_json::to_string_pretty(&inner.settings).map_err(|e| e.to_string())?;
        atomic_write(&Self::settings_path(&inner.data_dir), json.as_bytes())
    }

    fn load_tasks(
        data_dir: &Path,
        auto_resume: bool,
    ) -> (HashMap<String, DownloadTask>, Vec<String>) {
        let mut map = HashMap::new();
        let mut order = Vec::new();
        let p = Self::tasks_path(data_dir);
        if let Ok(raw) = std::fs::read_to_string(p) {
            if let Ok(list) = serde_json::from_str::<Vec<DownloadTask>>(&raw) {
                for mut t in list {
                    if t.id.is_empty() {
                        continue;
                    }
                    t.speed_bytes_per_sec = 0;
                    if t.status == TaskStatus::Downloading {
                        t.status = if auto_resume {
                            TaskStatus::Queued
                        } else {
                            TaskStatus::Paused
                        };
                    }
                    order.push(t.id.clone());
                    map.insert(t.id.clone(), t);
                }
            }
        }
        (map, order)
    }

    fn take_snapshot_locked(inner: &Inner) -> (PathBuf, Vec<DownloadTask>) {
        let list: Vec<DownloadTask> = inner
            .order
            .iter()
            .filter_map(|id| inner.tasks.get(id).cloned())
            .collect();
        (inner.data_dir.clone(), list)
    }

    fn write_tasks_snapshot(data_dir: &Path, list: &[DownloadTask]) {
        if let Ok(bytes) = serde_json::to_vec(list) {
            let _ = atomic_write(&Self::tasks_path(data_dir), &bytes);
        }
    }

    fn save_tasks_locked(inner: &Inner) {
        let (dir, list) = Self::take_snapshot_locked(inner);
        Self::write_tasks_snapshot(&dir, &list);
    }

    pub fn settings(&self) -> DownloadSettings {
        self.inner.lock().unwrap().settings.clone()
    }

    pub fn update_settings(
        self: &Arc<Self>,
        mut next: DownloadSettings,
        rt: &tokio::runtime::Handle,
    ) -> Result<DownloadSettings, String> {
        let saved = {
            let mut guard = self.inner.lock().unwrap();
            next.normalize(&guard.data_dir);
            guard.settings = next.clone();
            Self::save_settings_locked(&guard)?;
            guard.settings.clone()
        };
        self.limiter.set_rate_kbps_blocking(saved.speed_limit_kbps);
        Self::schedule(self, rt);
        Ok(saved)
    }

    pub fn active_count(&self) -> usize {
        let mut guard = self.inner.lock().unwrap();
        Self::refresh_readonly_locked(&mut guard);
        guard
            .tasks
            .values()
            .filter(|t| matches!(t.status, TaskStatus::Queued | TaskStatus::Downloading))
            .count()
    }

    pub fn list(&self) -> Vec<DownloadTask> {
        let mut guard = self.inner.lock().unwrap();
        Self::refresh_readonly_locked(&mut guard);
        let mut out: Vec<DownloadTask> = guard
            .order
            .iter()
            .filter_map(|id| guard.tasks.get(id).cloned())
            .collect();
        out.sort_by(|a, b| {
            a.series_title
                .cmp(&b.series_title)
                .then_with(|| a.series_id.cmp(&b.series_id))
                .then_with(|| a.episode_index.cmp(&b.episode_index))
                .then_with(|| a.created_at.cmp(&b.created_at))
        });
        out
    }

    /// Slim UI list projection: drops large `chunks`, `spadeA`, `etag`, `partPath`, and `episodeTitle` fields.
    pub fn list_slim(&self) -> Vec<serde_json::Value> {
        let tasks = self.list();
        tasks
            .into_iter()
            .map(|t| {
                let status = match t.status {
                    TaskStatus::Queued => "queued",
                    TaskStatus::Downloading => "downloading",
                    TaskStatus::Paused => "paused",
                    TaskStatus::Completed => "completed",
                    TaskStatus::Failed => "failed",
                    TaskStatus::Canceled => "canceled",
                };
                serde_json::json!({
                    "id": t.id,
                    "seriesId": t.series_id,
                    "seriesTitle": t.series_title,
                    "cover": t.cover,
                    "vid": t.vid,
                    "episodeIndex": t.episode_index,
                    "definition": t.definition,
                    "totalBytes": t.total_bytes,
                    "downloadedBytes": t.downloaded_bytes,
                    "speedBytesPerSec": t.speed_bytes_per_sec,
                    "chunkCount": t.chunks.len(),
                    "status": status,
                    "savePath": t.save_path,
                    "error": t.error,
                })
            })
            .collect()
    }

    pub fn get_local_path(&self, vid: &str) -> Option<String> {
        if vid.trim().is_empty() {
            return None;
        }
        let candidates: Vec<String> = {
            let mut guard = self.inner.lock().unwrap();
            Self::refresh_readonly_locked(&mut guard);
            guard
                .order
                .iter()
                .rev()
                .filter_map(|id| guard.tasks.get(id))
                .filter(|t| t.vid == vid && t.status == TaskStatus::Completed && !t.save_path.is_empty())
                .map(|t| t.save_path.clone())
                .collect()
        };
        for p in candidates {
            if let Ok(meta) = std::fs::metadata(&p) {
                if meta.is_file() && meta.len() > 0 {
                    return Some(p);
                }
            }
        }
        None
    }

    fn build_new_task(settings: &DownloadSettings, req: AddDownloadRequest, def: String) -> DownloadTask {
        let mut dir = PathBuf::from(&settings.download_dir);
        if settings.create_series_subdir {
            let fallback = format!("series_{}", sanitize_name(&req.series_id, "unknown"));
            let folder = sanitize_name(&req.series_title, &fallback);
            dir = dir.join(folder);
        }
        let ep_idx = if req.episode_index > 0 {
            req.episode_index
        } else {
            1
        };
        let ep_label = format!("第{:03}集", ep_idx);
        let file_name = if settings.create_series_subdir {
            format!("{}.mp4", ep_label)
        } else {
            format!(
                "{}_{}.mp4",
                sanitize_name(&req.series_title, "series"),
                ep_label
            )
        };
        let save_path = dir.join(file_name);
        let part_path = PathBuf::from(format!("{}.part", save_path.to_string_lossy()));

        let now = Utc::now();
        let id = Uuid::new_v4().simple().to_string();
        DownloadTask {
            id,
            series_id: req.series_id,
            series_title: req.series_title,
            cover: req.cover,
            vid: req.vid,
            episode_index: ep_idx,
            episode_title: req.episode_title,
            definition: def,
            total_bytes: 0,
            downloaded_bytes: 0,
            speed_bytes_per_sec: 0,
            chunks: Vec::new(),
            status: TaskStatus::Queued,
            save_path: save_path.to_string_lossy().to_string(),
            part_path: part_path.to_string_lossy().to_string(),
            error: String::new(),
            created_at: now,
            updated_at: now,
            etag: String::new(),
            encrypted: false,
            spade_a: String::new(),
        }
    }

    pub fn ingest_inbox(
        self: &Arc<Self>,
        rt: &tokio::runtime::Handle,
    ) -> Vec<DownloadTask> {
        let inbox = {
            let guard = self.inner.lock().unwrap();
            if !guard.worker_enabled || guard.closed {
                return Vec::new();
            }
            Self::inbox_dir(&guard.data_dir)
        };

        let Ok(rd) = std::fs::read_dir(&inbox) else {
            return Vec::new();
        };
        let mut files: Vec<PathBuf> = rd
            .flatten()
            .map(|e| e.path())
            .filter(|p| {
                p.is_file()
                    && p.extension().and_then(|s| s.to_str()) == Some("json")
            })
            .collect();
        if files.is_empty() {
            return Vec::new();
        }
        files.sort();

        let mut all_reqs = Vec::new();
        for f in files {
            if let Ok(raw) = std::fs::read(&f) {
                let _ = std::fs::remove_file(&f);
                if let Ok(reqs) = serde_json::from_slice::<Vec<AddDownloadRequest>>(&raw) {
                    all_reqs.extend(reqs);
                }
            }
        }
        if all_reqs.is_empty() {
            return Vec::new();
        }
        self.add_batch(all_reqs, rt).unwrap_or_default()
    }

    pub fn add_batch(
        self: &Arc<Self>,
        reqs: Vec<AddDownloadRequest>,
        rt: &tokio::runtime::Handle,
    ) -> Result<Vec<DownloadTask>, String> {
        let clean_reqs: Vec<AddDownloadRequest> = reqs
            .into_iter()
            .filter(|r| !r.vid.trim().is_empty())
            .collect();
        if clean_reqs.is_empty() {
            return Ok(Vec::new());
        }

        let (worker_enabled, data_dir, settings_snapshot) = {
            let guard = self.inner.lock().unwrap();
            (
                guard.worker_enabled,
                guard.data_dir.clone(),
                guard.settings.clone(),
            )
        };

        // Player process writes a single atomic batch file into download_inbox so the main process picks up all tasks cleanly.
        if !worker_enabled {
            let inbox = Self::inbox_dir(&data_dir);
            std::fs::create_dir_all(&inbox).map_err(|e| e.to_string())?;
            let fname = format!("{}_{}.json", Utc::now().timestamp_millis(), Uuid::new_v4().simple());
            let target = inbox.join(fname);
            let bytes = serde_json::to_vec(&clean_reqs).map_err(|e| e.to_string())?;
            atomic_write(&target, &bytes)?;

            let mut stubs = Vec::with_capacity(clean_reqs.len());
            for req in clean_reqs {
                let def = if req.definition.trim().is_empty() {
                    settings_snapshot.preferred_quality.clone()
                } else {
                    req.definition.trim().to_string()
                };
                stubs.push(Self::build_new_task(&settings_snapshot, req, def));
            }
            return Ok(stubs);
        }

        let mut results = Vec::with_capacity(clean_reqs.len());
        let mut changed = false;

        {
            let mut guard = self.inner.lock().unwrap();
            let mut existing_by_key: HashMap<(String, String), String> = HashMap::new();
            for id in &guard.order {
                if let Some(t) = guard.tasks.get(id) {
                    existing_by_key
                        .entry((t.vid.clone(), t.definition.clone()))
                        .or_insert_with(|| id.clone());
                }
            }

            for req in clean_reqs {
                let def = if req.definition.trim().is_empty() {
                    guard.settings.preferred_quality.clone()
                } else {
                    req.definition.trim().to_string()
                };
                let key = (req.vid.clone(), def.clone());

                if let Some(eid) = existing_by_key.get(&key).cloned() {
                    if let Some(existing) = guard.tasks.get(&eid).cloned() {
                        match existing.status {
                            TaskStatus::Queued | TaskStatus::Downloading => {
                                results.push(existing);
                                continue;
                            }
                            TaskStatus::Completed => {
                                if std::fs::metadata(&existing.save_path)
                                    .map(|m| m.is_file() && m.len() > 0)
                                    .unwrap_or(false)
                                {
                                    results.push(existing);
                                    continue;
                                }
                            }
                            TaskStatus::Paused | TaskStatus::Failed | TaskStatus::Canceled => {
                                if let Some(t) = guard.tasks.get_mut(&eid) {
                                    t.status = TaskStatus::Queued;
                                    t.error.clear();
                                    t.updated_at = Utc::now();
                                    results.push(t.clone());
                                    changed = true;
                                    continue;
                                }
                            }
                        }
                    }
                }

                let task = Self::build_new_task(&guard.settings, req, def);
                let tid = task.id.clone();
                existing_by_key.insert(key, tid.clone());
                guard.order.push(tid.clone());
                guard.tasks.insert(tid, task.clone());
                results.push(task);
                changed = true;
            }

            if changed {
                Self::save_tasks_locked(&guard);
            }
        }

        if changed {
            Self::schedule(self, rt);
        }
        Ok(results)
    }

    pub fn add(
        self: &Arc<Self>,
        req: AddDownloadRequest,
        rt: &tokio::runtime::Handle,
    ) -> Result<DownloadTask, String> {
        if req.vid.trim().is_empty() {
            return Err("缺少视频编号".to_string());
        }
        let mut out = self.add_batch(vec![req], rt)?;
        out.pop().ok_or_else(|| "创建下载任务失败".to_string())
    }

    pub fn pause(&self, id: &str) -> Result<(), String> {
        let mut guard = self.inner.lock().unwrap();
        let status = guard
            .tasks
            .get(id)
            .map(|t| t.status)
            .ok_or_else(|| "任务不存在".to_string())?;
        if status == TaskStatus::Queued {
            if let Some(t) = guard.tasks.get_mut(id) {
                t.status = TaskStatus::Paused;
                t.speed_bytes_per_sec = 0;
                t.updated_at = Utc::now();
            }
            Self::save_tasks_locked(&guard);
        } else if status == TaskStatus::Downloading {
            if let Some(rt_state) = guard.active.get(id) {
                rt_state
                    .pause_requested
                    .store(true, std::sync::atomic::Ordering::SeqCst);
                let _ = rt_state.cancel_tx.send(true);
            }
            if let Some(t) = guard.tasks.get_mut(id) {
                t.status = TaskStatus::Paused;
                t.speed_bytes_per_sec = 0;
                t.updated_at = Utc::now();
            }
            Self::save_tasks_locked(&guard);
        }
        Ok(())
    }

    pub fn resume(self: &Arc<Self>, id: &str, rt: &tokio::runtime::Handle) -> Result<(), String> {
        {
            let mut guard = self.inner.lock().unwrap();
            let t = guard
                .tasks
                .get_mut(id)
                .ok_or_else(|| "任务不存在".to_string())?;
            if matches!(
                t.status,
                TaskStatus::Paused | TaskStatus::Failed | TaskStatus::Canceled
            ) {
                t.status = TaskStatus::Queued;
                t.error.clear();
                t.updated_at = Utc::now();
                Self::save_tasks_locked(&guard);
            }
        }
        Self::schedule(self, rt);
        Ok(())
    }

    pub fn cancel(&self, id: &str) -> Result<(), String> {
        let mut guard = self.inner.lock().unwrap();
        if !guard.tasks.contains_key(id) {
            return Err("任务不存在".to_string());
        }
        if let Some(rt_state) = guard.active.get(id) {
            rt_state
                .pause_requested
                .store(false, std::sync::atomic::Ordering::SeqCst);
            let _ = rt_state.cancel_tx.send(true);
        }
        let part_path = if let Some(t) = guard.tasks.get_mut(id) {
            t.status = TaskStatus::Canceled;
            t.speed_bytes_per_sec = 0;
            t.downloaded_bytes = 0;
            t.chunks.clear();
            t.updated_at = Utc::now();
            t.part_path.clone()
        } else {
            String::new()
        };
        if !part_path.is_empty() {
            let _ = std::fs::remove_file(part_path);
        }
        Self::save_tasks_locked(&guard);
        Ok(())
    }

    pub fn delete(&self, id: &str, remove_file: bool) -> Result<(), String> {
        let mut guard = self.inner.lock().unwrap();
        let task = guard
            .tasks
            .remove(id)
            .ok_or_else(|| "任务不存在".to_string())?;
        guard.order.retain(|x| x != id);
        if let Some(rt_state) = guard.active.get(id) {
            rt_state
                .delete_requested
                .store(true, std::sync::atomic::Ordering::SeqCst);
            rt_state
                .delete_file
                .store(remove_file, std::sync::atomic::Ordering::SeqCst);
            let _ = rt_state.cancel_tx.send(true);
        }
        if !task.part_path.is_empty() {
            let _ = std::fs::remove_file(&task.part_path);
        }
        if remove_file && !task.vid.is_empty() {
            let _ = std::fs::remove_file(danmaku_cache_path(&guard.data_dir, &task.vid));
        }
        if remove_file && !task.save_path.is_empty() {
            let save_path = Path::new(&task.save_path);
            let _ = std::fs::remove_file(save_path);
            if let Some(parent) = save_path.parent() {
                let dl_root = Path::new(&guard.settings.download_dir);
                if parent != dl_root && parent.starts_with(dl_root) {
                    let _ = std::fs::remove_dir(parent);
                }
            }
        }
        Self::save_tasks_locked(&guard);
        Ok(())
    }

    pub fn delete_batch(
        &self,
        ids: &[String],
        remove_file: bool,
        rt: &tokio::runtime::Handle,
    ) -> Result<usize, String> {
        if ids.is_empty() {
            return Ok(0);
        }
        let id_set: std::collections::HashSet<&str> = ids.iter().map(|s| s.as_str()).collect();
        let (removed, remaining_dirs, dl_root, data_dir, create_subdir) = {
            let mut guard = self.inner.lock().unwrap();
            let mut removed = Vec::with_capacity(id_set.len());
            for id in &id_set {
                if let Some(task) = guard.tasks.remove(*id) {
                    if let Some(rt_state) = guard.active.get(*id) {
                        rt_state
                            .delete_requested
                            .store(true, std::sync::atomic::Ordering::SeqCst);
                        rt_state
                            .delete_file
                            .store(remove_file, std::sync::atomic::Ordering::SeqCst);
                        let _ = rt_state.cancel_tx.send(true);
                    }
                    removed.push(task);
                }
            }
            if removed.is_empty() {
                return Ok(0);
            }
            guard.order.retain(|x| !id_set.contains(x.as_str()));
            Self::save_tasks_locked(&guard);

            let remaining_dirs: std::collections::HashSet<PathBuf> = guard
                .tasks
                .values()
                .filter(|t| !t.save_path.is_empty())
                .filter_map(|t| Path::new(&t.save_path).parent().map(Path::to_path_buf))
                .collect();
            (
                removed,
                remaining_dirs,
                PathBuf::from(&guard.settings.download_dir),
                guard.data_dir.clone(),
                guard.settings.create_series_subdir,
            )
        };

        let count = removed.len();
        rt.spawn_blocking(move || {
            let mut whole_dirs = std::collections::HashSet::new();
            let mut files = Vec::new();
            for task in &removed {
                if !task.part_path.is_empty() {
                    let _ = std::fs::remove_file(&task.part_path);
                }
                if remove_file && !task.vid.is_empty() {
                    let _ = std::fs::remove_file(danmaku_cache_path(&data_dir, &task.vid));
                }
                if remove_file && !task.save_path.is_empty() {
                    let save_path = Path::new(&task.save_path);
                    if create_subdir {
                        if let Some(parent) = save_path.parent() {
                            if parent != dl_root
                                && parent.starts_with(&dl_root)
                                && !remaining_dirs.contains(parent)
                            {
                                whole_dirs.insert(parent.to_path_buf());
                                continue;
                            }
                        }
                    }
                    files.push(save_path.to_path_buf());
                }
            }
            for dir in whole_dirs {
                let _ = std::fs::remove_dir_all(dir);
            }
            for f in files {
                let _ = std::fs::remove_file(f);
            }
        });

        Ok(count)
    }

    pub fn clear_completed(&self) -> Result<(), String> {
        let mut guard = self.inner.lock().unwrap();
        let remove_ids: Vec<String> = guard
            .tasks
            .values()
            .filter(|t| matches!(t.status, TaskStatus::Completed | TaskStatus::Canceled))
            .map(|t| t.id.clone())
            .collect();
        for id in &remove_ids {
            guard.tasks.remove(id);
        }
        let remaining: std::collections::HashSet<String> = guard.tasks.keys().cloned().collect();
        guard.order.retain(|id| remaining.contains(id));
        Self::save_tasks_locked(&guard);
        Ok(())
    }

    pub fn pause_all(&self) -> Result<(), String> {
        let mut guard = self.inner.lock().unwrap();
        let ids: Vec<String> = guard.order.clone();
        for id in ids {
            let status = guard.tasks.get(&id).map(|t| t.status);
            if status == Some(TaskStatus::Queued) {
                if let Some(t) = guard.tasks.get_mut(&id) {
                    t.status = TaskStatus::Paused;
                    t.speed_bytes_per_sec = 0;
                    t.updated_at = Utc::now();
                }
            } else if status == Some(TaskStatus::Downloading) {
                if let Some(rt_state) = guard.active.get(&id) {
                    rt_state
                        .pause_requested
                        .store(true, std::sync::atomic::Ordering::SeqCst);
                    let _ = rt_state.cancel_tx.send(true);
                }
                if let Some(t) = guard.tasks.get_mut(&id) {
                    t.status = TaskStatus::Paused;
                    t.speed_bytes_per_sec = 0;
                    t.updated_at = Utc::now();
                }
            }
        }
        Self::save_tasks_locked(&guard);
        Ok(())
    }

    pub fn resume_all(self: &Arc<Self>, rt: &tokio::runtime::Handle) -> Result<(), String> {
        {
            let mut guard = self.inner.lock().unwrap();
            for t in guard.tasks.values_mut() {
                if matches!(t.status, TaskStatus::Paused | TaskStatus::Failed) {
                    t.status = TaskStatus::Queued;
                    t.error.clear();
                    t.updated_at = Utc::now();
                }
            }
            Self::save_tasks_locked(&guard);
        }
        Self::schedule(self, rt);
        Ok(())
    }

    pub fn open_location(&self, id: &str) -> Result<(), String> {
        let (target, dl_dir) = {
            let guard = self.inner.lock().unwrap();
            let dl_dir = guard.settings.download_dir.clone();
            let target = if !id.is_empty() {
                guard.tasks.get(id).and_then(|t| {
                    if !t.save_path.is_empty() && Path::new(&t.save_path).exists() {
                        Some(t.save_path.clone())
                    } else if !t.save_path.is_empty() {
                        Path::new(&t.save_path)
                            .parent()
                            .map(|p| p.to_string_lossy().to_string())
                    } else {
                        None
                    }
                })
            } else {
                None
            };
            (target.unwrap_or_else(|| dl_dir.clone()), dl_dir)
        };

        let _ = std::fs::create_dir_all(&dl_dir);
        let p = Path::new(&target);

        #[cfg(target_os = "windows")]
        {
            if p.is_file() {
                std::process::Command::new("explorer")
                    .arg("/select,")
                    .arg(p)
                    .spawn()
                    .map_err(|e| e.to_string())?;
            } else {
                std::process::Command::new("explorer")
                    .arg(p)
                    .spawn()
                    .map_err(|e| e.to_string())?;
            }
        }
        #[cfg(target_os = "macos")]
        {
            if p.is_file() {
                std::process::Command::new("open")
                    .arg("-R")
                    .arg(p)
                    .spawn()
                    .map_err(|e| e.to_string())?;
            } else {
                std::process::Command::new("open")
                    .arg(p)
                    .spawn()
                    .map_err(|e| e.to_string())?;
            }
        }
        #[cfg(all(unix, not(target_os = "macos")))]
        {
            let dir = if p.is_file() {
                p.parent().unwrap_or(p)
            } else {
                p
            };
            std::process::Command::new("xdg-open")
                .arg(dir)
                .spawn()
                .map_err(|e| e.to_string())?;
        }
        Ok(())
    }

    fn schedule(mgr: &Arc<Self>, rt: &tokio::runtime::Handle) {
        let to_spawn: Vec<(String, ActiveRuntime, watch::Receiver<bool>)> = {
            let mut guard = mgr.inner.lock().unwrap();
            if guard.closed || !guard.worker_enabled {
                return;
            }
            let max_c = guard.settings.max_concurrent;
            let mut running = guard.active.len();
            let mut list = Vec::new();
            let order = guard.order.clone();
            for id in order {
                if running >= max_c {
                    break;
                }
                if guard.active.contains_key(&id) {
                    continue;
                }
                let is_queued = guard
                    .tasks
                    .get(&id)
                    .map(|t| t.status == TaskStatus::Queued)
                    .unwrap_or(false);
                if !is_queued {
                    continue;
                }
                let (cancel_tx, cancel_rx) = watch::channel(false);
                let bytes = guard
                    .tasks
                    .get(&id)
                    .map(|t| t.downloaded_bytes)
                    .unwrap_or(0);
                if let Some(t) = guard.tasks.get_mut(&id) {
                    t.status = TaskStatus::Downloading;
                    t.error.clear();
                    t.updated_at = Utc::now();
                }
                let runtime_state = ActiveRuntime {
                    cancel_tx,
                    pause_requested: Arc::new(std::sync::atomic::AtomicBool::new(false)),
                    delete_requested: Arc::new(std::sync::atomic::AtomicBool::new(false)),
                    delete_file: Arc::new(std::sync::atomic::AtomicBool::new(false)),
                    last_bytes: bytes,
                    last_tick: Instant::now(),
                };
                guard.active.insert(id.clone(), runtime_state.clone());
                list.push((id, runtime_state, cancel_rx));
                running += 1;
            }
            if !list.is_empty() {
                Self::save_tasks_locked(&guard);
            }
            list
        };

        for (id, rt_state, cancel_rx) in to_spawn {
            let mgr_c = mgr.clone();
            let handle = rt.clone();
            rt.spawn(async move {
                let res = mgr_c.execute_task(&id, cancel_rx.clone()).await;
                let mut guard = mgr_c.inner.lock().unwrap();
                let removed = guard.active.remove(&id);
                let paused = rt_state
                    .pause_requested
                    .load(std::sync::atomic::Ordering::SeqCst);
                let deleted = rt_state
                    .delete_requested
                    .load(std::sync::atomic::Ordering::SeqCst);
                let del_file = rt_state
                    .delete_file
                    .load(std::sync::atomic::Ordering::SeqCst);
                let canceled = *cancel_rx.borrow();

                if deleted {
                    if let Some(t) = guard.tasks.remove(&id) {
                        if !t.part_path.is_empty() {
                            let _ = std::fs::remove_file(&t.part_path);
                        }
                        if del_file && !t.save_path.is_empty() {
                            let _ = std::fs::remove_file(&t.save_path);
                        }
                    }
                } else if let Some(t) = guard.tasks.get_mut(&id) {
                    t.speed_bytes_per_sec = 0;
                    t.updated_at = Utc::now();
                    if paused {
                        if t.status != TaskStatus::Completed {
                            t.status = TaskStatus::Paused;
                        }
                    } else if canceled {
                        if t.status == TaskStatus::Downloading {
                            t.status = TaskStatus::Paused;
                        }
                    } else if let Err(e) = res {
                        if !matches!(t.status, TaskStatus::Paused | TaskStatus::Canceled) {
                            t.status = TaskStatus::Failed;
                            t.error = e;
                        }
                    } else {
                        t.status = TaskStatus::Completed;
                        t.error.clear();
                    }
                }
                let _ = removed;
                let (dir, list) = Self::take_snapshot_locked(&guard);
                drop(guard);
                handle.spawn_blocking(move || {
                    Self::write_tasks_snapshot(&dir, &list);
                });
                Self::schedule(&mgr_c, &handle);
            });
        }
    }

    async fn execute_task(&self, id: &str, cancel_rx: watch::Receiver<bool>) -> Result<(), String> {
        let (vid, def, save_path, part_path, threads, retry_max, prev_total, prev_etag, prev_chunks) = {
            let guard = self.inner.lock().unwrap();
            let t = guard
                .tasks
                .get(id)
                .ok_or_else(|| "任务不存在".to_string())?;
            (
                t.vid.clone(),
                t.definition.clone(),
                t.save_path.clone(),
                t.part_path.clone(),
                guard.settings.threads_per_task,
                guard.settings.auto_retry_count,
                t.total_bytes,
                t.etag.clone(),
                t.chunks.clone(),
            )
        };

        if let Some(parent) = Path::new(&save_path).parent() {
            std::fs::create_dir_all(parent).map_err(|e| format!("创建目录失败: {e}"))?;
        }

        let resolved = self.resolver.resolve(&vid, &def, false).await?;
        let (encrypted, spade_a) = (resolved.encrypted, resolved.spade_a.clone());
        {
            let mut guard = self.inner.lock().unwrap();
            if let Some(t) = guard.tasks.get_mut(id) {
                if !resolved.definition.is_empty() {
                    t.definition = resolved.definition.clone();
                }
                t.encrypted = encrypted;
                t.spade_a = spade_a.clone();
            }
        }

        let mut media_url = String::new();
        let mut probe_opt = None;
        let mut last_probe_err = String::new();
        for cand_url in &resolved.urls {
            match self.probe_media(cand_url).await {
                Ok(p) => {
                    media_url = cand_url.clone();
                    probe_opt = Some(p);
                    break;
                }
                Err(e) => {
                    last_probe_err = e;
                }
            }
        }
        let probe = probe_opt.ok_or_else(|| {
            if last_probe_err.is_empty() {
                "媒体探测失败".to_string()
            } else {
                last_probe_err
            }
        })?;
        let total = probe.total_bytes;

        if total == 0 || !probe.accept_ranges {
            self.download_single_stream(id, &media_url, &part_path, total, cancel_rx.clone())
                .await?;
        } else {
            let can_resume = prev_total == total
                && !prev_chunks.is_empty()
                && (prev_etag.is_empty() || probe.etag.is_empty() || prev_etag == probe.etag)
                && std::fs::metadata(&part_path)
                    .map(|m| m.len() >= total)
                    .unwrap_or(false);

            let chunks = if can_resume {
                prev_chunks
            } else {
                let mut n = threads.max(1);
                if total < 512 * 1024 {
                    n = 1;
                } else {
                    let max_by_size = (total / (256 * 1024)).max(1) as usize;
                    n = n.min(max_by_size);
                }
                make_chunks(total, n)
            };

            let file = OpenOptions::new()
                .create(true)
                .read(true)
                .write(true)
                .open(&part_path)
                .map_err(|e| format!("打开临时文件失败: {e}"))?;

            if !can_resume {
                file.set_len(total)
                    .map_err(|e| format!("预分配空间失败: {e}"))?;
            }
            let file = Arc::new(file);

            {
                let mut guard = self.inner.lock().unwrap();
                if let Some(t) = guard.tasks.get_mut(id) {
                    t.total_bytes = total;
                    t.etag = probe.etag.clone();
                    t.downloaded_bytes = chunks.iter().map(|c| c.downloaded).sum();
                    t.chunks = chunks.clone();
                    Self::save_tasks_locked(&guard);
                }
            }

            let url_holder = Arc::new(tokio::sync::RwLock::new(media_url));
            let mut handles = Vec::new();
            for c in &chunks {
                if c.start + c.downloaded > c.end {
                    continue;
                }
                let c_idx = c.index;
                let client = self.client.clone();
                let limiter = self.limiter.clone();
                let inner = self.inner.clone();
                let file_c = file.clone();
                let task_id = id.to_string();
                let vid_c = vid.clone();
                let def_c = def.clone();
                let resolver = self.resolver.clone();
                let url_h = url_holder.clone();
                let c_rx = cancel_rx.clone();

                handles.push(tokio::spawn(async move {
                    download_chunk(
                        client, limiter, inner, file_c, task_id, c_idx, retry_max, vid_c, def_c,
                        resolver, url_h, c_rx,
                    )
                    .await
                }));
            }

            for h in handles {
                let r = h.await.map_err(|e| format!("工作线程异常: {e}"))?;
                r?;
            }
            drop(file);
        }

        if *cancel_rx.borrow() {
            return Err("已取消".to_string());
        }

        let _ = std::fs::remove_file(&save_path);
        if encrypted && !spade_a.is_empty() {
            let key = spade_to_key(&spade_a).ok_or_else(|| "解析视频解密密钥失败".to_string())?;
            let part_clone = part_path.clone();
            let save_clone = save_path.clone();
            tokio::task::spawn_blocking(move || -> Result<(), String> {
                let mut raw =
                    std::fs::read(&part_clone).map_err(|e| format!("读取临时文件失败: {e}"))?;
                decrypt_mp4(&mut raw, &key)?;
                let dec_path = PathBuf::from(format!("{}.dec", save_clone));
                std::fs::write(&dec_path, &raw).map_err(|e| format!("写入解密视频失败: {e}"))?;
                std::fs::rename(&dec_path, &save_clone)
                    .map_err(|e| format!("保存解密视频失败: {e}"))?;
                let _ = std::fs::remove_file(&part_clone);
                Ok(())
            })
            .await
            .map_err(|e| format!("解密任务异常: {e}"))??;
        } else {
            let part_clone = part_path.clone();
            let save_clone = save_path.clone();
            tokio::task::spawn_blocking(move || -> Result<(), String> {
                std::fs::rename(&part_clone, &save_clone)
                    .map_err(|e| format!("保存成品文件失败: {e}"))
            })
            .await
            .map_err(|e| format!("文件落盘异常: {e}"))??;
        }
        Ok(())
    }

    async fn probe_media(&self, url: &str) -> Result<ProbeResult, String> {
        if let Ok(res) = self.client.head(url).send().await {
            if res.status().is_success() {
                let len = res
                    .headers()
                    .get(CONTENT_LENGTH)
                    .and_then(|v| v.to_str().ok())
                    .and_then(|s| s.parse::<u64>().ok())
                    .unwrap_or(0);
                let ar = res
                    .headers()
                    .get(ACCEPT_RANGES)
                    .and_then(|v| v.to_str().ok())
                    .map(|s| s.to_ascii_lowercase().contains("bytes"))
                    .unwrap_or(false);
                let etag = res
                    .headers()
                    .get(ETAG)
                    .and_then(|v| v.to_str().ok())
                    .unwrap_or("")
                    .to_string();
                if len > 0 {
                    return Ok(ProbeResult {
                        total_bytes: len,
                        accept_ranges: ar,
                        etag,
                    });
                }
            }
        }

        let res2 = self
            .client
            .get(url)
            .header(RANGE, "bytes=0-0")
            .send()
            .await
            .map_err(|e| format!("探测媒体失败: {e}"))?;
        let status = res2.status();
        let etag = res2
            .headers()
            .get(ETAG)
            .and_then(|v| v.to_str().ok())
            .unwrap_or("")
            .to_string();
        if status.as_u16() == 206 {
            let total = res2
                .headers()
                .get(CONTENT_RANGE)
                .and_then(|v| v.to_str().ok())
                .and_then(parse_content_range_total)
                .unwrap_or(0);
            if total > 0 {
                return Ok(ProbeResult {
                    total_bytes: total,
                    accept_ranges: true,
                    etag,
                });
            }
        }
        if status.is_success() {
            let len = res2
                .headers()
                .get(CONTENT_LENGTH)
                .and_then(|v| v.to_str().ok())
                .and_then(|s| s.parse::<u64>().ok())
                .unwrap_or(0);
            return Ok(ProbeResult {
                total_bytes: len,
                accept_ranges: false,
                etag,
            });
        }
        Err(format!("媒体探测返回 HTTP {}", status.as_u16()))
    }

    async fn download_single_stream(
        &self,
        id: &str,
        url: &str,
        part_path: &str,
        known_total: u64,
        mut cancel_rx: watch::Receiver<bool>,
    ) -> Result<(), String> {
        let res = self
            .client
            .get(url)
            .send()
            .await
            .map_err(|e| format!("下载请求失败: {e}"))?;
        if !res.status().is_success() {
            return Err(format!("下载返回 HTTP {}", res.status().as_u16()));
        }
        let total = if known_total > 0 {
            known_total
        } else {
            res.content_length().unwrap_or(0)
        };

        let mut file = File::create(part_path).map_err(|e| format!("创建临时文件失败: {e}"))?;
        {
            let mut guard = self.inner.lock().unwrap();
            if let Some(t) = guard.tasks.get_mut(id) {
                t.total_bytes = total;
                t.downloaded_bytes = 0;
                t.chunks = vec![ChunkState {
                    index: 0,
                    start: 0,
                    end: total.saturating_sub(1),
                    downloaded: 0,
                }];
            }
        }

        use std::io::Write;
        let mut stream = res.bytes_stream();
        let mut pending: u64 = 0;
        let mut last_flush = Instant::now();
        let flush_single = |pending_ref: &mut u64| {
            if *pending_ref == 0 {
                return;
            }
            let delta = *pending_ref;
            *pending_ref = 0;
            let mut guard = self.inner.lock().unwrap();
            if let Some(t) = guard.tasks.get_mut(id) {
                t.downloaded_bytes += delta;
                if let Some(c0) = t.chunks.first_mut() {
                    c0.downloaded += delta;
                }
            }
        };
        loop {
            tokio::select! {
                _ = cancel_rx.changed() => {
                    if *cancel_rx.borrow() {
                        flush_single(&mut pending);
                        return Err("已暂停".to_string());
                    }
                }
                item = stream.next() => {
                    match item {
                        None => break,
                        Some(Err(e)) => {
                            flush_single(&mut pending);
                            return Err(format!("读取流失败: {e}"));
                        }
                        Some(Ok(bytes)) => {
                            self.limiter.wait_n(bytes.len()).await;
                            file.write_all(&bytes).map_err(|e| format!("写入文件失败: {e}"))?;
                            pending += bytes.len() as u64;
                            if pending >= 256 * 1024 || last_flush.elapsed() >= Duration::from_millis(180) {
                                flush_single(&mut pending);
                                last_flush = Instant::now();
                            }
                        }
                    }
                }
            }
        }
        flush_single(&mut pending);
        Ok(())
    }
}

struct ProbeResult {
    total_bytes: u64,
    accept_ranges: bool,
    etag: String,
}

#[allow(clippy::too_many_arguments)]
async fn download_chunk(
    client: Client,
    limiter: Arc<RateLimiter>,
    inner: Arc<Mutex<Inner>>,
    file: Arc<File>,
    task_id: String,
    chunk_idx: usize,
    retry_max: usize,
    vid: String,
    def: String,
    resolver: Arc<dyn SourceResolver>,
    url_holder: Arc<tokio::sync::RwLock<String>>,
    mut cancel_rx: watch::Receiver<bool>,
) -> Result<(), String> {
    let mut last_err = String::new();

    for attempt in 0..=retry_max {
        if *cancel_rx.borrow() {
            return Err("已暂停".to_string());
        }
        if attempt > 0 {
            let backoff = Duration::from_millis((attempt as u64 * 400).min(3000));
            tokio::select! {
                _ = cancel_rx.changed() => {
                    if *cancel_rx.borrow() {
                        return Err("已暂停".to_string());
                    }
                }
                _ = tokio::time::sleep(backoff) => {}
            }
        }

        let chunk = {
            let guard = inner.lock().unwrap();
            let t = guard
                .tasks
                .get(&task_id)
                .ok_or_else(|| "任务不存在".to_string())?;
            t.chunks
                .get(chunk_idx)
                .cloned()
                .ok_or_else(|| "分片不存在".to_string())?
        };

        let cur_start = chunk.start + chunk.downloaded;
        if cur_start > chunk.end {
            return Ok(());
        }

        let url = { url_holder.read().await.clone() };
        let req = client
            .get(&url)
            .header(RANGE, format!("bytes={}-{}", cur_start, chunk.end));

        let res = match tokio::select! {
            _ = cancel_rx.changed() => {
                if *cancel_rx.borrow() {
                    return Err("已暂停".to_string());
                }
                continue;
            }
            r = req.send() => r,
        } {
            Ok(r) => r,
            Err(e) => {
                last_err = format!("分片网络错误: {e}");
                continue;
            }
        };

        let code = res.status().as_u16();
        if matches!(code, 401 | 403 | 410) {
            last_err = format!("HTTP {code}");
            if let Ok(new_res) = resolver.resolve(&vid, &def, true).await {
                if !new_res.primary_url().is_empty() {
                    *url_holder.write().await = new_res.primary_url().to_string();
                }
            }
            continue;
        }
        if code != 206 && code != 200 {
            last_err = format!("HTTP {code}");
            continue;
        }

        let expected = chunk.end - cur_start + 1;
        let mut written_in_req: u64 = 0;
        let mut pending: u64 = 0;
        let mut last_flush = Instant::now();
        let mut stream = res.bytes_stream();

        let flush_chunk = |pending_ref: &mut u64| {
            if *pending_ref == 0 {
                return;
            }
            let delta = *pending_ref;
            *pending_ref = 0;
            let mut guard = inner.lock().unwrap();
            if let Some(t) = guard.tasks.get_mut(&task_id) {
                if let Some(c) = t.chunks.get_mut(chunk_idx) {
                    c.downloaded += delta;
                }
                t.downloaded_bytes += delta;
            }
        };

        while written_in_req < expected {
            tokio::select! {
                _ = cancel_rx.changed() => {
                    if *cancel_rx.borrow() {
                        flush_chunk(&mut pending);
                        return Err("已暂停".to_string());
                    }
                }
                item = stream.next() => {
                    match item {
                        None => break,
                        Some(Err(e)) => {
                            last_err = format!("读取分片失败: {e}");
                            break;
                        }
                        Some(Ok(bytes)) => {
                            let remain = (expected - written_in_req) as usize;
                            let slice = if bytes.len() > remain { &bytes[..remain] } else { &bytes[..] };
                            limiter.wait_n(slice.len()).await;
                            let offset = cur_start + written_in_req;
                            write_all_at(&file, slice, offset).map_err(|e| format!("写入磁盘失败: {e}"))?;
                            written_in_req += slice.len() as u64;
                            pending += slice.len() as u64;
                            if pending >= 256 * 1024 || last_flush.elapsed() >= Duration::from_millis(180) {
                                flush_chunk(&mut pending);
                                last_flush = Instant::now();
                            }
                        }
                    }
                }
            }
        }
        flush_chunk(&mut pending);

        if written_in_req >= expected {
            return Ok(());
        }
    }

    if last_err.is_empty() {
        last_err = "分片下载失败".to_string();
    }
    Err(last_err)
}

#[cfg(windows)]
fn write_all_at(file: &File, mut buf: &[u8], mut offset: u64) -> std::io::Result<()> {
    use std::os::windows::fs::FileExt;
    while !buf.is_empty() {
        let n = file.seek_write(buf, offset)?;
        if n == 0 {
            return Err(std::io::Error::new(
                std::io::ErrorKind::WriteZero,
                "failed to write whole buffer",
            ));
        }
        buf = &buf[n..];
        offset += n as u64;
    }
    Ok(())
}

#[cfg(unix)]
fn write_all_at(file: &File, mut buf: &[u8], mut offset: u64) -> std::io::Result<()> {
    use std::os::unix::fs::FileExt;
    while !buf.is_empty() {
        let n = file.write_at(buf, offset)?;
        if n == 0 {
            return Err(std::io::Error::new(
                std::io::ErrorKind::WriteZero,
                "failed to write whole buffer",
            ));
        }
        buf = &buf[n..];
        offset += n as u64;
    }
    Ok(())
}

fn make_chunks(total: u64, count: usize) -> Vec<ChunkState> {
    let count = count.max(1);
    let size = total / (count as u64);
    let mut chunks = Vec::with_capacity(count);
    let mut offset = 0u64;
    for i in 0..count {
        let end = if i == count - 1 {
            total - 1
        } else {
            offset + size - 1
        };
        chunks.push(ChunkState {
            index: i,
            start: offset,
            end,
            downloaded: 0,
        });
        offset = end + 1;
    }
    chunks
}

fn parse_content_range_total(header: &str) -> Option<u64> {
    let (_, total_str) = header.rsplit_once('/')?;
    let trimmed = total_str.trim();
    if trimmed == "*" {
        return None;
    }
    trimmed.parse::<u64>().ok().filter(|&n| n > 0)
}

pub fn danmaku_cache_path(data_dir: &Path, vid: &str) -> PathBuf {
    let clean = sanitize_name(vid, "danmaku");
    data_dir.join("danmaku").join(format!("{clean}.json"))
}

fn sanitize_name(name: &str, fallback: &str) -> String {
    let mut out = String::with_capacity(name.len());
    for ch in name.trim().chars() {
        if matches!(ch, '\\' | '/' | ':' | '*' | '?' | '"' | '<' | '>' | '|') || ch.is_control() {
            out.push('_');
        } else {
            out.push(ch);
        }
    }
    let trimmed = out.trim_matches(|c: char| c == '.' || c.is_whitespace());
    if trimmed.is_empty() {
        return fallback.to_string();
    }
    trimmed.chars().take(64).collect::<String>().trim_matches(|c: char| c == '.' || c.is_whitespace()).to_string()
}

fn atomic_write(path: &Path, bytes: &[u8]) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }
    let tmp = PathBuf::from(format!("{}.tmp", path.to_string_lossy()));
    std::fs::write(&tmp, bytes).map_err(|e| e.to_string())?;
    std::fs::rename(&tmp, path).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;

    struct TestResolver {
        url: String,
    }

    impl SourceResolver for TestResolver {
        fn resolve<'a>(
            &'a self,
            _vid: &'a str,
            quality: &'a str,
            _fresh: bool,
        ) -> Pin<Box<dyn Future<Output = Result<ResolvedSource, String>> + Send + 'a>> {
            Box::pin(async move {
                Ok(ResolvedSource {
                    urls: vec![self.url.clone()],
                    definition: quality.to_string(),
                    encrypted: false,
                    spade_a: String::new(),
                })
            })
        }
    }

    async fn start_range_server(
        payload: Arc<Vec<u8>>,
        slow_delay_ms: u64,
        req_count: Arc<AtomicUsize>,
    ) -> (String, tokio::task::JoinHandle<()>) {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let url = format!("http://{addr}/video.mp4");

        let handle = tokio::spawn(async move {
            loop {
                let Ok((mut sock, _)) = listener.accept().await else {
                    break;
                };
                let data = payload.clone();
                let cnt = req_count.clone();
                tokio::spawn(async move {
                    let mut buf = vec![0u8; 4096];
                    let n = match sock.read(&mut buf).await {
                        Ok(n) if n > 0 => n,
                        _ => return,
                    };
                    let req = String::from_utf8_lossy(&buf[..n]);
                    let first_line = req.lines().next().unwrap_or("");
                    let is_head = first_line.starts_with("HEAD ");
                    let range_hdr = req
                        .lines()
                        .find(|l| l.to_ascii_lowercase().starts_with("range:"))
                        .and_then(|l| l.split_once(':'))
                        .map(|(_, v)| v.trim().to_string());

                    if is_head {
                        let resp = format!(
                            "HTTP/1.1 200 OK\r\nAccept-Ranges: bytes\r\nContent-Length: {}\r\nETag: \"v1\"\r\nConnection: close\r\n\r\n",
                            data.len()
                        );
                        let _ = sock.write_all(resp.as_bytes()).await;
                        return;
                    }

                    cnt.fetch_add(1, Ordering::Relaxed);
                    if let Some(rng) = range_hdr {
                        let spec = rng.trim_start_matches("bytes=");
                        let (s_str, e_str) = spec.split_once('-').unwrap_or((spec, ""));
                        let start: usize = s_str.parse().unwrap_or(0);
                        let end: usize = if e_str.is_empty() {
                            data.len() - 1
                        } else {
                            e_str.parse().unwrap_or(data.len() - 1)
                        };
                        let slice = &data[start..=end];
                        let hdr = format!(
                            "HTTP/1.1 206 Partial Content\r\nAccept-Ranges: bytes\r\nETag: \"v1\"\r\nContent-Range: bytes {start}-{end}/{}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                            data.len(),
                            slice.len()
                        );
                        if sock.write_all(hdr.as_bytes()).await.is_err() {
                            return;
                        }
                        if slow_delay_ms > 0 {
                            for chunk in slice.chunks(32 * 1024) {
                                if sock.write_all(chunk).await.is_err() {
                                    return;
                                }
                                tokio::time::sleep(Duration::from_millis(slow_delay_ms)).await;
                            }
                        } else {
                            let _ = sock.write_all(slice).await;
                        }
                    } else {
                        let hdr = format!(
                            "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                            data.len()
                        );
                        let _ = sock.write_all(hdr.as_bytes()).await;
                        let _ = sock.write_all(&data).await;
                    }
                });
            }
        });
        (url, handle)
    }

    fn temp_dir(name: &str) -> PathBuf {
        let p = std::env::temp_dir().join(format!("lvguo_rust_test_{}_{}", name, Uuid::new_v4().simple()));
        let _ = std::fs::create_dir_all(&p);
        p
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 4)]
    async fn test_multi_thread_download_integrity() {
        let mut payload = vec![0u8; 2 * 1024 * 1024 + 54321];
        for (i, b) in payload.iter_mut().enumerate() {
            *b = ((i * 131 + 7) % 251) as u8;
        }
        let payload = Arc::new(payload);
        let req_count = Arc::new(AtomicUsize::new(0));
        let (url, srv_handle) = start_range_server(payload.clone(), 0, req_count).await;

        let dir = temp_dir("integrity");
        let out_dir = dir.join("out");
        let resolver = Arc::new(TestResolver { url });
        let rt = tokio::runtime::Handle::current();
        let mgr = DownloadManager::with_resolver(dir.clone(), resolver, &rt);

        let mut s = mgr.settings();
        s.download_dir = out_dir.to_string_lossy().to_string();
        s.threads_per_task = 6;
        mgr.update_settings(s, &rt).unwrap();

        let task = mgr
            .add(
                AddDownloadRequest {
                    series_id: "100".into(),
                    series_title: "多线程测试剧".into(),
                    cover: String::new(),
                    vid: "888001".into(),
                    episode_index: 1,
                    episode_title: String::new(),
                    definition: "1080p".into(),
                },
                &rt,
            )
            .unwrap();

        let deadline = Instant::now() + Duration::from_secs(10);
        let mut done = None;
        while Instant::now() < deadline {
            if let Some(t) = mgr.list().into_iter().find(|x| x.id == task.id) {
                if t.status == TaskStatus::Completed {
                    done = Some(t);
                    break;
                }
                if t.status == TaskStatus::Failed {
                    panic!("download failed: {}", t.error);
                }
            }
            tokio::time::sleep(Duration::from_millis(30)).await;
        }
        let done = done.expect("download did not complete in time");
        assert_eq!(done.chunks.len(), 6);
        let got = std::fs::read(&done.save_path).unwrap();
        assert_eq!(got.len(), payload.len());
        assert_eq!(&got[..], &payload[..]);
        assert_eq!(mgr.get_local_path("888001"), Some(done.save_path.clone()));

        mgr.shutdown();
        srv_handle.abort();
        let _ = std::fs::remove_dir_all(dir);
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 4)]
    async fn test_pause_restart_resume() {
        let mut payload = vec![0u8; 1600 * 1024];
        for (i, b) in payload.iter_mut().enumerate() {
            *b = ((i * 73 + 19) % 256) as u8;
        }
        let payload = Arc::new(payload);
        let req_count = Arc::new(AtomicUsize::new(0));
        let (url, srv_handle) = start_range_server(payload.clone(), 20, req_count).await;

        let dir = temp_dir("resume");
        let out_dir = dir.join("out");
        let resolver = Arc::new(TestResolver { url });
        let rt = tokio::runtime::Handle::current();

        let task_id = {
            let mgr1 = DownloadManager::with_resolver(dir.clone(), resolver.clone(), &rt);
            let mut s = mgr1.settings();
            s.download_dir = out_dir.to_string_lossy().to_string();
            s.threads_per_task = 4;
            s.auto_resume_on_start = false;
            mgr1.update_settings(s, &rt).unwrap();

            let task = mgr1
                .add(
                    AddDownloadRequest {
                        series_id: "200".into(),
                        series_title: "断点续传测试".into(),
                        cover: String::new(),
                        vid: "888002".into(),
                        episode_index: 2,
                        episode_title: String::new(),
                        definition: "720p".into(),
                    },
                    &rt,
                )
                .unwrap();

            let deadline = Instant::now() + Duration::from_secs(5);
            while Instant::now() < deadline {
                if let Some(t) = mgr1.list().into_iter().find(|x| x.id == task.id) {
                    if t.downloaded_bytes >= 96 * 1024 {
                        break;
                    }
                }
                tokio::time::sleep(Duration::from_millis(20)).await;
            }
            mgr1.pause(&task.id).unwrap();
            tokio::time::sleep(Duration::from_millis(80)).await;
            let snap = mgr1.list().into_iter().find(|x| x.id == task.id).unwrap();
            assert!(snap.downloaded_bytes > 0 && snap.downloaded_bytes < payload.len() as u64);
            mgr1.shutdown();
            task.id
        };

        let mgr2 = DownloadManager::with_resolver(dir.clone(), resolver, &rt);
        let restored = mgr2.list().into_iter().find(|x| x.id == task_id).unwrap();
        assert_eq!(restored.status, TaskStatus::Paused);
        assert!(restored.downloaded_bytes > 0);

        mgr2.resume(&task_id, &rt).unwrap();
        let deadline = Instant::now() + Duration::from_secs(15);
        let mut done = None;
        while Instant::now() < deadline {
            if let Some(t) = mgr2.list().into_iter().find(|x| x.id == task_id) {
                if t.status == TaskStatus::Completed {
                    done = Some(t);
                    break;
                }
                if t.status == TaskStatus::Failed {
                    panic!("resumed download failed: {}", t.error);
                }
            }
            tokio::time::sleep(Duration::from_millis(30)).await;
        }
        let done = done.expect("resumed download did not complete");
        let got = std::fs::read(&done.save_path).unwrap();
        assert_eq!(got.len(), payload.len());
        assert_eq!(&got[..], &payload[..]);

        mgr2.shutdown();
        srv_handle.abort();
        let _ = std::fs::remove_dir_all(dir);
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn test_rate_limiter_and_settings_persistence() {
        let rl = RateLimiter::new(64); // 64 KB/s
        rl.wait_n(64 * 1024).await; // drain initial burst
        let start = Instant::now();
        rl.wait_n(16 * 1024).await;
        assert!(start.elapsed() >= Duration::from_millis(150));

        let dir = temp_dir("settings");
        let rt = tokio::runtime::Handle::current();
        let resolver = Arc::new(TestResolver {
            url: "http://127.0.0.1:1/x.mp4".into(),
        });
        {
            let m1 = DownloadManager::with_resolver(dir.clone(), resolver.clone(), &rt);
            let mut s = m1.settings();
            s.max_concurrent = 4;
            s.threads_per_task = 6;
            s.speed_limit_kbps = 2048;
            s.preferred_quality = "720p".into();
            m1.update_settings(s, &rt).unwrap();
            m1.shutdown();
        }
        let m2 = DownloadManager::with_resolver(dir.clone(), resolver, &rt);
        let got = m2.settings();
        assert_eq!(got.max_concurrent, 4);
        assert_eq!(got.threads_per_task, 6);
        assert_eq!(got.speed_limit_kbps, 2048);
        assert_eq!(got.preferred_quality, "720p");
        m2.shutdown();
        let _ = std::fs::remove_dir_all(dir);
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn test_add_batch_single_save_and_dedup() {
        let dir = temp_dir("batch_dedup");
        let rt = tokio::runtime::Handle::current();
        let resolver = Arc::new(TestResolver {
            url: "http://127.0.0.1:1/x.mp4".into(),
        });
        let mgr = DownloadManager::with_resolver_mode(dir.clone(), resolver, &rt, true);
        let mut s = mgr.settings();
        s.max_concurrent = 1;
        mgr.update_settings(s, &rt).unwrap();

        let mut reqs = Vec::new();
        for i in 1..=50 {
            reqs.push(AddDownloadRequest {
                series_id: "300".into(),
                series_title: "批量测试剧".into(),
                cover: String::new(),
                vid: format!("vid_{i}"),
                episode_index: i,
                episode_title: format!("第{i}集"),
                definition: "720p".into(),
            });
        }
        // Include duplicates
        reqs.push(reqs[0].clone());
        reqs.push(reqs[1].clone());

        let added = mgr.add_batch(reqs, &rt).unwrap();
        assert_eq!(added.len(), 52);
        let list = mgr.list();
        assert_eq!(list.len(), 50);
        assert_eq!(list[0].episode_index, 1);
        assert_eq!(list[0].definition, "720p");

        let slim = mgr.list_slim();
        assert_eq!(slim.len(), 50);
        assert!(slim[0].get("chunks").is_none());
        assert!(slim[0].get("spadeA").is_none());
        assert!(slim[0].get("seriesTitle").is_some());

        mgr.shutdown();
        let _ = std::fs::remove_dir_all(dir);
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn test_inbox_cross_process_handoff() {
        let dir = temp_dir("inbox_handoff");
        let rt = tokio::runtime::Handle::current();
        let resolver = Arc::new(TestResolver {
            url: "http://127.0.0.1:1/x.mp4".into(),
        });

        // Simulate --player process (worker_enabled = false) queueing 20 episodes
        let player_mgr = DownloadManager::with_resolver_mode(dir.clone(), resolver.clone(), &rt, false);
        let mut reqs = Vec::new();
        for i in 1..=20 {
            reqs.push(AddDownloadRequest {
                series_id: "400".into(),
                series_title: "跨进程传递剧".into(),
                cover: String::new(),
                vid: format!("p_vid_{i}"),
                episode_index: i,
                episode_title: String::new(),
                definition: "1080p".into(),
            });
        }
        let stubs = player_mgr.add_batch(reqs, &rt).unwrap();
        assert_eq!(stubs.len(), 20);
        player_mgr.shutdown();

        // Main process (worker_enabled = true) ingests download_inbox automatically
        let main_mgr = DownloadManager::with_resolver_mode(dir.clone(), resolver, &rt, true);
        let list = main_mgr.list();
        assert_eq!(list.len(), 20);
        assert_eq!(list[0].series_title, "跨进程传递剧");
        assert_eq!(list[19].episode_index, 20);

        main_mgr.shutdown();
        let _ = std::fs::remove_dir_all(dir);
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn test_delete_batch_200_queued_fast() {
        let dir = temp_dir("del_batch_fast");
        let rt = tokio::runtime::Handle::current();
        let resolver = Arc::new(TestResolver {
            url: "http://127.0.0.1:1/x.mp4".into(),
        });
        let mgr = DownloadManager::with_resolver_mode(dir.clone(), resolver, &rt, true);
        let mut s = mgr.settings();
        s.max_concurrent = 1;
        mgr.update_settings(s, &rt).unwrap();

        let reqs: Vec<AddDownloadRequest> = (1..=200)
            .map(|i| AddDownloadRequest {
                series_id: "500".into(),
                series_title: "极速删除测试".into(),
                cover: String::new(),
                vid: format!("fast_vid_{i}"),
                episode_index: i,
                episode_title: String::new(),
                definition: "1080p".into(),
            })
            .collect();
        let added = mgr.add_batch(reqs, &rt).unwrap();
        let ids: Vec<String> = added.iter().map(|t| t.id.clone()).collect();

        let start = Instant::now();
        let deleted = mgr.delete_batch(&ids, true, &rt).unwrap();
        let elapsed = start.elapsed();
        assert_eq!(deleted, 200);
        assert!(mgr.list().is_empty());
        assert!(
            elapsed < Duration::from_millis(200),
            "delete_batch took too long: {elapsed:?}"
        );

        mgr.shutdown();
        let _ = std::fs::remove_dir_all(dir);
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn test_delete_batch_whole_series_removes_folder() {
        let dir = temp_dir("del_batch_folder");
        let out_dir = dir.join("downloads");
        let rt = tokio::runtime::Handle::current();
        let resolver = Arc::new(TestResolver {
            url: "http://127.0.0.1:1/x.mp4".into(),
        });
        let mgr = DownloadManager::with_resolver_mode(dir.clone(), resolver, &rt, true);
        let mut s = mgr.settings();
        s.download_dir = out_dir.to_string_lossy().to_string();
        s.create_series_subdir = true;
        s.max_concurrent = 1;
        mgr.update_settings(s, &rt).unwrap();

        let reqs: Vec<AddDownloadRequest> = (1..=30)
            .map(|i| AddDownloadRequest {
                series_id: "600".into(),
                series_title: "整部目录删除".into(),
                cover: String::new(),
                vid: format!("dir_vid_{i}"),
                episode_index: i,
                episode_title: String::new(),
                definition: "1080p".into(),
            })
            .collect();
        let added = mgr.add_batch(reqs, &rt).unwrap();
        let series_dir = Path::new(&added[0].save_path).parent().unwrap().to_path_buf();
        std::fs::create_dir_all(&series_dir).unwrap();
        for t in &added {
            std::fs::write(&t.save_path, b"dummy").unwrap();
        }
        assert!(series_dir.is_dir());

        let ids: Vec<String> = added.iter().map(|t| t.id.clone()).collect();
        let start = Instant::now();
        let deleted = mgr.delete_batch(&ids, true, &rt).unwrap();
        assert!(start.elapsed() < Duration::from_millis(200));
        assert_eq!(deleted, 30);
        assert!(mgr.list().is_empty());

        let deadline = Instant::now() + Duration::from_secs(2);
        while series_dir.exists() && Instant::now() < deadline {
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
        assert!(!series_dir.exists(), "series directory was not deleted via remove_dir_all");

        mgr.shutdown();
        let _ = std::fs::remove_dir_all(dir);
    }
}
