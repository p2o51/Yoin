/// Yoin's AI words on an album Memory, per account (Android data/local
/// MemoryCopyCache, `memory_copy_cache`): the AI title with the hash of the
/// writing it was made from, and the AI narration with its prompt key. One
/// JSON document per account on the key-value store, rows keyed by
/// `provider:rawId` (only albums have Memories here).
///
/// Both halves expire on their own keys: a new review re-titles the album
/// without touching the narration, and a new listening fact re-narrates it
/// without touching the title.
library;

import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';

import '../../platform/storage/stores.dart';
import '../model/model.dart';

/// Kotlin's String.hashCode (UTF-16 units, 31·h + c, 32-bit): the cache keys
/// Android writes (`titlePromptHash`, `narration-v2:<hash>`) come out the same.
int javaStringHash(String text) {
  var h = 0;
  for (final c in text.codeUnits) {
    h = (31 * h + c) & 0xFFFFFFFF;
  }
  return h >= 0x80000000 ? h - 0x100000000 : h;
}

/// One album's row (MemoryCopyCache).
@immutable
final class MemoryCopyRow {
  const MemoryCopyRow({this.copy = '', this.promptHash = '', this.generatedAt = 0, this.title, this.titlePromptHash});

  /// The narration in its cached form (encodeMemoryNarration); '' = none.
  final String copy;

  /// `narration-v<version>:<hash>` of what the narration was written from.
  final String promptHash;
  final int generatedAt;

  /// The AI title; null = none yet.
  final String? title;

  /// The hash of the writing the title was made from.
  final String? titlePromptHash;

  MemoryCopyRow copyWith({
    String? copy,
    String? promptHash,
    int? generatedAt,
    String? title,
    String? titlePromptHash,
  }) => MemoryCopyRow(
    copy: copy ?? this.copy,
    promptHash: promptHash ?? this.promptHash,
    generatedAt: generatedAt ?? this.generatedAt,
    title: title ?? this.title,
    titlePromptHash: titlePromptHash ?? this.titlePromptHash,
  );

  Map<String, Object?> toJson() => {'c': copy, 'p': promptHash, 'g': generatedAt, 't': title, 'th': titlePromptHash};

  static MemoryCopyRow? fromJson(Object? json) {
    if (json is! Map) return null;
    final g = json['g'];
    return MemoryCopyRow(
      copy: json['c'] is String ? json['c'] as String : '',
      promptHash: json['p'] is String ? json['p'] as String : '',
      generatedAt: g is num ? g.toInt() : 0,
      title: json['t'] is String ? json['t'] as String : null,
      titlePromptHash: json['th'] is String ? json['th'] as String : null,
    );
  }

  @override
  bool operator ==(Object other) =>
      other is MemoryCopyRow &&
      other.copy == copy &&
      other.promptHash == promptHash &&
      other.generatedAt == generatedAt &&
      other.title == title &&
      other.titlePromptHash == titlePromptHash;

  @override
  int get hashCode => Object.hash(copy, promptHash, generatedAt, title, titlePromptHash);
}

/// One account's cache. [shared] gives one instance per (store, account).
final class MemoryCopyCache {
  MemoryCopyCache(this._store, {required this.profileId});

  static final _shared = Expando<Map<String, MemoryCopyCache>>('MemoryCopyCache.shared');

  static MemoryCopyCache shared(KeyValueStore store, String profileId) =>
      (_shared[store] ??= {})[profileId] ??= MemoryCopyCache(store, profileId: profileId);

  static String keyFor(String profileId) => 'memory_copy_cache.v1.$profileId';

  final KeyValueStore _store;
  final String profileId;
  Future<Map<MediaId, MemoryCopyRow>>? _rows;
  Future<void> _tail = Future.value();
  final _changes = StreamController<MediaId>.broadcast();

  /// The album whose row was written.
  Stream<MediaId> get changes => _changes.stream;

  Future<MemoryCopyRow?> get(MediaId albumId) async {
    await _tail;
    return (await _load())[albumId];
  }

  /// The AI title only (Home, the album page): never a request.
  Future<String?> title(MediaId albumId) async {
    final t = (await get(albumId))?.title?.trim();
    return t == null || t.isEmpty ? null : t;
  }

  Future<void> put(MediaId albumId, MemoryCopyRow Function(MemoryCopyRow? current) edit) {
    final done = _tail.then((_) async {
      final rows = await _load();
      rows[albumId] = edit(rows[albumId]);
      await _store.write(
        keyFor(profileId),
        jsonEncode({for (final e in rows.entries) e.key.toString(): e.value.toJson()}),
      );
      if (!_changes.isClosed) _changes.add(albumId);
    });
    _tail = done.catchError((Object _) {});
    return done;
  }

  Future<Map<MediaId, MemoryCopyRow>> _load() => _rows ??= _read();

  Future<Map<MediaId, MemoryCopyRow>> _read() async {
    final rows = <MediaId, MemoryCopyRow>{};
    final raw = await _store.read(keyFor(profileId));
    if (raw == null || raw.isEmpty) return rows;
    try {
      final json = jsonDecode(raw);
      if (json is Map) {
        for (final e in json.entries) {
          final id = MediaId.tryParse(e.key as String);
          final row = MemoryCopyRow.fromJson(e.value);
          if (id != null && row != null) rows[id] = row;
        }
      }
    } on FormatException {
      // A damaged document reads as empty; the next write replaces it.
    }
    return rows;
  }
}
