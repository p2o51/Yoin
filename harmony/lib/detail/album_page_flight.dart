/// The album page's page-0 → page-1 hand-off (Android AlbumPageFlight.kt,
/// owner 2026-10-29): as the pager is dragged to the scrapbook, the hero cover
/// travels with it to its place on the scrapbook page — from the hero at rest
/// or, pulled up, from the stamp (owner 2026-11-06) — instead of sliding away
/// with page 0 and arriving again. Last Play stays put on each page (only the
/// art moves).
///
/// The pager position is the only driver: the finger moves the flight 1:1 and
/// the pager's own spring settles it. The scrapbook reports where its cover
/// rests (relative to its own page, so the value holds still while the pager
/// moves) and hides that cover until the flight lands; the flying cover is
/// read from it each frame. The scrapbook's score emblem rides above the
/// flying cover through [emblemLink], as it sits above the page's own cover.
///
/// Reduced motion: nothing flies — page 0 slides away with its cover and the
/// scrapbook keeps its own showing.
library;

import 'dart:ui' show lerpDouble;

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';

import '../theme/tokens.dart';

/// The flight's pager progress at which it has landed: the page's own cover takes over.
const double albumPageFlightLanded = 0.999;

/// One album page's flight state, shared by the reshape scaffold (which flies
/// the cover) and the scrapbook page (which hosts its target).
class AlbumPageFlight {
  AlbumPageFlight({required this.page, this.flies = true});

  /// The pager position, 0 = the hero page, 1 = the scrapbook.
  final ValueListenable<double> page;

  /// Whether the cover flies at all (false under reduced motion); the scaffold sets it each build.
  bool flies;

  /// Page 1's root box, at the pager's page origin.
  final GlobalKey pageKey = GlobalKey(debugLabel: 'albumPageFlight.page');

  /// The scrapbook cover's box, untransformed (its tilt and pager parallax are paint-only, inside it).
  final GlobalKey coverKey = GlobalKey(debugLabel: 'albumPageFlight.cover');

  /// The scrapbook emblem's paint position, for the copy drawn over the flying cover.
  final LayerLink emblemLink = LayerLink();

  /// The scrapbook cover's resting tilt, degrees.
  double coverTilt = 0;

  /// The scrapbook cover's corner.
  double coverCorner = ShapeTokens.hero;

  /// The scrapbook's emblem as it builds it (size included): drawn over the
  /// flying cover while it flies, at [emblemLink].
  Widget? emblem;

  /// Where page 1's origin rests in the scaffold's coordinates (its pager's top-start).
  Offset pageOrigin = Offset.zero;

  /// Pager progress, 0..1.
  double get t => page.value.clamp(0.0, 1.0);

  /// The scrapbook cover's resting rect in the scaffold's coordinates, or null
  /// while it isn't laid out (the page not built, the paper still coming in)
  /// or isn't wholly in its page (scrolled under the header): then nothing
  /// flies and page 0's cover slides away as it would.
  Rect? coverTarget() {
    final pageBox = pageKey.currentContext?.findRenderObject();
    final coverBox = coverKey.currentContext?.findRenderObject();
    if (pageBox is! RenderBox || coverBox is! RenderBox) return null;
    if (!pageBox.attached || !coverBox.attached || !pageBox.hasSize || !coverBox.hasSize) return null;
    final Offset topLeft;
    try {
      topLeft = coverBox.localToGlobal(Offset.zero, ancestor: pageBox);
    } catch (_) {
      // Not under the page (mid-rebuild): no target this frame.
      return null;
    }
    final inPage = topLeft & coverBox.size;
    if (inPage.top < 0 || inPage.bottom > pageBox.size.height) return null;
    return inPage.shift(pageOrigin);
  }

  /// Whether the cover is flying (or has flown) to the scrapbook this frame.
  bool coverFlying() => flies && coverTarget() != null;

  /// Whether the scrapbook hides its own cover and emblem: the flying ones stand in until landed.
  bool pageCoverHidden() => coverFlying() && t < albumPageFlightLanded;
}

/// Gives the scrapbook page its album page's flight.
class AlbumPageFlightScope extends InheritedWidget {
  const AlbumPageFlightScope({super.key, required this.flight, required super.child});

  final AlbumPageFlight? flight;

  static AlbumPageFlight? maybeOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<AlbumPageFlightScope>()?.flight;

  @override
  bool updateShouldNotify(AlbumPageFlightScope old) => old.flight != flight;
}

/// Page 0's hero text while its cover flies (albumHeroPageFade): gone a
/// third of the way in, so it never slides across the cover on its way to
/// page 1. 1 when nothing flies.
double albumHeroPageFade(double t, {required bool flying}) =>
    flying ? (1 - t * DetailTokens.albumFlightTextFade).clamp(0.0, 1.0) : 1;

/// [a] → [b] at [t], edge by edge.
Rect lerpRect(Rect a, Rect b, double t) => Rect.fromLTRB(
  lerpDouble(a.left, b.left, t)!,
  lerpDouble(a.top, b.top, t)!,
  lerpDouble(a.right, b.right, t)!,
  lerpDouble(a.bottom, b.bottom, t)!,
);

/// The flying cover this frame: [from] (where the pull-up holds it, before
/// any page shift) toward [target] at [t], tilting into the page's angle.
/// Without a target, or before the pager moves, it slides with page 0 by [shift].
@immutable
final class AlbumFlightPose {
  const AlbumFlightPose({required this.rect, this.tiltDegrees = 0, this.landed = false});

  final Rect rect;
  final double tiltDegrees;

  /// Landed: drawn no more (the page's own cover shows).
  final bool landed;

  static AlbumFlightPose of({
    required Rect from,
    required Rect? target,
    required double t,
    required double shift,
    double targetTilt = 0,
  }) {
    if (target == null || t <= 0) return AlbumFlightPose(rect: from.shift(Offset(shift, 0)));
    return AlbumFlightPose(
      rect: lerpRect(from, target, t),
      tiltDegrees: targetTilt * t,
      landed: t >= albumPageFlightLanded,
    );
  }
}
