import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:material_color_utilities/material_color_utilities.dart' show Hct;

import '../theme/playback_theme.dart';

// Focus (tap the cover) paints the whole room in the cover's colours
// (FocusField.kt, owner 2026-11-02, prototype "Yoin 渐变实验室", round 4): a
// full-screen gradient of 16 control points, each with its own colour, reach
// and slow path. No blur — the colours are interpolated, not smeared.
//
//  - six points around the cover take their colour from the matching spot
//    on the cover's rim (that spot slowly orbits, so colour creeps along it);
//  - eight points around the room take turns over the cover's main hues,
//    neighbours never the same, with a little of the matching corner mixed in;
//  - one point in the lower corner holds the second hue;
//  - one "wanderer" carries the cover's most vivid colour round the room.
//
// Chroma is lifted and lightness pinned into a band the screen's text reads
// on (pale in light theme, deep in dark). On entry every point starts packed
// into the cover and opens out with the focus spring.
//
// Pure Dart on ARGB ints, unit-tested in test/nowplaying/focus_field_test.dart.

/// Dark focus's room from Now Playing's dark scheme (FocusField.kt
/// darkRoomOf): the hues of [primary], up to two hues off the cover's
/// [coverSwatches] (else the scheme's, kept near the primary), held dark
/// enough for white lyrics but at full colour; the fourth a deep blend, the
/// wanderer a lit primary.
RoomPalette darkRoomOf(int primary, int secondary, int tertiary, [List<int> coverSwatches = const []]) =>
    _roomOf(primary, secondary, tertiary, coverSwatches, _darkRoomTones, _darkRoomMinChroma);

/// Light focus's room, the same recipe as [darkRoomOf] re-toned pale for dark
/// lyrics on top (owner 2026-11-05).
RoomPalette lightRoomOf(int primary, int secondary, int tertiary, [List<int> coverSwatches = const []]) =>
    _roomOf(primary, secondary, tertiary, coverSwatches, _lightRoomTones, _lightRoomMinChroma);

RoomPalette _roomOf(
  int primary,
  int secondary,
  int tertiary,
  List<int> coverSwatches,
  List<double> tones,
  double minChroma,
) {
  final p = Hct.fromInt(primary);
  final s = Hct.fromInt(secondary);
  final t = Hct.fromInt(tertiary);
  int at(double hue, double chroma, double tone) => Hct.from(hue, chroma, tone).toInt();
  // The second and third hues come from the cover itself; where it has no
  // other hue, the scheme's, kept within [darkRoomMaxHueTurn] of the primary.
  final (fromCover2, fromCover3) = coverSeedHues(p.hue, coverSwatches);
  final sHue = fromCover2 ?? nearHue(p.hue, s.hue, darkRoomMaxHueTurn);
  final tHue = fromCover3 ?? nearHue(p.hue, t.hue, darkRoomMaxHueTurn);
  final between = _midHue(p.hue, tHue);
  return RoomPalette(
    colors: [
      at(p.hue, math.max(p.chroma, minChroma), tones[0]),
      at(sHue, math.max(s.chroma, minChroma * 0.8), tones[1]),
      at(tHue, math.max(t.chroma, minChroma), tones[2]),
      at(between, minChroma, tones[3]),
    ],
    accent: at(p.hue, math.max(p.chroma, minChroma * 1.6), tones[4]),
  );
}

/// Dark room tones: primary, second, third, blend, wanderer (PR #128's).
const List<double> _darkRoomTones = [32, 24, 32, 14, 46];

/// Light room tones, in the pale band (~0.70–0.94 HSL): the blend lightest,
/// the wanderer the deepest point.
const List<double> _lightRoomTones = [88, 84, 88, 93, 80];

/// Light pastels need less chroma to read as colour than the dark room's.
const double _lightRoomMinChroma = 30;

/// 2026-11-05: back to the PR #128 tones with a little more chroma.
const double _darkRoomMinChroma = 44;

