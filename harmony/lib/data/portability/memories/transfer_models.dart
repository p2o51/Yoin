/// Transfer Memories: the two sides, refs and matches (:shared
/// portability/memories/TransferModels.kt + MemoryItems.kt's item rows).
library;

import 'package:flutter/foundation.dart';

/// One side of a transfer.
@immutable
final class TransferAccount {
  const TransferAccount({
    required this.scope,
    required this.provider,
    required this.displayName,
    this.serverKey,
    this.localProfileId,
  });

  /// The account's scope (sync id, or "local-`profile id`"): what copied note ids are derived from.
  final String scope;
  final String provider;
  final String displayName;

  /// Subsonic "host[:port]": two Subsonic accounts share ids only on one server.
  final String? serverKey;

  /// The local profile, when the account is on this device.
  final String? localProfileId;

  @override
  bool operator ==(Object other) =>
      other is TransferAccount &&
      other.scope == scope &&
      other.provider == provider &&
      other.displayName == displayName &&
      other.serverKey == serverKey &&
      other.localProfileId == localProfileId;

  @override
  int get hashCode => Object.hash(scope, provider, displayName, serverKey, localProfileId);
}

/// A song or album on the source side: provider + raw id. "provider:id".
@immutable
final class SourceRef {
  const SourceRef(this.provider, this.id);
  final String provider;
  final String id;

  @override
  bool operator ==(Object other) => other is SourceRef && other.provider == provider && other.id == id;

  @override
  int get hashCode => Object.hash(provider, id);

  @override
  String toString() => '$provider:$id';
}

/// A song or album on the target side. "provider:id" like a MediaId.
@immutable
final class TargetRef {
  const TargetRef(this.provider, this.id);
  final String provider;
  final String id;

  @override
  bool operator ==(Object other) => other is TargetRef && other.provider == provider && other.id == id;

  @override
  int get hashCode => Object.hash(provider, id);

  @override
  String toString() => '$provider:$id';
}

enum MatchStatus {
  /// Same id space: the key is copied as it is.
  direct('Direct'),

  /// Found with confidence (ISRC, title + artist + duration, or the album of its matched songs).
  matched('Matched'),

  /// Close candidates only: not copied unless the user picks one.
  uncertain('Uncertain'),

  /// Nothing found (or nothing known about the source to search with).
  unmatched('Unmatched'),

  /// The user picked the counterpart (this run or a previous one).
  picked('Picked');

  const MatchStatus(this.wireName);

  /// Kotlin's enum name (the golden vectors spell it so).
  final String wireName;

  static MatchStatus byWireName(String name) => values.firstWhere((s) => s.wireName == name);

  bool get copies => this == matched || this == picked;
}

/// Where one source song goes, as matching decided it.
@immutable
final class TrackPick {
  const TrackPick(this.source, this.status, this.chosen);
  final SourceRef source;
  final MatchStatus status;
  final TrackTarget? chosen;
}

/// A target song: its id, the names it carries, and the album it is on there (when known).
@immutable
final class TrackTarget {
  const TrackTarget({
    required this.provider,
    required this.id,
    this.title,
    this.artist,
    this.albumProvider,
    this.albumId,
  });

  final String provider;
  final String id;
  final String? title;
  final String? artist;
  final String? albumProvider;
  final String? albumId;

  TargetRef get ref => TargetRef(provider, id);

  TargetRef? get album => albumProvider != null && albumId != null ? TargetRef(albumProvider!, albumId!) : null;
}

/// Where one source album goes.
@immutable
final class AlbumPick {
  const AlbumPick(this.source, this.status, this.chosen);
  final SourceRef source;
  final MatchStatus status;
  final AlbumTarget? chosen;
}

@immutable
final class AlbumTarget {
  const AlbumTarget({required this.provider, required this.id, required this.name, this.artist});
  final String provider;
  final String id;
  final String name;
  final String? artist;

  TargetRef get ref => TargetRef(provider, id);
}

/// Counts per Memories kind, for previews and results.
@immutable
final class TransferCounts {
  const TransferCounts({
    this.songNotes = 0,
    this.trackRatings = 0,
    this.albumRatings = 0,
    this.albumReviews = 0,
    this.albumNotes = 0,
    this.albumTitles = 0,
    this.memoryTitles = 0,
    this.memoryCopies = 0,
    this.trackAlbumLinks = 0,
  });

  final int songNotes;
  final int trackRatings;
  final int albumRatings;
  final int albumReviews;
  final int albumNotes;
  final int albumTitles;
  final int memoryTitles;
  final int memoryCopies;
  final int trackAlbumLinks;

