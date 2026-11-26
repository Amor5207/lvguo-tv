import 'dart:async';
import 'dart:io';

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter/services.dart';
import 'package:media_kit/media_kit.dart';
import 'package:media_kit_video/media_kit_video.dart';
import 'package:window_manager/window_manager.dart';

import '../core/rust_bridge.dart';
import '../core/settings_store.dart';
import '../core/theme.dart';
import '../models/models.dart';
import 'emoji_text.dart';
import 'web_primitives.dart';

class _DanmakuBullet {
  final String text;
  final int lane;
  double progress = 0.0;
  Widget? cachedWidget;
  double cachedSize = -1;

  _DanmakuBullet({required this.text, required this.lane});

  Widget buildRich(double fontSize) {
    if (cachedWidget == null || (cachedSize - fontSize).abs() > 0.1) {
      cachedSize = fontSize;
      cachedWidget = RepaintBoundary(
        child: EmojiText(
          text,
          style: TextStyle(
            color: Colors.white,
            fontSize: fontSize,
            fontWeight: FontWeight.w700,
            shadows: const [
              Shadow(color: Colors.black, blurRadius: 1.5, offset: Offset(1, 1)),
            ],
          ),
        ),
      );
    }
    return cachedWidget!;
  }
}

/// Pure GPU-transform Flow delegate so active danmaku scroll at VSync
/// without triggering widget rebuilds or Stack relayouts every frame.
class _DanmakuFlowDelegate extends FlowDelegate {
  final List<_DanmakuBullet> bullets;
  final double laneHeight;

  _DanmakuFlowDelegate({
    required this.bullets,
    required this.laneHeight,
    required Listenable repaint,
  }) : super(repaint: repaint);

  @override
  BoxConstraints getConstraintsForChild(int i, BoxConstraints constraints) =>
      BoxConstraints(
        minWidth: 0,
        maxWidth: double.infinity,
        minHeight: 0,
        maxHeight: constraints.maxHeight,
      );

  @override
  void paintChildren(FlowPaintingContext context) {
    final w = context.size.width;
    final n = bullets.length;
    for (int i = 0; i < n && i < context.childCount; i++) {
      final b = bullets[i];
      final dx = w * (1.05 - b.progress);
      final dy = b.lane * laneHeight;
      context.paintChild(i, transform: Matrix4.translationValues(dx, dy, 0.0));
    }
  }

  @override
  bool shouldRepaint(covariant _DanmakuFlowDelegate oldDelegate) =>
      oldDelegate.bullets.length != bullets.length ||
      oldDelegate.laneHeight != laneHeight ||
      !identical(oldDelegate.bullets, bullets);
}

enum _PopMenuKind { none, vol, dmset, rate, quality, superres }

/// Pure state evaluation for player overlays so episode transitions stay deterministic
/// and testable across fast switches, slow switches, pauses, and hidden UI states.
@visibleForTesting
({bool showUi, bool showLoadingSpinner, bool showCenterPlay, bool hideCursor})
    computePlayerOverlayFlags({
  required bool uiHidden,
  required bool playing,
  required bool buffering,
  required bool switchingEpisode,
  required bool loadingStream,
  required bool graceTimerActive,
  required bool menuOpen,
  required String streamError,
}) {
  final inFastSwitchGrace = switchingEpisode && graceTimerActive;
  final showUi = !uiHidden ||
      (!playing && !switchingEpisode && !loadingStream && !graceTimerActive) ||
      menuOpen;
  final showLoadingSpinner = loadingStream ||
      (!inFastSwitchGrace && buffering && (playing || switchingEpisode));
  final showCenterPlay = !playing &&
      !switchingEpisode &&
      !loadingStream &&
      !buffering &&
      streamError.isEmpty;
  final hideCursor = uiHidden && (playing || switchingEpisode || loadingStream);
  return (
    showUi: showUi,
    showLoadingSpinner: showLoadingSpinner,
    showCenterPlay: showCenterPlay,
    hideCursor: hideCursor,
  );
}

class CinemaPlayer extends StatefulWidget {
  final DetailData detail;
  final List<SeasonItem> seasons;
  final int initialEpisodeIndex;
  final int initialPositionMs;
  final bool feedMode;
  final bool active;
  final bool feedFavorite;
  final bool canFeedPrev;
  final bool canFeedNext;
  final VoidCallback? onFeedPrev;
  final VoidCallback? onFeedNext;
  final VoidCallback? onFeedToggleFav;
  final ValueChanged<String> onToast;
  final Future<DetailData?> Function(String seriesId)? onLoadSeasonDetail;
  final VoidCallback? onClose;

  const CinemaPlayer({
    super.key,
    required this.detail,
    required this.seasons,
    this.initialEpisodeIndex = 0,
    this.initialPositionMs = 0,
    this.feedMode = false,
    this.active = true,
    this.feedFavorite = false,
    this.canFeedPrev = false,
    this.canFeedNext = true,
    this.onFeedPrev,
    this.onFeedNext,
    this.onFeedToggleFav,
    required this.onToast,
    this.onLoadSeasonDetail,
    this.onClose,
  });

  @override
  State<CinemaPlayer> createState() => _CinemaPlayerState();
}

