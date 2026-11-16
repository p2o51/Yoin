/// The kinds kept in an account's [MemoryStore]: song notes, the album score
/// and the album review (two kinds over one row), and the user's own Memory
/// titles (Android adapters/SongNoteSyncAdapter.kt, AlbumRatingSyncAdapter.kt,
/// AlbumReviewSyncAdapter.kt, AlbumTitleSyncAdapter.kt). Payload shapes are
/// Android's, so either app reads the other's records.
library;

import '../../memory/memory_store.dart';
import '../../model/media_id.dart';
import '../sync_contracts.dart';
import 'sync_projections.dart';

/// [profileId]'s Memory rows (the live store when one is open).
typedef MemoryStoreFor = MemoryStore Function(String profileId);

String albumKey(String provider, String albumId) => SyncFormat.compositeKey([provider, albumId]);

/// (provider, albumId) of an album key.
(String, String)? albumKeyParts(String key) {
  final parts = splitKey(key, 2);
  if (parts == null || parts[0].isEmpty || parts[1].isEmpty || parts[0].contains(':')) return null;
  return (parts[0], parts[1]);
}

String _requireProfile(String? id, String kind) => id ?? (throw ArgumentError('$kind is per-account'));

// ---------------------------------------------------------------------------- song_note

/// Song notes <-> [SyncKinds.songNote]. Key = note id (globally unique), so a
/// note id that already lives under another local profile is never
/// re-parented and deletes are scoped by profile.
///
/// `updatedAt` is itself a synced field: apply writes the payload's value,
/// which keeps the read-back projection identical to the payload's.
///
/// HarmonyOS notes also remember the album they were written on (`albumId`,
/// which places a note in its Memory without the album page). It travels as
/// an extra payload field that Android keeps but ignores; a note from a
/// device that doesn't send it keeps the album this device knows.
class SongNoteSyncAdapter extends SyncAdapter {
  SongNoteSyncAdapter(this._memory);

  final MemoryStoreFor _memory;

  @override
  String get kind => SyncKinds.songNote;
  @override
  int get kindVersion => 1;
  @override
  FileClass get fileClass => FileClass.state;
  @override
  bool get perAccount => true;
  @override
  bool get propagatesDeletes => true;
  @override
  ConflictPolicy get conflictPolicy => ConflictPolicy.lwwLiveWins;

  static const _albumId = 'albumId';

  @override
  Future<List<DomainRow>> readAll(String? localProfileId) async {
    final rows = await _memory(_requireProfile(localProfileId, kind)).syncRead();
    return [
      for (final note in rows.songNotes) DomainRow(key: note.id, projection: _projection(note), rowTs: note.updatedAt),
    ];
  }

  static JsonMap _projection(SongNote note) => {
    'trackId': note.trackId.rawId,
    'provider': note.trackId.provider,
    'content': note.content,
    'createdAt': note.createdAt,
    'updatedAt': note.updatedAt,
    'title': note.title,
    'artist': note.artist,
    'positionMs': note.positionMs,
    if (note.albumId != null) _albumId: note.albumId.toString(),
  };

  @override
  JsonMap? project(JsonMap payload) {
    final note = _parse('', payload, null);
    return note == null ? null : _projection(note);
  }

  /// [payload] as a note with [id]; [fallbackAlbum] when the payload names none.
  static SongNote? _parse(String id, JsonMap payload, MediaId? fallbackAlbum) {
    final position = optionalLong(payload, 'positionMs');
    final album = optionalString(payload, _albumId);
    if (position is! FieldOk<int?> || album is! FieldOk<String?>) return null;
    final trackId = requiredString(payload, 'trackId');
    final provider = requiredString(payload, 'provider');
    final content = requiredString(payload, 'content');
    final createdAt = requiredLong(payload, 'createdAt');
    final updatedAt = requiredLong(payload, 'updatedAt');
    final title = requiredString(payload, 'title');
    final artist = requiredString(payload, 'artist');
    if (trackId == null || trackId.isEmpty || provider == null || provider.isEmpty || provider.contains(':')) {
      return null;
    }
    if (content == null || createdAt == null || updatedAt == null || title == null || artist == null) return null;
    final albumId = album.value == null ? fallbackAlbum : MediaId.tryParse(album.value);
    if (album.value != null && albumId == null) return null;
    return SongNote(
      id: id,
      trackId: MediaId(provider, trackId),
      albumId: albumId,
      content: content,
      createdAt: createdAt,
      updatedAt: updatedAt,
      title: title,
      artist: artist,
      positionMs: position.value,
    );
  }

