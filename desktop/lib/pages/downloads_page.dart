import 'dart:async';

import 'package:flutter/material.dart';

import '../core/rust_bridge.dart';
import '../core/theme.dart';
import '../models/models.dart';
import '../widgets/web_primitives.dart';

class DownloadsPage extends StatefulWidget {
  final void Function(String seriesId, int episodeIndex, int positionMs, String title) onPlayOffline;
  final ValueChanged<String> onToast;

  const DownloadsPage({
    super.key,
    required this.onPlayOffline,
    required this.onToast,
  });

  @override
  State<DownloadsPage> createState() => _DownloadsPageState();
}

class _DownloadsPageState extends State<DownloadsPage> {
  int _tabIndex = 0; // 0: 正在下载, 1: 已完成
  Timer? _timer;
  List<DownloadTaskItem> _tasks = const [];
  Map<String, HistoryEntry> _historyBySeries = const {};
  final Set<String> _selectedIds = {};
  final Set<String> _expandedSeries = {}; // Collapsed by default
  bool _loading = true;
  bool _polling = false;
  int _pollCount = 0;

  static bool _taskListEquals(List<DownloadTaskItem> a, List<DownloadTaskItem> b) {
    if (identical(a, b)) return true;
    if (a.length != b.length) return false;
    for (int i = 0; i < a.length; i++) {
      final x = a[i];
      final y = b[i];
      if (x.id != y.id ||
          x.status != y.status ||
          x.downloadedBytes != y.downloadedBytes ||
          x.totalBytes != y.totalBytes ||
          x.speedBytesPerSec != y.speedBytesPerSec ||
          x.error != y.error) {
        return false;
      }
    }
    return true;
  }

  void _scheduleNextPoll() {
    _timer?.cancel();
    final hasActive = _tasks.any(
      (t) => t.status == 'downloading' || t.status == 'queued',
    );
    _timer = Timer(
      Duration(milliseconds: hasActive ? 900 : 2500),
      _poll,
    );
  }

