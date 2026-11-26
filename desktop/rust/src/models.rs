use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum TaskStatus {
    Queued,
    Downloading,
    Paused,
    Completed,
    Failed,
    Canceled,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ChunkState {
    pub index: usize,
    pub start: u64,
    pub end: u64,
    pub downloaded: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DownloadTask {
    pub id: String,
    pub series_id: String,
    pub series_title: String,
    #[serde(default)]
    pub cover: String,
    pub vid: String,
    pub episode_index: i32,
    #[serde(default)]
    pub episode_title: String,
    pub definition: String,
    pub total_bytes: u64,
    pub downloaded_bytes: u64,
    pub speed_bytes_per_sec: u64,
    #[serde(default)]
    pub chunks: Vec<ChunkState>,
    pub status: TaskStatus,
    pub save_path: String,
    #[serde(default)]
    pub part_path: String,
    #[serde(default)]
    pub error: String,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
    #[serde(default)]
    pub etag: String,
    #[serde(default)]
    pub encrypted: bool,
    #[serde(default)]
    pub spade_a: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DownloadSettings {
    #[serde(default)]
    pub download_dir: String,
    #[serde(default = "default_max_concurrent")]
    pub max_concurrent: usize,
    #[serde(default = "default_threads_per_task")]
    pub threads_per_task: usize,
    #[serde(default)]
    pub speed_limit_kbps: u32,
    #[serde(default = "default_quality")]
    pub preferred_quality: String,
    #[serde(default = "default_retry_count")]
    pub auto_retry_count: usize,
    #[serde(default = "default_true")]
    pub create_series_subdir: bool,
    #[serde(default = "default_true")]
    pub notify_on_complete: bool,
    #[serde(default = "default_true")]
    pub auto_resume_on_start: bool,
}

fn default_max_concurrent() -> usize {
    2
}
fn default_threads_per_task() -> usize {
    4
}
fn default_quality() -> String {
    "1080p".to_string()
}
fn default_retry_count() -> usize {
    3
}
fn default_true() -> bool {
    true
}

impl DownloadSettings {
    pub fn default_for(data_dir: &std::path::Path) -> Self {
        let dl_dir = dirs::download_dir()
            .map(|p| p.join("LvguoPC"))
            .unwrap_or_else(|| PathBuf::from(data_dir).join("downloads"));
        Self {
            download_dir: dl_dir.to_string_lossy().to_string(),
            max_concurrent: 2,
            threads_per_task: 4,
            speed_limit_kbps: 0,
            preferred_quality: "1080p".to_string(),
            auto_retry_count: 3,
            create_series_subdir: true,
            notify_on_complete: true,
            auto_resume_on_start: true,
        }
    }

    pub fn normalize(&mut self, data_dir: &std::path::Path) {
        let def = Self::default_for(data_dir);
        if self.download_dir.trim().is_empty() {
            self.download_dir = def.download_dir;
        }
        self.max_concurrent = self.max_concurrent.clamp(1, 4);
        self.threads_per_task = self.threads_per_task.clamp(1, 8);
        if self.preferred_quality.trim().is_empty() {
            self.preferred_quality = def.preferred_quality;
        }
        self.auto_retry_count = self.auto_retry_count.min(10);
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AddDownloadRequest {
    #[serde(default)]
    pub series_id: String,
    #[serde(default)]
    pub series_title: String,
    #[serde(default)]
    pub cover: String,
    pub vid: String,
    #[serde(default = "default_ep_index")]
    pub episode_index: i32,
    #[serde(default)]
    pub episode_title: String,
    #[serde(default, alias = "quality")]
    pub definition: String,
}

fn default_ep_index() -> i32 {
    1
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DownloadActionRequest {
    pub action: String,
    #[serde(default)]
    pub id: String,
    #[serde(default)]
    pub remove_file: bool,
}
