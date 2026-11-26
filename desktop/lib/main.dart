import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:media_kit/media_kit.dart';
import 'package:window_manager/window_manager.dart';

import 'core/app_preloader.dart';
import 'core/rust_bridge.dart';
import 'core/settings_store.dart';
import 'core/theme.dart';
import 'core/update_manager.dart';
import 'models/models.dart';
import 'pages/category_page.dart';
import 'pages/detail_page.dart';
import 'pages/downloads_page.dart';
import 'pages/feed_page.dart';
import 'pages/home_page.dart';
import 'pages/mine_page.dart';
import 'pages/rank_page.dart';
import 'pages/settings_page.dart';
import 'widgets/auth_dialog.dart';
import 'widgets/cinema_player.dart';
import 'widgets/search_popover.dart';
import 'widgets/top_nav.dart';

class _LaunchArgs {
  final bool isPlayer;
  final String seriesId;
  final int episodeIndex;
  final int positionMs;
  final String title;

  const _LaunchArgs({
    this.isPlayer = false,
    this.seriesId = '',
    this.episodeIndex = 0,
    this.positionMs = 0,
    this.title = '',
  });

  static _LaunchArgs parse(List<String> args) {
    bool isPlayer = false;
    String seriesId = '';
    int ep = 0;
    int pos = 0;
    String title = '';

    for (int i = 0; i < args.length; i++) {
      final a = args[i];
      if (a == '--player') {
        isPlayer = true;
      } else if (a == '--series' && i + 1 < args.length) {
        seriesId = args[++i];
      } else if (a == '--ep' && i + 1 < args.length) {
        ep = int.tryParse(args[++i]) ?? 0;
      } else if (a == '--pos' && i + 1 < args.length) {
        pos = int.tryParse(args[++i]) ?? 0;
      } else if (a == '--title' && i + 1 < args.length) {
        title = args[++i];
      } else if (a.startsWith('lvguo://')) {
        final uri = Uri.tryParse(a);
        if (uri != null) {
          final segs = uri.pathSegments.where((s) => s.isNotEmpty).toList();
          if (uri.host == 'play' && segs.isNotEmpty) {
            isPlayer = true;
            seriesId = segs.first;
          } else if (uri.host == 'detail' && segs.isNotEmpty) {
            seriesId = segs.first;
          } else if (segs.isNotEmpty) {
            seriesId = segs.last;
          }
        }
      }
    }
    return _LaunchArgs(
      isPlayer: isPlayer && seriesId.isNotEmpty,
      seriesId: seriesId,
      episodeIndex: ep,
      positionMs: pos,
      title: title,
    );
  }
}

Future<void> main(List<String> args) async {
  WidgetsFlutterBinding.ensureInitialized();
  PaintingBinding.instance.imageCache.maximumSize = 250;
  PaintingBinding.instance.imageCache.maximumSizeBytes = 128 << 20;
  MediaKit.ensureInitialized();
  await windowManager.ensureInitialized();
  await SettingsStore.instance.load();

  final launch = _LaunchArgs.parse(args);

  final windowOptions = launch.isPlayer
      ? WindowOptions(
          size: const Size(1280, 760),
          minimumSize: const Size(900, 560),
          center: true,
          backgroundColor: Colors.black,
          titleBarStyle: TitleBarStyle.hidden,
          windowButtonVisibility: false,
          title: launch.title.isNotEmpty ? '${launch.title} - 绿果PC' : '绿果PC 播放器',
        )
      : const WindowOptions(
          size: Size(1280, 800),
          minimumSize: Size(1024, 680),
          center: true,
          titleBarStyle: TitleBarStyle.hidden,
          windowButtonVisibility: false,
          title: '绿果PC',
        );

  await windowManager.waitUntilReadyToShow(windowOptions, () async {
    if (launch.isPlayer) {
      await windowManager.setTitle(
        launch.title.isNotEmpty ? '${launch.title} - 绿果PC' : '绿果PC 播放器',
      );
    } else {
      await windowManager.setTitle('绿果PC');
    }
    await windowManager.show();
    await windowManager.focus();
  });

  String? initError;
  try {
    await RustBridge.instance.init();
  } catch (e) {
    initError = e.toString();
  }

  if (launch.isPlayer) {
    runApp(StandalonePlayerApp(launch: launch, initError: initError));
  } else {
    runApp(LvguoDesktopApp(initError: initError, initialSeriesId: launch.seriesId));
  }
}

