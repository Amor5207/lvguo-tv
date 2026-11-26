# 绿果PC（Lvguo PC）

基于 **Flutter Desktop + Rust (`lvguo_core`) + libmpv (`media_kit`)** 构建的独立桌面端，主要面向 **Windows** 平台维护（同时提供 macOS / Linux 自动化构建流程）。

## 架构概览

- **UI 层 (`lib/`)**：桌面端主壳、多窗口独立播放器（`--player`）、弹幕、下载管理与设置。
- **核心层 (`rust/`)**：通过 FFI 与 Dart 隔离池通信，负责请求签名、设备身份、剧集解析、CENC 本地流式解密、多线程 Range 下载与自动更新管理。
- **视频画质增强 (`assets/shaders/`)**：
  - `off`：原画质（Spline36 标准缩放）
  - `cas`：AMD FidelityFX CAS 对比度自适应锐化（`//!HOOK MAIN`）
  - `fsr`：双 Pass 边缘重建 + RCAS 超分辨率增强（`//!HOOK MAIN` + `ewa_lanczossharp`）

## 构建与发布

### GitHub Actions 自动构建

推送 `vX.Y.Z` 标签（或手动触发 `.github/workflows/desktop-release.yml`）将自动并行构建桌面端安装包并挂载至 GitHub Release（通常耗时约 **5–8 分钟**）：

- Windows：`LvguoPC-Setup-<ver>-win-x64.exe`（Windows ARM64 可直接兼容运行）
- macOS：`LvguoPC-<ver>-macOS-universal.dmg`（通用架构，同时支持 Apple Silicon 与 Intel）
- Linux：`LvguoPC-<ver>-linux-x64.deb`、`LvguoPC-<ver>-linux-arm64.deb`

安装包发布在 [GitHub Releases](https://github.com/Amor5207/lvguo-tv/releases/latest)。

### 本地构建

版本号统一从 `pubspec.yaml` 读取（发版前同步更新 `pubspec.yaml`、`rust/src/hotupdate.rs`、`lib/core/update_manager.dart`、`test/ui_audit_test.dart`）：

```powershell
# Windows (PowerShell)
.\scripts\build_windows.ps1 -Arch x64
```

```bash
# macOS
./scripts/build_macos.sh --arch universal

# Linux
./scripts/build_linux.sh --arch x64
```