  @override
  void initState() {
    super.initState();
    _poll();
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  Future<void> _poll() async {
    if (_polling) return;
    _polling = true;
    try {
      final res = await RustBridge.instance.callMap('download.list');
      Map<String, HistoryEntry>? nextHist;
      // Refresh local progress on first load & periodically so offline playback progress stays synced
      if (_pollCount % 5 == 0) {
        try {
          final hRes = await RustBridge.instance.callMap('history.list', {'limit': 200});
          final hMap = <String, HistoryEntry>{};
          for (final raw in (hRes['items'] as List<dynamic>? ?? const [])) {
            final entry = HistoryEntry.fromJson(Map<String, dynamic>.from(raw as Map));
            if (entry.seriesId.isNotEmpty) {
              hMap[entry.seriesId] = entry;
            }
          }
          nextHist = hMap;
        } catch (_) {}
      }
      _pollCount++;
      if (!mounted) return;
      final list = (res['items'] as List<dynamic>? ?? const [])
          .map((e) => DownloadTaskItem.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList();
      final validIds = list.map((e) => e.id).toSet();
      final changed = _loading ||
          !_taskListEquals(_tasks, list) ||
          (nextHist != null && nextHist.length != _historyBySeries.length);
      if (changed) {
        setState(() {
          _tasks = list;
          if (nextHist != null) _historyBySeries = nextHist;
          _selectedIds.removeWhere((id) => !validIds.contains(id));
          _loading = false;
        });
      }
    } catch (_) {
      if (mounted && _loading) setState(() => _loading = false);
    } finally {
      _polling = false;
      if (mounted) _scheduleNextPoll();
    }
  }

  Future<void> _act(String action, {String id = '', bool removeFile = false}) async {
    try {
      final res = await RustBridge.instance.callMap('download.action', {
        'action': action,
        'id': id,
        'removeFile': removeFile,
      });
      if (!mounted) return;
      final list = (res['items'] as List<dynamic>? ?? const [])
          .map((e) => DownloadTaskItem.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList();
      final validIds = list.map((e) => e.id).toSet();
      setState(() {
        _tasks = list;
        _selectedIds.removeWhere((x) => !validIds.contains(x));
      });
    } catch (e) {
      widget.onToast('操作失败：$e');
    }
  }

  Future<void> _confirmBatchDelete(List<String> targetIds) async {
    if (targetIds.isEmpty) return;
    final p = LvguoPalette.of(context);
    bool removeFile = true;
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setModalState) => AlertDialog(
          backgroundColor: p.surface,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(18),
            side: BorderSide(color: p.sep),
          ),
          title: Text(
            '批量删除 (${targetIds.length} 集)',
            style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700, color: p.text),
          ),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '确定移除已选中的 ${targetIds.length} 个下载任务吗？剧集下全部任务移除后，对应本地目录将一并清空删除。',
                style: TextStyle(fontSize: 13.5, height: 1.5, color: p.text2),
              ),
              const SizedBox(height: 10),
              CheckboxListTile(
                contentPadding: EdgeInsets.zero,
                controlAffinity: ListTileControlAffinity.leading,
                activeColor: p.primary,
                value: removeFile,
                onChanged: (v) => setModalState(() => removeFile = v ?? false),
                title: Text(
                  '同时删除本地视频、弹幕缓存及空文件夹',
                  style: TextStyle(fontSize: 13, color: p.text2),
                ),
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(ctx).pop(false),
              child: Text('取消', style: TextStyle(color: p.text2)),
            ),
            FilledButton(
              style: FilledButton.styleFrom(
                backgroundColor: p.danger,
                foregroundColor: Colors.white,
                shape: const StadiumBorder(),
              ),
              onPressed: () => Navigator.of(ctx).pop(true),
              child: const Text('删除'),
            ),
          ],
        ),
      ),
    );
    if (ok != true) return;
    try {
      final res = await RustBridge.instance.callMap('download.delete_batch', {
        'ids': targetIds,
        'removeFile': removeFile,
      });
      if (!mounted) return;
      final list = (res['items'] as List<dynamic>? ?? const [])
          .map((e) => DownloadTaskItem.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList();
      final deleted = (res['deleted'] as num?)?.toInt() ?? targetIds.length;
      final validIds = list.map((e) => e.id).toSet();
      setState(() {
        _tasks = list;
        _selectedIds
          ..removeAll(targetIds)
          ..removeWhere((x) => !validIds.contains(x));
      });
      widget.onToast('已删除 $deleted 个任务');
    } catch (e) {
      if (mounted) widget.onToast('删除失败：$e');
    }
  }

  Future<void> _openLocation(String id) async {
    try {
      await RustBridge.instance.callMap('download.open', {'id': id});
    } catch (e) {
      widget.onToast('打开目录失败：$e');
    }
  }

  List<_SeriesFolder> _groupBySeries(List<DownloadTaskItem> list) {
    final map = <String, List<DownloadTaskItem>>{};
    for (final t in list) {
      final key = t.seriesId.isNotEmpty ? t.seriesId : t.seriesTitle;
      map.putIfAbsent(key, () => []).add(t);
    }
    final folders = <_SeriesFolder>[];
    for (final entry in map.entries) {
      final eps = List<DownloadTaskItem>.from(entry.value)
        ..sort((a, b) => a.episodeIndex.compareTo(b.episodeIndex));
      final first = eps.first;
      final cover = eps.firstWhere((e) => e.cover.isNotEmpty, orElse: () => first).cover;
      final title = first.seriesTitle.isNotEmpty ? first.seriesTitle : '剧集 ${entry.key}';
      folders.add(_SeriesFolder(
        key: entry.key,
        seriesId: first.seriesId,
        seriesTitle: title,
        cover: cover,
        tasks: eps,
      ));
    }
    folders.sort((a, b) => a.seriesTitle.compareTo(b.seriesTitle));
    return folders;
  }

  @override
  Widget build(BuildContext context) {
    final p = LvguoPalette.of(context);
    final w = MediaQuery.of(context).size.width;
    final gutter = w <= 1366 ? 40.0 : 56.0;
    final active = _tasks.where((t) => t.status != 'completed').toList();
    final completed = _tasks.where((t) => t.status == 'completed').toList();
    final shown = _tabIndex == 0 ? active : completed;
    final folders = _groupBySeries(shown);
    final shownIds = shown.map((t) => t.id).toList();
    final shownSelectedIds = shownIds.where(_selectedIds.contains).toList();
    final allSelected = shown.isNotEmpty && shownSelectedIds.length == shown.length;
    final someSelected = shownSelectedIds.isNotEmpty && !allSelected;

    return SingleChildScrollView(
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 1440),
          child: Padding(
            padding: EdgeInsets.fromLTRB(gutter, 34, gutter, 72),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // Header + Direct Batch Actions
                Row(
                  children: [
                    Text(
                      '下载管理',
                      style: TextStyle(
                        fontSize: 32,
                        height: 1.15,
                        fontWeight: FontWeight.w700,
                        letterSpacing: -0.64,
                        color: p.text,
                      ),
                    ),
                    if (folders.isNotEmpty) ...[
                      const SizedBox(width: 14),
                      Container(
                        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 3),
                        decoration: BoxDecoration(
                          color: p.fill,
                          borderRadius: BorderRadius.circular(999),
                        ),
                        child: Text(
                          '${folders.length} 部剧集 · ${shown.length} 集',
                          style: TextStyle(
                            fontSize: 12.5,
                            fontWeight: FontWeight.w500,
                            color: p.text3,
                          ),
                        ),
                      ),
                    ],
                    const SizedBox(width: 16),
                    Expanded(
                      child: Wrap(
                        spacing: 8,
                        runSpacing: 8,
                        alignment: WrapAlignment.end,
                        crossAxisAlignment: WrapCrossAlignment.center,
                        children: [
                          if (_tabIndex == 0 && active.isNotEmpty) ...[
                            OutlinedButton.icon(
                              style: OutlinedButton.styleFrom(
                                foregroundColor: p.text2,
                                side: BorderSide(color: p.sep),
                                shape: const StadiumBorder(),
                                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                              ),
                              onPressed: () => _act('resume_all'),
                              icon: const Icon(Icons.play_arrow_rounded, size: 17),
                              label: const Text('全部继续', style: TextStyle(fontSize: 13)),
                            ),
                            OutlinedButton.icon(
                              style: OutlinedButton.styleFrom(
                                foregroundColor: p.text2,
                                side: BorderSide(color: p.sep),
                                shape: const StadiumBorder(),
                                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                              ),
                              onPressed: () => _act('pause_all'),
                              icon: const Icon(Icons.pause_rounded, size: 17),
                              label: const Text('全部暂停', style: TextStyle(fontSize: 13)),
                            ),
                          ],
                          if (shown.isNotEmpty) ...[
                            OutlinedButton.icon(
                              style: OutlinedButton.styleFrom(
                                foregroundColor: allSelected ? p.primary : p.text2,
                                backgroundColor: allSelected ? p.primarySoft : Colors.transparent,
                                side: BorderSide(color: allSelected ? p.primary.withValues(alpha: 0.4) : p.sep),
                                shape: const StadiumBorder(),
                                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                              ),
                              onPressed: () {
                                setState(() {
                                  if (allSelected) {
                                    _selectedIds.removeAll(shownIds);
                                  } else {
                                    _selectedIds.addAll(shownIds);
                                  }
                                });
                              },
                              icon: Icon(
                                allSelected
                                    ? Icons.check_circle_rounded
                                    : someSelected
                                        ? Icons.remove_circle_outline_rounded
                                        : Icons.radio_button_unchecked_rounded,
                                size: 16,
                              ),
                              label: Text(
                                allSelected ? '取消全选' : '全选 (${shownSelectedIds.length}/${shown.length})',
                                style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w500),
                              ),
                            ),
                            FilledButton.icon(
                              style: FilledButton.styleFrom(
                                backgroundColor: shownSelectedIds.isEmpty ? p.fill : p.danger,
                                foregroundColor: shownSelectedIds.isEmpty ? p.text4 : Colors.white,
                                elevation: 0,
                                shape: const StadiumBorder(),
                                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
                              ),
                              onPressed: shownSelectedIds.isEmpty
                                  ? null
                                  : () => _confirmBatchDelete(shownSelectedIds),
                              icon: const Icon(Icons.delete_outline_rounded, size: 17),
                              label: Text(
                                shownSelectedIds.isEmpty
                                    ? '批量删除'
                                    : '删除选中 (${shownSelectedIds.length})',
                                style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600),
                              ),
                            ),
                          ],
                        ],
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 16),

                // Unified underline tabs
                UnderlineTabBar(
                  labels: [
                    '正在下载 (${active.length})',
                    '已完成 (${completed.length})',
                  ],
                  activeIndex: _tabIndex,
                  onChanged: (i) {
                    if (i == _tabIndex) return;
                    setState(() {
                      _tabIndex = i;
                      _selectedIds.clear();
                    });
                  },
                  indicatorColor: p.primary,
                  gap: 28,
                ),

                const SizedBox(height: 22),

                if (_loading)
                  const _DownloadsSkeleton()
                else if (shown.isEmpty)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 90),
                    child: Center(
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Container(
                            width: 60,
                            height: 60,
                            decoration: BoxDecoration(
                              color: p.fill,
                              shape: BoxShape.circle,
                            ),
                            child: Icon(
                              _tabIndex == 0
                                  ? Icons.downloading_rounded
                                  : Icons.download_done_rounded,
                              size: 28,
                              color: p.text4,
                            ),
                          ),
                          const SizedBox(height: 14),
                          Text(
                            _tabIndex == 0 ? '当前没有正在下载的任务' : '暂无已完成的离线剧集',
                            style: TextStyle(
                              fontSize: 15,
                              fontWeight: FontWeight.w600,
                              color: p.text2,
                            ),
                          ),
                          const SizedBox(height: 6),
                          Text(
                            _tabIndex == 0
                                ? '在播放页点击「下载」可批量缓存剧集与弹幕'
                                : '下载完成后将按剧集自动整理到这里',
                            style: TextStyle(fontSize: 13, color: p.text3),
                          ),
                        ],
                      ),
                    ),
                  )
                else
                  Column(
                    children: [
                      for (int f = 0; f < folders.length; f++)
                        Padding(
                          padding: EdgeInsets.only(bottom: f == folders.length - 1 ? 0 : 14),
                          child: _SeriesFolderCard(
                            folder: folders[f],
                            history: _historyBySeries[folders[f].seriesId],
                            palette: p,
                            isCompletedTab: _tabIndex == 1,
                            expanded: _expandedSeries.contains(folders[f].key),
                            selectedIds: _selectedIds,
                            onToggleExpand: () {
                              setState(() {
                                final k = folders[f].key;
                                if (_expandedSeries.contains(k)) {
                                  _expandedSeries.remove(k);
                                } else {
                                  _expandedSeries.add(k);
                                }
                              });
                            },
                            onToggleFolderSelect: (selectAll) {
                              setState(() {
                                final ids = folders[f].tasks.map((t) => t.id);
                                if (selectAll) {
                                  _selectedIds.addAll(ids);
                                } else {
                                  _selectedIds.removeAll(ids);
                                }
                              });
                            },
                            onToggleItemSelect: (id) {
                              setState(() {
                                if (_selectedIds.contains(id)) {
                                  _selectedIds.remove(id);
                                } else {
                                  _selectedIds.add(id);
                                }
                              });
                            },
                            onOpenDir: () => _openLocation(folders[f].tasks.first.id),
                            onPause: (t) => _act('pause', id: t.id),
                            onResume: (t) => _act('resume', id: t.id),
                            onPlayResume: (epIdx, posMs) => widget.onPlayOffline(
                              folders[f].seriesId,
                              epIdx,
                              posMs,
                              folders[f].seriesTitle,
                            ),
                          ),
                        ),
                    ],
                  ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _DownloadsSkeleton extends StatelessWidget {
  const _DownloadsSkeleton();

  @override
  Widget build(BuildContext context) {
    final p = LvguoPalette.of(context);
    return Column(
      children: [
        for (int i = 0; i < 3; i++)
          Container(
            margin: EdgeInsets.only(bottom: i == 2 ? 0 : 14),
            padding: const EdgeInsets.all(18),
            decoration: BoxDecoration(
              color: p.surface,
              borderRadius: BorderRadius.circular(14),
              border: Border.all(color: p.sep),
            ),
            child: const Row(
              children: [
                SkeletonBox(width: 50, height: 68, radius: 8),
                SizedBox(width: 16),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      SkeletonBox(width: 220, height: 17, radius: 5),
                      SizedBox(height: 10),
                      SkeletonBox(width: 150, height: 13, radius: 4),
                    ],
                  ),
                ),
                SkeletonBox(width: 96, height: 34, radius: 99),
              ],
            ),
          ),
      ],
    );
  }
}

