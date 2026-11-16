import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';

import '../../platform/storage/stores.dart';

/// One song's cached lyrics (Android `lyrics_cache` row). [songKey] is the
/// track's [MediaId] string (provider included); [storedSongId] is the
/// provider's song id as stored — a user-chosen row that isn't typed carries
/// [LyricsCachePolicy.userChosenSongIdPrefix] in front.
@immutable
final class LyricsCacheEntry {
  const LyricsCacheEntry({
    required this.songKey,
    required this.lyricsProvider,
    required this.storedSongId,
    required this.lrc,
    required this.cachedAt,
  });

  final String songKey;
  final String lyricsProvider;
  final String? storedSongId;
  final String lrc;

  /// When it was written; for a user-chosen row, when it was last used.
  final int cachedAt;

  bool get userChosen => LyricsCachePolicy.isUserChosen(lyricsProvider, storedSongId);

  /// The provider's own song id, without the user-chosen mark.
  String? get providerSongId => LyricsCachePolicy.providerSongId(storedSongId);

  Map<String, Object?> toJson() => {'lrc': lrc, 'p': lyricsProvider, 'id': storedSongId, 'at': cachedAt};

  static LyricsCacheEntry? fromJson(String songKey, Object? json) {
    if (json is! Map || json['lrc'] is! String || json['p'] is! String || json['at'] is! int) return null;
    return LyricsCacheEntry(
      songKey: songKey,
      lyricsProvider: json['p'] as String,
      storedSongId: json['id'] is String ? json['id'] as String : null,
      lrc: json['lrc'] as String,
      cachedAt: json['at'] as int,
    );
  }

  LyricsCacheEntry copyWith({int? cachedAt}) => LyricsCacheEntry(
    songKey: songKey,
    lyricsProvider: lyricsProvider,
    storedSongId: storedSongId,
    lrc: lrc,
    cachedAt: cachedAt ?? this.cachedAt,
  );
}

/// One song in the size budget (LyricsCachePolicy.Footprint): its lyrics
/// row's bytes plus its free provider translations'. [lyricsCachedAt] is the
/// row's `cachedAt` (eviction checks it didn't change); null for an orphan
/// translation with no row. [typed] rows are never evicted.
@immutable
final class LyricsFootprint {
  const LyricsFootprint({
    required this.songKey,
    required this.userChosen,
    required this.lastUsedAt,
    required this.bytes,
    required this.lyricsCachedAt,
    this.typed = false,
  });

  final String songKey;
  final bool userChosen;
  final int lastUsedAt;
  final int bytes;
  final int? lyricsCachedAt;
  final bool typed;

  @override
  String toString() => 'LyricsFootprint($songKey, $bytes B, used $lastUsedAt${userChosen ? ', chosen' : ''})';
}

/// How long cached lyrics live (Android LyricsCachePolicy.kt; owner
/// 2026-10-05: lyrics the user chose never expire by age, the whole cache is
/// capped by size).
///
/// Two kinds of row, told apart without a schema:
/// - **automatic**: picked by the fallback chain (QQ → NetEase → LRCLIB).
///   Re-matched after [automaticTtlMs], so a better source or a matcher fix
///   can still replace it.
/// - **user-chosen**: typed / pasted ([manualProvider]), applied from the
///   lyrics search, or an accepted switch to a source with a translation.
///   Never expire; win over automatic lyrics and the Subsonic server's on
///   load; `cachedAt` means "last used" for them, refreshed on read (at most
///   every [recencyRefreshMs]).
///
/// Budget: per song, the row's `lrc` in UTF-8 plus that song's free
/// `provider:*` translations (they go with the row). Over [budgetBytes] the
/// cache is trimmed in [planEviction]'s order to [trimTargetBytes]. Paid
/// Gemini translations are neither counted nor evicted (redoing them costs
/// money). Typed rows count but are never evicted — no source can bring them back.
abstract final class LyricsCachePolicy {
  /// Typed or pasted lyrics' provider. Never expires, never overwritten by sync.
  static const String manualProvider = 'manual';

  /// The mark on a user-chosen row's song id (no provider's ids start with it).
  static const String userChosenSongIdPrefix = 'user|';

  /// Automatic rows: 30 days.
  static const int automaticTtlMs = 30 * 24 * 60 * 60 * 1000;

