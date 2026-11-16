import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/painting.dart';

import '../playback/now_playing_state.dart' show RepeatMode;
import '../playback/play_mode.dart';
import 'focus_field.dart' show argbToHsl;

// Action lights (ActionGlow.kt, owner 2026-11-04, prototype v1 approved):
// every Now Playing action that changes something lights the background from
// the control that was pressed, in the cover's colours with Yoin's fine dots,
// for 0.6–1.7 s. Adding something sends the light out (favourite, a rating, a
// note); taking it away draws a dimmer, shorter light back into the control.
// Waits use a slow sweep in the cover's colours. Opening a sheet, switching a
// tab or leaving the page lights nothing.
//
// Controls fire with NowPlayingTransportSignal.glow; the background
// (NowPlayingBackground) runs and draws them behind the content. Lengths are
// logical px (= dp).

enum ActionGlowKind {
  /// The heart filled: two beats, then a ring of dots flies out.
  favoriteOn(1400),

  /// The heart emptied: a dim bloom drawn back into it.
  favoriteOff(700),

  /// Added somewhere (to a playlist, the library): dots pour in, then a bloom.
  gatherInto(1300),

  /// A rating landed ([ActionGlow.value] 0.1–10): a band leaves the bar at that height; full marks spray dots.
  ratingCommit(1300),

  /// The rating was cleared: the light drains down the bar.
  ratingClear(800),

  // Play mode (owner 2026-11-06): every turn of the key gets the same quiet
  // light — one soft glow in the cover's colour swells from the key and
  // settles; turning something off draws a dimmer glow back in. A tap while
  // the last one is still lit takes it over.

  /// Shuffle on: the key's quiet glow.
  shuffleOn(_playModeOnMs),

  /// Shuffle off: a dimmer glow drawn back into the key.
  shuffleOff(_playModeOffMs),

  /// Repeat all: the key's quiet glow.
  repeatAll(_playModeOnMs),

  /// Repeat one: the key's quiet glow.
  repeatOne(_playModeOnMs),

  /// Repeat off: a dimmer glow drawn back into the key.
  repeatOff(_playModeOffMs),

  /// The wave bar let go: a flash where the playhead landed.
  seekRelease(650),

  /// A note saved: ink rises from the anchor to the line at [ActionGlow.targetY], which glows.
  noteSave(1700),

  /// A note deleted: its line's light shrinks back.
  noteDelete(700),

  /// New lyrics or a translation arrived: each line lights once, top to bottom.
  lyricsReveal(1300),

  /// Playback moved to another device: the light leaves downwards, then a ring comes back from the bottom.
  deviceHandoff(2100),

  /// Keep screen on: the whole room brightens for a moment.
  roomLift(900),

  /// The cover's menu opened: a ring closes in on the cover.
  coverHold(700),

  /// Lyrics copied or shared: a band at [ActionGlow.targetY].
  lineGlow(1400);

  const ActionGlowKind(this.durationMs);

  final int durationMs;

  /// A play-mode light: a newer one takes over from it instead of piling on.
  bool get isPlayMode =>
      this == shuffleOn || this == shuffleOff || this == repeatAll || this == repeatOne || this == repeatOff;

  /// A full-marks rating sprays dots and runs longer.
  int durationFor(double value) => this == ratingCommit && value >= fullRating ? 1700 : durationMs;

  /// Lights that need no control to start from.
  bool get anchorless => this == roomLift || this == lyricsReveal;
}

/// Never retired.
const int _notRetired = 1 << 62;

/// One fired light. [anchorRoot] is the control (global px); [value] the
/// rating for [ActionGlowKind.ratingCommit]; [targetY] a lyric line's height
/// (global px) where the kind lands on one; [areaRoot] the lyrics' bounds.
final class ActionGlow {
  const ActionGlow({
    required this.kind,
    required this.anchorRoot,
    required this.bornUptimeMs,
    this.value = 0,
    this.targetY = double.nan,
    this.areaRoot,
    this.retiredUptimeMs = _notRetired,
  });

