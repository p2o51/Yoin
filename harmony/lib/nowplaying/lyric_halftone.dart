import 'dart:ui' as ui;

import 'package:flutter/widgets.dart';

import '../theme/tokens.dart';
import '../ui/adaptive/yoin_window_info.dart';

// Focus's lyric halftone (LyricHalftone.kt; 波点, owner 2026-11-03, prototype
// v7e): in focus every lyric line is printed through a fine grid of dots. The
// current line develops from dots to solid text, the other lines rest as fine
// dots, and a line that has been sung breaks back into dots. Focus only — the
// resting page and the opened lyrics stay solid.
//
// A line's dot radius lives in dp: rest at [NowPlayingEffectTokens.lyricDotsRest],
// [NowPlayingEffectTokens.lyricDotsSolid] and above = no mask at all (solid
// text, not offscreen).

typedef _T = NowPlayingEffectTokens;

/// The dot radius (dp) a line shows at [focus] 0..1, given its own [lineRadius].
double lyricDotRadius(double lineRadius, double focus) {
  final f = focus.clamp(0.0, 1.0);
  return _T.lyricDotsSolid + (lineRadius - _T.lyricDotsSolid) * f;
}

/// Whether the line at [index] prints as [NowPlayingEffectTokens.lyricDotsReadable]
/// while [current] is the line being sung (LyricHalftone.kt isReadableLyricLine):
/// one of the next [NowPlayingEffectTokens.lyricDotsReadableLines], or one of the
/// [NowPlayingEffectTokens.lyricDotsReadableLinesAbove] just sung (Android
/// 11-14). The current line itself is solid, and nothing is readable while no
/// line is current ([current] < 0).
bool isReadableLyricLine(int index, int current) {
  if (current < 0 || index == current) return false;
  final gap = index - current;
  return gap > 0 ? gap <= _T.lyricDotsReadableLines : -gap <= _T.lyricDotsReadableLinesAbove;
}

/// [isReadableLyricLine] for a lyric row of a list whose title card may hold
/// the focus: while the song's intro seats the title card ([introOnHeader],
/// no line current yet), the first rows read as the lines after it, as
/// Android's full list counts the header as item 0.
bool lyricRowReadable(int index, int active, {bool introOnHeader = false}) =>
    active >= 0 ? isReadableLyricLine(index, active) : introOnHeader && isReadableLyricLine(index + 1, 0);

/// The other lines' resting dot radius for [window] ([readable]: see
/// [isReadableLyricLine]; full enough to read everywhere). Tablets and the
/// fold's inner screen (medium width AND height) rest fuller, so the other
/// lines still read across the longer column.
double lyricDotsRestFor(YoinWindowInfo window, {bool readable = false}) {
  if (readable) return _T.lyricDotsReadable;
  final large = window.width >= YoinWindowInfo.mediumWidth && window.height >= YoinWindowInfo.mediumHeight;
  return large ? _T.lyricDotsRestLarge : _T.lyricDotsRest;
}

/// Prints the full lyrics list below it through the focus halftone, as the
/// focus list does: the two-column layout keeps its lyrics beside a focused
/// cover (LyricsFullscreenPane's `focus`). [focus] is the focus stage's
/// progress, 0 = solid.
class LyricHalftoneScope extends InheritedWidget {
  const LyricHalftoneScope({super.key, required this.focus, required super.child});

  final Animation<double> focus;

  /// The focus progress the lines print at (and rebuild with), or null outside a scope.
  static Animation<double>? maybeOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<LyricHalftoneScope>()?.focus;

  /// Whether [context] sits in a scope, without depending on it.
  static bool isIn(BuildContext context) => context.getInheritedWidgetOfExactType<LyricHalftoneScope>() != null;

  @override
  bool updateShouldNotify(LyricHalftoneScope oldWidget) => !identical(oldWidget.focus, focus);
}

/// How many radius steps a dot tile is quantised to (one tile image per step).
const int lyricDotRadiusSteps = 30;

/// The tile step [radius] (dp) prints at: 0 = nothing shows, [lyricDotRadiusSteps]
/// = a full cell; null = solid (no mask).
int? lyricDotStep(double radius) {
  if (radius >= _T.lyricDotsSolid) return null;
  return (radius / _T.lyricDotsSolid * lyricDotRadiusSteps).round().clamp(0, lyricDotRadiusSteps);
}

/// Prints [child] through a grid of dots [NowPlayingEffectTokens.lyricDotsCell]
/// apart, each [radius] dp across: solid at or above the solid radius
/// (no offscreen layer), invisible at step 0.
class LyricHalftone extends StatelessWidget {
  const LyricHalftone({super.key, required this.radius, required this.child});

  final double radius;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final step = lyricDotStep(radius);
    if (step == null) return child;
    if (step <= 0) return Opacity(opacity: 0, child: child);
    final dpr = MediaQuery.maybeDevicePixelRatioOf(context) ?? 1;
    final tile = _DotTiles.tile(dpr, step);
    final scale = 1 / dpr;
    return ShaderMask(
      blendMode: BlendMode.dstIn,
      shaderCallback: (_) =>
          ImageShader(tile, TileMode.repeated, TileMode.repeated, Matrix4.diagonal3Values(scale, scale, 1).storage),
      child: child,
    );
  }
}

/// The dot tiles, one per (cell in device px, step), drawn once.
abstract final class _DotTiles {
  static final Map<(int, int), ui.Image> _cache = {};

  static ui.Image tile(double dpr, int step) {
    final cell = (_T.lyricDotsCell * dpr).round().clamp(2, 1 << 12);
    return _cache.putIfAbsent((cell, step), () {
      final recorder = ui.PictureRecorder();
      Canvas(recorder).drawCircle(
        Offset(cell / 2, cell / 2),
        _T.lyricDotsSolid * step / lyricDotRadiusSteps * dpr,
        Paint()
          ..color = const Color(0xFF000000)
          ..isAntiAlias = true,
      );
      final picture = recorder.endRecording();
      final image = picture.toImageSync(cell, cell);
      picture.dispose();
      return image;
    });
  }
}
