import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../core/rust_bridge.dart';
import '../core/theme.dart';

/// Top-right anchored floating login card matching `LV.mountLogin` in tokens.css + core.js,
/// plus seamless switch to Phone SMS login (including MFA verification).
class AuthPopover extends StatefulWidget {
  final VoidCallback onSuccess;
  final VoidCallback onClose;

  const AuthPopover({
    super.key,
    required this.onSuccess,
    required this.onClose,
  });

  @override
  State<AuthPopover> createState() => _AuthPopoverState();
}

class _AuthPopoverState extends State<AuthPopover> {
  bool _phoneMode = false;

  // QR State (1:1 with core.js mountLogin)
  String _qrToken = '';
  Uint8List? _qrBytes;
  bool _qrLoading = true;
  bool _qrFailed = false;
  String _hint = '';
  bool _hintOk = false;
  int _expiresAtMs = 0;
  int _secondsLeft = 0;
  Timer? _pollTimer;
  Timer? _tickTimer;

  // Phone SMS State
  final _phoneCtrl = TextEditingController();
  final _codeCtrl = TextEditingController();
  String _phoneSessionId = '';
  int _smsCountdown = 0;
  Timer? _smsTimer;
  String _phoneError = '';
  bool _phoneBusy = false;
  String? _mfaNumber;
  String? _mfaContent;

  @override
  void initState() {
    super.initState();
    _startQr();
  }

  @override
  void dispose() {
    _pollTimer?.cancel();
    _tickTimer?.cancel();
    _smsTimer?.cancel();
    _phoneCtrl.dispose();
    _codeCtrl.dispose();
    super.dispose();
  }

