/// Every Memories item of one source account (:shared MemoryItems.kt),
/// normalized from this device's stores or from sync records ([fromRecords]:
/// the replica, or a backup snapshot).
library;

import 'package:flutter/foundation.dart';

import '../../sync/adapters/sync_projections.dart';
import '../../sync/adapters/track_rating_sync_adapter.dart';
import '../../sync/sync_contracts.dart';
import '../backup_format.dart';
import '../readable_export.dart' show ReadableExport;
import 'transfer_models.dart';
import 'transfer_rules.dart';

@immutable
final class MemoryItems {
  const MemoryItems({
    this.songNotes = const [],
    this.trackRatings = const [],
    this.albumRatings = const [],
    this.albumReviews = const [],
    this.albumNotes = const [],
    this.albumTitles = const [],
    this.memoryTitles = const [],
    this.memoryCopies = const [],
    this.trackAlbumLinks = const [],
    this.trackFacts = const {},
    this.albumFacts = const {},
  });

  final List<SourceNote> songNotes;
  final List<SourceTrackRating> trackRatings;
  final List<SourceAlbumRating> albumRatings;
  final List<SourceAlbumReview> albumReviews;
  final List<SourceAlbumNote> albumNotes;
  final List<SourceAlbumTitle> albumTitles;
  final List<SourceMemoryTitle> memoryTitles;
  final List<SourceMemoryCopy> memoryCopies;
  final List<SourceTrackLink> trackAlbumLinks;

  /// Local facts about source songs (title, album, duration), keyed by song.
  final Map<SourceRef, SourceTrackFacts> trackFacts;

  /// Local facts about source albums: name and artist.
  final Map<SourceRef, SourceAlbumFacts> albumFacts;

  MemoryItems copyWith({
    List<SourceNote>? songNotes,
    List<SourceTrackRating>? trackRatings,
    List<SourceAlbumRating>? albumRatings,
    List<SourceAlbumReview>? albumReviews,
    List<SourceAlbumNote>? albumNotes,
    List<SourceAlbumTitle>? albumTitles,
    List<SourceMemoryTitle>? memoryTitles,
    List<SourceMemoryCopy>? memoryCopies,
    List<SourceTrackLink>? trackAlbumLinks,
  }) => MemoryItems(
    songNotes: songNotes ?? this.songNotes,
    trackRatings: trackRatings ?? this.trackRatings,
    albumRatings: albumRatings ?? this.albumRatings,
    albumReviews: albumReviews ?? this.albumReviews,
    albumNotes: albumNotes ?? this.albumNotes,
    albumTitles: albumTitles ?? this.albumTitles,
    memoryTitles: memoryTitles ?? this.memoryTitles,
    memoryCopies: memoryCopies ?? this.memoryCopies,
    trackAlbumLinks: trackAlbumLinks ?? this.trackAlbumLinks,
    trackFacts: trackFacts,
    albumFacts: albumFacts,
  );

  /// Every song with a note, a score or a link.
  Set<SourceRef> get tracks => {
    for (final n in songNotes) n.track,
    for (final r in trackRatings) r.track,
    for (final l in trackAlbumLinks) l.track,
  };

  /// Every album with album-keyed data.
  Set<SourceRef> get albums => {
    for (final r in albumRatings) r.album,
    for (final r in albumReviews) r.album,
    for (final n in albumNotes) n.album,
    for (final t in albumTitles) t.album,
    for (final t in memoryTitles)
      if (t.entityType == TransferRules.entityAlbum) SourceRef(t.provider, t.entityId),
    for (final c in memoryCopies)
      if (c.entityType == TransferRules.entityAlbum) SourceRef(c.provider, c.entityId),
  };

  bool get isEmpty => TransferRules.countItems(this) == 0;

