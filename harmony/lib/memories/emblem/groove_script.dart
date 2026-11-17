/// 唱片刻纹 · the four award tiers (Android GrooveScript.kt, 1:1 from groove.js
/// v4 `TIERS` / `tierOf` / `script()`). Every channel is a pure function of t
/// (seconds) built from closed-form springs (dampingRatio, stiffness, mass 1),
/// and every haptic beat is found on the same curve that draws the picture.
/// Change a spring and the beats move.
library;

import 'dart:collection';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';

import '../palette/memory_palette.dart' show jsRound;
import 'groove_geometry.dart';

/// Closed-form springs and the prototype's beat-finding scans.
abstract final class GrooveMath {
  /// Where a spring (dampingRatio [z], stiffness [k], mass 1) going from [x0]
  /// to [x1] with initial velocity [v0] is at [t] seconds.
  static double springAt(double z, double k, double t, double x0, double x1, [double v0 = 0]) {
    if (t <= 0) return x0;
    final w0 = math.sqrt(k);
    final d0 = x0 - x1;
    final double d;
    if (z < 1) {
      final wd = w0 * math.sqrt(1 - z * z);
      d = math.exp(-z * w0 * t) * (d0 * math.cos(wd * t) + ((v0 + z * w0 * d0) / wd) * math.sin(wd * t));
    } else {
      d = math.exp(-w0 * t) * (d0 + (v0 + w0 * d0) * t);
    }
    return x1 + d;
  }

  /// An upper bound of |springAt(t) − x1| from [t] on.
  static double springEnvelope(double z, double k, double t, double x0, double x1, [double v0 = 0]) {
    final w0 = math.sqrt(k);
    final d0 = x0 - x1;
    if (z < 1) {
      final wd = w0 * math.sqrt(1 - z * z);
      return math.exp(-z * w0 * t) * (d0.abs() + ((v0 + z * w0 * d0) / wd).abs());
    }
    return math.exp(-w0 * t) * (d0.abs() + (v0 + w0 * d0).abs() * t);
  }

  static double cl(double v) => v.clamp(0.0, 1.0);

  static double gauss(double t, double c, double w) {
    final x = (t - c) / w;
    return math.exp(-(x * x));
  }

  /// An effects-spring flash: a stiff critically-damped rise, then an exponential fade.
  static double pulse(double t, double at, double hold) =>
      t < at ? 0 : cl(springAt(1, 2400, t - at, 0, 1)) * math.exp(-math.max(0.0, t - at - 0.04) / hold);

  /// The prototype's 1ms scans, including its floating-point step accumulation.
  static double argmax(double a, double b, double Function(double) fn) {
    var best = double.negativeInfinity;
    var bt = a;
    var t = a;
    while (t <= b) {
      final v = fn(t);
      if (v > best) {
        best = v;
        bt = t;
      }
      t += 0.001;
    }
    return bt;
  }

  static double argmin(double a, double b, double Function(double) fn) => argmax(a, b, (t) => -fn(t));

  static double? firstT(double a, double b, bool Function(double) pred) {
    var t = a;
    while (t <= b) {
      if (pred(t)) return t;
      t += 0.001;
    }
    return null;
  }
}

/// JS `(+v).toFixed(2)` as a number (half-up on the decimal value).
double _toFixed2(double v) => (v * 100).roundToDouble() / 100;

/// The haptic primitives the beat plan is written in (Android
/// `VibrationEffect.Composition` primitives), each with its named-haptic stand-in.
enum GroovePrimitive {
  tick(GrooveFallback.tick),
  lowTick(GrooveFallback.lightTick),
  click(GrooveFallback.click),
  thud(GrooveFallback.confirm),
  spin(GrooveFallback.tick),

  /// No named-haptic stand-in: skipped by the fallback.
  quickRise(null);

  const GroovePrimitive(this.defaultFallback);
  final GrooveFallback? defaultFallback;
}

/// The named haptic a beat falls back to (`YoinHaptics.tick / lightTick / click / confirm`).
enum GrooveFallback { tick, lightTick, click, confirm }

/// One haptic beat at [atMs] after the award's first frame; [scale] is the primitive's 0–1 strength.
@immutable
final class GrooveBeat {
  const GrooveBeat({
    required this.atMs,
    required this.primitive,
    required this.scale,
    required this.fallback,
    required this.what,
  });

  final int atMs;
  final GroovePrimitive primitive;
  final double scale;
  final GrooveFallback? fallback;
  final String what;

  GrooveBeat copyWith({int? atMs, double? scale, String? what}) => GrooveBeat(
    atMs: atMs ?? this.atMs,
    primitive: primitive,
    scale: scale ?? this.scale,
    fallback: fallback,
    what: what ?? this.what,
  );

