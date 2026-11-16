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
///
/// [ratingNeedsSync] / [reviewNeedsSync] are NeoDB's dirty flags (a Mark and
/// a Review are two resources, so either half can wait alone): a local edit
/// sets them, a NeoDB push that landed clears them. [neoDbReviewUuid] is the
/// remote review's id when NeoDB gave one. Cloud sync never touches the three.
@immutable
final class AlbumRatingRow {
  const AlbumRatingRow({
    required this.albumId,
    this.rating = 0,
    this.review,
    required this.updatedAt,
    this.reviewUpdatedAt,
    this.ratingNeedsSync = false,
    this.reviewNeedsSync = false,
    this.neoDbReviewUuid,
  });

  final MediaId albumId;
  final double rating;
  final String? review;

  /// The row's last touch (a rating or a review).
  final int updatedAt;

  /// When the review text was last written; null without a review.
  final int? reviewUpdatedAt;

  final bool ratingNeedsSync;
  final bool reviewNeedsSync;
  final String? neoDbReviewUuid;

  bool get hasScore => rating > 0;
  bool get hasReview => review != null && review!.trim().isNotEmpty;

  /// A row with neither a score nor a review is no write at all.
  bool get isEmpty => !hasScore && !hasReview;

  Map<String, Object?> toJson() => {
    'r': rating,
    'v': review,
    'u': updatedAt,
    'vu': reviewUpdatedAt,
    if (ratingNeedsSync) 'rn': true,
    if (reviewNeedsSync) 'vn': true,
    'nu': ?neoDbReviewUuid,
  };

  /// This row with NeoDB's three fields replaced (the rest kept).
  AlbumRatingRow withNeoDb({bool? ratingNeedsSync, bool? reviewNeedsSync, String? Function()? neoDbReviewUuid}) =>
      AlbumRatingRow(
        albumId: albumId,
        rating: rating,
        review: review,
        updatedAt: updatedAt,
        reviewUpdatedAt: reviewUpdatedAt,
        ratingNeedsSync: ratingNeedsSync ?? this.ratingNeedsSync,
        reviewNeedsSync: reviewNeedsSync ?? this.reviewNeedsSync,
        neoDbReviewUuid: neoDbReviewUuid == null ? this.neoDbReviewUuid : neoDbReviewUuid(),
      );

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
      ratingNeedsSync: json['rn'] == true,
      reviewNeedsSync: json['vn'] == true,
      neoDbReviewUuid: json['nu'] as String?,
    );
  }

  @override
  bool operator ==(Object other) =>
      other is AlbumRatingRow &&
      other.albumId == albumId &&
      other.rating == rating &&
      other.review == review &&
      other.updatedAt == updatedAt &&
      other.reviewUpdatedAt == reviewUpdatedAt &&
      other.ratingNeedsSync == ratingNeedsSync &&
      other.reviewNeedsSync == reviewNeedsSync &&
      other.neoDbReviewUuid == neoDbReviewUuid;

  @override
  int get hashCode => Object.hash(
    albumId,
    rating,
    review,
    updatedAt,
    reviewUpdatedAt,
    ratingNeedsSync,
    reviewNeedsSync,
    neoDbReviewUuid,
  );
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

/// One song's plays in Yoin (Android `play_history` grouped by song:
/// PlayHistoryDao.getArtistTopSongs). Every started song adds one play, the
/// same start that adds an [AlbumPlays] play; the row keeps the song's last
/// known title, album and artist so Most Played lists it without a read.
@immutable
final class TrackPlays {
  const TrackPlays({
    required this.trackId,
    required this.playCount,
    required this.lastPlayedAt,
    this.title = '',
    this.album = '',
    this.albumId,
    this.artist = '',
    this.coverArtKey,
    this.durationSec,
  });

  final MediaId trackId;
  final int playCount;
  final int lastPlayedAt;
  final String title;
  final String album;
  final MediaId? albumId;
  final String artist;
  final String? coverArtKey;
  final int? durationSec;

  TrackPlays plus(Track track, {required int atMs}) => TrackPlays(
    trackId: trackId,
    playCount: playCount + 1,
    lastPlayedAt: atMs > lastPlayedAt ? atMs : lastPlayedAt,
    title: _nonBlank(track.title)?.trim() ?? title,
    album: _nonBlank(track.album)?.trim() ?? album,
    albumId: track.albumId ?? albumId,
    artist: _nonBlank(track.artist)?.trim() ?? artist,
    coverArtKey: track.coverArt?.storageKey ?? coverArtKey,
    // Android keeps MAX(durationMs).
    durationSec: switch ((track.durationSec, durationSec)) {
      (final a?, final b?) => a > b ? a : b,
      (final a, final b) => a ?? b,
    },
  );

