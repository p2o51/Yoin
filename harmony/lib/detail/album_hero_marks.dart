import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../data/model/model.dart';
import '../memories/album_score.dart';
import '../memories/memory_rate_sheet.dart';
import '../memories/palette/memory_palette.dart';
import '../theme/theme.dart';
import '../ui/component/spring_cross_fade.dart';
import 'detail_palette.dart';
import 'dot_dissolve.dart';
import 'detail_strings.dart';

/// Album hero numbers (Android AlbumDetailScreen AlbumHeroDetails /
/// AlbumSpectrumBar / AlbumDetailComponents). Detail-local until the shared
/// tokens take them (report: YoinTokens.kt has no counterpart yet).
abstract final class AlbumHeroTokens {
  /// Emblem ↔ pen gap (scoreBesideComment Row spacedBy).
  static const double metaGap = 20;

  /// Under the emblem row, before the track-count line (the row itself sits
  /// in the hero column's existing 8dp top padding).
  static const double metaBottom = 28;

  /// Emblem → "X/N rated" (AlbumScoreEmblem spacedBy).
  static const double coverageGap = 10;

  /// AlbumPageEmblemSize.
  static const double emblem = 64;

  /// The pen: FilledTonalIconButton 40dp with an 18dp Edit glyph.
  static const double pen = 40;
  static const double penGlyph = 18;

  /// Compact butterfly pose (CompactArrowScale / OffsetYFraction / SquashX).
  static const double butterflyScale = 1.8;
  static const double butterflyOffsetYFraction = -0.1;
  static const double butterflySquashX = 1.35;

  /// The arms' centre line: the page surface at this alpha (drawButterfly).
  static const double butterflyLineAlpha = 0.65;
}

// ---------------------------------------------------------------------------
// Butterfly: the Yoin mark's two lower arms behind the cover.
// ---------------------------------------------------------------------------

/// The album "family" backdrop (Android DetailBackdrops / drawButterfly,
/// Compact): the mark's first and third arms scaled up about the hero band
/// and squashed wide so they bleed off the page's sides. Arm 0 wears the
/// cover scheme's primary, arm 2 its secondary; the centre lines are the page
/// surface at 0.65. [leaving] (0 = whole, 1 = gone) follows the pull-up: the
/// blocks stay where they are and break up into fine dots (DotDissolve);
/// reduced motion fades them instead.
class AlbumButterfly extends StatelessWidget {
  const AlbumButterfly({super.key, required this.leaving});

  final double leaving;

  @override
  Widget build(BuildContext context) {
    final surface = Theme.of(context).colorScheme.surface;
    final reduce = MediaQuery.disableAnimationsOf(context);
    final shown = (1 - leaving).clamp(0.0, 1.0);
    if (shown <= 0) return const SizedBox.shrink();
    return DetailColorsBuilder(
      builder: (context, page) => DotDissolve(
        progress: () => reduce ? 0 : leaving,
        child: Opacity(
          opacity: reduce ? shown : 1,
          child: CustomPaint(
            painter: AlbumButterflyPainter(
              blockA: page.accent,
              blockB: page.secondary,
              line: surface.withValues(alpha: AlbumHeroTokens.butterflyLineAlpha),
            ),
            size: Size.infinite,
          ),
        ),
      ),
    );
  }
}

/// Paints the butterfly into a box as wide as the page and as tall as the
/// hero band (cover side + 56). It runs past the box; the hero layer clips it
/// at the header's bottom edge.
class AlbumButterflyPainter extends CustomPainter {
  AlbumButterflyPainter({required this.blockA, required this.blockB, required this.line});

  final Color blockA;
  final Color blockB;
  final Color line;

  @override
  void paint(Canvas canvas, Size size) {
    final w = size.width;
    final h = size.height;
    if (w <= 0 || h <= 0) return;
    final m = YoinMarkArms.instance;
    final s = math.min(w, h) / YoinMarkArms.viewBox;
    canvas.save();
    // MarkPose(left 0, top 0, width w, height band) in Compose's withTransform order.
    canvas.translate(0, AlbumHeroTokens.butterflyOffsetYFraction * h);
    canvas.translate(w / 2, h / 2);
    canvas.scale(AlbumHeroTokens.butterflyScale);
    canvas.translate(-w / 2, -h / 2);
    canvas.translate((w - YoinMarkArms.viewBox * s) / 2, (h - YoinMarkArms.viewBox * s) / 2);
    canvas.scale(s);
    final hub = YoinMarkArms.hub;
    canvas.translate(hub.dx, hub.dy);
    canvas.scale(AlbumHeroTokens.butterflySquashX, 1);
    canvas.translate(-hub.dx, -hub.dy);
    m.drawArm(canvas, 0, blockA, line);
    m.drawArm(canvas, 2, blockB, line);
    canvas.restore();
  }

