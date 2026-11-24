/// Copies Memories from one account into a local profile once matching
/// decided where each song and album goes (:shared MemoryTransferApplier.kt).
/// Copy, never move. Rules (docs/cloud-sync.md「转移 Memories」):
///
///  - Ids are copied as they are when both sides share an id space
///    ([TransferRules.compatible]); otherwise the song's / album's pick
///    (Matched or Picked) says where it goes.
///  - Notes get [MigrationIds] ids, so a rerun finds its own copies and never
///    touches the original; mentions in notes and reviews follow their song / album.
///  - Conflicts in the target: notes are all kept; scores and titles: the later
///    edit wins; reviews: both kept, the source's under "— 来自 <source account>";
///    the target's own AI title wins.
///  - The ledger entries returned say what was copied (skipped next time),
///    matched, picked or unmatched.
library;

import 'package:flutter/foundation.dart';

import '../../sync/sync_contracts.dart';
import '../backup_ports.dart' show BackupTrackAlbumLink;
import 'memory_items.dart';
import 'transfer_models.dart';
import 'transfer_rules.dart';

/// A song or album note as the target holds it (only what a copy decides on).
@immutable
final class TransferNoteState {
  const TransferNoteState(this.profileId, this.createdAt, this.updatedAt);
  final String profileId;
  final int createdAt;
  final int updatedAt;
}

@immutable
final class TransferSongNote {
  const TransferSongNote({
    required this.id,
    required this.profileId,
    required this.provider,
    required this.trackId,
    required this.content,
    required this.createdAt,
    required this.updatedAt,
    required this.title,
    required this.artist,
    this.positionMs,
  });

  final String id;
  final String profileId;
  final String provider;
  final String trackId;
  final String content;
  final int createdAt;
  final int updatedAt;
  final String title;
  final String artist;
  final int? positionMs;

  TransferSongNote withCreatedAt(int createdAt) => TransferSongNote(
    id: id,
    profileId: profileId,
    provider: provider,
    trackId: trackId,
    content: content,
    createdAt: createdAt,
    updatedAt: updatedAt,
    title: title,
    artist: artist,
    positionMs: positionMs,
  );
}

@immutable
final class TransferAlbumNote {
  const TransferAlbumNote({
    required this.id,
    required this.profileId,
    required this.provider,
    required this.albumId,
    required this.content,
    required this.createdAt,
    required this.updatedAt,
    required this.albumName,
    required this.artist,
  });

  final String id;
  final String profileId;
  final String provider;
  final String albumId;
  final String content;
  final int createdAt;
  final int updatedAt;
  final String albumName;
  final String artist;

  TransferAlbumNote withCreatedAt(int createdAt) => TransferAlbumNote(
    id: id,
    profileId: profileId,
    provider: provider,
    albumId: albumId,
    content: content,
    createdAt: createdAt,
    updatedAt: updatedAt,
    albumName: albumName,
    artist: artist,
  );
}

/// The target's song score: null [rating] = a cleared score (the row stays so the clear syncs).
@immutable
final class TransferTrackRating {
  const TransferTrackRating(this.rating, this.updatedAt);
  final double? rating;
  final int updatedAt;
}

/// The target's album row: score (0 = none) and review share it.
@immutable
final class TransferAlbumRow {
  const TransferAlbumRow(this.rating, this.review, this.reviewUpdatedAt, this.updatedAt);
  final double rating;
  final String? review;
  final int? reviewUpdatedAt;
  final int updatedAt;
}

@immutable
final class TransferAlbumTitle {
  const TransferAlbumTitle(this.title, this.updatedAt);
  final String title;
  final int updatedAt;
}

/// memory_copy_cache: the AI mood line ([copyText]) and AI title of one Memory.
@immutable
final class TransferMemoryRow {
  const TransferMemoryRow({
    required this.provider,
    required this.entityType,
    required this.entityId,
    required this.copyText,
    required this.promptHash,
    required this.generatedAt,
    this.title,
    this.titlePromptHash,
  });

  final String provider;
  final String entityType;
  final String entityId;
  final String copyText;
  final String promptHash;
  final int generatedAt;
  final String? title;
  final String? titlePromptHash;

  TransferMemoryRow withTitle(String title, String? titlePromptHash) => TransferMemoryRow(
    provider: provider,
    entityType: entityType,
    entityId: entityId,
    copyText: copyText,
    promptHash: promptHash,
    generatedAt: generatedAt,
    title: title,
    titlePromptHash: titlePromptHash,
  );

