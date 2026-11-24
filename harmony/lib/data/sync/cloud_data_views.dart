/// Read-only views of what Cloud sync holds for one music account, for the
/// Cloud sync page's account rows and its cloud data page (Android
/// data/sync/CloudDataViews.kt + the sync-record half of
/// data/transfer/memories/MemoryItems.fromRecords). Built from this device's
/// sync replica, which mirrors what every device published; nothing here writes.
///
/// HarmonyOS keeps the per-account half only (counts, the account browser);
/// Android's "All files" and "Global data" pages aren't ported yet.
library;

import 'dart:convert';

import 'package:flutter/foundation.dart';

import 'adapters/sync_projections.dart';
import 'adapters/track_rating_sync_adapter.dart';
import 'sync_contracts.dart';
import 'sync_database.dart';

/// What one account holds in the cloud (Android CloudScopeCounts).
/// [albums] = albums with a rating or a review.
@immutable
final class CloudScopeCounts {
  const CloudScopeCounts({this.notes = 0, this.albums = 0, this.songRatings = 0, this.memoryTitles = 0});

  /// Song notes and album notes, each once.
  final int notes;
  final int albums;
  final int songRatings;
  final int memoryTitles;

  int get total => notes + albums + songRatings + memoryTitles;

  @override
  bool operator ==(Object other) =>
      other is CloudScopeCounts &&
      other.notes == notes &&
      other.albums == albums &&
      other.songRatings == songRatings &&
      other.memoryTitles == memoryTitles;

  @override
  int get hashCode => Object.hash(notes, albums, songRatings, memoryTitles);

  @override
  String toString() => 'CloudScopeCounts($notes, $albums, $songRatings, $memoryTitles)';
}

@immutable
final class CloudNote {
  const CloudNote({
    required this.id,
    required this.title,
    required this.artist,
    required this.content,
    required this.positionMs,
    required this.updatedAt,
  });

  final String id;
  final String title;
  final String artist;
  final String content;
  final int? positionMs;
  final int updatedAt;
}

/// An album's rating and review together. Names are null when nothing here knows them.
@immutable
final class CloudAlbumEntry {
  const CloudAlbumEntry({
    required this.albumId,
    required this.name,
    required this.artist,
    required this.rating,
    required this.review,
    required this.updatedAt,
  });

  final String albumId;
  final String? name;
  final String? artist;
  final double? rating;
  final String? review;
  final int updatedAt;
}

@immutable
final class CloudSongRating {
  const CloudSongRating({
    required this.trackId,
    required this.title,
    required this.artist,
    required this.rating,
    required this.updatedAt,
  });

  final String trackId;
  final String? title;
  final String? artist;
  final double rating;
  final int updatedAt;
}

/// A Memories title: the user's own ([custom]) or the AI's. [subject] names the album when known.
@immutable
final class CloudMemoryTitle {
  const CloudMemoryTitle({required this.title, required this.custom, required this.subject, required this.updatedAt});

  final String title;
  final bool custom;
  final String? subject;
  final int updatedAt;
}

/// One account's notes, ratings, reviews and Memories titles as the cloud holds them, newest first.
@immutable
final class CloudAccountContent {
  const CloudAccountContent({
    this.notes = const [],
    this.albums = const [],
    this.songRatings = const [],
    this.titles = const [],
  });

  final List<CloudNote> notes;
  final List<CloudAlbumEntry> albums;
  final List<CloudSongRating> songRatings;
  final List<CloudMemoryTitle> titles;
}

typedef _Ref = (String provider, String id);

/// One scope's Memories items from sync records (tombstones ignored), as
/// Android MemoryItems.fromRecords reads them.
final class _Items {
  final songNotes =
      <(String id, _Ref track, String content, int updatedAt, String title, String artist, int? positionMs)>[];
  final trackRatings = <(_Ref track, double rating, int updatedAt)>[];
  final albumRatings = <(_Ref album, double rating, int updatedAt)>[];
  final albumReviews = <(_Ref album, String review, int updatedAt)>[];
  final albumNotes = <(_Ref album, String albumName, String artist)>[];
  final albumTitles = <(_Ref album, String title, int updatedAt)>[];
  final memoryTitles = <(String provider, String entityType, String entityId, String title, int updatedAt)>[];

