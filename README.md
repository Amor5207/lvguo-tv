<div align="center">

<img src="docs/assets/icon.png" width="112" height="112" alt="绿果">

# 绿果TV（已停止维护 · 请迁移至新仓库）

[![Archived](https://img.shields.io/badge/status-unmaintained-ef4444?style=flat-square)](https://github.com/Amor5207/lvguo-core)
[![New Repository](https://img.shields.io/badge/new%20repo-Amor5207%2Flvguo--core-16a34a?style=flat-square)](https://github.com/Amor5207/lvguo-core)

> **重要迁移公告**：本仓库（`lvguo-tv`）已正式停止维护与更新。  
> 绿果现已完成 **3.0 重大架构升级**，所有开发与版本发布已全面迁移至全新仓库：
>
> 👉 **[https://github.com/Amor5207/lvguo-core](https://github.com/Amor5207/lvguo-core)**

</div>

---

## 为什么要升级并迁移至 `lvguo-core`？

1. **全新 Core 与极薄宿主解耦架构**：将请求签名、剧集解析、CENC 流式解密、账号同步与多线程下载全部收敛至统一的 **`lvguo-core`（1.0.0）** 原生动态库。
2. **支持单文件热更新**：后续协议适配与修复无需整包重新安装，Android TV / PC / Web 各端均可直接通过单个核心动态库文件热替换完成升级。
3. **全面取消邀请码与强制登录限制**：新版本默认打开即可浏览、搜索与 1080p 播放；官方 3.0.0 通用发行包（Android 64/32 位通用 APK、Windows 统一安装包、macOS Universal、Linux / Web）均已在新仓库发布。
4. **开放 SDK，支持 `Core + AI` 自定义界面**：新仓库提供完整的 C-ABI（`lvguo_core.h`）、HTTP RPC（`POST /api/call`）与 Android JNI 文档，欢迎借助 AI 构建完全符合个人使用习惯的上层客户端。

---

## 立即前往新仓库

- **新项目主页与文档**：[https://github.com/Amor5207/lvguo-core](https://github.com/Amor5207/lvguo-core)
- **下载最新 3.0.0 全端通用安装包**：[https://github.com/Amor5207/lvguo-core/releases](https://github.com/Amor5207/lvguo-core/releases)

> 本旧仓库代码仅作历史归档留存，不再处理新的 Issue、PR 或发布新版本，强烈建议所有用户尽快升级至 3.0.0。
