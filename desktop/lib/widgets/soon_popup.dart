import 'package:flutter/material.dart';

import '../core/rust_bridge.dart';
import '../core/theme.dart';
import '../models/models.dart';

/// Matches `soonPopup(id)` in web/ui/pc/app.js:
/// Shows poster + release schedule + intro + Reserve/Reserved toggle for unreleased series/seasons.
Future<void> showSoonPopup(
  BuildContext context, {
  required String seriesId,
  ReservationItem? initialItem,
  required bool loggedIn,
  required VoidCallback onOpenAuth,
  required ValueChanged<String> onToast,
  ValueChanged<bool>? onReservedChanged,
  ValueChanged<String>? onOpenDetail,
}) {
  return showDialog<void>(
    context: context,
    barrierDismissible: true,
    barrierColor: Colors.black.withValues(alpha: 0.48),
    builder: (ctx) => _SoonPopupDialog(
      seriesId: seriesId,
      initialItem: initialItem,
      loggedIn: loggedIn,
      onOpenAuth: onOpenAuth,
      onToast: onToast,
      onReservedChanged: onReservedChanged,
      onOpenDetail: onOpenDetail,
    ),
  );
}

class _SoonPopupDialog extends StatefulWidget {
  final String seriesId;
  final ReservationItem? initialItem;
  final bool loggedIn;
  final VoidCallback onOpenAuth;
  final ValueChanged<String> onToast;
  final ValueChanged<bool>? onReservedChanged;
  final ValueChanged<String>? onOpenDetail;

  const _SoonPopupDialog({
    required this.seriesId,
    this.initialItem,
    required this.loggedIn,
    required this.onOpenAuth,
    required this.onToast,
    this.onReservedChanged,
    this.onOpenDetail,
  });

  @override
  State<_SoonPopupDialog> createState() => _SoonPopupDialogState();
}

class _SoonPopupDialogState extends State<_SoonPopupDialog> {
  bool _loading = true;
  bool _submitting = false;
  bool _reserved = false;
  String _title = '';
  String _cover = '';
  String _intro = '';
  String _whenText = '待上线';

  @override
  void initState() {
    super.initState();
    final init = widget.initialItem;
    if (init != null) {
      _title = init.title;
      _cover = init.cover;
      _intro = init.desc;
      _whenText = init.publishText.isNotEmpty ? init.publishText : '待上线';
      _reserved = true;
    }
    _fetchDetail();
  }

  Future<void> _fetchDetail() async {
    try {
      final res = await RustBridge.instance.callMap('detail', {'id': widget.seriesId});
      if (!mounted) return;
      final d = DetailData.fromJson(
        Map<String, dynamic>.from(res['detail'] as Map? ?? const {}),
      );
      if (d.episodes.isNotEmpty) {
        Navigator.of(context).pop();
        widget.onOpenDetail?.call(widget.seriesId);
        return;
      }
      String when = '';
      if (res['reservation'] is Map) {
        final rv = Map<String, dynamic>.from(res['reservation'] as Map);
        when = rv['publishText']?.toString() ?? '';
        if (when.isEmpty) {
          final ts = (rv['time'] as num?)?.toInt() ?? 0;
          if (ts > 0) {
            final dt = DateTime.fromMillisecondsSinceEpoch(ts * 1000);
            when = '${dt.month}月${dt.day}日 上线';
          }
        }
      }
      setState(() {
        _loading = false;
        if (d.title.isNotEmpty) _title = d.title;
        if (d.cover.isNotEmpty) _cover = d.cover;
        if (d.intro.isNotEmpty) _intro = d.intro;
        if (when.isNotEmpty) _whenText = when;
        if (res.containsKey('reserved')) {
          _reserved = res['reserved'] == true;
        }
      });
    } catch (e) {
      if (!mounted) return;
      if (_title.isEmpty) {
        Navigator.of(context).pop();
        widget.onToast('加载失败：$e');
      } else {
        setState(() => _loading = false);
      }
    }
  }

