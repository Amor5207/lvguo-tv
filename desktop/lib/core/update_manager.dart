import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';

import '../widgets/update_dialog.dart';
import 'rust_bridge.dart';
import 'settings_store.dart';

enum AppUpdateState { idle, available, downloading, ready }

class AppUpdateInfo {
  final String currentVersion;
  final String latestVersion;
  final String notes;
  final String releaseUrl;
  final String downloadUrl;
  final String directDownloadUrl;
  final String assetName;
  final int assetSize;

  const AppUpdateInfo({
    required this.currentVersion,
    required this.latestVersion,
    required this.notes,
    required this.releaseUrl,
    required this.downloadUrl,
    this.directDownloadUrl = '',
    required this.assetName,
    required this.assetSize,
  });

  factory AppUpdateInfo.fromJson(Map<String, dynamic> j) {
    return AppUpdateInfo(
      currentVersion: (j['currentVersion'] ?? '2.1.0').toString(),
      latestVersion: (j['latestVersion'] ?? '').toString(),
      notes: (j['notes'] ?? '').toString(),
      releaseUrl: (j['releaseUrl'] ?? 'https://github.com/Amor5207/lvguo-tv/releases/latest').toString(),
      downloadUrl: (j['downloadUrl'] ?? '').toString(),
      directDownloadUrl: (j['directDownloadUrl'] ?? '').toString(),
      assetName: (j['assetName'] ?? '').toString(),
      assetSize: (j['assetSize'] as num?)?.toInt() ?? 0,
    );
  }
}

class AppUpdateManager extends ChangeNotifier {
  AppUpdateManager._();
  static final AppUpdateManager instance = AppUpdateManager._();

  static const String githubRepoUrl = 'https://github.com/Amor5207/lvguo-tv';
  static const String currentVersion = '2.1.0';

  AppUpdateState state = AppUpdateState.idle;
  AppUpdateInfo? info;
  String? readyFilePath;
  int downloadedBytes = 0;
  int totalBytes = 0;

  bool _checking = false;
  bool _startupChecked = false;
  bool _cancelRequested = false;
  bool _progressDialogOpen = false;
  HttpClient? _activeClient;

  bool get checking => _checking;

  int get percent {
    if (totalBytes <= 0) return 0;
    final p = (downloadedBytes * 100 ~/ totalBytes);
    return p.clamp(0, 100);
  }

  String get statusLabel {
    final i = info;
    switch (state) {
      case AppUpdateState.available:
        return i != null ? '发现 v${i.latestVersion}' : '发现新版本';
      case AppUpdateState.downloading:
        return '下载中 $percent%';
      case AppUpdateState.ready:
        return i != null ? '可安装 v${i.latestVersion}' : '可安装';
      case AppUpdateState.idle:
        return '已是最新';
    }
  }

  Future<bool> openUrl(String url) async {
    final trimmed = url.trim();
    if (trimmed.isEmpty) return false;
    try {
      if (Platform.isWindows) {
        await Process.start('cmd', ['/c', 'start', '', trimmed]);
      } else if (Platform.isMacOS) {
        await Process.start('open', [trimmed]);
      } else {
        await Process.start('xdg-open', [trimmed]);
      }
      return true;
    } catch (_) {
      return false;
    }
  }

  /// Silent background check triggered once after MainShell mounts.
  /// Never throws or shows error toast if offline/rate-limited.
  Future<void> checkOnStartup(BuildContext context) async {
    if (_startupChecked || _checking || state == AppUpdateState.downloading) {
      return;
    }
    _startupChecked = true;
    _checking = true;
    notifyListeners();
    try {
      final res = await RustBridge.instance.callMap('app.update.check');
      _checking = false;
      final ok = res['ok'] == true;
      final hasUpdate = res['hasUpdate'] == true;
      if (ok && hasUpdate) {
        final parsed = AppUpdateInfo.fromJson(res);
        info = parsed;
        if (parsed.assetName.isNotEmpty) {
          final existing = await _existingReadyFile(parsed);
          if (existing != null) {
            readyFilePath = existing;
            state = AppUpdateState.ready;
          } else {
            state = AppUpdateState.available;
          }
        } else {
          state = AppUpdateState.available;
        }
        notifyListeners();
        if (!context.mounted) return;
        if (parsed.latestVersion != SettingsStore.instance.ignoredUpdateVersion) {
          await showFoundDialog(context);
        }
      } else {
        if (state != AppUpdateState.ready && state != AppUpdateState.downloading) {
          info = null;
          state = AppUpdateState.idle;
        }
        notifyListeners();
      }
    } catch (_) {
      _checking = false;
      notifyListeners();
    }
  }

