/// The search's All page and Memories list as Home's Jump Back In tiles
/// (owner 2026-11-20, #Yoin:#18, prototype v3.1; Android
/// ui/library/LibrarySearchTiles.kt): a Material shape behind each cover
/// (album Bun, song Circle, playlist Ghostish, artist a plain circle), a song
/// met through a note or an album through its memory's words as a 1×2 with
/// the words beside the cover and the hit inked like a marker pen. The model
/// is library_search_memories.dart.
library;

import 'dart:math' as math;
import 'dart:typed_data';

import 'package:flutter/material.dart';

import '../data/model/model.dart';
import '../data/release/release_marks.dart';
import '../data/search/memory_search.dart';
import '../data/search/search_match.dart';
import '../data/search/search_page.dart';
import '../detail/detail_strings.dart';
import '../detail/release_kind_labels.dart';
import '../home/home_album_memory_mark.dart';
import '../home/home_ui_state.dart' show HomeCardKind;
import '../home/widgets/elastic_press.dart';
import '../home/widgets/home_artwork.dart';
import '../memories/memories_ui_state.dart' show MemoryScoreKind;
import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/adaptive/yoin_window_info.dart';
import '../ui/component/component_strings.dart';
import '../ui/component/explicit_badge.dart';
import '../ui/component/seam/seam.dart';
import '../ui/shapes/cookie_unit.dart';
import 'library_search_memories.dart';
import 'library_strings.dart';

/// What a tile does when tapped: the surface's navigation and playback, bound once.
@immutable
final class SearchTileActions {
  const SearchTileActions({
    required this.onSong,
    required this.onSongLongPress,
    required this.onNote,
    required this.onAlbum,
    required this.onLedgerAlbum,
    required this.onArtist,
    required this.onPlaylist,
    required this.onShowAll,
  });

  final ValueChanged<Track> onSong;
  final ValueChanged<Track>? onSongLongPress;

  /// A note: play its song from where it was written; [track] = the returned song, if any.
  final void Function(NoteSearchHit note, Track? track) onNote;

  /// A returned album; [memory] non-null = it holds a memory, opened on the scrapbook.
  final void Function(Album album, MemoryAttachment? memory) onAlbum;

  /// An album known only from the ledger (another account's, or not returned): its scrapbook.
  final ValueChanged<SearchLedgerAlbum> onLedgerAlbum;
  final ValueChanged<Artist> onArtist;
  final ValueChanged<Playlist> onPlaylist;
  final ValueChanged<SearchSection> onShowAll;
}

/// The All page's tile columns (Android searchTileColumns): Home's three on a
/// phone (the Pura X outer screen and narrow windows too), the Library
/// grid's count wider — a foldable's inner screen, a landscape handset, a
/// MateBook window — between 3 and 8.
int searchTileColumns(YoinWindowInfo window) {
  if (window.layoutMode == LayoutMode.compact && !window.isCompactHeight) return SearchTokens.tileMinColumns;
  final minimum = window.isCompactHeight ? SearchTokens.tileMinCellCompactHeight : SearchTokens.tileMinCell;
  final fit = ((window.width - 2 * SearchTokens.tilePageSide + SearchTokens.tileGap) / (minimum + SearchTokens.tileGap))
      .floor();
  return fit.clamp(SearchTokens.tileMinColumns, SearchTokens.tileMaxColumns);
}

/// A cell's side for [columns] across [width].
double searchTileCell(double width, int columns) =>
    (width - 2 * SearchTokens.tilePageSide - SearchTokens.tileGap * (columns - 1)) / columns;

/// The All page: the best-match card, then each section as rows of tiles, best section first.
class SearchAllTiles extends StatefulWidget {
  const SearchAllTiles({
    super.key,
    required this.page,
    required this.query,
    required this.actions,
    required this.controller,
    required this.columns,
    this.providerName,
    this.bottom = SearchTokens.resultsBottomReserve,
  });

  final LibrarySearchPage page;
  final String query;
  final SearchTileActions actions;
  final ScrollController controller;
  final int columns;

  /// The service's name for "N less relevant from Spotify"; null hides that line.
  final String? providerName;
  final double bottom;

  @override
  State<SearchAllTiles> createState() => _SearchAllTilesState();
}

class _SearchAllTilesState extends State<SearchAllTiles> {
  /// Rows already shown once: only a row new to the page fades in (not one scrolled back into view).
  final _seen = <String>{};
  late SearchQuery _query = SearchQuery(widget.query);

  @override
  void didUpdateWidget(SearchAllTiles old) {
    super.didUpdateWidget(old);
    if (old.query != widget.query) _query = SearchQuery(widget.query);
  }

