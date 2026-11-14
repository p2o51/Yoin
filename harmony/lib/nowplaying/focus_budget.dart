import 'dart:math' as math;

import 'package:flutter/foundation.dart';

import '../theme/tokens.dart';
import 'now_playing_presentation.dart';
import 'stage_budget.dart';

typedef _T = NowPlayingTokens;
typedef _F = NowPlayingFocusTokens;

/// Focus is worth a tap only when the cover grows by max(40, 12 %)
/// (NowPlayingBudget.kt coverFocusOffered).
bool coverFocusOffered(double rest, double focus) =>
    focus - rest >= math.max(_T.coverFocusMinGrowth, rest * _T.coverFocusMinGrowthFraction);

/// A gap with its resting [nominal] and the [min] it compresses to.
@immutable
final class NpGap {
  const NpGap(this.nominal, this.min);

  final double nominal;
  final double min;

  double get slack => math.max(0, nominal - min);

  /// 0 = nominal, 1 = fully compressed.
  double at(double squeeze) => nominal + (min - nominal) * squeeze.clamp(0.0, 1.0);
}

/// How far each tier compresses (0..1) and what is still missing after both.
typedef Squeeze = ({double lower, double upper, double unmet});

double _slack(List<NpGap> gaps) => gaps.fold(0.0, (a, g) => a + g.slack);
double _nominal(List<NpGap> gaps) => gaps.fold(0.0, (a, g) => a + g.nominal);

/// Spends [deficit] from the LOWER gaps first, then the UPPER ones, each tier
/// on one shared fraction (NowPlayingBudget.kt squeezeGaps).
Squeeze squeezeGaps(double deficit, List<NpGap> lower, List<NpGap> upper) {
  if (deficit <= 0) return (lower: 0, upper: 0, unmet: 0);
  final lowerSlack = _slack(lower);
  final upperSlack = _slack(upper);
  if (deficit <= lowerSlack) return (lower: lowerSlack > 0 ? deficit / lowerSlack : 0, upper: 0, unmet: 0);
  final rest = deficit - lowerSlack;
  if (rest <= upperSlack) return (lower: 1, upper: upperSlack > 0 ? rest / upperSlack : 0, unmet: 0);
  return (lower: 1, upper: 1, unmet: rest - upperSlack);
}

/// The focus lyric window's geometry: lines printed [NowPlayingFocusTokens.lyricScale]
/// larger, the current one pinned to the top with [NowPlayingFocusTokens.linesAbove]
/// whole line above it. Height that holds [n] clean lines.
double focusLyricWindow(StageMetrics m, int n) {
  const pad = _T.lyricLinePads;
  final active = (m.activeLyricLine + pad) * _F.lyricScale;
  final inactive = (m.inactiveLyricLine + pad) * _F.lyricScale;
  final following = math.max(0, n - 1) + _F.linesAbove;
  final lines = active + (inactive + _T.lyricsListLineGap) * following;
  return (_T.lyricsListPadding + lines + _T.lyricsFadeBottom) / (1 - _F.lyricsAnchorFraction);
}

/// The single column in FOCUS (tap the cover): the top bar, rating, tabs and
/// pills retreat; the title and both transport rows stay; the cover grows
/// with equal margins left, top and right, and the column narrows to it.
/// Every slot is exact: they sum to the content height.
@immutable
final class StageFocusPose {
  const StageFocusPose({
    required this.top,
    required this.cover,
    required this.coverGap,
    required this.lyrics,
    required this.controls,
    required this.hero,
    required this.bottom,
    required this.stageWidth,
    required this.labelsUnderWave,
    this.lyricInset = 0,
    this.short = false,
  });

  /// The cover's top (the top bar's slot once it retreats).
  final double top;
  final double cover;
  final double coverGap;
  final double lyrics;
  final double controls;
  final double hero;

  /// The air under the hero (the pills' slot once they retreat).
  final double bottom;

  /// The column's width in focus (the cover + its 24 insets), centred.
  final double stageWidth;

  /// The focus transport's time labels sit under the wave (a narrow cover).
  final bool labelsUnderWave;

  /// Short windows: the lyrics line up with the centred cover (each side's
  /// extra inset); the transport and title keep the full width.
  final double lyricInset;

  /// A short window's focus (the cover keeps its size; the lyrics gain).
  final bool short;

  double get total => top + cover + coverGap + lyrics + controls + hero + bottom;

  @override
  bool operator ==(Object other) =>
      other is StageFocusPose &&
      other.top == top &&
      other.cover == cover &&
      other.coverGap == coverGap &&
      other.lyrics == lyrics &&
      other.controls == controls &&
      other.hero == hero &&
      other.bottom == bottom &&
      other.stageWidth == stageWidth &&
      other.labelsUnderWave == labelsUnderWave &&
      other.lyricInset == lyricInset &&
      other.short == short;

