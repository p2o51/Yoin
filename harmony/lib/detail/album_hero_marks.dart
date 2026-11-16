import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../data/model/model.dart';
import '../memories/album_score.dart';
import '../memories/memory_rate_sheet.dart';
import '../memories/palette/memory_palette.dart';
import '../theme/motion.dart';
import '../ui/component/artwork_image.dart';
import '../ui/component/spring_cross_fade.dart';
import 'detail_listening.dart';
import 'detail_palette.dart';
import 'dot_dissolve.dart';
import 'detail_strings.dart';

/// Album hero numbers (Android AlbumDetailScreen AlbumHeroDetails /
/// AlbumSpectrumBar / AlbumDetailComponents). Detail-local until the shared
/// tokens take them (report: YoinTokens.kt has no counterpart yet).
abstract final class AlbumHeroTokens {
  /// Emblem ↔ comment gap (scoreBesideComment Row spacedBy).
  static const double metaGap = 20;

  /// The written review: at most three lines, the day it was written 4 under it.
  static const int reviewMaxLines = 3;
  static const double reviewDateGap = 4;

  /// Under the emblem row, before the track-count line (the row itself sits
  /// in the hero column's existing 8dp top padding).
  static const double metaBottom = 28;

  /// Emblem → "X/N rated" (AlbumScoreEmblem spacedBy).
  static const double coverageGap = 10;

  /// AlbumPageEmblemSize.
  static const double emblem = 64;

  /// Between the meta blocks (AlbumHeroMetaBlocks spacedBy).
  static const double blockGap = 14;

  /// Last Play: caption → day, day → time.
  static const double lastPlayCaptionGap = 4;

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
///
/// [hugging]: the whole mark hugging a side cover instead (huggingPose: a
/// landscape hero, a page in the detail column whose sides are not screen
/// edges) — the caller sizes it to [albumHuggingMark]'s box.
class AlbumButterfly extends StatelessWidget {
  const AlbumButterfly({super.key, required this.leaving, this.hugging = false});

  final double leaving;
  final bool hugging;

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
              scale: hugging ? 1 : AlbumHeroTokens.butterflyScale,
              offsetYFraction: hugging ? 0 : AlbumHeroTokens.butterflyOffsetYFraction,
              squashX: hugging ? 1 : AlbumHeroTokens.butterflySquashX,
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
///
/// [scale] / [offsetYFraction] / [squashX] are the MarkPose (the Compact
/// pose by default; 1 / 0 / 1 for the hugging one, drawn into a mark-sized box).
class AlbumButterflyPainter extends CustomPainter {
  AlbumButterflyPainter({
    required this.blockA,
    required this.blockB,
    required this.line,
    this.scale = AlbumHeroTokens.butterflyScale,
    this.offsetYFraction = AlbumHeroTokens.butterflyOffsetYFraction,
    this.squashX = AlbumHeroTokens.butterflySquashX,
  });

  final Color blockA;
  final Color blockB;
  final Color line;
  final double scale;
  final double offsetYFraction;
  final double squashX;

  @override
  void paint(Canvas canvas, Size size) {
    final w = size.width;
    final h = size.height;
    if (w <= 0 || h <= 0) return;
    final m = YoinMarkArms.instance;
    final s = math.min(w, h) / YoinMarkArms.viewBox;
    canvas.save();
    // MarkPose(left 0, top 0, width w, height band) in Compose's withTransform order.
    canvas.translate(0, offsetYFraction * h);
    canvas.translate(w / 2, h / 2);
    canvas.scale(scale);
    canvas.translate(-w / 2, -h / 2);
    canvas.translate((w - YoinMarkArms.viewBox * s) / 2, (h - YoinMarkArms.viewBox * s) / 2);
    canvas.scale(s);
    final hub = YoinMarkArms.hub;
    canvas.translate(hub.dx, hub.dy);
    canvas.scale(squashX, 1);
    canvas.translate(-hub.dx, -hub.dy);
    m.drawArm(canvas, 0, blockA, line);
    m.drawArm(canvas, 2, blockB, line);
    canvas.restore();
  }

  @override
  bool shouldRepaint(AlbumButterflyPainter old) =>
      old.blockA != blockA ||
      old.blockB != blockB ||
      old.line != line ||
      old.scale != scale ||
      old.offsetYFraction != offsetYFraction ||
      old.squashX != squashX;
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
// The comment: the review as written, or the pen.
// ---------------------------------------------------------------------------

/// The album's comment (Android AlbumHeroComment): the user's own words, so
/// no "Comment" caption. Written, the review shows as text — three lines at
/// most, then the day it was written (owner 2026-10-05: "写了就留 comment，
/// 再加一个写的日期") — and a tap edits it; not written yet, only the pen.
/// Writing the first words or clearing them crossfades the two.
class AlbumHeroComment extends ConsumerWidget {
  const AlbumHeroComment({super.key, required this.albumId, this.onEdit, this.now});

  final MediaId albumId;

  /// Opens the rate sheet with the caret in the review; null: not interactive.
  final VoidCallback? onEdit;

