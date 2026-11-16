/// 曲奇浪口, the Cookie wave top seam (SeamCookie.kt): a graphic's top edge
/// under fixed chrome is one wave lip, cut as a heightfield — never holes,
/// islands or marks. Its character is scrubbed by the item's own exit
/// progress q: Cookie crowns as it goes under, an almost pure sine halfway,
/// soft SoftBurst points near the end, rolling a quarter turn as it goes. The
/// tips ease toward the page colour, so they never read sharp.
///
/// Positional throughout: at rest every lip depends on q alone (a grid row
/// matches and holds still). Speed only widens the band and the waves and
/// adds one coherent ripple along the row, settling with the flow's afterglow.
library;

import 'dart:math' as math;
import 'dart:ui';

import '../../../theme/tokens.dart';

const double _tau = math.pi * 2;

/// One graphic's lip, in its own px; built by [SeamLip.configure].
final class SeamLip {
  const SeamLip({
    required this.seam,
    required this.rest,
    required this.lobes,
    required this.amplitude,
    required this.character,
    required this.phase,
    required this.shoulder,
    required this.veil,
    required this.veilDepth,
    this.shoulderReach = SeamTokens.cookieShoulderReach,
    this.guard = SeamTokens.cookieGuard,
    this.corner = SeamTokens.cookieCorner,
    this.edge = SeamTokens.cookieEdge,
  });

  /// The seam's y (s): content above it is gone.
  final double seam;

  /// The front's mean depth below the seam, .45G.
  final double rest;
  final double lobes;
  final double amplitude;

  /// p: Cookie (.5) → sine (≈.95) → SoftBurst (1.8), after the tip-radius floor.
  final double character;
  final double phase;

  /// The shoulders' depth at the item's side edges (squares only).
  final double shoulder;
  final double shoulderReach;
  final double guard;
  final double corner;
  final double edge;

  /// How far the tips ease toward the page colour at the seam, and over what depth.
  final double veil;
  final double veilDepth;

  /// Below this depth the content passes through untouched.
  double get reach => math.max(rest + amplitude + shoulder, veilDepth) + edge;

  /// The lip for a graphic whose top is [seam] px above the seam line
  /// (negative: still below it); null while the seam doesn't touch it yet.
  /// Under [reduced] motion there is no stretch, disorder, lag or roll; the
  /// morph and the envelope stay, being positional.
  static SeamLip? configure({
    required double seam,
    required double width,
    required double height,
    required double centreX,
    required double stretch,
    required double disorder,
    required double lagPx,
    required double travelPx,
    required double reveal,
    required bool reduced,
  }) {
    if (width <= 0 || height <= 0) return null;
    final sigma = reduced ? 0.0 : stretch;
    final band = _lerp(SeamTokens.cookieBand, SeamTokens.cookieBandStretched, sigma) * reveal;
    if (band < SeamTokens.cookieMinBand) return null;
    final q = seamCookieExitProgress(seam, height, SeamTokens.cookieEntry);
    final lobes = seamCookieLobeCount(width, SeamTokens.cookieLobe);
    final lambda = width / lobes;
    final wave = _lerp(SeamTokens.cookieAmplitude, SeamTokens.cookieAmplitudeStretched, sigma);
    final amplitude =
        math.min(
          seamCookieEnvelope(q) * wave,
          math.min(SeamTokens.cookieAmplitudeOfBand * band, SeamTokens.cookieAmplitudeOfLobe * lambda),
        ) *
        reveal;
    double phase = 0;
    if (!reduced) {
      final lag = lagPx.clamp(-band / 2, band / 2);
      final along = centreX / SeamTokens.cookieRippleWavelength - travelPx / SeamTokens.cookieRippleTravel;
      phase =
          SeamTokens.cookieRoll * lobes * q +
          SeamTokens.cookieLagPhase * lag / band +
          SeamTokens.cookieRipple * disorder * math.sin(along * _tau);
    }
    final lip = SeamLip(
      seam: seam,
      rest: SeamTokens.cookieRest * band,
      lobes: lobes.toDouble(),
      amplitude: amplitude,
      character: seamCookieTipFloor(seamCookieCharacter(q), lambda, amplitude, SeamTokens.cookieTipRadius),
      phase: phase,
      shoulder: SeamTokens.cookieShoulder * band * _smoothstep(0, SeamTokens.cookieShoulderIn, q),
      veil: SeamTokens.cookieVeil * reveal,
      veilDepth: SeamTokens.cookieVeilDepth * band,
    );
    // Wholly below the lip and its veil: untouched.
    return -seam < lip.reach ? lip : null;
  }

