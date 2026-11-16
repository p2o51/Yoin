/// 唱片刻纹 · Groove — the Memory emblem (Android GrooveEmblem.kt; owner
/// 2026-10-03, v4 2026-10-04: flat Material, nothing glossy). One ring per
/// track, outer = track 1; only rated tracks are cut (solid, flat palette
/// colour); unrated tracks stay a dotted pre-cut lattice. The centre label
/// carries the score: album = filled Cookie12Sided, average = outlined circle
/// on a paler disc, unrated = a dashed Cookie12Sided "empty mould" in neutral
/// tokens. It is an exhibit: not clickable.
///
/// Tilt is read as a flat colour shift of the outer ring lines ([tilt], from
/// [GrooveTiltController]); at rest nothing shows. The unrated mould is
/// static — Android dropped its ripple loop (owner, 2026-10-05).
library;

import 'dart:math' as math;
import 'dart:ui' show PointMode;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import '../../theme/theme.dart';
import '../../ui/shapes/cookie_unit.dart';
import '../palette/memory_palette.dart';
import 'groove_award.dart';
import 'groove_colors.dart';
import 'groove_geometry.dart';
import 'groove_tilt.dart';

/// The groove emblem of one memory, drawn natively at [size] (dp).
///
/// [ratedTracks] is in album order (track 1 first). [scoreText] is the label's
/// one-decimal score as every Memories surface shows it (`MemoryScores.text`);
/// the award tier follows it. [award] drives the award (null: the resting,
/// finished emblem). [captionAlpha] fades the "Avg." caption on its own (the
/// card ⇄ diary morph); it is read in paint only. [tilt] is the device tilt
/// in −1…1 screen axes, also read in paint only.
class GrooveEmblem extends StatefulWidget {
  const GrooveEmblem({
    super.key,
    required this.size,
    required this.ratedTracks,
    required this.kind,
    this.scoreText,
    required this.palette,
    this.surface = GrooveSurface.cover,
    this.award,
    this.captionAlpha,
    this.tilt,
  });

  final double size;
  final List<bool> ratedTracks;
  final GrooveScoreKind kind;
  final String? scoreText;
  final MemoryPalette palette;
  final GrooveSurface surface;
  final GrooveAwardController? award;
  final ValueListenable<double>? captionAlpha;
  final ValueListenable<Offset>? tilt;

  /// The award tier of what this emblem shows (0–4).
  int get tier => grooveTierOf(kind, scoreText);

  @override
  State<GrooveEmblem> createState() => _GrooveEmblemState();
}

class _GrooveEmblemState extends State<GrooveEmblem> with TickerProviderStateMixin {
  _GrooveArt? _art;
  Object? _artKey;

  GrooveAwardSpec get _spec => GrooveAwardSpec(
    tier: widget.tier,
    kind: widget.kind,
    trackRated: List.unmodifiable(widget.ratedTracks),
    size: widget.size,
    surface: widget.surface,
  );

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    widget.award?.attach(_spec, this, reducedMotion: MediaQuery.disableAnimationsOf(context));
  }

  @override
  void didUpdateWidget(GrooveEmblem oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.award, widget.award)) oldWidget.award?.detach();
    widget.award?.attach(_spec, this, reducedMotion: MediaQuery.disableAnimationsOf(context));
  }

  @override
  void dispose() {
    widget.award?.detach();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final neutrals = GrooveNeutrals.from(scheme);
    final strings = _GrooveStrings.of(context);
    final key = (
      Object.hashAll(widget.ratedTracks),
      widget.ratedTracks.length,
      widget.kind,
      widget.scoreText,
      widget.size,
      widget.surface,
      widget.palette,
      neutrals,
      strings.unratedWord,
    );
    if (key != _artKey) {
      _artKey = key;
      _art = _GrooveArt(
        geometry: grooveGeometry(widget.ratedTracks, widget.size, widget.kind, widget.surface),
        colors: grooveColors(
          widget.palette,
          widget.kind,
          dark: neutrals.dark,
          surface: widget.surface,
          neutrals: neutrals,
        ),
        scoreText: widget.scoreText ?? '',
        unratedWord: strings.unratedWord,
      );
    }
    final award = widget.award;
    final caption = widget.captionAlpha;
    final tilt = widget.tilt;
    final repaint = award == null && caption == null && tilt == null
        ? null
        : Listenable.merge([award?.repaint, caption, tilt]);
    return Semantics(
      image: true,
      label: strings.description(widget.kind, widget.scoreText),
      child: SizedBox.square(
        dimension: widget.size,
        child: CustomPaint(painter: _GroovePainter(_art!, award, caption, tilt, repaint)),
      ),
    );
  }
}

