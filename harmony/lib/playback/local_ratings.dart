import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart' show AppLifecycleListener;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/model/model.dart';
import '../data/source/music_source.dart';
import '../platform/storage/stores.dart';

/// One track's local rating for one account (Android data/local/LocalRating,
/// the `local_ratings` row keyed by (profileId, songId, provider)).
@immutable
final class LocalRating {
  const LocalRating({
    required this.profileId,
    required this.songId,
    required this.provider,
    required this.rating,
    required this.serverRating,
    this.needsSync = false,
    required this.updatedAt,
  });

  final String profileId;
  final String songId;
  final String provider;

  /// 0.0–10.0, or null when cleared. 0.0 is a real score, not "unrated".
  final double? rating;

  /// What the provider gets: round(rating / 2), 0–5 (0 clears on Subsonic).
  final int serverRating;

  /// Stored but not yet taken by the provider.
  final bool needsSync;
  final int updatedAt;

  MediaId get trackId => MediaId(provider, songId);

  LocalRating synced() => LocalRating(
    profileId: profileId,
    songId: songId,
    provider: provider,
    rating: rating,
    serverRating: serverRating,
    needsSync: false,
    updatedAt: updatedAt,
  );

  Map<String, Object?> toJson() => {'r': rating, 's': serverRating, 'n': needsSync, 'u': updatedAt};

  static LocalRating? fromJson(String profileId, MediaId id, Object? json) {
    if (json is! Map) return null;
    final r = json['r'];
    final s = json['s'];
    final u = json['u'];
    return LocalRating(
      profileId: profileId,
      songId: id.rawId,
      provider: id.provider,
      rating: r is num ? r.toDouble().clamp(0.0, 10.0) : null,
      serverRating: s is num ? s.toInt().clamp(0, 5) : serverRatingFor(r is num ? r.toDouble() : null),
      needsSync: json['n'] == true,
      updatedAt: u is num ? u.toInt() : 0,
    );
  }

  @override
  bool operator ==(Object other) =>
      other is LocalRating &&
      other.profileId == profileId &&
      other.songId == songId &&
      other.provider == provider &&
      other.rating == rating &&
      other.serverRating == serverRating &&
      other.needsSync == needsSync &&
      other.updatedAt == updatedAt;

  @override
  int get hashCode => Object.hash(profileId, songId, provider, rating, serverRating, needsSync, updatedAt);
}

/// The provider's 0–5 for a local 0.0–10.0 (YoinRepository.storeRating):
/// round half up, a cleared rating sends 0.
int serverRatingFor(double? rating) => ((rating ?? 0) / 2).round().clamp(0, 5);

/// Local ratings on the key-value store: one JSON document per account,
/// `{"provider:rawId": {r, s, n, u}}` (Android keeps a Room table; the shape
/// carries the same columns).
class LocalRatingStore {
  const LocalRatingStore(this._store);

  final KeyValueStore _store;

  static String keyFor(String profileId) => 'local_ratings.$profileId';

  Future<Map<MediaId, LocalRating>> read(String profileId) async {
    final raw = await _store.read(keyFor(profileId));
    if (raw == null || raw.isEmpty) return {};
    final Object? decoded;
    try {
      decoded = jsonDecode(raw);
    } on FormatException {
      return {};
    }
    if (decoded is! Map) return {};
    final out = <MediaId, LocalRating>{};
    decoded.forEach((key, value) {
      final id = MediaId.tryParse(key is String ? key : null);
      if (id == null) return;
      final row = LocalRating.fromJson(profileId, id, value);
      if (row != null) out[id] = row;
    });
    return out;
  }

  Future<void> write(String profileId, Map<MediaId, LocalRating> rows) =>
      _store.write(keyFor(profileId), jsonEncode({for (final e in rows.entries) e.key.toString(): e.value.toJson()}));
}

/// After this many sends in a row fail, a pending-ratings sync gives up
/// until the next trigger (RATING_RETRY_MAX_FAILURES).
const int ratingRetryMaxFailures = 3;

/// The active account's local ratings (Android YoinRepository "Rating
/// (local-first, best-effort server sync)").
///
/// A rating is stored at once (in call order) and only then pushed to the
/// provider as [serverRatingFor]; pushes go one at a time, and one that a
/// newer rating of the same track overtook while it waited is skipped. A
/// push that lands clears needsSync only while the row still holds the
/// rating it sent (markSynced), so a late answer never rolls a newer one
/// back. A push that fails leaves the row needsSync: [syncPending] sends
/// those again when an account becomes active and when the app comes back
/// to the foreground (Android: when a network comes back), stopping after
/// [ratingRetryMaxFailures] failures in a row, or at once when the source
/// keeps no ratings (they stay local, as on Spotify / Apple Music).
///
/// The rating survives a failed push: it is never rolled back and no notice
/// is raised for it (Android shows none, and has no copy for it).
class LocalRatings extends Notifier<Map<MediaId, LocalRating>> {
  String? _profileId;
  MusicSource? _source;
  Completer<String?> _ready = Completer<String?>();
  int _generation = 0;
  AppLifecycleListener? _lifecycle;
  bool _syncing = false;