  @override
  String toString() => 'GrooveBeat($atMs ms ${primitive.name} ×$scale · $what)';
}

/// One tier's choreography (prototype `script(tier, inf, kind)`). [order] is a
/// cut's ring order among the rated rings (outer first), [ring] a ring index.
class GrooveScript {
  GrooveScript._(this.tier, this.kind, this.ringCount, this.ratedRingCount)
    : _a = kind == GrooveScoreKind.album ? 1.0 : 0.7,
      durationS = switch (tier) {
        1 => 0.62,
        2 => 1.0,
        3 => 1.3,
        _ => 1.6,
      },
      _labAt = tier == 4 ? 0.74 : 0.5 {
    if (tier == 2) {
      final cross = GrooveMath.firstT(0, durationS, (t) => disc(t) >= 0);
      _t2Cross = (cross == null || cross == 0) ? 0.43 : cross;
      _t2Peak = GrooveMath.argmax(_t2Cross, durationS, disc);
    }
    beats = _buildBeats();
  }

  final int tier;
  final GrooveScoreKind kind;
  final int ringCount;
  final int ratedRingCount;
  final double _a;

  /// Duration in seconds.
  final double durationS;

  /// Tiers 3 / 4: the label lifts until here, then presses down on its own spring.
  final double _labAt;
  double _t2Cross = 0;
  double _t2Peak = 0;

  /// Tier 4: the moment the flat accent reaches the rim.
  static const double _tf = 1.02;

  /// Beats closer than this blur into one buzz; the stronger one is kept.
  static const int mergeWindowMs = 45;

  /// The raw beat plan (prototype `hapticPlan`), merged so no two beats are closer than 45ms.
  late final List<GrooveBeat> beats;

  /// Disc (and album label) rotation, degrees; 0 at rest.
  double disc(double t) => switch (tier) {
    1 => GrooveMath.springAt(1, 90, t, -16, 0),
    2 => GrooveMath.springAt(0.74, 70, t, -330, 0),
    3 => GrooveMath.springAt(0.8, 42, t, -360, 0, 1300),
    _ => GrooveMath.springAt(0.82, 44, t, -720, 0, 2000),
  };

  double _lift(double t) =>
      tier == 4 ? GrooveMath.springAt(1, 200, t, 1, 1 + 0.18 * _a) : GrooveMath.springAt(1, 220, t, 1, 1 + 0.12 * _a);

  /// Label scale; 1 at rest.
  double label(double t) => switch (tier) {
    1 => GrooveMath.springAt(0.9, 300, t, 1 + 0.06 * _a, 1),
    2 => t < _t2Peak ? 1 : GrooveMath.springAt(0.5, 420, t - _t2Peak, 1, 1, -1.1 * _a),
    3 => t < _labAt ? _lift(t) : GrooveMath.springAt(0.55, 520, t - _labAt, _lift(_labAt), 1),
    _ => t < _labAt ? _lift(t) : GrooveMath.springAt(0.5, 600, t - _labAt, _lift(_labAt), 1),
  };

  /// How much of a cut is drawn, 0–1 (clockwise from its start).
  double cut(int order, double t) => switch (tier) {
    1 => GrooveMath.cl(GrooveMath.springAt(1, 140, t, 0, 1)),
    2 => GrooveMath.cl(GrooveMath.springAt(1, 150, t - (0.05 + order * 0.05), 0, 1)),
    3 => GrooveMath.cl(GrooveMath.springAt(1, 150, t - (0.04 + order * 0.06), 0, 1)),
    _ => GrooveMath.cl(GrooveMath.springAt(1, 150, t - (0.04 + order * 0.045), 0, 1)),
  };

  double _t3At(int order) => 0.72 + order * 0.08;
  double _t4At(int ring) => 0.28 + ring * 0.055;

  /// Opacity of a cut's flat "lit" colour.
  double cutGlow(int order, double t) => switch (tier) {
    2 => 0.85 * GrooveMath.gauss(t, _t2Peak + 0.01, 0.085),
    3 => 0.95 * GrooveMath.pulse(t, _t3At(order), 0.2),
    4 => 0.7 * GrooveMath.pulse(t, _tf, 0.26),
    _ => 0,
  };

  /// Opacity of a whole ring lit in turn, outer → inner (tier 4 only).
  double ringGlow(int ring, double t) => tier == 4 ? 0.9 * GrooveMath.pulse(t, _t4At(ring), 0.2) : 0;

  /// The 10.0 rim pulse (tier 4 only).
  double flare(double t) => tier == 4 ? GrooveMath.pulse(t, _tf - 0.04, 0.3) : 0;

  /// The thin ring released from the rim (tier 4 only): scale and opacity.
  double burstScale(double t) =>
      tier == 4 ? 1 + 0.2 * GrooveMath.cl(GrooveMath.springAt(1, 90, t - (_tf - 0.04), 0, 1)) : 1;

