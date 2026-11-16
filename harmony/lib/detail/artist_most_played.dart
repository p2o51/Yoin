import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../app/providers.dart';
import '../data/memory/memory_store.dart';
import '../data/model/model.dart';
import '../nowplaying/haptics.dart';
import '../playback/playback_context.dart';
import '../theme/tokens.dart';
import '../ui/component/media_artwork.dart';
import '../ui/component/meta_line.dart';
import '../ui/component/seam/seam.dart';
import 'detail_listening.dart';
import 'detail_play.dart';
import 'detail_strings.dart';

/// The artist page's Most Played numbers (ArtistDetailScreen ArtistMostPlayed /
/// ArtistPlayedRow). Detail-local until the shared tokens take them.
abstract final class ArtistMostPlayedTokens {
  /// Row: 8 padding each way, 12 between rank, thumb, text and plays.
  static const double rowPadding = 8;
  static const double rowGap = 12;

  /// The rank column's minimum width.
  static const double rankMinWidth = 16;

  /// The row thumb.
  static const double thumb = 44;

  /// Header → first row.
  static const double headerGap = 6;

  /// Under the section, before the discography.
  static const double bottomGap = 32;
}

/// The user's own most played songs by this artist (local plays, so it
/// exists for every provider — it stands in for the "Popular" list Spotify no
/// longer exposes): an underlined "Most Played" with the total, then
/// rank · thumb · title / album, duration · plays. A row plays the list from
/// itself with the artist as the context.
class ArtistMostPlayed extends ConsumerWidget {
  const ArtistMostPlayed({super.key, required this.artist, required this.listening});

  final ArtistDetail artist;
  final ArtistListening listening;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = DetailStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final source = ref.watch(activeSourceProvider).value;
    void play(int index) => playFromDetail(
      DetailPlayer.widget(ref),
      listening.tracks(artistId: artist.id, artistName: artist.name),
      startIndex: index,
      context: ArtistPlaybackContext(artistId: artist.id, name: artist.name, cover: artist.coverArt),
    );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: ArtistMostPlayedTokens.rowPadding),
          child: SeamFade(
            fontSize: theme.textTheme.labelLarge?.fontSize ?? 14,
            child: Row(
              children: [
                Expanded(
                  child: Text(
                    strings.artistMostPlayed,
                    style: theme.textTheme.labelLarge?.copyWith(
                      decoration: TextDecoration.underline,
                      decorationColor: colors.onSurface,
                      fontWeight: FontWeight.w500,
                      color: colors.onSurface,
                    ),
                  ),
                ),
                if (listening.playCount > 0)
                  Text(
                    strings.artistPlays(listening.playCount),
                    style: theme.textTheme.labelMedium?.copyWith(color: colors.onSurfaceVariant),
                  ),
              ],
            ),
          ),
        ),
        const SizedBox(height: ArtistMostPlayedTokens.headerGap),
        for (final (i, row) in listening.mostPlayed.indexed)
          _PlayedRow(
            key: ValueKey(row.trackId),
            rank: i + 1,
            row: row,
            coverUrl: switch (CoverRef.fromStorageKey(row.coverArtKey)) {
              final cover? => source?.resolveCoverUrl(cover),
              null => null,
            },
            onTap: () {
              YoinHaptics.click();
              play(i);
            },
          ),
      ],
    );
  }
}

class _PlayedRow extends StatelessWidget {
  const _PlayedRow({super.key, required this.rank, required this.row, required this.coverUrl, required this.onTap});

  final int rank;
  final TrackPlays row;
  final String? coverUrl;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final strings = DetailStrings.of(context);
    final tabular = const [FontFeature.tabularFigures()];
    final duration = row.durationSec != null && row.durationSec! > 0 ? formatTrackDuration(row.durationSec) : null;
    final album = row.album.trim();
    return Material(
      type: MaterialType.transparency,
      child: InkWell(
        customBorder: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(ShapeTokens.listRow)),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(ArtistMostPlayedTokens.rowPadding),
          child: Row(
            children: [
              ConstrainedBox(
                constraints: const BoxConstraints(minWidth: ArtistMostPlayedTokens.rankMinWidth),
                child: SeamFade(
                  fontSize: theme.textTheme.labelLarge?.fontSize ?? 14,
                  child: Text(
                    '$rank',
                    textAlign: TextAlign.end,
                    style: theme.textTheme.labelLarge?.copyWith(color: colors.onSurfaceVariant, fontFeatures: tabular),
                  ),
                ),
              ),
              const SizedBox(width: ArtistMostPlayedTokens.rowGap),
              SizedBox.square(
                dimension: ArtistMostPlayedTokens.thumb,
                child: SeamDissolve(
                  child: MediaArtwork(
                    url: coverUrl,
                    fallbackIcon: YoinSymbols.musicNote,
                    radius: ShapeTokens.thumb,
                    decodeWidth: 120,
                  ),
                ),
              ),
              const SizedBox(width: ArtistMostPlayedTokens.rowGap),
              Expanded(
                child: SeamFade(
                  fontSize: theme.textTheme.titleMedium?.fontSize ?? 16,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        row.title,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: theme.textTheme.titleMedium?.copyWith(color: colors.onSurface),
                      ),
                      if (album.isNotEmpty || duration != null)
                        MetaLine(
                          style: theme.textTheme.bodySmall?.copyWith(fontFeatures: tabular),
                          groups: [
                            if (album.isNotEmpty) MetaPlain(album),
                            if (duration != null) album.isNotEmpty ? MetaMuted(duration) : MetaPlain(duration),
                          ],
                        ),
                    ],
                  ),
                ),
              ),
              const SizedBox(width: ArtistMostPlayedTokens.rowGap),
              SeamFade(
                fontSize: theme.textTheme.labelLarge?.fontSize ?? 14,
                child: Text(
                  strings.artistPlays(row.playCount),
                  style: theme.textTheme.labelLarge?.copyWith(color: colors.onSurfaceVariant, fontFeatures: tabular),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
