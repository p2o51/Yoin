import 'dart:ui' show lerpDouble;

import 'package:flutter/material.dart';
import 'package:flutter/physics.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../data/model/model.dart';
import '../data/source/music_source.dart' show LibraryMembership;
import '../playback/playback_controller.dart';
import '../shell/bar_suppression.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/adaptive/yoin_window_info.dart';
import '../ui/component/empty_state_panel.dart';
import '../ui/component/expressive_segment_chip.dart';
import '../settings/settings_components.dart' show SpringReveal;
import '../ui/component/seam/seam.dart';
import '../ui/component/spring_cross_fade.dart';
import '../ui/component/track_library_button.dart';
import '../ui/component/user_message.dart';
import '../ui/component/yoin_loading_indicator.dart';
import '../ui/navigation/pane/detail_pane.dart';
import 'library_cells.dart';
import 'library_grid_anchor.dart';
import 'library_search.dart';
import 'library_sort.dart';
import 'library_strings.dart';
import 'library_views.dart';
import 'playlist_deletions.dart';
import 'playlist_writes.dart' show playlistAccountProvider;

/// Each type is capped at this many in the All layout.
const _allSectionCap = 40;

/// Opens the full-screen search, morphing out of the pill at [pillRect]
/// (global). Its own route: the back arrow and the system back both pop it
/// through the same spring (no PopScope). On a Wide window a result opens in
/// the detail column like the grid's: the search collapses first and keeps
/// its query (adaptive-principles §5).
Future<void> openLibrarySearch(BuildContext context, Rect pillRect) =>
    Navigator.of(context).push(_SearchRoute(pillRect: pillRect));

/// A search type chip's label.
String librarySearchFilterLabel(LibraryStrings s, LibrarySearchFilter f) => switch (f) {
  LibrarySearchFilter.all => s.searchFilterAll,
  LibrarySearchFilter.artists => s.tabArtists,
  LibrarySearchFilter.albums => s.tabAlbums,
  LibrarySearchFilter.songs => s.tabSongs,
  LibrarySearchFilter.playlists => s.tabPlaylists,
};

/// The search route's name: Esc in its field leaves the search (Android/Mac).
const librarySearchRouteName = 'library-search';

class _SearchRoute extends PageRoute<void> with DetailPaneYieldingRoute<void> {
  _SearchRoute({required this.pillRect}) : super(settings: const RouteSettings(name: librarySearchRouteName));

  final Rect pillRect;

  @override
  bool get opaque => false;
  @override
  bool get maintainState => true;
  @override
  Color? get barrierColor => null;
  @override
  String? get barrierLabel => null;
  @override
  Duration get transitionDuration => const Duration(milliseconds: 400);

  @override
  Simulation? createSimulation({required bool forward}) => SpringSimulation(
    YoinSprings.standardDefaultSpatial,
    controller?.value ?? (forward ? 0 : 1),
    forward ? 1 : 0,
    controller?.velocity ?? 0,
    tolerance: const Tolerance(distance: 0.001, velocity: 0.01),
  );

  @override
  Widget buildPage(BuildContext context, Animation<double> animation, Animation<double> secondaryAnimation) =>
      // The bar has no place over search (it would flip the section under it).
      const ChromeBarSuppression(child: LibrarySearchSurface());

  @override
  Widget buildTransitions(
    BuildContext context,
    Animation<double> animation,
    Animation<double> secondaryAnimation,
    Widget child,
  ) {
    final colors = Theme.of(context).colorScheme;
    final reduce = MediaQuery.disableAnimationsOf(context);
    return LayoutBuilder(
      builder: (context, constraints) {
        final full = Offset.zero & constraints.biggest;
        return AnimatedBuilder(
          animation: animation,
          child: child,
          builder: (context, child) {
            // One progress: the container morphs pill → full screen.
            final t = animation.value;
            final rect = reduce ? full : Rect.lerp(pillRect, full, t)!;
            final radius = reduce ? 0.0 : lerpDouble(28, 0, t.clamp(0.0, 1.0))!;
            return Stack(
              children: [
                Positioned.fromRect(
                  rect: rect,
                  child: ClipRSuperellipse(
                    borderRadius: BorderRadius.circular(radius),
                    child: ColoredBox(
                      color: colors.surfaceContainerHigh,
                      child: Opacity(
                        opacity: ((t - 0.2) / 0.8).clamp(0.0, 1.0),
                        child: OverflowBox(
                          alignment: Alignment.topLeft,
                          minWidth: full.width,
                          maxWidth: full.width,
                          minHeight: full.height,
                          maxHeight: full.height,
                          // The content stays at screen coordinates; only the window onto it grows.
                          child: Transform.translate(offset: -rect.topLeft, child: child),
                        ),
                      ),
                    ),
                  ),
                ),
              ],
            );
          },
        );
      },
    );
  }
}

