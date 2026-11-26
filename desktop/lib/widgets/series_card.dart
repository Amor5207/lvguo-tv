import 'package:flutter/material.dart';

import '../core/theme.dart';
import '../models/models.dart';

/// Matches `.card` + `.poster` in web/ui/pc/app.css (aspect-ratio 3/4, radius 10px, no border)
class SeriesCard extends StatefulWidget {
  final SeriesItem item;
  final VoidCallback onTap;
  final int? rankBadge;
  final bool hideEpisodeBadge;
  final String? subtitle;
  final double? progressFraction;
  final VoidCallback? onDelete;

  const SeriesCard({
    super.key,
    required this.item,
    required this.onTap,
    this.rankBadge,
    this.hideEpisodeBadge = false,
    this.subtitle,
    this.progressFraction,
    this.onDelete,
  });

  @override
  State<SeriesCard> createState() => _SeriesCardState();
}

class _SeriesCardState extends State<SeriesCard> {
  bool _hovered = false;

  @override
  Widget build(BuildContext context) {
    final p = LvguoPalette.of(context);
    final rank = widget.rankBadge ?? (widget.item.rank > 0 ? widget.item.rank : null);
    final eps = widget.item.episodes;

    return RepaintBoundary(
      child: MouseRegion(
        onEnter: (_) => setState(() => _hovered = true),
        onExit: (_) => setState(() => _hovered = false),
        cursor: SystemMouseCursors.click,
        child: GestureDetector(
          onTap: widget.onTap,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              AspectRatio(
                aspectRatio: 3 / 4,
                child: Container(
                  decoration: BoxDecoration(
                    color: p.skeleton,
                    borderRadius: BorderRadius.circular(10),
                  ),
                  clipBehavior: Clip.antiAlias,
                  child: Stack(
                    fit: StackFit.expand,
                    children: [
                      AnimatedScale(
                        scale: _hovered ? 1.04 : 1.0,
                        duration: const Duration(milliseconds: 200),
                        curve: Curves.easeOutCubic,
                        child: widget.item.cover.isNotEmpty
                            ? Image.network(
                                widget.item.cover,
                                fit: BoxFit.cover,
                                cacheWidth: 360,
                                filterQuality: FilterQuality.medium,
                                gaplessPlayback: true,
                                errorBuilder: (_, _, _) => Container(color: p.skeleton),
                              )
                            : Container(color: p.skeleton),
                      ),
                    if (rank != null)
                      Positioned(
                        left: 8,
                        top: 8,
                        child: Container(
                          constraints: const BoxConstraints(minWidth: 24, minHeight: 24),
                          padding: const EdgeInsets.symmetric(horizontal: 6),
                          alignment: Alignment.center,
                          decoration: BoxDecoration(
                            color: rank <= 3
                                ? Colors.white
                                : Colors.black.withValues(alpha: 0.60),
                            borderRadius: BorderRadius.circular(6),
                          ),
                          child: Text(
                            '$rank',
                            style: TextStyle(
                              fontSize: 13,
                              fontWeight: FontWeight.w700,
                              color: rank <= 3 ? const Color(0xFF0C0C0D) : Colors.white,
                            ),
                          ),
                        ),
                      ),
                    if (eps > 0 && !widget.hideEpisodeBadge && widget.progressFraction == null)
                      Positioned(
                        right: 8,
                        bottom: 8,
                        child: Container(
                          padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
                          decoration: BoxDecoration(
                            color: Colors.black.withValues(alpha: 0.60),
                            borderRadius: BorderRadius.circular(5),
                          ),
                          child: Text(
                            '$eps集',
                            style: const TextStyle(
                              fontSize: 11.5,
                              color: Colors.white,
                              fontWeight: FontWeight.w500,
                            ),
                          ),
                        ),
                      ),
                    if (widget.onDelete != null && _hovered)
                      Positioned(
                        right: 8,
                        top: 8,
                        child: GestureDetector(
                          onTap: widget.onDelete,
                          child: Container(
                            width: 28,
                            height: 28,
                            decoration: BoxDecoration(
                              color: Colors.black.withValues(alpha: 0.60),
                              shape: BoxShape.circle,
                            ),
                            child: const Icon(Icons.close, size: 14, color: Colors.white),
                          ),
                        ),
                      ),
                    if (widget.progressFraction != null)
                      Positioned(
                        left: 0,
                        right: 0,
                        bottom: 0,
                        child: SizedBox(
                          height: 3,
                          child: Stack(
                            children: [
                              Container(color: Colors.black.withValues(alpha: 0.35)),
                              FractionallySizedBox(
                                widthFactor: widget.progressFraction!.clamp(0.0, 1.0),
                                child: Container(color: p.primary),
                              ),
                            ],
                          ),
                        ),
                      ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 10),
            Text(
              widget.item.title,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                fontSize: 14.5,
                fontWeight: FontWeight.w600,
                height: 1.35,
                color: p.text,
              ),
            ),
            if (widget.subtitle != null && widget.subtitle!.isNotEmpty) ...[
              const SizedBox(height: 2),
              Text(
                widget.subtitle!,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(fontSize: 12.5, color: p.text2),
              ),
            ],
          ],
        ),
      ),
    ),
    );
  }
}
