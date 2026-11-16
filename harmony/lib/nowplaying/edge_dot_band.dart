import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/painting.dart';

// Focus's edge dots, ordered (EdgeDotBand.kt; owner 2026-11-03, prototype
// v7e "整齐细点带"): the dots sit on one fixed hex lattice and only their SIZE
// changes — biggest at the screen edge, shrinking to nothing inward. The
// motion lives in that gradient's inner edge: slow waves travel round the border.
//
//  - tinted by the cover's main colours by angle, very slowly rotating, over a
//    faint wash of the same colours;
//  - an entrance, not a fixture: it bursts out from the cover's side when focus
//    opens (≈0.2 s to widest, dots a little bigger), settles within a second,
//    recedes from 2 s and is gone at 8 s; a song change in focus bursts it
//    again, with a sweep from the left;
//  - each new lyric line sends one soft swell out from the lyrics; pausing
//    slows the waves and lets the band settle thinner.
//
// Also here: the transport keys' tap dots — a ring of lattice dots leaving the
// tapped key's outline (leaning left for PREVIOUS, right for NEXT), gone in
// about a second, on top of the background's own pulse.
//
// Times are the background clock's ms; lengths are logical px (= dp).

/// When the band was (re)born and what last stirred it. Plain fields: written
/// by the background's listeners, read in paint.
class EdgeBandState {
  double bornAtMs = -1e9;
  double linePulseAtMs = -1e9;
  double songPulseAtMs = -1e9;

  /// The waves' own clock (s): runs at [pausedWaveRate]× while paused.
  double phase = 0;
  double _lastDrawMs = double.nan;

  void enter(double nowMs) => bornAtMs = nowMs;

  void songChange(double nowMs) {
    bornAtMs = nowMs;
    songPulseAtMs = nowMs;
  }

  void lineChange(double nowMs) => linePulseAtMs = nowMs;

  /// True while there is still something of the band to draw.
  bool alive(double nowMs) => nowMs - bornAtMs < bandGoneS * 1000;

  /// True while the burst runs: the band wants every frame, not the field's pace.
  bool bursting(double nowMs) => nowMs - bornAtMs < edgeBandFastMs;

  void advance(double nowMs, double playing) {
    final last = _lastDrawMs;
    _lastDrawMs = nowMs;
    if (last.isNaN || nowMs <= last) return;
    final dt = math.min(0.25, (nowMs - last) / 1000);
    phase += dt * (pausedWaveRate + (1 - pausedWaveRate) * playing);
  }
}

/// The band's life curve at [lifeS] seconds: (burst, fade), both 0..1.
(double, double) edgeBandLife(double lifeS) {
  final double burst;
  if (lifeS < 0) {
    burst = 0;
  } else if (lifeS < _burstRiseS) {
    burst = 1 - math.pow(1 - lifeS / _burstRiseS, 3).toDouble();
  } else {
    burst = math.exp(-(lifeS - _burstRiseS) * _burstDecay);
  }
  final double fade;
  if (lifeS < _bandRecedeS) {
    fade = 1;
  } else if (lifeS >= bandGoneS) {
    fade = 0;
  } else {
    fade = 1 - _smooth((lifeS - _bandRecedeS) / (bandGoneS - _bandRecedeS));
  }
  return (burst, fade);
}

/// Dots by colour layer and size bucket, drawn as round points (one raw
/// points call per bucket).
class DotLayers {
  DotLayers(this.layers)
    : _xy = List.generate(layers * _buckets, (_) => Float32List(256)),
      _counts = Int32List(layers * _buckets);

  final int layers;
  final List<Float32List> _xy;
  final Int32List _counts;
  final Paint _paint = Paint()
    ..isAntiAlias = true
    ..strokeCap = StrokeCap.round;

  void reset() => _counts.fillRange(0, _counts.length, 0);

  /// How many dots are queued (tests).
  int get count => _counts.fold(0, (a, b) => a + b);

  void add(int layer, double x, double y, double r, double rMax) {
    final b = ((r / rMax) * _buckets).floor().clamp(0, _buckets - 1);
    final i = layer * _buckets + b;
    final n = _counts[i];
    if (n * 2 + 2 > _xy[i].length) {
      final grown = Float32List(_xy[i].length * 2)..setAll(0, _xy[i]);
      _xy[i] = grown;
    }
    _xy[i][n * 2] = x;
    _xy[i][n * 2 + 1] = y;
    _counts[i] = n + 1;
  }

  void draw(Canvas canvas, double rMax, Color Function(int layer) colorOf) {
    for (var layer = 0; layer < layers; layer++) {
      var any = false;
      for (var b = 0; b < _buckets; b++) {
        if (_counts[layer * _buckets + b] > 0) any = true;
      }
      if (!any) continue;
      _paint.color = colorOf(layer);
      for (var b = 0; b < _buckets; b++) {
        final i = layer * _buckets + b;
        final n = _counts[i];
        if (n == 0) continue;
        _paint.strokeWidth = 2 * rMax * (b + 0.5) / _buckets;
        canvas.drawRawPoints(ui.PointMode.points, Float32List.sublistView(_xy[i], 0, n * 2), _paint);
      }
    }
  }