class _CircleSelect extends StatelessWidget {
  final bool checked;
  final bool indeterminate;
  final LvguoPalette palette;
  final VoidCallback onTap;
  final double size;

  const _CircleSelect({
    required this.checked,
    this.indeterminate = false,
    required this.palette,
    required this.onTap,
    this.size = 18,
  });

  @override
  Widget build(BuildContext context) {
    final active = checked || indeterminate;
    return MouseRegion(
      cursor: SystemMouseCursors.click,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(4),
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 160),
            width: size,
            height: size,
            decoration: BoxDecoration(
              color: active ? palette.primary : Colors.transparent,
              shape: BoxShape.circle,
              border: Border.all(
                color: active ? palette.primary : palette.text4,
                width: 1.5,
              ),
            ),
            alignment: Alignment.center,
            child: active
                ? Icon(
                    checked ? Icons.check_rounded : Icons.remove_rounded,
                    size: size * 0.68,
                    color: Colors.white,
                  )
                : null,
          ),
        ),
      ),
    );
  }
}

class _SeriesFolder {
  final String key;
  final String seriesId;
  final String seriesTitle;
  final String cover;
  final List<DownloadTaskItem> tasks;

  const _SeriesFolder({
    required this.key,
    required this.seriesId,
    required this.seriesTitle,
    required this.cover,
    required this.tasks,
  });
}

