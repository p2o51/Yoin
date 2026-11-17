import '../data/ai/ai_settings.dart';

/// What the AI page's chart plots of one dot (Android AiTierBenchmark.kt
/// AiChartPoint): a service tier ([AiTierPoint]) or, while Custom is chosen, a
/// search service ([AiSearchPoint]). Both are scored the same way.
sealed class AiChartPoint {
  const AiChartPoint();

  double get accuracy;
  double get seconds;
  double get costPer1000;

  /// Every number is an estimate: it was not run on the 499 songs.
  bool get estimated;

  /// 0–100: [AiTierPoint.fastestSeconds] or less is 100, [AiTierPoint.slowestSeconds] or more is 0.
  double get speedScore =>
      ((AiTierPoint.slowestSeconds - seconds) / (AiTierPoint.slowestSeconds - AiTierPoint.fastestSeconds) * 100).clamp(
        0.0,
        100.0,
      );

  /// 0–100: accuracy and speed in one number, accuracy weighing more (About is written once).
  double get overall => AiTierPoint.accuracyWeight * accuracy + (1 - AiTierPoint.accuracyWeight) * speedScore;
}

/// One tier's numbers, behind the AI page's chart and the tier page.
///
/// Source: the 500-song benchmark of 2026-11-13 (499 songs after duplicates),
/// About prompt in Simplified Chinese, no thinking. [seconds] is the median
/// over all of them. [accuracy] is the mean of two scores a Gemini agent gave
/// every answer against reference answers: the five fields (release date,
/// place, lyricist, composer, producer; fields no public source settles left
/// out, an empty field counting as zero) and the review paragraph's
/// factuality. [costPer1000] is US$ for 1,000 About calls at list prices,
/// tokens plus search; Gemini's assumes the monthly 5,000 free searches, and
/// 3.5 Flash-Lite's token price is a third party's figure. Qwen's is converted
/// from yuan at 7.1. DeepSeek's two-search tier was not run on this set: its
/// numbers are estimates ([estimated]). Qwen has one tier ([AiProvider.tiers]).
final class AiTierPoint extends AiChartPoint {
  const AiTierPoint(
    this.provider,
    this.tier, {
    required this.accuracy,
    required this.seconds,
    required this.costPer1000,
    this.costEstimated = false,
    this.estimated = false,
  });

  static const double fastestSeconds = 1;
  static const double slowestSeconds = 10;
  static const double accuracyWeight = 0.7;

  final AiProvider provider;
  final AiTier tier;
  @override
  final double accuracy;
  @override
  final double seconds;
  @override
  final double costPer1000;

  /// Only the cost is an estimate (Gemini 3.5 Flash-Lite's third-party token price).
  final bool costEstimated;

  /// Every number is an estimate: the tier was not run on the benchmark.
  @override
  final bool estimated;
}

abstract final class AiTierBenchmark {
  static const List<AiTierPoint> points = [
    AiTierPoint(AiProvider.gemini, AiTier.good, accuracy: 95, seconds: 2.8, costPer1000: 0.54),
    AiTierPoint(AiProvider.gemini, AiTier.best, accuracy: 95, seconds: 2.6, costPer1000: 0.95, costEstimated: true),
    AiTierPoint(AiProvider.deepSeek, AiTier.good, accuracy: 92, seconds: 3.7, costPer1000: 2.89),
    // Not run on the 500 songs, so estimated (owner 2026-11-14: "a little stronger; it searches
    // one more time"). In the 35-song test of 2026-11-12, Best was +2 accuracy points, +0.7 s and
    // x1.35 cost over Good. Applied to Good's 500-song 92 / 3.7 s / $2.89, with the accuracy gain
    // halved because the 500-song accuracy averages two scores: 93 / 4.4 s / $3.9.
    AiTierPoint(AiProvider.deepSeek, AiTier.best, accuracy: 93, seconds: 4.4, costPer1000: 3.9, estimated: true),
    AiTierPoint(AiProvider.qwen, AiTier.good, accuracy: 83, seconds: 4.9, costPer1000: 0.57),
  ];