  /// Album names and artists the album notes carry.
  Map<_Ref, (String, String)> get albumFacts => {
    for (final n in albumNotes)
      if (n.$2.trim().isNotEmpty) n.$1: (n.$2, n.$3),
  };

  static _Items fromRecords(Iterable<SyncRecordEntity> records, String scope) {
    final items = _Items();
    for (final record in records) {
      if (record.scope != scope || record.deleted) continue;
      final p = _payload(record.payload);
      if (p == null) continue;
      switch (record.kind) {
        case SyncKinds.songNote:
          final provider = requiredString(p, 'provider');
          final trackId = requiredString(p, 'trackId');
          final content = requiredString(p, 'content');
          if (provider == null || trackId == null || content == null) continue;
          final position = optionalLong(p, 'positionMs');
          items.songNotes.add((
            record.key,
            (provider, _storedRawId(provider, trackId)),
            content,
            requiredLong(p, 'updatedAt') ?? record.ts,
            requiredString(p, 'title') ?? '',
            requiredString(p, 'artist') ?? '',
            position is FieldOk<int?> ? position.value : null,
          ));
        case SyncKinds.trackRating:
          final wire = rating(p, 'rating');
          if (wire == null) continue;
          final value = TrackRatingSyncAdapter.ratingFrom(wire, p);
          final provider = requiredString(p, 'provider');
          final songId = requiredString(p, 'songId');
          if (value == null || provider == null || songId == null) continue;
          items.trackRatings.add(((provider, songId), value, record.ts));
        case SyncKinds.albumRating:
          final value = rating(p, 'rating');
          final album = _albumRef(record.key);
          if (value == null || value <= 0 || album == null) continue;
          items.albumRatings.add((album, value, record.ts));
        case SyncKinds.albumReview:
          final review = optionalString(p, 'review');
          final text = review is FieldOk<String?> ? review.value : null;
          final album = _albumRef(record.key);
          if (text == null || text.trim().isEmpty || album == null) continue;
          items.albumReviews.add((album, text, record.ts));
        case SyncKinds.albumTitle:
          final title = requiredString(p, 'title')?.trim();
          final album = _albumRef(record.key);
          if (title == null || title.isEmpty || album == null) continue;
          items.albumTitles.add((album, title, record.ts));
        case SyncKinds.memoryTitle:
          final parts = splitKey(record.key, 3);
          final title = requiredString(p, 'title');
          if (parts == null || title == null || title.trim().isEmpty) continue;
          items.memoryTitles.add((parts[0], parts[1], parts[2], title, record.ts));
        case SyncKinds.albumNote:
          final provider = requiredString(p, 'provider');
          final albumId = requiredString(p, 'albumId');
          if (provider == null || albumId == null || requiredString(p, 'content') == null) continue;
          items.albumNotes.add((
            (provider, albumId),
            requiredString(p, 'albumName') ?? '',
            requiredString(p, 'artist') ?? '',
          ));
      }
    }
    return items;
  }

  static JsonMap? _payload(String? text) {
    if (text == null) return null;
    try {
      final decoded = jsonDecode(text);
      return decoded is Map<String, Object?> ? decoded : null;
    } on FormatException {
      return null;
    }
  }

  static _Ref? _albumRef(String key) {
    final parts = splitKey(key, 2);
    return parts == null ? null : (parts[0], parts[1]);
  }

  /// Android MediaId.storedRawId: a stored id may carry its provider prefix.
  static String _storedRawId(String provider, String stored) =>
      stored.startsWith('$provider:') ? stored.substring(provider.length + 1) : stored;
}

/// Pure builders from sync records; unit-tested on their own.
abstract final class CloudDataViews {
  static const _albumEntity = 'album';