class _CinemaPlayerState extends State<CinemaPlayer>
    with TickerProviderStateMixin {
  late final Player _player;
  late final VideoController _videoCtrl;
  final _focusNode = FocusNode();

  late final AnimationController _enterCtrl;
  late final Animation<double> _enterOpacity;
  late final Animation<double> _enterScale;

  late final AnimationController _flashCtrl;
  IconData _flashIcon = Icons.play_arrow_rounded;

  late DetailData _activeDetail;
  late DetailData _browseDetail;
  late List<SeasonItem> _seasons;
  final Map<String, DetailData> _seasonDetails = {};
  bool _browseLoading = false;

  int _epIdx = 0;
  int _epGroupIdx = 0;
  String _quality = SettingsStore.instance.defaultQuality;
  String _activeDef = '';
  double _rate = SettingsStore.instance.keepRate
      ? SettingsStore.instance.playbackRate
      : 1.0;
  double _volume = SettingsStore.instance.volume;
  bool _muted = SettingsStore.instance.muted;
  bool _dockOn = true;
  int _dockTab = 0; // 0 = 选集, 1 = 评论
  bool _isFullscreen = false;

  bool _loadingStream = true;
  bool _switchingEpisode = false;
  bool _playingOffline = false;
  String _streamError = '';
  int _loadToken = 0;
  int _pendingSeekMs = 0;
  int _retryAttempts = 0;
  Timer? _loadingOverlayTimer;
  final Map<String, PlayResult> _playCache = {};
  final Set<String> _prefetchingVids = {};

  List<GearItem> _gears = const [];
  List<CommentItem> _comments = const [];
  bool _loadingComments = false;
  int _commentShown = 30;
  Set<String> _downloadedVids = const {};
  Map<String, String> _downloadStatusByVid = const {};
  final GlobalKey _activeSeasonKey = GlobalKey();
  final GlobalKey _activeEpKey = GlobalKey();

  final ValueNotifier<Duration> _positionNotifier = ValueNotifier(Duration.zero);
  final ValueNotifier<int> _dmFrameNotifier = ValueNotifier(0);
  final ValueNotifier<int> _dmListNotifier = ValueNotifier(0);
  Duration _lastPosition = Duration.zero;
  int _lastPosNotifyMs = 0;
  Duration get _position => _lastPosition;
  set _position(Duration v) {
    _lastPosition = v;
    _positionNotifier.value = v;
  }
  Duration _duration = Duration.zero;
  bool _playing = false;
  bool _buffering = false;

  // Idle UI & Popovers
  bool _uiHidden = false;
  bool _hoveringControls = false;
  bool _hoveringSeek = false;
  double _hoverSeekFrac = 0.0;
  bool _draggingSeek = false;
  double _dragSeekFrac = 0.0;
  _PopMenuKind _openMenu = _PopMenuKind.none;
  Timer? _idleTimer;
  Timer? _wheelCooldown;
  double _wheelAccum = 0;

  // Danmaku
  final List<_DanmakuBullet> _activeBullets = [];
  int _commentCursor = 0;
  late final Ticker _dmTicker;
  Duration _lastDmElapsed = Duration.zero;
  Timer? _dmSpawn;
  Timer? _progressTick;
  bool _disposed = false;
  bool _wasPlayingBeforeInactive = false;
  String _appliedSuperRes = SettingsStore.instance.superRes;
  bool _appliedGpuAccel = SettingsStore.instance.gpuAccel;
  Future<void>? _enhancementsFuture;

  final List<StreamSubscription<dynamic>> _subs = [];

  @override
  void initState() {
    super.initState();
    _enterCtrl = AnimationController(
      vsync: this,
      duration: Duration(milliseconds: widget.feedMode ? 0 : 420),
    );
    _enterOpacity = CurvedAnimation(
      parent: _enterCtrl,
      curve: const Cubic(0.22, 1, 0.36, 1),
    );
    _enterScale = Tween<double>(begin: 1.03, end: 1.0).animate(
      CurvedAnimation(parent: _enterCtrl, curve: const Cubic(0.22, 1, 0.36, 1)),
    );
    if (widget.feedMode) {
      _enterCtrl.value = 1.0;
    } else {
      _enterCtrl.forward();
    }

    _flashCtrl = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 550),
    );

    _activeDetail = widget.detail;
    _browseDetail = widget.detail;
    _seasons = widget.seasons;
    _seasonDetails[widget.detail.id] = widget.detail;
    _dockOn = !widget.feedMode && SettingsStore.instance.dockOpen;
    _epIdx = widget.initialEpisodeIndex.clamp(
      0,
      _activeDetail.episodes.isEmpty ? 0 : _activeDetail.episodes.length - 1,
    );
    _epGroupIdx = _epIdx ~/ 100;

    final gpuOn = SettingsStore.instance.gpuAccel;
    _player = Player(configuration: const PlayerConfiguration(title: '绿果PC'));
    _videoCtrl = VideoController(
      _player,
      configuration: VideoControllerConfiguration(
        hwdec: gpuOn ? 'auto-safe' : 'no',
        enableHardwareAcceleration: gpuOn,
      ),
    );
    SettingsStore.instance.addListener(_onSettingsChanged);
    _enhancementsFuture = _applyVideoEnhancements();

    _subs.add(_player.stream.playing.listen((v) {
      if (!mounted) return;
      final finishSwitch = _switchingEpisode &&
          v &&
          !_buffering &&
          (_duration > Duration.zero || _lastPosition > Duration.zero);
      setState(() {
        _playing = v;
        if (finishSwitch || (v && !_switchingEpisode)) {
          _loadingOverlayTimer?.cancel();
          _loadingStream = false;
          _switchingEpisode = false;
        }
      });
      _syncDmTicker();
      if (v) {
        _scheduleIdleHide();
      } else if (!_switchingEpisode &&
          !_loadingStream &&
          !(_loadingOverlayTimer?.isActive ?? false)) {
        _showUiNow();
      }
    }));

    _subs.add(_player.stream.position.listen((v) {
      if (!mounted || _draggingSeek) return;
      _lastPosition = v;
      if (_switchingEpisode && v > Duration.zero) {
        _loadingOverlayTimer?.cancel();
        if (_loadingStream || _switchingEpisode) {
          setState(() {
            _loadingStream = false;
            _switchingEpisode = false;
          });
        }
      }
      if (!_uiHidden || widget.feedMode) {
        final nowMs = DateTime.now().millisecondsSinceEpoch;
        if (v == Duration.zero || nowMs - _lastPosNotifyMs >= 65) {
          _lastPosNotifyMs = nowMs;
          _positionNotifier.value = v;
        }
      }
    }));

    _subs.add(_player.stream.duration.listen((v) {
      if (!mounted) return;
      setState(() => _duration = v);
      if (v > Duration.zero && _pendingSeekMs > 1000) {
        final seekTarget = _pendingSeekMs;
        _pendingSeekMs = 0;
        final clampedMs = seekTarget.clamp(0, (v.inMilliseconds - 250).clamp(0, v.inMilliseconds));
        if (clampedMs > 0) {
          _player.seek(Duration(milliseconds: clampedMs));
        }
      }
    }));

    _subs.add(_player.stream.buffering.listen((v) {
      if (!mounted) return;
      final finishSwitch = _switchingEpisode &&
          !v &&
          _playing &&
          (_duration > Duration.zero || _lastPosition > Duration.zero);
      setState(() {
        _buffering = v;
        if (finishSwitch) {
          _loadingOverlayTimer?.cancel();
          _loadingStream = false;
          _switchingEpisode = false;
        }
      });
    }));

    _subs.add(_player.stream.error.listen((err) {
      final msg = err.trim();
      if (msg.isEmpty || !mounted) return;
      if (_retryAttempts < 1) {
        _retryAttempts++;
        final ep = _currentEpisode;
        if (ep != null) _playCache.remove(ep.vid);
        _loadEpisode(_epIdx, seekMs: _position.inMilliseconds, forceFresh: true, isRetry: true);
        return;
      }
      _loadingOverlayTimer?.cancel();
      setState(() {
        _loadingStream = false;
        _switchingEpisode = false;
        _streamError = msg;
      });
    }));

    _subs.add(_player.stream.completed.listen((done) {
      if (done &&
          !_loadingStream &&
          _duration > Duration.zero &&
          SettingsStore.instance.autoNext) {
        if (_epIdx + 1 < _activeDetail.episodes.length) {
          _nextEpisode();
        } else if (widget.feedMode && widget.onFeedNext != null) {
          widget.onFeedNext!();
        } else if (!widget.feedMode) {
          _advanceToNextSeason(manual: false);
        }
      }
    }));

    _dmTicker = createTicker(_onDmTick);

    _dmSpawn = Timer.periodic(const Duration(milliseconds: 1200), (_) {
      if (!mounted || !_playing || !widget.active || !SettingsStore.instance.danmakuEnabled) return;
      if (_comments.isEmpty || _activeBullets.length >= 18) return;
      final c = _comments[_commentCursor % _comments.length];
      _commentCursor++;
      final lane = _commentCursor % 7;
      _activeBullets.add(_DanmakuBullet(text: c.content, lane: lane));
      _dmListNotifier.value++;
      _dmFrameNotifier.value++;
      _syncDmTicker();
    });

    _progressTick = Timer.periodic(const Duration(seconds: 10), (_) => _reportProgress());

    _refreshDownloadedState();
    _ensureSeasonsLoaded();
    _loadEpisode(_epIdx, seekMs: widget.initialPositionMs);
    _scheduleIdleHide();
    _scrollDockToActive();
  }

  void _onDmTick(Duration elapsed) {
    if (_disposed || !mounted || !widget.active || !_playing || !SettingsStore.instance.danmakuEnabled) {
      _syncDmTicker();
      return;
    }
    final dtSec = _lastDmElapsed == Duration.zero
        ? (1.0 / 120.0)
        : ((elapsed - _lastDmElapsed).inMicroseconds / 1e6).clamp(0.0, 0.05);
    _lastDmElapsed = elapsed;
    if (_activeBullets.isEmpty) {
      _syncDmTicker();
      return;
    }
    final step = dtSec * 0.125 * SettingsStore.instance.danmakuSpeed;
    for (final b in _activeBullets) {
      b.progress += step;
    }
    final prevLen = _activeBullets.length;
    _activeBullets.removeWhere((b) => b.progress > 1.35);
    if (_activeBullets.length != prevLen) {
      _dmListNotifier.value++;
    }
    _dmFrameNotifier.value++;
    if (_activeBullets.isEmpty) {
      _syncDmTicker();
    }
  }

  void _syncDmTicker() {
    final shouldTick = !_disposed &&
        widget.active &&
        _playing &&
        SettingsStore.instance.danmakuEnabled &&
        _activeBullets.isNotEmpty;
    if (shouldTick && !_dmTicker.isActive) {
      _lastDmElapsed = Duration.zero;
      _dmTicker.start();
    } else if (!shouldTick && _dmTicker.isActive) {
      _dmTicker.stop();
      _lastDmElapsed = Duration.zero;
    }
  }

  @override
  void didUpdateWidget(covariant CinemaPlayer oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.active != widget.active) {
      if (!widget.active) {
        _wasPlayingBeforeInactive = _playing;
        if (_playing) {
          unawaited(_player.pause());
        }
        _syncDmTicker();
      } else {
        if (_wasPlayingBeforeInactive) {
          _wasPlayingBeforeInactive = false;
          unawaited(_player.play());
        }
        _syncDmTicker();
      }
    }
    if (oldWidget.detail.id != widget.detail.id) {
      _reportProgress();
      _activeDetail = widget.detail;
      _browseDetail = widget.detail;
      _seasons = widget.seasons;
      _seasonDetails[widget.detail.id] = widget.detail;
      _epIdx = widget.initialEpisodeIndex.clamp(
        0,
        _activeDetail.episodes.isEmpty ? 0 : _activeDetail.episodes.length - 1,
      );
      _epGroupIdx = _epIdx ~/ 100;
      _refreshDownloadedState();
      _ensureSeasonsLoaded();
      _loadEpisode(_epIdx, seekMs: widget.initialPositionMs);
    } else if (widget.seasons.length != _seasons.length) {
      _seasons = widget.seasons;
    }
  }

  @override
  void dispose() {
    _disposed = true;
    SettingsStore.instance.removeListener(_onSettingsChanged);
    _reportProgress();
    if (_isFullscreen) {
      windowManager.setFullScreen(false);
    }
    _loadingOverlayTimer?.cancel();
    _idleTimer?.cancel();
    _wheelCooldown?.cancel();
    if (_dmTicker.isActive) _dmTicker.stop();
    _dmTicker.dispose();
    _dmSpawn?.cancel();
    _progressTick?.cancel();
    for (final s in _subs) {
      s.cancel();
    }
    _enterCtrl.dispose();
    _flashCtrl.dispose();
    _positionNotifier.dispose();
    _dmFrameNotifier.dispose();
    _dmListNotifier.dispose();
    _player.dispose();
    _focusNode.dispose();
    super.dispose();
  }

  void _onSettingsChanged() {
    if (_disposed || !mounted) return;
    final store = SettingsStore.instance;
    if (store.superRes != _appliedSuperRes || store.gpuAccel != _appliedGpuAccel) {
      _enhancementsFuture = _applyVideoEnhancements();
    }
  }

  Future<String> _ensureShaderFile(String assetFileName) async {
    final baseDir = RustBridge.instance.dataDir.isNotEmpty
        ? RustBridge.instance.dataDir
        : Directory.systemTemp.path;
    final dir = Directory('$baseDir${Platform.pathSeparator}shaders');
    if (!await dir.exists()) {
      await dir.create(recursive: true);
    }
    final target = File('${dir.path}${Platform.pathSeparator}$assetFileName');
    final content = await rootBundle.loadString('assets/shaders/$assetFileName');
    if (!await target.exists() || await target.readAsString() != content) {
      await target.writeAsString(content, flush: true);
    }
    return target.path;
  }

  Future<void> _applyVideoEnhancements() async {
    if (_disposed || !mounted) return;
    final plat = _player.platform;
    if (plat is! NativePlayer) return;
    try {
      await _videoCtrl.platform.future;
      await plat.waitForPlayerInitialization;
      if (_disposed || !mounted) return;
      final gpuOn = SettingsStore.instance.gpuAccel;
      final sr = SettingsStore.instance.superRes;
      _appliedSuperRes = sr;
      _appliedGpuAccel = gpuOn;
      await plat.setProperty('http-proxy', '');
      await plat.setProperty('network-timeout', '15');
      await plat.setProperty('cache', 'yes');
      await plat.setProperty('demuxer-max-bytes', '67108864');
      await plat.setProperty('demuxer-readahead-secs', '20');
      await plat.setProperty('vd-lavc-threads', '0');
      if (gpuOn) {
        await plat.setProperty('hwdec-codecs', 'all');
        await plat.setProperty('vd-lavc-dr', 'yes');
      }
      if (_disposed || !mounted) return;
      // Clear first so switching between CAS and FSR forces mpv to reload the shader chain.
      await plat.setProperty('glsl-shaders', '');
      if (sr == 'off') {
        await plat.setProperty('sharpen', '0');
        await plat.setProperty('deband', 'no');
        await plat.setProperty('scale', 'spline36');
        await plat.setProperty('cscale', 'spline36');
        await plat.setProperty('dscale', 'mitchell');
        await plat.setProperty('sigmoid-upscaling', 'no');
      } else if (!gpuOn) {
        await plat.setProperty('scale', 'spline36');
        await plat.setProperty('cscale', 'bilinear');
        await plat.setProperty('dscale', 'mitchell');
        await plat.setProperty('sigmoid-upscaling', 'no');
        await plat.setProperty('deband', 'no');
        await plat.setProperty('sharpen', sr == 'fsr' ? '0.75' : '0.4');
      } else {
        final assetName = sr == 'fsr' ? 'fsr_superres.glsl' : 'cas_sharpen.glsl';
        final shaderPath = await _ensureShaderFile(assetName);
        if (_disposed || !mounted) return;
        if (sr == 'fsr') {
          await plat.setProperty('scale', 'ewa_lanczossharp');
          await plat.setProperty('cscale', 'spline36');
          await plat.setProperty('dscale', 'mitchell');
          await plat.setProperty('correct-downscaling', 'yes');
          await plat.setProperty('sigmoid-upscaling', 'yes');
          await plat.setProperty('deband', 'no');
          await plat.setProperty('sharpen', '0.2');
        } else {
          await plat.setProperty('scale', 'spline36');
          await plat.setProperty('cscale', 'spline36');
          await plat.setProperty('dscale', 'mitchell');
          await plat.setProperty('correct-downscaling', 'yes');
          await plat.setProperty('sigmoid-upscaling', 'no');
          await plat.setProperty('deband', 'no');
          await plat.setProperty('sharpen', '0.25');
        }
        await plat.setProperty(
          'glsl-shaders',
          shaderPath.replaceAll('\\', '/'),
        );
      }
    } catch (_) {}
  }

  EpisodeItem? get _currentEpisode =>
      _activeDetail.episodes.isNotEmpty && _epIdx < _activeDetail.episodes.length
          ? _activeDetail.episodes[_epIdx]
          : null;

  void _triggerFlash(IconData icon) {
    _flashIcon = icon;
    _flashCtrl.forward(from: 0.0);
  }

  void _togglePlayPause({bool flash = true}) {
    final willPlay = !_playing;
    if (willPlay) {
      if (flash) {
        _triggerFlash(Icons.play_arrow_rounded);
      }
    } else {
      // Clear any active play flash so center play & pause icons never overlap
      _flashCtrl.stop();
      _flashCtrl.value = 0.0;
    }
    _player.playOrPause();
  }

  void _showUiNow() {
    if (_positionNotifier.value != _lastPosition) {
      _positionNotifier.value = _lastPosition;
    }
    if (_uiHidden && mounted) setState(() => _uiHidden = false);
    _scheduleIdleHide();
  }

  void _scheduleIdleHide() {
    _idleTimer?.cancel();
    _idleTimer = Timer(const Duration(milliseconds: 2800), () {
      if (!mounted ||
          !_playing ||
          _hoveringControls ||
          _openMenu != _PopMenuKind.none ||
          _draggingSeek) {
        return;
      }
      setState(() {
        _uiHidden = true;
        _openMenu = _PopMenuKind.none;
      });
    });
  }

  Future<void> _ensureSeasonsLoaded() async {
    if (_seasons.isNotEmpty || !_activeDetail.multiSeason) return;
    try {
      final res = await RustBridge.instance.callMap('seasons', {
        'id': _activeDetail.id,
        'quick': true,
      });
      final list = (res['items'] as List<dynamic>? ?? const [])
          .map((e) => SeasonItem.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList();
      if (mounted && list.isNotEmpty) {
        setState(() => _seasons = list);
        _scrollDockToActive();
      }
    } catch (_) {}
  }

  void _scrollDockToActive({bool resetToPlayingSeason = false}) {
    if (resetToPlayingSeason) {
      setState(() {
        if (_browseDetail.id != _activeDetail.id) {
          _browseDetail = _activeDetail;
        }
        _epGroupIdx = _epIdx ~/ 100;
      });
    }
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_dockOn || _dockTab != 0) return;
      final seasonCtx = _activeSeasonKey.currentContext;
      if (seasonCtx != null) {
        Scrollable.ensureVisible(
          seasonCtx,
          alignment: 0.4,
          duration: const Duration(milliseconds: 220),
          curve: Curves.easeOutCubic,
        );
      }
      final epCtx = _activeEpKey.currentContext;
      if (epCtx != null) {
        Scrollable.ensureVisible(
          epCtx,
          alignment: 0.35,
          duration: const Duration(milliseconds: 220),
          curve: Curves.easeOutCubic,
        );
      }
    });
  }

  static int _dlStatusRank(String st) {
    switch (st) {
      case 'completed':
        return 5;
      case 'downloading':
        return 4;
      case 'queued':
        return 3;
      case 'paused':
        return 2;
      case 'failed':
        return 1;
      default:
        return 0;
    }
  }

  Future<void> _refreshDownloadedState() async {
    try {
      final res = await RustBridge.instance.callMap('download.list');
      final done = <String>{};
      final statusMap = <String, String>{};
      for (final raw in (res['items'] as List<dynamic>? ?? const [])) {
        final t = DownloadTaskItem.fromJson(Map<String, dynamic>.from(raw as Map));
        if (t.vid.isEmpty) continue;
        if (t.status == 'completed') {
          done.add(t.vid);
        }
        final prev = statusMap[t.vid];
        if (prev == null || _dlStatusRank(t.status) > _dlStatusRank(prev)) {
          statusMap[t.vid] = t.status;
        }
      }
      if (mounted) {
        setState(() {
          _downloadedVids = done;
          _downloadStatusByVid = statusMap;
        });
      }
    } catch (_) {}
  }

  Future<void> _prefetchEpisode(int idx) async {
    if (idx < 0 || idx >= _activeDetail.episodes.length) return;
    final ep = _activeDetail.episodes[idx];
    if (ep.vid.isEmpty ||
        _playCache.containsKey(ep.vid) ||
        _prefetchingVids.contains(ep.vid)) {
      return;
    }
    _prefetchingVids.add(ep.vid);
    try {
      final res = await RustBridge.instance.callMap('play', {
        'vid': ep.vid,
        'q': _quality,
        'fresh': false,
        'prefetch': true,
      });
      if (!mounted) return;
      _playCache[ep.vid] = PlayResult.fromJson(res);
    } catch (_) {
    } finally {
      _prefetchingVids.remove(ep.vid);
    }
  }

  Future<void> _waitForEpisodeFrameRendered(
    dynamic platformCtrl,
    int tok,
  ) async {
    if (tok == 1) {
      await (platformCtrl.waitUntilFirstFrameRendered as Future<void>).timeout(
        const Duration(milliseconds: 1200),
        onTimeout: () {},
      );
      return;
    }
    if (_player.state.playing &&
        !_player.state.buffering &&
        _player.state.duration > Duration.zero) {
      return;
    }
    final done = Completer<void>();
    void finish() {
      if (!done.isCompleted) done.complete();
    }

    late final StreamSubscription<Duration> posSub;
    late final StreamSubscription<bool> bufSub;
    posSub = _player.stream.position.listen((d) {
      if (d > Duration.zero || tok != _loadToken || !mounted) finish();
    });
    bufSub = _player.stream.buffering.listen((b) {
      if ((!b && _player.state.playing && _player.state.duration > Duration.zero) ||
          tok != _loadToken ||
          !mounted) {
        finish();
      }
    });
    try {
      await done.future.timeout(const Duration(milliseconds: 1500), onTimeout: () {});
    } finally {
      await posSub.cancel();
      await bufSub.cancel();
    }
  }

  Future<void> _loadEpisode(
    int idx, {
    int seekMs = 0,
    bool forceFresh = false,
    bool isRetry = false,
  }) async {
    if (_activeDetail.episodes.isEmpty) return;
    final clamped = idx.clamp(0, _activeDetail.episodes.length - 1);
    final ep = _activeDetail.episodes[clamped];
    final tok = ++_loadToken;
    if (!isRetry) {
      _retryAttempts = 0;
    }
    _pendingSeekMs = seekMs;

    _loadingOverlayTimer?.cancel();
    final hasCached = !forceFresh && _playCache.containsKey(ep.vid);
    final isSwitch = tok > 1;
    setState(() {
      _epIdx = clamped;
      if (_browseDetail.id == _activeDetail.id) {
        _epGroupIdx = clamped ~/ 100;
      }
      _switchingEpisode = isSwitch;
      // Show immediate spinner ONLY for initial uncached load; episode switches wait 280ms grace
      // so fast preloaded/local switches show zero loading overlay & zero play/pause button.
      _loadingStream = !hasCached && !isSwitch;
      _streamError = '';
      _playingOffline = false;
      _duration = Duration.zero;
      _position = Duration.zero;
      _activeBullets.clear();
      _dmListNotifier.value++;
      _commentCursor = 0;
    });
    if (_dockOn && _dockTab == 0 && _browseDetail.id == _activeDetail.id) {
      _scrollDockToActive();
    }
    if (isSwitch || hasCached) {
      _loadingOverlayTimer = Timer(const Duration(milliseconds: 280), () {
        if (mounted && tok == _loadToken && _streamError.isEmpty) {
          setState(() => _loadingStream = true);
        }
      });
    }

    _loadComments(ep.vid, tok);
    // Immediately preload next episode when entering the current episode
    unawaited(_prefetchEpisode(clamped + 1));

    try {
      final PlayResult playRes;
      if (!forceFresh && _playCache.containsKey(ep.vid)) {
        playRes = _playCache[ep.vid]!;
      } else {
        final res = await RustBridge.instance.callMap('play', {
          'vid': ep.vid,
          'q': _quality,
          'fresh': forceFresh,
        });
        playRes = PlayResult.fromJson(res);
        _playCache[ep.vid] = playRes;
      }
      if (!mounted || tok != _loadToken) return;
      setState(() {
        _gears = playRes.gears;
        _activeDef = playRes.definition.isNotEmpty ? playRes.definition : _quality;
      });

      String targetSource = playRes.mediaUrl;
      bool offline = false;
      if (playRes.localFile != null &&
          playRes.localFile!.isNotEmpty &&
          File(playRes.localFile!).existsSync()) {
        targetSource = playRes.localFile!;
        offline = true;
      }

      if (targetSource.isEmpty) {
        _loadingOverlayTimer?.cancel();
        setState(() {
          _loadingStream = false;
          _switchingEpisode = false;
          _streamError = '未获取到播放地址';
        });
        return;
      }

      // Ensure VideoController texture/vo backend and shaders are initialized BEFORE opening media
      final platformCtrl = await _videoCtrl.platform.future;
      if (!mounted || tok != _loadToken) return;
      if (_enhancementsFuture != null) {
        await _enhancementsFuture;
        if (!mounted || tok != _loadToken) return;
      }

      await _player.open(Media(targetSource), play: true);
      if (!mounted || tok != _loadToken) return;

      await _player.setRate(_rate);
      await _player.setVolume(_muted ? 0 : _volume);

      // Clear spinner when first frame of this episode actually begins rendering
      await _waitForEpisodeFrameRendered(platformCtrl, tok);

      if (!mounted || tok != _loadToken) return;
      final frameReady = !isSwitch ||
          _player.state.position > Duration.zero ||
          (_player.state.playing && !_player.state.buffering);
      if (frameReady) {
        _loadingOverlayTimer?.cancel();
      }
      setState(() {
        if (frameReady) {
          _loadingStream = false;
          _switchingEpisode = false;
        }
        _playingOffline = offline;
      });
      // Preload one episode further ahead once current frame is ready
      unawaited(_prefetchEpisode(clamped + 2));
    } catch (e) {
      if (!mounted || tok != _loadToken) return;
      _loadingOverlayTimer?.cancel();
      setState(() {
        _loadingStream = false;
        _switchingEpisode = false;
        _streamError = e.toString();
      });
    }
  }

  Future<void> _loadComments(String vid, int tok) async {
    setState(() {
      _loadingComments = true;
      _commentShown = 30;
    });
    try {
      final res = await RustBridge.instance.callMap('comments', {
        'id': _activeDetail.id,
        'vid': vid,
        'offset': 0,
      });
      if (!mounted || tok != _loadToken) return;
      final raw = (res['items'] ?? const []) as List<dynamic>;
      final parsed = raw
          .whereType<Map>()
          .map((e) => CommentItem.fromJson(Map<String, dynamic>.from(e)))
          .where((c) => c.content.trim().isNotEmpty)
          .toList();
      setState(() {
        _comments = parsed;
        _loadingComments = false;
      });
    } catch (_) {
      if (mounted && tok == _loadToken) {
        setState(() => _loadingComments = false);
      }
    }
  }

  void _reportProgress() {
    final ep = _currentEpisode;
    if (ep == null) return;
    if (widget.feedMode && _epIdx == 0) return;
    final posMs = _position.inMilliseconds;
    RustBridge.instance.call('progress.report', {
      'seriesId': _activeDetail.id,
      'vid': ep.vid,
      'index': _epIdx,
      'positionMs': posMs,
      'title': _activeDetail.title,
      'cover': _activeDetail.cover,
      'episodeCount': _activeDetail.episodes.length,
    }).catchError((_) {});
  }

  void _prevEpisode() {
    if (_epIdx <= 0) {
      widget.onToast('已经是第一集');
      return;
    }
    _reportProgress();
    _loadEpisode(_epIdx - 1);
  }

  void _nextEpisode() {
    if (_epIdx + 1 >= _activeDetail.episodes.length) {
      if (widget.feedMode && widget.onFeedNext != null) {
        widget.onFeedNext!();
      } else {
        _advanceToNextSeason(manual: true);
      }
      return;
    }
    _reportProgress();
    _loadEpisode(_epIdx + 1);
  }

  Future<void> _advanceToNextSeason({bool manual = false}) async {
    final curSeasonIdx = _seasons.indexWhere((s) => s.id == _activeDetail.id);
    if (curSeasonIdx < 0 || curSeasonIdx + 1 >= _seasons.length) {
      if (manual) widget.onToast('已经是最后一集');
      return;
    }
    final nxt = _seasons[curSeasonIdx + 1];
    final label = '第 ${nxt.number} 季';
    if (nxt.episodes > 0) {
      try {
        DetailData? nextDetail = _seasonDetails[nxt.id];
        if (nextDetail == null) {
          if (widget.onLoadSeasonDetail != null) {
            nextDetail = await widget.onLoadSeasonDetail!(nxt.id);
          } else {
            final res = await RustBridge.instance.callMap('detail', {'id': nxt.id});
            nextDetail = DetailData.fromJson(
              Map<String, dynamic>.from(res['detail'] as Map? ?? const {}),
            );
          }
          if (nextDetail != null) {
            _seasonDetails[nxt.id] = nextDetail;
          }
        }
        if (!mounted) return;
        if (nextDetail == null || nextDetail.episodes.isEmpty) {
          widget.onToast('$label暂时无法播放');
          return;
        }
        _reportProgress();
        widget.onToast('本季已播完，继续播放$label');
        setState(() {
          _activeDetail = nextDetail!;
          _browseDetail = nextDetail;
          _epGroupIdx = 0;
          _playCache.clear();
        });
        await _loadEpisode(0);
      } catch (_) {
        if (mounted) widget.onToast('下一季加载失败，请重试');
      }
      return;
    }

    // 下一季已公布但未上线：已预约就提示；没预约就弹窗询问是否预约
    try {
      final res = await RustBridge.instance.callMap('detail', {'id': nxt.id});
      if (!mounted) return;
      if (res['reserved'] == true) {
        widget.onToast('本季已播完 · $label已预约');
        return;
      }
      final p = LvguoPalette.of(context);
      final ok = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          backgroundColor: p.surface,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(18),
            side: BorderSide(color: p.sep),
          ),
          title: Text('$label尚未上线', style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700, color: p.text)),
          content: Text(
            '预约后，上线时会出现在「我的 · 预约」。',
            style: TextStyle(fontSize: 14, color: p.text2),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(ctx).pop(false),
              child: Text('再想想', style: TextStyle(color: p.text2)),
            ),
            FilledButton(
              style: FilledButton.styleFrom(
                backgroundColor: p.primary,
                foregroundColor: p.primaryOn,
                shape: const StadiumBorder(),
              ),
              onPressed: () => Navigator.of(ctx).pop(true),
              child: const Text('预约'),
            ),
          ],
        ),
      );
      if (ok != true || !mounted) return;
      await RustBridge.instance.callMap('reservation.set', {
        'seriesId': nxt.id,
        'add': true,
      });
      if (mounted) widget.onToast('已预约$label');
    } catch (e) {
      if (mounted) widget.onToast('$e');
    }
  }

  Future<void> _switchBrowseSeason(String seasonId) async {
    if (_browseDetail.id == seasonId) return;
    if (_seasonDetails.containsKey(seasonId)) {
      setState(() {
        _browseDetail = _seasonDetails[seasonId]!;
        _epGroupIdx = 0;
      });
      return;
    }
    setState(() => _browseLoading = true);
    try {
      DetailData? fetched;
      if (widget.onLoadSeasonDetail != null) {
        fetched = await widget.onLoadSeasonDetail!(seasonId);
      } else {
        final res = await RustBridge.instance.callMap('detail', {'id': seasonId, 'lite': true});
        fetched = DetailData.fromJson(Map<String, dynamic>.from(res['detail'] as Map? ?? const {}));
      }
      if (!mounted || fetched == null) return;
      _seasonDetails[seasonId] = fetched;
      setState(() {
        _browseDetail = fetched!;
        _epGroupIdx = 0;
        _browseLoading = false;
      });
    } catch (e) {
      if (mounted) {
        setState(() => _browseLoading = false);
        widget.onToast(e.toString());
      }
    }
  }

  Future<void> _showBatchDownloadDialog() async {
    final d = _activeDetail;
    if (d.episodes.isEmpty) return;
    final p = LvguoPalette.dark;
    final curEp = _currentEpisode;

    bool isBlockedStatus(String? st) =>
        st == 'completed' || st == 'downloading' || st == 'queued';

    final selected = <String>{
      if (curEp != null && !isBlockedStatus(_downloadStatusByVid[curEp.vid]))
        curEp.vid,
    };

    String quality = _activeDef.isNotEmpty ? _activeDef : _quality;
    if (!const ['1080p', '720p', '480p'].contains(quality)) {
      quality = SettingsStore.instance.defaultQuality;
    }

    bool dialogOpen = true;
    void Function(VoidCallback)? modalSetState;
    unawaited(_refreshDownloadedState().then((_) {
      if (!dialogOpen || !mounted || modalSetState == null) return;
      modalSetState!(() {
        selected.removeWhere((vid) => isBlockedStatus(_downloadStatusByVid[vid]));
      });
    }));

    await showDialog<void>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setModalState) {
          modalSetState = setModalState;
          int doneCount = 0;
          int activeCount = 0;
          for (final ep in d.episodes) {
            final st = _downloadStatusByVid[ep.vid];
            if (st == 'completed') {
              doneCount++;
            } else if (st == 'downloading' || st == 'queued') {
              activeCount++;
            }
          }
          final undownloadedCount = d.episodes.length - doneCount - activeCount;

          return AlertDialog(
            backgroundColor: p.surface,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(18),
              side: BorderSide(color: p.sep),
            ),
            title: Row(
              children: [
                Expanded(
                  child: Text(
                    '下载剧集 · ${d.title}',
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
              width: 540,
              height: 370,
              child: Column(
                children: [
                  Row(
                    children: [
                      TextButton(
                        onPressed: undownloadedCount == 0
                            ? null
                            : () {
                                setModalState(() {
                                  selected.clear();
                                  for (final ep in d.episodes) {
                                    if (!isBlockedStatus(_downloadStatusByVid[ep.vid])) {
                                      selected.add(ep.vid);
                                    }
                                  }
                                });
                              },
                        child: const Text('全选未下'),
                      ),
                      TextButton(
                        onPressed: () => setModalState(() => selected.clear()),
                        child: const Text('清空'),
                      ),
                      const Spacer(),
                      Text(
                        '已下 $doneCount · 下载中 $activeCount · 未下 $undownloadedCount · 已选 ${selected.length}',
                        style: TextStyle(fontSize: 12, color: p.text3),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  Expanded(
                    child: GridView.builder(
                      itemCount: d.episodes.length,
                      gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
                        maxCrossAxisExtent: 82,
                        mainAxisSpacing: 8,
                        crossAxisSpacing: 8,
                        childAspectRatio: 1.25,
                      ),
                      itemBuilder: (_, i) {
                        final ep = d.episodes[i];
                        final st = _downloadStatusByVid[ep.vid];
                        final isDone = st == 'completed';
                        final isActive = st == 'downloading' || st == 'queued';
                        final isFailed = st == 'failed';
                        final selectable = !isDone && !isActive;
                        final on = selectable && selected.contains(ep.vid);

                        final Color bgColor;
                        final Color borderColor;
                        final Color numColor;
                        final String subLabel;
                        final Color subColor;

                        if (isDone) {
                          bgColor = p.primary.withValues(alpha: 0.14);
                          borderColor = p.primary.withValues(alpha: 0.45);
                          numColor = p.primary;
                          subLabel = '已下';
                          subColor = p.primary;
                        } else if (isActive) {
                          bgColor = p.gold.withValues(alpha: 0.14);
                          borderColor = p.gold.withValues(alpha: 0.45);
                          numColor = p.gold;
                          subLabel = st == 'downloading' ? '下载中' : '队列中';
                          subColor = p.gold;
                        } else if (on) {
                          bgColor = p.primarySoft;
                          borderColor = p.primary;
                          numColor = p.primary;
                          subLabel = isFailed ? '重下' : '已选';
                          subColor = p.primary;
                        } else {
                          bgColor = p.fill2;
                          borderColor = isFailed ? p.danger.withValues(alpha: 0.45) : p.sep;
                          numColor = p.text;
                          subLabel = isFailed ? '失败' : '未下';
                          subColor = isFailed ? p.danger : p.text3;
                        }

                        return MouseRegion(
                          cursor: selectable ? SystemMouseCursors.click : SystemMouseCursors.basic,
                          child: GestureDetector(
                            onTap: !selectable
                                ? null
                                : () {
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
                              padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 4),
                              decoration: BoxDecoration(
                                color: bgColor,
                                borderRadius: BorderRadius.circular(8),
                                border: Border.all(color: borderColor),
                              ),
                              child: Column(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  Row(
                                    mainAxisSize: MainAxisSize.min,
                                    children: [
                                      if (isDone) ...[
                                        Icon(Icons.check_rounded, size: 12, color: numColor),
                                        const SizedBox(width: 2),
                                      ],
                                      Text(
                                        '${ep.index}',
                                        style: TextStyle(
                                          fontSize: 13,
                                          fontWeight: (on || isDone || isActive)
                                              ? FontWeight.w700
                                              : FontWeight.w500,
                                          color: numColor,
                                        ),
                                      ),
                                    ],
                                  ),
                                  const SizedBox(height: 2),
                                  Text(
                                    subLabel,
                                    style: TextStyle(
                                      fontSize: 10,
                                      fontWeight: FontWeight.w500,
                                      color: subColor,
                                    ),
                                  ),
                                ],
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
                          if (mounted) {
                            setState(() {
                              final nextMap = Map<String, String>.from(_downloadStatusByVid);
                              for (final ep in d.episodes) {
                                if (selected.contains(ep.vid) && nextMap[ep.vid] != 'completed') {
                                  nextMap[ep.vid] = 'queued';
                                }
                              }
                              _downloadStatusByVid = nextMap;
                            });
                          }
                          widget.onToast('已添加 ${items.length} 个下载任务');
                          unawaited(_refreshDownloadedState());
                        } catch (e) {
                          widget.onToast('添加下载失败：$e');
                        }
                      },
                child: const Text('开始下载'),
              ),
            ],
          );
        },
      ),
    ).whenComplete(() => dialogOpen = false);
  }

  Future<void> _toggleFullscreen() async {
    final next = !_isFullscreen;
    await windowManager.setFullScreen(next);
    if (mounted) setState(() => _isFullscreen = next);
  }

  void _onPointerSignal(PointerSignalEvent ev) {
    if (ev is! PointerScrollEvent) return;
    if (_wheelCooldown?.isActive ?? false) return;
    _wheelAccum += ev.scrollDelta.dy;
    if (_wheelAccum.abs() > 40) {
      final dir = _wheelAccum > 0 ? 1 : -1;
      _wheelAccum = 0;
      _wheelCooldown = Timer(const Duration(milliseconds: 650), () {});
      if (widget.feedMode) {
        if (dir > 0) {
          widget.onFeedNext?.call();
        } else {
          widget.onFeedPrev?.call();
        }
      } else {
        if (dir > 0) {
          _nextEpisode();
        } else {
          _prevEpisode();
        }
      }
    }
  }

  KeyEventResult _handleKey(FocusNode node, KeyEvent ev) {
    if (ev is! KeyDownEvent) return KeyEventResult.ignored;
    final key = ev.logicalKey;
    if (key == LogicalKeyboardKey.space || key == LogicalKeyboardKey.keyK) {
      _showUiNow();
      _togglePlayPause();
      return KeyEventResult.handled;
    } else if (key == LogicalKeyboardKey.arrowRight) {
      _showUiNow();
      final t = _position + const Duration(seconds: 10);
      _player.seek(_duration > Duration.zero && t > _duration ? _duration : t);
      _triggerFlash(Icons.fast_forward_rounded);
      return KeyEventResult.handled;
    } else if (key == LogicalKeyboardKey.arrowLeft) {
      _showUiNow();
      final t = _position - const Duration(seconds: 10);
      _player.seek(t.isNegative ? Duration.zero : t);
      _triggerFlash(Icons.fast_rewind_rounded);
      return KeyEventResult.handled;
    } else if (key == LogicalKeyboardKey.arrowUp) {
      if (widget.feedMode) {
        widget.onFeedPrev?.call();
      } else {
        _prevEpisode();
      }
      return KeyEventResult.handled;
    } else if (key == LogicalKeyboardKey.arrowDown) {
      if (widget.feedMode) {
        widget.onFeedNext?.call();
      } else {
        _nextEpisode();
      }
      return KeyEventResult.handled;
    } else if (key == LogicalKeyboardKey.keyN) {
      _nextEpisode();
      return KeyEventResult.handled;
    } else if (key == LogicalKeyboardKey.keyP) {
      _prevEpisode();
      return KeyEventResult.handled;
    } else if (key == LogicalKeyboardKey.keyF) {
      _toggleFullscreen();
      return KeyEventResult.handled;
    } else if (key == LogicalKeyboardKey.keyM) {
      _showUiNow();
      _muted = !_muted;
      _player.setVolume(_muted ? 0 : _volume);
      SettingsStore.instance.setAudio(isMuted: _muted);
      setState(() {});
      return KeyEventResult.handled;
    } else if (key == LogicalKeyboardKey.keyD) {
      _showUiNow();
      final next = !SettingsStore.instance.danmakuEnabled;
      SettingsStore.instance.setDanmaku(enabled: next);
      _syncDmTicker();
      widget.onToast(next ? '弹幕已开启' : '弹幕已关闭');
      setState(() {});
      return KeyEventResult.handled;
    } else if (key == LogicalKeyboardKey.keyT) {
      setState(() => _dockOn = !_dockOn);
      if (!widget.feedMode) SettingsStore.instance.setDockOpen(_dockOn);
      if (_dockOn && _dockTab == 0) {
        _scrollDockToActive(resetToPlayingSeason: true);
      }
      return KeyEventResult.handled;
    } else if (key == LogicalKeyboardKey.escape) {
      _showUiNow();
      if (_openMenu != _PopMenuKind.none) {
        setState(() => _openMenu = _PopMenuKind.none);
      } else if (_isFullscreen) {
        _toggleFullscreen();
      } else if (!widget.feedMode) {
        widget.onClose?.call();
      }
      return KeyEventResult.handled;
    }
    return KeyEventResult.ignored;
  }

  String _fmtDuration(Duration d) {
    final s = d.inSeconds.clamp(0, 359999);
    final h = s ~/ 3600;
    final m = (s % 3600) ~/ 60;
    final sec = s % 60;
    if (h > 0) {
      return '$h:${m.toString().padLeft(2, '0')}:${sec.toString().padLeft(2, '0')}';
    }
    return '$m:${sec.toString().padLeft(2, '0')}';
  }

  @override
  Widget build(BuildContext context) {
    final ep = _currentEpisode;
    final dm = SettingsStore.instance;
    final flags = computePlayerOverlayFlags(
      uiHidden: _uiHidden,
      playing: _playing,
      buffering: _buffering,
      switchingEpisode: _switchingEpisode,
      loadingStream: _loadingStream,
      graceTimerActive: _loadingOverlayTimer?.isActive ?? false,
      menuOpen: _openMenu != _PopMenuKind.none,
      streamError: _streamError,
    );
    final showUi = flags.showUi;
    final showLoadingSpinner = flags.showLoadingSpinner;
    final showCenterPlay = flags.showCenterPlay;

    return FadeTransition(
      opacity: _enterOpacity,
      child: ScaleTransition(
        scale: _enterScale,
        child: Focus(
          focusNode: _focusNode,
          autofocus: true,
          onKeyEvent: _handleKey,
          child: MouseRegion(
            onHover: (_) => _showUiNow(),
            cursor: flags.hideCursor
                ? SystemMouseCursors.none
                : SystemMouseCursors.basic,
            child: Container(
              color: Colors.black,
              child: Row(
                children: [
                  Expanded(
                    child: Listener(
                      onPointerSignal: _onPointerSignal,
                      child: Stack(
                        fit: StackFit.expand,
                        children: [
                          // Pure video stage + click/double-click
                          GestureDetector(
                            behavior: HitTestBehavior.opaque,
                            onTap: () {
                              _focusNode.requestFocus();
                              if (_openMenu != _PopMenuKind.none) {
                                setState(() => _openMenu = _PopMenuKind.none);
                                return;
                              }
                              _togglePlayPause();
                            },
                            onDoubleTap: _toggleFullscreen,
                            child: RepaintBoundary(
                              child: Center(
                                child: Video(
                                  controller: _videoCtrl,
                                  controls: NoVideoControls,
                                  fill: Colors.black,
                                ),
                              ),
                            ),
                          ),
                          // Feed poster cover while initial frame loads (.fd-cover)
                          if (widget.feedMode && _loadingStream && _activeDetail.cover.isNotEmpty)
                            IgnorePointer(
                              child: AnimatedOpacity(
                                duration: const Duration(milliseconds: 200),
                                opacity: _loadingStream ? 1.0 : 0.0,
                                child: Image.network(
                                  _activeDetail.cover,
                                  fit: BoxFit.cover,
                                  cacheWidth: 420,
                                  gaplessPlayback: true,
                                  errorBuilder: (_, __, ___) => const SizedBox.shrink(),
                                ),
                              ),
                            ),
                          // Danmaku overlay (.dml) isolated in RepaintBoundary
                          if (dm.danmakuEnabled)
                            Positioned.fill(
                              top: 84,
                              bottom: 130,
                              child: RepaintBoundary(
                                child: LayoutBuilder(
                                  builder: (_, box) {
                                    final usableH = box.maxHeight * dm.danmakuArea;
                                    final laneH = (usableH / 7).clamp(24.0, 44.0);
                                    return IgnorePointer(
                                      child: Opacity(
                                        opacity: dm.danmakuOpacity,
                                        child: ValueListenableBuilder<int>(
                                          valueListenable: _dmListNotifier,
                                          builder: (_, __, ___) {
                                            final bulletsSnap = List<_DanmakuBullet>.unmodifiable(_activeBullets);
                                            return Flow(
                                              delegate: _DanmakuFlowDelegate(
                                                bullets: bulletsSnap,
                                                laneHeight: laneH,
                                                repaint: _dmFrameNotifier,
                                              ),
                                              children: [
                                                for (final b in bulletsSnap)
                                                  b.buildRich(dm.danmakuFontSize),
                                              ],
                                            );
                                          },
                                        ),
                                      ),
                                    );
                                  },
                                ),
                              ),
                            ),
                          // Top & Bottom gradient shades (.ui fade 300ms)
                          Positioned(
                            left: 0,
                            right: 0,
                            top: 0,
                            height: 160,
                            child: IgnorePointer(
                              child: AnimatedOpacity(
                                duration: const Duration(milliseconds: 300),
                                opacity: showUi ? 1.0 : 0.0,
                                child: DecoratedBox(
                                  decoration: BoxDecoration(
                                    gradient: LinearGradient(
                                      begin: Alignment.topCenter,
                                      end: Alignment.bottomCenter,
                                      colors: [Colors.black.withValues(alpha: 0.55), Colors.transparent],
                                    ),
                                  ),
                                ),
                              ),
                            ),
                          ),
                          Positioned(
                            left: 0,
                            right: 0,
                            bottom: 0,
                            height: 220,
                            child: IgnorePointer(
                              child: AnimatedOpacity(
                                duration: const Duration(milliseconds: 300),
                                opacity: showUi ? 1.0 : 0.0,
                                child: DecoratedBox(
                                  decoration: BoxDecoration(
                                    gradient: LinearGradient(
                                      begin: Alignment.bottomCenter,
                                      end: Alignment.topCenter,
                                      colors: [Colors.black.withValues(alpha: 0.62), Colors.transparent],
                                    ),
                                  ),
                                ),
                              ),
                            ),
                          ),
                          // Center feedback pulse (.flash)
                          Center(
                            child: IgnorePointer(
                              child: AnimatedBuilder(
                                animation: _flashCtrl,
                                builder: (_, __) {
                                  final t = _flashCtrl.value;
                                  if (t == 0.0 || t >= 1.0) return const SizedBox.shrink();
                                  final op = t < 0.3
                                      ? (t / 0.3)
                                      : (1.0 - (t - 0.3) / 0.7);
                                  final sc = 0.7 + 0.6 * t;
                                  return Opacity(
                                    opacity: op.clamp(0.0, 1.0),
                                    child: Transform.scale(
                                      scale: sc,
                                      child: Container(
                                        width: 96,
                                        height: 96,
                                        decoration: const BoxDecoration(
                                          color: Color(0x59141414),
                                          shape: BoxShape.circle,
                                        ),
                                        child: Icon(_flashIcon, size: 42, color: Colors.white),
                                      ),
                                    ),
                                  );
                                },
                              ),
                            ),
                          ),
                          // Spinner (.pload)
                          if (showLoadingSpinner)
                            const Center(
                              child: IgnorePointer(
                                child: Column(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    SizedBox(
                                      width: 50,
                                      height: 50,
                                      child: CircularProgressIndicator(
                                        color: Colors.white,
                                        strokeWidth: 3,
                                      ),
                                    ),
                                    SizedBox(height: 14),
                                    Text(
                                      '正在加载',
                                      style: TextStyle(color: Colors.white70, fontSize: 13, fontWeight: FontWeight.w600),
                                    ),
                                  ],
                                ),
                              ),
                            ),
                          // Center play icon ONLY when paused (instant hide when resuming so no flash overlap)
                          if (showCenterPlay)
                            Center(
                              child: MouseRegion(
                                cursor: SystemMouseCursors.click,
                                child: GestureDetector(
                                  onTap: () => _togglePlayPause(flash: false),
                                  child: const Icon(
                                    Icons.play_arrow_rounded,
                                    size: 72,
                                    color: Colors.white,
                                    shadows: [Shadow(color: Colors.black54, blurRadius: 16)],
                                  ),
                                ),
                              ),
                            ),
                          // Error overlay (.perr)
                          if (_streamError.isNotEmpty)
                            Container(
                              color: Colors.black.withValues(alpha: 0.42),
                              alignment: Alignment.center,
                              child: Container(
                                width: 420,
                                padding: const EdgeInsets.fromLTRB(32, 32, 32, 28),
                                decoration: BoxDecoration(
                                  color: const Color(0xB8282A29),
                                  borderRadius: BorderRadius.circular(30),
                                  border: Border.all(color: Colors.white.withValues(alpha: 0.14), width: 0.5),
                                ),
                                child: Stack(
                                  clipBehavior: Clip.none,
                                  children: [
                                    Column(
                                      mainAxisSize: MainAxisSize.min,
                                      children: [
                                        const Text(
                                          '无法播放',
                                          style: TextStyle(color: Colors.white, fontSize: 21, fontWeight: FontWeight.w700),
                                        ),
                                        const SizedBox(height: 8),
                                        Text(
                                          _streamError,
                                          textAlign: TextAlign.center,
                                          style: const TextStyle(color: Colors.white70, fontSize: 14.5, height: 1.6),
                                        ),
                                        const SizedBox(height: 22),
                                        FilledButton.icon(
                                          onPressed: () => _loadEpisode(_epIdx, forceFresh: true),
                                          style: FilledButton.styleFrom(
                                            backgroundColor: LvguoPalette.dark.primary,
                                            foregroundColor: LvguoPalette.dark.primaryOn,
                                            shape: const StadiumBorder(),
                                            padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 13),
                                          ),
                                          icon: const Icon(Icons.refresh, size: 17),
                                          label: const Text('重试', style: TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
                                        ),
                                      ],
                                    ),
                                    if (!widget.feedMode && widget.onClose != null)
                                      Positioned(
                                        right: -18,
                                        top: -18,
                                        child: GestureDetector(
                                          onTap: widget.onClose,
                                          child: Container(
                                            width: 36,
                                            height: 36,
                                            decoration: BoxDecoration(
                                              color: Colors.white.withValues(alpha: 0.12),
                                              shape: BoxShape.circle,
                                            ),
                                            child: const Icon(Icons.close, size: 18, color: Colors.white),
                                          ),
                                        ),
                                      ),
                                  ],
                                ),
                              ),
                            ),
                          // Top bar (.ptop)
                          Positioned(
                            left: 28,
                            right: 28,
                            top: 24,
                            child: IgnorePointer(
                              ignoring: !showUi,
                              child: AnimatedOpacity(
                                duration: const Duration(milliseconds: 300),
                                opacity: showUi ? 1.0 : 0.0,
                                child: MouseRegion(
                                  onEnter: (_) => _hoveringControls = true,
                                  onExit: (_) => _hoveringControls = false,
                                  child: Row(
                                    children: [
                                      if (!widget.feedMode || _isFullscreen) ...[
                                        _CircleGlassBtn(
                                          icon: Icons.arrow_back_ios_new_rounded,
                                          tooltip: '返回 (Esc)',
                                          onTap: () {
                                            if (_isFullscreen) {
                                              _toggleFullscreen();
                                            } else {
                                              widget.onClose?.call();
                                            }
                                          },
                                        ),
                                        const SizedBox(width: 16),
                                      ],
                                      if (!widget.feedMode)
                                        Expanded(
                                          child: Column(
                                            crossAxisAlignment: CrossAxisAlignment.start,
                                            children: [
                                              Text(
                                                _activeDetail.title,
                                                maxLines: 1,
                                                overflow: TextOverflow.ellipsis,
                                                style: const TextStyle(
                                                  color: Colors.white,
                                                  fontSize: 18,
                                                  fontWeight: FontWeight.w700,
                                                  shadows: [Shadow(color: Colors.black54, blurRadius: 10)],
                                                ),
                                              ),
                                              Text(
                                                '第 ${ep?.index ?? (_epIdx + 1)} 集 · 共 ${_activeDetail.episodes.length} 集'
                                                '${_playingOffline ? ' · 本地离线播放' : ''}',
                                                style: TextStyle(
                                                  color: Colors.white.withValues(alpha: 0.72),
                                                  fontSize: 13.5,
                                                ),
                                              ),
                                            ],
                                          ),
                                        )
                                      else ...[
                                        const Spacer(),
                                        Text(
                                          '上下滑动 / ↑ ↓ 切换剧集',
                                          style: TextStyle(
                                            fontSize: 12.5,
                                            color: Colors.white.withValues(alpha: 0.72),
                                            shadows: const [Shadow(color: Colors.black54, blurRadius: 6)],
                                          ),
                                        ),
                                      ],
                                    ],
                                  ),
                                ),
                              ),
                            ),
                          ),
                          // Feed Mode floating right drama nav (.fd-nav)
                          if (widget.feedMode)
                            Positioned(
                              right: 28,
                              top: 0,
                              bottom: 0,
                              child: Center(
                                child: IgnorePointer(
                                  ignoring: !showUi,
                                  child: AnimatedOpacity(
                                    duration: const Duration(milliseconds: 300),
                                    opacity: showUi ? 1.0 : 0.0,
                                    child: MouseRegion(
                                      onEnter: (_) => _hoveringControls = true,
                                      onExit: (_) => _hoveringControls = false,
                                      child: Column(
                                        mainAxisSize: MainAxisSize.min,
                                        children: [
                                          _CircleGlassBtn(
                                            icon: Icons.keyboard_arrow_up_rounded,
                                            tooltip: '上一部 (↑)',
                                            enabled: widget.canFeedPrev,
                                            onTap: widget.onFeedPrev ?? () {},
                                          ),
                                          const SizedBox(height: 12),
                                          _CircleGlassBtn(
                                            icon: Icons.keyboard_arrow_down_rounded,
                                            tooltip: '下一部 (↓)',
                                            enabled: widget.canFeedNext,
                                            onTap: widget.onFeedNext ?? () {},
                                          ),
                                        ],
                                      ),
                                    ),
                                  ),
                                ),
                              ),
                            ),
                          // Bottom bar (.pbot)
                          Positioned(
                            left: 32,
                            right: 32,
                            bottom: 18,
                            child: IgnorePointer(
                              ignoring: !showUi,
                              child: AnimatedOpacity(
                                duration: const Duration(milliseconds: 300),
                                opacity: showUi ? 1.0 : 0.0,
                                child: MouseRegion(
                                  onEnter: (_) => _hoveringControls = true,
                                  onExit: (_) => _hoveringControls = false,
                                  child: Column(
                                    crossAxisAlignment: CrossAxisAlignment.start,
                                    mainAxisSize: MainAxisSize.min,
                                    children: [
                                      if (widget.feedMode) _buildFeedInfo(ep),
                                      _buildSeekBar(),
                                      const SizedBox(height: 6),
                                      _buildTransportRow(),
                                    ],
                                  ),
                                ),
                              ),
                            ),
                          ),
                          // Floating Popover Menus (.pmenu)
                          if (_openMenu != _PopMenuKind.none)
                            Positioned(
                              right: 36,
                              bottom: 84,
                              child: MouseRegion(
                                onEnter: (_) => _hoveringControls = true,
                                onExit: (_) => _hoveringControls = false,
                                child: _buildPopoverMenu(),
                              ),
                            ),
                          // Feed bottom 3px hairline progress bar (.fd-bar)
                          if (widget.feedMode && _duration.inMilliseconds > 0)
                            Positioned(
                              left: 0,
                              right: 0,
                              bottom: 0,
                              height: 3,
                              child: RepaintBoundary(
                                child: IgnorePointer(
                                  child: ValueListenableBuilder<Duration>(
                                    valueListenable: _positionNotifier,
                                    builder: (_, pos, __) => Stack(
                                      children: [
                                        Container(color: Colors.white.withValues(alpha: 0.16)),
                                        FractionallySizedBox(
                                          widthFactor: (pos.inMilliseconds / _duration.inMilliseconds)
                                              .clamp(0.0, 1.0),
                                          child: Container(color: LvguoPalette.dark.primary),
                                        ),
                                      ],
                                    ),
                                  ),
                                ),
                              ),
                            ),
                        ],
                      ),
                    ),
                  ),
                  // Right-side #0E0E0F 396px Dock (.dock) animated width
                  AnimatedContainer(
                    duration: const Duration(milliseconds: 280),
                    curve: const Cubic(0.22, 1, 0.36, 1),
                    width: _dockOn ? 396 : 0,
                    clipBehavior: Clip.hardEdge,
                    decoration: const BoxDecoration(color: Color(0xFF0E0E0F)),
                    child: _dockOn
                        ? OverflowBox(
                            alignment: Alignment.topLeft,
                            minWidth: 396,
                            maxWidth: 396,
                            child: RepaintBoundary(child: _buildDock()),
                          )
                        : const SizedBox.shrink(),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildFeedInfo(EpisodeItem? ep) {
    final parts = <String>[
      if (ep != null) '第 ${ep.index} 集 · 共 ${_activeDetail.episodes.length} 集',
      if (_activeDetail.tag.isNotEmpty) _activeDetail.tag,
      if (_activeDetail.plays > 0) formatPlays(_activeDetail.plays),
    ];
    return Padding(
      padding: const EdgeInsets.only(bottom: 14),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 680),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              _activeDetail.title,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                color: Colors.white,
                fontSize: 22,
                fontWeight: FontWeight.w700,
                shadows: [Shadow(color: Colors.black54, blurRadius: 8)],
              ),
            ),
            if (parts.isNotEmpty) ...[
              const SizedBox(height: 6),
              Text(
                parts.join(' · '),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(color: Colors.white.withValues(alpha: 0.86), fontSize: 13.5),
              ),
            ],
            if (_activeDetail.intro.isNotEmpty) ...[
              const SizedBox(height: 6),
              Text(
                _activeDetail.intro,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  color: Colors.white.withValues(alpha: 0.78),
                  fontSize: 13.5,
                  height: 1.5,
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildSeekBar() {
    final durMs = _duration.inMilliseconds;

    return RepaintBoundary(
      child: LayoutBuilder(
        builder: (_, box) {
          final w = box.maxWidth;
          return MouseRegion(
            onEnter: (_) => setState(() => _hoveringSeek = true),
            onExit: (_) => setState(() => _hoveringSeek = false),
            onHover: (ev) {
              if (w > 0) {
                setState(() => _hoverSeekFrac = (ev.localPosition.dx / w).clamp(0.0, 1.0));
              }
            },
            cursor: SystemMouseCursors.click,
            child: GestureDetector(
              behavior: HitTestBehavior.opaque,
              onHorizontalDragStart: (d) {
                if (w <= 0) return;
                setState(() {
                  _draggingSeek = true;
                  _dragSeekFrac = (d.localPosition.dx / w).clamp(0.0, 1.0);
                });
              },
              onHorizontalDragUpdate: (d) {
                if (w <= 0) return;
                setState(() => _dragSeekFrac = (d.localPosition.dx / w).clamp(0.0, 1.0));
              },
              onHorizontalDragEnd: (_) {
                if (durMs > 0) {
                  _player.seek(Duration(milliseconds: (_dragSeekFrac * durMs).round()));
                }
                setState(() => _draggingSeek = false);
              },
              onTapDown: (d) {
                if (w > 0 && durMs > 0) {
                  final frac = (d.localPosition.dx / w).clamp(0.0, 1.0);
                  _player.seek(Duration(milliseconds: (frac * durMs).round()));
                }
              },
              child: SizedBox(
                height: 26,
                child: ValueListenableBuilder<Duration>(
                  valueListenable: _positionNotifier,
                  builder: (_, pos, __) {
                    final curFrac = _draggingSeek
                        ? _dragSeekFrac
                        : (durMs > 0 ? (pos.inMilliseconds / durMs).clamp(0.0, 1.0) : 0.0);
                    return Stack(
                      clipBehavior: Clip.none,
                      alignment: Alignment.centerLeft,
                      children: [
                        AnimatedContainer(
                          duration: const Duration(milliseconds: 220),
                          height: (_hoveringSeek || _draggingSeek) ? 9 : 5,
                          width: w,
                          decoration: BoxDecoration(
                            color: Colors.white.withValues(alpha: 0.24),
                            borderRadius: BorderRadius.circular(99),
                          ),
                          alignment: Alignment.centerLeft,
                          child: FractionallySizedBox(
                            widthFactor: curFrac,
                            child: Container(
                              decoration: BoxDecoration(
                                color: Colors.white,
                                borderRadius: BorderRadius.circular(99),
                              ),
                            ),
                          ),
                        ),
                        AnimatedPositioned(
                          duration: const Duration(milliseconds: 80),
                          left: (curFrac * w - 9).clamp(0.0, (w - 18).clamp(0.0, w)),
                          child: AnimatedScale(
                            duration: const Duration(milliseconds: 200),
                            scale: (_hoveringSeek || _draggingSeek) ? 1.0 : 0.0,
                            child: Container(
                              width: 18,
                              height: 18,
                              decoration: const BoxDecoration(
                                color: Colors.white,
                                shape: BoxShape.circle,
                                boxShadow: [BoxShadow(color: Colors.black54, blurRadius: 10)],
                              ),
                            ),
                          ),
                        ),
                        if ((_hoveringSeek || _draggingSeek) && durMs > 0)
                          Positioned(
                            left: ((_draggingSeek ? _dragSeekFrac : _hoverSeekFrac) * w - 26)
                                .clamp(0.0, (w - 56).clamp(0.0, w)),
                            bottom: 32,
                            child: Container(
                              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
                              decoration: BoxDecoration(
                                color: const Color(0xD2282828),
                                borderRadius: BorderRadius.circular(10),
                              ),
                              child: Text(
                                _fmtDuration(Duration(
                                  milliseconds:
                                      ((_draggingSeek ? _dragSeekFrac : _hoverSeekFrac) * durMs)
                                          .round(),
                                )),
                                style: const TextStyle(
                                  color: Colors.white,
                                  fontSize: 13,
                                  fontWeight: FontWeight.w700,
                                ),
                              ),
                            ),
                          ),
                      ],
                    );
                  },
                ),
              ),
            ),
          );
        },
      ),
    );
  }

  Widget _buildTransportRow() {
    final dm = SettingsStore.instance;
    const green = Color(0xFFC6ED92);

    return LayoutBuilder(
      builder: (_, box) {
        final compact = box.maxWidth < 680;
        final btnW = compact ? 34.0 : 40.0;
        final textPadH = compact ? 8.0 : 11.0;

        return Row(
          children: [
            _PlainCtrlBtn(
              icon: _playing ? Icons.pause_rounded : Icons.play_arrow_rounded,
              iconSize: 20,
              width: btnW,
              tooltip: '播放 / 暂停 (空格)',
              onTap: () => _togglePlayPause(flash: false),
            ),
            _PlainCtrlBtn(
              icon: Icons.skip_previous_rounded,
              iconSize: 23,
              width: btnW,
              tooltip: '上一集 (P)',
              onTap: _prevEpisode,
            ),
            _PlainCtrlBtn(
              icon: Icons.skip_next_rounded,
              iconSize: 23,
              width: btnW,
              tooltip: '下一集 (N)',
              onTap: _nextEpisode,
            ),
            Padding(
              padding: EdgeInsets.only(left: compact ? 4 : 6, right: compact ? 6 : 12),
              child: ValueListenableBuilder<Duration>(
                valueListenable: _positionNotifier,
                builder: (_, pos, __) => Text(
                  '${_fmtDuration(pos)} / ${_fmtDuration(_duration)}',
                  style: TextStyle(
                    color: Colors.white.withValues(alpha: 0.85),
                    fontSize: compact ? 12.5 : 13.5,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
            ),
            const Spacer(),
            Flexible(
              flex: 0,
              child: FittedBox(
                fit: BoxFit.scaleDown,
                alignment: Alignment.centerRight,
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    if (widget.feedMode && widget.onFeedToggleFav != null)
                      _PlainCtrlBtn(
                        icon: widget.feedFavorite ? Icons.favorite : Icons.favorite_border,
                        iconSize: 20,
                        width: btnW,
                        color: widget.feedFavorite ? const Color(0xFFFF6B81) : null,
                        tooltip: widget.feedFavorite ? '已追剧' : '追剧',
                        onTap: widget.onFeedToggleFav!,
                      ),
                    if (!_playingOffline)
                      _PlainCtrlBtn(
                        icon: Icons.download_rounded,
                        iconSize: 20,
                        width: btnW,
                        tooltip: '下载整剧 / 选集',
                        onTap: _showBatchDownloadDialog,
                      ),
                    _PlainCtrlBtn(
                      icon: Icons.subtitles_outlined,
                      iconSize: 20,
                      width: btnW,
                      color: dm.danmakuEnabled ? green : null,
                      tooltip: '弹幕开关 (D)',
                      onTap: () {
                        final next = !dm.danmakuEnabled;
                        dm.setDanmaku(enabled: next);
                        _syncDmTicker();
                        widget.onToast(next ? '弹幕已开启' : '弹幕已关闭');
                        setState(() {});
                      },
                    ),
                    _PlainCtrlBtn(
                      icon: Icons.tune_rounded,
                      iconSize: 20,
                      width: btnW,
                      color: _openMenu == _PopMenuKind.dmset ? green : null,
                      tooltip: '弹幕设置',
                      onTap: () => setState(() {
                        _openMenu = _openMenu == _PopMenuKind.dmset ? _PopMenuKind.none : _PopMenuKind.dmset;
                      }),
                    ),
                    _PlainCtrlBtn(
                      icon: (_muted || _volume == 0) ? Icons.volume_off_rounded : Icons.volume_up_rounded,
                      iconSize: 20,
                      width: btnW,
                      color: _openMenu == _PopMenuKind.vol ? green : null,
                      tooltip: '音量 (M 静音)',
                      onTap: () => setState(() {
                        _openMenu = _openMenu == _PopMenuKind.vol ? _PopMenuKind.none : _PopMenuKind.vol;
                      }),
                    ),
                    _TextCtrlBtn(
                      label: '${_rate}x',
                      active: _openMenu == _PopMenuKind.rate || dm.keepRate,
                      horizontalPadding: textPadH,
                      tooltip: '倍速',
                      onTap: () => setState(() {
                        _openMenu = _openMenu == _PopMenuKind.rate ? _PopMenuKind.none : _PopMenuKind.rate;
                      }),
                    ),
                    _TextCtrlBtn(
                      label: dm.superRes == 'fsr'
                          ? '超分'
                          : dm.superRes == 'cas'
                              ? '锐化'
                              : '原画质',
                      active: _openMenu == _PopMenuKind.superres || dm.superRes != 'off',
                      horizontalPadding: textPadH,
                      tooltip: 'GPU 超分辨率画质增强',
                      onTap: () => setState(() {
                        _openMenu = _openMenu == _PopMenuKind.superres ? _PopMenuKind.none : _PopMenuKind.superres;
                      }),
                    ),
                    _TextCtrlBtn(
                      label: _activeDef.isNotEmpty ? _activeDef : _quality,
                      active: _openMenu == _PopMenuKind.quality,
                      horizontalPadding: textPadH,
                      tooltip: '清晰度',
                      onTap: () => setState(() {
                        _openMenu = _openMenu == _PopMenuKind.quality ? _PopMenuKind.none : _PopMenuKind.quality;
                      }),
                    ),
                    _TextCtrlBtn(
                      label: '选集',
                      active: _dockOn,
                      horizontalPadding: textPadH,
                      tooltip: '选集与评论 (T)',
                      onTap: () {
                        setState(() {
                          _openMenu = _PopMenuKind.none;
                          _dockOn = !_dockOn;
                        });
                        if (!widget.feedMode) dm.setDockOpen(_dockOn);
                        if (_dockOn && _dockTab == 0) {
                          _scrollDockToActive(resetToPlayingSeason: true);
                        }
                      },
                    ),
                    _PlainCtrlBtn(
                      icon: _isFullscreen ? Icons.fullscreen_exit_rounded : Icons.fullscreen_rounded,
                      iconSize: 22,
                      width: btnW,
                      tooltip: '全屏 (F)',
                      onTap: _toggleFullscreen,
                    ),
                  ],
                ),
              ),
            ),
          ],
        );
      },
    );
  }

  Widget _buildPopoverMenu() {
    final dm = SettingsStore.instance;
    const green = Color(0xFFC6ED92);

    return Container(
      width: _openMenu == _PopMenuKind.dmset
          ? 340
          : _openMenu == _PopMenuKind.vol
              ? 256
              : 172,
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: const Color(0xB8282A29),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: Colors.white.withValues(alpha: 0.14), width: 0.5),
        boxShadow: const [BoxShadow(color: Colors.black54, blurRadius: 48)],
      ),
      child: switch (_openMenu) {
        _PopMenuKind.vol => Row(
            children: [
              IconButton(
                onPressed: () {
                  _muted = !_muted || _volume == 0;
                  if (_volume == 0) _volume = 60;
                  _player.setVolume(_muted ? 0 : _volume);
                  dm.setAudio(vol: _volume, isMuted: _muted);
                  setState(() {});
                },
                icon: Icon(
                  (_muted || _volume == 0) ? Icons.volume_off : Icons.volume_up,
                  color: Colors.white,
                ),
              ),
              Expanded(
                child: Slider(
                  value: _muted ? 0 : _volume,
                  min: 0,
                  max: 100,
                  activeColor: Colors.white,
                  inactiveColor: Colors.white24,
                  onChanged: (v) {
                    _volume = v;
                    _muted = v == 0;
                    _player.setVolume(v);
                    dm.setAudio(vol: v, isMuted: _muted);
                    setState(() {});
                  },
                ),
              ),
              SizedBox(
                width: 30,
                child: Text(
                  '${(_muted ? 0 : _volume).round()}',
                  textAlign: TextAlign.right,
                  style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w700, fontSize: 13),
                ),
              ),
            ],
          ),
        _PopMenuKind.dmset => Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  const Text('弹幕', style: TextStyle(color: Colors.white, fontSize: 15, fontWeight: FontWeight.w600)),
                  WebSwitch(
                    value: dm.danmakuEnabled,
                    activeTrackColor: LvguoPalette.dark.primary,
                    activeThumbColor: Colors.white,
                    inactiveTrackColor: Colors.white.withValues(alpha: 0.22),
                    onChanged: (v) {
                      dm.setDanmaku(enabled: v);
                      _syncDmTicker();
                      setState(() {});
                    },
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Row(
                children: [
                  const SizedBox(width: 56, child: Text('区域', style: TextStyle(color: Colors.white70, fontSize: 13))),
                  Expanded(
                    child: Container(
                      padding: const EdgeInsets.all(2),
                      decoration: BoxDecoration(
                        color: Colors.white.withValues(alpha: 0.1),
                        borderRadius: BorderRadius.circular(10),
                      ),
                      child: Row(
                        children: [
                          for (final a in const [(0.25, '1/4'), (0.5, '半屏'), (0.75, '3/4'), (1.0, '全屏')])
                            Expanded(
                              child: GestureDetector(
                                onTap: () {
                                  dm.setDanmaku(area: a.$1);
                                  setState(() {});
                                },
                                child: Container(
                                  height: 28,
                                  alignment: Alignment.center,
                                  decoration: BoxDecoration(
                                    color: (dm.danmakuArea - a.$1).abs() < 0.05
                                        ? Colors.white.withValues(alpha: 0.22)
                                        : Colors.transparent,
                                    borderRadius: BorderRadius.circular(8),
                                  ),
                                  child: Text(a.$2, style: const TextStyle(color: Colors.white, fontSize: 12.5, fontWeight: FontWeight.w600)),
                                ),
                              ),
                            ),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
              _DmSlider(
                label: '不透明度',
                valueText: '${(dm.danmakuOpacity * 100).round()}%',
                value: dm.danmakuOpacity,
                min: 0.2,
                max: 1.0,
                onChanged: (v) {
                  dm.setDanmaku(opacity: v);
                  setState(() {});
                },
              ),
              _DmSlider(
                label: '速度',
                valueText: '${dm.danmakuSpeed.toStringAsFixed(1)}x',
                value: dm.danmakuSpeed,
                min: 0.6,
                max: 1.8,
                onChanged: (v) {
                  dm.setDanmaku(speed: v);
                  setState(() {});
                },
              ),
              _DmSlider(
                label: '字号',
                valueText: '${dm.danmakuFontSize.round()}px',
                value: dm.danmakuFontSize,
                min: 14,
                max: 28,
                onChanged: (v) {
                  dm.setDanmaku(fontSize: v);
                  setState(() {});
                },
              ),
            ],
          ),
        _PopMenuKind.rate => Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              for (final r in const [0.75, 1.0, 1.25, 1.5, 2.0])
                InkWell(
                  onTap: () {
                    _rate = r;
                    _player.setRate(r);
                    dm.setPlaybackRate(r);
                    setState(() => _openMenu = _PopMenuKind.none);
                  },
                  borderRadius: BorderRadius.circular(13),
                  child: Container(
                    height: 40,
                    padding: const EdgeInsets.symmetric(horizontal: 14),
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text(
                          '${r}x',
                          style: TextStyle(
                            color: _rate == r ? green : Colors.white,
                            fontSize: 14.5,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                        if (_rate == r) const Icon(Icons.check, size: 16, color: green),
                      ],
                    ),
                  ),
                ),
              Container(height: 1, margin: const EdgeInsets.symmetric(horizontal: 8, vertical: 5), color: Colors.white12),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 2),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    const Text('锁定倍速', style: TextStyle(color: Colors.white, fontSize: 13.5, fontWeight: FontWeight.w600)),
                    WebSwitch(
                      value: dm.keepRate,
                      activeTrackColor: LvguoPalette.dark.primary,
                      activeThumbColor: Colors.white,
                      inactiveTrackColor: Colors.white.withValues(alpha: 0.22),
                      onChanged: (v) {
                        dm.setKeepRate(v);
                        setState(() {});
                      },
                    ),
                  ],
                ),
              ),
            ],
          ),
        _PopMenuKind.quality => Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              for (final q in (_gears.isNotEmpty
                  ? _gears.map((g) => g.definition).toSet().toList().reversed
                  : ['1080p', '720p', '480p']))
                InkWell(
                  onTap: () {
                    final posMs = _position.inMilliseconds;
                    _quality = q;
                    _playCache.clear();
                    dm.setDefaultQuality(q);
                    setState(() => _openMenu = _PopMenuKind.none);
                    _loadEpisode(_epIdx, seekMs: posMs);
                  },
                  borderRadius: BorderRadius.circular(13),
                  child: Container(
                    height: 40,
                    padding: const EdgeInsets.symmetric(horizontal: 14),
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text(
                          q,
                          style: TextStyle(
                            color: q == _activeDef ? green : Colors.white,
                            fontSize: 14.5,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                        if (q == _activeDef) const Icon(Icons.check, size: 16, color: green),
                      ],
                    ),
                  ),
                ),
            ],
          ),
        _PopMenuKind.superres => Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              for (final opt in const [
                ('off', '原画质（关闭）', 'Spline36 标准缩放'),
                ('cas', '锐化增强（CAS）', '对比度自适应锐化'),
                ('fsr', '超分增强（FSR）', '边缘重建 + 细节超分'),
              ])
                InkWell(
                  onTap: () async {
                    await dm.setSuperRes(opt.$1);
                    if (_enhancementsFuture != null) {
                      await _enhancementsFuture;
                    } else {
                      await _applyVideoEnhancements();
                    }
                    if (!mounted) return;
                    setState(() => _openMenu = _PopMenuKind.none);
                    if (!dm.gpuAccel && opt.$1 != 'off') {
                      widget.onToast('画质增强已切换为：${opt.$2}（开启 GPU 加速效果更佳）');
                    } else {
                      widget.onToast('画质增强已切换为：${opt.$2}');
                    }
                  },
                  borderRadius: BorderRadius.circular(13),
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              opt.$2,
                              style: TextStyle(
                                color: dm.superRes == opt.$1 ? green : Colors.white,
                                fontSize: 13.5,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                            Text(
                              opt.$3,
                              style: TextStyle(
                                color: Colors.white.withValues(alpha: 0.5),
                                fontSize: 11,
                              ),
                            ),
                          ],
                        ),
                        if (dm.superRes == opt.$1) const Icon(Icons.check, size: 16, color: green),
                      ],
                    ),
                  ),
                ),
            ],
          ),
        _PopMenuKind.none => const SizedBox.shrink(),
      },
    );
  }

  /// Right-side 396px `#0E0E0F` dock matching `.dock` in web/ui/pc/app.css
  Widget _buildDock() {
    final sameSeason = _browseDetail.id == _activeDetail.id;
    final total = _browseDetail.episodes.length;
    final groups = <List<int>>[];
    if (total > 110) {
      for (int i = 0; i < total; i += 100) {
        groups.add([i + 1, (i + 100).clamp(1, total)]);
      }
    } else if (total > 0) {
      groups.add([1, total]);
    }
    final gi = _epGroupIdx.clamp(0, groups.isEmpty ? 0 : groups.length - 1);
    final startIdx = groups.isNotEmpty ? groups[gi][0] - 1 : 0;
    final endIdx = groups.isNotEmpty ? groups[gi][1] : 0;

    return Container(
      width: 396,
      decoration: const BoxDecoration(
        color: Color(0xFF0E0E0F),
        border: Border(left: BorderSide(color: Color(0x14FFFFFF))),
      ),
      child: Column(
        children: [
          // Header (.dh) with animated sliding underline .dseg
          Container(
            height: 60,
            padding: const EdgeInsets.fromLTRB(24, 0, 14, 0),
            decoration: const BoxDecoration(
              border: Border(bottom: BorderSide(color: Color(0x14FFFFFF))),
            ),
            child: Row(
              children: [
                UnderlineTabBar(
                  labels: const ['选集', '评论'],
                  activeIndex: _dockTab,
                  onChanged: (i) {
                    setState(() => _dockTab = i);
                    if (i == 0 && _dockOn) {
                      _scrollDockToActive(resetToPlayingSeason: true);
                    }
                  },
                  gap: 24,
                  height: 60,
                  fontSize: 15,
                  activeTextColor: Colors.white,
                  inactiveTextColor: Colors.white.withValues(alpha: 0.5),
                  hoverTextColor: Colors.white,
                  indicatorColor: Colors.white,
                  bottomBorderColor: Colors.transparent,
                ),
                const Spacer(),
                _PlainCtrlBtn(
                  icon: Icons.close,
                  tooltip: '收起',
                  onTap: () {
                    setState(() => _dockOn = false);
                    if (!widget.feedMode) SettingsStore.instance.setDockOpen(false);
                  },
                ),
              ],
            ),
          ),
          // Body (.db)
          Expanded(
            child: _dockTab == 0
                ? SingleChildScrollView(
                    padding: const EdgeInsets.fromLTRB(24, 20, 24, 28),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        // .epdetail
                        Container(
                          padding: const EdgeInsets.all(12),
                          margin: const EdgeInsets.only(bottom: 14),
                          decoration: BoxDecoration(
                            color: Colors.white.withValues(alpha: 0.06),
                            borderRadius: BorderRadius.circular(12),
                            border: Border.all(color: Colors.white.withValues(alpha: 0.07)),
                          ),
                          child: Row(
                            children: [
                              ClipRRect(
                                borderRadius: BorderRadius.circular(8),
                                child: SizedBox(
                                  width: 58,
                                  height: 78,
                                  child: _browseDetail.cover.isNotEmpty
                                      ? Image.network(
                                          _browseDetail.cover,
                                          fit: BoxFit.cover,
                                          cacheWidth: 180,
                                          gaplessPlayback: true,
                                        )
                                      : Container(color: Colors.white10),
                                ),
                              ),
                              const SizedBox(width: 12),
                              Expanded(
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Text(
                                      _browseDetail.title,
                                      maxLines: 1,
                                      overflow: TextOverflow.ellipsis,
                                      style: const TextStyle(color: Colors.white, fontSize: 14.5, fontWeight: FontWeight.w700),
                                    ),
                                    const SizedBox(height: 4),
                                    Text(
                                      [
                                        if (total > 0) '全 $total 集',
                                        if (_browseDetail.plays > 0) formatPlays(_browseDetail.plays),
                                        if (_browseDetail.tag.isNotEmpty) _browseDetail.tag,
                                      ].join(' · '),
                                      maxLines: 1,
                                      overflow: TextOverflow.ellipsis,
                                      style: TextStyle(color: Colors.white.withValues(alpha: 0.6), fontSize: 12),
                                    ),
                                    if (_browseDetail.intro.isNotEmpty) ...[
                                      const SizedBox(height: 4),
                                      Text(
                                        _browseDetail.intro,
                                        maxLines: 2,
                                        overflow: TextOverflow.ellipsis,
                                        style: TextStyle(color: Colors.white.withValues(alpha: 0.52), fontSize: 12, height: 1.4),
                                      ),
                                    ],
                                  ],
                                ),
                              ),
                            ],
                          ),
                        ),
                        // .epseas — strictly `第 N 季` (no extra title)
                        if (_seasons.length > 1)
                          Padding(
                            padding: const EdgeInsets.only(bottom: 14),
                            child: SingleChildScrollView(
                              scrollDirection: Axis.horizontal,
                              child: Row(
                                children: _seasons.map((s) {
                                  final cur = s.id == _browseDetail.id;
                                  final soon = s.episodes <= 0;
                                  return Padding(
                                    padding: const EdgeInsets.only(right: 8),
                                    child: MouseRegion(
                                      cursor: (cur || soon) ? SystemMouseCursors.basic : SystemMouseCursors.click,
                                      child: GestureDetector(
                                        onTap: (cur || soon) ? null : () => _switchBrowseSeason(s.id),
                                        child: Container(
                                          key: cur ? _activeSeasonKey : null,
                                          height: 34,
                                          padding: const EdgeInsets.symmetric(horizontal: 12),
                                          alignment: Alignment.center,
                                          decoration: BoxDecoration(
                                            color: cur ? Colors.white : Colors.white.withValues(alpha: 0.045),
                                            borderRadius: BorderRadius.circular(999),
                                            border: Border.all(
                                              color: cur ? Colors.white : Colors.white.withValues(alpha: 0.09),
                                            ),
                                          ),
                                          child: Row(
                                            mainAxisSize: MainAxisSize.min,
                                            children: [
                                              Text(
                                                '第 ${s.number} 季',
                                                style: TextStyle(
                                                  color: cur ? const Color(0xFF111111) : Colors.white.withValues(alpha: 0.62),
                                                  fontSize: 13,
                                                  fontWeight: cur ? FontWeight.w700 : FontWeight.w500,
                                                ),
                                              ),
                                              if (soon) ...[
                                                const SizedBox(width: 6),
                                                Text(
                                                  '待上线',
                                                  style: TextStyle(
                                                    color: cur ? const Color(0x85000000) : Colors.white.withValues(alpha: 0.48),
                                                    fontSize: 10,
                                                  ),
                                                ),
                                              ],
                                            ],
                                          ),
                                        ),
                                      ),
                                    ),
                                  );
                                }).toList(),
                              ),
                            ),
                          ),
                        if (!sameSeason && _currentEpisode != null)
                          Padding(
                            padding: const EdgeInsets.only(bottom: 12),
                            child: Text(
                              '当前仍在播放：${_activeDetail.title} · 第 ${_currentEpisode!.index} 集',
                              style: TextStyle(color: Colors.white.withValues(alpha: 0.52), fontSize: 12),
                            ),
                          ),
                        if (groups.length > 1)
                          Padding(
                            padding: const EdgeInsets.only(bottom: 14),
                            child: SingleChildScrollView(
                              scrollDirection: Axis.horizontal,
                              child: Row(
                                children: groups.asMap().entries.map((e) {
                                  final on = e.key == gi;
                                  return MouseRegion(
                                    cursor: SystemMouseCursors.click,
                                    child: GestureDetector(
                                      onTap: () => setState(() => _epGroupIdx = e.key),
                                      child: Container(
                                        height: 28,
                                        margin: const EdgeInsets.only(right: 4),
                                        padding: const EdgeInsets.symmetric(horizontal: 10),
                                        alignment: Alignment.center,
                                        decoration: BoxDecoration(
                                          color: on ? Colors.white.withValues(alpha: 0.1) : Colors.transparent,
                                          borderRadius: BorderRadius.circular(6),
                                        ),
                                        child: Text(
                                          '${e.value[0]}–${e.value[1]}',
                                          style: TextStyle(
                                            fontSize: 13,
                                            fontWeight: on ? FontWeight.w500 : FontWeight.w400,
                                            color: on ? Colors.white : Colors.white.withValues(alpha: 0.45),
                                          ),
                                        ),
                                      ),
                                    ),
                                  );
                                }).toList(),
                              ),
                            ),
                          ),
                        if (_browseLoading)
                          const Center(
                            child: Padding(
                              padding: EdgeInsets.all(24),
                              child: CircularProgressIndicator(color: Colors.white),
                            ),
                          )
                        else
                          GridView.builder(
                            shrinkWrap: true,
                            physics: const NeverScrollableScrollPhysics(),
                            gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                              crossAxisCount: 5,
                              mainAxisSpacing: 9,
                              crossAxisSpacing: 9,
                              childAspectRatio: 1.32,
                            ),
                            itemCount: (endIdx - startIdx).clamp(0, total),
                            itemBuilder: (_, idx) {
                              final epRealIdx = startIdx + idx;
                              final ep = _browseDetail.episodes[epRealIdx];
                              final cur = sameSeason && epRealIdx == _epIdx;
                              final seen = sameSeason && epRealIdx < _epIdx;
                              final cached = _downloadedVids.contains(ep.vid);

                              return InkWell(
                                onTap: () {
                                  _reportProgress();
                                  if (!sameSeason) {
                                    setState(() {
                                      _activeDetail = _browseDetail;
                                    });
                                  }
                                  _loadEpisode(epRealIdx);
                                },
                                borderRadius: BorderRadius.circular(10),
                                child: Container(
                                  key: cur ? _activeEpKey : null,
                                  decoration: BoxDecoration(
                                    color: cur
                                        ? Colors.white
                                        : seen
                                            ? Colors.white.withValues(alpha: 0.035)
                                            : Colors.white.withValues(alpha: 0.075),
                                    borderRadius: BorderRadius.circular(10),
                                    border: Border.all(
                                      color: cur ? Colors.white : Colors.white.withValues(alpha: 0.055),
                                    ),
                                  ),
                                  child: Stack(
                                    alignment: Alignment.center,
                                    children: [
                                      Row(
                                        mainAxisSize: MainAxisSize.min,
                                        children: [
                                          if (cur && _playing) ...[
                                            const EqualizerBars(color: Color(0xFF17A34A)),
                                            const SizedBox(width: 4),
                                          ],
                                          Text(
                                            '${ep.index}',
                                            style: TextStyle(
                                              fontSize: 15,
                                              fontWeight: cur ? FontWeight.w700 : FontWeight.w500,
                                              color: cur
                                                  ? const Color(0xFF111111)
                                                  : seen
                                                      ? Colors.white.withValues(alpha: 0.38)
                                                      : Colors.white.withValues(alpha: 0.9),
                                            ),
                                          ),
                                        ],
                                      ),
                                      if (cached)
                                        Positioned(
                                          right: 5,
                                          top: 5,
                                          child: Container(
                                            width: 5,
                                            height: 5,
                                            decoration: BoxDecoration(
                                              color: cur ? const Color(0xFF17A34A) : LvguoPalette.dark.primary,
                                              shape: BoxShape.circle,
                                            ),
                                          ),
                                        ),
                                    ],
                                  ),
                                ),
                              );
                            },
                          ),
                      ],
                    ),
                  )
                : _loadingComments
                    ? const Center(child: CircularProgressIndicator(color: Colors.white))
                    : _comments.isEmpty
                        ? Center(
                            child: Text(
                              '这一集还没有评论',
                              style: TextStyle(color: Colors.white.withValues(alpha: 0.45), fontSize: 14),
                            ),
                          )
                        : ListView(
                            padding: const EdgeInsets.fromLTRB(24, 16, 24, 28),
                            children: [
                              for (int i = 0; i < _comments.take(_commentShown).length; i++) ...[
                                if (i > 0)
                                  Divider(color: Colors.white.withValues(alpha: 0.07), height: 22),
                                Row(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    CircleAvatar(
                                      radius: 16,
                                      backgroundColor: Colors.white.withValues(alpha: 0.1),
                                      backgroundImage: _comments[i].avatar.isNotEmpty
                                          ? NetworkImage(_comments[i].avatar)
                                          : null,
                                      child: _comments[i].avatar.isEmpty
                                          ? Text(
                                              _comments[i].user.isNotEmpty
                                                  ? _comments[i].user.substring(0, 1)
                                                  : '用',
                                              style: const TextStyle(fontSize: 13, color: Colors.white),
                                            )
                                          : null,
                                    ),
                                    const SizedBox(width: 12),
                                    Expanded(
                                      child: Column(
                                        crossAxisAlignment: CrossAxisAlignment.start,
                                        children: [
                                          Text(
                                            _comments[i].user.isNotEmpty
                                                ? _comments[i].user
                                                : '匿名用户',
                                            style: TextStyle(
                                              fontSize: 12.5,
                                              color: Colors.white.withValues(alpha: 0.45),
                                            ),
                                          ),
                                          const SizedBox(height: 4),
                                          EmojiText(
                                            _comments[i].content,
                                            style: TextStyle(
                                              fontSize: 14,
                                              height: 1.7,
                                              color: Colors.white.withValues(alpha: 0.9),
                                            ),
                                          ),
                                        ],
                                      ),
                                    ),
                                  ],
                                ),
                              ],
                              if (_comments.length > _commentShown) ...[
                                const SizedBox(height: 14),
                                TextButton(
                                  onPressed: () => setState(() => _commentShown += 30),
                                  style: TextButton.styleFrom(
                                    backgroundColor: Colors.white.withValues(alpha: 0.08),
                                    foregroundColor: Colors.white.withValues(alpha: 0.8),
                                    minimumSize: const Size.fromHeight(40),
                                  ),
                                  child: const Text('加载更多'),
                                ),
                              ],
                            ],
                          ),
          ),
        ],
      ),
    );
  }
}

