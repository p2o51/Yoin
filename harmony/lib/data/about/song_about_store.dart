/// A song's About (Android data/local SongAboutEntry + SongAboutEntryDao):
/// the AI's canonical fields (Creation Time / Location / Lyricist / Composer /
/// Producer / the review paragraph), the questions asked about the song with
/// their answers, and the listener's own entries.
///
/// Rows are keyed by the normalised title + artist + album, never by account
/// or provider: the same song played from another account sees the same
/// rows, exactly as on Android. Android keeps a Room table; here the whole
/// table is one JSON document on the key-value store ([SongAboutStore.key]).
library;

import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';

import '../../platform/storage/stores.dart';
import '../ai/ai_service.dart' show AboutFields;

/// What a row is (SongAboutEntry.KIND_*).
abstract final class SongAboutKinds {
  /// One of the AI's [AboutFields].
  static const String canonical = 'canonical';

  /// A question asked about the song, and the AI's answer.
  static const String ask = 'ask';

  /// The listener's own entry (written on the album page's scrapbook): never the AI's.
  static const String own = 'own';
}

final RegExp _featSuffix = RegExp(r'\s*\(feat\.[^)]*\)', caseSensitive: false);
final RegExp _whitespace = RegExp(r'\s+');

/// Cheap identity normalisation so "Fake Love" / "fake love" / "Fake Love
/// (feat. X)" collapse to one key (SongAboutEntry.normalize).
String normalizeAboutKey(String text) =>
    text.trim().toLowerCase().replaceAll(_featSuffix, '').replaceAll(_whitespace, ' ').trim();

/// The primary key of one row (Android AlbumScrapbookAboutRef).
@immutable
final class AboutRef {
  const AboutRef({
    required this.titleKey,
    required this.artistKey,
    required this.albumKey,
    required this.kind,
    required this.entryKey,
  });

  final String titleKey;
  final String artistKey;
  final String albumKey;
  final String kind;
  final String entryKey;

  @override
  bool operator ==(Object other) =>
      other is AboutRef &&
      other.titleKey == titleKey &&
      other.artistKey == artistKey &&
      other.albumKey == albumKey &&
      other.kind == kind &&
      other.entryKey == entryKey;

  @override
  int get hashCode => Object.hash(titleKey, artistKey, albumKey, kind, entryKey);

  @override
  String toString() => 'AboutRef($titleKey|$artistKey|$albumKey|$kind|$entryKey)';
}

/// One row (SongAboutEntry). Canonical rows: [entryKey] is one of
/// [AboutFields], [promptText] and [titleText] null. Ask rows: [entryKey] is
/// the normalised question, [promptText] the question as asked, [titleText]
/// the AI's short heading. Own rows: [titleText] is the listener's heading,
/// [entryKey] its normalised form when first written. A blank [answerText]
/// is a row removed on the scrapbook (kept so the removal syncs).
@immutable
final class SongAboutEntry {
  const SongAboutEntry({
    required this.titleKey,
    required this.artistKey,
    required this.albumKey,
    required this.titleDisplay,
    required this.artistDisplay,
    required this.albumDisplay,
    required this.kind,
    required this.entryKey,
    this.promptText,
    this.titleText,
    required this.answerText,
    required this.createdAt,
    required this.updatedAt,
  });

  final String titleKey;
  final String artistKey;
  final String albumKey;
  final String titleDisplay;
  final String artistDisplay;
  final String albumDisplay;
  final String kind;
  final String entryKey;
  final String? promptText;
  final String? titleText;
  final String answerText;
  final int createdAt;
  final int updatedAt;

  AboutRef get ref =>
      AboutRef(titleKey: titleKey, artistKey: artistKey, albumKey: albumKey, kind: kind, entryKey: entryKey);

  bool get isCanonical => kind == SongAboutKinds.canonical;
  bool get isOwn => kind == SongAboutKinds.own;

  SongAboutEntry copyWith({String? titleText, bool clearTitle = false, String? answerText, int? updatedAt}) =>
      SongAboutEntry(
        titleKey: titleKey,
        artistKey: artistKey,
        albumKey: albumKey,
        titleDisplay: titleDisplay,
        artistDisplay: artistDisplay,
        albumDisplay: albumDisplay,
        kind: kind,
        entryKey: entryKey,
        promptText: promptText,
        titleText: clearTitle ? null : (titleText ?? this.titleText),
        answerText: answerText ?? this.answerText,
        createdAt: createdAt,
        updatedAt: updatedAt ?? this.updatedAt,
      );

  Map<String, Object?> toJson() => {
    'tk': titleKey,
    'ak': artistKey,
    'lk': albumKey,
    'td': titleDisplay,
    'ad': artistDisplay,
    'ld': albumDisplay,
    'k': kind,
    'e': entryKey,
    'p': promptText,
    't': titleText,
    'a': answerText,
    'c': createdAt,
    'u': updatedAt,
  };