  /// Counts per account scope (syncProfileId), for scopes holding anything.
  static Map<String, CloudScopeCounts> summary(Iterable<SyncRecordEntity> records) {
    final live = [
      for (final r in records)
        if (!r.deleted) r,
    ];
    final scopes = {
      for (final r in live)
        if (r.scope != SyncFormat.globalScope) r.scope,
    };
    final out = <String, CloudScopeCounts>{};
    for (final scope in scopes) {
      final items = _Items.fromRecords(live, scope);
      final counts = _countsOf(items);
      final songsWithData = {for (final n in items.songNotes) n.$2, for (final r in items.trackRatings) r.$1};
      if (counts.total > 0 || songsWithData.isNotEmpty) out[scope] = counts;
    }
    return out;
  }

  static CloudScopeCounts _countsOf(_Items items) => CloudScopeCounts(
    notes: items.songNotes.length + items.albumNotes.length,
    albums: {for (final r in items.albumRatings) r.$1, for (final r in items.albumReviews) r.$1}.length,
    songRatings: items.trackRatings.length,
    memoryTitles: items.albumTitles.length + items.memoryTitles.length,
  );

  /// One account's records for the browser, newest first. Song names come from
  /// the account's own notes, album names from its album notes (Android also
  /// asks this device's plays; HarmonyOS has no such lookup here yet).
  static CloudAccountContent account(Iterable<SyncRecordEntity> records, String scope) {
    final items = _Items.fromRecords(records, scope);
    final noted = <_Ref, (String, String)>{for (final n in items.songNotes) n.$2: (n.$5, n.$6)};
    final facts = items.albumFacts;
    (String?, String?) trackName(_Ref ref) {
      final known = noted[ref];
      return known != null && known.$1.trim().isNotEmpty ? (known.$1, known.$2) : (null, null);
    }

    (String?, String?) albumName(_Ref ref) {
      final known = facts[ref];
      return known == null ? (null, null) : (known.$1, known.$2);
    }

    final albumRefs = <_Ref>{for (final r in items.albumRatings) r.$1, for (final r in items.albumReviews) r.$1};
    final albums = [
      for (final ref in albumRefs)
        () {
          final rated = items.albumRatings.where((r) => r.$1 == ref).firstOrNull;
          final reviewed = items.albumReviews.where((r) => r.$1 == ref).firstOrNull;
          final (name, artist) = albumName(ref);
          final ratedAt = rated?.$3 ?? 0;
          final reviewedAt = reviewed?.$3 ?? 0;
          return CloudAlbumEntry(
            albumId: ref.$2,
            name: name,
            artist: artist,
            rating: rated?.$2,
            review: reviewed?.$2,
            updatedAt: ratedAt > reviewedAt ? ratedAt : reviewedAt,
          );
        }(),
    ];
    final songRatings = [
      for (final r in items.trackRatings)
        () {
          final (title, artist) = trackName(r.$1);
          return CloudSongRating(trackId: r.$1.$2, title: title, artist: artist, rating: r.$2, updatedAt: r.$3);
        }(),
    ];
    final titles = [
      for (final t in items.albumTitles)
        CloudMemoryTitle(title: t.$2, custom: true, subject: albumName(t.$1).$1, updatedAt: t.$3),
      for (final t in items.memoryTitles)
        CloudMemoryTitle(
          title: t.$4,
          custom: false,
          subject: t.$2 == _albumEntity ? albumName((t.$1, t.$3)).$1 : null,
          updatedAt: t.$5,
        ),
    ];
    int newestFirst(int a, int b) => b.compareTo(a);
    return CloudAccountContent(
      // Android lists song notes here (album notes are counted, not listed).
      notes: [
        for (final n in items.songNotes)
          CloudNote(id: n.$1, title: n.$5, artist: n.$6, content: n.$3, positionMs: n.$7, updatedAt: n.$4),
      ]..sort((a, b) => newestFirst(a.updatedAt, b.updatedAt)),
      albums: albums..sort((a, b) => newestFirst(a.updatedAt, b.updatedAt)),
      songRatings: songRatings..sort((a, b) => newestFirst(a.updatedAt, b.updatedAt)),
      titles: titles..sort((a, b) => newestFirst(a.updatedAt, b.updatedAt)),
    );
  }
}