/// Up to two hues the cover really holds besides [primaryHue], from its
/// [swatches] in order of importance: each a colour (not a grey), at least
/// [seedMinHueGap] from the primary and from each other. Null where none.
(double?, double?) coverSeedHues(double primaryHue, List<int> swatches) {
  final picked = <double>[];
  for (final c in swatches) {
    final hct = Hct.fromInt(c);
    if (hct.chroma < seedMinChroma) continue;
    final far = [primaryHue, ...picked].every((h) => (((hct.hue - h + 540) % 360) - 180).abs() >= seedMinHueGap);
    if (far) picked.add(hct.hue);
    if (picked.length == 2) break;
  }
  return (picked.isNotEmpty ? picked[0] : null, picked.length > 1 ? picked[1] : null);
}

/// A swatch under this chroma is a grey: it lends the room no hue.
const double seedMinChroma = 12;

/// Hues closer than this to one already in the room count as the same colour.
const double seedMinHueGap = 25;

/// [b], turned toward [a] the shorter way until it is at most [maxTurn] degrees from it.
double nearHue(double a, double b, double maxTurn) {
  final d = ((b - a + 540) % 360) - 180;
  return (a + d.clamp(-maxTurn, maxTurn) + 360) % 360;
}

/// How far the dark room's other colours may turn from the primary's hue.
const double darkRoomMaxHueTurn = 50;

double _midHue(double a, double b) {
  final d = ((b - a + 540) % 360) - 180;
  return (a + d / 2 + 360) % 360;
}

/// A ready-made room palette (ARGB): four colours, most important first, and
/// the wanderer ([accent]).
final class RoomPalette {
  RoomPalette({required this.colors, required this.accent}) : assert(colors.length == 4, 'four colours');

  final List<int> colors;
  final int accent;

  /// The colour [phase] of the way round the four (wraps), for the rim's creep.
  int around(double phase) {
    final x = ((phase % 4) + 4) % 4;
    final i = x.floor().clamp(0, 3);
    return _mix(colors[i], colors[(i + 1) % 4], x - i);
  }

  @override
  bool operator ==(Object other) => other is RoomPalette && listEquals(other.colors, colors) && other.accent == accent;

  @override
  int get hashCode => Object.hash(Object.hashAll(colors), accent);
}

/// One control point of the field, in the field's own px.
@immutable
final class FieldPoint {
  const FieldPoint(this.x, this.y, this.color, this.reach, this.weight);

  final double x;
  final double y;
  final int color;
  final double reach;
  final double weight;

  FieldPoint withColor(int c) => FieldPoint(x, y, c, reach, weight);

  @override
  bool operator ==(Object other) =>
      other is FieldPoint &&
      other.x == x &&
      other.y == y &&
      other.color == color &&
      other.reach == reach &&
      other.weight == weight;

  @override
  int get hashCode => Object.hash(x, y, color, reach, weight);
}

