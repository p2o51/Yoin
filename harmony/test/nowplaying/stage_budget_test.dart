import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/nowplaying/stage_budget.dart';
import 'package:yoin/theme/tokens.dart';

double _sum(StageColumnBudget b) =>
    b.topBar + b.cover + b.coverGap + b.tabs + b.tabGap + b.lyrics + b.controls + b.hero + b.accessory;

void main() {
  test('should_matchAndroidExample_when_412x892Phone', () {
    // 892 − 40 status − 24 nav = 828.
    final b = resolveStageColumnBudget(width: 412, height: 828);
    expect(b.cover, 296);
    expect(b.topBar, 56);
    expect(b.controls, 148);
    expect(b.hero, 86);
    expect(b.accessory, 68);
    expect(b.lyrics, 158);
    expect(b.lyricFrame, LyricFrame.list);
    expect(b.heroOneLine, isFalse);
    expect(b.labelsUnderWave, isFalse);
    expect(_sum(b), 828);
  });

  test('should_needAbout126dp_when_twoCleanLines', () {
    expect(minLyricWindow(const StageMetrics()), closeTo(125.64, 0.01));
  });

  test('should_squeezeLowerGapsFirst_when_slightlyShort', () {
    final b = resolveStageColumnBudget(width: 412, height: 790);
    expect(b.lyrics, greaterThanOrEqualTo(minLyricWindow(const StageMetrics()) - 1));
    expect(b.topBar, 56, reason: 'upper gaps wait until lower gaps are spent');
    expect(b.controls, lessThan(148));
    expect(_sum(b), closeTo(790, 0.01));
  });

  test('should_foldHeroThenLyrics_when_veryShort', () {
    final b = resolveStageColumnBudget(width: 412, height: 600);
    expect(b.heroOneLine, isTrue);
    expect(b.lyricFrame, LyricFrame.oneLine);
    expect(b.cover, greaterThanOrEqualTo(NowPlayingTokens.coverFloor));
    expect(_sum(b), closeTo(600, 0.01));
  });

  test('should_hideCover_when_belowFloor', () {
    final b = resolveStageColumnBudget(width: 412, height: 420);
    expect(b.coverHidden, isTrue);
    expect(b.lyrics, greaterThanOrEqualTo(0));
  });

  test('should_dropTimeLabelsUnderWave_when_narrowerThan388', () {
    final b = resolveStageColumnBudget(width: 380, height: 828);
    expect(b.labelsUnderWave, isTrue);
    expect(b.controls, 168);
  });

  test('should_keepTimeLabelsInline_when_mate70AirWidth', () {
    // Mate 70 Air: 1320 px / 3.25 = 406 vp → a 358 vp transport, one row like Android.
    final b = resolveStageColumnBudget(width: 406, height: 828);
    expect(b.labelsUnderWave, isFalse);
  });

  test('should_holdOneLineFrame_when_withinHysteresis', () {
    // The first height (1 dp steps) where the list fits again.
    var h = 500.0;
    while (resolveStageColumnBudget(width: 412, height: h).lyricFrame == LyricFrame.oneLine) {
      h += 1;
    }
    expect(
      resolveStageColumnBudget(width: 412, height: h, previousFrame: LyricFrame.oneLine).lyricFrame,
      LyricFrame.oneLine,
    );
    expect(
      resolveStageColumnBudget(width: 412, height: h + 9, previousFrame: LyricFrame.oneLine).lyricFrame,
      LyricFrame.list,
    );
  });

  test('should_giveReleasedSlotsToLyrics_when_expanded', () {
    final rest = resolveStageColumnBudget(width: 412, height: 828);
    final full = StageSlots.lerp(rest, 1);
    expect(full.coverRow, 0);
    expect(full.controls, 0);
    expect(full.hero, rest.hero);
    expect(full.topBar + full.coverRow + full.coverGap + full.lyrics + full.controls + full.hero + full.accessory, 828);
    expect(full.lyrics, greaterThan(rest.lyrics + rest.cover));
    final half = StageSlots.lerp(rest, 0.5);
    expect(half.coverRow, rest.cover / 2);
  });

  test('should_roundSlotsToDevicePixels_when_fractionalDensity', () {
    final b = resolveStageColumnBudget(width: 411.4, height: 827.3, devicePixelRatio: 2.625);
    for (final v in [b.topBar, b.cover, b.coverGap, b.controls, b.hero, b.accessory, b.lyrics]) {
      expect((v * 2.625 - (v * 2.625).roundToDouble()).abs(), lessThan(1e-6));
    }
  });
}
