import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../data/ai/ai_settings.dart';
import '../nowplaying/haptics.dart';
import '../theme/motion.dart';
import 'ai_chart_labels.dart';
import 'ai_settings_screen.dart' show AiSpringSwap;
import 'ai_tier_benchmark.dart';
import 'settings_components.dart';
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

/// Custom's search services on the chart (searchServiceColor), made the way
/// [aiProviderColor] is: a pair per theme. DeepSeek keeps its service colour;
/// No search, the baseline, is the scheme's [outline].
Color aiSearchServiceColor(AiSearchService service, {required bool dark, required Color outline}) => switch (service) {
  AiSearchService.deepSeek => aiProviderColor(AiProvider.deepSeek, dark: dark),
  AiSearchService.exa => dark ? const Color(0xFFB9A3FF) : const Color(0xFF6A4CD6),
  AiSearchService.tavily => aiProviderColor(AiProvider.gemini, dark: dark),
  AiSearchService.brave => aiProviderColor(AiProvider.qwen, dark: dark),
  AiSearchService.doubao => aiProviderColor(AiProvider.custom, dark: dark),
  AiSearchService.none => outline,
};

/// Every dot the chart can show, in a fixed order: the tiers, then Custom's search services.
final List<(AiChartPoint, AiChartSet)> _allDots = [
  for (final p in AiTierBenchmark.points) (p, AiChartSet.tiers),
  for (final p in AiSearchBenchmark.points) (p, AiChartSet.search),
];

/// One dot as drawn this frame. [label] is null for a dot that goes unnamed.
class _Dot {
  const _Dot({
    required this.point,
    required this.set,
    required this.color,
    required this.label,
    required this.chosen,
    required this.mine,
    required this.named,
  });

  final AiChartPoint point;
  final AiChartSet set;
  final Color color;
  final TextPainter? label;

  /// Picked: the tier in use (its service's), or Custom's search service.
  final bool chosen;

  /// Drawn at full strength; another service's tiers are faded.
  final bool mine;

  /// Its name shows (alpha aside): the chosen service's tiers, or any search dot (faded in when chosen).
  final bool named;
}

/// Every tier as a dot, or with Custom every search service Custom can use
/// (DeepSeek writing with each) (TradeoffChart). It opens on cost against the
/// overall score (accuracy and speed together); the button beside the legend
/// steps through [aiChartViews], the other three being two of speed / cost /
/// accuracy, and the dots spring to their new places. Each set has its own
/// axis ranges ([AiChartSet.range]); going to or from Custom cross-fades the
/// two sets. The chosen dot grows and haloes on a spring, so picking another
/// tier or search moves the highlight. A dot whose numbers are estimates says
/// so in its name.
class AiTradeoffChart extends StatefulWidget {
  const AiTradeoffChart({super.key, required this.provider, required this.tier, required this.searchService});

  final AiProvider provider;

  /// The tier in use ([AiSettings.activeTier]).
  final AiTier tier;
  final AiSearchService searchService;

  @override
  State<AiTradeoffChart> createState() => _AiTradeoffChartState();
}

class _AiTradeoffChartState extends State<AiTradeoffChart> with TickerProviderStateMixin {
  int _view = 0;
  int _turns = 0;

  AiChartPoint? get _chosen => chosenChartPoint(widget.provider, widget.tier, widget.searchService);

  SpringValue _spring(double initial, SpringDescription spring) =>
      SpringValue(vsync: this, initial: initial, spring: spring);

  // Fixed order and count: one pair of springs per dot, each on its own set's ranges.
  late final List<(SpringValue, SpringValue)> _positions = [
    for (final (p, set) in _allDots)
      (
        _spring(aiChartViews.first.$1.fraction(p, set), YoinSprings.defaultSpatial),
        _spring(aiChartViews.first.$2.fraction(p, set), YoinSprings.defaultSpatial),
      ),
  ];

