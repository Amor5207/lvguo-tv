import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

class SettingsStore extends ChangeNotifier {
  SettingsStore._();
  static final SettingsStore instance = SettingsStore._();

  SharedPreferences? _prefs;

  String themePref = 'auto'; // auto | light | dark
  String defaultQuality = '1080p';
  bool autoNext = true;
  bool keepRate = true;
  bool dockOpen = true;
  bool gpuAccel = true;
  String superRes = 'off'; // off | cas | fsr
  double playbackRate = 1.0;
  double volume = 100.0;
  bool muted = false;

  bool danmakuEnabled = true;
  double danmakuOpacity = 0.85;
  double danmakuFontSize = 18.0;
  double danmakuSpeed = 1.0;
  double danmakuArea = 0.5;
  String ignoredUpdateVersion = '';

  ThemeMode get themeMode {
    switch (themePref) {
      case 'light':
        return ThemeMode.light;
      case 'dark':
        return ThemeMode.dark;
      default:
        return ThemeMode.system;
    }
  }

  Future<void> load() async {
    _prefs = await SharedPreferences.getInstance();
    themePref = _prefs?.getString('lv.theme') ?? 'auto';
    defaultQuality = _prefs?.getString('lv.quality') ?? '1080p';
    autoNext = _prefs?.getBool('lv.autoNext') ?? true;
    keepRate = _prefs?.getBool('lv.keepRate') ?? true;
    dockOpen = _prefs?.getBool('lv.dock') ?? true;
    gpuAccel = _prefs?.getBool('lv.gpuAccel') ?? true;
    superRes = _prefs?.getString('lv.superRes') ?? 'off';
    playbackRate = _prefs?.getDouble('lv.rate') ?? 1.0;
    volume = _prefs?.getDouble('lv.vol') ?? 100.0;
    muted = _prefs?.getBool('lv.muted') ?? false;

    danmakuEnabled = _prefs?.getBool('lv.dm.on') ?? true;
    danmakuOpacity = _prefs?.getDouble('lv.dm.opacity') ?? 0.85;
    danmakuFontSize = _prefs?.getDouble('lv.dm.size') ?? 18.0;
    danmakuSpeed = _prefs?.getDouble('lv.dm.speed') ?? 1.0;
    danmakuArea = _prefs?.getDouble('lv.dm.area') ?? 0.5;
    ignoredUpdateVersion = _prefs?.getString('lv.update.ignored') ?? '';
    notifyListeners();
  }

  Future<void> setTheme(String val) async {
    themePref = val;
    await _prefs?.setString('lv.theme', val);
    notifyListeners();
  }

  Future<void> setDefaultQuality(String val) async {
    defaultQuality = val;
    await _prefs?.setString('lv.quality', val);
    notifyListeners();
  }

  Future<void> setAutoNext(bool val) async {
    autoNext = val;
    await _prefs?.setBool('lv.autoNext', val);
    notifyListeners();
  }

  Future<void> setKeepRate(bool val) async {
    keepRate = val;
    await _prefs?.setBool('lv.keepRate', val);
    notifyListeners();
  }

  Future<void> setDockOpen(bool val) async {
    dockOpen = val;
    await _prefs?.setBool('lv.dock', val);
    notifyListeners();
  }

  Future<void> setGpuAccel(bool val) async {
    gpuAccel = val;
    await _prefs?.setBool('lv.gpuAccel', val);
    notifyListeners();
  }

  Future<void> setSuperRes(String val) async {
    superRes = val;
    await _prefs?.setString('lv.superRes', val);
    notifyListeners();
  }

  Future<void> setPlaybackRate(double val) async {
    playbackRate = val;
    await _prefs?.setDouble('lv.rate', val);
    notifyListeners();
  }

  Future<void> setAudio({double? vol, bool? isMuted}) async {
    if (vol != null) {
      volume = vol.clamp(0.0, 100.0);
      await _prefs?.setDouble('lv.vol', volume);
    }
    if (isMuted != null) {
      muted = isMuted;
      await _prefs?.setBool('lv.muted', muted);
    }
    notifyListeners();
  }

  Future<void> setDanmaku({
    bool? enabled,
    double? opacity,
    double? fontSize,
    double? speed,
    double? area,
  }) async {
    if (enabled != null) {
      danmakuEnabled = enabled;
      await _prefs?.setBool('lv.dm.on', enabled);
    }
    if (opacity != null) {
      danmakuOpacity = opacity;
      await _prefs?.setDouble('lv.dm.opacity', opacity);
    }
    if (fontSize != null) {
      danmakuFontSize = fontSize;
      await _prefs?.setDouble('lv.dm.size', fontSize);
    }
    if (speed != null) {
      danmakuSpeed = speed;
      await _prefs?.setDouble('lv.dm.speed', speed);
    }
    if (area != null) {
      danmakuArea = area;
      await _prefs?.setDouble('lv.dm.area', area);
    }
    notifyListeners();
  }

  Future<void> setIgnoredUpdateVersion(String val) async {
    ignoredUpdateVersion = val;
    await _prefs?.setString('lv.update.ignored', val);
    notifyListeners();
  }
}