  final ActionGlowKind kind;
  final Rect anchorRoot;
  final int bornUptimeMs;
  final double value;
  final double targetY;
  final Rect? areaRoot;

  /// When a newer light of the same family took over; this one fades out over [glowHandOverMs].
  final int retiredUptimeMs;

  bool get retired => retiredUptimeMs != _notRetired;

  int get durationMs => kind.durationFor(value);

  /// Done drawing at [nowMs]: its time is up, or it has handed over.
  bool isDone(int nowMs) => nowMs - bornUptimeMs > durationMs || nowMs - retiredUptimeMs > glowHandOverMs;

  ActionGlow retire(int atMs) => ActionGlow(
    kind: kind,
    anchorRoot: anchorRoot,
    bornUptimeMs: bornUptimeMs,
    value: value,
    targetY: targetY,
    areaRoot: areaRoot,
    retiredUptimeMs: atMs,
  );
}

/// Adds [incoming] to the running [glows]: a play-mode light retires the
/// play-mode lights still running, so quick taps round the cycle read as one
/// light moving on rather than a pile.
void addHandingOver(List<ActionGlow> glows, ActionGlow incoming) {
  if (incoming.kind.isPlayMode) {
    for (var i = 0; i < glows.length; i++) {
      final g = glows[i];
      if (g.kind.isPlayMode && !g.retired) glows[i] = g.retire(incoming.bornUptimeMs);
    }
  }
  glows.add(incoming);
}

enum GlowHoldKind { rating, scrub }

/// A finger held on a control: the rating bar's column, the wave bar's scrub.
final class GlowHold {
  const GlowHold({required this.kind, required this.pointRoot, required this.barRoot, this.value = 0});
  final GlowHoldKind kind;

  /// The finger's spot (global px): the value's point on the bar.
  final Offset pointRoot;

  /// The control's bounds (global px).
  final Rect barRoot;

  /// The rating under the finger (0–10), for the column's strength.
  final double value;
}

/// Controls whose bounds a light starts from when it isn't fired by the control itself.
enum GlowAnchor { devices, write, cover }

/// Which light a play-mode change gets, or null when nothing changed.
ActionGlowKind? playModeGlowKind(PlayMode from, PlayMode to) {
  if (from.shuffled != to.shuffled) return to.shuffled ? ActionGlowKind.shuffleOn : ActionGlowKind.shuffleOff;
  if (from.repeat != to.repeat) {
    return switch (to.repeat) {
      RepeatMode.one => ActionGlowKind.repeatOne,
      RepeatMode.off => ActionGlowKind.repeatOff,
      RepeatMode.all => ActionGlowKind.repeatAll,
    };
  }
  return null;
}

/// Which light a rating commit gets: a cleared rating (null or 0) drains.
ActionGlowKind ratingGlowKind(double? value) =>
    value == null || value <= 0 ? ActionGlowKind.ratingClear : ActionGlowKind.ratingCommit;

/// Where [value] (0–10) sits on a rating bar laid out at [bar]: from the
/// bottom up when vertical, from the start along when horizontal.
Offset ratingPoint(Rect bar, double value, {required bool vertical, required bool rtl}) {
  final f = (value / fullRatingScale).clamp(0.0, 1.0);
  if (vertical) return Offset(bar.center.dx, bar.bottom - bar.height * f);
  final x = rtl ? bar.right - bar.width * f : bar.left + bar.width * f;
  return Offset(x, bar.center.dy);
}

/// The palette a light draws in: the cover's primary / secondary / tertiary, made glowy.
final class GlowPalette {
  GlowPalette(Color primary, Color secondary, Color tertiary, {required this.dark})
    : p = _tint(primary, 1, dark),
      s = _tint(secondary, 0.5, dark),
      t = _tint(tertiary, 1, dark);