/// Copied from Android res/values{,-zh-rCN}/strings_memories.xml.
final class _GrooveStrings {
  const _GrooveStrings(this._zh);
  final bool _zh;

  static _GrooveStrings of(BuildContext context) =>
      _GrooveStrings(Localizations.maybeLocaleOf(context)?.languageCode == 'zh');

  String get unratedWord => _zh ? '未评分' : 'Unrated'; // mem_emblem_unrated

  String description(GrooveScoreKind kind, String? score) => switch (kind) {
    _ when score == null || kind == GrooveScoreKind.none => _zh ? '未评分' : 'Not rated', // mem_cd_emblem_unrated
    GrooveScoreKind.album => _zh ? '专辑评分 $score' : 'Album rating $score', // mem_cd_emblem_album
    _ => _zh ? '曲目均分 $score' : 'Track average $score', // mem_cd_emblem_average
  };
}

/// Everything that depends only on size and model, built once (paths, dot lattices, type), in dp.
final class _GrooveArt {
  _GrooveArt({required this.geometry, required this.colors, required String scoreText, required String unratedWord}) {
    final g = geometry;
    final c = Offset(g.radius, g.radius);
    center = c;
    dots = [
      for (final ring in g.rings)
        () {
          final n = g.dotCount(ring);
          final phase = ring.index.isOdd ? 0.5 : 0.0;
          return [
            for (var i = 0; i < n; i++)
              c +
                  Offset(math.cos((i + phase) * 2 * math.pi / n), math.sin((i + phase) * 2 * math.pi / n)) *
                      ring.radius,
          ];
        }(),
    ];
    final l = g.labelRadius;
    final kind = g.kind;
    labelPath = kind == GrooveScoreKind.album ? _cookie(c, l) : null;
    labelRingPath = kind == GrooveScoreKind.album && g.big ? _cookie(c, l * 0.94) : null;
    if (kind == GrooveScoreKind.none) {
      final w = g.unratedLabelStroke;
      final path = _cookie(c, l - w / 2);
      mouldPath = path;
      // 24 dashes around the mould (two per lobe), the first starting on the top lobe's peak (the path's start)
      final dashes = Path();
      for (final metric in path.computeMetrics()) {
        final per = metric.length / 24;
        for (var i = 0; i < 24; i++) {
          dashes.addPath(metric.extractPath(per * i, per * i + per * 0.55), Offset.zero);
        }
      }
      mouldDashes = dashes;
    } else {
      mouldPath = null;
      mouldDashes = null;
    }
    if (kind != GrooveScoreKind.none) {
      final f = g.scoreFontSize;
      score = _text(
        scoreText,
        _axes(
          gsf(f, weight: kind == GrooveScoreKind.album ? 690 : 640, height: 1, letterSpacing: 0, round: 50),
          width: scoreText.length >= 4 ? 76 : 92,
          color: colors.ink,
        ),
      );
      // only a track average says what it is ("Avg."); an album score stands alone (owner, 2026-10-05)
      caption = g.showsCaption
          ? _text(
              'Avg.',
              _axes(
                gsf(g.captionFontSize, weight: 600, height: 1, letterSpacing: 0),
                width: 100,
                color: colors.caption,
              ),
            )
          : null;
      unrated = null;
    } else {
      score = null;
      caption = null;
      unrated = g.showsUnratedWord
          ? _text(
              unratedWord,
              _axes(
                gsf(g.unratedFontSize, weight: 600, height: 1, letterSpacing: 0),
                width: g.unratedWidthAxis,
                color: colors.ink,
              ),
            )
          : null;
    }
  }

  final GrooveGeometry geometry;
  final GrooveColors colors;

  /// The tilt tones of the cut grooves (none when unrated) and of the rim / hairline.
  late final GrooveTint? cutTint = geometry.kind == GrooveScoreKind.none ? null : colors.tint(colors.cut);
  late final GrooveTint rimTint = colors.tint(geometry.kind == GrooveScoreKind.none ? colors.hairlineStroke : colors.rim);
  late final Offset center;
  late final List<List<Offset>> dots;
  late final Path? labelPath;
  late final Path? labelRingPath;
  late final Path? mouldPath;
  late final Path? mouldDashes;
  late final TextPainter? score;
  late final TextPainter? caption;
  late final TextPainter? unrated;

