import 'dart:async';

import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../core/rust_bridge.dart';
import '../core/theme.dart';
import '../models/models.dart';
import 'web_primitives.dart';

/// Top-right floating search popover matching `.spw / .spop` in web/ui/pc/app.css + openSearch() in app.js
class SearchPopover extends StatefulWidget {
  final ValueChanged<String> onOpenDetail;
  final VoidCallback onClose;

  const SearchPopover({
    super.key,
    required this.onOpenDetail,
    required this.onClose,
  });

  @override
  State<SearchPopover> createState() => _SearchPopoverState();
}

class _SearchPopoverState extends State<SearchPopover> {
  final _ctrl = TextEditingController();
  Timer? _deb;

  List<String> _recent = const [];
  List<SeriesItem> _hot = const [];
  bool _loadingHot = true;

  List<String> _suggests = const [];
  List<SeriesItem> _results = const [];
  bool _searching = false;
  bool _more = false;
  int _page = 1;
  String _activeQuery = '';
  String _searchError = '';

  @override
  void initState() {
    super.initState();
    _loadRecent();
    _loadHot();
  }

  @override
  void dispose() {
    _deb?.cancel();
    _ctrl.dispose();
    super.dispose();
  }

  Future<void> _loadRecent() async {
    final prefs = await SharedPreferences.getInstance();
    if (!mounted) return;
    setState(() {
      _recent = prefs.getStringList('lv.recent') ?? const [];
    });
  }

  Future<void> _saveRecent(String q) async {
    final next = [q, ..._recent.where((x) => x != q)].take(12).toList();
    setState(() => _recent = next);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setStringList('lv.recent', next);
  }

  Future<void> _clearRecent() async {
    setState(() => _recent = const []);
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove('lv.recent');
  }