  Future<void> _storeChain = Future.value();
  Future<void> _pushChain = Future.value();

  /// Per (profile, track), the newest rating stored whose push hasn't run yet.
  final Map<String, LocalRating> _newest = {};

  KeyValueStore? get _kv {
    try {
      return ref.read(keyValueStoreProvider);
    } catch (_) {
      return null;
    }
  }

  @override
  Map<MediaId, LocalRating> build() {
    // Rebuilt when the account changes: another account's ratings load.
    final source = ref.watch(activeSourceProvider).value;
    final generation = ++_generation;
    _source = source;
    _profileId = null;
    // A write still waiting on the previous account's load gives up.
    if (!_ready.isCompleted) _ready.complete(null);
    _ready = Completer<String?>();
    // After build returns: the load may finish (or fail) synchronously and set state.
    scheduleMicrotask(() => _load(generation));
    try {
      _lifecycle?.dispose();
      _lifecycle = AppLifecycleListener(onResume: () => syncPending());
    } catch (_) {
      _lifecycle = null; // No binding (plain unit tests).
    }
    ref.onDispose(() {
      _lifecycle?.dispose();
      _lifecycle = null;
      // Nothing loads any more: a write or a cloud sync step waiting for the rows stops waiting.
      if (!_ready.isCompleted) _ready.complete(null);
    });
    return const {};
  }

  Future<void> _load(int generation) async {
    String? profileId;
    var rows = <MediaId, LocalRating>{};
    try {
      profileId = (await ref.read(profileRepositoryProvider).activeProfile())?.id;
      final kv = _kv;
      if (profileId != null && kv != null) rows = await LocalRatingStore(kv).read(profileId);
    } catch (e) {
      _log('load failed: ${e.runtimeType}');
    }
    if (!ref.mounted || generation != _generation) return;
    _profileId = profileId;
    // Writes made while loading (none reach here: they wait on _ready) keep precedence.
    state = {...rows, ...state};
    if (!_ready.isCompleted) _ready.complete(profileId);
    if (profileId != null) unawaited(syncPending());
  }

  /// The rating shown for [id] (null: not rated or cleared).
  double? ratingOf(MediaId id) => state[id]?.rating;

  /// Rates [id] 0.0–10.0: stored at once, then pushed. The future is the
  /// whole write, push included.
  Future<void> setRating(MediaId id, double rating) => _write(id, rating.clamp(0.0, 10.0).toDouble());

  /// Takes [id] back to "not rated" (null, never 0.0). The row is kept, so
  /// the clear syncs like any other edit; the provider gets 0.
  Future<void> clearRating(MediaId id) => _write(id, null);

  Future<void> _write(MediaId id, double? rating) async {
    final profileId = _profileId ?? await _ready.future;
    if (profileId == null || !ref.mounted || profileId != _profileId) return;
    final pending = LocalRating(
      profileId: profileId,
      songId: id.rawId,
      provider: id.provider,
      rating: rating,
      serverRating: serverRatingFor(rating),
      needsSync: true,
      updatedAt: DateTime.now().millisecondsSinceEpoch,
    );
    state = {...state, id: pending};
    _newest[_key(pending)] = pending;
    await _persist(profileId);
    await _enqueuePush(() => _push(pending));
  }

  /// Writes this account's rows, in call order. False when the store failed.
  Future<bool> _persist(String profileId) {
    final kv = _kv;
    if (kv == null) return Future.value(true);
    final queued = Map<MediaId, LocalRating>.of(state);
    final done = Completer<bool>();
    _storeChain = _storeChain.then((_) async {
      try {
        // The rows as they are when the write runs: a cloud sync edit queued
        // before this one changed them, and writing the older copy would undo
        // it on disk while the sync replica believes it landed. Another
        // account by then: the copy taken for this one.
        final rows = ref.mounted && _profileId == profileId ? Map<MediaId, LocalRating>.of(state) : queued;
        await LocalRatingStore(kv).write(profileId, rows);
        done.complete(true);
      } catch (e) {
        _log('store failed: ${e.runtimeType}');
        done.complete(false);
      }
    });
    return done.future;
  }

  Future<T> _enqueuePush<T>(Future<T> Function() task) {
    final done = Completer<T>();
    _pushChain = _pushChain.then((_) async {
      try {
        done.complete(await task());
      } catch (e, st) {
        done.completeError(e, st);
      }
    });
    return done.future;
  }

