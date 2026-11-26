import 'package:flutter/material.dart';

import '../core/app_preloader.dart';
import '../core/theme.dart';
import '../models/models.dart';
import '../widgets/series_card.dart';
import '../widgets/web_primitives.dart';

/// 1:1 with `rank()` in web/ui/pc/app.js (`榜单` title + `#rty` WebChips + `.bar2` UnderlineTabBar + ranked `.grid`).
class RankPage extends StatefulWidget {
  final ValueChanged<String> onOpenDetail;
  final String? initialBoard;

  const RankPage({
    super.key,
    required this.onOpenDetail,
    this.initialBoard,
  });

  @override
  State<RankPage> createState() => _RankPageState();
}

class _RankPageState extends State<RankPage> {
  final ScrollController _scrollCtrl = ScrollController();

  List<CatOption> _types = const [];
  String _typeId = 'all';

  List<CatBoard> _boards = const [
    CatBoard(id: 'ranklist_hot_play_sc', label: '热播榜', groups: []),
    CatBoard(id: 'ranklist_hot_sc', label: '推荐榜', groups: []),
  ];
  String _boardId = 'ranklist_hot_play_sc';

  List<SeriesItem> _items = const [];
  int _nextOffset = 0;
  String _session = '';
  bool _more = false;
  bool _loading = true;
  bool _loadingMore = false;
  String _err = '';

  @override
  void initState() {
    super.initState();
    if (widget.initialBoard != null && widget.initialBoard!.isNotEmpty) {
      _boardId = widget.initialBoard!;
    }
    _scrollCtrl.addListener(_onScroll);
    _hydrateFromCacheOrLoad();
  }