  @override
  int get hashCode =>
      Object.hash(top, cover, coverGap, lyrics, controls, hero, bottom, stageWidth, labelsUnderWave, lyricInset, short);
}

/// The focus pose for a resting [rest] column (NowPlayingBudget.kt
/// solveFocus / shortWindowFocus), or null when the cover can't grow by
/// max(40, 12 %): then it is a plain image. Short windows (the lyrics rest
/// on one line) keep the cover's size, centred, and open the lyrics.
StageFocusPose? resolveStageFocus({
  required StageColumnBudget rest,
  StageMetrics metrics = const StageMetrics(),
  double devicePixelRatio = 1,
  NowPlayingEnlargedSpec? enlarged,
}) {
  if (rest.cover <= 0) return null;
  double px(double v) => (v * devicePixelRatio).roundToDouble() / devicePixelRatio;
  final width = rest.width;
  final height = rest.height;
  final stageMaxWidth = enlarged == null ? width : math.min(width, enlarged.columnWidth + 2 * _T.columnPadding);

  NpGap controlsGap(bool labelsUnder) {
    final transport = _T.controlSize * 2 + _T.controlRowGap + (labelsUnder ? metrics.timeLabelLine : 0);
    return NpGap(
      math.max(_T.controlsSlotNominal + (labelsUnder ? metrics.timeLabelLine : 0), transport + _T.controlsAirMin),
      transport + _T.controlsAirMin,
    );
  }

  final heroContent = metrics.heroArtistLine + _T.heroLineGap + metrics.heroTitleLine;
  final hero = rest.heroOneLine
      ? NpGap(metrics.heroTitleLine + _T.heroOneLineAir, metrics.heroTitleLine + _T.heroAirMin)
      : NpGap(math.max(_T.heroSlotNominal, heroContent + _T.heroAirMin), heroContent + _T.heroAirMin);
  const coverGap = NpGap(_T.coverGapNominal, _T.coverGapMin);
  const focusBottom = NpGap(_F.bottomNominal, _F.bottomMin);
  final focusCoverCap = [
    stageMaxWidth - 2 * _T.columnPadding,
    enlarged == null ? _T.phoneFocusCoverMax : stageMaxWidth,
    rest.cover * _T.coverFocusMaxScale,
  ].reduce(math.min);

  StageFocusPose finish({
    required double top,
    required double cover,
    required double cg,
    required double ctl,
    required double hr,
    required double bottom,
    required bool labelsUnder,
    required bool short,
  }) {
    final t = px(top), c = px(cover), g = px(cg), k = px(ctl), h = px(hr), b = px(bottom);
    final stageWidth = short ? rest.stageWidth : px(c + 2 * _T.columnPadding);
    return StageFocusPose(
      top: t,
      cover: c,
      coverGap: g,
      lyrics: math.max(0.0, px(height - t - c - g - k - h - b)),
      controls: k,
      hero: h,
      bottom: b,
      stageWidth: stageWidth,
      labelsUnderWave: labelsUnder,
      lyricInset: short ? math.max(0.0, px((rest.stageWidth - 2 * _T.columnPadding - c) / 2)) : 0,
      short: short,
    );
  }

  StageFocusPose? solveFocus(bool labelsUnder) {
    final controls = controlsGap(labelsUnder);
    final lower = [controls, hero, focusBottom];
    const upper = [coverGap];
    final nominal = _nominal(lower) + _nominal(upper);
    final fixedMin = nominal - _slack(lower) - _slack(upper);

    StageFocusPose pose(double cover, double listMin) {
      final top = math.max(_F.topMin, math.min((width - cover) / 2, height - cover - fixedMin - listMin));
      final s = squeezeGaps(math.max(0, top + cover + nominal + listMin - height), lower, upper);
      return finish(
        top: top,
        cover: cover,
        cg: coverGap.at(s.upper),
        ctl: controls.at(s.lower),
        hr: hero.at(s.lower),
        bottom: focusBottom.at(s.lower),
        labelsUnder: labelsUnder,
        short: false,
      );
    }

    // Equal margins first; three clean lines where they fit, else two. The
    // column narrows to the cover, so it keeps 70 % of its resting size and
    // the transport's narrowest width; below that the top margin gives.
    final equalFloor = math.max(rest.cover * _F.equalMarginCoverKeep, _F.minColumn);
    double equalCover(int lines) =>
        math.max(0, math.min(focusCoverCap, (height - fixedMin - focusLyricWindow(metrics, lines)) * 2 - width));
    final freeMin = focusLyricWindow(metrics, _F.minCleanLines);
    final freeCover = math.min(focusCoverCap, math.max(0.0, height - fixedMin - freeMin - _F.topMin));
    final StageFocusPose? chosen;
    if (equalCover(_F.cleanLines) >= equalFloor) {
      chosen = pose(equalCover(_F.cleanLines), focusLyricWindow(metrics, _F.cleanLines));
    } else if (equalCover(_F.minCleanLines) >= equalFloor) {
      chosen = pose(equalCover(_F.minCleanLines), freeMin);
    } else if (equalFloor <= focusCoverCap && height - equalFloor - fixedMin - freeMin >= _F.topMin) {
      chosen = pose(equalFloor, freeMin);
    } else {
      chosen = pose(freeCover, freeMin);
    }
    // §6: the cover is tappable only when it visibly grows.
    return coverFocusOffered(rest.cover, chosen.cover) ? chosen : null;
  }

  StageFocusPose? shortWindowFocus(int lines, {required bool coverGives}) {
    final controls = controlsGap(rest.labelsUnderWave);
    final lower = [controls, hero, focusBottom];
    const upper = [coverGap];
    final nominal = _nominal(lower) + _nominal(upper);
    final listMin = focusLyricWindow(metrics, lines);
    final s = squeezeGaps(math.max(0, _F.topShort + rest.cover + nominal + listMin - height), lower, upper);
    // Only when even the squeezed gaps can't hold the lines does the cover
    // give — never so far that the lyrics, which share its width, crowd.
    final cover = math.max(math.max(0.0, rest.cover - s.unmet), math.min(rest.cover, _F.shortMinColumn));
    if (cover < rest.cover * _F.equalMarginCoverKeep) return null;
    if (!coverGives && cover < rest.cover - 0.5) return null;
    final cg = coverGap.at(s.upper), ctl = controls.at(s.lower), hr = hero.at(s.lower), b = focusBottom.at(s.lower);
    final lyric = height - _F.topShort - cover - cg - ctl - hr - b;
    if (lyric < listMin - 0.5) return null;
    return finish(
      top: _F.topShort,
      cover: cover,
      cg: cg,
      ctl: ctl,
      hr: hr,
      bottom: b,
      labelsUnder: rest.labelsUnderWave,
      short: true,
    );
  }

  final shortWindow = rest.lyricFrame == LyricFrame.oneLine;
  if (shortWindow) {
    // The cover keeps its size before the lyrics keep their second line.
    return shortWindowFocus(_F.minCleanLines, coverGives: false) ??
        shortWindowFocus(1, coverGives: false) ??
        shortWindowFocus(1, coverGives: true);
  }
  var focus = solveFocus(rest.labelsUnderWave);
  // The narrowed column can put the time labels under the wave: solve again with that row.
  if (focus != null && !rest.labelsUnderWave && focus.cover < transportLabelsInlineMinWidth) {
    focus = solveFocus(true);
  }
  return focus;
}