/// The control points for one frame (FocusField.kt focusFieldPoints).
///
/// [coverCenterX]/[coverCenterY]/[coverSize]: the cover as laid out in focus
/// (px); [width]/[height]: the field; [spread] 0 = every point packed into
/// the cover, 1 = opened out; [timeMs] drives the slow paths. With a [room]
/// palette the colours come from it as they are; without one they are the
/// cover's, toned by [toneForRoom].
List<FieldPoint> focusFieldPoints({
  required CoverField field,
  required bool dark,
  required double width,
  required double height,
  required double coverCenterX,
  required double coverCenterY,
  required double coverSize,
  required double spread,
  required double timeMs,
  RoomPalette? room,
}) {
  final s = spread.clamp(0.0, 1.0);
  final open = 0.3 + 0.7 * s;
  // The owner's 「明显」: every path focusDriftSpeed× faster, focusDriftReach× wider.
  final time = timeMs * focusDriftSpeed;
  const reach = focusDriftReach;
  double px(double x) => coverCenterX + (x - coverCenterX) * open;
  double py(double y) => coverCenterY + (y - coverCenterY) * open;
  final palette = field.argbPalette;
  final points = <FieldPoint>[];
  // Around the cover: tight, nearly still; their sampling spots orbit the rim.
  for (var i = 0; i < 6; i++) {
    final a = i / 6 * _tau + 0.4;
    final ua = a + time / 16000;
    final color =
        room?.around(ua / _tau * 4) ?? toneForRoom(field.at(0.5 + 0.4 * math.cos(ua), 0.5 + 0.4 * math.sin(ua)), dark);
    points.add(
      FieldPoint(
        px(coverCenterX + math.cos(a) * coverSize * 0.48),
        py(coverCenterY + math.sin(a) * coverSize * 0.48),
        color,
        coverSize * 0.3 * open,
        1,
      ),
    );
  }
  // Around the room: broad, slow drift; neighbours take different main hues.
  for (var i = 0; i < _roomAnchors.length; i++) {
    final (fx, fy) = _roomAnchors[i];
    final dx = math.sin(time / (5200 + i * 640) + i) * coverSize * 0.09 * reach;
    final dy = math.cos(time / (6100 + i * 530) + i * 1.7) * coverSize * 0.1 * reach;
    final hue = palette[_roomHueOrder[i]];
    final near = field.at(fx.clamp(0.06, 0.94), fy.clamp(0.06, 0.94));
    points.add(
      FieldPoint(
        px(fx * width + dx),
        py(fy * height + dy),
        room?.colors[_roomHueOrder[i]] ?? toneForRoom(_mix(hue, near, 0.3), dark, i.isOdd ? -0.04 : 0.03),
        coverSize * 0.69 * open,
        0.9,
      ),
    );
  }
  // The lower corner holds the second hue.
  points.add(
    FieldPoint(
      px(width * 0.23 + math.sin(time / 8800) * coverSize * 0.12 * reach),
      py(height * 0.81 + math.sin(time / 6100 + 1) * coverSize * 0.28 * math.min(reach, 1.6)),
      room?.colors[1] ?? toneForRoom(palette[1], dark, dark ? 0.06 : 0),
      coverSize * 0.34 * open,
      1.4,
    ),
  );
  // The wanderer: the cover's most vivid colour, roaming (wider, but kept on screen).
  final roam = math.sqrt(reach);
  points.add(
    FieldPoint(
      px(width * (0.5 + math.min(0.46, 0.35 * roam) * math.sin(time / 7300 + 0.6))),
      py(height * (0.52 + math.min(0.44, 0.33 * roam) * math.sin(time / 9700 + 2.2))),
      room?.accent ?? toneForRoom(field.accentArgb, dark, dark ? 0.12 : -0.02),
      coverSize * 0.37 * open,
      2.2,
    ),
  );
  return points;
}

/// [to]'s points with each colour [t] of the way from [from]'s matching
/// point: a song change in focus keeps the geometry and crosses the colours
/// over. With [fromRight] set the new colours sweep in from that side.
List<FieldPoint> crossFieldColors(
  List<FieldPoint> from,
  List<FieldPoint> to,
  double t, {
  bool? fromRight,
  double width = 0,
}) {
  if (from.length != to.length) return to;
  final k = t.clamp(0.0, 1.0);
  return [
    for (var i = 0; i < to.length; i++)
      to[i].withColor(
        _mix(from[i].color, to[i].color, fromRight == null ? k : sweepAt(to[i].x, width, k, fromRight: fromRight)),
      ),
  ];
}