  @override
  Widget build(BuildContext context) {
    final strings = LibraryStrings.of(context);
    final page = widget.page;
    return LayoutBuilder(
      builder: (context, constraints) {
        final cell = searchTileCell(constraints.maxWidth, widget.columns);
        Widget entering(String key, Widget child) =>
            _Entrance(key: ValueKey(key), animate: _seen.add(key), child: child);
        final children = <Widget>[
          if (page.best case final best?)
            entering('best:${best.key}', _BestMatchCard(tile: best, query: _query, actions: widget.actions)),
          for (final section in page.sections)
            if (section.rows.isNotEmpty) ...[
              _SectionHeader(
                key: ValueKey('head-${section.section.name}'),
                title: _sectionTitle(strings, section.section),
                showAll: section.hasMore,
                onShowAll: () => widget.actions.onShowAll(section.section),
              ),
              for (final row in section.rows)
                entering(
                  'row-${section.section.name}-${row.key}',
                  _TileRow(row: row, cell: cell, query: _query, actions: widget.actions),
                ),
              if (section.folded > 0 && widget.providerName != null)
                entering(
                  'folded-${section.section.name}',
                  _FoldedNote(
                    text: strings.searchFolded(section.folded, widget.providerName!),
                    showAll: strings.searchShowAll,
                    onTap: () => widget.actions.onShowAll(section.section),
                  ),
                ),
            ],
        ];
        return ListView(
          controller: widget.controller,
          padding: EdgeInsets.only(top: SearchTokens.tilePageTop, bottom: widget.bottom),
          children: children,
        );
      },
    );
  }
}

/// The Memories chip: every note and memory the query met, one full-width 1×2 per row.
class SearchMemoriesList extends StatefulWidget {
  const SearchMemoriesList({
    super.key,
    required this.page,
    required this.query,
    required this.actions,
    required this.controller,
    this.bottom = SearchTokens.resultsBottomReserve,
  });

  final LibrarySearchPage page;
  final String query;
  final SearchTileActions actions;
  final ScrollController controller;
  final double bottom;

  @override
  State<SearchMemoriesList> createState() => _SearchMemoriesListState();
}

class _SearchMemoriesListState extends State<SearchMemoriesList> {
  final _seen = <String>{};
  late SearchQuery _query = SearchQuery(widget.query);

  @override
  void didUpdateWidget(SearchMemoriesList old) {
    super.didUpdateWidget(old);
    if (old.query != widget.query) _query = SearchQuery(widget.query);
  }

  @override
  Widget build(BuildContext context) {
    final strings = LibraryStrings.of(context);
    final theme = Theme.of(context);
    final memories = widget.page.memories;
    if (memories.isEmpty) {
      return Padding(
        padding: const EdgeInsets.all(SearchTokens.memoriesEmptyPadding),
        child: Center(
          child: Text(
            strings.searchNoMemories,
            textAlign: TextAlign.center,
            style: theme.textTheme.bodyLarge!.copyWith(color: theme.colorScheme.onSurfaceVariant),
          ),
        ),
      );
    }
    return LayoutBuilder(
      builder: (context, constraints) {
        final cell = searchTileCell(constraints.maxWidth, SearchTokens.tileMinColumns);
        final rowWidth = constraints.maxWidth - 2 * SearchTokens.tilePageSide;
        return ListView.separated(
          controller: widget.controller,
          padding: EdgeInsets.fromLTRB(
            SearchTokens.tilePageSide,
            SearchTokens.tilePageTop,
            SearchTokens.tilePageSide,
            widget.bottom,
          ),
          itemCount: memories.length,
          separatorBuilder: (_, _) => const SizedBox(height: SearchTokens.tileRowGap),
          itemBuilder: (context, i) {
            final tile = memories[i];
            return _Entrance(
              key: ValueKey(tile.key),
              animate: _seen.add(tile.key),
              child: _Tile(
                tile: tile,
                cell: cell,
                width: rowWidth,
                query: _query,
                actions: widget.actions,
                textLines: SearchTokens.memoriesTextLines,
              ),
            );
          },
        );
      },
    );
  }
}

String _sectionTitle(LibraryStrings s, SearchSection section) => switch (section) {
  SearchSection.songs => s.searchSectionSongs,
  SearchSection.albums => s.searchSectionAlbums,
  SearchSection.artists => s.searchSectionArtists,
  SearchSection.playlists => s.searchSectionPlaylists,
};

/// A row or card new to the page fades in on the effects spring; reduced motion shows it at once.
class _Entrance extends StatefulWidget {
  const _Entrance({super.key, required this.animate, required this.child});

