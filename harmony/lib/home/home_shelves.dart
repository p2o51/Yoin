import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/model/model.dart';
import '../theme/tokens.dart';
import 'home_actions.dart';
import 'home_header.dart';
import 'home_metrics.dart';
import 'home_selection.dart';
import 'home_strings.dart';
import 'home_ui_state.dart';
import 'widgets/elastic_press.dart';
import 'widgets/home_artwork.dart';
import 'widgets/home_motion.dart';
import 'widgets/home_text.dart';

/// A shelf card's height: the 82 cover, 6, then two caption lines.
double _cardHeight(BuildContext context) {
  final scaler = MediaQuery.textScalerOf(context);
  return HomeTokens.recentlyAddedAlbumCover +
      HomeShelfMetrics.albumCardCaptionGap +
      // The name line holds a version tag (labelMedium + its padding) at most.
      math.max(scaler.scale(TypeTokens.bodySmall.lineHeight), scaler.scale(TypeTokens.labelMedium.lineHeight) + 2 * HomeVersionTagMetrics.paddingV) +
      scaler.scale(TypeTokens.labelSmall.lineHeight);
}

EdgeInsetsDirectional _shelfPadding(HomeFeedFrame frame) => EdgeInsetsDirectional.only(start: frame.start, end: frame.end);

/// The full-bleed section frame: the title inside the page margins, the shelf to the edges.
class _ShelfSection extends StatelessWidget {
  const _ShelfSection({required this.title, required this.frame, required this.gap, required this.shelf});

  final String title;
  final HomeFeedFrame frame;
  final double gap;
  final Widget shelf;

  @override
  Widget build(BuildContext context) => Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(padding: _shelfPadding(frame), child: HomeSectionTitle(title)),
          SizedBox(height: gap),
          shelf,
        ],
      );
}

/// Recently Added: one shelf (no edge fade) whose first item is a 2 × 2
/// track grid, then album cards. Either half alone takes the full width.
class RecentlyAddedSection extends StatelessWidget {
  const RecentlyAddedSection({super.key, required this.tracks, required this.albums, required this.frame});

  final List<Track> tracks;
  final List<Album> albums;
  final HomeFeedFrame frame;

  @override
  Widget build(BuildContext context) {
    final shownTracks = tracks.take(HomeShelfMetrics.phoneTrackRows * HomeShelfMetrics.phoneTrackColumns).toList();
    final shownAlbums = albums.take(HomeShelfMetrics.phoneAlbums).toList();
    final columns = recentlyAddedGridColumns(shownTracks.length);
    final rows = (shownTracks.length / columns).ceil();
    final scaler = MediaQuery.textScalerOf(context);
    final tileHeight = math.max(HomeTokens.recentlyAddedTrackCover,
        scaler.scale(TypeTokens.bodyMedium.lineHeight) + scaler.scale(TypeTokens.labelSmall.lineHeight));
    final gridHeight = rows * tileHeight + math.max(0, rows - 1) * HomeShelfMetrics.trackGridRowGap;
    final gridWidth = shownAlbums.isEmpty ? frame.content : recentlyAddedGridWidth(frame);
    final height = math.max(shownTracks.isEmpty ? 0.0 : gridHeight, shownAlbums.isEmpty ? 0.0 : _cardHeight(context));
    final hasGrid = shownTracks.isNotEmpty;
    return _ShelfSection(
      title: HomeStrings.of(context).sectionRecentlyAdded,
      frame: frame,
      gap: HomeSectionMetrics.shelfSpacing,
      shelf: EdgeFadeShelf(
        fade: false,
        height: height,
        padding: _shelfPadding(frame),
        itemCount: shownAlbums.length + (hasGrid ? 1 : 0),
        itemBuilder: (context, i) {
          if (hasGrid && i == 0) {
            return Align(
              alignment: Alignment.topCenter,
              child: SizedBox(width: gridWidth, child: _TrackGrid(tracks: shownTracks, columns: columns, tileHeight: tileHeight)),
            );
          }
          final album = shownAlbums[i - (hasGrid ? 1 : 0)];
          return Align(alignment: Alignment.topCenter, child: AlbumShelfCard(key: ValueKey(album.id), album: album));
        },
      ),
    );
  }
}

class _TrackGrid extends StatelessWidget {
  const _TrackGrid({required this.tracks, required this.columns, required this.tileHeight});

  final List<Track> tracks;
  final int columns;
  final double tileHeight;

  @override
  Widget build(BuildContext context) {
    final rows = <List<Track>>[
      for (var i = 0; i < tracks.length; i += columns) tracks.sublist(i, math.min(i + columns, tracks.length)),
    ];
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        for (var r = 0; r < rows.length; r++) ...[
          if (r > 0) const SizedBox(height: HomeShelfMetrics.trackGridRowGap),
          SizedBox(
            height: tileHeight,
            child: Row(
              children: [
                for (var c = 0; c < rows[r].length; c++) ...[
                  if (c > 0) const SizedBox(width: HomeTokens.recentlyAddedTileGap),
                  Expanded(child: TrackTile(key: ValueKey(rows[r][c].id), track: rows[r][c])),
                ],
                if (rows[r].length < columns) ...[
                  const SizedBox(width: HomeTokens.recentlyAddedTileGap),
                  Spacer(flex: columns - rows[r].length),
                ],
              ],
            ),
          ),
        ],
      ],
    );
  }
}