/// A front sweeping across a field [width] wide as [t] goes 0 → 1, entering
/// from the right ([fromRight]) or the left: how far it has passed [x].
double sweepAt(double x, double width, double t, {required bool fromRight, double front = sweepFront}) {
  final xn = width > 0 ? (x / width).clamp(0.0, 1.0) : 0.5;
  final fromEntry = fromRight ? 1 - xn : xn;
  final u = ((t.clamp(0.0, 1.0) * (1 + front) - fromEntry) / front).clamp(0.0, 1.0);
  return u * u * (3 - 2 * u);
}

/// A sweep's soft front, as a share of the field's width.
const double sweepFront = 0.3;

/// A song's colour journey (owner 2026-11-05, 「一首歌一段色程」): the field
/// the room travels to over the song — its main hues turned one step.
CoverField journeyedField(CoverField field) => field.withPalette(journeyOrder(field.argbPalette, candidates: 3));

/// [journeyedField] for a ready-made room palette (its fourth, the deep blend, stays put).
RoomPalette journeyedRoom(RoomPalette room) =>
    RoomPalette(colors: journeyOrder(room.colors, candidates: 2), accent: room.accent);

/// [colors] with the one of its next [candidates] whose hue lies furthest
/// from the first (weighted by saturation) moved to the front.
List<int> journeyOrder(List<int> colors, {required int candidates}) {
  final firstHue = argbToHsl(colors[0]).$1;
  var best = 1;
  var bestDistance = -1.0;
  for (var i = 1; i <= math.min(candidates, colors.length - 1); i++) {
    final (h, s, _) = argbToHsl(colors[i]);
    final d = _hueDistance(firstHue, h) * s;
    if (d > bestDistance) {
      bestDistance = d;
      best = i;
    }
  }
  return [
    colors[best],
    for (var i = 0; i < colors.length; i++)
      if (i != best) colors[i],
  ];
}

double _hueDistance(double a, double b) {
  final d = (a - b).abs() % 360;
  return d > 180 ? 360 - d : d;
}

/// How far the journey has reached a point at [x] of [width] when the song is
/// [progress] (0..1) through: the entry side first, all of the room by the end.
double journeyAt(double x, double width, double progress, {required bool fromRight}) =>
    sweepAt(x, width, progress, fromRight: fromRight);

/// [base]'s points with each colour moved toward [toward]'s matching point by
/// [journeyMaxMix] × [journeyAt] its x.
List<FieldPoint> journeyFieldColors(
  List<FieldPoint> base,
  List<FieldPoint> toward,
  double width,
  double progress, {
  required bool fromRight,
}) {
  if (base.length != toward.length || progress <= 0) return base;
  return [
    for (var i = 0; i < base.length; i++)
      () {
        final k = journeyMaxMix * journeyAt(base[i].x, width, progress, fromRight: fromRight);
        return k <= 0 ? base[i] : base[i].withColor(_mix(base[i].color, toward[i].color, k));
      }(),
  ];
}

/// How much of the second hue the room holds at the song's end.
const double journeyMaxMix = 0.6;

/// Focus's colour journey as drawn: eases toward the playhead's progress
/// (~1.2 s), so a song change or a seek glides back instead of jumping. The
/// entry side is latched while the journey is near its start.
class JourneyState {
  double shown = 0;
  bool fromRight = true;
  double _lastMs = double.nan;

  double step(double target, {required bool enterFromRight, required double nowMs}) {
    final goal = target.clamp(0.0, 1.0);
    final dt = nowMs - _lastMs;
    shown = _lastMs.isNaN || dt < 0 || dt > journeySnapGapMs
        ? goal
        : shown + (goal - shown) * math.min(1.0, dt / journeyEaseMs);
    _lastMs = nowMs;
    if (shown < 0.05) fromRight = enterFromRight;
    return shown;
  }
}

const double journeyEaseMs = 1200;

/// Longer than this between frames (focus left and re-entered): take the playhead as is.
const double journeySnapGapMs = 500;