  Map<String, Object?> toJson() => {
    'n': playCount,
    'l': lastPlayedAt,
    't': title,
    'al': album,
    'a': albumId?.toString(),
    'ar': artist,
    'c': coverArtKey,
    'd': durationSec,
  };

  static TrackPlays? fromJson(MediaId id, Object? json) {
    if (json is! Map) return null;
    final n = json['n'];
    final l = json['l'];
    if (n is! num || l is! num) return null;
    final d = json['d'];
    return TrackPlays(
      trackId: id,
      playCount: n.toInt(),
      lastPlayedAt: l.toInt(),
      title: (json['t'] as String?) ?? '',
      album: (json['al'] as String?) ?? '',
      albumId: MediaId.tryParse(json['a'] as String?),
      artist: (json['ar'] as String?) ?? '',
      coverArtKey: json['c'] as String?,
      durationSec: d is num ? d.toInt() : null,
    );
  }

  @override
  bool operator ==(Object other) =>
      other is TrackPlays &&
      other.trackId == trackId &&
      other.playCount == playCount &&
      other.lastPlayedAt == lastPlayedAt &&
      other.title == title &&
      other.album == album &&
      other.albumId == albumId &&
      other.artist == artist &&
      other.coverArtKey == coverArtKey &&
      other.durationSec == durationSec;

  @override
  int get hashCode =>
      Object.hash(trackId, playCount, lastPlayedAt, title, album, albumId, artist, coverArtKey, durationSec);
}

String? _nonBlank(String? s) => s == null || s.trim().isEmpty ? null : s;

/// The user's own title for an album's Memory (Android `album_memory_titles`,
/// AlbumMemoryTitleStore): stored trimmed, [updatedAt] the edit time.
/// Restoring Yoin's title is a delete of the row, never a blank title.
@immutable
final class MemoryTitleRow {
  const MemoryTitleRow({required this.albumId, required this.title, required this.updatedAt});

  final MediaId albumId;
  final String title;
  final int updatedAt;

  Map<String, Object?> toJson() => {'t': title, 'u': updatedAt};

  static MemoryTitleRow? fromJson(MediaId id, Object? json) {
    if (json is! Map) return null;
    final t = json['t'];
    final u = json['u'];
    if (t is! String || t.trim().isEmpty) return null;
    return MemoryTitleRow(albumId: id, title: t, updatedAt: u is num ? u.toInt() : 0);
  }

  @override
  bool operator ==(Object other) =>
      other is MemoryTitleRow && other.albumId == albumId && other.title == title && other.updatedAt == updatedAt;

  @override
  int get hashCode => Object.hash(albumId, title, updatedAt);
}

/// Which of an account's Memory rows changed.
enum MemoryStoreChange { albumRatings, songNotes, plays, titles }

/// The rows cloud sync reads and edits in one step ([MemoryStore.syncEdit]):
/// album rows including empty ones (a cleared score with no review is still a
/// row, as on Android), every note and every user title. Edits happen in
/// place on these collections.
final class MemorySyncRows {
  MemorySyncRows._(this.albumRatings, this.songNotes, this.titles);

  final Map<MediaId, AlbumRatingRow> albumRatings;
  final List<SongNote> songNotes;
  final Map<MediaId, MemoryTitleRow> titles;
}

/// One account's Memory rows on a [KeyValueStore]. Writes are serialised in
/// call order; reads after a write see it. [changes] fires after every
/// write, so the Memories deck can refresh in place.
final class MemoryStore {
  MemoryStore(this._store, {required this.profileId});

  /// One store per (key-value store, account), shared by the screens
  /// ([memoryStoreProvider]) and cloud sync, so a sync writes through the same
  /// queue and cache the screens read instead of under them. Never disposed:
  /// it is only a cache and a queue, and a sync may reach it at any time.
  static final _shared = Expando<Map<String, MemoryStore>>('MemoryStore.shared');

  /// [profileId]'s rows on [store], the same instance every time.
  static MemoryStore shared(KeyValueStore store, String profileId) =>
      (_shared[store] ??= {})[profileId] ??= MemoryStore(store, profileId: profileId);

  final KeyValueStore _store;
  final String profileId;

  static String albumRatingsKey(String profileId) => 'album_ratings.v1.$profileId';
  static String songNotesKey(String profileId) => 'song_notes.v1.$profileId';
  static String albumPlaysKey(String profileId) => 'album_plays.v1.$profileId';
  static String titlesKey(String profileId) => 'memory_titles.v1.$profileId';
  static String trackPlaysKey(String profileId) => 'track_plays.v1.$profileId';

