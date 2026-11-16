import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/providers.dart';
import '../../data/model/model.dart';
import 'artwork_image.dart';
import 'artwork_swap.dart';

/// Artwork from the active source, the one way screens show covers.
/// Square, no border (owner: covers never get strokes), [radius] from
/// ShapeTokens (thumb/cover 4, hero 8, Now Playing 16). Decodes at the
/// size it is shown and comes in on the effects spring once loaded; a failed
/// cover retries ([ArtworkContent]).
class CoverArt extends ConsumerWidget {
  const CoverArt({super.key, required this.cover, required this.size, required this.radius, this.requestSize});

  final CoverRef? cover;

  /// Logical edge length the cover is laid out at.
  final double size;
  final double radius;

  /// Pixel size to ask the server for; defaults to [size] × device pixel ratio.
  final int? requestSize;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final source = ref.watch(activeSourceProvider).value;
    final dpr = MediaQuery.devicePixelRatioOf(context);
    final px = requestSize ?? (size * dpr).round();
    final ref0 = cover;
    final url = switch (ref0) {
      null => null,
      // The tip pages' made-up covers need no source.
      CoverUrl(:final url) when DemoArtwork.parse(url) != null => url,
      _ => source?.resolveCoverUrl(ref0, size: px),
    };
    return SizedBox.square(
      dimension: size,
      child: ClipRSuperellipse(
        borderRadius: BorderRadius.circular(radius),
        child: ColoredBox(
          color: Theme.of(context).colorScheme.surfaceContainerHigh,
          child: ArtworkContent(url: url, decodeWidth: px),
        ),
      ),
    );
  }
}
