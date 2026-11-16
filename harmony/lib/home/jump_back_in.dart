import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../memories/memory_title_editor.dart';
import '../theme/tokens.dart';
import 'home_actions.dart';
import 'home_header.dart';
import 'home_jbi_template.dart';
import 'home_metrics.dart';
import 'home_row_presets.dart';
import 'home_rows_resize.dart';
import 'home_section.dart';
import 'home_selection.dart';
import 'home_strings.dart';
import 'home_ui_state.dart';
import 'widgets/cover_palette.dart';
import 'widgets/elastic_press.dart';
import 'widgets/home_artwork.dart';
import 'widgets/home_text.dart';
import 'widgets/spring_height.dart';

/// Jump Back In. Phone (N ≤ 2): 3 × 4 cells packed in rows of equal columns,
/// a signal card (rating / note, "1×2") taking two with one cover beside it;
/// landscape handset: up to 6 × 2. Wider feeds (N ≥ 3): K phone-sized lanes
/// staggered by a seeded template (home_jbi_template.dart), covers 100–128
/// following the live column, a signal card standing (1×2) or lying (2×1);
/// a shelf too short for even one templated row falls back to the packed
/// grid. A short last row keeps its column width. Those are the L row
/// preset; S / M / XL trim or grow them (home_row_presets.dart), and under
/// Home's [HomeRowsScope] edit mode's ⌟ resizes the shelf card by card.
class JumpBackInSection extends StatelessWidget {
  const JumpBackInSection({super.key, required this.cards, required this.frame, this.preset = HomeRowPreset.defaultPreset});

  /// Signal cards first, then covers in ranking order ([withJbiSignals]).
  final List<HomeWidgetCard> cards;
  final HomeFeedFrame frame;
  final HomeRowPreset preset;

  @override
  Widget build(BuildContext context) {
    final geometry = jbiGeometry(frame);
    // The L template; the other presets grow or trim it (jbiRowLadder).
    final layout = geometry.templated
        ? jbiLayout(cards, columns: geometry.columns, rows: geometry.rows, seed: jbiLayoutSeed(cards))
        : null;
    final ladder = jbiRowLadder(geometry, cards, layout);
    final track = HomeRowsScope.maybeOf(context)?.track(HomeSection.jumpBackIn);
    final grid = HomeRowsBody<JbiPresetLayout>(
      track: track,
      ladder: ladder,
      ladderKey: (geometry.columns, geometry.rows, geometry.templated, geometry.landscape, cards.map((c) => c.stableId).join('|')),
      preset: preset,
      builder: (context, stop) => switch (stop.payload) {
        JbiTemplatePreset(:final layout) => _JbiLaneGrid(
            layout: layout,
            children: [
              for (final cell in layout.cells)
                HomeRowsCard(
                  key: ValueKey(cell.card.stableId),
                  cardKey: cell.card.stableId,
                  kind: cell.piece.kind,
                  child: cell.piece.kind.isSignal
                      ? JbiSignalCard(card: cell.card, coverSide: geometry.coverSide, tall: cell.piece.kind == JbiPieceKind.tallSignal)
                      : JbiCoverCell(card: cell.card, coverSide: geometry.coverSide),
                ),
            ],
          ),
        JbiPackedPreset(:final rows) => Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              for (var r = 0; r < rows.length; r++) ...[
                if (r > 0) const SizedBox(height: HomeTokens.jbiRowGap),
                _PackedRow(row: rows[r], columns: geometry.columns, coverSide: geometry.coverSide),
              ],
            ],
          ),
      },
    );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        HomeSectionTitle(HomeStrings.of(context).sectionJumpBackIn),
        const SizedBox(height: HomeSectionMetrics.jbiSpacing),
        // A new grid (the width class moving, a re-seat) swaps in place — a
        // crossfade ghosted the page (2026-11-12) — and the height springs.
        SpringHeight(heightKey: (geometry.columns, geometry.rows, layout?.template.rows, cards.where((c) => c.expanded).length), child: grid),
      ],
    );
  }
}

/// One packed row: a cover takes one column, a signal card two (weights),
/// spacers pad a short row so every card keeps its column width; 12 apart,
/// hung from the top so a signal card's copy runs below its cover line.
class _PackedRow extends StatelessWidget {
  const _PackedRow({required this.row, required this.columns, required this.coverSide});

  final List<HomeWidgetCard> row;
  final int columns;
  final double coverSide;

  @override
  Widget build(BuildContext context) {
    final units = row.fold<int>(0, (n, c) => n + (c.expanded ? 2 : 1));
    final children = <Widget>[
      for (final card in row)
        Expanded(
          key: ValueKey(card.stableId),
          flex: card.expanded ? 2 : 1,
          child: HomeRowsCard(
            cardKey: card.stableId,
            kind: card.expanded ? JbiPieceKind.wideSignal : JbiPieceKind.cover,
            child: card.expanded
                ? JbiSignalCard(card: card, coverSide: coverSide)
                : JbiCoverCell(card: card, coverSide: coverSide),
          ),
        ),
      for (var i = units; i < columns; i++) const Expanded(child: SizedBox.shrink()),
    ];
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        for (var i = 0; i < children.length; i++) ...[
          if (i > 0) const SizedBox(width: HomeTokens.jbiColumnGap),
          children[i],
        ],
      ],
    );
  }
}