  static const int _buckets = 8;
}

/// Focus's edge band. [focus] 0..1 (the stage), [playing] 0..1 (a spring),
/// [cover] and [lyrics] in this canvas's px (the band grows out from the
/// cover; a line change swells out from the lyrics), [palette] the cover's
/// four main hues (ARGB).
void paintEdgeBand(
  Canvas canvas,
  Size size, {
  required EdgeBandState state,
  required DotLayers dots,
  required double nowMs,
  required double focus,
  required double playing,
  required Rect cover,
  required Offset? lyrics,
  required List<int> palette,
  required Color primary,
  required bool dark,
}) {
  state.advance(nowMs, playing);
  final (burst, fade) = edgeBandLife((nowMs - state.bornAtMs) / 1000);
  if (focus <= 0.01 || fade <= 0 || palette.isEmpty) return;
  final w = size.width;
  final h = size.height;
  final tt = state.phase;
  final short = math.min(w, h);
  final diag = math.sqrt(w * w + h * h);
  const pitch = bandPitch;
  const rowStep = pitch * 0.866;
  final bandWidth = short * 0.15 * (1 + 1.4 * burst) * (0.25 + 0.75 * fade);
  final rMax = bandDotRadius * (1 + 0.4 * burst);
  final corner = short * 0.09;
  final limit = bandWidth * 1.7;
  final ink = [
    for (final c in palette)
      dark ? Color.lerp(Color(c), const Color(0xFFFFFFFF), 0.35)! : Color.lerp(Color(c), primary, 0.55)!,
  ];

  // The faint wash under the band, so the dots sit on light.
  final washAlpha = (dark ? 0.22 : 0.3) * focus * fade;
  final washDepth = bandWidth * 1.1;
  Color washColor(int i) => Color(palette[i % palette.length]).withValues(alpha: washAlpha);
  const clear = Color(0x00000000);
  canvas
    ..drawRect(
      Rect.fromLTWH(0, 0, w, washDepth),
      Paint()..shader = ui.Gradient.linear(Offset.zero, Offset(0, washDepth), [washColor(1), clear]),
    )
    ..drawRect(
      Rect.fromLTWH(0, h - washDepth, w, washDepth),
      Paint()..shader = ui.Gradient.linear(Offset(0, h - washDepth), Offset(0, h), [clear, washColor(0)]),
    )
    ..drawRect(
      Rect.fromLTWH(0, 0, washDepth, h),
      Paint()..shader = ui.Gradient.linear(Offset.zero, Offset(washDepth, 0), [washColor(2), clear]),
    )
    ..drawRect(
      Rect.fromLTWH(w - washDepth, 0, washDepth, h),
      Paint()..shader = ui.Gradient.linear(Offset(w - washDepth, 0), Offset(w, 0), [clear, washColor(3)]),
    );

  // A new lyric line: one soft swell out from the lyrics.
  final lp = (nowMs - state.linePulseAtMs) / 1000;
  final lineAmp = lp >= 0 && lp <= 1.6 ? 0.38 * math.exp(-lp * 1.6) : 0.0;
  final lineRadius = lp * 620;
  const lineWidth2 = 5000.0;
  final origin = lyrics ?? Offset(w * 0.5, h * 0.6);
  // A new song: a sweep from the left.
  final sp = (nowMs - state.songPulseAtMs) / 1000;
  final songAmp = sp >= 0 && sp <= 1.4 ? 0.55 * math.sin(math.pi * sp / 1.4) : 0.0;
  final songX = sp * w * 0.9;
  const songWidth2 = 9000.0;
  final play = 0.78 + 0.22 * playing;
  final cx = cover.center.dx;
  final cy = cover.center.dy;

  dots.reset();
  var j = 0;
  while (j * rowStep <= h) {
    final y = j * rowStep;
    final off = j.isOdd ? pitch / 2 : 0.0;
    final ey = math.min(y, h - y);
    var i = 0;
    while (i * pitch + off <= w) {
      final x = i * pitch + off;
      final ex = math.min(x, w - x);
      if (ex > limit && ey > limit) {
        // Skip the room's middle in one step.
        i = math.max(i + 1, ((w - limit - off) / pitch).floor());
        continue;
      }
      var e = math.min(ex, ey);
      if (ex < corner && ey < corner) e = corner - _hypot(corner - ex, corner - ey);
      final th = math.atan2(y - h / 2, x - w / 2);
      final wave = (0.5 + 0.36 * math.sin(2 * th - tt * 0.35) + 0.16 * math.sin(5 * th + tt * 0.25 + 1.1)).clamp(
        0.08,
        1.0,
      );
      // Entry: grows out from the cover's side with a small overshoot.
      final ek = (focus * 1.8 - _hypot(x - cx, y - cy) / diag * 0.8).clamp(0.0, 1.0);
      final q = ek - 1;
      final grow = 1 + 2.1 * q * q * q + 1.1 * q * q;
      var swell = 1.0;
      if (lineAmp > 0) {
        final dd = _hypot(x - origin.dx, y - origin.dy) - lineRadius;
        swell += lineAmp * math.exp(-dd * dd / lineWidth2);
      }
      if (songAmp > 0) {
        final dx = x - songX;
        swell += songAmp * math.exp(-dx * dx / songWidth2);
      }
      final reach = math.max(1.0, bandWidth * wave * grow * play * swell);
      var d = 1 - e / reach;
      if (d > 0) {
        d = d * d * (3 - 2 * d);
        final r = rMax * d;
        if (r >= 0.25) {
          final seg = (((th / (2 * math.pi) + 0.5) + tt * 0.004) * bandSegments).floor();
          dots.add(((seg % bandSegments) + bandSegments) % bandSegments, x, y, r, rMax);
        }
      }
      i++;
    }
    j++;
  }
  final alpha = (dark ? 0.55 : 0.45) * focus * math.min(1.0, fade * 1.4);
  dots.draw(canvas, rMax, (k) {
    final u = k / bandSegments * ink.length;
    final a = u.floor() % ink.length;
    final f = u - u.floor();
    return Color.lerp(ink[a], ink[(a + 1) % ink.length], f)!.withValues(alpha: alpha);
  });
}

