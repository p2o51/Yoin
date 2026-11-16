import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../data/model/model.dart';
import '../detail/detail_routes.dart';
import '../detail/playlist_detail_view_model.dart' show playlistHeroCover;
import '../playback/playback_context.dart';
import '../playback/playback_controller.dart';
import '../theme/tokens.dart';
import '../ui/adaptive/yoin_window_info.dart';
import '../ui/component/empty_state_panel.dart';
import '../ui/component/meta_line.dart';
import '../ui/component/seam/seam.dart';
import '../ui/component/yoin_dropdown_menu.dart';
import '../ui/component/yoin_fast_scroller.dart';
import '../ui/component/yoin_loading_indicator.dart';
import 'library_cells.dart';
import 'library_sort.dart';
import 'library_strings.dart';
import 'library_view_model.dart';

/// Physics for every Library view: clamping, always scrollable (pull to
/// refresh arrives as overscroll even on short content).
const libraryScrollPhysics = AlwaysScrollableScrollPhysics(parent: ClampingScrollPhysics());

/// Bottom padding so rows clear the floating bar (108 + nav inset; 16 + inset on a landscape handset).
double libraryBottomPadding(BuildContext context) {
  final inset = MediaQuery.paddingOf(context).bottom;
  return (YoinWindowInfo.of(context).isCompactHeight ? 16 : LibraryTokens.bottomGroupPadding) + inset;
}

/// Records the open (Recents) and pushes the detail page.
void openLibraryItem(BuildContext context, WidgetRef ref, LibraryItem item) {
  ref.read(libraryViewModelProvider.notifier).recordOpened(item);
  switch (item) {
    case LibraryArtistItem(:final artist):
      DetailRoutes.openArtist(context, artist.id, name: artist.name);
    case LibraryAlbumItem(:final album):
      DetailRoutes.openAlbum(context, album.id, preview: album);
    case LibraryPlaylistItem(:final playlist):
      DetailRoutes.openPlaylist(context, playlist.id, preview: playlist);
  }
}

String librarySortLabel(LibraryStrings s, LibrarySort sort) => switch (sort) {
  LibrarySort.recents => s.sortRecents,
  LibrarySort.recentlyAdded => s.sortRecentlyAdded,
  LibrarySort.alphabetical => s.sortAlphabetical,
  LibrarySort.creator => s.sortCreator,
};

/// The fast-scroller marks of a sorted view, labels in the app's language.
List<FastScrollMark> libraryMarks(LibraryStrings s, List<FastScrollSection> sections) => [
  for (final section in sections)
    switch (section.label) {
      LetterLabel(:final letter) => FastScrollMark(bubble: letter, tick: letter, startIndex: section.startIndex),
      MonthLabel(:final year, :final month, :final tickIsMonth) => FastScrollMark(
        bubble: s.yearMonth(year, month),
        tick: tickIsMonth ? s.shortMonth(month) : '$year',
        startIndex: section.startIndex,
      ),
      RecentsLabel(:final bucket) => () {
        final text = switch (bucket) {
          RecentsBucket.today => s.recentsToday,
          RecentsBucket.thisWeek => s.recentsThisWeek,
          RecentsBucket.thisMonth => s.recentsThisMonth,
          RecentsBucket.notOpened => s.recentsNotOpened,
        };
        return FastScrollMark(bubble: text, tick: text, startIndex: section.startIndex);
      }(),
    },
];

/// The sort row (LibrarySortRow): the order's label + sort glyph; tap opens
/// the menu of offered orders, the active one checked.
class LibrarySortRow extends ConsumerWidget {
  const LibrarySortRow({super.key, required this.tab, required this.sort});

