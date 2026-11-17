/// What the user played and visited, per account (Android ActivityEvent +
/// YoinRepository recordPlay / recordAlbumVisit). Home (Activities, Jump
/// Back In) and Library (Recents) read it; playback and detail pages write it.
library;

import 'dart:async';
import 'dart:convert';

import '../../platform/storage/stores.dart';

enum ActivityEntityType { album, artist, playlist, song }

enum ActivityActionType { played, visited }

final class ActivityEvent {
  const ActivityEvent({
    required this.entityType,
    required this.actionType,
    required this.entityId,
    required this.title,
    required this.timestampMs,
    this.subtitle = '',
    this.coverArtKey,
    this.songId,
    this.albumId,
    this.artistId,
  });

  final ActivityEntityType entityType;
  final ActivityActionType actionType;

  /// Raw provider id of the entity (album / artist / playlist / song id).
  final String entityId;
  final String title;
  final String subtitle;

  /// CoverRef.storageKey of the cover to show.
  final String? coverArtKey;
  final String? songId;
  final String? albumId;
  final String? artistId;
  final int timestampMs;

  /// Plays and visits of the same entity collapse onto this key (newest wins).
  String get entityKey => '${entityType.name}:$entityId';

  /// This row under another [title] (a playlist renamed since it was played).
  ActivityEvent withTitle(String title) => ActivityEvent(
    entityType: entityType,
    actionType: actionType,
    entityId: entityId,
    title: title,
    timestampMs: timestampMs,
    subtitle: subtitle,
    coverArtKey: coverArtKey,
    songId: songId,
    albumId: albumId,
    artistId: artistId,
  );

  Map<String, Object?> toJson() => {
        'e': entityType.name,
        'a': actionType.name,
        'id': entityId,
        't': title,
        's': subtitle,
        'c': coverArtKey,
        'song': songId,
        'album': albumId,
        'artist': artistId,
        'ts': timestampMs,
      };

  factory ActivityEvent.fromJson(Map<String, Object?> json) => ActivityEvent(
        entityType: ActivityEntityType.values.byName(json['e']! as String),
        actionType: ActivityActionType.values.byName(json['a']! as String),
        entityId: json['id']! as String,
        title: json['t']! as String,
        subtitle: (json['s'] as String?) ?? '',
        coverArtKey: json['c'] as String?,
        songId: json['song'] as String?,
        albumId: json['album'] as String?,
        artistId: json['artist'] as String?,
        timestampMs: json['ts']! as int,
      );
}

/// One account's log: newest first, one row per entity, capped.
abstract interface class ActivityLog {
  Future<void> record(ActivityEvent event);
  Future<List<ActivityEvent>> recent({int limit = cap});

  /// Current rows first, then after every change.
  Stream<List<ActivityEvent>> watchRecent({int limit = cap});

  /// Drops [entityType] [entityId]'s row: the entity is gone from the service
  /// (a playlist deleted), so the Activities don't bring it back (Android
  /// ActivityEventDao.deletePlaylist).
  Future<void> forget(ActivityEntityType entityType, String entityId);

  /// [entityType] [entityId]'s row takes [title]: a playlist renamed on its
  /// page, so the Activities don't keep the name it had when it was played
  /// (Android ActivityEventDao.renamePlaylist).
  Future<void> retitle(ActivityEntityType entityType, String entityId, String title);

  static const cap = 400;
}

/// [ActivityLog] in one JSON value of a [KeyValueStore] (Preferences).
/// Writes are serialised; a failed write never throws to the caller
/// (logging must not affect playback).
final class StoredActivityLog implements ActivityLog {
  StoredActivityLog(this._store, {required String profileId}) : _key = keyFor(profileId);

  /// [profileId]'s log in the store.
  static String keyFor(String profileId) => 'activity.v1.$profileId';

  final KeyValueStore _store;
  final String _key;
  List<ActivityEvent>? _rows;
  Future<void> _tail = Future.value();
  final _changes = StreamController<List<ActivityEvent>>.broadcast();

  Future<List<ActivityEvent>> _load() async {
    if (_rows != null) return _rows!;
    final raw = await _store.read(_key);
    _rows = raw == null
        ? []
        : [for (final e in jsonDecode(raw) as List<Object?>) ActivityEvent.fromJson((e! as Map).cast<String, Object?>())];
    return _rows!;
  }

  @override
  Future<void> record(ActivityEvent event) {
    final done = _tail.then((_) async {
      try {
        final rows = await _load();
        rows.removeWhere((r) => r.entityKey == event.entityKey);
        rows.insert(0, event);
        if (rows.length > ActivityLog.cap) rows.removeRange(ActivityLog.cap, rows.length);
        _changes.add(List.unmodifiable(rows));
        await _store.write(_key, jsonEncode([for (final r in rows) r.toJson()]));
      } catch (_) {
        // Logging is best effort.
      }
    });
    _tail = done;
    return done;
  }

  @override
  Future<void> forget(ActivityEntityType entityType, String entityId) {
    final done = _tail.then((_) async {
      try {
        final rows = await _load();
        final before = rows.length;
        rows.removeWhere((r) => r.entityType == entityType && r.entityId == entityId);
        if (rows.length == before) return;
        _changes.add(List.unmodifiable(rows));
        await _store.write(_key, jsonEncode([for (final r in rows) r.toJson()]));
      } catch (_) {
        // Best effort, as logging.
      }
    });
    _tail = done;
    return done;
  }

  @override
  Future<void> retitle(ActivityEntityType entityType, String entityId, String title) {
    final done = _tail.then((_) async {
      try {
        final rows = await _load();
        var changed = false;
        for (final (i, r) in rows.indexed) {
          if (r.entityType != entityType || r.entityId != entityId || r.title == title) continue;
          rows[i] = r.withTitle(title);
          changed = true;
        }
        if (!changed) return;
        _changes.add(List.unmodifiable(rows));
        await _store.write(_key, jsonEncode([for (final r in rows) r.toJson()]));
      } catch (_) {
        // Best effort: the service has the name.
      }
    });
    _tail = done;
    return done;
  }

  @override
  Future<List<ActivityEvent>> recent({int limit = ActivityLog.cap}) async {
    await _tail;
    return List.unmodifiable((await _load()).take(limit));
  }

  @override
  Stream<List<ActivityEvent>> watchRecent({int limit = ActivityLog.cap}) async* {
    yield await recent(limit: limit);
    yield* _changes.stream.map((rows) => List.unmodifiable(rows.take(limit)));
  }
}
