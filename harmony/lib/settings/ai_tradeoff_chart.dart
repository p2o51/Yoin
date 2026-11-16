import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../data/ai/ai_settings.dart';
import '../nowplaying/haptics.dart';
import '../theme/motion.dart';
import 'ai_tier_benchmark.dart';
import 'settings_metrics.dart';
import 'settings_strings.dart';

/// A service's colour on the chart and its legend (providerColor): series
/// colours, as on Android, tuned for a light and a dark surface.
Color aiProviderColor(AiProvider provider, {required bool dark}) => switch (provider) {
  AiProvider.gemini => dark ? const Color(0xFF7FA9FF) : const Color(0xFF2F6FE4),
  AiProvider.deepSeek => dark ? const Color(0xFF5FD3AC) : const Color(0xFF1F9A74),
  AiProvider.qwen => dark ? const Color(0xFFF2A463) : const Color(0xFFC4661B),
  AiProvider.custom => dark ? const Color(0xFFFF8FCF) : const Color(0xFFC2338A),
};

/// Every tier as a dot on two of speed / cost / accuracy (TradeoffChart). No
/// axis picker: the button beside the legend steps through [aiChartViews] and
/// the dots spring to their new places. Untested tiers are hollow dots in the corner.
class AiTradeoffChart extends StatefulWidget {
  const AiTradeoffChart({super.key, required this.provider, required this.tier});

  final AiProvider provider;
  final AiTier tier;

  @override
  State<AiTradeoffChart> createState() => _AiTradeoffChartState();
}

class _AiTradeoffChartState extends State<AiTradeoffChart> with TickerProviderStateMixin {
  static final List<AiTierPoint> _measured = AiTierBenchmark.points.where((p) => p.measured).toList();
  static final List<AiTierPoint> _untested = AiTierBenchmark.points.where((p) => !p.measured).toList();

  int _view = 0;
  int _turns = 0;

  // Fixed order and count: one pair of springs per measured tier.
  late final List<(SpringValue, SpringValue)> _positions = [
    for (final p in _measured)
      (
        SpringValue(vsync: this, initial: aiChartViews.first.$1.fraction(p) ?? 0, spring: YoinSprings.defaultSpatial),
        SpringValue(vsync: this, initial: aiChartViews.first.$2.fraction(p) ?? 0, spring: YoinSprings.defaultSpatial),
      ),
  ];
  late final SpringValue _turn = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultSpatial);

  @override
  void initState() {
    super.initState();
    _positions;
    _turn;
  }

  @override
  void dispose() {
    for (final (x, y) in _positions) {
      x.dispose();
      y.dispose();
    }
    _turn.dispose();
    super.dispose();
  }

  void _flip() {
    YoinHaptics.tick();
    setState(() {
      _view = (_view + 1) % aiChartViews.length;
      _turns++;
    });
    final (xAxis, yAxis) = aiChartViews[_view];
    final reduce = MediaQuery.disableAnimationsOf(context);
    for (final (i, p) in _measured.indexed) {
      final (x, y) = _positions[i];
      if (reduce) {
        x.snapTo(xAxis.fraction(p) ?? 0);
        y.snapTo(yAxis.fraction(p) ?? 0);
      } else {
        x.animateTo(xAxis.fraction(p) ?? 0);
        y.animateTo(yAxis.fraction(p) ?? 0);
      }
    }
    reduce ? _turn.snapTo(_turns * 0.5) : _turn.animateTo(_turns * 0.5);
  }

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final text = theme.textTheme;
    final dark = colors.surface.computeLuminance() < 0.5;
    final (xAxis, yAxis) = aiChartViews[_view];
    String axisName(AiAxis axis) => switch (axis) {
      AiAxis.speed => strings.aiAxisSpeed,
      AiAxis.cost => strings.aiAxisCost,
      AiAxis.accuracy => strings.aiAxisAccuracy,
    };
    final listenables = <Listenable>[
      for (final (x, y) in _positions) ...[x.animation, y.animation],
    ];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SizedBox(
          height: SettingsMetrics.aiChartHeight,
          child: AnimatedBuilder(
            animation: Listenable.merge(listenables),
            builder: (context, _) => CustomPaint(
              painter: _ChartPainter(
                provider: widget.provider,
                tier: widget.tier,
                dark: dark,
                measured: _measured,
                untested: _untested,
                positions: [for (final (x, y) in _positions) Offset(x.value, y.value)],
                xAxis: xAxis,
                yAxis: yAxis,
                xName: axisName(xAxis),
                yName: axisName(yAxis),
                tierName: strings.aiTier,
                untestedText: strings.aiUntested,
                grid: colors.outlineVariant,
                surface: colors.surface,
                small: text.labelSmall!.copyWith(
                  fontSize: SettingsMetrics.aiChartEndText,
                  color: colors.onSurfaceVariant,
                ),
                axisStyle: text.labelMedium!.copyWith(
                  fontWeight: FontWeight.w600,
                  fontVariations: const [FontVariation.weight(600)],
                  color: colors.onSurface,
                ),
                dotLabel: text.labelMedium!.copyWith(color: colors.onSurface),
                textDirection: Directionality.of(context),
              ),
            ),
          ),
        ),
        const SizedBox(height: SettingsMetrics.aiChartGap),
        LayoutBuilder(
          builder: (context, constraints) {
            final showText = constraints.maxWidth >= SettingsMetrics.aiFlipTextMinWidth;
            return Row(
              children: [
                Expanded(child: _Legend(dark: dark)),
                Semantics(
                  button: true,
                  label: showText ? null : strings.aiFlip,
                  child: Material(
                    key: const ValueKey('ai-flip'),
                    color: colors.surfaceBright,
                    shape: const StadiumBorder(),
                    child: InkWell(
                      customBorder: const StadiumBorder(),
                      onTap: _flip,
                      child: SizedBox(
                        height: SettingsMetrics.aiFlipHeight,
                        child: Padding(
                          padding: EdgeInsets.symmetric(
                            horizontal: showText ? SettingsMetrics.aiFlipPadding : SettingsMetrics.aiFlipPaddingNarrow,
                          ),
                          child: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              RotationTransition(
                                turns: _turn.animation,
                                child: Icon(
                                  YoinSymbols.refresh,
                                  size: SettingsMetrics.aiFlipIcon,
                                  color: colors.onSurface,
                                ),
                              ),
                              if (showText) ...[
                                const SizedBox(width: SettingsMetrics.aiFlipGap),
                                Text(strings.aiFlip, style: text.labelMedium!.copyWith(color: colors.onSurface)),
                              ],
                            ],
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ],
            );
          },
        ),
      ],
    );
  }
}