  Map<MediaId, AlbumRatingRow>? _ratings;
  List<SongNote>? _notes;
  Map<MediaId, AlbumPlays>? _plays;
  Map<MediaId, MemoryTitleRow>? _titles;
  Map<MediaId, TrackPlays>? _trackPlays;
  Future<void> _tail = Future.value();
  final _changes = StreamController<MemoryStoreChange>.broadcast();

  Stream<MemoryStoreChange> get changes => _changes.stream;

  // ---- reads ----

  /// Rows with a score or a review (an emptied row is kept for sync, never shown).
  Future<Map<MediaId, AlbumRatingRow>> albumRatings() async {
    await _tail;
    return Map.unmodifiable({
      for (final e in (await _loadRatings()).entries)
        if (!e.value.isEmpty) e.key: e.value,
    });
  }

  Future<AlbumRatingRow?> albumRating(MediaId albumId) async => (await albumRatings())[albumId];

  /// [albumId]'s row as stored, an emptied one included (Android
  /// AlbumRatingDao.get): a cleared score still waits for NeoDB.
  Future<AlbumRatingRow?> albumRatingRow(MediaId albumId) async {
    await _tail;
    return (await _loadRatings())[albumId];
  }

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

  /// The account's plays by song (keys are provider-namespaced ids), most
  /// played first, then most recently played.
  Future<List<TrackPlays>> trackPlays() async {
    await _tail;
    final rows = (await _loadTrackPlays()).values.toList()
      ..sort((a, b) {
        final byCount = b.playCount.compareTo(a.playCount);
        return byCount != 0 ? byCount : b.lastPlayedAt.compareTo(a.lastPlayedAt);
      });
    return List.unmodifiable(rows);
  }

  /// The user's own Memory titles, by album.
  Future<Map<MediaId, MemoryTitleRow>> titles() async {
    await _tail;
    return Map.unmodifiable(await _loadTitles());
  }

  Future<MemoryTitleRow?> titleOf(MediaId albumId) async => (await titles())[albumId];

  // ---- writes ----

