import 'dart:async';
import 'dart:math' as math;
import 'dart:ui';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../core/app_preloader.dart';
import '../core/rust_bridge.dart';
import '../core/theme.dart';
import '../models/models.dart';
import '../widgets/series_card.dart';
import '../widgets/web_primitives.dart';

class HomePage extends StatefulWidget {
  final ValueChanged<String> onOpenDetail;
  final void Function(String seriesId, int episodeIndex, int positionMs, String title) onPlaySeries;
  final ValueChanged<String> onGoRank;
  final VoidCallback onGoMine;
  final ValueChanged<bool> onScrollSolidChanged;
  final ValueChanged<String> onToast;

  const HomePage({
    super.key,
    required this.onOpenDetail,
    required this.onPlaySeries,
    required this.onGoRank,
    required this.onGoMine,
    required this.onScrollSolidChanged,
    required this.onToast,
  });

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with TickerProviderStateMixin {
  final ScrollController _scrollCtrl = ScrollController();

  bool _loading = true;
  String _error = '';

  List<SeriesItem> _heroItems = const [];
  int _heroIdx = 0;
  int _prevHeroIdx = -1;
  int _heroDir = 1;
  bool _heroHold = false;
  late final AnimationController _slideCtrl;
  late final AnimationController _heroProgressCtrl;

  List<HistoryEntry> _continueList = const [];
  List<SeriesItem> _hotShelf = const [];

  List<FeedChannel> _channels = const [];
  String _activeChannel = '16';
  List<SeriesItem> _forYouItems = const [];
  String _cell = '';
  String _session = '';
  int _nextOffset = 0;
  bool _hasMore = false;
  bool _loadingMore = false;
  bool _swapping = false;
  late final AnimationController _swapSpinCtrl;

  bool _lastSolid = true;

  @override
  void initState() {
    super.initState();
    _slideCtrl = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 780),
      value: 1.0,
    );
    _heroProgressCtrl = AnimationController(
      vsync: this,
      duration: const Duration(seconds: 8),
    )..addStatusListener((status) {
        if (status == AnimationStatus.completed && mounted && !_heroHold) {
          _showHeroSlide(_heroIdx + 1);
        }
      });

