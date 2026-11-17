import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../detail/album_hero_marks.dart' show parseSvgPathData;
import '../nowplaying/haptics.dart';
import 'landing_motion.dart';

// The landing's character (LandingMascot.kt): the three-arrow mark split into
// its fins. Pink is its left hand, navy its right, violet its head; the hands
// wave from the joint where the fins meet. No eyes (owner 10-09). Everything
// it does is a spring read while painting, so a wave never rebuilds or
// re-lays out anything. Brand artwork, not a symbol: the geometry is the
// launcher mark's (res/drawable/landing_mascot_*.xml), not drawn here.

/// The mark's viewport: 1300 × 1040 units (the launcher mark, widened and
/// shifted 140 right so the fins the launcher mask crops stay whole).
const double mascotViewportWidth = 1300;
const double mascotViewportHeight = 1040;
const double mascotAspect = mascotViewportWidth / mascotViewportHeight;

/// One path of a fin, in viewport units.
final class MascotFinPart {
  MascotFinPart(String d, {this.fill, this.stroke, this.strokeWidth = 0})
    : path = parseSvgPathData(d).shift(_groupShift);

  final Path path;
  final Color? fill;
  final Color? stroke;
  final double strokeWidth;

  static const _groupShift = Offset(140, 0);
}

/// One fin: its entrance pose (dx, dy in viewport units, rot in degrees,
/// scale) plus its gesture angle ([arm]), turning about its joint.
class MascotFin {
  MascotFin(TickerProvider vsync, {required this.pivot, required this.parts})
    : dx = LandingAnim(vsync, 0),
      dy = LandingAnim(vsync, 0),
      rot = LandingAnim(vsync, 0),
      scale = LandingAnim(vsync, 1),
      arm = LandingAnim(vsync, 0);

  final Offset pivot;
  final List<MascotFinPart> parts;
  final LandingAnim dx;
  final LandingAnim dy;
  final LandingAnim rot;
  final LandingAnim scale;
  final LandingAnim arm;

  List<LandingAnim> get _all => [dx, dy, rot, scale, arm];

  void rest() {
    dx.snapTo(0);
    dy.snapTo(0);
    rot.snapTo(0);
    scale.snapTo(1);
    arm.snapTo(0);
  }

  void dispose() {
    for (final a in _all) {
      a.dispose();
    }
  }
}

/// The mascot's choreography (LandingMascotState). Gestures replace each
/// other: a new one cancels the one running and starts from the pose on screen.
class LandingMascotState {
  LandingMascotState(TickerProvider vsync, {required this.reduced, this.haptics = true})
    : pink = MascotFin(vsync, pivot: const Offset(660, 520), parts: _pinkParts()),
      violet = MascotFin(vsync, pivot: const Offset(652, 480), parts: _violetParts()),
      navy = MascotFin(vsync, pivot: const Offset(660, 500), parts: _navyParts()),
      alpha = LandingAnim(vsync, 1),
      hop = LandingAnim(vsync, 0);

  /// Reduced motion: no gestures, the mark simply there.
  final bool reduced;

  /// Whether the wave's peaks tick (off in tests and previews).
  final bool haptics;

  final MascotFin pink;
  final MascotFin violet;
  final MascotFin navy;

  /// Drawn in this order (pink under violet under navy, as the mark stacks).
  List<MascotFin> get fins => [pink, violet, navy];

  /// Whole-character alpha (the entrance fade).
  final LandingAnim alpha;

  /// Vertical kick, in viewport units (negative = up).
  final LandingAnim hop;

  int _gesture = 0;
  bool _disposed = false;

  /// Everything the painter reads.
  Listenable get repaint => Listenable.merge([
    alpha.animation,
    hop.animation,
    for (final fin in fins) ...[
      fin.dx.animation,
      fin.dy.animation,
      fin.rot.animation,
      fin.scale.animation,
      fin.arm.animation,
    ],
  ]);

  /// Starts a gesture; [body] gets a check that says whether it still runs.
  void _play(Future<void> Function(bool Function() live) body) {
    final id = ++_gesture;
    bool live() => !_disposed && id == _gesture;
    unawaited(body(live));
  }

  Future<void> _wait(int ms) => Future<void>.delayed(Duration(milliseconds: ms));

