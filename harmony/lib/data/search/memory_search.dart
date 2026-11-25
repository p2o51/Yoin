/// Memories and notes in library search (docs/search.md; :shared
/// search/MemorySearch.kt, ported against memory-search.json). They are read
/// from the memory ledger, every account's at once (#178, owner 2026-11-08):
/// a search on one account finds what was written on another, and "no
/// results" never depends on the account in use. Read-only; local; no
/// service is asked.
library;

import 'dart:typed_data';

import '../memory/memory_ledger.dart';
import '../notes/note_markup.dart';
import 'search_match.dart';

/// What kind of mark a ledger entry is (:shared MemoryLedgerKind). The first
/// four are writing; the last two are scores. HarmonyOS keeps no album notes
/// yet; the kind is here so the port reads the same ledger as the others.
enum SearchLedgerKind {
  albumNote(wrote: true),
  songNote(wrote: true),
  review(wrote: true),
  title(wrote: true),
  albumRating(wrote: false),
  trackRating(wrote: false);

  const SearchLedgerKind({required this.wrote});
  final bool wrote;
}

/// `profileId|provider|albumId`: one album in one account (:shared ledgerAlbumKey).
String ledgerAlbumKey(String profileId, String provider, String albumId) => '$profileId|$provider|$albumId';

/// The album an entry belongs to, in its own account (:shared MemoryLedgerAlbum).
final class SearchLedgerAlbum {
  const SearchLedgerAlbum({
    required this.profileId,
    required this.provider,
    required this.albumId,
    required this.name,
    required this.artist,
    this.coverKey,
  });

  final String profileId;
  final String provider;

  /// Raw id in [provider]'s namespace.
  final String albumId;
  final String name;
  final String artist;

  /// The cover's storage key (CoverRef.storageKey); null when unknown.
  final String? coverKey;

  /// One album in one account: two accounts on the same album are two albums here.
  String get key => ledgerAlbumKey(profileId, provider, albumId);

  @override
  bool operator ==(Object other) =>
      other is SearchLedgerAlbum &&
      other.profileId == profileId &&
      other.provider == provider &&
      other.albumId == albumId &&
      other.name == name &&
      other.artist == artist &&
      other.coverKey == coverKey;

  @override
  int get hashCode => Object.hash(profileId, provider, albumId, name, artist, coverKey);
}

/// One ledger entry as search reads it (:shared MemoryLedgerEntry).
final class SearchLedgerEntry {
  const SearchLedgerEntry({
    required this.id,
    required this.kind,
    required this.at,
    required this.profileId,
    this.album,
    this.text,
    this.trackTitle,
    this.songId,
    this.songProvider,
    this.positionMs,
    this.score,
  });

  /// [entry] of the app's ledger (data/memory/memory_ledger.dart).
  factory SearchLedgerEntry.fromLedger(MemoryLedgerEntry entry) {
    final album = entry.album;
    return SearchLedgerEntry(
      id: entry.id,
      kind: switch (entry.kind) {
        MemoryLedgerKind.songNote => SearchLedgerKind.songNote,
        MemoryLedgerKind.review => SearchLedgerKind.review,
        MemoryLedgerKind.title => SearchLedgerKind.title,
        MemoryLedgerKind.albumRating => SearchLedgerKind.albumRating,
        MemoryLedgerKind.trackRating => SearchLedgerKind.trackRating,
      },
      at: entry.at,
      profileId: entry.profileId,
      album: album == null
          ? null
          : SearchLedgerAlbum(
              profileId: album.profileId,
              provider: album.albumId.provider,
              albumId: album.albumId.rawId,
              name: album.name,
              artist: album.artist,
              coverKey: album.cover?.storageKey,
            ),
      text: entry.text,
      trackTitle: entry.trackTitle,
      songId: entry.songId?.rawId,
      songProvider: entry.songId?.provider,
      positionMs: entry.positionMs,
      score: entry.score,
    );
  }

  /// Stable across reloads: the kind plus the row's own key.
  final String id;
  final SearchLedgerKind kind;

