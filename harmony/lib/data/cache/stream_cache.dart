/// The stream cache (Android OfflineDownloads.streamCache + CacheMetadata):
/// songs fill it as they play, 500 MB, the least recently played go first.
/// Downloads (lib/data/offline) are apart from it and nothing here evicts them.
///
/// Android gets this from Media3's SimpleCache under a CacheDataSource. Here
/// it is plain Dart: whole songs as files in the app's cache folder
/// (`media_cache/`) with an index (`index.json`) that records, per song and
/// provider, what Android's `cache_metadata` table holds. [StreamCacheProxy]
/// fills it and serves it to the player.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';

/// The song a cached file is (Android CacheMetadata's columns but the size and times).
@immutable
final class StreamCacheSong {
  const StreamCacheSong({required this.provider, required this.songId, this.title, this.artist, this.album});

  /// [MediaId.provider] of the song.
  final String provider;

  /// The song's [MediaId] string.
  final String songId;
  final String? title;
  final String? artist;
  final String? album;
}

/// One whole song in the cache (a row of Android's `cache_metadata`, keyed
/// by [key], which carries the provider).
@immutable
final class StreamCacheEntry {
  const StreamCacheEntry({
    required this.key,
    required this.provider,
    required this.songId,
    this.title,
    this.artist,
    this.album,
    this.contentType,
    required this.fileName,
    required this.fileSizeBytes,
    required this.cachedAt,
    required this.lastAccessedAt,
  });

  final String key;
  final String provider;
  final String songId;
  final String? title;
  final String? artist;
  final String? album;

  /// The server's Content-Type, served again with the file.
  final String? contentType;
  final String fileName;
  final int fileSizeBytes;
  final DateTime cachedAt;
  final DateTime lastAccessedAt;

  StreamCacheEntry touched(DateTime at) => StreamCacheEntry(
    key: key,
    provider: provider,
    songId: songId,
    title: title,
    artist: artist,
    album: album,
    contentType: contentType,
    fileName: fileName,
    fileSizeBytes: fileSizeBytes,
    cachedAt: cachedAt,
    lastAccessedAt: at,
  );

  Map<String, Object?> toJson() => {
    'key': key,
    'provider': provider,
    'songId': songId,
    'title': title,
    'artist': artist,
    'album': album,
    'contentType': contentType,
    'file': fileName,
    'size': fileSizeBytes,
    'cachedAt': cachedAt.millisecondsSinceEpoch,
    'accessedAt': lastAccessedAt.millisecondsSinceEpoch,
  };

  static StreamCacheEntry? fromJson(Object? json) {
    if (json is! Map) return null;
    final key = json['key'];
    final provider = json['provider'];
    final songId = json['songId'];
    final file = json['file'];
    final size = json['size'];
    if (key is! String || provider is! String || songId is! String || file is! String || size is! int) return null;
    DateTime at(Object? ms) => DateTime.fromMillisecondsSinceEpoch(ms is int ? ms : 0);
    String? text(Object? v) => v is String ? v : null;
    return StreamCacheEntry(
      key: key,
      provider: provider,
      songId: songId,
      title: text(json['title']),
      artist: text(json['artist']),
      album: text(json['album']),
      contentType: text(json['contentType']),
      fileName: file,
      fileSizeBytes: size,
      cachedAt: at(json['cachedAt']),
      lastAccessedAt: at(json['accessedAt']),
    );
  }
}

class StreamCache extends ChangeNotifier {
  StreamCache({
    required Future<Directory?> Function() directory,
    this.budgetBytes = defaultBudgetBytes,
    DateTime Function()? now,
  }) : _directory = directory,
       _now = now ?? DateTime.now;

  final Future<Directory?> Function() _directory;
  final DateTime Function() _now;

  /// Android STREAM_CACHE_BYTES.
  static const defaultBudgetBytes = 500 * 1024 * 1024;

  /// The folder under the app's cache folder (Android STREAM_CACHE_DIR).
  static const folderName = 'media_cache';
  static const indexName = 'index.json';
  static const indexVersion = 1;
  static const _partSuffix = '.part';

  /// The most the cached songs take together; the least recently played go first.
  final int budgetBytes;