/// The templated lanes (JbiSpanGrid): every cell measured at its span's
/// width, height loose; the row pitch is the tallest cover / lying card + the
/// 16 row gap (or half a standing card's), and each piece sits at (row + its
/// lane's offset) × pitch. [children] are in [layout]'s cell order.
class _JbiLaneGrid extends MultiChildRenderObjectWidget {
  const _JbiLaneGrid({required this.layout, required super.children});

  final JbiLayout layout;

  @override
  RenderObject createRenderObject(BuildContext context) => _RenderJbiLaneGrid(layout.template, Directionality.of(context));

  @override
  void updateRenderObject(BuildContext context, _RenderJbiLaneGrid renderObject) {
    renderObject
      ..template = layout.template
      ..textDirection = Directionality.of(context);
  }
}

class _JbiLaneParentData extends ContainerBoxParentData<RenderBox> {}

class _RenderJbiLaneGrid extends RenderBox
    with ContainerRenderObjectMixin<RenderBox, _JbiLaneParentData>, RenderBoxContainerDefaultsMixin<RenderBox, _JbiLaneParentData> {
  _RenderJbiLaneGrid(this._template, this._textDirection);

  JbiTemplate _template;
  set template(JbiTemplate value) {
    if (identical(value, _template)) return;
    _template = value;
    markNeedsLayout();
  }

  TextDirection _textDirection;
  set textDirection(TextDirection value) {
    if (value == _textDirection) return;
    _textDirection = value;
    markNeedsLayout();
  }

  @override
  void setupParentData(RenderBox child) {
    if (child.parentData is! _JbiLaneParentData) child.parentData = _JbiLaneParentData();
  }

  @override
  void performLayout() {
    final width = constraints.maxWidth.isFinite ? constraints.maxWidth : constraints.minWidth;
    const gap = HomeTokens.jbiColumnGap;
    final pitchX = (width - gap * (_template.columns - 1)) / _template.columns + gap;
    double widthOf(JbiPiece p) => math.max(0.0, p.kind.columnSpan * pitchX - gap);
    final pieces = _template.pieces;
    // Covers and lying cards set the row; a standing card takes two rows at least.
    var rowHeight = 0.0;
    var child = firstChild;
    var index = 0;
    while (child != null && index < pieces.length) {
      final piece = pieces[index];
      if (piece.kind != JbiPieceKind.tallSignal) {
        child.layout(BoxConstraints.tightFor(width: widthOf(piece)), parentUsesSize: true);
        rowHeight = math.max(rowHeight, child.size.height);
      }
      child = childAfter(child);
      index++;
    }
    final tallMin = rowHeight * 2 + HomeTokens.jbiRowGap;
    var tallest = tallMin;
    child = firstChild;
    index = 0;
    while (child != null && index < pieces.length) {
      final piece = pieces[index];
      if (piece.kind == JbiPieceKind.tallSignal) {
        final w = widthOf(piece);
        child.layout(BoxConstraints(minWidth: w, maxWidth: w, minHeight: tallMin), parentUsesSize: true);
        tallest = math.max(tallest, child.size.height);
      }
      child = childAfter(child);
      index++;
    }
    final pitchY = math.max(rowHeight + HomeTokens.jbiRowGap, (tallest + HomeTokens.jbiRowGap) / 2);
    var height = 0.0;
    index = 0;
    child = firstChild;
    while (child != null && index < pieces.length) {
      final piece = pieces[index];
      final x = piece.column * pitchX;
      final y = _template.topOf(piece) * pitchY;
      final w = widthOf(piece);
      (child.parentData! as _JbiLaneParentData).offset =
          Offset(_textDirection == TextDirection.rtl ? width - x - w : x, y);
      height = math.max(height, y + child.size.height);
      child = childAfter(child);
      index++;
    }
    size = constraints.constrain(Size(width, height));
  }

  @override
  void paint(PaintingContext context, Offset offset) => defaultPaint(context, offset);

  @override
  bool hitTestChildren(BoxHitTestResult result, {required Offset position}) => defaultHitTestChildren(result, position: position);
}

/// WidgetCoverBlock: the backdrop cover (left-aligned), 5 below it the title
/// (16 / 22 SemiBold, version tag) and the subtitle (hidden when blank). The
/// whole cell is the tap target.
class JbiCoverCell extends ConsumerWidget {
  const JbiCoverCell({super.key, required this.card, required this.coverSide});

  final HomeWidgetCard card;
  final double coverSide;

  @override
  Widget build(BuildContext context, WidgetRef ref) => ElasticPress(
        onTap: () => openHomeTarget(context, ref, card.target),
        child: _CoverBlock(card: card, coverSide: coverSide),
      );
}

