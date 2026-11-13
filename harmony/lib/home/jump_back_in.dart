import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../theme/tokens.dart';
import 'home_actions.dart';
import 'home_header.dart';
import 'home_metrics.dart';
import 'home_selection.dart';
import 'home_strings.dart';
import 'home_ui_state.dart';
import 'widgets/elastic_press.dart';
import 'widgets/home_artwork.dart';
import 'widgets/home_text.dart';

/// Jump Back In: covers in rows of equal columns (phone: 3 × 4, 12 cells).
/// A short last row keeps its column width.
class JumpBackInSection extends StatelessWidget {
  const JumpBackInSection({super.key, required this.cards, required this.frame});

  final List<HomeWidgetCard> cards;
  final HomeFeedFrame frame;

  @override
  Widget build(BuildContext context) {
    final geometry = jbiGeometry(frame);
    final shown = cards.take(geometry.cells).toList();
    final rows = <List<HomeWidgetCard>>[
      for (var i = 0; i < shown.length; i += geometry.columns) shown.sublist(i, math.min(i + geometry.columns, shown.length)),
    ];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        HomeSectionTitle(HomeStrings.of(context).sectionJumpBackIn),
        const SizedBox(height: HomeSectionMetrics.jbiSpacing),
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