/// Paints [points] at the vertices of a ([gridWidth] + 1) × ([gridHeight] + 1)
/// lattice over a [width] × [height] field into [out] (ARGB): each vertex the
/// Gaussian-weighted mix of the points, with a faint [base] so far cells stay
/// defined. The Gaussian splits into x and y factors (one exp per point per
/// column and per row). Android renders cell centres into a bitmap and
/// upscales it bilinearly; the lattice drawn as a Gouraud mesh is the same field.
void renderFocusField(
  List<FieldPoint> points,
  int base,
  double width,
  double height,
  int gridWidth,
  int gridHeight,
  Int32List out,
) {
  final cols = gridWidth + 1;
  final rows = gridHeight + 1;
  final cellW = width / gridWidth;
  final cellH = height / gridHeight;
  final n = points.length;
  final ex = Float64List(n * cols);
  final ey = Float64List(n * rows);
  final rs = Float64List(n), gs = Float64List(n), bs = Float64List(n);
  for (var k = 0; k < n; k++) {
    final p = points[k];
    final inv = 1 / (2 * p.reach * p.reach);
    rs[k] = ((p.color >> 16) & 0xFF).toDouble();
    gs[k] = ((p.color >> 8) & 0xFF).toDouble();
    bs[k] = (p.color & 0xFF).toDouble();
    for (var i = 0; i < cols; i++) {
      final dx = i * cellW - p.x;
      ex[k * cols + i] = math.exp(-dx * dx * inv);
    }
    for (var j = 0; j < rows; j++) {
      final dy = j * cellH - p.y;
      ey[k * rows + j] = p.weight * math.exp(-dy * dy * inv);
    }
  }
  final baseR = ((base >> 16) & 0xFF) * _baseWeight;
  final baseG = ((base >> 8) & 0xFF) * _baseWeight;
  final baseB = (base & 0xFF) * _baseWeight;
  for (var j = 0; j < rows; j++) {
    for (var i = 0; i < cols; i++) {
      var r = baseR, g = baseG, b = baseB, w = _baseWeight;
      for (var k = 0; k < n; k++) {
        final wt = ey[k * rows + j] * ex[k * cols + i];
        r += rs[k] * wt;
        g += gs[k] * wt;
        b += bs[k] * wt;
        w += wt;
      }
      out[j * cols + i] = _argb((r / w).toInt(), (g / w).toInt(), (b / w).toInt());
    }
  }
}

/// Keep the hue, lift the chroma, pin the lightness into the band text reads on.
int toneForRoom(int color, bool dark, [double bias = 0]) {
  final (h, sat, light) = argbToHsl(color);
  final s = math.min(1.0, sat * 1.5 + 0.1);
  var l = ((dark ? darkRoomFloor + light * darkRoomSpan : lightRoomFloor + light * lightRoomSpan) + bias).clamp(
    0.0,
    1.0,
  );
  // HSL lightness flatters yellows and greens: in the dark room, step them
  // down until the light text over them still reads.
  var out = hslToArgb(h, s, l);
  while (dark && l > 0.05 && relativeLuminance(out) > _darkRoomMaxLuminance) {
    l -= 0.02;
    out = hslToArgb(h, s, l);
  }
  return out;
}

/// The field's colours, each lifted (light theme) just enough that [text]
/// keeps [minContrast] over it, hue and saturation kept (owner 2026-11-05).
List<FieldPoint> readableUnder(List<FieldPoint> points, int text, {double minContrast = focusLyricContrast}) {
  final minLum = minContrast * (relativeLuminance(text) + 0.05) - 0.05;
  if (minLum <= 0) return points;
  return [for (final p in points) relativeLuminance(p.color) >= minLum ? p : p.withColor(lightenTo(p.color, minLum))];
}

