/// Persistent cache for album / artist / playlist DETAIL responses (Android
/// data/cache/DetailCacheStore.kt), stored as JSON and bounded by a byte
/// budget — like Spotify's on-device cache.
///
/// Android keeps the rows in Room; here each row is one file under
/// `<filesDir>/detail_cache/` and a small index (`index.json`: key, size,
/// cachedAt, accessedAt) says what is there, read once and rewritten
/// atomically (temp file + rename) after changes.
///
/// Eviction is LRU by `accessedAt` once the total size exceeds [maxBytes],
/// down to [trimTargetBytes] (hysteresis, so the next writes have room).
/// Entries older than [maxAgeMs] are never served and get purged, save those
/// [keeps] holds (downloaded albums and playlists). Profile-scoped via the
/// key, so it survives account switches without leaking one account's data
/// into another.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:isolate';

import '../model/model.dart';
import 'detail_cache_json.dart';

/// A cached disk row. [cachedAt] is there at once so callers can judge
/// freshness before paying the decode; [value] reads and decodes lazily, so a
/// row that is never served is never decoded. Only a decoded row is
/// LRU-touched; a corrupt one is deleted instead.
final class Cached<V> {
  Cached(this.cachedAt, this._load);

  final int cachedAt;
  final Future<V?> Function() _load;
  Future<V?>? _value;

  /// The row's value, or null when it can't be read or decoded.
  Future<V?> value() => _value ??= _load();
}

/// Whether a row must stay (a downloaded album or playlist): never aged out or trimmed.
typedef DetailCacheKeeps = Future<bool> Function(String profileId, String kind, String entityId);

final class DetailCacheStore {
  DetailCacheStore(
    this._directory, {
    int Function()? clock,
    this.maxBytes = defaultMaxBytes,
    this.maxAgeMs = defaultMaxAgeMs,
    int? trimTargetBytes,
    DetailCacheKeeps? keeps,
  }) : _clock = clock ?? _now,
       trimTargetBytes = trimTargetBytes ?? maxBytes * 5 ~/ 6,
       _keeps = keeps ?? _keepNothing {
    assert(this.trimTargetBytes >= 0 && this.trimTargetBytes <= maxBytes, 'trimTargetBytes must be within 0..maxBytes');
  }

  static const kindAlbum = 'ALBUM';
  static const kindArtist = 'ARTIST';
  static const kindPlaylist = 'PLAYLIST';

  /// ~24 MB on disk (JSON size).
  static const defaultMaxBytes = 24 * 1024 * 1024;

  /// Entries older than this are never served and get purged (30 days).
  static const defaultMaxAgeMs = 30 * 24 * 60 * 60 * 1000;

  /// A row this big decodes / encodes on a background isolate.
  static const _isolateBytes = 64 * 1024;

  static int _now() => DateTime.now().millisecondsSinceEpoch;
  static Future<bool> _keepNothing(String p, String k, String i) async => false;

  /// The cache's folder (null: no files on this build, the cache is off).
  final Future<Directory?> Function() _directory;
  final int Function() _clock;
  final int maxBytes;
  final int maxAgeMs;
  final int trimTargetBytes;
  final DetailCacheKeeps _keeps;

  // ---------------------------------------------------------------- typed API

  Future<Cached<Album>?> readAlbum(String profileId, String id) =>
      _read(profileId, kindAlbum, id, DetailCacheJson.albumFrom);

  /// [stillCurrent] guards a background write; see [_write].
  Future<void> writeAlbum(String profileId, String id, Album value, {bool Function()? stillCurrent}) =>
      _write(profileId, kindAlbum, id, stillCurrent, () => DetailCacheJson.album(value));

  Future<Cached<ArtistDetail>?> readArtist(String profileId, String id) =>
      _read(profileId, kindArtist, id, DetailCacheJson.artistFrom);

  Future<void> writeArtist(String profileId, String id, ArtistDetail value, {bool Function()? stillCurrent}) =>
      _write(profileId, kindArtist, id, stillCurrent, () => DetailCacheJson.artist(value));

  Future<Cached<Playlist>?> readPlaylist(String profileId, String id) =>
      _read(profileId, kindPlaylist, id, DetailCacheJson.playlistFrom);

  Future<void> writePlaylist(String profileId, String id, Playlist value, {bool Function()? stillCurrent}) =>
      _write(profileId, kindPlaylist, id, stillCurrent, () => DetailCacheJson.playlist(value));

  /// Drops one cached copy (an edit changed it).
  Future<void> remove(String profileId, String kind, String id) => _locked(() async {
    final index = await _index();
    if (index == null) return;
    await _delete(index, _key(profileId, kind, id));
    _scheduleFlush();
  });