  static AiTierPoint? point(AiProvider provider, AiTier tier) =>
      points.where((p) => p.provider == provider && p.tier == tier).firstOrNull;
}

/// Custom's search services, for the chart while Custom is chosen (owner
/// 2026-11-14): the writing model is fixed and only the search changes, so a
/// dot is what a search service does for About.
///
/// Source: the same 499-song benchmark of 2026-11-13 (report `yoin_499.html`),
/// writing model deepseek-flash for every service, About prompt in Simplified
/// Chinese, no thinking. [accuracy] is the mean of the field accuracy and the
/// review fact score, as for the tiers; [seconds] the median; [costPer1000]
/// US$ per 1,000 About calls, search plus DeepSeek API tokens.
///  - DeepSeek search: field 85.0, review 98.8, so 91.9; 3.69 s; $2.89 ($0.75 search + $2.14 tokens).
///  - Exa (with page text): field 86.5, review 98.9, so 92.7; 3.94 s; $9.04 ($7.00 + $2.04).
///  - Doubao (Volcengine web search): field 79.4, review 97.0, so 88.2; 4.47 s; $2.38 pay-as-you-go
///    ($0.56 + $1.82). Inside a monthly search package it is the tokens alone, $1.82.
///  - No search (deepseek-flash alone): field 41.3, review 88.7, so 65.0; 2.40 s; $0.32.
///
/// Tavily and Brave were not run on the 499 songs, only in the 35-song side
/// test of the same report (same writer, 66 fields, a field hit rate and no
/// review score): DeepSeek Search 83.3 %, Tavily advanced (snippets) 75.8 % at
/// 5.74 s and $17.17, Brave (summary only) 60.3 % at 3.19 s and $5.68. Their
/// field accuracy is DeepSeek search's 499-song 85.0 scaled by hit rate
/// against DeepSeek's 83.3 %, their review score is taken as DeepSeek search's
/// 98.8, and the two are averaged ([AiSearchBenchmark.estimateAccuracy]):
/// Tavily 77.3 and 98.8, so 88; Brave 61.5 and 98.8, so 80. Seconds and cost
/// are the side test's own. Yoin's Brave call reads page text through Brave's
/// LLM Context endpoint first, which the side test's summary-only run did not,
/// so Brave may do better than this.
final class AiSearchPoint extends AiChartPoint {
  const AiSearchPoint(
    this.service, {
    required this.accuracy,
    required this.seconds,
    required this.costPer1000,
    this.estimated = false,
  });

  final AiSearchService service;
  @override
  final double accuracy;
  @override
  final double seconds;
  @override
  final double costPer1000;
  @override
  final bool estimated;
}

abstract final class AiSearchBenchmark {
  /// DeepSeek search on the 499 songs: the yardstick the side test's estimates are scaled by.
  static const double deepSeekField = 85.0;
  static const double deepSeekReview = 98.8;

  /// DeepSeek Search's field hit rate in the 35-song side test.
  static const double deepSeekSideHit = 83.3;
  static const double tavilySideHit = 75.8;
  static const double braveSideHit = 60.3;

  /// A 499-song accuracy for a service seen only in the side test, from its field hit rate there.
  static double estimateAccuracy(double sideHitRate) =>
      (deepSeekField * sideHitRate / deepSeekSideHit + deepSeekReview) / 2;

  /// In [AiSearchService] order, one per service.
  static const List<AiSearchPoint> points = [
    AiSearchPoint(AiSearchService.deepSeek, accuracy: 91.9, seconds: 3.69, costPer1000: 2.89),
    AiSearchPoint(AiSearchService.exa, accuracy: 92.7, seconds: 3.94, costPer1000: 9.04),
    // estimateAccuracy(75.8) = 88.07.
    AiSearchPoint(AiSearchService.tavily, accuracy: 88, seconds: 5.7, costPer1000: 17.2, estimated: true),
    // estimateAccuracy(60.3) = 80.17.
    AiSearchPoint(AiSearchService.brave, accuracy: 80, seconds: 3.2, costPer1000: 5.7, estimated: true),
    AiSearchPoint(AiSearchService.doubao, accuracy: 88.2, seconds: 4.47, costPer1000: 2.38),
    AiSearchPoint(AiSearchService.none, accuracy: 65.0, seconds: 2.40, costPer1000: 0.32),
  ];