  // 0 → 1 as a dot becomes the chosen one. Its size moves on the spatial spring; its halo and a
  // search dot's name fade on the effects spring.
  late final List<SpringValue> _emphasis = [
    for (final (p, _) in _allDots) _spring(identical(p, _chosen) ? 1 : 0, YoinSprings.defaultSpatial),
  ];
  late final List<SpringValue> _glow = [
    for (final (p, _) in _allDots) _spring(identical(p, _chosen) ? 1 : 0, YoinSprings.defaultEffects),
  ];
  late final SpringValue _searchShown = _spring(
    AiChartSet.forProvider(widget.provider) == AiChartSet.search ? 1 : 0,
    YoinSprings.defaultEffects,
  );
  late final SpringValue _turn = _spring(0, YoinSprings.defaultSpatial);

  /// Each name's top-left from its dot, on the spatial spring; made at the first layout.
  final List<(SpringValue, SpringValue)?> _labelAt = List.filled(_allDots.length, null);

  bool get _reduce => MediaQuery.maybeDisableAnimationsOf(context) ?? false;

  void _go(SpringValue v, double target) => _reduce ? v.snapTo(target) : v.animateTo(target);

  @override
  void didUpdateWidget(AiTradeoffChart old) {
    super.didUpdateWidget(old);
    final chosen = _chosen;
    for (final (i, (p, _)) in _allDots.indexed) {
      _go(_emphasis[i], identical(p, chosen) ? 1 : 0);
      _go(_glow[i], identical(p, chosen) ? 1 : 0);
    }
    _go(_searchShown, AiChartSet.forProvider(widget.provider) == AiChartSet.search ? 1 : 0);
  }

  @override
  void dispose() {
    for (final (x, y) in _positions) {
      x.dispose();
      y.dispose();
    }
    for (final v in [..._emphasis, ..._glow, _searchShown, _turn]) {
      v.dispose();
    }
    for (final at in _labelAt) {
      at?.$1.dispose();
      at?.$2.dispose();
    }
    super.dispose();
  }

  void _flip() {
    YoinHaptics.segmentTick();
    setState(() {
      _view = (_view + 1) % aiChartViews.length;
      _turns++;
    });
    final (xAxis, yAxis) = aiChartViews[_view];
    for (final (i, (p, set)) in _allDots.indexed) {
      final (x, y) = _positions[i];
      _go(x, xAxis.fraction(p, set));
      _go(y, yAxis.fraction(p, set));
    }
    _go(_turn, _turns * 0.5);
  }

  /// The chart's plot in a canvas of [size]: room for the y values on the left and the x axis below.
  static Rect plotIn(Size size) => Rect.fromLTRB(
    SettingsMetrics.aiChartLeft,
    SettingsMetrics.aiChartTop,
    size.width - SettingsMetrics.aiChartRight,
    size.height - SettingsMetrics.aiChartBottom,
  );

  List<_Dot> _dots(BuildContext context, TextStyle dotLabel, TextScaler scaler) {
    final strings = SettingsStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final dark = colors.surface.computeLuminance() < 0.5;
    final chosen = _chosen;
    TextPainter? name(String text, {required bool bold}) {
      if (text.isEmpty) return null;
      final weight = bold ? 700.0 : 500.0;
      return TextPainter(
        text: TextSpan(
          text: text,
          style: dotLabel.copyWith(
            fontWeight: bold ? FontWeight.w700 : FontWeight.w500,
            fontVariations: [FontVariation.weight(weight)],
          ),
        ),
        textDirection: Directionality.of(context),
        textScaler: scaler,
        maxLines: 1,
      )..layout();
    }

    return [
      for (final (point, set) in _allDots)
        switch (point) {
          AiTierPoint() => _Dot(
            point: point,
            set: set,
            color: aiProviderColor(point.provider, dark: dark),
            // A service with one tier has no tier to name: its dot goes without. An estimated
            // tier's name carries the mark the tier page puts on an estimated cost.
            label: name(
              point.provider.tiers.length <= 1
                  ? ''
                  : point.estimated
                  ? strings.aiTierEstimated(strings.aiTier(point.tier))
                  : strings.aiTier(point.tier),
              bold: point.tier == widget.tier,
            ),
            chosen: identical(point, chosen),
            mine: point.provider == widget.provider,
            named: point.provider == widget.provider,
          ),
          AiSearchPoint() => _Dot(
            point: point,
            set: set,
            color: aiSearchServiceColor(point.service, dark: dark, outline: colors.outline),
            label: name(
              point.estimated
                  ? strings.aiTierEstimated(strings.aiSearchService(point.service))
                  : strings.aiSearchService(point.service),
              bold: true,
            ),
            chosen: identical(point, chosen),
            mine: true,
            named: true,
          ),
        },
    ];
  }