  final LibraryTab tab;
  final LibrarySort sort;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = LibraryStrings.of(context);
    final theme = Theme.of(context);
    return Align(
      alignment: AlignmentDirectional.centerStart,
      child: Transform.translate(
        offset: const Offset(-LibraryTokens.sortRowPressBleed, 0),
        child: Builder(
          builder: (anchorContext) => Semantics(
            button: true,
            label: strings.sortAction,
            child: InkWell(
              customBorder: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(12)),
              onTap: () async {
                final chosen = await showYoinDropdownMenu<LibrarySort>(
                  context: anchorContext,
                  anchor: globalRectOf(anchorContext),
                  items: [
                    for (final option in librarySortOptions(tab))
                      YoinMenuItem(value: option, label: librarySortLabel(strings, option), checked: option == sort),
                  ],
                );
                if (chosen != null) await ref.read(libraryViewModelProvider.notifier).selectSort(tab, chosen);
              },
              child: ConstrainedBox(
                constraints: const BoxConstraints(minHeight: 48),
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: LibraryTokens.sortRowPressBleed, vertical: 10),
                  // The label and its glyph fade into the chrome seam like text.
                  child: SeamFade(
                    fontSize: theme.textTheme.labelLarge?.fontSize ?? 14,
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(
                          librarySortLabel(strings, sort),
                          style: theme.textTheme.labelLarge?.copyWith(color: theme.colorScheme.onSurface),
                        ),
                        const SizedBox(width: 6),
                        Icon(YoinSymbols.sort, size: 18, color: theme.colorScheme.onSurfaceVariant),
                      ],
                    ),
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

/// Centred 48dp loading indicator (TabLoadingState), scrollable so a pull still works.
class LibraryTabLoading extends StatelessWidget {
  const LibraryTabLoading({super.key});

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, c) => SingleChildScrollView(
      physics: libraryScrollPhysics,
      child: SizedBox(
        height: c.maxHeight,
        child: const Center(child: YoinLoadingIndicator()),
      ),
    ),
  );
}

/// Each view's scroll offset lives in the route's PageStorage under this key (L4):
/// the cross-fade disposes a view once it has faded out, and a ScrollController
/// forgets its offset when it detaches, so coming back restores from here.
PageStorageKey<String> libraryScrollStorageKey(LibraryTab tab) => PageStorageKey('library-${tab.name}');

/// All / Artists / Albums: one grid with the sort row as its first full-span item.
class LibraryGridView extends ConsumerWidget {
  const LibraryGridView({
    super.key,
    required this.tab,
    required this.view,
    required this.sort,
    required this.controller,
    required this.emptyText,
    this.loading = false,
  });

  final LibraryTab tab;
  final SortedView? view;
  final LibrarySort sort;
  final ScrollController controller;
  final String emptyText;

  /// Something still loading (All waits for every list before "empty").
  final bool loading;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final view = this.view;
    if (view == null || (view.items.isEmpty && loading)) return const LibraryTabLoading();
    if (view.items.isEmpty) return EmptyStatePanel(message: emptyText, physics: libraryScrollPhysics);
    final strings = LibraryStrings.of(context);
    final bottom = libraryBottomPadding(context);
    final options = librarySortOptions(tab);
    return LayoutBuilder(
      builder: (context, constraints) {
        // Columns follow the view's own width (a pane gives away width; never the device).
        final width = constraints.maxWidth;
        final columns = libraryGridColumns(width);
        final cellWidth = (width - 32 - LibraryTokens.gridGutterH * (columns - 1)) / columns;
        final extent = libraryCellExtent(cellWidth, MediaQuery.textScalerOf(context));
        return YoinFastScroller(
          controller: controller,
          itemCount: view.items.length,
          columns: columns,
          marks: libraryMarks(strings, view.sections),
          bottomInset: bottom,
          child: CustomScrollView(
            key: libraryScrollStorageKey(tab),
            controller: controller,
            physics: libraryScrollPhysics,
            slivers: [
              SliverPadding(
                padding: const EdgeInsets.fromLTRB(16, LibraryTokens.gridTopPadding, 16, 0),
                sliver: SliverToBoxAdapter(
                  child: options.length >= 2 ? LibrarySortRow(tab: tab, sort: sort) : const SizedBox.shrink(),
                ),
              ),
              SliverPadding(
                padding: EdgeInsets.fromLTRB(16, 0, 16, bottom),
                sliver: SliverGrid.builder(
                  gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                    crossAxisCount: columns,
                    crossAxisSpacing: LibraryTokens.gridGutterH,
                    mainAxisSpacing: LibraryTokens.gridGutterV,
                    mainAxisExtent: extent,
                  ),
                  itemCount: view.items.length,
                  findChildIndexCallback: (key) {
                    final k = (key as ValueKey<String>).value;
                    final i = view.items.indexWhere((it) => it.key == k);
                    return i < 0 ? null : i;
                  },
                  itemBuilder: (context, i) {
                    final item = view.items[i];
                    void open() => openLibraryItem(context, ref, item);
                    return KeyedSubtree(
                      key: ValueKey(item.key),
                      child: switch (item) {
                        LibraryArtistItem(:final artist) => ArtistGridCell(artist: artist, onTap: open),
                        LibraryAlbumItem(:final album) => AlbumGridCell(album: album, onTap: open),
                        LibraryPlaylistItem(:final playlist) => PlaylistGridCell(playlist: playlist, onTap: open),
                      },
                    );
                  },
                ),
              ),
            ],
          ),
        );
      },
    );
  }
}