  static AiSearchPoint point(AiSearchService service) => points.firstWhere((p) => p.service == service);
}

/// An axis range, min first.
typedef AiRange = ({double min, double max});

/// Which dots the chart shows, each with axis ranges of its own (AiChartSet):
/// Custom's search costs run to Tavily's $17, and No search sits far below
/// every tier's accuracy.
enum AiChartSet {
  tiers,
  search;

  List<AiChartPoint> get points => switch (this) {
    tiers => AiTierBenchmark.points,
    search => AiSearchBenchmark.points,
  };

  /// The axis's worse-or-better ends in this set, min first.
  AiRange range(AiAxis axis) => switch (axis) {
    AiAxis.speed => (min: AiTierPoint.fastestSeconds, max: AiTierPoint.slowestSeconds),
    AiAxis.cost => this == tiers ? (min: 0.0, max: 4.0) : (min: 0.0, max: 20.0),
    AiAxis.accuracy => this == tiers ? (min: 70.0, max: 100.0) : (min: 60.0, max: 100.0),
    AiAxis.overall => (min: 50.0, max: 100.0),
  };

  static AiChartSet forProvider(AiProvider provider) => provider == AiProvider.custom ? search : tiers;
}

/// One of the chart's axes (AiAxis). Every axis reads "better" toward the top / right.
enum AiAxis {
  speed(higherIsBetter: false),
  cost(higherIsBetter: false),
  accuracy(higherIsBetter: true),

  /// [AiChartPoint.overall]: accuracy and speed together.
  overall(higherIsBetter: true);

  const AiAxis({required this.higherIsBetter});

  final bool higherIsBetter;

  double value(AiChartPoint point) => switch (this) {
    speed => point.seconds,
    cost => point.costPer1000,
    accuracy => point.accuracy,
    overall => point.overall,
  };

  /// 0 = worst edge, 1 = best edge, on [set]'s range for this axis.
  double fraction(AiChartPoint point, AiChartSet set) {
    final r = set.range(this);
    final f = ((value(point) - r.min) / (r.max - r.min)).clamp(0.0, 1.0);
    return higherIsBetter ? f : 1 - f;
  }
}

/// The views 「换个角度」 steps through: (x, y) (AiChartViews). The first is
/// what the page opens on: what it costs against how good it is overall. The
/// other three set the plain measures against each other. Both sets use the
/// same views.
const List<(AiAxis, AiAxis)> aiChartViews = [
  (AiAxis.cost, AiAxis.overall),
  (AiAxis.speed, AiAxis.accuracy),
  (AiAxis.cost, AiAxis.accuracy),
  (AiAxis.speed, AiAxis.cost),
];

/// The dot the chart highlights: the tier in use, or with Custom the search
/// service picked (chosenChartPoint).
AiChartPoint? chosenChartPoint(AiProvider provider, AiTier tier, AiSearchService searchService) =>
    switch (AiChartSet.forProvider(provider)) {
      AiChartSet.tiers => AiTierBenchmark.point(provider, tier),
      AiChartSet.search => AiSearchBenchmark.point(searchService),
    };

/// The value printed at an axis end in [set]: [worst] is the origin side (axisValue).
String axisValue(AiAxis axis, AiChartSet set, {required bool worst}) {
  final atMin = axis.higherIsBetter == worst;
  final r = set.range(axis);
  final v = trimNumber(atMin ? r.min : r.max);
  return switch (axis) {
    AiAxis.speed => '${v}s',
    AiAxis.cost => '\$$v',
    AiAxis.accuracy => '$v%',
    AiAxis.overall => v,
  };
}

/// A whole number without its ".0", else one decimal (trimNumber).
String trimNumber(double v) => v == v.truncateToDouble() ? v.toInt().toString() : v.toStringAsFixed(1);
