/// What the search's All page shows for one query (owner 2026-11-20,
/// prototype v3.1 in #Yoin:#18; :shared search/SearchPage.kt, ported against
/// memory-search.json): the service's rows and the listener's memories merged
/// into the same sections — no Memories section — as Home's Jump Back In
/// tiles. A song met through a note, an album met through its memory's words,
/// is a 1×2 tile; everything else a 1×1. Sections are ordered by how well
/// they met the query; inside one, the tiles interleave the way Home's shelf
/// does ([packSearchTiles]).
///
/// The service's rows go in as names only ([SearchServiceRows]) and come back
/// as indexes into those lists, so the screen keeps its own Track / Album /
/// Artist / Playlist.
library;

import '../../theme/tokens.dart' show SearchTokens;
import 'memory_search.dart';
import 'search_match.dart';

/// The All page's sections, in their default order.
enum SearchSection { songs, albums, artists, playlists }

/// A row the service returned, as search reads it: provider, raw id and names.
final class SearchSongRow {
  const SearchSongRow({required this.provider, required this.rawId, this.title, this.artist, this.album});
  final String provider;
  final String rawId;
  final String? title;
  final String? artist;
  final String? album;
  String get key => '$provider:$rawId';
}

final class SearchAlbumRow {
  const SearchAlbumRow({required this.provider, required this.rawId, required this.name, this.artist});
  final String provider;
  final String rawId;
  final String name;
  final String? artist;
  String get key => '$provider:$rawId';
  SearchAlbumRef get ref => (provider: provider, rawId: rawId);
}

final class SearchArtistRow {
  const SearchArtistRow({required this.provider, required this.rawId, required this.name});
  final String provider;
  final String rawId;
  final String name;
  String get key => '$provider:$rawId';
}

final class SearchPlaylistRow {
  const SearchPlaylistRow({required this.provider, required this.rawId, required this.name, this.owner});
  final String provider;
  final String rawId;
  final String name;
  final String? owner;
  String get key => '$provider:$rawId';
}

/// What the service answered, in its order.
final class SearchServiceRows {
  const SearchServiceRows({
    this.songs = const [],
    this.albums = const [],
    this.artists = const [],
    this.playlists = const [],
  });
  final List<SearchSongRow> songs;
  final List<SearchAlbumRow> albums;
  final List<SearchArtistRow> artists;
  final List<SearchPlaylistRow> playlists;
}

/// What a tile is. [note] and [memory] come from the ledger alone; the rest are service rows.
enum SearchTileKind { song, note, album, memory, artist, playlist }

/// One tile of a section. [index] points into [SearchServiceRows]' list of
/// its kind; -1 for a ledger tile. [wide] tiles take two cells.
final class SearchPageTile {
  const SearchPageTile({
    required this.kind,
    required this.key,
    required this.index,
    required this.score,
    required this.relevant,
    required this.wide,
    this.note,
    this.hit,
    this.attachment,
  });

  final SearchTileKind kind;

  /// Stable across reloads: the list key (`song:provider:id`, `note:entryId`,
  /// `album:…`, `memory:albumKey`, `artist:…`, `playlist:…`).
  final String key;
  final int index;
  final SearchScore score;

  /// The query met the row itself, or a note / memory joined to it. Ledger tiles always are.
  final bool relevant;
  final bool wide;

  /// Song: one of this account's notes on it the query met, shown beside the cover. Note: the note.
  final NoteSearchHit? note;

  /// Album: the memory's own words met the query, shown beside the cover. Memory: the hit.
  final MemorySearchHit? hit;

  /// Album: the memory kept on it in the account in use (its mark).
  final MemoryAttachment? attachment;
}

/// One row of packed tiles: at most one wide tile; [wideAtEnd] puts it after the singles (rows alternate).
final class PackedTileRow<T> {
  const PackedTileRow(this.tiles, {this.wideAtEnd = false});
  final List<T> tiles;
  final bool wideAtEnd;
}

final class SearchPageSection {
  const SearchPageSection({
    required this.section,
    required this.tiles,
    required this.allRows,
    required this.folded,
    required this.more,
    required this.score,
  });

  final SearchSection section;

  /// Every tile of the section, best first: what its own type chip lists (folded rows last).
  final List<SearchPageTile> tiles;

  /// What All shows of it, packed into rows.
  final List<PackedTileRow<SearchPageTile>> allRows;

  /// The service's rows the query didn't meet at all, left out of All (they still list under the type).
  final int folded;

