/// An album's About (Android data/local AlbumAboutEntry + AlbumAboutEntryDao):
/// the AI's credits and editorial paragraph for one album (two canonical rows,
/// [YoinAlbumAbout.canonCredits] / [YoinAlbumAbout.canonBlurb]) and the
/// questions asked about it with their answers ([YoinAlbumAbout.kindAsk]).
///
/// Rows are keyed by the album's identity on its service (provider + raw id),
/// not by normalised metadata like the song rows: an album's credits belong to
/// one release (a deluxe edition, a reissue). Global, like the song rows: it is
/// reference material, not the listener's own data. Android keeps a Room table
/// (v37); here the whole table is one JSON document on the key-value store
/// ([AlbumAboutStore.key]).
library;

import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';

import '../../platform/storage/stores.dart';
import '../albumabout/album_about_models.dart';
import '../albumabout/yoin_album_about.dart';

/// The primary key of one row.
@immutable
final class AlbumAboutRef {
  const AlbumAboutRef({required this.provider, required this.albumId, required this.kind, required this.entryKey});

  final String provider;
  final String albumId;
  final String kind;
  final String entryKey;

  @override
  bool operator ==(Object other) =>
      other is AlbumAboutRef &&
      other.provider == provider &&
      other.albumId == albumId &&
      other.kind == kind &&
      other.entryKey == entryKey;

  @override
  int get hashCode => Object.hash(provider, albumId, kind, entryKey);

  @override
  String toString() => 'AlbumAboutRef($provider|$albumId|$kind|$entryKey)';
}

/// One row (AlbumAboutEntry). Canonical rows: [entryKey] is
/// [YoinAlbumAbout.canonCredits] (the credits as compact JSON,
/// [AlbumAiCredits.encode]) or [YoinAlbumAbout.canonBlurb] (the paragraph,
/// blank when the AI knew too little). Ask rows: [entryKey] is the question's
/// key ([YoinAlbumAbout.questionKey]), [promptText] the question as asked,
/// [titleText] the AI's short heading.
@immutable
final class AlbumAboutEntry {
  const AlbumAboutEntry({
    required this.provider,
    required this.albumId,
    required this.albumDisplay,
    required this.artistDisplay,
    required this.kind,
    required this.entryKey,
    this.promptText,
    this.titleText,
    required this.answerText,
    required this.createdAt,
    required this.updatedAt,
  });

  final String provider;
  final String albumId;
  final String albumDisplay;
  final String artistDisplay;
  final String kind;
  final String entryKey;
  final String? promptText;
  final String? titleText;
  final String answerText;
  final int createdAt;
  final int updatedAt;

  AlbumAboutRef get ref => AlbumAboutRef(provider: provider, albumId: albumId, kind: kind, entryKey: entryKey);

  bool get isCanonical => kind == YoinAlbumAbout.kindCanonical;

  /// As the shared read model takes it ([YoinAlbumAbout.content]).
  AlbumAboutRow get row => AlbumAboutRow(
    kind: kind,
    entryKey: entryKey,
    promptText: promptText,
    titleText: titleText,
    answerText: answerText,
    createdAt: createdAt,
  );

  AlbumAboutEntry copyWith({int? updatedAt}) => AlbumAboutEntry(
    provider: provider,
    albumId: albumId,
    albumDisplay: albumDisplay,
    artistDisplay: artistDisplay,
    kind: kind,
    entryKey: entryKey,
    promptText: promptText,
    titleText: titleText,
    answerText: answerText,
    createdAt: createdAt,
    updatedAt: updatedAt ?? this.updatedAt,
  );

  Map<String, Object?> toJson() => {
    'pv': provider,
    'id': albumId,
    'ld': albumDisplay,
    'ad': artistDisplay,
    'k': kind,
    'e': entryKey,
    'p': promptText,
    't': titleText,
    'a': answerText,
    'c': createdAt,
    'u': updatedAt,
  };

  static AlbumAboutEntry? fromJson(Object? json) {
    if (json is! Map) return null;
    String? s(String k) => json[k] is String ? json[k] as String : null;
    int? n(String k) => json[k] is num ? (json[k] as num).toInt() : null;
    final pv = s('pv'), id = s('id'), k = s('k'), e = s('e'), a = s('a');
    final c = n('c'), u = n('u');
    if (pv == null || id == null || k == null || e == null || a == null || c == null || u == null) return null;
    return AlbumAboutEntry(
      provider: pv,
      albumId: id,
      albumDisplay: s('ld') ?? '',
      artistDisplay: s('ad') ?? '',
      kind: k,
      entryKey: e,
      promptText: s('p'),
      titleText: s('t'),
      answerText: a,
      createdAt: c,
      updatedAt: u,
    );
  }

