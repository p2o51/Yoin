/// 唱片刻纹 · Groove — geometry (Android ui/memories/emblem/GrooveGeometry.kt,
/// itself 1:1 from docs/handoff/memories-showcase/groove.js v4 `geom` /
/// `cutRuns` / `layout`). Lengths are dp; angles are degrees, 0 = 3 o'clock,
/// clockwise. One ring per track, outer = track 1, merged when there are more
/// tracks than the size's ring budget. Only rated tracks are cut.
library;

import 'dart:math' as math;

import 'package:flutter/foundation.dart';

import '../palette/memory_palette.dart' show jsRound;

/// Which score the label carries: the album score, the track average, or
/// none (an empty mould).
enum GrooveScoreKind { album, average, none }

/// Where the emblem sits. [cover]: pinned on the artwork (opaque ground,
/// hairline when unrated). [bar]: the diary title's native 48dp copy (rim
/// inset only .5, faint ground when unrated).
enum GrooveSurface { cover, bar }

/// The displayed one-decimal score as tenths ("9.5" → 95), null when [text]
/// is not a one-decimal score.
int? grooveScoreTenths(String? text) {
  if (text == null) return null;
  final parts = text.trim().split('.');
  final whole = int.tryParse(parts[0]);
  if (whole == null) return null;
  if (parts.length == 1) return whole * 10;
  final frac = parts[1];
  final first = frac.isEmpty ? 0 : int.tryParse(frac[0]);
  if (first == null) return null;
  return whole * 10 + first;
}

/// Award tier 0–4 from the displayed one-decimal score (prototype `tierOf`):
/// 0 unrated · 1 below 6.0 · 2 6.0–7.9 · 3 8.0–9.9 · 4 exactly 10.0.
/// Pass the text the label shows (`MemoryScores.text`), so 9.95 → "10.0" → 4.
int grooveTierOf(GrooveScoreKind kind, String? scoreText) {
  if (kind == GrooveScoreKind.none) return 0;
  final tenths = grooveScoreTenths(scoreText);
  if (tenths == null) return 0;
  if (tenths >= 100) return 4;
  if (tenths >= 80) return 3;
  if (tenths >= 60) return 2;
  return 1;
}

/// A run of rated tracks inside one ring: an arc from [startDegrees] clockwise to [endDegrees].
@immutable
final class GrooveRun {
  const GrooveRun(this.startDegrees, this.endDegrees);
  final double startDegrees;
  final double endDegrees;
  double get sweepDegrees => endDegrees - startDegrees;

  @override
  bool operator ==(Object other) =>
      other is GrooveRun && other.startDegrees == startDegrees && other.endDegrees == endDegrees;
  @override
  int get hashCode => Object.hash(startDegrees, endDegrees);
  @override
  String toString() => 'GrooveRun($startDegrees → $endDegrees)';
}

/// One ring: tracks [firstTrack, endTrack), centred at [radius].
@immutable
final class GrooveRing {
  const GrooveRing({
    required this.index,
    required this.firstTrack,
    required this.endTrack,
    required this.radius,
    required this.runs,
  });
  final int index;
  final int firstTrack;
  final int endTrack;
  final double radius;
  final List<GrooveRun> runs;
}

/// Size thresholds of the ring budget and the simplified ("tiny") drawing.
abstract final class GrooveSizes {
  static const double tinyBelow = 60;
  static const double bigFrom = 110;
}

@immutable
final class GrooveGeometry {
  const GrooveGeometry({
    required this.size,
    required this.kind,
    required this.surface,
    required this.tiny,
    required this.big,
    required this.rimRadius,
    required this.labelRadius,
    required this.grooveOuter,
    required this.grooveInner,
    required this.pitch,
    required this.rings,
    required this.dotDiameter,
    required this.cutWidth,
    required this.rimWidth,
    required this.rim2At,
  });

  final double size;
  final GrooveScoreKind kind;
  final GrooveSurface surface;
  final bool tiny;
  final bool big;
  final double rimRadius;
  final double labelRadius;
  final double grooveOuter;
  final double grooveInner;
  final double pitch;
  final List<GrooveRing> rings;
  final double dotDiameter;
  final double cutWidth;
  final double rimWidth;
  final double rim2At;

  double get radius => size / 2;

  /// Rings that carry at least one cut, outer first; a ring's index here is its award order.
  List<int> get ratedRings => [
    for (final r in rings)
      if (r.runs.isNotEmpty) r.index,
  ];

  /// The award order of [ring] (unrated rings fall back to 0).
  int ringOrder(int ring) => math.max(0, ratedRings.indexOf(ring));

  /// Dotted pre-cut lattice: dots per ring.
  int dotCount(GrooveRing ring) {
    final circumference = 2 * math.pi * ring.radius;
    final target = math.max(tiny ? 2.3 : 2.6, dotDiameter * 2.3);
    return math.max(6, jsRound(circumference / target).toInt());
  }

  /// The faint band under a cut, a hair wider than the pitch.
  double get underWidth => pitch + 0.05;
  double get rim2Width => math.max(0.75, size * 0.008);
  double get flareWidth => math.max(1.6, size * 0.026);
  double get burstWidth => math.max(1.0, size * 0.014);
  bool get hasLitLayers => kind != GrooveScoreKind.none && !tiny;