  /// Tiles the All page had no room for.
  final int more;
  final SearchScore score;
}

/// The one result the All page leads with, in a large card: [index] into the service's list of [kind].
final class SearchPageBest {
  const SearchPageBest(this.kind, this.index);
  final SearchTileKind kind;
  final int index;
}

final class SearchPageLayout {
  const SearchPageLayout({required this.sections, required this.best, required this.memories});

  static const empty = SearchPageLayout(sections: [], best: null, memories: []);

  final List<SearchPageSection> sections;
  final SearchPageBest? best;

  /// Every note and memory hit, best first: what the Memories type chip lists, one per row.
  final List<SearchPageTile> memories;

  bool get isEmpty => sections.isEmpty && memories.isEmpty;

  SearchPageSection? section(SearchSection section) => sections.where((s) => s.section == section).firstOrNull;
}

extension SearchRowScores on SearchQuery {
  /// How well this query met one song: its title, else its artist or album.
  SearchScore scoreOfSong(SearchSongRow song) => SearchScore.max(
    score(song.title, MatchTier.primary),
    SearchScore.max(score(song.artist, MatchTier.secondary), score(song.album, MatchTier.secondary)),
  );

  SearchScore scoreOfAlbum(SearchAlbumRow album) =>
      SearchScore.max(score(album.name, MatchTier.primary), score(album.artist, MatchTier.secondary));

  SearchScore scoreOfArtist(SearchArtistRow artist) => score(artist.name, MatchTier.primary);

  SearchScore scoreOfPlaylist(SearchPlaylistRow playlist) =>
      SearchScore.max(score(playlist.name, MatchTier.primary), score(playlist.owner, MatchTier.secondary));
}

