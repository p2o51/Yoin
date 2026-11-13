import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../ui/component/media_artwork.dart';
import 'pull_up_reveal.dart';

/// Playlist sleeves (Android PlaylistSleeves.kt): up to two album covers
/// fanned behind the hero cover.
abstract final class PlaylistSleeveGeometry {
  /// Sleeve side as a share of the cover.
  static const double sideShare = 0.86;

  /// Horizontal offset (share of the cover side) at full spread.
  static const double maxSpread = 0.20;

  /// Drop below the cover centre (share of the side).
  static const double drop = 0.07;

  /// Tilt, degrees (start sleeve −, end sleeve +).
  static const double tiltDegrees = 6.3;

  /// Half the rotated sleeve's horizontal extent, as a share of the cover.
  static final double halfExtent =
      sideShare / 2 * (math.cos(tiltDegrees * math.pi / 180) + math.sin(tiltDegrees * math.pi / 180));

  /// The spread that keeps a sleeve whole in a box with [sideRoom] beside
  /// the cover: clamp(sideRoom / cover + 0.5 − halfExtent, 0, 0.20).
  static double spread(double sideRoom, double cover) {
    if (cover <= 0) return 0;
    return (sideRoom / cover + 0.5 - halfExtent).clamp(0.0, maxSpread);
  }

  /// The sleeve's centre offset from the cover centre: [index] 0 leans to the start, 1 to the end.
  static Offset centerOffset(int index, double cover, double spread, {required bool rtl}) {
    final dir = (index == 0 ? -1.0 : 1.0) * (rtl ? -1 : 1);
    return Offset(dir * spread * cover, drop * cover);
  }
}

/// Draws [urls] (at most two) behind a cover at [cover] (slot space); they
/// leave on the pull-up by fading with [leaving] (v1: no dot dissolve).
class PlaylistSleeves extends StatelessWidget {
  const PlaylistSleeves({
    super.key,
    required this.urls,
    required this.cover,
    required this.leaving,
    required this.corner,
  });

  final List<String?> urls;
  final Rect cover;
  final double leaving;
  final double corner;

  @override
  Widget build(BuildContext context) {
    if (urls.isEmpty || leaving >= 0.999) return const SizedBox.shrink();
    final rtl = Directionality.of(context) == TextDirection.rtl;
    return LayoutBuilder(
      builder: (context, constraints) {
        final sideRoom = (constraints.maxWidth - cover.width) / 2;
        final spread = PlaylistSleeveGeometry.spread(sideRoom, cover.width);
        final side = cover.width * PlaylistSleeveGeometry.sideShare;
        final dpr = MediaQuery.devicePixelRatioOf(context);
        return Opacity(
          opacity: 1 - leaving,
          child: Stack(
            clipBehavior: Clip.none,
            children: [
              for (final (i, url) in urls.take(2).indexed.toList().reversed)
                Positioned.fromRect(
                  rect: Rect.fromCenter(
                    center: cover.center + PlaylistSleeveGeometry.centerOffset(i, cover.width, spread, rtl: rtl),
                    width: side,
                    height: side,
                  ),
                  child: Transform.rotate(
                    angle: (i == 0 ? -1 : 1) * (rtl ? -1 : 1) * PlaylistSleeveGeometry.tiltDegrees * math.pi / 180,
                    child: MediaArtwork(
                      url: url,
                      fallbackIcon: YoinSymbols.album,
                      radius: corner,
                      decodeWidth: (side * dpr).round(),
                    ),
                  ),
                ),
            ],
          ),
        );
      },
    );
  }
}

/// The resting hero corner, for the sleeves.
const playlistSleeveCorner = PullUpTokens.heroCorner;
