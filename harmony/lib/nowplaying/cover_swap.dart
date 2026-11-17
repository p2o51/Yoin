import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/model/model.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/component/artwork_image.dart';
import '../ui/component/artwork_swap.dart';

/// A cover that swaps old → new on a song change ([ArtworkSwap]): the old
/// image stays drawn under the new one until the new one is fully revealed,
/// and a skip mid-reveal freezes the half-revealed cover under the next one.
/// Now Playing prints its cover in through the screen-print halftone
/// ([ArtworkReveal.dotDissolve], NowPlayingScreen.kt) on the slow
/// artworkDissolve spring, entering from the side the song came from
/// ([direction]); the pill crossfades on the default effects spring and snaps
/// a memory-cache hit. A failed cover shows [fallback] while it retries.
///
/// Callers draw their own placeholder when [cover] is null.
class CoverSwap extends ConsumerWidget {
  const CoverSwap({
    super.key,
    required this.cover,
    required this.size,
    required this.radius,
    required this.spring,
    this.reveal = ArtworkReveal.crossfade,
    this.direction = 1,
    this.fallback,
    this.requestSize,
    this.fallbackRequestSize,
  });

  final CoverRef cover;
  final double size;
  final double radius;
  final SpringDescription spring;
  final ArtworkReveal reveal;

  /// +1: the song came by a skip forward (or played on), −1: a skip back.
  final int direction;

  /// What a failed cover shows while it retries (the caller's null-cover glyph).
  final Widget? fallback;

  /// Pixel size to ask the server for; defaults to [size] × device pixel ratio.
  final int? requestSize;

  /// Another decode of the same cover that is likely already in memory (Now
  /// Playing passes the pill's). Drawn under the first image until that one
  /// has a frame, so a cold load never shows an empty box where the flying
  /// cover just landed.
  final int? fallbackRequestSize;

  /// The NP cover's spring (1.0 / 45).
  static final artworkDissolve = YoinSprings.of(MotionTokens.artworkDissolve);

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final source = ref.watch(activeSourceProvider).value;
    final dpr = MediaQuery.devicePixelRatioOf(context);
    final px = requestSize ?? (size * dpr).round();
    final cover = this.cover;
    // The tip pages' made-up covers need no source.
    final demo = cover is CoverUrl && DemoArtwork.parse(cover.url) != null ? cover.url : null;
    final url = demo ?? source?.resolveCoverUrl(cover, size: px);
    final fallbackPx = fallbackRequestSize;
    final underUrl = fallbackPx == null || fallbackPx == px ? null : source?.resolveCoverUrl(cover, size: fallbackPx);
    return SizedBox.square(
      dimension: size,
      child: ClipRSuperellipse(
        borderRadius: BorderRadius.circular(radius),
        child: ColoredBox(
          color: Theme.of(context).colorScheme.surfaceContainerHigh,
          child: ArtworkContent(
            url: url,
            decodeWidth: px,
            reveal: reveal,
            direction: direction,
            spring: spring,
            fallback: fallback,
            underlay: underUrl == null
                ? null
                : Image(
                    image: artworkImageProvider(underUrl, cacheWidth: fallbackPx),
                    fit: BoxFit.cover,
                    gaplessPlayback: true,
                    errorBuilder: (_, _, _) => const SizedBox.expand(),
                  ),
          ),
        ),
      ),
    );
  }
}
