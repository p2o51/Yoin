/// The memory ledger (Android data/memory MemoryLedger.kt, owner 2026-11-08):
/// every memory the listener has kept, on one timeline. The pull-down
/// Memories deck deals them one at a time; the ledger is the whole account
/// book that All Memories (and Home's Memories grid) read. Local only.
///
/// HarmonyOS keeps one account's rows at a time (MemoryStore is per
/// profile), so the ledger is the account in use; Android's cross-account
/// "From …" lines have nothing to show here.
library;

import 'package:flutter/foundation.dart';

import '../../playback/local_ratings.dart';
import '../model/model.dart';
import 'memory_store.dart';

/// What kind of mark a ledger entry is. The first three are writing; the
/// last two are scores. (Android's album notes predate the deck and have no
/// HarmonyOS rows.)
enum MemoryLedgerKind {
  songNote(wrote: true),
  review(wrote: true),
  title(wrote: true),
  albumRating(wrote: false),
  trackRating(wrote: false);

  const MemoryLedgerKind({required this.wrote});
  final bool wrote;
}

/// The album an entry belongs to: what the grid colours a cell with.
@immutable
final class MemoryLedgerAlbum {
  const MemoryLedgerAlbum({required this.albumId, required this.name, this.artist = '', this.cover});

  final MediaId albumId;
  final String name;
  final String artist;
  final CoverRef? cover;

  String get key => albumId.toString();

  MemoryLedgerAlbum withCover(CoverRef? cover) =>
      MemoryLedgerAlbum(albumId: albumId, name: name, artist: artist, cover: cover);

  @override
  bool operator ==(Object other) =>
      other is MemoryLedgerAlbum &&
      other.albumId == albumId &&
      other.name == name &&
      other.artist == artist &&
      other.cover == cover;

  @override
  int get hashCode => Object.hash(albumId, name, artist, cover);
}

@immutable
final class MemoryLedgerEntry {
  const MemoryLedgerEntry({
    required this.id,
    required this.kind,
    required this.at,
    this.album,
    this.text,
    this.trackTitle,
    this.songId,
    this.positionMs,
    this.score,
  });

  /// Stable across reloads: the kind plus the row's own key.
  final String id;
  final MemoryLedgerKind kind;

  /// When it was written or scored (epoch ms).
  final int at;

  /// Null for a song note or track score whose album Yoin never learned.
  final MemoryLedgerAlbum? album;

  /// A note's or review's text, or the title itself.
  final String? text;

  /// The song a song note or track score is about, when known.
  final String? trackTitle;

  /// That song's id: what tells two songs of one name apart.
  final MediaId? songId;

  /// Where in the song a note was written (ms).
  final int? positionMs;

  /// An album's or track's score, 0–10.
  final double? score;
}

@immutable
final class MemoryLedgerStats {
  const MemoryLedgerStats({
    required this.albums,
    required this.writings,
    required this.characters,
    required this.ratedTracks,
    this.firstAt,
  });

  /// Albums with any memory.
  final int albums;

  /// Notes and reviews (titles and scores aren't writing you'd count).
  final int writings;

  /// Characters written in them; a CJK character counts as one.
  final int characters;

  /// Tracks with a score.
  final int ratedTracks;

  /// The first memory's time; null without any.
  final int? firstAt;
}

MemoryLedgerStats memoryLedgerStats(List<MemoryLedgerEntry> entries) {
  final writings = [
    for (final e in entries)
      if (e.kind == MemoryLedgerKind.songNote || e.kind == MemoryLedgerKind.review) e,
  ];
  return MemoryLedgerStats(
    albums: {for (final e in entries) ?e.album?.key}.length,
    writings: writings.length,
    characters: writings.fold(0, (sum, e) => sum + (e.text?.runes.length ?? 0)),
    ratedTracks: entries.where((e) => e.kind == MemoryLedgerKind.trackRating).length,
    firstAt: entries.isEmpty ? null : entries.map((e) => e.at).reduce((a, b) => a < b ? a : b),
  );
}

/// One account's rows, as MemoryStore and the local ratings hold them.
@immutable
final class MemoryLedgerRows {
  const MemoryLedgerRows({
    this.albumRatings = const {},
    this.songNotes = const [],
    this.titles = const {},
    this.trackRatings = const {},
  });

  final Map<MediaId, AlbumRatingRow> albumRatings;
  final List<SongNote> songNotes;
  final Map<MediaId, MemoryTitleRow> titles;
  final Map<MediaId, LocalRating> trackRatings;
}

/// A song as the loaded albums know it: its album and its name.
typedef LedgerKnownTrack = ({MemoryLedgerAlbum album, String? title});

/// Turns [rows] into entries, newest first. [album] names an album Yoin knows
/// (its plays, its cached detail); [track] places a song on its album. A
/// song note or track score whose album is unknown keeps a null album; an
/// album entry whose album can't be named at all is left out.
List<MemoryLedgerEntry> buildMemoryLedger(
  MemoryLedgerRows rows, {
  required MemoryLedgerAlbum? Function(MediaId albumId) album,
  required LedgerKnownTrack? Function(MediaId trackId) track,
}) {
  final out = <MemoryLedgerEntry>[];
  for (final note in rows.songNotes) {
    if (note.isBlank) continue;
    final known = track(note.trackId);
    final noteAlbum = (note.albumId == null ? null : album(note.albumId!)) ?? known?.album;
    out.add(
      MemoryLedgerEntry(
        id: 'song-note:${note.id}',
        kind: MemoryLedgerKind.songNote,
        at: note.createdAt,
        album: noteAlbum,
        text: note.content,
        trackTitle: _nonBlank(note.title) ?? _nonBlank(known?.title),
        songId: note.trackId,
        positionMs: note.positionMs,
      ),
    );
  }
  for (final rating in rows.trackRatings.values) {
    final score = rating.rating;
    if (score == null) continue;
    final known = track(rating.trackId);
    out.add(
      MemoryLedgerEntry(
        id: 'track-rating:${rating.trackId}',
        kind: MemoryLedgerKind.trackRating,
        at: rating.updatedAt,
        album: known?.album,
        trackTitle: _nonBlank(known?.title),
        songId: rating.trackId,
        score: score,
      ),
    );
  }
  for (final row in rows.albumRatings.values) {
    final named = album(row.albumId);
    if (named == null) continue;
    if (row.hasScore) {
      out.add(
        MemoryLedgerEntry(
          id: 'album-rating:${row.albumId}',
          kind: MemoryLedgerKind.albumRating,
          at: row.updatedAt,
          album: named,
          score: row.rating,
        ),
      );
    }
    if (row.hasReview) {
      out.add(
        MemoryLedgerEntry(
          id: 'review:${row.albumId}',
          kind: MemoryLedgerKind.review,
          at: row.reviewUpdatedAt ?? row.updatedAt,
          album: named,
          text: row.review!.trim(),
        ),
      );
    }
  }
  for (final title in rows.titles.values) {
    final named = album(title.albumId);
    if (named == null) continue;
    out.add(
      MemoryLedgerEntry(
        id: 'title:${title.albumId}',
        kind: MemoryLedgerKind.title,
        at: title.updatedAt,
        album: named,
        text: title.title,
      ),
    );
  }
  out.sort(compareLedgerNewestFirst);
  return out;
}

/// Newest first, then by id (a stable order for ties).
int compareLedgerNewestFirst(MemoryLedgerEntry a, MemoryLedgerEntry b) {
  final c = b.at.compareTo(a.at);
  return c != 0 ? c : a.id.compareTo(b.id);
}

String? _nonBlank(String? s) => s == null || s.trim().isEmpty ? null : s;
