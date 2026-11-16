/// Paid (Gemini) lyric translations <-> [SyncKinds.lyricsTranslation]
/// (Android adapters/LyricsTranslationSyncAdapter.kt), key =
/// trackProvider␟trackRawId␟sourceHash␟targetLanguage␟model. Free
/// `provider:*` translations are re-fetchable and never sync.
///
/// Write-once union: the key pins the content, an existing translation is
/// never overwritten. A translation is only usable where the exact source
/// lines load again, so the payload may carry a snapshot of the lyrics it was
/// made from ([lyricsField]: lyricsProvider, lyricsProviderSongId, lrc). The
/// snapshot is NOT part of the projection; [readAll] collects it and
/// [decoratePayload] stamps it onto the payload this device writes. It is
/// attached only when it provably matches (cached before the translation,
/// same line count), never from typed ("manual") lyrics, and a received
/// snapshot seeds the lyrics cache only where nothing better is there.
library;

import 'dart:convert';

import '../../lyrics/lrc_parser.dart';
import '../../lyrics/lyrics_cache.dart';
import '../../model/model.dart';
import '../sync_contracts.dart';
import 'sync_projections.dart';

class LyricsTranslationSyncAdapter extends SyncAdapter {
  LyricsTranslationSyncAdapter(this._cache, {int Function()? clock, this.onLyricsSeeded})
    : _clock = clock ?? (() => DateTime.now().millisecondsSinceEpoch);

  final LyricsCacheStore _cache;
  final int Function() _clock;

  /// A song's lyrics row was written from a snapshot (the cache budget counts it).
  final Future<void> Function(String songKey)? onLyricsSeeded;

  /// Payload field holding the optional lyrics snapshot.
  static const lyricsField = 'lyrics';
  static const providerModelPrefix = LyricsCachePolicy.providerTranslationModelPrefix;
  static const manualLyricsProvider = LyricsCachePolicy.manualProvider;

  /// Lyrics snapshots found by the last [readAll], by record key, for [decoratePayload].
  final Map<String, JsonMap> _snapshots = {};

  @override
  String get kind => SyncKinds.lyricsTranslation;
  @override
  int get kindVersion => 1;
  @override
  FileClass get fileClass => FileClass.artifacts;
  @override
  bool get perAccount => false;
  @override
  bool get propagatesDeletes => false;
  @override
  ConflictPolicy get conflictPolicy => ConflictPolicy.lww;

  static String keyOf(
    String trackProvider,
    String trackRawId,
    String sourceHash,
    String targetLanguage,
    String model,
  ) => SyncFormat.compositeKey([trackProvider, trackRawId, sourceHash, targetLanguage, model]);

  /// Lines the repository translates from [lrc] (Android's rule): LrcParser's lines, trimmed, blanks dropped.
  static int sourceLineCount(String lrc) => switch (LrcParser.parse(lrc)) {
    SyncedLyrics(:final lines) => lines.where((l) => l.text.trim().isNotEmpty).length,
    UnsyncedLyrics(:final text) => text.split('\n').where((l) => l.trim().isNotEmpty).length,
  };

  static int? translationCount(String translationsJson) {
    try {
      final json = jsonDecode(translationsJson);
      return json is List ? json.length : null;
    } catch (_) {
      return null;
    }
  }

  @override
  Future<List<DomainRow>> readAll(String? localProfileId) async {
    final rows = (await _cache.paidTranslations()).where((t) => !t.model.startsWith(providerModelPrefix));
    final lyricsBySong = <String, LyricsCacheEntry?>{};
    final found = <String, JsonMap>{};
    final result = <DomainRow>[];
    for (final row in rows) {
      final fields = _Fields.of(row);
      if (fields == null) continue;
      if (!lyricsBySong.containsKey(row.songKey)) lyricsBySong[row.songKey] = await _cache.get(row.songKey);
      final snapshot = _snapshotOf(row, lyricsBySong[row.songKey]);
      if (snapshot != null) found[fields.key] = snapshot;
      result.add(DomainRow(key: fields.key, projection: fields.projection(), rowTs: row.cachedAt));
    }
    _snapshots
      ..clear()
      ..addAll(found);
    return result;
  }