  Directory? _dir;
  Future<void>? _starting;
  bool _disposed = false;
  final _entries = <String, StreamCacheEntry>{};
  int _generation = 0;
  int _partSeq = 0;
  Future<void> _saving = Future.value();

  /// Reads the index, dropping rows whose file went (the system may empty the
  /// cache folder) and files no row names. Safe to call again.
  Future<void> start() => _starting ??= _start();

  Future<void> _start() async {
    Directory? dir;
    try {
      final base = await _directory();
      if (base != null) {
        dir = Directory('${base.path}${Platform.pathSeparator}$folderName');
        await dir.create(recursive: true);
      }
    } catch (e) {
      debugPrint('YoinStreamCache: no folder: $e');
      dir = null;
    }
    if (_disposed || dir == null) return;
    _dir = dir;
    for (final entry in await _readIndex(dir)) {
      _entries[entry.key] = entry;
    }
    final named = {for (final e in _entries.values) e.fileName, indexName};
    try {
      await for (final entity in dir.list()) {
        if (entity is! File) continue;
        final name = _nameOf(entity);
        if (!named.contains(name)) await _delete(entity);
      }
    } catch (e) {
      debugPrint('YoinStreamCache: folder not swept: $e');
    }
    await _evict();
    _save();
    if (!_disposed) notifyListeners();
  }

  /// This build keeps a stream cache (it has a cache folder); known once started.
  bool get available => _dir != null;

  /// What the cached songs take, bytes (Android cacheSpace).
  int get sizeBytes => _entries.values.fold(0, (sum, e) => sum + e.fileSizeBytes);

  List<StreamCacheEntry> get entries => List.unmodifiable(_entries.values);

  /// Bumped by [clear]: a fetch that began before it never stores what it got.
  int get generation => _generation;

  /// The key a stream is cached under (Android mediaCacheKey + SubsonicCacheKey):
  /// the provider and the address without what signs it, so the same song at
  /// the same quality is one entry whatever salt signed it; a transcode keeps
  /// its own entry (maxBitRate and format stay in the address).
  static String keyOf(String provider, String url) {
    final uri = Uri.tryParse(url);
    if (uri == null || !uri.hasQuery) return '$provider|$url';
    final kept = [
      for (final part in uri.query.split('&'))
        if (part.isNotEmpty && !_unsigned.contains(Uri.decodeQueryComponent(part.split('=').first))) part,
    ];
    return '$provider|${uri.replace(query: kept.join('&')).toString()}';
  }

  /// What Subsonic adds after the user: the signature, the protocol, a
  /// password (legacy auth) and the length estimate (the same bytes either way).
  static const _unsigned = {'t', 's', 'p', 'v', 'c', 'f', 'estimateContentLength'};

  /// The whole song under [key], marked as just played; null when it isn't stored.
  Future<(File, StreamCacheEntry)?> open(String key) async {
    final dir = _dir;
    final entry = _entries[key];
    if (dir == null || entry == null) return null;
    final file = File(_pathIn(dir, entry.fileName));
    if (!await file.exists()) {
      _entries.remove(key);
      _save();
      if (!_disposed) notifyListeners();
      return null;
    }
    final touched = entry.touched(_now());
    _entries[key] = touched;
    _save();
    return (file, touched);
  }

  /// Whether [key] is stored (no disk read).
  bool contains(String key) => _entries.containsKey(key);

  /// A new part file a fetch of [key] writes to; null without a folder.
  File? newPartFile(String key) {
    final dir = _dir;
    if (dir == null) return null;
    return File(_pathIn(dir, '${_hash(key)}.${_partSeq++}$_partSuffix'));
  }

