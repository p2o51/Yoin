import 'package:flutter/material.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../../data/source/music_source.dart';
import '../../library/library_strings.dart';
import '../../theme/tokens.dart';
import 'spring_cross_fade.dart';
import 'yoin_loading_indicator.dart';

/// Add to library on a catalogue song (Android TrackLibraryButton),
/// deliberately not the favourite heart: in the library (primary, done),
/// waiting for the service (tap asks again), or add. A spinner while it works.
class TrackLibraryButton extends StatelessWidget {
  const TrackLibraryButton({super.key, required this.membership, required this.onPressed, this.working = false});

  final LibraryMembership membership;
  final VoidCallback onPressed;
  final bool working;

  @override
  Widget build(BuildContext context) {
    final strings = LibraryStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final added = membership == LibraryMembership.added;
    final (IconData icon, String label) = switch (membership) {
      LibraryMembership.added => (YoinSymbols.libraryAdded, strings.cdLibraryAdded),
      LibraryMembership.pending => (YoinSymbols.refresh, strings.cdLibraryPending),
      _ => (YoinSymbols.libraryAdd, strings.cdLibraryAdd),
    };
    return IconButton(
      tooltip: label,
      onPressed: working || added ? null : onPressed,
      icon: SpringCrossFade(
        childKey: working ? 'working' : membership,
        fit: StackFit.loose,
        child: working
            ? const YoinLoadingIndicator(size: LibraryTokens.libraryAddProgress, contained: false)
            : Icon(icon, color: added ? colors.primary : colors.onSurfaceVariant),
      ),
    );
  }
}
