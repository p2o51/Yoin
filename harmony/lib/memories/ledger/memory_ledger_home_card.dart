/// Home's Memories grid card (Android ui/home/HomeMemoryLedger.kt
/// MemoryLedgerSection, owner 2026-11-08 "B3"): how much you've written, and
/// the calendar wall of it, a cell a month, week or day as you write more. A
/// tap opens All Memories. Home shows it under its own section title
/// (home_section_memory_grid) and hides it while the ledger is empty.
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/providers.dart';
import '../../nowplaying/haptics.dart';
import '../../theme/theme.dart';
import '../../theme/tokens.dart';
import 'memory_grid_view.dart';
import 'memory_ledger_screen.dart';
import 'memory_ledger_strings.dart';
import 'memory_ledger_view_model.dart';

class MemoryLedgerHomeCard extends ConsumerStatefulWidget {
  const MemoryLedgerHomeCard({super.key, required this.state, this.onOpen});

  final MemoryLedgerState state;

  /// Defaults to pushing All Memories.
  final VoidCallback? onOpen;

  @override
  ConsumerState<MemoryLedgerHomeCard> createState() => _MemoryLedgerHomeCardState();
}

class _MemoryLedgerHomeCardState extends ConsumerState<MemoryLedgerHomeCard> with TickerProviderStateMixin {
  late final LedgerColorBook _colors = LedgerColorBook(vsync: this);

  @override
  void dispose() {
    _colors.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final strings = MemoryLedgerStrings.of(context);
    final theme = Theme.of(context);
    final state = widget.state;
    final source = ref.watch(activeSourceProvider).value;
    for (final album in ledgerAlbumsIn(state.grid.cells)) {
      final cover = album.cover;
      _colors.request(album, cover == null ? null : source?.resolveCoverUrl(cover, size: 200));
    }
    // What you wrote leads; with only scores so far, the scores.
    final wrote = state.stats.writings > 0;
    final count = wrote ? state.stats.writings : state.entries.length;
    return ConstrainedBox(
      // A tablet's wide feed would blow a month's quads up past a cover; the card stays phone-wide.
      constraints: const BoxConstraints(maxWidth: MemoryLedgerTokens.homeCardMax),
      child: Semantics(
        button: true,
        label: strings.open,
        child: Material(
          color: theme.colorScheme.surfaceContainerLow,
          borderRadius: BorderRadius.circular(MemoryLedgerTokens.homeCardCorner),
          clipBehavior: Clip.antiAlias,
          child: InkWell(
            onTap: () {
              YoinHaptics.click();
              (widget.onOpen ?? () => MemoryLedgerScreen.open(context))();
            },
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.end,
                    children: [
                      Text(
                        '$count',
                        style: gsf(
                          MemoryLedgerTokens.statFigure,
                          weight: 600,
                          height: 1.25,
                          letterSpacing: 0,
                        ).copyWith(color: theme.colorScheme.onSurface),
                      ),
                      const SizedBox(width: 6),
                      Padding(
                        padding: const EdgeInsets.only(bottom: 3),
                        child: Text(
                          wrote ? strings.writingsLabel(count) : strings.scoresLabel(count),
                          style: theme.textTheme.labelMedium!.copyWith(color: theme.colorScheme.onSurfaceVariant),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  ExcludeSemantics(
                    child: MemoryGridView(grid: state.grid, colors: _colors, placement: MemoryGridPlacement.home),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
