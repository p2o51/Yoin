import 'dart:ui' show lerpDouble;

import 'package:flutter/material.dart';
import 'package:flutter/physics.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../data/model/model.dart';
import '../playback/playback_controller.dart';
import '../shell/bar_suppression.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/adaptive/yoin_window_info.dart';
import '../ui/component/empty_state_panel.dart';
import '../ui/component/expressive_segment_chip.dart';
import '../ui/component/spring_cross_fade.dart';
import '../ui/component/yoin_loading_indicator.dart';
import 'library_cells.dart';
import 'library_search.dart';
import 'library_sort.dart';
import 'library_strings.dart';
import 'library_views.dart';

/// Each type is capped at this many in the All layout.
const _allSectionCap = 40;

/// Opens the full-screen search, morphing out of the pill at [pillRect]
/// (global). Its own route: the back arrow and the system back both pop it
/// through the same spring (no PopScope).
Future<void> openLibrarySearch(BuildContext context, Rect pillRect) =>
    Navigator.of(context).push(_SearchRoute(pillRect: pillRect));

class _SearchRoute extends PageRoute<void> {
  _SearchRoute({required this.pillRect});

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

/// The expanded search: field, type chips, results.
class LibrarySearchSurface extends ConsumerStatefulWidget {
  const LibrarySearchSurface({super.key});

  @override
  ConsumerState<LibrarySearchSurface> createState() => _LibrarySearchSurfaceState();
}

class _LibrarySearchSurfaceState extends ConsumerState<LibrarySearchSurface> {
  final _field = TextEditingController();
  late final LibrarySearchNotifier _search = ref.read(librarySearchProvider.notifier);
  final _scrolls = <LibrarySearchFilter, ScrollController>{};

  @override
  void dispose() {
    // Collapsing clears the query and the type filter.
    _search.clear();
    _field.dispose();
    for (final c in _scrolls.values) {
      c.dispose();
    }
    super.dispose();
  }

  ScrollController _scrollFor(LibrarySearchFilter f) => _scrolls.putIfAbsent(f, ScrollController.new);

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(librarySearchProvider);
    final strings = LibraryStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    ref.listen(librarySearchProvider.select((s) => s.query), (previous, query) {
      // A new query starts every filter's list at the top.
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
                      textInputAction: TextInputAction.search,
                      style: Theme.of(context).textTheme.bodyLarge,
                      onChanged: _search.search,
                      decoration: InputDecoration.collapsed(
                        hintText: strings.searchPlaceholder,
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
              child: Row(
                children: [
                  for (final (i, f) in LibrarySearchFilter.values.indexed) ...[
                    if (i > 0) const SizedBox(width: LibraryTokens.chipGap),
                    ExpressiveSegmentChip(
                      label: _filterLabel(strings, f),
                      selected: state.filter == f,
                      onTap: () => _search.setFilter(f),
                    ),
                  ],
                ],
              ),
            ),
          ),
          Expanded(
            child: SpringCrossFade(childKey: (state.filter, _phase(state)), child: _results(context, state, strings)),
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

  static String _filterLabel(LibraryStrings s, LibrarySearchFilter f) => switch (f) {
    LibrarySearchFilter.all => s.searchFilterAll,
    LibrarySearchFilter.artists => s.tabArtists,
    LibrarySearchFilter.albums => s.tabAlbums,
    LibrarySearchFilter.songs => s.tabSongs,
  };

  Widget _results(BuildContext context, LibrarySearchState state, LibraryStrings strings) {
    final colors = Theme.of(context).colorScheme;
    if (state.isSearching) return const Center(child: YoinLoadingIndicator());
    if (state.error != null) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(strings.errorSearch, style: Theme.of(context).textTheme.bodyLarge?.copyWith(color: colors.error)),
            TextButton(onPressed: _search.retry, child: Text(strings.searchRetry)),
          ],
        ),
      );
    }
    final r = state.results;
    if (r == null) return const SizedBox.shrink();
    final empty = switch (state.filter) {
      LibrarySearchFilter.all => r.artists.isEmpty && r.albums.isEmpty && r.tracks.isEmpty,
      LibrarySearchFilter.artists => r.artists.isEmpty,
      LibrarySearchFilter.albums => r.albums.isEmpty,
      LibrarySearchFilter.songs => r.tracks.isEmpty,
    };
    if (empty) return EmptyStatePanel(message: strings.emptySearch);
    final bottom = SearchTokens.resultsBottomReserve + MediaQuery.paddingOf(context).bottom;
    final controller = _scrollFor(state.filter);
    switch (state.filter) {
      case LibrarySearchFilter.artists:
        return _grid(context, [for (final a in r.artists) LibraryArtistItem(a)], controller, bottom);
      case LibrarySearchFilter.albums:
        return _grid(context, [for (final a in r.albums) LibraryAlbumItem(a)], controller, bottom);
      case LibrarySearchFilter.songs:
        return ListView.builder(
          controller: controller,
          padding: EdgeInsets.only(bottom: bottom),
          itemCount: r.tracks.length,
          itemBuilder: (context, i) => LibrarySongRow(
            key: ValueKey('${r.tracks[i].id}#$i'),
            track: r.tracks[i],
            onTap: () => _playSong(r.tracks, i),
          ),
        );
      case LibrarySearchFilter.all:
        return _all(context, r, strings, controller, bottom);
    }
  }

  void _playSong(List<Track> tracks, int index) =>
      ref.read(playbackControllerProvider.notifier).playTracks(tracks, startIndex: index);

  Widget _grid(BuildContext context, List<LibraryItem> items, ScrollController controller, double bottom) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final width = constraints.maxWidth;
        final columns = libraryGridColumns(width);
        final cell = (width - 32 - LibraryTokens.gridGutterH * (columns - 1)) / columns;
        return GridView.builder(
          controller: controller,
          padding: EdgeInsets.fromLTRB(16, 8, 16, bottom),
          gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
            crossAxisCount: columns,
            crossAxisSpacing: LibraryTokens.gridGutterH,
            mainAxisSpacing: LibraryTokens.gridGutterV,
            mainAxisExtent: libraryCellExtent(cell, MediaQuery.textScalerOf(context)),
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
        if (tracks.isNotEmpty) LibrarySectionHeader(strings.searchSectionSongs),
        for (final (i, t) in tracks.indexed)
          LibrarySongRow(key: ValueKey('${t.id}#$i'), track: t, onTap: () => _playSong(r.tracks, i)),
      ],
    );
  }
}
