import 'package:flutter/material.dart';

import '../../theme/motion.dart';

/// Artwork from a resolved URL (component/ExpressiveComponents.kt
/// ExpressiveMediaArtwork): surfaceContainerHighest fill with a 24dp
/// [fallbackIcon] while loading or on failure, the image fading in on the
/// effects spring once decoded. No border (covers never get strokes).
///
/// Callers pass the URL so screens that show the same item share one image
/// cache key; [decodeWidth] is the px the image is decoded at.
class MediaArtwork extends StatelessWidget {
  const MediaArtwork({
    super.key,
    required this.url,
    required this.fallbackIcon,
    this.radius = 4,
    this.circle = false,
    this.decodeWidth,
    this.fallbackIconSize = 24,
  });

  final String? url;
  final IconData fallbackIcon;
  final double radius;
  final bool circle;
  final int? decodeWidth;
  final double fallbackIconSize;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final content = ColoredBox(
      color: colors.surfaceContainerHighest,
      child: Stack(
        fit: StackFit.expand,
        children: [
          Center(
            child: Icon(fallbackIcon, size: fallbackIconSize, color: colors.onSurfaceVariant),
          ),
          if (url != null) _FadeInImage(url: url!, decodeWidth: decodeWidth),
        ],
      ),
    );
    return circle
        ? ClipOval(child: content)
        : ClipRSuperellipse(borderRadius: BorderRadius.circular(radius), child: content);
  }
}

class _FadeInImage extends StatefulWidget {
  const _FadeInImage({required this.url, this.decodeWidth});

  final String url;
  final int? decodeWidth;

  @override
  State<_FadeInImage> createState() => _FadeInImageState();
}

class _FadeInImageState extends State<_FadeInImage> with TickerProviderStateMixin {
  late final SpringValue _alpha = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultEffects);

  @override
  void didUpdateWidget(_FadeInImage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.url != widget.url) _alpha.snapTo(0);
  }

  @override
  void dispose() {
    _alpha.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Image.network(
    widget.url,
    fit: BoxFit.cover,
    width: double.infinity,
    height: double.infinity,
    cacheWidth: widget.decodeWidth,
    gaplessPlayback: true,
    errorBuilder: (_, _, _) => const SizedBox.shrink(),
    frameBuilder: (context, child, frame, sync) {
      if (sync) {
        _alpha.snapTo(1);
      } else if (frame != null) {
        _alpha.animateTo(1);
      }
      return FadeTransition(opacity: _alpha.animation.drive(Tween(begin: 0, end: 1)), child: child);
    },
  );
}
