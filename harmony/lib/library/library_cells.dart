import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../app/providers.dart';
import '../data/model/model.dart';
import '../detail/detail_track_menu.dart';
import '../detail/playback_projection.dart';
import '../theme/tokens.dart';
import '../ui/component/elastic_press.dart';
import '../ui/component/media_artwork.dart';
import '../ui/component/meta_line.dart';
import '../ui/component/song_list_item.dart';
import 'library_strings.dart';

/// Library covers are always requested at 256 px (Android
/// resolveSubsonicCoverUrl(id, size = 256)) and decoded at the cell size.
const libraryCoverRequestSize = 256;

/// The 256 px cover URL for [cover], or null.
String? libraryCoverUrl(WidgetRef ref, CoverRef? cover) {
  if (cover == null) return null;
  return ref.watch(activeSourceProvider).value?.resolveCoverUrl(cover, size: libraryCoverRequestSize);
}

/// Grid columns: Compact 3; wider windows floor((w − 64 + 12) / (96 + 12)).
int libraryGridColumns(double width) {
  if (width < 600) return 3;
  return ((width - 64 + LibraryTokens.gridGutterH) / (96 + LibraryTokens.gridGutterH)).floor().clamp(3, 16);
}

/// Album / playlist / artist grid cell height for a cell [cellWidth] wide.
double libraryCellExtent(double cellWidth, TextScaler scaler) =>
    cellWidth + LibraryTokens.coverToTitle + scaler.scale(20) + scaler.scale(16) + 2;

/// Album grid cell: cover (Cover shape), title + version tag, artist.
class AlbumGridCell extends ConsumerWidget {
  const AlbumGridCell({super.key, required this.album, required this.onTap});

  final Album album;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context, WidgetRef ref) => _SquareCell(
    url: libraryCoverUrl(ref, album.coverArt),
    fallbackIcon: YoinSymbols.album,
    title: albumTitleWithoutVersion(album.name, album.version),
    version: album.version,
    subtitle: album.artist ?? '',
    onTap: onTap,
  );
}

/// Playlist grid cell: subtitle = owner, else "Playlist".
class PlaylistGridCell extends ConsumerWidget {
  const PlaylistGridCell({super.key, required this.playlist, required this.onTap});

  final Playlist playlist;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final owner = playlist.owner?.trim();
    return _SquareCell(
      url: libraryCoverUrl(ref, playlist.coverArt),
      fallbackIcon: YoinSymbols.playlist,
      title: playlist.name,
      subtitle: owner == null || owner.isEmpty ? LibraryStrings.of(context).playlistFallback : owner,
      onTap: onTap,
    );
  }
}

class _SquareCell extends StatelessWidget {
  const _SquareCell({
    required this.url,
    required this.fallbackIcon,
    required this.title,
    required this.subtitle,
    required this.onTap,
    this.version,
  });

  final String? url;
  final IconData fallbackIcon;
  final String title;
  final String? version;
  final String subtitle;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final dpr = MediaQuery.devicePixelRatioOf(context);
    return ElasticPress(
      onTap: onTap,
      semanticsLabel: title,
      child: LayoutBuilder(
        builder: (context, constraints) => Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox.square(
              dimension: constraints.maxWidth,
              child: MediaArtwork(
                url: url,
                fallbackIcon: fallbackIcon,
                radius: ShapeTokens.cover,
                decodeWidth: (constraints.maxWidth * dpr).round(),
              ),
            ),
            const SizedBox(height: LibraryTokens.coverToTitle),
            Row(
              children: [
                Flexible(
                  child: Text(title, maxLines: 1, overflow: TextOverflow.ellipsis, style: theme.textTheme.titleSmall),
                ),
                if (version != null) ...[const SizedBox(width: 4), Flexible(child: AlbumVersionTag(version!))],
              ],
            ),
            Text(
              subtitle,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
            ),
          ],
        ),
      ),
    );
  }
}

/// Artist grid cell: round cover, centred name, "%d albums".
class ArtistGridCell extends ConsumerWidget {
  const ArtistGridCell({super.key, required this.artist, required this.onTap});

  final Artist artist;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final dpr = MediaQuery.devicePixelRatioOf(context);
    final url = libraryCoverUrl(ref, artist.coverArt);
    return ElasticPress(
      onTap: onTap,
      semanticsLabel: artist.name,
      child: LayoutBuilder(
        builder: (context, constraints) => Column(
          children: [
            SizedBox.square(
              dimension: constraints.maxWidth,
              child: MediaArtwork(
                url: url,
                fallbackIcon: YoinSymbols.artist,
                circle: true,
                decodeWidth: (constraints.maxWidth * dpr).round(),
              ),
            ),
            const SizedBox(height: LibraryTokens.coverToTitle),
            Text(
              artist.name,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              textAlign: TextAlign.center,
              style: theme.textTheme.titleSmall,
            ),
            if (artist.albumCount != null)
              Text(
                LibraryStrings.of(context).artistGridAlbumCount(artist.albumCount!),
                maxLines: 1,
                textAlign: TextAlign.center,
                style: theme.textTheme.bodySmall?.copyWith(fontSize: 12, color: theme.colorScheme.onSurfaceVariant),
              ),
          ],
        ),
      ),
    );
  }
}