/// Playlists: rows with owner / count / duration stats, sort row first.
class LibraryPlaylistsView extends ConsumerWidget {
  const LibraryPlaylistsView({super.key, required this.view, required this.sort, required this.controller});

  final SortedView? view;
  final LibrarySort sort;
  final ScrollController controller;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final view = this.view;
    final strings = LibraryStrings.of(context);
    if (view == null) return const LibraryTabLoading();
    if (view.items.isEmpty) return EmptyStatePanel(message: strings.emptyPlaylists, physics: libraryScrollPhysics);
    return ListView.builder(
      key: libraryScrollStorageKey(LibraryTab.playlists),
      controller: controller,
      physics: libraryScrollPhysics,
      padding: EdgeInsets.only(top: LibraryTokens.gridTopPadding, bottom: libraryBottomPadding(context)),
      itemCount: view.items.length + 1,
      itemBuilder: (context, i) {
        if (i == 0) {
          return Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: LibrarySortRow(tab: LibraryTab.playlists, sort: sort),
          );
        }
        final item = view.items[i - 1] as LibraryPlaylistItem;
        final p = item.playlist;
        final meta = <MetaGroup>[
          if (p.owner?.trim().isNotEmpty ?? false) MetaPlain(p.owner!),
          if (p.songCount != null) MetaStat('${p.songCount}', strings.playlistTrackUnit(p.songCount!)),
          if (p.durationSec != null)
            ...durationStatGroups(
              p.durationSec!,
              hourUnit: strings.durationHourUnit,
              minuteUnit: strings.durationMinuteUnit,
            ),
        ];
        return LibraryListRow(
          key: ValueKey(item.key),
          url: libraryCoverUrl(ref, playlistHeroCover(p)),
          fallbackIcon: YoinSymbols.playlist,
          title: p.name,
          meta: meta.isEmpty ? [MetaPlain(strings.playlistFallback)] : meta,
          onTap: () => openLibraryItem(context, ref, item),
        );
      },
    );
  }
}

/// Songs (Subsonic: newest albums' songs, paged), with the foot while more can follow.
class LibrarySongsView extends ConsumerWidget {
  const LibrarySongsView({super.key, required this.songs, required this.more, required this.controller});