  final bool dark;
  final Color p;
  final Color s;
  final Color t;

  /// The peak alpha a burst uses, as the play / pause pulse.
  double get cap => dark ? 0.42 : 0.40;

  static Color _tint(Color color, double satScale, bool dark) {
    final (h, _, _) = argbToHsl(color.toARGB32());
    return HSLColor.fromAHSL(1, ((h % 360) + 360) % 360, (dark ? 0.75 : 0.85) * satScale, 0.62).toColor();
  }

  /// Dots read a touch darker on a light page, lighter on a dark one.
  Color dotColor(Color c, double alpha) {
    final (h, s, l) = argbToHsl(c.toARGB32());
    final light = dark ? math.min(0.8, l + 0.1) : math.max(0.4, l - 0.2);
    return HSLColor.fromAHSL(alpha.clamp(0.0, 1.0), h, s, light).toColor();
  }

  @override
  bool operator ==(Object other) =>
      other is GlowPalette && other.dark == dark && other.p == p && other.s == s && other.t == t;

  @override
  int get hashCode => Object.hash(dark, p, s, t);
}

/// Draws [glow] [tMs] into its run on a [size] canvas whose global origin is
/// [origin].
void paintActionGlow(
  Canvas canvas,
  Size size,
  ActionGlow glow,
  double tMs,
  Offset origin,
  GlowPalette pal,
  BlendMode blend,
) {
  final d = glow.durationMs.toDouble();
  if (tMs < 0 || tMs > d) return;
  final u = tMs / d;
  // A light that handed over fades out quickly under the one that took over.
  final since = glow.bornUptimeMs + tMs - glow.retiredUptimeMs;
  final k = pal.cap * (since > 0 ? 1 - (since / glowHandOverMs).clamp(0.0, 1.0) : 1);
  final a = glow.anchorRoot.shift(-origin);
  final c = a.center;
  final w = size.width;
  final h = size.height;
  // Leans toward the middle of the screen, away from the edge the control sits on.
  final inward = c.dx > w / 2 ? -1.0 : 1.0;
  switch (glow.kind) {
    case ActionGlowKind.favoriteOn:
      final beat = math.max(_bell(tMs / 260), 0.75 * _bell((tMs - 300) / 300));
      _bloom(canvas, c, 70 + 50 * u, pal.t, k * 1.5 * beat + k * 0.5 * _bell(u), blend);
      _bloom(
        canvas,
        Offset(c.dx + inward * 40, c.dy - 30),
        170 * _easeOut(math.min(1, tMs / 700)),
        pal.p,
        k * 0.6 * _bell(u),
        blend,
      );
      if (tMs > 120) {
        final r = 30 + 110 * _easeOut(math.min(1, (tMs - 120) / 900));
        for (var i = 0; i < 14; i++) {
          final ang = i / 14 * _tau + 0.2;
          _dot(
            canvas,
            Offset(c.dx + math.cos(ang) * r, c.dy + math.sin(ang) * r * 0.9),
            2.2 * (1 - u) + 0.4,
            pal.dotColor(i % 3 == 0 ? pal.p : pal.t, (1 - u) * 0.9),
          );
        }
      }
    case ActionGlowKind.favoriteOff:
      _bloom(canvas, c, 110 * (1 - _easeOut(u)) + 10, pal.s, k * 0.8 * _bell(u), blend);
    case ActionGlowKind.gatherInto:
      final g = math.min(1.0, tMs / 700);
      if (g < 1) {
        final r = 140 * (1 - _easeOut(g)) + 6;
        for (var i = 0; i < 18; i++) {
          final ang = i / 18 * _tau;
          _dot(
            canvas,
            Offset(c.dx + math.cos(ang) * r, c.dy + math.sin(ang) * r),
            1.8,
            pal.dotColor(i.isEven ? pal.p : pal.t, 0.85),
          );
        }
      }
      if (tMs > 650) {
        final v = (tMs - 650) / 650;
        _bloom(canvas, c, 60 + 60 * _easeOut(v), pal.p, k * 1.4 * _bell(v), blend);
      }
    case ActionGlowKind.ratingCommit:
      final str = 0.35 + 0.65 * (glow.value / fullRatingScale).clamp(0.0, 1.0);
      // A band leaves the bar at the rating's height and crosses the room.
      _ellipse(
        canvas,
        Offset(c.dx + inward * 160 * _easeOut(u), c.dy),
        140 + 260 * _easeOut(u),
        60 + 40 * u,
        pal.p,
        k * 1.6 * str * _bell(u),
        blend,
      );
      _bloom(canvas, c, 50 + 40 * u, pal.t, k * 1.2 * str * _bell(u * 1.4), blend);
      if (glow.value >= fullRating) {
        for (var i = 0; i < 26; i++) {
          final sx = c.dx + inward * (i / 26) * 300 * _easeOut(u);
          final sy = c.dy + math.sin(i * 1.7 + tMs * 0.006) * 18 * u;
          _dot(canvas, Offset(sx, sy), 1.8 * (1 - u) + 0.3, pal.dotColor(i.isEven ? pal.p : pal.t, 1 - u));
        }
      }
    case ActionGlowKind.ratingClear:
      final bottom = a.height > a.width ? a.bottom : c.dy;
      final y = c.dy + (bottom - c.dy) * _easeOut(u);
      _ellipse(canvas, Offset(c.dx, y), 70, 40, pal.s, k * 0.9 * (1 - u), blend);
    case ActionGlowKind.shuffleOn || ActionGlowKind.repeatAll || ActionGlowKind.repeatOne:
      _bloom(canvas, c, 56 + 34 * _easeOut(u), pal.p, k * 0.75 * _swell(u), blend);
    case ActionGlowKind.shuffleOff || ActionGlowKind.repeatOff:
      _bloom(canvas, c, 72 - 28 * _easeOut(u), pal.s, k * 0.45 * _swell(u), blend);
    case ActionGlowKind.seekRelease:
      _bloom(canvas, c, 40 + 50 * u, pal.p, k * 1.3 * _bell(u), blend);
      _ellipse(canvas, c, 120 + 120 * u, 30, pal.p, k * 0.6 * _bell(u), blend);
    case ActionGlowKind.noteSave:
      final ly = glow.targetY.isNaN ? h * 0.55 : glow.targetY - origin.dy;
      final q = _easeOut(math.min(1, tMs / 650));
      final toX = w * 0.2;
      if (tMs < 800) {
        final fade = tMs < 650 ? 1.0 : 1 - (tMs - 650) / 150;
        _bloom(canvas, Offset(c.dx + (toX - c.dx) * q, c.dy + (ly - c.dy) * q), 50, pal.t, k * 1.4 * fade, blend);
      }
      if (tMs > 500) {
        final v = (tMs - 500) / 1200;
        _ellipse(canvas, Offset(w / 2, ly), w * 0.9, 36, pal.t, k * 1.3 * _bell(v), blend);
        _dotRow(canvas, ly + 18, w * 0.06, w * 0.94, pal.dotColor(pal.t, 0.7 * _bell(v)), tMs);
      }
    case ActionGlowKind.noteDelete:
      final ly = glow.targetY.isNaN ? h * 0.55 : glow.targetY - origin.dy;
      _ellipse(
        canvas,
        Offset(w * 0.17 + w * 0.33 * u, ly),
        w * 0.55 * (1 - u) + 20,
        28,
        pal.s,
        k * 0.8 * (1 - u),
        blend,
      );
    case ActionGlowKind.lyricsReveal:
      final area = glow.areaRoot?.shift(-origin) ?? Rect.fromLTRB(0, h * 0.45, w, h * 0.75);
      const step = 34.0;
      var i = 0;
      var y = area.top + step * 0.6;
      while (y < area.bottom && i < 12) {
        final v = (tMs - i * 90) / 700;
        if (v >= 0 && v <= 1) {
          _ellipse(canvas, Offset(area.center.dx, y), area.width * 0.95, 24, pal.p, k * 0.9 * _bell(v), blend);
        }
        y += step;
        i++;
      }
    case ActionGlowKind.deviceHandoff:
      if (tMs < 1100) {
        final v = tMs / 1100;
        final y = c.dy + 140 * _easeOut(v);
        _bloom(canvas, Offset(c.dx, y), 90 - 30 * v, pal.p, k * 1.4 * _bell(v), blend);
        _dotRow(canvas, y + 10, c.dx - 50, c.dx + 50, pal.dotColor(pal.p, 0.7 * (1 - v)), tMs);
      }
      if (tMs > 900) {
        final v = (tMs - 900) / 1200;
        _ring(canvas, Offset(w / 2, h + 20), 120 + 520 * _easeOut(v), pal.p, k * 1.1 * _bell(v), blend, width: 0.1);
      }
    case ActionGlowKind.roomLift:
      final color = pal.dark
          ? pal.p.withValues(alpha: 0.14 * _bell(u))
          : const Color(0xFFFFFFFF).withValues(alpha: 0.5 * _bell(u));
      canvas.drawRect(
        Offset.zero & size,
        Paint()
          ..color = color
          ..blendMode = blend,
      );
    case ActionGlowKind.coverHold:
      _ring(
        canvas,
        c,
        math.max(a.width, a.height) * 0.72 - 40 * _easeOut(u),
        pal.p,
        k * 1.1 * _bell(u),
        blend,
        width: 0.1,
      );
    case ActionGlowKind.lineGlow:
      final ly = glow.targetY.isNaN ? c.dy : glow.targetY - origin.dy;
      _ellipse(canvas, Offset(w / 2, ly), w, 40, pal.p, k * 1.1 * _bell(u), blend);
  }
}