  /// Where each name goes for the view on screen. Every name has a place
  /// worked out, so picking a service shows its names where they belong
  /// rather than sliding them there.
  void _placeLabels(List<_Dot> dots, Size size) {
    final plot = plotIn(size);
    final (xAxis, yAxis) = aiChartViews[_view];
    final centres = [
      for (final d in dots)
        Offset(
          plot.left + xAxis.fraction(d.point, d.set) * plot.width,
          plot.bottom - yAxis.fraction(d.point, d.set) * plot.height,
        ),
    ];
    final targets = List<Offset>.filled(dots.length, Offset.zero);
    for (final set in AiChartSet.values) {
      final inSet = [
        for (var i = 0; i < dots.length; i++)
          if (dots[i].set == set) i,
      ];
      final setCentres = [for (final i in inSet) centres[i]];
      // A tier's names are placed with its service's other tier (both show); a search
      // service's name shows alone, so each is placed on its own.
      final groups = <String, List<int>>{};
      for (var k = 0; k < inSet.length; k++) {
        if (dots[inSet[k]].label == null) continue;
        final key = switch (dots[inSet[k]].point) {
          AiTierPoint(:final provider) => provider.name,
          AiSearchPoint(:final service) => service.name,
        };
        (groups[key] ??= []).add(k);
      }
      for (final group in groups.values) {
        final placed = AiChartLabels.place(
          plot: plot,
          dots: setCentres,
          dotRadius: SettingsMetrics.aiDotLabelKeepOff,
          gap: SettingsMetrics.aiDotLabelGap,
          labels: [for (final k in group) (k, dots[inSet[k]].label!.size)],
        );
        for (final (n, k) in group.indexed) {
          targets[inSet[k]] = placed[n];
        }
      }
    }
    for (final (i, target) in targets.indexed) {
      final at = _labelAt[i];
      if (at == null) {
        _labelAt[i] = (_spring(target.dx, YoinSprings.defaultSpatial), _spring(target.dy, YoinSprings.defaultSpatial));
      } else {
        _go(at.$1, target.dx);
        _go(at.$2, target.dy);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final text = theme.textTheme;
    final dark = colors.surface.computeLuminance() < 0.5;
    final scaler = MediaQuery.textScalerOf(context);
    final (xAxis, yAxis) = aiChartViews[_view];
    final set = AiChartSet.forProvider(widget.provider);
    String axisName(AiAxis axis) => switch (axis) {
      AiAxis.speed => strings.aiAxisSpeed,
      AiAxis.cost => strings.aiAxisCost,
      AiAxis.accuracy => strings.aiAxisAccuracy,
      AiAxis.overall => strings.aiAxisOverall,
    };
    // From the type scale: a bare TextStyle has no font family, and the canvas would draw the
    // system font instead of the app's.
    final dotLabel = text.labelMedium!.copyWith(color: colors.onSurface);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SizedBox(
          height: SettingsMetrics.aiChartHeight,
          child: LayoutBuilder(
            builder: (context, constraints) {
              final size = Size(constraints.maxWidth, SettingsMetrics.aiChartHeight);
              final dots = _dots(context, dotLabel, scaler);
              _placeLabels(dots, size);
              return AnimatedBuilder(
                animation: Listenable.merge([
                  for (final (x, y) in _positions) ...[x.animation, y.animation],
                  for (final at in _labelAt) ...[?at?.$1.animation, ?at?.$2.animation],
                  ..._emphasis.map((v) => v.animation),
                  ..._glow.map((v) => v.animation),
                  _searchShown.animation,
                ]),
                builder: (context, _) => CustomPaint(
                  key: const ValueKey('ai-chart'),
                  size: size,
                  painter: _ChartPainter(
                    dots: dots,
                    positions: [for (final (x, y) in _positions) Offset(x.value, y.value)],
                    labelOffsets: [
                      for (final at in _labelAt) at == null ? Offset.zero : Offset(at.$1.value, at.$2.value),
                    ],
                    emphasis: [for (final v in _emphasis) v.value],
                    glow: [for (final v in _glow) v.value.clamp(0.0, 1.0)],
                    searchShown: _searchShown.value.clamp(0.0, 1.0),
                    xAxis: xAxis,
                    yAxis: yAxis,
                    xName: axisName(xAxis),
                    yName: axisName(yAxis),
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
                    textDirection: Directionality.of(context),
                    textScaler: scaler,
                  ),
                ),
              );
            },
          ),
        ),
        const SizedBox(height: SettingsMetrics.aiChartGap),
        LayoutBuilder(
          builder: (context, constraints) {
            final showText = constraints.maxWidth >= SettingsMetrics.aiFlipTextMinWidth;
            return Row(
              children: [
                Expanded(
                  child: AiSpringSwap(
                    childKey: set,
                    child: _Legend(set: set, dark: dark),
                  ),
                ),
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
        SpringReveal(
          visible: set == AiChartSet.search,
          child: Padding(
            padding: const EdgeInsetsDirectional.only(
              start: SettingsMetrics.aiSectionLabelInset,
              top: SettingsMetrics.aiChartNoteTop,
            ),
            child: Text(
              strings.aiSearchChartNote,
              key: const ValueKey('ai-search-chart-note'),
              style: text.bodySmall!.copyWith(color: colors.onSurfaceVariant),
            ),
          ),
        ),
      ],
    );
  }
}

/// The colours under the chart: the three tiered services, or Custom's search services.
class _Legend extends StatelessWidget {
  const _Legend({required this.set, required this.dark});

  final AiChartSet set;
  final bool dark;

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final theme = Theme.of(context);
    final outline = theme.colorScheme.outline;
    final entries = switch (set) {
      AiChartSet.tiers => [
        for (final p in const [AiProvider.gemini, AiProvider.deepSeek, AiProvider.qwen])
          (strings.aiProvider(p), aiProviderColor(p, dark: dark)),
      ],
      AiChartSet.search => [
        for (final p in AiSearchBenchmark.points)
          (strings.aiSearchService(p.service), aiSearchServiceColor(p.service, dark: dark, outline: outline)),
      ],
    };
    return Wrap(
      key: ValueKey('ai-legend-${set.name}'),
      spacing: SettingsMetrics.aiLegendGap,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        for (final (name, color) in entries)
          Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Container(
                width: SettingsMetrics.aiLegendDot,
                height: SettingsMetrics.aiLegendDot,
                decoration: ShapeDecoration(color: color, shape: const CircleBorder()),
              ),
              const SizedBox(width: SettingsMetrics.aiLegendDotGap),
              Text(name, style: theme.textTheme.labelSmall!.copyWith(color: theme.colorScheme.onSurfaceVariant)),
            ],
          ),
      ],
    );
  }
}

