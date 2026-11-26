import 'package:flutter/material.dart';

import '../core/rust_bridge.dart';
import '../core/settings_store.dart';
import '../core/theme.dart';
import '../models/models.dart';
import '../widgets/soon_popup.dart';
import '../widgets/web_primitives.dart';

/// 1:1 with `detail()` + `.dpage` in web/ui/pc/app.css + app.js.
class DetailPage extends StatefulWidget {
  final String seriesId;
  final bool loggedIn;
  final ValueChanged<String> onToast;
  final ValueChanged<String> onSwitchSeries;
  final VoidCallback onOpenAuth;
  final VoidCallback onBack;
  final void Function(
    DetailData detail,
    List<SeasonItem> seasons,
    int episodeIndex,
    int positionMs,
  ) onPlayEpisode;

  const DetailPage({
    super.key,
    required this.seriesId,
    required this.loggedIn,
    required this.onToast,
    required this.onSwitchSeries,
    required this.onOpenAuth,
    required this.onBack,
    required this.onPlayEpisode,
  });

  @override
  State<DetailPage> createState() => _DetailPageState();
}

class _DetailPageState extends State<DetailPage> {
  DetailData? _detail;
  List<SeasonItem> _seasons = const [];
  Set<String> _downloadedVids = const {};
  bool _favorite = false;
  bool _reserved = false;
  HistoryEntry? _progress;
  bool _introExpanded = false;
  int _epRangeIndex = 0;

  bool _loading = true;
  String _err = '';

  @override
  void initState() {
    super.initState();
    _loadDetail();
  }

