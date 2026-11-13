import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/providers.dart';
import '../../data/model/model.dart';
import '../../theme/motion.dart';

/// Artwork from the active source, the one way screens show covers.
/// Square, no border (owner: covers never get strokes), [radius] from
/// ShapeTokens (thumb/cover 4, hero 8, Now Playing 16). Decodes at the
/// size it is shown and fades in on the effects spring once loaded.
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
    final url = ref0 == null ? null : source?.resolveCoverUrl(ref0, size: px);
    return SizedBox.square(
      dimension: size,
      child: ClipRSuperellipse(
        borderRadius: BorderRadius.circular(radius),
        child: ColoredBox(
          color: Theme.of(context).colorScheme.surfaceContainerHigh,
          child: url == null ? null : _FadeInNetworkImage(url: url, cacheWidth: px),
        ),
      ),
    );
  }
}

class _FadeInNetworkImage extends StatefulWidget {
  const _FadeInNetworkImage({required this.url, required this.cacheWidth});

  final String url;
  final int cacheWidth;

  @override
  State<_FadeInNetworkImage> createState() => _FadeInNetworkImageState();
}

class _FadeInNetworkImageState extends State<_FadeInNetworkImage> with TickerProviderStateMixin {
  late final SpringValue _alpha = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultEffects);

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
        cacheWidth: widget.cacheWidth,
        gaplessPlayback: true,
        errorBuilder: (_, _, _) => const SizedBox.expand(),
        frameBuilder: (context, child, frame, wasSynchronouslyLoaded) {
          if (wasSynchronouslyLoaded) {
            _alpha.snapTo(1);
          } else if (frame != null) {
            _alpha.animateTo(1);
          }
          return FadeTransition(opacity: _alpha.animation.drive(Tween(begin: 0, end: 1)), child: child);
        },
      );
}