/// Where the search's Artists and Albums grids were left, one exact anchor
/// per scope and type (Android LibrarySearchScrollMemory.gridAnchor). Kept
/// beside the query, not in the surface: a result opened in the detail
/// column collapses the surface with its query kept, and the grid comes back
/// where it was left. Another query (a blank one ends the search) forgets
/// them all.
final class LibrarySearchGridAnchors {
  final _anchors = <(LibrarySearchScope, LibrarySearchFilter), LibraryGridAnchor>{};

  /// The anchor of [scope]'s [filter] grid.
  LibraryGridAnchor of(LibrarySearchScope scope, LibrarySearchFilter filter) =>
      _anchors.putIfAbsent((scope, filter), () => LibraryGridAnchor(exact: true));

  /// Every grid starts at the top again.
  void forget() {
    for (final anchor in _anchors.values) {
      anchor.reset();
    }
  }
}

/// The search grids' anchors, forgotten when the query changes.
final librarySearchGridAnchorsProvider = Provider<LibrarySearchGridAnchors>((ref) {
  final anchors = LibrarySearchGridAnchors();
  ref.listen(librarySearchProvider.select((s) => s.query), (previous, query) {
    if (previous != query) anchors.forget();
  });
  return anchors;
});

/// The expanded search: field, type chips, results.
class LibrarySearchSurface extends ConsumerStatefulWidget {
  const LibrarySearchSurface({super.key});

  @override
  ConsumerState<LibrarySearchSurface> createState() => _LibrarySearchSurfaceState();
}

class _LibrarySearchSurfaceState extends ConsumerState<LibrarySearchSurface> {
  final _field = TextEditingController();
  late final LibrarySearchNotifier _search = ref.read(librarySearchProvider.notifier);
  final _scrolls = <(LibrarySearchScope, LibrarySearchFilter), ScrollController>{};

  /// The route this surface lives on (read while mounted; dispose can't).
  DetailPaneYieldingRoute<dynamic>? _route;

  @override
  void initState() {
    super.initState();
    // A search that stepped aside for the detail column comes back as it
    // was, the kept query selected so typing replaces it (QA r2_22).
    final kept = ref.read(librarySearchProvider).query;
    _seeded = kept.isNotEmpty;
    _field.value = TextEditingValue(
      text: kept,
      selection: TextSelection(baseOffset: 0, extentOffset: kept.length),
    );
  }

