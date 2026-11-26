import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lvguo_desktop/core/theme.dart';
import 'package:lvguo_desktop/core/update_manager.dart';
import 'package:lvguo_desktop/models/models.dart';
import 'package:lvguo_desktop/widgets/cinema_player.dart';
import 'package:lvguo_desktop/widgets/series_card.dart';
import 'package:lvguo_desktop/widgets/top_nav.dart';
import 'package:lvguo_desktop/widgets/update_dialog.dart';
import 'package:lvguo_desktop/widgets/web_primitives.dart';

void main() {
  group('Player overlay state machine during episode switch', () {
    test('UI hidden before switch stays hidden with zero play button and zero grace spinner', () {
      final fastHidden = computePlayerOverlayFlags(
        uiHidden: true,
        playing: false,
        buffering: true,
        switchingEpisode: true,
        loadingStream: false,
        graceTimerActive: true,
        menuOpen: false,
        streamError: '',
      );
      expect(fastHidden.showUi, isFalse);
      expect(fastHidden.showCenterPlay, isFalse);
      expect(fastHidden.showLoadingSpinner, isFalse);
      expect(fastHidden.hideCursor, isTrue);

      final slowHidden = computePlayerOverlayFlags(
        uiHidden: true,
        playing: false,
        buffering: true,
        switchingEpisode: true,
        loadingStream: true,
        graceTimerActive: false,
        menuOpen: false,
        streamError: '',
      );
      expect(slowHidden.showUi, isFalse);
      expect(slowHidden.showCenterPlay, isFalse);
      expect(slowHidden.showLoadingSpinner, isTrue);
    });

    test('UI visible before switch stays visible during switch and still hides center play', () {
      final visSwitch = computePlayerOverlayFlags(
        uiHidden: false,
        playing: false,
        buffering: false,
        switchingEpisode: true,
        loadingStream: false,
        graceTimerActive: true,
        menuOpen: false,
        streamError: '',
      );
      expect(visSwitch.showUi, isTrue);
      expect(visSwitch.showCenterPlay, isFalse);
      expect(visSwitch.showLoadingSpinner, isFalse);
      expect(visSwitch.hideCursor, isFalse);
    });

    test('Manual pause shows UI and center play button cleanly', () {
      final paused = computePlayerOverlayFlags(
        uiHidden: false,
        playing: false,
        buffering: false,
        switchingEpisode: false,
        loadingStream: false,
        graceTimerActive: false,
        menuOpen: false,
        streamError: '',
      );
      expect(paused.showUi, isTrue);
      expect(paused.showCenterPlay, isTrue);
      expect(paused.showLoadingSpinner, isFalse);
    });
  });

  group('Desktop UI layout overflow & styling audit', () {
    testWidgets('TopNav, SeriesCard, primitives, and UpdateDialog render with zero overflow at min 1024x680',
        (WidgetTester tester) async {
      await tester.binding.setSurfaceSize(const Size(1024, 680));
      addTearDown(() => tester.binding.setSurfaceSize(null));

      final sampleItem = SeriesItem(
        id: '123',
        title: '这是一个超长的测试短剧标题用来验证卡片与导航栏在窄屏下不会发生布局溢出',
        cover: '',
        episodes: 88,
        plays: 123456,
        intro: '测试简介内容',
        category: '都市,逆袭',
        firstVid: 'v1',
        rank: 1,
      );

      await tester.pumpWidget(
        MaterialApp(
          theme: LvguoTheme.buildDark(),
          home: Scaffold(
            body: Column(
              children: [
                TopNav(
                  activeTab: AppTab.home,
                  solidNav: true,
                  searchOpen: false,
                  loggedIn: true,
                  profile: const UserProfile(
                    uid: '1',
                    name: '测试账号用户',
                    avatar: '',
                    cover: '',
                    signature: '签名',
                    vip: true,
                    watchMs: 60000,
                    activeDays: 10,
                  ),
                  daysLeft: 30,
                  activeDownloadCount: 12,
                  onSelectTab: (_) {},
                  onToggleSearch: () {},
                  onOpenDownloads: () {},
                  onOpenSettings: () {},
                  onOpenAuth: () {},
                  onLogout: () {},
                ),
                Padding(
                  padding: const EdgeInsets.all(16),
                  child: Row(
                    children: [
                      UnderlineTabBar(
                        labels: const ['正在下载 (12)', '已完成 (120)'],
                        activeIndex: 0,
                        onChanged: (_) {},
                      ),
                      const SizedBox(width: 20),
                      SlidingSegmentedControl<String>(
                        options: const [
                          ('off', '关闭'),
                          ('cas', '锐化'),
                          ('fsr', '超分'),
                        ],
                        value: 'fsr',
                        onChanged: (_) {},
                      ),
                    ],
                  ),
                ),
                SizedBox(
                  width: 190,
                  height: 190 * (4 / 3) + 58,
                  child: SeriesCard(
                    item: sampleItem,
                    rankBadge: 1,
                    subtitle: '看到第 18 集 / 共 88 集',
                    progressFraction: 0.45,
                    onTap: () {},
                  ),
                ),
              ],
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);

      final ctx = tester.element(find.byType(TopNav));
      showUpdateFoundDialog(
        ctx,
        info: const AppUpdateInfo(
          currentVersion: '2.0.11',
          latestVersion: '2.0.12',
          notes: '1. 优化播放器切换剧集表现\n2. 新增启动预载与单播放窗口机制\n3. 修复若干布局与稳定性问题',
          releaseUrl: 'https://github.com/Amor5207/lvguo-tv/releases',
          downloadUrl: 'https://github.com/Amor5207/lvguo-tv/releases',
          assetName: 'LvguoPC-Setup-2.0.12-win-x64.exe',
          assetSize: 26687822,
        ),
        onIgnore: () async {},
        onOpenReleasePage: () async {},
        onStartDownload: () async {},
      );
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    });

    testWidgets('HomeSkeleton and DetailSkeleton render cleanly without overflow at 1024x680 and 1280x800',
        (WidgetTester tester) async {
      for (final sz in const [Size(1024, 680), Size(1280, 800)]) {
        await tester.binding.setSurfaceSize(sz);
        final gutter = sz.width <= 1366 ? 40.0 : 56.0;
        final cols = 5;
        final cardW = ((sz.width - gutter * 2 - (cols - 1) * 20.0) / cols).clamp(120.0, 240.0);
        final heroH = (sz.height * 0.62).clamp(480.0, 560.0);

        await tester.pumpWidget(
          MaterialApp(
            theme: LvguoTheme.buildDark(),
            home: Scaffold(
              body: HomeSkeleton(
                gutter: gutter,
                heroHeight: heroH,
                cols: cols,
                cardW: cardW,
              ),
            ),
          ),
        );
        await tester.pump(const Duration(milliseconds: 100));
        expect(tester.takeException(), isNull);

        await tester.pumpWidget(
          MaterialApp(
            theme: LvguoTheme.buildDark(),
            home: Scaffold(
              body: DetailSkeleton(gutter: gutter),
            ),
          ),
        );
        await tester.pump(const Duration(milliseconds: 100));
        expect(tester.takeException(), isNull);
      }
      await tester.binding.setSurfaceSize(null);
    });
  });
}
