import 'package:flutter/material.dart';

import '../core/app_preloader.dart';
import '../core/theme.dart';
import '../models/models.dart';
import '../widgets/series_card.dart';
import '../widgets/web_primitives.dart';

/// 1:1 with `category()` in web/ui/pc/app.js (`.filters` `.frow` + `.fchips` + `.grid`).
class CategoryPage extends StatefulWidget {
  final ValueChanged<String> onOpenDetail;

  const CategoryPage({super.key, required this.onOpenDetail});

  @override
  State<CategoryPage> createState() => _CategoryPageState();
}

class _CategoryPageState extends State<CategoryPage> {
  final ScrollController _scrollCtrl = ScrollController();

  List<CatBoard> _boards = const [];
  String _boardId = '';
  final Map<String, String> _selected = {};
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
    _scrollCtrl.addListener(_onScroll);
    _hydrateFromCacheOrLoad();
  }

  void _hydrateFromCacheOrLoad() {
    final opts = AppPreloader.instance.peekCategoryOptions(type: 'all');
    if (opts != null) {
      final boards = (opts['boards'] as List<dynamic>? ?? const [])
          .map((e) => CatBoard.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList();
      final preferred = boards.firstWhere(
        (b) => b.groups.isNotEmpty,
        orElse: () => boards.isNotEmpty
            ? boards.first
            : const CatBoard(id: '', label: '', groups: []),
      );
      final list = preferred.id.isNotEmpty
          ? AppPreloader.instance.peekCategoryList(type: 'all', board: preferred.id)
          : null;
      if (list != null) {
        final parsed = (list['items'] as List<dynamic>? ?? const [])
            .map((e) => SeriesItem.fromJson(Map<String, dynamic>.from(e as Map)))
            .toList();
        _boards = boards;
        _boardId = preferred.id;
        _items = parsed;
        _nextOffset = (list['next'] as num?)?.toInt() ?? parsed.length;
        _session = list['session']?.toString() ?? '';
        _more = list['more'] == true && parsed.isNotEmpty;
        _loading = false;
        WidgetsBinding.instance.addPostFrameCallback((_) => _ensureFilledViewport());
        return;
      }
    }
    _loadOptions();
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

  static String _shortGroupName(String raw) {
    switch (raw) {
      case '综合':
        return '受众';
      case '时代背景':
        return '背景';
      case '主题情节':
        return '题材';
      case '角色设定':
        return '人设';
      default:
        return raw.length > 2 ? raw.substring(0, 2) : raw;
    }
  }

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

  CatBoard? get _activeBoard {
    for (final b in _boards) {
      if (b.id == _boardId) return b;
    }
    return _boards.isNotEmpty ? _boards.first : null;
  }

  Future<void> _loadOptions() async {
    setState(() {
      _loading = true;
      _err = '';
    });
    try {
      final res = await AppPreloader.instance.getCategoryOptions(type: 'all');
      final boards = (res['boards'] as List<dynamic>? ?? const [])
          .map((e) => CatBoard.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList();
      if (!mounted) return;
      final preferred = boards.firstWhere(
        (b) => b.groups.isNotEmpty,
        orElse: () => boards.isNotEmpty
            ? boards.first
            : const CatBoard(id: '', label: '', groups: []),
      );
      setState(() {
        _boards = boards;
        _boardId = preferred.id;
      });
      await _loadList();
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _err = e.toString();
      });
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
      final res = await AppPreloader.instance.getCategoryList(
        type: 'all',
        board: _boardId,
        selected: _selected,
        offset: append ? _nextOffset : 0,
        session: append ? _session : '',
      );
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
        _session = res['session']?.toString() ?? '';
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
    final board = _activeBoard;
    final boardsWithGroups = _boards.where((b) => b.groups.isNotEmpty).toList();
    final w = MediaQuery.of(context).size.width;
    final gutter = w <= 1366 ? 40.0 : 56.0;
    final cols = _calcCols(w);
    final cardW = ((w - gutter * 2 - (cols - 1) * 20.0) / cols).clamp(120.0, 260.0);

    return CustomScrollView(
      controller: _scrollCtrl,
      slivers: [
        SliverPadding(
          padding: EdgeInsets.fromLTRB(gutter, 36, gutter, 24),
          sliver: SliverToBoxAdapter(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '分类',
                  style: TextStyle(
                    fontSize: 32,
                    height: 1.15,
                    fontWeight: FontWeight.w700,
                    letterSpacing: -0.64,
                    color: p.text,
                  ),
                ),
                const SizedBox(height: 14),
                if (boardsWithGroups.length > 1) ...[
                  SingleChildScrollView(
                    scrollDirection: Axis.horizontal,
                    child: Row(
                      children: boardsWithGroups.map((b) {
                        return Padding(
                          padding: const EdgeInsets.only(right: 6),
                          child: WebChip(
                            label: b.label,
                            active: b.id == _boardId,
                            onTap: () {
                              if (b.id == _boardId) return;
                              setState(() {
                                _boardId = b.id;
                                _selected.clear();
                              });
                              _loadList();
                            },
                          ),
                        );
                      }).toList(),
                    ),
                  ),
                  const SizedBox(height: 8),
                ],
                // Borderless .filters rows matching app.css lines 215-221
                if (board != null && board.groups.isNotEmpty)
                  Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: board.groups.map((g) {
                      final cur = _selected[g.name] ?? '';
                      return Padding(
                        padding: const EdgeInsets.symmetric(vertical: 3),
                        child: Row(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            SizedBox(
                              width: 48,
                              height: 32,
                              child: Align(
                                alignment: Alignment.centerLeft,
                                child: Text(
                                  _shortGroupName(g.name),
                                  style: TextStyle(
                                    fontSize: 13.5,
                                    color: p.text4,
                                    fontWeight: FontWeight.w400,
                                  ),
                                ),
                              ),
                            ),
                            Expanded(
                              child: Wrap(
                                spacing: 4,
                                runSpacing: 2,
                                children: [
                                  WebChip(
                                    label: '全部',
                                    active: cur.isEmpty,
                                    onTap: () {
                                      setState(() => _selected.remove(g.name));
                                      _loadList();
                                    },
                                  ),
                                  ...g.items.map((opt) {
                                    return WebChip(
                                      label: opt.label,
                                      active: cur == opt.id,
                                      onTap: () {
                                        setState(() => _selected[g.name] = opt.id);
                                        _loadList();
                                      },
                                    );
                                  }),
                                ],
                              ),
                            ),
                          ],
                        ),
                      );
                    }).toList(),
                  ),
              ],
            ),
          ),
        ),
        if (_loading)
          buildSliverGridSkeleton(
            gutter: gutter,
            cols: cols,
            cardW: cardW,
            count: cols * 2,
            topPadding: 0,
            bottomPadding: 80,
            mainAxisSpacing: 32,
            extraHeight: 42,
          )
        else if (_err.isNotEmpty)
          SliverPadding(
            padding: EdgeInsets.fromLTRB(gutter, 0, gutter, 80),
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
                        onPressed: _loadList,
                        child: const Text('重试'),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          )
        else if (_items.isEmpty)
          SliverPadding(
            padding: EdgeInsets.fromLTRB(gutter, 0, gutter, 80),
            sliver: SliverToBoxAdapter(
              child: SizedBox(
                height: 260,
                child: Center(
                  child: Text(
                    '当前筛选条件下暂无剧集',
                    style: TextStyle(color: p.text3, fontSize: 14),
                  ),
                ),
              ),
            ),
          )
        else
          SliverPadding(
            padding: EdgeInsets.fromLTRB(gutter, 0, gutter, 80),
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
                      key: ValueKey('cat_${_boardId}_${item.id}_$i'),
                      index: i,
                      child: SeriesCard(
                        item: item,
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