  @override
  void didUpdateWidget(covariant DetailPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.seriesId != widget.seriesId ||
        oldWidget.loggedIn != widget.loggedIn) {
      _epRangeIndex = 0;
      _introExpanded = false;
      _loadDetail();
    }
  }

  Future<void> _loadDetail() async {
    setState(() {
      _loading = true;
      _err = '';
    });
    try {
      final res = await RustBridge.instance.callMap('detail', {'id': widget.seriesId});
      final d = DetailData.fromJson(
        Map<String, dynamic>.from(res['detail'] as Map? ?? const {}),
      );
      HistoryEntry? prog;
      if (res['progress'] is Map) {
        prog = HistoryEntry.fromJson(Map<String, dynamic>.from(res['progress'] as Map));
      }

      List<SeasonItem> seasons = const [];
      if (d.multiSeason) {
        try {
          final sRes = await RustBridge.instance.callMap('seasons', {
            'id': d.id,
            'quick': true,
          });
          seasons = (sRes['items'] as List<dynamic>? ?? const [])
              .map((e) => SeasonItem.fromJson(Map<String, dynamic>.from(e as Map)))
              .toList();
        } catch (_) {}
      }

      final dlRes = await RustBridge.instance
          .callMap('download.list')
          .catchError((_) => <String, dynamic>{});
      final dlTasks = (dlRes['items'] as List<dynamic>? ?? const [])
          .map((e) => DownloadTaskItem.fromJson(Map<String, dynamic>.from(e as Map)))
          .where((t) => t.status == 'completed')
          .map((t) => t.vid)
          .toSet();

      if (!mounted) return;
      final curIdx = prog != null ? prog.episodeIndex : -1;
      setState(() {
        _detail = d;
        _seasons = seasons;
        _downloadedVids = dlTasks;
        _favorite = res['favorite'] == true;
        _reserved = res['reserved'] == true;
        _progress = prog;
        _epRangeIndex = curIdx > 0 && d.episodes.length > 110 ? curIdx ~/ 100 : 0;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _err = e.toString();
      });
    }
  }

  Future<void> _toggleFavorite() async {
    if (!widget.loggedIn) {
      widget.onOpenAuth();
      widget.onToast('请先登录后收藏');
      return;
    }
    final d = _detail;
    if (d == null) return;
    final next = !_favorite;
    try {
      await RustBridge.instance.callMap('favorite.set', {
        'seriesId': d.id,
        'add': next,
      });
      if (!mounted) return;
      setState(() => _favorite = next);
      widget.onToast(next ? '已加入收藏' : '已取消收藏');
    } catch (e) {
      widget.onToast('操作失败：$e');
    }
  }

  Future<void> _toggleReservation() async {
    if (!widget.loggedIn) {
      widget.onOpenAuth();
      widget.onToast('请先登录后预约');
      return;
    }
    final d = _detail;
    if (d == null) return;
    final next = !_reserved;
    if (!next) {
      final p = LvguoPalette.of(context);
      final ok = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          backgroundColor: p.surface,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(18),
            side: BorderSide(color: p.sep),
          ),
          title: Text('取消预约？', style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700, color: p.text)),
          content: Text('《${d.title}》上线后将不再提醒。', style: TextStyle(fontSize: 14, color: p.text2)),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(ctx).pop(false),
              child: Text('再想想', style: TextStyle(color: p.text2)),
            ),
            FilledButton(
              style: FilledButton.styleFrom(
                backgroundColor: p.danger,
                foregroundColor: Colors.white,
                shape: const StadiumBorder(),
              ),
              onPressed: () => Navigator.of(ctx).pop(true),
              child: const Text('取消预约'),
            ),
          ],
        ),
      );
      if (ok != true || !mounted) return;
    }
    try {
      await RustBridge.instance.callMap('reservation.set', {
        'seriesId': d.id,
        'add': next,
      });
      if (!mounted) return;
      setState(() => _reserved = next);
      widget.onToast(next ? '已预约' : '已取消预约');
    } catch (e) {
      widget.onToast('操作失败：$e');
    }
  }

  Future<void> _downloadOne(EpisodeItem ep) async {
    final d = _detail;
    if (d == null) return;
    try {
      await RustBridge.instance.callMap('download.add', {
        'seriesId': d.id,
        'seriesTitle': d.title,
        'cover': d.cover,
        'vid': ep.vid,
        'episodeIndex': ep.index,
        'episodeTitle': ep.title,
        'quality': SettingsStore.instance.defaultQuality,
      });
      widget.onToast('已加入下载队列：第${ep.index}集');
    } catch (e) {
      widget.onToast('添加下载失败：$e');
    }
  }

  Future<void> _showBatchDownloadDialog() async {
    final d = _detail;
    if (d == null || d.episodes.isEmpty) return;
    final p = LvguoPalette.of(context);
    final selected = <String>{for (final ep in d.episodes) ep.vid};
    String quality = SettingsStore.instance.defaultQuality;

    await showDialog<void>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setModalState) => AlertDialog(
          backgroundColor: p.surface,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(18),
            side: BorderSide(color: p.sep),
          ),
          title: Row(
            children: [
              Expanded(
                child: Text(
                  '批量下载 · ${d.title}',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: p.text),
                ),
              ),
              const SizedBox(width: 12),
              DropdownButton<String>(
                value: quality,
                underline: const SizedBox.shrink(),
                style: TextStyle(fontSize: 13, color: p.text),
                dropdownColor: p.surface2,
                items: const [
                  DropdownMenuItem(value: '1080p', child: Text('1080p 超清')),
                  DropdownMenuItem(value: '720p', child: Text('720p 高清')),
                  DropdownMenuItem(value: '480p', child: Text('480p 标清')),
                ],
                onChanged: (v) {
                  if (v != null) setModalState(() => quality = v);
                },
              ),
            ],
          ),
          content: SizedBox(
            width: 520,
            height: 340,
            child: Column(
              children: [
                Row(
                  children: [
                    TextButton(
                      onPressed: () {
                        setModalState(() {
                          selected
                            ..clear()
                            ..addAll(d.episodes.map((e) => e.vid));
                        });
                      },
                      child: const Text('全选'),
                    ),
                    TextButton(
                      onPressed: () => setModalState(() => selected.clear()),
                      child: const Text('清空'),
                    ),
                    const Spacer(),
                    Text(
                      '已选 ${selected.length} / ${d.episodes.length} 集',
                      style: TextStyle(fontSize: 12.5, color: p.text3),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                Expanded(
                  child: GridView.builder(
                    itemCount: d.episodes.length,
                    gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
                      maxCrossAxisExtent: 74,
                      mainAxisSpacing: 8,
                      crossAxisSpacing: 8,
                      childAspectRatio: 1.55,
                    ),
                    itemBuilder: (_, i) {
                      final ep = d.episodes[i];
                      final on = selected.contains(ep.vid);
                      return MouseRegion(
                        cursor: SystemMouseCursors.click,
                        child: GestureDetector(
                          onTap: () {
                            setModalState(() {
                              if (on) {
                                selected.remove(ep.vid);
                              } else {
                                selected.add(ep.vid);
                              }
                            });
                          },
                          child: Container(
                            alignment: Alignment.center,
                            decoration: BoxDecoration(
                              color: on ? p.primarySoft : p.fill2,
                              borderRadius: BorderRadius.circular(8),
                              border: Border.all(
                                color: on ? p.primary : p.sep,
                              ),
                            ),
                            child: Text(
                              '${ep.index}',
                              style: TextStyle(
                                fontSize: 13,
                                fontWeight: on ? FontWeight.w700 : FontWeight.w500,
                                color: on ? p.primary : p.text,
                              ),
                            ),
                          ),
                        ),
                      );
                    },
                  ),
                ),
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(ctx).pop(),
              child: Text('取消', style: TextStyle(color: p.text2)),
            ),
            FilledButton(
              style: FilledButton.styleFrom(
                backgroundColor: p.primary,
                foregroundColor: p.primaryOn,
                shape: const StadiumBorder(),
              ),
              onPressed: selected.isEmpty
                  ? null
                  : () async {
                      Navigator.of(ctx).pop();
                      final items = [
                        for (final ep in d.episodes)
                          if (selected.contains(ep.vid))
                            {
                              'seriesId': d.id,
                              'seriesTitle': d.title,
                              'cover': d.cover,
                              'vid': ep.vid,
                              'episodeIndex': ep.index,
                              'episodeTitle': ep.title,
                              'quality': quality,
                            },
                      ];
                      if (items.isEmpty) return;
                      try {
                        await RustBridge.instance.callMap('download.add_batch', {
                          'items': items,
                        });
                        widget.onToast('已添加 ${items.length} 个下载任务');
                      } catch (e) {
                        widget.onToast('添加下载失败：$e');
                      }
                    },
              child: const Text('开始下载'),
            ),
          ],
        ),
      ),
    );
  }

  void _playPrimary() {
    final d = _detail;
    if (d == null || d.episodes.isEmpty) return;
    int epIdx = 0;
    int posMs = 0;
    final p = _progress;
    if (p != null) {
      final idxByVid = d.episodes.indexWhere((e) => e.vid == p.vid);
      if (idxByVid >= 0) {
        epIdx = idxByVid;
        posMs = p.positionMs;
      } else {
        epIdx = p.episodeIndex.clamp(0, d.episodes.length - 1);
        posMs = p.positionMs;
      }
    }
    widget.onPlayEpisode(d, _seasons, epIdx, posMs);
  }

  @override
  Widget build(BuildContext context) {
    final p = LvguoPalette.of(context);
    final screenW = MediaQuery.of(context).size.width;
    final gutter = screenW <= 1366 ? 40.0 : 56.0;

    if (_loading) {
      return DetailSkeleton(gutter: gutter);
    }
    if (_err.isNotEmpty || _detail == null) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(_err.isNotEmpty ? _err : '加载失败', style: TextStyle(color: p.text2)),
            const SizedBox(height: 14),
            Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                OutlinedButton(
                  onPressed: widget.onBack,
                  child: const Text('返回'),
                ),
                const SizedBox(width: 10),
                FilledButton(
                  style: FilledButton.styleFrom(
                    backgroundColor: p.primary,
                    foregroundColor: p.primaryOn,
                  ),
                  onPressed: _loadDetail,
                  child: const Text('重试'),
                ),
              ],
            ),
          ],
        ),
      );
    }

    final d = _detail!;
    final prog = _progress;
    final cur = prog != null ? prog.episodeIndex : -1;
    final total = d.episodes.length;

    // Web only chunks episodes when total > 110 (`epGroups` line 870 app.js)
    final epRanges = <(int, int)>[];
    if (total > 110) {
      for (int start = 0; start < total; start += 100) {
        epRanges.add((start, (start + 100).clamp(0, total)));
      }
    } else if (total > 0) {
      epRanges.add((0, total));
    }
    final activeRange = epRanges.isEmpty
        ? (0, 0)
        : epRanges[_epRangeIndex.clamp(0, epRanges.length - 1)];
    final visibleEps = d.episodes.sublist(activeRange.$1, activeRange.$2);
    final titleSize = (screenW * 0.026).clamp(28.0, 38.0);
    final metaNodes = <Widget>[
      if (d.rating.isNotEmpty)
        Text(
          '★ ${d.rating}',
          style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: p.gold),
        ),
      if (d.tag.isNotEmpty)
        Container(
          height: 20,
          padding: const EdgeInsets.symmetric(horizontal: 6),
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: p.fill,
            borderRadius: BorderRadius.circular(4),
          ),
          child: Text(
            d.tag,
            style: TextStyle(fontSize: 11.5, fontWeight: FontWeight.w600, color: p.text2),
          ),
        ),
      if (total > 0)
        Text('全 $total 集', style: TextStyle(fontSize: 14, color: p.text3)),
      if (d.plays > 0)
        Text(formatPlays(d.plays), style: TextStyle(fontSize: 14, color: p.text3)),
    ];

    return SingleChildScrollView(
      child: Padding(
        padding: EdgeInsets.fromLTRB(gutter, 40, gutter, 80),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // .dback
            MouseRegion(
              cursor: SystemMouseCursors.click,
              child: GestureDetector(
                onTap: widget.onBack,
                child: SizedBox(
                  height: 32,
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(Icons.chevron_left_rounded, size: 19, color: p.text3),
                      const SizedBox(width: 2),
                      Text(
                        '返回',
                        style: TextStyle(
                          fontSize: 14,
                          color: p.text3,
                          fontWeight: FontWeight.w400,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
            const SizedBox(height: 20),

            // .dhead (align-items: flex-end; gap: 44px)
            Row(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                // .dposter (240px wide, 3:4)
                Container(
                  width: 240,
                  height: 320,
                  decoration: BoxDecoration(
                    color: p.skeleton,
                    borderRadius: BorderRadius.circular(12),
                  ),
                  clipBehavior: Clip.antiAlias,
                  child: d.cover.isNotEmpty
                      ? Image.network(
                          d.cover,
                          fit: BoxFit.cover,
                          cacheWidth: 420,
                          gaplessPlayback: true,
                          errorBuilder: (_, __, ___) => Icon(
                            Icons.movie_outlined,
                            size: 40,
                            color: p.text4,
                          ),
                        )
                      : Icon(Icons.movie_outlined, size: 40, color: p.text4),
                ),
                const SizedBox(width: 44),
                Expanded(
                  child: ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: 760),
                    child: Padding(
                      padding: const EdgeInsets.only(bottom: 4),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            d.title,
                            style: TextStyle(
                              fontSize: titleSize,
                              height: 1.2,
                              fontWeight: FontWeight.w700,
                              letterSpacing: -0.02 * titleSize,
                              color: p.text,
                            ),
                          ),
                          if (metaNodes.isNotEmpty) ...[
                            const SizedBox(height: 12),
                            Wrap(
                              spacing: 10,
                              runSpacing: 6,
                              crossAxisAlignment: WrapCrossAlignment.center,
                              children: [
                                for (int i = 0; i < metaNodes.length; i++) ...[
                                  if (i > 0)
                                    Container(
                                      width: 2,
                                      height: 2,
                                      decoration: BoxDecoration(
                                        color: p.text4,
                                        shape: BoxShape.circle,
                                      ),
                                    ),
                                  metaNodes[i],
                                ],
                              ],
                            ),
                          ],
                          if (d.intro.isNotEmpty) ...[
                            const SizedBox(height: 16),
                            ConstrainedBox(
                              constraints: const BoxConstraints(maxWidth: 720),
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    d.intro,
                                    maxLines: _introExpanded ? 20 : 3,
                                    overflow: TextOverflow.ellipsis,
                                    style: TextStyle(
                                      fontSize: 15,
                                      height: 1.75,
                                      color: p.text2,
                                    ),
                                  ),
                                  if (d.intro.length > 120) ...[
                                    const SizedBox(height: 4),
                                    MouseRegion(
                                      cursor: SystemMouseCursors.click,
                                      child: GestureDetector(
                                        onTap: () =>
                                            setState(() => _introExpanded = !_introExpanded),
                                        child: Text(
                                          _introExpanded ? '收起' : '展开',
                                          style: TextStyle(
                                            fontSize: 13.5,
                                            color: p.text3,
                                            fontWeight: FontWeight.w500,
                                          ),
                                        ),
                                      ),
                                    ),
                                  ],
                                ],
                              ),
                            ),
                          ],
                          if (d.actors.isNotEmpty) ...[
                            const SizedBox(height: 14),
                            Text.rich(
                              TextSpan(
                                children: [
                                  TextSpan(
                                    text: '主演   ',
                                    style: TextStyle(color: p.text3, fontSize: 14.5),
                                  ),
                                  for (int i = 0; i < d.actors.take(6).length; i++) ...[
                                    if (i > 0)
                                      TextSpan(
                                        text: '  /  ',
                                        style: TextStyle(color: p.text4, fontSize: 14.5),
                                      ),
                                    TextSpan(
                                      text: d.actors[i],
                                      style: TextStyle(color: p.text2, fontSize: 14.5),
                                    ),
                                  ],
                                ],
                              ),
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ],
                          const SizedBox(height: 24),

                          // .dacts
                          Wrap(
                            spacing: 10,
                            runSpacing: 10,
                            children: [
                              if (total > 0)
                                FilledButton.icon(
                                  style: FilledButton.styleFrom(
                                    backgroundColor: p.primary,
                                    foregroundColor: p.primaryOn,
                                    shape: const StadiumBorder(),
                                    minimumSize: const Size(0, 46),
                                    padding: const EdgeInsets.symmetric(horizontal: 24),
                                  ),
                                  onPressed: _playPrimary,
                                  icon: const Icon(Icons.play_arrow_rounded, size: 20),
                                  label: Text(
                                    prog != null
                                        ? '继续观看 第 ${cur + 1} 集'
                                        : '播放第 1 集',
                                    style: const TextStyle(
                                      fontSize: 15,
                                      fontWeight: FontWeight.w600,
                                    ),
                                  ),
                                )
                              else
                                FilledButton.icon(
                                  style: FilledButton.styleFrom(
                                    backgroundColor: _reserved ? p.fill : p.primary,
                                    foregroundColor: _reserved ? p.text : p.primaryOn,
                                    shape: const StadiumBorder(),
                                    minimumSize: const Size(0, 46),
                                    padding: const EdgeInsets.symmetric(horizontal: 24),
                                  ),
                                  onPressed: _toggleReservation,
                                  icon: Icon(
                                    _reserved
                                        ? Icons.check_rounded
                                        : Icons.notifications_none_rounded,
                                    size: 18,
                                    color: _reserved ? p.primary : p.primaryOn,
                                  ),
                                  label: Text(
                                    _reserved ? '已预约' : '预约',
                                    style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
                                  ),
                                ),
                              TextButton.icon(
                                style: TextButton.styleFrom(
                                  foregroundColor: p.text,
                                  backgroundColor: p.fill,
                                  shape: const StadiumBorder(),
                                  minimumSize: const Size(0, 46),
                                  padding: const EdgeInsets.symmetric(horizontal: 24),
                                ),
                                onPressed: _toggleFavorite,
                                icon: Icon(
                                  _favorite ? Icons.favorite_rounded : Icons.favorite_border_rounded,
                                  size: 18,
                                  color: _favorite ? p.danger : p.text,
                                ),
                                label: Text(
                                  _favorite ? '已收藏' : '收藏',
                                  style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
                                ),
                              ),
                              if (total > 0)
                                TextButton.icon(
                                  style: TextButton.styleFrom(
                                    foregroundColor: p.text2,
                                    backgroundColor: p.fill,
                                    shape: const StadiumBorder(),
                                    minimumSize: const Size(0, 46),
                                    padding: const EdgeInsets.symmetric(horizontal: 22),
                                  ),
                                  onPressed: _showBatchDownloadDialog,
                                  icon: const Icon(Icons.download_rounded, size: 18),
                                  label: const Text(
                                    '批量下载',
                                    style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
                                  ),
                                ),
                            ],
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ],
            ),

            // .dseasons — strictly `第 N 季` (+ `待上线` if soon), NO title suffix
            if (_seasons.length > 1) ...[
              const SizedBox(height: 44),
              Row(
                crossAxisAlignment: CrossAxisAlignment.baseline,
                textBaseline: TextBaseline.alphabetic,
                children: [
                  Text(
                    '选择季',
                    style: TextStyle(
                      fontSize: 20,
                      fontWeight: FontWeight.w700,
                      letterSpacing: -0.2,
                      color: p.text,
                    ),
                  ),
                  const SizedBox(width: 14),
                  Text(
                    '共 ${_seasons.length} 季',
                    style: TextStyle(fontSize: 14, color: p.text3),
                  ),
                ],
              ),
              const SizedBox(height: 14),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: _seasons.map((s) {
                  final isCurrent = s.id == d.id || s.id == widget.seriesId;
                  final soon = s.episodes <= 0;
                  return MouseRegion(
                    cursor: isCurrent ? SystemMouseCursors.basic : SystemMouseCursors.click,
                    child: GestureDetector(
                      onTap: () {
                        if (isCurrent) return;
                        if (soon) {
                          showSoonPopup(
                            context,
                            seriesId: s.id,
                            loggedIn: widget.loggedIn,
                            onOpenAuth: widget.onOpenAuth,
                            onToast: widget.onToast,
                            onOpenDetail: widget.onSwitchSeries,
                          );
                        } else {
                          widget.onSwitchSeries(s.id);
                        }
                      },
                      child: Container(
                        height: 38,
                        padding: const EdgeInsets.symmetric(horizontal: 14),
                        decoration: BoxDecoration(
                          color: isCurrent ? p.text : Colors.transparent,
                          borderRadius: BorderRadius.circular(8),
                          border: isCurrent ? null : Border.all(color: p.sep),
                        ),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Text(
                              '第 ${s.number} 季',
                              style: TextStyle(
                                fontSize: 14,
                                fontWeight: FontWeight.w500,
                                color: isCurrent ? p.bg : p.text,
                              ),
                            ),
                            if (soon) ...[
                              const SizedBox(width: 8),
                              Text(
                                '待上线',
                                style: TextStyle(
                                  fontSize: 12,
                                  color: isCurrent ? p.bg.withValues(alpha: 0.6) : p.primary,
                                ),
                              ),
                            ],
                          ],
                        ),
                      ),
                    ),
                  );
                }).toList(),
              ),
            ],

            // .dsec Episode grid (only numbers, clean .epn style)
            if (total > 0) ...[
              const SizedBox(height: 48),
              Row(
                children: [
                  Text(
                    '选集',
                    style: TextStyle(
                      fontSize: 20,
                      fontWeight: FontWeight.w700,
                      letterSpacing: -0.2,
                      color: p.text,
                    ),
                  ),
                  const SizedBox(width: 14),
                  Text(
                    '共 $total 集${prog != null ? ' · 上次看到第 ${cur + 1} 集' : ''}',
                    style: TextStyle(fontSize: 14, color: p.text3),
                  ),
                  const Spacer(),
                  if (epRanges.length > 1)
                    UnderlineTabBar(
                      labels: [
                        for (final r in epRanges) '${r.$1 + 1}–${r.$2}',
                      ],
                      activeIndex: _epRangeIndex.clamp(0, epRanges.length - 1),
                      onChanged: (idx) => setState(() => _epRangeIndex = idx),
                      gap: 12,
                      height: 34,
                      fontSize: 14,
                      bottomBorderColor: Colors.transparent,
                    ),
                ],
              ),
              const SizedBox(height: 18),
              GridView.builder(
                shrinkWrap: true,
                physics: const NeverScrollableScrollPhysics(),
                itemCount: visibleEps.length,
                gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
                  maxCrossAxisExtent: 74,
                  mainAxisSpacing: 8,
                  crossAxisSpacing: 8,
                  childAspectRatio: 1.5,
                ),
                itemBuilder: (_, i) {
                  final ep = visibleEps[i];
                  final realIndex = activeRange.$1 + i;
                  final isCur = cur >= 0 && realIndex == cur;
                  final isSeen = cur >= 0 && realIndex < cur;
                  final isDone = _downloadedVids.contains(ep.vid);

                  return _EpisodeTile(
                    episode: ep,
                    current: isCur,
                    seen: isSeen,
                    downloaded: isDone,
                    palette: p,
                    onPlay: () => widget.onPlayEpisode(
                      d,
                      _seasons,
                      realIndex,
                      (prog != null && realIndex == cur) ? prog.positionMs : 0,
                    ),
                    onDownload: () => _downloadOne(ep),
                  );
                },
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _EpisodeTile extends StatefulWidget {
  final EpisodeItem episode;
  final bool current;
  final bool seen;
  final bool downloaded;
  final LvguoPalette palette;
  final VoidCallback onPlay;
  final VoidCallback onDownload;

  const _EpisodeTile({
    required this.episode,
    required this.current,
    required this.seen,
    required this.downloaded,
    required this.palette,
    required this.onPlay,
    required this.onDownload,
  });

  @override
  State<_EpisodeTile> createState() => _EpisodeTileState();
}

class _EpisodeTileState extends State<_EpisodeTile> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    final p = widget.palette;
    return MouseRegion(
      cursor: SystemMouseCursors.click,
      onEnter: (_) => setState(() => _hover = true),
      onExit: (_) => setState(() => _hover = false),
      child: GestureDetector(
        onTap: widget.onPlay,
        onSecondaryTap: widget.onDownload,
        child: Container(
          height: 44,
          decoration: BoxDecoration(
            color: widget.current
                ? p.text
                : _hover
                    ? p.fill3
                    : p.fill2,
            borderRadius: BorderRadius.circular(8),
          ),
          child: Stack(
            children: [
              Center(
                child: Text(
                  '${widget.episode.index}',
                  style: TextStyle(
                    fontSize: 15,
                    fontWeight: widget.current ? FontWeight.w600 : FontWeight.w500,
                    color: widget.current
                        ? p.bg
                        : widget.seen
                            ? p.text4
                            : p.text,
                  ),
                ),
              ),
              if (widget.downloaded)
                Positioned(
                  right: 5,
                  bottom: 5,
                  child: Container(
                    width: 5,
                    height: 5,
                    decoration: BoxDecoration(
                      color: widget.current ? p.bg : p.primary,
                      shape: BoxShape.circle,
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}