class _Legend extends StatelessWidget {
  const _Legend({required this.dark});

  final bool dark;

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final theme = Theme.of(context);
    return Wrap(
      spacing: SettingsMetrics.aiLegendGap,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        for (final provider in const [AiProvider.gemini, AiProvider.deepSeek, AiProvider.qwen])
          Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Container(
                width: SettingsMetrics.aiLegendDot,
                height: SettingsMetrics.aiLegendDot,
                decoration: ShapeDecoration(
                  color: aiProviderColor(provider, dark: dark),
                  shape: const CircleBorder(),
                ),
              ),
              const SizedBox(width: SettingsMetrics.aiLegendDotGap),
              Text(
                strings.aiProvider(provider),
                style: theme.textTheme.labelSmall!.copyWith(color: theme.colorScheme.onSurfaceVariant),
              ),
            ],
          ),
      ],
    );
  }
}

class _ChartPainter extends CustomPainter {
  _ChartPainter({
    required this.provider,
    required this.tier,
    required this.dark,
    required this.measured,
    required this.untested,
    required this.positions,
    required this.xAxis,
    required this.yAxis,
    required this.xName,
    required this.yName,
    required this.tierName,
    required this.untestedText,
    required this.grid,
    required this.surface,
    required this.small,
    required this.axisStyle,
    required this.dotLabel,
    required this.textDirection,
  });

  final AiProvider provider;
  final AiTier tier;
  final bool dark;
  final List<AiTierPoint> measured;
  final List<AiTierPoint> untested;
  final List<Offset> positions;
  final AiAxis xAxis;
  final AiAxis yAxis;
  final String xName;
  final String yName;
  final String Function(AiTier) tierName;
  final String untestedText;
  final Color grid;
  final Color surface;
  final TextStyle small;
  final TextStyle axisStyle;
  final TextStyle dotLabel;
  final TextDirection textDirection;

  TextPainter _layout(String text, TextStyle style) => TextPainter(
    text: TextSpan(text: text, style: style),
    textDirection: textDirection,
    maxLines: 1,
  )..layout();

  void _drawEnd(Canvas canvas, String text, Offset anchor, {required bool alignEnd}) {
    final t = _layout(text, small);
    t.paint(canvas, Offset(alignEnd ? anchor.dx - t.width : anchor.dx, anchor.dy));
  }