  Future<void> _loadHot() async {
    try {
      final opts = await RustBridge.instance.callMap('category.options', {'type': 'all'});
      final boards = (opts['boards'] as List<dynamic>? ?? const [])
          .map((e) => CatBoard.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList();
      final hotBoard = boards.firstWhere(
        (b) => b.label.contains('热搜'),
        orElse: () => boards.isNotEmpty
            ? boards.first
            : const CatBoard(id: 'ranklist_hot_play_sc', label: '热播榜', groups: []),
      );
      final res = await RustBridge.instance.callMap('rank', {
        'board': hotBoard.id,
        'offset': 0,
      });
      if (!mounted) return;
      final list = (res['items'] as List<dynamic>? ?? const [])
          .map((e) => SeriesItem.fromJson(Map<String, dynamic>.from(e as Map)))
          .take(8)
          .toList();
      setState(() {
        _hot = list;
        _loadingHot = false;
      });
    } catch (_) {
      if (mounted) setState(() => _loadingHot = false);
    }
  }

  void _onChanged(String val) {
    _deb?.cancel();
    final q = val.trim();
    if (q.isEmpty) {
      setState(() {
        _activeQuery = '';
        _suggests = const [];
        _results = const [];
        _searchError = '';
      });
      return;
    }
    _deb = Timer(const Duration(milliseconds: 280), () => _runSearch(q, commit: false));
  }

  Future<void> _runSearch(String raw, {bool commit = true, int page = 1}) async {
    final q = raw.trim();
    if (q.isEmpty) return;
    if (commit) {
      _ctrl.text = q;
      _saveRecent(q);
    }
    setState(() {
      _activeQuery = q;
      if (page == 1) {
        _searching = true;
        _results = const [];
        _searchError = '';
      }
    });

    if (page == 1) {
      RustBridge.instance.callMap('suggest', {'q': q}).then((r) {
        if (!mounted || _activeQuery != q) return;
        final sugs = (r['items'] as List<dynamic>? ?? const [])
            .map((e) => e.toString())
            .where((x) => x != q)
            .take(6)
            .toList();
        setState(() => _suggests = sugs);
      }).catchError((_) {});
    }

    try {
      final res = await RustBridge.instance.callMap('search', {'q': q, 'page': page});
      if (!mounted || _activeQuery != q) return;
      final list = (res['items'] as List<dynamic>? ?? const [])
          .map((e) => SeriesItem.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList();
      setState(() {
        if (page == 1) {
          _results = list;
        } else {
          final seen = _results.map((x) => x.id).toSet();
          _results = [..._results, ...list.where((x) => !seen.contains(x.id))];
        }
        _page = page;
        _more = res['more'] == true;
        _searching = false;
      });
    } catch (e) {
      if (!mounted || _activeQuery != q) return;
      setState(() {
        _searching = false;
        _searchError = e.toString();
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final p = LvguoPalette.of(context);
    final sz = MediaQuery.of(context).size;
    final gutter = sz.width <= 1366 ? 40.0 : 56.0;
    final popWidth = (sz.width - 32).clamp(280.0, 440.0);
    final maxH = (sz.height - 88).clamp(320.0, 700.0);

    return Stack(
      children: [
        Positioned.fill(
          child: GestureDetector(
            onTap: widget.onClose,
            behavior: HitTestBehavior.translucent,
            child: const SizedBox.expand(),
          ),
        ),
        Positioned(
          right: gutter,
          top: 60,
          child: Material(
            color: Colors.transparent,
            child: Container(
              width: popWidth,
              constraints: BoxConstraints(maxHeight: maxH),
              decoration: BoxDecoration(
                color: p.surface,
                borderRadius: BorderRadius.circular(14),
                border: Border.all(color: p.sep),
                boxShadow: [
                  BoxShadow(
                    color: Colors.black.withValues(alpha: 0.20),
                    blurRadius: 54,
                    offset: const Offset(0, 20),
                  ),
                ],
              ),
              clipBehavior: Clip.antiAlias,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  // .sform
                  Container(
                    height: 56,
                    padding: const EdgeInsets.fromLTRB(18, 0, 14, 0),
                    decoration: BoxDecoration(
                      border: Border(bottom: BorderSide(color: p.sep)),
                    ),
                    child: Row(
                      children: [
                        Icon(Icons.search_rounded, size: 19, color: p.text3),
                        const SizedBox(width: 12),
                        Expanded(
                          child: TextField(
                            controller: _ctrl,
                            autofocus: true,
                            onChanged: _onChanged,
                            onSubmitted: (v) => _runSearch(v),
                            style: TextStyle(fontSize: 16, color: p.text),
                            decoration: InputDecoration(
                              hintText: '搜索短剧、演员',
                              hintStyle: TextStyle(color: p.text4),
                              filled: false,
                              border: InputBorder.none,
                              enabledBorder: InputBorder.none,
                              focusedBorder: InputBorder.none,
                              isCollapsed: true,
                            ),
                          ),
                        ),
                        if (_ctrl.text.isNotEmpty)
                          InkWell(
                            onTap: () {
                              _ctrl.clear();
                              _onChanged('');
                            },
                            borderRadius: BorderRadius.circular(99),
                            child: Container(
                              width: 28,
                              height: 28,
                              decoration: BoxDecoration(color: p.fill, shape: BoxShape.circle),
                              child: Icon(Icons.close, size: 14, color: p.text2),
                            ),
                          ),
                      ],
                    ),
                  ),
                  // .sbody (scrollbar hidden to match Web .sbody::-webkit-scrollbar{display:none})
                  Flexible(
                    child: ScrollConfiguration(
                      behavior: ScrollConfiguration.of(context).copyWith(scrollbars: false),
                      child: SingleChildScrollView(
                        padding: const EdgeInsets.fromLTRB(10, 6, 10, 14),
                        child: _activeQuery.isEmpty
                            ? _buildIdle(p)
                            : _buildResults(p),
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildIdle(LvguoPalette p) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (_recent.isNotEmpty) ...[
          Padding(
            padding: const EdgeInsets.fromLTRB(10, 14, 10, 8),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text('最近搜索', style: TextStyle(fontSize: 12.5, fontWeight: FontWeight.w500, color: p.text3)),
                InkWell(
                  onTap: _clearRecent,
                  child: Text('清空', style: TextStyle(fontSize: 12.5, color: p.text3)),
                ),
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(8, 0, 8, 4),
            child: Wrap(
              spacing: 8,
              runSpacing: 8,
              children: _recent.take(8).map((t) {
                return InkWell(
                  onTap: () => _runSearch(t),
                  borderRadius: BorderRadius.circular(8),
                  child: Container(
                    height: 30,
                    padding: const EdgeInsets.symmetric(horizontal: 10),
                    decoration: BoxDecoration(
                      color: p.fill2,
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(t, style: TextStyle(fontSize: 13.5, color: p.text2)),
                      ],
                    ),
                  ),
                );
              }).toList(),
            ),
          ),
        ],
        Padding(
          padding: const EdgeInsets.fromLTRB(10, 14, 10, 8),
          child: Text('大家都在搜', style: TextStyle(fontSize: 12.5, fontWeight: FontWeight.w500, color: p.text3)),
        ),
        if (_loadingHot)
          const _SearchListSkeleton()
        else
          ..._hot.take(8).toList().asMap().entries.map((e) => _srow(p, e.value, rank: e.key + 1)),
      ],
    );
  }

  Widget _buildResults(LvguoPalette p) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (_suggests.isNotEmpty)
          Padding(
            padding: const EdgeInsets.fromLTRB(8, 10, 8, 6),
            child: Wrap(
              spacing: 8,
              runSpacing: 8,
              children: _suggests.map((s) {
                return InkWell(
                  onTap: () => _runSearch(s),
                  borderRadius: BorderRadius.circular(8),
                  child: Container(
                    height: 30,
                    padding: const EdgeInsets.symmetric(horizontal: 10),
                    decoration: BoxDecoration(
                      color: p.fill2,
                      borderRadius: BorderRadius.circular(8),
                    ),
                    alignment: Alignment.center,
                    child: Text(s, style: TextStyle(fontSize: 13.5, color: p.text2)),
                  ),
                );
              }).toList(),
            ),
          ),
        if (_searching)
          const _SearchListSkeleton()
        else if (_searchError.isNotEmpty)
          Padding(
            padding: const EdgeInsets.all(24),
            child: Center(child: Text(_searchError, style: TextStyle(color: p.text3, fontSize: 14))),
          )
        else if (_results.isEmpty)
          Padding(
            padding: const EdgeInsets.all(30),
            child: Center(
              child: Text('没有找到“$_activeQuery”相关的剧', style: TextStyle(color: p.text3, fontSize: 14)),
            ),
          )
        else ...[
          ..._results.map((s) => _srow(p, s, query: _activeQuery)),
          if (_more)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Center(
                child: InkWell(
                  onTap: () => _runSearch(_activeQuery, commit: false, page: _page + 1),
                  borderRadius: BorderRadius.circular(8),
                  child: Container(
                    height: 34,
                    padding: const EdgeInsets.symmetric(horizontal: 16),
                    decoration: BoxDecoration(color: p.fill, borderRadius: BorderRadius.circular(8)),
                    alignment: Alignment.center,
                    child: Text('加载更多', style: TextStyle(fontSize: 13.5, fontWeight: FontWeight.w500, color: p.text2)),
                  ),
                ),
              ),
            ),
        ],
      ],
    );
  }

  Widget _srow(LvguoPalette p, SeriesItem s, {int? rank, String? query}) {
    final cats = s.category.split(',').where((e) => e.trim().isNotEmpty).take(2);
    final meta = [
      if (s.episodes > 0) '全 ${s.episodes} 集',
      if (s.plays > 0) formatPlays(s.plays),
      ...cats,
    ].join(' · ');

    return InkWell(
      onTap: () {
        if (_ctrl.text.trim().isNotEmpty) {
          _saveRecent(_ctrl.text.trim());
        }
        widget.onClose();
        widget.onOpenDetail(s.id);
      },
      borderRadius: BorderRadius.circular(8),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
        child: Row(
          children: [
            ClipRRect(
              borderRadius: BorderRadius.circular(6),
              child: SizedBox(
                width: 42,
                height: 56,
                child: s.cover.isNotEmpty
                    ? Image.network(
                        s.cover,
                        fit: BoxFit.cover,
                        cacheWidth: 160,
                        gaplessPlayback: true,
                        errorBuilder: (_, _, _) => Container(color: p.skeleton),
                      )
                    : Container(color: p.skeleton),
              ),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _highlightTitle(p, s.title, query),
                  if (meta.isNotEmpty) ...[
                    const SizedBox(height: 3),
                    Text(
                      meta,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(fontSize: 12.5, color: p.text3),
                    ),
                  ],
                ],
              ),
            ),
            if (rank != null) ...[
              const SizedBox(width: 8),
              SizedBox(
                width: 22,
                child: Text(
                  '$rank',
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                    color: rank <= 3 ? p.text : p.text4,
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _highlightTitle(LvguoPalette p, String title, String? query) {
    if (query == null || query.isEmpty) {
      return Text(
        title,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(fontSize: 14.5, fontWeight: FontWeight.w500, color: p.text),
      );
    }
    final lower = title.toLowerCase();
    final qLower = query.toLowerCase();
    final idx = lower.indexOf(qLower);
    if (idx < 0) {
      return Text(
        title,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(fontSize: 14.5, fontWeight: FontWeight.w500, color: p.text),
      );
    }
    return Text.rich(
      TextSpan(
        children: [
          if (idx > 0) TextSpan(text: title.substring(0, idx)),
          TextSpan(
            text: title.substring(idx, idx + query.length),
            style: TextStyle(color: p.primary, fontWeight: FontWeight.w600),
          ),
          if (idx + query.length < title.length)
            TextSpan(text: title.substring(idx + query.length)),
        ],
      ),
      maxLines: 1,
      overflow: TextOverflow.ellipsis,
      style: TextStyle(fontSize: 14.5, fontWeight: FontWeight.w500, color: p.text),
    );
  }
}

class _SearchListSkeleton extends StatelessWidget {
  const _SearchListSkeleton();

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      child: Column(
        children: [
          for (int i = 0; i < 5; i++)
            Padding(
              padding: EdgeInsets.only(bottom: i == 4 ? 0 : 12),
              child: const Row(
                children: [
                  SkeletonBox(width: 42, height: 56, radius: 6),
                  SizedBox(width: 14),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        SkeletonBox(width: 190, height: 14, radius: 5),
                        SizedBox(height: 8),
                        SkeletonBox(width: 120, height: 11, radius: 4),
                      ],
                    ),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }
}
