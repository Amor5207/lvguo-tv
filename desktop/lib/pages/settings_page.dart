import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';

import '../core/rust_bridge.dart';
import '../core/settings_store.dart';
import '../core/theme.dart';
import '../core/update_manager.dart';
import '../models/models.dart';
import '../widgets/web_primitives.dart';

/// 1:1 with `settings()` + `.setp` (sticky left `.stoc` + borderless `.scard` hairline groups + `.skeys`)
/// in web/ui/pc/app.css + app.js, without Invite UI while keeping Desktop Download & About/Update settings.
class SettingsPage extends StatefulWidget {
  final bool loggedIn;
  final UserProfile? profile;
  final int daysLeft;
  final VoidCallback onOpenAuth;
  final VoidCallback onLogout;
  final ValueChanged<String> onToast;

  const SettingsPage({
    super.key,
    required this.loggedIn,
    required this.profile,
    this.daysLeft = -1,
    required this.onOpenAuth,
    required this.onLogout,
    required this.onToast,
  });

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  final _scrollCtrl = ScrollController();

  final _tocKeys = <String, GlobalKey>{
    'account': GlobalKey(),
    'playback': GlobalKey(),
    'danmaku': GlobalKey(),
    'download': GlobalKey(),
    'appearance': GlobalKey(),
    'shortcuts': GlobalKey(),
    'about': GlobalKey(),
  };
  String _activeToc = 'account';

  DownloadSettingsData? _dlSettings;
  HotUpdateStatusData? _hotStatus;
  bool _loading = true;

  final _speedCtrl = TextEditingController();

  @override
  void initState() {
    super.initState();
    AppUpdateManager.instance.addListener(_onUpdateChanged);
    _load();
  }

  @override
  void dispose() {
    AppUpdateManager.instance.removeListener(_onUpdateChanged);
    _scrollCtrl.dispose();
    _speedCtrl.dispose();
    super.dispose();
  }

  void _onUpdateChanged() {
    if (mounted) setState(() {});
  }

  Future<void> _load() async {
    try {
      final results = await Future.wait<Map<String, dynamic>>([
        RustBridge.instance.callMap('download.settings.get'),
        RustBridge.instance.callMap('hotupdate.status'),
      ]);
      if (!mounted) return;
      final dlObj = DownloadSettingsData.fromJson(results[0]);
      final hotObj = HotUpdateStatusData.fromJson(results[1]);
      _speedCtrl.text = dlObj.speedLimitKbps.toString();
      setState(() {
        _dlSettings = dlObj;
        _hotStatus = hotObj;
        _loading = false;
      });
    } catch (_) {
      if (mounted) setState(() => _loading = false);
    }
  }

  void _scrollTo(String key) {
    setState(() => _activeToc = key);
    final ctx = _tocKeys[key]?.currentContext;
    if (ctx != null) {
      Scrollable.ensureVisible(
        ctx,
        duration: const Duration(milliseconds: 280),
        curve: Curves.easeOutCubic,
        alignment: 0.04,
      );
    }
  }

  Future<void> _saveDownload(DownloadSettingsData next) async {
    try {
      final res = await RustBridge.instance.callMap(
        'download.settings.set',
        next.toJson(),
      );
      if (!mounted) return;
      setState(() => _dlSettings = DownloadSettingsData.fromJson(res));
      widget.onToast('下载设置已保存');
    } catch (e) {
      widget.onToast('保存失败：$e');
    }
  }

  Future<void> _pickDir() async {
    final s = _dlSettings;
    if (s == null) return;
    final dir = await FilePicker.platform.getDirectoryPath(
      dialogTitle: '选择默认下载目录',
      initialDirectory: s.downloadDir,
    );
    if (dir == null) return;
    await _saveDownload(DownloadSettingsData(
      downloadDir: dir,
      maxConcurrent: s.maxConcurrent,
      threadsPerTask: s.threadsPerTask,
      speedLimitKbps: s.speedLimitKbps,
      preferredQuality: s.preferredQuality,
      autoRetryCount: s.autoRetryCount,
      createSeriesSubdir: s.createSeriesSubdir,
      notifyOnComplete: s.notifyOnComplete,
      autoResumeOnStart: s.autoResumeOnStart,
    ));
  }