  @override
  bool shouldRepaint(AlbumButterflyPainter old) => old.blockA != blockA || old.blockB != blockB || old.line != line;
}

/// The two Yoin mark arms the album backdrop uses, parsed once (Android
/// YoinMark.kt ARM_DEFS, the 1024 × 1024 view box; brand artwork, not a
/// symbol). Only arms 0 and 2 are kept: nothing in Harmony draws arm 1 yet.
final class YoinMarkArms {
  YoinMarkArms._()
    : _fills = {0: parseSvgPathData(_arm0Fill), 2: parseSvgPathData(_arm2Fill)},
      _lines = {0: parseSvgPathData(_arm0Line), 2: parseSvgPathData(_arm2Line)};

  static final YoinMarkArms instance = YoinMarkArms._();

  static const double viewBox = 1024;

  /// Where the three arms meet (YoinMark PIVOT).
  static const Offset hub = Offset(512, 500);

  static const Map<int, double> _lineWidths = {0: 8, 2: 5};

  final Map<int, Path> _fills;
  final Map<int, Path> _lines;

  void drawArm(Canvas canvas, int arm, Color fill, Color line) {
    canvas.drawPath(_fills[arm]!, Paint()..color = fill);
    canvas.drawPath(
      _lines[arm]!,
      Paint()
        ..color = line
        ..style = PaintingStyle.stroke
        ..strokeWidth = _lineWidths[arm]!
        ..strokeCap = StrokeCap.round,
    );
  }

  static const _arm0Fill =
      'M172.678 401.921C149.97 396.319 99.5819 390.37 66.6847 432.804C32.9531 476.314 41.3602 538.563 85.4627 571.842C106.711 587.875 134.238 598.446 151.945 604.775C173.271 612.397 197.656 619.65 221.53 626.142C228.147 627.941 256.803 632.768 263.5 634.5C258.128 687.726 249.508 750.719 310.209 800.246C352.996 835.156 416.368 829.236 451.753 787.025C486.19 745.944 506.216 688.882 466.5 653.5C466.5 653.5 448.817 634.685 456.166 622.084C459.88 615.715 482.277 584.668 503.523 555.516C522.775 529.098 509.29 491.515 477.595 483.515L431.67 471.924L172.678 401.921Z';
  static const _arm0Line =
      'M87 437C131.456 476.373 325.523 529.493 417 551.132C380.523 608.976 329.456 678.162 417 762';
  static const _arm2Fill =
      'M767.435 383.407C788.087 343.579 835.72 324.591 878.753 340.771C925.194 358.234 948.689 410.045 931.23 456.494C914.712 500.436 877.296 527.369 853.146 542.122C825.327 559.116 792.803 573.003 761.852 584.176C739.154 592.37 715.544 599.723 692.517 606.16C694.132 611.89 695.648 617.706 697.044 623.59C708.757 672.964 715.743 742.622 684.615 806.496C662.876 851.103 609.099 869.638 564.499 847.896C519.9 826.154 501.368 772.368 523.106 727.761C527.415 718.919 530.182 698.615 522.225 665.077C514.774 633.667 501.025 604.12 491.764 588.484L475.768 561.478C451.638 520.739 475.49 468.426 522.07 459.928L552.934 454.296C591.446 447.27 650.468 433.336 700.854 415.148C726.258 405.977 746.213 396.875 759.491 388.764C763.261 386.461 765.811 384.655 767.435 383.407Z';
  static const _arm2Line =
      'M900 381C870.952 458.266 639.23 518.528 527 539.001C562.367 598.701 621.217 742.48 573.684 840';
}

