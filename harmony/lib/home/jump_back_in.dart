import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../theme/tokens.dart';
import 'home_actions.dart';
import 'home_header.dart';
import 'home_jbi_template.dart';
import 'home_metrics.dart';
import 'home_selection.dart';
import 'home_strings.dart';
import 'home_ui_state.dart';
import 'widgets/elastic_press.dart';
import 'widgets/home_artwork.dart';
import 'widgets/home_text.dart';
import 'widgets/spring_height.dart';

/// Jump Back In. Phone (N ≤ 2): 3 × 4 covers in rows of equal columns;
/// landscape handset: up to 6 × 2. Wider feeds (N ≥ 3): K phone-sized lanes
/// staggered by a seeded template (home_jbi_template.dart), covers 100–128
/// following the live column; a shelf too short for even one templated row
/// falls back to the plain K-column grid. A short last row keeps its column width.
class JumpBackInSection extends StatelessWidget {
  const JumpBackInSection({super.key, required this.cards, required this.frame});

  final List<HomeWidgetCard> cards;
  final HomeFeedFrame frame;

  @override
  Widget build(BuildContext context) {
    final geometry = jbiGeometry(frame);
    final layout = geometry.templated
        ? jbiLayout(cards, columns: geometry.columns, rows: geometry.rows, seed: jbiLayoutSeed(cards))
        : null;
    final Widget grid;
    if (layout != null) {
      grid = _JbiLaneGrid(
        layout: layout,
        children: [
          for (final cell in layout.cells) JbiCoverCell(key: ValueKey(cell.card.stableId), card: cell.card, coverSide: geometry.coverSide),
        ],
      );
    } else {
      final shown = cards.take(geometry.cells).toList();
      final rows = <List<HomeWidgetCard>>[
        for (var i = 0; i < shown.length; i += geometry.columns) shown.sublist(i, math.min(i + geometry.columns, shown.length)),
      ];
      grid = Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          for (var r = 0; r < rows.length; r++) ...[
            if (r > 0) const SizedBox(height: HomeTokens.jbiRowGap),
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                for (var c = 0; c < geometry.columns; c++) ...[
                  if (c > 0) const SizedBox(width: HomeTokens.jbiColumnGap),
                  Expanded(
                    child: c < rows[r].length
                        ? JbiCoverCell(key: ValueKey(rows[r][c].stableId), card: rows[r][c], coverSide: geometry.coverSide)
                        : const SizedBox.shrink(),
                  ),
                ],
              ],
            ),
          ],
        ],
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        HomeSectionTitle(HomeStrings.of(context).sectionJumpBackIn),
        const SizedBox(height: HomeSectionMetrics.jbiSpacing),
        // A new grid (the width class moving, a re-seat) swaps in place — a
        // crossfade ghosted the page (2026-11-12) — and the height springs.
        SpringHeight(heightKey: (geometry.columns, geometry.rows, layout?.template.rows), child: grid),
      ],
    );
  }
}

/// The templated lanes (JbiSpanGrid, covers only): every cell [JbiCoverCell]
/// measured at its column's width, height loose; the row pitch is the
/// tallest cell + the 16 row gap, and each cover sits at (row + its lane's
/// offset) × pitch. [children] are in [layout]'s cell order.
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
    final cellWidth = math.max(0.0, pitchX - gap);
    final pieces = _template.pieces;
    var rowHeight = 0.0;
    var child = firstChild;
    while (child != null) {
      child.layout(BoxConstraints.tightFor(width: cellWidth), parentUsesSize: true);
      rowHeight = math.max(rowHeight, child.size.height);
      child = childAfter(child);
    }
    final pitchY = rowHeight + HomeTokens.jbiRowGap;
    var height = 0.0;
    var index = 0;
    child = firstChild;
    while (child != null && index < pieces.length) {
      final piece = pieces[index];
      final x = piece.column * pitchX;
      final y = _template.topOf(piece) * pitchY;
      (child.parentData! as _JbiLaneParentData).offset =
          Offset(_textDirection == TextDirection.rtl ? width - x - cellWidth : x, y);
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
  Widget build(BuildContext context, WidgetRef ref) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final titleStyle = text.titleMedium!.copyWith(
      fontSize: HomeJbiMetrics.titleSize,
      height: HomeJbiMetrics.titleLineHeight / HomeJbiMetrics.titleSize,
      fontWeight: FontWeight.w600,
      color: colors.onSurface,
    );
    return ElasticPress(
      onTap: () => openHomeTarget(context, ref, card.target),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          BackdropCover(cover: card.cover, kind: card.kind, size: coverSide, requestSize: HomeCoverRequest.jbi),
          const SizedBox(height: HomeJbiMetrics.captionGap),
          VersionTitle(title: card.title, version: card.version, style: titleStyle),
          if (card.subtitle.trim().isNotEmpty)
            SeamText(card.subtitle, style: text.bodySmall!.copyWith(color: colors.onSurfaceVariant)),
        ],
      ),
    );
  }
}