  /// The field opened on a kept query.
  bool _seeded = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final route = ModalRoute.of(context);
    _route = route is DetailPaneYieldingRoute ? route : null;
  }

  @override
  void dispose() {
    // Collapsing clears the query and the type filter — unless a result
    // went to the detail column, which keeps them (adaptive-principles §5).
    // After the frame: Riverpod forbids changing a provider while the tree
    // is being finalised.
    if (!(_route?.handedToColumn ?? false)) Future.microtask(_search.clear);
    _field.dispose();
    for (final c in _scrolls.values) {
      c.dispose();
    }
    super.dispose();
  }

  /// One scroll per scope and type. The Artists and Albums grids keep an
  /// exact anchor ([LibrarySearchGridAnchors]): after a rotation or a resize,
  /// back at the width they were left at, they land exactly there; a type or
  /// scope switched away from and back, or the surface come back from the
  /// detail column, finds its place too.
  ScrollController _scrollFor(LibrarySearchScope scope, LibrarySearchFilter f) => _scrolls.putIfAbsent(
    (scope, f),
    () => switch (f) {
      LibrarySearchFilter.artists || LibrarySearchFilter.albums => LibraryGridScrollController(
        exact: true,
        anchor: ref.read(librarySearchGridAnchorsProvider).of(scope, f),
      ),
      LibrarySearchFilter.all || LibrarySearchFilter.songs || LibrarySearchFilter.playlists => ScrollController(),
    },
  );

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(librarySearchProvider);
    final strings = LibraryStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    ref.listen(librarySearchProvider.select((s) => s.query), (previous, query) {
      // A new query starts every filter's list at the top.
      // (The grids' anchors forget their places with it: librarySearchGridAnchorsProvider.)
      if (previous != query) {
        for (final c in _scrolls.values) {
          if (c.hasClients) c.jumpTo(0);
        }
      }
    });
    return Material(
      type: MaterialType.transparency,
      child: Column(
        children: [
          SafeArea(
            bottom: false,
            child: SizedBox(
              height: 56 + 16,
              child: Row(
                children: [
                  const SizedBox(width: 4),
                  IconButton(
                    tooltip: strings.cdCloseSearch,
                    icon: const Icon(YoinSymbols.back),
                    onPressed: () => Navigator.of(context).maybePop(),
                  ),
                  Expanded(
                    child: TextField(
                      controller: _field,
                      autofocus: true,
                      selectAllOnFocus: _seeded,
                      textInputAction: TextInputAction.search,
                      style: Theme.of(context).textTheme.bodyLarge,
                      onChanged: _search.search,
                      decoration: InputDecoration.collapsed(
                        hintText: state.scope == LibrarySearchScope.catalog
                            ? strings.searchPlaceholderAppleMusic
                            : strings.searchPlaceholder,
                        hintStyle: Theme.of(context).textTheme.bodyLarge?.copyWith(color: colors.onSurfaceVariant),
                      ),
                    ),
                  ),
                  if (state.isSearching)
                    const Padding(padding: EdgeInsets.all(15), child: YoinLoadingIndicator(size: 18, contained: false))
                  else if (state.query.isNotEmpty)
                    IconButton(
                      tooltip: strings.cdClearSearch,
                      icon: const Icon(YoinSymbols.close),
                      onPressed: () {
                        _field.clear();
                        _search.search('');
                      },
                    ),
                  const SizedBox(width: 4),
                ],
              ),
            ),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(vertical: SearchTokens.filterRowPaddingV),
            child: SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 16),
              // The scope pair (catalogue | library, where the service has a catalogue), a hairline, the types.
              child: Row(
                children: [
                  if (state.canSearchCatalog) ...[
                    for (final (i, scope) in const [
                      LibrarySearchScope.catalog,
                      LibrarySearchScope.library,
                    ].indexed) ...[
                      if (i > 0) const SizedBox(width: LibraryTokens.chipGap),
                      ExpressiveSegmentChip(
                        label: scope == LibrarySearchScope.catalog
                            ? strings.searchScopeAppleMusic
                            : strings.searchScopeLibrary,
                        selected: state.scope == scope,
                        onTap: () => _search.setScope(scope),
                      ),
                    ],
                    const SizedBox(width: LibraryTokens.chipGap),
                    SizedBox(
                      height: LibraryTokens.searchScopeDividerHeight,
                      child: VerticalDivider(width: 1, thickness: 1, color: colors.outlineVariant),
                    ),
                    const SizedBox(width: LibraryTokens.chipGap),
                  ],
                  for (final (i, f) in state.filters.indexed) ...[
                    if (i > 0) const SizedBox(width: LibraryTokens.chipGap),
                    ExpressiveSegmentChip(
                      label: librarySearchFilterLabel(strings, f),
                      selected: state.filter == f,
                      onTap: () => _search.setFilter(f),
                    ),
                  ],
                ],
              ),
            ),
          ),
          Expanded(
            // The results go under the filter row through the chrome seam, in the user's style.
            child: ChromeSeam(
              background: SeamBackground([colors.surfaceContainerHigh]),
              child: SpringCrossFade(
                childKey: (state.scope, state.filter, _phase(state)),
                child: _results(context, state, strings),
              ),
            ),
          ),
        ],
      ),
    );
  }

  static String _phase(LibrarySearchState s) => s.isSearching
      ? 'searching'
      : s.error != null
      ? 'error'
      : s.results == null
      ? 'none'
      : 'results';

  Widget _results(BuildContext context, LibrarySearchState state, LibraryStrings strings) {
    final colors = Theme.of(context).colorScheme;
    if (state.isSearching) return const Center(child: YoinLoadingIndicator());
    if (state.error != null) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              // The failure's own line (connectivity, the server's words), else Search failed.
              userMessageText(context, state.error, strings.errorSearch),
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.bodyLarge?.copyWith(color: colors.error),
            ),
            TextButton(onPressed: _search.retry, child: Text(strings.searchRetry)),
          ],
        ),
      );
    }
    final found = state.results;
    if (found == null) return const SizedBox.shrink();
    // A playlist deleted on its page (or on its way out) leaves the results too.
    final shownPlaylists = ref
        .watch(playlistDeletionsProvider)
        .visible(found.playlists, ref.watch(playlistAccountProvider).value);
    final r = identical(shownPlaylists, found.playlists)
        ? found
        : SearchResults(tracks: found.tracks, albums: found.albums, artists: found.artists, playlists: shownPlaylists);
    final empty = switch (state.filter) {
      LibrarySearchFilter.all => r.isEmpty,
      LibrarySearchFilter.artists => r.artists.isEmpty,
      LibrarySearchFilter.albums => r.albums.isEmpty,
      LibrarySearchFilter.songs => r.tracks.isEmpty,
      LibrarySearchFilter.playlists => r.playlists.isEmpty,
    };
    if (empty) return EmptyStatePanel(message: strings.emptySearch);
    final bottom = SearchTokens.resultsBottomReserve + MediaQuery.paddingOf(context).bottom;
    final controller = _scrollFor(state.scope, state.filter);
    switch (state.filter) {
      case LibrarySearchFilter.artists:
        return _grid(
          context,
          [for (final a in r.artists) LibraryArtistItem(a)],
          controller as LibraryGridScrollController,
          bottom,
        );
      case LibrarySearchFilter.albums:
        return _grid(
          context,
          [for (final a in r.albums) LibraryAlbumItem(a)],
          controller as LibraryGridScrollController,
          bottom,
        );
      case LibrarySearchFilter.songs:
        return ListView.builder(
          controller: controller,
          padding: EdgeInsets.only(bottom: bottom),
          itemCount: r.tracks.length,
          itemBuilder: (context, i) =>
              _songRow(state, r.tracks[i], key: ValueKey('${r.tracks[i].id}#$i'), onTap: () => _playSong(r.tracks, i)),
        );
      case LibrarySearchFilter.playlists:
        return ListView(
          controller: controller,
          padding: EdgeInsets.only(bottom: bottom),
          children: [for (final p in r.playlists) _playlistRow(context, p)],
        );
      case LibrarySearchFilter.all:
        return _all(context, state, r, strings, controller, bottom);
    }
  }

  /// A song row; in the catalogue, with Add to library and the line it leaves
  /// under the row (Android SearchSongRow).
  Widget _songRow(LibrarySearchState state, Track track, {required Key key, required VoidCallback onTap}) {
    if (!state.canAddToLibrary || state.scope != LibrarySearchScope.catalog) {
      return LibrarySongRow(key: key, track: track, onTap: onTap);
    }
    final membership = state.membership[track.id] ?? LibraryMembership.unknown;
    final feedback = state.feedback[track.id];
    return Column(
      key: key,
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        LibrarySongRow(
          track: track,
          onTap: onTap,
          trailing: TrackLibraryButton(
            membership: membership,
            working: state.adding.contains(track.id),
            onPressed: () => _search.addToLibrary(track),
          ),
        ),
        SpringReveal(
          visible: feedback != null,
          child: feedback == null
              ? null
              : _AddFeedbackLine(
                  feedback: feedback,
                  added: membership == LibraryMembership.added,
                  failure: state.addFailures[track.id],
                ),
        ),
      ],
    );
  }

  Widget _playlistRow(BuildContext context, Playlist p) => LibraryListRow(
    key: ValueKey('playlist:${p.id}'),
    url: libraryCoverUrl(ref, p.coverArt),
    fallbackIcon: YoinSymbols.playlist,
    title: p.name,
    onTap: () => openLibraryItem(context, ref, LibraryPlaylistItem(p)),
  );

  void _playSong(List<Track> tracks, int index) =>
      ref.read(playbackControllerProvider.notifier).playTracks(tracks, startIndex: index);

  /// A result grid: the usual cells, however few the results.
  Widget _grid(BuildContext context, List<LibraryItem> items, LibraryGridScrollController controller, double bottom) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final width = constraints.maxWidth;
        final columns = libraryGridColumns(width);
        final cell = (width - 32 - LibraryTokens.gridGutterH * (columns - 1)) / columns;
        final extent = libraryCellExtent(cell, MediaQuery.textScalerOf(context));
        controller.anchor.layOut(
          LibraryGridLayout(
            width: width,
            columns: columns,
            stride: extent + LibraryTokens.gridGutterV,
            top: LibraryTokens.gridTopPadding,
          ),
          items.length,
        );
        return GridView.builder(
          controller: controller,
          padding: EdgeInsets.fromLTRB(16, LibraryTokens.gridTopPadding, 16, bottom),
          gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
            crossAxisCount: columns,
            crossAxisSpacing: LibraryTokens.gridGutterH,
            mainAxisSpacing: LibraryTokens.gridGutterV,
            mainAxisExtent: extent,
          ),
          itemCount: items.length,
          itemBuilder: (context, i) => _cell(context, items[i]),
        );
      },
    );
  }

  Widget _cell(BuildContext context, LibraryItem item) {
    void open() => openLibraryItem(context, ref, item);
    return switch (item) {
      LibraryArtistItem(:final artist) => ArtistGridCell(key: ValueKey(item.key), artist: artist, onTap: open),
      LibraryAlbumItem(:final album) => AlbumGridCell(key: ValueKey(item.key), album: album, onTap: open),
      LibraryPlaylistItem(:final playlist) => PlaylistGridCell(
        key: ValueKey(item.key),
        playlist: playlist,
        onTap: open,
      ),
    };
  }

  Widget _all(
    BuildContext context,
    LibrarySearchState state,
    SearchResults r,
    LibraryStrings strings,
    ScrollController controller,
    double bottom,
  ) {
    final columns = libraryGridColumns(YoinWindowInfo.of(context).width);
    final albums = r.albums.take(_allSectionCap).toList();
    final tracks = r.tracks.take(_allSectionCap).toList();
    return ListView(
      controller: controller,
      padding: EdgeInsets.only(bottom: bottom),
      children: [
        if (r.artists.isNotEmpty) LibrarySectionHeader(strings.searchSectionArtists),
        for (final a in r.artists.take(_allSectionCap))
          LibraryListRow(
            key: ValueKey('artist:${a.id}'),
            url: libraryCoverUrl(ref, a.coverArt),
            fallbackIcon: YoinSymbols.artist,
            circle: true,
            title: a.name,
            onTap: () => openLibraryItem(context, ref, LibraryArtistItem(a)),
          ),
        if (albums.isNotEmpty) LibrarySectionHeader(strings.searchSectionAlbums),
        for (var start = 0; start < albums.length; start += columns)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, LibraryTokens.gridGutterV),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                for (var c = 0; c < columns; c++) ...[
                  if (c > 0) const SizedBox(width: 12),
                  Expanded(
                    child: start + c < albums.length
                        ? _cell(context, LibraryAlbumItem(albums[start + c]))
                        : const SizedBox.shrink(),
                  ),
                ],
              ],
            ),
          ),
        // Android's order: artists, albums, playlists, songs.
        if (state.filters.contains(LibrarySearchFilter.playlists) && r.playlists.isNotEmpty)
          LibrarySectionHeader(strings.searchSectionPlaylists),
        if (state.filters.contains(LibrarySearchFilter.playlists))
          for (final p in r.playlists.take(_allSectionCap)) _playlistRow(context, p),
        if (tracks.isNotEmpty) LibrarySectionHeader(strings.searchSectionSongs),
        for (final (i, t) in tracks.indexed)
          _songRow(state, t, key: ValueKey('${t.id}#$i'), onTap: () => _playSong(r.tracks, i)),
      ],
    );
  }
}

/// The line under a song after Add to library: Added (primary), waiting for
/// Apple Music, or that it couldn't (error).
class _AddFeedbackLine extends StatelessWidget {
  const _AddFeedbackLine({required this.feedback, required this.added, this.failure});

  final LibraryAddFeedback feedback;
  final bool added;

  /// What the failed add threw: its own line when it has one.
  final Object? failure;

  @override
  Widget build(BuildContext context) {
    final strings = LibraryStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final (String text, Color color) = added
        ? (strings.searchAdded, colors.primary)
        : switch (feedback) {
            LibraryAddFeedback.added => (strings.searchAdded, colors.primary),
            LibraryAddFeedback.pending => (strings.feedbackApplePending, colors.onSurfaceVariant),
            LibraryAddFeedback.failed => (userMessageText(context, failure, strings.errorAddSong), colors.error),
          };
    return Padding(
      padding: const EdgeInsetsDirectional.only(
        start: LibraryTokens.libraryAddFeedbackStart,
        end: LibraryTokens.rowPaddingH,
        bottom: LibraryTokens.libraryAddFeedbackBottom,
      ),
      child: Text(text, style: Theme.of(context).textTheme.bodySmall!.copyWith(color: color)),
    );
  }
}
