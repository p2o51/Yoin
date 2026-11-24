/// The backup / transfer ports on HarmonyOS's own stores (what Android's
/// SharedBackupBridge / RoomTransferStore / DomainMemoryItems do on Room): an
/// account's [MemoryStore], its [MemoryCopyCache], its song scores (through
/// [TrackRatingAccess], the LocalRatings notifier in the app), its
/// [TrackAlbumLinkStore] and its plays (names for the readable Markdown,
/// facts for matching).
library;

import 'dart:convert';

import '../../platform/storage/stores.dart';
import '../../playback/local_ratings.dart';
import '../memory/memory_copy_cache.dart';
import '../memory/memory_store.dart';
import '../memory/track_album_links.dart';
import '../model/media_id.dart';
import '../sync/adapters/track_rating_sync_adapter.dart';
import '../sync/sync_database.dart';
import 'backup_ports.dart';
import 'memories/memory_items.dart';
import 'memories/memory_transfer_applier.dart';
import 'memories/transfer_models.dart';
import 'memories/transfer_rules.dart';
import 'readable_export.dart';

/// Every per-account store the core touches, on one key-value store.
final class HarmonyMemoryStores {
  HarmonyMemoryStores({required this.store, required this.ratings, required this.profileIds});

  final KeyValueStore store;
  final TrackRatingAccess ratings;

  /// Every local account's id (whose stores hold the notes a global note id may already name).
  final Future<List<String>> Function() profileIds;

  MemoryStore memory(String profileId) => MemoryStore.shared(store, profileId);

  MemoryCopyCache copies(String profileId) => MemoryCopyCache.shared(store, profileId);

  TrackAlbumLinkStore links(String profileId) => TrackAlbumLinkStore.shared(store, profileId);
}

MediaId? _mediaId(String provider, String id) =>
    provider.isEmpty || provider.contains(':') || id.isEmpty ? null : MediaId(provider, id);

/// [BackupDomain] on the stores.
final class HarmonyBackupDomain implements BackupDomain {
  HarmonyBackupDomain(this._stores);

  final HarmonyMemoryStores _stores;

  @override
  Future<List<BackupTrackAlbumLink>> trackAlbumLinks(String localProfileId) async => [
    for (final link in (await _stores.links(localProfileId).links()).values)
      if (link.albumId != null)
        BackupTrackAlbumLink(
          provider: link.trackId.provider,
          trackId: link.trackId.rawId,
          albumId: link.albumId!.rawId,
          source: link.source,
          updatedAt: link.updatedAt,
        ),
  ];

  /// A link that found no album isn't a fact here: the backup's may fill it.
  @override
  Future<BackupTrackAlbumLink?> trackAlbumLink(String localProfileId, String provider, String trackId) async {
    final id = _mediaId(provider, trackId);
    if (id == null) return null;
    final link = (await _stores.links(localProfileId).links())[id];
    if (link == null || link.albumId == null) return null;
    return BackupTrackAlbumLink(
      provider: provider,
      trackId: trackId,
      albumId: link.albumId!.rawId,
      source: link.source,
      updatedAt: link.updatedAt,
    );
  }

  @override
  Future<void> putTrackAlbumLink(String localProfileId, BackupTrackAlbumLink link) async {
    final track = _mediaId(link.provider, link.trackId);
    final album = _mediaId(link.provider, link.albumId);
    if (track == null || album == null) throw ArgumentError('Not a track album link');
    await _stores.links(localProfileId).putAll([
      TrackAlbumLink(trackId: track, albumId: album, source: link.source, updatedAt: link.updatedAt),
    ]);
  }

  @override
  Future<String?> songNoteOwner(String id) async {
    for (final profileId in await _stores.profileIds()) {
      final rows = await _stores.memory(profileId).syncRead();
      if (rows.songNotes.any((n) => n.id == id)) return profileId;
    }
    return null;
  }

  @override
  Future<String?> albumNoteOwner(String id) async {
    for (final profileId in await _stores.profileIds()) {
      final rows = await _stores.memory(profileId).syncRead();
      if (rows.albumNotes.any((n) => n.id == id)) return profileId;
    }
    return null;
  }
}

