import 'dart:math' as math;
import 'dart:ui' show lerpDouble;

import 'package:flutter/foundation.dart';

import '../theme/tokens.dart';
import 'focus_budget.dart';
import 'now_playing_presentation.dart';

/// Text line heights the budget needs, read from the live text styles at the
/// current font scale (NowPlayingMetrics.kt rememberStageColumnMetrics), so
/// a larger font grows a slot instead of clipping.
@immutable
final class StageMetrics {
  const StageMetrics({
    this.heroArtistLine = 24,
    this.heroTitleLine = 44,
    this.activeLyricLine = 24,
    this.inactiveLyricLine = 20,
    this.timeLabelLine = 20,
    this.tabTextLine = 20,
  });

  /// titleMedium line (the artist, font × 0.9, line height kept).
  final double heroArtistLine;

  /// displaySmall line.
  final double heroTitleLine;

  /// bodyLarge / bodyMedium lines of the lyric window.
  final double activeLyricLine;
  final double inactiveLyricLine;

  /// labelLarge, the time labels when they drop under the wave.
  final double timeLabelLine;

  /// labelLarge, the compact text tabs (Lyrics | Note).
  final double tabTextLine;

  @override
  bool operator ==(Object other) =>
      other is StageMetrics &&
      other.heroArtistLine == heroArtistLine &&
      other.heroTitleLine == heroTitleLine &&
      other.activeLyricLine == activeLyricLine &&
      other.inactiveLyricLine == inactiveLyricLine &&
      other.timeLabelLine == timeLabelLine &&
      other.tabTextLine == tabTextLine;

  @override
  int get hashCode =>
      Object.hash(heroArtistLine, heroTitleLine, activeLyricLine, inactiveLyricLine, timeLabelLine, tabTextLine);
}

typedef _T = NowPlayingTokens;

/// The enlarged phone's tab row, tall enough for the lyric tools at its end
/// (NowPlayingBudget.kt EnlargedToolTabRow; LyricToolsMetrics.enlargedTabKey).
const double enlargedToolTabRow = 44;

enum LyricFrame { list, oneLine }

/// The phone column at rest (NowPlayingBudget.kt resolveStageColumnBudget,
/// rest pose only): every slot's height, summing to the content height.
@immutable
final class StageColumnBudget {
  const StageColumnBudget({
    required this.width,
    required this.height,
    required this.topBar,
    required this.cover,
    required this.coverGap,
    required this.tabs,
    required this.tabGap,
    required this.lyrics,
    required this.controls,
    required this.hero,
    required this.accessory,
    required this.heroOneLine,
    required this.lyricFrame,
    required this.labelsUnderWave,
    this.ratingColumn = NowPlayingTokens.phoneRatingColumn,
    double? stageWidth,
  }) : stageWidth = stageWidth ?? width;

  final double width;
  final double height;
  final double topBar;

  /// The cover square's edge (0 = hidden: below the 96 floor).
  final double cover;
  final double coverGap;

  /// The compact text-tab row (Lyrics | Note) and its gap; 0 when the lyrics
  /// fold to one line.
  final double tabs;
  final double tabGap;

  /// The lyric window: whatever the rest leaves (elastic).
  final double lyrics;
  final double controls;
  final double hero;
  final double accessory;

  /// Short windows fold the hero to one row (title then artist).
  final bool heroOneLine;
  final LyricFrame lyricFrame;

  /// The transport is narrower than 360: time labels move under the wave.
  final bool labelsUnderWave;

  /// The rating column beside the cover (56 on the phone; the enlarged
  /// phone grows it with its column).
  final double ratingColumn;

  /// The column's own width, centred in [width]: the enlarged phone narrows
  /// to its cover row (cover + gap + rating + padding); [width] elsewhere.
  final double stageWidth;

  bool get coverHidden => cover <= 0;

