import 'package:flutter/material.dart';

import '../core/theme.dart';
import '../models/models.dart';

enum AppTab { home, feed, rank, category, mine, downloads, settings, detail }

class TopNav extends StatelessWidget {
  final AppTab activeTab;
  final bool solidNav;
  final bool searchOpen;
  final bool loggedIn;
  final UserProfile? profile;
  final int daysLeft;
  final int activeDownloadCount;
  final ValueChanged<AppTab> onSelectTab;
  final VoidCallback onToggleSearch;
  final VoidCallback onOpenDownloads;
  final VoidCallback onOpenSettings;
  final VoidCallback onOpenAuth;
  final VoidCallback onLogout;

  const TopNav({
    super.key,
    required this.activeTab,
    required this.solidNav,
    required this.searchOpen,
    required this.loggedIn,
    required this.profile,
    this.daysLeft = -1,
    required this.activeDownloadCount,
    required this.onSelectTab,
    required this.onToggleSearch,
    required this.onOpenDownloads,
    required this.onOpenSettings,
    required this.onOpenAuth,
    required this.onLogout,
  });

  @override
  Widget build(BuildContext context) {
    final p = LvguoPalette.of(context);
    const navItems = [
      (AppTab.home, '首页'),
      (AppTab.feed, '刷剧'),
      (AppTab.rank, '榜单'),
      (AppTab.category, '分类'),
      (AppTab.mine, '我的'),
    ];
    final gutter = MediaQuery.of(context).size.width <= 1366 ? 40.0 : 56.0;

    return RepaintBoundary(
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 180),
        height: 64,
        padding: EdgeInsets.symmetric(horizontal: gutter),
        decoration: BoxDecoration(
          color: solidNav ? p.bg.withValues(alpha: 0.96) : Colors.transparent,
          border: Border(
            bottom: BorderSide(
              color: solidNav ? p.sep : Colors.transparent,
            ),
          ),
        ),
        child: Row(
          children: [
              // Left: Brand
              Expanded(
                child: Align(
                  alignment: Alignment.centerLeft,
                  child: InkWell(
                    onTap: () => onSelectTab(AppTab.home),
                    borderRadius: BorderRadius.circular(8),
                    child: Padding(
                      padding: const EdgeInsets.symmetric(vertical: 6, horizontal: 2),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          ClipRRect(
                            borderRadius: BorderRadius.circular(7),
                            child: Image.asset('assets/logo.png', width: 28, height: 28),
                          ),
                          const SizedBox(width: 10),
                          Text(
                            '绿果',
                            style: TextStyle(
                              fontSize: 17,
                              fontWeight: FontWeight.w700,
                              color: p.text,
                              letterSpacing: 0.2,
                            ),
                          ),
                          Text(
                            'PC',
                            style: TextStyle(
                              fontSize: 17,
                              fontWeight: FontWeight.w700,
                              color: p.primary,
                              letterSpacing: 0.2,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
              // Center: Plain text tabs matching #tabs (no pill box!)
              Row(
                mainAxisSize: MainAxisSize.min,
                children: navItems.map((item) {
                  final on = activeTab == item.$1;
                  return _NavTabItem(
                    label: item.$2,
                    selected: on,
                    onTap: () => onSelectTab(item.$1),
                  );
                }).toList(),
              ),
              // Right: Circular tools + account button
              Expanded(
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    _RoundToolBtn(
                      tooltip: '搜索 ( / )',
                      icon: Icons.search_rounded,
                      active: searchOpen,
                      onTap: onToggleSearch,
                    ),
                    const SizedBox(width: 4),
                    Stack(
                      clipBehavior: Clip.none,
                      children: [
                        _RoundToolBtn(
                          tooltip: '下载管理',
                          icon: Icons.download_rounded,
                          active: activeTab == AppTab.downloads,
                          onTap: onOpenDownloads,
                        ),
                        if (activeDownloadCount > 0)
                          Positioned(
                            right: 1,
                            top: 1,
                            child: Container(
                              padding: const EdgeInsets.symmetric(horizontal: 4.5, vertical: 1),
                              decoration: BoxDecoration(
                                color: p.primary,
                                borderRadius: BorderRadius.circular(99),
                              ),
                              child: Text(
                                '$activeDownloadCount',
                                style: TextStyle(
                                  fontSize: 10,
                                  fontWeight: FontWeight.w700,
                                  color: p.primaryOn,
                                ),
                              ),
                            ),
                          ),
                      ],
                    ),
                    const SizedBox(width: 4),
                    _RoundToolBtn(
                      tooltip: '设置',
                      icon: Icons.settings_outlined,
                      active: activeTab == AppTab.settings,
                      onTap: onOpenSettings,
                    ),
                    const SizedBox(width: 8),
                    if (!loggedIn)
                      InkWell(
                        onTap: onOpenAuth,
                        borderRadius: BorderRadius.circular(99),
                        child: Container(
                          width: 32,
                          height: 32,
                          decoration: BoxDecoration(
                            color: p.fill,
                            shape: BoxShape.circle,
                          ),
                          child: Icon(Icons.person_outline, size: 17, color: p.text),
                        ),
                      )
                    else
                      PopupMenuButton<String>(
                        tooltip: profile?.name ?? '账号',
                        offset: const Offset(0, 42),
                        color: p.surface,
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(12),
                          side: BorderSide(color: p.sep),
                        ),
                        onSelected: (val) {
                          if (val == 'mine') onSelectTab(AppTab.mine);
                          if (val == 'downloads') onOpenDownloads();
                          if (val == 'settings') onOpenSettings();
                          if (val == 'logout') onLogout();
                        },
                        itemBuilder: (_) => [
                          PopupMenuItem(
                            enabled: false,
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  profile?.name ?? '红果用户',
                                  style: TextStyle(
                                    fontSize: 15,
                                    fontWeight: FontWeight.w600,
                                    color: p.text,
                                  ),
                                ),
                                const SizedBox(height: 2),
                                Text(
                                  daysLeft >= 0 ? '登录有效期还剩 $daysLeft 天' : '已登录',
                                  style: TextStyle(fontSize: 12.5, color: p.text3),
                                ),
                              ],
                            ),
                          ),
                          _SoftMenuDivider(color: p.sep),
                          PopupMenuItem(
                            value: 'mine',
                            child: Row(
                              children: [
                                Icon(Icons.movie_outlined, size: 17, color: p.text3),
                                const SizedBox(width: 10),
                                const Text('我的'),
                              ],
                            ),
                          ),
                          PopupMenuItem(
                            value: 'downloads',
                            child: Row(
                              children: [
                                Icon(Icons.download_rounded, size: 17, color: p.text3),
                                const SizedBox(width: 10),
                                const Text('下载管理'),
                              ],
                            ),
                          ),
                          PopupMenuItem(
                            value: 'settings',
                            child: Row(
                              children: [
                                Icon(Icons.settings_outlined, size: 17, color: p.text3),
                                const SizedBox(width: 10),
                                const Text('设置'),
                              ],
                            ),
                          ),
                          _SoftMenuDivider(color: p.sep),
                          PopupMenuItem(
                            value: 'logout',
                            child: Row(
                              children: [
                                Icon(Icons.logout_rounded, size: 17, color: p.danger),
                                const SizedBox(width: 10),
                                Text('退出登录', style: TextStyle(color: p.danger)),
                              ],
                            ),
                          ),
                        ],
                        child: Container(
                          width: 32,
                          height: 32,
                          decoration: BoxDecoration(
                            color: p.fill,
                            shape: BoxShape.circle,
                          ),
                          clipBehavior: Clip.antiAlias,
                          child: (profile?.avatar.isNotEmpty ?? false)
                              ? Image.network(
                                  profile!.avatar,
                                  fit: BoxFit.cover,
                                  cacheWidth: 96,
                                  gaplessPlayback: true,
                                  errorBuilder: (_, _, _) => Center(
                                    child: Text(
                                      (profile?.name.isNotEmpty ?? false)
                                          ? profile!.name.substring(0, 1)
                                          : '绿',
                                      style: TextStyle(
                                        fontSize: 13.5,
                                        fontWeight: FontWeight.w700,
                                        color: p.text,
                                      ),
                                    ),
                                  ),
                                )
                              : Center(
                                  child: Text(
                                    (profile?.name.isNotEmpty ?? false)
                                        ? profile!.name.substring(0, 1)
                                        : '绿',
                                    style: TextStyle(
                                      fontSize: 13.5,
                                      fontWeight: FontWeight.w700,
                                      color: p.text,
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

class _NavTabItem extends StatefulWidget {
  final String label;
  final bool selected;
  final VoidCallback onTap;

  const _NavTabItem({
    required this.label,
    required this.selected,
    required this.onTap,
  });

  @override
  State<_NavTabItem> createState() => _NavTabItemState();
}

class _NavTabItemState extends State<_NavTabItem> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    final p = LvguoPalette.of(context);
    return MouseRegion(
      onEnter: (_) => setState(() => _hover = true),
      onExit: (_) => setState(() => _hover = false),
      cursor: SystemMouseCursors.click,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: widget.onTap,
        child: Container(
          height: 44,
          margin: const EdgeInsets.symmetric(horizontal: 3),
          padding: const EdgeInsets.symmetric(horizontal: 14),
          alignment: Alignment.center,
          child: Text(
            widget.label,
            style: TextStyle(
              fontSize: 15,
              fontWeight: widget.selected ? FontWeight.w600 : FontWeight.w500,
              color: widget.selected
                  ? p.primary
                  : _hover
                      ? p.text
                      : p.text2,
            ),
          ),
        ),
      ),
    );
  }
}

class _RoundToolBtn extends StatefulWidget {
  final String tooltip;
  final IconData icon;
  final bool active;
  final VoidCallback onTap;

  const _RoundToolBtn({
    required this.tooltip,
    required this.icon,
    required this.active,
    required this.onTap,
  });

  @override
  State<_RoundToolBtn> createState() => _RoundToolBtnState();
}

class _RoundToolBtnState extends State<_RoundToolBtn> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    final p = LvguoPalette.of(context);
    return Tooltip(
      message: widget.tooltip,
      child: MouseRegion(
        onEnter: (_) => setState(() => _hover = true),
        onExit: (_) => setState(() => _hover = false),
        cursor: SystemMouseCursors.click,
        child: GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: widget.onTap,
          child: Container(
            width: 38,
            height: 38,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: _hover ? p.fill : Colors.transparent,
            ),
            child: Icon(
              widget.icon,
              size: 19,
              color: _hover ? p.text : p.text2,
            ),
          ),
        ),
      ),
    );
  }
}

class _SoftMenuDivider extends PopupMenuEntry<String> {
  final Color color;

  const _SoftMenuDivider({required this.color});

  @override
  double get height => 9;

  @override
  bool represents(String? value) => false;

  @override
  State<_SoftMenuDivider> createState() => _SoftMenuDividerState();
}

class _SoftMenuDividerState extends State<_SoftMenuDivider> {
  @override
  Widget build(BuildContext context) {
    return Container(
      height: 9,
      alignment: Alignment.center,
      padding: const EdgeInsets.symmetric(horizontal: 14),
      child: Container(height: 1, color: widget.color),
    );
  }
}