  Future<void> onManualCheck(
    BuildContext context, {
    required ValueChanged<String> onToast,
  }) async {
    switch (state) {
      case AppUpdateState.ready:
        await launchInstaller(context, onToast);
        return;
      case AppUpdateState.downloading:
        await showProgressDialog(context, onToast: onToast);
        return;
      case AppUpdateState.available:
        if (info != null) {
          await showFoundDialog(context, onToast: onToast);
          return;
        }
        break;
      case AppUpdateState.idle:
        break;
    }

    if (_checking) {
      onToast('正在检查…');
      return;
    }

    _checking = true;
    notifyListeners();
    onToast('正在检查更新…');
    try {
      final res = await RustBridge.instance.callMap('app.update.check');
      _checking = false;
      final ok = res['ok'] == true;
      final hasUpdate = res['hasUpdate'] == true;
      if (ok && hasUpdate) {
        final parsed = AppUpdateInfo.fromJson(res);
        info = parsed;
        if (parsed.assetName.isNotEmpty) {
          final existing = await _existingReadyFile(parsed);
          if (existing != null) {
            readyFilePath = existing;
            state = AppUpdateState.ready;
            notifyListeners();
            if (context.mounted) {
              await launchInstaller(context, onToast);
            }
            return;
          }
        }
        state = AppUpdateState.available;
        notifyListeners();
        if (context.mounted) {
          await showFoundDialog(context, onToast: onToast);
        }
      } else if (ok) {
        info = null;
        state = AppUpdateState.idle;
        notifyListeners();
        onToast('已是最新版本');
      } else {
        notifyListeners();
        onToast('检查失败，请稍后重试');
      }
    } catch (_) {
      _checking = false;
      notifyListeners();
      onToast('检查失败，请稍后重试');
    }
  }

  Future<String?> _existingReadyFile(AppUpdateInfo parsed) async {
    try {
      final dir = Directory('${Directory.systemTemp.path}${Platform.pathSeparator}lvguo_updates');
      final file = File('${dir.path}${Platform.pathSeparator}${parsed.assetName}');
      if (await file.exists()) {
        final len = await file.length();
        if (len > 0 && (parsed.assetSize <= 0 || len == parsed.assetSize)) {
          return file.path;
        }
      }
    } catch (_) {}
    return null;
  }

  Future<void> showFoundDialog(
    BuildContext context, {
    ValueChanged<String>? onToast,
  }) async {
    final i = info;
    if (i == null || !context.mounted) return;
    await showUpdateFoundDialog(
      context,
      info: i,
      onIgnore: () async {
        await SettingsStore.instance.setIgnoredUpdateVersion(i.latestVersion);
      },
      onOpenReleasePage: () async {
        await openUrl(i.releaseUrl);
      },
      onStartDownload: () async {
        if (i.downloadUrl.isEmpty) {
          await openUrl(i.releaseUrl);
          return;
        }
        unawaited(startDownload(context, i, onToast: onToast));
        if (context.mounted) {
          await showProgressDialog(context, onToast: onToast);
        }
      },
    );
  }

  Future<void> showProgressDialog(
    BuildContext context, {
    ValueChanged<String>? onToast,
  }) async {
    final i = info;
    if (i == null || !context.mounted || _progressDialogOpen) return;
    _progressDialogOpen = true;
    try {
      await showUpdateProgressDialog(
        context,
        info: i,
        onCancel: cancelDownload,
        onBackground: () {
          onToast?.call('已转入后台下载');
        },
      );
    } finally {
      _progressDialogOpen = false;
    }
  }