/// [color] with its HSL lightness raised until its luminance reaches [minLum] (or white).
int lightenTo(int color, double minLum) {
  final (h, s, l) = argbToHsl(color);
  var lo = l;
  var hi = 1.0;
  // Luminance rises with HSL lightness at a fixed hue and saturation: bisect.
  for (var i = 0; i < 14; i++) {
    final mid = (lo + hi) / 2;
    if (relativeLuminance(hslToArgb(h, s, mid)) >= minLum) {
      hi = mid;
    } else {
      lo = mid;
    }
  }
  return hslToArgb(h, s, hi);
}

/// Light focus: the current lyric line (the accent colour) over the room.
const double focusLyricContrast = 4;

double relativeLuminance(int color) {
  double channel(int c) {
    final v = c / 255;
    return v <= 0.04045 ? v / 12.92 : math.pow((v + 0.055) / 1.055, 2.4).toDouble();
  }

  return 0.2126 * channel((color >> 16) & 0xFF) +
      0.7152 * channel((color >> 8) & 0xFF) +
      0.0722 * channel(color & 0xFF);
}

/// (hue 0..360, saturation 0..1, lightness 0..1), Android's argbToHsl.
(double, double, double) argbToHsl(int color) {
  final r = ((color >> 16) & 0xFF) / 255;
  final g = ((color >> 8) & 0xFF) / 255;
  final b = (color & 0xFF) / 255;
  final mx = math.max(r, math.max(g, b));
  final mn = math.min(r, math.min(g, b));
  final l = (mx + mn) / 2;
  var h = 0.0;
  var s = 0.0;
  if (mx != mn) {
    final d = mx - mn;
    s = l > 0.5 ? d / (2 - mx - mn) : d / (mx + mn);
    if (mx == r) {
      h = (g - b) / d + (g < b ? 6 : 0);
    } else if (mx == g) {
      h = (b - r) / d + 2;
    } else {
      h = (r - g) / d + 4;
    }
    h *= 60;
  }
  return (h, s, l);
}

int hslToArgb(double h, double s, double l) {
  final a = s * math.min(l, 1 - l);
  double f(int n) {
    final k = (n + h / 30) % 12;
    return l - a * math.max(-1.0, math.min(math.min(k - 3, 9 - k), 1.0));
  }

  return _argb((f(0) * 255).round(), (f(8) * 255).round(), (f(4) * 255).round());
}

int _mix(int a, int b, double t) {
  int ch(int shift) => (((a >> shift) & 0xFF) * (1 - t) + ((b >> shift) & 0xFF) * t).round();
  return _argb(ch(16), ch(8), ch(0));
}

/// Blends two ARGB colours channel by channel (alpha forced opaque).
int mixArgb(int a, int b, double t) => _mix(a, b, t);

int _argb(int r, int g, int b) => 0xFF000000 | (r.clamp(0, 255) << 16) | (g.clamp(0, 255) << 8) | b.clamp(0, 255);

const double _baseWeight = 0.02;

/// Dark room's lightness floor (owner 2026-11-03: "深色全屏播放页面有点亮").
const double darkRoomFloor = 0.10;
const double darkRoomSpan = 0.34;

/// Light room's lightness band: the pale 0.70–0.94 the dark text reads on.
const double lightRoomFloor = 0.70;
const double lightRoomSpan = 0.24;

/// Focus motion (owner 2026-11-05, 「明显」): paths this many times faster…
const double focusDriftSpeed = 3;

/// …and this many times wider.
const double focusDriftReach = 2.2;
const double _darkRoomMaxLuminance = 0.065;
const double _tau = 2 * math.pi;

/// Room points as fractions of the field: corners, edge midpoints, a touch outside.
const List<(double, double)> _roomAnchors = [
  (0, 0),
  (0.5, -0.04),
  (1, 0),
  (1.04, 0.42),
  (1, 0.98),
  (0.5, 1.04),
  (0, 0.98),
  (-0.04, 0.42),
];

/// Which main hue each room point takes: neighbours differ.
const List<int> _roomHueOrder = [0, 1, 0, 2, 3, 2, 1, 3];