  TransferMemoryRow withCopy(String copyText, String promptHash, int generatedAt) => TransferMemoryRow(
    provider: provider,
    entityType: entityType,
    entityId: entityId,
    copyText: copyText,
    promptHash: promptHash,
    generatedAt: generatedAt,
    title: title,
    titlePromptHash: titlePromptHash,
  );
}

/// The target profile's Memories as a transfer reads and writes them. Writes
/// never push anything to a service: album rows keep their NeoDB flags unset,
/// song scores aren't sent to the target service.
abstract interface class MemoryTransferStore {
  Future<TransferNoteState?> songNote(String id);
  Future<void> putSongNote(TransferSongNote note);
  Future<TransferNoteState?> albumNote(String id);
  Future<void> putAlbumNote(TransferAlbumNote note);
  Future<TransferTrackRating?> trackRating(String profileId, String provider, String songId);
  Future<void> putTrackRating(String profileId, String provider, String songId, double rating, int updatedAt);
  Future<TransferAlbumRow?> albumRow(String profileId, String provider, String albumId);

  /// A new album row (none exists yet).
  Future<void> insertAlbumRow(String profileId, String provider, String albumId, TransferAlbumRow row);

  /// Only the score and updatedAt of an existing row.
  Future<void> updateAlbumRating(String profileId, String provider, String albumId, double rating, int updatedAt);

  /// Only the review, its date and updatedAt of an existing row.
  Future<void> updateAlbumReview(
    String profileId,
    String provider,
    String albumId,
    String review,
    int reviewUpdatedAt,
    int updatedAt,
  );

  Future<TransferAlbumTitle?> albumTitle(String profileId, String provider, String albumId);
  Future<void> putAlbumTitle(String profileId, String provider, String albumId, TransferAlbumTitle title);
  Future<TransferMemoryRow?> memory(String profileId, String provider, String entityType, String entityId);
  Future<void> putMemory(String profileId, TransferMemoryRow row);
  Future<bool> hasTrackAlbumLink(String profileId, String provider, String trackId);
  Future<void> putTrackAlbumLink(String profileId, BackupTrackAlbumLink link);
}

/// What [MemoryTransferApplier.apply] did, and the ledger entries to store for the next run.
@immutable
final class TransferOutcome {
  const TransferOutcome(this.result, this.ledgerEntries);
  final TransferResult result;
  final Map<String, String> ledgerEntries;
}

final class _Counter {
  int songNotes = 0;
  int trackRatings = 0;
  int albumRatings = 0;
  int albumReviews = 0;
  int albumNotes = 0;
  int albumTitles = 0;
  int memoryTitles = 0;
  int memoryCopies = 0;
  int trackAlbumLinks = 0;

  TransferCounts counts() => TransferCounts(
    songNotes: songNotes,
    trackRatings: trackRatings,
    albumRatings: albumRatings,
    albumReviews: albumReviews,
    albumNotes: albumNotes,
    albumTitles: albumTitles,
    memoryTitles: memoryTitles,
    memoryCopies: memoryCopies,
    trackAlbumLinks: trackAlbumLinks,
  );
}

class MemoryTransferApplier {
  MemoryTransferApplier(this._store);

  final MemoryTransferStore _store;

