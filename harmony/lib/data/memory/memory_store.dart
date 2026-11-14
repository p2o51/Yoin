/// What a Memory is made of, per account (Android data/local AlbumRating,
/// SongNote, PlayHistory): the album score and review, the notes pinned to a
/// moment of a song, and the plays heard in Yoin. Track ratings live in
/// `lib/playback/local_ratings.dart`.
///
/// Android keeps Room tables keyed by (profileId, id, provider); here each
/// kind is one JSON document per account on the key-value store, rows keyed
/// by `provider:rawId`, so two accounts (and two providers) never mix.
library;

import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';

import '../../platform/storage/stores.dart';
import '../model/model.dart';

/// The album score and the review (Android `album_ratings`).
///
/// [rating] is 0.0–10.0; 0 means "no album score" (Android stores 0f the
/// same way and reads `rating > 0f` as set).
@immutable
final class AlbumRatingRow {
  const AlbumRatingRow({
    required this.albumId,
    this.rating = 0,
    this.review,
    required this.updatedAt,
    this.reviewUpdatedAt,
  });

  final MediaId albumId;
  final double rating;
  final String? review;

  /// The row's last touch (a rating or a review).
  final int updatedAt;

  /// When the review text was last written; null without a review.
  final int? reviewUpdatedAt;

  bool get hasScore => rating > 0;
  bool get hasReview => review != null && review!.trim().isNotEmpty;

  /// A row with neither a score nor a review is no write at all.
  bool get isEmpty => !hasScore && !hasReview;

  Map<String, Object?> toJson() => {'r': rating, 'v': review, 'u': updatedAt, 'vu': reviewUpdatedAt};

  static AlbumRatingRow? fromJson(MediaId id, Object? json) {
    if (json is! Map) return null;
    final r = json['r'];
    final u = json['u'];
    final vu = json['vu'];
    return AlbumRatingRow(
      albumId: id,
      rating: r is num ? r.toDouble().clamp(0.0, 10.0) : 0,
      review: json['v'] as String?,
      updatedAt: u is num ? u.toInt() : 0,
      reviewUpdatedAt: vu is num ? vu.toInt() : null,
    );
  }

  @override
  bool operator ==(Object other) =>
      other is AlbumRatingRow &&
      other.albumId == albumId &&
      other.rating == rating &&
      other.review == review &&
      other.updatedAt == updatedAt &&
      other.reviewUpdatedAt == reviewUpdatedAt;

  @override
  int get hashCode => Object.hash(albumId, rating, review, updatedAt, reviewUpdatedAt);
}

/// A note on one song, pinned to a moment (Android `song_notes`, v27
/// `positionMs`). [albumId] is HarmonyOS's own column: the album the song
/// was on when the note was written, so a note finds its Memory without the
/// album detail.
@immutable
final class SongNote {
  const SongNote({
    required this.id,
    required this.trackId,
    this.albumId,
    required this.content,
    required this.createdAt,
    required this.updatedAt,
    this.title = '',
    this.artist = '',
    this.positionMs,
  });

  final String id;
  final MediaId trackId;
  final MediaId? albumId;
  final String content;
  final int createdAt;
  final int updatedAt;
  final String title;
  final String artist;

  /// Playhead (ms into the song) when the note was written; null = not anchored.
  final int? positionMs;

  bool get isBlank => content.trim().isEmpty;

  Map<String, Object?> toJson() => {
    'id': id,
    't': trackId.toString(),
    'a': albumId?.toString(),
    'c': content,
    'ca': createdAt,
    'u': updatedAt,
    'ti': title,
    'ar': artist,
    'p': positionMs,
  };

  static SongNote? fromJson(Object? json) {
    if (json is! Map) return null;
    final id = json['id'];
    final track = MediaId.tryParse(json['t'] as String?);
    final content = json['c'];
    if (id is! String || track == null || content is! String) return null;
    final ca = json['ca'];
    final u = json['u'];
    final p = json['p'];
    return SongNote(
      id: id,
      trackId: track,
      albumId: MediaId.tryParse(json['a'] as String?),
      content: content,
      createdAt: ca is num ? ca.toInt() : 0,
      updatedAt: u is num ? u.toInt() : 0,
      title: (json['ti'] as String?) ?? '',
      artist: (json['ar'] as String?) ?? '',
      positionMs: p is num ? p.toInt() : null,
    );
  }

  @override
  bool operator ==(Object other) =>
      other is SongNote &&
      other.id == id &&
      other.trackId == trackId &&
      other.albumId == albumId &&
      other.content == content &&
      other.createdAt == createdAt &&
      other.updatedAt == updatedAt &&
      other.title == title &&
      other.artist == artist &&
      other.positionMs == positionMs;

  @override
  int get hashCode => Object.hash(id, trackId, albumId, content, createdAt, updatedAt, title, artist, positionMs);
}

/// One album's plays in Yoin (Android `play_history` grouped by album:
/// PlayHistoryDao.getAlbumAggregates). Every started song of the album adds
/// one play, as every started song adds one history row on Android. Visits
/// never count.
@immutable
final class AlbumPlays {
  const AlbumPlays({
    required this.albumId,
    required this.playCount,
    required this.firstPlayedAt,
    required this.lastPlayedAt,
    this.albumName = '',
    this.artistName = '',
    this.coverArtKey,
  });