class _CircleGlassBtn extends StatelessWidget {
  final IconData icon;
  final String tooltip;
  final VoidCallback onTap;
  final bool enabled;

  const _CircleGlassBtn({
    required this.icon,
    required this.tooltip,
    required this.onTap,
    this.enabled = true,
  });

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: tooltip,
      child: Opacity(
        opacity: enabled ? 1.0 : 0.35,
        child: InkWell(
          onTap: enabled ? onTap : null,
          borderRadius: BorderRadius.circular(99),
          child: Container(
            width: 46,
            height: 46,
            decoration: BoxDecoration(
              color: const Color(0x88000000),
              shape: BoxShape.circle,
              border: Border.all(color: Colors.white.withValues(alpha: 0.32), width: 1),
              boxShadow: const [BoxShadow(color: Colors.black38, blurRadius: 10)],
            ),
            child: Icon(icon, size: 20, color: Colors.white),
          ),
        ),
      ),
    );
  }
}

class _PlainCtrlBtn extends StatelessWidget {
  final IconData icon;
  final double iconSize;
  final double width;
  final Color? color;
  final String tooltip;
  final VoidCallback onTap;

  const _PlainCtrlBtn({
    required this.icon,
    this.iconSize = 21,
    this.width = 40,
    this.color,
    required this.tooltip,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: tooltip,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(99),
        child: SizedBox(
          width: width,
          height: 40,
          child: Icon(
            icon,
            size: iconSize,
            color: color ?? Colors.white.withValues(alpha: 0.88),
          ),
        ),
      ),
    );
  }
}