  /// A user-chosen row's "last used" is written at most once a day.
  static const int recencyRefreshMs = 24 * 60 * 60 * 1000;

  /// The whole lyrics cache: 10 MiB — about 2,000 songs at 2–6 KB with their translations.
  static const int budgetBytes = 10 * 1024 * 1024;

  /// Once over budget, trimmed to here (1 MiB to spare, so not every write trims).
  static const int trimTargetBytes = 9 * 1024 * 1024;

  /// Free, re-fetchable provider translations' model prefix in the translation cache.
  static const String providerTranslationModelPrefix = 'provider:';

  static bool isUserChosen(String lyricsProvider, String? storedSongId) =>
      isTyped(lyricsProvider) || (storedSongId?.startsWith(userChosenSongIdPrefix) ?? false);

  /// Typed / pasted lyrics: nothing can fetch them again, so the budget never evicts them.
  static bool isTyped(String lyricsProvider) => lyricsProvider == manualProvider;

  /// The provider's own song id without the user-chosen mark; empty is null.
  static String? providerSongId(String? stored) {
    if (stored == null) return null;
    if (!stored.startsWith(userChosenSongIdPrefix)) return stored;
    final id = stored.substring(userChosenSongIdPrefix.length);
    return id.isEmpty ? null : id;
  }

  /// Shown as it is: a user-chosen row at any age, an automatic one within the TTL.
  static bool isUsable(LyricsCacheEntry entry, int now) => entry.userChosen || entry.cachedAt >= now - automaticTtlMs;

  /// Whether reading a user-chosen row should move its `cachedAt` to now. An
  /// automatic row's `cachedAt` starts its TTL and is never touched.
  static bool needsRecencyRefresh(LyricsCacheEntry entry, int now) =>
      entry.userChosen && now - entry.cachedAt >= recencyRefreshMs;

  /// A row; a [userChosen] non-manual one gets the mark on its song id.
  static LyricsCacheEntry entry({
    required String songKey,
    required String lyricsProvider,
    required String? lyricsProviderSongId,
    required String lrc,
    required int cachedAt,
    required bool userChosen,
  }) => LyricsCacheEntry(
    songKey: songKey,
    lyricsProvider: lyricsProvider,
    storedSongId: userChosen && lyricsProvider != manualProvider
        ? '$userChosenSongIdPrefix${lyricsProviderSongId ?? ''}'
        : lyricsProviderSongId,
    lrc: lrc,
    cachedAt: cachedAt,
  );

  static int utf8Bytes(String text) => utf8.encode(text).length;

  /// Over [budgetBytes]: the songs to evict, in order, until at most
  /// [targetBytes]: automatic rows (and orphan translations) oldest first,
  /// then user-chosen rows least recently used first. Typed rows and
  /// [protectedSong] (just written, even when it alone is over budget) are
  /// never evicted; if everything else goes and it's still over, so be it.
  static List<LyricsFootprint> planEviction(
    List<LyricsFootprint> footprints, {
    int budgetBytes = budgetBytes,
    int targetBytes = trimTargetBytes,
    String? protectedSong,
  }) {
    var remaining = footprints.fold<int>(0, (sum, f) => sum + f.bytes);
    if (remaining <= budgetBytes) return const [];
    final ordered = [...footprints]..sort(_evictionOrder);
    final victims = <LyricsFootprint>[];
    for (final candidate in ordered) {
      if (remaining <= targetBytes) break;
      if (candidate.typed) continue;
      if (protectedSong != null && candidate.songKey == protectedSong) continue;
      victims.add(candidate);
      remaining -= candidate.bytes;
    }
    return victims;
  }

  static int _evictionOrder(LyricsFootprint a, LyricsFootprint b) {
    if (a.userChosen != b.userChosen) return a.userChosen ? 1 : -1;
    final byUse = a.lastUsedAt.compareTo(b.lastUsedAt);
    if (byUse != 0) return byUse;
    return a.songKey.compareTo(b.songKey);
  }
}

/// The lyrics cache and the translation cache in the app's cache store
/// (Android `lyrics_cache` + `lyrics_translation_cache`), kept apart from the
/// settings ([legacy]) so 10 MiB of lyrics never ride along with a settings
/// write. The store can't list its keys, so an index (one JSON value) keeps
/// what the budget needs: each song's row size and age and its free
/// translations' keys and sizes.
class LyricsCacheStore {
  LyricsCacheStore(this._store, {KeyValueStore? legacy}) : _legacy = legacy ?? _store;

