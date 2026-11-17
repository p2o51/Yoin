/// 唱片刻纹 · award haptics (Android GrooveHaptics.kt). The beat plan is
/// groove.js `hapticPlan` thinned by twostate4's `awardBeats`: tier 4 keeps at
/// most 4 ring ticks (≥ 90ms apart, ×.35) and drops QUICK_RISE, so no tier
/// exceeds 7 beats; a replay keeps only the climax; the diary's 48dp nod is
/// the tier's strongest beat at half strength; reduced motion keeps only the
/// strongest beat, on the reveal's last frame.
///
/// Playing it follows Android's two routes. Where the motor plays custom
/// patterns ([HapticsHost.supportsPatterns]: HarmonyOS HD haptics), the whole
/// plan is ONE `VibratorPattern` started on the award's first frame
/// ([grooveHapticPulses], Android's `VibrationEffect.Composition`). Otherwise
/// each beat plays its named stand-in through [YoinHaptics] at its time
/// (Android's View fallback); QUICK_RISE and tier 4's later ring ticks have no
/// stand-in and are skipped there.
library;

import 'dart:math' as math;

import '../../nowplaying/haptics.dart';
import '../../platform/haptics/haptics_host.dart';
import '../palette/memory_palette.dart' show jsRound;
import 'groove_script.dart';

/// Which award a beat plan is for.
enum GrooveBeatMode {
  /// The card's first award.
  first,

  /// A replay on an awarded card (grooves stay cut).
  replay,

  /// The diary emblem's first nod, out of its waiting pose.
  nod,

  /// A replayed nod: from rest, dipping first.
  nodAgain,
}

/// Spring constants of the award flow (twostate4 `SPR.settle`, `EFFECTS`, `NOD`, `REVEAL`).
abstract final class GrooveSprings {
  /// Interrupted / unfinished award back to rest.
  static const settle = (z: 0.9, k: 520.0);

  /// Reduced-motion settle: alpha only, no overshoot.
  static const effects = (z: 1.0, k: 200.0);

  /// The diary emblem's nod (scale .6 → 1, tier 2+ also −90° → 0°).
  static const nod = (z: 0.7, k: 420.0);

  /// The reduced award's alpha reveal (97.7% at 200ms).
  static const reveal = (z: 1.0, k: 800.0);

  /// A replay's label joining the script from rest (~70ms).
  static const replayJoin = (z: 1.0, k: 1600.0);

  /// The reduced replay's label dip.
  static const dip = (z: 1.0, k: 2400.0);
}

/// Beat times that come from springs outside the tier scripts.
abstract final class GrooveBeatTimes {
  /// The nod's landing frame: scale .6 → 1 on [GrooveSprings.nod] first reaches 1.
  static final int nodMs = () {
    for (var t = 0.0; t < 0.6; t += 0.001) {
      if (GrooveMath.springAt(GrooveSprings.nod.z, GrooveSprings.nod.k, t, 0.6, 1) >= 1) {
        return jsRound(t * 1000).toInt();
      }
    }
    return 160;
  }();

  /// A replayed nod starts at rest and dips (v0 −7): its beat lands where the scale comes back up through 1.
  static final int nodAgainMs = () {
    for (var t = 0.02; t < 0.6; t += 0.001) {
      if (GrooveMath.springAt(GrooveSprings.nod.z, GrooveSprings.nod.k, t, 1, 1, -7) >= 1) {
        return jsRound(t * 1000).toInt();
      }
    }
    return 215;
  }();

  /// Reduced motion: the award develops by alpha in ~200ms and its one beat lands as it finishes.
  static const int revealMs = 200;

  /// Tier 4's ring ticks: at least this far apart.
  static const int ringTickSpacingMs = 90;
}

/// The tier's strongest beat (ties go to THUD).
GrooveBeat strongestBeat(List<GrooveBeat> plan) =>
    plan.reduce((a, b) => (b.scale > a.scale || (b.scale == a.scale && b.primitive == GroovePrimitive.thud)) ? b : a);