class _SeriesFolderCard extends StatefulWidget {
  final _SeriesFolder folder;
  final HistoryEntry? history;
  final LvguoPalette palette;
  final bool isCompletedTab;
  final bool expanded;
  final Set<String> selectedIds;
  final VoidCallback onToggleExpand;
  final ValueChanged<bool> onToggleFolderSelect;
  final ValueChanged<String> onToggleItemSelect;
  final VoidCallback onOpenDir;
  final ValueChanged<DownloadTaskItem> onPause;
  final ValueChanged<DownloadTaskItem> onResume;
  final void Function(int epIndexZeroBased, int positionMs) onPlayResume;

  const _SeriesFolderCard({
    required this.folder,
    required this.history,
    required this.palette,
    required this.isCompletedTab,
    required this.expanded,
    required this.selectedIds,
    required this.onToggleExpand,
    required this.onToggleFolderSelect,
    required this.onToggleItemSelect,
    required this.onOpenDir,
    required this.onPause,
    required this.onResume,
    required this.onPlayResume,
  });

  @override
  State<_SeriesFolderCard> createState() => _SeriesFolderCardState();
}

class _SeriesFolderCardState extends State<_SeriesFolderCard> {
  bool _headerHover = false;

  @override
  Widget build(BuildContext context) {
    final p = widget.palette;
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final tasks = widget.folder.tasks;
    final hist = widget.history;
    final selectedCount = tasks.where((t) => widget.selectedIds.contains(t.id)).length;
    final allSelected = selectedCount == tasks.length && tasks.isNotEmpty;
    final someSelected = selectedCount > 0 && !allSelected;

    final totalBytes = tasks.fold<int>(
      0,
      (sum, t) => sum + (t.totalBytes > 0 ? t.totalBytes : t.downloadedBytes),
    );
    final downloadedBytes = tasks.fold<int>(0, (sum, t) => sum + t.downloadedBytes);
    final speedBytes = tasks.fold<int>(
      0,
      (sum, t) => sum + (t.status == 'downloading' ? t.speedBytesPerSec : 0),
    );
    final aggProgress = totalBytes > 0 ? (downloadedBytes / totalBytes).clamp(0.0, 1.0) : 0.0;
    final defTag = tasks.firstWhere((t) => t.definition.isNotEmpty, orElse: () => tasks.first).definition;

    final resumeEpIdx = hist != null
        ? hist.episodeIndex
        : (tasks.first.episodeIndex - 1).clamp(0, 9999);
    final resumePosMs = hist != null ? hist.positionMs : 0;
    final resumeLabel = hist != null
        ? '继续第 ${hist.episodeIndex + 1} 集'
        : '播放第 ${tasks.first.episodeIndex} 集';

    return Container(
      decoration: BoxDecoration(
        color: p.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(
          color: allSelected
              ? p.primary.withValues(alpha: 0.55)
              : _headerHover
                  ? p.text4.withValues(alpha: 0.45)
                  : p.sep,
        ),
        boxShadow: isDark
            ? null
            : const [
                BoxShadow(
                  color: Color(0x07000000),
                  blurRadius: 12,
                  offset: Offset(0, 3),
                ),
              ],
      ),
      clipBehavior: Clip.antiAlias,
      child: Column(
        children: [
          MouseRegion(
            cursor: SystemMouseCursors.click,
            onEnter: (_) => setState(() => _headerHover = true),
            onExit: (_) => setState(() => _headerHover = false),
            child: GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTap: widget.onToggleExpand,
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 14, 14, 14),
                child: Row(
                  children: [
                    _CircleSelect(
                      checked: allSelected,
                      indeterminate: someSelected,
                      palette: p,
                      onTap: () => widget.onToggleFolderSelect(!allSelected),
                    ),
                    const SizedBox(width: 12),
                    Container(
                      width: 50,
                      height: 68,
                      decoration: BoxDecoration(
                        color: p.skeleton,
                        borderRadius: BorderRadius.circular(8),
                        border: Border.all(color: p.sep),
                      ),
                      clipBehavior: Clip.antiAlias,
                      child: widget.folder.cover.isNotEmpty
                          ? Image.network(
                              widget.folder.cover,
                              fit: BoxFit.cover,
                              cacheWidth: 160,
                              gaplessPlayback: true,
                              errorBuilder: (_, _, _) => Icon(Icons.movie_outlined, color: p.text4, size: 22),
                            )
                          : Icon(Icons.movie_outlined, color: p.text4, size: 22),
                    ),
                    const SizedBox(width: 16),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            widget.folder.seriesTitle,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              fontSize: 16,
                              fontWeight: FontWeight.w700,
                              letterSpacing: -0.2,
                              color: p.text,
                            ),
                          ),
                          const SizedBox(height: 8),
                          Wrap(
                            spacing: 8,
                            runSpacing: 6,
                            crossAxisAlignment: WrapCrossAlignment.center,
                            children: [
                              _MetaPill(
                                text: '共 ${tasks.length} 集',
                                bg: p.fill,
                                fg: p.text2,
                              ),
                              if (totalBytes > 0)
                                _MetaPill(
                                  text: formatBytes(totalBytes),
                                  bg: p.fill,
                                  fg: p.text3,
                                ),
                              if (defTag.isNotEmpty)
                                _MetaPill(
                                  text: defTag,
                                  bg: p.fill,
                                  fg: p.text3,
                                ),
                              if (hist != null)
                                _MetaPill(
                                  text: '看到第 ${hist.episodeIndex + 1} 集',
                                  bg: p.primarySoft,
                                  fg: p.primary,
                                  bold: true,
                                ),
                              if (speedBytes > 0)
                                _MetaPill(
                                  text: '${formatBytes(speedBytes)}/s',
                                  bg: p.primarySoft,
                                  fg: p.primary,
                                  bold: true,
                                ),
                            ],
                          ),
                          if (!widget.isCompletedTab && totalBytes > 0) ...[
                            const SizedBox(height: 10),
                            ClipRRect(
                              borderRadius: BorderRadius.circular(999),
                              child: LinearProgressIndicator(
                                value: aggProgress,
                                minHeight: 3,
                                backgroundColor: p.fill,
                                color: p.primary,
                              ),
                            ),
                          ],
                        ],
                      ),
                    ),
                    const SizedBox(width: 16),
                    if (widget.isCompletedTab && tasks.isNotEmpty) ...[
                      FilledButton.tonalIcon(
                        style: FilledButton.styleFrom(
                          backgroundColor: p.primarySoft,
                          foregroundColor: p.primary,
                          elevation: 0,
                          shape: const StadiumBorder(),
                          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
                        ),
                        onPressed: () => widget.onPlayResume(resumeEpIdx, resumePosMs),
                        icon: const Icon(Icons.play_arrow_rounded, size: 18),
                        label: Text(
                          resumeLabel,
                          style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600),
                        ),
                      ),
                      const SizedBox(width: 4),
                    ],
                    Tooltip(
                      message: '在文件夹中打开',
                      child: IconButton(
                        icon: Icon(Icons.folder_open_rounded, size: 19, color: p.text3),
                        onPressed: widget.onOpenDir,
                      ),
                    ),
                    Padding(
                      padding: const EdgeInsets.all(6),
                      child: AnimatedRotation(
                        turns: widget.expanded ? 0.0 : -0.25,
                        duration: const Duration(milliseconds: 180),
                        child: Icon(
                          Icons.keyboard_arrow_down_rounded,
                          size: 22,
                          color: p.text3,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
          if (widget.expanded) ...[
            Container(height: 1, color: p.sep),
            if (widget.isCompletedTab)
              RepaintBoundary(
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxHeight: 420),
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: GridView.builder(
                      shrinkWrap: true,
                      itemCount: tasks.length,
                      gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
                        maxCrossAxisExtent: 156,
                        mainAxisSpacing: 10,
                        crossAxisSpacing: 10,
                        childAspectRatio: 2.35,
                      ),
                      itemBuilder: (_, i) {
                        final t = tasks[i];
                        final isCurrentHist = hist != null && t.episodeIndex == hist.episodeIndex + 1;
                        return _CompletedEpisodeTile(
                          task: t,
                          palette: p,
                          isLastWatched: isCurrentHist,
                          selected: widget.selectedIds.contains(t.id),
                          onPlay: () => widget.onPlayResume(
                            (t.episodeIndex - 1).clamp(0, 9999),
                            isCurrentHist ? hist.positionMs : 0,
                          ),
                          onToggleSelect: () => widget.onToggleItemSelect(t.id),
                        );
                      },
                    ),
                  ),
                ),
              )
            else
              RepaintBoundary(
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxHeight: 440),
                  child: ListView.builder(
                    shrinkWrap: true,
                    itemCount: tasks.length,
                    itemBuilder: (_, i) => _ActiveDownloadRow(
                      task: tasks[i],
                      isLast: i == tasks.length - 1,
                      palette: p,
                      selected: widget.selectedIds.contains(tasks[i].id),
                      onToggleSelect: () => widget.onToggleItemSelect(tasks[i].id),
                      onPause: () => widget.onPause(tasks[i]),
                      onResume: () => widget.onResume(tasks[i]),
                    ),
                  ),
                ),
              ),
          ],
        ],
      ),
    );
  }
}