  final List<Track>? songs;
  final SongsMore more;
  final ScrollController controller;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final songs = this.songs;
    if (songs == null) return const LibraryTabLoading();
    if (songs.isEmpty) {
      return EmptyStatePanel(message: LibraryStrings.of(context).emptySongs, physics: libraryScrollPhysics);
    }
    final hasFoot = more != SongsMore.none;
    final vm = ref.read(libraryViewModelProvider.notifier);
    return NotificationListener<ScrollMetricsNotification>(
      onNotification: (n) {
        _maybeLoadMore(n.metrics, vm);
        return false;
      },
      child: NotificationListener<ScrollUpdateNotification>(
        onNotification: (n) {
          _maybeLoadMore(n.metrics, vm);
          return false;
        },
        child: ListView.builder(
          key: libraryScrollStorageKey(LibraryTab.songs),
          controller: controller,
          physics: libraryScrollPhysics,
          padding: EdgeInsets.only(top: LibraryTokens.gridTopPadding, bottom: libraryBottomPadding(context)),
          itemCount: songs.length + (hasFoot ? 1 : 0),
          itemBuilder: (context, i) {
            if (i == songs.length) return _SongsFoot(more: more, onRetry: vm.loadMoreSongs);
            return LibrarySongRow(
              key: ValueKey('${songs[i].id}#$i'),
              track: songs[i],
              onTap: () => ref.read(playbackControllerProvider.notifier).playTracks(songs, startIndex: i),
            );
          },
        ),
      ),
    );
  }

  /// Next page once the list is within about one screen of its end; never after a failure.
  void _maybeLoadMore(ScrollMetrics metrics, LibraryViewModel vm) {
    if (more != SongsMore.available) return;
    if (metrics.extentAfter <= metrics.viewportDimension) vm.loadMoreSongs();
  }
}

class _SongsFoot extends StatelessWidget {
  const _SongsFoot({required this.more, required this.onRetry});

  final SongsMore more;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 12),
    child: Center(
      child: more == SongsMore.failed
          ? IconButton(
              tooltip: LibraryStrings.of(context).errorRetry,
              icon: const Icon(YoinSymbols.refresh),
              onPressed: onRetry,
            )
          : const YoinLoadingIndicator(size: 40),
    ),
  );
}

/// Favorites (getStarred): Artists / Albums / Songs sections.
class LibraryFavoritesView extends ConsumerWidget {
  const LibraryFavoritesView({super.key, required this.favorites, required this.controller});

  final Starred? favorites;
  final ScrollController controller;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final f = favorites;
    final strings = LibraryStrings.of(context);
    if (f == null) return const LibraryTabLoading();
    if (f.artists.isEmpty && f.albums.isEmpty && f.tracks.isEmpty) {
      return EmptyStatePanel(message: strings.emptyFavorites, physics: libraryScrollPhysics);
    }
    final rows = <Widget>[
      if (f.artists.isNotEmpty) LibrarySectionHeader(strings.favoritesSectionArtists),
      for (final a in f.artists)
        LibraryListRow(
          key: ValueKey('fav-artist-${a.id}'),
          url: libraryCoverUrl(ref, a.coverArt),
          fallbackIcon: YoinSymbols.artist,
          circle: true,
          title: a.name,
          trailing: a.albumCount == null ? null : LibraryMetaPill(strings.artistRowAlbumCount(a.albumCount!)),
          onTap: () => openLibraryItem(context, ref, LibraryArtistItem(a)),
        ),
      if (f.albums.isNotEmpty) LibrarySectionHeader(strings.favoritesSectionAlbums),
      for (final a in f.albums)
        LibraryListRow(
          key: ValueKey('fav-album-${a.id}'),
          url: libraryCoverUrl(ref, a.coverArt),
          fallbackIcon: YoinSymbols.album,
          title: albumTitleWithoutVersion(a.name, a.version),
          version: a.version,
          meta: [if (a.artist?.isNotEmpty ?? false) MetaPlain(a.artist!)],
          onTap: () => openLibraryItem(context, ref, LibraryAlbumItem(a)),
        ),
      if (f.tracks.isNotEmpty) LibrarySectionHeader(strings.favoritesSectionSongs),
      for (final (i, t) in f.tracks.indexed)
        LibrarySongRow(
          key: ValueKey('fav-song-${t.id}#$i'),
          track: t,
          onTap: () => ref
              .read(playbackControllerProvider.notifier)
              .playTracks(f.tracks, startIndex: i, context: const FavoritesPlaybackContext()),
        ),
    ];
    return ListView(
      key: libraryScrollStorageKey(LibraryTab.favorites),
      controller: controller,
      physics: libraryScrollPhysics,
      padding: EdgeInsets.only(top: LibraryTokens.gridTopPadding, bottom: libraryBottomPadding(context)),
      children: rows,
    );
  }
}