  void _hydrateFromCacheOrLoad() {
    final opts = AppPreloader.instance.peekCategoryOptions(type: _typeId);
    final list = (_typeId == 'all' &&
            (_boardId == 'ranklist_hot_play_sc' || _boardId == 'ranklist_hot_sc'))
        ? AppPreloader.instance.peekRank(_boardId)
        : AppPreloader.instance.peekCategoryList(type: _typeId, board: _boardId);
    if (opts != null && list != null) {
      final types = (opts['types'] as List<dynamic>? ?? const [])
          .map((e) => CatOption.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList();
      final boards = (opts['boards'] as List<dynamic>? ?? const [])
          .map((e) => CatBoard.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList();
      if (types.isNotEmpty) _types = types;
      if (boards.isNotEmpty) {
        _boards = boards;
        if (!boards.any((b) => b.id == _boardId)) {
          _boardId = boards.first.id;
        }
      }
      final parsed = (list['items'] as List<dynamic>? ?? const [])
          .map((e) => SeriesItem.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList();
      _items = parsed;
      _nextOffset = (list['next'] as num?)?.toInt() ?? parsed.length;
      _session = list['session']?.toString() ?? '';
      _more = list['more'] == true && parsed.isNotEmpty;
      _loading = false;
      WidgetsBinding.instance.addPostFrameCallback((_) => _ensureFilledViewport());
      return;
    }
    _initOptions();
  }

  @override
  void didUpdateWidget(covariant RankPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.initialBoard != null &&
        widget.initialBoard!.isNotEmpty &&
        widget.initialBoard != oldWidget.initialBoard &&
        widget.initialBoard != _boardId) {
      _boardId = widget.initialBoard!;
      _loadList();
    }
  }

  @override
  void dispose() {
    _scrollCtrl.removeListener(_onScroll);
    _scrollCtrl.dispose();
    super.dispose();
  }

  void _onScroll() {
    if (!_scrollCtrl.hasClients) return;
    final pos = _scrollCtrl.position;
    if (pos.pixels >= pos.maxScrollExtent - 500 && _more && !_loadingMore) {
      _loadList(append: true);
    }
  }

  int _calcCols(double w) => w <= 760
      ? 3
      : w <= 1366
          ? 5
          : w >= 2000
              ? 8
              : w >= 1680
                  ? 7
                  : 6;

  Future<void> _ensureFilledViewport() async {
    for (var i = 0; i < 5; i++) {
      if (!mounted || !_more || _loadingMore) break;
      final w = MediaQuery.maybeOf(context)?.size.width ?? 1280.0;
      final target = _calcCols(w) * 3;
      if (_items.length >= target &&
          _scrollCtrl.hasClients &&
          _scrollCtrl.position.maxScrollExtent > 40) {
        break;
      }
      await _loadList(append: true);
    }
  }

  Future<void> _initOptions({String? switchType}) async {
    if (switchType != null) {
      _typeId = switchType;
    }
    setState(() {
      _loading = true;
      _err = '';
    });
    try {
      final res = await AppPreloader.instance.getCategoryOptions(type: _typeId);
      if (!mounted) return;
      final types = (res['types'] as List<dynamic>? ?? const [])
          .map((e) => CatOption.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList();
      final boards = (res['boards'] as List<dynamic>? ?? const [])
          .map((e) => CatBoard.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList();

      setState(() {
        if (types.isNotEmpty) _types = types;
        if (boards.isNotEmpty) {
          _boards = boards;
          if (!boards.any((b) => b.id == _boardId)) {
            _boardId = boards.first.id;
          }
        }
      });
      await _loadList();
    } catch (e) {
      await _loadList();
    }
  }

  Future<void> _loadList({bool append = false}) async {
    if (append) {
      if (_loadingMore || !_more) return;
      setState(() => _loadingMore = true);
    } else {
      setState(() {
        _loading = true;
        _err = '';
        _nextOffset = 0;
        _session = '';
      });
    }

    try {
      Map<String, dynamic> res;
      if (_typeId == 'all' &&
          (_boardId == 'ranklist_hot_play_sc' || _boardId == 'ranklist_hot_sc')) {
        res = await AppPreloader.instance.getRank(
          _boardId,
          offset: append ? _nextOffset : 0,
          session: append ? _session : '',
        );
      } else {
        res = await AppPreloader.instance.getCategoryList(
          type: _typeId,
          board: _boardId,
          selected: const <String, String>{},
          offset: append ? _nextOffset : 0,
          session: append ? _session : '',
        );
      }

      if (!mounted) return;
      final list = (res['items'] as List<dynamic>? ?? const [])
          .map((e) => SeriesItem.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList();
      setState(() {
        if (append) {
          final seen = _items.map((x) => x.id).toSet();
          _items = [..._items, ...list.where((x) => !seen.contains(x.id))];
        } else {
          _items = list;
        }
        _nextOffset = (res['next'] as num?)?.toInt() ?? (_nextOffset + list.length);
        _session = res['session']?.toString() ?? _session;
        _more = res['more'] == true && list.isNotEmpty;
        _loading = false;
        _loadingMore = false;
      });
      if (!append) {
        WidgetsBinding.instance.addPostFrameCallback((_) => _ensureFilledViewport());
      }
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _loadingMore = false;
        _err = e.toString();
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final p = LvguoPalette.of(context);
    final w = MediaQuery.of(context).size.width;
    final gutter = w <= 1366 ? 40.0 : 56.0;
    final cols = _calcCols(w);
    final cardW = ((w - gutter * 2 - (cols - 1) * 20.0) / cols).clamp(120.0, 260.0);
    final boardIndex = _boards.indexWhere((b) => b.id == _boardId).clamp(0, _boards.length - 1);

    return CustomScrollView(
      controller: _scrollCtrl,
      slivers: [
        SliverPadding(
          padding: EdgeInsets.fromLTRB(gutter, 36, gutter, 12),
          sliver: SliverToBoxAdapter(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '榜单',
                  style: TextStyle(
                    fontSize: 32,
                    height: 1.15,
                    fontWeight: FontWeight.w700,
                    letterSpacing: -0.64,
                    color: p.text,
                  ),
                ),
                if (_types.length > 1) ...[
                  const SizedBox(height: 16),
                  SingleChildScrollView(
                    scrollDirection: Axis.horizontal,
                    child: Row(
                      children: _types.map((t) {
                        return Padding(
                          padding: const EdgeInsets.only(right: 6),
                          child: WebChip(
                            label: t.label,
                            active: t.id == _typeId,
                            onTap: () {
                              if (t.id == _typeId) return;
                              _initOptions(switchType: t.id);
                            },
                          ),
                        );
                      }).toList(),
                    ),
                  ),
                ],
              ],
            ),
          ),
        ),
        SliverPersistentHeader(
          pinned: true,
          delegate: _StickyBoardBarDelegate(
            gutter: gutter,
            palette: p,
            labels: _boards.map((b) => b.label).toList(),
            activeIndex: boardIndex,
            onChanged: (idx) {
              final next = _boards[idx].id;
              if (next == _boardId) return;
              setState(() => _boardId = next);
              _loadList();
            },
          ),
        ),
        if (_loading)
          buildSliverGridSkeleton(
            gutter: gutter,
            cols: cols,
            cardW: cardW,
            count: cols * 2,
            topPadding: 24,
            bottomPadding: 80,
            mainAxisSpacing: 32,
            extraHeight: 42,
          )
        else if (_err.isNotEmpty)
          SliverPadding(
            padding: EdgeInsets.fromLTRB(gutter, 24, gutter, 80),
            sliver: SliverToBoxAdapter(
              child: SizedBox(
                height: 280,
                child: Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(_err, style: TextStyle(color: p.text2, fontSize: 14)),
                      const SizedBox(height: 14),
                      FilledButton(
                        style: FilledButton.styleFrom(
                          backgroundColor: p.primary,
                          foregroundColor: p.primaryOn,
                          shape: const StadiumBorder(),
                        ),
                        onPressed: () => _loadList(),
                        child: const Text('重试'),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          )
        else
          SliverPadding(
            padding: EdgeInsets.fromLTRB(gutter, 24, gutter, 80),
            sliver: SliverGrid(
              gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: cols,
                mainAxisSpacing: 32,
                crossAxisSpacing: 20,
                childAspectRatio: cardW / (cardW * (4 / 3) + 42),
              ),
              delegate: SliverChildBuilderDelegate(
                (_, i) {
                  if (i < _items.length) {
                    final item = _items[i];
                    return RiseCard(
                      key: ValueKey('rk_${_boardId}_${item.id}_$i'),
                      index: i,
                      child: SeriesCard(
                        item: item,
                        rankBadge: i + 1,
                        onTap: () => widget.onOpenDetail(item.id),
                      ),
                    );
                  }
                  return const SeriesCardSkeleton();
                },
                childCount: _items.length +
                    (_loadingMore
                        ? ((cols - (_items.length % cols)) % cols) + cols
                        : 0),
              ),
            ),
          ),
      ],
    );
  }
}

class _StickyBoardBarDelegate extends SliverPersistentHeaderDelegate {
  final double gutter;
  final LvguoPalette palette;
  final List<String> labels;
  final int activeIndex;
  final ValueChanged<int> onChanged;

  _StickyBoardBarDelegate({
    required this.gutter,
    required this.palette,
    required this.labels,
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
                labels: labels,
                activeIndex: activeIndex,
                onChanged: onChanged,
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
  bool shouldRebuild(covariant _StickyBoardBarDelegate oldDelegate) {
    return oldDelegate.gutter != gutter ||
        oldDelegate.palette != palette ||
        oldDelegate.activeIndex != activeIndex ||
        oldDelegate.labels.length != labels.length ||
        !_listEquals(oldDelegate.labels, labels);
  }

  static bool _listEquals(List<String> a, List<String> b) {
    for (var i = 0; i < a.length; i++) {
      if (a[i] != b[i]) return false;
    }
    return true;
  }
}