/// Absolute SVG path data with M, L, C and Z (a command's extra coordinate
/// sets repeat it; M's repeat as L) — all the mark's arms use.
Path parseSvgPathData(String d) {
  final tokens = RegExp(r'[A-Za-z]|-?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?').allMatches(d).map((m) => m[0]!);
  final path = Path();
  String? command;
  final n = <double>[];
  void flush() {
    switch (command) {
      case 'M':
        for (var i = 0; i + 1 < n.length; i += 2) {
          i == 0 ? path.moveTo(n[i], n[i + 1]) : path.lineTo(n[i], n[i + 1]);
        }
      case 'L':
        for (var i = 0; i + 1 < n.length; i += 2) {
          path.lineTo(n[i], n[i + 1]);
        }
      case 'C':
        for (var i = 0; i + 5 < n.length; i += 6) {
          path.cubicTo(n[i], n[i + 1], n[i + 2], n[i + 3], n[i + 4], n[i + 5]);
        }
      case 'Z':
        path.close();
      case null:
        break;
      default:
        throw FormatException('Unsupported path command $command');
    }
    n.clear();
  }

  for (final t in tokens) {
    final value = double.tryParse(t);
    if (value != null) {
      n.add(value);
      continue;
    }
    flush();
    command = t;
    if (t == 'Z') {
      flush();
      command = null;
    }
  }
  flush();
  return path;
}

// ---------------------------------------------------------------------------
// Score emblem: the unrated mould.
// ---------------------------------------------------------------------------

/// The album's score emblem as v1 can show it (Android ScoreEmblem →
/// GrooveEmblem, Page surface, 64dp): always the unrated mould — a faint
/// ground, one dotted ring per track (merged to the size's ring budget), a
/// dashed Cookie12Sided slot and the word "Unrated". Harmony keeps no local
/// album or track ratings yet (Android's score comes from them, never from the
/// server's stars), so the album and average kinds have nothing to show.
/// Not tappable: Android opens the rate sheet / scrapbook, both deferred.
class AlbumUnratedEmblem extends StatelessWidget {
  const AlbumUnratedEmblem({super.key, required this.trackCount, this.size = AlbumHeroTokens.emblem});

  final int trackCount;
  final double size;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final strings = DetailStrings.of(context);
    return Semantics(
      image: true,
      label: strings.emblemCdUnrated,
      child: ExcludeSemantics(
        child: SizedBox.square(
          dimension: size,
          child: CustomPaint(
            painter: UnratedEmblemPainter(
              geometry: UnratedEmblemGeometry(size: size, trackCount: trackCount),
              outline: colors.outline,
              ink: colors.onSurfaceVariant,
              dark: colors.surface.computeLuminance() < 0.5,
              word: strings.emblemUnrated,
            ),
          ),
        ),
      ),
    );
  }
}

/// GrooveGeometry for the unrated kind on a page (rim inset 0.5, no rim
/// stroke), in dp. Ported from Android grooveGeometry.
@immutable
final class UnratedEmblemGeometry {
  factory UnratedEmblemGeometry({required double size, required int trackCount}) {
    final s = size;
    final r = s / 2;
    final tiny = s < 60;
    final big = s >= 110;
    final rimR = r - 0.5;
    final labelR = s * (tiny ? 0.345 : (big ? 0.3 : 0.305)) * (s >= 60 && s < 88 ? 1.07 : 1.0);
    final leadIn = tiny ? 0.9 : math.max(1.4, s * 0.016);
    final gOut = rimR - leadIn;
    final gIn = labelR + math.max(1.2, s * 0.017);
    final band = gOut - gIn;
    final cap = big ? 12 : (s >= 88 ? 8 : (s >= 64 ? 6 : 3));
    final minPitch = tiny ? 1.3 : 1.72;
    final n = math.max(trackCount, 1);
    final ringCount = math.max(1, math.min(math.min(cap, n), (band / minPitch).floor()));
    final pitch = band / ringCount;
    return UnratedEmblemGeometry._(
      size: s,
      tiny: tiny,
      rimRadius: rimR,
      labelRadius: labelR,
      ringRadii: [for (var i = 0; i < ringCount; i++) gOut - pitch * (i + 0.5)],
      dotDiameter: math.min(pitch * 0.58, tiny ? 1.0 : 1.2),
      labelStroke: tiny ? 1.4 : math.max(1.1, s * 0.013),
      showsWord: s >= 64,
      wordSize: s < 88 ? 9.5 : math.max(10.0, s * 0.125),
      wordWidthAxis: s < 88 ? 76 : 92,
    );
  }

  const UnratedEmblemGeometry._({
    required this.size,
    required this.tiny,
    required this.rimRadius,
    required this.labelRadius,
    required this.ringRadii,
    required this.dotDiameter,
    required this.labelStroke,
    required this.showsWord,
    required this.wordSize,
    required this.wordWidthAxis,
  });

  final double size;
  final bool tiny;
  final double rimRadius;
  final double labelRadius;