  Future<void> _refreshHotStatus() async {
    try {
      final res = await RustBridge.instance.callMap('hotupdate.status');
      if (!mounted) return;
      setState(() => _hotStatus = HotUpdateStatusData.fromJson(res));
    } catch (_) {}
  }

  Future<void> _handleCheckUpdate() async {
    await AppUpdateManager.instance.onManualCheck(
      context,
      onToast: widget.onToast,
    );
    await _refreshHotStatus();
  }

  Future<void> _rollbackAlgo() async {
    try {
      final res = await RustBridge.instance.callMap('hotupdate.rollback');
      if (!mounted) return;
      setState(() => _hotStatus = HotUpdateStatusData.fromJson(res));
      widget.onToast('已回滚至内置签名/解析算法');
    } catch (e) {
      widget.onToast('回滚失败：$e');
    }
  }

  @override
  Widget build(BuildContext context) {
    final p = LvguoPalette.of(context);
    final store = SettingsStore.instance;
    final w = MediaQuery.of(context).size.width;
    final gutter = w <= 1366 ? 40.0 : 56.0;

    if (_loading) {
      return Center(child: CircularProgressIndicator(color: p.primary, strokeWidth: 2.5));
    }

    const tocEntries = [
      ('account', '账号', Icons.person_outline_rounded),
      ('playback', '播放', Icons.play_circle_outline_rounded),
      ('danmaku', '弹幕', Icons.subtitles_outlined),
      ('download', '下载', Icons.download_rounded),
      ('appearance', '外观', Icons.brightness_auto_outlined),
      ('shortcuts', '快捷键', Icons.tune_rounded),
      ('about', '关于', Icons.movie_outlined),
    ];

    final dl = _dlSettings;
    final hot = _hotStatus;

    return SingleChildScrollView(
      controller: _scrollCtrl,
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 1120),
          child: Padding(
            padding: EdgeInsets.fromLTRB(gutter, 30, gutter, 100),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (w > 1000) ...[
                  SizedBox(
                    width: 184,
                    child: Padding(
                      padding: const EdgeInsets.only(top: 62),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: tocEntries.map((entry) {
                          final on = _activeToc == entry.$1;
                          return MouseRegion(
                            cursor: SystemMouseCursors.click,
                            child: GestureDetector(
                              onTap: () => _scrollTo(entry.$1),
                              child: Container(
                                height: 36,
                                margin: const EdgeInsets.only(bottom: 2),
                                padding: const EdgeInsets.symmetric(horizontal: 12),
                                decoration: BoxDecoration(
                                  color: on ? p.primarySoft : Colors.transparent,
                                  borderRadius: BorderRadius.circular(8),
                                ),
                                child: Row(
                                  children: [
                                    Icon(
                                      entry.$3,
                                      size: 16,
                                      color: on ? p.primary : p.text3,
                                    ),
                                    const SizedBox(width: 10),
                                    Text(
                                      entry.$2,
                                      style: TextStyle(
                                        fontSize: 14,
                                        fontWeight: on ? FontWeight.w600 : FontWeight.w400,
                                        color: on ? p.primary : p.text3,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ),
                          );
                        }).toList(),
                      ),
                    ),
                  ),
                  const SizedBox(width: 56),
                ],
                // Right .smain
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        '设置',
                        style: TextStyle(
                          fontSize: 30,
                          fontWeight: FontWeight.w800,
                          letterSpacing: -0.6,
                          color: p.text,
                        ),
                      ),
                      const SizedBox(height: 6),

                      // 1. 账号
                      _SettingGroup(
                        key: _tocKeys['account'],
                        title: '账号',
                        palette: p,
                        rows: [
                          widget.loggedIn
                              ? Padding(
                                  padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 13),
                                  child: Row(
                                    children: [
                                      Container(
                                        width: 44,
                                        height: 44,
                                        decoration: BoxDecoration(
                                          color: p.primarySoft,
                                          shape: BoxShape.circle,
                                        ),
                                        clipBehavior: Clip.antiAlias,
                                        child: widget.profile?.avatar.isNotEmpty == true
                                            ? Image.network(
                                                widget.profile!.avatar,
                                                fit: BoxFit.cover,
                                                cacheWidth: 120,
                                                gaplessPlayback: true,
                                              )
                                            : Center(
                                                child: Text(
                                                  (widget.profile?.name.isNotEmpty == true)
                                                      ? widget.profile!.name[0]
                                                      : '红',
                                                  style: TextStyle(
                                                    fontWeight: FontWeight.w800,
                                                    fontSize: 18,
                                                    color: p.primary,
                                                  ),
                                                ),
                                              ),
                                      ),
                                      const SizedBox(width: 16),
                                      Expanded(
                                        child: Column(
                                          crossAxisAlignment: CrossAxisAlignment.start,
                                          children: [
                                            Text(
                                              widget.profile?.name ?? '红果用户',
                                              style: TextStyle(
                                                fontSize: 15.5,
                                                fontWeight: FontWeight.w600,
                                                color: p.text,
                                              ),
                                            ),
                                            const SizedBox(height: 3),
                                            Text(
                                              widget.daysLeft >= 0
                                                  ? '登录有效期 ${widget.daysLeft} 天'
                                                  : '已登录',
                                              style: TextStyle(fontSize: 13, color: p.text3),
                                            ),
                                          ],
                                        ),
                                      ),
                                      OutlinedButton(
                                        style: OutlinedButton.styleFrom(
                                          foregroundColor: p.danger,
                                          side: BorderSide(color: p.sep),
                                          shape: const StadiumBorder(),
                                        ),
                                        onPressed: widget.onLogout,
                                        child: const Text('退出登录'),
                                      ),
                                    ],
                                  ),
                                )
                              : _SettingRow(
                                  label: '红果账号未登录',
                                  palette: p,
                                  trailing: FilledButton.icon(
                                    style: FilledButton.styleFrom(
                                      backgroundColor: p.primary,
                                      foregroundColor: p.primaryOn,
                                      shape: const StadiumBorder(),
                                    ),
                                    onPressed: widget.onOpenAuth,
                                    icon: const Icon(Icons.person_outline_rounded, size: 17),
                                    label: const Text('扫码登录'),
                                  ),
                                ),
                        ],
                      ),

                      // 2. 播放
                      _SettingGroup(
                        key: _tocKeys['playback'],
                        title: '播放',
                        palette: p,
                        rows: [
                          _SettingRow(
                            label: '默认清晰度',
                            palette: p,
                            trailing: SlidingSegmentedControl<String>(
                              value: store.defaultQuality,
                              options: const [
                                ('360p', '360P'),
                                ('540p', '540P'),
                                ('720p', '720P'),
                                ('1080p', '1080P'),
                              ],
                              onChanged: (v) async {
                                await store.setDefaultQuality(v);
                                setState(() {});
                              },
                            ),
                          ),
                          _SettingRow(
                            label: '自动连播',
                            palette: p,
                            trailing: WebSwitch(
                              value: store.autoNext,
                              onChanged: (v) async {
                                await store.setAutoNext(v);
                                setState(() {});
                              },
                            ),
                          ),
                          _SettingRow(
                            label: '换集保持倍速',
                            palette: p,
                            trailing: WebSwitch(
                              value: store.keepRate,
                              onChanged: (v) async {
                                await store.setKeepRate(v);
                                setState(() {});
                              },
                            ),
                          ),
                          _SettingRow(
                            label: '播放时展开选集',
                            palette: p,
                            trailing: WebSwitch(
                              value: store.dockOpen,
                              onChanged: (v) async {
                                await store.setDockOpen(v);
                                setState(() {});
                              },
                            ),
                          ),
                          _SettingRow(
                            label: 'GPU 硬件加速',
                            desc: '启用显卡硬解与 GPU 渲染，大幅降低 CPU 占用并提升播放流畅度',
                            palette: p,
                            trailing: WebSwitch(
                              value: store.gpuAccel,
                              onChanged: (v) async {
                                await store.setGpuAccel(v);
                                setState(() {});
                                widget.onToast('已${v ? "开启" : "关闭"} GPU 硬件加速（下次打开播放生效）');
                              },
                            ),
                          ),
                          _SettingRow(
                            label: '超分辨率画质增强',
                            desc: '基于 GPU 着色器实时锐化与细节重建，播控栏可随时切换',
                            palette: p,
                            trailing: SlidingSegmentedControl<String>(
                              value: store.superRes,
                              options: const [
                                ('off', '关闭'),
                                ('cas', '锐化'),
                                ('fsr', '超分'),
                              ],
                              onChanged: (v) async {
                                await store.setSuperRes(v);
                                setState(() {});
                              },
                            ),
                          ),
                        ],
                      ),

                      // 3. 弹幕
                      _SettingGroup(
                        key: _tocKeys['danmaku'],
                        title: '弹幕',
                        palette: p,
                        rows: [
                          _SettingRow(
                            label: '显示弹幕',
                            palette: p,
                            trailing: WebSwitch(
                              value: store.danmakuEnabled,
                              onChanged: (v) async {
                                await store.setDanmaku(enabled: v);
                                setState(() {});
                              },
                            ),
                          ),
                          _SettingRow(
                            label: '显示区域',
                            palette: p,
                            trailing: SlidingSegmentedControl<double>(
                              value: store.danmakuArea,
                              options: const [
                                (0.25, '1/4 屏'),
                                (0.5, '半屏'),
                                (0.75, '3/4 屏'),
                                (1.0, '全屏'),
                              ],
                              onChanged: (v) async {
                                await store.setDanmaku(area: v);
                                setState(() {});
                              },
                            ),
                          ),
                          _SettingRow(
                            label: '不透明度',
                            palette: p,
                            trailing: Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                SizedBox(
                                  width: 220,
                                  child: Slider(
                                    activeColor: p.text,
                                    inactiveColor: p.fill3,
                                    min: 0.2,
                                    max: 1.0,
                                    value: store.danmakuOpacity,
                                    onChanged: (v) async {
                                      await store.setDanmaku(opacity: v);
                                      setState(() {});
                                    },
                                  ),
                                ),
                                SizedBox(
                                  width: 48,
                                  child: Text(
                                    '${(store.danmakuOpacity * 100).round()}%',
                                    textAlign: TextAlign.right,
                                    style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: p.text2),
                                  ),
                                ),
                              ],
                            ),
                          ),
                          _SettingRow(
                            label: '速度',
                            palette: p,
                            trailing: Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                SizedBox(
                                  width: 220,
                                  child: Slider(
                                    activeColor: p.text,
                                    inactiveColor: p.fill3,
                                    min: 0.6,
                                    max: 1.8,
                                    value: store.danmakuSpeed,
                                    onChanged: (v) async {
                                      await store.setDanmaku(speed: v);
                                      setState(() {});
                                    },
                                  ),
                                ),
                                SizedBox(
                                  width: 48,
                                  child: Text(
                                    '${store.danmakuSpeed.toStringAsFixed(1)}x',
                                    textAlign: TextAlign.right,
                                    style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: p.text2),
                                  ),
                                ),
                              ],
                            ),
                          ),
                          _SettingRow(
                            label: '字号',
                            palette: p,
                            trailing: Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                SizedBox(
                                  width: 220,
                                  child: Slider(
                                    activeColor: p.text,
                                    inactiveColor: p.fill3,
                                    min: 14,
                                    max: 28,
                                    divisions: 7,
                                    value: store.danmakuFontSize.clamp(14, 28),
                                    onChanged: (v) async {
                                      await store.setDanmaku(fontSize: v);
                                      setState(() {});
                                    },
                                  ),
                                ),
                                SizedBox(
                                  width: 48,
                                  child: Text(
                                    '${store.danmakuFontSize.round()}px',
                                    textAlign: TextAlign.right,
                                    style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: p.text2),
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ],
                      ),