/// A held finger's light, at [strength] (0–1, its fade in and out).
void paintGlowHold(Canvas canvas, GlowHold hold, Offset origin, double strength, GlowPalette pal, BlendMode blend) {
  if (strength <= 0.001) return;
  final k = pal.cap * strength;
  final p = hold.pointRoot - origin;
  switch (hold.kind) {
    case GlowHoldKind.rating:
      // A column of light up the bar to the finger, brighter the higher the score.
      final str = 0.3 + 0.7 * (hold.value / fullRatingScale).clamp(0.0, 1.0);
      final bar = hold.barRoot.shift(-origin);
      _bloom(canvas, p, 70, pal.p, k * 1.3 * str, blend);
      if (bar.height > bar.width) {
        _ellipse(
          canvas,
          Offset(p.dx, (p.dy + bar.bottom) / 2),
          45,
          (bar.bottom - p.dy) / 2 + 30,
          pal.p,
          k * 0.7 * str,
          blend,
        );
      }
    case GlowHoldKind.scrub:
      _bloom(canvas, p, 46, pal.p, k * 1.1, blend);
  }
}

/// A slow sweep in the cover's colours across the lyrics while something is fetched for them.
void paintGlowWait(
  Canvas canvas,
  Size size,
  Rect? areaRoot,
  Offset origin,
  double tMs,
  double strength,
  GlowPalette pal,
  BlendMode blend,
) {
  if (strength <= 0.001) return;
  final w = size.width;
  final shifted = areaRoot?.shift(-origin);
  final area = shifted != null && !shifted.isEmpty
      ? shifted
      : Rect.fromLTRB(0, size.height * 0.45, w, size.height * 0.75);
  final y = area.top + 40;
  final x = area.left - 80 + ((tMs / 1400) % 1) * (area.width + 160);
  _ellipse(canvas, Offset(x, y), 160, 70, pal.p, pal.cap * 0.9 * strength, blend);
  _dotRow(
    canvas,
    y + 60,
    math.max(area.left, x - 60),
    math.min(area.right, x + 60),
    pal.dotColor(pal.p, 0.55 * strength),
    tMs,
  );
}