  Future<TransferOutcome> apply({
    required TransferAccount source,
    required TransferAccount target,
    required MemoryItems items,
    required List<TrackPick> tracks,
    required List<AlbumPick> albums,
    required Map<String, String> ledger,
    required int Function() clock,
  }) async {
    final profileId = target.localProfileId ?? (throw ArgumentError('Memories are copied into a local profile'));
    final pending = TransferRules.pendingItems(items, ledger);
    final trackMatches = {for (final t in tracks) t.source: t};
    final albumMatches = {for (final a in albums) a.source: a};

    TargetRef? trackTarget(SourceRef ref) {
      if (TransferRules.compatible(source, target, ref)) return TransferRules.targetOf(ref.provider, ref.id);
      final pick = trackMatches[ref];
      return pick != null && pick.status.copies ? pick.chosen?.ref : null;
    }

    TargetRef? albumTarget(SourceRef ref) {
      if (TransferRules.compatible(source, target, ref)) return TransferRules.targetOf(ref.provider, ref.id);
      final pick = albumMatches[ref];
      return pick != null && pick.status.copies ? pick.chosen?.ref : null;
    }

    String remap(String text) => TransferRules.remapMentions(text, trackTarget, albumTarget);

    TargetRef? memoryEntityTarget(String provider, String entityType, String entityId) {
      final ref = SourceRef(provider, entityId);
      if (entityType == TransferRules.entityAlbum) return albumTarget(ref);
      if (TransferRules.compatible(source, target, ref)) return TransferRules.targetOf(provider, entityId);
      return null;
    }

    final copied = _Counter();
    final updated = _Counter();
    final kept = _Counter();
    final unmatched = _Counter();
    var joined = 0;
    final entries = <String, String>{};

    for (final note in pending.songNotes) {
      final ledgerKey = TransferRules.songNoteKey(note);
      final track = trackTarget(note.track);
      if (track == null) {
        unmatched.songNotes++;
        entries[ledgerKey] = TransferLedger.unmatched;
        continue;
      }
      final id = MigrationIds.derive(source.scope, note.id, target.scope);
      final matched = trackMatches[note.track]?.chosen;
      final row = TransferSongNote(
        id: id,
        profileId: profileId,
        provider: track.provider,
        trackId: track.id,
        content: remap(note.content),
        createdAt: note.createdAt,
        updatedAt: note.updatedAt,
        title: _nonBlank(matched?.title) ?? note.title,
        artist: _nonBlank(matched?.artist) ?? note.artist,
        positionMs: note.positionMs,
      );
      final existing = await _store.songNote(id);
      if (existing == null) {
        await _store.putSongNote(row);
        copied.songNotes++;
      } else if (existing.profileId != profileId || existing.updatedAt >= note.updatedAt) {
        kept.songNotes++;
      } else {
        await _store.putSongNote(row.withCreatedAt(existing.createdAt));
        updated.songNotes++;
      }
      entries[ledgerKey] = '${TransferLedger.copied}$id';
    }

    for (final rating in pending.trackRatings) {
      final ledgerKey = TransferRules.trackRatingKey(rating);
      final track = trackTarget(rating.track);
      if (track == null) {
        unmatched.trackRatings++;
        entries[ledgerKey] = TransferLedger.unmatched;
        continue;
      }
      final existing = await _store.trackRating(profileId, track.provider, track.id);
      if (existing == null) {
        await _store.putTrackRating(profileId, track.provider, track.id, rating.rating, rating.updatedAt);
        copied.trackRatings++;
      } else if (existing.rating == rating.rating) {
        kept.trackRatings++;
      } else if (existing.rating == null || rating.updatedAt > existing.updatedAt) {
        await _store.putTrackRating(profileId, track.provider, track.id, rating.rating, rating.updatedAt);
        updated.trackRatings++;
      } else {
        kept.trackRatings++;
      }
      entries[ledgerKey] = '${TransferLedger.copied}${SyncFormat.compositeKey([track.provider, track.id])}';
    }

    for (final rating in pending.albumRatings) {
      final ledgerKey = TransferRules.albumRatingKey(rating);
      final album = albumTarget(rating.album);
      if (album == null) {
        unmatched.albumRatings++;
        entries[ledgerKey] = TransferLedger.unmatched;
        continue;
      }
      final existing = await _store.albumRow(profileId, album.provider, album.id);
      if (existing == null) {
        await _store.insertAlbumRow(
          profileId,
          album.provider,
          album.id,
          TransferAlbumRow(rating.rating, null, null, rating.updatedAt),
        );
        copied.albumRatings++;
      } else if (existing.rating == rating.rating) {
        kept.albumRatings++;
      } else if (existing.rating == 0) {
        // The row exists for its review only: the score itself is new here.
        await _store.updateAlbumRating(
          profileId,
          album.provider,
          album.id,
          rating.rating,
          _max(existing.updatedAt, rating.updatedAt),
        );
        copied.albumRatings++;
      } else if (rating.updatedAt > existing.updatedAt) {
        // Rating only: the NeoDB flags stay as they are (never set: nothing is pushed for a copy).
        await _store.updateAlbumRating(
          profileId,
          album.provider,
          album.id,
          rating.rating,
          _max(existing.updatedAt, rating.updatedAt),
        );
        updated.albumRatings++;
      } else {
        kept.albumRatings++;
      }
      entries[ledgerKey] = '${TransferLedger.copied}${SyncFormat.compositeKey([album.provider, album.id])}';
    }

    for (final review in pending.albumReviews) {
      final ledgerKey = TransferRules.albumReviewKey(review);
      final album = albumTarget(review.album);
      if (album == null) {
        unmatched.albumReviews++;
        entries[ledgerKey] = TransferLedger.unmatched;
        continue;
      }
      final existing = await _store.albumRow(profileId, album.provider, album.id);
      final text = remap(review.review.trim());
      final writtenAt = review.reviewUpdatedAt ?? review.updatedAt;
      final existingReview = existing?.review;
      if (existing == null) {
        await _store.insertAlbumRow(
          profileId,
          album.provider,
          album.id,
          TransferAlbumRow(0, text, writtenAt, review.updatedAt),
        );
        copied.albumReviews++;
      } else if (existingReview == null || existingReview.trim().isEmpty) {
        // The row exists for its score only: the review itself is new here.
        await _store.updateAlbumReview(
          profileId,
          album.provider,
          album.id,
          text,
          writtenAt,
          _max(existing.updatedAt, review.updatedAt),
        );
        copied.albumReviews++;
      } else if (existingReview.contains(text)) {
        kept.albumReviews++;
      } else {
        await _store.updateAlbumReview(
          profileId,
          album.provider,
          album.id,
          TransferRules.joinReviews(existingReview, text, source.displayName),
          _max(existing.reviewUpdatedAt ?? existing.updatedAt, writtenAt),
          _max(existing.updatedAt, review.updatedAt),
        );
        updated.albumReviews++;
        joined++;
      }
      entries[ledgerKey] = '${TransferLedger.copied}${SyncFormat.compositeKey([album.provider, album.id])}';
    }

    for (final note in pending.albumNotes) {
      final ledgerKey = TransferRules.albumNoteKey(note);
      final album = albumTarget(note.album);
      if (album == null) {
        unmatched.albumNotes++;
        entries[ledgerKey] = TransferLedger.unmatched;
        continue;
      }
      final id = MigrationIds.derive(source.scope, note.id, target.scope);
      final matched = albumMatches[note.album]?.chosen;
      final row = TransferAlbumNote(
        id: id,
        profileId: profileId,
        provider: album.provider,
        albumId: album.id,
        content: remap(note.content),
        createdAt: note.createdAt,
        updatedAt: note.updatedAt,
        albumName: _nonBlank(matched?.name) ?? note.albumName,
        artist: _nonBlank(matched?.artist) ?? note.artist,
      );
      final existing = await _store.albumNote(id);
      if (existing == null) {
        await _store.putAlbumNote(row);
        copied.albumNotes++;
      } else if (existing.profileId != profileId || existing.updatedAt >= note.updatedAt) {
        kept.albumNotes++;
      } else {
        await _store.putAlbumNote(row.withCreatedAt(existing.createdAt));
        updated.albumNotes++;
      }
      entries[ledgerKey] = '${TransferLedger.copied}$id';
    }

    for (final title in pending.albumTitles) {
      final ledgerKey = TransferRules.albumTitleKey(title);
      final album = albumTarget(title.album);
      if (album == null) {
        unmatched.albumTitles++;
        entries[ledgerKey] = TransferLedger.unmatched;
        continue;
      }
      final existing = await _store.albumTitle(profileId, album.provider, album.id);
      if (existing == null) {
        await _store.putAlbumTitle(
          profileId,
          album.provider,
          album.id,
          TransferAlbumTitle(title.title, title.updatedAt),
        );
        copied.albumTitles++;
      } else if (existing.title == title.title || existing.updatedAt >= title.updatedAt) {
        kept.albumTitles++;
      } else {
        await _store.putAlbumTitle(
          profileId,
          album.provider,
          album.id,
          TransferAlbumTitle(title.title, title.updatedAt),
        );
        updated.albumTitles++;
      }
      entries[ledgerKey] = '${TransferLedger.copied}${SyncFormat.compositeKey([album.provider, album.id])}';
    }

    for (final title in pending.memoryTitles) {
      final ledgerKey = TransferRules.memoryTitleKey(title);
      final entity = memoryEntityTarget(title.provider, title.entityType, title.entityId);
      if (entity == null) {
        unmatched.memoryTitles++;
        entries[ledgerKey] = TransferLedger.unmatched;
        continue;
      }
      final existing = await _store.memory(profileId, entity.provider, title.entityType, entity.id);
      if (existing == null) {
        await _store.putMemory(
          profileId,
          TransferMemoryRow(
            provider: entity.provider,
            entityType: title.entityType,
            entityId: entity.id,
            copyText: '',
            promptHash: '',
            generatedAt: 0,
            title: title.title,
            titlePromptHash: title.titlePromptHash,
          ),
        );
        copied.memoryTitles++;
      } else if (existing.title != null && existing.title!.trim().isNotEmpty) {
        // The target's own AI title wins: it was made from the target's own Memories.
        kept.memoryTitles++;
      } else {
        await _store.putMemory(profileId, existing.withTitle(title.title, title.titlePromptHash));
        copied.memoryTitles++;
      }
      entries[ledgerKey] =
          '${TransferLedger.copied}${SyncFormat.compositeKey([entity.provider, title.entityType, entity.id])}';
    }

    for (final copy in pending.memoryCopies) {
      final ledgerKey = TransferRules.memoryCopyKey(copy);
      final entity = memoryEntityTarget(copy.provider, copy.entityType, copy.entityId);
      if (entity == null) {
        unmatched.memoryCopies++;
        entries[ledgerKey] = TransferLedger.unmatched;
        continue;
      }
      final existing = await _store.memory(profileId, entity.provider, copy.entityType, entity.id);
      if (existing == null) {
        await _store.putMemory(
          profileId,
          TransferMemoryRow(
            provider: entity.provider,
            entityType: copy.entityType,
            entityId: entity.id,
            copyText: copy.copy,
            promptHash: copy.promptHash,
            generatedAt: copy.generatedAt,
          ),
        );
        copied.memoryCopies++;
      } else if (existing.copyText.trim().isEmpty) {
        await _store.putMemory(profileId, existing.withCopy(copy.copy, copy.promptHash, copy.generatedAt));
        copied.memoryCopies++;
      } else if (copy.generatedAt > existing.generatedAt) {
        await _store.putMemory(profileId, existing.withCopy(copy.copy, copy.promptHash, copy.generatedAt));
        updated.memoryCopies++;
      } else {
        kept.memoryCopies++;
      }
      entries[ledgerKey] =
          '${TransferLedger.copied}${SyncFormat.compositeKey([entity.provider, copy.entityType, entity.id])}';
    }

    // Track → album links: the source's (mapped), then what matching learned; a link already there stays.
    final linked = <TargetRef>{};
    for (final link in pending.trackAlbumLinks) {
      final ledgerKey = TransferRules.linkKey(link);
      final track = trackTarget(link.track);
      // The album side is mapped on its own: a catalog song can sit on a library-only album id.
      final album =
          trackMatches[link.track]?.chosen?.album ?? albumTarget(SourceRef(link.track.provider, link.albumId));
      if (track == null || album == null || album.provider != track.provider) {
        unmatched.trackAlbumLinks++;
        entries[ledgerKey] = TransferLedger.unmatched;
        continue;
      }
      final linkSource = track.id == link.track.id ? link.source : BackupTrackAlbumLink.sourceImport;
      if (await _writeLink(profileId, track, album.id, linkSource, link.updatedAt)) {
        copied.trackAlbumLinks++;
      } else {
        kept.trackAlbumLinks++;
      }
      linked.add(track);
      entries[ledgerKey] = '${TransferLedger.copied}${SyncFormat.compositeKey([track.provider, track.id])}';
    }
    for (final match in tracks) {
      final chosen = match.chosen;
      if (chosen == null || !match.status.copies) continue;
      final album = chosen.album;
      if (album == null) continue;
      if (linked.contains(chosen.ref) || album.provider != chosen.provider) continue;
      if (await _writeLink(profileId, chosen.ref, album.id, BackupTrackAlbumLink.sourceImport, clock())) {
        copied.trackAlbumLinks++;
      }
    }

    for (final match in tracks) {
      entries[TransferRules.trackKey(match.source)] = TransferRules.ledgerValue(match.status, match.chosen?.ref);
    }
    for (final match in albums) {
      entries[TransferRules.albumKey(match.source)] = TransferRules.ledgerValue(match.status, match.chosen?.ref);
    }

    return TransferOutcome(
      TransferResult(
        copied: copied.counts(),
        updated: updated.counts(),
        kept: kept.counts(),
        reviewsJoined: joined,
        unmatched: unmatched.counts(),
        skippedPreviously: TransferRules.countItems(items) - TransferRules.countItems(pending),
      ),
      entries,
    );
  }

  Future<bool> _writeLink(String profileId, TargetRef track, String albumId, String source, int updatedAt) async {
    if (await _store.hasTrackAlbumLink(profileId, track.provider, track.id)) return false;
    await _store.putTrackAlbumLink(
      profileId,
      BackupTrackAlbumLink(
        provider: track.provider,
        trackId: track.id,
        albumId: albumId,
        source: source,
        updatedAt: updatedAt,
      ),
    );
    return true;
  }
}

String? _nonBlank(String? s) => s == null || s.trim().isEmpty ? null : s;

int _max(int a, int b) => a > b ? a : b;