  /// When it was written or scored (epoch ms).
  final int at;
  final String profileId;

  /// Null for a song note or track score whose album Yoin never learned.
  final SearchLedgerAlbum? album;

  /// A note's or review's text (NoteMarkup), or the title itself.
  final String? text;
  final String? trackTitle;

  /// The song's raw id (song notes and track scores).
  final String? songId;

  /// The song's provider, known even when its album isn't.
  final String? songProvider;

  /// Where in the song a note was written (ms).
  final int? positionMs;

  /// An album's or track's score, 0–10.
  final double? score;

  /// Any writing, or an album score. A song's score alone doesn't make a memory.
  bool get _makesMemory => kind.wrote || kind == SearchLedgerKind.albumRating;
}

/// Which of a memory's own words the query met.
enum MemoryHitField {
  /// The memory's title: the one the listener gave it, else the AI's.
  title(MatchTier.primary),

  /// The album's name or artist: shown on the album row when it is there.
  album(MatchTier.secondary),

  /// The album review.
  review(MatchTier.body),

  /// One of the album's notes (its diary).
  albumNote(MatchTier.body);

  const MemoryHitField(this.tier);
  final MatchTier tier;
}

/// What kind of mark an album's cover wears (Home's rule): your score, else
/// your tracks' average, else notes.
enum LedgerMarkKind { albumRating, trackAverage, notesOnly }

/// An album's memory mark: what Home's memory mark draws. Reviews and titles alone wear none.
final class LedgerMark {
  const LedgerMark(this.kind, this.score, this.noteCount);

  final LedgerMarkKind kind;

  /// 32-bit, as Kotlin's Float.
  final double? score;
  final int noteCount;

  @override
  bool operator ==(Object other) =>
      other is LedgerMark && other.kind == kind && other.score == score && other.noteCount == noteCount;

  @override
  int get hashCode => Object.hash(kind, score, noteCount);
}

/// The newest of [entries] by `at`, the first on a tie (Kotlin maxByOrNull).
SearchLedgerEntry? _newest(Iterable<SearchLedgerEntry> entries) {
  SearchLedgerEntry? best;
  for (final e in entries) {
    if (best == null || e.at > best.at) best = e;
  }
  return best;
}

/// The mark for one album's ledger [entries] (any kinds), or null when nothing on it earns one.
LedgerMark? ledgerMarkOf(List<SearchLedgerEntry> entries) {
  final notes = entries
      .where((e) => e.kind == SearchLedgerKind.albumNote || e.kind == SearchLedgerKind.songNote)
      .length;
  final rating = _newest(entries.where((e) => e.kind == SearchLedgerKind.albumRating))?.score;
  if (rating != null) return LedgerMark(LedgerMarkKind.albumRating, rating, notes);
  // Kotlin keeps scores as Float and averages them as Double.
  final tracks = [
    for (final e in entries)
      if (e.kind == SearchLedgerKind.trackRating && e.score != null) toFloat32(e.score!),
  ];
  if (tracks.isNotEmpty) {
    final average = tracks.reduce((a, b) => a + b) / tracks.length;
    return LedgerMark(LedgerMarkKind.trackAverage, toFloat32(average), notes);
  }
  return notes > 0 ? LedgerMark(LedgerMarkKind.notesOnly, null, notes) : null;
}

/// [value] rounded to the nearest 32-bit float (Kotlin's Double.toFloat()).
double toFloat32(double value) {
  _float32[0] = value;
  return _float32[0];
}

final _float32 = Float32List(1);

/// An album memory the query met: one per album in one account, at its best hit.
final class MemorySearchHit {
  const MemorySearchHit({
    required this.album,
    required this.field,
    required this.entryId,
    required this.fragment,
    required this.score,
    required this.title,
    required this.albumScore,
    required this.lastAt,
    required this.isOtherAccount,
    this.mark,
  });

  final SearchLedgerAlbum album;
  final MemoryHitField field;

  /// The ledger entry the hit is in: the diary entry to open at. Null for a
  /// hit on the album's name or an AI title.
  final String? entryId;