  /// Every entry of [profileId].
  Future<void> removeProfile(String profileId) => _locked(() async {
    final index = await _index();
    if (index == null) return;
    for (final e in [...index.rows.values.where((r) => r.profileId == profileId)]) {
      await _delete(index, e.key);
    }
    _scheduleFlush();
  });

  /// Hygiene pass (runs as the cache first opens): ages out anything past [maxAgeMs].
  Future<void> purgeExpired() => _locked(() async {
    final index = await _index();
    if (index == null) return;
    final cutoff = _clock() - maxAgeMs;
    for (final e in [...index.rows.values.where((r) => r.cachedAt < cutoff)]) {
      if (!await _keeps(e.profileId, e.kind, e.entityId)) await _delete(index, e.key);
    }
    _scheduleFlush();
  });

  /// The bytes the rows take, as the index counts them.
  Future<int> totalBytes() async => (await _index())?.total ?? 0;

  /// Writes the index now (tests, and before the app goes away).
  Future<void> flush() => _locked(() async {
    _flushTimer?.cancel();
    _flushTimer = null;
    final index = await _index();
    if (index != null && index.dirty) await _saveIndex(index);
  });

  // ---------------------------------------------------------------- read / write

  Future<Cached<V>?> _read<V>(String profileId, String kind, String id, V? Function(Object?) decode) async {
    final index = await _index();
    if (index == null) return null;
    final key = _key(profileId, kind, id);
    final row = index.rows[key];
    if (row == null) return null;
    if (_clock() - row.cachedAt > maxAgeMs && !await _keeps(profileId, kind, id)) {
      await _locked(() async {
        await _delete(index, key);
        _scheduleFlush();
      });
      return null;
    }
    return Cached<V>(row.cachedAt, () async {
      V? value;
      try {
        final file = File('${index.folder.path}/${row.file}');
        final text = await file.readAsString();
        final raw = text.length >= _isolateBytes ? await Isolate.run(() => jsonDecode(text)) : jsonDecode(text);
        if (raw is Map && raw['key'] == key) value = decode(raw['data']);
      } on Object {
        value = null;
      }
      final live = index.rows[key];
      if (value != null) {
        // LRU-touch only rows actually served; written with the next flush.
        if (identical(live, row)) {
          row.accessedAt = _clock();
          index.dirty = true;
          _scheduleFlush();
        }
      } else if (identical(live, row)) {
        // Corrupt / incompatible: delete it, or it would sit LRU-protected forever.
        await _locked(() async {
          await _delete(index, key);
          _scheduleFlush();
        });
      }
      return value;
    });
  }

  /// Upserts one row. [stillCurrent] is asked under the write lock, right
  /// before the write, and a false answer skips it: a caller persisting in the
  /// background passes its profile + invalidation check, so an edit that drops
  /// the entry meanwhile always wins — its remove either came first (this
  /// write is skipped) or queues behind this write on the same lock.
  Future<void> _write(
    String profileId,
    String kind,
    String id,
    bool Function()? stillCurrent,
    Map<String, Object?> Function() encode,
  ) async {
    final index = await _index();
    if (index == null) return;
    final key = _key(profileId, kind, id);
    String text;
    try {
      text = jsonEncode({'key': key, 'data': encode()});
    } on Object {
      return;
    }
    await _locked(() async {
      if (stillCurrent != null && !stillCurrent()) return;
      final now = _clock();
      final old = index.rows[key];
      final name = old?.file ?? _fileName(index, key);
      final bytes = utf8.encode(text).length;
      try {
        await _atomicWrite(File('${index.folder.path}/$name'), text);
      } on Object {
        return;
      }
      if (old != null) index.total -= old.bytes;
      index.rows[key] = _Row(
        key: key,
        profileId: profileId,
        kind: kind,
        entityId: id,
        file: name,
        bytes: bytes,
        cachedAt: now,
        accessedAt: now,
      );
      index.total += bytes;
      index.dirty = true;
      if (index.total > maxBytes) await _trim(index, keep: key);
      await _saveIndex(index);
    });
  }

  /// Once over [maxBytes], evicts the oldest-accessed rows until under
  /// [trimTargetBytes]. The row just written ([keep]) is never evicted by its
  /// own write, nor a kept row. Called under the lock.
  Future<void> _trim(_Index index, {required String keep}) async {
    final oldestFirst = [...index.rows.values]..sort((a, b) => a.accessedAt.compareTo(b.accessedAt));
    for (final row in oldestFirst) {
      if (index.total <= trimTargetBytes) break;
      if (row.key == keep) continue;
      if (await _keeps(row.profileId, row.kind, row.entityId)) continue;
      await _delete(index, row.key);
    }
  }

  Future<void> _delete(_Index index, String key) async {
    final row = index.rows.remove(key);
    if (row == null) return;
    index.total -= row.bytes;
    if (index.total < 0) index.total = 0;
    index.dirty = true;
    try {
      await File('${index.folder.path}/${row.file}').delete();
    } on Object {
      // already gone
    }
  }