  @override
  Future<ApplyOutcome> apply(
    String? localProfileId,
    String key,
    JsonMap payload,
    int versionTs,
    String? expectedLocalHash,
  ) {
    final store = _memory(_requireProfile(localProfileId, kind));
    return store.syncEdit((rows) {
      final index = rows.songNotes.indexWhere((n) => n.id == key);
      final existing = index < 0 ? null : rows.songNotes[index];
      final currentHash = existing == null ? null : hashOrNull(_projection(existing));
      final note = _parse(key, payload, existing?.albumId);
      if (note == null) return Skipped(SkipReason.unsupported, currentHash);
      if (currentHash != expectedLocalHash) return Skipped(SkipReason.changedLocally, currentHash);
      if (index < 0) {
        rows.songNotes.add(note);
      } else {
        rows.songNotes[index] = note;
      }
      return Applied(hashOrNull(_projection(note)));
    });
  }

  @override
  Future<ApplyOutcome> delete(String? localProfileId, String key, String? expectedLocalHash) {
    final store = _memory(_requireProfile(localProfileId, kind));
    return store.syncEdit((rows) {
      final index = rows.songNotes.indexWhere((n) => n.id == key);
      if (index < 0) return const Applied(null);
      final currentHash = hashOrNull(_projection(rows.songNotes[index]));
      if (currentHash != expectedLocalHash) return Skipped(SkipReason.changedLocally, currentHash);
      rows.songNotes.removeAt(index);
      return const Applied(null);
    });
  }
}

// ---------------------------------------------------------------------------- album_rating / album_review

/*
 * album_rating and album_review are two synced kinds over ONE album row. Each
 * adapter reads and writes only its own field and treats the other kind's
 * leftovers as "nothing here":
 *  - An empty value (score 0 / no review) reports rowTs = 0, so a row that
 *    exists only because the OTHER field was written never outranks a real
 *    value from another device when this device first joins.
 *  - apply with expectedLocalHash = null also accepts a row whose own field is
 *    still empty (it was created by the sibling kind).
 *  - apply always leaves a row behind, even for an empty value.
 *  - delete clears only this kind's field and drops the row only when the
 *    sibling field is empty too.
 *  - updatedAt is shared, so apply moves it forward only.
 */

class AlbumRatingSyncAdapter extends SyncAdapter {
  AlbumRatingSyncAdapter(this._memory);

  final MemoryStoreFor _memory;

  @override
  String get kind => SyncKinds.albumRating;
  @override
  int get kindVersion => 1;
  @override
  FileClass get fileClass => FileClass.state;
  @override
  bool get perAccount => true;
  @override
  bool get propagatesDeletes => false;
  @override
  ConflictPolicy get conflictPolicy => ConflictPolicy.lww;

  @override
  Future<List<DomainRow>> readAll(String? localProfileId) async {
    final rows = await _memory(_requireProfile(localProfileId, kind)).syncRead();
    return [
      for (final row in rows.albumRatings.values)
        DomainRow(
          key: albumKey(row.albumId.provider, row.albumId.rawId),
          projection: _projection(row.albumId.rawId, row.albumId.provider, row.rating),
          rowTs: row.rating == 0 ? 0 : row.updatedAt,
        ),
    ];
  }

  static JsonMap _projection(String albumId, String provider, double rating) => {
    'albumId': albumId,
    'provider': provider,
    'rating': jsonRating(rating),
  };

