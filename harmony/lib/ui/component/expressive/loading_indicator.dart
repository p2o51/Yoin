import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';

import '../../shapes/shape_path.dart';

// M3 Expressive LoadingIndicator, ported from the compiled classes Android
// Yoin ships (read with javap, not guessed): androidx.compose.material3
// 1.5.0-alpha27 — `LoadingIndicatorKt` (indeterminate impl, morph sequence,
// scale factor, path processing), `LoadingIndicatorDefaults`,
// `tokens/LoadingIndicatorTokens`. The shapes and morphs come from the
// graphics-shapes / MaterialShapes port in lib/ui/shapes.

// ──────────────────────────── LoadingIndicator ────────────────────────────

/// LoadingIndicatorDefaults + tokens/LoadingIndicatorTokens + the private
/// constants of LoadingIndicator.kt.
abstract final class LoadingIndicatorDefaults {
  /// LoadingIndicatorTokens.ContainerWidth / ContainerHeight (dp).
  static const double containerWidth = 48;
  static const double containerHeight = 48;

  /// LoadingIndicatorTokens.ActiveSize: the shape's size inside the container (dp).
  static const double indicatorSize = 38;

  /// ActiveIndicatorScale = IndicatorSize / min(ContainerWidth, ContainerHeight).
  static const double activeIndicatorScale = indicatorSize / containerWidth;

  /// GlobalRotationDurationMillis: one linear full turn.
  static const Duration globalRotationDuration = Duration(milliseconds: 4666);

  /// MorphIntervalMillis: a new morph starts this often.
  static const Duration morphInterval = Duration(milliseconds: 650);

  /// The morph spring: spring(dampingRatio 0.6, stiffness 200, visibilityThreshold 0.1).
  static const double morphDampingRatio = 0.6;
  static const double morphStiffness = 200;
  static const double morphVisibilityThreshold = 0.1;

  /// FullRotation / QuarterRotation (degrees): each morph also turns the shape a quarter.
  static const double fullRotation = 360;
  static const double quarterRotation = 90;

  /// IndeterminateIndicatorPolygons, in order.
  static final List<RoundedPolygon> indeterminateIndicatorPolygons = [
    MaterialShapes.softBurst,
    MaterialShapes.cookie9Sided,
    MaterialShapes.pentagon,
    MaterialShapes.pill,
    MaterialShapes.sunny,
    MaterialShapes.cookie4Sided,
    MaterialShapes.oval,
  ];

  /// morphSequence(polygons, circularSequence = true): normalized pairs,
  /// the last morphing back into the first.
  static List<Morph> morphSequence(List<RoundedPolygon> polygons, {required bool circular}) => [
    for (var i = 0; i < polygons.length; i++)
      if (i + 1 < polygons.length)
        Morph(polygons[i].normalized(), polygons[i + 1].normalized())
      else if (circular)
        Morph(polygons[i].normalized(), polygons[0].normalized()),
  ];

  /// calculateScaleFactor: shrink so every shape fits the container at any rotation.
  static double calculateScaleFactor(List<RoundedPolygon> polygons) {
    var scaleFactor = 1.0;
    for (final polygon in polygons) {
      final bounds = polygon.calculateBounds();
      final maxBounds = polygon.calculateMaxBounds();
      final scaleX = (bounds[2] - bounds[0]) / (maxBounds[2] - maxBounds[0]);
      final scaleY = (bounds[3] - bounds[1]) / (maxBounds[3] - maxBounds[1]);
      scaleFactor = math.min(scaleFactor, math.max(scaleX, scaleY));
    }
    return scaleFactor;
  }

  static final List<Morph> indeterminateMorphs = morphSequence(indeterminateIndicatorPolygons, circular: true);

  static final double indeterminateScaleFactor =
      calculateScaleFactor(indeterminateIndicatorPolygons) * activeIndicatorScale;

  /// How long Compose plays the morph spring before snapping to 1:
  /// SpringEstimation.estimateAnimationDurationMillis (under-damped case)
  /// with displacement and velocity in visibility-threshold units.
  static final Duration morphSpringDuration = () {
    const p0 = 1 / morphVisibilityThreshold; // |0 - 1| / threshold
    const v0 = 0.0;
    final omega = math.sqrt(morphStiffness);
    final r = -morphDampingRatio * omega;
    final imaginary = math.sqrt(4 * morphStiffness - math.pow(2 * morphDampingRatio * omega, 2)) * 0.5;
    const c1 = p0;
    final c2 = (v0 - r * c1) / imaginary;
    final c = math.sqrt(c1 * c1 + c2 * c2);
    final seconds = math.log(1 / c) / r;
    return Duration(milliseconds: (seconds * 1000).truncate());
  }();