class LvguoDesktopApp extends StatefulWidget {
  final String? initError;
  final String initialSeriesId;

  const LvguoDesktopApp({
    super.key,
    this.initError,
    this.initialSeriesId = '',
  });

  @override
  State<LvguoDesktopApp> createState() => _LvguoDesktopAppState();
}

class _LvguoDesktopAppState extends State<LvguoDesktopApp> {
  @override
  void initState() {
    super.initState();
    SettingsStore.instance.addListener(_onSettingsChanged);
  }

  @override
  void dispose() {
    SettingsStore.instance.removeListener(_onSettingsChanged);
    RustBridge.instance.shutdown();
    super.dispose();
  }

  void _onSettingsChanged() {
    if (mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '绿果PC',
      debugShowCheckedModeBanner: false,
      theme: LvguoTheme.buildLight(),
      darkTheme: LvguoTheme.buildDark(),
      themeMode: SettingsStore.instance.themeMode,
      home: widget.initError != null
          ? _InitErrorScreen(error: widget.initError!)
          : MainShell(initialSeriesId: widget.initialSeriesId),
    );
  }
}

/// Dedicated OS window spawned for playing a series (`--player --series <id>`),
/// completely decoupled from the main browse window so closing never affects main navigation.
class StandalonePlayerApp extends StatefulWidget {
  final _LaunchArgs launch;
  final String? initError;

  const StandalonePlayerApp({
    super.key,
    required this.launch,
    this.initError,
  });

  @override
  State<StandalonePlayerApp> createState() => _StandalonePlayerAppState();
}

class _StandalonePlayerAppState extends State<StandalonePlayerApp> {
  @override
  void initState() {
    super.initState();
    SettingsStore.instance.addListener(_onSettingsChanged);
  }

  @override
  void dispose() {
    SettingsStore.instance.removeListener(_onSettingsChanged);
    RustBridge.instance.shutdown();
    super.dispose();
  }

  void _onSettingsChanged() {
    if (mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: widget.launch.title.isNotEmpty
          ? '${widget.launch.title} - 绿果PC'
          : '绿果PC 播放器',
      debugShowCheckedModeBanner: false,
      theme: LvguoTheme.buildDark(),
      darkTheme: LvguoTheme.buildDark(),
      themeMode: ThemeMode.dark,
      home: widget.initError != null
          ? _InitErrorScreen(error: widget.initError!)
          : _StandalonePlayerShell(launch: widget.launch),
    );
  }
}

class _StandalonePlayerShell extends StatefulWidget {
  final _LaunchArgs launch;

  const _StandalonePlayerShell({required this.launch});

  @override
  State<_StandalonePlayerShell> createState() => _StandalonePlayerShellState();
}