  final MediaId albumId;
  final int playCount;
  final int firstPlayedAt;
  final int lastPlayedAt;
  final String albumName;
  final String artistName;
  final String? coverArtKey;

  AlbumPlays plus({required int atMs, String? albumName, String? artistName, String? coverArtKey}) => AlbumPlays(
    albumId: albumId,
    playCount: playCount + 1,
    firstPlayedAt: atMs < firstPlayedAt ? atMs : firstPlayedAt,
    lastPlayedAt: atMs > lastPlayedAt ? atMs : lastPlayedAt,
    albumName: _nonBlank(albumName) ?? this.albumName,
    artistName: _nonBlank(artistName) ?? this.artistName,
    coverArtKey: coverArtKey ?? this.coverArtKey,
  );

  Map<String, Object?> toJson() => {
    'n': playCount,
    'f': firstPlayedAt,
    'l': lastPlayedAt,
    't': albumName,
    's': artistName,
    'c': coverArtKey,
  };

  static AlbumPlays? fromJson(MediaId id, Object? json) {
    if (json is! Map) return null;
    final n = json['n'];
    final f = json['f'];
    final l = json['l'];
    if (n is! num || f is! num || l is! num) return null;
    return AlbumPlays(
      albumId: id,
      playCount: n.toInt(),
      firstPlayedAt: f.toInt(),
      lastPlayedAt: l.toInt(),
      albumName: (json['t'] as String?) ?? '',
      artistName: (json['s'] as String?) ?? '',
      coverArtKey: json['c'] as String?,
    );
  }

  @override
  bool operator ==(Object other) =>
      other is AlbumPlays &&
      other.albumId == albumId &&
      other.playCount == playCount &&
      other.firstPlayedAt == firstPlayedAt &&
      other.lastPlayedAt == lastPlayedAt &&
      other.albumName == albumName &&
      other.artistName == artistName &&
      other.coverArtKey == coverArtKey;

  @override
  int get hashCode => Object.hash(albumId, playCount, firstPlayedAt, lastPlayedAt, albumName, artistName, coverArtKey);
}

String? _nonBlank(String? s) => s == null || s.trim().isEmpty ? null : s;

/// Which of an account's Memory rows changed.
enum MemoryStoreChange { albumRatings, songNotes, plays }

/// One account's Memory rows on a [KeyValueStore]. Writes are serialised in
/// call order; reads after a write see it. [changes] fires after every
/// write, so the Memories deck can refresh in place.
final class MemoryStore {
  MemoryStore(this._store, {required this.profileId});

  final KeyValueStore _store;
  final String profileId;

  static String albumRatingsKey(String profileId) => 'album_ratings.v1.$profileId';
  static String songNotesKey(String profileId) => 'song_notes.v1.$profileId';
  static String albumPlaysKey(String profileId) => 'album_plays.v1.$profileId';

  Map<MediaId, AlbumRatingRow>? _ratings;
  List<SongNote>? _notes;
  Map<MediaId, AlbumPlays>? _plays;
  Future<void> _tail = Future.value();
  final _changes = StreamController<MemoryStoreChange>.broadcast();

  Stream<MemoryStoreChange> get changes => _changes.stream;

  // ---- reads ----

  Future<Map<MediaId, AlbumRatingRow>> albumRatings() async {
    await _tail;
    return Map.unmodifiable(await _loadRatings());
  }

  Future<AlbumRatingRow?> albumRating(MediaId albumId) async => (await albumRatings())[albumId];

  /// Every note of the account, newest edit first.
  Future<List<SongNote>> songNotes() async {
    await _tail;
    final notes = [...await _loadNotes()]..sort((a, b) => b.updatedAt.compareTo(a.updatedAt));
    return List.unmodifiable(notes);
  }

  /// The notes on [trackIds] (any album), newest edit first.
  Future<List<SongNote>> notesForTracks(Iterable<MediaId> trackIds) async {
    final ids = trackIds.toSet();
    return [
      for (final n in await songNotes())
        if (ids.contains(n.trackId)) n,
    ];
  }

  /// The account's plays by album, most recently played first.
  Future<List<AlbumPlays>> albumPlays() async {
    await _tail;
    final rows = (await _loadPlays()).values.toList()..sort((a, b) => b.lastPlayedAt.compareTo(a.lastPlayedAt));
    return List.unmodifiable(rows);
  }

  Future<AlbumPlays?> playsOf(MediaId albumId) async {
    await _tail;
    return (await _loadPlays())[albumId];
  }

  // ---- writes ----

  /// The album score: 0.0–10.0; null (or 0) clears it. The review stays.
  Future<void> setAlbumRating(MediaId albumId, double? rating, {required int nowMs}) =>
      _write(MemoryStoreChange.albumRatings, () async {
        final rows = await _loadRatings();
        final old = rows[albumId];
        final next = AlbumRatingRow(
          albumId: albumId,
          rating: (rating ?? 0).clamp(0.0, 10.0).toDouble(),
          review: old?.review,
          updatedAt: nowMs,
          reviewUpdatedAt: old?.reviewUpdatedAt,
        );
        _putRating(rows, next);
        await _saveRatings(rows);
      });

