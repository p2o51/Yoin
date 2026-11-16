import '../data/ai/ai_settings.dart';

/// One tier's numbers (Android AiTierBenchmark.kt AiTierPoint), behind the AI
/// page's chart and the tier page.
///
/// Source: 35 hard songs checked by hand on 2026-11-12, About prompt in
/// Simplified Chinese, no thinking; [accuracy] is the share of 66 checkable
/// credit/date fields answered right. [costPer1000] is US$ for 1,000 About
/// calls at list prices; Gemini's assumes the monthly 5,000 free searches, and
/// 3.5 Flash-Lite's price was unpublished, so its cost is estimated at twice 3.1's.
final class AiTierPoint {
  const AiTierPoint(
    this.provider,
    this.tier, {
    required this.accuracy,
    required this.seconds,
    required this.costPer1000,
    this.costEstimated = false,
  });

  final AiProvider provider;
  final AiTier tier;
  final double? accuracy;
  final double? seconds;
  final double? costPer1000;
  final bool costEstimated;

  bool get measured => accuracy != null && seconds != null && costPer1000 != null;
}

abstract final class AiTierBenchmark {
  static const List<AiTierPoint> points = [
    AiTierPoint(AiProvider.gemini, AiTier.good, accuracy: 73, seconds: 2.8, costPer1000: 0.6),
    AiTierPoint(AiProvider.gemini, AiTier.best, accuracy: 83, seconds: 2.6, costPer1000: 1.2, costEstimated: true),
    AiTierPoint(AiProvider.deepSeek, AiTier.good, accuracy: 83, seconds: 3.7, costPer1000: 2.6),
    AiTierPoint(AiProvider.deepSeek, AiTier.best, accuracy: 85, seconds: 4.4, costPer1000: 3.5),
    // Not tested yet: drawn as hollow dots.
    AiTierPoint(AiProvider.qwen, AiTier.good, accuracy: null, seconds: null, costPer1000: null),
    AiTierPoint(AiProvider.qwen, AiTier.best, accuracy: null, seconds: null, costPer1000: null),
  ];

  static AiTierPoint? point(AiProvider provider, AiTier tier) =>
      points.where((p) => p.provider == provider && p.tier == tier).firstOrNull;
}

/// One of the chart's axes (AiAxis). Every axis reads "better" toward the top / right.
enum AiAxis {
  speed(2, 5, higherIsBetter: false),
  cost(0, 4, higherIsBetter: false),
  accuracy(60, 90, higherIsBetter: true);

  const AiAxis(this.min, this.max, {required this.higherIsBetter});

  final double min;
  final double max;
  final bool higherIsBetter;

  double? value(AiTierPoint point) => switch (this) {
    AiAxis.speed => point.seconds,
    AiAxis.cost => point.costPer1000,
    AiAxis.accuracy => point.accuracy,
  };

  /// 0 = worst edge, 1 = best edge.
  double? fraction(AiTierPoint point) {
    final v = value(point);
    if (v == null) return null;
    final f = ((v - min) / (max - min)).clamp(0.0, 1.0);
    return higherIsBetter ? f : 1 - f;
  }

  /// The value printed at an axis end: [worst] is the origin side (axisValue).
  String endLabel({required bool worst}) {
    final atMin = higherIsBetter == worst;
    final v = trimNumber(atMin ? min : max);
    return switch (this) {
      AiAxis.speed => '${v}s',
      AiAxis.cost => '\$$v',
      AiAxis.accuracy => '$v%',
    };
  }
}

/// The views 「换个角度」 steps through: (x, y) (AiChartViews).
const List<(AiAxis, AiAxis)> aiChartViews = [
  (AiAxis.speed, AiAxis.accuracy),
  (AiAxis.cost, AiAxis.accuracy),
  (AiAxis.speed, AiAxis.cost),
];

/// A whole number without its ".0", else one decimal (trimNumber).
String trimNumber(double v) => v == v.truncateToDouble() ? v.toInt().toString() : v.toStringAsFixed(1);