class _StandalonePlayerShellState extends State<_StandalonePlayerShell>
    with WindowListener {
  DetailData? _detail;
  List<SeasonItem> _seasons = const [];
  bool _loading = true;
  String _error = '';
  bool _isMaximized = false;
  bool _isFullScreen = false;

  String? _toastMsg;
  Timer? _toastTimer;

  @override
  void initState() {
    super.initState();
    windowManager.addListener(this);
    _syncWindowFlags();
    _load();
  }

  Future<void> _syncWindowFlags() async {
    final max = await windowManager.isMaximized();
    final fs = await windowManager.isFullScreen();
    if (!mounted) return;
    setState(() {
      _isMaximized = max;
      _isFullScreen = fs;
    });
  }

  @override
  void onWindowMaximize() {
    if (mounted) setState(() => _isMaximized = true);
  }

  @override
  void onWindowUnmaximize() {
    if (mounted) setState(() => _isMaximized = false);
  }

  @override
  void onWindowEnterFullScreen() {
    if (mounted) setState(() => _isFullScreen = true);
  }

  @override
  void onWindowLeaveFullScreen() {
    if (mounted) setState(() => _isFullScreen = false);
  }

  @override
  void dispose() {
    windowManager.removeListener(this);
    _toastTimer?.cancel();
    super.dispose();
  }

  void _showToast(String msg) {
    _toastTimer?.cancel();
    setState(() => _toastMsg = msg);
    _toastTimer = Timer(const Duration(milliseconds: 2400), () {
      if (mounted) setState(() => _toastMsg = null);
    });
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = '';
    });
    try {
      final res = await RustBridge.instance.callMap('detail', {
        'id': widget.launch.seriesId,
      });
      final d = DetailData.fromJson(
        Map<String, dynamic>.from(res['detail'] as Map? ?? const {}),
      );
      if (d.episodes.isEmpty) {
        if (!mounted) return;
        setState(() {
          _loading = false;
          _error = '暂无可播放的剧集';
        });
        return;
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

      if (!mounted) return;
      unawaited(windowManager.setTitle('${d.title} - 绿果PC'));
      setState(() {
        _detail = d;
        _seasons = seasons;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = e.toString();
      });
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
    final p = LvguoPalette.dark;
    final baseTitle = (_detail?.title.isNotEmpty == true)
        ? _detail!.title
        : (widget.launch.title.isNotEmpty ? widget.launch.title : '播放器');
    final windowTitle = '$baseTitle - 绿果PC';

    return Scaffold(
      backgroundColor: Colors.black,
      body: Column(
        children: [
          if (!_isFullScreen)
            _AppTitleBar(
              title: windowTitle,
              isMaximized: _isMaximized,
              backgroundColor: const Color(0xFF0D0E10),
              foregroundColor: Colors.white,
              borderColor: Colors.white.withValues(alpha: 0.08),
              onMinimize: () => windowManager.minimize(),
              onToggleMaximize: () async {
                if (await windowManager.isMaximized()) {
                  await windowManager.unmaximize();
                } else {
                  await windowManager.maximize();
                }
              },
              onClose: () => windowManager.close(),
            ),
          Expanded(
            child: Stack(
              children: [
                if (_loading)
                  const Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        SizedBox(
                          width: 44,
                          height: 44,
                          child: CircularProgressIndicator(color: Colors.white, strokeWidth: 2.5),
                        ),
                        SizedBox(height: 14),
                        Text(
                          '正在解析播放地址…',
                          style: TextStyle(color: Colors.white70, fontSize: 13.5, fontWeight: FontWeight.w500),
                        ),
                      ],
                    ),
                  )
                else if (_error.isNotEmpty || _detail == null)
                  Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const Text('加载失败', style: TextStyle(color: Colors.white, fontSize: 20, fontWeight: FontWeight.w700)),
                        const SizedBox(height: 8),
                        Text(_error, style: const TextStyle(color: Colors.white70, fontSize: 14)),
                        const SizedBox(height: 18),
                        Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            OutlinedButton(
                              style: OutlinedButton.styleFrom(foregroundColor: Colors.white),
                              onPressed: () => windowManager.close(),
                              child: const Text('关闭窗口'),
                            ),
                            const SizedBox(width: 10),
                            FilledButton(
                              style: FilledButton.styleFrom(
                                backgroundColor: p.primary,
                                foregroundColor: p.primaryOn,
                                shape: const StadiumBorder(),
                              ),
                              onPressed: _load,
                              child: const Text('重试'),
                            ),
                          ],
                        ),
                      ],
                    ),
                  )
                else
                  Positioned.fill(
                    child: CinemaPlayer(
                      key: ValueKey('standalone_${_detail!.id}_${widget.launch.episodeIndex}'),
                      detail: _detail!,
                      seasons: _seasons,
                      initialEpisodeIndex: widget.launch.episodeIndex,
                      initialPositionMs: widget.launch.positionMs,
                      onToast: _showToast,
                      onLoadSeasonDetail: _loadSeasonDetail,
                      onClose: () => windowManager.close(),
                    ),
                  ),
                if (_toastMsg != null)
                  Positioned(
                    left: 0,
                    right: 0,
                    bottom: 32,
                    child: IgnorePointer(
                      child: Center(
                        child: Container(
                          padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 10),
                          decoration: BoxDecoration(
                            color: const Color(0xEC222226),
                            borderRadius: BorderRadius.circular(999),
                            border: Border.all(color: Colors.white12),
                          ),
                          child: Text(
                            _toastMsg!,
                            style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w500, color: Colors.white),
                          ),
                        ),
                      ),
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