  /// The morph spring's value [elapsed] after it starts from 0 toward 1
  /// (Compose SpringSimulation, under-damped), 1 once its duration is over.
  static double morphProgressAt(Duration elapsed) {
    if (elapsed >= morphSpringDuration) return 1;
    final t = elapsed.inMicroseconds / Duration.microsecondsPerSecond;
    final omega = math.sqrt(morphStiffness);
    final dampedFreq = omega * math.sqrt(1 - morphDampingRatio * morphDampingRatio);
    const displacement = -1.0; // start - target
    final cosCoeff = displacement;
    final sinCoeff = (morphDampingRatio * omega * displacement) / dampedFreq;
    final x =
        math.exp(-morphDampingRatio * omega * t) *
        (cosCoeff * math.cos(dampedFreq * t) + sinCoeff * math.sin(dampedFreq * t));
    return 1 + x;
  }
}

/// One frame of the indeterminate indicator.
@immutable
final class LoadingIndicatorFrame {
  const LoadingIndicatorFrame({required this.morphIndex, required this.morphProgress, required this.rotationDegrees});

  final int morphIndex;
  final double morphProgress;
  final double rotationDegrees;

  /// The frame [elapsed] after the indicator appears. A morph starts every
  /// [LoadingIndicatorDefaults.morphInterval]; it springs 0 → 1 (with
  /// overshoot) and turns a quarter; once settled the next morph starts from
  /// the same shape and angle, so holding at 1 equals Compose's "advance the
  /// index, snap to 0, add 90°". The whole shape also turns once per 4666 ms.
  factory LoadingIndicatorFrame.at(Duration elapsed, {int morphCount = 7}) {
    final interval = LoadingIndicatorDefaults.morphInterval.inMicroseconds;
    final us = elapsed.inMicroseconds;
    final k = us ~/ interval;
    final local = Duration(microseconds: us - k * interval);
    final progress = LoadingIndicatorDefaults.morphProgressAt(local);
    final targetAngle = (LoadingIndicatorDefaults.quarterRotation * (k + 1)) % LoadingIndicatorDefaults.fullRotation;
    final rotationPeriod = LoadingIndicatorDefaults.globalRotationDuration.inMicroseconds;
    final global = (us % rotationPeriod) / rotationPeriod * LoadingIndicatorDefaults.fullRotation;
    return LoadingIndicatorFrame(
      morphIndex: k % morphCount,
      morphProgress: progress,
      rotationDegrees: progress * LoadingIndicatorDefaults.quarterRotation + targetAngle + global,
    );
  }

  /// The resting frame shown with reduced motion: the first shape, unmoved.
  static const still = LoadingIndicatorFrame(
    morphIndex: 0,
    morphProgress: 0,
    rotationDegrees: LoadingIndicatorDefaults.quarterRotation,
  );
}

enum _IndicatorPalette { material, yoin }

/// M3 Expressive indeterminate LoadingIndicator / ContainedLoadingIndicator:
/// a shape that keeps turning while it springs through SoftBurst, 9-sided
/// cookie, pentagon, pill, sunny, 4-sided cookie and oval.
///
/// [contained] draws the 48 dp round container behind it (the shape fills
/// 38/48 of it); the whole thing scales with [size], like Compose's
/// `Modifier.size` on the indicator. Colours default to Material's
/// (contained: primaryContainer / onPrimaryContainer; bare: primary).
/// [ExpressiveLoadingIndicator.yoin] is Android Yoin's `YoinLoadingIndicator`:
/// contained, secondaryContainer / onSecondaryContainer.
class ExpressiveLoadingIndicator extends StatefulWidget {
  const ExpressiveLoadingIndicator({
    super.key,
    this.size = LoadingIndicatorDefaults.containerWidth,
    this.contained = false,
    this.indicatorColor,
    this.containerColor,
    this.semanticsLabel,
  }) : _palette = _IndicatorPalette.material;

  /// YoinLoadingIndicator(size = 48.dp).
  const ExpressiveLoadingIndicator.yoin({
    super.key,
    this.size = LoadingIndicatorDefaults.containerWidth,
    this.semanticsLabel,
  }) : contained = true,
       indicatorColor = null,
       containerColor = null,
       _palette = _IndicatorPalette.yoin;

  final double size;
  final bool contained;
  final Color? indicatorColor;
  final Color? containerColor;
  final String? semanticsLabel;
  final _IndicatorPalette _palette;