class _MetaPill extends StatelessWidget {
  final String text;
  final Color bg;
  final Color fg;
  final bool bold;

  const _MetaPill({
    required this.text,
    required this.bg,
    required this.fg,
    this.bold = false,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: bg,
        borderRadius: BorderRadius.circular(6),
      ),
      child: Text(
        text,
        style: TextStyle(
          fontSize: 11.5,
          fontWeight: bold ? FontWeight.w600 : FontWeight.w500,
          color: fg,
        ),
      ),
    );
  }
}

class _CompletedEpisodeTile extends StatefulWidget {
  final DownloadTaskItem task;
  final LvguoPalette palette;
  final bool isLastWatched;
  final bool selected;
  final VoidCallback onPlay;
  final VoidCallback onToggleSelect;

  const _CompletedEpisodeTile({
    required this.task,
    required this.palette,
    required this.isLastWatched,
    required this.selected,
    required this.onPlay,
    required this.onToggleSelect,
  });

  @override
  State<_CompletedEpisodeTile> createState() => _CompletedEpisodeTileState();
}

class _CompletedEpisodeTileState extends State<_CompletedEpisodeTile> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    final p = widget.palette;
    final t = widget.task;
    final highlighted = widget.selected || widget.isLastWatched || _hover;

    return MouseRegion(
      cursor: SystemMouseCursors.click,
      onEnter: (_) => setState(() => _hover = true),
      onExit: (_) => setState(() => _hover = false),
      child: GestureDetector(
        onTap: widget.onPlay,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 140),
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
          decoration: BoxDecoration(
            color: highlighted ? p.primarySoft : p.fill,
            borderRadius: BorderRadius.circular(10),
            border: Border.all(
              color: widget.selected
                  ? p.primary
                  : _hover
                      ? p.primary.withValues(alpha: 0.45)
                      : widget.isLastWatched
                          ? p.primary.withValues(alpha: 0.30)
                          : Colors.transparent,
            ),
          ),
          child: Row(
            children: [
              _CircleSelect(
                checked: widget.selected,
                palette: p,
                size: 15,
                onTap: widget.onToggleSelect,
              ),
              const SizedBox(width: 6),
              Expanded(
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Flexible(
                          child: Text(
                            '第 ${t.episodeIndex} 集',
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              fontSize: 13,
                              fontWeight: FontWeight.w600,
                              color: highlighted ? p.primary : p.text,
                            ),
                          ),
                        ),
                        if (widget.isLastWatched) ...[
                          const SizedBox(width: 4),
                          Container(
                            width: 5,
                            height: 5,
                            decoration: BoxDecoration(
                              color: p.primary,
                              shape: BoxShape.circle,
                            ),
                          ),
                        ],
                      ],
                    ),
                    const SizedBox(height: 1),
                    Text(
                      [
                        if (t.definition.isNotEmpty) t.definition,
                        if (t.totalBytes > 0) formatBytes(t.totalBytes),
                      ].join(' · '),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: 10.5,
                        color: p.text3,
                      ),
                    ),
                  ],
                ),
              ),
              if (_hover && !widget.selected)
                Icon(Icons.play_arrow_rounded, size: 16, color: p.primary),
            ],
          ),
        ),
      ),
    );
  }
}