/// Names for the readable Markdown from what this device played (never the network).
final class HarmonyReadableNames extends ReadableNames {
  HarmonyReadableNames(this._stores);

  final HarmonyMemoryStores _stores;
  final _albums = <String, Map<MediaId, AlbumPlays>>{};
  final _tracks = <String, Map<MediaId, TrackPlays>>{};

  @override
  Future<ReadableAlbum?> album(String? localProfileId, String provider, String albumId) async {
    final id = _mediaId(provider, albumId);
    if (localProfileId == null || id == null) return null;
    final plays = _albums[localProfileId] ??= {
      for (final p in await _stores.memory(localProfileId).albumPlays()) p.albumId: p,
    };
    final play = plays[id];
    if (play == null || play.albumName.trim().isEmpty) return null;
    return ReadableAlbum(play.albumName, play.artistName.trim().isEmpty ? null : play.artistName);
  }

  @override
  Future<ReadableTrack?> track(String? localProfileId, String provider, String trackId) async {
    final id = _mediaId(provider, trackId);
    if (localProfileId == null || id == null) return null;
    final plays = _tracks[localProfileId] ??= {
      for (final p in await _stores.memory(localProfileId).trackPlays()) p.trackId: p,
    };
    final play = plays[id];
    if (play == null || play.title.trim().isEmpty) return null;
    return ReadableTrack(play.title, play.artist.trim().isEmpty ? null : play.artist, play.albumId?.rawId);
  }
}

/// One local account's Memories (Android MemoryItems.fromProfile): the stores,
/// plus what its plays know (song facts to match with, album names, and a
/// link for a noted or scored song only a play places on its album).
Future<MemoryItems> memoryItemsOfProfile(HarmonyMemoryStores stores, String profileId) async {
  final rows = await stores.memory(profileId).syncRead();
  final ratings = await stores.ratings.read(profileId);
  final copies = await stores.copies(profileId).syncRead();
  final links = await stores.links(profileId).links();
  final trackPlays = await stores.memory(profileId).trackPlays();
  final albumPlays = await stores.memory(profileId).albumPlays();

  SourceRef ref(MediaId id) => SourceRef(id.provider, TransferRules.storedRawId(id.provider, id.rawId));

  final songNotes = [
    for (final n in rows.songNotes)
      SourceNote(
        id: n.id,
        track: ref(n.trackId),
        content: n.content,
        createdAt: n.createdAt,
        updatedAt: n.updatedAt,
        title: n.title,
        artist: n.artist,
        positionMs: n.positionMs,
      ),
  ];
  final trackRatings = [
    for (final r in ratings.values)
      if (r.rating != null) SourceTrackRating(SourceRef(r.provider, r.songId), r.rating!, r.updatedAt),
  ];
  final trackLinks = [
    for (final l in links.values)
      if (l.albumId != null) SourceTrackLink(ref(l.trackId), l.albumId!.rawId, l.source, l.updatedAt),
  ];
  final linked = {for (final l in trackLinks) l.track};
  final memoryTracks = {for (final n in songNotes) n.track, for (final r in trackRatings) r.track};
  final playLinks = [
    for (final p in trackPlays)
      if (p.albumId != null && memoryTracks.contains(ref(p.trackId)) && !linked.contains(ref(p.trackId)))
        SourceTrackLink(ref(p.trackId), p.albumId!.rawId, BackupTrackAlbumLink.sourcePlay, p.lastPlayedAt),
  ];
  return MemoryItems(
    songNotes: songNotes,
    trackRatings: trackRatings,
    albumRatings: [
      for (final r in rows.albumRatings.values)
        if (r.rating > 0) SourceAlbumRating(ref(r.albumId), r.rating, r.updatedAt),
    ],
    albumReviews: [
      for (final r in rows.albumRatings.values)
        if (r.hasReview) SourceAlbumReview(ref(r.albumId), r.review!, r.updatedAt, r.reviewUpdatedAt),
    ],
    albumNotes: [
      for (final n in rows.albumNotes)
        SourceAlbumNote(
          id: n.id,
          album: ref(n.albumId),
          content: n.content,
          createdAt: n.createdAt,
          updatedAt: n.updatedAt,
          albumName: n.albumName,
          artist: n.artist,
        ),
    ],
    albumTitles: [for (final t in rows.titles.values) SourceAlbumTitle(ref(t.albumId), t.title, t.updatedAt)],
    memoryTitles: [
      for (final MapEntry(key: album, value: row) in copies.entries)
        if (row.title != null && row.title!.trim().isNotEmpty)
          SourceMemoryTitle(
            album.provider,
            TransferRules.entityAlbum,
            album.rawId,
            row.title!,
            row.titlePromptHash,
            row.generatedAt,
          ),
    ],
    memoryCopies: [
      for (final MapEntry(key: album, value: row) in copies.entries)
        if (row.copy.trim().isNotEmpty)
          SourceMemoryCopy(
            album.provider,
            TransferRules.entityAlbum,
            album.rawId,
            row.copy,
            row.promptHash,
            row.generatedAt,
          ),
    ],
    trackAlbumLinks: [...trackLinks, ...playLinks],
    trackFacts: {
      for (final p in trackPlays)
        if (p.title.trim().isNotEmpty)
          ref(p.trackId): SourceTrackFacts(
            title: p.title,
            artist: p.artist,
            album: p.album.trim().isEmpty ? null : p.album,
            albumId: p.albumId?.rawId,
            durationSec: p.durationSec,
          ),
    },
    albumFacts: {
      for (final p in albumPlays)
        if (p.albumName.trim().isNotEmpty) ref(p.albumId): SourceAlbumFacts(p.albumName, p.artistName),
      for (final n in rows.albumNotes)
        if (n.albumName.trim().isNotEmpty) ref(n.albumId): SourceAlbumFacts(n.albumName, n.artist),
    },
  );
}

