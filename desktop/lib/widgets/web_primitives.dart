import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../core/theme.dart';

/// Matches `.chip` in web/ui/pc/app.css (height 32, radius 8, borderless, soft primary on active).
class WebChip extends StatefulWidget {
  final String label;
  final bool active;
  final VoidCallback onTap;
  final Widget? leading;

  const WebChip({
    super.key,
    required this.label,
    required this.active,
    required this.onTap,
    this.leading,
  });

  @override
  State<WebChip> createState() => _WebChipState();
}

class _WebChipState extends State<WebChip> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    final p = LvguoPalette.of(context);
    return MouseRegion(
      cursor: SystemMouseCursors.click,
      onEnter: (_) => setState(() => _hover = true),
      onExit: (_) => setState(() => _hover = false),
      child: GestureDetector(
        onTap: widget.onTap,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 180),
          curve: Curves.easeOut,
          height: 32,
          padding: const EdgeInsets.symmetric(horizontal: 12),
          decoration: BoxDecoration(
            color: widget.active ? p.primarySoft : Colors.transparent,
            borderRadius: BorderRadius.circular(8),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (widget.leading != null) ...[
                widget.leading!,
                const SizedBox(width: 6),
              ],
              Text(
                widget.label,
                style: TextStyle(
                  fontSize: 14,
                  fontWeight: widget.active ? FontWeight.w600 : FontWeight.w400,
                  color: widget.active
                      ? p.primary
                      : _hover
                          ? p.text
                          : p.text3,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Matches `.bar2 .seg` underline tab bar with 280ms sliding `2px` underline.
class UnderlineTabBar extends StatefulWidget {
  final List<String> labels;
  final int activeIndex;
  final ValueChanged<int> onChanged;
  final double gap;
  final double height;
  final double fontSize;
  final Color? activeTextColor;
  final Color? inactiveTextColor;
  final Color? hoverTextColor;
  final Color? indicatorColor;
  final Color? bottomBorderColor;

  const UnderlineTabBar({
    super.key,
    required this.labels,
    required this.activeIndex,
    required this.onChanged,
    this.gap = 26,
    this.height = 44,
    this.fontSize = 15.5,
    this.activeTextColor,
    this.inactiveTextColor,
    this.hoverTextColor,
    this.indicatorColor,
    this.bottomBorderColor,
  });

  @override
  State<UnderlineTabBar> createState() => _UnderlineTabBarState();
}

class _UnderlineTabBarState extends State<UnderlineTabBar> {
  final GlobalKey _stackKey = GlobalKey();
  final List<GlobalKey> _tabKeys = [];
  double _left = 0;
  double _width = 0;
  bool _hasPos = false;
  int _hoverIdx = -1;

  @override
  void initState() {
    super.initState();
    _syncKeys();
    WidgetsBinding.instance.addPostFrameCallback((_) => _measure());
  }

  @override
  void didUpdateWidget(covariant UnderlineTabBar oldWidget) {
    super.didUpdateWidget(oldWidget);
    _syncKeys();
    WidgetsBinding.instance.addPostFrameCallback((_) => _measure());
  }

  void _syncKeys() {
    while (_tabKeys.length < widget.labels.length) {
      _tabKeys.add(GlobalKey());
    }
    if (_tabKeys.length > widget.labels.length) {
      _tabKeys.removeRange(widget.labels.length, _tabKeys.length);
    }
  }

  void _measure() {
    if (!mounted ||
        widget.activeIndex < 0 ||
        widget.activeIndex >= _tabKeys.length) {
      return;
    }
    final stackBox =
        _stackKey.currentContext?.findRenderObject() as RenderBox?;
    final tabBox =
        _tabKeys[widget.activeIndex].currentContext?.findRenderObject()
            as RenderBox?;
    if (stackBox == null || tabBox == null || !tabBox.attached) return;
    final pos = tabBox.localToGlobal(Offset.zero, ancestor: stackBox);
    if ((pos.dx - _left).abs() > 0.5 ||
        (tabBox.size.width - _width).abs() > 0.5 ||
        !_hasPos) {
      setState(() {
        _left = pos.dx;
        _width = tabBox.size.width;
        _hasPos = true;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final p = LvguoPalette.of(context);
    final actColor = widget.activeTextColor ?? p.primary;
    final inactColor = widget.inactiveTextColor ?? p.text3;
    final hovColor = widget.hoverTextColor ?? p.text;
    final indColor = widget.indicatorColor ?? p.text;
    final borderColor = widget.bottomBorderColor ?? p.sep;
    final hasBorder = borderColor.a > 0;

    return Container(
      decoration: hasBorder
          ? BoxDecoration(
              border: Border(bottom: BorderSide(color: borderColor, width: 1)),
            )
          : null,
      child: SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        child: SizedBox(
          key: _stackKey,
          height: widget.height,
          child: Stack(
            clipBehavior: Clip.none,
            children: [
              Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  for (int i = 0; i < widget.labels.length; i++) ...[
                    if (i > 0) SizedBox(width: widget.gap),
                    MouseRegion(
                      cursor: SystemMouseCursors.click,
                      onEnter: (_) => setState(() => _hoverIdx = i),
                      onExit: (_) {
                        if (_hoverIdx == i) setState(() => _hoverIdx = -1);
                      },
                      child: GestureDetector(
                        key: _tabKeys[i],
                        behavior: HitTestBehavior.opaque,
                        onTap: () => widget.onChanged(i),
                        child: Container(
                          height: widget.height,
                          alignment: Alignment.center,
                          child: Text(
                            widget.labels[i],
                            style: TextStyle(
                              fontSize: widget.fontSize,
                              fontWeight: i == widget.activeIndex
                                  ? FontWeight.w600
                                  : FontWeight.w500,
                              color: i == widget.activeIndex
                                  ? actColor
                                  : _hoverIdx == i
                                      ? hovColor
                                      : inactColor,
                            ),
                          ),
                        ),
                      ),
                    ),
                  ],
                ],
              ),
              if (_hasPos)
                AnimatedPositioned(
                  duration: const Duration(milliseconds: 280),
                  curve: const Cubic(0.22, 1, 0.36, 1),
                  left: _left,
                  width: _width,
                  bottom: 0,
                  height: 2,
                  child: DecoratedBox(
                    decoration: BoxDecoration(
                      color: indColor,
                      borderRadius: BorderRadius.circular(1),
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Smooth pulsing placeholder block matching `.skel` in web/ui/pc/app.css.
class SkeletonBox extends StatefulWidget {
  final double? width;
  final double? height;
  final double radius;

  const SkeletonBox({
    super.key,
    this.width,
    this.height,
    this.radius = 8,
  });

  @override
  State<SkeletonBox> createState() => _SkeletonBoxState();
}

class _SkeletonBoxState extends State<SkeletonBox>
    with SingleTickerProviderStateMixin {
  late final AnimationController _ctrl;

  @override
  void initState() {
    super.initState();
    _ctrl = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1100),
    )..repeat(reverse: true);
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final c0 = isDark ? const Color(0xFF202329) : const Color(0xFFEDEDEF);
    final c1 = isDark ? const Color(0xFF2D3139) : const Color(0xFFDFDFE3);
    return AnimatedBuilder(
      animation: _ctrl,
      builder: (_, __) {
        final t = Curves.easeInOut.transform(_ctrl.value);
        return Container(
          width: widget.width,
          height: widget.height,
          decoration: BoxDecoration(
            color: Color.lerp(c0, c1, t),
            borderRadius: BorderRadius.circular(widget.radius),
          ),
        );
      },
    );
  }
}

/// 3:4 card skeleton matching `SeriesCard` dimensions cleanly with or without subtitle.
class SeriesCardSkeleton extends StatelessWidget {
  final bool showSubtitle;

  const SeriesCardSkeleton({super.key, this.showSubtitle = false});

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const AspectRatio(
          aspectRatio: 3 / 4,
          child: SkeletonBox(width: double.infinity, height: double.infinity, radius: 10),
        ),
        const SizedBox(height: 10),
        LayoutBuilder(
          builder: (_, box) => Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              SkeletonBox(width: box.maxWidth * 0.78, height: 15, radius: 5),
              if (showSubtitle) ...[
                const SizedBox(height: 6),
                SkeletonBox(width: box.maxWidth * 0.46, height: 12, radius: 4),
              ],
            ],
          ),
        ),
      ],
    );
  }
}

/// Sliver grid of card skeletons matching `.grid` on Rank/Category/Mine/Home.
Widget buildSliverGridSkeleton({
  required double gutter,
  required int cols,
  required double cardW,
  int count = 12,
  double topPadding = 24,
  double bottomPadding = 40,
  double mainAxisSpacing = 28,
  double extraHeight = 42,
  bool showSubtitle = false,
}) {
  return SliverPadding(
    padding: EdgeInsets.fromLTRB(gutter, topPadding, gutter, bottomPadding),
    sliver: SliverGrid(
      gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: cols,
        mainAxisSpacing: mainAxisSpacing,
        crossAxisSpacing: 20,
        childAspectRatio: cardW / (cardW * (4 / 3) + extraHeight),
      ),
      delegate: SliverChildBuilderDelegate(
        (_, _) => SeriesCardSkeleton(showSubtitle: showSubtitle),
        childCount: count,
      ),
    ),
  );
}

/// Full Home page skeleton matching real hero banner + shelf + recommendation grid coordinates 1:1.
class HomeSkeleton extends StatelessWidget {
  final double gutter;
  final double heroHeight;
  final int cols;
  final double cardW;

  const HomeSkeleton({
    super.key,
    required this.gutter,
    required this.heroHeight,
    required this.cols,
    required this.cardW,
  });

  @override
  Widget build(BuildContext context) {
    final screenW = MediaQuery.of(context).size.width;
    final artW = math.min(screenW * 0.62, heroHeight * 1.08);
    final txtW = math.min(600.0, screenW * 0.48);
    final shelfH = cardW * (4 / 3) + 44;

    return CustomScrollView(
      physics: const NeverScrollableScrollPhysics(),
      slivers: [
        SliverToBoxAdapter(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              SizedBox(
                height: heroHeight,
                width: double.infinity,
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                    Positioned(
                      right: 0,
                      top: 0,
                      bottom: 0,
                      width: artW,
                      child: const SkeletonBox(
                        width: double.infinity,
                        height: double.infinity,
                        radius: 0,
                      ),
                    ),
                    Positioned(
                      left: gutter,
                      top: 104,
                      width: txtW,
                      child: const Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          SkeletonBox(width: 72, height: 22, radius: 99),
                          SizedBox(height: 14),
                          SkeletonBox(width: 320, height: 42, radius: 8),
                          SizedBox(height: 12),
                          SkeletonBox(width: 240, height: 15, radius: 5),
                          SizedBox(height: 14),
                          SkeletonBox(width: 380, height: 14, radius: 5),
                          SizedBox(height: 8),
                          SkeletonBox(width: 280, height: 14, radius: 5),
                          SizedBox(height: 24),
                          Row(
                            children: [
                              SkeletonBox(width: 112, height: 48, radius: 99),
                              SizedBox(width: 12),
                              SkeletonBox(width: 96, height: 48, radius: 99),
                            ],
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 16),
              Padding(
                padding: EdgeInsets.symmetric(horizontal: gutter),
                child: const SkeletonBox(width: 110, height: 24, radius: 6),
              ),
              const SizedBox(height: 14),
              SizedBox(
                height: shelfH,
                child: ListView.separated(
                  scrollDirection: Axis.horizontal,
                  physics: const NeverScrollableScrollPhysics(),
                  padding: EdgeInsets.symmetric(horizontal: gutter),
                  itemCount: cols + 1,
                  separatorBuilder: (_, _) => const SizedBox(width: 20),
                  itemBuilder: (_, _) => SizedBox(
                    width: cardW,
                    child: const SeriesCardSkeleton(),
                  ),
                ),
              ),
              const SizedBox(height: 36),
              Padding(
                padding: EdgeInsets.symmetric(horizontal: gutter),
                child: const SkeletonBox(width: 130, height: 24, radius: 6),
              ),
              const SizedBox(height: 16),
            ],
          ),
        ),
        buildSliverGridSkeleton(
          gutter: gutter,
          cols: cols,
          cardW: cardW,
          count: cols * 2,
          topPadding: 0,
          bottomPadding: 48,
          mainAxisSpacing: 28,
          extraHeight: 42,
        ),
      ],
    );
  }
}

/// Detail page skeleton matching 240x320 poster + metadata + episode tiles.
class DetailSkeleton extends StatelessWidget {
  final double gutter;

  const DetailSkeleton({super.key, required this.gutter});

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      physics: const NeverScrollableScrollPhysics(),
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 1600),
          child: Padding(
            padding: EdgeInsets.fromLTRB(gutter, 36, gutter, 80),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const SkeletonBox(width: 72, height: 32, radius: 99),
                const SizedBox(height: 24),
                const Row(
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    SkeletonBox(width: 240, height: 320, radius: 12),
                    SizedBox(width: 44),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          SkeletonBox(width: 340, height: 38, radius: 8),
                          SizedBox(height: 14),
                          SkeletonBox(width: 240, height: 15, radius: 5),
                          SizedBox(height: 18),
                          SkeletonBox(width: 520, height: 14, radius: 5),
                          SizedBox(height: 8),
                          SkeletonBox(width: 440, height: 14, radius: 5),
                          SizedBox(height: 28),
                          Row(
                            children: [
                              SkeletonBox(width: 136, height: 46, radius: 99),
                              SizedBox(width: 12),
                              SkeletonBox(width: 108, height: 46, radius: 99),
                            ],
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 48),
                const SkeletonBox(width: 140, height: 22, radius: 6),
                const SizedBox(height: 18),
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    for (int i = 0; i < 24; i++)
                      const SkeletonBox(width: 68, height: 44, radius: 8),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// Matches `.seg` + `.seg .th` sliding thumb control in web/ui/pc/app.css.
class SlidingSegmentedControl<T> extends StatefulWidget {
  final T value;
  final List<(T, String)> options;
  final ValueChanged<T> onChanged;
  final double height;
  final double fontSize;
  final double horizontalPadding;

  const SlidingSegmentedControl({
    super.key,
    required this.value,
    required this.options,
    required this.onChanged,
    this.height = 32,
    this.fontSize = 14,
    this.horizontalPadding = 16,
  });

  @override
  State<SlidingSegmentedControl<T>> createState() =>
      _SlidingSegmentedControlState<T>();
}

class _SlidingSegmentedControlState<T>
    extends State<SlidingSegmentedControl<T>> {
  final GlobalKey _trackKey = GlobalKey();
  final List<GlobalKey> _btnKeys = [];
  double _left = 0;
  double _width = 0;
  bool _hasPos = false;

  @override
  void initState() {
    super.initState();
    _syncKeys();
    WidgetsBinding.instance.addPostFrameCallback((_) => _measure());
  }

  @override
  void didUpdateWidget(covariant SlidingSegmentedControl<T> oldWidget) {
    super.didUpdateWidget(oldWidget);
    _syncKeys();
    WidgetsBinding.instance.addPostFrameCallback((_) => _measure());
  }

  void _syncKeys() {
    while (_btnKeys.length < widget.options.length) {
      _btnKeys.add(GlobalKey());
    }
    if (_btnKeys.length > widget.options.length) {
      _btnKeys.removeRange(widget.options.length, _btnKeys.length);
    }
  }

  void _measure() {
    if (!mounted) return;
    final idx = widget.options.indexWhere((o) => o.$1 == widget.value);
    if (idx < 0 || idx >= _btnKeys.length) return;
    final trackBox =
        _trackKey.currentContext?.findRenderObject() as RenderBox?;
    final btnBox =
        _btnKeys[idx].currentContext?.findRenderObject() as RenderBox?;
    if (trackBox == null || btnBox == null || !btnBox.attached) return;
    final pos = btnBox.localToGlobal(Offset.zero, ancestor: trackBox);
    if ((pos.dx - _left).abs() > 0.5 ||
        (btnBox.size.width - _width).abs() > 0.5 ||
        !_hasPos) {
      setState(() {
        _left = pos.dx;
        _width = btnBox.size.width;
        _hasPos = true;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final p = LvguoPalette.of(context);
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return Container(
      padding: const EdgeInsets.all(3),
      decoration: BoxDecoration(
        color: p.fill,
        borderRadius: BorderRadius.circular(9),
      ),
      child: SizedBox(
        key: _trackKey,
        height: widget.height,
        child: Stack(
          children: [
            if (_hasPos)
              AnimatedPositioned(
                duration: const Duration(milliseconds: 280),
                curve: const Cubic(0.22, 1, 0.36, 1),
                left: _left,
                width: _width,
                top: 0,
                bottom: 0,
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    color: isDark ? p.surface3 : p.surface,
                    borderRadius: BorderRadius.circular(7),
                    boxShadow: isDark
                        ? null
                        : const [
                            BoxShadow(
                              color: Color(0x14000000),
                              blurRadius: 2,
                              offset: Offset(0, 1),
                            ),
                          ],
                  ),
                ),
              ),
            Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                for (int i = 0; i < widget.options.length; i++)
                  MouseRegion(
                    cursor: SystemMouseCursors.click,
                    child: GestureDetector(
                      key: _btnKeys[i],
                      behavior: HitTestBehavior.opaque,
                      onTap: () => widget.onChanged(widget.options[i].$1),
                      child: Container(
                        height: widget.height,
                        padding: EdgeInsets.symmetric(
                          horizontal: widget.horizontalPadding,
                        ),
                        alignment: Alignment.center,
                        child: Text(
                          widget.options[i].$2,
                          style: TextStyle(
                            fontSize: widget.fontSize,
                            fontWeight: widget.options[i].$1 == widget.value
                                ? FontWeight.w600
                                : FontWeight.w500,
                            color: widget.options[i].$1 == widget.value
                                ? p.primary
                                : p.text3,
                          ),
                        ),
                      ),
                    ),
                  ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

/// Matches `.sw` switch in web/ui/pc/app.css (44x26, ink track on active, 20x20 sliding circle).
class WebSwitch extends StatelessWidget {
  final bool value;
  final ValueChanged<bool> onChanged;
  final Color? activeTrackColor;
  final Color? activeThumbColor;
  final Color? inactiveTrackColor;

  const WebSwitch({
    super.key,
    required this.value,
    required this.onChanged,
    this.activeTrackColor,
    this.activeThumbColor,
    this.inactiveTrackColor,
  });

  @override
  Widget build(BuildContext context) {
    final p = LvguoPalette.of(context);
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final trackOn = activeTrackColor ?? p.text;
    final trackOff = inactiveTrackColor ?? p.fill3;
    final thumbOn = activeThumbColor ?? (isDark ? const Color(0xFF0A0A0B) : Colors.white);
    final thumbOff = Colors.white;

    return MouseRegion(
      cursor: SystemMouseCursors.click,
      child: GestureDetector(
        onTap: () => onChanged(!value),
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 220),
          curve: const Cubic(0.22, 1, 0.36, 1),
          width: 44,
          height: 26,
          padding: const EdgeInsets.all(3),
          decoration: BoxDecoration(
            color: value ? trackOn : trackOff,
            borderRadius: BorderRadius.circular(99),
          ),
          child: AnimatedAlign(
            duration: const Duration(milliseconds: 220),
            curve: const Cubic(0.22, 1, 0.36, 1),
            alignment: value ? Alignment.centerRight : Alignment.centerLeft,
            child: Container(
              width: 20,
              height: 20,
              decoration: BoxDecoration(
                color: value ? thumbOn : thumbOff,
                shape: BoxShape.circle,
                boxShadow: const [
                  BoxShadow(
                    color: Color(0x40000000),
                    blurRadius: 3,
                    offset: Offset(0, 1),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// Matches `.rise` card entrance animation (`400ms` ease-out translateY(10->0) + fade, staggered 24ms * i).
class RiseCard extends StatefulWidget {
  final int index;
  final Widget child;

  const RiseCard({
    super.key,
    required this.index,
    required this.child,
  });

  @override
  State<RiseCard> createState() => _RiseCardState();
}

class _RiseCardState extends State<RiseCard>
    with SingleTickerProviderStateMixin {
  AnimationController? _ctrl;
  Animation<double>? _opacity;
  Animation<Offset>? _offset;
  Timer? _delayTimer;

  bool get _shouldAnimate => widget.index < 12;

  @override
  void initState() {
    super.initState();
    if (!_shouldAnimate) return;
    final ctrl = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 220),
    );
    _ctrl = ctrl;
    final curved = CurvedAnimation(
      parent: ctrl,
      curve: const Cubic(0.22, 1, 0.36, 1),
    );
    _opacity = curved;
    _offset = Tween<Offset>(
      begin: const Offset(0, 0.04),
      end: Offset.zero,
    ).animate(curved);

    final delayMs = math.min(widget.index, 10) * 18;
    if (delayMs == 0) {
      ctrl.forward();
    } else {
      _delayTimer = Timer(Duration(milliseconds: delayMs), () {
        if (mounted) ctrl.forward();
      });
    }
  }

  @override
  void dispose() {
    _delayTimer?.cancel();
    _ctrl?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (!_shouldAnimate || _opacity == null || _offset == null) {
      return widget.child;
    }
    return FadeTransition(
      opacity: _opacity!,
      child: SlideTransition(
        position: _offset!,
        child: widget.child,
      ),
    );
  }
}

/// Matches `.eq` 3-bar bouncing audio equalizer next to playing episode number.
class EqualizerBars extends StatefulWidget {
  final Color color;

  const EqualizerBars({super.key, required this.color});

  @override
  State<EqualizerBars> createState() => _EqualizerBarsState();
}

class _EqualizerBarsState extends State<EqualizerBars>
    with SingleTickerProviderStateMixin {
  late final AnimationController _ctrl;

  @override
  void initState() {
    super.initState();
    _ctrl = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1000),
    )..repeat();
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  double _barHeight(double phaseShift) {
    final t = (_ctrl.value + phaseShift) % 1.0;
    final tri = t < 0.5 ? t * 2.0 : (1.0 - t) * 2.0;
    return 0.35 + 0.65 * Curves.easeInOut.transform(tri);
  }

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 13,
      height: 13,
      child: AnimatedBuilder(
        animation: _ctrl,
        builder: (_, __) => Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            for (final shift in const [0.0, 0.4, 0.7])
              Align(
                alignment: Alignment.bottomCenter,
                child: Container(
                  width: 3,
                  height: 13 * _barHeight(shift),
                  decoration: BoxDecoration(
                    color: widget.color,
                    borderRadius: BorderRadius.circular(2),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