  int get total =>
      songNotes +
      trackRatings +
      albumRatings +
      albumReviews +
      albumNotes +
      albumTitles +
      memoryTitles +
      memoryCopies +
      trackAlbumLinks;

  Map<String, int> toJson() => {
    'songNotes': songNotes,
    'trackRatings': trackRatings,
    'albumRatings': albumRatings,
    'albumReviews': albumReviews,
    'albumNotes': albumNotes,
    'albumTitles': albumTitles,
    'memoryTitles': memoryTitles,
    'memoryCopies': memoryCopies,
    'trackAlbumLinks': trackAlbumLinks,
  };
}

/// What applying a transfer did.
@immutable
final class TransferResult {
  const TransferResult({
    required this.copied,
    required this.updated,
    required this.kept,
    required this.reviewsJoined,
    required this.unmatched,
    required this.skippedPreviously,
  });

  /// New rows in the target.
  final TransferCounts copied;

  /// Target rows updated (a newer source value, a review joined, a missing field filled).
  final TransferCounts updated;

  /// The target already had it (same value, or a newer one).
  final TransferCounts kept;

  /// Reviews where both texts were kept.
  final int reviewsJoined;

  /// Items whose song or album has no counterpart (yet): retried on the next run.
  final TransferCounts unmatched;

  /// Items a previous run already copied.
  final int skippedPreviously;
}

// ---- the items of one source account (MemoryItems.kt)

@immutable
final class SourceNote {
  const SourceNote({
    required this.id,
    required this.track,
    required this.content,
    required this.createdAt,
    required this.updatedAt,
    required this.title,
    required this.artist,
    this.positionMs,
  });

  final String id;
  final SourceRef track;
  final String content;
  final int createdAt;
  final int updatedAt;
  final String title;
  final String artist;
  final int? positionMs;
}

@immutable
final class SourceTrackRating {
  const SourceTrackRating(this.track, this.rating, this.updatedAt);
  final SourceRef track;
  final double rating;
  final int updatedAt;
}

@immutable
final class SourceAlbumRating {
  const SourceAlbumRating(this.album, this.rating, this.updatedAt);
  final SourceRef album;
  final double rating;
  final int updatedAt;
}

@immutable
final class SourceAlbumReview {
  const SourceAlbumReview(this.album, this.review, this.updatedAt, this.reviewUpdatedAt);
  final SourceRef album;
  final String review;
  final int updatedAt;
  final int? reviewUpdatedAt;
}

@immutable
final class SourceAlbumNote {
  const SourceAlbumNote({
    required this.id,
    required this.album,
    required this.content,
    required this.createdAt,
    required this.updatedAt,
    required this.albumName,
    required this.artist,
  });

  final String id;
  final SourceRef album;
  final String content;
  final int createdAt;
  final int updatedAt;
  final String albumName;
  final String artist;
}

@immutable
final class SourceAlbumTitle {
  const SourceAlbumTitle(this.album, this.title, this.updatedAt);
  final SourceRef album;
  final String title;
  final int updatedAt;
}

/// memory_copy_cache's AI title; [entityType] "album" keys an album, anything else is copied as is.
@immutable
final class SourceMemoryTitle {
  const SourceMemoryTitle(
    this.provider,
    this.entityType,
    this.entityId,
    this.title,
    this.titlePromptHash,
    this.updatedAt,
  );
  final String provider;
  final String entityType;
  final String entityId;
  final String title;
  final String? titlePromptHash;
  final int updatedAt;
}

@immutable
final class SourceMemoryCopy {
  const SourceMemoryCopy(this.provider, this.entityType, this.entityId, this.copy, this.promptHash, this.generatedAt);
  final String provider;
  final String entityType;
  final String entityId;
  final String copy;
  final String promptHash;
  final int generatedAt;
}

@immutable
final class SourceTrackLink {
  const SourceTrackLink(this.track, this.albumId, this.source, this.updatedAt);
  final SourceRef track;
  final String albumId;
  final String source;
  final int updatedAt;
}

/// A song the source knows something about (Yoin's plays): what matching searches with.
@immutable
final class SourceTrackFacts {
  const SourceTrackFacts({required this.title, required this.artist, this.album, this.albumId, this.durationSec});
  final String title;
  final String artist;
  final String? album;
  final String? albumId;
  final int? durationSec;
}

/// An album the source knows by name (plays, album notes).
@immutable
final class SourceAlbumFacts {
  const SourceAlbumFacts(this.name, this.artist);
  final String name;
  final String artist;
}