  @override
  JsonMap? project(JsonMap payload) {
    final albumId = requiredString(payload, 'albumId');
    final provider = requiredString(payload, 'provider');
    final value = rating(payload, 'rating');
    if (albumId == null || albumId.isEmpty || provider == null || provider.isEmpty || value == null) return null;
    return _projection(albumId, provider, value);
  }

  @override
  Future<ApplyOutcome> apply(
    String? localProfileId,
    String key,
    JsonMap payload,
    int versionTs,
    String? expectedLocalHash,
  ) async {
    final parts = albumKeyParts(key);
    if (parts == null) return const Skipped(SkipReason.unsupported, null);
    final (provider, rawId) = parts;
    final id = MediaId(provider, rawId);
    return _memory(_requireProfile(localProfileId, kind)).syncEdit((rows) {
      final existing = rows.albumRatings[id];
      final currentHash = existing == null ? null : hashOrNull(_projection(rawId, provider, existing.rating));
      final value = rating(payload, 'rating');
      final matchesKey = payload['albumId'] == rawId && payload['provider'] == provider;
      if (value == null || !matchesKey) return Skipped(SkipReason.unsupported, currentHash);
      final unchanged =
          currentHash == expectedLocalHash || (expectedLocalHash == null && existing != null && existing.rating == 0);
      if (!unchanged) return Skipped(SkipReason.changedLocally, currentHash);
      rows.albumRatings[id] = AlbumRatingRow(
        albumId: id,
        rating: value,
        review: existing?.review,
        updatedAt: existing == null ? versionTs : _max(existing.updatedAt, versionTs),
        reviewUpdatedAt: existing?.reviewUpdatedAt,
        // NeoDB's push state is the device's own (Android SyncDomainDao leaves it alone).
        ratingNeedsSync: existing?.ratingNeedsSync ?? false,
        reviewNeedsSync: existing?.reviewNeedsSync ?? false,
        neoDbReviewUuid: existing?.neoDbReviewUuid,
      );
      return Applied(hashOrNull(_projection(rawId, provider, value)));
    });
  }

  @override
  Future<ApplyOutcome> delete(String? localProfileId, String key, String? expectedLocalHash) async {
    final parts = albumKeyParts(key);
    if (parts == null) return const Skipped(SkipReason.unsupported, null);
    final (provider, rawId) = parts;
    final id = MediaId(provider, rawId);
    return _memory(_requireProfile(localProfileId, kind)).syncEdit((rows) {
      final current = rows.albumRatings[id];
      if (current == null) return const Applied(null);
      final currentHash = hashOrNull(_projection(rawId, provider, current.rating));
      if (currentHash != expectedLocalHash) return Skipped(SkipReason.changedLocally, currentHash);
      if (!current.hasReview) {
        rows.albumRatings.remove(id);
        return const Applied(null);
      }
      rows.albumRatings[id] = AlbumRatingRow(
        albumId: id,
        rating: 0,
        review: current.review,
        updatedAt: current.updatedAt,
        reviewUpdatedAt: current.reviewUpdatedAt,
        ratingNeedsSync: current.ratingNeedsSync,
        reviewNeedsSync: current.reviewNeedsSync,
        neoDbReviewUuid: current.neoDbReviewUuid,
      );
      return Applied(hashOrNull(_projection(rawId, provider, 0)));
    });
  }
}

/// The album review <-> [SyncKinds.albumReview]. Clearing a review is a value
/// (null), never a delete. Concurrent edits keep both texts.
class AlbumReviewSyncAdapter extends SyncAdapter {
  AlbumReviewSyncAdapter(this._memory);

  final MemoryStoreFor _memory;

  @override
  String get kind => SyncKinds.albumReview;
  @override
  int get kindVersion => 1;
  @override
  FileClass get fileClass => FileClass.state;
  @override
  bool get perAccount => true;
  @override
  bool get propagatesDeletes => false;
  @override
  ConflictPolicy get conflictPolicy => ConflictPolicy.keepBothText;