/// A list row with a 48dp leading artwork (Playlists tab, Favorites).
class LibraryListRow extends StatelessWidget {
  const LibraryListRow({
    super.key,
    required this.url,
    required this.fallbackIcon,
    required this.title,
    required this.onTap,
    this.circle = false,
    this.version,
    this.meta = const [],
    this.trailing,
  });

  final String? url;
  final IconData fallbackIcon;
  final bool circle;
  final String title;
  final String? version;
  final List<MetaGroup> meta;
  final Widget? trailing;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final dpr = MediaQuery.devicePixelRatioOf(context);
    return Material(
      type: MaterialType.transparency,
      child: InkWell(
        onTap: onTap,
        customBorder: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(ShapeTokens.listRow)),
        child: Padding(
          padding: const EdgeInsets.symmetric(
            horizontal: LibraryTokens.rowPaddingH,
            vertical: LibraryTokens.rowPaddingV,
          ),
          child: Row(
            children: [
              SizedBox.square(
                dimension: LibraryTokens.rowThumb,
                child: MediaArtwork(
                  url: url,
                  fallbackIcon: fallbackIcon,
                  circle: circle,
                  radius: ShapeTokens.thumb,
                  decodeWidth: (LibraryTokens.rowThumb * dpr).round(),
                ),
              ),
              const SizedBox(width: LibraryTokens.rowGap),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Flexible(
                          child: Text(
                            title,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: theme.textTheme.titleSmall,
                          ),
                        ),
                        if (version != null) ...[const SizedBox(width: 4), Flexible(child: AlbumVersionTag(version!))],
                      ],
                    ),
                    if (meta.isNotEmpty) MetaLine(groups: meta),
                  ],
                ),
              ),
              if (trailing != null) ...[const SizedBox(width: LibraryTokens.rowGap), trailing!],
            ],
          ),
        ),
      ),
    );
  }
}

/// The trailing "%d albums" pill on Favorites artist rows (ExpressiveMetaPill).
class LibraryMetaPill extends StatelessWidget {
  const LibraryMetaPill(this.text, {super.key});

  final String text;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return DecoratedBox(
      decoration: ShapeDecoration(
        color: colors.surfaceContainerHighest.withValues(alpha: LibraryTokens.metaPillAlpha),
        shape: const StadiumBorder(),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(
          horizontal: LibraryTokens.metaPillPaddingH,
          vertical: LibraryTokens.metaPillPaddingV,
        ),
        child: Text(text, style: Theme.of(context).textTheme.labelMedium?.copyWith(color: colors.onSurfaceVariant)),
      ),
    );
  }
}

/// A song row bound to the playback projection (current / playing).
class LibrarySongRow extends ConsumerWidget {
  const LibrarySongRow({super.key, required this.track, required this.onTap, this.onLongPress});

  final Track track;
  final VoidCallback onTap;

  /// Opens the song sheet; null = [openTrackMenu] with this window's navigation.
  final VoidCallback? onLongPress;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final play = ref.watch(trackPlayStateProvider(track.id));
    final cover = track.coverArt ?? (track.albumId == null ? null : CoverSourceRelative(track.albumId!.rawId));
    return SongListItem(
      title: track.title ?? '',
      coverUrl: libraryCoverUrl(ref, cover),
      artist: track.artist,
      album: track.album,
      durationSec: track.durationSec,
      isCurrent: play.current,
      isPlaying: play.playing,
      decodeWidth: (LibraryTokens.rowThumb * MediaQuery.devicePixelRatioOf(context)).round(),
      nowPlayingLabel: LibraryStrings.of(context).cdNowPlaying,
      onTap: onTap,
      onLongPress: onLongPress ?? () => openTrackMenu(context, track),
    );
  }
}

/// Section header (Favorites, search): labelLarge, padding 16 / 18 / 6.
class LibrarySectionHeader extends StatelessWidget {
  const LibrarySectionHeader(this.text, {super.key});

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsetsDirectional.only(
      start: 16,
      end: 16,
      top: SearchTokens.sectionHeaderTop,
      bottom: SearchTokens.sectionHeaderBottom,
    ),
    child: Text(
      text,
      style: Theme.of(context).textTheme.labelLarge?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant),
    ),
  );
}