  final bool animate;
  final Widget child;

  @override
  State<_Entrance> createState() => _EntranceState();
}

class _EntranceState extends State<_Entrance> with SingleTickerProviderStateMixin {
  late final SpringValue _alpha = SpringValue(
    vsync: this,
    initial: widget.animate ? 0 : 1,
    spring: YoinSprings.defaultEffects,
  );

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (MediaQuery.disableAnimationsOf(context)) {
      _alpha.snapTo(1);
    } else {
      _alpha.animateTo(1);
    }
  }

  @override
  void dispose() {
    _alpha.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => FadeTransition(opacity: _alpha.animation, child: widget.child);
}

class _SectionHeader extends StatelessWidget {
  const _SectionHeader({super.key, required this.title, required this.showAll, required this.onShowAll});

  final String title;
  final bool showAll;
  final VoidCallback onShowAll;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final style = theme.textTheme.labelLarge!;
    return Padding(
      padding: const EdgeInsetsDirectional.only(
        start: SearchTokens.tilePageSide,
        end: SearchTokens.tileHeaderEnd,
        top: SearchTokens.sectionHeaderTop,
        bottom: SearchTokens.sectionHeaderBottom,
      ),
      child: Row(
        children: [
          Expanded(
            child: SeamFade(
              fontSize: style.fontSize ?? 14,
              child: Text(title, style: style.copyWith(color: theme.colorScheme.onSurfaceVariant)),
            ),
          ),
          if (showAll)
            GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTap: onShowAll,
              child: MouseRegion(
                cursor: SystemMouseCursors.click,
                child: Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: SearchTokens.showAllPaddingH,
                    vertical: SearchTokens.showAllPaddingV,
                  ),
                  child: SeamFade(
                    fontSize: style.fontSize ?? 14,
                    child: Text(
                      LibraryStrings.of(context).searchShowAll,
                      style: style.copyWith(color: theme.colorScheme.primary),
                    ),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

/// "7 less relevant from Spotify": the service's rows the query doesn't appear in, behind the section's chip.
class _FoldedNote extends StatelessWidget {
  const _FoldedNote({required this.text, required this.showAll, required this.onTap});

  final String text;
  final String showAll;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsetsDirectional.only(
        start: SearchTokens.tilePageSide,
        end: SearchTokens.tilePageSide,
        top: SearchTokens.foldedTop,
      ),
      child: Material(
        color: theme.colorScheme.surfaceContainer,
        shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(SearchTokens.foldedCorner)),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.symmetric(
              horizontal: SearchTokens.foldedPaddingH,
              vertical: SearchTokens.foldedPaddingV,
            ),
            child: Row(
              children: [
                Expanded(
                  child: Text(
                    text,
                    style: theme.textTheme.bodyMedium!.copyWith(color: theme.colorScheme.onSurfaceVariant),
                  ),
                ),
                Text(showAll, style: theme.textTheme.labelLarge!.copyWith(color: theme.colorScheme.primary)),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// One packed row: its 1×2 first, or after the singles when the row alternates.
class _TileRow extends StatelessWidget {
  const _TileRow({required this.row, required this.cell, required this.query, required this.actions});

  final LibrarySearchTileRow row;
  final double cell;
  final SearchQuery query;
  final SearchTileActions actions;

  @override
  Widget build(BuildContext context) {
    final wide = [
      for (final t in row.tiles)
        if (t.wide) t,
    ];
    final singles = [
      for (final t in row.tiles)
        if (!t.wide) t,
    ];
    final ordered = row.wideAtEnd ? [...singles, ...wide] : [...wide, ...singles];
    return Padding(
      padding: const EdgeInsetsDirectional.only(
        start: SearchTokens.tilePageSide,
        end: SearchTokens.tilePageSide,
        bottom: SearchTokens.tileRowGap,
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        spacing: SearchTokens.tileGap,
        children: [
          for (final tile in ordered)
            _Tile(
              key: ValueKey(tile.key),
              tile: tile,
              cell: cell,
              width: tile.wide ? cell * 2 + SearchTokens.tileGap : cell,
              query: query,
              actions: actions,
            ),
        ],
      ),
    );
  }
}

/// A ledger album's cover: its stored key; a server-relative one only resolves in the account in use.
CoverRef? _ledgerCover(SearchLedgerAlbum? album, {required bool sameAccount}) {
  final ref = CoverRef.fromStorageKey(album?.coverKey);
  if (ref == null) return null;
  return ref is CoverUrl || sameAccount ? ref : null;
}

HomeAlbumMemoryMark _homeMark(LedgerMark mark) => HomeAlbumMemoryMark(
  kind: switch (mark.kind) {
    LedgerMarkKind.albumRating => MemoryScoreKind.albumRating,
    LedgerMarkKind.trackAverage => MemoryScoreKind.averageTrackRating,
    LedgerMarkKind.notesOnly => MemoryScoreKind.none,
  },
  scoreText: mark.score == null ? null : _score(mark.score!),
  noteCount: mark.noteCount,
);

String _score(double score) => score.toStringAsFixed(1);

String _noteTime(int positionMs) {
  final seconds = positionMs ~/ 1000;
  return '${seconds ~/ 60}:${(seconds % 60).toString().padLeft(2, '0')}';
}

class _Tile extends StatelessWidget {
  const _Tile({
    super.key,
    required this.tile,
    required this.cell,
    required this.width,
    required this.query,
    required this.actions,
    this.textLines = SearchTokens.tileTextLines,
  });

  final LibrarySearchTile tile;
  final double cell;
  final double width;
  final SearchQuery query;
  final SearchTileActions actions;
  final int textLines;

  @override
  Widget build(BuildContext context) {
    switch (tile) {
      case SearchSongTile(:final track, :final note):
        return _TileFrame(
          width: width,
          cell: cell,
          art: BackdropCover(cover: track.coverArt, kind: HomeCardKind.song, size: cell),
          side: note == null ? null : _NoteSide(note: note, lines: textLines),
          title: track.title ?? '',
          explicit: track.isExplicit,
          subtitle: track.artist ?? '',
          query: query,
          onTap: () => note != null ? actions.onNote(note, track) : actions.onSong(track),
          onLongPress: actions.onSongLongPress == null ? null : () => actions.onSongLongPress!(track),
        );
      case SearchNoteTile(:final note):
        return _TileFrame(
          width: width,
          cell: cell,
          art: BackdropCover(
            cover: _ledgerCover(note.album, sameAccount: !note.isOtherAccount),
            kind: HomeCardKind.song,
            size: cell,
          ),
          side: _NoteSide(note: note, lines: textLines),
          title: note.trackTitle ?? '',
          subtitle: note.album?.artist ?? '',
          otherAccount: note.isOtherAccount,
          query: query,
          onTap: () => actions.onNote(note, null),
        );
      case SearchAlbumTile(:final album, :final memory, :final hit):
        final marks = allowsCoverMarks(album.id.provider);
        final mark = memory?.mark;
        return _TileFrame(
          width: width,
          cell: cell,
          // The kind, version tag and "E" on the shape's band; the name alone under it (PR #343).
          art: BackdropCover(
            cover: album.coverArt,
            kind: HomeCardKind.album,
            size: cell,
            mark: mark != null && marks ? _homeMark(mark) : null,
            version: album.version,
            explicit: album.isExplicit,
            kindTag: releaseKindTag(DetailStrings.of(context), album.releaseType),
          ),
          side: hit == null ? null : _MemorySide(hit: hit, mark: mark ?? hit.mark, lines: textLines),
          title: ReleaseMarks.nameWithoutKindSuffix(album.name, album.releaseType),
          describeExplicit: album.isExplicit,
          subtitle: album.artist ?? '',
          inlineMark: marks ? null : mark,
          query: query,
          onTap: () => actions.onAlbum(album, memory),
        );
      case SearchMemoryTile(:final hit):
        final marks = allowsCoverMarks(hit.album.provider);
        final mark = hit.mark;
        return _TileFrame(
          width: width,
          cell: cell,
          art: BackdropCover(
            cover: _ledgerCover(hit.album, sameAccount: !hit.isOtherAccount),
            kind: HomeCardKind.album,
            size: cell,
            mark: mark != null && marks ? _homeMark(mark) : null,
          ),
          side: tile.wide ? _MemorySide(hit: hit, mark: mark, lines: textLines) : null,
          title: hit.album.name,
          subtitle: hit.album.artist,
          inlineMark: marks ? null : mark,
          otherAccount: hit.isOtherAccount,
          query: query,
          onTap: () => actions.onLedgerAlbum(hit.album),
        );
      case SearchArtistTile(:final artist):
        return _TileFrame(
          width: width,
          cell: cell,
          art: BackdropCover(cover: artist.coverArt, kind: HomeCardKind.artist, size: cell),
          title: artist.name,
          subtitle: null,
          centered: true,
          query: query,
          onTap: () => actions.onArtist(artist),
        );
      case SearchPlaylistTile(:final playlist):
        return _TileFrame(
          width: width,
          cell: cell,
          art: BackdropCover(cover: playlist.coverArt, kind: HomeCardKind.playlist, size: cell),
          title: playlist.name,
          subtitle: playlist.owner,
          query: query,
          onTap: () => actions.onPlaylist(playlist),
        );
    }
  }
}

/// A tile: the cover [cell] square (with [side] beside it on a 1×2), then the
/// caption under both. The whole tile is one press target; the cover takes
/// the press, as on Home.
class _TileFrame extends StatelessWidget {
  const _TileFrame({
    required this.width,
    required this.cell,
    required this.art,
    required this.title,
    required this.subtitle,
    required this.query,
    required this.onTap,
    this.side,
    this.onLongPress,
    this.inlineMark,
    this.otherAccount = false,
    this.centered = false,
    this.explicit = false,
    this.describeExplicit = false,
  });

  /// The song is marked explicit: the "E" follows the title (ExplicitTitle).
  final bool explicit;

  /// The album is marked explicit (its "E" is on the cover's band): the title still says so.
  final bool describeExplicit;

  final double width;
  final double cell;
  final Widget art;
  final String title;
  final String? subtitle;
  final SearchQuery query;
  final VoidCallback onTap;
  final Widget? side;
  final VoidCallback? onLongPress;
  final LedgerMark? inlineMark;
  final bool otherAccount;
  final bool centered;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final subtitle = this.subtitle;
    final hasSubtitle = subtitle != null && subtitle.trim().isNotEmpty;
    final align = centered ? CrossAxisAlignment.center : CrossAxisAlignment.start;
    final rowAlign = centered ? MainAxisAlignment.center : MainAxisAlignment.start;
    Widget content = SizedBox(
      width: width,
      child: ElasticPress(
        onTap: onTap,
        child: Column(
          crossAxisAlignment: align,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              spacing: SearchTokens.tileGap,
              children: [
                art,
                if (side != null)
                  Expanded(
                    child: Padding(
                      padding: const EdgeInsets.only(top: SearchTokens.tileSideTop),
                      child: side,
                    ),
                  ),
              ],
            ),
            const SizedBox(height: SearchTokens.tileCaptionGap),
            Row(
              mainAxisAlignment: rowAlign,
              children: [
                if (inlineMark != null)
                  Padding(
                    padding: const EdgeInsetsDirectional.only(end: SearchTokens.inlineMarkGap),
                    child: InlineMemoryMark(mark: inlineMark!),
                  ),
                Flexible(
                  child: _explicitTitle(
                    context,
                    ExplicitTitle(
                      text: title,
                      explicit: explicit,
                      style: theme.textTheme.titleSmall,
                      child: MarkedText(
                        text: title,
                        marks: [query.match(title).originalRange],
                        style: theme.textTheme.titleSmall!.copyWith(color: theme.colorScheme.onSurface),
                        maxLines: 1,
                      ),
                    ),
                  ),
                ),
              ],
            ),
            if (hasSubtitle || otherAccount)
              Row(
                mainAxisAlignment: rowAlign,
                children: [
                  if (otherAccount)
                    const Padding(
                      padding: EdgeInsetsDirectional.only(end: SearchTokens.otherAccountBadgeGap),
                      child: _OtherAccountBadge(),
                    ),
                  if (hasSubtitle)
                    Flexible(
                      child: MarkedText(
                        text: subtitle,
                        marks: [query.match(subtitle).originalRange],
                        style: theme.textTheme.bodySmall!.copyWith(color: theme.colorScheme.onSurfaceVariant),
                        maxLines: 1,
                      ),
                    ),
                ],
              ),
          ],
        ),
      ),
    );
    if (onLongPress != null) content = GestureDetector(onLongPress: onLongPress, child: content);
    return content;
  }

  /// An album's "Explicit" is said by its title (the E is on the band, which a small tile may leave out).
  Widget _explicitTitle(BuildContext context, Widget title) => !describeExplicit
      ? title
      : Semantics(
          label: '${this.title}, ${ComponentStrings.of(context).explicitBadge}',
          excludeSemantics: true,
          child: title,
        );
}

/// A note beside its song's cover: "Your note", where it was written, and the note with the hit inked.
class _NoteSide extends StatelessWidget {
  const _NoteSide({required this.note, required this.lines});

  final NoteSearchHit note;
  final int lines;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final position = note.positionMs;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      spacing: SearchTokens.tileSideSpacing,
      children: [
        _SideLabel(LibraryStrings.of(context).searchYourNote),
        if (position != null)
          _SideText(_noteTime(position), theme.textTheme.headlineSmall!.copyWith(color: theme.colorScheme.tertiary)),
        _FragmentText(fragment: note.fragment, lines: lines),
      ],
    );
  }
}

/// A memory's words beside its album's cover: its score and mark, its title, the words with the hit inked.
class _MemorySide extends StatelessWidget {
  const _MemorySide({required this.hit, required this.mark, required this.lines});

  final MemorySearchHit hit;
  final LedgerMark? mark;
  final int lines;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final mark = this.mark;
    final score = mark?.score ?? hit.albumScore;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      spacing: SearchTokens.tileSideSpacing,
      children: [
        if (mark != null || score != null)
          Row(
            children: [
              if (mark != null)
                Padding(
                  padding: const EdgeInsetsDirectional.only(end: SearchTokens.sideMarkGap),
                  child: InlineMemoryMark(mark: mark, size: SearchTokens.sideMarkSize),
                ),
              if (score != null)
                _SideText(_score(score), theme.textTheme.headlineSmall!.copyWith(color: theme.colorScheme.tertiary)),
            ],
          ),
        _SideLabel(hit.title ?? LibraryStrings.of(context).searchYourMemory),
        _FragmentText(fragment: hit.fragment, lines: lines),
      ],
    );
  }
}

class _SideLabel extends StatelessWidget {
  const _SideLabel(this.text);
  final String text;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return _SideText(text, theme.textTheme.labelMedium!.copyWith(color: theme.colorScheme.onSurfaceVariant));
  }
}