/// Lays out the All page for [query] from the service's [rows], the ledger's
/// [memories] and the memories [attachments] kept on the albums returned (by
/// album). [activeProfileId] places the ledger's hits: a note on a returned
/// song joins that song's tile only when it's this account's. [columns] is
/// the grid's width in cells (3 on a phone).
///
/// Relevance: a service can answer with rows the query doesn't appear in at
/// all (Spotify often does). When any of a section's service rows does meet
/// the query, the ones that don't leave All (they still list under the
/// section's own chip, after the rest). When none does — a romanised query,
/// say — the service is trusted and its rows stay.
SearchPageLayout layoutSearchPage(
  SearchQuery query,
  SearchServiceRows rows,
  MemorySearchMatches memories,
  Map<SearchAlbumRef, MemoryAttachment> attachments,
  String? activeProfileId, {
  int columns = 3,
  int maxRows = SearchTokens.allMaxRows,
  int maxWide = SearchTokens.allMaxWide,
}) {
  if (query.isBlank) return SearchPageLayout.empty;

  // Songs: notes on returned songs (this account's) join their tile; the rest stand alone.
  final notesBySong = <String, List<NoteSearchHit>>{};
  for (final n in memories.notes) {
    if (n.profileId == activeProfileId && n.provider != null && n.songId != null) {
      (notesBySong['${n.provider}:${n.songId}'] ??= []).add(n);
    }
  }
  final songTiles = <SearchPageTile>[];
  for (final (index, song) in rows.songs.indexed) {
    NoteSearchHit? note;
    for (final n in notesBySong[song.key] ?? const <NoteSearchHit>[]) {
      if (note == null) {
        note = n;
        continue;
      }
      final s = n.score.compareTo(note.score);
      if (s > 0 || (s == 0 && n.at > note.at)) note = n;
    }
    final own = query.scoreOfSong(song);
    songTiles.add(
      SearchPageTile(
        kind: SearchTileKind.song,
        key: 'song:${song.key}',
        index: index,
        score: SearchScore.max(own, note?.score ?? SearchScore.none),
        relevant: own.isHit || note != null,
        wide: note != null,
        note: note,
      ),
    );
  }
  final joinedNotes = {for (final t in songTiles) ?t.note?.entryId};
  final returnedSongs = {for (final s in rows.songs) s.key};
  final seenSongs = <String>{};
  final looseNotes = <SearchPageTile>[];
  for (final n in memories.notes) {
    if (joinedNotes.contains(n.entryId)) continue;
    // One tile per song: its best note (the hits are best first).
    final distinct = n.provider != null && n.songId != null ? '${n.profileId}|${n.provider}|${n.songId}' : n.entryId;
    if (!seenSongs.add(distinct)) continue;
    if (n.profileId == activeProfileId &&
        n.provider != null &&
        n.songId != null &&
        returnedSongs.contains('${n.provider}:${n.songId}')) {
      continue;
    }
    looseNotes.add(_noteTile(n));
  }

  // Albums: a memory hit on a returned album (this account's) joins its tile; the rest stand alone.
  final hitsByAlbum = {for (final m in memories.memories) m.album.key: m};
  final albumTiles = <SearchPageTile>[];
  for (final (index, album) in rows.albums.indexed) {
    final key = activeProfileId == null ? null : ledgerAlbumKey(activeProfileId, album.provider, album.rawId);
    final found = key == null ? null : hitsByAlbum[key];
    final hit = found != null && found.field != MemoryHitField.album ? found : null;
    final own = query.scoreOfAlbum(album);
    albumTiles.add(
      SearchPageTile(
        kind: SearchTileKind.album,
        key: 'album:${album.key}',
        index: index,
        score: SearchScore.max(own, hit?.score ?? SearchScore.none),
        relevant: own.isHit || hit != null,
        wide: hit != null,
        hit: hit,
        attachment: attachments[album.ref],
      ),
    );
  }
  final joinedAlbums = {
    if (activeProfileId != null)
      for (final a in rows.albums) ledgerAlbumKey(activeProfileId, a.provider, a.rawId),
  };
  final looseMemories = [
    for (final m in memories.memories)
      if (!joinedAlbums.contains(m.album.key)) _memoryTile(m),
  ];

  final artistTiles = [
    for (final (index, artist) in rows.artists.indexed)
      _serviceTile(SearchTileKind.artist, 'artist:${artist.key}', index, query.scoreOfArtist(artist)),
  ];
  final playlistTiles = [
    for (final (index, playlist) in rows.playlists.indexed)
      _serviceTile(SearchTileKind.playlist, 'playlist:${playlist.key}', index, query.scoreOfPlaylist(playlist)),
  ];

  final sections = stableSorted(
    [
      ?_section(SearchSection.songs, songTiles, looseNotes, columns, maxRows, maxWide),
      ?_section(SearchSection.albums, albumTiles, looseMemories, columns, maxRows, maxWide),
      ?_section(SearchSection.artists, artistTiles, const [], columns, maxRows, maxWide),
      ?_section(SearchSection.playlists, playlistTiles, const [], columns, maxRows, maxWide),
    ],
    (a, b) {
      final s = b.score.compareTo(a.score);
      return s != 0 ? s : a.section.index.compareTo(b.section.index);
    },
  );

  final memoryTiles = stableSorted(
    [
      for (final t in songTiles)
        if (t.note != null) t,
      ...looseNotes,
      for (final t in albumTiles)
        if (t.hit != null) t,
      for (final t in looseMemories)
        if (t.wide) t,
    ],
    (a, b) {
      final s = b.score.compareTo(a.score);
      return s != 0 ? s : a.key.compareTo(b.key);
    },
  );

  return SearchPageLayout(
    sections: sections,
    best: _bestMatch(songTiles, albumTiles, artistTiles, playlistTiles),
    memories: memoryTiles,
  );
}

SearchPageTile _noteTile(NoteSearchHit note) => SearchPageTile(
  kind: SearchTileKind.note,
  key: 'note:${note.entryId}',
  index: -1,
  score: note.score,
  relevant: true,
  wide: true,
  note: note,
);

SearchPageTile _memoryTile(MemorySearchHit hit) => SearchPageTile(
  kind: SearchTileKind.memory,
  key: 'memory:${hit.album.key}',
  index: -1,
  score: hit.score,
  relevant: true,
  wide: hit.field != MemoryHitField.album,
  hit: hit,
);

SearchPageTile _serviceTile(SearchTileKind kind, String key, int index, SearchScore score) =>
    SearchPageTile(kind: kind, key: key, index: index, score: score, relevant: score.isHit, wide: false);

