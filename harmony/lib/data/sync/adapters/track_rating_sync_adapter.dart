/// Song ratings <-> [SyncKinds.trackRating], key = provider␟songId (Android
/// adapters/TrackRatingSyncAdapter.kt).
///
/// Only the 0..10 rating syncs. "Not rated" (a cleared row, rating null)
/// travels as `rating: 0`; a real 0.0 score adds `"zero": true`. serverRating
/// and needsSync are this device's push state for the provider: a new row
/// starts as already pushed (another device owned the push), an existing row
/// keeps its push state unless the rating value itself changes.
library;

import '../../../playback/local_ratings.dart';
import '../../model/media_id.dart';
import '../sync_contracts.dart';
import 'sync_projections.dart';

/// Reads and edits one account's rating rows in one step (the live
/// LocalRatings notifier for the active account, the store otherwise).
abstract interface class TrackRatingAccess {
  Future<Map<MediaId, LocalRating>> read(String profileId);

  Future<R> edit<R>(String profileId, R Function(Map<MediaId, LocalRating> rows) edit);
}

class TrackRatingSyncAdapter extends SyncAdapter {
  TrackRatingSyncAdapter(this._ratings);

  final TrackRatingAccess _ratings;

  /// Marks a real 0.0 score; a bare `rating: 0` is "not rated".
  static const zero = 'zero';

  @override
  String get kind => SyncKinds.trackRating;
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

  static String _profile(String? id) => id ?? (throw ArgumentError('track_rating is per-account'));

  @override
  Future<List<DomainRow>> readAll(String? localProfileId) async {
    final rows = await _ratings.read(_profile(localProfileId));
    return [
      for (final row in rows.values)
        DomainRow(
          key: SyncFormat.compositeKey([row.provider, row.songId]),
          projection: projection(row.songId, row.provider, row.rating),
          rowTs: row.updatedAt,
        ),
    ];
  }

  static JsonMap projection(String songId, String provider, double? value) => {
    'songId': songId,
    'provider': provider,
    'rating': jsonRating(value ?? 0),
    if (value == 0) zero: true,
  };

  /// The local rating a synced [wire] value means: 0 is "not rated" unless the payload marks it a score.
  static double? ratingFrom(double wire, JsonMap payload) {
    if (wire != 0) return wire;
    return payload[zero] == true ? 0 : null;
  }

  @override
  JsonMap? project(JsonMap payload) {
    final songId = requiredString(payload, 'songId');
    final provider = requiredString(payload, 'provider');
    final wire = rating(payload, 'rating');
    if (songId == null || songId.isEmpty || provider == null || provider.isEmpty || wire == null) return null;
    return projection(songId, provider, ratingFrom(wire, payload));
  }

  @override
  Future<ApplyOutcome> apply(
    String? localProfileId,
    String key,
    JsonMap payload,
    int versionTs,
    String? expectedLocalHash,
  ) async {
    final profileId = _profile(localProfileId);
    final parts = splitKey(key, 2);
    if (parts == null || parts[0].isEmpty || parts[1].isEmpty || parts[0].contains(':')) {
      return const Skipped(SkipReason.unsupported, null);
    }
    final provider = parts[0];
    final songId = parts[1];
    final id = MediaId(provider, songId);
    return _ratings.edit(profileId, (rows) {
      final existing = rows[id];
      final currentHash = existing == null ? null : hashOrNull(projection(songId, provider, existing.rating));
      final wire = rating(payload, 'rating');
      final matchesKey = payload['songId'] == songId && payload['provider'] == provider;
      if (wire == null || !matchesKey) return Skipped(SkipReason.unsupported, currentHash);
      final value = ratingFrom(wire, payload);
      if (currentHash != expectedLocalHash) return Skipped(SkipReason.changedLocally, currentHash);
      final updated = existing == null || existing.rating != value
          ? LocalRating(
              profileId: profileId,
              songId: songId,
              provider: provider,
              rating: value,
              serverRating: serverRatingFor(value),
              needsSync: false,
              updatedAt: versionTs,
            )
          : LocalRating(
              profileId: profileId,
              songId: songId,
              provider: provider,
              rating: existing.rating,
              serverRating: existing.serverRating,
              needsSync: existing.needsSync,
              updatedAt: versionTs,
            );
      rows[id] = updated;
      return Applied(hashOrNull(projection(songId, provider, updated.rating)));
    });
  }

  @override
  Future<ApplyOutcome> delete(String? localProfileId, String key, String? expectedLocalHash) async {
    final profileId = _profile(localProfileId);
    final parts = splitKey(key, 2);
    if (parts == null || parts[0].isEmpty || parts[1].isEmpty || parts[0].contains(':')) {
      return const Skipped(SkipReason.unsupported, null);
    }
    final id = MediaId(parts[0], parts[1]);
    return _ratings.edit(profileId, (rows) {
      final current = rows[id];
      if (current == null) return const Applied(null);
      final currentHash = hashOrNull(projection(parts[1], parts[0], current.rating));
      if (currentHash != expectedLocalHash) return Skipped(SkipReason.changedLocally, currentHash);
      rows.remove(id);
      return const Applied(null);
    });
  }
}

/// [TrackRatingAccess] straight on the store (tests, and accounts no screen has open).
class StoreTrackRatingAccess implements TrackRatingAccess {
  StoreTrackRatingAccess(this._store);

  final LocalRatingStore _store;
  Future<void> _tail = Future.value();

  @override
  Future<Map<MediaId, LocalRating>> read(String profileId) async {
    await _tail;
    return _store.read(profileId);
  }

  @override
  Future<R> edit<R>(String profileId, R Function(Map<MediaId, LocalRating> rows) edit) {
    final done = _tail.then((_) async {
      final rows = await _store.read(profileId);
      final result = edit(rows);
      await _store.write(profileId, rows);
      return result;
    });
    _tail = done.then((_) {}, onError: (Object _) {});
    return done;
  }
}