/// [MemoryTransferStore] on the stores. Album Memories only (HarmonyOS's
/// MemoryCopyCache keeps album rows, as Android's only entity type today).
final class HarmonyTransferStore implements MemoryTransferStore {
  HarmonyTransferStore(this._stores);

  final HarmonyMemoryStores _stores;

  @override
  Future<TransferNoteState?> songNote(String id) async {
    for (final profileId in await _stores.profileIds()) {
      final rows = await _stores.memory(profileId).syncRead();
      for (final n in rows.songNotes) {
        if (n.id == id) return TransferNoteState(profileId, n.createdAt, n.updatedAt);
      }
    }
    return null;
  }

  @override
  Future<void> putSongNote(TransferSongNote note) async {
    final track = _mediaId(note.provider, note.trackId) ?? (throw ArgumentError('Not a song'));
    await _stores.memory(note.profileId).syncEdit((rows) {
      final index = rows.songNotes.indexWhere((n) => n.id == note.id);
      final row = SongNote(
        id: note.id,
        trackId: track,
        albumId: index < 0 ? null : rows.songNotes[index].albumId,
        content: note.content,
        createdAt: note.createdAt,
        updatedAt: note.updatedAt,
        title: note.title,
        artist: note.artist,
        positionMs: note.positionMs,
      );
      if (index < 0) {
        rows.songNotes.add(row);
      } else {
        rows.songNotes[index] = row;
      }
    });
  }

  @override
  Future<TransferNoteState?> albumNote(String id) async {
    for (final profileId in await _stores.profileIds()) {
      final rows = await _stores.memory(profileId).syncRead();
      for (final n in rows.albumNotes) {
        if (n.id == id) return TransferNoteState(profileId, n.createdAt, n.updatedAt);
      }
    }
    return null;
  }

  @override
  Future<void> putAlbumNote(TransferAlbumNote note) async {
    final album = _mediaId(note.provider, note.albumId) ?? (throw ArgumentError('Not an album'));
    await _stores.memory(note.profileId).syncEdit((rows) {
      final row = AlbumNote(
        id: note.id,
        albumId: album,
        content: note.content,
        createdAt: note.createdAt,
        updatedAt: note.updatedAt,
        albumName: note.albumName,
        artist: note.artist,
      );
      final index = rows.albumNotes.indexWhere((n) => n.id == note.id);
      if (index < 0) {
        rows.albumNotes.add(row);
      } else {
        rows.albumNotes[index] = row;
      }
    });
  }