  // ---------------------------------------------------------------- index

  Future<_Index?>? _loading;
  _Index? _loaded;
  Timer? _flushTimer;
  Future<void> _lock = Future.value();

  Future<T> _locked<T>(Future<T> Function() body) {
    final run = _lock.then((_) => body());
    _lock = run.then((_) {}, onError: (Object _) {});
    return run;
  }

  Future<_Index?> _index() => _loading ??= _loadIndex();

  Future<_Index?> _loadIndex() async {
    Directory? root;
    try {
      root = await _directory();
    } on Object {
      root = null;
    }
    if (root == null) return null;
    final folder = Directory('${root.path}/detail_cache');
    try {
      await folder.create(recursive: true);
    } on Object {
      return null;
    }
    final index = _Index(folder);
    _loaded = index;
    try {
      final file = File('${folder.path}/index.json');
      if (await file.exists()) {
        final raw = jsonDecode(await file.readAsString());
        if (raw is Map && raw['v'] == 1 && raw['rows'] is List) {
          for (final r in raw['rows'] as List) {
            final row = _Row.fromJson(r);
            if (row == null) continue;
            index.rows[row.key] = row;
            index.total += row.bytes;
          }
        }
      }
    } on Object {
      // An unreadable index: start empty; stray files are swept below.
      index.rows.clear();
      index.total = 0;
    }
    unawaited(_locked(() => _sweep(index)));
    // The hygiene pass, once per launch: as the cache first opens, off the start-up path.
    unawaited(purgeExpired());
    return index;
  }

  /// Deletes row files the index doesn't know (a crash between a file write and its index write).
  Future<void> _sweep(_Index index) async {
    try {
      final known = {for (final r in index.rows.values) r.file};
      await for (final entity in index.folder.list()) {
        if (entity is! File) continue;
        final name = entity.uri.pathSegments.last;
        if (name == 'index.json' || known.contains(name)) continue;
        await entity.delete();
      }
    } on Object {
      // best effort
    }
  }

  void _scheduleFlush() {
    if (!(_loaded?.dirty ?? false)) return;
    _flushTimer?.cancel();
    _flushTimer = Timer(const Duration(seconds: 2), () {
      _flushTimer = null;
      unawaited(flush());
    });
  }

  Future<void> _saveIndex(_Index index) async {
    index.dirty = false;
    final text = jsonEncode({
      'v': 1,
      'rows': [for (final r in index.rows.values) r.toJson()],
    });
    try {
      await _atomicWrite(File('${index.folder.path}/index.json'), text);
    } on Object {
      index.dirty = true;
    }
  }

  static Future<void> _atomicWrite(File file, String text) async {
    final temp = File('${file.path}.tmp');
    await temp.writeAsString(text, flush: true);
    await temp.rename(file.path);
  }

  static String _key(String profileId, String kind, String id) => '$profileId\u0000$kind\u0000$id';

  /// A file name for [key]: its FNV-1a hash, made unique within the index.
  static String _fileName(_Index index, String key) {
    var hash = 0xcbf29ce484222325;
    for (final unit in utf8.encode(key)) {
      hash ^= unit;
      hash *= 0x100000001b3; // wraps at 64 bits
    }
    final base = hash.toUnsigned(64).toRadixString(16).padLeft(16, '0');
    final taken = {for (final r in index.rows.values) r.file};
    var name = '$base.json';
    for (var n = 1; taken.contains(name); n++) {
      name = '$base-$n.json';
    }
    return name;
  }
}

final class _Index {
  _Index(this.folder);
  final Directory folder;
  final Map<String, _Row> rows = {};
  int total = 0;
  bool dirty = false;
}

final class _Row {
  _Row({
    required this.key,
    required this.profileId,
    required this.kind,
    required this.entityId,
    required this.file,
    required this.bytes,
    required this.cachedAt,
    required this.accessedAt,
  });

  final String key;
  final String profileId;
  final String kind;
  final String entityId;
  final String file;
  final int bytes;
  final int cachedAt;
  int accessedAt;

  Map<String, Object> toJson() => {
    'p': profileId,
    'k': kind,
    'i': entityId,
    'f': file,
    'b': bytes,
    'c': cachedAt,
    'a': accessedAt,
  };

  static _Row? fromJson(Object? json) {
    if (json is! Map) return null;
    final p = json['p'], k = json['k'], i = json['i'], f = json['f'];
    final b = json['b'], c = json['c'], a = json['a'];
    if (p is! String || k is! String || i is! String || f is! String) return null;
    if (b is! int || c is! int || a is! int) return null;
    return _Row(
      key: DetailCacheStore._key(p, k, i),
      profileId: p,
      kind: k,
      entityId: i,
      file: f,
      bytes: b,
      cachedAt: c,
      accessedAt: a,
    );
  }
}