  @override
  Future<List<DomainRow>> readAll(String? localProfileId) async {
    final rows = await _memory(_requireProfile(localProfileId, kind)).syncRead();
    return [
      for (final row in rows.albumRatings.values)
        DomainRow(
          key: albumKey(row.albumId.provider, row.albumId.rawId),
          projection: _projection(row.albumId.rawId, row.albumId.provider, row.review),
          rowTs: row.hasReview ? row.updatedAt : 0,
        ),
    ];
  }

  static JsonMap _projection(String albumId, String provider, String? review) => {
    'albumId': albumId,
    'provider': provider,
    'review': review,
  };

  @override
  JsonMap? project(JsonMap payload) {
    final albumId = requiredString(payload, 'albumId');
    final provider = requiredString(payload, 'provider');
    final review = optionalString(payload, 'review');
    if (albumId == null || albumId.isEmpty || provider == null || provider.isEmpty) return null;
    if (review is! FieldOk<String?>) return null;
    return _projection(albumId, provider, review.value);
  }

  @override
  Future<ApplyOutcome> apply(
    String? localProfileId,
    String key,
    JsonMap payload,
    int versionTs,
    String? expectedLocalHash,
  ) async {
    final parts = albumKeyParts(key);
    if (parts == null) return const Skipped(SkipReason.unsupported, null);
    final (provider, rawId) = parts;
    final id = MediaId(provider, rawId);
    return _memory(_requireProfile(localProfileId, kind)).syncEdit((rows) {
      final existing = rows.albumRatings[id];
      final currentHash = existing == null ? null : hashOrNull(_projection(rawId, provider, existing.review));
      final matchesKey = payload['albumId'] == rawId && payload['provider'] == provider;
      final review = optionalString(payload, 'review');
      if (review is! FieldOk<String?> || !matchesKey) return Skipped(SkipReason.unsupported, currentHash);
      final unchanged =
          currentHash == expectedLocalHash || (expectedLocalHash == null && existing != null && !existing.hasReview);
      if (!unchanged) return Skipped(SkipReason.changedLocally, currentHash);
      final text = review.value;
      // The review's writing date: the incoming version's time when the text
      // changes, null once cleared; an identical text keeps its date.
      final blank = text == null || text.trim().isEmpty;
      final reviewUpdatedAt = blank
          ? null
          : existing?.review == text
          ? (existing?.reviewUpdatedAt ?? versionTs)
          : versionTs;
      rows.albumRatings[id] = AlbumRatingRow(
        albumId: id,
        rating: existing?.rating ?? 0,
        review: text,
        updatedAt: existing == null ? versionTs : _max(existing.updatedAt, versionTs),
        reviewUpdatedAt: reviewUpdatedAt,
        ratingNeedsSync: existing?.ratingNeedsSync ?? false,
        reviewNeedsSync: existing?.reviewNeedsSync ?? false,
        neoDbReviewUuid: existing?.neoDbReviewUuid,
      );
      return Applied(hashOrNull(_projection(rawId, provider, text)));
    });
  }

  @override
  Future<ApplyOutcome> delete(String? localProfileId, String key, String? expectedLocalHash) async {
    final parts = albumKeyParts(key);
    if (parts == null) return const Skipped(SkipReason.unsupported, null);
    final (provider, rawId) = parts;
    final id = MediaId(provider, rawId);
    return _memory(_requireProfile(localProfileId, kind)).syncEdit((rows) {
      final current = rows.albumRatings[id];
      if (current == null) return const Applied(null);
      final currentHash = hashOrNull(_projection(rawId, provider, current.review));
      if (currentHash != expectedLocalHash) return Skipped(SkipReason.changedLocally, currentHash);
      if (current.rating == 0) {
        rows.albumRatings.remove(id);
        return const Applied(null);
      }
      rows.albumRatings[id] = AlbumRatingRow(
        albumId: id,
        rating: current.rating,
        updatedAt: current.updatedAt,
        ratingNeedsSync: current.ratingNeedsSync,
        reviewNeedsSync: current.reviewNeedsSync,
        neoDbReviewUuid: current.neoDbReviewUuid,
      );
      return Applied(hashOrNull(_projection(rawId, provider, null)));
    });
  }