  /// The fins fly in from three sides and land as the mark, then it waves twice.
  void intro({VoidCallback? onLanded}) => _play((live) async {
    if (reduced) {
      for (final fin in fins) {
        fin.rest();
      }
      alpha.snapTo(1);
      onLanded?.call();
      return;
    }
    final from = {pink: (-330.0, 220.0, -55.0), violet: (0.0, -300.0, 24.0), navy: (330.0, 230.0, 55.0)};
    for (final fin in fins) {
      final (x, y, r) = from[fin]!;
      fin.dx.snapTo(x);
      fin.dy.snapTo(y);
      fin.rot.snapTo(r);
      fin.scale.snapTo(0.35);
      fin.arm.snapTo(0);
    }
    alpha.snapTo(0);
    unawaited(
      _wait(60).then((_) {
        if (live()) alpha.animateTo(1, LandingSprings.mascotLand);
      }),
    );
    for (final (fin, wait) in [(violet, 80), (pink, 170), (navy, 260)]) {
      unawaited(
        _wait(wait).then((_) {
          if (!live()) return;
          fin.dx.animateTo(0, LandingSprings.mascotLand);
          fin.dy.animateTo(0, LandingSprings.mascotLand);
          fin.rot.animateTo(0, LandingSprings.mascotLand);
          fin.scale.animateTo(1, LandingSprings.mascotLand);
        }),
      );
    }
    await _wait(_landedAfterMs);
    if (!live()) return;
    onLanded?.call();
    await _waveSequence(live, times: 2, ticks: true);
  });

  /// Both hands up and back, [times] swings; a tick at the top of the first hand's swings.
  void wave({int times = 1, bool ticks = true}) => _play((live) => _waveSequence(live, times: times, ticks: ticks));

  Future<void> _waveSequence(bool Function() live, {required int times, required bool ticks}) async {
    if (reduced) return;
    // (delay before this pose, pink arm, navy arm, head tilt): pink swings clockwise to raise, navy the other way.
    final poses = times <= 1
        ? const [(0, 28.0, -12.0, -2.5), (240, 8.0, -2.0, 2.5), (240, 0.0, 0.0, 0.0)]
        : const [
            (0, 26.0, -12.0, -2.5),
            (230, 9.0, -3.0, 2.5),
            (230, 30.0, -14.0, -2.5),
            (230, 10.0, -4.0, 2.5),
            (240, 0.0, 0.0, 0.0),
          ];
    for (var i = 0; i < poses.length; i++) {
      final (after, left, right, head) = poses[i];
      if (after > 0) await _wait(after);
      if (!live()) return;
      pink.arm.animateTo(left, LandingSprings.mascotGesture);
      navy.arm.animateTo(right, LandingSprings.mascotGesture);
      violet.arm.animateTo(head, LandingSprings.mascotGesture);
      if (ticks && haptics && left > 20 && (i == 0 || i == 2)) {
        unawaited(
          _wait(_peakAfterMs).then((_) {
            if (live()) YoinHaptics.tick();
          }),
        );
      }
    }
  }

  /// Both hands up and a hop: a connection went through.
  void cheer() => _play((live) async {
    if (reduced) return;
    hop.animateTo(0, LandingSprings.mascotHop, velocity: -_cheerHopVelocity);
    pink.arm.animateTo(36, LandingSprings.mascotGesture);
    await navy.arm.animateTo(-16, LandingSprings.mascotGesture);
    await _wait(_cheerHoldMs);
    if (!live()) return;
    pink.arm.animateTo(0, LandingSprings.mascotGesture);
    navy.arm.animateTo(0, LandingSprings.mascotGesture);
  });

  /// A small nod toward something the user picked.
  void nod() => _play((live) async {
    if (reduced) return;
    hop.animateTo(0, LandingSprings.mascotHop, velocity: -_nodHopVelocity);
    await violet.arm.animateTo(7, LandingSprings.mascotGesture);
    await _wait(_nodHoldMs);
    if (!live()) return;
    violet.arm.animateTo(0, LandingSprings.mascotGesture);
  });

  /// The head rings out a "no": something the user typed was refused.
  void shake() => _play((live) async {
    if (reduced) return;
    violet.arm.snapTo(0);
    violet.arm.animateTo(0, LandingSprings.mascotShake, velocity: _shakeVelocity);
  });

  void dispose() {
    _disposed = true;
    for (final fin in fins) {
      fin.dispose();
    }
    alpha.dispose();
    hop.dispose();
  }

  static const _landedAfterMs = 760;
  static const _peakAfterMs = 120;
  static const double _cheerHopVelocity = 1700;
  static const _cheerHoldMs = 520;
  static const double _nodHopVelocity = 700;
  static const _nodHoldMs = 380;
  static const double _shakeVelocity = 160;
}

/// The mascot, drawn at the width of its box (the height follows the mark's
/// aspect). [breath] (−1..1) lifts it while it waits; null holds it still.
class LandingMascot extends StatelessWidget {
  const LandingMascot({super.key, required this.state, this.breath});

  final LandingMascotState state;
  final Animation<double>? breath;