  /// The user names [albumId]'s Memory [title], stored trimmed. A blank (or
  /// null) title restores Yoin's: the row goes. The same words as stored are
  /// no edit and write nothing (no new `updatedAt`).
  Future<void> setTitle(MediaId albumId, String? title, {required int nowMs}) =>
      _write(MemoryStoreChange.titles, () async {
        final rows = await _loadTitles();
        final words = _nonBlank(title)?.trim();
        if (words == null) {
          if (rows.remove(albumId) == null) return;
        } else {
          if (rows[albumId]?.title == words) return;
          rows[albumId] = MemoryTitleRow(albumId: albumId, title: words, updatedAt: nowMs);
        }
        await _store.write(
          titlesKey(profileId),
          jsonEncode({for (final e in rows.entries) e.key.toString(): e.value.toJson()}),
        );
      });

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
          ratingNeedsSync: true,
          reviewNeedsSync: old?.reviewNeedsSync ?? false,
          neoDbReviewUuid: old?.neoDbReviewUuid,
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
          // The written date moves only when the words do (Android setAlbumReview).
          reviewUpdatedAt: text == null ? null : (text == old?.review ? (old?.reviewUpdatedAt ?? nowMs) : nowMs),
          ratingNeedsSync: old?.ratingNeedsSync ?? false,
          reviewNeedsSync: true,
          neoDbReviewUuid: old?.neoDbReviewUuid,
        );
        _putRating(rows, next);
        await _saveRatings(rows);
      });

  /// A NeoDB push landed the score [sentRating]: its dirty flag goes only
  /// while the row still holds that score (a score set meanwhile stays dirty
  /// and goes with the next push). Android AlbumRatingDao.markRatingPushed.
  Future<void> markRatingPushed(MediaId albumId, {required double sentRating}) =>
      _write(MemoryStoreChange.albumRatings, () async {
        final rows = await _loadRatings();
        final row = rows[albumId];
        if (row == null || row.rating != sentRating || !row.ratingNeedsSync) return;
        rows[albumId] = row.withNeoDb(ratingNeedsSync: false);
        await _saveRatings(rows);
      });

  /// A NeoDB push landed the words [sentReview] (null: the clear): the flag
  /// goes and [reviewUuid] is kept only while the row still holds them.
  Future<void> markReviewPushed(MediaId albumId, {required String? sentReview, String? reviewUuid}) =>
      _write(MemoryStoreChange.albumRatings, () async {
        final rows = await _loadRatings();
        final row = rows[albumId];
        if (row == null || row.review != sentReview) return;
        if (!row.reviewNeedsSync && row.neoDbReviewUuid == reviewUuid) return;
        rows[albumId] = row.withNeoDb(reviewNeedsSync: false, neoDbReviewUuid: () => reviewUuid);
        await _saveRatings(rows);
      });

  /// Writes [row] as pulled from NeoDB (NeoDBSyncService.pullAlbum's upsert).
  Future<void> putPulledAlbumRating(AlbumRatingRow row) => _write(MemoryStoreChange.albumRatings, () async {
    final rows = await _loadRatings();
    _putRating(rows, row);
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

  /// One started [track] at [atMs] (one `play_history` row on Android). Fires
  /// [MemoryStoreChange.plays], as the album play written beside it does.
  Future<void> recordTrackPlay(Track track, {required int atMs}) => _write(MemoryStoreChange.plays, () async {
    final rows = await _loadTrackPlays();
    final old = rows[track.id];
    rows[track.id] = (old ?? TrackPlays(trackId: track.id, playCount: 0, lastPlayedAt: atMs)).plus(track, atMs: atMs);
    await _store.write(
      trackPlaysKey(profileId),
      jsonEncode({for (final e in rows.entries) e.key.toString(): e.value.toJson()}),
    );
  });

  /// Cloud sync's view of the rows, copied (nothing here is edited).
  Future<MemorySyncRows> syncRead() async {
    await _tail;
    return MemorySyncRows._(Map.of(await _loadRatings()), List.of(await _loadNotes()), Map.of(await _loadTitles()));
  }

  /// Runs [edit] on the rows in the write queue (no other write in between),
  /// then saves what it changed and tells the screens. Cloud sync's
  /// check-then-write (the expected-hash guard) happens inside [edit].
  Future<R> syncEdit<R>(R Function(MemorySyncRows rows) edit) {
    final done = Completer<R>();
    _tail = _tail.then((_) async {
      try {
        final ratings = await _loadRatings();
        final notes = await _loadNotes();
        final titles = await _loadTitles();
        final before = (_ratingsJson(ratings), _notesJson(notes), _titlesJson(titles));
        final result = edit(MemorySyncRows._(ratings, notes, titles));
        final after = (_ratingsJson(ratings), _notesJson(notes), _titlesJson(titles));
        if (after.$1 != before.$1) {
          await _store.write(albumRatingsKey(profileId), after.$1);
          _emit(MemoryStoreChange.albumRatings);
        }
        if (after.$2 != before.$2) {
          await _store.write(songNotesKey(profileId), after.$2);
          _emit(MemoryStoreChange.songNotes);
        }
        if (after.$3 != before.$3) {
          await _store.write(titlesKey(profileId), after.$3);
          _emit(MemoryStoreChange.titles);
        }
        done.complete(result);
      } catch (e, st) {
        // A failed edit leaves the rows as they are stored: drop the cache.
        _ratings = null;
        _notes = null;
        _titles = null;
        done.completeError(e, st);
      }
    });
    return done.future;
  }

  void _emit(MemoryStoreChange change) {
    if (!_changes.isClosed) _changes.add(change);
  }

  static String _ratingsJson(Map<MediaId, AlbumRatingRow> rows) =>
      jsonEncode({for (final e in rows.entries) e.key.toString(): e.value.toJson()});

  static String _notesJson(List<SongNote> notes) => jsonEncode([for (final n in notes) n.toJson()]);

  static String _titlesJson(Map<MediaId, MemoryTitleRow> rows) =>
      jsonEncode({for (final e in rows.entries) e.key.toString(): e.value.toJson()});

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

  /// An emptied row stays (score 0, no review), as Android's album_ratings
  /// row does: cloud sync reads a vanished row as "the store was reset" and
  /// would bring the old score back. Public reads leave empty rows out.
  void _putRating(Map<MediaId, AlbumRatingRow> rows, AlbumRatingRow next) {
    if (next.isEmpty && !rows.containsKey(next.albumId)) return;
    rows[next.albumId] = next;
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

  Future<Map<MediaId, MemoryTitleRow>> _loadTitles() async {
    if (_titles != null) return _titles!;
    final decoded = await _decode(titlesKey(profileId));
    final out = <MediaId, MemoryTitleRow>{};
    if (decoded is Map) {
      decoded.forEach((key, value) {
        final id = MediaId.tryParse(key is String ? key : null);
        final row = id == null ? null : MemoryTitleRow.fromJson(id, value);
        if (row != null) out[id!] = row;
      });
    }
    return _titles = out;
  }

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

  Future<Map<MediaId, TrackPlays>> _loadTrackPlays() async {
    if (_trackPlays != null) return _trackPlays!;
    final decoded = await _decode(trackPlaysKey(profileId));
    final out = <MediaId, TrackPlays>{};
    if (decoded is Map) {
      decoded.forEach((key, value) {
        final id = MediaId.tryParse(key is String ? key : null);
        final row = id == null ? null : TrackPlays.fromJson(id, value);
        if (row != null) out[id!] = row;
      });
    }
    return _trackPlays = out;
  }
}