  /// The album review; blank clears it. The score stays.
  Future<void> setAlbumReview(MediaId albumId, String? review, {required int nowMs}) =>
      _write(MemoryStoreChange.albumRatings, () async {
        final rows = await _loadRatings();
        final old = rows[albumId];
        final text = _nonBlank(review)?.trim();
        final next = AlbumRatingRow(
          albumId: albumId,
          rating: old?.rating ?? 0,
          review: text,
          updatedAt: nowMs,
          reviewUpdatedAt: text == null ? null : nowMs,
        );
        _putRating(rows, next);
        await _saveRatings(rows);
      });

  /// Adds [note], or replaces the note with its id. A blank note is deleted.
  Future<void> putSongNote(SongNote note) => _write(MemoryStoreChange.songNotes, () async {
    final notes = await _loadNotes();
    notes.removeWhere((n) => n.id == note.id);
    if (!note.isBlank) notes.add(note);
    await _saveNotes(notes);
  });

  Future<void> deleteSongNote(String id) => _write(MemoryStoreChange.songNotes, () async {
    final notes = await _loadNotes();
    notes.removeWhere((n) => n.id == id);
    await _saveNotes(notes);
  });

  /// One started song of [albumId] at [atMs].
  Future<void> recordAlbumPlay(
    MediaId albumId, {
    required int atMs,
    String? albumName,
    String? artistName,
    String? coverArtKey,
  }) => _write(MemoryStoreChange.plays, () async {
    final rows = await _loadPlays();
    final old = rows[albumId];
    rows[albumId] = old == null
        ? AlbumPlays(
            albumId: albumId,
            playCount: 1,
            firstPlayedAt: atMs,
            lastPlayedAt: atMs,
            albumName: albumName?.trim() ?? '',
            artistName: artistName?.trim() ?? '',
            coverArtKey: coverArtKey,
          )
        : old.plus(atMs: atMs, albumName: albumName, artistName: artistName, coverArtKey: coverArtKey);
    await _store.write(
      albumPlaysKey(profileId),
      jsonEncode({for (final e in rows.entries) e.key.toString(): e.value.toJson()}),
    );
  });

  void dispose() => _changes.close();

  // ---- internals ----

  Future<void> _write(MemoryStoreChange change, Future<void> Function() body) {
    final done = _tail.then((_) async {
      await body();
      if (!_changes.isClosed) _changes.add(change);
    });
    // A failed write fails its caller, never the writes queued after it.
    _tail = done.catchError((Object _) {});
    return done;
  }

  void _putRating(Map<MediaId, AlbumRatingRow> rows, AlbumRatingRow next) {
    if (next.isEmpty) {
      rows.remove(next.albumId);
    } else {
      rows[next.albumId] = next;
    }
  }

  Future<Object?> _decode(String key) async {
    final raw = await _store.read(key);
    if (raw == null || raw.isEmpty) return null;
    try {
      return jsonDecode(raw);
    } on FormatException {
      return null;
    }
  }

  Future<Map<MediaId, AlbumRatingRow>> _loadRatings() async {
    if (_ratings != null) return _ratings!;
    final decoded = await _decode(albumRatingsKey(profileId));
    final out = <MediaId, AlbumRatingRow>{};
    if (decoded is Map) {
      decoded.forEach((key, value) {
        final id = MediaId.tryParse(key is String ? key : null);
        final row = id == null ? null : AlbumRatingRow.fromJson(id, value);
        if (row != null) out[id!] = row;
      });
    }
    return _ratings = out;
  }

  Future<void> _saveRatings(Map<MediaId, AlbumRatingRow> rows) => _store.write(
    albumRatingsKey(profileId),
    jsonEncode({for (final e in rows.entries) e.key.toString(): e.value.toJson()}),
  );

  Future<List<SongNote>> _loadNotes() async {
    if (_notes != null) return _notes!;
    final decoded = await _decode(songNotesKey(profileId));
    final out = <SongNote>[];
    if (decoded is List) {
      for (final e in decoded) {
        final note = SongNote.fromJson(e);
        if (note != null) out.add(note);
      }
    }
    return _notes = out;
  }

  Future<void> _saveNotes(List<SongNote> notes) =>
      _store.write(songNotesKey(profileId), jsonEncode([for (final n in notes) n.toJson()]));

  Future<Map<MediaId, AlbumPlays>> _loadPlays() async {
    if (_plays != null) return _plays!;
    final decoded = await _decode(albumPlaysKey(profileId));
    final out = <MediaId, AlbumPlays>{};
    if (decoded is Map) {
      decoded.forEach((key, value) {
        final id = MediaId.tryParse(key is String ? key : null);
        final row = id == null ? null : AlbumPlays.fromJson(id, value);
        if (row != null) out[id!] = row;
      });
    }
    return _plays = out;
  }
}