class _ActiveDownloadRow extends StatelessWidget {
  final DownloadTaskItem task;
  final bool isLast;
  final LvguoPalette palette;
  final bool selected;
  final VoidCallback onToggleSelect;
  final VoidCallback onPause;
  final VoidCallback onResume;

  const _ActiveDownloadRow({
    required this.task,
    required this.isLast,
    required this.palette,
    required this.selected,
    required this.onToggleSelect,
    required this.onPause,
    required this.onResume,
  });

  String _statusText() {
    switch (task.status) {
      case 'downloading':
        return '${formatBytes(task.speedBytesPerSec)}/s · ${task.chunkCount} 线程';
      case 'paused':
        return '已暂停（支持断点续传）';
      case 'queued':
        return '排队等待中';
      case 'error':
        return task.error.isNotEmpty ? '失败：${task.error}' : '下载失败';
      default:
        return task.status;
    }
  }

  @override
  Widget build(BuildContext context) {
    final p = palette;
    final active = task.status == 'downloading' || task.status == 'queued';
    final err = task.status == 'error';

    return Container(
      padding: const EdgeInsets.fromLTRB(16, 13, 14, 13),
      decoration: BoxDecoration(
        border: isLast ? null : Border(bottom: BorderSide(color: p.sep)),
      ),
      child: Row(
        children: [
          _CircleSelect(
            checked: selected,
            palette: p,
            onTap: onToggleSelect,
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Text(
                      '第 ${task.episodeIndex} 集',
                      style: TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w600,
                        color: p.text,
                      ),
                    ),
                    if (task.definition.isNotEmpty) ...[
                      const SizedBox(width: 8),
                      _MetaPill(text: task.definition, bg: p.fill, fg: p.text2),
                    ],
                    const Spacer(),
                    Text(
                      '${formatBytes(task.downloadedBytes)} / ${formatBytes(task.totalBytes)} · ${_statusText()}',
                      style: TextStyle(
                        fontSize: 12,
                        color: err ? p.danger : p.text3,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                ClipRRect(
                  borderRadius: BorderRadius.circular(999),
                  child: LinearProgressIndicator(
                    value: task.totalBytes > 0 ? task.progress : null,
                    minHeight: 4,
                    backgroundColor: p.fill,
                    color: err ? p.danger : p.primary,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 14),
          if (active)
            IconButton(
              tooltip: '暂停',
              icon: Icon(Icons.pause_circle_outline_rounded, size: 22, color: p.text2),
              onPressed: onPause,
            )
          else
            IconButton(
              tooltip: err ? '重试' : '继续下载',
              icon: Icon(Icons.play_circle_outline_rounded, size: 22, color: p.primary),
              onPressed: onResume,
            ),
        ],
      ),
    );
  }
}