class _AppTitleBar extends StatelessWidget {
  final String title;
  final bool isMaximized;
  final Color backgroundColor;
  final Color foregroundColor;
  final Color borderColor;
  final VoidCallback onMinimize;
  final VoidCallback onToggleMaximize;
  final VoidCallback onClose;

  const _AppTitleBar({
    required this.title,
    required this.isMaximized,
    required this.backgroundColor,
    required this.foregroundColor,
    required this.borderColor,
    required this.onMinimize,
    required this.onToggleMaximize,
    required this.onClose,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      height: 36,
      decoration: BoxDecoration(
        color: backgroundColor,
        border: Border(
          bottom: BorderSide(color: borderColor),
        ),
      ),
      child: Row(
        children: [
          Expanded(
            child: DragToMoveArea(
              child: GestureDetector(
                behavior: HitTestBehavior.translucent,
                onDoubleTap: onToggleMaximize,
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 14),
                  child: Row(
                    children: [
                      ClipRRect(
                        borderRadius: BorderRadius.circular(4),
                        child: Image.asset('assets/logo.png', width: 17, height: 17),
                      ),
                      const SizedBox(width: 9),
                      Expanded(
                        child: Text(
                          title,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: 12.5,
                            fontWeight: FontWeight.w500,
                            color: foregroundColor.withValues(alpha: 0.90),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
          _WindowCaptionBtn(
            tooltip: '最小化',
            icon: Icons.remove_rounded,
            foregroundColor: foregroundColor,
            onTap: onMinimize,
          ),
          _WindowCaptionBtn(
            tooltip: isMaximized ? '向下还原' : '最大化',
            icon: isMaximized ? Icons.filter_none_rounded : Icons.crop_square_rounded,
            iconSize: isMaximized ? 13.5 : 15,
            foregroundColor: foregroundColor,
            onTap: onToggleMaximize,
          ),
          _WindowCaptionBtn(
            tooltip: '关闭',
            icon: Icons.close_rounded,
            isClose: true,
            foregroundColor: foregroundColor,
            onTap: onClose,
          ),
        ],
      ),
    );
  }
}

class _WindowCaptionBtn extends StatefulWidget {
  final String tooltip;
  final IconData icon;
  final double iconSize;
  final bool isClose;
  final Color foregroundColor;
  final VoidCallback onTap;

  const _WindowCaptionBtn({
    required this.tooltip,
    required this.icon,
    this.iconSize = 16,
    this.isClose = false,
    required this.foregroundColor,
    required this.onTap,
  });

  @override
  State<_WindowCaptionBtn> createState() => _WindowCaptionBtnState();
}

class _WindowCaptionBtnState extends State<_WindowCaptionBtn> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    final bg = _hover
        ? (widget.isClose
            ? const Color(0xFFE81123)
            : widget.foregroundColor.withValues(alpha: 0.08))
        : Colors.transparent;
    final fg = (_hover && widget.isClose)
        ? Colors.white
        : widget.foregroundColor.withValues(alpha: _hover ? 1.0 : 0.78);
    return Tooltip(
      message: widget.tooltip,
      waitDuration: const Duration(milliseconds: 500),
      child: MouseRegion(
        cursor: SystemMouseCursors.click,
        onEnter: (_) => setState(() => _hover = true),
        onExit: (_) => setState(() => _hover = false),
        child: GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: widget.onTap,
          child: Container(
            width: 46,
            height: 36,
            color: bg,
            alignment: Alignment.center,
            child: Icon(
              widget.icon,
              size: widget.iconSize,
              color: fg,
            ),
          ),
        ),
      ),
    );
  }
}

class _InitErrorScreen extends StatelessWidget {
  final String error;

  const _InitErrorScreen({required this.error});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.error_outline_rounded, size: 48, color: Colors.redAccent),
              const SizedBox(height: 16),
              const Text(
                '绿果PC 内核初始化失败',
                style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700),
              ),
              const SizedBox(height: 10),
              SelectableText(error, textAlign: TextAlign.center),
            ],
          ),
        ),
      ),
    );
  }
}