class _SideText extends StatelessWidget {
  const _SideText(this.text, this.style);
  final String text;
  final TextStyle style;

  @override
  Widget build(BuildContext context) => SeamFade(
    fontSize: style.fontSize ?? 14,
    child: Text(text, style: style, maxLines: 1, overflow: TextOverflow.ellipsis),
  );
}

class _FragmentText extends StatelessWidget {
  const _FragmentText({required this.fragment, required this.lines});

  final SearchFragment fragment;
  final int lines;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return MarkedText(
      text: fragment.text,
      marks: [fragment.highlight],
      style: theme.textTheme.bodyMedium!.copyWith(color: theme.colorScheme.onSurface),
      maxLines: lines,
    );
  }
}

/// The best match, large: Home's tile at hero size, what it is, its name, and a way in.
class _BestMatchCard extends StatelessWidget {
  const _BestMatchCard({required this.tile, required this.query, required this.actions});

  final LibrarySearchTile tile;
  final SearchQuery query;
  final SearchTileActions actions;

  @override
  Widget build(BuildContext context) {
    final strings = LibraryStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final (String title, String subtitle, String kind, Widget art, VoidCallback open) = switch (tile) {
      SearchSongTile(:final track) => (
        track.title ?? '',
        track.artist ?? '',
        strings.searchSectionSongs,
        BackdropCover(cover: track.coverArt, kind: HomeCardKind.song, size: SearchTokens.bestArt),
        () => actions.onSong(track),
      ),
      SearchAlbumTile(:final album, :final memory) => (
        ReleaseMarks.nameWithoutKindSuffix(album.name, album.releaseType),
        album.artist ?? '',
        strings.searchSectionAlbums,
        BackdropCover(
          cover: album.coverArt,
          kind: HomeCardKind.album,
          size: SearchTokens.bestArt,
          mark: memory?.mark != null && allowsCoverMarks(album.id.provider) ? _homeMark(memory!.mark!) : null,
        ),
        () => actions.onAlbum(album, memory),
      ),
      SearchArtistTile(:final artist) => (
        artist.name,
        '',
        strings.searchSectionArtists,
        BackdropCover(cover: artist.coverArt, kind: HomeCardKind.artist, size: SearchTokens.bestArt),
        () => actions.onArtist(artist),
      ),
      SearchPlaylistTile(:final playlist) => (
        playlist.name,
        playlist.owner ?? '',
        strings.searchSectionPlaylists,
        BackdropCover(cover: playlist.coverArt, kind: HomeCardKind.playlist, size: SearchTokens.bestArt),
        () => actions.onPlaylist(playlist),
      ),
      // Never the card: a note or a memory alone.
      SearchNoteTile() || SearchMemoryTile() => ('', '', '', const SizedBox.shrink(), () {}),
    };
    final memory = tile is SearchAlbumTile ? (tile as SearchAlbumTile).memory : null;
    final memoryScore = memory?.albumScore ?? memory?.mark?.score;
    final memoryLine = [?memory?.title, if (memoryScore != null) _score(memoryScore)].join(' · ');
    return Padding(
      padding: const EdgeInsets.fromLTRB(
        SearchTokens.tilePageSide,
        SearchTokens.bestMarginTop,
        SearchTokens.tilePageSide,
        SearchTokens.bestMarginBottom,
      ),
      child: ElasticPress(
        onTap: open,
        child: DecoratedBox(
          decoration: ShapeDecoration(
            color: colors.surfaceContainer,
            shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(SearchTokens.bestCorner)),
          ),
          child: Padding(
            padding: const EdgeInsets.all(SearchTokens.bestPadding),
            child: Row(
              children: [
                art,
                Expanded(
                  child: Padding(
                    padding: const EdgeInsetsDirectional.only(start: SearchTokens.bestTextStart),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        _SideText(kind, theme.textTheme.labelMedium!.copyWith(color: colors.onSurfaceVariant)),
                        ExplicitTitle(
                          text: title,
                          explicit: switch (tile) {
                            SearchSongTile(:final track) => track.isExplicit,
                            SearchAlbumTile(:final album) => album.isExplicit,
                            _ => false,
                          },
                          style: theme.textTheme.headlineSmall,
                          maxLines: 2,
                          child: MarkedText(
                            text: title,
                            marks: [query.match(title).originalRange],
                            style: theme.textTheme.headlineSmall!.copyWith(color: colors.onSurface),
                            maxLines: 2,
                          ),
                        ),
                        if (subtitle.trim().isNotEmpty)
                          _SideText(subtitle, theme.textTheme.bodyMedium!.copyWith(color: colors.onSurfaceVariant)),
                        if (memory != null)
                          Padding(
                            padding: const EdgeInsets.only(top: SearchTokens.bestMemoryTop),
                            child: Row(
                              children: [
                                if (memory.mark case final mark?)
                                  Padding(
                                    padding: const EdgeInsetsDirectional.only(end: SearchTokens.sideMarkGap),
                                    child: InlineMemoryMark(mark: mark),
                                  ),
                                if (memoryLine.isNotEmpty)
                                  Flexible(
                                    child: _SideText(
                                      memoryLine,
                                      theme.textTheme.bodyMedium!.copyWith(color: colors.tertiary),
                                    ),
                                  ),
                              ],
                            ),
                          ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// The memory mark beside a title instead of on the cover: Spotify's covers
/// may carry nothing ([allowsCoverMarks]). The same cookie as the sticker:
/// filled = your score, a line = your tracks' average, dashed = notes only.
class InlineMemoryMark extends StatelessWidget {
  const InlineMemoryMark({super.key, required this.mark, this.size = SearchTokens.inlineMarkSize});

  final LedgerMark mark;
  final double size;

  @override
  Widget build(BuildContext context) => SizedBox.square(
    dimension: size,
    child: CustomPaint(painter: _InlineMarkPainter(mark.kind, Theme.of(context).colorScheme.tertiary)),
  );
}

class _InlineMarkPainter extends CustomPainter {
  _InlineMarkPainter(this.kind, this.ink);

  final LedgerMarkKind kind;
  final Color ink;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..isAntiAlias = true
      ..color = ink;
    if (kind == LedgerMarkKind.albumRating) {
      canvas.drawPath(_cookie(size.shortestSide, Offset.zero), paint);
      return;
    }
    const stroke = SearchTokens.inlineMarkStroke;
    final outline = _cookie(size.shortestSide - stroke, const Offset(stroke / 2, stroke / 2));
    paint
      ..style = PaintingStyle.stroke
      ..strokeWidth = stroke;
    if (kind == LedgerMarkKind.trackAverage) {
      canvas.drawPath(outline, paint);
      return;
    }
    // Notes only: whole dashes round the loop.
    for (final metric in outline.computeMetrics()) {
      const period = SearchTokens.inlineMarkDash + SearchTokens.inlineMarkDashGap;
      final count = math.max(1, (metric.length / period).round());
      final step = metric.length / count;
      final on = step * SearchTokens.inlineMarkDash / period;
      for (var i = 0; i < count; i++) {
        final start = i * step + (step - on) / 2;
        canvas.drawPath(metric.extractPath(start, start + on), paint);
      }
    }
  }

  static Path _cookie(double side, Offset origin) {
    final m = Float64List(16)
      ..[0] = side
      ..[5] = side
      ..[10] = 1
      ..[12] = origin.dx
      ..[13] = origin.dy
      ..[15] = 1;
    return CookieUnit.path.transform(m);
  }

  @override
  bool shouldRepaint(_InlineMarkPainter old) => old.kind != kind || old.ink != ink;
}

/// Another account's: a light badge (the account in use carries none).
class _OtherAccountBadge extends StatelessWidget {
  const _OtherAccountBadge();

  @override
  Widget build(BuildContext context) => SizedBox.square(
    dimension: SearchTokens.otherAccountBadge,
    child: DecoratedBox(
      decoration: BoxDecoration(color: Theme.of(context).colorScheme.secondary, shape: BoxShape.circle),
    ),
  );
}

/// [text] with [marks] inked like a marker pen: a band over the lower part of
/// each line the hit covers, a little slanted and ragged, in the theme's
/// tertiary container, under the glyphs (Android MarkedText).
class MarkedText extends StatelessWidget {
  const MarkedText({super.key, required this.text, required this.marks, required this.style, required this.maxLines});

  final String text;
  final List<SearchRange> marks;
  final TextStyle style;
  final int maxLines;

  @override
  Widget build(BuildContext context) {
    final shown = [
      for (final m in marks)
        if (!m.isEmpty && m.start < text.length) m,
    ];
    final label = Text(text, style: style, maxLines: maxLines, overflow: TextOverflow.ellipsis);
    final faded = SeamFade(fontSize: style.fontSize ?? 14, child: label);
    if (shown.isEmpty) return faded;
    final ink = Theme.of(context).colorScheme.tertiaryContainer;
    final full = DefaultTextStyle.of(context).style.merge(style);
    final scaler = MediaQuery.textScalerOf(context);
    final direction = Directionality.of(context);
    final locale = Localizations.maybeLocaleOf(context);
    return LayoutBuilder(
      builder: (context, constraints) => CustomPaint(
        painter: _MarkerPainter(
          text: text,
          style: full,
          maxLines: maxLines,
          maxWidth: constraints.maxWidth,
          scaler: scaler,
          direction: direction,
          locale: locale,
          marks: shown,
          ink: ink,
        ),
        child: faded,
      ),
    );
  }
}

class _MarkerPainter extends CustomPainter {
  _MarkerPainter({
    required this.text,
    required this.style,
    required this.maxLines,
    required this.maxWidth,
    required this.scaler,
    required this.direction,
    required this.locale,
    required this.marks,
    required this.ink,
  });

  final String text;
  final TextStyle style;
  final int maxLines;
  final double maxWidth;
  final TextScaler scaler;
  final TextDirection direction;
  final Locale? locale;
  final List<SearchRange> marks;
  final Color ink;

  @override
  void paint(Canvas canvas, Size size) {
    final painter = TextPainter(
      text: TextSpan(text: text, style: style),
      textDirection: direction,
      textScaler: scaler,
      maxLines: maxLines,
      ellipsis: '…',
      locale: locale,
    )..layout(maxWidth: maxWidth.isFinite ? maxWidth : double.infinity);
    final fill = Paint()
      ..isAntiAlias = true
      ..color = ink;
    for (final mark in marks) {
      final end = math.min(mark.end, text.length);
      // One band per line: the boxes a line holds, joined.
      final lines = <double, Rect>{};
      for (final box in painter.getBoxesForSelection(TextSelection(baseOffset: mark.start, extentOffset: end))) {
        final rect = box.toRect();
        final key = rect.top.roundToDouble();
        lines[key] = lines[key]?.expandToInclude(rect) ?? rect;
      }
      for (final r in lines.values) {
        final h = r.height;
        final left = r.left - h * SearchTokens.markerOverhang;
        final right = r.right + h * SearchTokens.markerOverhang;
        final path = Path()
          ..moveTo(left, r.top + h * SearchTokens.markerTopStart)
          ..lineTo(right, r.top + h * SearchTokens.markerTopEnd)
          ..lineTo(right - h * SearchTokens.markerBottomEndInset, r.bottom - h * SearchTokens.markerBottomEnd)
          ..lineTo(left + h * SearchTokens.markerBottomStartInset, r.bottom - h * SearchTokens.markerBottomStart)
          ..close();
        canvas.drawPath(path, fill);
        canvas.drawPath(
          path,
          Paint()
            ..isAntiAlias = true
            ..color = ink
            ..style = PaintingStyle.stroke
            ..strokeJoin = StrokeJoin.round
            ..strokeWidth = h * SearchTokens.markerStroke,
        );
      }
    }
    painter.dispose();
  }

  @override
  bool shouldRepaint(_MarkerPainter old) =>
      old.text != text ||
      old.style != style ||
      old.maxLines != maxLines ||
      old.maxWidth != maxWidth ||
      old.scaler != scaler ||
      old.direction != direction ||
      old.ink != ink ||
      !_sameMarks(old.marks, marks);

  static bool _sameMarks(List<SearchRange> a, List<SearchRange> b) {
    if (a.length != b.length) return false;
    for (var i = 0; i < a.length; i++) {
      if (a[i] != b[i]) return false;
    }
    return true;
  }
}