  Future<void> _startQr() async {
    _pollTimer?.cancel();
    _tickTimer?.cancel();
    setState(() {
      _qrLoading = true;
      _qrFailed = false;
      _qrBytes = null;
      _hint = '';
      _hintOk = false;
      _secondsLeft = 0;
    });

    try {
      final res = await RustBridge.instance.callMap('login.qr.start');
      final rawDataUrl = res['qr']?.toString() ?? '';
      final b64 = rawDataUrl.contains(',')
          ? rawDataUrl.split(',').last
          : rawDataUrl;
      final bytes = base64Decode(b64);
      final exp = (res['expiresAt'] as num?)?.toInt() ?? 0;
      if (!mounted) return;
      setState(() {
        _qrToken = res['token']?.toString() ?? '';
        _qrBytes = bytes;
        _expiresAtMs = exp;
        _qrLoading = false;
      });
      _tickTimer = Timer.periodic(const Duration(milliseconds: 500), (_) {
        if (!mounted || _expiresAtMs <= 0) return;
        final left = ((_expiresAtMs - DateTime.now().millisecondsSinceEpoch) / 1000).round();
        if (left != _secondsLeft) {
          setState(() => _secondsLeft = left);
        }
        if (left <= -1) {
          _startQr();
        }
      });
      _pollTimer = Timer.periodic(const Duration(milliseconds: 1600), (_) => _pollQr());
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _qrLoading = false;
        _qrFailed = true;
      });
    }
  }

  Future<void> _pollQr() async {
    if (_qrToken.isEmpty || _qrFailed || !mounted) return;
    try {
      final res = await RustBridge.instance.callMap('login.qr.poll', {'token': _qrToken});
      if (!mounted) return;
      final state = res['state']?.toString() ?? 'waiting';
      if (state == 'confirmed') {
        _pollTimer?.cancel();
        _tickTimer?.cancel();
        setState(() {
          _hintOk = true;
          _hint = '登录成功';
        });
        await Future<void>.delayed(const Duration(milliseconds: 350));
        widget.onSuccess();
      } else if (state == 'scanned') {
        setState(() {
          _hintOk = true;
          _hint = '已扫码，请在手机上确认';
        });
      } else if (state == 'canceled') {
        setState(() {
          _hintOk = false;
          _hint = '已取消，正在刷新二维码';
        });
        _startQr();
      } else if (state == 'expired') {
        _startQr();
      } else if (_hint.isNotEmpty) {
        setState(() => _hint = '');
      }
    } catch (_) {}
  }

  Future<void> _sendSms() async {
    final phone = _phoneCtrl.text.trim();
    if (phone.isEmpty) {
      setState(() => _phoneError = '请输入 11 位手机号');
      return;
    }
    setState(() {
      _phoneBusy = true;
      _phoneError = '';
      _mfaNumber = null;
      _mfaContent = null;
    });
    try {
      final res = await RustBridge.instance.callMap('login.phone.send', {
        'phone': phone,
        if (_phoneSessionId.isNotEmpty) 'sessionId': _phoneSessionId,
      });
      if (!mounted) return;
      _phoneSessionId = res['sessionId']?.toString() ?? _phoneSessionId;
      _smsTimer?.cancel();
      setState(() {
        _smsCountdown = 60;
        _phoneBusy = false;
      });
      _smsTimer = Timer.periodic(const Duration(seconds: 1), (t) {
        if (!mounted || _smsCountdown <= 1) {
          t.cancel();
          if (mounted) setState(() => _smsCountdown = 0);
        } else {
          setState(() => _smsCountdown--);
        }
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _phoneBusy = false;
        _phoneError = e.toString();
      });
    }
  }

  Future<void> _submitSmsLogin() async {
    if (_phoneSessionId.isEmpty) {
      setState(() => _phoneError = '请先获取验证码');
      return;
    }
    final code = _codeCtrl.text.trim();
    if (code.length < 4) {
      setState(() => _phoneError = '请输入短信里的验证码');
      return;
    }
    setState(() {
      _phoneBusy = true;
      _phoneError = '';
    });
    try {
      final res = await RustBridge.instance.callMap('login.phone.login', {
        'sessionId': _phoneSessionId,
        'code': code,
      });
      if (!mounted) return;
      final status = res['status']?.toString() ?? '';
      if (status == 'ok') {
        widget.onSuccess();
      } else if (status == 'mfa') {
        setState(() {
          _phoneBusy = false;
          _mfaNumber = res['number']?.toString();
          _mfaContent = res['content']?.toString();
        });
      } else {
        setState(() {
          _phoneBusy = false;
          _phoneError = '登录状态：$status';
        });
      }
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _phoneBusy = false;
        _phoneError = e.toString();
      });
    }
  }

  Future<void> _verifyMfa() async {
    setState(() {
      _phoneBusy = true;
      _phoneError = '';
    });
    try {
      final res = await RustBridge.instance.callMap('login.phone.verify', {
        'sessionId': _phoneSessionId,
      });
      if (!mounted) return;
      final status = res['status']?.toString() ?? '';
      if (status == 'ok') {
        widget.onSuccess();
      } else if (status == 'pending') {
        setState(() {
          _phoneBusy = false;
          _phoneError = '尚未收到验证短信，请发送几秒后再试';
        });
      } else {
        setState(() {
          _phoneBusy = false;
          _phoneError = '验证状态：$status';
        });
      }
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _phoneBusy = false;
        _phoneError = e.toString();
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final p = LvguoPalette.of(context);
    final gutter = MediaQuery.of(context).size.width <= 1366 ? 40.0 : 56.0;

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
          top: 58,
          child: Material(
            color: Colors.transparent,
            child: Container(
              width: 332,
              padding: const EdgeInsets.fromLTRB(24, 24, 24, 20),
              decoration: BoxDecoration(
                color: p.surface,
                borderRadius: BorderRadius.circular(28),
                border: Border.all(color: p.sep),
                boxShadow: [
                  BoxShadow(
                    color: Colors.black.withValues(alpha: 0.18),
                    blurRadius: 48,
                    offset: const Offset(0, 18),
                  ),
                ],
              ),
              child: Stack(
                children: [
                  Positioned(
                    right: 0,
                    top: 0,
                    child: InkWell(
                      onTap: widget.onClose,
                      borderRadius: BorderRadius.circular(99),
                      child: Container(
                        width: 30,
                        height: 30,
                        decoration: BoxDecoration(color: p.fill, shape: BoxShape.circle),
                        child: Icon(Icons.close, size: 15, color: p.text2),
                      ),
                    ),
                  ),
                  Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      ClipRRect(
                        borderRadius: BorderRadius.circular(13),
                        child: Image.asset('assets/logo.png', width: 48, height: 48),
                      ),
                      const SizedBox(height: 8),
                      Text(
                        '绿果PC',
                        style: TextStyle(
                          fontSize: 18,
                          fontWeight: FontWeight.w700,
                          letterSpacing: 0.3,
                          color: p.text,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        _phoneMode
                            ? '使用手机号短信验证码登录\n收藏与观看进度自动同步'
                            : '用手机红果 App 扫码登录\n收藏与观看进度自动同步',
                        textAlign: TextAlign.center,
                        style: TextStyle(fontSize: 13, height: 1.5, color: p.text3),
                      ),
                      if (!_phoneMode) ...[
                        Container(
                          width: 196,
                          height: 196,
                          margin: const EdgeInsets.only(top: 18, bottom: 12),
                          padding: const EdgeInsets.all(12),
                          decoration: BoxDecoration(
                            color: Colors.white,
                            borderRadius: BorderRadius.circular(22),
                            border: Border.all(color: p.sep),
                          ),
                          child: Stack(
                            fit: StackFit.expand,
                            children: [
                              if (_qrLoading)
                                Center(
                                  child: CircularProgressIndicator(
                                    color: p.primary,
                                    strokeWidth: 2.5,
                                  ),
                                )
                              else if (_qrBytes != null)
                                Image.memory(_qrBytes!, fit: BoxFit.contain)
                              else
                                const SizedBox.shrink(),
                              if (_qrFailed)
                                GestureDetector(
                                  onTap: _startQr,
                                  child: Container(
                                    decoration: BoxDecoration(
                                      color: Colors.white.withValues(alpha: 0.92),
                                      borderRadius: BorderRadius.circular(14),
                                    ),
                                    alignment: Alignment.center,
                                    child: const Text(
                                      '二维码加载失败，点击刷新重试',
                                      textAlign: TextAlign.center,
                                      style: TextStyle(
                                        color: Color(0xFF222222),
                                        fontWeight: FontWeight.w600,
                                        fontSize: 13,
                                      ),
                                    ),
                                  ),
                                ),
                            ],
                          ),
                        ),
                        SizedBox(
                          height: 22,
                          child: Text(
                            _hint,
                            style: TextStyle(
                              fontWeight: FontWeight.w600,
                              fontSize: 13.5,
                              color: _hintOk ? p.primary : p.text,
                            ),
                          ),
                        ),
                        const SizedBox(height: 6),
                        Row(
                          mainAxisAlignment: MainAxisAlignment.spaceBetween,
                          children: [
                            Text(
                              _qrLoading
                                  ? '正在生成二维码'
                                  : (_secondsLeft > 0 ? '二维码 $_secondsLeft 秒后失效' : ''),
                              style: TextStyle(fontSize: 12.5, color: p.text3),
                            ),
                            InkWell(
                              onTap: _qrLoading ? null : _startQr,
                              borderRadius: BorderRadius.circular(99),
                              child: Container(
                                height: 28,
                                padding: const EdgeInsets.symmetric(horizontal: 12),
                                decoration: BoxDecoration(
                                  color: p.fill2,
                                  borderRadius: BorderRadius.circular(99),
                                  border: Border.all(color: p.sep),
                                ),
                                child: Row(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    Icon(Icons.refresh, size: 13, color: p.text2),
                                    const SizedBox(width: 5),
                                    Text(
                                      '刷新二维码',
                                      style: TextStyle(
                                        fontSize: 12.5,
                                        fontWeight: FontWeight.w500,
                                        color: p.text2,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ),
                          ],
                        ),
                      ] else ...[
                        const SizedBox(height: 16),
                        TextField(
                          controller: _phoneCtrl,
                          keyboardType: TextInputType.phone,
                          style: TextStyle(fontSize: 14, color: p.text),
                          decoration: InputDecoration(
                            hintText: '输入 11 位手机号',
                            hintStyle: TextStyle(color: p.text4, fontSize: 13.5),
                            isDense: true,
                            contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 11),
                            filled: true,
                            fillColor: p.surface2,
                            border: OutlineInputBorder(
                              borderRadius: BorderRadius.circular(10),
                              borderSide: BorderSide(color: p.sep),
                            ),
                            enabledBorder: OutlineInputBorder(
                              borderRadius: BorderRadius.circular(10),
                              borderSide: BorderSide(color: p.sep),
                            ),
                            focusedBorder: OutlineInputBorder(
                              borderRadius: BorderRadius.circular(10),
                              borderSide: BorderSide(color: p.primary, width: 1.5),
                            ),
                          ),
                        ),
                        const SizedBox(height: 10),
                        Row(
                          children: [
                            Expanded(
                              child: TextField(
                                controller: _codeCtrl,
                                keyboardType: TextInputType.number,
                                style: TextStyle(fontSize: 14, color: p.text),
                                decoration: InputDecoration(
                                  hintText: '短信验证码',
                                  hintStyle: TextStyle(color: p.text4, fontSize: 13.5),
                                  isDense: true,
                                  contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 11),
                                  filled: true,
                                  fillColor: p.surface2,
                                  border: OutlineInputBorder(
                                    borderRadius: BorderRadius.circular(10),
                                    borderSide: BorderSide(color: p.sep),
                                  ),
                                  enabledBorder: OutlineInputBorder(
                                    borderRadius: BorderRadius.circular(10),
                                    borderSide: BorderSide(color: p.sep),
                                  ),
                                  focusedBorder: OutlineInputBorder(
                                    borderRadius: BorderRadius.circular(10),
                                    borderSide: BorderSide(color: p.primary, width: 1.5),
                                  ),
                                ),
                              ),
                            ),
                            const SizedBox(width: 8),
                            SizedBox(
                              height: 40,
                              child: OutlinedButton(
                                style: OutlinedButton.styleFrom(
                                  foregroundColor: p.text2,
                                  side: BorderSide(color: p.sep),
                                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                                  padding: const EdgeInsets.symmetric(horizontal: 14),
                                ),
                                onPressed: (_phoneBusy || _smsCountdown > 0) ? null : _sendSms,
                                child: Text(
                                  _smsCountdown > 0 ? '${_smsCountdown}s' : '获取验证码',
                                  style: const TextStyle(fontSize: 13),
                                ),
                              ),
                            ),
                          ],
                        ),
                        if (_mfaNumber != null) ...[
                          const SizedBox(height: 10),
                          Container(
                            width: double.infinity,
                            padding: const EdgeInsets.all(10),
                            decoration: BoxDecoration(
                              color: p.surface2,
                              borderRadius: BorderRadius.circular(12),
                              border: Border.all(color: p.sep),
                            ),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text('发送验证短信（用登录手机号发送后点击验证）',
                                    style: TextStyle(fontSize: 12, color: p.text2, fontWeight: FontWeight.w600)),
                                const SizedBox(height: 6),
                                Row(
                                  children: [
                                    Expanded(child: Text('收件号码：$_mfaNumber', style: const TextStyle(fontSize: 12.5))),
                                    TextButton(
                                      onPressed: () => Clipboard.setData(ClipboardData(text: _mfaNumber ?? '')),
                                      child: const Text('复制'),
                                    ),
                                  ],
                                ),
                                Row(
                                  children: [
                                    Expanded(child: Text('短信内容：$_mfaContent', style: const TextStyle(fontSize: 12.5))),
                                    TextButton(
                                      onPressed: () => Clipboard.setData(ClipboardData(text: _mfaContent ?? '')),
                                      child: const Text('复制'),
                                    ),
                                  ],
                                ),
                                const SizedBox(height: 4),
                                SizedBox(
                                  width: double.infinity,
                                  child: FilledButton(
                                    onPressed: _phoneBusy ? null : _verifyMfa,
                                    style: FilledButton.styleFrom(
                                      backgroundColor: p.primary,
                                      foregroundColor: p.primaryOn,
                                    ),
                                    child: const Text('我已发送'),
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ],
                        if (_phoneError.isNotEmpty) ...[
                          const SizedBox(height: 8),
                          Text(_phoneError, style: TextStyle(fontSize: 12, color: p.danger)),
                        ],
                        if (_mfaNumber == null) ...[
                          const SizedBox(height: 12),
                          SizedBox(
                            width: double.infinity,
                            height: 40,
                            child: FilledButton(
                              onPressed: _phoneBusy ? null : _submitSmsLogin,
                              style: FilledButton.styleFrom(
                                backgroundColor: p.primary,
                                foregroundColor: p.primaryOn,
                                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                              ),
                              child: Text(_phoneBusy ? '登录中...' : '登录'),
                            ),
                          ),
                        ],
                      ],
                      const SizedBox(height: 10),
                      Divider(color: p.sep, height: 14),
                      TextButton(
                        onPressed: () => setState(() {
                          _phoneMode = !_phoneMode;
                          if (!_phoneMode && _qrBytes == null) _startQr();
                        }),
                        child: Text(
                          _phoneMode ? '返回红果 App 扫码登录' : '使用手机号验证码登录',
                          style: TextStyle(fontSize: 12.5, color: p.primary),
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        ),
      ],
    );
  }
}