  /// What to show, with the hit marked.
  final SearchFragment fragment;
  final SearchScore score;

  /// The memory's title when it has one (the listener's, else the AI's).
  final String? title;

  /// The album's score, 0–10, when it has one.
  final double? albumScore;

  /// The memory's newest mark (epoch ms).
  final int lastAt;

  /// Kept on an account other than the one in use: what earns a source badge.
  final bool isOtherAccount;

  /// The cover's memory mark.
  final LedgerMark? mark;

  MemorySearchHit withMark(LedgerMark? mark) => MemorySearchHit(
    album: album,
    field: field,
    entryId: entryId,
    fragment: fragment,
    score: score,
    title: title,
    albumScore: albumScore,
    lastAt: lastAt,
    isOtherAccount: isOtherAccount,
    mark: mark,
  );
}

/// A song note the query met. Opening it plays the song from [positionMs] (note_open_target.dart).
final class NoteSearchHit {
  const NoteSearchHit({
    required this.entryId,
    required this.profileId,
    required this.provider,
    required this.songId,
    required this.trackTitle,
    required this.album,
    required this.positionMs,
    required this.fragment,
    required this.score,
    required this.at,
    required this.isOtherAccount,
  });

  final String entryId;
  final String profileId;

  /// The song's provider and raw id: what plays it.
  final String? provider;
  final String? songId;
  final String? trackTitle;
  final SearchLedgerAlbum? album;

  /// Where in the song it was written (ms); null for an unanchored note.
  final int? positionMs;
  final SearchFragment fragment;
  final SearchScore score;
  final int at;
  final bool isOtherAccount;
}

/// Everything the ledger answered for one query, each list best first.
final class MemorySearchMatches {
  const MemorySearchMatches({this.memories = const [], this.notes = const []});

  static const empty = MemorySearchMatches();

  final List<MemorySearchHit> memories;
  final List<NoteSearchHit> notes;

  bool get isEmpty => memories.isEmpty && notes.isEmpty;

  /// Without the memories whose only hit is their album's name, where that
  /// album is a row of the results already ([attached] keys): it carries the
  /// memory there. A hit in the memory's own words stays even then.
  MemorySearchMatches withoutAttached(Set<String> attached) => attached.isEmpty
      ? this
      : MemorySearchMatches(
          memories: [
            for (final m in memories)
              if (!(m.field == MemoryHitField.album && attached.contains(m.album.key))) m,
          ],
          notes: notes,
        );
}

/// What an album row shows of the memory kept on it.
final class MemoryAttachment {
  const MemoryAttachment({
    required this.albumKey,
    required this.title,
    required this.albumScore,
    required this.lastAt,
    this.mark,
  });

  final String albumKey;
  final String? title;
  final double? albumScore;
  final int lastAt;

  /// The cover's memory mark; null when only a review or title is kept.
  final LedgerMark? mark;
}

/// A sort that keeps equal elements in their order (Kotlin's sortedWith is stable; Dart's sort isn't).
List<T> stableSorted<T>(Iterable<T> items, int Function(T a, T b) compare) {
  final indexed = items.indexed.toList();
  indexed.sort((a, b) {
    final c = compare(a.$2, b.$2);
    return c != 0 ? c : a.$1.compareTo(b.$1);
  });
  return [for (final (_, item) in indexed) item];
}

int _boolOrder(bool a, bool b) => a == b ? 0 : (a ? 1 : -1);