                      // 4. 下载
                      if (dl != null)
                        _SettingGroup(
                          key: _tocKeys['download'],
                          title: '下载',
                          palette: p,
                          rows: [
                            _SettingRow(
                              label: '默认下载目录',
                              desc: dl.downloadDir,
                              palette: p,
                              trailing: OutlinedButton(
                                style: OutlinedButton.styleFrom(
                                  foregroundColor: p.text,
                                  side: BorderSide(color: p.sep),
                                  shape: const StadiumBorder(),
                                ),
                                onPressed: _pickDir,
                                child: const Text('更改'),
                              ),
                            ),
                            _SettingRow(
                              label: '同时下载任务数 (${dl.maxConcurrent.clamp(1, 4)})',
                              palette: p,
                              trailing: SizedBox(
                                width: 220,
                                child: Slider(
                                  activeColor: p.text,
                                  inactiveColor: p.fill3,
                                  min: 1,
                                  max: 4,
                                  divisions: 3,
                                  value: dl.maxConcurrent.toDouble().clamp(1, 4),
                                  onChanged: (v) {
                                    _saveDownload(DownloadSettingsData(
                                      downloadDir: dl.downloadDir,
                                      maxConcurrent: v.round(),
                                      threadsPerTask: dl.threadsPerTask,
                                      speedLimitKbps: dl.speedLimitKbps,
                                      preferredQuality: dl.preferredQuality,
                                      autoRetryCount: dl.autoRetryCount,
                                      createSeriesSubdir: dl.createSeriesSubdir,
                                      notifyOnComplete: dl.notifyOnComplete,
                                      autoResumeOnStart: dl.autoResumeOnStart,
                                    ));
                                  },
                                ),
                              ),
                            ),
                            _SettingRow(
                              label: '单任务分片线程 (${dl.threadsPerTask.clamp(1, 8)})',
                              desc: '多线程 Range 分片并发下载，支持断点续传',
                              palette: p,
                              trailing: SizedBox(
                                width: 220,
                                child: Slider(
                                  activeColor: p.text,
                                  inactiveColor: p.fill3,
                                  min: 1,
                                  max: 8,
                                  divisions: 7,
                                  value: dl.threadsPerTask.toDouble().clamp(1, 8),
                                  onChanged: (v) {
                                    _saveDownload(DownloadSettingsData(
                                      downloadDir: dl.downloadDir,
                                      maxConcurrent: dl.maxConcurrent,
                                      threadsPerTask: v.round(),
                                      speedLimitKbps: dl.speedLimitKbps,
                                      preferredQuality: dl.preferredQuality,
                                      autoRetryCount: dl.autoRetryCount,
                                      createSeriesSubdir: dl.createSeriesSubdir,
                                      notifyOnComplete: dl.notifyOnComplete,
                                      autoResumeOnStart: dl.autoResumeOnStart,
                                    ));
                                  },
                                ),
                              ),
                            ),
                            _SettingRow(
                              label: '限速 (KB/s，0 为不限速)',
                              palette: p,
                              trailing: Row(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  SizedBox(
                                    width: 96,
                                    height: 36,
                                    child: TextField(
                                      controller: _speedCtrl,
                                      keyboardType: TextInputType.number,
                                      style: TextStyle(fontSize: 13, color: p.text),
                                      decoration: InputDecoration(
                                        filled: true,
                                        fillColor: p.fill2,
                                        contentPadding: const EdgeInsets.symmetric(horizontal: 10),
                                        border: OutlineInputBorder(
                                          borderRadius: BorderRadius.circular(8),
                                          borderSide: BorderSide(color: p.sep),
                                        ),
                                      ),
                                    ),
                                  ),
                                  const SizedBox(width: 8),
                                  OutlinedButton(
                                    style: OutlinedButton.styleFrom(
                                      foregroundColor: p.text,
                                      side: BorderSide(color: p.sep),
                                      shape: const StadiumBorder(),
                                    ),
                                    onPressed: () {
                                      final val = int.tryParse(_speedCtrl.text.trim()) ?? 0;
                                      _saveDownload(DownloadSettingsData(
                                        downloadDir: dl.downloadDir,
                                        maxConcurrent: dl.maxConcurrent,
                                        threadsPerTask: dl.threadsPerTask,
                                        speedLimitKbps: val < 0 ? 0 : val,
                                        preferredQuality: dl.preferredQuality,
                                        autoRetryCount: dl.autoRetryCount,
                                        createSeriesSubdir: dl.createSeriesSubdir,
                                        notifyOnComplete: dl.notifyOnComplete,
                                        autoResumeOnStart: dl.autoResumeOnStart,
                                      ));
                                    },
                                    child: const Text('保存'),
                                  ),
                                ],
                              ),
                            ),
                            _SettingRow(
                              label: '启动时自动断点续传',
                              palette: p,
                              trailing: WebSwitch(
                                value: dl.autoResumeOnStart,
                                onChanged: (v) {
                                  _saveDownload(DownloadSettingsData(
                                    downloadDir: dl.downloadDir,
                                    maxConcurrent: dl.maxConcurrent,
                                    threadsPerTask: dl.threadsPerTask,
                                    speedLimitKbps: dl.speedLimitKbps,
                                    preferredQuality: dl.preferredQuality,
                                    autoRetryCount: dl.autoRetryCount,
                                    createSeriesSubdir: dl.createSeriesSubdir,
                                    notifyOnComplete: dl.notifyOnComplete,
                                    autoResumeOnStart: v,
                                  ));
                                },
                              ),
                            ),
                          ],
                        ),

                      // 5. 外观
                      _SettingGroup(
                        key: _tocKeys['appearance'],
                        title: '外观',
                        palette: p,
                        rows: [
                          _SettingRow(
                            label: '主题',
                            palette: p,
                            trailing: SlidingSegmentedControl<String>(
                              value: store.themePref,
                              options: const [
                                ('auto', '跟随系统'),
                                ('light', '浅色'),
                                ('dark', '深色'),
                              ],
                              onChanged: (v) async {
                                await store.setTheme(v);
                                setState(() {});
                              },
                            ),
                          ),
                        ],
                      ),

                      // 7. 快捷键 (.skeys 2-col hairline table matching app.js line 1170)
                      _SettingGroup(
                        key: _tocKeys['shortcuts'],
                        title: '快捷键',
                        palette: p,
                        rows: [
                          LayoutBuilder(
                            builder: (_, __) {
                              const pairs = [
                                ('/', '搜索'),
                                ('空格 / K', '播放 / 暂停'),
                                ('← →', '后退 / 前进 10 秒'),
                                ('↑ ↓', '上一集 / 下一集'),
                                ('滚轮', '切换剧集'),
                                ('F', '全屏'),
                                ('M', '静音'),
                                ('D', '弹幕开关'),
                                ('T', '选集面板'),
                                ('Esc', '退出播放'),
                              ];
                              return Column(
                                children: [
                                  for (int row = 0; row < pairs.length; row += 2) ...[
                                    if (row > 0) Divider(height: 1, thickness: 1, color: p.sep),
                                    IntrinsicHeight(
                                      child: Row(
                                        children: [
                                          Expanded(
                                            child: _ShortcutCell(
                                              label: pairs[row].$2,
                                              shortcut: pairs[row].$1,
                                              palette: p,
                                            ),
                                          ),
                                          VerticalDivider(width: 1, thickness: 1, color: p.sep),
                                          Expanded(
                                            child: row + 1 < pairs.length
                                                ? _ShortcutCell(
                                                    label: pairs[row + 1].$2,
                                                    shortcut: pairs[row + 1].$1,
                                                    palette: p,
                                                  )
                                                : const SizedBox.shrink(),
                                          ),
                                        ],
                                      ),
                                    ),
                                  ],
                                ],
                              );
                            },
                          ),
                        ],
                      ),

                      // 7. 关于（与 TV 版展示范围一致：检查更新、版本、播放模块、开源地址，不暴露内部接口）
                      _SettingGroup(
                        key: _tocKeys['about'],
                        title: '关于',
                        palette: p,
                        rows: [
                          _SettingRow(
                            label: '检查更新',
                            palette: p,
                            trailing: FilledButton(
                              style: FilledButton.styleFrom(
                                backgroundColor: AppUpdateManager.instance.state == AppUpdateState.idle
                                    ? p.fill
                                    : p.primary,
                                foregroundColor: AppUpdateManager.instance.state == AppUpdateState.idle
                                    ? p.text2
                                    : p.primaryOn,
                                elevation: 0,
                                shape: const StadiumBorder(),
                              ),
                              onPressed: AppUpdateManager.instance.checking
                                  ? null
                                  : _handleCheckUpdate,
                              child: Text(
                                AppUpdateManager.instance.checking
                                    ? '检查中…'
                                    : AppUpdateManager.instance.statusLabel,
                              ),
                            ),
                          ),
                          _SettingRow(
                            label: '关于软件',
                            desc: '绿果PC · 独立桌面客户端（纯本地解析与下载引擎）',
                            palette: p,
                            trailing: Text(
                              'v${AppUpdateManager.currentVersion}',
                              style: TextStyle(
                                fontSize: 14,
                                fontWeight: FontWeight.w600,
                                color: p.text3,
                              ),
                            ),
                          ),
                          _SettingRow(
                            label: '播放模块',
                            palette: p,
                            trailing: Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                if (hot != null && hot.activeModuleRevision > 0) ...[
                                  OutlinedButton(
                                    style: OutlinedButton.styleFrom(
                                      foregroundColor: p.text2,
                                      side: BorderSide(color: p.sep),
                                      shape: const StadiumBorder(),
                                    ),
                                    onPressed: _rollbackAlgo,
                                    child: const Text('回滚内置'),
                                  ),
                                  const SizedBox(width: 10),
                                ],
                                Text(
                                  (hot != null && hot.activeModuleRevision > 0)
                                      ? 'r${hot.activeModuleRevision}'
                                      : '内置',
                                  style: TextStyle(
                                    fontSize: 14,
                                    fontWeight: FontWeight.w600,
                                    color: p.text3,
                                  ),
                                ),
                              ],
                            ),
                          ),
                          _SettingRow(
                            label: '开源地址',
                            desc: 'github.com/Amor5207/lvguo-tv',
                            palette: p,
                            trailing: OutlinedButton(
                              style: OutlinedButton.styleFrom(
                                foregroundColor: p.text2,
                                side: BorderSide(color: p.sep),
                                shape: const StadiumBorder(),
                              ),
                              onPressed: () => AppUpdateManager.instance
                                  .openUrl(AppUpdateManager.githubRepoUrl),
                              child: const Text('访问 GitHub'),
                            ),
                          ),
                        ],
                      ),
                    ],
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