  final KeyValueStore _store;

  /// Where Harmony kept the user's picks before the cache (the settings store).
  final KeyValueStore _legacy;

  static const String _indexKey = 'yoin.lyrics.cache.index';
  static String rowKey(String songKey) => 'yoin.lyrics.cache.$songKey';

  /// What Harmony stored before the cache (the user's own picks only).
  static String legacyUserKey(String songKey) => 'yoin.lyrics.user.$songKey';

  static String translationKey(String songKey, String model, String targetLanguage) =>
      'yoin.lyrics.translation|$model|$targetLanguage|$songKey';

  Map<String, _IndexEntry>? _index;
  Future<void> _tail = Future.value();

  /// Runs [body] after every earlier call (the index is read-modify-written).
  Future<T> _locked<T>(Future<T> Function() body) {
    final run = _tail.then((_) => body());
    _tail = run.then((_) {}, onError: (Object _) {});
    return run;
  }

  Future<Map<String, _IndexEntry>> _loadIndex() async {
    final loaded = _index;
    if (loaded != null) return loaded;
    final map = <String, _IndexEntry>{};
    try {
      final raw = await _store.read(_indexKey);
      final json = raw == null ? null : jsonDecode(raw);
      if (json is Map) {
        json.forEach((key, value) {
          final entry = _IndexEntry.fromJson(value);
          if (key is String && entry != null) map[key] = entry;
        });
      }
    } catch (e) {
      debugPrint('lyrics cache index unreadable: ${e.runtimeType}');
    }
    return _index = map;
  }

  Future<void> _saveIndex() async {
    final index = _index;
    if (index == null) return;
    await _store.write(_indexKey, jsonEncode({for (final e in index.entries) e.key: e.value.toJson()}));
  }

  /// The song's row; a pick stored before the cache existed is moved in (as user-chosen) on the way.
  Future<LyricsCacheEntry?> get(String songKey, {int? now}) async {
    final raw = await _store.read(rowKey(songKey));
    if (raw != null) {
      try {
        final entry = LyricsCacheEntry.fromJson(songKey, jsonDecode(raw));
        if (entry != null) return entry;
      } catch (_) {}
    }
    final legacy = await _legacy.read(legacyUserKey(songKey));
    if (legacy == null) return null;
    try {
      final json = jsonDecode(legacy);
      if (json is! Map || json['lrc'] is! String) return null;
      final provider = json['provider'] is String ? json['provider'] as String : LyricsCachePolicy.manualProvider;
      final entry = LyricsCachePolicy.entry(
        songKey: songKey,
        lyricsProvider: provider,
        lyricsProviderSongId: json['providerSongId'] is String ? json['providerSongId'] as String : null,
        lrc: json['lrc'] as String,
        cachedAt: now ?? DateTime.now().millisecondsSinceEpoch,
        userChosen: true,
      );
      await upsert(entry);
      await _legacy.write(legacyUserKey(songKey), null);
      return entry;
    } catch (_) {
      return null;
    }
  }

  Future<void> upsert(LyricsCacheEntry entry) => _locked(() async {
    await _store.write(rowKey(entry.songKey), jsonEncode(entry.toJson()));
    final index = await _loadIndex();
    final previous = index[entry.songKey];
    index[entry.songKey] = _IndexEntry(
      lrcBytes: LyricsCachePolicy.utf8Bytes(entry.lrc),
      cachedAt: entry.cachedAt,
      userChosen: entry.userChosen,
      typed: LyricsCachePolicy.isTyped(entry.lyricsProvider),
      translations: previous?.translations ?? {},
    );
    await _saveIndex();
  });

  /// A user-chosen row was used at [usedAt].
  Future<void> markUsed(String songKey, int usedAt) async {
    final entry = await get(songKey);
    if (entry == null) return;
    await upsert(entry.copyWith(cachedAt: usedAt));
  }