void _bloom(Canvas canvas, Offset center, double radius, Color color, double alpha, BlendMode blend) {
  if (radius <= 1 || alpha <= 0.002) return;
  final a = alpha.clamp(0.0, 1.0);
  canvas.drawCircle(
    center,
    radius,
    Paint()
      ..blendMode = blend
      ..shader = ui.Gradient.radial(
        center,
        radius,
        [color.withValues(alpha: a), color.withValues(alpha: a * 0.45), color.withValues(alpha: 0)],
        const [0, 0.5, 1],
      ),
  );
}

/// A soft ellipse: a bloom stretched to [rx] × [ry].
void _ellipse(Canvas canvas, Offset center, double rx, double ry, Color color, double alpha, BlendMode blend) {
  if (rx <= 1 || ry <= 1 || alpha <= 0.002) return;
  final a = alpha.clamp(0.0, 1.0);
  canvas
    ..save()
    ..translate(center.dx, center.dy)
    ..scale(rx / ry, 1)
    ..drawCircle(
      Offset.zero,
      ry,
      Paint()
        ..blendMode = blend
        ..shader = ui.Gradient.radial(
          Offset.zero,
          ry,
          [color.withValues(alpha: a), color.withValues(alpha: a * 0.35), color.withValues(alpha: 0)],
          const [0, 0.55, 1],
        ),
    )
    ..restore();
}