/// The single column's slots this frame: rest ⇄ focus by [f], then ⇄ the
/// Expanded layout by [d] (Android stage(rest, focus, expanded)).
StageSlots stageSlotsAt(StageColumnBudget rest, StageFocusPose? focus, {required double f, required double d}) {
  final ff = focus == null ? 0.0 : f.clamp(0.0, 1.0);
  final t = d.clamp(0.0, 1.0);
  double l(double a, double b, double x) => a + (b - a) * x;
  double base(double r, double? fo) => fo == null ? r : l(r, fo, ff);
  final topBar = l(base(rest.topBar, focus?.top), _T.topBarNominal, t);
  final coverRow = l(base(rest.cover, focus?.cover), 0, t);
  final coverGap = l(base(rest.coverGap, focus?.coverGap), _T.coverGapMin, t);
  final tabs = l(base(rest.tabs, focus == null ? null : 0), _T.expandedTabRow, t);
  final tabGap = l(base(rest.tabGap, focus == null ? null : 0), _T.expandedTabGap, t);
  final controls = l(base(rest.controls, focus?.controls), 0, t);
  final accessory = l(base(rest.accessory, focus?.bottom), _T.accessorySlotNominal, t);
  final hero = base(rest.hero, focus?.hero);
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

/// The column's width this frame: it narrows to the focus cover, and opens
/// back to the resting column as the stage expands.
double liveStageWidth(StageColumnBudget rest, StageFocusPose? focus, {required double f, required double d}) {
  final ff = focus == null ? 0.0 : f.clamp(0.0, 1.0);
  final focused = rest.stageWidth + ((focus?.stageWidth ?? rest.stageWidth) - rest.stageWidth) * ff;
  return focused + (rest.stageWidth - focused) * d.clamp(0.0, 1.0);
}