  @override
  Future<TransferTrackRating?> trackRating(String profileId, String provider, String songId) async {
    final id = _mediaId(provider, songId);
    if (id == null) return null;
    final row = (await _stores.ratings.read(profileId))[id];
    return row == null ? null : TransferTrackRating(row.rating, row.updatedAt);
  }

  /// Never pushed to the target service (Android: needsSync stays false for a copy).
  @override
  Future<void> putTrackRating(String profileId, String provider, String songId, double rating, int updatedAt) async {
    final id = _mediaId(provider, songId) ?? (throw ArgumentError('Not a song'));
    await _stores.ratings.edit(profileId, (rows) {
      rows[id] = LocalRating(
        profileId: profileId,
        songId: songId,
        provider: provider,
        rating: rating,
        serverRating: serverRatingFor(rating),
        needsSync: false,
        updatedAt: updatedAt,
      );
    });
  }

  @override
  Future<TransferAlbumRow?> albumRow(String profileId, String provider, String albumId) async {
    final id = _mediaId(provider, albumId);
    if (id == null) return null;
    final row = await _stores.memory(profileId).albumRatingRow(id);
    return row == null ? null : TransferAlbumRow(row.rating, row.review, row.reviewUpdatedAt, row.updatedAt);
  }

  @override
  Future<void> insertAlbumRow(String profileId, String provider, String albumId, TransferAlbumRow row) async {
    final id = _mediaId(provider, albumId) ?? (throw ArgumentError('Not an album'));
    await _stores.memory(profileId).syncEdit((rows) {
      if (rows.albumRatings.containsKey(id)) return;
      rows.albumRatings[id] = AlbumRatingRow(
        albumId: id,
        rating: row.rating,
        review: row.review,
        updatedAt: row.updatedAt,
        reviewUpdatedAt: row.reviewUpdatedAt,
      );
    });
  }

  @override
  Future<void> updateAlbumRating(
    String profileId,
    String provider,
    String albumId,
    double rating,
    int updatedAt,
  ) async {
    final id = _mediaId(provider, albumId) ?? (throw ArgumentError('Not an album'));
    await _stores.memory(profileId).syncEdit((rows) {
      final row = rows.albumRatings[id];
      if (row == null) return;
      rows.albumRatings[id] = AlbumRatingRow(
        albumId: id,
        rating: rating,
        review: row.review,
        updatedAt: updatedAt,
        reviewUpdatedAt: row.reviewUpdatedAt,
        ratingNeedsSync: row.ratingNeedsSync,
        reviewNeedsSync: row.reviewNeedsSync,
        neoDbReviewUuid: row.neoDbReviewUuid,
      );
    });
  }

  @override
  Future<void> updateAlbumReview(
    String profileId,
    String provider,
    String albumId,
    String review,
    int reviewUpdatedAt,
    int updatedAt,
  ) async {
    final id = _mediaId(provider, albumId) ?? (throw ArgumentError('Not an album'));
    await _stores.memory(profileId).syncEdit((rows) {
      final row = rows.albumRatings[id];
      if (row == null) return;
      rows.albumRatings[id] = AlbumRatingRow(
        albumId: id,
        rating: row.rating,
        review: review,
        updatedAt: updatedAt,
        reviewUpdatedAt: reviewUpdatedAt,
        ratingNeedsSync: row.ratingNeedsSync,
        reviewNeedsSync: row.reviewNeedsSync,
        neoDbReviewUuid: row.neoDbReviewUuid,
      );
    });
  }

  @override
  Future<TransferAlbumTitle?> albumTitle(String profileId, String provider, String albumId) async {
    final id = _mediaId(provider, albumId);
    if (id == null) return null;
    final row = await _stores.memory(profileId).titleOf(id);
    return row == null ? null : TransferAlbumTitle(row.title, row.updatedAt);
  }

  @override
  Future<void> putAlbumTitle(String profileId, String provider, String albumId, TransferAlbumTitle title) async {
    final id = _mediaId(provider, albumId) ?? (throw ArgumentError('Not an album'));
    await _stores.memory(profileId).syncEdit((rows) {
      rows.titles[id] = MemoryTitleRow(albumId: id, title: title.title.trim(), updatedAt: title.updatedAt);
    });
  }