  @override
  Widget build(BuildContext context) => ExcludeSemantics(
    child: CustomPaint(painter: _MascotPainter(state, breath), child: const SizedBox.expand()),
  );
}

class _MascotPainter extends CustomPainter {
  _MascotPainter(this.state, this.breath)
    : super(repaint: breath == null ? state.repaint : Listenable.merge([state.repaint, breath]));

  final LandingMascotState state;
  final Animation<double>? breath;

  static const double _breathLift = 5;

  @override
  void paint(Canvas canvas, Size size) {
    final alpha = state.alpha.value.clamp(0.0, 1.0);
    if (alpha <= 0) return;
    // One painter, every transform done while drawing (as layers, a fin moved
    // by a fraction of a pixel showed a seam along the box's edge on Android).
    final unit = size.width / mascotViewportWidth;
    final b = breath == null ? 0.0 : math.sin(breath!.value * 2 * math.pi);
    final lift = (state.hop.value + b * _breathLift) * unit;
    for (final fin in state.fins) {
      canvas.save();
      canvas.translate(fin.dx.value * unit, fin.dy.value * unit + lift);
      final pivot = fin.pivot * unit;
      canvas.translate(pivot.dx, pivot.dy);
      canvas.rotate((fin.rot.value + fin.arm.value) * math.pi / 180);
      canvas.scale(fin.scale.value);
      canvas.translate(-pivot.dx, -pivot.dy);
      canvas.scale(unit);
      for (final part in fin.parts) {
        if (part.fill != null) {
          canvas.drawPath(part.path, Paint()..color = part.fill!.withValues(alpha: part.fill!.a * alpha));
        }
        if (part.stroke != null) {
          canvas.drawPath(
            part.path,
            Paint()
              ..style = PaintingStyle.stroke
              ..strokeWidth = part.strokeWidth
              ..color = part.stroke!.withValues(alpha: part.stroke!.a * alpha),
          );
        }
      }
      canvas.restore();
    }
  }

  @override
  bool shouldRepaint(_MascotPainter old) => old.state != state || old.breath != breath;
}

// The mark's colours (launcher mark artwork, not theme roles).
const _pink = Color(0xFFFF59CD);
const _navy = Color(0xFF192396);
const _violet = Color(0xFF9113FF);
const _white = Color(0xFFFFFFFF);

List<MascotFinPart> _pinkParts() => [
  MascotFinPart(
    'M81.9383 348.573C51.2968 340.611 -16.6967 332.156 -61.0882 392.462C-106.605 454.297 -95.2609 542.765 -35.7491 590.06C-7.07619 612.846 30.0687 627.87 53.9624 636.864C82.7386 647.696 115.644 658.004 147.859 667.229C156.788 669.786 195.457 676.647 204.494 679.108C197.245 754.752 185.613 844.276 267.523 914.662C325.26 964.275 410.773 955.863 458.523 895.873C504.991 837.49 532.015 756.394 478.422 706.111C478.422 706.111 454.56 679.371 464.476 661.463C470.504 650.578 512.994 588.968 545.393 542.319C563.625 516.068 550.284 479.655 519.395 471.444L431.422 448.059L81.9383 348.573Z',
    fill: _pink,
  ),
  MascotFinPart(
    'M-34 399C26.0829 454.85 288.368 530.198 412 560.892C362.701 642.941 293.683 741.079 412 860',
    stroke: _white,
    strokeWidth: 8,
  ),
];

List<MascotFinPart> _navyParts() => [
  MascotFinPart(
    'M885.037 322.715C912.923 266.179 977.242 239.224 1035.35 262.193C1098.06 286.981 1129.78 360.529 1106.21 426.466C1083.91 488.843 1033.38 527.075 1000.77 548.017C963.209 572.142 919.292 591.855 877.499 607.716C846.85 619.347 814.97 629.785 783.876 638.923C786.057 647.057 788.104 655.313 789.989 663.666C805.805 733.753 815.238 832.636 773.206 923.307C743.852 986.628 671.237 1012.94 611.014 982.077C550.792 951.213 525.768 874.862 555.122 811.541C560.94 798.989 564.676 770.166 553.932 722.557C543.871 677.97 525.306 636.027 512.801 613.831L470.401 538.577C447.592 498.094 471.201 447.168 516.835 438.415L595.398 423.346C647.4 413.372 727.098 393.593 795.134 367.773C829.436 354.755 856.382 341.834 874.311 330.32C879.402 327.05 882.844 324.487 885.037 322.715Z',
    fill: _navy,
  ),
  MascotFinPart(
    'M1064 319C1024.75 428.754 711.646 514.356 560 543.437C607.788 628.239 687.306 832.475 623.08 971',
    stroke: _white,
    strokeWidth: 5,
  ),
];