  @override
  void paint(Canvas canvas, Size size) {
    const left = SettingsMetrics.aiChartLeft;
    final right = size.width - SettingsMetrics.aiChartRight;
    const top = SettingsMetrics.aiChartTop;
    final bottom = size.height - SettingsMetrics.aiChartBottom;
    const steps = SettingsMetrics.aiChartGridSteps;
    final gridPaint = Paint()
      ..color = grid
      ..strokeWidth = SettingsMetrics.aiChartGridStroke;
    for (var i = 0; i <= steps; i++) {
      final gx = left + (right - left) * i / steps;
      final gy = top + (bottom - top) * i / steps;
      canvas.drawLine(Offset(gx, top), Offset(gx, bottom), gridPaint);
      canvas.drawLine(Offset(left, gy), Offset(right, gy), gridPaint);
    }
    Offset at(Offset f) => Offset(left + f.dx * (right - left), bottom - f.dy * (bottom - top));

    // Axis ends: worst at the origin, best at the far end.
    _drawEnd(
      canvas,
      xAxis.endLabel(worst: true),
      Offset(left, bottom + SettingsMetrics.aiChartXEndGap),
      alignEnd: false,
    );
    _drawEnd(
      canvas,
      xAxis.endLabel(worst: false),
      Offset(right, bottom + SettingsMetrics.aiChartXEndGap),
      alignEnd: true,
    );
    _drawEnd(
      canvas,
      yAxis.endLabel(worst: true),
      Offset(left - SettingsMetrics.aiChartYEndGap, bottom - SettingsMetrics.aiChartYWorstLift),
      alignEnd: true,
    );
    _drawEnd(canvas, yAxis.endLabel(worst: false), Offset(left - SettingsMetrics.aiChartYEndGap, top), alignEnd: true);
    final xText = _layout('$xName →', axisStyle);
    xText.paint(canvas, Offset((left + right - xText.width) / 2, bottom + SettingsMetrics.aiChartXLabelTop));
    const gutter = left - SettingsMetrics.aiChartYEndGap;
    final yText = _layout('↑$yName', axisStyle);
    if (yText.width <= gutter) {
      yText.paint(canvas, Offset(gutter - yText.width, (top + bottom - yText.height) / 2));
    } else {
      // Too long to sit beside the plot (「More accurate」): it runs up the axis instead,
      // written like the x label, so its arrow still points at the better end.
      final along = _layout('$yName →', axisStyle);
      final centre = Offset(along.height / 2, (top + bottom) / 2);
      canvas
        ..save()
        ..translate(centre.dx, centre.dy)
        ..rotate(-math.pi / 2);
      along.paint(canvas, Offset(-along.width / 2, -along.height / 2));
      canvas.restore();
    }

    for (final (i, point) in untested.indexed) {
      final mine = point.provider == provider;
      final chosen = mine && point.tier == tier;
      final c = Offset(
        right - (SettingsMetrics.aiUntestedInset + i * SettingsMetrics.aiUntestedStep),
        bottom - SettingsMetrics.aiUntestedInset,
      );
      _dashedCircle(
        canvas,
        c,
        chosen ? SettingsMetrics.aiDotPicked : SettingsMetrics.aiDot,
        aiProviderColor(point.provider, dark: dark).withValues(alpha: mine ? 1 : SettingsMetrics.aiUntestedDimAlpha),
      );
      if (mine && i == untested.length - 1) {
        final t = _layout(untestedText, small);
        t.paint(canvas, Offset(c.dx - SettingsMetrics.aiUntestedInset - t.width, c.dy - t.height / 2));
      }
    }

    for (final (i, point) in measured.indexed) {
      final mine = provider == AiProvider.custom || point.provider == provider;
      final chosen = point.provider == provider && point.tier == tier;
      final alpha = provider == AiProvider.qwen || !mine ? SettingsMetrics.aiDotDimAlpha : 1.0;
      final color = aiProviderColor(point.provider, dark: dark);
      final c = at(positions[i]);
      if (chosen) {
        canvas.drawCircle(
          c,
          SettingsMetrics.aiDotHalo,
          Paint()..color = color.withValues(alpha: SettingsMetrics.aiDotHaloAlpha),
        );
      }
      canvas.drawCircle(
        c,
        chosen ? SettingsMetrics.aiDotRingPicked : SettingsMetrics.aiDotRing,
        Paint()..color = surface,
      );
      canvas.drawCircle(
        c,
        chosen ? SettingsMetrics.aiDotPicked : SettingsMetrics.aiDot,
        Paint()..color = color.withValues(alpha: alpha),
      );
      if (point.provider == provider) {
        final weight = chosen ? 700.0 : 500.0;
        final t = _layout(
          tierName(point.tier),
          dotLabel.copyWith(
            fontWeight: chosen ? FontWeight.w700 : FontWeight.w500,
            fontVariations: [FontVariation.weight(weight)],
          ),
        );
        t.paint(canvas, Offset(c.dx + SettingsMetrics.aiDotLabelGap, c.dy - t.height / 2));
      }
    }
  }

  /// A hollow dot drawn as dashes round its edge (PathEffect.dashPathEffect).
  void _dashedCircle(Canvas canvas, Offset centre, double radius, Color color) {
    final paint = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = SettingsMetrics.aiUntestedStroke;
    final rect = Rect.fromCircle(center: centre, radius: radius);
    final circumference = 2 * math.pi * radius;
    const period = SettingsMetrics.aiUntestedDash + SettingsMetrics.aiUntestedDashGap;
    for (var d = 0.0; d < circumference; d += period) {
      final sweep = math.min(SettingsMetrics.aiUntestedDash, circumference - d) / radius;
      canvas.drawArc(rect, d / radius - math.pi / 2, sweep, false, paint);
    }
  }

  @override
  bool shouldRepaint(_ChartPainter old) =>
      old.provider != provider ||
      old.tier != tier ||
      old.dark != dark ||
      old.xAxis != xAxis ||
      old.yAxis != yAxis ||
      old.xName != xName ||
      old.grid != grid ||
      old.surface != surface ||
      old.small != small ||
      !_samePositions(old.positions, positions);

  static bool _samePositions(List<Offset> a, List<Offset> b) {
    if (a.length != b.length) return false;
    for (var i = 0; i < a.length; i++) {
      if (a[i] != b[i]) return false;
    }
    return true;
  }
}
