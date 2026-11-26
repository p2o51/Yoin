/// Memories and notes in Library search (docs/search.md, #Yoin:#18; Android
/// data/search/SearchPage.kt + MemorySearchSource.kt): where the search reads
/// the ledger, and the All page in the app's own models. The rules —
/// matching, ranking, section order, relevance folding, the best-match card,
/// packing — are the search core's (lib/data/search/, the :shared port
/// checked against memory-search.json); this file hands it the service's rows
/// by name and puts the Track / Album / Artist / Playlist back on the tiles
/// it returns.
library;

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/memory/memory_copy_cache.dart';
import '../data/memory/memory_ledger.dart';
import '../data/model/model.dart';
import '../data/search/memory_search.dart';
import '../data/search/search_match.dart';
import '../data/search/search_page.dart';
import '../memories/ledger/memory_ledger_view_model.dart';
import '../theme/tokens.dart';

/// What search reads of the ledger at once: every counted account's entries
/// (#178), the AI-written memory titles by album key, and the account in use.
@immutable
final class MemorySearchSnapshot {
  const MemorySearchSnapshot({this.entries = const [], this.aiTitles = const {}, this.activeProfileId});

  static const empty = MemorySearchSnapshot();

  final List<SearchLedgerEntry> entries;
  final Map<String, String> aiTitles;
  final String? activeProfileId;

  /// The memories and notes [query] meets, best first.
  MemorySearchMatches search(String query) =>
      searchMemoryLedger(entries, SearchQuery(query), activeProfileId, aiTitles: aiTitles);
}

/// Where search reads the ledger (Android MemorySearchPort on Room).
abstract interface class MemorySearchPort {
  /// The ledger now; [MemorySearchSnapshot.empty] when it can't be read.
  Future<MemorySearchSnapshot> read();
}

/// The app's ledger ([memoryLedgerProvider], the one All Memories and Home's
/// grid read, kept fresh by every write) and each account's AI titles
/// (MemoryCopyCache, read each time: a title lands without the ledger
/// changing). Local only; nothing here asks a service.
class LedgerMemorySearchPort implements MemorySearchPort {
  LedgerMemorySearchPort(this._ref);

  final Ref _ref;
  List<MemoryLedgerEntry>? _mappedFrom;
  List<SearchLedgerEntry> _mapped = const [];

  @override
  Future<MemorySearchSnapshot> read() async {
    try {
      final ledger = await _ref.read(memoryLedgerProvider.future);
      if (!identical(_mappedFrom, ledger.entries)) {
        _mapped = [for (final e in ledger.entries) SearchLedgerEntry.fromLedger(e)];
        _mappedFrom = ledger.entries;
      }
      final kv = _ref.read(keyValueStoreProvider);
      final titles = <String, String>{};
      for (final profileId in {for (final e in _mapped) e.profileId}) {
        if (profileId.isEmpty) continue;
        try {
          final rows = await MemoryCopyCache.shared(kv, profileId).syncRead();
          for (final MapEntry(key: id, value: row) in rows.entries) {
            final title = row.title?.trim();
            if (title != null && title.isNotEmpty) titles[ledgerAlbumKey(profileId, id.provider, id.rawId)] = title;
          }
        } catch (_) {
          // That account's AI titles only.
        }
      }
      return MemorySearchSnapshot(entries: _mapped, aiTitles: titles, activeProfileId: ledger.activeProfileId);
    } catch (_) {
      return MemorySearchSnapshot.empty;
    }
  }
}

final memorySearchPortProvider = Provider<MemorySearchPort>((ref) => LedgerMemorySearchPort(ref));

// ---------------------------------------------------------------- the page

/// One tile of the All page or the Memories list. [wide] tiles take two cells.
sealed class LibrarySearchTile {
  const LibrarySearchTile();

  /// Stable across reloads: the list key.
  String get key;
  bool get wide => false;
}

/// A song the service returned. [note] = one of the listener's notes on it
/// the query met: the tile shows it beside the cover.
final class SearchSongTile extends LibrarySearchTile {
  const SearchSongTile(this.track, this.note);
  final Track track;
  final NoteSearchHit? note;
  @override
  String get key => 'song:${track.id}';
  @override
  bool get wide => note != null;
}

/// A note the query met, on a song the service didn't return (another account's, or not matched there).
final class SearchNoteTile extends LibrarySearchTile {
  const SearchNoteTile(this.note);
  final NoteSearchHit note;
  @override
  String get key => 'note:${note.entryId}';
  @override
  bool get wide => true;
}

/// An album the service returned. [memory] = the memory kept on it in the
/// account in use (its mark); [hit] = that memory's own words met the query.
final class SearchAlbumTile extends LibrarySearchTile {
  const SearchAlbumTile(this.album, this.memory, this.hit);
  final Album album;
  final MemoryAttachment? memory;
  final MemorySearchHit? hit;
  @override
  String get key => 'album:${album.id}';
  @override
  bool get wide => hit != null;
}

/// A memory the query met on an album the service didn't return (another account's, or only in the ledger).
final class SearchMemoryTile extends LibrarySearchTile {
  const SearchMemoryTile(this.hit);
  final MemorySearchHit hit;
  @override
  String get key => 'memory:${hit.album.key}';
  @override
  bool get wide => hit.field != MemoryHitField.album;
}