/// The beats of one award (prototype `awardBeats(m, mode, size)` + the nod's
/// landing times). [tiny]: the emblem is drawn simplified (< 60dp, no lit
/// layers). Empty for no script (unrated).
List<GrooveBeat> grooveAwardBeats(
  GrooveScript? script,
  GrooveBeatMode mode, {
  required bool tiny,
  bool reducedMotion = false,
}) {
  if (script == null) return const [];
  var plan = script.beats;
  if (script.tier == 4) {
    final n = tiny ? 0 : script.ringCount;
    final stride = math.max(1, (n / 4).ceil());
    final keep = plan
        .where((b) => b.primitive != GroovePrimitive.tick && b.primitive != GroovePrimitive.quickRise)
        .toList();
    var last = -1000000000;
    for (var i = 0; i < n; i += stride) {
      var best = -1.0;
      var at = 0.0;
      for (var x = 0.0; x <= script.durationS; x += 0.001) {
        final v = script.ringGlow(i, x);
        if (v > best) {
          best = v;
          at = x;
        }
      }
      final ms = jsRound(at * 1000).toInt();
      if (ms - last >= GrooveBeatTimes.ringTickSpacingMs) {
        keep.add(
          GrooveBeat(
            atMs: ms,
            primitive: GroovePrimitive.tick,
            scale: 0.35,
            fallback: i == 0 ? GrooveFallback.lightTick : null,
            what: 'ring ${i + 1} lit',
          ),
        );
        last = ms;
      }
    }
    plan = keep..sort((a, b) => a.atMs.compareTo(b.atMs));
  }
  if (mode == GrooveBeatMode.nod || mode == GrooveBeatMode.nodAgain) {
    final s = strongestBeat(plan);
    final at = reducedMotion
        ? GrooveBeatTimes.revealMs
        : (mode == GrooveBeatMode.nodAgain ? GrooveBeatTimes.nodAgainMs : GrooveBeatTimes.nodMs);
    return [s.copyWith(scale: ((s.scale * 0.5) * 100).roundToDouble() / 100, atMs: at, what: 'diary emblem nod')];
  }
  if (reducedMotion) {
    return [strongestBeat(plan).copyWith(atMs: GrooveBeatTimes.revealMs, what: 'reveal finished')];
  }
  if (mode == GrooveBeatMode.replay) {
    final keep = switch (script.tier) {
      4 => {GroovePrimitive.thud, GroovePrimitive.click},
      1 => {GroovePrimitive.lowTick},
      _ => {GroovePrimitive.click},
    };
    plan = plan.where((b) => keep.contains(b.primitive)).toList();
  }
  return plan;
}

/// Receives each beat when it is due on the fallback route. The default plays
/// the beat's named stand-in ([playGrooveBeatFallback]).
typedef GrooveBeatSink = void Function(GrooveBeat beat);

/// Plays [beat] through [YoinHaptics] (silent for beats with no stand-in).
void playGrooveBeatFallback(GrooveBeat beat) {
  switch (beat.fallback) {
    case GrooveFallback.tick:
      YoinHaptics.tick();
    case GrooveFallback.lightTick:
      YoinHaptics.lightTick();
    case GrooveFallback.click:
      YoinHaptics.click();
    case GrooveFallback.confirm:
      YoinHaptics.confirm();
    case null:
      break;
  }
}

/// The beats the named-haptic fallback actually plays (QUICK_RISE and tier 4's later ring ticks are skipped).
List<GrooveBeat> fallbackBeats(List<GrooveBeat> beats) => [
  for (final b in beats)
    if (b.fallback != null) b,
];

/// How each primitive feels as a pattern event: its sharpness (0 soft … 1
/// crisp), how long it lasts on the motor, and whether it swells. Transients
/// last HarmonyOS's default 48ms.
abstract final class GroovePulseShapes {
  static const int transientMs = 48;
  static const int spinMs = 70;
  static const int quickRiseMs = 90;

  static ({double frequency, int durationMs, bool rise}) of(GroovePrimitive p) => switch (p) {
    GroovePrimitive.tick => (frequency: 0.85, durationMs: 0, rise: false),
    GroovePrimitive.lowTick => (frequency: 0.3, durationMs: 0, rise: false),
    GroovePrimitive.click => (frequency: 0.6, durationMs: 0, rise: false),
    GroovePrimitive.thud => (frequency: 0.1, durationMs: 0, rise: false),
    GroovePrimitive.spin => (frequency: 0.5, durationMs: spinMs, rise: false),
    GroovePrimitive.quickRise => (frequency: 0.7, durationMs: quickRiseMs, rise: true),
  };

  /// How long [p] keeps the motor busy.
  static int busyMs(GroovePrimitive p) {
    final d = of(p).durationMs;
    return d == 0 ? transientMs : d;
  }
}

/// [beats] (times relative to now) as one pattern: each primitive at its
/// strength, and a beat whose predecessor is still playing starts right after
/// it (Android `composeSchedule`).
List<HapticPulse> grooveHapticPulses(List<GrooveBeat> beats) {
  var cursor = 0;
  final out = <HapticPulse>[];
  for (final b in beats) {
    final at = math.max(math.max(0, b.atMs), cursor);
    final shape = GroovePulseShapes.of(b.primitive);
    out.add(
      HapticPulse(
        atMs: at,
        intensity: b.scale.clamp(0.0, 1.0),
        frequency: shape.frequency,
        durationMs: shape.durationMs,
        rise: shape.rise,
      ),
    );
    cursor = at + GroovePulseShapes.busyMs(b.primitive);
  }
  return out;
}