  @override
  JsonMap? project(JsonMap payload) => _Fields.parse(payload)?.projection();

  @override
  JsonMap decoratePayload(JsonMap payload, String deviceName) {
    if (payload.containsKey(lyricsField)) return payload;
    final key = _Fields.parse(payload)?.key;
    final snapshot = key == null ? null : _snapshots[key];
    return snapshot == null ? payload : withField(payload, lyricsField, snapshot);
  }

  @override
  Future<ApplyOutcome> apply(
    String? localProfileId,
    String key,
    JsonMap payload,
    int versionTs,
    String? expectedLocalHash,
  ) async {
    final parts = splitKey(key, 5);
    if (parts == null) return const Skipped(SkipReason.unsupported, null);
    final existing = await _translation(parts);
    final currentHash = hashOrNull(existing == null ? null : _Fields.of(existing)?.projection());
    final fields = _Fields.parse(payload);
    if (fields == null || fields.key != key || fields.model.startsWith(providerModelPrefix)) {
      return Skipped(SkipReason.unsupported, currentHash);
    }
    final now = _clock();
    if (existing != null) {
      // Write-once: an identical translation only gets its lyrics seeded; a different one is kept as is.
      if (currentHash != hashOrNull(fields.projection())) {
        return Skipped(currentHash != expectedLocalHash ? SkipReason.changedLocally : SkipReason.policy, currentHash);
      }
      await _seedLyrics(fields, payload, now);
      return Applied(currentHash);
    }
    if (expectedLocalHash != null) return const Skipped(SkipReason.changedLocally, null);
    if (translationCount(fields.translationsJson) == null) return const Skipped(SkipReason.unsupported, null);
    await _seedLyrics(fields, payload, now);
    await _cache.insertPaidTranslationIfAbsent(fields.toTranslation(now), cachedAt: now);
    final written = await _translation(parts);
    return Applied(hashOrNull(written == null ? null : _Fields.of(written)?.projection()));
  }

  @override
  Future<ApplyOutcome> delete(String? localProfileId, String key, String? expectedLocalHash) async {
    final parts = splitKey(key, 5);
    if (parts == null) return const Skipped(SkipReason.unsupported, null);
    final current = await _translation(parts);
    if (current == null) return const Applied(null);
    final currentHash = hashOrNull(_Fields.of(current)?.projection());
    if (currentHash != expectedLocalHash) return Skipped(SkipReason.changedLocally, currentHash);
    await _cache.deletePaidTranslation(
      songKey: _songKey(parts[0], parts[1]),
      sourceHash: parts[2],
      targetLanguage: parts[3],
      model: parts[4],
    );
    return const Applied(null);
  }

  static String _songKey(String provider, String rawId) => '$provider:$rawId';

  Future<PaidTranslation?> _translation(List<String> parts) {
    if (parts[0].isEmpty || parts[0].contains(':') || parts[1].isEmpty) return Future.value();
    return _cache.paidTranslation(
      songKey: _songKey(parts[0], parts[1]),
      sourceHash: parts[2],
      targetLanguage: parts[3],
      model: parts[4],
    );
  }

