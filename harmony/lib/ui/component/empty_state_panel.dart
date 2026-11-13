import 'package:flutter/material.dart';

import '../../theme/tokens.dart';

/// EmptyState (LibraryScreen.kt EmptyState over ExpressiveSectionPanel): a
/// centred card (surfaceContainerHighest @ 0.72, card corner) holding one
/// bodyLarge line. Vertically scrollable with [physics] so a pull-to-refresh
/// still works over it.
class EmptyStatePanel extends StatelessWidget {
  const EmptyStatePanel({super.key, required this.message, this.physics});

  final String message;
  final ScrollPhysics? physics;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return LayoutBuilder(
      builder: (context, constraints) => SingleChildScrollView(
        physics: physics ?? const AlwaysScrollableScrollPhysics(parent: ClampingScrollPhysics()),
        child: ConstrainedBox(
          constraints: BoxConstraints(minHeight: constraints.maxHeight),
          child: Center(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: DecoratedBox(
                decoration: ShapeDecoration(
                  color: colors.surfaceContainerHighest.withValues(alpha: LibraryTokens.emptyStateAlpha),
                  shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(ShapeTokens.card)),
                ),
                child: Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: LibraryTokens.emptyStatePaddingH,
                    vertical: LibraryTokens.emptyStatePaddingV,
                  ),
                  child: Text(
                    message,
                    textAlign: TextAlign.center,
                    style: Theme.of(context).textTheme.bodyLarge?.copyWith(color: colors.onSurfaceVariant),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