  static TextStyle _axes(TextStyle style, {required double width, required Color color}) => style.copyWith(
    color: color,
    leadingDistribution: TextLeadingDistribution.even,
    fontVariations: [...?style.fontVariations, FontVariation('wdth', width)],
  );

  static TextPainter _text(String text, TextStyle style) => TextPainter(
    text: TextSpan(text: text, style: style),
    textDirection: TextDirection.ltr,
    textScaler: TextScaler.noScaling,
    maxLines: 1,
  )..layout();
}

/// Cookie12Sided with outer (lobe) radius [radius] around [center] (GrooveEmblem.kt cookie()).
Path _cookie(Offset center, double radius) => CookieUnit.at(center, radius);

// ---------------------------------------------------------------------------

class _GroovePainter extends CustomPainter {
  _GroovePainter(this.art, this.award, this.captionAlpha, this.tilt, Listenable? repaint) : super(repaint: repaint);

  final _GrooveArt art;
  final GrooveAwardController? award;
  final ValueListenable<double>? captionAlpha;
  final ValueListenable<Offset>? tilt;

  /// Tilt colour: the outer ring lines are split into 15° flat segments.
  static const int _tintSegments = 24;
  static const double _tintStep = 360 / _tintSegments;

  /// Weights of the tilt colour: the rim answers most, then the two outer cut rings.
  static const double _rimTintWeight = 1;
  static const double _hairlineTintWeight = 0.6;
  static const List<double> _ringTintWeights = [0.85, 0.55];
  static const List<double> _shifts = [-360, 0, 360];

  static double _deg(double d) => d * math.pi / 180;

  Paint _fill(Color c, [double alpha = 1]) => Paint()
    ..isAntiAlias = true
    ..color = c.withValues(alpha: c.a * alpha.clamp(0.0, 1.0));

  Paint _stroke(Color c, double width, {double alpha = 1, StrokeCap cap = StrokeCap.butt}) => _fill(c, alpha)
    ..style = PaintingStyle.stroke
    ..strokeWidth = width
    ..strokeCap = cap;

  void _arc(Canvas canvas, Color color, double radius, double start, double sweep, double width, [double alpha = 1]) {
    if (sweep <= 0 || alpha <= 0) return;
    canvas.drawArc(
      Rect.fromCircle(center: art.center, radius: radius),
      _deg(start),
      _deg(sweep),
      false,
      _stroke(color, width, alpha: alpha),
    );
  }

  void _rotate(Canvas canvas, double degrees) {
    if (degrees == 0) return;
    final c = art.center;
    canvas
      ..translate(c.dx, c.dy)
      ..rotate(_deg(degrees))
      ..translate(-c.dx, -c.dy);
  }

  void _scale(Canvas canvas, double s) {
    if (s == 1) return;
    final c = art.center;
    canvas
      ..translate(c.dx, c.dy)
      ..scale(s)
      ..translate(-c.dx, -c.dy);
  }

  @override
  void paint(Canvas canvas, Size size) {
    final g = art.geometry;
    final c = art.colors;
    final a = award;
    final k = size.width / g.size;
    final emblemAlpha = (a?.emblemAlpha ?? 1).clamp(0.0, 1.0);
    if (emblemAlpha <= 0) return;
    canvas.save();
    canvas.scale(k);
    _scale(canvas, a?.emblemScale ?? 1);
    if (emblemAlpha < 1) {
      canvas.saveLayer(Offset.zero & Size.square(g.size), Paint()..color = Color.fromRGBO(0, 0, 0, emblemAlpha));
    }
    _paintEmblem(canvas, g, c, a);
    if (emblemAlpha < 1) canvas.restore();
    canvas.restore();
  }