  @override
  bool operator ==(Object other) =>
      other is StageColumnBudget &&
      other.width == width &&
      other.height == height &&
      other.topBar == topBar &&
      other.cover == cover &&
      other.coverGap == coverGap &&
      other.tabs == tabs &&
      other.tabGap == tabGap &&
      other.lyrics == lyrics &&
      other.controls == controls &&
      other.hero == hero &&
      other.accessory == accessory &&
      other.heroOneLine == heroOneLine &&
      other.lyricFrame == lyricFrame &&
      other.labelsUnderWave == labelsUnderWave &&
      other.ratingColumn == ratingColumn &&
      other.stageWidth == stageWidth;

  @override
  int get hashCode => Object.hash(
    width,
    height,
    topBar,
    cover,
    coverGap,
    tabs,
    tabGap,
    lyrics,
    controls,
    hero,
    accessory,
    heroOneLine,
    lyricFrame,
    labelsUnderWave,
    ratingColumn,
    stageWidth,
  );
}

/// Transport labels sit inline only when the transport is at least this wide.
///
/// Android uses 360 (PlaybackLabelsInlineMinWidth) to drop the labels under
/// the bar on the narrow Fold left pane (~1:1.5, well under 340). HarmonyOS
/// phones are a few vp narrower than Android's 411 dp reference: the Mate 70
/// Air is 406 vp, a 358 vp transport, which 360 would put on two lines while
/// every Android phone shows one row (owner: row 2 must be Android's single
/// row). 340 keeps phones inline and still drops the labels on narrow panes;
/// inline, the wave keeps ≥ 164 vp at 340 (56 + 8 + 44 + 8 + 8 + 52 = 176 taken).
const double transportLabelsInlineMinWidth = 340;

/// Height the lyric window needs to hold [cleanLines] clean lines with the
/// active one anchored at 22 % (LyricsDisplay geometry).
double minLyricWindow(StageMetrics m, {int cleanLines = NowPlayingTokens.phoneCleanLines}) {
  const pad = NowPlayingTokens.lyricLinePads;
  final active = m.activeLyricLine + pad;
  final inactive = (m.inactiveLyricLine + pad + NowPlayingTokens.lyricsListLineGap) * (cleanLines - 1);
  return (NowPlayingTokens.lyricsListPadding + active + inactive + NowPlayingTokens.lyricsFadeBottom) /
      (1 - NowPlayingTokens.lyricsAnchorFraction);
}