  /// Outer ring first.
  final List<double> ringRadii;
  final double dotDiameter;
  final double labelStroke;
  final bool showsWord;
  final double wordSize;
  final double wordWidthAxis;

  /// The spindle hole of an empty pressing (below 64dp).
  bool get showsSpindle => size < 64;
  double get spindleRadius => math.max(1.6, size * 0.045);

  /// Dots on ring [i] (GrooveGeometry.dotCount).
  int dotCount(int i) {
    final circumference = 2 * math.pi * ringRadii[i];
    final target = math.max(tiny ? 2.3 : 2.6, dotDiameter * 2.3);
    return math.max(6, (circumference / target).round());
  }
}

class UnratedEmblemPainter extends CustomPainter {
  UnratedEmblemPainter({
    required this.geometry,
    required this.outline,
    required this.ink,
    required this.dark,
    required this.word,
  });

  final UnratedEmblemGeometry geometry;
  final Color outline;
  final Color ink;
  final bool dark;
  final String word;

  @override
  void paint(Canvas canvas, Size size) {
    final g = geometry;
    final k = size.width / g.size;
    final c = Offset(size.width / 2, size.height / 2);
    // GrooveColors (Unrated, not on a cover).
    final ground = outline.withValues(alpha: dark ? 0.1 : 0.06);
    final dot = outline.withValues(alpha: g.tiny ? (dark ? 0.95 : 0.8) : (dark ? 0.82 : 0.6));
    final slotFill = outline.withValues(alpha: dark ? 0.14 : 0.08);

    canvas.drawCircle(c, g.rimRadius * k, Paint()..color = ground);

    final dotPaint = Paint()
      ..color = dot
      ..strokeWidth = g.dotDiameter * k
      ..strokeCap = StrokeCap.round;
    for (var i = 0; i < g.ringRadii.length; i++) {
      final n = g.dotCount(i);
      final phase = i.isOdd ? 0.5 : 0.0;
      final r = g.ringRadii[i] * k;
      canvas.drawPoints(ui.PointMode.points, [
        for (var j = 0; j < n; j++)
          c + Offset(math.cos((j + phase) * 2 * math.pi / n), math.sin((j + phase) * 2 * math.pi / n)) * r,
      ], dotPaint);
    }

    // The mould: a Cookie12Sided whose lobes reach labelRadius − stroke / 2.
    final mould = cookie12Path(c, (g.labelRadius - g.labelStroke / 2) * k);
    canvas.drawPath(mould, Paint()..color = slotFill);
    final slot = Paint()
      ..color = outline
      ..style = PaintingStyle.stroke
      ..strokeWidth = g.labelStroke * k
      ..strokeCap = StrokeCap.round;
    // 24 dashes (two per lobe), the first starting on the top lobe's peak (where the path starts).
    for (final metric in mould.computeMetrics()) {
      final per = metric.length / 24;
      for (var i = 0; i < 24; i++) {
        canvas.drawPath(metric.extractPath(per * i, per * i + per * 0.55), slot);
      }
    }
    if (g.showsSpindle) {
      canvas.drawCircle(c, g.spindleRadius * k, Paint()..color = outline.withValues(alpha: 0.85));
    }
    if (g.showsWord) {
      final f = g.wordSize * k;
      final base = gsf(f, weight: 600, height: 1, letterSpacing: 0);
      final tp = TextPainter(
        text: TextSpan(
          text: word,
          style: base.copyWith(
            color: ink,
            fontVariations: [...?base.fontVariations, FontVariation('wdth', g.wordWidthAxis)],
          ),
        ),
        textDirection: TextDirection.ltr,
        maxLines: 1,
      )..layout();
      tp.paint(canvas, c - Offset(tp.width / 2, tp.height / 2));
      tp.dispose();
    }
  }

  @override
  bool shouldRepaint(UnratedEmblemPainter old) =>
      old.geometry.size != geometry.size ||
      old.geometry.ringRadii.length != geometry.ringRadii.length ||
      old.outline != outline ||
      old.ink != ink ||
      old.dark != dark ||
      old.word != word;
}