  /// Deletes the row unless it was rewritten since [cachedAt]; 1 when deleted.
  Future<int> deleteIfUnchanged(String songKey, int cachedAt) async {
    final entry = await get(songKey);
    if (entry == null || entry.cachedAt != cachedAt) return 0;
    return _locked(() async {
      await _store.write(rowKey(songKey), null);
      final index = await _loadIndex();
      final previous = index[songKey];
      if (previous != null) {
        if (previous.translations.isEmpty) {
          index.remove(songKey);
        } else {
          index[songKey] = previous.withoutLyrics();
        }
        await _saveIndex();
      }
      return 1;
    });
  }

  /// Deletes the song's free provider translations.
  Future<void> deleteProviderTranslations(String songKey) => _locked(() async {
    final index = await _loadIndex();
    final previous = index[songKey];
    if (previous == null) return;
    for (final key in previous.translations.keys) {
      await _store.write(key, null);
    }
    if (previous.lrcBytes == null) {
      index.remove(songKey);
    } else {
      index[songKey] = previous.withTranslations({});
    }
    await _saveIndex();
  });

  /// Every song's share of the budget.
  Future<List<LyricsFootprint>> footprints() => _locked(() async {
    final index = await _loadIndex();
    return [
      for (final e in index.entries)
        if (e.value.lrcBytes != null || e.value.translations.isNotEmpty)
          LyricsFootprint(
            songKey: e.key,
            userChosen: e.value.lrcBytes != null && e.value.userChosen,
            lastUsedAt:
                e.value.cachedAt ?? e.value.translations.values.fold<int>(0, (a, t) => t.cachedAt > a ? t.cachedAt : a),
            bytes: (e.value.lrcBytes ?? 0) + e.value.translations.values.fold<int>(0, (a, t) => a + t.bytes),
            lyricsCachedAt: e.value.lrcBytes == null ? null : e.value.cachedAt,
            typed: e.value.lrcBytes != null && e.value.typed,
          ),
    ];
  });

  // Translations ----------------------------------------------------------------

  /// The translation cached for these exact lines ([sourceHash]) in [targetLanguage] by [model], one per line.
  Future<List<String>?> getTranslation({
    required String songKey,
    required String sourceHash,
    required String targetLanguage,
    required String model,
  }) async {
    final key = translationKey(songKey, model, targetLanguage);
    var slot = await _readSlot(key);
    final paid = !model.startsWith(LyricsCachePolicy.providerTranslationModelPrefix);
    // A paid translation of other lines (synced in, or kept when the song's lyrics changed) has its own slot.
    if (paid && slot != null && slot.hash != sourceHash) slot = await _readSlot(paidSlotKey(key, sourceHash));
    if (slot == null || slot.hash != sourceHash) return null;
    // Paid translations written before the index existed join it as they are read (sync lists them from it).
    if (paid) await _indexPaid(slot.storageKey, songKey, targetLanguage, model, slot);
    return slot.lines;
  }

  /// A paid translation of lines other than the slot's current ones, kept beside it (Android keys its
  /// rows by the source hash too, so a song can hold several).
  static String paidSlotKey(String translationKey, String sourceHash) => '$translationKey#$sourceHash';

  /// Paid (Gemini) translations by storage key: what cloud sync lists, since the store can't list keys.
  static const String paidIndexKey = 'yoin.lyrics.translation.paid';

  Map<String, _PaidEntry>? _paid;

  Future<Map<String, _PaidEntry>> _loadPaid() async {
    final loaded = _paid;
    if (loaded != null) return loaded;
    final map = <String, _PaidEntry>{};
    try {
      final raw = await _store.read(paidIndexKey);
      final json = raw == null ? null : jsonDecode(raw);
      if (json is Map) {
        json.forEach((key, value) {
          final entry = _PaidEntry.fromJson(value);
          if (key is String && entry != null) map[key] = entry;
        });
      }
    } catch (e) {
      debugPrint('paid translation index unreadable: ${e.runtimeType}');
    }
    return _paid = map;
  }

  Future<void> _savePaid() async {
    final paid = _paid;
    if (paid == null) return;
    await _store.write(paidIndexKey, jsonEncode({for (final e in paid.entries) e.key: e.value.toJson()}));
  }

  Future<void> _indexPaid(String storageKey, String songKey, String language, String model, _Slot slot) =>
      _locked(() async {
        final paid = await _loadPaid();
        final entry = _PaidEntry(songKey: songKey, sourceHash: slot.hash, language: language, model: model);
        if (paid[storageKey] == entry) return;
        paid[storageKey] = entry;
        await _savePaid();
      });