class _ChartPainter extends CustomPainter {
  _ChartPainter({
    required this.dots,
    required this.positions,
    required this.labelOffsets,
    required this.emphasis,
    required this.glow,
    required this.searchShown,
    required this.xAxis,
    required this.yAxis,
    required this.xName,
    required this.yName,
    required this.grid,
    required this.surface,
    required this.small,
    required this.axisStyle,
    required this.textDirection,
    required this.textScaler,
  });

  final List<_Dot> dots;
  final List<Offset> positions;
  final List<Offset> labelOffsets;
  final List<double> emphasis;
  final List<double> glow;
  final double searchShown;
  final AiAxis xAxis;
  final AiAxis yAxis;
  final String xName;
  final String yName;
  final Color grid;
  final Color surface;
  final TextStyle small;
  final TextStyle axisStyle;
  final TextDirection textDirection;
  final TextScaler textScaler;

  double _setAlpha(AiChartSet set) => set == AiChartSet.search ? searchShown : 1 - searchShown;

  TextPainter _layout(String text, TextStyle style) => TextPainter(
    text: TextSpan(text: text, style: style),
    textDirection: textDirection,
    textScaler: textScaler,
    maxLines: 1,
  )..layout();

  void _paintFaded(Canvas canvas, TextPainter t, Offset at, double alpha) {
    if (alpha <= 0.001) return;
    if (alpha >= 0.999) {
      t.paint(canvas, at);
      return;
    }
    canvas.saveLayer(at & t.size, Paint()..color = Color.fromRGBO(0, 0, 0, alpha));
    t.paint(canvas, at);
    canvas.restore();
  }

