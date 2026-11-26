import 'package:flutter/material.dart';

import '../core/theme.dart';
import '../core/update_manager.dart';

Future<void> showUpdateFoundDialog(
  BuildContext context, {
  required AppUpdateInfo info,
  required Future<void> Function() onIgnore,
  required Future<void> Function() onOpenReleasePage,
  required Future<void> Function() onStartDownload,
}) {
  return showDialog<void>(
    context: context,
    barrierDismissible: true,
    builder: (ctx) {
      final p = LvguoPalette.of(ctx);
      final metaText = info.assetSize > 0
          ? 'v${info.latestVersion} · ${(info.assetSize / 1048576.0).toStringAsFixed(1)} MB'
          : 'v${info.latestVersion} · GitHub Releases';
      final hasDirect = info.downloadUrl.isNotEmpty;

      return Dialog(
        backgroundColor: p.surface,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(18),
          side: BorderSide(color: p.sep),
        ),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 460),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(26, 24, 26, 22),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '发现新版本 v${info.latestVersion}',
                  style: TextStyle(
                    fontSize: 19,
                    fontWeight: FontWeight.w700,
                    color: p.text,
                  ),
                ),
                const SizedBox(height: 6),
                Text(
                  metaText,
                  style: TextStyle(fontSize: 13, color: p.text3),
                ),
                if (info.notes.isNotEmpty) ...[
                  const SizedBox(height: 14),
                  Container(
                    width: double.infinity,
                    constraints: const BoxConstraints(maxHeight: 240),
                    padding: const EdgeInsets.all(14),
                    decoration: BoxDecoration(
                      color: p.fill,
                      borderRadius: BorderRadius.circular(12),
                      border: Border.all(color: p.sep),
                    ),
                    child: SingleChildScrollView(
                      child: SelectableText(
                        info.notes,
                        style: TextStyle(
                          fontSize: 13.5,
                          height: 1.55,
                          color: p.text2,
                        ),
                      ),
                    ),
                  ),
                ] else ...[
                  const SizedBox(height: 12),
                  Text(
                    '当前版本 v${info.currentVersion}，可前往 GitHub Release 下载最新版本。',
                    style: TextStyle(fontSize: 13.5, height: 1.5, color: p.text2),
                  ),
                ],
                const SizedBox(height: 22),
                Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    TextButton(
                      onPressed: () async {
                        await onIgnore();
                        if (ctx.mounted) Navigator.of(ctx).pop();
                      },
                      style: TextButton.styleFrom(
                        foregroundColor: p.text3,
                        shape: const StadiumBorder(),
                      ),
                      child: const Text('忽略本次更新'),
                    ),
                    if (hasDirect) ...[
                      const SizedBox(width: 8),
                      OutlinedButton(
                        onPressed: () async {
                          Navigator.of(ctx).pop();
                          await onOpenReleasePage();
                        },
                        style: OutlinedButton.styleFrom(
                          foregroundColor: p.text2,
                          side: BorderSide(color: p.sep),
                          shape: const StadiumBorder(),
                        ),
                        child: const Text('发布页'),
                      ),
                    ],
                    const SizedBox(width: 10),
                    FilledButton(
                      onPressed: () async {
                        Navigator.of(ctx).pop();
                        await onStartDownload();
                      },
                      style: FilledButton.styleFrom(
                        backgroundColor: p.primary,
                        foregroundColor: p.primaryOn,
                        shape: const StadiumBorder(),
                        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
                      ),
                      child: Text(hasDirect ? '立即更新' : '前往下载'),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      );
    },
  );
}

Future<void> showUpdateProgressDialog(
  BuildContext context, {
  required AppUpdateInfo info,
  required VoidCallback onCancel,
  required VoidCallback onBackground,
}) {
  return showDialog<void>(
    context: context,
    barrierDismissible: true,
    builder: (ctx) {
      return ListenableBuilder(
        listenable: AppUpdateManager.instance,
        builder: (ctx, _) {
          final p = LvguoPalette.of(ctx);
          final mgr = AppUpdateManager.instance;
          final pct = mgr.percent;
          final doneMb = (mgr.downloadedBytes / 1048576.0).toStringAsFixed(1);
          final totalMb = mgr.totalBytes > 0
              ? (mgr.totalBytes / 1048576.0).toStringAsFixed(1)
              : '--';
          final progress = mgr.totalBytes > 0
              ? (mgr.downloadedBytes / mgr.totalBytes).clamp(0.0, 1.0)
              : null;

          return Dialog(
            backgroundColor: p.surface,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(18),
              side: BorderSide(color: p.sep),
            ),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 420),
              child: Padding(
                padding: const EdgeInsets.fromLTRB(26, 24, 26, 22),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      '正在下载 v${info.latestVersion}',
                      style: TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.w700,
                        color: p.text,
                      ),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      '$pct% · $doneMb / $totalMb MB',
                      style: TextStyle(fontSize: 13, color: p.text3),
                    ),
                    const SizedBox(height: 14),
                    ClipRRect(
                      borderRadius: BorderRadius.circular(999),
                      child: LinearProgressIndicator(
                        value: progress,
                        minHeight: 7,
                        backgroundColor: p.fill,
                        valueColor: AlwaysStoppedAnimation<Color>(p.primary),
                      ),
                    ),
                    const SizedBox(height: 20),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.end,
                      children: [
                        TextButton(
                          onPressed: () {
                            onCancel();
                            Navigator.of(ctx).pop();
                          },
                          style: TextButton.styleFrom(
                            foregroundColor: p.text3,
                            shape: const StadiumBorder(),
                          ),
                          child: const Text('取消'),
                        ),
                        const SizedBox(width: 10),
                        FilledButton(
                          onPressed: () {
                            Navigator.of(ctx).pop();
                            onBackground();
                          },
                          style: FilledButton.styleFrom(
                            backgroundColor: p.primary,
                            foregroundColor: p.primaryOn,
                            shape: const StadiumBorder(),
                          ),
                          child: const Text('后台更新'),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
          );
        },
      );
    },
  );
}