  Future<_Slot?> _readSlot(String storageKey) async {
    final raw = await _store.read(storageKey);
    if (raw == null) return null;
    try {
      final json = jsonDecode(raw);
      if (json is! Map || json['hash'] is! String || json['lines'] is! List) return null;
      final at = json['at'];
      return _Slot(
        storageKey: storageKey,
        hash: json['hash'] as String,
        lines: [for (final l in json['lines'] as List) l is String ? l : ''],
        cachedAt: at is int ? at : 0,
        translationsJson: json['tj'] is String ? json['tj'] as String : null,
      );
    } catch (_) {
      return null;
    }
  }

  // Paid translations for cloud sync (Android `lyrics_translation_cache`'s Gemini rows) -----------------

  /// Every paid translation the index knows, with the text sync carries.
  Future<List<PaidTranslation>> paidTranslations() async {
    final paid = await _locked(() async => Map.of(await _loadPaid()));
    final out = <PaidTranslation>[];
    for (final e in paid.entries) {
      final slot = await _readSlot(e.key);
      if (slot == null || slot.hash != e.value.sourceHash) continue;
      out.add(e.value.translation(slot));
    }
    return out;
  }

  /// The paid translation for exactly these lines, language and model; null when none is kept.
  Future<PaidTranslation?> paidTranslation({
    required String songKey,
    required String sourceHash,
    required String targetLanguage,
    required String model,
  }) async {
    final key = translationKey(songKey, model, targetLanguage);
    final entry = _PaidEntry(songKey: songKey, sourceHash: sourceHash, language: targetLanguage, model: model);
    for (final storageKey in [key, paidSlotKey(key, sourceHash)]) {
      final slot = await _readSlot(storageKey);
      if (slot != null && slot.hash == sourceHash) return entry.translation(slot);
    }
    return null;
  }

  /// Keeps [translation] unless one for the same lines, language and model is already here (write-once):
  /// in the song's slot when that is free or holds these lines, else beside it. The text is kept as
  /// received, so it reads back byte for byte.
  Future<void> insertPaidTranslationIfAbsent(PaidTranslation translation, {required int cachedAt}) => _locked(() async {
    final key = translationKey(translation.songKey, translation.model, translation.targetLanguage);
    final current = await _readSlot(key);
    if (current != null && current.hash == translation.sourceHash) return;
    final side = paidSlotKey(key, translation.sourceHash);
    if (current != null && await _readSlot(side) != null) return;
    final storageKey = current == null ? key : side;
    final lines = translation.lines;
    if (lines == null) throw const FormatException('translationsJson');
    await _store.write(
      storageKey,
      jsonEncode({'hash': translation.sourceHash, 'lines': lines, 'at': cachedAt, 'tj': translation.translationsJson}),
    );
    final paid = await _loadPaid();
    paid[storageKey] = _PaidEntry(
      songKey: translation.songKey,
      sourceHash: translation.sourceHash,
      language: translation.targetLanguage,
      model: translation.model,
    );
    await _savePaid();
  });

  /// Drops the paid translation for exactly these lines, language and model (a synced delete).
  Future<void> deletePaidTranslation({
    required String songKey,
    required String sourceHash,
    required String targetLanguage,
    required String model,
  }) => _locked(() async {
    final key = translationKey(songKey, model, targetLanguage);
    final paid = await _loadPaid();
    for (final storageKey in [key, paidSlotKey(key, sourceHash)]) {
      final slot = await _readSlot(storageKey);
      if (slot == null || slot.hash != sourceHash) continue;
      await _store.write(storageKey, null);
      paid.remove(storageKey);
    }
    await _savePaid();
  });