  void _paintEmblem(Canvas canvas, GrooveGeometry g, GrooveColors c, GrooveAwardController? a) {
    final center = art.center;
    final disc = a?.discDegrees ?? 0;
    final unrated = g.kind == GrooveScoreKind.none;
    final t = tilt?.value ?? Offset.zero;
    final tv = grooveTiltVars(t.dx, t.dy);
    final tm = tv.tm * (a?.tintAlpha ?? 1);
    final ta = tv.ta;

    // 1 · ground: opaque on a cover so the silhouette separates from any artwork (flat, no shadow)
    canvas.drawCircle(center, g.rimRadius, _fill(c.ground));

    // 2 · disc space (turns in the award)
    canvas.save();
    _rotate(canvas, disc);
    if (!unrated) {
      for (final ring in g.rings) {
        for (final run in ring.runs) {
          _arc(canvas, c.under, ring.radius, run.startDegrees, run.sweepDegrees, g.underWidth);
        }
      }
    }
    final dotPaint = _stroke(unrated && g.tiny ? c.tinyDot : c.dot, g.dotDiameter, cap: StrokeCap.round);
    for (final ring in art.dots) {
      canvas.drawPoints(PointMode.points, ring, dotPaint);
    }
    if (!unrated) {
      for (final ring in g.rings) {
        if (ring.runs.isEmpty) continue;
        final order = g.ringOrder(ring.index);
        final progress = a?.cutProgress(order) ?? 1;
        final alpha = a?.cutAlpha(order) ?? 1;
        if (progress <= 0 || alpha <= 0) continue;
        for (final run in ring.runs) {
          _arc(canvas, c.cut, ring.radius, run.startDegrees, run.sweepDegrees * progress, g.cutWidth, alpha);
        }
        final cutTint = art.cutTint;
        if (ring.index < _ringTintWeights.length && tm > 0.001 && cutTint != null) {
          final revealed = [
            for (final run in ring.runs) (run.startDegrees, run.startDegrees + run.sweepDegrees * progress),
          ];
          _tintRing(canvas, ring.radius, g.cutWidth, cutTint, _ringTintWeights[ring.index] * alpha, ta, tm, revealed);
        }
      }
      if (g.hasLitLayers && a != null) {
        for (final ring in g.rings) {
          final glow = a.cutGlow(g.ringOrder(ring.index));
          if (glow > 0.002) {
            for (final run in ring.runs) {
              _arc(canvas, c.lit, ring.radius, run.startDegrees, run.sweepDegrees, g.cutWidth, glow);
            }
          }
          final whole = a.ringGlow(ring.index);
          if (whole > 0.002) {
            canvas.drawCircle(center, ring.radius, _stroke(c.lit, g.cutWidth, alpha: math.min(whole, 1)));
          }
        }
      }
    }
    canvas.restore();

    // 3 · rim: album = accent double rim, average = single palette rim, unrated = hairline only on a cover
    final rimR = g.rimRadius;
    switch (g.kind) {
      case GrooveScoreKind.album || GrooveScoreKind.average:
        final w = g.rimWidth;
        canvas.drawCircle(center, rimR - w / 2, _stroke(c.rim, w));
        if (tm > 0.001) _tintRing(canvas, rimR - w / 2, w, art.rimTint, _rimTintWeight, ta, tm, null);
        if (g.kind == GrooveScoreKind.album && !g.tiny) {
          canvas.drawCircle(center, rimR - g.rim2At, _stroke(c.rim2, g.rim2Width));
        }
      case GrooveScoreKind.none:
        if (g.surface == GrooveSurface.cover) {
          canvas.drawCircle(center, rimR - 0.4, _stroke(c.hairline, 0.8));
          if (!g.tiny && tm > 0.001) {
            _tintRing(canvas, rimR - 0.4, 0.8, art.rimTint, _hairlineTintWeight, ta, tm, null);
          }
        }
    }

    // 3b · the 10.0 moment: the rim pulses in flat accent and lets one thin ring go
    if (g.hasLitLayers && a != null) {
      final flare = a.flare;
      if (flare > 0.002) {
        final fw = g.flareWidth;
        canvas.drawCircle(center, rimR - fw / 2, _stroke(c.flare, fw, alpha: math.min(flare, 1)));
      }
      final burst = a.burstAlpha;
      if (burst > 0.002) {
        canvas.save();
        _scale(canvas, a.burstScale);
        canvas.drawCircle(center, rimR, _stroke(c.flare, g.burstWidth, alpha: math.min(burst, 1)));
        canvas.restore();
      }
    }

    // 4 · centre label (flat fills)
    final labelAlpha = (a?.labelAlpha ?? 1).clamp(0.0, 1.0);
    if (labelAlpha > 0) {
      canvas.save();
      _scale(canvas, a?.labelScale ?? 1);
      _paintLabel(canvas, g, c, disc, labelAlpha);
      canvas.restore();
    }
  }