  /// One scope's Memories from sync records (the replica, or a backup snapshot). Tombstones are ignored.
  static MemoryItems fromRecords(List<WireRecord> records, String scope) {
    final notes = <SourceNote>[];
    final ratings = <SourceTrackRating>[];
    final albumRatings = <SourceAlbumRating>[];
    final reviews = <SourceAlbumReview>[];
    final albumNotes = <SourceAlbumNote>[];
    final titles = <SourceAlbumTitle>[];
    final memoryTitles = <SourceMemoryTitle>[];
    final memoryCopies = <SourceMemoryCopy>[];
    final links = <SourceTrackLink>[];
    for (final record in records) {
      if (record.scope != scope || record.deleted) continue;
      final p = record.payload;
      if (p == null) continue;
      switch (record.kind) {
        case SyncKinds.songNote:
          final provider = requiredString(p, 'provider');
          final trackId = requiredString(p, 'trackId');
          final content = requiredString(p, 'content');
          if (provider == null || trackId == null || content == null) continue;
          final position = optionalLong(p, 'positionMs');
          notes.add(
            SourceNote(
              id: record.key,
              track: SourceRef(provider, TransferRules.storedRawId(provider, trackId)),
              content: content,
              createdAt: requiredLong(p, 'createdAt') ?? record.ts,
              updatedAt: requiredLong(p, 'updatedAt') ?? record.ts,
              title: requiredString(p, 'title') ?? '',
              artist: requiredString(p, 'artist') ?? '',
              positionMs: position is FieldOk<int?> ? position.value : null,
            ),
          );
        case SyncKinds.trackRating:
          final wire = rating(p, 'rating');
          if (wire == null) continue;
          final value = TrackRatingSyncAdapter.ratingFrom(ReadableExport.asFloat(wire), p);
          final provider = requiredString(p, 'provider');
          final songId = requiredString(p, 'songId');
          if (value == null || provider == null || songId == null) continue;
          ratings.add(SourceTrackRating(SourceRef(provider, songId), value, record.ts));
        case SyncKinds.albumRating:
          final value = rating(p, 'rating');
          final album = _albumRef(record);
          if (value == null || value <= 0 || album == null) continue;
          albumRatings.add(SourceAlbumRating(album, ReadableExport.asFloat(value), record.ts));
        case SyncKinds.albumReview:
          final review = optionalString(p, 'review');
          final album = _albumRef(record);
          if (review is! FieldOk<String?> || review.value == null || review.value!.trim().isEmpty || album == null) {
            continue;
          }
          reviews.add(SourceAlbumReview(album, review.value!, record.ts, record.ts));
        case SyncKinds.albumTitle:
          final title = requiredString(p, 'title')?.trim();
          final album = _albumRef(record);
          if (title == null || title.isEmpty || album == null) continue;
          titles.add(SourceAlbumTitle(album, title, record.ts));
        case SyncKinds.memoryTitle:
          final parts = record.key.split(SyncFormat.keySeparator);
          final title = requiredString(p, 'title');
          if (parts.length != 3 || title == null || title.trim().isEmpty) continue;
          final hash = optionalString(p, 'titlePromptHash');
          memoryTitles.add(
            SourceMemoryTitle(
              parts[0],
              parts[1],
              parts[2],
              title,
              hash is FieldOk<String?> ? hash.value : null,
              record.ts,
            ),
          );
        case SyncKinds.memoryCopy:
          final parts = record.key.split(SyncFormat.keySeparator);
          final copy = requiredString(p, 'copy');
          final promptHash = requiredString(p, 'promptHash');
          if (parts.length != 3 || copy == null || copy.trim().isEmpty || promptHash == null) continue;
          memoryCopies.add(
            SourceMemoryCopy(
              parts[0],
              parts[1],
              parts[2],
              copy,
              promptHash,
              requiredLong(p, 'generatedAt') ?? record.ts,
            ),
          );
        case SyncKinds.albumNote:
          final provider = requiredString(p, BackupFields.provider);
          final albumId = requiredString(p, BackupFields.albumId);
          final content = requiredString(p, BackupFields.content);
          if (provider == null || albumId == null || content == null) continue;
          albumNotes.add(
            SourceAlbumNote(
              id: record.key,
              album: SourceRef(provider, albumId),
              content: content,
              createdAt: requiredLong(p, BackupFields.createdAt) ?? record.ts,
              updatedAt: requiredLong(p, BackupFields.updatedAt) ?? record.ts,
              albumName: requiredString(p, BackupFields.albumName) ?? '',
              artist: requiredString(p, BackupFields.artist) ?? '',
            ),
          );
        case BackupKinds.trackAlbumLink:
          final provider = requiredString(p, BackupFields.provider);
          final trackId = requiredString(p, BackupFields.trackId);
          final albumId = requiredString(p, BackupFields.albumId);
          if (provider == null || trackId == null || albumId == null || albumId.trim().isEmpty) continue;
          links.add(
            SourceTrackLink(
              SourceRef(provider, trackId),
              albumId,
              requiredString(p, BackupFields.source) ?? 'import',
              record.ts,
            ),
          );
      }
    }
    return MemoryItems(
      songNotes: notes,
      trackRatings: ratings,
      albumRatings: albumRatings,
      albumReviews: reviews,
      albumNotes: albumNotes,
      albumTitles: titles,
      memoryTitles: memoryTitles,
      memoryCopies: memoryCopies,
      trackAlbumLinks: links,
      albumFacts: {
        for (final n in albumNotes)
          if (n.albumName.trim().isNotEmpty) n.album: SourceAlbumFacts(n.albumName, n.artist),
      },
    );
  }

  static SourceRef? _albumRef(WireRecord record) {
    final parts = record.key.split(SyncFormat.keySeparator);
    return parts.length == 2 ? SourceRef(parts[0], parts[1]) : null;
  }
}