final class SearchArtistTile extends LibrarySearchTile {
  const SearchArtistTile(this.artist);
  final Artist artist;
  @override
  String get key => 'artist:${artist.id}';
}

final class SearchPlaylistTile extends LibrarySearchTile {
  const SearchPlaylistTile(this.playlist);
  final Playlist playlist;
  @override
  String get key => 'playlist:${playlist.id}';
}

/// One row of tiles: at most one wide tile; [wideAtEnd] puts it after the singles.
@immutable
final class LibrarySearchTileRow {
  const LibrarySearchTileRow(this.tiles, {this.wideAtEnd = false});
  final List<LibrarySearchTile> tiles;
  final bool wideAtEnd;

  /// The row's identity: its tiles' keys.
  String get key => tiles.map((t) => t.key).join('|');
}

@immutable
final class LibrarySearchSection {
  const LibrarySearchSection({
    required this.section,
    required this.tileCount,
    required this.rows,
    required this.folded,
  });

  final SearchSection section;

  /// Every tile of the section (what its own chip has).
  final int tileCount;

  /// What All shows of it.
  final List<LibrarySearchTileRow> rows;

  /// The service's rows the query didn't meet, left out of All.
  final int folded;

  int get shown => rows.fold(0, (sum, r) => sum + r.tiles.length);

  /// More than All shows: the header offers Show all.
  bool get hasMore => tileCount > shown;
}

@immutable
final class LibrarySearchPage {
  const LibrarySearchPage({required this.sections, required this.best, required this.memories});

  static const empty = LibrarySearchPage(sections: [], best: null, memories: []);

  final List<LibrarySearchSection> sections;

  /// The large card the All page leads with: a song (no note), an album
  /// (with its memory), an artist or a playlist.
  final LibrarySearchTile? best;

  /// Every note and memory hit, best first: the Memories chip's list.
  final List<LibrarySearchTile> memories;

  bool get isEmpty => sections.isEmpty && memories.isEmpty;
}

/// Builds the All page for [query] from the service's [results], the ledger's
/// [memories] and [ledger] (the memories kept on the returned albums are read
/// from it). [columns] is the grid's width in cells (3 on a phone).
LibrarySearchPage buildLibrarySearchPage(
  String query,
  SearchResults? results,
  MemorySearchMatches memories,
  MemorySearchSnapshot ledger, {
  int columns = 3,
}) {
  final q = SearchQuery(query);
  if (q.isBlank) return LibrarySearchPage.empty;
  final tracks = results?.tracks ?? const <Track>[];
  final albums = results?.albums ?? const <Album>[];
  final artists = results?.artists ?? const <Artist>[];
  final playlists = results?.playlists ?? const <Playlist>[];
  final albumRows = [
    for (final a in albums) SearchAlbumRow(provider: a.id.provider, rawId: a.id.rawId, name: a.name, artist: a.artist),
  ];
  final attachments = memoryAttachments(
    [for (final a in albumRows) a.ref],
    ledger.entries,
    ledger.activeProfileId,
    aiTitles: ledger.aiTitles,
  );
  final layout = layoutSearchPage(
    q,
    SearchServiceRows(
      songs: [
        for (final t in tracks)
          SearchSongRow(provider: t.id.provider, rawId: t.id.rawId, title: t.title, artist: t.artist, album: t.album),
      ],
      albums: albumRows,
      artists: [for (final a in artists) SearchArtistRow(provider: a.id.provider, rawId: a.id.rawId, name: a.name)],
      playlists: [
        for (final p in playlists)
          SearchPlaylistRow(provider: p.id.provider, rawId: p.id.rawId, name: p.name, owner: p.owner),
      ],
    ),
    memories,
    attachments,
    ledger.activeProfileId,
    columns: columns,
    maxRows: SearchTokens.allMaxRows,
    maxWide: SearchTokens.allMaxWide,
  );
  LibrarySearchTile tile(SearchPageTile t) => switch (t.kind) {
    SearchTileKind.song => SearchSongTile(tracks[t.index], t.note),
    SearchTileKind.note => SearchNoteTile(t.note!),
    SearchTileKind.album => SearchAlbumTile(albums[t.index], t.attachment, t.hit),
    SearchTileKind.memory => SearchMemoryTile(t.hit!),
    SearchTileKind.artist => SearchArtistTile(artists[t.index]),
    SearchTileKind.playlist => SearchPlaylistTile(playlists[t.index]),
  };
  final best = layout.best;
  return LibrarySearchPage(
    sections: [
      for (final s in layout.sections)
        LibrarySearchSection(
          section: s.section,
          tileCount: s.tiles.length,
          rows: [
            for (final r in s.allRows) LibrarySearchTileRow([for (final t in r.tiles) tile(t)], wideAtEnd: r.wideAtEnd),
          ],
          folded: s.folded,
        ),
    ],
    best: best == null
        ? null
        : switch (best.kind) {
            SearchTileKind.song => SearchSongTile(tracks[best.index], null),
            SearchTileKind.album => SearchAlbumTile(albums[best.index], attachments[albumRows[best.index].ref], null),
            SearchTileKind.artist => SearchArtistTile(artists[best.index]),
            SearchTileKind.playlist => SearchPlaylistTile(playlists[best.index]),
            SearchTileKind.note || SearchTileKind.memory => null,
          },
    memories: [for (final t in layout.memories) tile(t)],
  );
}