  Future<void> _toggleReserve() async {
    if (!widget.loggedIn) {
      Navigator.of(context).pop();
      widget.onOpenAuth();
      widget.onToast('请先登录后再预约');
      return;
    }
    final next = !_reserved;
    if (!next) {
      final p = LvguoPalette.of(context);
      final ok = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          backgroundColor: p.surface,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(18),
            side: BorderSide(color: p.sep),
          ),
          title: Text('取消预约？', style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700, color: p.text)),
          content: Text(
            '《${_title.isNotEmpty ? _title : '该剧集'}》上线后将不再提醒。',
            style: TextStyle(fontSize: 14, color: p.text2),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(ctx).pop(false),
              child: Text('再想想', style: TextStyle(color: p.text2)),
            ),
            FilledButton(
              style: FilledButton.styleFrom(
                backgroundColor: p.danger,
                foregroundColor: Colors.white,
                shape: const StadiumBorder(),
              ),
              onPressed: () => Navigator.of(ctx).pop(true),
              child: const Text('取消预约'),
            ),
          ],
        ),
      );
      if (ok != true || !mounted) return;
    }
    setState(() => _submitting = true);
    try {
      await RustBridge.instance.callMap('reservation.set', {
        'seriesId': widget.seriesId,
        'add': next,
      });
      if (!mounted) return;
      setState(() {
        _reserved = next;
        _submitting = false;
      });
      widget.onReservedChanged?.call(next);
      widget.onToast(next ? '已预约' : '已取消预约');
    } catch (e) {
      if (!mounted) return;
      setState(() => _submitting = false);
      widget.onToast('操作失败：$e');
    }
  }

  @override
  Widget build(BuildContext context) {
    final p = LvguoPalette.of(context);
    return Dialog(
      backgroundColor: p.surface,
      surfaceTintColor: Colors.transparent,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(22),
        side: BorderSide(color: p.sep),
      ),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 520),
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: 150,
                height: 200,
                decoration: BoxDecoration(
                  color: p.skeleton,
                  borderRadius: BorderRadius.circular(10),
                ),
                clipBehavior: Clip.antiAlias,
                child: _cover.isNotEmpty
                    ? Image.network(
                        _cover,
                        fit: BoxFit.cover,
                        cacheWidth: 360,
                        gaplessPlayback: true,
                        errorBuilder: (_, _, _) => Icon(Icons.movie_outlined, size: 36, color: p.text4),
                      )
                    : Icon(Icons.movie_outlined, size: 36, color: p.text4),
              ),
              const SizedBox(width: 22),
              Expanded(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 3),
                      decoration: BoxDecoration(
                        color: p.primarySoft,
                        borderRadius: BorderRadius.circular(999),
                      ),
                      child: Text(
                        _whenText,
                        style: TextStyle(
                          fontSize: 12,
                          fontWeight: FontWeight.w600,
                          color: p.primary,
                        ),
                      ),
                    ),
                    const SizedBox(height: 10),
                    if (_loading && _title.isEmpty) ...[
                      Container(
                        height: 24,
                        width: 200,
                        decoration: BoxDecoration(
                          color: p.skeleton,
                          borderRadius: BorderRadius.circular(6),
                        ),
                      ),
                      const SizedBox(height: 12),
                      Container(
                        height: 14,
                        width: 140,
                        decoration: BoxDecoration(
                          color: p.skeleton,
                          borderRadius: BorderRadius.circular(6),
                        ),
                      ),
                    ] else ...[
                      Text(
                        _title.isNotEmpty ? _title : '待上线剧集',
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: 20,
                          fontWeight: FontWeight.w700,
                          height: 1.25,
                          color: p.text,
                        ),
                      ),
                      if (_intro.isNotEmpty) ...[
                        const SizedBox(height: 10),
                        Text(
                          _intro,
                          maxLines: 4,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: 13.5,
                            height: 1.6,
                            color: p.text2,
                          ),
                        ),
                      ],
                    ],
                    const SizedBox(height: 20),
                    Row(
                      children: [
                        FilledButton.icon(
                          style: FilledButton.styleFrom(
                            backgroundColor: _reserved ? p.fill : p.primary,
                            foregroundColor: _reserved ? p.text : p.primaryOn,
                            shape: const StadiumBorder(),
                            minimumSize: const Size(0, 40),
                            padding: const EdgeInsets.symmetric(horizontal: 20),
                          ),
                          onPressed: _submitting ? null : _toggleReserve,
                          icon: Icon(
                            _reserved ? Icons.check_rounded : Icons.notifications_none_rounded,
                            size: 18,
                            color: _reserved ? p.primary : p.primaryOn,
                          ),
                          label: Text(
                            _reserved ? '已预约' : '预约',
                            style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
                          ),
                        ),
                        const SizedBox(width: 10),
                        TextButton(
                          style: TextButton.styleFrom(
                            backgroundColor: p.fill,
                            foregroundColor: p.text2,
                            shape: const StadiumBorder(),
                            minimumSize: const Size(0, 40),
                            padding: const EdgeInsets.symmetric(horizontal: 18),
                          ),
                          onPressed: () => Navigator.of(context).pop(),
                          child: const Text('关闭', style: TextStyle(fontSize: 14)),
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
    );
  }
}