/// Solves the phone column for a [width] × [height] content box (after the
/// system bars; bottom = max(nav inset, 16)). When the slots do not fit, the
/// ladder squeezes, in order: lower gaps (controls, hero, accessory) → upper
/// gaps (top bar, cover gap) → hero to one row → lyrics to one line (8 dp
/// hysteresis against [previousFrame]) → cover down to its 96 floor →
/// cover hidden. Never yields: two transport rows, the pill row, one title
/// line, 48 dp touch targets. Each slot rounds to device pixels on its own,
/// so the lyric list sees identical constraints every frame.
StageColumnBudget resolveStageColumnBudget({
  required double width,
  required double height,
  StageMetrics metrics = const StageMetrics(),
  double devicePixelRatio = 1,
  LyricFrame? previousFrame,
  NowPlayingEnlargedSpec? enlarged,
}) {
  double px(double v) => (v * devicePixelRatio).roundToDouble() / devicePixelRatio;

  // The enlarged phone (Medium Full): a wider column with a bigger rating
  // column, no 312 cap on the cover, three clean lyric lines, and the cover
  // gives way down to its comfort size before anything folds (§6, lyrics first).
  final ratingColumn = enlarged?.ratingColumn ?? _T.phoneRatingColumn;
  final stageMaxWidth = enlarged == null ? width : math.min(width, enlarged.columnWidth + 2 * _T.columnPadding);
  final coverCap = enlarged == null
      ? math.min(width - 2 * _T.columnPadding - _T.ratingGap - ratingColumn, _T.phoneCoverMax)
      : stageMaxWidth - 2 * _T.columnPadding - _T.ratingGap - ratingColumn;
  final coverComfort = enlarged == null ? null : math.min(coverCap, NowPlayingTokens.enlargedCoverComfort);

  final transportWidth = stageMaxWidth - 2 * _T.columnPadding;
  final labelsUnder = transportWidth < transportLabelsInlineMinWidth;
  final transportContent = _T.controlSize * 2 + _T.controlRowGap + (labelsUnder ? metrics.timeLabelLine : 0);
  final heroContent = metrics.heroArtistLine + _T.heroLineGap + metrics.heroTitleLine;

  var cover = math.max(0.0, coverCap);
  var topBar = _T.topBarNominal;
  var coverGap = _T.coverGapNominal;
  var controls = math.max(
    _T.controlsSlotNominal + (labelsUnder ? metrics.timeLabelLine : 0),
    transportContent + _T.controlsAirMin,
  );
  final controlsMin = transportContent + _T.controlsAirMin;
  var hero = math.max(_T.heroSlotNominal, heroContent + _T.heroAirMin);
  var heroMin = heroContent + _T.heroAirMin;
  var accessory = math.max(_T.accessorySlotNominal, _T.pillHeight + _T.accessoryAirMin);
  final accessoryMin = _T.pillHeight + _T.accessoryAirMin;
  var heroOneLine = false;
  var frame = LyricFrame.list;
  // The enlarged phone's lyric tools ride the end of the tab row at rest
  // (EnlargedToolTabRow): the row holds their 44 dp keys.
  var tabs = enlarged != null ? enlargedToolTabRow : math.max(_T.tabRowNominal, metrics.tabTextLine + _T.tabRowAir);
  final tabsMin = enlarged != null ? enlargedToolTabRow : metrics.tabTextLine;
  var tabGap = _T.tabGapNominal;

  final listMin = minLyricWindow(
    metrics,
    cleanLines: enlarged == null ? NowPlayingTokens.phoneCleanLines : NowPlayingTokens.enlargedCleanLines,
  );
  const oneLineMin = _T.oneLineRowNominal + _T.oneLineAirMin;
  // Hysteresis: once on one line, the list needs 8 dp more to come back.
  final listNeeds = previousFrame == LyricFrame.oneLine ? listMin + _T.lyricFrameHysteresis : listMin;

  double fixed() => topBar + cover + coverGap + tabs + tabGap + controls + hero + accessory;
  double deficit(double need) => need - (height - fixed());

  // (1) lower gaps, then (2) upper gaps.
  double squeeze(double need, double value, double min) {
    final d = deficit(need);
    if (d <= 0) return value;
    return math.max(min, value - d);
  }

  controls = squeeze(listNeeds, controls, controlsMin);
  hero = squeeze(listNeeds, hero, heroMin);
  accessory = squeeze(listNeeds, accessory, accessoryMin);
  topBar = squeeze(listNeeds, topBar, _T.topBarMin);
  coverGap = squeeze(listNeeds, coverGap, _T.coverGapMin);
  tabs = squeeze(listNeeds, tabs, tabsMin);

  // (2½) the enlarged phone's cover steps down to its comfort size.
  if (coverComfort != null && deficit(listNeeds) > 0) {
    cover = math.max(coverComfort, cover - deficit(listNeeds));
  }

  // (3) hero on one row.
  if (deficit(listNeeds) > 0) {
    heroOneLine = true;
    hero = metrics.heroTitleLine + _T.heroOneLineAir;
    heroMin = metrics.heroTitleLine + _T.heroAirMin;
    hero = squeeze(listNeeds, hero, heroMin);
  }

  // (4) lyrics fold to one tappable line.
  var need = listNeeds;
  if (deficit(listNeeds) > 0) {
    frame = LyricFrame.oneLine;
    need = oneLineMin;
    // The one-line row is not a pager page: no tab row over it.
    tabs = 0;
    tabGap = 0;
  }

  // (5) cover shrinks to its floor, then hides.
  if (deficit(need) > 0) {
    final shrunk = cover - deficit(need);
    if (shrunk >= _T.coverFloor) {
      cover = shrunk;
    } else {
      cover = 0;
      coverGap = 0;
    }
  }

  topBar = px(topBar);
  cover = px(cover);
  coverGap = px(coverGap);
  tabs = px(tabs);
  tabGap = px(tabGap);
  controls = px(controls);
  hero = px(hero);
  accessory = px(accessory);
  final lyrics = math.max(0.0, px(height - fixed()));

  return StageColumnBudget(
    width: width,
    height: height,
    topBar: topBar,
    cover: cover,
    coverGap: coverGap,
    tabs: tabs,
    tabGap: tabGap,
    lyrics: lyrics,
    controls: controls,
    hero: hero,
    accessory: accessory,
    heroOneLine: heroOneLine,
    lyricFrame: frame,
    labelsUnderWave: labelsUnder,
    ratingColumn: ratingColumn,
    stageWidth: enlarged == null
        ? width
        : math.min(stageMaxWidth, px(cover + _T.ratingGap + ratingColumn + 2 * _T.columnPadding)),
  );
}