  @override
  State<ExpressiveLoadingIndicator> createState() => _ExpressiveLoadingIndicatorState();
}

class _ExpressiveLoadingIndicatorState extends State<ExpressiveLoadingIndicator> with SingleTickerProviderStateMixin {
  final ValueNotifier<LoadingIndicatorFrame> _frame = ValueNotifier(LoadingIndicatorFrame.still);
  late final Ticker _ticker = createTicker((elapsed) => _frame.value = LoadingIndicatorFrame.at(elapsed));
  bool _reduceMotion = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _reduceMotion = MediaQuery.maybeDisableAnimationsOf(context) ?? false;
    if (_reduceMotion) {
      if (_ticker.isActive) _ticker.stop();
      _frame.value = LoadingIndicatorFrame.still;
    } else if (!_ticker.isActive) {
      _ticker.start();
    }
  }

  @override
  void dispose() {
    _ticker.dispose();
    _frame.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final Color ink;
    final Color? container;
    switch (widget._palette) {
      case _IndicatorPalette.yoin:
        ink = colors.onSecondaryContainer;
        container = colors.secondaryContainer;
      case _IndicatorPalette.material:
        ink = widget.indicatorColor ?? (widget.contained ? colors.onPrimaryContainer : colors.primary);
        container = widget.contained ? (widget.containerColor ?? colors.primaryContainer) : null;
    }
    return Semantics(
      label: widget.semanticsLabel,
      child: RepaintBoundary(
        child: SizedBox.square(
          dimension: widget.size,
          child: CustomPaint(
            painter: LoadingIndicatorPainter(frame: _frame, indicatorColor: ink, containerColor: container),
          ),
        ),
      ),
    );
  }
}

/// Paints one [LoadingIndicatorFrame]: the container (CornerFull), then the
/// current morph, scaled by the indeterminate scale factor, centred on its
/// bounds and rotated about the centre (LoadingIndicatorImpl + processPath).
class LoadingIndicatorPainter extends CustomPainter {
  LoadingIndicatorPainter({required this.frame, required this.indicatorColor, this.containerColor})
    : super(repaint: frame);

  final ValueListenable<LoadingIndicatorFrame> frame;
  final Color indicatorColor;
  final Color? containerColor;

  final Path _path = Path();

  @override
  void paint(Canvas canvas, Size size) {
    if (size.isEmpty) return;
    if (containerColor != null) {
      final rect = Offset.zero & size;
      canvas.drawRRect(
        RRect.fromRectAndRadius(rect, Radius.circular(size.shortestSide / 2)),
        Paint()..color = containerColor!,
      );
    }
    final f = frame.value;
    final morphs = LoadingIndicatorDefaults.indeterminateMorphs;
    final cubics = morphs[f.morphIndex % morphs.length].asCubics(f.morphProgress);
    final scale = LoadingIndicatorDefaults.indeterminateScaleFactor;
    final sx = size.width * scale;
    final sy = size.height * scale;
    // Path.getBounds on Android is the control-point hull; centre on it.
    var minX = double.maxFinite, minY = double.maxFinite, maxX = -double.maxFinite, maxY = -double.maxFinite;
    for (final c in cubics) {
      for (var i = 0; i < 8; i += 2) {
        minX = math.min(minX, c.points[i] * sx);
        maxX = math.max(maxX, c.points[i] * sx);
        minY = math.min(minY, c.points[i + 1] * sy);
        maxY = math.max(maxY, c.points[i + 1] * sy);
      }
    }
    final center = size.center(Offset.zero);
    final dx = center.dx - (minX + maxX) / 2;
    final dy = center.dy - (minY + maxY) / 2;
    _path.reset();
    _path.moveTo(cubics.first.anchor0X * sx + dx, cubics.first.anchor0Y * sy + dy);
    for (final c in cubics) {
      _path.cubicTo(
        c.control0X * sx + dx,
        c.control0Y * sy + dy,
        c.control1X * sx + dx,
        c.control1Y * sy + dy,
        c.anchor1X * sx + dx,
        c.anchor1Y * sy + dy,
      );
    }
    _path.close();
    canvas
      ..save()
      ..translate(center.dx, center.dy)
      ..rotate(f.rotationDegrees * math.pi / 180)
      ..translate(-center.dx, -center.dy)
      ..drawPath(_path, Paint()..color = indicatorColor)
      ..restore();
  }

  @override
  bool shouldRepaint(LoadingIndicatorPainter old) =>
      old.frame != frame || old.indicatorColor != indicatorColor || old.containerColor != containerColor;
}