class _CoverBlock extends StatelessWidget {
  const _CoverBlock({required this.card, required this.coverSide});

  final HomeWidgetCard card;
  final double coverSide;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final titleStyle = text.titleMedium!.copyWith(
      fontSize: HomeJbiMetrics.titleSize,
      height: HomeJbiMetrics.titleLineHeight / HomeJbiMetrics.titleSize,
      fontWeight: FontWeight.w600,
      color: colors.onSurface,
    );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        BackdropCover(cover: card.cover, kind: card.kind, size: coverSide, requestSize: HomeCoverRequest.jbi),
        const SizedBox(height: HomeJbiMetrics.captionGap),
        VersionTitle(title: card.title, version: card.version, style: titleStyle),
        if (card.subtitle.trim().isNotEmpty) SeamText(card.subtitle, style: text.bodySmall!.copyWith(color: colors.onSurfaceVariant)),
      ],
    );
  }
}

/// The signal card (WidgetCard12): its cover block, and the copy — the score
/// tinted to the cover (the palette's base on a light surface, its brighter
/// accent on a dark one), what it rests on, the note or Memory title. Lying
/// (the phone's 1×2, a template's 2×1) the copy sits 16 to the cover's right,
/// hung from its top; [tall] (a template's 1×2) it sits 10 below. One tap
/// target, the cover's press.
class JbiSignalCard extends ConsumerWidget {
  const JbiSignalCard({super.key, required this.card, required this.coverSide, this.tall = false});

  final HomeWidgetCard card;
  final double coverSide;
  final bool tall;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final block = SizedBox(width: coverSide, child: _CoverBlock(card: card, coverSide: coverSide));
    final copy = _SignalCopy(card: card, coverSide: coverSide, commentMaxLines: HomeJbiMetrics.signalCommentMaxLines);
    return ElasticPress(
      onTap: () => openHomeTarget(context, ref, card.target),
      child: tall
          ? Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [block, const SizedBox(height: HomeJbiMetrics.tallCopyGap), copy],
            )
          : Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [block, const SizedBox(width: HomeJbiMetrics.signalCopyGap), Expanded(child: copy)],
            ),
    );
  }
}

/// The signal card's big line (Android HomeWidgetCard.shownRating): the
/// score, or N/A when there is none. Android's cards always carry a rating
/// text — formatMemoryScore gives "N/A" for no rating and ratingUnavailable
/// swaps in home_widget_rating_na — so an unrated note or memory reads N/A
/// there too; a null [JbiSignal.scoreText] is that same unrated case.
String jbiSignalHeadline(JbiSignal signal, HomeStrings strings) => signal.scoreText ?? strings.widgetRatingNa;

class _SignalCopy extends ConsumerWidget {
  const _SignalCopy({required this.card, required this.coverSide, required this.commentMaxLines});

  final HomeWidgetCard card;
  final double coverSide;
  final int commentMaxLines;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final signal = card.signal!;
    final strings = HomeStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final dark = theme.brightness == Brightness.dark;
    final basis = signal.basisDateMs != null
        ? strings.memoryDate(signal.basisDateMs!)
        : (signal.basisTracks == null ? null : strings.widgetBasisTracks(signal.basisTracks!.$1, signal.basisTracks!.$2));
    final image = resolveHomeCover(ref, context, card.cover,
        requestSize: HomeCoverRequest.jbi, side: coverSide * HomeBackdropMetrics.artworkFraction);
    const tabular = [FontFeature.tabularFigures()];
    final comment = signal.comment;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      spacing: HomeJbiMetrics.signalCopySpacing,
      children: [
        BackdropColorBuilder(
          cacheKey: image?.url,
          image: image?.image,
          fallback: dark ? colors.tertiary : colors.secondary,
          accent: dark,
          builder: (context, ink, _) => Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            spacing: HomeJbiMetrics.signalBasisGap,
            children: [
              SeamText(
                jbiSignalHeadline(signal, strings),
                style: theme.textTheme.headlineSmall!.copyWith(fontWeight: FontWeight.w700, color: ink, fontFeatures: tabular),
              ),
              if (basis != null)
                Flexible(
                  child: Padding(
                    padding: const EdgeInsets.only(bottom: HomeJbiMetrics.signalBasisLift),
                    child: SeamText(
                      basis,
                      style: theme.textTheme.labelSmall!.copyWith(
                        color: ink.withValues(alpha: HomeJbiMetrics.signalBasisAlpha),
                        fontFeatures: tabular,
                      ),
                    ),
                  ),
                ),
            ],
          ),
        ),
        if (comment != null && comment.isNotEmpty)
          SeamText(
            comment,
            style: signal.commentIsHeadline
                ? memoryWrittenTitleStyle(
                    HomeJbiMetrics.headlineSize,
                    height: HomeJbiMetrics.headlineLineHeight / HomeJbiMetrics.headlineSize,
                    color: colors.onSurface,
                  )
                : theme.textTheme.bodyMedium!.copyWith(color: colors.onSurface),
            maxLines: commentMaxLines,
          ),
      ],
    );
  }
}