/// The Expanded lyrics stage's slots (stage item 12), interpolated by the
/// detail progress [d] from [rest]: the cover row, controls and the cover
/// gap's excess give their height to the lyric viewport; the top bar returns
/// to 56, the text tabs grow into the tab group row and the accessory row
/// holds the page's tools.
@immutable
final class StageSlots {
  const StageSlots({
    required this.topBar,
    required this.coverRow,
    required this.coverGap,
    required this.tabs,
    required this.tabGap,
    required this.lyrics,
    required this.controls,
    required this.hero,
    required this.accessory,
  });

  final double topBar;
  final double coverRow;
  final double coverGap;
  final double tabs;
  final double tabGap;
  final double lyrics;
  final double controls;
  final double hero;
  final double accessory;

  static StageSlots lerp(StageColumnBudget rest, double d) {
    final t = d.clamp(0.0, 1.0);
    double l(double a, double b) => lerpDouble(a, b, t)!;
    final topBar = l(rest.topBar, NowPlayingTokens.topBarNominal);
    final coverRow = l(rest.cover, 0);
    final coverGap = l(rest.coverGap, NowPlayingTokens.coverGapMin);
    final tabs = l(rest.tabs, NowPlayingTokens.expandedTabRow);
    final tabGap = l(rest.tabGap, NowPlayingTokens.expandedTabGap);
    final controls = l(rest.controls, 0);
    final accessory = l(rest.accessory, NowPlayingTokens.accessorySlotNominal);
    final hero = rest.hero;
    final lyrics = math.max(
      0.0,
      rest.height - topBar - coverRow - coverGap - tabs - tabGap - controls - hero - accessory,
    );
    return StageSlots(
      topBar: topBar,
      coverRow: coverRow,
      coverGap: coverGap,
      tabs: tabs,
      tabGap: tabGap,
      lyrics: lyrics,
      controls: controls,
      hero: hero,
      accessory: accessory,
    );
  }
}

// ── Dual pane (Wide Full) ────────────────────────────────────────────────

/// Where the two-column player sets its title (DualPaneTitleForm).
enum DualPaneTitleForm { twoLines, oneLine, topBar }

abstract final class DualPaneTokens {
  /// The left stack's measuring width (TabletNP: the 312 cover side).
  static const double stackWidth = 312;
  static const double coverMin = 168;
  static const double coverFloor = 140;
  static const double frameNominal = 16;
  static const double frameMin = 8;
  static const double barGapNominal = 8;
  static const double barGapMin = 4;
  static const double coverToTransportNominal = 12;
  static const double coverToTransportMin = 8;

  /// The rating column (56) and its 12 gap at the cover's end.
  static const double ratingSide = 68;
  static const double ratingColumn = 56;
  static const double transportToTitleNominal = 16;
  static const double transportToTitleMin = 8;
  static const double columnGap = 24;
  static const double rightColumnMin = 320;
  static const double framePadding = 24;
  static const double topBarHeight = 48;
  static const double accessoryHeight = 68;