/// A Recently Added track: 52 cover, title (13 Medium) and artist. Tap plays it alone.
class TrackTile extends ConsumerWidget {
  const TrackTile({super.key, required this.track});
  final Track track;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final image = resolveHomeCover(ref, context, trackCover(track),
        requestSize: HomeCoverRequest.standard, side: HomeTokens.recentlyAddedTrackCover);
    final artist = track.artist ?? '';
    return ElasticPress(
      onTap: () => openHomeTarget(context, ref, HomePlaySong(track)),
      child: Row(
        spacing: HomeTokens.recentlyAddedTileGap,
        children: [
          HomeArtwork(image: image, kind: HomeCardKind.album, size: HomeTokens.recentlyAddedTrackCover),
          Expanded(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                SeamText(track.title ?? '',
                    style: text.bodyMedium!.copyWith(fontSize: HomeShelfMetrics.trackTitleSize, fontWeight: FontWeight.w500, color: colors.onSurface)),
                if (artist.isNotEmpty) SeamText(artist, style: text.labelSmall!.copyWith(color: colors.onSurfaceVariant)),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// An 82-wide album card: Bun backdrop cover, name (+ version tag), artist.
class AlbumShelfCard extends ConsumerWidget {
  const AlbumShelfCard({super.key, required this.album});
  final Album album;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final card = albumCard(album);
    return _ShelfCard(
      card: card,
      caption: card.subtitle,
      onTap: () => openHomeTarget(context, ref, card.target),
    );
  }
}

/// An 82-wide playlist card: Ghostish backdrop cover, name, "N songs".
class PlaylistShelfCard extends ConsumerWidget {
  const PlaylistShelfCard({super.key, required this.playlist});
  final Playlist playlist;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final card = playlistCard(playlist);
    final count = playlist.songCount ?? 0;
    return _ShelfCard(
      card: card,
      caption: count > 0 ? HomeStrings.of(context).playlistSongs(count) : '',
      onTap: () => openHomeTarget(context, ref, card.target),
    );
  }
}

class _ShelfCard extends StatelessWidget {
  const _ShelfCard({required this.card, required this.caption, required this.onTap});

  final HomeWidgetCard card;
  final String caption;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    return SizedBox(
      width: HomeTokens.recentlyAddedAlbumCover,
      child: ElasticPress(
        onTap: onTap,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            BackdropCover(cover: card.cover, kind: card.kind, size: HomeTokens.recentlyAddedAlbumCover),
            const SizedBox(height: HomeShelfMetrics.albumCardCaptionGap),
            VersionTitle(
              title: card.title,
              version: card.version,
              style: text.bodySmall!.copyWith(fontWeight: FontWeight.w500, color: colors.onSurface),
            ),
            if (caption.isNotEmpty) SeamText(caption, style: text.labelSmall!.copyWith(color: colors.onSurfaceVariant)),
          ],
        ),
      ),
    );
  }
}

/// Recently Played: album cards, with the scroll-aware edge fade.
class RecentlyPlayedSection extends StatelessWidget {
  const RecentlyPlayedSection({super.key, required this.albums, required this.frame});

  final List<Album> albums;
  final HomeFeedFrame frame;

  @override
  Widget build(BuildContext context) => _ShelfSection(
        title: HomeStrings.of(context).sectionRecentlyPlayed,
        frame: frame,
        gap: HomeSectionMetrics.shelfSpacing,
        shelf: EdgeFadeShelf(
          height: _cardHeight(context),
          padding: _shelfPadding(frame),
          itemCount: albums.length,
          itemBuilder: (context, i) => Align(alignment: Alignment.topCenter, child: AlbumShelfCard(key: ValueKey(albums[i].id), album: albums[i])),
        ),
      );
}

/// Your Playlists: playlist cards, with the scroll-aware edge fade.
class YourPlaylistsSection extends StatelessWidget {
  const YourPlaylistsSection({super.key, required this.playlists, required this.frame});

  final List<Playlist> playlists;
  final HomeFeedFrame frame;

  @override
  Widget build(BuildContext context) => _ShelfSection(
        title: HomeStrings.of(context).sectionYourPlaylists,
        frame: frame,
        gap: HomeSectionMetrics.shelfSpacing,
        shelf: EdgeFadeShelf(
          height: _cardHeight(context),
          padding: _shelfPadding(frame),
          itemCount: playlists.length,
          itemBuilder: (context, i) =>
              Align(alignment: Alignment.topCenter, child: PlaylistShelfCard(key: ValueKey(playlists[i].id), playlist: playlists[i])),
        ),
      );
}
