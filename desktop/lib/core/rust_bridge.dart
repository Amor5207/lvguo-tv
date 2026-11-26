import 'dart:async';
import 'dart:convert';
import 'dart:ffi' as ffi;
import 'dart:io';
import 'dart:isolate';

import 'package:ffi/ffi.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

typedef _InitNative = ffi.Pointer<Utf8> Function(ffi.Pointer<Utf8> dataDir);
typedef _InitDart = ffi.Pointer<Utf8> Function(ffi.Pointer<Utf8> dataDir);

typedef _ShutdownNative = ffi.Void Function();
typedef _ShutdownDart = void Function();

typedef _MediaBaseNative = ffi.Pointer<Utf8> Function();
typedef _MediaBaseDart = ffi.Pointer<Utf8> Function();

typedef _CallNative = ffi.Pointer<Utf8> Function(
  ffi.Pointer<Utf8> action,
  ffi.Pointer<Utf8> paramsJson,
);
typedef _CallDart = ffi.Pointer<Utf8> Function(
  ffi.Pointer<Utf8> action,
  ffi.Pointer<Utf8> paramsJson,
);

typedef _FreeNative = ffi.Void Function(ffi.Pointer<Utf8> ptr);
typedef _FreeDart = void Function(ffi.Pointer<Utf8> ptr);

/// Persistent background isolate entrypoint for non-blocking Rust FFI calls + off-UI JSON decoding.
void _ffiWorkerMain(List<Object> initArgs) {
  final replyPort = initArgs[0] as SendPort;
  final libPath = initArgs[1] as String;

  final lib = ffi.DynamicLibrary.open(libPath);
  final callFn = lib.lookupFunction<_CallNative, _CallDart>('lvguo_call');
  final freeFn = lib.lookupFunction<_FreeNative, _FreeDart>('lvguo_free_string');

  final cmdPort = ReceivePort();
  replyPort.send(cmdPort.sendPort);

  cmdPort.listen((msg) {
    if (msg is! List || msg.length < 3) return;
    final id = msg[0] as int;
    final action = msg[1] as String;
    final paramsJson = msg[2] as String;
    try {
      final actPtr = action.toNativeUtf8();
      final paramPtr = paramsJson.toNativeUtf8();
      final resPtr = callFn(actPtr, paramPtr);
      calloc.free(actPtr);
      calloc.free(paramPtr);
      final out = resPtr.toDartString();
      freeFn(resPtr);
      final decoded = jsonDecode(out) as Map<String, dynamic>;
      replyPort.send(<Object>[id, decoded]);
    } catch (e) {
      replyPort.send(<Object>[
        id,
        <String, dynamic>{'ok': false, 'error': e.toString()},
      ]);
    }
  });
}

class _FfiWorker {
  final Isolate isolate;
  final SendPort sendPort;
  final ReceivePort recvPort;

  _FfiWorker(this.isolate, this.sendPort, this.recvPort);

  void kill() {
    recvPort.close();
    isolate.kill(priority: Isolate.immediate);
  }
}

class RustException implements Exception {
  final String message;
  RustException(this.message);

  @override
  String toString() => message;
}

class RustBridge {
  RustBridge._();
  static final RustBridge instance = RustBridge._();

  static const int _poolSize = 2;

  String? _libPath;
  ffi.DynamicLibrary? _lib;
  String mediaBaseUrl = '';
  String dataDir = '';
  bool initialized = false;
  Future<void>? _initFuture;

  final List<_FfiWorker> _workers = [];
  final Map<int, Completer<Map<String, dynamic>>> _pending = {};
  int _nextReqId = 1;
  int _rrIndex = 0;

  static String resolveLibraryPath() {
    final exeDir = File(Platform.resolvedExecutable).parent.path;
    final libName = Platform.isWindows
        ? 'lvguo_core.dll'
        : Platform.isMacOS
            ? 'liblvguo_core.dylib'
            : 'liblvguo_core.so';

    final candidates = <String>[
      p.join(exeDir, libName),
      if (Platform.isLinux) p.normalize(p.join(exeDir, 'lib', libName)),
      if (Platform.isMacOS) p.normalize(p.join(exeDir, '..', 'Frameworks', libName)),
      p.normalize(p.join(Directory.current.path, 'rust', 'target', 'release', libName)),
      p.normalize(p.join(Directory.current.path, 'rust', 'target', 'debug', libName)),
      p.normalize(p.join(Directory.current.path, 'desktop', 'rust', 'target', 'release', libName)),
      p.normalize(p.join(Directory.current.path, 'desktop', 'rust', 'target', 'debug', libName)),
    ];

    for (final c in candidates) {
      if (File(c).existsSync()) {
        return c;
      }
    }
    return libName;
  }