class MainShell extends StatefulWidget {
  final String initialSeriesId;

  const MainShell({super.key, this.initialSeriesId = ''});

  @override
  State<MainShell> createState() => _MainShellState();
}

class _MainShellState extends State<MainShell> with WindowListener {
  final FocusNode _focusNode = FocusNode();

  AppTab _tab = AppTab.home;
  AppTab _prevNonDetailTab = AppTab.home;
  final Set<AppTab> _mountedTabs = {AppTab.home};
  String? _rankBoard;
  final List<String> _detailStack = [];

  bool _homeSolidNav = true;
  bool _searchOpen = false;
  bool _authOpen = false;

  bool _loggedIn = false;
  UserProfile? _profile;
  int _daysLeft = -1;
  int _activeDownloads = 0;
  bool _isMaximized = false;
  bool _refreshingDlCount = false;
  DateTime? _lastMeRefreshAt;
  Process? _playerProcess;
  int _playerLaunchSeq = 0;

  String? _toastMsg;
  Timer? _toastTimer;
  Timer? _dlPollTimer;

  @override
  void initState() {
    super.initState();
    if (widget.initialSeriesId.isNotEmpty) {
      _prevNonDetailTab = AppTab.home;
      _detailStack.add(widget.initialSeriesId);
      _tab = AppTab.detail;
    }
    windowManager.addListener(this);
    _syncMaximized();
    _refreshMe();
    _refreshDownloadCount();
    _dlPollTimer = Timer.periodic(
      const Duration(seconds: 2),
      (_) => _refreshDownloadCount(),
    );
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        AppPreloader.instance.startWarmup();
        unawaited(AppUpdateManager.instance.checkOnStartup(context));
      }
    });
  }

  Future<void> _syncMaximized() async {
    final max = await windowManager.isMaximized();
    if (mounted) setState(() => _isMaximized = max);
  }

  @override
  void onWindowMaximize() {
    if (mounted) setState(() => _isMaximized = true);
  }

  @override
  void onWindowUnmaximize() {
    if (mounted) setState(() => _isMaximized = false);
  }

  @override
  void dispose() {
    windowManager.removeListener(this);
    _toastTimer?.cancel();
    _dlPollTimer?.cancel();
    _focusNode.dispose();
    super.dispose();
  }

  @override
  void onWindowFocus() {
    _refreshDownloadCount();
    final now = DateTime.now();
    if (_lastMeRefreshAt == null ||
        now.difference(_lastMeRefreshAt!) > const Duration(seconds: 30)) {
      _refreshMe();
    }
  }

  void _showToast(String msg) {
    _toastTimer?.cancel();
    setState(() => _toastMsg = msg);
    _toastTimer = Timer(const Duration(milliseconds: 2400), () {
      if (mounted) setState(() => _toastMsg = null);
    });
  }

  Future<void> _refreshMe() async {
    _lastMeRefreshAt = DateTime.now();
    try {
      final me = await RustBridge.instance.callMap('me');
      if (!mounted) return;
      final logged = me['loggedIn'] == true;
      setState(() {
        _loggedIn = logged;
        _daysLeft = (me['daysLeft'] as num?)?.toInt() ?? -1;
        _profile = logged && me['profile'] is Map
            ? UserProfile.fromJson(Map<String, dynamic>.from(me['profile'] as Map))
            : null;
      });
    } catch (_) {}
  }

  Future<void> _refreshDownloadCount() async {
    if (_refreshingDlCount) return;
    _refreshingDlCount = true;
    try {
      final res = await RustBridge.instance.callMap('download.summary');
      if (!mounted) return;
      final active = (res['active'] as num?)?.toInt() ?? 0;
      if (active != _activeDownloads) {
        setState(() => _activeDownloads = active);
      }
    } catch (_) {
    } finally {
      _refreshingDlCount = false;
    }
  }

  Future<void> _logout() async {
    try {
      await RustBridge.instance.callMap('logout');
      if (!mounted) return;
      setState(() {
        _loggedIn = false;
        _profile = null;
        _daysLeft = -1;
      });
      _showToast('已退出登录');
    } catch (e) {
      _showToast('退出失败：$e');
    }
  }

  void _selectTab(AppTab next) {
    setState(() {
      _searchOpen = false;
      _authOpen = false;
      if (next != AppTab.detail) {
        _prevNonDetailTab = next;
        _detailStack.clear();
      }
      if (next == AppTab.home ||
          next == AppTab.feed ||
          next == AppTab.rank ||
          next == AppTab.category) {
        _mountedTabs.add(next);
      }
      _tab = next;
      if (next != AppTab.home) {
        _homeSolidNav = true;
      }
    });
  }

  void _openDetail(String seriesId) {
    if (seriesId.isEmpty) return;
    setState(() {
      _searchOpen = false;
      _authOpen = false;
      if (_tab != AppTab.detail) {
        _prevNonDetailTab = _tab;
      }
      _detailStack.add(seriesId);
      _tab = AppTab.detail;
    });
  }

  void _backFromDetail() {
    setState(() {
      if (_detailStack.length > 1) {
        _detailStack.removeLast();
      } else {
        _detailStack.clear();
        _tab = _prevNonDetailTab;
      }
    });
  }

  /// Open playback in a single dedicated OS window: if a previous player window
  /// is still open, close it cleanly before opening the newly requested one.
  Future<void> _openPlayerWindow({
    required String seriesId,
    int episodeIndex = 0,
    int positionMs = 0,
    String title = '',
  }) async {
    if (seriesId.isEmpty) return;
    final seq = ++_playerLaunchSeq;
    final prev = _playerProcess;
    _playerProcess = null;
    if (prev != null) {
      if (Platform.isWindows) {
        try {
          final res = await Process.run('taskkill', ['/PID', '${prev.pid}'])
              .timeout(const Duration(milliseconds: 500));
          if (res.exitCode == 0) {
            await Future<void>.delayed(const Duration(milliseconds: 120));
          }
        } catch (_) {}
      }
      try {
        prev.kill();
      } catch (_) {}
    }
    if (!mounted || seq != _playerLaunchSeq) return;
    try {
      final proc = await Process.start(
        Platform.resolvedExecutable,
        [
          '--player',
          '--series',
          seriesId,
          '--ep',
          '$episodeIndex',
          '--pos',
          '$positionMs',
          if (title.isNotEmpty) ...['--title', title],
        ],
        mode: ProcessStartMode.detached,
      );
      if (!mounted || seq != _playerLaunchSeq) {
        try {
          proc.kill();
        } catch (_) {}
        return;
      }
      _playerProcess = proc;
    } catch (e) {
      _showToast('无法打开播放窗口：$e');
    }
  }

  KeyEventResult _handleGlobalKey(FocusNode node, KeyEvent event) {
    if (event is! KeyDownEvent) return KeyEventResult.ignored;

    final primary = FocusManager.instance.primaryFocus;
    final inText = primary?.context?.widget is EditableText;

    final isCtrlK = event.logicalKey == LogicalKeyboardKey.keyK &&
        HardwareKeyboard.instance.isControlPressed;
    final isSlash = event.logicalKey == LogicalKeyboardKey.slash && !inText;

    if (isCtrlK || isSlash) {
      setState(() {
        _searchOpen = !_searchOpen;
        _authOpen = false;
      });
      return KeyEventResult.handled;
    }

    if (event.logicalKey == LogicalKeyboardKey.escape) {
      if (_searchOpen || _authOpen) {
        setState(() {
          _searchOpen = false;
          _authOpen = false;
        });
        return KeyEventResult.handled;
      }
    }
    return KeyEventResult.ignored;
  }

  Widget _buildSecondaryTab() {
    switch (_tab) {
      case AppTab.mine:
        return MinePage(
          loggedIn: _loggedIn,
          profile: _profile,
          onOpenAuth: () => setState(() {
            _authOpen = true;
            _searchOpen = false;
          }),
          onOpenDetail: _openDetail,
          onPlaySeries: (id, ep, pos, title) => _openPlayerWindow(
            seriesId: id,
            episodeIndex: ep,
            positionMs: pos,
            title: title,
          ),
          onToast: _showToast,
        );
      case AppTab.downloads:
        return DownloadsPage(
          onPlayOffline: (id, ep, pos, title) => _openPlayerWindow(
            seriesId: id,
            episodeIndex: ep,
            positionMs: pos,
            title: title,
          ),
          onToast: _showToast,
        );
      case AppTab.settings:
        return SettingsPage(
          loggedIn: _loggedIn,
          profile: _profile,
          daysLeft: _daysLeft,
          onOpenAuth: () => setState(() {
            _authOpen = true;
            _searchOpen = false;
          }),
          onLogout: _logout,
          onToast: _showToast,
        );
      case AppTab.detail:
        final sid = _detailStack.isNotEmpty ? _detailStack.last : '';
        return DetailPage(
          key: ValueKey('detail_$sid'),
          seriesId: sid,
          loggedIn: _loggedIn,
          onToast: _showToast,
          onSwitchSeries: _openDetail,
          onOpenAuth: () => setState(() {
            _authOpen = true;
            _searchOpen = false;
          }),
          onBack: _backFromDetail,
          onPlayEpisode: (d, _, epIdx, posMs) => _openPlayerWindow(
            seriesId: d.id,
            episodeIndex: epIdx,
            positionMs: posMs,
            title: d.title,
          ),
        );
      default:
        return const SizedBox.shrink();
    }
  }

  Widget _buildBody() {
    final isPrimary = _tab == AppTab.home ||
        _tab == AppTab.feed ||
        _tab == AppTab.rank ||
        _tab == AppTab.category;
    final primaryIdx = switch (_tab) {
      AppTab.home => 0,
      AppTab.feed => 1,
      AppTab.rank => 2,
      AppTab.category => 3,
      _ => 0,
    };

    return Stack(
      children: [
        Offstage(
          offstage: !isPrimary,
          child: IndexedStack(
            index: primaryIdx,
            children: [
              HomePage(
                onOpenDetail: _openDetail,
                onPlaySeries: (id, ep, pos, title) => _openPlayerWindow(
                  seriesId: id,
                  episodeIndex: ep,
                  positionMs: pos,
                  title: title,
                ),
                onGoRank: (board) {
                  setState(() {
                    _rankBoard = board;
                    _prevNonDetailTab = AppTab.rank;
                    _mountedTabs.add(AppTab.rank);
                    _tab = AppTab.rank;
                  });
                },
                onGoMine: () => _selectTab(AppTab.mine),
                onScrollSolidChanged: (solid) {
                  if (mounted && _tab == AppTab.home && _homeSolidNav != solid) {
                    setState(() => _homeSolidNav = solid);
                  }
                },
                onToast: _showToast,
              ),
              if (_mountedTabs.contains(AppTab.feed))
                FeedPage(
                  active: _tab == AppTab.feed,
                  onToast: _showToast,
                  onOpenDetail: _openDetail,
                )
              else
                const SizedBox.shrink(),
              if (_mountedTabs.contains(AppTab.rank))
                RankPage(
                  initialBoard: _rankBoard,
                  onOpenDetail: _openDetail,
                )
              else
                const SizedBox.shrink(),
              if (_mountedTabs.contains(AppTab.category))
                CategoryPage(onOpenDetail: _openDetail)
              else
                const SizedBox.shrink(),
            ],
          ),
        ),
        if (!isPrimary) Positioned.fill(child: _buildSecondaryTab()),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final p = LvguoPalette.of(context);
    final isHome = _tab == AppTab.home;
    final solidNav = isHome ? _homeSolidNav : true;

    return Focus(
      focusNode: _focusNode,
      autofocus: true,
      onKeyEvent: _handleGlobalKey,
      child: Scaffold(
        backgroundColor: p.bg,
        body: Column(
          children: [
            _AppTitleBar(
              title: '绿果PC',
              isMaximized: _isMaximized,
              backgroundColor: p.bg,
              foregroundColor: p.text,
              borderColor: p.sep,
              onMinimize: () => windowManager.minimize(),
              onToggleMaximize: () async {
                if (await windowManager.isMaximized()) {
                  await windowManager.unmaximize();
                } else {
                  await windowManager.maximize();
                }
              },
              onClose: () => windowManager.close(),
            ),
            Expanded(
              child: Stack(
                children: [
                  // Main #view area: full-bleed on home, below 64px nav on all other pages.
                  Positioned.fill(
                    top: isHome ? 0 : 64,
                    child: _buildBody(),
                  ),

                  // Fixed 64px #nav
                  Positioned(
                    left: 0,
                    right: 0,
                    top: 0,
                    child: TopNav(
                      activeTab: _tab,
                      solidNav: solidNav,
                      searchOpen: _searchOpen,
                      loggedIn: _loggedIn,
                      profile: _profile,
                      daysLeft: _daysLeft,
                      activeDownloadCount: _activeDownloads,
                      onSelectTab: _selectTab,
                      onToggleSearch: () => setState(() {
                        _searchOpen = !_searchOpen;
                        _authOpen = false;
                      }),
                      onOpenDownloads: () => _selectTab(AppTab.downloads),
                      onOpenSettings: () => _selectTab(AppTab.settings),
                      onOpenAuth: () => setState(() {
                        _authOpen = true;
                        _searchOpen = false;
                      }),
                      onLogout: _logout,
                    ),
                  ),

                  // SearchPopover handles its own full-screen click-outside barrier + top-right alignment
                  if (_searchOpen)
                    Positioned.fill(
                      child: SearchPopover(
                        onOpenDetail: _openDetail,
                        onClose: () => setState(() => _searchOpen = false),
                      ),
                    ),

                  // AuthPopover handles its own full-screen click-outside barrier + top-right alignment
                  if (_authOpen)
                    Positioned.fill(
                      child: AuthPopover(
                        onSuccess: () async {
                          setState(() => _authOpen = false);
                          await _refreshMe();
                          _showToast('登录成功');
                        },
                        onClose: () => setState(() => _authOpen = false),
                      ),
                    ),

                  // Floating #toast pill
                  if (_toastMsg != null)
                    Positioned(
                      left: 0,
                      right: 0,
                      bottom: 32,
                      child: IgnorePointer(
                        child: Center(
                          child: Container(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 18,
                              vertical: 10,
                            ),
                            decoration: BoxDecoration(
                              color: p.surface2.withValues(alpha: 0.96),
                              borderRadius: BorderRadius.circular(999),
                              border: Border.all(color: p.sep),
                              boxShadow: const [
                                BoxShadow(
                                  color: Color(0x33000000),
                                  blurRadius: 20,
                                  offset: Offset(0, 6),
                                ),
                              ],
                            ),
                            child: Text(
                              _toastMsg!,
                              style: TextStyle(
                                fontSize: 13,
                                fontWeight: FontWeight.w500,
                                color: p.text,
                              ),
                            ),
                          ),
                        ),
                      ),
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
