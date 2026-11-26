import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../app/providers.dart';
import '../data/model/model.dart';
import '../data/release/release_marks.dart';
import '../detail/detail_track_menu.dart';
import '../detail/playback_projection.dart';
import '../detail/detail_strings.dart';
import '../detail/release_kind_labels.dart';
import '../detail/playlist_detail_view_model.dart' show playlistHeroCover;
import '../home/home_ui_state.dart' show HomeCardKind;
import '../home/widgets/home_artwork.dart' show BackdropCover;
import '../theme/tokens.dart';
import '../ui/component/artwork_image.dart';
import '../ui/component/component_strings.dart';
import '../ui/component/elastic_press.dart';
import '../ui/component/media_artwork.dart';
import '../ui/component/meta_line.dart';
import '../ui/component/seam/seam.dart';
import '../ui/component/song_list_item.dart';
import 'library_strings.dart';

/// Library covers are always requested at 256 px (Android
/// resolveSubsonicCoverUrl(id, size = 256)) and decoded at the cell size.
const libraryCoverRequestSize = 256;

/// The 256 px cover URL for [cover], or null.
String? libraryCoverUrl(WidgetRef ref, CoverRef? cover) {
  if (cover == null) return null;
  // The tip pages' made-up covers need no source.
  if (cover is CoverUrl && DemoArtwork.parse(cover.url) != null) return cover.url;
  return ref.watch(activeSourceProvider).value?.resolveCoverUrl(cover, size: libraryCoverRequestSize);
}

/// Grid columns: Compact 3; wider windows floor((w − 64 + 12) / (96 + 12)).
int libraryGridColumns(double width) {
  if (width < 600) return 3;
  return ((width - 64 + LibraryTokens.gridGutterH) / (96 + LibraryTokens.gridGutterH)).floor().clamp(3, 16);
}

/// A short Library on a wide window (owner 2026-11-12, 「内容少的资料库用更大的格子」;
/// Android LibrarySparseGrid.kt): at the usual cells a handful of albums sit
/// in one row with the page empty below. While [itemCount] cells can't fill
/// the page, they grow — fewer columns than [baseColumns], never fewer than
/// half of them (cells at most about twice the usual size) nor
/// [LibraryTokens.sparseMinColumns] — until the rows just fill [height]. A
/// shelf that fills the page at the usual cells keeps them.
///
/// [width] is the grid's own width inside its padding, [cellExtra] a cell's
/// height past its square cover (the title lines and their gap), [leading]
/// what sits above the first row (the sort row), [rowGap] / [columnGap] the
/// grid's spacing.
int librarySparseColumns({
  required int itemCount,
  required int baseColumns,
  required double width,
  required double height,
  required double cellExtra,
  double leading = 0,
  double rowGap = LibraryTokens.gridGutterV,
  double columnGap = LibraryTokens.gridGutterH,
}) {
  if (baseColumns <= LibraryTokens.sparseMinColumns || itemCount <= 0) return baseColumns;
  final half = (baseColumns + 1) ~/ 2;
  final fewest = half < LibraryTokens.sparseMinColumns ? LibraryTokens.sparseMinColumns : half;
  for (var columns = fewest; columns < baseColumns; columns++) {
    final cell = (width - columnGap * (columns - 1)) / columns;
    final rows = (itemCount + columns - 1) ~/ columns;
    final content = leading + (cell + cellExtra) * rows + rowGap * (rows - 1);
    if (content <= height) return columns;
  }
  return baseColumns;
}

/// The columns of a Library view's grid (All, Artists, Albums) [width] wide
/// and [height] tall: [libraryGridColumns], grown by [librarySparseColumns]
/// for a short shelf of [itemCount] — past Compact and not on a landscape
/// handset ([compactHeight]), where the usual cells stay. [bottom] is the
/// room kept over the floating bar, [sortRow] whether the sort row leads, [filterRow] whether Albums'
/// kind filter follows it.
/// Search results keep [libraryGridColumns].
int libraryViewGridColumns({
  required double width,
  required double height,
  required int itemCount,
  required double bottom,
  required bool sortRow,
  required bool compactHeight,
  bool filterRow = false,
  TextScaler scaler = TextScaler.noScaling,
}) {
  final base = libraryGridColumns(width);
  if (compactHeight || !height.isFinite) return base;
  return librarySparseColumns(
    itemCount: itemCount,
    baseColumns: base,
    width: width - 32,
    height: height - LibraryTokens.gridTopPadding - bottom,
    cellExtra: libraryCellExtent(0, scaler),
    leading: (sortRow ? LibraryTokens.sparseSortRow : 0) +
        (filterRow ? LibraryTokens.chipHeight + LibraryTokens.kindFilterBottom : 0),
  );
}

/// Album / playlist / artist grid cell height for a cell [cellWidth] wide.
double libraryCellExtent(double cellWidth, TextScaler scaler) =>
    cellWidth + LibraryTokens.coverToTitle + scaler.scale(20) + scaler.scale(16) + 2;

/// Album grid cell (Android AlbumCard on the backdrop, PR #343): the cover on Home's album shape in the
/// cover's colour, its kind ("EP" / "Single"), version tag and Explicit "E" on the shape's band above it;
/// under it the name alone (no version, no Apple Music " - EP" / " - Single") and the artist.
class AlbumGridCell extends ConsumerWidget {
  const AlbumGridCell({super.key, required this.album, required this.onTap});

