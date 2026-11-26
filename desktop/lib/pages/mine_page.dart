import 'package:flutter/material.dart';

import '../core/rust_bridge.dart';
import '../core/theme.dart';
import '../models/models.dart';
import '../widgets/series_card.dart';
import '../widgets/soon_popup.dart';
import '../widgets/web_primitives.dart';

/// 1:1 with `mine()` + `.minep` in web/ui/pc/app.css + app.js (borderless header directly on bg).
class MinePage extends StatefulWidget {
  final bool loggedIn;
  final UserProfile? profile;
  final VoidCallback onOpenAuth;
  final ValueChanged<String> onOpenDetail;
  final void Function(String seriesId, int episodeIndex, int positionMs, String title) onPlaySeries;
  final ValueChanged<String> onToast;

  const MinePage({
    super.key,
    required this.loggedIn,
    required this.profile,
    required this.onOpenAuth,
    required this.onOpenDetail,
    required this.onPlaySeries,
    required this.onToast,
  });

  @override
  State<MinePage> createState() => _MinePageState();
}

class _MinePageState extends State<MinePage> {
  int _tabIndex = 0; // 0: 观看历史, 1: 收藏, 2: 预约
  List<HistoryEntry> _history = const [];
  List<HistoryEntry> _favorites = const [];
  List<ReservationItem> _reservations = const [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _loadAll();
  }

