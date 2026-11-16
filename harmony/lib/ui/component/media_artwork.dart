import 'package:flutter/material.dart';

import '../../theme/tokens.dart';
import 'artwork_swap.dart';

/// Artwork from a resolved URL (component/ExpressiveComponents.kt
/// ExpressiveMediaArtwork): surfaceContainerHighest fill with a 24dp
/// [fallbackIcon] for no url, and under a failed cover while it retries
/// ([ArtworkContent]); the image comes in on the effects spring ([ArtworkSwap]).
/// No border (covers never get strokes).
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
    this.fallbackIconSize = ArtworkTokens.fallbackIcon,
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
      child: ArtworkContent(
        url: url,
        decodeWidth: decodeWidth,
        fallback: ArtworkFallbackIcon(fallbackIcon, size: fallbackIconSize),
      ),
    );
    return circle
        ? ClipOval(child: content)
        : ClipRSuperellipse(borderRadius: BorderRadius.circular(radius), child: content);
  }
}