/// Searches the ledger's [entries] (every account's) for [query]. [aiTitles]
/// holds AI-written memory titles by album key; the listener's own title (a
/// title entry) wins over it. Same-strength hits from [activeProfileId] come
/// first, then newer ones; the account never lifts a weaker hit over a stronger one.
MemorySearchMatches searchMemoryLedger(
  List<SearchLedgerEntry> entries,
  SearchQuery query,
  String? activeProfileId, {
  Map<String, String> aiTitles = const {},
}) {
  if (query.isBlank || entries.isEmpty) return MemorySearchMatches.empty;

  final byAlbum = <String, List<SearchLedgerEntry>>{};
  final everything = <String, List<SearchLedgerEntry>>{};
  final notes = <NoteSearchHit>[];
  for (final entry in entries) {
    if (entry.kind == SearchLedgerKind.songNote) {
      final hit = _noteHit(entry, query, activeProfileId);
      if (hit != null) notes.add(hit);
    }
    final album = entry.album;
    if (album == null) continue;
    (everything[album.key] ??= []).add(entry);
    if (entry._makesMemory && entry.kind != SearchLedgerKind.songNote) {
      (byAlbum[album.key] ??= []).add(entry);
    }
  }

  final memories = <MemorySearchHit>[
    for (final MapEntry(:key, :value) in byAlbum.entries)
      if (_memoryHit(value, query, activeProfileId, aiTitles) case final hit?)
        hit.withMark(ledgerMarkOf(everything[key] ?? const [])),
  ];
  return MemorySearchMatches(
    memories: stableSorted(memories, (a, b) {
      final s = b.score.compareTo(a.score);
      if (s != 0) return s;
      final o = _boolOrder(a.isOtherAccount, b.isOtherAccount);
      if (o != 0) return o;
      final t = b.lastAt.compareTo(a.lastAt);
      return t != 0 ? t : a.album.key.compareTo(b.album.key);
    }),
    notes: stableSorted(notes, (a, b) {
      final s = b.score.compareTo(a.score);
      if (s != 0) return s;
      final o = _boolOrder(a.isOtherAccount, b.isOtherAccount);
      if (o != 0) return o;
      final t = b.at.compareTo(a.at);
      return t != 0 ? t : a.entryId.compareTo(b.entryId);
    }),
  );
}

final class _Candidate {
  const _Candidate(this.field, this.entryId, this.match, this.at);
  final MemoryHitField field;
  final String? entryId;
  final FieldMatch match;
  final int at;
}

MemorySearchHit? _memoryHit(
  List<SearchLedgerEntry> entries,
  SearchQuery query,
  String? activeProfileId,
  Map<String, String> aiTitles,
) {
  final album = entries.first.album;
  if (album == null) return null;
  final userTitle = _newest(entries.where((e) => e.kind == SearchLedgerKind.title));
  final title = _nonBlank(userTitle?.text) ?? _nonBlank(aiTitles[album.key]);

  final name = query.match(album.name);
  final artist = query.match(album.artist);
  final candidates = [
    _Candidate(MemoryHitField.title, userTitle?.id, query.match(title), userTitle?.at ?? 0),
    _Candidate(MemoryHitField.album, null, name.strength.index >= artist.strength.index ? name : artist, 0),
    for (final entry in entries)
      if (entry.kind == SearchLedgerKind.review || entry.kind == SearchLedgerKind.albumNote)
        _Candidate(
          entry.kind == SearchLedgerKind.review ? MemoryHitField.review : MemoryHitField.albumNote,
          entry.id,
          query.match(entry.text == null ? null : noteWords(entry.text!)),
          entry.at,
        ),
  ];
  // Best tier and strength; between equals, the newer writing (the first of equals stays).
  _Candidate? best;
  for (final c in candidates) {
    if (c.match.strength == MatchStrength.none) continue;
    if (best == null) {
      best = c;
      continue;
    }
    final s = SearchScore(c.field.tier, c.match.strength).compareTo(SearchScore(best.field.tier, best.match.strength));
    if (s > 0 || (s == 0 && c.at > best.at)) best = c;
  }
  if (best == null) return null;
  final fragment = query.fragment(best.match);
  if (fragment == null) return null;
  return MemorySearchHit(
    album: album,
    field: best.field,
    entryId: best.entryId,
    fragment: fragment,
    score: SearchScore(best.field.tier, best.match.strength),
    title: title,
    albumScore: _newest(entries.where((e) => e.kind == SearchLedgerKind.albumRating))?.score,
    lastAt: entries.map((e) => e.at).reduce((a, b) => a > b ? a : b),
    isOtherAccount: activeProfileId != null && album.profileId != activeProfileId,
  );
}