  /// The right column: tabs → 12 → pager → 12 → tools.
  static const double rightGap = 12;
  static const double titleLineGap = 2;
}

/// The dual pane's rest pose (resolveDualPaneBudget, no focus).
@immutable
final class DualPaneBudget {
  const DualPaneBudget({
    required this.frameTop,
    required this.barGap,
    required this.frameBottom,
    required this.titleForm,
    required this.slot,
    required this.cover,
    required this.coverToTransport,
    required this.transportToTitle,
    required this.title,
    this.focusCover,
  });

  final double frameTop;
  final double barGap;
  final double frameBottom;
  final DualPaneTitleForm titleForm;

  /// The left stack's width: the cover with the rating column at its side.
  final double slot;
  final double cover;
  final double coverToTransport;
  final double transportToTitle;

  /// 0 when the title folded into the top bar.
  final double title;

  /// Focus: the rating column retreats, the gap under the cover closes and
  /// the cover grows (≤ 1.5×, the lyrics keep 320) into the stack's width.
  /// Null when it can't grow by max(40, 12 %): the cover is a plain image.
  final double? focusCover;

  /// The left stack's width in focus.
  double get focusSlot => math.max(DualPaneTokens.stackWidth, focusCover ?? cover);

  @override
  bool operator ==(Object other) =>
      other is DualPaneBudget &&
      other.focusCover == focusCover &&
      other.frameTop == frameTop &&
      other.barGap == barGap &&
      other.frameBottom == frameBottom &&
      other.titleForm == titleForm &&
      other.slot == slot &&
      other.cover == cover &&
      other.coverToTransport == coverToTransport &&
      other.transportToTitle == transportToTitle &&
      other.title == title;

  @override
  int get hashCode => Object.hash(
    frameTop,
    barGap,
    frameBottom,
    titleForm,
    slot,
    cover,
    coverToTransport,
    transportToTitle,
    title,
    focusCover,
  );
}

/// The transport's height in the 312 stack (labels drop under the wave
/// there: 312 < [transportLabelsInlineMinWidth]).
double dualPaneTransportHeight(StageMetrics m) {
  const width = DualPaneTokens.stackWidth;
  final under = width < transportLabelsInlineMinWidth;
  return NowPlayingTokens.controlSize * 2 + NowPlayingTokens.controlRowGap + (under ? m.timeLabelLine : 0);
}