  /// Today, for the date's "Today / Yesterday" (tests); null = the clock.
  final DateTime? now;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final score = ref.watch(albumScoreProvider(albumId));
    final review = score.review?.trim() ?? '';
    final written = review.isNotEmpty;
    return SpringCrossFade(
      childKey: written,
      fit: StackFit.loose,
      child: written
          ? AlbumReviewText(review: review, writtenAt: score.reviewUpdatedAt, onTap: onEdit, now: now)
          : AlbumCommentPen(onTap: onEdit),
    );
  }
}

/// The written review (AlbumHeroComment's text branch): bodyMedium in
/// onSurface, three lines then an ellipsis; under it, 4 apart, the day it was
/// written in labelMedium / onSurfaceVariant (the Last Play day's words).
/// The whole block is one button: "Edit comment".
class AlbumReviewText extends StatelessWidget {
  const AlbumReviewText({super.key, required this.review, this.writtenAt, this.onTap, this.now});

  final String review;
  final int? writtenAt;
  final VoidCallback? onTap;
  final DateTime? now;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final strings = DetailStrings.of(context);
    final at = writtenAt;
    return Semantics(
      button: onTap != null,
      onTapHint: strings.albumCdEditComment,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: onTap,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          spacing: AlbumHeroTokens.reviewDateGap,
          children: [
            Text(
              review,
              maxLines: AlbumHeroTokens.reviewMaxLines,
              overflow: TextOverflow.ellipsis,
              style: theme.textTheme.bodyMedium?.copyWith(color: colors.onSurface),
            ),
            if (at != null)
              Text(
                lastPlayLabels(at, strings, now: now).$1,
                maxLines: 1,
                style: theme.textTheme.labelMedium?.copyWith(color: colors.onSurfaceVariant),
              ),
          ],
        ),
      ),
    );
  }
}

/// The album comment's pen (Android AlbumHeroComment, no review yet): a 40dp
/// tonal round button with the Edit glyph, "Write a comment"; opens the rate
/// sheet with the caret in the review.
class AlbumCommentPen extends StatelessWidget {
  const AlbumCommentPen({super.key, this.onTap});

  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Semantics(
      button: onTap != null,
      label: DetailStrings.of(context).albumCdWriteComment,
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

/// The emblem and the comment on one row, the cover's width
/// (scoreBesideComment): the comment takes what the emblem leaves. The emblem
/// is the album's live score (ScoreEmblem: album score, else the track
/// average, else the empty mould); it and the comment open the rate and
/// review sheet (AlbumRateSheet), the comment with the caret in the review.
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
          image: url == null ? null : artworkImageProvider(url, cacheWidth: 200, cacheHeight: 200),
          builder: (context, palette) {
            void open({bool review = false}) => showAlbumRateSheet(
              context,
              albumId: albumId,
              albumName: albumName,
              palette: palette,
              focusReview: review,
              coverUrl: url,
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
                Expanded(
                  child: Align(
                    alignment: AlignmentDirectional.centerStart,
                    child: AlbumHeroComment(albumId: albumId, onEdit: () => open(review: true)),
                  ),
                ),
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
              // Its own node, the size of its words: left to merge upward it took the hero's whole
              // drag region as its bounds.
              child: Semantics(
                container: true,
                child: Text(
                  text,
                  maxLines: 1,
                  style: theme.textTheme.labelSmall?.copyWith(
                    color: theme.colorScheme.onSurfaceVariant,
                    fontFeatures: const [FontFeature.tabularFigures()],
                  ),
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

// ---------------------------------------------------------------------------
// The hero's meta blocks beside a side cover (Short, Landscape, Medium, Wide).
// ---------------------------------------------------------------------------

/// Last Play + score emblem + comment (Android AlbumHeroMetaBlocks), for the
/// layouts that set the meta beside or under a side cover. Row form: Last
/// Play | emblem across, the comment under them; [stacked] (Short's narrow
/// column beside the cover): one block per line, Last Play a size smaller and
/// without its caption. 14 between blocks. [interactive] false while the
/// pulled-up list is the dominant layer (its taps go nowhere).
class AlbumHeroMetaBlocks extends StatelessWidget {
  const AlbumHeroMetaBlocks({
    super.key,
    required this.albumId,
    required this.albumName,
    this.coverUrl,
    this.stacked = false,
    this.interactive = true,
  });

  final MediaId albumId;
  final String albumName;
  final String? coverUrl;
  final bool stacked;
  final bool interactive;

  @override
  Widget build(BuildContext context) {
    final url = coverUrl;
    return IgnorePointer(
      ignoring: !interactive,
      child: MemoryPaletteBuilder(
        cacheKey: url,
        image: url == null ? null : artworkImageProvider(url, cacheWidth: 200, cacheHeight: 200),
        builder: (context, palette) {
          void open({bool review = false}) => showAlbumRateSheet(
            context,
            albumId: albumId,
            albumName: albumName,
            palette: palette,
            focusReview: review,
            coverUrl: url,
          );
          final lastPlay = AlbumLastPlayBlock(albumId: albumId, compact: stacked);
          final emblem = Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Semantics(
                button: true,
                label: DetailStrings.of(context).albumCdRate,
                child: AlbumScoreEmblem(albumId: albumId, palette: palette, onTap: open, size: AlbumHeroTokens.emblem),
              ),
              AlbumRatedCoverage(albumId: albumId),
            ],
          );
          final comment = AlbumHeroComment(albumId: albumId, onEdit: () => open(review: true));
          return Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            spacing: AlbumHeroTokens.blockGap,
            children: stacked
                ? [lastPlay, emblem, comment]
                : [
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Flexible(child: lastPlay),
                        emblem,
                      ],
                    ),
                    comment,
                  ],
          );
        },
      ),
    );
  }
}

/// Last Play (Android AlbumHeroMetaBlocks lastPlay): the underlined caption,
/// the day ("Yesterday", "Never" before the first play) and the 24-hour time
/// under it. The first play turns "Never" into a day: the word cross-fades
/// and the time row grows in on the spatial spring. [compact] (Short's
/// column beside the cover): no caption, a size smaller.
class AlbumLastPlayBlock extends ConsumerWidget {
  const AlbumLastPlayBlock({super.key, required this.albumId, this.compact = false, this.now});

