import 'dart:ui';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/ai/ai_settings.dart';
import 'package:yoin/settings/ai_chart_labels.dart';
import 'package:yoin/settings/ai_tier_benchmark.dart';

/// Android AiTierBenchmarkTest and AiChartLabelsTest: the chart's scores, the
/// order of its views, its two sets, and where it puts a dot's name.
void main() {
  group('AiTierBenchmark', () {
    test('should_openOnCostAgainstTheOverallScore', () {
      expect(aiChartViews.first, (AiAxis.cost, AiAxis.overall));
    });

    test('should_pairThePlainMeasures_inTheOtherThreeViews', () {
      final rest = aiChartViews.skip(1).toList();
      expect(rest, hasLength(3));
      expect(rest.any((v) => v.$1 == AiAxis.overall || v.$2 == AiAxis.overall), isFalse);
      expect(rest.map((v) => {v.$1, v.$2}).toSet(), {
        {AiAxis.speed, AiAxis.accuracy},
        {AiAxis.cost, AiAxis.accuracy},
        {AiAxis.speed, AiAxis.cost},
      });
    });

    test('should_weighAccuracySevenToThree_when_scoringOverall', () {
      const point = AiTierPoint(AiProvider.gemini, AiTier.good, accuracy: 90, seconds: 5.5, costPer1000: 1);
      // 5.5 s is halfway between 1 s (100) and 10 s (0).
      expect(point.speedScore, closeTo(50, 0.01));
      expect(point.overall, closeTo(0.7 * 90 + 0.3 * 50, 0.01));
    });

    test('should_keepTheSpeedScoreBetweenZeroAndAHundred_when_outsideTheRange', () {
      const instant = AiTierPoint(AiProvider.gemini, AiTier.good, accuracy: 90, seconds: 0.4, costPer1000: 1);
      const slow = AiTierPoint(AiProvider.gemini, AiTier.good, accuracy: 90, seconds: 14, costPer1000: 1);
      expect(instant.speedScore, 100);
      expect(slow.speedScore, 0);
    });

    test('should_placeDeepSeekBestAsAnEstimate_when_aLittleAboveGood', () {
      final good = AiTierBenchmark.point(AiProvider.deepSeek, AiTier.good)!;
      final best = AiTierBenchmark.point(AiProvider.deepSeek, AiTier.best)!;
      expect(best.estimated, isTrue);
      expect(good.estimated, isFalse);
      expect((best.accuracy, best.seconds, best.costPer1000), (93.0, 4.4, 3.9));
      expect(best.overall, closeTo(0.7 * 93 + 0.3 * (10 - 4.4) / 9 * 100, 0.01));
      expect(
        best.accuracy > good.accuracy && best.seconds > good.seconds && best.costPer1000 > good.costPer1000,
        isTrue,
      );
    });

    test('should_markOnlyDeepSeekBestAsEstimated_when_listed', () {
      expect(
        [
          for (final p in AiTierBenchmark.points)
            if (p.estimated) (p.provider, p.tier),
        ],
        [(AiProvider.deepSeek, AiTier.best)],
      );
    });

    test('should_keepEveryTierInsideThePlot_inEveryView', () {
      for (final (x, y) in aiChartViews) {
        for (final point in AiTierBenchmark.points) {
          for (final axis in [x, y]) {
            final f = axis.fraction(point, AiChartSet.tiers);
            expect(f > 0 && f < 1, isTrue, reason: '$axis clips ${point.provider} ${point.tier}');
          }
        }
      }
    });

    test('should_onlyHoldTiersTheirServiceOffers_when_listed', () {
      for (final point in AiTierBenchmark.points) {
        expect(point.provider.tiers, contains(point.tier));
      }
      // Qwen has one tier, and it was measured.
      expect(AiTierBenchmark.point(AiProvider.qwen, AiTier.good)!.estimated, isFalse);
      expect(AiTierBenchmark.point(AiProvider.qwen, AiTier.best), isNull);
    });

    test('should_holdTheMeasuredSearchNumbers_withDeepSeekWritingForEach', () {
      void check(AiSearchService service, double accuracy, double seconds, double cost) {
        final point = AiSearchBenchmark.point(service);
        expect(point.estimated, isFalse, reason: '$service');
        expect((point.accuracy, point.seconds, point.costPer1000), (accuracy, seconds, cost), reason: '$service');
      }

      check(AiSearchService.deepSeek, 91.9, 3.69, 2.89);
      check(AiSearchService.exa, 92.7, 3.94, 9.04);
      check(AiSearchService.doubao, 88.2, 4.47, 2.38);
      check(AiSearchService.none, 65.0, 2.40, 0.32);
      // Each accuracy is the mean of the report's field and review scores.
      expect(AiSearchBenchmark.point(AiSearchService.deepSeek).accuracy, closeTo((85.0 + 98.8) / 2, 0.05));
      expect(AiSearchBenchmark.point(AiSearchService.exa).accuracy, closeTo((86.5 + 98.9) / 2, 0.05));
      expect(AiSearchBenchmark.point(AiSearchService.doubao).accuracy, closeTo((79.4 + 97.0) / 2, 0.05));
      expect(AiSearchBenchmark.point(AiSearchService.none).accuracy, closeTo((41.3 + 88.7) / 2, 0.05));
    });

    test('should_estimateTavilyAndBrave_fromTheSideTestsHitRates', () {
      expect(AiSearchBenchmark.estimateAccuracy(AiSearchBenchmark.tavilySideHit), closeTo(88.07, 0.01));
      expect(AiSearchBenchmark.estimateAccuracy(AiSearchBenchmark.braveSideHit), closeTo(80.17, 0.01));
      // DeepSeek's own side-test hit rate gives back its measured accuracy.
      expect(AiSearchBenchmark.estimateAccuracy(AiSearchBenchmark.deepSeekSideHit), closeTo(91.9, 0.01));

      final tavily = AiSearchBenchmark.point(AiSearchService.tavily);
      final brave = AiSearchBenchmark.point(AiSearchService.brave);
      expect(tavily.estimated && brave.estimated, isTrue);
      expect(tavily.accuracy, AiSearchBenchmark.estimateAccuracy(AiSearchBenchmark.tavilySideHit).roundToDouble());
      expect(brave.accuracy, AiSearchBenchmark.estimateAccuracy(AiSearchBenchmark.braveSideHit).roundToDouble());
      expect((tavily.seconds, tavily.costPer1000), (5.7, 17.2));
      expect((brave.seconds, brave.costPer1000), (3.2, 5.7));
      expect(
        {
          for (final p in AiSearchBenchmark.points)
            if (p.estimated) p.service,
        },
        {AiSearchService.tavily, AiSearchService.brave},
      );
    });

    test('should_holdOnePointPerSearchService_inMenuOrder', () {
      expect(AiSearchBenchmark.points.map((p) => p.service), AiSearchService.values);
    });

    test('should_scoreSearchServicesLikeTiers_when_comparing', () {
      final exa = AiSearchBenchmark.point(AiSearchService.exa);
      final asTier = AiTierPoint(
        AiProvider.deepSeek,
        AiTier.good,
        accuracy: exa.accuracy,
        seconds: exa.seconds,
        costPer1000: exa.costPer1000,
      );
      expect(exa.speedScore, asTier.speedScore);
      expect(exa.overall, asTier.overall);
      expect(exa.overall, closeTo(0.7 * 92.7 + 0.3 * (10 - 3.94) / 9 * 100, 0.01));
    });

    test('should_plotTheSearchServices_onlyForCustom', () {
      expect(AiChartSet.forProvider(AiProvider.custom), AiChartSet.search);
      for (final p in [AiProvider.gemini, AiProvider.deepSeek, AiProvider.qwen]) {
        expect(AiChartSet.forProvider(p), AiChartSet.tiers, reason: '$p');
      }
      expect(AiChartSet.tiers.points, AiTierBenchmark.points);
      expect(AiChartSet.search.points, AiSearchBenchmark.points);
    });

    test('should_giveEachSetItsOwnAxisRanges', () {
      expect(AiChartSet.tiers.range(AiAxis.cost), (min: 0.0, max: 4.0));
      expect(AiChartSet.search.range(AiAxis.cost), (min: 0.0, max: 20.0));
      expect(AiChartSet.tiers.range(AiAxis.accuracy), (min: 70.0, max: 100.0));
      expect(AiChartSet.search.range(AiAxis.accuracy), (min: 60.0, max: 100.0));
      for (final set in AiChartSet.values) {
        expect(set.range(AiAxis.speed), (min: 1.0, max: 10.0));
        expect(set.range(AiAxis.overall), (min: 50.0, max: 100.0));
      }
      // The same cost sits at different places in the two sets.
      final deepSeek = AiSearchBenchmark.point(AiSearchService.deepSeek);
      expect(AiAxis.cost.fraction(deepSeek, AiChartSet.tiers), closeTo(1 - 2.89 / 4, 0.001));
      expect(AiAxis.cost.fraction(deepSeek, AiChartSet.search), closeTo(1 - 2.89 / 20, 0.001));
    });

    test('should_keepEverySearchServiceInsideThePlot_withBetterTowardTopRight', () {
      for (final (x, y) in aiChartViews) {
        for (final point in AiSearchBenchmark.points) {
          for (final axis in [x, y]) {
            final f = axis.fraction(point, AiChartSet.search);
            expect(f > 0 && f < 1, isTrue, reason: '$axis clips ${point.service}');
          }
        }
      }
      final exa = AiSearchBenchmark.point(AiSearchService.exa);
      final tavily = AiSearchBenchmark.point(AiSearchService.tavily);
      final none = AiSearchBenchmark.point(AiSearchService.none);
      expect(AiAxis.cost.fraction(exa, AiChartSet.search) > AiAxis.cost.fraction(tavily, AiChartSet.search), isTrue);
      expect(AiAxis.speed.fraction(none, AiChartSet.search) > AiAxis.speed.fraction(tavily, AiChartSet.search), isTrue);
      expect(
        AiAxis.accuracy.fraction(exa, AiChartSet.search) > AiAxis.accuracy.fraction(none, AiChartSet.search),
        isTrue,
      );
    });

    test('should_printEachSetsOwnAxisEnds', () {
      // Cost's worst end is the dear one, on the origin side.
      expect(axisValue(AiAxis.cost, AiChartSet.tiers, worst: true), r'$4');
      expect(axisValue(AiAxis.cost, AiChartSet.search, worst: true), r'$20');
      expect(axisValue(AiAxis.cost, AiChartSet.search, worst: false), r'$0');
      expect(axisValue(AiAxis.accuracy, AiChartSet.search, worst: true), '60%');
      expect(axisValue(AiAxis.speed, AiChartSet.search, worst: true), '10s');
      expect(axisValue(AiAxis.overall, AiChartSet.tiers, worst: false), '100');
    });

    test('should_highlightTheChosenSearch_forCustom_andTheTierOtherwise', () {
      for (final service in AiSearchService.values) {
        expect(chosenChartPoint(AiProvider.custom, AiTier.best, service), AiSearchBenchmark.point(service));
      }
      expect(
        chosenChartPoint(AiProvider.deepSeek, AiTier.best, AiSearchService.exa),
        AiTierBenchmark.point(AiProvider.deepSeek, AiTier.best),
      );
    });
  });

  group('AiChartLabels', () {
    const plot = Rect.fromLTRB(52, 8, 352, 166);
    const size = Size(40, 16);

    bool overlapsAnyDot(Offset centre, Offset at, List<Offset> dots, int self) {
      final box = (centre + at) & size;
      for (final (i, d) in dots.indexed) {
        if (i == self) continue;
        final nearX = d.dx.clamp(box.left, box.right);
        final nearY = d.dy.clamp(box.top, box.bottom);
        if ((d.dx - nearX) * (d.dx - nearX) + (d.dy - nearY) * (d.dy - nearY) < 9.5 * 9.5) return true;
      }
      return false;
    }

    test('should_putTheNameOnTheRight_when_itFitsThere', () {
      final placed = AiChartLabels.place(
        plot: plot,
        dots: const [Offset(150, 80)],
        dotRadius: 9.5,
        gap: 10,
        labels: const [(0, size)],
      );
      expect(placed.single, const Offset(10, -8));
    });

    test('should_moveTheName_when_theRightRunsOffThePlotOrOverADot', () {
      // At the right edge: the left side.
      final edge = AiChartLabels.place(
        plot: plot,
        dots: const [Offset(345, 80)],
        dotRadius: 9.5,
        gap: 10,
        labels: const [(0, size)],
      );
      expect(edge.single, const Offset(-50, -8));
      // A neighbour on the right: anywhere but over it.
      const dots = [Offset(150, 80), Offset(175, 80)];
      final crowded = AiChartLabels.place(plot: plot, dots: dots, dotRadius: 9.5, gap: 10, labels: const [(0, size)]);
      expect(overlapsAnyDot(dots[0], crowded.single, dots, 0), isFalse);
      expect(crowded.single, isNot(const Offset(10, -8)));
    });

    test('should_keepTwoNamesApart_when_theirDotsNearlyTouch', () {
      const dots = [Offset(200, 80), Offset(203, 82)];
      final placed = AiChartLabels.place(
        plot: plot,
        dots: dots,
        dotRadius: 9.5,
        gap: 10,
        labels: const [(0, size), (1, size)],
      );
      final a = (dots[0] + placed[0]) & size;
      final b = (dots[1] + placed[1]) & size;
      expect(a.overlaps(b), isFalse);
      for (final (i, at) in placed.indexed) {
        final box = (dots[i] + at) & size;
        expect(plot.contains(box.topLeft) && plot.contains(box.bottomRight), isTrue);
      }
    });

    // Android's cases, in dp: a 360 dp phone and the Pixel Tablet both ways; name sizes a little over
    // labelMedium in bold, an estimated tier's carrying "(est.)" / 「（估）」.
    test('should_keepEveryNameInsideThePlotAndOffTheDots_inEveryViewWidthAndLanguage', () {
      const names = {
        'en': {AiTier.good: Size(36, 16), AiTier.best: Size(32, 16)},
        'zh': {AiTier.good: Size(14, 16), AiTier.best: Size(27, 16)},
      };
      const estimatedNames = {
        'en': {AiTier.good: Size(74, 16), AiTier.best: Size(70, 16)},
        'zh': {AiTier.good: Size(42, 16), AiTier.best: Size(55, 16)},
      };
      final points = AiTierBenchmark.points;
      for (final width in [328.0, 768.0, 1248.0]) {
        final plot = Rect.fromLTRB(52, 8, width - 8, 200 - 34);
        for (final language in names.keys) {
          for (final (x, y) in aiChartViews) {
            final dots = [
              for (final p in points)
                Offset(
                  plot.left + x.fraction(p, AiChartSet.tiers) * plot.width,
                  plot.bottom - y.fraction(p, AiChartSet.tiers) * plot.height,
                ),
            ];
            for (final provider in [AiProvider.gemini, AiProvider.deepSeek]) {
              final labels = [
                for (final (i, p) in points.indexed)
                  if (p.provider == provider) (i, (p.estimated ? estimatedNames : names)[language]![p.tier]!),
              ];
              final placed = AiChartLabels.place(plot: plot, dots: dots, dotRadius: 9.5, gap: 10, labels: labels);
              final boxes = [for (final (k, (i, size)) in labels.indexed) (dots[i] + placed[k]) & size];
              final at = '$width dp, $language, $x / $y, $provider';
              for (final (k, box) in boxes.indexed) {
                expect(
                  box.left >= plot.left && box.right <= plot.right && box.top >= plot.top && box.bottom <= plot.bottom,
                  isTrue,
                  reason: '$at: off the plot $box',
                );
                for (final (d, dot) in dots.indexed) {
                  if (d == labels[k].$1) continue;
                  final nx = dot.dx.clamp(box.left, box.right);
                  final ny = dot.dy.clamp(box.top, box.bottom);
                  expect((dot - Offset(nx, ny)).distance >= 9.5, isTrue, reason: '$at: name over dot $d');
                }
                for (final (o, other) in boxes.indexed) {
                  if (o != k) expect(other.overlaps(box), isFalse, reason: '$at: names overlap');
                }
              }
            }
          }
        }
      }
    });
  });
}