/// A tapped transport key: its bounds (global px), which way it leans (−1
/// PREVIOUS, +1 NEXT, 0 PLAY), and when (monotonic ms).
final class TapDots {
  const TapDots({required this.boundsRoot, required this.lean, required this.atUptimeMs});
  final Rect boundsRoot;
  final int lean;
  final int atUptimeMs;
}

/// The tap dots at [t] 0..1 of their life: a ring of lattice dots leaves the
/// key's outline ([key], this canvas's px; a stadium), leaning toward [lean].
void paintTapDots(Canvas canvas, DotLayers dots, Rect key, int lean, double t, Color color) {
  if (t <= 0 || t >= 1 || key.isEmpty) return;
  const pitch = _tapPitch;
  const rowStep = pitch * 0.866;
  final ring = _tapReach * (1 - math.pow(1 - t, 3));
  final width = 9 + 16 * t;
  final amp = math.pow(1 - t, 1.4).toDouble();
  const rMax = _tapDotRadius;
  final radius = math.min(key.width, key.height) / 2;
  final cx = key.center.dx;
  final cy = key.center.dy;
  final halfStraight = math.max(0.0, key.width / 2 - radius);
  final halfStraightY = math.max(0.0, key.height / 2 - radius);
  final reach = _tapReach * 1.75 + width * 2 + 4;
  final left = key.left - reach;
  final top = key.top - reach;
  final right = key.right + reach;
  final bottom = key.bottom + reach;
  dots.reset();
  var j = (top / rowStep).floor();
  while (j * rowStep <= bottom) {
    final y = j * rowStep;
    final off = j.isOdd ? pitch / 2 : 0.0;
    var i = ((left - off) / pitch).floor();
    while (i * pitch + off <= right) {
      final x = i * pitch + off;
      i++;
      // Signed distance to the stadium.
      final qx = math.max(0.0, (x - cx).abs() - halfStraight);
      final qy = math.max(0.0, (y - cy).abs() - halfStraightY);
      final sd = _hypot(qx, qy) - radius;
      if (sd <= 0) continue;
      final ang = math.atan2(y - cy, x - cx);
      final leanBy = lean != 0 ? math.max(0.2, 1 + 0.75 * lean * math.cos(ang)) : 1.0;
      final rr = ring * leanBy + 3 * math.sin(5 * ang + 9 * t);
      final g = (sd - rr) / width;
      final d = amp * math.exp(-g * g);
      final r = rMax * d;
      if (r >= 0.25) dots.add(0, x, y, r, rMax);
    }
    j++;
  }
  dots.draw(canvas, rMax, (_) => color);
}

double _hypot(double a, double b) => math.sqrt(a * a + b * b);

double _smooth(double x) {
  final c = x.clamp(0.0, 1.0);
  return c * c * (3 - 2 * c);
}

const double bandPitch = 5.4;
const double bandDotRadius = 1.2;
const int bandSegments = 12;
const double _burstRiseS = 0.22;
const double _burstDecay = 3.2;
const double _bandRecedeS = 2;
const double bandGoneS = 8;
const double pausedWaveRate = 0.2;

/// The edge band runs every frame for its burst, then at the field's pace.
const double edgeBandFastMs = 1200;

const double _tapPitch = 4.6;
const double _tapReach = 72;
const double _tapDotRadius = 1.7;

/// The tap dots' life.
const int tapDotsMs = 1050;
