import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lvguo_desktop/core/app_preloader.dart';
import 'package:lvguo_desktop/core/rust_bridge.dart';
import 'package:lvguo_desktop/core/settings_store.dart';
import 'package:lvguo_desktop/models/models.dart';
import 'package:lvguo_desktop/widgets/emoji_text.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  test('formatBytes and formatPlays format correctly', () {
    expect(formatBytes(0), '0 B');
    expect(formatBytes(1024), '1.0 KB');
    expect(formatPlays(15000), '1.5万次播放');
  });

  test('SettingsStore defaults and updates GPU & SuperRes', () async {
    SharedPreferences.setMockInitialValues({});
    await SettingsStore.instance.load();
    expect(SettingsStore.instance.gpuAccel, isTrue);
    expect(SettingsStore.instance.superRes, 'off');

    await SettingsStore.instance.setGpuAccel(false);
    await SettingsStore.instance.setSuperRes('fsr');
    expect(SettingsStore.instance.gpuAccel, isFalse);
    expect(SettingsStore.instance.superRes, 'fsr');

    await SettingsStore.instance.setGpuAccel(true);
    await SettingsStore.instance.setSuperRes('off');
  });

  testWidgets('Bundled GLSL shaders load cleanly and ValueNotifier avoids parent rebuild',
      (WidgetTester tester) async {
    final cas = await rootBundle.loadString('assets/shaders/cas_sharpen.glsl');
    final fsr = await rootBundle.loadString('assets/shaders/fsr_superres.glsl');
    expect(cas, contains('//!HOOK OUTPUT'));
    expect(fsr, contains('//!HOOK OUTPUT'));

    int parentBuilds = 0;
    int childBuilds = 0;
    final pos = ValueNotifier<Duration>(Duration.zero);

    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (_) {
            parentBuilds++;
            return ValueListenableBuilder<Duration>(
              valueListenable: pos,
              builder: (_, val, __) {
                childBuilds++;
                return Text('${val.inSeconds}s');
              },
            );
          },
        ),
      ),
    );

    expect(parentBuilds, 1);
    expect(childBuilds, 1);

    for (var i = 1; i <= 10; i++) {
      pos.value = Duration(seconds: i);
      await tester.pump();
    }

    expect(parentBuilds, 1);
    expect(childBuilds, 11);
    pos.dispose();
  });

  test('EmojiText builds inline sticker WidgetSpans and preserves unknown tags', () {
    const style = TextStyle(fontSize: 16);
    final spans = buildEmojiSpans('姜姐[吃瓜]真棒[笑哭][不存在]', style);
    expect(spans.whereType<WidgetSpan>().length, 2);
    final textParts = spans.whereType<TextSpan>().map((s) => s.text).join();
    expect(textParts, '姜姐真棒[不存在]');
    expect(kLvguoEmojiMap.length, 53);
  });

  test('AppPreloader.startWarmup is safe no-op before RustBridge init', () {
    expect(RustBridge.instance.initialized, isFalse);
    AppPreloader.instance.startWarmup();
  });
}