class _TextCtrlBtn extends StatelessWidget {
  final String label;
  final bool active;
  final double horizontalPadding;
  final String tooltip;
  final VoidCallback onTap;

  const _TextCtrlBtn({
    required this.label,
    required this.active,
    this.horizontalPadding = 11,
    required this.tooltip,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: tooltip,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(99),
        child: Container(
          height: 36,
          padding: EdgeInsets.symmetric(horizontal: horizontalPadding),
          alignment: Alignment.center,
          child: Text(
            label,
            style: TextStyle(
              fontSize: 13.5,
              fontWeight: FontWeight.w700,
              color: active ? const Color(0xFFC6ED92) : Colors.white.withValues(alpha: 0.88),
            ),
          ),
        ),
      ),
    );
  }
}

class _DmSlider extends StatelessWidget {
  final String label;
  final String valueText;
  final double value;
  final double min;
  final double max;
  final ValueChanged<double> onChanged;

  const _DmSlider({
    required this.label,
    required this.valueText,
    required this.value,
    required this.min,
    required this.max,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        SizedBox(
          width: 64,
          child: Text(label, style: TextStyle(color: Colors.white.withValues(alpha: 0.75), fontSize: 13.5)),
        ),
        Expanded(
          child: Slider(
            value: value.clamp(min, max),
            min: min,
            max: max,
            activeColor: Colors.white,
            inactiveColor: Colors.white24,
            onChanged: onChanged,
          ),
        ),
        SizedBox(
          width: 44,
          child: Text(
            valueText,
            textAlign: TextAlign.right,
            style: const TextStyle(color: Colors.white, fontSize: 13, fontWeight: FontWeight.w600),
          ),
        ),
      ],
    );
  }
}