  @override
  bool operator ==(Object other) =>
      other is AlbumAboutEntry &&
      other.ref == ref &&
      other.albumDisplay == albumDisplay &&
      other.artistDisplay == artistDisplay &&
      other.promptText == promptText &&
      other.titleText == titleText &&
      other.answerText == answerText &&
      other.createdAt == createdAt &&
      other.updatedAt == updatedAt;

  @override
  int get hashCode => Object.hash(ref, promptText, titleText, answerText, createdAt, updatedAt);

  @override
  String toString() => 'AlbumAboutEntry($provider:$albumId $kind/$entryKey: $answerText)';
}

/// The album About table (AlbumAboutEntryDao). One instance per key-value
/// store ([shared]), so the scrapbook and cloud sync read and write through
/// one cache and one queue; [changes] fires after every write.
final class AlbumAboutStore {
  AlbumAboutStore(this._store);

  static final _shared = Expando<AlbumAboutStore>('AlbumAboutStore.shared');

  /// The store on [store], the same instance every time.
  static AlbumAboutStore shared(KeyValueStore store) => _shared[store] ??= AlbumAboutStore(store);

  /// The document's key on the key-value store.
  static const String key = 'album_about.v1';

  final KeyValueStore _store;
  Future<Map<AlbumAboutRef, AlbumAboutEntry>>? _rows;
  Future<void> _tail = Future.value();
  final _changes = StreamController<void>.broadcast();

  /// After every write.
  Stream<void> get changes => _changes.stream;

  // ---- reads ----

  /// Every row.
  Future<List<AlbumAboutEntry>> all() async {
    await _tail;
    return List.unmodifiable((await _load()).values);
  }

  /// One album's rows (AlbumAboutEntryDao.observe): canonical first, then the questions newest first.
  Future<List<AlbumAboutEntry>> forAlbum(String provider, String albumId) async {
    final rows = [
      for (final r in await all())
        if (r.provider == provider && r.albumId == albumId) r,
    ];
    rows.sort((a, b) {
      final c = (a.isCanonical ? 0 : 1).compareTo(b.isCanonical ? 0 : 1);
      return c != 0 ? c : b.createdAt.compareTo(a.createdAt);
    });
    return rows;
  }

  /// One album's canonical rows (getCanonical).
  Future<List<AlbumAboutEntry>> canonical(String provider, String albumId) async => [
    for (final r in await forAlbum(provider, albumId))
      if (r.isCanonical) r,
  ];

  Future<AlbumAboutEntry?> get(AlbumAboutRef ref) async {
    await _tail;
    return (await _load())[ref];
  }

  // ---- writes ----

  Future<void> upsert(AlbumAboutEntry row) => upsertAll([row]);

  Future<void> upsertAll(List<AlbumAboutEntry> rows) => _write((map) {
    for (final r in rows) {
      map[r.ref] = r;
    }
  });

  /// Every row (AlbumAboutEntryDao.deleteAll): all of it is the AI's, so a language switch clears it.
  Future<void> deleteAll() => _write((map) => map.clear());

  /// Reads and edits the rows in one queued step (cloud sync's apply): [body]
  /// returns its result and whether it changed anything; only a change is written.
  Future<R> edit<R>((R, bool) Function(Map<AlbumAboutRef, AlbumAboutEntry> rows) body) {
    final done = _tail.then((_) async {
      final rows = await _load();
      final (result, changed) = body(rows);
      if (changed) await _persist(rows);
      return result;
    });
    _tail = done.then((_) {}, onError: (Object _) {});
    return done;
  }

  // ---- internals ----

  Future<void> _write(void Function(Map<AlbumAboutRef, AlbumAboutEntry> rows) edit) {
    final done = _tail.then((_) async {
      final rows = await _load();
      edit(rows);
      await _persist(rows);
    });
    // A failed write fails its caller, never the writes queued after it.
    _tail = done.catchError((Object _) {});
    return done;
  }

  Future<void> _persist(Map<AlbumAboutRef, AlbumAboutEntry> rows) async {
    await _store.write(key, jsonEncode([for (final r in rows.values) r.toJson()]));
    if (!_changes.isClosed) _changes.add(null);
  }

  /// Read once; every caller shares the one map (a second read never replaces a map a write has edited).
  Future<Map<AlbumAboutRef, AlbumAboutEntry>> _load() => _rows ??= _read();

  Future<Map<AlbumAboutRef, AlbumAboutEntry>> _read() async {
    final rows = <AlbumAboutRef, AlbumAboutEntry>{};
    final raw = await _store.read(key);
    if (raw != null && raw.isNotEmpty) {
      try {
        final list = jsonDecode(raw);
        if (list is List) {
          for (final item in list) {
            final row = AlbumAboutEntry.fromJson(item);
            if (row != null) rows[row.ref] = row;
          }
        }
      } on FormatException {
        // A damaged document reads as empty; the next write replaces it.
      }
    }
    return rows;
  }
}