  @override
  void didUpdateWidget(covariant MinePage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.loggedIn != widget.loggedIn ||
        oldWidget.profile?.uid != widget.profile?.uid) {
      _loadAll();
    }
  }

  Future<void> _loadAll() async {
    setState(() => _loading = true);
    try {
      final hRes = await RustBridge.instance.callMap('history.list', {'limit': 120});
      final h = (hRes['items'] as List<dynamic>? ?? const [])
          .map((e) => HistoryEntry.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList();

      List<HistoryEntry> fav = const [];
      List<ReservationItem> rsv = const [];
      if (widget.loggedIn) {
        final results = await Future.wait<Map<String, dynamic>>([
          RustBridge.instance.callMap('favorites.list').catchError((_) => <String, dynamic>{}),
          RustBridge.instance
              .callMap('reservations.list', {'online': false})
              .catchError((_) => <String, dynamic>{}),
          RustBridge.instance
              .callMap('reservations.list', {'online': true})
              .catchError((_) => <String, dynamic>{}),
        ]);
        fav = (results[0]['items'] as List<dynamic>? ?? const [])
            .map((e) => HistoryEntry.fromJson(Map<String, dynamic>.from(e as Map)))
            .toList();
        final upcoming = (results[1]['items'] as List<dynamic>? ?? const [])
            .map((e) => ReservationItem.fromJson(Map<String, dynamic>.from(e as Map)));
        final online = (results[2]['items'] as List<dynamic>? ?? const [])
            .map((e) => ReservationItem.fromJson(Map<String, dynamic>.from(e as Map)));
        final seenRsv = <String>{};
        final combinedRsv = <ReservationItem>[];
        for (final item in [...upcoming, ...online]) {
          if (item.seriesId.isNotEmpty && seenRsv.add(item.seriesId)) {
            combinedRsv.add(item);
          }
        }
        rsv = combinedRsv;
      }
      if (!mounted) return;
      setState(() {
        _history = h;
        _favorites = fav;
        _reservations = rsv;
        _loading = false;
      });
    } catch (_) {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<bool> _confirm(String title, String message, String confirmLabel) async {
    final p = LvguoPalette.of(context);
    final res = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: p.surface,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(22),
          side: BorderSide(color: p.sep),
        ),
        title: Text(title, style: TextStyle(fontSize: 19, fontWeight: FontWeight.w800, color: p.text)),
        content: Text(message, style: TextStyle(fontSize: 14.5, height: 1.6, color: p.text2)),
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
            child: Text(confirmLabel),
          ),
        ],
      ),
    );
    return res == true;
  }

  Future<void> _deleteHistory(HistoryEntry h) async {
    if (!await _confirm('删除这条观看记录？', '《${h.title}》将从观看历史中删除。', '删除')) return;
    try {
      await RustBridge.instance.callMap('history.delete', {'seriesId': h.seriesId});
      if (!mounted) return;
      setState(() {
        _history = _history.where((x) => x.seriesId != h.seriesId).toList();
      });
      widget.onToast('已删除');
    } catch (e) {
      widget.onToast('删除失败：$e');
    }
  }

  Future<void> _removeFavorite(HistoryEntry h) async {
    if (!await _confirm('取消收藏？', '《${h.title}》将从收藏中移除。', '取消收藏')) return;
    try {
      await RustBridge.instance.callMap('favorite.set', {
        'seriesId': h.seriesId,
        'add': false,
      });
      if (!mounted) return;
      setState(() {
        _favorites = _favorites.where((x) => x.seriesId != h.seriesId).toList();
      });
      widget.onToast('已取消收藏');
    } catch (e) {
      widget.onToast('操作失败：$e');
    }
  }

  Future<void> _cancelReservation(ReservationItem r) async {
    if (!await _confirm('取消预约？', '《${r.title}》上线后将不再提醒。', '取消预约')) return;
    try {
      await RustBridge.instance.callMap('reservation.set', {
        'seriesId': r.seriesId,
        'add': false,
      });
      if (!mounted) return;
      setState(() {
        _reservations = _reservations.where((x) => x.seriesId != r.seriesId).toList();
      });
      widget.onToast('已取消预约');
    } catch (e) {
      widget.onToast('操作失败：$e');
    }
  }

  @override
  Widget build(BuildContext context) {
    final p = LvguoPalette.of(context);
    final prof = widget.profile;
    final w = MediaQuery.of(context).size.width;
    final gutter = w <= 1366 ? 40.0 : 56.0;

    return CustomScrollView(
      slivers: [
        SliverPadding(
          padding: EdgeInsets.fromLTRB(gutter, 26, gutter, 18),
          sliver: SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.only(top: 6, bottom: 4),
              child: Row(
                children: [
                  Container(
                    width: 72,
                    height: 72,
                    decoration: BoxDecoration(
                      color: widget.loggedIn ? p.primarySoft : p.fill2,
                      shape: BoxShape.circle,
                    ),
                    clipBehavior: Clip.antiAlias,
                    child: prof != null && prof.avatar.isNotEmpty
                        ? Image.network(
                            prof.avatar,
                            fit: BoxFit.cover,
                            cacheWidth: 160,
                            gaplessPlayback: true,
                            errorBuilder: (_, __, ___) => Center(
                              child: Text(
                                prof.name.isNotEmpty ? prof.name[0] : '绿',
                                style: TextStyle(fontSize: 28, fontWeight: FontWeight.w800, color: p.primary),
                              ),
                            ),
                          )
                        : Center(
                            child: widget.loggedIn
                                ? Text(
                                    (prof?.name.isNotEmpty == true) ? prof!.name[0] : '绿',
                                    style: TextStyle(fontSize: 28, fontWeight: FontWeight.w800, color: p.primary),
                                  )
                                : Icon(Icons.person_outline_rounded, size: 32, color: p.text3),
                          ),
                  ),
                  const SizedBox(width: 20),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Text(
                              widget.loggedIn ? (prof?.name ?? '红果用户') : '本地记录',
                              style: TextStyle(
                                fontSize: 28,
                                fontWeight: FontWeight.w800,
                                letterSpacing: -0.56,
                                color: p.text,
                              ),
                            ),
                            if (prof?.vip == true) ...[
                              const SizedBox(width: 10),
                              Container(
                                padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
                                decoration: BoxDecoration(
                                  gradient: const LinearGradient(
                                    colors: [Color(0xFFF6D38B), Color(0xFFE8B25A)],
                                  ),
                                  borderRadius: BorderRadius.circular(6),
                                ),
                                child: const Text(
                                  'VIP',
                                  style: TextStyle(
                                    fontSize: 11,
                                    fontWeight: FontWeight.w800,
                                    color: Color(0xFF5A3A00),
                                    letterSpacing: 0.2,
                                  ),
                                ),
                              ),
                            ],
                          ],
                        ),
                        if (widget.loggedIn && (prof?.signature.isNotEmpty == true)) ...[
                          const SizedBox(height: 4),
                          Text(
                            prof!.signature,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(fontSize: 14.5, color: p.text3),
                          ),
                        ] else if (!widget.loggedIn) ...[
                          const SizedBox(height: 4),
                          Text(
                            '登录后可同步观看历史、收藏与预约',
                            style: TextStyle(fontSize: 14.5, color: p.text3),
                          ),
                        ],
                      ],
                    ),
                  ),
                  // .mstat
                  Row(
                    children: [
                      _StatBox(
                        count: _history.length,
                        label: '观看历史',
                        palette: p,
                        onTap: () => setState(() => _tabIndex = 0),
                      ),
                      const SizedBox(width: 32),
                      _StatBox(
                        count: _favorites.length,
                        label: '收藏',
                        palette: p,
                        onTap: () => setState(() => _tabIndex = 1),
                      ),
                      const SizedBox(width: 32),
                      _StatBox(
                        count: _reservations.length,
                        label: '预约',
                        palette: p,
                        onTap: () => setState(() => _tabIndex = 2),
                      ),
                    ],
                  ),
                  if (!widget.loggedIn) ...[
                    const SizedBox(width: 24),
                    FilledButton(
                      style: FilledButton.styleFrom(
                        backgroundColor: p.primary,
                        foregroundColor: p.primaryOn,
                        shape: const StadiumBorder(),
                        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
                      ),
                      onPressed: widget.onOpenAuth,
                      child: const Text('登录', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
                    ),
                  ],
                ],
              ),
            ),
          ),
        ),
        SliverPersistentHeader(
          pinned: true,
          delegate: _StickyMineTabsDelegate(
            gutter: gutter,
            palette: p,
            activeIndex: _tabIndex,
            onChanged: (i) => setState(() => _tabIndex = i),
          ),
        ),
        SliverPadding(
          padding: EdgeInsets.fromLTRB(gutter, 22, gutter, 64),
          sliver: _loading
              ? _buildResponsiveSkeletonSliverGrid(context)
              : _buildTabSliver(context),
        ),
      ],
    );
  }

  Widget _buildTabSliver(BuildContext context) {
    final p = LvguoPalette.of(context);

    if (_tabIndex == 0) {
      if (_history.isEmpty) {
        return SliverToBoxAdapter(
          child: _EmptyState(
            icon: Icons.history_rounded,
            text: '还没有观看记录',
            palette: p,
          ),
        );
      }
      return _buildResponsiveSliverGrid(
        context,
        itemCount: _history.length,
        itemBuilder: (_, i) {
          final h = _history[i];
          final pr = h.episodeCount > 0
              ? ((h.episodeIndex + (h.positionMs > 0 ? 0.5 : 0)) / h.episodeCount).clamp(0.0, 1.0)
              : null;
          return RiseCard(
            index: i,
            child: SeriesCard(
              item: SeriesItem(
                id: h.seriesId,
                title: h.title,
                cover: h.cover,
                episodes: h.episodeCount,
                plays: 0,
                intro: '',
                category: '',
                firstVid: h.vid,
                rank: 0,
              ),
              hideEpisodeBadge: true,
              progressFraction: pr,
              subtitle: '看到第 ${h.episodeIndex + 1} 集${h.episodeCount > 0 ? ' · 共 ${h.episodeCount} 集' : ''}',
              onTap: () => widget.onPlaySeries(
                h.seriesId,
                h.episodeIndex,
                h.positionMs,
                h.title,
              ),
              onDelete: () => _deleteHistory(h),
            ),
          );
        },
      );
    }

    if (!widget.loggedIn) {
      return SliverToBoxAdapter(
        child: Center(
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 72),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(Icons.lock_outline_rounded, size: 32, color: p.text4),
                const SizedBox(height: 12),
                Text(
                  _tabIndex == 1 ? '登录后查看收藏' : '登录后查看预约',
                  style: TextStyle(color: p.text2, fontSize: 15),
                ),
                const SizedBox(height: 16),
                FilledButton(
                  style: FilledButton.styleFrom(
                    backgroundColor: p.primary,
                    foregroundColor: p.primaryOn,
                    shape: const StadiumBorder(),
                  ),
                  onPressed: widget.onOpenAuth,
                  child: const Text('登录'),
                ),
              ],
            ),
          ),
        ),
      );
    }

    if (_tabIndex == 1) {
      if (_favorites.isEmpty) {
        return SliverToBoxAdapter(
          child: _EmptyState(
            icon: Icons.favorite_border_rounded,
            text: '还没有收藏',
            palette: p,
          ),
        );
      }
      return _buildResponsiveSliverGrid(
        context,
        itemCount: _favorites.length,
        itemBuilder: (_, i) {
          final f = _favorites[i];
          return RiseCard(
            index: i,
            child: SeriesCard(
              item: SeriesItem(
                id: f.seriesId,
                title: f.title,
                cover: f.cover,
                episodes: f.episodeCount,
                plays: 0,
                intro: '',
                category: '',
                firstVid: f.vid,
                rank: 0,
              ),
              onTap: () => widget.onOpenDetail(f.seriesId),
              onDelete: () => _removeFavorite(f),
            ),
          );
        },
      );
    }

    if (_reservations.isEmpty) {
      return SliverToBoxAdapter(
        child: _EmptyState(
          icon: Icons.notifications_none_rounded,
          text: '还没有预约',
          palette: p,
        ),
      );
    }
    return _buildResponsiveSliverGrid(
      context,
      itemCount: _reservations.length,
      itemBuilder: (_, i) {
        final r = _reservations[i];
        return RiseCard(
          index: i,
          child: Stack(
            children: [
              SeriesCard(
                item: SeriesItem(
                  id: r.seriesId,
                  title: r.title,
                  cover: r.cover,
                  episodes: r.online ? r.episodeCount : 0,
                  plays: 0,
                  intro: r.desc,
                  category: '',
                  firstVid: '',
                  rank: 0,
                ),
                hideEpisodeBadge: true,
                onTap: () {
                  if (r.online) {
                    widget.onOpenDetail(r.seriesId);
                  } else {
                    showSoonPopup(
                      context,
                      seriesId: r.seriesId,
                      initialItem: r,
                      loggedIn: widget.loggedIn,
                      onOpenAuth: widget.onOpenAuth,
                      onToast: widget.onToast,
                      onOpenDetail: widget.onOpenDetail,
                      onReservedChanged: (reserved) {
                        if (!reserved && mounted) {
                          setState(() {
                            _reservations = _reservations.where((x) => x.seriesId != r.seriesId).toList();
                          });
                        }
                      },
                    );
                  }
                },
                onDelete: () => _cancelReservation(r),
              ),
              Positioned(
                left: 8,
                top: 8,
                child: IgnorePointer(
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 3),
                    decoration: BoxDecoration(
                      color: r.online ? p.primary : const Color(0x990D1110),
                      borderRadius: BorderRadius.circular(99),
                    ),
                    child: Text(
                      r.online ? '已上线' : (r.publishText.isNotEmpty ? r.publishText : '待上线'),
                      style: const TextStyle(
                        fontSize: 11.5,
                        fontWeight: FontWeight.w700,
                        color: Colors.white,
                      ),
                    ),
                  ),
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  Widget _buildResponsiveSliverGrid(
    BuildContext context, {
    required int itemCount,
    required IndexedWidgetBuilder itemBuilder,
  }) {
    final w = MediaQuery.of(context).size.width;
    final gutter = w <= 1366 ? 40.0 : 56.0;
    final cols = w <= 760
        ? 3
        : w <= 1366
            ? 5
            : w >= 2000
                ? 8
                : w >= 1680
                    ? 7
                    : 6;
    final cardW = ((w - gutter * 2 - (cols - 1) * 20.0) / cols).clamp(120.0, 260.0);

    return SliverGrid(
      gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: cols,
        mainAxisSpacing: 32,
        crossAxisSpacing: 20,
        childAspectRatio: cardW / (cardW * (4 / 3) + 52),
      ),
      delegate: SliverChildBuilderDelegate(
        itemBuilder,
        childCount: itemCount,
      ),
    );
  }

  Widget _buildResponsiveSkeletonSliverGrid(BuildContext context) {
    final w = MediaQuery.of(context).size.width;
    final gutter = w <= 1366 ? 40.0 : 56.0;
    final cols = w <= 760
        ? 3
        : w <= 1366
            ? 5
            : w >= 2000
                ? 8
                : w >= 1680
                    ? 7
                    : 6;
    final cardW = ((w - gutter * 2 - (cols - 1) * 20.0) / cols).clamp(120.0, 260.0);

    return SliverGrid(
      gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: cols,
        mainAxisSpacing: 32,
        crossAxisSpacing: 20,
        childAspectRatio: cardW / (cardW * (4 / 3) + 52),
      ),
      delegate: SliverChildBuilderDelegate(
        (_, _) => const SeriesCardSkeleton(showSubtitle: true),
        childCount: cols * 2,
      ),
    );
  }
}

class _StickyMineTabsDelegate extends SliverPersistentHeaderDelegate {
  final double gutter;
  final LvguoPalette palette;
  final int activeIndex;
  final ValueChanged<int> onChanged;

  _StickyMineTabsDelegate({
    required this.gutter,
    required this.palette,
    required this.activeIndex,
    required this.onChanged,
  });

  @override
  double get minExtent => 44;

  @override
  double get maxExtent => 44;

  @override
  Widget build(BuildContext context, double shrinkOffset, bool overlapsContent) {
    final stuck = shrinkOffset > 0 || overlapsContent;
    return Container(
      color: palette.bg.withValues(alpha: 0.96),
      child: Stack(
        fit: StackFit.expand,
        children: [
          Padding(
            padding: EdgeInsets.symmetric(horizontal: gutter),
            child: Align(
              alignment: Alignment.centerLeft,
              child: UnderlineTabBar(
                labels: const ['观看历史', '收藏', '预约'],
                activeIndex: activeIndex,
                onChanged: onChanged,
                gap: 26,
                indicatorColor: palette.primary,
                bottomBorderColor: Colors.transparent,
              ),
            ),
          ),
          Positioned(
            left: stuck ? 0 : gutter,
            right: stuck ? 0 : gutter,
            bottom: 0,
            height: 1,
            child: ColoredBox(color: palette.sep),
          ),
        ],
      ),
    );
  }

  @override
  bool shouldRebuild(covariant _StickyMineTabsDelegate oldDelegate) {
    return oldDelegate.gutter != gutter ||
        oldDelegate.palette != palette ||
        oldDelegate.activeIndex != activeIndex;
  }
}

class _StatBox extends StatelessWidget {
  final int count;
  final String label;
  final LvguoPalette palette;
  final VoidCallback onTap;

  const _StatBox({
    required this.count,
    required this.label,
    required this.palette,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return MouseRegion(
      cursor: SystemMouseCursors.click,
      child: GestureDetector(
        onTap: onTap,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              '$count',
              style: TextStyle(
                fontSize: 26,
                height: 1.1,
                fontWeight: FontWeight.w800,
                letterSpacing: -0.52,
                color: palette.text,
              ),
            ),
            const SizedBox(height: 5),
            Text(
              label,
              style: TextStyle(fontSize: 12.5, color: palette.text3),
            ),
          ],
        ),
      ),
    );
  }
}

class _EmptyState extends StatelessWidget {
  final IconData icon;
  final String text;
  final LvguoPalette palette;

  const _EmptyState({
    required this.icon,
    required this.text,
    required this.palette,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 90),
      child: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 30, color: palette.text4),
            const SizedBox(height: 12),
            Text(text, style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600, color: palette.text2)),
          ],
        ),
      ),
    );
  }
}