  Future<void> init() async {
    if (initialized) return;
    if (_initFuture != null) return _initFuture;
    _initFuture = _doInit();
    try {
      await _initFuture;
    } finally {
      _initFuture = null;
    }
  }

  Future<void> _doInit() async {
    if (initialized) return;
    _libPath = resolveLibraryPath();
    _lib = ffi.DynamicLibrary.open(_libPath!);

    final supportDir = await getApplicationSupportDirectory();
    final appData = Directory(p.join(supportDir.path, 'LvguoTV'));
    if (!appData.existsSync()) {
      appData.createSync(recursive: true);
    }
    dataDir = appData.path;

    final initFn = _lib!.lookupFunction<_InitNative, _InitDart>('lvguo_init');
    final freeFn = _lib!.lookupFunction<_FreeNative, _FreeDart>('lvguo_free_string');

    final dirPtr = dataDir.toNativeUtf8();
    final resPtr = initFn(dirPtr);
    calloc.free(dirPtr);

    final raw = resPtr.toDartString();
    freeFn(resPtr);

    final decoded = jsonDecode(raw) as Map<String, dynamic>;
    if (decoded['ok'] != true) {
      throw RustException(decoded['error']?.toString() ?? '核心初始化失败');
    }
    final data = decoded['data'] as Map<String, dynamic>? ?? const {};
    mediaBaseUrl = data['mediaBaseUrl']?.toString() ?? '';

    await _spawnPool();
    initialized = true;
  }

  Future<void> _spawnPool() async {
    if (_workers.isNotEmpty || _libPath == null) return;
    final libPath = _libPath!;
    await Future.wait(List.generate(_poolSize, (_) async {
      final rp = ReceivePort();
      final ready = Completer<SendPort>();
      rp.listen((msg) {
        if (msg is SendPort) {
          if (!ready.isCompleted) ready.complete(msg);
          return;
        }
        if (msg is List && msg.length >= 2) {
          final id = msg[0] as int;
          final decoded = msg[1] as Map<String, dynamic>;
          final completer = _pending.remove(id);
          completer?.complete(decoded);
        }
      });
      final iso = await Isolate.spawn(_ffiWorkerMain, <Object>[rp.sendPort, libPath]);
      final sp = await ready.future;
      _workers.add(_FfiWorker(iso, sp, rp));
    }));
  }

  void shutdown() {
    for (final c in _pending.values) {
      if (!c.isCompleted) {
        c.complete(<String, dynamic>{'ok': false, 'error': '核心已关闭'});
      }
    }
    _pending.clear();
    for (final w in _workers) {
      w.kill();
    }
    _workers.clear();

    if (!initialized || _lib == null) return;
    final fn = _lib!.lookupFunction<_ShutdownNative, _ShutdownDart>('lvguo_shutdown');
    fn();
    initialized = false;
  }

  String refreshMediaBaseUrl() {
    if (!initialized || _lib == null) return mediaBaseUrl;
    final fn = _lib!.lookupFunction<_MediaBaseNative, _MediaBaseDart>('lvguo_media_base_url');
    final freeFn = _lib!.lookupFunction<_FreeNative, _FreeDart>('lvguo_free_string');
    final ptr = fn();
    final url = ptr.toDartString();
    freeFn(ptr);
    if (url.isNotEmpty) {
      mediaBaseUrl = url;
    }
    return mediaBaseUrl;
  }

  Future<dynamic> call(String action, [Map<String, dynamic>? params]) async {
    if (!initialized) {
      await init();
    }
    if (_workers.isEmpty) {
      await _spawnPool();
    }
    final paramsJson = jsonEncode(params ?? const {});
    final reqId = _nextReqId++;
    final completer = Completer<Map<String, dynamic>>();
    _pending[reqId] = completer;

    final worker = _workers[_rrIndex % _workers.length];
    _rrIndex = (_rrIndex + 1) % _workers.length;
    worker.sendPort.send(<Object>[reqId, action, paramsJson]);

    final decoded = await completer.future;
    if (decoded['ok'] != true) {
      throw RustException(decoded['error']?.toString() ?? '请求失败');
    }
    return decoded['data'];
  }

  Future<Map<String, dynamic>> callMap(
    String action, [
    Map<String, dynamic>? params,
  ]) async {
    final data = await call(action, params);
    if (data is Map<String, dynamic>) {
      return data;
    }
    if (data is Map) {
      return Map<String, dynamic>.from(data);
    }
    return const {};
  }
}
