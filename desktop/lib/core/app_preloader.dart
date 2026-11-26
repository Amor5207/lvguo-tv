import 'dart:async';

import '../models/models.dart';
import 'rust_bridge.dart';

/// Non-blocking startup & tab data preloader.
///
/// All calls run in `RustBridge`'s persistent background FFI isolates and Rust
/// worker runtime, sharing in-flight futures so switching tabs is instantaneous
/// without blocking the Flutter UI thread or issuing duplicate network calls.
class AppPreloader {
  AppPreloader._();
  static final AppPreloader instance = AppPreloader._();

  static const Duration _ttl = Duration(minutes: 4);

  final Map<String, Future<Map<String, dynamic>>> _inflight = {};
  final Map<String, ({DateTime at, Map<String, dynamic> data})> _cache = {};
  bool _warmed = false;

  Future<Map<String, dynamic>> _fetchCached(
    String key,
    String action,
    Map<String, dynamic> params, {
    bool force = false,
  }) {
    if (!force) {
      final hit = _cache[key];
      if (hit != null && DateTime.now().difference(hit.at) < _ttl) {
        return Future.value(hit.data);
      }
      final existing = _inflight[key];
      if (existing != null) return existing;
    }
    late final Future<Map<String, dynamic>> fut;
    fut = RustBridge.instance.callMap(action, params).then((res) {
      _cache[key] = (at: DateTime.now(), data: res);
      if (identical(_inflight[key], fut)) {
        _inflight.remove(key);
      }
      return res;
    }).catchError((Object e) {
      if (identical(_inflight[key], fut)) {
        _inflight.remove(key);
      }
      throw e;
    });
    _inflight[key] = fut;
    return fut;
  }

  Map<String, dynamic>? peekCached(String key) {
    final hit = _cache[key];
    if (hit != null && DateTime.now().difference(hit.at) < _ttl) {
      return hit.data;
    }
    return null;
  }

  Map<String, dynamic>? peekHome() => peekCached('home');
  Map<String, dynamic>? peekRank(String board) => peekCached('rank:$board');
  Map<String, dynamic>? peekCategoryOptions({String type = 'all'}) =>
      peekCached('cat_opts:$type');
  Map<String, dynamic>? peekCategoryList({
    String type = 'all',
    required String board,
  }) =>
      peekCached('cat_list:$type:$board');

  Future<Map<String, dynamic>> getHome({bool force = false}) =>
      _fetchCached('home', 'home', const {}, force: force);

  Future<Map<String, dynamic>> getRank(
    String board, {
    int offset = 0,
    String session = '',
    bool force = false,
  }) {
    if (offset != 0 || session.isNotEmpty) {
      return RustBridge.instance.callMap('rank', {
        'board': board,
        'offset': offset,
        'session': session,
      });
    }
    return _fetchCached('rank:$board', 'rank', {
      'board': board,
      'offset': 0,
      'session': '',
    }, force: force);
  }

  Future<Map<String, dynamic>> getCategoryOptions({
    String type = 'all',
    bool force = false,
  }) =>
      _fetchCached(
        'cat_opts:$type',
        'category.options',
        {'type': type},
        force: force,
      );

  Future<Map<String, dynamic>> getCategoryList({
    String type = 'all',
    required String board,
    Map<String, String> selected = const {},
    int offset = 0,
    String session = '',
    bool force = false,
  }) {
    if (offset != 0 || session.isNotEmpty || selected.isNotEmpty) {
      return RustBridge.instance.callMap('category.list', {
        'type': type,
        'board': board,
        'selected': selected,
        'offset': offset,
        'session': session,
      });
    }
    return _fetchCached(
      'cat_list:$type:$board',
      'category.list',
      {
        'type': type,
        'board': board,
        'selected': const <String, String>{},
        'offset': 0,
        'session': '',
      },
      force: force,
    );
  }

  Future<Map<String, dynamic>> getLiteDetail(String seriesId, {bool force = false}) =>
      _fetchCached(
        'detail_lite:$seriesId',
        'detail',
        {'id': seriesId, 'lite': true},
        force: force,
      );

  Future<Map<String, dynamic>> getQuickSeasons(String seriesId, {bool force = false}) =>
      _fetchCached(
        'seasons_quick:$seriesId',
        'seasons',
        {'id': seriesId, 'quick': true},
        force: force,
      );

  /// Fires lightweight background preloads right after app startup without
  /// touching or blocking the Flutter UI thread.
  void startWarmup() {
    if (_warmed || !RustBridge.instance.initialized) return;
    _warmed = true;

    // Kick off core tab requests in background isolates.
    unawaited(
      getHome().then((home) {
        final fy = Map<String, dynamic>.from(home['forYou'] as Map? ?? const {});
        final items = (fy['items'] as List<dynamic>? ?? const [])
            .whereType<Map>()
            .map((e) => SeriesItem.fromJson(Map<String, dynamic>.from(e)))
            .toList();
        if (items.isNotEmpty) {
          final firstId = items.first.id;
          if (firstId.isNotEmpty) {
            unawaited(getLiteDetail(firstId).catchError((_) => <String, dynamic>{}));
            unawaited(getQuickSeasons(firstId).catchError((_) => <String, dynamic>{}));
          }
        }
      }).catchError((_) {}),
    );

    // Slight stagger so first-frame home layout renders immediately with zero contention.
    Future.delayed(const Duration(milliseconds: 120), () {
      if (!RustBridge.instance.initialized) return;
      unawaited(getRank('ranklist_hot_play_sc').catchError((_) => <String, dynamic>{}));
      unawaited(getRank('ranklist_hot_sc').catchError((_) => <String, dynamic>{}));
      unawaited(
        getCategoryOptions(type: 'all').then((res) {
          final boards = (res['boards'] as List<dynamic>? ?? const [])
              .whereType<Map>()
              .map((e) => CatBoard.fromJson(Map<String, dynamic>.from(e)))
              .toList();
          if (boards.isEmpty) return;
          final preferred = boards.firstWhere(
            (b) => b.groups.isNotEmpty,
            orElse: () => boards.first,
          );
          if (preferred.id.isNotEmpty) {
            unawaited(
              getCategoryList(type: 'all', board: preferred.id)
                  .catchError((_) => <String, dynamic>{}),
            );
          }
        }).catchError((_) {}),
      );
    });
  }
}
