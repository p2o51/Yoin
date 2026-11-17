import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/nowplaying/focus_budget.dart';
import 'package:yoin/nowplaying/now_playing_presentation.dart';
import 'package:yoin/nowplaying/stage_budget.dart';
import 'package:yoin/theme/tokens.dart';

void main() {
  StageColumnBudget phone({double width = 412, double height = 828}) =>
      resolveStageColumnBudget(width: width, height: height);

  test('should_growCoverWithEqualMargins_when_phoneFocuses', () {
    final rest = phone();
    final focus = resolveStageFocus(rest: rest)!;
    expect(focus.cover, greaterThan(rest.cover));
    expect(focus.cover, lessThanOrEqualTo(rest.cover * NowPlayingTokens.coverFocusMaxScale));
    expect(coverFocusOffered(rest.cover, focus.cover), isTrue);
    // Equal margins: the top equals each side margin (the column narrows to the cover).
    expect(focus.top, closeTo((rest.width - focus.cover) / 2, 1));
    expect(focus.stageWidth, closeTo(focus.cover + 2 * NowPlayingTokens.columnPadding, 0.01));
    expect(focus.total, closeTo(rest.height, 1));
  });

  test('should_keepTwoCleanFocusLines_when_focused', () {
    final focus = resolveStageFocus(rest: phone())!;
    expect(focus.lyrics, greaterThanOrEqualTo(focusLyricWindow(const StageMetrics(), 2) - 1));
  });

  test('should_offerNoFocus_when_coverCannotGrowEnough', () {
    // A wide, short window: the resting cover is at its cap and the height leaves no room to grow.
    final rest = phone(width: 412, height: 700);
    final focus = resolveStageFocus(rest: rest);
    if (focus != null) {
      expect(coverFocusOffered(rest.cover, focus.cover) || focus.short, isTrue);
    }
    final tight = resolveStageColumnBudget(width: 360, height: 760);
    final tightFocus = resolveStageFocus(rest: tight);
    if (tightFocus != null && !tightFocus.short) {
      expect(tightFocus.cover - tight.cover, greaterThanOrEqualTo(NowPlayingTokens.coverFocusMinGrowth));
    }
  });

  test('should_keepCoverAndOpenLyrics_when_shortWindowFocuses', () {
    final rest = resolveStageColumnBudget(width: 412, height: 600);
    expect(rest.lyricFrame, LyricFrame.oneLine);
    final focus = resolveStageFocus(rest: rest);
    if (focus == null) return;
    expect(focus.short, isTrue);
    expect(focus.cover, lessThanOrEqualTo(rest.cover + 0.5));
    expect(focus.top, NowPlayingFocusTokens.topShort);
    expect(focus.stageWidth, rest.stageWidth);
    expect(focus.total, closeTo(rest.height, 1));
  });

  test('should_growEnlargedCover_when_mediumFocuses', () {
    final spec = nowPlayingEnlargedSpec(800);
    final rest = resolveStageColumnBudget(width: 800, height: 1180, enlarged: spec);
    final focus = resolveStageFocus(rest: rest, enlarged: spec);
    expect(focus, isNotNull);
    expect(focus!.cover, greaterThan(rest.cover));
    expect(focus.stageWidth, lessThanOrEqualTo(800));
  });

  test('should_evenTheGapsOverTheCoverAndUnderTheTitle_when_focusOnTheEnlargedColumn', () {
    // Android 11-14 (cabd5e434): on a 3:2 tablet the equal side/top margin hung the
    // focus stage low. The gaps over the cover and under the title match now.
    final spec = nowPlayingEnlargedSpec(800);
    final rest = resolveStageColumnBudget(width: 800, height: 1180, enlarged: spec);
    final focus = resolveStageFocus(rest: rest, enlarged: spec)!;
    expect(focus.top, closeTo(focus.bottom, 1));
    expect(focus.total, closeTo(1180, 1));
  });

  test('should_keepTheCoverAndLiftTheStage_when_enlargedFocusGapsAreEvened', () {
    final spec = nowPlayingEnlargedSpec(800);
    final rest = resolveStageColumnBudget(width: 800, height: 1180, enlarged: spec);
    final focus = resolveStageFocus(rest: rest, enlarged: spec)!;
    // The equal side margin still picks the cover; only the vertical gaps move.
    expect(focus.cover, greaterThan(rest.cover));
    expect(focus.lyrics, greaterThanOrEqualTo(0));
    expect(focus.top, lessThan((800 - focus.cover) / 2));
  });

  test('should_keepEqualMargins_when_phoneFocuses', () {
    final rest = phone();
    final focus = resolveStageFocus(rest: rest)!;
    expect(focus.top, closeTo((rest.width - focus.cover) / 2, 1));
    // No lift on a phone: the top keeps its equal margin over the smaller gap under the title.
    expect(focus.top, greaterThan(focus.bottom));
  });

  test('should_interpolateSlots_when_focusAndDetailMove', () {
    final rest = phone();
    final focus = resolveStageFocus(rest: rest)!;
    final atRest = stageSlotsAt(rest, focus, f: 0, d: 0);
    expect(atRest.coverRow, rest.cover);
    expect(atRest.tabs, rest.tabs);
    final atFocus = stageSlotsAt(rest, focus, f: 1, d: 0);
    expect(atFocus.coverRow, focus.cover);
    expect(atFocus.tabs, 0);
    expect(atFocus.topBar, focus.top);
    expect(atFocus.lyrics, closeTo(focus.lyrics, 1));
    // Expanded wins whatever the focus did.
    final expanded = stageSlotsAt(rest, focus, f: 1, d: 1);
    expect(expanded.coverRow, 0);
    expect(liveStageWidth(rest, focus, f: 1, d: 0), focus.stageWidth);
    expect(liveStageWidth(rest, focus, f: 1, d: 1), rest.stageWidth);
  });

  test('should_squeezeLowerGapsFirst_when_deficit', () {
    const lower = [NpGap(20, 10)];
    const upper = [NpGap(16, 8)];
    expect(squeezeGaps(5, lower, upper), (lower: 0.5, upper: 0.0, unmet: 0.0));
    expect(squeezeGaps(14, lower, upper), (lower: 1.0, upper: 0.5, unmet: 0.0));
    expect(squeezeGaps(30, lower, upper), (lower: 1.0, upper: 1.0, unmet: 12.0));
  });

  test('should_growDualPaneCover_when_wideFocusFits', () {
    final budget = resolveDualPaneBudget(rowWidth: 1232, contentHeight: 760);
    expect(budget.focusCover, isNotNull);
    expect(budget.focusCover!, greaterThan(budget.cover));
    expect(budget.focusCover!, lessThanOrEqualTo(budget.cover * NowPlayingTokens.coverFocusMaxScale));
  });

  test('should_offerLandscapeFocus_when_wideHandset', () {
    final l = resolveLandscapeLayout(width: 860, height: 340);
    expect(l.stacked, isFalse);
    expect(l.focusColumnX, lessThan(l.columnX + 1), reason: 'the rating column steps away');
  });
}
