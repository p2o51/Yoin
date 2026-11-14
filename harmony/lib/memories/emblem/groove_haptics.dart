/// 唱片刻纹 · award haptics (Android GrooveHaptics.kt). The beat plan is
/// groove.js `hapticPlan` thinned by twostate4's `awardBeats`: tier 4 keeps at
/// most 4 ring ticks (≥ 90ms apart, ×.35) and drops QUICK_RISE, so no tier
/// exceeds 7 beats; a replay keeps only the climax; the diary's 48dp nod is
/// the tier's strongest beat at half strength; reduced motion keeps only the
/// strongest beat, on the reveal's last frame.
///
/// HarmonyOS: there is no composed-primitive vibrator path yet
/// (lib/nowplaying/haptics.dart is Flutter's HapticFeedback), so beats play
/// through [YoinHaptics] at their times — Android's View fallback route.
/// Beats without a stand-in (QUICK_RISE, tier 4's later ring ticks) are
/// skipped there; the full plan stays available as data for a future
/// `yoin/haptics` channel ([GrooveBeatSink]).
library;

import 'dart:math' as math;

import '../../nowplaying/haptics.dart';
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

/// Receives each beat when it is due. The default plays the beat's named
/// fallback ([playGrooveBeatFallback]); a future vibrator channel can play the
/// [GrooveBeat.primitive] at [GrooveBeat.scale] instead.
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