NoteSearchHit? _noteHit(SearchLedgerEntry entry, SearchQuery query, String? activeProfileId) {
  final words = entry.text == null ? null : noteWords(entry.text!);
  final body = query.match(words);
  // The song's name and artist are weaker than any hit in the note's own
  // words, yet a note on the song typed is still worth showing.
  final title = query.match(entry.trackTitle);
  final artist = query.match(entry.album?.artist);
  final FieldMatch match;
  final MatchTier tier;
  if (body.strength != MatchStrength.none) {
    (match, tier) = (body, MatchTier.body);
  } else if (title.strength.index >= artist.strength.index && title.strength != MatchStrength.none) {
    (match, tier) = (title, MatchTier.incidental);
  } else if (artist.strength != MatchStrength.none) {
    (match, tier) = (artist, MatchTier.incidental);
  } else {
    return null;
  }
  // A hit on the song's name shows the note itself, unmarked: the name is on the row already.
  final fragment = tier == MatchTier.body
      ? query.fragment(match)
      : query.fragment(FieldMatch(MatchStrength.contains, FoldedText.of(words ?? '')))?.withoutHighlight();
  if (fragment == null) return null;
  return NoteSearchHit(
    entryId: entry.id,
    profileId: entry.profileId,
    provider: entry.songProvider ?? entry.album?.provider,
    songId: entry.songId,
    trackTitle: entry.trackTitle,
    album: entry.album,
    positionMs: entry.positionMs,
    fragment: fragment,
    score: SearchScore(tier, match.strength),
    at: entry.at,
    isOtherAccount: activeProfileId != null && entry.profileId != activeProfileId,
  );
}

/// An album as a returned row names it: provider and raw id.
typedef SearchAlbumRef = ({String provider, String rawId});

/// The memories kept on [albums] (a search's album rows), by album. Phase 1
/// attaches only on the exact album in the account in use, `(profileId,
/// provider, albumId)`: never by a similar title, since a wrong attachment is
/// worse than a missing one. [aiTitles] as in [searchMemoryLedger].
Map<SearchAlbumRef, MemoryAttachment> memoryAttachments(
  List<SearchAlbumRef> albums,
  List<SearchLedgerEntry> entries,
  String? activeProfileId, {
  Map<String, String> aiTitles = const {},
}) {
  if (activeProfileId == null || albums.isEmpty) return const {};
  final wanted = {for (final a in albums) ledgerAlbumKey(activeProfileId, a.provider, a.rawId): a};
  final found = <String, List<SearchLedgerEntry>>{};
  final everything = <String, List<SearchLedgerEntry>>{};
  for (final entry in entries) {
    final key = entry.album?.key;
    if (key == null || !wanted.containsKey(key)) continue;
    (everything[key] ??= []).add(entry);
    if (entry._makesMemory && entry.kind != SearchLedgerKind.songNote) (found[key] ??= []).add(entry);
  }
  // A memory, or anything that marks the cover on Home (a song's note or score counts there).
  final out = <SearchAlbumRef, MemoryAttachment>{};
  for (final MapEntry(:key, value: all) in everything.entries) {
    final album = wanted[key]!;
    final kept = found[key] ?? const <SearchLedgerEntry>[];
    final mark = ledgerMarkOf(all);
    if (kept.isEmpty && mark == null) continue;
    final title = _newest(kept.where((e) => e.kind == SearchLedgerKind.title))?.text ?? aiTitles[key];
    out[album] = MemoryAttachment(
      albumKey: key,
      title: _nonBlank(title),
      albumScore: _newest(kept.where((e) => e.kind == SearchLedgerKind.albumRating))?.score,
      lastAt: all.map((e) => e.at).reduce((a, b) => a > b ? a : b),
      mark: mark,
    );
  }
  return out;
}

/// A note's or review's words for search: mention names, no Markdown marks, no `yoin:` links (NoteMarkup).
String noteWords(String text) => NoteMarkup.plainText(text);

String? _nonBlank(String? s) => s == null || isBlank(s) ? null : s;