  void _drawEnd(Canvas canvas, String text, Offset anchor, {required bool alignEnd, double alpha = 1}) {
    final t = _layout(text, small);
    _paintFaded(canvas, t, Offset(alignEnd ? anchor.dx - t.width : anchor.dx, anchor.dy), alpha);
  }

  /// Axis ends: worst at the origin, best at the far end. An end the two sets
  /// print differently cross-fades with them.
  void _drawEnds(Canvas canvas, AiAxis axis, {required bool worst, required Offset anchor, required bool alignEnd}) {
    final tiers = axisValue(axis, AiChartSet.tiers, worst: worst);
    final search = axisValue(axis, AiChartSet.search, worst: worst);
    if (tiers == search) {
      _drawEnd(canvas, tiers, anchor, alignEnd: alignEnd);
    } else {
      _drawEnd(canvas, tiers, anchor, alignEnd: alignEnd, alpha: _setAlpha(AiChartSet.tiers));
      _drawEnd(canvas, search, anchor, alignEnd: alignEnd, alpha: _setAlpha(AiChartSet.search));
    }
  }

  @override
  void paint(Canvas canvas, Size size) {
    final plot = _AiTradeoffChartState.plotIn(size);
    final Rect(:left, :right, :top, :bottom) = plot;
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

    final xEnds = bottom + SettingsMetrics.aiChartXEndGap;
    final yEnds = left - SettingsMetrics.aiChartYEndGap;
    _drawEnds(canvas, xAxis, worst: true, anchor: Offset(left, xEnds), alignEnd: false);
    _drawEnds(canvas, xAxis, worst: false, anchor: Offset(right, xEnds), alignEnd: true);
    _drawEnds(
      canvas,
      yAxis,
      worst: true,
      anchor: Offset(yEnds, bottom - SettingsMetrics.aiChartYWorstLift),
      alignEnd: true,
    );
    _drawEnds(canvas, yAxis, worst: false, anchor: Offset(yEnds, top), alignEnd: true);
    final xText = _layout('$xName →', axisStyle);
    xText.paint(canvas, Offset((left + right - xText.width) / 2, bottom + SettingsMetrics.aiChartXLabelTop));
    final gutter = left - SettingsMetrics.aiChartYEndGap;
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

    // Set by set; in each, the chosen dot last: two dots can overlap, and the chosen one stays whole.
    final order = [for (var i = 0; i < dots.length; i++) i]
      ..sort((a, b) {
        final bySet = dots[a].set.index.compareTo(dots[b].set.index);
        if (bySet != 0) return bySet;
        final byChosen = (dots[a].chosen ? 1 : 0).compareTo(dots[b].chosen ? 1 : 0);
        return byChosen != 0 ? byChosen : a.compareTo(b);
      });
    for (final i in order) {
      final dot = dots[i];
      final shown = _setAlpha(dot.set);
      if (shown <= 0.001) continue;
      final e = emphasis[i];
      final g = glow[i];
      final alpha = (dot.mine ? 1.0 : SettingsMetrics.aiDotDimAlpha) * shown;
      final c = at(positions[i]);
      if (g > 0) {
        canvas.drawCircle(
          c,
          SettingsMetrics.aiDotHalo,
          Paint()..color = dot.color.withValues(alpha: SettingsMetrics.aiDotHaloAlpha * g * shown),
        );
      }
      canvas.drawCircle(
        c,
        SettingsMetrics.aiDotRing + SettingsMetrics.aiDotGrow * e,
        Paint()..color = surface.withValues(alpha: surface.a * shown),
      );
      canvas.drawCircle(
        c,
        SettingsMetrics.aiDot + SettingsMetrics.aiDotGrow * e,
        Paint()..color = dot.color.withValues(alpha: dot.color.a * alpha),
      );
      final name = dot.label;
      if (dot.named && name != null) {
        // A search dot's name shows only on the chosen one, fading in as it is picked.
        final nameAlpha = dot.set == AiChartSet.search ? g : 1.0;
        _paintFaded(canvas, name, c + labelOffsets[i], nameAlpha * shown);
      }
    }
  }

  // Every frame of a spring brings new numbers; a rebuild with none brings new text or colours.
  @override
  bool shouldRepaint(_ChartPainter old) => true;
}