  double burstAlpha(double t) => tier == 4 ? 0.9 * GrooveMath.pulse(t, _tf - 0.04, 0.2) : 0;

  List<GrooveBeat> _buildBeats() {
    final out = <GrooveBeat>[];
    void beat(
      double at,
      GroovePrimitive p,
      double scale,
      String what, {
      GrooveFallback? fallback,
      bool noFallback = false,
    }) {
      out.add(
        GrooveBeat(
          atMs: math.max(0.0, jsRound(at * 1000)).toInt(),
          primitive: p,
          scale: _toFixed2(scale),
          fallback: noFallback ? null : (fallback ?? p.defaultFallback),
          what: what,
        ),
      );
    }

    switch (tier) {
      case 1:
        final s = GrooveMath.firstT(0.05, durationS, (t) => label(t) <= 1);
        beat((s == null || s == 0) ? 0.36 : s, GroovePrimitive.lowTick, 0.5, 'label settles');
      case 2:
        final fastest = GrooveMath.argmax(0, _t2Cross, (t) => disc(t + 0.001) - disc(t));
        beat(fastest, GroovePrimitive.tick, 0.5, 'spin-up at its fastest');
        beat(_t2Peak, GroovePrimitive.click, 0.75, 'overshoot peak: cuts lit, label nods');
      case 3:
        final contact = GrooveMath.argmin(_labAt, _labAt + 0.3, label);
        beat(0, GroovePrimitive.spin, 0.5, 'flick: one full turn');
        beat(contact, GroovePrimitive.click, 1.0, 'label at its lowest', fallback: GrooveFallback.confirm);
        final n = ratedRingCount;
        final stride = math.max(1, (n / 4).ceil());
        for (var j = 0; j < n; j += stride) {
          final at = _t3At(j);
          beat(
            GrooveMath.argmax(at, at + 0.2, (t) => GrooveMath.pulse(t, at, 0.2)),
            GroovePrimitive.lowTick,
            0.35,
            'rated ring ${j + 1} lit',
          );
        }
      default:
        final contact = GrooveMath.argmin(_labAt, _labAt + 0.3, label);
        final flarePeak = GrooveMath.argmax(_tf - 0.04, _tf + 0.2, flare);
        beat(0, GroovePrimitive.spin, 0.8, 'flick: two turns');
        final n = ringCount;
        final stride = math.max(1, (n / 8).ceil());
        for (var i = 0; i < n; i += stride) {
          final at = _t4At(i);
          beat(
            GrooveMath.argmax(at, at + 0.2, (t) => GrooveMath.pulse(t, at, 0.2)),
            GroovePrimitive.tick,
            0.45,
            'ring ${i + 1} lit',
            fallback: i == 0 ? GrooveFallback.lightTick : null,
            noFallback: i != 0,
          );
        }
        beat(contact - 0.01, GroovePrimitive.thud, 1.0, 'label stamps down', fallback: GrooveFallback.confirm);
        beat(_tf - 0.04, GroovePrimitive.quickRise, 0.7, 'rim turns accent', noFallback: true);
        beat(flarePeak, GroovePrimitive.click, 1.0, 'rim fullest, ring released', fallback: GrooveFallback.click);
    }
    return mergeCloseBeats(out);
  }
}

/// Two beats closer than [GrooveScript.mergeWindowMs] blur into one: sorted by time, the stronger one is kept.
List<GrooveBeat> mergeCloseBeats(List<GrooveBeat> beats) {
  final sorted = [...beats]..sort((a, b) => a.atMs.compareTo(b.atMs));
  final kept = <GrooveBeat>[];
  for (final b in sorted) {
    if (kept.isNotEmpty && b.atMs - kept.last.atMs < GrooveScript.mergeWindowMs) {
      if (b.scale > kept.last.scale) kept[kept.length - 1] = b;
    } else {
      kept.add(b);
    }
  }
  return kept;
}

final LinkedHashMap<(int, GrooveScoreKind, int, int), GrooveScript> _scriptCache = LinkedHashMap();

/// The script of a tier as drawn at [size], cached by (tier, kind, ring layout). Null when tier 0.
GrooveScript? grooveScriptFor({
  required int tier,
  required GrooveScoreKind kind,
  required List<bool> trackRated,
  required double size,
}) {
  if (tier == 0) return null;
  final layout = grooveLayout(trackRated, kind, size);
  final key = (tier, kind, layout.ringCount, layout.ratedRingCount);
  final hit = _scriptCache.remove(key);
  final script = hit ?? GrooveScript._(tier, kind, layout.ringCount, layout.ratedRingCount);
  _scriptCache[key] = script;
  while (_scriptCache.length > 32) {
    _scriptCache.remove(_scriptCache.keys.first);
  }
  return script;
}