class _SettingGroup extends StatelessWidget {
  final String title;
  final LvguoPalette palette;
  final List<Widget> rows;

  const _SettingGroup({
    super.key,
    required this.title,
    required this.palette,
    required this.rows,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: 38),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.only(left: 4, bottom: 10),
            child: Text(
              title,
              style: TextStyle(
                fontSize: 13,
                fontWeight: FontWeight.w700,
                letterSpacing: 0.52,
                color: palette.text3,
              ),
            ),
          ),
          // .scard: border-top & border-bottom hairline ONLY (no background box!)
          Container(
            decoration: BoxDecoration(
              border: Border(
                top: BorderSide(color: palette.sep),
                bottom: BorderSide(color: palette.sep),
              ),
            ),
            child: Column(
              children: [
                for (int i = 0; i < rows.length; i++) ...[
                  if (i > 0) Divider(height: 1, thickness: 1, color: palette.sep),
                  rows[i],
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _SettingRow extends StatelessWidget {
  final String label;
  final String? desc;
  final LvguoPalette palette;
  final Widget trailing;

  const _SettingRow({
    required this.label,
    this.desc,
    required this.palette,
    required this.trailing,
  });

  @override
  Widget build(BuildContext context) {
    return ConstrainedBox(
      constraints: const BoxConstraints(minHeight: 64),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 13),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Text(
                    label,
                    style: TextStyle(
                      fontSize: 15.5,
                      fontWeight: FontWeight.w600,
                      color: palette.text,
                    ),
                  ),
                  if (desc != null && desc!.isNotEmpty) ...[
                    const SizedBox(height: 3),
                    Text(
                      desc!,
                      style: TextStyle(fontSize: 13, color: palette.text3),
                    ),
                  ],
                ],
              ),
            ),
            const SizedBox(width: 20),
            trailing,
          ],
        ),
      ),
    );
  }
}

class _ShortcutCell extends StatelessWidget {
  final String label;
  final String shortcut;
  final LvguoPalette palette;

  const _ShortcutCell({
    required this.label,
    required this.shortcut,
    required this.palette,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 22, vertical: 12),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(
            label,
            style: TextStyle(fontSize: 14.5, color: palette.text2),
          ),
          Container(
            constraints: const BoxConstraints(minWidth: 30),
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 3),
            decoration: BoxDecoration(
              color: palette.fill,
              borderRadius: BorderRadius.circular(8),
            ),
            child: Text(
              shortcut,
              textAlign: TextAlign.center,
              style: TextStyle(
                fontSize: 13,
                fontWeight: FontWeight.w700,
                color: palette.text2,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