List<MascotFinPart> _violetParts() => [
  MascotFinPart(
    'M892.423 116.351C950.378 122.723 992.396 177.293 986.272 238.237C980.147 299.181 928.201 343.421 870.245 337.05C831.395 332.779 766.502 354.791 695.984 393.382C663.959 410.908 635.711 429.056 615.431 442.875C605.353 449.743 597.396 455.436 592.099 459.305C589.454 461.238 587.483 462.708 586.253 463.632C585.639 464.094 585.21 464.419 584.977 464.597C584.934 464.63 584.897 464.656 584.868 464.679L569.274 476.708C531.442 505.895 476.643 497.207 449.054 457.649L437.612 441.243C437.542 441.145 437.392 440.935 437.162 440.62C436.592 439.838 435.537 438.409 434.015 436.42C430.964 432.432 426.074 426.242 419.481 418.517C406.214 402.971 386.515 381.758 361.501 359.937C310.204 315.191 243.776 274.135 169.61 264.915C111.743 257.722 70.4305 202.56 77.3365 141.707C84.2425 80.8542 136.751 37.3551 194.619 44.5487C324.766 60.7276 428.536 129.93 496.358 189.092C512.793 203.428 527.54 217.544 540.492 230.754C557.899 219.85 577.542 208.155 598.714 196.569C671.227 156.886 785.747 104.624 892.423 116.351ZM584.773 464.753C584.775 464.751 584.779 464.748 584.783 464.745C584.791 464.739 584.802 464.73 584.816 464.719C584.824 464.714 584.837 464.705 584.84 464.702L584.839 464.701C584.804 464.728 584.782 464.745 584.773 464.753Z',
    fill: _violet,
  ),
  MascotFinPart(
    'M540.14 366.692L538.076 368.079L539.545 370.257L541.635 368.712L540.14 366.692ZM131.462 89.0588L131.135 91.5371C252.98 108.934 354.696 177.977 426.046 242.845C461.703 275.262 489.726 306.592 508.832 329.814C518.384 341.423 525.704 351.003 530.632 357.674C533.096 361.009 534.962 363.617 536.209 365.388C536.833 366.273 537.302 366.949 537.614 367.402C537.77 367.629 537.887 367.8 537.964 367.913C538.003 367.97 538.031 368.012 538.05 368.039C538.059 368.053 538.066 368.063 538.071 368.07C538.073 368.073 538.074 368.075 538.075 368.077C538.076 368.078 538.076 368.079 540.14 366.692C542.204 365.305 542.203 365.303 542.201 365.301C542.2 365.299 542.198 365.295 542.195 365.291C542.189 365.283 542.181 365.271 542.171 365.255C542.15 365.224 542.119 365.179 542.078 365.118C541.996 364.998 541.874 364.82 541.713 364.587C541.391 364.119 540.912 363.429 540.278 362.528C539.009 360.728 537.121 358.089 534.634 354.721C529.658 347.986 522.284 338.337 512.67 326.652C493.445 303.285 465.255 271.769 429.379 239.152C357.665 173.953 255.042 104.179 131.788 86.5806L131.462 89.0588ZM540.14 366.692C541.635 368.712 541.636 368.712 541.638 368.711C541.639 368.709 541.642 368.707 541.646 368.705C541.653 368.699 541.665 368.691 541.68 368.68C541.71 368.657 541.757 368.623 541.818 368.578C541.942 368.487 542.127 368.351 542.373 368.172C542.866 367.812 543.601 367.278 544.568 366.582C546.503 365.188 549.367 363.145 553.077 360.552C560.496 355.364 571.294 347.974 584.8 339.175C611.814 321.573 649.645 298.341 692.913 275.812C736.189 253.28 784.857 231.477 833.551 216.707C882.271 201.929 930.853 194.243 974.019 199.787L974.31 197.304L974.602 194.821C930.46 189.152 881.116 197.033 832.059 211.913C782.976 226.801 734.015 248.746 690.575 271.364C647.128 293.986 609.157 317.305 582.048 334.968C568.492 343.8 557.649 351.221 550.191 356.435C546.463 359.043 543.58 361.099 541.628 362.504C540.651 363.207 539.908 363.748 539.407 364.113C539.157 364.295 538.967 364.434 538.84 364.528C538.776 364.575 538.728 364.61 538.696 364.634C538.679 364.646 538.667 364.655 538.659 364.661C538.654 364.664 538.651 364.667 538.649 364.668C538.647 364.67 538.645 364.671 540.14 366.692Z',
    fill: _white,
  ),
];