  // label
  double get albumLabelRingStroke => size * 0.007;
  double get averageLabelStroke => math.max(1.2, size * 0.017);
  double get unratedLabelStroke => tiny ? 1.4 : math.max(1.1, size * 0.013);
  bool get showsSpindle => kind == GrooveScoreKind.none && size < 64;
  double get spindleRadius => math.max(1.6, size * 0.045);

  // type
  double get scoreFontSize {
    if (big) return size * 0.27;
    if (size >= 64) return math.max(size * 0.29, 21.5);
    return math.max(15.5, size * 0.36);
  }

  /// "Avg." under a track average (≥ 88dp); an album score has no caption.
  bool get showsCaption => kind == GrooveScoreKind.average && size >= 88;
  double get captionFontSize => math.max(10.0, size * (big ? 0.1 : 0.105));
  bool get showsUnratedWord => kind == GrooveScoreKind.none && size >= 64;
  double get unratedFontSize => size < 88 ? 9.5 : math.max(10.0, size * 0.125);
  double get unratedWidthAxis => size < 88 ? 76 : 92;
}

/// The ring budget of a size: ≥ 110 → 12, ≥ 88 → 8, ≥ 64 → 6, else 3.
int grooveRingCap(double size) => size >= GrooveSizes.bigFrom
    ? 12
    : size >= 88
    ? 8
    : size >= 64
    ? 6
    : 3;

/// Prototype `geom(m, s, kind, on)`.
GrooveGeometry grooveGeometry(List<bool> trackRated, double size, GrooveScoreKind kind, GrooveSurface surface) {
  final s = size;
  final r = s / 2;
  final tiny = s < GrooveSizes.tinyBelow;
  final big = s >= GrooveSizes.bigFrom;
  final rimR = r - (surface == GrooveSurface.bar ? 0.5 : 1.0);
  // the unrated mould holds a word, not a score, so it opens a little wider below 88
  final labelR =
      s * (tiny ? 0.345 : (big ? 0.3 : 0.305)) * (kind == GrooveScoreKind.none && s >= 60 && s < 88 ? 1.07 : 1.0);
  final rimW = switch (kind) {
    GrooveScoreKind.album => math.max(1.1, s * 0.019),
    GrooveScoreKind.average => math.max(0.9, s * 0.012),
    GrooveScoreKind.none => 0.0,
  };
  final rim2At = rimW + math.max(0.9, s * 0.011);
  final leadIn = tiny ? 0.9 : math.max(1.4, s * 0.016);
  final gOut = rimR - (kind == GrooveScoreKind.album && !tiny ? rim2At + 0.4 : rimW) - leadIn;
  final gIn = labelR + math.max(1.2, s * 0.017);
  final band = gOut - gIn;
  final cap = grooveRingCap(s);
  final minPitch = tiny ? 1.3 : 1.72;
  final n = trackRated.length;
  final ringCount = math.max(1, math.min(math.min(cap, n), (band / minPitch).floor()));
  final pitch = band / ringCount;
  final rings = List.generate(ringCount, (i) {
    final a = jsRound(i * n / ringCount).toInt();
    final b = jsRound((i + 1) * n / ringCount).toInt();
    return GrooveRing(
      index: i,
      firstTrack: a,
      endTrack: b,
      radius: gOut - pitch * (i + 0.5),
      runs: kind == GrooveScoreKind.none ? const [] : grooveCutRuns(trackRated.sublist(a, b)),
    );
  });
  final dw = math.min(pitch * 0.58, tiny ? 1.0 : 1.2);
  final sw = math.min(pitch * 0.64, dw + 0.25);
  return GrooveGeometry(
    size: s,
    kind: kind,
    surface: surface,
    tiny: tiny,
    big: big,
    rimRadius: rimR,
    labelRadius: labelR,
    grooveOuter: gOut,
    grooveInner: gIn,
    pitch: pitch,
    rings: rings,
    dotDiameter: dw,
    cutWidth: sw,
    rimWidth: rimW,
    rim2At: rim2At,
  );
}

/// Prototype `cutRuns(ring)`: runs of rated tracks inside one ring. Each
/// track gets an equal share of the circle, starting at 12 o'clock.
List<GrooveRun> grooveCutRuns(List<bool> rated) {
  final len = rated.length;
  final runs = <GrooveRun>[];
  var start = -1;
  for (var j = 0; j <= len; j++) {
    final isRated = j < len && rated[j];
    if (isRated && start < 0) start = j;
    if (!isRated && start >= 0) {
      runs.add(GrooveRun(-90.0 + start / len * 360.0, -90.0 + j / len * 360.0));
      start = -1;
    }
  }
  return runs;
}

/// Prototype `layout(m, s)`: what the award needs (always measured on a cover).
@immutable
final class GrooveLayout {
  const GrooveLayout({required this.ringCount, required this.ratedRingCount, required this.tiny});
  final int ringCount;
  final int ratedRingCount;
  final bool tiny;
}

GrooveLayout grooveLayout(List<bool> trackRated, GrooveScoreKind kind, double size) {
  final g = grooveGeometry(trackRated, size, kind, GrooveSurface.cover);
  final rated = kind == GrooveScoreKind.none ? 0 : g.ratedRings.length;
  return GrooveLayout(ringCount: g.rings.length, ratedRingCount: rated, tiny: g.tiny);
}