  Future<void> startDownload(
    BuildContext context,
    AppUpdateInfo target, {
    ValueChanged<String>? onToast,
  }) async {
    if (state == AppUpdateState.downloading) return;
    state = AppUpdateState.downloading;
    downloadedBytes = 0;
    totalBytes = target.assetSize;
    _cancelRequested = false;
    notifyListeners();

    File? partFile;
    try {
      final dir = Directory('${Directory.systemTemp.path}${Platform.pathSeparator}lvguo_updates');
      await dir.create(recursive: true);
      final name = target.assetName.isNotEmpty
          ? target.assetName
          : 'LvguoPC-Update-${target.latestVersion}';
      final finalPath = '${dir.path}${Platform.pathSeparator}$name';
      partFile = File('$finalPath.part');
      if (await partFile.exists()) {
        await partFile.delete();
      }

      final urls = <String>[
        if (target.downloadUrl.isNotEmpty) target.downloadUrl,
        if (target.directDownloadUrl.isNotEmpty &&
            target.directDownloadUrl != target.downloadUrl)
          target.directDownloadUrl,
      ];

      Object? lastErr;
      bool downloaded = false;
      for (final dlUrl in urls) {
        if (_cancelRequested) {
          throw const _DownloadCancelled();
        }
        downloadedBytes = 0;
        notifyListeners();
        if (await partFile.exists()) {
          await partFile.delete();
        }

        final client = HttpClient();
        _activeClient = client;
        client.connectionTimeout = const Duration(seconds: 15);
        try {
          final req = await client.getUrl(Uri.parse(dlUrl));
          req.headers.set(HttpHeaders.userAgentHeader, 'LvguoPC/$currentVersion');
          final resp = await req.close();
          if (resp.statusCode < 200 || resp.statusCode >= 300) {
            throw HttpException('HTTP ${resp.statusCode}');
          }
          if (resp.contentLength > 0) {
            totalBytes = resp.contentLength;
          }

          final sink = partFile.openWrite();
          try {
            await for (final chunk in resp) {
              if (_cancelRequested) {
                throw const _DownloadCancelled();
              }
              sink.add(chunk);
              downloadedBytes += chunk.length;
              notifyListeners();
            }
            await sink.flush();
          } finally {
            await sink.close();
          }

          client.close(force: false);
          _activeClient = null;
          downloaded = true;
          break;
        } on _DownloadCancelled {
          client.close(force: true);
          _activeClient = null;
          rethrow;
        } catch (e) {
          client.close(force: true);
          _activeClient = null;
          lastErr = e;
        }
      }
      if (!downloaded) {
        throw lastErr ?? const HttpException('No download URL');
      }

      final out = File(finalPath);
      if (await out.exists()) {
        await out.delete();
      }
      await partFile.rename(finalPath);

      readyFilePath = finalPath;
      state = AppUpdateState.ready;
      notifyListeners();

      if (_progressDialogOpen && context.mounted) {
        Navigator.of(context, rootNavigator: true).pop();
      }
      if (context.mounted) {
        await launchInstaller(context, onToast);
      }
    } on _DownloadCancelled {
      _activeClient = null;
      try {
        if (partFile != null && await partFile.exists()) {
          await partFile.delete();
        }
      } catch (_) {}
      state = AppUpdateState.available;
      downloadedBytes = 0;
      notifyListeners();
    } catch (_) {
      _activeClient = null;
      try {
        if (partFile != null && await partFile.exists()) {
          await partFile.delete();
        }
      } catch (_) {}
      state = AppUpdateState.available;
      downloadedBytes = 0;
      notifyListeners();
      if (_progressDialogOpen && context.mounted) {
        Navigator.of(context, rootNavigator: true).pop();
      }
      onToast?.call('下载失败，可前往 GitHub 发布页下载');
    }
  }

  void cancelDownload() {
    _cancelRequested = true;
    try {
      _activeClient?.close(force: true);
    } catch (_) {}
    _activeClient = null;
  }

  Future<void> launchInstaller(
    BuildContext context,
    ValueChanged<String>? onToast,
  ) async {
    final path = readyFilePath;
    if (path == null || !await File(path).exists()) {
      state = AppUpdateState.available;
      notifyListeners();
      if (context.mounted) {
        await showFoundDialog(context, onToast: onToast);
      }
      return;
    }

    try {
      if (Platform.isWindows) {
        await Process.start(path, const [], mode: ProcessStartMode.detached);
        onToast?.call('正在启动安装程序…');
      } else if (Platform.isMacOS) {
        await Process.start('open', [path], mode: ProcessStartMode.detached);
        onToast?.call('已打开安装包');
      } else {
        await Process.start('xdg-open', [File(path).parent.path], mode: ProcessStartMode.detached);
        onToast?.call('已打开安装包所在目录');
      }
    } catch (_) {
      onToast?.call('无法直接启动安装包，请前往 GitHub 发布页下载');
      final i = info;
      if (i != null) {
        await openUrl(i.releaseUrl);
      }
    }
  }
}

class _DownloadCancelled implements Exception {
  const _DownloadCancelled();
}
