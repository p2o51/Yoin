import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../home/widgets/home_motion.dart' show StagedReveal, stagedBeatLanded;
import '../theme/tokens.dart';
import '../ui/component/media_artwork.dart';
import 'dot_dissolve.dart';
import 'pull_up_reveal.dart';
import 'reshape_scaffold.dart' show DetailStagedRevealScope;

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
  /// [open] runs 0 → 1 as the sleeve comes out from behind the cover on the first open.
  static Offset centerOffset(int index, double cover, double spread, {required bool rtl, double open = 1}) {
    final dir = (index == 0 ? -1.0 : 1.0) * (rtl ? -1 : 1);
    return Offset(dir * spread * cover * open, drop * cover * open);
  }

  /// The first open (PlaylistSleeveReveal): each sleeve opens from behind the
  /// cover once the cover's own beat has started, this far apart (the first
  /// one beat after the cover), on the Expressive slow spatial spring.
  static const Duration revealStagger = Duration(milliseconds: 50);

  /// A sleeve grows from this share of its side as it opens.
  static const double revealScaleFrom = 0.6;

  /// Its opacity runs this much faster than its beat (full by ~0.7).
  static const double revealFadeInRate = 1.4;

  /// The sleeve's side, centre offset, tilt (radians) and opacity at its reveal beat [beat].
  static ({double grow, double open, double alpha}) revealPose(double beat) {
    final b = beat.clamp(0.0, 1.0);
    return (grow: revealScaleFrom + (1 - revealScaleFrom) * b, open: b, alpha: (b * revealFadeInRate).clamp(0.0, 1.0));
  }
}

/// Draws [urls] (at most two) behind a cover at [cover] (slot space); they
/// leave on the pull-up by breaking into dots with [leaving] (reduced motion: a fade).
///
/// On the page's first open they come out from behind the cover one beat
/// each, 50 ms apart, once the cover's own staged beat has started
/// ([DetailStagedRevealScope]); settled from the start when the cover already
/// was (a returning page never replays it) or there is no reveal above.
class PlaylistSleeves extends StatefulWidget {
  const PlaylistSleeves({
    super.key,
    required this.urls,
    required this.cover,
    required this.leaving,
    required this.corner,
    this.sideRoom,
  });

  final List<String?> urls;
  final Rect cover;
  final double leaving;
  final double corner;

  /// The room beside the cover the fan may take; null = to this box's sides.
  final double? sideRoom;

  @override
  State<PlaylistSleeves> createState() => _PlaylistSleevesState();
}

class _PlaylistSleevesState extends State<PlaylistSleeves> with TickerProviderStateMixin {
  StagedReveal? _reveal;
  Animation<double>? _hero;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final hero = DetailStagedRevealScope.maybeHeroOf(context);
    if (_reveal != null && identical(hero, _hero)) return;
    _hero?.removeListener(_onHero);
    _hero = hero;
    // Not observed: the hero beat animating must not rebuild the sleeves.
    final settled = _reveal?.played ?? (hero == null || hero.value >= stagedBeatLanded);
    _reveal?.dispose();
    _reveal = StagedReveal.once(
      vsync: this,
      delays: [for (var i = 1; i <= _sleeveCount; i++) PlaylistSleeveGeometry.revealStagger * i],
      played: settled,
    );
    if (!settled) {
      hero!.addListener(_onHero);
      _onHero();
    }
  }

  void _onHero() {
    final reveal = _reveal;
    final hero = _hero;
    if (!mounted || reveal == null || hero == null || reveal.played || hero.value <= 0) return;
    hero.removeListener(_onHero);
    reveal.start(reducedMotion: MediaQuery.disableAnimationsOf(context));
  }

  @override
  void dispose() {
    _hero?.removeListener(_onHero);
    _reveal?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final urls = widget.urls;
    final leaving = widget.leaving;
    final cover = widget.cover;
    if (urls.isEmpty || leaving >= 0.999) return const SizedBox.shrink();
    final beats = _reveal!.beats;
    final rtl = Directionality.of(context) == TextDirection.rtl;
    return LayoutBuilder(
      builder: (context, constraints) {
        final sideRoom = widget.sideRoom ?? (constraints.maxWidth - cover.width) / 2;
        final spread = PlaylistSleeveGeometry.spread(sideRoom, cover.width);
        final side = cover.width * PlaylistSleeveGeometry.sideShare;
        final dpr = MediaQuery.devicePixelRatioOf(context);
        final reduce = MediaQuery.disableAnimationsOf(context);
        return DotDissolve(
          progress: () => reduce ? 0 : leaving,
          child: Opacity(
            opacity: reduce ? 1 - leaving : 1,
            child: AnimatedBuilder(
              animation: Listenable.merge([for (final b in beats) b.animation]),
              builder: (context, _) => Stack(
                clipBehavior: Clip.none,
                children: [
                  for (final (i, url) in urls.take(_sleeveCount).indexed.toList().reversed)
                    _sleeve(i, url, beats[i].value, cover, spread, side, dpr, rtl),
                ],
              ),
            ),
          ),
        );
      },
    );
  }

  Widget _sleeve(int i, String? url, double beat, Rect cover, double spread, double side, double dpr, bool rtl) {
    final pose = PlaylistSleeveGeometry.revealPose(beat);
    final grown = side * pose.grow;
    return Positioned.fromRect(
      rect: Rect.fromCenter(
        center: cover.center + PlaylistSleeveGeometry.centerOffset(i, cover.width, spread, rtl: rtl, open: pose.open),
        width: grown,
        height: grown,
      ),
      child: Opacity(
        opacity: pose.alpha,
        child: Transform.rotate(
          angle: (i == 0 ? -1 : 1) * (rtl ? -1 : 1) * PlaylistSleeveGeometry.tiltDegrees * pose.open * math.pi / 180,
          child: MediaArtwork(
            url: url,
            fallbackIcon: YoinSymbols.album,
            radius: widget.corner,
            // Decoded once at the resting side: growing in never re-decodes.
            decodeWidth: (side * dpr).round(),
          ),
        ),
      ),
    );
  }
}

/// At most two sleeves (PlaylistSleeveCount).
const _sleeveCount = 2;

/// The resting hero corner, for the sleeves.
const playlistSleeveCorner = PullUpTokens.heroCorner;

/// The sleeves behind a cover drawn in the page (PlaylistCoverSleeves: a
/// landscape hero, the Medium row, the Wide identity column), filled into
/// the cover's box: whole, on a stage 1.6 × the cover centred on it (so the
/// dissolve holds the fanned sheets), fanned no wider than [sideRoom] less
/// the edge clearance allows.
class PlaylistCoverSleeves extends StatelessWidget {
  const PlaylistCoverSleeves({
    super.key,
    required this.urls,
    required this.side,
    required this.sideRoom,
    this.leaving = 0,
  });

  final List<String?> urls;
  final double side;
  final double sideRoom;
  final double leaving;

  /// The stage's side, × the cover side (PlaylistCoverSleeveStage).
  static const double stage = 1.6;

  @override
  Widget build(BuildContext context) {
    final stageSide = side * stage;
    final inset = (stageSide - side) / 2;
    return OverflowBox(
      minWidth: stageSide,
      maxWidth: stageSide,
      minHeight: stageSide,
      maxHeight: stageSide,
      child: PlaylistSleeves(
        urls: urls,
        cover: Rect.fromLTWH(inset, inset, side, side),
        leaving: leaving,
        corner: playlistSleeveCorner,
        sideRoom: math.max(0, sideRoom - DetailLayoutTokens.sleeveEdgeClearance),
      ),
    );
  }
}