  static SongAboutEntry? fromJson(Object? json) {
    if (json is! Map) return null;
    String? s(String k) => json[k] is String ? json[k] as String : null;
    int? n(String k) => json[k] is num ? (json[k] as num).toInt() : null;
    final tk = s('tk'), ak = s('ak'), lk = s('lk'), k = s('k'), e = s('e'), a = s('a');
    final c = n('c'), u = n('u');
    if (tk == null || ak == null || lk == null || k == null || e == null || a == null || c == null || u == null) {
      return null;
    }
    return SongAboutEntry(
      titleKey: tk,
      artistKey: ak,
      albumKey: lk,
      titleDisplay: s('td') ?? '',
      artistDisplay: s('ad') ?? '',
      albumDisplay: s('ld') ?? '',
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
      other is SongAboutEntry &&
      other.ref == ref &&
      other.titleDisplay == titleDisplay &&
      other.artistDisplay == artistDisplay &&
      other.albumDisplay == albumDisplay &&
      other.promptText == promptText &&
      other.titleText == titleText &&
      other.answerText == answerText &&
      other.createdAt == createdAt &&
      other.updatedAt == updatedAt;

  @override
  int get hashCode => Object.hash(ref, promptText, titleText, answerText, createdAt, updatedAt);

  @override
  String toString() => 'SongAboutEntry($kind/$entryKey: $answerText)';
}

/// The About table (SongAboutEntryDao). One instance per key-value store
/// ([shared]), so the screens and cloud sync read and write through one
/// cache and one queue; [changes] fires after every write.
final class SongAboutStore {
  SongAboutStore(this._store);

  static final _shared = Expando<SongAboutStore>('SongAboutStore.shared');

  /// The store on [store], the same instance every time.
  static SongAboutStore shared(KeyValueStore store) => _shared[store] ??= SongAboutStore(store);

  /// The document's key on the key-value store.
  static const String key = 'song_about.v1';

  final KeyValueStore _store;
  Future<Map<AboutRef, SongAboutEntry>>? _rows;
  Future<void> _tail = Future.value();
  final _changes = StreamController<void>.broadcast();

  /// After every write.
  Stream<void> get changes => _changes.stream;

  // ---- reads ----

  /// Every row, removed (blank) ones included.
  Future<List<SongAboutEntry>> all() async {
    await _tail;
    return List.unmodifiable((await _load()).values);
  }

  /// One song's rows (SongAboutEntryDao.observe): canonical first, then by updatedAt, newest first.
  Future<List<SongAboutEntry>> forSong(String titleKey, String artistKey, String albumKey) async {
    final rows = [
      for (final r in await all())
        if (r.titleKey == titleKey && r.artistKey == artistKey && r.albumKey == albumKey) r,
    ];
    rows.sort((a, b) {
      final c = (a.isCanonical ? 0 : 1).compareTo(b.isCanonical ? 0 : 1);
      return c != 0 ? c : b.updatedAt.compareTo(a.updatedAt);
    });
    return rows;
  }

  /// Every row filed under one album key, newest first (observeByAlbum).
  Future<List<SongAboutEntry>> byAlbum(String albumKey) async {
    final rows = [
      for (final r in await all())
        if (r.albumKey == albumKey) r,
    ]..sort((a, b) => b.updatedAt.compareTo(a.updatedAt));
    return rows;
  }

  Future<SongAboutEntry?> get(AboutRef ref) async {
    await _tail;
    return (await _load())[ref];
  }

  // ---- writes ----

  Future<void> upsert(SongAboutEntry row) => upsertAll([row]);

  Future<void> upsertAll(List<SongAboutEntry> rows) => _write((map) {
    for (final r in rows) {
      map[r.ref] = r;
    }
  });

  /// Everything the AI wrote (a language switch makes it stale); the listener's own entries stay.
  Future<void> deleteGenerated() => _write((map) => map.removeWhere((_, r) => !r.isOwn));

  Future<void> deleteAll() => _write((map) => map.clear());

  /// Reads and edits the rows in one queued step (cloud sync's apply): [body]
  /// returns its result and whether it changed anything; only a change is written.
  Future<R> edit<R>((R, bool) Function(Map<AboutRef, SongAboutEntry> rows) body) {
    final done = _tail.then((_) async {
      final rows = await _load();
      final (result, changed) = body(rows);
      if (changed) {
        await _store.write(key, jsonEncode([for (final r in rows.values) r.toJson()]));
        if (!_changes.isClosed) _changes.add(null);
      }
      return result;
    });
    _tail = done.then((_) {}, onError: (Object _) {});
    return done;
  }

  // ---- internals ----

  Future<void> _write(void Function(Map<AboutRef, SongAboutEntry> rows) edit) {
    final done = _tail.then((_) async {
      final rows = await _load();
      edit(rows);
      await _store.write(key, jsonEncode([for (final r in rows.values) r.toJson()]));
      if (!_changes.isClosed) _changes.add(null);
    });
    // A failed write fails its caller, never the writes queued after it.
    _tail = done.catchError((Object _) {});
    return done;
  }

  /// Read once; every caller shares the one map (a second read never replaces a map a write has edited).
  Future<Map<AboutRef, SongAboutEntry>> _load() => _rows ??= _read();

  Future<Map<AboutRef, SongAboutEntry>> _read() async {
    final rows = <AboutRef, SongAboutEntry>{};
    final raw = await _store.read(key);
    if (raw != null && raw.isNotEmpty) {
      try {
        final list = jsonDecode(raw);
        if (list is List) {
          for (final item in list) {
            final row = SongAboutEntry.fromJson(item);
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

/// The canonical fields' display order (SongAboutEntry.CANONICAL_ORDER).
const List<String> aboutCanonicalOrder = AboutFields.order;