  /// Stores a translation; a free `provider:*` one goes into the index (and the budget). Returns its bytes.
  Future<int> upsertTranslation({
    required String songKey,
    required String sourceHash,
    required String targetLanguage,
    required String model,
    required List<String> lines,
    required int cachedAt,
  }) async {
    final key = translationKey(songKey, model, targetLanguage);
    final linesJson = jsonEncode(lines);
    final paid = !model.startsWith(LyricsCachePolicy.providerTranslationModelPrefix);
    if (paid) {
      // Paid translations cost money and sync: the slot's earlier lines' one moves aside, never away.
      final previous = await _readSlot(key);
      if (previous != null && previous.hash != sourceHash) {
        final side = paidSlotKey(key, previous.hash);
        await _store.write(
          side,
          jsonEncode({'hash': previous.hash, 'lines': previous.lines, 'at': previous.cachedAt, 'tj': previous.translationsJson}),
        );
        await _indexPaid(side, songKey, targetLanguage, model, previous);
      }
    }
    await _store.write(key, jsonEncode({'hash': sourceHash, 'lines': lines, 'at': cachedAt}));
    final bytes = LyricsCachePolicy.utf8Bytes(linesJson);
    if (paid) {
      await _indexPaid(
        key,
        songKey,
        targetLanguage,
        model,
        _Slot(storageKey: key, hash: sourceHash, lines: lines, cachedAt: cachedAt, translationsJson: null),
      );
      return bytes;
    }
    await _locked(() async {
      final index = await _loadIndex();
      final previous = index[songKey] ?? const _IndexEntry.empty();
      index[songKey] = previous.withTranslations({
        ...previous.translations,
        key: _TranslationFootprint(bytes, cachedAt),
      });
      await _saveIndex();
    });
    return bytes;
  }
}

/// A paid translation as cloud sync carries it (Android `lyrics_translation_cache` row).
@immutable
final class PaidTranslation {
  const PaidTranslation({
    required this.songKey,
    required this.sourceHash,
    required this.targetLanguage,
    required this.model,
    required this.translationsJson,
    required this.cachedAt,
  });

  /// The track's [MediaId] string.
  final String songKey;
  final String sourceHash;
  final String targetLanguage;
  final String model;

  /// The lines as a JSON array of strings, the exact text sync hashes.
  final String translationsJson;
  final int cachedAt;

  /// The lines, or null when [translationsJson] isn't an array.
  List<String>? get lines {
    try {
      final json = jsonDecode(translationsJson);
      if (json is! List) return null;
      return [for (final l in json) l is String ? l : ''];
    } catch (_) {
      return null;
    }
  }
}

/// One stored translation value.
final class _Slot {
  const _Slot({
    required this.storageKey,
    required this.hash,
    required this.lines,
    required this.cachedAt,
    required this.translationsJson,
  });

  final String storageKey;
  final String hash;
  final List<String> lines;
  final int cachedAt;

  /// The text as synced in; null for one written here (then it is [lines] encoded).
  final String? translationsJson;
}

/// What the paid index keeps per stored translation.
final class _PaidEntry {
  const _PaidEntry({required this.songKey, required this.sourceHash, required this.language, required this.model});

  final String songKey;
  final String sourceHash;
  final String language;
  final String model;

  PaidTranslation translation(_Slot slot) => PaidTranslation(
    songKey: songKey,
    sourceHash: sourceHash,
    targetLanguage: language,
    model: model,
    translationsJson: slot.translationsJson ?? jsonEncode(slot.lines),
    cachedAt: slot.cachedAt,
  );

  Map<String, Object?> toJson() => {'s': songKey, 'h': sourceHash, 'l': language, 'm': model};

  static _PaidEntry? fromJson(Object? json) {
    if (json is! Map) return null;
    final s = json['s'], h = json['h'], l = json['l'], m = json['m'];
    if (s is! String || h is! String || l is! String || m is! String) return null;
    return _PaidEntry(songKey: s, sourceHash: h, language: l, model: m);
  }

  @override
  bool operator ==(Object other) =>
      other is _PaidEntry &&
      other.songKey == songKey &&
      other.sourceHash == sourceHash &&
      other.language == language &&
      other.model == model;

  @override
  int get hashCode => Object.hash(songKey, sourceHash, language, model);
}

final class _TranslationFootprint {
  const _TranslationFootprint(this.bytes, this.cachedAt);

  final int bytes;
  final int cachedAt;
}

/// What the index keeps per song; [lrcBytes] / [cachedAt] are null when only translations are left.
final class _IndexEntry {
  const _IndexEntry({
    required this.lrcBytes,
    required this.cachedAt,
    required this.userChosen,
    required this.typed,
    required this.translations,
  });

  const _IndexEntry.empty()
    : lrcBytes = null,
      cachedAt = null,
      userChosen = false,
      typed = false,
      translations = const {};