    _swapSpinCtrl = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 800),
    );

    _scrollCtrl.addListener(_onScroll);
    HardwareKeyboard.instance.addHandler(_onKey);
    _loadAll();
  }

  @override
  void dispose() {
    HardwareKeyboard.instance.removeHandler(_onKey);
    _scrollCtrl.removeListener(_onScroll);
    _scrollCtrl.dispose();
    _slideCtrl.dispose();
    _heroProgressCtrl.dispose();
    _swapSpinCtrl.dispose();
    super.dispose();
  }

  bool _onKey(KeyEvent ev) {
    if (ev is! KeyDownEvent || !mounted || _heroItems.length < 2) return false;
    final primary = FocusManager.instance.primaryFocus;
    if (primary?.context?.widget is EditableText) return false;
    if (_scrollCtrl.hasClients && _scrollCtrl.offset > 200) return false;

    if (ev.logicalKey == LogicalKeyboardKey.arrowLeft) {
      _showHeroSlide(_heroIdx - 1, dirOverride: -1);
      return true;
    } else if (ev.logicalKey == LogicalKeyboardKey.arrowRight) {
      _showHeroSlide(_heroIdx + 1, dirOverride: 1);
      return true;
    }
    return false;
  }

  void _onScroll() {
    if (!_scrollCtrl.hasClients) return;
    final pos = _scrollCtrl.position;
    final solid = true; // app.js: navbg.classList.toggle('on', curTab === 'home' || y > 4)
    if (solid != _lastSolid) {
      _lastSolid = solid;
      widget.onScrollSolidChanged(solid);
    }
    if (pos.pixels >= pos.maxScrollExtent - 500 && _hasMore && !_loadingMore) {
      _loadMore();
    }
  }

  void _showHeroSlide(int rawIndex, {int? dirOverride}) {
    final n = _heroItems.length;
    if (n < 2) return;
    final nx = ((rawIndex % n) + n) % n;
    if (nx == _heroIdx) {
      _heroProgressCtrl.forward(from: 0);
      return;
    }
    setState(() {
      _prevHeroIdx = _heroIdx;
      _heroDir = dirOverride ?? (rawIndex < _heroIdx ? -1 : 1);
      _heroIdx = nx;
    });
    _slideCtrl.forward(from: 0);
    if (!_heroHold) {
      _heroProgressCtrl.forward(from: 0);
    } else {
      _heroProgressCtrl.value = 0;
    }
  }

  void _setHeroHold(bool hold) {
    if (_heroHold == hold) return;
    setState(() => _heroHold = hold);
    if (hold) {
      _heroProgressCtrl.stop();
    } else if (_heroItems.length > 1) {
      _heroProgressCtrl.forward();
    }
  }

  Future<void> _loadAll() async {
    setState(() {
      _loading = true;
      _error = '';
    });
    try {
      final results = await Future.wait<Map<String, dynamic>>([
        AppPreloader.instance
            .getRank('ranklist_hot_sc')
            .catchError((_) => <String, dynamic>{}),
        AppPreloader.instance
            .getRank('ranklist_hot_play_sc')
            .catchError((_) => <String, dynamic>{}),
        AppPreloader.instance.getHome(),
      ]);
      if (!mounted) return;

      final heroRaw = (results[0]['items'] as List<dynamic>? ?? const [])
          .map((e) => SeriesItem.fromJson(Map<String, dynamic>.from(e as Map)))
          .take(5)
          .toList();
      final hotRaw = (results[1]['items'] as List<dynamic>? ?? const [])
          .map((e) => SeriesItem.fromJson(Map<String, dynamic>.from(e as Map)))
          .take(12)
          .toList();

      final homeRes = results[2];
      final cont = (homeRes['continue'] as List<dynamic>? ?? const [])
          .map((e) => HistoryEntry.fromJson(Map<String, dynamic>.from(e as Map)))
          .take(12)
          .toList();
      final chans = (homeRes['channels'] as List<dynamic>? ?? const [])
          .map((e) => FeedChannel.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList();
      final fy = Map<String, dynamic>.from(homeRes['forYou'] as Map? ?? const {});
      final fyItems = (fy['items'] as List<dynamic>? ?? const [])
          .map((e) => SeriesItem.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList();

      setState(() {
        _heroItems = heroRaw.isNotEmpty ? heroRaw : fyItems.take(5).toList();
        _heroIdx = 0;
        _prevHeroIdx = -1;
        _hotShelf = hotRaw;
        _continueList = cont;
        _channels = chans;
        if (chans.isNotEmpty && !chans.any((c) => c.id == _activeChannel)) {
          _activeChannel = chans.first.id;
        }
        _forYouItems = fyItems;
        _cell = homeRes['cell']?.toString() ?? '';
        _session =
            homeRes['session']?.toString() ?? fy['session']?.toString() ?? '';
        _nextOffset = (fy['next'] as num?)?.toInt() ?? fyItems.length;
        _hasMore = fy['more'] == true;
        _loading = false;
      });
      if (_heroItems.length > 1) {
        _heroProgressCtrl.forward(from: 0);
      }
      unawaited(_ensureFilledViewport());
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = e.toString();
      });
    }
  }

  Future<void> _switchChannel(String chId) async {
    if (_activeChannel == chId && !_swapping) return;
    setState(() {
      _activeChannel = chId;
      _swapping = true;
      _cell = '';
      _session = '';
      _nextOffset = 0;
    });
    _swapSpinCtrl.repeat();
    try {
      final res = await RustBridge.instance.callMap('foryou', {
        'channel': chId,
        'offset': 0,
      });
      if (!mounted) return;
      final page = Map<String, dynamic>.from(res['page'] as Map? ?? const {});
      final items = (page['items'] as List<dynamic>? ?? const [])
          .map((e) => SeriesItem.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList();
      setState(() {
        _forYouItems = items;
        _cell = res['cell']?.toString() ?? '';
        _session = res['session']?.toString() ?? page['session']?.toString() ?? '';
        _nextOffset = (page['next'] as num?)?.toInt() ?? items.length;
        _hasMore = page['more'] == true;
        _swapping = false;
      });
      unawaited(_ensureFilledViewport());
    } catch (e) {
      if (mounted) {
        setState(() => _swapping = false);
        widget.onToast('切换频道失败：$e');
      }
    } finally {
      _swapSpinCtrl.stop();
      _swapSpinCtrl.value = 0;
    }
  }

  Future<void> _swapBatch() async {
    if (_swapping) return;
    setState(() => _swapping = true);
    _swapSpinCtrl.repeat();
    try {
      final res = await RustBridge.instance.callMap('foryou', {
        'channel': _activeChannel,
        'offset': _hasMore ? _nextOffset : 0,
        if (_hasMore && _cell.isNotEmpty) 'cell': _cell,
        if (_hasMore && _session.isNotEmpty) 'session': _session,
      });
      if (!mounted) return;
      final page = Map<String, dynamic>.from(res['page'] as Map? ?? const {});
      final items = (page['items'] as List<dynamic>? ?? const [])
          .map((e) => SeriesItem.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList();
      if (items.isNotEmpty) {
        setState(() {
          _forYouItems = items;
          _cell = res['cell']?.toString() ?? _cell;
          _session = res['session']?.toString() ?? page['session']?.toString() ?? _session;
          _nextOffset = (page['next'] as num?)?.toInt() ?? (_nextOffset + items.length);
          _hasMore = page['more'] == true;
          _swapping = false;
        });
        unawaited(_ensureFilledViewport());
      } else {
        setState(() => _swapping = false);
        await _switchChannel(_activeChannel);
      }
    } catch (e) {
      if (mounted) {
        setState(() => _swapping = false);
        widget.onToast('换一批失败：$e');
      }
    } finally {
      _swapSpinCtrl.stop();
      _swapSpinCtrl.value = 0;
    }
  }

  Future<void> _loadMore() async {
    if (_loadingMore || !_hasMore) return;
    setState(() => _loadingMore = true);
    try {
      final res = await RustBridge.instance.callMap('foryou', {
        'channel': _activeChannel,
        'offset': _nextOffset,
        if (_cell.isNotEmpty) 'cell': _cell,
        if (_session.isNotEmpty) 'session': _session,
      });
      if (!mounted) return;
      final page = Map<String, dynamic>.from(res['page'] as Map? ?? const {});
      final items = (page['items'] as List<dynamic>? ?? const [])
          .map((e) => SeriesItem.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList();
      final seen = _forYouItems.map((e) => e.id).toSet();
      setState(() {
        _forYouItems = [
          ..._forYouItems,
          ...items.where((e) => !seen.contains(e.id)),
        ];
        _cell = res['cell']?.toString() ?? _cell;
        _session = res['session']?.toString() ?? page['session']?.toString() ?? _session;
        _nextOffset = (page['next'] as num?)?.toInt() ?? (_nextOffset + items.length);
        _hasMore = page['more'] == true && items.isNotEmpty;
        _loadingMore = false;
      });
    } catch (_) {
      if (mounted) setState(() => _loadingMore = false);
    }
  }

  Future<void> _ensureFilledViewport() async {
    for (int step = 0; step < 5; step++) {
      if (!mounted || !_hasMore) return;
      final cols = _colsForWidth(MediaQuery.of(context).size.width);
      if (_forYouItems.length >= cols * 3) return;
      await _loadMore();
    }
  }

  Future<void> _deleteContinue(HistoryEntry h) async {
    final p = LvguoPalette.of(context);
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: p.surface,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(22),
          side: BorderSide(color: p.sep),
        ),
        title: Text(
          '删除这条观看记录？',
          style: TextStyle(color: p.text, fontSize: 19, fontWeight: FontWeight.w800),
        ),
        content: Text(
          '《${h.title}》将从当前观看历史中删除。',
          style: TextStyle(color: p.text2, fontSize: 14.5, height: 1.6),
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
    );
    if (ok != true) return;
    try {
      await RustBridge.instance.callMap('history.delete', {'seriesId': h.seriesId});
      if (!mounted) return;
      setState(() {
        _continueList = _continueList.where((x) => x.seriesId != h.seriesId).toList();
      });
      widget.onToast('已删除');
    } catch (e) {
      widget.onToast('移除失败：$e');
    }
  }

  double _gutter(double w) => w <= 1366 ? 40.0 : 56.0;

  int _colsForWidth(double w) {
    if (w <= 760) return 3;
    if (w <= 1366) return 5;
    if (w >= 2000) return 8;
    if (w >= 1680) return 7;
    return 6;
  }

  @override
  Widget build(BuildContext context) {
    final p = LvguoPalette.of(context);
    final size = MediaQuery.of(context).size;
    final gutter = _gutter(size.width);
    final cols = _colsForWidth(size.width);
    final heroHeight = (size.height * 0.62).clamp(480.0, 560.0);
    final contentW = (size.width - gutter * 2).clamp(320.0, 3000.0);
    final cardW = ((contentW - (cols - 1) * 20.0) / cols).clamp(120.0, 240.0);

    if (_loading) {
      return HomeSkeleton(
        gutter: gutter,
        heroHeight: heroHeight,
        cols: cols,
        cardW: cardW,
      );
    }
    if (_error.isNotEmpty) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.refresh_rounded, size: 32, color: p.text4),
            const SizedBox(height: 12),
            Text('加载失败', style: TextStyle(color: p.text2, fontSize: 16, fontWeight: FontWeight.w600)),
            const SizedBox(height: 4),
            Text(_error, style: TextStyle(color: p.text3, fontSize: 14)),
            const SizedBox(height: 20),
            FilledButton.icon(
              style: FilledButton.styleFrom(
                backgroundColor: p.primary,
                foregroundColor: p.primaryOn,
                shape: const StadiumBorder(),
              ),
              onPressed: _loadAll,
              icon: const Icon(Icons.refresh_rounded, size: 17),
              label: const Text('重试'),
            ),
          ],
        ),
      );
    }

    final shelfHeight = cardW * (4 / 3) + 58;

    return CustomScrollView(
      controller: _scrollCtrl,
      slivers: [
        SliverToBoxAdapter(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (_heroItems.isNotEmpty)
                RepaintBoundary(
                  child: _buildHero(context, heroHeight, gutter, size.width),
                )
              else
                const SizedBox(height: 84),
              const SizedBox(height: 16),
              if (_continueList.isNotEmpty) ...[
                Padding(
                  padding: EdgeInsets.symmetric(horizontal: gutter),
                  child: _buildSectionHeader(
                    context,
                    '继续观看',
                    actionLabel: '查看全部',
                    onAction: widget.onGoMine,
                  ),
                ),
                const SizedBox(height: 14),
                _HorizontalShelf(
                  gutter: gutter,
                  cardWidth: cardW,
                  height: shelfHeight,
                  itemCount: _continueList.length,
                  itemBuilder: (_, i) {
                    final h = _continueList[i];
                    final pr = h.episodeCount > 0
                        ? ((h.episodeIndex + (h.positionMs > 0 ? 0.5 : 0)) / h.episodeCount)
                            .clamp(0.0, 1.0)
                        : 0.0;
                    final sub = '看到第 ${h.episodeIndex + 1} 集'
                        '${h.episodeCount > 0 ? ' / 共 ${h.episodeCount} 集' : ''}';
                    return SizedBox(
                      width: cardW,
                      child: RiseCard(
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
                          subtitle: sub,
                          onTap: () => widget.onPlaySeries(
                            h.seriesId,
                            h.episodeIndex,
                            h.positionMs,
                            h.title,
                          ),
                          onDelete: () => _deleteContinue(h),
                        ),
                      ),
                    );
                  },
                ),
                const SizedBox(height: 36),
              ],
              if (_hotShelf.isNotEmpty) ...[
                Padding(
                  padding: EdgeInsets.symmetric(horizontal: gutter),
                  child: _buildSectionHeader(
                    context,
                    '热播榜',
                    actionLabel: '查看全部',
                    onAction: () => widget.onGoRank('ranklist_hot_play_sc'),
                  ),
                ),
                const SizedBox(height: 14),
                _HorizontalShelf(
                  gutter: gutter,
                  cardWidth: cardW,
                  height: shelfHeight - 14,
                  itemCount: _hotShelf.length,
                  itemBuilder: (_, i) => SizedBox(
                    width: cardW,
                    child: RiseCard(
                      index: i,
                      child: SeriesCard(
                        item: _hotShelf[i],
                        rankBadge: i + 1,
                        onTap: () => widget.onOpenDetail(_hotShelf[i].id),
                      ),
                    ),
                  ),
                ),
                const SizedBox(height: 36),
              ],
              Padding(
                padding: EdgeInsets.symmetric(horizontal: gutter),
                child: _buildForYouHeader(context),
              ),
              const SizedBox(height: 16),
            ],
          ),
        ),
        SliverPadding(
          padding: EdgeInsets.fromLTRB(gutter, 0, gutter, 48),
          sliver: SliverGrid(
            gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
              crossAxisCount: cols,
              mainAxisSpacing: 28,
              crossAxisSpacing: 20,
              childAspectRatio: cardW / (cardW * (4 / 3) + 42),
            ),
            delegate: SliverChildBuilderDelegate(
              (_, i) {
                if (i < _forYouItems.length) {
                  final item = _forYouItems[i];
                  return RiseCard(
                    key: ValueKey('fy_${_activeChannel}_${item.id}_$i'),
                    index: i,
                    child: SeriesCard(
                      item: item,
                      onTap: () => widget.onOpenDetail(item.id),
                    ),
                  );
                }
                return const SeriesCardSkeleton();
              },
              childCount: _forYouItems.length +
                  (_loadingMore
                      ? ((cols - (_forYouItems.length % cols)) % cols) + cols
                      : 0),
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildHero(BuildContext context, double height, double gutter, double screenW) {
    final p = LvguoPalette.of(context);
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final curItem = _heroItems[_heroIdx.clamp(0, _heroItems.length - 1)];
    final prevItem = (_prevHeroIdx >= 0 && _prevHeroIdx < _heroItems.length && _prevHeroIdx != _heroIdx)
        ? _heroItems[_prevHeroIdx]
        : null;

    return ClipRect(
      clipBehavior: Clip.hardEdge,
      child: SizedBox(
        height: height,
        width: double.infinity,
        child: Stack(
          fit: StackFit.expand,
          children: [
            Positioned.fill(child: ColoredBox(color: p.bg)),
            // Subtle static ambient backdrop rendered cleanly inside ClipRect (AnimatedSwitcher avoids per-frame re-blur)
            Positioned.fill(
              child: ClipRect(
                clipBehavior: Clip.hardEdge,
                child: AnimatedSwitcher(
                  duration: const Duration(milliseconds: 500),
                  child: curItem.cover.isEmpty
                      ? const SizedBox.shrink()
                      : SizedBox.expand(
                          key: ValueKey('hero_bg_${curItem.id}'),
                          child: Opacity(
                            opacity: isDark ? 0.24 : 0.16,
                            child: Transform.scale(
                              scale: 1.1,
                              child: RepaintBoundary(
                                child: ImageFiltered(
                                  imageFilter: ImageFilter.blur(sigmaX: 24, sigmaY: 24),
                                  child: Image.network(
                                    curItem.cover,
                                    fit: BoxFit.cover,
                                    alignment: const Alignment(0, -0.4),
                                    cacheWidth: 120,
                                    filterQuality: FilterQuality.low,
                                    gaplessPlayback: true,
                                    errorBuilder: (_, __, ___) => const SizedBox.shrink(),
                                  ),
                                ),
                              ),
                            ),
                          ),
                        ),
                ),
              ),
            ),
            if (prevItem != null && _slideCtrl.isAnimating)
              AnimatedBuilder(
                animation: _slideCtrl,
                builder: (_, __) {
                  final t = const Cubic(0.22, 1, 0.36, 1).transform(_slideCtrl.value);
                  final op = (1.0 - t).clamp(0.0, 1.0);
                  final dx = -0.04 * _heroDir * t * screenW;
                  return Opacity(
                    opacity: op,
                    child: Transform.translate(
                      offset: Offset(dx, 0),
                      child: _buildHeroSlideContent(
                        context,
                        prevItem,
                        height,
                        gutter,
                        screenW,
                        staggerValue: 1.0 - t,
                        artParallax: 1.0,
                      ),
                    ),
                  );
                },
              ),
            AnimatedBuilder(
              animation: _slideCtrl,
              builder: (_, __) {
                final raw = _slideCtrl.value;
                final slideT = const Cubic(0.22, 1, 0.36, 1).transform(raw);
                final artT = const Cubic(0.16, 1, 0.3, 1).transform(raw);
                final op = raw >= 1.0 ? 1.0 : slideT;
                final dx = prevItem == null || raw >= 1.0
                    ? 0.0
                    : 0.05 * _heroDir * (1.0 - slideT) * screenW;
                return Opacity(
                  opacity: op,
                  child: Transform.translate(
                    offset: Offset(dx, 0),
                    child: _buildHeroSlideContent(
                      context,
                      curItem,
                      height,
                      gutter,
                      screenW,
                      staggerValue: prevItem == null ? 1.0 : raw,
                      artParallax: prevItem == null ? 1.0 : artT,
                    ),
                  ),
                );
              },
            ),
            // .hnav bottom-left 8s animated progress dots
            if (_heroItems.length > 1)
              Positioned(
                left: gutter,
                bottom: 28,
                child: MouseRegion(
                  onEnter: (_) => _setHeroHold(true),
                  onExit: (_) => _setHeroHold(false),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      for (int i = 0; i < _heroItems.length; i++)
                        MouseRegion(
                          cursor: SystemMouseCursors.click,
                          child: GestureDetector(
                            behavior: HitTestBehavior.opaque,
                            onTap: () => _showHeroSlide(i),
                            child: Container(
                              height: 22,
                              margin: const EdgeInsets.only(right: 6),
                              alignment: Alignment.center,
                              child: AnimatedContainer(
                                duration: const Duration(milliseconds: 700),
                                curve: const Cubic(0.22, 1, 0.36, 1),
                                width: i == _heroIdx ? 36 : 8,
                                height: 8,
                                decoration: BoxDecoration(
                                  color: p.fill3,
                                  borderRadius: BorderRadius.circular(99),
                                ),
                                clipBehavior: Clip.antiAlias,
                                child: i == _heroIdx
                                    ? AnimatedBuilder(
                                        animation: _heroProgressCtrl,
                                        builder: (_, __) => Align(
                                          alignment: Alignment.centerLeft,
                                          child: FractionallySizedBox(
                                            widthFactor: _heroProgressCtrl.value.clamp(0.0, 1.0),
                                            heightFactor: 1.0,
                                            child: ColoredBox(color: p.primary),
                                          ),
                                        ),
                                      )
                                    : const SizedBox.shrink(),
                              ),
                            ),
                          ),
                        ),
                    ],
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _buildHeroSlideContent(
    BuildContext context,
    SeriesItem item,
    double height,
    double gutter,
    double screenW, {
    required double staggerValue,
    required double artParallax,
  }) {
    final p = LvguoPalette.of(context);
    final artW = math.min(screenW * 0.62, height * 1.08);
    final txtW = math.min(600.0, screenW * 0.48);
    final titleSize = (screenW * 0.030).clamp(30.0, 44.0);

    final cats = item.category
        .split(',')
        .map((e) => e.trim())
        .where((e) => e.isNotEmpty)
        .take(3)
        .toList();

    final metaItems = <String>[
      if (item.episodes > 0) '全 ${item.episodes} 集',
      if (item.plays > 0) formatPlays(item.plays),
      ...cats,
    ];

    Widget staggerChild(double delayFrac, Widget child) {
      final t = ((staggerValue - delayFrac) / 0.55).clamp(0.0, 1.0);
      final eased = const Cubic(0.16, 1, 0.3, 1).transform(t);
      return Opacity(
        opacity: eased,
        child: Transform.translate(
          offset: Offset(0, 14.0 * (1.0 - eased)),
          child: child,
        ),
      );
    }

    return Stack(
      fit: StackFit.expand,
      children: [
        // .art crisp right-side artwork with left gradient mask
        if (item.cover.isNotEmpty)
          Positioned(
            right: 0,
            top: 0,
            bottom: 0,
            width: artW,
            child: ClipRect(
              clipBehavior: Clip.hardEdge,
              child: MouseRegion(
                cursor: SystemMouseCursors.click,
                child: GestureDetector(
                  onTap: () => widget.onOpenDetail(item.id),
                  child: Transform.translate(
                    offset: Offset(0.03 * _heroDir * (1.0 - artParallax) * screenW, 0),
                    child: Transform.scale(
                      scale: 1.0 + 0.04 * (1.0 - artParallax),
                      child: ShaderMask(
                        shaderCallback: (bounds) => const LinearGradient(
                          begin: Alignment.centerLeft,
                          end: Alignment.centerRight,
                          colors: [Colors.transparent, Colors.white],
                          stops: [0.0, 0.42],
                        ).createShader(bounds),
                        blendMode: BlendMode.dstIn,
                        child: Image.network(
                          item.cover,
                          fit: BoxFit.cover,
                          alignment: const Alignment(0, -0.56),
                          cacheWidth: 960,
                          filterQuality: FilterQuality.medium,
                          gaplessPlayback: true,
                          errorBuilder: (_, __, ___) => const SizedBox.shrink(),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),

        // .scrim left text protection + bottom fade into shelves
        Positioned.fill(
          child: IgnorePointer(
            child: DecoratedBox(
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.centerLeft,
                  end: Alignment.centerRight,
                  colors: [
                    p.bg.withValues(alpha: 0.88),
                    p.bg.withValues(alpha: 0.52),
                    Colors.transparent,
                  ],
                  stops: const [0.0, 0.38, 0.66],
                ),
              ),
            ),
          ),
        ),
        Positioned.fill(
          child: IgnorePointer(
            child: DecoratedBox(
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.bottomCenter,
                  end: Alignment.topCenter,
                  colors: [
                    p.bg,
                    p.bg.withValues(alpha: 0.0),
                  ],
                  stops: const [0.0, 0.26],
                ),
              ),
            ),
          ),
        ),

        // .txt
        Positioned(
          left: gutter,
          top: 104,
          width: txtW,
          child: MouseRegion(
            onEnter: (_) => _setHeroHold(true),
            onExit: (_) => _setHeroHold(false),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                staggerChild(
                  0.06,
                  Text(
                    '推荐榜',
                    style: TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.w700,
                      letterSpacing: 1.0,
                      color: p.primary,
                    ),
                  ),
                ),
                const SizedBox(height: 12),
                staggerChild(
                  0.12,
                  Text(
                    item.title,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: titleSize,
                      height: 1.15,
                      fontWeight: FontWeight.w800,
                      letterSpacing: -0.03 * titleSize,
                      color: p.text,
                    ),
                  ),
                ),
                if (metaItems.isNotEmpty) ...[
                  const SizedBox(height: 14),
                  staggerChild(
                    0.18,
                    Wrap(
                      crossAxisAlignment: WrapCrossAlignment.center,
                      spacing: 10,
                      runSpacing: 6,
                      children: [
                        for (int i = 0; i < metaItems.length; i++) ...[
                          if (i > 0)
                            Container(
                              width: 3,
                              height: 3,
                              decoration: BoxDecoration(
                                color: p.text4,
                                shape: BoxShape.circle,
                              ),
                            ),
                          Text(
                            metaItems[i],
                            style: TextStyle(
                              fontSize: 15,
                              fontWeight: FontWeight.w500,
                              color: p.text2,
                            ),
                          ),
                        ],
                      ],
                    ),
                  ),
                ],
                if (item.intro.isNotEmpty) ...[
                  const SizedBox(height: 12),
                  staggerChild(
                    0.24,
                    Text(
                      item.intro,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: 15,
                        height: 1.75,
                        color: p.text2,
                      ),
                    ),
                  ),
                ],
                const SizedBox(height: 24),
                staggerChild(
                  0.30,
                  Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      _HeroPlayButton(
                        palette: p,
                        onTap: () => widget.onPlaySeries(item.id, 0, 0, item.title),
                      ),
                      const SizedBox(width: 12),
                      _HeroGlassButton(
                        palette: p,
                        onTap: () => widget.onOpenDetail(item.id),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildSectionHeader(
    BuildContext context,
    String title, {
    String? actionLabel,
    VoidCallback? onAction,
  }) {
    final p = LvguoPalette.of(context);
    return Row(
      crossAxisAlignment: CrossAxisAlignment.baseline,
      textBaseline: TextBaseline.alphabetic,
      children: [
        Text(
          title,
          style: TextStyle(
            fontSize: 20,
            fontWeight: FontWeight.w700,
            letterSpacing: -0.2,
            color: p.text,
          ),
        ),
        if (actionLabel != null && onAction != null) ...[
          const SizedBox(width: 14),
          MouseRegion(
            cursor: SystemMouseCursors.click,
            child: GestureDetector(
              onTap: onAction,
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    actionLabel,
                    style: TextStyle(
                      fontSize: 13.5,
                      color: p.text3,
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                  const SizedBox(width: 2),
                  Icon(Icons.chevron_right_rounded, size: 15, color: p.text3),
                ],
              ),
            ),
          ),
        ],
      ],
    );
  }

  Widget _buildForYouHeader(BuildContext context) {
    final p = LvguoPalette.of(context);
    return Row(
      children: [
        Text(
          '为你推荐',
          style: TextStyle(
            fontSize: 20,
            fontWeight: FontWeight.w700,
            letterSpacing: -0.2,
            color: p.text,
          ),
        ),
        const SizedBox(width: 18),
        if (_channels.length > 1)
          Expanded(
            child: SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: Row(
                children: _channels.map((ch) {
                  return Padding(
                    padding: const EdgeInsets.only(right: 6),
                    child: WebChip(
                      label: ch.label,
                      active: ch.id == _activeChannel,
                      onTap: () => _switchChannel(ch.id),
                    ),
                  );
                }).toList(),
              ),
            ),
          )
        else
          const Spacer(),
        const SizedBox(width: 12),
        MouseRegion(
          cursor: _swapping ? SystemMouseCursors.wait : SystemMouseCursors.click,
          child: GestureDetector(
            onTap: _swapBatch,
            child: Opacity(
              opacity: _swapping ? 0.55 : 1.0,
              child: Container(
                height: 32,
                padding: const EdgeInsets.symmetric(horizontal: 10),
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      '换一批',
                      style: TextStyle(
                        fontSize: 13.5,
                        color: p.text3,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                    const SizedBox(width: 6),
                    RotationTransition(
                      turns: _swapSpinCtrl,
                      child: Icon(Icons.refresh_rounded, size: 15, color: p.text3),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }
}

class _HeroPlayButton extends StatefulWidget {
  final LvguoPalette palette;
  final VoidCallback onTap;

  const _HeroPlayButton({required this.palette, required this.onTap});

  @override
  State<_HeroPlayButton> createState() => _HeroPlayButtonState();
}

class _HeroPlayButtonState extends State<_HeroPlayButton> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    final p = widget.palette;
    return MouseRegion(
      cursor: SystemMouseCursors.click,
      onEnter: (_) => setState(() => _hover = true),
      onExit: (_) => setState(() => _hover = false),
      child: GestureDetector(
        onTap: widget.onTap,
        child: AnimatedScale(
          scale: _hover ? 1.03 : 1.0,
          duration: const Duration(milliseconds: 280),
          curve: const Cubic(0.22, 1, 0.36, 1),
          child: Container(
            height: 48,
            padding: const EdgeInsets.fromLTRB(22, 0, 28, 0),
            decoration: BoxDecoration(
              color: p.primary,
              borderRadius: BorderRadius.circular(99),
              boxShadow: [
                BoxShadow(
                  color: p.primary.withValues(alpha: 0.35),
                  blurRadius: 22,
                  spreadRadius: -4,
                  offset: const Offset(0, 8),
                ),
              ],
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(Icons.play_arrow_rounded, size: 20, color: p.primaryOn),
                const SizedBox(width: 8),
                Text(
                  '播放',
                  style: TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.w700,
                    color: p.primaryOn,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _HeroGlassButton extends StatefulWidget {
  final LvguoPalette palette;
  final VoidCallback onTap;

  const _HeroGlassButton({required this.palette, required this.onTap});

  @override
  State<_HeroGlassButton> createState() => _HeroGlassButtonState();
}

class _HeroGlassButtonState extends State<_HeroGlassButton> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    final p = widget.palette;
    return MouseRegion(
      cursor: SystemMouseCursors.click,
      onEnter: (_) => setState(() => _hover = true),
      onExit: (_) => setState(() => _hover = false),
      child: GestureDetector(
        onTap: widget.onTap,
        child: AnimatedScale(
          scale: _hover ? 1.03 : 1.0,
          duration: const Duration(milliseconds: 280),
          curve: const Cubic(0.22, 1, 0.36, 1),
          child: Container(
            height: 48,
            padding: const EdgeInsets.symmetric(horizontal: 22),
            decoration: BoxDecoration(
              color: p.surface,
              borderRadius: BorderRadius.circular(99),
              border: Border.all(color: p.sep),
              boxShadow: [
                BoxShadow(
                  color: Colors.black.withValues(alpha: _hover ? 0.14 : 0.08),
                  blurRadius: _hover ? 18 : 10,
                  offset: const Offset(0, 4),
                ),
              ],
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  '详情',
                  style: TextStyle(
                    fontSize: 15.5,
                    fontWeight: FontWeight.w600,
                    color: p.text,
                  ),
                ),
                const SizedBox(width: 4),
                Icon(Icons.chevron_right_rounded, size: 18, color: p.text),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _HorizontalShelf extends StatefulWidget {
  final double gutter;
  final double cardWidth;
  final double height;
  final int itemCount;
  final IndexedWidgetBuilder itemBuilder;

  const _HorizontalShelf({
    required this.gutter,
    required this.cardWidth,
    required this.height,
    required this.itemCount,
    required this.itemBuilder,
  });

  @override
  State<_HorizontalShelf> createState() => _HorizontalShelfState();
}

class _HorizontalShelfState extends State<_HorizontalShelf> {
  final ScrollController _ctrl = ScrollController();
  bool _canLeft = false;
  bool _canRight = false;

  @override
  void initState() {
    super.initState();
    _ctrl.addListener(_syncArrows);
    WidgetsBinding.instance.addPostFrameCallback((_) => _syncArrows());
  }

  @override
  void didUpdateWidget(covariant _HorizontalShelf oldWidget) {
    super.didUpdateWidget(oldWidget);
    WidgetsBinding.instance.addPostFrameCallback((_) => _syncArrows());
  }

  @override
  void dispose() {
    _ctrl.removeListener(_syncArrows);
    _ctrl.dispose();
    super.dispose();
  }

  void _syncArrows() {
    if (!mounted || !_ctrl.hasClients) return;
    final pos = _ctrl.position;
    final left = pos.pixels > 4;
    final right = pos.pixels < pos.maxScrollExtent - 4;
    if (left != _canLeft || right != _canRight) {
      setState(() {
        _canLeft = left;
        _canRight = right;
      });
    }
  }

  void _glide(int dir) {
    if (!_ctrl.hasClients) return;
    final step = widget.cardWidth + 20.0;
    final per = math.max(1, ((_ctrl.position.viewportDimension - widget.gutter * 2) / step).floor());
    final rawTarget = ((_ctrl.offset / step).round() + dir * per) * step;
    final target = rawTarget.clamp(0.0, _ctrl.position.maxScrollExtent);
    _ctrl.animateTo(
      target,
      duration: const Duration(milliseconds: 620),
      curve: Curves.easeInOutCubic,
    );
  }

  @override
  Widget build(BuildContext context) {
    final p = LvguoPalette.of(context);
    final arrowTop = (widget.cardWidth * (4 / 3)) * 0.5 - 20;

    return SizedBox(
      height: widget.height,
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          ScrollConfiguration(
            behavior: ScrollConfiguration.of(context).copyWith(
              dragDevices: {
                PointerDeviceKind.touch,
                PointerDeviceKind.mouse,
                PointerDeviceKind.trackpad,
              },
            ),
            child: ListView.separated(
              controller: _ctrl,
              padding: EdgeInsets.symmetric(horizontal: widget.gutter),
              scrollDirection: Axis.horizontal,
              itemCount: widget.itemCount,
              separatorBuilder: (_, __) => const SizedBox(width: 20),
              itemBuilder: widget.itemBuilder,
            ),
          ),
          if (_canLeft)
            Positioned(
              left: math.max(8.0, widget.gutter - 20),
              top: arrowTop,
              child: _ShelfArrow(
                palette: p,
                icon: Icons.chevron_left_rounded,
                onTap: () => _glide(-1),
              ),
            ),
          if (_canRight)
            Positioned(
              right: math.max(8.0, widget.gutter - 20),
              top: arrowTop,
              child: _ShelfArrow(
                palette: p,
                icon: Icons.chevron_right_rounded,
                onTap: () => _glide(1),
              ),
            ),
        ],
      ),
    );
  }
}

class _ShelfArrow extends StatefulWidget {
  final LvguoPalette palette;
  final IconData icon;
  final VoidCallback onTap;

  const _ShelfArrow({
    required this.palette,
    required this.icon,
    required this.onTap,
  });

  @override
  State<_ShelfArrow> createState() => _ShelfArrowState();
}

class _ShelfArrowState extends State<_ShelfArrow> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    return MouseRegion(
      cursor: SystemMouseCursors.click,
      onEnter: (_) => setState(() => _hover = true),
      onExit: (_) => setState(() => _hover = false),
      child: GestureDetector(
        onTap: widget.onTap,
        child: Container(
          width: 40,
          height: 40,
          decoration: BoxDecoration(
            color: _hover ? widget.palette.surface2 : widget.palette.surface,
            shape: BoxShape.circle,
            border: Border.all(color: widget.palette.sep),
            boxShadow: const [
              BoxShadow(
                color: Color(0x33000000),
                blurRadius: 16,
                offset: Offset(0, 6),
              ),
            ],
          ),
          child: Icon(widget.icon, size: 22, color: widget.palette.text),
        ),
      ),
    );
  }
}
