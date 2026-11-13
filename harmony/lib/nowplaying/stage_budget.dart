import 'dart:math' as math;
import 'dart:ui' show lerpDouble;

import 'package:flutter/foundation.dart';

import '../theme/tokens.dart';

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

  @override
  bool operator ==(Object other) =>
      other is StageMetrics &&
      other.heroArtistLine == heroArtistLine &&
      other.heroTitleLine == heroTitleLine &&
      other.activeLyricLine == activeLyricLine &&
      other.inactiveLyricLine == inactiveLyricLine &&
      other.timeLabelLine == timeLabelLine;

  @override
  int get hashCode => Object.hash(heroArtistLine, heroTitleLine, activeLyricLine, inactiveLyricLine, timeLabelLine);
}

typedef _T = NowPlayingTokens;

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
  });

  final double width;
  final double height;
  final double topBar;

  /// The cover square's edge (0 = hidden: below the 96 floor).
  final double cover;
  final double coverGap;

  /// The text-tab row and its gap; 0 in v1 (no About / Note tabs) but kept
  /// so they can return without a rewrite.
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
      other.labelsUnderWave == labelsUnderWave;

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
  );
}

/// Transport labels sit inline only when the transport is at least this wide (PlaybackControls.kt L1024).
const double transportLabelsInlineMinWidth = 360;

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
}) {
  double px(double v) => (v * devicePixelRatio).roundToDouble() / devicePixelRatio;

  final transportWidth = width - 2 * _T.columnPadding;
  final labelsUnder = transportWidth < transportLabelsInlineMinWidth;
  final transportContent = _T.controlSize * 2 + _T.controlRowGap + (labelsUnder ? metrics.timeLabelLine : 0);
  final heroContent = metrics.heroArtistLine + _T.heroLineGap + metrics.heroTitleLine;

  var cover = math.max(
    0.0,
    math.min(width - 2 * _T.columnPadding - _T.ratingGap - _T.phoneRatingColumn, _T.phoneCoverMax),
  );
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
  const tabs = 0.0;
  const tabGap = 0.0;

  final listMin = minLyricWindow(metrics);
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
  );
}

/// The Expanded lyrics stage's slots (stage item 12), interpolated by the
/// detail progress [d] from [rest]: the cover row, controls and the cover
/// gap's excess give their height to the lyric viewport; the top bar returns
/// to 56 and the accessory row holds the lyric tools.
@immutable
final class StageSlots {
  const StageSlots({
    required this.topBar,
    required this.coverRow,
    required this.coverGap,
    required this.lyrics,
    required this.controls,
    required this.hero,
    required this.accessory,
  });

  final double topBar;
  final double coverRow;
  final double coverGap;
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
    final controls = l(rest.controls, 0);
    final accessory = l(rest.accessory, NowPlayingTokens.accessorySlotNominal);
    final hero = rest.hero;
    final lyrics = math.max(0.0, rest.height - topBar - coverRow - coverGap - controls - hero - accessory);
    return StageSlots(
      topBar: topBar,
      coverRow: coverRow,
      coverGap: coverGap,
      lyrics: lyrics,
      controls: controls,
      hero: hero,
      accessory: accessory,
    );
  }
}