  @override
  Future<TransferMemoryRow?> memory(String profileId, String provider, String entityType, String entityId) async {
    final id = _mediaId(provider, entityId);
    if (entityType != TransferRules.entityAlbum || id == null) return null;
    final row = await _stores.copies(profileId).get(id);
    return row == null
        ? null
        : TransferMemoryRow(
            provider: provider,
            entityType: entityType,
            entityId: entityId,
            copyText: row.copy,
            promptHash: row.promptHash,
            generatedAt: row.generatedAt,
            title: row.title,
            titlePromptHash: row.titlePromptHash,
          );
  }

  @override
  Future<void> putMemory(String profileId, TransferMemoryRow row) async {
    final id = _mediaId(row.provider, row.entityId);
    if (row.entityType != TransferRules.entityAlbum || id == null) {
      throw UnsupportedError('Only album Memories are kept here');
    }
    await _stores.copies(profileId).syncEdit((rows) {
      rows[id] = MemoryCopyRow(
        copy: row.copyText,
        promptHash: row.promptHash,
        generatedAt: row.generatedAt,
        title: row.title,
        titlePromptHash: row.titlePromptHash,
      );
    });
  }

  @override
  Future<bool> hasTrackAlbumLink(String profileId, String provider, String trackId) async {
    final id = _mediaId(provider, trackId);
    if (id == null) return false;
    return (await _stores.links(profileId).links())[id]?.albumId != null;
  }

  @override
  Future<void> putTrackAlbumLink(String profileId, BackupTrackAlbumLink link) =>
      HarmonyBackupDomain(_stores).putTrackAlbumLink(profileId, link);
}

/// The transfer ledger in the sync store's meta: the same keys Android keeps in sync_meta.
final class DatabaseTransferLedger extends TransferLedger {
  DatabaseTransferLedger(this._db);

  final SyncDatabase _db;

  @override
  Future<Map<String, String>> read(String srcScope, String dstProfileId) async {
    final prefix = TransferLedger.metaPrefix(srcScope, dstProfileId);
    return {
      for (final e in _db.metaWithPrefix(prefix))
        if (e.key.startsWith(prefix)) e.key.substring(prefix.length): e.value,
    };
  }

  @override
  Future<void> write(String srcScope, String dstProfileId, Map<String, String> entries) async {
    if (entries.isEmpty) return;
    final prefix = TransferLedger.metaPrefix(srcScope, dstProfileId);
    await _db.transaction(() async {
      for (final e in entries.entries) {
        _db.putMeta('$prefix${e.key}', e.value);
      }
    });
  }
}

/// The ledger on the key-value store, for a device where Cloud sync never ran
/// (no sync store to keep it in): same keys, one JSON document.
final class KeyValueTransferLedger extends TransferLedger {
  KeyValueTransferLedger(this._store);

  static const key = 'memories_transfer_ledger.v1';

  final KeyValueStore _store;

  Future<Map<String, String>> _all() async {
    final raw = await _store.read(key);
    if (raw == null || raw.isEmpty) return {};
    try {
      final decoded = jsonDecode(raw);
      return decoded is Map ? {for (final e in decoded.entries) '${e.key}': '${e.value}'} : {};
    } on FormatException {
      return {};
    }
  }

  @override
  Future<Map<String, String>> read(String srcScope, String dstProfileId) async {
    final prefix = TransferLedger.metaPrefix(srcScope, dstProfileId);
    return {
      for (final e in (await _all()).entries)
        if (e.key.startsWith(prefix)) e.key.substring(prefix.length): e.value,
    };
  }

  @override
  Future<void> write(String srcScope, String dstProfileId, Map<String, String> entries) async {
    if (entries.isEmpty) return;
    final prefix = TransferLedger.metaPrefix(srcScope, dstProfileId);
    final all = await _all();
    for (final e in entries.entries) {
      all['$prefix${e.key}'] = e.value;
    }
    await _store.write(key, jsonEncode(all));
  }
}