  Future<void> _push(LocalRating pending) async {
    final key = _key(pending);
    // Overtaken while it waited: the newer rating's own push carries it.
    if (!identical(_newest[key], pending)) return;
    try {
      final source = _sourceFor(pending);
      if (source == null) return;
      try {
        await source.writeActions.setRating(pending.trackId, pending.serverRating);
        _markSynced(pending);
      } catch (e) {
        _log('push failed: ${e.runtimeType}');
      }
    } finally {
      if (identical(_newest[key], pending)) _newest.remove(key);
    }
  }

  /// The active source, when [row] is its account's and it keeps ratings.
  MusicSource? _sourceFor(LocalRating row) {
    if (!ref.mounted || _profileId != row.profileId) return null;
    final source = _source;
    if (source == null || source.provider != row.provider) return null;
    if (!source.capabilities.contains(Capability.rating)) return null;
    return source;
  }

  /// The provider took [sent]: clear needsSync only while the row still holds
  /// that rating (a newer one written meanwhile keeps its flag).
  void _markSynced(LocalRating sent) {
    if (!ref.mounted || _profileId != sent.profileId) return;
    final row = state[sent.trackId];
    if (row == null || row.rating != sent.rating || !row.needsSync) return;
    state = {...state, sent.trackId: row.synced()};
    _persist(sent.profileId).ignore();
  }

  /// Sends again the ratings the active account's provider hasn't taken.
  /// One at a time behind the push queue, each row read again right before
  /// it goes. Ends early when the account changes, when the source keeps no
  /// ratings, or after [ratingRetryMaxFailures] failures in a row.
  Future<void> syncPending() async {
    if (_syncing) return;
    final profileId = _profileId;
    final source = _source;
    if (profileId == null || source == null || !source.capabilities.contains(Capability.rating)) return;
    _syncing = true;
    try {
      final waiting = [
        for (final row in state.values)
          if (row.needsSync && row.provider == source.provider) row.trackId,
      ];
      var failuresInARow = 0;
      for (final id in waiting) {
        final goOn = await _enqueuePush(() async {
          // The account moved on: these ratings are another server's now.
          if (!ref.mounted || _profileId != profileId || !identical(_source, source)) return false;
          final row = state[id];
          if (row == null || !row.needsSync) return true;
          // A rating written since has its own push on the way.
          if (_newest.containsKey(_key(row))) return true;
          try {
            await source.writeActions.setRating(id, row.serverRating);
            _markSynced(row);
            failuresInARow = 0;
          } on UnsupportedSourceOperation {
            return false;
          } catch (e) {
            _log('retry failed: ${e.runtimeType}');
            failuresInARow++;
          }
          return failuresInARow < ratingRetryMaxFailures;
        });
        if (!goOn) return;
      }
    } finally {
      _syncing = false;
    }
  }

  /// Cloud sync's step over [profileId]'s rows: [edit] changes them in place
  /// (or not) and its answer comes back. Runs behind this notifier's own
  /// writes, so a rating made meanwhile never overwrites a synced one; for the
  /// active account the screen state follows at once. Rows written here keep
  /// their own needsSync (sync sets it), and no push starts: another device
  /// already sent its rating to the provider.
  Future<R> syncEdit<R>(String profileId, R Function(Map<MediaId, LocalRating> rows) edit) async {
    // Until the active account's rows are loaded (again, when it changes meanwhile).
    while (!_ready.isCompleted) {
      await _ready.future;
    }
    final kv = _kv;
    if (kv == null) throw StateError('No store');
    final done = Completer<R>();
    _storeChain = _storeChain.then((_) async {
      try {
        final active = ref.mounted && _profileId == profileId;
        final rows = active ? Map<MediaId, LocalRating>.of(state) : await LocalRatingStore(kv).read(profileId);
        final before = jsonEncode({for (final e in rows.entries) e.key.toString(): e.value.toJson()});
        final result = edit(rows);
        final after = jsonEncode({for (final e in rows.entries) e.key.toString(): e.value.toJson()});
        if (after != before) {
          await LocalRatingStore(kv).write(profileId, rows);
          if (active && ref.mounted && _profileId == profileId) state = rows;
        }
        done.complete(result);
      } catch (e, st) {
        done.completeError(e, st);
      }
    });
    return done.future;
  }

  String _key(LocalRating r) => '${r.profileId}|${r.provider}:${r.songId}';

  void _log(String message) => debugPrint('YoinRatings: $message');
}

final localRatingsProvider = NotifierProvider<LocalRatings, Map<MediaId, LocalRating>>(LocalRatings.new);