  /// Stores [part] (all of [song]) under [key], then lets the least recently
  /// played go until the songs fit the budget. Nothing is stored (and [part]
  /// is deleted) when [clear] ran since [generation] or the song alone is
  /// over the budget. Returns the new row.
  Future<StreamCacheEntry?> commit(
    String key,
    StreamCacheSong song,
    File part, {
    required int generation,
    String? contentType,
    String? suffix,
  }) async {
    final dir = _dir;
    if (dir == null || _disposed || generation != _generation) {
      await _delete(part);
      return null;
    }
    final int size;
    try {
      size = await part.length();
    } catch (_) {
      return null;
    }
    if (size <= 0 || size > budgetBytes) {
      await _delete(part);
      return null;
    }
    final name = '${_hash(key)}.${_safeSuffix(suffix)}';
    try {
      await part.rename(_pathIn(dir, name));
    } catch (e) {
      debugPrint('YoinStreamCache: not stored: $e');
      await _delete(part);
      return null;
    }
    // Cleared while the file moved into place: it goes too.
    if (generation != _generation) {
      await _delete(File(_pathIn(dir, name)));
      return null;
    }
    final now = _now();
    final entry = StreamCacheEntry(
      key: key,
      provider: song.provider,
      songId: song.songId,
      title: song.title,
      artist: song.artist,
      album: song.album,
      contentType: contentType,
      fileName: name,
      fileSizeBytes: size,
      cachedAt: now,
      lastAccessedAt: now,
    );
    _entries[key] = entry;
    await _evict(keep: key);
    _save();
    if (!_disposed) notifyListeners();
    return entry;
  }

  /// Empties the cache (Android clearStreamCache). Downloads stay. A song
  /// still fetching plays on but isn't stored.
  Future<void> clear() async {
    await start();
    _generation++;
    final dir = _dir;
    final gone = _entries.values.toList();
    _entries.clear();
    if (!_disposed) notifyListeners();
    if (dir == null) return;
    for (final e in gone) {
      await _delete(File(_pathIn(dir, e.fileName)));
    }
    _save();
  }

  /// Deletes a part file a fetch gave up on.
  Future<void> discard(File part) => _delete(part);

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }

  /// The index's pending writes, for tests.
  @visibleForTesting
  Future<void> get saved => _saving;

  // ── Internals ─────────────────────────────────────────────────────

  Future<void> _evict({String? keep}) async {
    final dir = _dir;
    if (dir == null) return;
    var size = sizeBytes;
    if (size <= budgetBytes) return;
    final oldest = _entries.values.where((e) => e.key != keep).toList()
      ..sort((a, b) => a.lastAccessedAt.compareTo(b.lastAccessedAt));
    for (final e in oldest) {
      if (size <= budgetBytes) break;
      _entries.remove(e.key);
      size -= e.fileSizeBytes;
      await _delete(File(_pathIn(dir, e.fileName)));
    }
  }

  static String _hash(String key) => sha1.convert(utf8.encode(key)).toString();

  static String _safeSuffix(String? suffix) {
    final s = suffix?.toLowerCase();
    return s != null && RegExp(r'^[a-z0-9]{1,8}$').hasMatch(s) ? s : 'audio';
  }

  static String _pathIn(Directory dir, String name) => '${dir.path}${Platform.pathSeparator}$name';

  static String _nameOf(FileSystemEntity entity) =>
      entity.uri.pathSegments.lastWhere((s) => s.isNotEmpty, orElse: () => '');

  static Future<void> _delete(File file) async {
    try {
      if (await file.exists()) await file.delete();
    } catch (_) {
      // Already gone, or held open: the next start sweeps it.
    }
  }

  Future<List<StreamCacheEntry>> _readIndex(Directory dir) async {
    final file = File(_pathIn(dir, indexName));
    try {
      if (!await file.exists()) return [];
      final raw = jsonDecode(await file.readAsString());
      if (raw is! Map || raw['entries'] is! List) return [];
      final out = <StreamCacheEntry>[];
      for (final item in raw['entries'] as List) {
        final entry = StreamCacheEntry.fromJson(item);
        if (entry == null) continue;
        if (await File(_pathIn(dir, entry.fileName)).exists()) out.add(entry);
      }
      return out;
    } catch (e) {
      debugPrint('YoinStreamCache: index unreadable: $e');
      return [];
    }
  }

  /// Writes the index, one write at a time, replacing the file whole.
  void _save() {
    final dir = _dir;
    if (dir == null || _disposed) return;
    final body = jsonEncode({
      'version': indexVersion,
      'entries': [for (final e in _entries.values) e.toJson()],
    });
    _saving = _saving.then((_) async {
      try {
        final tmp = File(_pathIn(dir, '$indexName.tmp'));
        await tmp.writeAsString(body, flush: true);
        await tmp.rename(_pathIn(dir, indexName));
      } catch (e) {
        debugPrint('YoinStreamCache: index not saved: $e');
      }
    });
  }
}