  final MediaId albumId;
  final bool compact;

  /// Today, for "Today / Yesterday" (tests); null = the clock.
  final DateTime? now;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = DetailStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final at = ref.watch(albumLastPlayedProvider(albumId));
    final labels = at == null ? null : lastPlayLabels(at, strings, now: now);
    final dayStyle = (compact ? theme.textTheme.bodyMedium : theme.textTheme.bodyLarge)?.copyWith(
      color: colors.onSurface,
    );
    final timeStyle = (compact ? theme.textTheme.bodySmall : theme.textTheme.bodyMedium)?.copyWith(
      color: colors.onSurfaceVariant,
      fontFeatures: const [FontFeature.tabularFigures()],
    );
    final day = labels?.$1 ?? strings.albumNever;
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (!compact)
          Padding(
            padding: const EdgeInsets.only(bottom: AlbumHeroTokens.lastPlayCaptionGap),
            child: Text(
              strings.albumLastPlay,
              style: theme.textTheme.labelLarge?.copyWith(
                decoration: TextDecoration.underline,
                decorationColor: colors.onSurface,
                fontWeight: FontWeight.w500,
                color: colors.onSurface,
              ),
            ),
          ),
        SpringCrossFade(
          childKey: day,
          fit: StackFit.loose,
          child: Text(day, key: ValueKey(day), style: dayStyle),
        ),
        SpringSizeReveal(
          visible: labels != null,
          child: Padding(
            padding: const EdgeInsets.only(top: AlbumHeroTokens.lastPlayCaptionGap),
            child: Text(labels?.$2 ?? '', style: timeStyle),
          ),
        ),
      ],
    );
  }
}

/// expandVertically + fade from the top on the spatial / effects springs
/// (AnimatedVisibility(expandVertically, fadeIn)); keeps its child while it
/// leaves. [axis] horizontal: expandHorizontally from the start edge.
class SpringSizeReveal extends StatefulWidget {
  const SpringSizeReveal({super.key, required this.visible, required this.child, this.axis = Axis.vertical});

  final bool visible;
  final Widget child;
  final Axis axis;

  @override
  State<SpringSizeReveal> createState() => _SpringSizeRevealState();
}

class _SpringSizeRevealState extends State<SpringSizeReveal> with TickerProviderStateMixin {
  late final SpringValue _size = SpringValue(
    vsync: this,
    initial: widget.visible ? 1 : 0,
    spring: YoinSprings.defaultSpatial,
  );
  late final SpringValue _alpha = SpringValue(
    vsync: this,
    initial: widget.visible ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );
  late Widget _shown = widget.child;

  @override
  void didUpdateWidget(SpringSizeReveal old) {
    super.didUpdateWidget(old);
    if (widget.visible) _shown = widget.child;
    if (old.visible != widget.visible) {
      if (MediaQuery.disableAnimationsOf(context)) {
        _size.snapTo(widget.visible ? 1 : 0);
        _alpha.snapTo(widget.visible ? 1 : 0);
      } else {
        _size.animateTo(widget.visible ? 1 : 0);
        _alpha.animateTo(widget.visible ? 1 : 0);
      }
    }
  }

  @override
  void dispose() {
    _size.dispose();
    _alpha.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: Listenable.merge([_size.animation, _alpha.animation]),
    child: _shown,
    builder: (context, child) {
      final f = _size.value.clamp(0.0, 1.2);
      if (f <= 0.001 && !widget.visible) return const SizedBox.shrink();
      final vertical = widget.axis == Axis.vertical;
      return ClipRect(
        child: Align(
          alignment: vertical ? Alignment.topCenter : AlignmentDirectional.centerStart,
          heightFactor: vertical ? f : null,
          widthFactor: vertical ? null : f,
          child: Opacity(opacity: _alpha.value.clamp(0.0, 1.0), child: child),
        ),
      );
    },
  );
}