SearchPageSection? _section(
  SearchSection section,
  List<SearchPageTile> service,
  List<SearchPageTile> ledger,
  int columns,
  int maxRows,
  int maxWide,
) {
  if (service.isEmpty && ledger.isEmpty) return null;
  final fold = service.any((t) => t.relevant) && service.any((t) => !t.relevant);
  final kept = [
    for (final t in service)
      if (!fold || t.relevant) t,
  ];
  final folded = [
    for (final t in service)
      if (fold && !t.relevant) t,
  ];
  // The service's order stands inside its rows (it ranks by more than names); the ledger's hits follow.
  final shown = [...kept, ...ledger];
  final packed = packSearchTiles(shown, columns, maxRows: maxRows, maxWide: maxWide, isWide: (t) => t.wide);
  var score = SearchScore.none;
  for (final (i, t) in shown.indexed) {
    score = i == 0 ? t.score : SearchScore.max(score, t.score);
  }
  return SearchPageSection(
    section: section,
    tiles: [...shown, ...folded],
    allRows: packed,
    folded: folded.length,
    more: shown.length - packed.fold(0, (sum, row) => sum + row.tiles.length),
    score: score,
  );
}

SearchPageBest? _bestMatch(
  List<SearchPageTile> songs,
  List<SearchPageTile> albums,
  List<SearchPageTile> artists,
  List<SearchPageTile> playlists,
) {
  (SearchScore, SearchPageBest)? best;
  void offer(SearchScore score, SearchPageTile tile) {
    if (score.isStrongName && (best == null || score > best!.$1)) best = (score, SearchPageBest(tile.kind, tile.index));
  }

  // Only the thing's own name earns the card, never a note or memory on it.
  for (final t in songs) {
    offer(t.note == null ? t.score : SearchScore.none, t);
  }
  for (final t in albums) {
    offer(t.hit == null ? t.score : SearchScore.none, t);
  }
  for (final t in artists) {
    offer(t.score, t);
  }
  for (final t in playlists) {
    offer(t.score, t);
  }
  return best?.$2;
}

/// Packs [tiles] (in rank order) into rows of [columns] cells, Home's Jump
/// Back In way: a row holding a 1×2 ([isWide]) is that 1×2 and `columns - 2`
/// singles; the 1×2 rows are spread between rows of singles rather than
/// stacked, and alternate sides. A full row of singles leads when there is
/// one (names rank above notes); a short one goes last, so no row in the
/// middle has a hole. At most [maxRows] rows and [maxWide] 1×2s (0 = no
/// limit); a 1×2 over the limit is left out, as is what doesn't fit.
List<PackedTileRow<T>> packSearchTiles<T>(
  List<T> tiles,
  int columns, {
  int maxRows = 0,
  int maxWide = 0,
  required bool Function(T) isWide,
}) {
  if (tiles.isEmpty) return const [];
  final cols = columns < 2 ? 2 : columns;
  final allWides = tiles.where(isWide).toList();
  final wides = maxWide > 0 ? allWides.take(maxWide).toList() : allWides;
  final singles = tiles.where((t) => !isWide(t)).toList();
  final perWide = cols - 2;
  final pairN = wides.length * perWide < singles.length ? wides.length * perWide : singles.length;
  final restN = singles.length - pairN;
  var singleRows = (restN + cols - 1) ~/ cols;
  final leadSingle = restN >= cols;
  // Leading full rows take the best singles; otherwise the best ride beside the 1×2s.
  final rest = leadSingle ? singles.take(restN).toList() : singles.skip(pairN).toList();
  final pairs = leadSingle ? singles.skip(restN).toList() : singles.take(pairN).toList();
  var restAt = 0;
  var pairAt = 0;
  final plan = <bool>[]; // true = a 1×2 row
  var w = wides.length;
  if (leadSingle && singleRows > 0) {
    plan.add(false);
    singleRows--;
  }
  while (w > 0 || singleRows > 0) {
    if (w > 0) {
      plan.add(true);
      w--;
    }
    if (singleRows > 0 && (restN % cols == 0 || singleRows > 1 || w == 0)) {
      plan.add(false);
      singleRows--;
    }
  }
  final rows = <PackedTileRow<T>>[];
  var nextWide = 0;
  var side = 0;
  for (final wideRow in plan) {
    if (maxRows > 0 && rows.length >= maxRows) break;
    if (wideRow) {
      final row = <T>[wides[nextWide++]];
      for (var k = 0; k < perWide && pairAt < pairs.length; k++) {
        row.add(pairs[pairAt++]);
      }
      rows.add(PackedTileRow(row, wideAtEnd: side % 2 == 1));
      side++;
    } else {
      final row = <T>[];
      for (var k = 0; k < cols && restAt < rest.length; k++) {
        row.add(rest[restAt++]);
      }
      rows.add(PackedTileRow(row));
    }
  }
  return rows;
}