  final int? lrcBytes;
  final int? cachedAt;
  final bool userChosen;
  final bool typed;
  final Map<String, _TranslationFootprint> translations;

  _IndexEntry withoutLyrics() =>
      _IndexEntry(lrcBytes: null, cachedAt: null, userChosen: false, typed: false, translations: translations);

  _IndexEntry withTranslations(Map<String, _TranslationFootprint> next) =>
      _IndexEntry(lrcBytes: lrcBytes, cachedAt: cachedAt, userChosen: userChosen, typed: typed, translations: next);

  Map<String, Object?> toJson() => {
    'l': lrcBytes,
    'c': cachedAt,
    'u': userChosen,
    'm': typed,
    't': {
      for (final t in translations.entries) t.key: [t.value.bytes, t.value.cachedAt],
    },
  };

  static _IndexEntry? fromJson(Object? json) {
    if (json is! Map) return null;
    final translations = <String, _TranslationFootprint>{};
    final t = json['t'];
    if (t is Map) {
      t.forEach((key, value) {
        if (key is String && value is List && value.length == 2 && value[0] is int && value[1] is int) {
          translations[key] = _TranslationFootprint(value[0] as int, value[1] as int);
        }
      });
    }
    return _IndexEntry(
      lrcBytes: json['l'] is int ? json['l'] as int : null,
      cachedAt: json['c'] is int ? json['c'] as int : null,
      userChosen: json['u'] == true,
      typed: json['m'] == true,
      translations: translations,
    );
  }
}

/// Keeps the lyrics cache within [LyricsCachePolicy.budgetBytes] (Android
/// LyricsCacheBudget.kt). Call [onWrite] after each row or free translation
/// written. The index holds every size, so the total is exact on each check;
/// over budget, [LyricsCachePolicy.planEviction] picks the songs to drop.
///
/// Typed rows are never evicted. When only they (and the song just written)
/// are left over the target, trimming stops and says so once (until the cache
/// is back under the target). A failed trim is only logged: it must never fail
/// loading or applying lyrics.
class LyricsCacheBudget {
  LyricsCacheBudget(
    this._store, {
    this.budgetBytes = LyricsCachePolicy.budgetBytes,
    this.targetBytes = LyricsCachePolicy.trimTargetBytes,
  });

  final LyricsCacheStore _store;
  final int budgetBytes;
  final int targetBytes;
  bool _stuckReported = false;

  /// [songKey] just had bytes written; evicts other songs when needed (never it).
  Future<void> onWrite(String songKey) async {
    try {
      await _trim(protectedSong: songKey);
    } catch (e) {
      debugPrint('Lyrics cache trim failed: ${e.runtimeType}');
    }
  }

  /// Checks now; returns how many songs were evicted.
  Future<int> trim() => _trim(protectedSong: null);

  Future<int> _trim({required String? protectedSong}) async {
    final footprints = await _store.footprints();
    final total = footprints.fold<int>(0, (a, f) => a + f.bytes);
    final victims = LyricsCachePolicy.planEviction(
      footprints,
      budgetBytes: budgetBytes,
      targetBytes: targetBytes,
      protectedSong: protectedSong,
    );
    final left = total - victims.fold<int>(0, (a, f) => a + f.bytes);
    final stuck = total > budgetBytes && left > targetBytes;
    if (stuck && !_stuckReported) {
      final typed = footprints.where((f) => f.typed).fold<int>(0, (a, f) => a + f.bytes);
      debugPrint(
        'Lyrics cache stays over budget: $left bytes left after eviction (target $targetBytes); '
        '$typed bytes are typed lyrics, which are never evicted',
      );
    }
    _stuckReported = stuck;
    var evicted = 0;
    for (final victim in victims) {
      if (await _evict(victim)) evicted++;
    }
    return evicted;
  }

  /// The row first (given up if it was rewritten meanwhile), then its free translations. Orphans go at once.
  Future<bool> _evict(LyricsFootprint victim) async {
    final cachedAt = victim.lyricsCachedAt;
    if (cachedAt != null && await _store.deleteIfUnchanged(victim.songKey, cachedAt) == 0) return false;
    await _store.deleteProviderTranslations(victim.songKey);
    return true;
  }
}