  final Album album;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final detail = DetailStrings.of(context);
    return _SquareCell(
      cover: album.coverArt,
      kind: HomeCardKind.album,
      title: ReleaseMarks.nameWithoutKindSuffix(albumTitleWithoutVersion(album.name, album.version), album.releaseType),
      version: album.version,
      explicit: album.isExplicit,
      kindTag: releaseKindTag(detail, album.releaseType),
      subtitle: album.artist ?? '',
      onTap: onTap,
    );
  }
}

/// Playlist grid cell: subtitle = owner, else "Playlist"; the cover on the playlist shape.
class PlaylistGridCell extends ConsumerWidget {
  const PlaylistGridCell({super.key, required this.playlist, required this.onTap});

  final Playlist playlist;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final owner = playlist.owner?.trim();
    return _SquareCell(
      cover: playlistHeroCover(playlist),
      kind: HomeCardKind.playlist,
      title: playlist.name,
      subtitle: owner == null || owner.isEmpty ? LibraryStrings.of(context).playlistFallback : owner,
      onTap: onTap,
    );
  }
}

class _SquareCell extends StatelessWidget {
  const _SquareCell({
    required this.cover,
    required this.kind,
    required this.title,
    required this.subtitle,
    required this.onTap,
    this.version,
    this.explicit = false,
    this.kindTag,
  });

  final CoverRef? cover;
  final HomeCardKind kind;
  final String title;
  final String? version;
  final bool explicit;
  final String? kindTag;
  final String subtitle;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return ElasticPress(
      onTap: onTap,
      // "Explicit" is the title's, so it is said even where a narrow band leaves the E out.
      semanticsLabel: explicit ? '$title, ${ComponentStrings.of(context).explicitBadge}' : title,
      child: LayoutBuilder(
        builder: (context, constraints) => Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Home's shape in the cover's colour (read while the grid rests: LibraryPaletteGate), the
            // cover bottom-end; the marks on the band. One static shape per cover (no press morph).
            BackdropCover(
              cover: cover,
              kind: kind,
              size: constraints.maxWidth,
              requestSize: libraryCoverRequestSize,
              version: version,
              explicit: explicit,
              kindTag: kindTag,
              pressMorph: false,
            ),
            const SizedBox(height: LibraryTokens.coverToTitle),
            SeamFade(
              fontSize: theme.textTheme.titleSmall!.fontSize ?? 14,
              child: Text(
                title,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                softWrap: false,
                style: theme.textTheme.titleSmall,
              ),
            ),
            SeamFade(
              fontSize: theme.textTheme.bodySmall?.fontSize ?? 12,
              child: Text(
                subtitle,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
              ),
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
              child: SeamDissolve(
                round: true,
                child: MediaArtwork(
                  url: url,
                  fallbackIcon: YoinSymbols.artist,
                  circle: true,
                  decodeWidth: (constraints.maxWidth * dpr).round(),
                ),
              ),
            ),
            const SizedBox(height: LibraryTokens.coverToTitle),
            SeamFade(
              fontSize: theme.textTheme.titleSmall?.fontSize ?? 14,
              child: Text(
                artist.name,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                textAlign: TextAlign.center,
                style: theme.textTheme.titleSmall,
              ),
            ),
            if (artist.albumCount != null)
              SeamFade(
                fontSize: 12,
                child: Text(
                  LibraryStrings.of(context).artistGridAlbumCount(artist.albumCount!),
                  maxLines: 1,
                  textAlign: TextAlign.center,
                  style: theme.textTheme.bodySmall?.copyWith(fontSize: 12, color: theme.colorScheme.onSurfaceVariant),
                ),
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
                child: SeamDissolve(
                  round: circle,
                  child: MediaArtwork(
                    url: url,
                    fallbackIcon: fallbackIcon,
                    circle: circle,
                    radius: ShapeTokens.thumb,
                    decodeWidth: (LibraryTokens.rowThumb * dpr).round(),
                  ),
                ),
              ),
              const SizedBox(width: LibraryTokens.rowGap),
              Expanded(
                child: SeamFade(
                  fontSize: theme.textTheme.titleSmall!.fontSize ?? 14,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      VersionTitleRow.orTitle(
                        name: title,
                        nameStyle: theme.textTheme.titleSmall!,
                        version: version,
                      ),
                      if (meta.isNotEmpty) MetaLine(groups: meta),
                    ],
                  ),
                ),
              ),
              if (trailing != null) ...[
                const SizedBox(width: LibraryTokens.rowGap),
                SeamFade(fontSize: theme.textTheme.labelMedium?.fontSize ?? 12, child: trailing!),
              ],
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
  const LibrarySongRow({super.key, required this.track, required this.onTap, this.onLongPress, this.trailing});

  final Track track;
  final VoidCallback onTap;

  /// A row action after the duration (Add to library).
  final Widget? trailing;

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
      trailing: trailing,
      isExplicit: track.isExplicit,
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
    child: SeamFade(
      fontSize: Theme.of(context).textTheme.labelLarge?.fontSize ?? 14,
      child: Text(
        text,
        style: Theme.of(context).textTheme.labelLarge?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant),
      ),
    ),
  );
}
