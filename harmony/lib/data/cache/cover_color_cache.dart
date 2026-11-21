/// Compact disk-backed LRU cache for extracted cover colours (backdrop base +
/// accent and theme seed), keyed by [coverCacheKey] so auth salt rotations
/// never invalidate cached colours.
///
/// Loaded once into memory at launch (before the first frame, bounded by a
/// short timeout) so Home cards, Memories and Detail pages paint in their true
/// cover colours on Frame 1 instead of flashing neutral fallback tones while
/// images decode and quantise. Writes are coalesced and atomic.
library;

import 'dart:async';
import 'dart:collection';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';

import 'cover_cache.dart';

/// Persisted colours for one cover. Either pair ([baseArgb]/[accentArgb]) or
/// [seedArgb] (or both) may be populated depending on which surface read the
/// cover first.
@immutable
final class StoredCoverColors {
  const StoredCoverColors({this.baseArgb, this.accentArgb, this.seedArgb});

  final int? baseArgb;
  final int? accentArgb;
  final int? seedArgb;

  bool get hasBackdrop => baseArgb != null && accentArgb != null;

  StoredCoverColors merge({int? baseArgb, int? accentArgb, int? seedArgb}) => StoredCoverColors(
    baseArgb: baseArgb ?? this.baseArgb,
    accentArgb: accentArgb ?? this.accentArgb,
    seedArgb: seedArgb ?? this.seedArgb,
  );

  List<int?> toJson() => [baseArgb, accentArgb, seedArgb];

  static StoredCoverColors? fromJson(Object? raw) {
    if (raw is! List || raw.length < 3) return null;
    int? asInt(Object? v) => v is int ? v : null;
    final b = asInt(raw[0]);
    final a = asInt(raw[1]);
    final s = asInt(raw[2]);
    if (b == null && a == null && s == null) return null;
    return StoredCoverColors(baseArgb: b, accentArgb: a, seedArgb: s);
  }
}

class CoverColorStore {
  CoverColorStore({
    Future<Directory?> Function()? directory,
    this.capacity = 512,
    this.flushDelay = const Duration(milliseconds: 1200),
  }) : _directory = directory ?? _noDirectory;

  static CoverColorStore instance = CoverColorStore();

  static const fileName = 'cover_colors_v1.json';

  final Future<Directory?> Function() _directory;
  final int capacity;
  final Duration flushDelay;

  final LinkedHashMap<String, StoredCoverColors> _entries = LinkedHashMap();
  File? _file;
  Future<void>? _loading;
  Timer? _flushTimer;
  bool _dirty = false;

  /// Installs and starts loading the process-wide cover colour store.
  static Future<void> install(Future<Directory?> Function() directory) {
    instance._flushTimer?.cancel();
    instance = CoverColorStore(directory: directory);
    return instance.load();
  }

  @visibleForTesting
  static void debugReset() {
    instance._flushTimer?.cancel();
    instance = CoverColorStore();
  }

  /// Loads cached colours from disk into memory (safe to call repeatedly).
  Future<void> load() => _loading ??= _load();

  Future<void> _load() async {
    try {
      final dir = await _directory();
      if (dir == null) return;
      final file = File('${dir.path}${Platform.pathSeparator}$fileName');
      _file = file;
      if (!await file.exists()) return;
      final raw = await file.readAsString();
      final decoded = jsonDecode(raw);
      if (decoded is! Map) return;
      for (final entry in decoded.entries) {
        if (entry.key is! String) continue;
        final colors = StoredCoverColors.fromJson(entry.value);
        if (colors == null) continue;
        // Preserve any fresher entry computed before load finished.
        final existing = _entries.remove(entry.key);
        _entries[entry.key as String] = existing == null
            ? colors
            : colors.merge(
                baseArgb: existing.baseArgb,
                accentArgb: existing.accentArgb,
                seedArgb: existing.seedArgb,
              );
      }
      _trim();
    } catch (_) {
      // Corrupt or unreadable cache file: start fresh cleanly.
    }
  }

  StoredCoverColors? get(String url) {
    final key = coverCacheKey(url);
    final hit = _entries.remove(key);
    if (hit == null) return null;
    _entries[key] = hit;
    return hit;
  }

  void putBackdrop(String url, int baseArgb, int accentArgb) {
    final key = coverCacheKey(url);
    final prev = _entries.remove(key);
    if (prev != null && prev.baseArgb == baseArgb && prev.accentArgb == accentArgb) {
      _entries[key] = prev;
      return;
    }
    _entries[key] = (prev ?? const StoredCoverColors()).merge(baseArgb: baseArgb, accentArgb: accentArgb);
    _trim();
    _scheduleFlush();
  }

  void putSeed(String url, int seedArgb) {
    final key = coverCacheKey(url);
    final prev = _entries.remove(key);
    if (prev != null && prev.seedArgb == seedArgb) {
      _entries[key] = prev;
      return;
    }
    _entries[key] = (prev ?? const StoredCoverColors()).merge(seedArgb: seedArgb);
    _trim();
    _scheduleFlush();
  }

  void _trim() {
    while (_entries.length > capacity) {
      _entries.remove(_entries.keys.first);
    }
  }

  void _scheduleFlush() {
    if (_file == null) return;
    _dirty = true;
    _flushTimer ??= Timer(flushDelay, () {
      _flushTimer = null;
      unawaited(flushNow());
    });
  }

  /// Writes pending colour updates atomically to disk.
  Future<void> flushNow() async {
    _flushTimer?.cancel();
    _flushTimer = null;
    final file = _file;
    if (!_dirty || file == null) return;
    _dirty = false;
    try {
      await file.parent.create(recursive: true);
      final payload = jsonEncode({for (final e in _entries.entries) e.key: e.value.toJson()});
      final tmp = File('${file.path}.tmp');
      await tmp.writeAsString(payload, flush: true);
      await tmp.rename(file.path);
    } catch (_) {
      // Cache persistence is best-effort.
    }
  }

  static Future<Directory?> _noDirectory() async => null;
}