  /// Seeds the lyrics cache from a received snapshot when the song has no row, or only an automatic one
  /// past its time ([LyricsCachePolicy.isUsable]). Never replaces lyrics the user chose (typed or
  /// `user|`-marked, any age); the seeded row counts as fresh. A `user|` mark in the snapshot is kept, so
  /// the user's choice follows them.
  Future<void> _seedLyrics(_Fields fields, JsonMap payload, int now) async {
    final snapshot = payload[lyricsField];
    if (snapshot is! Map<String, Object?>) return;
    final lyricsProvider = requiredString(snapshot, 'lyricsProvider');
    if (lyricsProvider == null || lyricsProvider.isEmpty || lyricsProvider == manualLyricsProvider) return;
    final songId = optionalString(snapshot, 'lyricsProviderSongId');
    if (songId is! FieldOk<String?>) return;
    final lrc = requiredString(snapshot, 'lrc');
    if (lrc == null || lrc.trim().isEmpty) return;
    final count = translationCount(fields.translationsJson);
    if (count == null || sourceLineCount(lrc) != count) return;
    final songKey = _songKey(fields.trackProvider, fields.trackRawId);
    final existing = await _cache.get(songKey);
    if (existing != null && LyricsCachePolicy.isUsable(existing, now)) return;
    await _cache.upsert(
      LyricsCacheEntry(
        songKey: songKey,
        lyricsProvider: lyricsProvider,
        storedSongId: songId.value,
        lrc: lrc,
        cachedAt: now,
      ),
    );
    await onLyricsSeeded?.call(songKey);
  }

  JsonMap? _snapshotOf(PaidTranslation row, LyricsCacheEntry? lyrics) {
    if (lyrics == null || lyrics.lyricsProvider == manualLyricsProvider) return null;
    // An automatic row's cachedAt is when it was fetched: newer than the translation means the lyrics
    // changed since. A user-chosen row's cachedAt is "last used" and moves on reads, so only the
    // line-count check below vouches for it.
    if (!lyrics.userChosen && lyrics.cachedAt > row.cachedAt) return null;
    final count = translationCount(row.translationsJson);
    if (count == null || sourceLineCount(lyrics.lrc) != count) return null;
    return {'lyricsProvider': lyrics.lyricsProvider, 'lyricsProviderSongId': lyrics.storedSongId, 'lrc': lyrics.lrc};
  }
}

final class _Fields {
  const _Fields({
    required this.trackProvider,
    required this.trackRawId,
    required this.sourceHash,
    required this.targetLanguage,
    required this.model,
    required this.translationsJson,
  });

  final String trackProvider;
  final String trackRawId;
  final String sourceHash;
  final String targetLanguage;
  final String model;
  final String translationsJson;

  String get key => LyricsTranslationSyncAdapter.keyOf(trackProvider, trackRawId, sourceHash, targetLanguage, model);

  JsonMap projection() => {
    'trackProvider': trackProvider,
    'trackRawId': trackRawId,
    'sourceHash': sourceHash,
    'targetLanguage': targetLanguage,
    'model': model,
    'translationsJson': translationsJson,
  };

  PaidTranslation toTranslation(int cachedAt) => PaidTranslation(
    songKey: '$trackProvider:$trackRawId',
    sourceHash: sourceHash,
    targetLanguage: targetLanguage,
    model: model,
    translationsJson: translationsJson,
    cachedAt: cachedAt,
  );

  /// The song key is a MediaId string: `provider:rawId`.
  static _Fields? of(PaidTranslation row) {
    final id = MediaId.tryParse(row.songKey);
    if (id == null) return null;
    return _Fields(
      trackProvider: id.provider,
      trackRawId: id.rawId,
      sourceHash: row.sourceHash,
      targetLanguage: row.targetLanguage,
      model: row.model,
      translationsJson: row.translationsJson,
    );
  }

  static _Fields? parse(JsonMap payload) {
    String? nonEmpty(String name) {
      final v = requiredString(payload, name);
      return v == null || v.isEmpty ? null : v;
    }

    final provider = nonEmpty('trackProvider');
    final rawId = nonEmpty('trackRawId');
    final hash = nonEmpty('sourceHash');
    final language = nonEmpty('targetLanguage');
    final model = nonEmpty('model');
    final translations = requiredString(payload, 'translationsJson');
    if (provider == null || rawId == null || hash == null || language == null || model == null) return null;
    if (translations == null) return null;
    return _Fields(
      trackProvider: provider,
      trackRawId: rawId,
      sourceHash: hash,
      targetLanguage: language,
      model: model,
      translationsJson: translations,
    );
  }
}