void _ring(
  Canvas canvas,
  Offset center,
  double radius,
  Color color,
  double alpha,
  BlendMode blend, {
  double width = 0.16,
}) {
  if (radius <= 1 || alpha <= 0.002) return;
  final a = alpha.clamp(0.0, 1.0);
  canvas.drawCircle(
    center,
    radius,
    Paint()
      ..blendMode = blend
      ..shader = ui.Gradient.radial(
        center,
        radius,
        [const Color(0x00000000), color.withValues(alpha: 0), color.withValues(alpha: a), color.withValues(alpha: 0)],
        [0, math.max(0.0, 1 - width * 2), 1 - width, 1],
      ),
  );
}

void _dot(Canvas canvas, Offset center, double radius, Color color) {
  if (radius <= 0.15 || color.a <= 0.01) return;
  canvas.drawCircle(center, radius, Paint()..color = color);
}

/// A row of fine dots whose sizes ripple over time, Yoin's halftone.
void _dotRow(Canvas canvas, double y, double x0, double x1, Color color, double tMs) {
  if (color.a <= 0.01) return;
  var x = x0;
  while (x <= x1) {
    final n = 0.5 + 0.5 * math.sin(x * 0.05 + tMs * 0.004);
    _dot(canvas, Offset(x, y), 1.3 * (0.4 + 0.6 * n), color);
    x += 6;
  }
}

double _bell(double t) => math.sin(math.pi * t.clamp(0.0, 1.0));

/// Up quickly, then a long settle: a light that arrives and lets go without a bounce.
double _swell(double t) {
  final u = t.clamp(0.0, 1.0);
  if (u < _swellPeak) return math.sin(math.pi / 2 * u / _swellPeak);
  final v = (u - _swellPeak) / (1 - _swellPeak);
  return 1 - v * v * (3 - 2 * v);
}

double _easeOut(double t) => 1 - math.pow(1 - t.clamp(0.0, 1.0), 3).toDouble();

const double _tau = 2 * math.pi;
const double _swellPeak = 0.28;
const int _playModeOnMs = 800;
const int _playModeOffMs = 550;

/// How long a light that was taken over takes to fade out.
const int glowHandOverMs = 160;
const double fullRatingScale = 10;

/// A rating this high counts as full marks for the spray.
const double fullRating = 9.5;