/// Solves the two columns for a [rowWidth] × [contentHeight] box (the
/// window less its side frame and top inset). Ladder: gaps → two-line title
/// → one line → the cover shrinks → the title moves into the top bar.
DualPaneBudget resolveDualPaneBudget({
  required double rowWidth,
  required double contentHeight,
  StageMetrics metrics = const StageMetrics(),
  double navBottom = 0,
  double barHeight = DualPaneTokens.topBarHeight,
}) {
  final transport = dualPaneTransportHeight(metrics);
  final artist = metrics.heroArtistLine + DualPaneTokens.titleLineGap;
  // headlineMedium ≈ the hero's displaySmall line less 8 (36 vs 44).
  final titleLine = metrics.heroTitleLine - 8;
  final titleOneLine = artist + titleLine;
  final titleTwoLines = artist + titleLine * 2;
  final fixedContent = barHeight + transport + navBottom;
  const gapsNominal =
      DualPaneTokens.transportToTitleNominal +
      DualPaneTokens.frameNominal +
      DualPaneTokens.frameNominal +
      DualPaneTokens.barGapNominal +
      DualPaneTokens.coverToTransportNominal;
  const gapsMin =
      DualPaneTokens.transportToTitleMin +
      DualPaneTokens.frameMin +
      DualPaneTokens.frameMin +
      DualPaneTokens.barGapMin +
      DualPaneTokens.coverToTransportMin;
  final widthCap = rowWidth - DualPaneTokens.columnGap - DualPaneTokens.rightColumnMin - DualPaneTokens.ratingSide;

  double coverFor(double title) => contentHeight - fixedContent - title - gapsMin;

  DualPaneBudget budgetFor(DualPaneTitleForm form, double title) {
    final cover = math.max(
      DualPaneTokens.coverFloor,
      math.min(math.min(coverFor(title), DualPaneTokens.stackWidth), widthCap),
    );
    // Squeeze lower gaps (transport→title, frame bottom) first, then upper.
    var deficit = math.max(0.0, fixedContent + title + gapsNominal + cover - contentHeight);
    double take(double nominal, double min) {
      final d = math.min(deficit, nominal - min);
      deficit -= d;
      return nominal - d;
    }

    final transportToTitle = take(DualPaneTokens.transportToTitleNominal, DualPaneTokens.transportToTitleMin);
    final frameBottom = take(DualPaneTokens.frameNominal, DualPaneTokens.frameMin);
    final frameTop = take(DualPaneTokens.frameNominal, DualPaneTokens.frameMin);
    final barGap = take(DualPaneTokens.barGapNominal, DualPaneTokens.barGapMin);
    final coverToTransport = take(DualPaneTokens.coverToTransportNominal, DualPaneTokens.coverToTransportMin);
    final focusHeight =
        contentHeight -
        barHeight -
        transport -
        navBottom -
        frameTop -
        barGap -
        frameBottom -
        DualPaneTokens.coverToTransportMin -
        transportToTitle -
        title;
    final focusCover = [
      cover * NowPlayingTokens.coverFocusMaxScale,
      rowWidth - DualPaneTokens.columnGap - DualPaneTokens.rightColumnMin,
      focusHeight,
    ].reduce(math.min);
    return DualPaneBudget(
      focusCover: coverFocusOffered(cover, focusCover) ? focusCover : null,
      frameTop: frameTop,
      barGap: barGap,
      frameBottom: frameBottom,
      titleForm: form,
      slot: math.max(DualPaneTokens.stackWidth, cover + DualPaneTokens.ratingSide),
      cover: cover,
      coverToTransport: coverToTransport,
      transportToTitle: transportToTitle,
      title: title,
    );
  }

  // Two lines give way to one where only one line leaves the cover room to focus.
  final twoLines = coverFor(titleTwoLines) >= DualPaneTokens.stackWidth
      ? budgetFor(DualPaneTitleForm.twoLines, titleTwoLines)
      : null;
  final oneLine = coverFor(titleOneLine) >= DualPaneTokens.coverMin
      ? budgetFor(DualPaneTitleForm.oneLine, titleOneLine)
      : null;
  if (twoLines != null && (twoLines.focusCover != null || oneLine?.focusCover == null)) return twoLines;
  if (oneLine != null) return oneLine;
  return budgetFor(DualPaneTitleForm.topBar, 0);
}

// ── Landscape handset ───────────────────────────────────────────────────

abstract final class LandscapeTokens {
  /// Below this content width the transport and title stack under the cover.
  static const double stackedMaxWidth = 792;
  static const double ratingColumn = 56;
  static const double ratingGap = 12;
  static const double columnGap = 28;
  static const double titleRow = 36;
  static const double coverToTransport = 10;
  static const double transportToTitle = 4;
  static const double coverFloor = 120;
  static const double stackedCoverFloor = 72;
  static const double stackedLeftMin = 300;
  static const double stackedLeftShare = 0.46;
  static const double restColumnMin = 300;
  static const double headerHeight = 40;
  static const double tabRow = 40;
  static const double lyricsToTransport = 8;

  /// Focus: the right column keeps this much; on the narrower windows the
  /// cover stays within this share of the width ("封面可以不铺那么满").
  static const double focusColumnMin = 300;
  static const double focusCoverShare = 0.44;
}