/// MaterialShapes.Cookie12Sided (a 12-point star, inner radius 0.8, corners
/// rounded 0.5) as a smooth 12-lobe curve whose lobes reach [radius], the
/// first lobe at 12 o'clock where the path starts.
Path cookie12Path(Offset centre, double radius) {
  const lobes = 12;
  const steps = 240;
  // Rounded corners pull the peaks in and fill the valleys: the lobes swing 0.88 … 1.
  const valley = 0.88;
  final path = Path();
  for (var i = 0; i <= steps; i++) {
    final a = -math.pi / 2 + i / steps * 2 * math.pi;
    final wave = (math.cos(lobes * (a + math.pi / 2)) + 1) / 2;
    final r = radius * (valley + (1 - valley) * wave);
    final p = centre + Offset(math.cos(a), math.sin(a)) * r;
    i == 0 ? path.moveTo(p.dx, p.dy) : path.lineTo(p.dx, p.dy);
  }
  return path..close();
}

// ---------------------------------------------------------------------------
// Comment pen.
// ---------------------------------------------------------------------------

/// The album comment's pen (Android AlbumHeroComment): a 40dp tonal round
/// button with the Edit glyph; opens the rate sheet with the caret in the review.
class AlbumCommentPen extends StatelessWidget {
  const AlbumCommentPen({super.key, this.onTap});

  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Semantics(
      button: onTap != null,
      label: DetailStrings.of(context).albumCdRate,
      excludeSemantics: true,
      child: Material(
        color: colors.secondaryContainer,
        shape: const CircleBorder(),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: onTap,
          child: SizedBox(
            width: AlbumHeroTokens.pen,
            height: AlbumHeroTokens.pen,
            child: Icon(YoinSymbols.edit, size: AlbumHeroTokens.penGlyph, color: colors.onSecondaryContainer),
          ),
        ),
      ),
    );
  }
}

/// The emblem and the pen on one row, the cover's width (scoreBesideComment).
/// The emblem is the album's live score (ScoreEmblem: album score, else the
/// track average, else the empty mould); it and the pen open the rate and
/// review sheet (AlbumRateSheet), the pen with the caret in the review.
class AlbumHeroMetaRow extends StatelessWidget {
  const AlbumHeroMetaRow({
    super.key,
    required this.width,
    required this.trackCount,
    required this.albumId,
    required this.albumName,
    this.coverUrl,
  });

  final double width;
  final int trackCount;
  final MediaId albumId;
  final String albumName;
  final String? coverUrl;

  @override
  Widget build(BuildContext context) {
    final url = coverUrl;
    return Center(
      child: SizedBox(
        width: width,
        child: MemoryPaletteBuilder(
          cacheKey: url,
          image: url == null ? null : ResizeImage(NetworkImage(url), width: 200, height: 200),
          builder: (context, palette) {
            void open({bool review = false}) => showAlbumRateSheet(
              context,
              albumId: albumId,
              albumName: albumName,
              palette: palette,
              focusReview: review,
            );
            return Row(
              children: [
                Semantics(
                  button: true,
                  label: DetailStrings.of(context).albumCdRate,
                  child: AlbumScoreEmblem(
                    albumId: albumId,
                    palette: palette,
                    onTap: open,
                    size: AlbumHeroTokens.emblem,
                  ),
                ),
                AlbumRatedCoverage(albumId: albumId),
                const SizedBox(width: AlbumHeroTokens.metaGap),
                AlbumCommentPen(onTap: () => open(review: true)),
              ],
            );
          },
        ),
      ),
    );
  }
}

/// How many tracks a computed average stands on ("2/4 rated", Android
/// AlbumRatedCoverage) beside the emblem. Only for the average: a manual
/// album score stands alone, and "not rated" says nothing (owner 2026-10-05).
class AlbumRatedCoverage extends ConsumerWidget {
  const AlbumRatedCoverage({super.key, required this.albumId});

  final MediaId albumId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final score = ref.watch(albumScoreProvider(albumId));
    final text = albumRatedCoverageText(score, DetailStrings.of(context));
    final theme = Theme.of(context);
    return SpringCrossFade(
      childKey: text ?? const Object(),
      fit: StackFit.loose,
      child: text == null
          ? const SizedBox.shrink()
          : Padding(
              padding: const EdgeInsetsDirectional.only(start: AlbumHeroTokens.coverageGap),
              child: Text(
                text,
                maxLines: 1,
                style: theme.textTheme.labelSmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                  fontFeatures: const [FontFeature.tabularFigures()],
                ),
              ),
            ),
    );
  }
}

/// "X/N rated" when the emblem shows the track average, else null. N is the
/// loaded track count (the rated count is taken over the loaded tracks).
String? albumRatedCoverageText(AlbumScore score, DetailStrings strings) {
  if (score.albumRating != null || score.average == null || score.trackCount == 0) return null;
  return strings.albumRated(score.ratedCount, score.trackCount);
}
