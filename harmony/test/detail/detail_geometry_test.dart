import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/detail/artist_detail_screen.dart';
import 'package:yoin/detail/flowing_titles.dart';
import 'package:yoin/detail/playlist_sleeves.dart';
import 'package:yoin/detail/pull_up_reveal.dart';
import 'package:yoin/ui/component/meta_line.dart';
import 'package:yoin/ui/component/yoin_fast_scroller.dart';

void main() {
  group('PullUpMath', () {
    test('should_capOvershoot_when_pastHero', () {
      expect(PullUpMath.applyRubberBand(0.4), 0.4);
      expect(PullUpMath.applyRubberBand(-0.2), 0);
      final banded = PullUpMath.applyRubberBand(2);
      expect(banded, greaterThan(1));
      expect(banded, lessThan(1 + PullUpTokens.rubberBand));
    });

    test('should_pickListOrHero_when_released', () {
      const travel = 500.0;
      expect(PullUpMath.releaseToList(0.9, -900, travel), isTrue, reason: 'fast fling up wins over position');
      expect(PullUpMath.releaseToList(0.1, 900, travel), isFalse, reason: 'fast fling down wins over position');
      expect(PullUpMath.releaseToList(0.4, 0, travel), isTrue);
      expect(PullUpMath.releaseToList(0.6, 0, travel), isFalse);
    });

    test('should_handOverLayers_when_expandGrows', () {
      expect(PullUpMath.heroAlpha(0), 1);
      expect(PullUpMath.heroAlpha(0.55), 0);
      expect(PullUpMath.listAlpha(0.3), 0);
      expect(PullUpMath.listAlpha(0.8), 1);
      expect(PullUpMath.listShift(1), 0);
      expect(PullUpMath.heroShift(0.6), -PullUpTokens.handoffShift);
    });

    test('should_flyCoverToStamp_when_fullyExpanded', () {
      const rest = Rect.fromLTWH(50, 100, 260, 260);
      const stamp = Rect.fromLTWH(300, 10, 48, 48);
      expect(PullUpMath.coverRect(rest, stamp, 0), rest);
      expect(PullUpMath.coverRect(rest, stamp, 1), stamp);
      expect(PullUpMath.coverCorner(1), PullUpTokens.stampCorner);
    });

    test('should_capHeroCover_when_wideWindow', () {
      expect(PullUpMath.heroCoverSide(360), closeTo(266.4, 0.01));
      expect(PullUpMath.heroCoverSide(800), 300);
    });
  });

  group('flowing titles', () {
    test('should_countWideGlyphsDouble_when_measuring', () {
      expect(titleWidthUnits('abc'), 3);
      expect(titleWidthUnits('晴天'), 4);
    });

    test('should_glueShortTitle_when_underSixteenUnits', () {
      expect(glueTitle('Blue Monday'), 'Blue Monday');
      expect(glueTitle('晴天'), '晴⁠天');
      const long = 'A very long song title that wraps';
      expect(glueTitle(long), long);
    });
  });

  group('playlist sleeves', () {
    test('should_shrinkSpread_when_boxNarrow', () {
      expect(PlaylistSleeveGeometry.spread(200, 260), PlaylistSleeveGeometry.maxSpread);
      expect(PlaylistSleeveGeometry.spread(0, 260), closeTo(0.5 - PlaylistSleeveGeometry.halfExtent, 1e-9));
      expect(PlaylistSleeveGeometry.spread(-100, 260), 0);
    });

    test('should_leanStartSleeveToStart_when_ltr', () {
      final start = PlaylistSleeveGeometry.centerOffset(0, 200, 0.2, rtl: false);
      final end = PlaylistSleeveGeometry.centerOffset(1, 200, 0.2, rtl: false);
      expect(start.dx, -40);
      expect(end.dx, 40);
      expect(start.dy, closeTo(14, 1e-9));
    });
  });

  test('should_sizeRingFromPortrait_when_drawing', () {
    const p = 200.0;
    expect(ArtistRingGeometry.room(p), closeTo(68, 1e-9));
    expect(
      ArtistRingGeometry.radius(p) + ArtistRingGeometry.stroke(p) / 2,
      closeTo(p * ArtistRingGeometry.reach, 1e-9),
    );
  });

  group('meta helpers', () {
    test('should_showMinutesEvenIfZero_when_noHours', () {
      expect(durationStatGroups(30, hourUnit: 'h', minuteUnit: 'min').map((g) => (g as MetaStat).value), ['0']);
      expect(durationStatGroups(7200, hourUnit: 'h', minuteUnit: 'min').map((g) => (g as MetaStat).value), ['2']);
      expect(durationStatGroups(7380, hourUnit: 'h', minuteUnit: 'min').map((g) => (g as MetaStat).value), ['2', '3']);
    });

    test('should_stripVersionSuffix_when_present', () {
      expect(albumTitleWithoutVersion('Blue (Deluxe)', 'Deluxe'), 'Blue');
      expect(albumTitleWithoutVersion('Blue', 'Deluxe'), 'Blue');
      expect(formatTrackDuration(185), '3:05');
      expect(formatTrackDuration(3725), '1:02:05');
    });
  });

  group('fast scroller geometry', () {
    test('should_mapFingerToFraction_when_dragging', () {
      expect(FastScrollerGeometry.fractionAt(24, 548), 0);
      expect(FastScrollerGeometry.fractionAt(524, 548), 1);
      expect(FastScrollerGeometry.thumbTop(1, 548), 500);
    });

    test('should_findMarkCoveringItem_when_held', () {
      const marks = [
        FastScrollMark(bubble: 'A', tick: 'A', startIndex: 0),
        FastScrollMark(bubble: 'B', tick: 'B', startIndex: 10),
      ];
      expect(FastScrollerGeometry.markAt(marks, 9), 0);
      expect(FastScrollerGeometry.markAt(marks, 10), 1);
      expect(FastScrollerGeometry.lineAt(0.5, 11), 5);
    });
  });
}