  /// The profile Pr in [-1, 1] at [x]: crowns (1) at the lobes' centres when the phase is 0.
  double profile(double width, double x) {
    final c = math.cos((lobes * x / width - 0.5 + phase) * _tau);
    final g = math.pow(((1 + c) / 2 + SeamTokens.cookieNotch) / (1 + SeamTokens.cookieNotch), character).toDouble();
    return 2 * g - 1;
  }

  /// The front f(x): how far below the seam the item starts at [x]. It
  /// flattens to the plain rest line before the item's own bottom edge
  /// reaches it, so the last sliver is never cut into separate teeth.
  double front(double width, double height, double x, {required bool round}) {
    final sideEdge = 1 - _smoothstep(0, shoulderReach, math.min(x, width - x));
    final shoulderDepth = round ? 0.0 : shoulder * sideEdge * sideEdge;
    final bottom = silhouetteBottom(width, height, x, round: round);
    final guardFactor = _smoothstep(0, guard, bottom - seam - rest);
    return rest + guardFactor * (-amplitude * profile(width, x) + shoulderDepth);
  }

  /// The item's bottom edge at [x]: an ellipse when [round], else the 4dp rounded square.
  double silhouetteBottom(double width, double height, double x, {required bool round}) {
    if (round) {
      final u = ((x - width / 2) / (width / 2)).clamp(-1.0, 1.0);
      return height / 2 * (1 + math.sqrt(math.max(0, 1 - u * u)));
    }
    final ex = x < corner ? corner - x : (x > width - corner ? x - (width - corner) : 0.0);
    return ex > 0 ? height - corner + math.sqrt(math.max(0, corner * corner - ex * ex)) : height;
  }

  /// How far the content has eased toward the page at [depth] below the seam (k).
  double veilAt(double depth) => veil * (1 - _smoothstep(0, veilDepth, depth));

  /// The region below the front (what stays), as a path over an element
  /// [width] × [height], down to [bottom]; the front sampled every [step].
  Path keep(
    double width,
    double height, {
    required bool round,
    required double bottom,
    double step = SeamTokens.cookieStep,
  }) {
    double edgeAt(double x) => seam + front(width, height, x, round: round);
    final path = Path()..moveTo(-1, edgeAt(0));
    var x = 0.0;
    while (true) {
      path.lineTo(x, edgeAt(x));
      if (x >= width) break;
      x = math.min(x + step, width);
    }
    path
      ..lineTo(width + 1, edgeAt(width))
      ..lineTo(width + 1, bottom)
      ..lineTo(-1, bottom)
      ..close();
    return path;
  }
}

/// q: 0 while the item's top is [entry] below the seam, 1 once its bottom reaches it.
double seamCookieExitProgress(double seam, double height, double entry) =>
    ((seam + entry) / (height + entry)).clamp(0.0, 1.0);

/// E(q), the M3 wavy indicator's envelope: flat at both ends of the exit.
double seamCookieEnvelope(double q) =>
    _smoothstep(0, SeamTokens.cookieEnvelopeIn, q) * (1 - _smoothstep(SeamTokens.cookieEnvelopeOut, 1, q));

/// n: whole lobes of about [lobe] across the item, at least two.
int seamCookieLobeCount(double width, double lobe) => math.max(SeamTokens.cookieMinLobes, (width / lobe).round());

/// p(q), log-interpolated Cookie → SoftBurst over the middle of the exit.
double seamCookieCharacter(double q) {
  final t = _smoothstep(SeamTokens.cookieMorphFrom, SeamTokens.cookieMorphTo, q);
  return math.exp(
    math.log(SeamTokens.cookieCharacterFrom) +
        (math.log(SeamTokens.cookieCharacterTo) - math.log(SeamTokens.cookieCharacterFrom)) * t,
  );
}

/// Caps p so a tip's radius never drops under [tipRadius]: λ² / (4π² · ρ · A).
double seamCookieTipFloor(double character, double lambda, double amplitude, double tipRadius) {
  if (amplitude <= 1e-4) return character;
  return math.min(character, lambda * lambda / (4 * math.pi * math.pi * tipRadius * amplitude));
}

double _smoothstep(double from, double to, double value) {
  final t = ((value - from) / (to - from)).clamp(0.0, 1.0);
  return t * t * (3 - 2 * t);
}

double _lerp(double a, double b, double t) => a + (b - a) * t;