  void _paintLabel(Canvas canvas, GrooveGeometry g, GrooveColors c, double disc, double alpha) {
    final center = art.center;
    final l = g.labelRadius;
    switch (g.kind) {
      case GrooveScoreKind.album:
        canvas.save();
        _rotate(canvas, disc);
        final label = art.labelPath;
        if (label != null) canvas.drawPath(label, _fill(c.label, alpha));
        final ring = art.labelRingPath;
        if (ring != null) canvas.drawPath(ring, _stroke(c.labelRing, g.albumLabelRingStroke, alpha: 0.9 * alpha));
        canvas.restore();
      case GrooveScoreKind.average:
        final w = g.averageLabelStroke;
        canvas.drawCircle(center, l - w / 2, _fill(c.labelFill, alpha));
        canvas.drawCircle(center, l - w / 2, _stroke(c.labelStroke, w, alpha: alpha));
      case GrooveScoreKind.none:
        final mould = art.mouldPath;
        final dashes = art.mouldDashes;
        if (mould != null) canvas.drawPath(mould, _fill(c.slotFill, alpha));
        if (dashes != null) {
          canvas.drawPath(dashes, _stroke(c.slot, g.unratedLabelStroke, alpha: alpha, cap: StrokeCap.round));
        }
        // spindle hole: an empty pressing
        if (g.showsSpindle) canvas.drawCircle(center, g.spindleRadius, _fill(c.slot, 0.85 * alpha));
    }
    _paintType(canvas, g, c, alpha);
  }

  /// CSS `line-height: 1`: each line box is its font size tall, the glyphs centred in it.
  void _line(Canvas canvas, TextPainter text, double lineTop, double fontSize, double alpha) {
    if (alpha <= 0) return;
    final x = art.center.dx - text.width / 2;
    final y = lineTop + (fontSize - text.height) / 2;
    if (alpha >= 1) {
      text.paint(canvas, Offset(x, y));
      return;
    }
    canvas.saveLayer(null, Paint()..color = Color.fromRGBO(0, 0, 0, alpha));
    text.paint(canvas, Offset(x, y));
    canvas.restore();
  }

  void _paintType(Canvas canvas, GrooveGeometry g, GrooveColors c, double alpha) {
    final s = g.size;
    final r = g.radius;
    final l = g.labelRadius;
    final score = art.score;
    if (score != null) {
      final f = g.scoreFontSize;
      final cap = art.caption;
      final capF = g.captionFontSize;
      final scoreMargin = cap != null ? f * 0.06 : 0.0;
      final total = scoreMargin + f + (cap != null ? s * 0.012 + capF : 0.0);
      final top = r - l + (2 * l - total) / 2;
      final scoreTop = top + scoreMargin + (cap == null ? f * 0.02 : 0.0);
      _line(canvas, score, scoreTop, f, alpha);
      final capAlpha = (captionAlpha?.value ?? 1).clamp(0.0, 1.0);
      if (cap != null && capAlpha > 0) {
        _line(canvas, cap, top + scoreMargin + f + s * 0.012, capF, alpha * capAlpha);
      }
    }
    final word = art.unrated;
    if (word != null) {
      final f = g.unratedFontSize;
      _line(canvas, word, r - f / 2, f, alpha);
    }
  }

  /// Prototype `tintRing`: 24 flat 15° segments; the high tone's opacity
  /// follows max(0, cos θ), the low tone's max(0, −cos θ), turned to the tilt
  /// direction [ta] and faded by its size [tm]. [clip] limits a groove ring's
  /// tint to its cut (and revealed) angles — angle intersections, no mask.
  void _tintRing(
    Canvas canvas,
    double radius,
    double width,
    GrooveTint tint,
    double weight,
    double ta,
    double tm,
    List<(double, double)>? clip,
  ) {
    for (var n = 0; n < _tintSegments; n++) {
      final a = -90 + n * _tintStep;
      final kk = math.cos((a + _tintStep / 2) * math.pi / 180);
      final Color color;
      final double amount;
      if (kk > 0.02) {
        color = tint.high;
        amount = kk * weight;
      } else if (kk < -0.02) {
        color = tint.low;
        amount = -kk * weight;
      } else {
        continue;
      }
      final alpha = amount * tm;
      final s0 = a + ta;
      final s1 = s0 + _tintStep;
      if (clip == null) {
        _arc(canvas, color, radius, s0, _tintStep, width, alpha);
      } else {
        for (final (r0, r1) in clip) {
          for (final shift in _shifts) {
            final lo = math.max(s0 + shift, r0);
            final hi = math.min(s1 + shift, r1);
            if (hi > lo) _arc(canvas, color, radius, lo, hi - lo, width, alpha);
          }
        }
      }
    }
  }

  @override
  bool shouldRepaint(_GroovePainter old) =>
      !identical(old.art, art) ||
      !identical(old.award, award) ||
      !identical(old.captionAlpha, captionAlpha) ||
      !identical(old.tilt, tilt);
}
