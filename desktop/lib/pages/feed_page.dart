import 'package:flutter/material.dart';

import '../core/app_preloader.dart';
import '../core/rust_bridge.dart';
import '../core/theme.dart';
import '../models/models.dart';
import '../widgets/cinema_player.dart';

/// 1:1 with `feedPage()` in web/ui/pc/app.js (`#player.feed` immersive vertical short-drama streamer).
class FeedPage extends StatefulWidget {
  final ValueChanged<String> onToast;
  final ValueChanged<String> onOpenDetail;
  final bool active;

  const FeedPage({
    super.key,
    required this.onToast,
    required this.onOpenDetail,
    this.active = true,
  });

  @override
  State<FeedPage> createState() => _FeedPageState();
}

class _FeedPageState extends State<FeedPage> {
  final List<SeriesItem> _queue = [];
  String _cell = '';
  String _session = '';
  int _nextOffset = 0;
  bool _hasMore = true;

  int _idx = 0;
  DetailData? _detail;
  List<SeasonItem> _seasons = const [];
  bool _fav = false;
  bool _loading = true;
  String _err = '';
  int _loadToken = 0;

  @override
  void initState() {
    super.initState();
    _initFeed();
  }

  Future<void> _initFeed() async {
    setState(() {
      _loading = true;
      _err = '';
    });
    try {
      final home = await AppPreloader.instance.getHome();
      final fy = Map<String, dynamic>.from(home['forYou'] as Map? ?? const {});
      final items = (fy['items'] as List<dynamic>? ?? const [])
          .map((e) => SeriesItem.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList();
      _queue
        ..clear()
        ..addAll(items);
      _cell = home['cell']?.toString() ?? '';
      _session = home['session']?.toString() ?? fy['session']?.toString() ?? '';
      _nextOffset = (fy['next'] as num?)?.toInt() ?? items.length;
      _hasMore = fy['more'] == true;
      _idx = 0;
      if (_queue.isEmpty) {
        await _appendBatch();
      }
      if (!mounted) return;
      if (_queue.isEmpty) {
        setState(() {
          _loading = false;
          _err = '暂无推荐短剧';
        });
        return;
      }
      await _openAt(0);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _err = e.toString();
      });
    }
  }

  Future<void> _appendBatch() async {
    if (!_hasMore) return;
    final res = await RustBridge.instance.callMap('foryou', {
      'channel': '16',
      'offset': _nextOffset,
      if (_cell.isNotEmpty) 'cell': _cell,
      if (_session.isNotEmpty) 'session': _session,
    });
    final page = Map<String, dynamic>.from(res['page'] as Map? ?? const {});
    final items = (page['items'] as List<dynamic>? ?? const [])
        .map((e) => SeriesItem.fromJson(Map<String, dynamic>.from(e as Map)))
        .toList();
    final seen = _queue.map((e) => e.id).toSet();
    _queue.addAll(items.where((e) => !seen.contains(e.id)));
    _cell = res['cell']?.toString() ?? _cell;
    _session = res['session']?.toString() ?? page['session']?.toString() ?? _session;
    _nextOffset = (page['next'] as num?)?.toInt() ?? (_nextOffset + items.length);
    _hasMore = page['more'] == true && items.isNotEmpty;
  }

  Future<void> _openAt(int targetIndex) async {
    final token = ++_loadToken;
    if (targetIndex < 0) return;

    if (targetIndex >= _queue.length - 2 && _hasMore) {
      _appendBatch().catchError((_) {});
    }
    if (targetIndex >= _queue.length) {
      await _appendBatch();
    }
    if (!mounted || token != _loadToken) return;
    if (targetIndex >= _queue.length) {
      widget.onToast('已经到底了');
      return;
    }

    setState(() {
      _loading = true;
      _err = '';
    });

    int cur = targetIndex;
    for (int attempt = 0; attempt < 5; attempt++) {
      if (cur >= _queue.length) {
        if (_hasMore) {
          await _appendBatch();
        }
        if (cur >= _queue.length) break;
      }
      final s = _queue[cur];
      try {
        final dRes = await AppPreloader.instance.getLiteDetail(s.id);
        if (!mounted || token != _loadToken) return;
        final detail = DetailData.fromJson(
          Map<String, dynamic>.from(dRes['detail'] as Map? ?? const {}),
        );
        if (detail.episodes.isEmpty) {
          cur++;
          continue;
        }
        List<SeasonItem> seasons = const [];
        try {
          final sRes = await AppPreloader.instance.getQuickSeasons(s.id);
          seasons = (sRes['items'] as List<dynamic>? ?? const [])
              .map((e) => SeasonItem.fromJson(Map<String, dynamic>.from(e as Map)))
              .toList();
        } catch (_) {}

        if (!mounted || token != _loadToken) return;
        setState(() {
          _idx = cur;
          _detail = detail;
          _seasons = seasons;
          _fav = dRes['favorite'] == true;
          _loading = false;
        });
        return;
      } catch (_) {
        cur++;
      }
    }

    if (!mounted || token != _loadToken) return;
    setState(() {
      _loading = false;
      _err = '加载推荐剧集失败';
    });
  }

  Future<void> _toggleFav() async {
    final d = _detail;
    if (d == null) return;
    final next = !_fav;
    try {
      await RustBridge.instance.callMap('favorite.set', {
        'seriesId': d.id,
        'add': next,
      });
      if (!mounted) return;
      setState(() => _fav = next);
      widget.onToast(next ? '已加入收藏' : '已取消收藏');
    } catch (e) {
      widget.onToast('操作失败：$e');
    }
  }

  Future<DetailData?> _loadSeasonDetail(String seriesId) async {
    try {
      final res = await RustBridge.instance.callMap('detail', {'id': seriesId});
      return DetailData.fromJson(
        Map<String, dynamic>.from(res['detail'] as Map? ?? const {}),
      );
    } catch (_) {
      return null;
    }
  }

  @override
  Widget build(BuildContext context) {
    final p = LvguoPalette.of(context);
    if (_loading && _detail == null) {
      return Container(
        color: Colors.black,
        alignment: Alignment.center,
        child: CircularProgressIndicator(color: p.primary, strokeWidth: 2.5),
      );
    }
    if (_err.isNotEmpty && _detail == null) {
      return Container(
        color: Colors.black,
        alignment: Alignment.center,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(_err, style: const TextStyle(color: Colors.white70, fontSize: 14)),
            const SizedBox(height: 14),
            FilledButton(
              style: FilledButton.styleFrom(
                backgroundColor: p.primary,
                foregroundColor: p.primaryOn,
                shape: const StadiumBorder(),
              ),
              onPressed: _initFeed,
              child: const Text('重试'),
            ),
          ],
        ),
      );
    }

    final d = _detail;
    if (d == null) return const SizedBox.shrink();

    return CinemaPlayer(
      key: ValueKey('feed_${d.id}'),
      detail: d,
      seasons: _seasons,
      initialEpisodeIndex: 0,
      initialPositionMs: 0,
      feedMode: true,
      active: widget.active,
      feedFavorite: _fav,
      canFeedPrev: _idx > 0,
      canFeedNext: true,
      onFeedPrev: () => _openAt(_idx - 1),
      onFeedNext: () => _openAt(_idx + 1),
      onFeedToggleFav: _toggleFav,
      onToast: widget.onToast,
      onLoadSeasonDetail: _loadSeasonDetail,
      onClose: () => widget.onOpenDetail(d.id),
    );
  }
}
