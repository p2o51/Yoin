import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../data/model/model.dart';
import '../playback/playback_context.dart';
import '../theme/tokens.dart';
import '../ui/component/cover_art.dart';
import 'now_playing_view_model.dart';
import 'nowplaying_strings.dart';

/// One section of the queue sheet (QueueSheet.kt queueSections).
@immutable
final class QueueSection {
  const QueueSection(this.label, this.entries);
  final String label;

  /// Queue indices (engine order) in display order.
  final List<int> entries;
}

/// The context's name for the header and "Next from: …" (null = none).
String? playContextName(PlaybackContext context) => switch (context) {
  AlbumPlaybackContext(:final name) => name,
  PlaylistPlaybackContext(:final name) => name,
  ArtistPlaybackContext(:final name) => name,
  NoPlaybackContext() || FavoritesPlaybackContext() => null,
};

/// Spotify order: Now playing, Next in queue (user-added: none until queue
/// editing lands), Next from (context) / Next up with the rest in play order.
/// Played tracks are left out.
List<QueueSection> queueSections({
  required int currentIndex,
  required List<int> upcoming,
  required String nowLabel,
  required String nextLabel,
}) => [
  if (currentIndex >= 0) QueueSection(nowLabel, [currentIndex]),
  if (upcoming.isNotEmpty) QueueSection(nextLabel, upcoming),
];

/// The queue sheet's content (QueueSheet.kt): header, then sections of
/// 64 dp rows; tapping a row skips to it and closes the sheet.
class QueueSheet extends ConsumerWidget {
  const QueueSheet({super.key, required this.onSkip});

  final ValueChanged<int> onSkip;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(nowPlayingViewModelProvider);
    final strings = NowPlayingStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    if (state is! NowPlayingContent) return const SizedBox(height: 120);
    final name = playContextName(state.context);
    final sections = queueSections(
      currentIndex: state.currentIndex,
      upcoming: state.upcoming,
      nowLabel: strings.queueSectionNow,
      nextLabel: name == null ? strings.queueNextUp : strings.queueNextFrom(name),
    );
    final navBottom = MediaQuery.paddingOf(context).bottom;
    return CustomScrollView(
      shrinkWrap: true,
      slivers: [
        SliverToBoxAdapter(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(24, 4, 24, 4),
            child: name == null
                ? Text(strings.queueTitle, style: text.titleLarge, maxLines: 1, overflow: TextOverflow.ellipsis)
                : Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(strings.queuePlayingFrom, style: text.labelMedium!.copyWith(color: colors.onSurfaceVariant)),
                      Text(name, style: text.titleLarge, maxLines: 1, overflow: TextOverflow.ellipsis),
                    ],
                  ),
          ),
        ),
        for (final section in sections) ...[
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(24, 18, 12, 2),
              child: Text(
                section.label,
                style: text.titleSmall!.copyWith(color: colors.onSurface, fontWeight: FontWeight.w600),
              ),
            ),
          ),
          SliverList.builder(
            itemCount: section.entries.length,
            itemBuilder: (context, i) {
              final index = section.entries[i];
              final current = index == state.currentIndex;
              return _QueueRow(
                track: state.queue[index],
                current: current,
                playing: current && state.isPlaying,
                onTap: current ? null : () => onSkip(index),
              );
            },
          ),
        ],
        SliverToBoxAdapter(child: SizedBox(height: 16 + navBottom)),
      ],
    );
  }
}

class _QueueRow extends StatelessWidget {
  const _QueueRow({required this.track, required this.current, required this.playing, required this.onTap});

  final Track track;
  final bool current;
  final bool playing;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final strings = NowPlayingStrings.of(context);
    return Material(
      color: colors.surfaceContainer,
      child: InkWell(
        onTap: onTap,
        child: SizedBox(
          height: 64,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 24),
            child: Row(
              children: [
                track.coverArt == null
                    ? Container(
                        width: 48,
                        height: 48,
                        decoration: BoxDecoration(
                          color: colors.surfaceContainerHighest,
                          borderRadius: BorderRadius.circular(ShapeTokens.thumb),
                        ),
                        child: Icon(YoinSymbols.musicNote, color: colors.onSurfaceVariant),
                      )
                    : CoverArt(cover: track.coverArt, size: 48, radius: ShapeTokens.thumb),
                const SizedBox(width: 14),
                Expanded(
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        track.title ?? '',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: text.bodyLarge!.copyWith(
                          fontWeight: current ? FontWeight.w600 : FontWeight.w500,
                          color: current ? colors.primary : colors.onSurface,
                        ),
                      ),
                      if (track.artist != null)
                        Text(
                          track.artist!,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: text.bodyMedium!.copyWith(color: colors.onSurfaceVariant),
                        ),
                    ],
                  ),
                ),
                if (current)
                  Semantics(
                    label: playing ? strings.queueCdPlaying : strings.queueCdPaused,
                    child: Icon(YoinSymbols.equalizer, size: 22, color: colors.primary),
                  ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// Opens the queue sheet (Standard-role modal sheet, container
/// surfaceContainer) on [navigatorContext] — Now Playing's own navigator, so
/// the sheet sits above the player and system back reaches it first.
Future<void> showQueueSheet(BuildContext navigatorContext, {required ValueChanged<int> onSkip}) =>
    showModalBottomSheet<void>(
      context: navigatorContext,
      useSafeArea: false,
      isScrollControlled: true,
      showDragHandle: true,
      backgroundColor: Theme.of(navigatorContext).colorScheme.surfaceContainer,
      builder: (sheetContext) => QueueSheet(
        onSkip: (index) {
          onSkip(index);
          Navigator.of(sheetContext).pop();
        },
      ),
    );