/// The landscape handset's frame (resolveLandscapeLayout, rest pose).
/// Wide (an ordinary phone on its side): the cover takes the height with
/// the rating column at its end; the right column carries the header, the
/// tabs, a few lyric lines, the transport and the title. Stacked (16:9, a
/// narrow window): transport and title under the cover; the right column
/// is header + tabs + a full column of lyrics.
@immutable
final class LandscapeLayout {
  const LandscapeLayout({
    required this.stacked,
    required this.cover,
    required this.leftWidth,
    required this.columnX,
    required this.transport,
    double? focusCover,
    double? focusColumnX,
  }) : focusCover = focusCover ?? cover,
       focusColumnX = focusColumnX ?? columnX;

  final bool stacked;
  final double cover;

  /// Focus (every landscape window): the header, tabs and rating step away;
  /// the cover takes the height on an ordinary phone, stays within 44 % of
  /// the width elsewhere; the right column starts at [focusColumnX].
  final double focusCover;
  final double focusColumnX;

  /// The left column (cover + rating; stacked: at least its floor).
  final double leftWidth;

  /// Where the right column starts.
  final double columnX;

  /// The two-row transport's height at its own width.
  final double transport;

  @override
  bool operator ==(Object other) =>
      other is LandscapeLayout &&
      other.stacked == stacked &&
      other.cover == cover &&
      other.leftWidth == leftWidth &&
      other.columnX == columnX &&
      other.transport == transport &&
      other.focusCover == focusCover &&
      other.focusColumnX == focusColumnX;

  @override
  int get hashCode => Object.hash(stacked, cover, leftWidth, columnX, transport, focusCover, focusColumnX);
}

/// The transport's height at [width] (labels under the wave when narrow).
double transportHeightAt(double width, StageMetrics m) =>
    NowPlayingTokens.controlSize * 2 +
    NowPlayingTokens.controlRowGap +
    (width - 2 * NowPlayingTokens.columnPadding < transportLabelsInlineMinWidth ? m.timeLabelLine : 0);

LandscapeLayout resolveLandscapeLayout({
  required double width,
  required double height,
  StageMetrics metrics = const StageMetrics(),
}) {
  const rating = LandscapeTokens.ratingColumn + LandscapeTokens.ratingGap;
  if (width >= LandscapeTokens.stackedMaxWidth) {
    final cover = math.max(
      LandscapeTokens.coverFloor,
      math.min(height, width - LandscapeTokens.columnGap - rating - LandscapeTokens.restColumnMin),
    );
    final columnX = cover + rating + LandscapeTokens.columnGap;
    final focusCover = math.max(
      LandscapeTokens.coverFloor,
      [
        height,
        width - LandscapeTokens.columnGap - LandscapeTokens.focusColumnMin,
        width * LandscapeTokens.focusCoverShare,
      ].reduce(math.min),
    );
    return LandscapeLayout(
      stacked: false,
      cover: cover,
      focusCover: focusCover,
      focusColumnX: focusCover + LandscapeTokens.columnGap,
      leftWidth: cover + rating,
      columnX: columnX,
      transport: transportHeightAt(width - columnX + 2 * NowPlayingTokens.columnPadding, metrics),
    );
  }
  double coverFor(double transport) => math.max(
    LandscapeTokens.stackedCoverFloor,
    height - LandscapeTokens.coverToTransport - transport - LandscapeTokens.transportToTitle - LandscapeTokens.titleRow,
  );
  final leftFloor = math.min(LandscapeTokens.stackedLeftMin, width * LandscapeTokens.stackedLeftShare);
  double leftFor(double cover) => math.max(cover + rating, leftFloor);
  var transport = transportHeightAt(0, metrics);
  var cover = coverFor(transport);
  transport = transportHeightAt(leftFor(cover) + 2 * NowPlayingTokens.columnPadding, metrics);
  cover = coverFor(transport);
  final left = leftFor(cover);
  return LandscapeLayout(
    stacked: true,
    cover: cover,
    leftWidth: left,
    columnX: left + LandscapeTokens.columnGap,
    transport: transport,
  );
}