  @override
  JsonMap? mergeConcurrent(JsonMap winner, JsonMap loser, String loserLabel) {
    final winnerText = requiredString(winner, 'review');
    final loserText = requiredString(loser, 'review');
    if (winnerText == null || winnerText.trim().isEmpty || loserText == null || loserText.trim().isEmpty) return null;
    if (winnerText == loserText) return null;
    return withField(winner, 'review', mergedReview(winnerText, loserText, loserLabel));
  }

  /// Both texts, the loser under a "— device · date —" divider.
  static String mergedReview(String winner, String loser, String loserLabel) => '$winner\n\n— $loserLabel —\n$loser';
}

// ---------------------------------------------------------------------------- album_title

/// The user's own Memory titles <-> [SyncKinds.albumTitle]: one record per
/// album (key provider␟albumId), last writer wins on the edit time. A missing
/// row means "use Yoin's title" (restoring it deletes the row), so deletes
/// propagate as tombstones.
class AlbumTitleSyncAdapter extends SyncAdapter {
  AlbumTitleSyncAdapter(this._memory);

  final MemoryStoreFor _memory;

  @override
  String get kind => SyncKinds.albumTitle;
  @override
  int get kindVersion => 1;
  @override
  FileClass get fileClass => FileClass.state;
  @override
  bool get perAccount => true;
  @override
  bool get propagatesDeletes => true;
  @override
  ConflictPolicy get conflictPolicy => ConflictPolicy.lww;

  static const _title = 'title';

  static JsonMap _projection(String title) => {_title: title};

  @override
  Future<List<DomainRow>> readAll(String? localProfileId) async {
    final rows = await _memory(_requireProfile(localProfileId, kind)).syncRead();
    return [
      for (final row in rows.titles.values)
        DomainRow(
          key: albumKey(row.albumId.provider, row.albumId.rawId),
          projection: _projection(row.title),
          rowTs: row.updatedAt,
        ),
    ];
  }

  @override
  JsonMap? project(JsonMap payload) {
    final title = requiredString(payload, _title)?.trim();
    return title == null || title.isEmpty ? null : _projection(title);
  }

  @override
  Future<ApplyOutcome> apply(
    String? localProfileId,
    String key,
    JsonMap payload,
    int versionTs,
    String? expectedLocalHash,
  ) async {
    final parts = albumKeyParts(key);
    if (parts == null) return const Skipped(SkipReason.unsupported, null);
    final id = MediaId(parts.$1, parts.$2);
    return _memory(_requireProfile(localProfileId, kind)).syncEdit((rows) {
      final current = rows.titles[id];
      final currentHash = current == null ? null : hashOrNull(_projection(current.title));
      final title = project(payload)?[_title] as String?;
      if (title == null) return Skipped(SkipReason.unsupported, currentHash);
      if (currentHash != expectedLocalHash) return Skipped(SkipReason.changedLocally, currentHash);
      rows.titles[id] = MemoryTitleRow(albumId: id, title: title, updatedAt: versionTs);
      return Applied(hashOrNull(_projection(title)));
    });
  }

  @override
  Future<ApplyOutcome> delete(String? localProfileId, String key, String? expectedLocalHash) async {
    final parts = albumKeyParts(key);
    if (parts == null) return const Applied(null);
    final id = MediaId(parts.$1, parts.$2);
    return _memory(_requireProfile(localProfileId, kind)).syncEdit((rows) {
      final current = rows.titles[id];
      if (current == null) return const Applied(null);
      final currentHash = hashOrNull(_projection(current.title));
      if (currentHash != expectedLocalHash) return Skipped(SkipReason.changedLocally, currentHash);
      rows.titles.remove(id);
      return const Applied(null);
    });
  }
}

int _max(int a, int b) => a > b ? a : b;
