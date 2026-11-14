import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/home/home_feed_density.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

/// Ports of Android's FeedUnitsTest and HomeFeedDensityTest (the L preset only).
void main() {
  HomeFeedDensity densityAt(double width, {double height = 1000, bool tabletop = false}) =>
      HomeFeedDensity.of(YoinWindowInfo(width, height, isTabletop: tabletop));

  int unitsAt(double width) => densityAt(width).units;

  void expectUnits(int expected, List<double> widths) {
    for (final w in widths) {
      expect(unitsAt(w), expected, reason: 'width $w');
    }
  }

  group('feed units', () {
    test('should_returnTwo_when_phonePortraitWidths', () {
      expectUnits(2, [360, 393, 411, 430, 479]);
    });

    test('should_returnOne_when_containerBelow342', () {
      expectUnits(1, [330, 341]);
      expectUnits(2, [342]);
    });

    test('should_returnThreeOrFour_when_compactAtLeast480', () {
      expectUnits(3, [480, 560, 581]);
      expectUnits(4, [582, 599]);
    });

    test('should_returnFour_when_mediumOrTabletop', () {
      expectUnits(4, [600, 690, 709, 753, 800, 839]);
      expect(feedUnitsFor(688, wide: false), 4);
      expect(feedUnitsFor(1000, wide: false), 4);
      for (final w in [600.0, 800.0, 1280.0]) {
        expect(densityAt(w, tabletop: true).units, 4, reason: 'tabletop $w');
      }
    });

    test('should_scaleFiveToEight_when_wide', () {
      expectUnits(5, [840, 860, 893]);
      expectUnits(6, [894, 1000, 1033]);
      expectUnits(7, [1034, 1129, 1173]);
      expectUnits(8, [1174, 1280, 1440, 2000]);
    });

    test('should_neverDecrease_when_widthSweeps', () {
      var previous = unitsAt(320);
      for (var w = 320.0; w <= 2000; w += 0.5) {
        final units = unitsAt(w);
        expect(units >= previous, isTrue, reason: 'width $w: $units < $previous');
        expect(units, inInclusiveRange(1, 8));
        previous = units;
      }
      expect(previous, 8);
    });

    test('should_keepTier_when_floatSumUlpBelowBreakpoint', () {
      expect(unitsAt(581.995), 4);
      expect(unitsAt(893.995), 6);
      expect(unitsAt(1173.995), 8);
    });
  });

  group('frame', () {
    test('should_matchTodayMargins_when_atRest', () {
      const expectedStart = {411: 16.0, 600: 16.0, 690: 16.0, 753: 32.5, 800: 56.0, 839: 75.5, 860: 32.0, 1129: 32.0, 1280: 32.0};
      expectedStart.forEach((w, start) {
        final frame = densityAt(w.toDouble()).frameClass;
        final insets = feedFrameInsets(w.toDouble(), frame);
        expect(insets.start, closeTo(start, 0.01), reason: 'start $w');
        expect(insets.end, closeTo(start, 0.01), reason: 'end $w');
        expect(w - insets.start - insets.end, closeTo(feedContentWidth(w.toDouble(), frame), 0.01), reason: 'content $w');
      });
      expect(densityAt(914, height: 411).frameClass, FeedFrameClass.landscape);
      expect(feedFrameInsets(914, FeedFrameClass.landscape), const FeedFrameInsets(24, 24));
      expect(feedContentWidth(914, FeedFrameClass.landscape), 866);
    });

    test('should_startAlignCappedFrame_when_widerThanRest', () {
      final insets = feedFrameInsets(1280, FeedFrameClass.capped);
      expect(insets.start, closeTo(76, 0.01));
      expect(1280 - insets.start - insets.end, closeTo(688, 0.01));
      expect(insets.end, closeTo(516, 0.01));
      expect(feedContentWidth(1280, FeedFrameClass.capped), 688);
    });

    test('should_keepContentWidthMonotonic_when_crossing840', () {
      double contentAt(double w) {
        final frame = densityAt(w).frameClass;
        final insets = feedFrameInsets(w, frame);
        final content = w - insets.start - insets.end;
        expect(content, closeTo(feedContentWidth(w, frame), 0.01), reason: 'width $w');
        return content;
      }

      expect(contentAt(839), closeTo(688, 0.01));
      expect(contentAt(840), closeTo(776, 0.01));
      var previous = contentAt(320);
      for (var w = 320.0; w <= 2000; w += 0.5) {
        final content = contentAt(w);
        expect(content >= previous - 0.01, isTrue, reason: 'width $w');
        previous = content;
      }
    });

    test('should_centreTabletopFrame_when_restingWiderThan840', () {
      expect(densityAt(1000, tabletop: true).frameClass, FeedFrameClass.cappedCentred);
      final insets = feedFrameInsets(1000, FeedFrameClass.cappedCentred);
      expect(insets.start, closeTo(insets.end, 0.01));
      expect(insets.start, closeTo(156, 0.01));
      for (final w in [411.0, 690.0, 753.0, 800.0]) {
        expect(feedFrameInsets(w, FeedFrameClass.cappedCentred), feedFrameInsets(w, FeedFrameClass.capped));
      }
    });

    test('should_blendInsets_when_lerped', () {
      final a = feedFrameInsets(900, FeedFrameClass.capped);
      final b = feedFrameInsets(900, FeedFrameClass.wide);
      expect(FeedFrameInsets.lerp(a, b, 0), a);
      expect(FeedFrameInsets.lerp(a, b, 1), b);
      expect(FeedFrameInsets.lerp(a, b, 0.5).start, closeTo((a.start + b.start) / 2, 1e-9));
    });
  });

  group('cover columns', () {
    test('should_seatPhoneSizedColumns_when_deviceWidths', () {
      const expected = {480: 3, 600: 4, 690: 5, 800: 5, 860: 6, 1129: 8, 1280: 9, 1440: 10, 2000: 10};
      expected.forEach((w, k) => expect(densityAt(w.toDouble()).coverColumns, k, reason: 'width $w'));
    });

    test('should_keepCoversPhoneSized_when_anyTemplatedWidth', () {
      for (var w = 480; w <= 2400; w++) {
        final density = densityAt(w.toDouble());
        if (density.units < 3) continue;
        final k = density.coverColumns;
        final content = feedContentWidth(w.toDouble(), density.frameClass);
        final column = (content - 12 * (k - 1)) / k;
        expect(column >= 112 - 0.01, isTrue, reason: '$w: column $column');
        if (k > 3 && k < 10) expect(column < 112 + 124 / k + 0.01, isTrue, reason: '$w: column $column');
        expect(jbiTemplateCoverSide(column), inInclusiveRange(100, 128), reason: '$w');
      }
    });

    test('should_crossCoverColumnBreakpoint_when_floatSumUlpBelow', () {
      expect(feedCoverColumns(484 - 0.001), 4);
      expect(feedCoverColumns(483.9), 3);
    });
  });

  group('activities bento', () {
    const palette = <int, List<(List<int>, List<int>)>>{
      3: [([2, 1], [1, 2])],
      4: [([2, 2], [1, 2, 1])],
      5: [([2, 2, 1], [1, 2, 2]), ([2, 2, 1], [2, 1, 2])],
      6: [([2, 2, 1, 1], [1, 2, 2, 1]), ([2, 2, 2], [1, 1, 1, 2, 1]), ([2, 2, 2], [1, 2, 1, 1, 1])],
      7: [([2, 2, 2, 1], [1, 2, 1, 1, 2]), ([2, 2, 2, 1], [1, 2, 2, 1, 1]), ([3, 2, 1, 1], [1, 1, 2, 1, 2]), ([3, 2, 1, 1], [1, 1, 2, 2, 1])],
      8: [
        ([2, 2, 2, 1, 1], [1, 2, 2, 1, 2]),
        ([2, 2, 2, 2], [1, 2, 1, 1, 2, 1]),
        ([3, 2, 2, 1], [1, 1, 2, 1, 1, 2]),
        ([3, 2, 2, 1], [1, 1, 2, 2, 1, 1]),
      ],
    };
    const strips = {3: 1, 4: 2, 5: 2, 6: 2, 7: 3, 8: 3};
    const ownerItemCounts = {3: 5, 4: 7, 5: 8, 6: 10, 7: 12, 8: 13};

    ActivityBentoSpec spec(int units, int seed, {bool hasHero = true}) =>
        activityBentoSpec(feedUnits: units, compactHeight: false, seed: seed, hasHero: hasHero);

    Set<int> seams(List<int> spans) {
      final out = <int>{};
      var sum = 0;
      for (final s in spans.take(spans.length - 1)) {
        sum += s;
        out.add(sum);
      }
      return out;
    }

    test('should_keepPhoneRecipe_when_unitsTwo', () {
      for (var seed = 0; seed <= 20; seed++) {
        for (final hasHero in [true, false]) {
          final s = spec(2, seed, hasHero: hasHero);
          expect(s.recipe, BentoRecipe.phone);
          expect((s.totalSlots, s.supportingSlots), (4, 3));
          expect(activityUnitSlots(s, hasHero: hasHero, supportingCount: 12), isEmpty);
        }
      }
    });

    test('should_useLandscape_when_compactHeight', () {
      final specs = {for (var u = 1; u <= 8; u++) activityBentoSpec(feedUnits: u, compactHeight: true, seed: u)};
      expect(specs, hasLength(1));
      expect(specs.single.recipe, BentoRecipe.landscape);
      expect(specs.single.totalSlots, 3);
    });

    test('should_useTwoSmalls_when_unitsOne', () {
      final s = spec(1, 7);
      expect(s.recipe, BentoRecipe.phoneNarrow);
      expect(s.totalSlots, 4);
      expect(spec(0, 7), s);
      expect(spec(-3, 7), s);
    });

    test('should_clampToEight_when_unitsAboveRange', () {
      for (var seed = 0; seed <= 7; seed++) {
        expect(spec(12, seed), spec(8, seed));
      }
    });

    test('should_pickPaletteOptionBySeed_when_units', () {
      palette.forEach((units, options) {
        for (var seed = 0; seed < options.length * 3; seed++) {
          final s = spec(units, seed);
          expect(s.recipe, BentoRecipe.units);
          expect(s.row1, options[seed % options.length].$1, reason: 'N=$units seed=$seed');
          expect(s.row2, options[seed % options.length].$2, reason: 'N=$units seed=$seed');
          expect(s.strips, strips[units]);
          expect(s.totalSlots, ownerItemCounts[units]);
          expect(s.row1Height, units <= 4 ? 124 : 128);
        }
      });
    });

    test('should_fillEveryRowAndStagger_when_units', () {
      palette.forEach((units, options) {
        for (var seed = 0; seed < options.length; seed++) {
          final s = spec(units, seed);
          expect(s.row1.reduce((a, b) => a + b), units);
          expect(s.row2.reduce((a, b) => a + b), units);
          final shared = seams(s.row1).intersection(seams(s.row2));
          expect(shared.length, lessThanOrEqualTo(1));
          if (units <= 5 && !(s.row1.join() == '221' && s.row2.join() == '212')) expect(shared, isEmpty);
        }
      });
    });

    test('should_neverGiveWideThreeUnits_when_heroMissing', () {
      for (var units = 3; units <= 8; units++) {
        final eligible = palette[units]!.where((o) => o.$1.first == 2).toList();
        for (var seed = 0; seed <= 23; seed++) {
          final s = spec(units, seed, hasHero: false);
          expect(s.row1, eligible[seed % eligible.length].$1);
          final slots = activityUnitSlots(s, hasHero: false, supportingCount: s.totalSlots);
          expect(slots.first, const ActivitySlot(0, 0, 2, SlotKind.wide, 0));
          expect(slots.where((x) => x.kind == SlotKind.hero), isEmpty);
          expect(slots.map((x) => x.entryIndex), List.generate(s.totalSlots, (i) => i));
        }
      }
      expect(spec(7, 2).row1.first, 3);
      expect(spec(7, 2, hasHero: false).row1.first, 2);
    });

    test('should_placeOnUnitLines_when_walkingSlots', () {
      palette.forEach((units, options) {
        for (var seed = 0; seed < options.length; seed++) {
          final s = spec(units, seed);
          final slots = activityUnitSlots(s, hasHero: true, supportingCount: s.supportingSlots);
          expect(slots, hasLength(s.totalSlots));
          expect(slots.first, ActivitySlot(0, 0, s.row1.first, SlotKind.hero, -1));
          expect(slots.skip(1).map((x) => x.entryIndex), List.generate(s.supportingSlots, (i) => i));
          for (final (rowIndex, spans) in [(0, s.row1), (1, s.row2)]) {
            final row = slots.where((x) => x.row == rowIndex).toList();
            expect(row.map((x) => x.span), spans);
            expect(row.last.startUnit + row.last.span, units);
          }
          final stripRow = slots.where((x) => x.row == 2).toList();
          expect(stripRow.map((x) => x.startUnit), List.generate(s.strips, (i) => i));
          expect(stripRow.every((x) => x.span == 1 && x.kind == SlotKind.strip), isTrue);
        }
      });
    });

    test('should_degradeFromBottom_when_fewEntries', () {
      final s = spec(8, 0);
      expect(s.row1, [2, 2, 2, 1, 1]);
      final full = activityUnitSlots(s, hasHero: true, supportingCount: 12);
      expect(full, hasLength(13));
      expect(activityUnitSlots(s, hasHero: true, supportingCount: 40), full);
      for (var count = 0; count <= 12; count++) {
        final slots = activityUnitSlots(s, hasHero: true, supportingCount: count);
        expect(slots.take(slots.length - 1), full.take(count), reason: 'count=$count');
        final last = slots.last;
        expect(last, full[count].copyWith(span: last.span, kind: last.kind));
        expect(last.startUnit + last.span, last.kind == SlotKind.strip ? s.strips : s.units);
      }
      expect(activityUnitSlots(s, hasHero: true, supportingCount: 11).where((x) => x.row == 2).map((x) => x.span), [1, 2]);
      final nine = activityUnitSlots(s, hasHero: true, supportingCount: 9);
      expect(nine.where((x) => x.row == 2), isEmpty);
      final six = activityUnitSlots(s, hasHero: true, supportingCount: 6).where((x) => x.row == 1).toList();
      expect(six.map((x) => (x.startUnit, x.span, x.kind)), [(0, 1, SlotKind.small), (1, 7, SlotKind.wide)]);
      expect(activityUnitSlots(s, hasHero: true, supportingCount: 5).where((x) => x.row == 1), [const ActivitySlot(1, 0, 8, SlotKind.wide, 4)]);
      expect(activityUnitSlots(s, hasHero: true, supportingCount: 0), [full.first.copyWith(span: 8)]);
      expect(activityUnitSlots(spec(8, 0, hasHero: false), hasHero: false, supportingCount: 0), isEmpty);
    });

    test('should_beDeterministic_when_sameSeed', () {
      // Java's String.hashCode, so the seed matches Android's.
      expect(activityLayoutSeed('abc'), 96354);
      expect(activityLayoutSeed(null), 0);
      expect(javaStringHash('polygenelubricants'), -2147483648);
      expect(activityLayoutSeed('polygenelubricants'), 0);
      expect(javaStringHash('album:al-42'), -1538755947);
      expect(javaStringHash('活动'), 888013);
      for (final key in ['album:al-42', 'playlist:pl-7', '', '活动', null]) {
        final seed = activityLayoutSeed(key);
        expect(seed, greaterThanOrEqualTo(0));
        for (var units = 1; units <= 8; units++) {
          expect(spec(units, seed), spec(units, seed));
        }
      }
      expect(spec(8, -1).recipe, BentoRecipe.units);
    });

    test('should_keepCellsInBand_when_measuredForms', () {
      // Resting widths: 480, 599, 600, 690 fold, 800 tablet portrait, 840, 1000, 1129, 1280 tablet landscape, 1440 MateBook window.
      for (final w in [480.0, 599, 600, 690, 709, 800, 840, 1000, 1129, 1280, 1440]) {
        final density = densityAt(w.toDouble());
        final content = feedContentWidth(w.toDouble(), density.frameClass);
        final units = density.units;
        final unit = (content - 10 * (units - 1)) / units;
        expect(unit, inInclusiveRange(130, 185), reason: 'W=$w C=$content N=$units');
        for (final (row1, row2) in palette[units]!) {
          for (final row in [row1, row2]) {
            for (final span in row) {
              final width = span * unit + (span - 1) * 10;
              if (span == 1) expect(width >= 120, isTrue);
              if (span == 2) expect(width <= 380, isTrue);
              if (span == 3) expect(width <= 520, isTrue);
            }
          }
        }
      }
    });
  });

  group('recently added', () {
    (int, int) tier(double width) {
      final d = densityAt(width);
      return (d.units, d.coverColumns);
    }

    test('should_matchPhoneFormula_when_content328to484', () {
      for (var c = 328.0; c <= 484; c += 0.25) {
        expect(recentlyAddedGridWidth(c, compactHeight: false), closeTo((c - 14) * (2.6 / 3.6), 0.01));
      }
      for (final c in [485.0, 688, 1216, 2000]) {
        expect(recentlyAddedGridWidth(c.toDouble(), compactHeight: false), 340);
      }
    });

    test('should_keepLandscapeFormula_when_compactHeight', () {
      expect(recentlyAddedGridWidth(300, compactHeight: true), closeTo(128.7, 0.01));
      expect(recentlyAddedGridWidth(1400, compactHeight: true), 340);
    });

    test('should_keepPhoneTwoByTwo_when_phoneOrLandscapePhone', () {
      for (final w in [360.0, 393, 411, 479, 600]) {
        final (units, cover) = tier(w.toDouble());
        expect(recentlyAddedTrackColumns(units, cover, compactHeight: false), 2, reason: 'W=$w');
      }
      for (var units = 1; units <= 8; units++) {
        expect(recentlyAddedTrackColumns(units, 10, compactHeight: true), 2);
        expect(recentlyAddedTrackRows(units, compactHeight: true), 2);
      }
      expect(recentlyAddedTrackRows(2, compactHeight: false), 2);
      expect(recentlyAddedAlbumLimit(2), 12);
      expect(recentlyAddedAlbumStack(2), 1);
    });

    test('should_seatThreeColumns_when_tabletPortrait', () {
      final (units, cover) = tier(800);
      expect((units, cover), (4, 5));
      final columns = recentlyAddedTrackColumns(units, cover, compactHeight: false);
      expect(columns, 3);
      expect(recentlyAddedTrackRows(units, compactHeight: false), 4);
      expect(recentlyAddedAlbumStack(4), 2);
      expect(recentlyAddedAlbumLimit(columns), 16);
      expect(recentlyAddedGridWidth(688, compactHeight: false, columns: columns), closeTo(514, 0.001));
    });

    test('should_seatFourColumns_when_tabletLandscapeOrMateBook', () {
      for (final w in [1280.0, 1440]) {
        final (units, cover) = tier(w.toDouble());
        final columns = recentlyAddedTrackColumns(units, cover, compactHeight: false);
        expect(columns, 4, reason: 'W=$w');
        expect(recentlyAddedAlbumLimit(columns), 20);
        expect(recentlyAddedGridWidth(1216, compactHeight: false, columns: columns), closeTo(688, 0.001));
      }
    });

    test('should_stepColumnsByCoverColumns_when_feedWidens', () {
      const expected = {3: 2, 4: 2, 5: 3, 6: 3, 7: 3, 8: 4, 9: 4, 10: 4};
      expected.forEach((cover, columns) => expect(recentlyAddedTrackColumns(5, cover, compactHeight: false), columns));
    });

    test('should_useJustEnoughColumns_when_fewTracks', () {
      expect(recentlyAddedGridColumns(1, maxColumns: 4), 2);
      expect(recentlyAddedGridColumns(4, maxColumns: 4), 2);
      expect(recentlyAddedGridColumns(5, maxColumns: 4), 3);
      expect(recentlyAddedGridColumns(6, maxColumns: 3), 3);
      expect(recentlyAddedGridColumns(8, maxColumns: 4), 4);
      expect(recentlyAddedGridColumns(8, maxColumns: 2), 2);
      expect(recentlyAddedGridColumns(6, rows: 4, maxColumns: 4), 2);
      expect(recentlyAddedGridColumns(9, rows: 4, maxColumns: 4), 3);
      expect(recentlyAddedGridColumns(16, rows: 4, maxColumns: 4), 4);
    });
  });

  group('jbi rows', () {
    test('should_stackRowsByLaneCount_when_templated', () {
      expect(jbiTemplateRows(3), 4);
      expect(jbiTemplateRows(4), 3);
      expect(jbiTemplateRows(5), 3);
      expect(jbiTemplateRows(6), 2);
      expect(jbiTemplateRows(10), 2);
    });
  });
}
