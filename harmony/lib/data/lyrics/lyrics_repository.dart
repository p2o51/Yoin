import 'dart:async';
import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/providers.dart';
import '../../nowplaying/lyrics_logic.dart';
import '../ai/ai_settings.dart';
import '../ai/ai_client.dart';
import '../ai/ai_service.dart';
import '../model/model.dart';
import '../source/music_source.dart';
import 'lrc_parser.dart';
import 'lyric_provider.dart';
import 'lyrics_cache.dart';
import 'lyrics_registry.dart';
import 'lyrics_search.dart';
import 'lyrics_translation.dart';

/// Lyrics as loaded for a song, and where they came from (Android
/// YoinRepository.LoadedLyrics). [providerName] is a registry provider,
/// [LyricsCachePolicy.manualProvider] or `subsonic`.
@immutable
final class LoadedLyrics {
  const LoadedLyrics({required this.sheet, this.providerName, this.providerSongId});

  final LyricSheet sheet;
  final String? providerName;
  final String? providerSongId;
}

/// The whole set — lyrics AND their translation — from a later source that
/// translates this song (LyricsTranslationProviderSwitchOffer). Two sources
/// are never mixed line by line.
@immutable
final class LyricsSwitchOffer {
  const LyricsSwitchOffer({
    required this.providerName,
    required this.providerSongId,
    required this.sheet,
    required this.rawLrc,
    required this.translations,
  });

  final String providerName;
  final String providerSongId;

  /// The offered lyrics, rows as Now Playing shows them; [translations] is keyed by row.
  final LyricSheet sheet;
  final String rawLrc;
  final Map<int, String> translations;
}

/// What the Translate key's lookup came to (LyricsTranslationResult).
sealed class LyricsTranslationResult {
  const LyricsTranslationResult();
}

/// Translations by row; with [sheet] when the source's lyrics were fetched again (they replace the shown ones).
final class LyricsTranslated extends LyricsTranslationResult {
  const LyricsTranslated(this.translations, {this.sheet, this.providerName, this.providerSongId});

  final Map<int, String> translations;
  final LyricSheet? sheet;
  final String? providerName;
  final String? providerSongId;
}

final class LyricsSwitchAvailable extends LyricsTranslationResult {
  const LyricsSwitchAvailable(this.offer);
  final LyricsSwitchOffer offer;
}

final class LyricsAlreadyTargetLanguage extends LyricsTranslationResult {
  const LyricsAlreadyTargetLanguage(this.targetLanguage);
  final String targetLanguage;
}

final class LyricsApiKeyMissing extends LyricsTranslationResult {
  const LyricsApiKeyMissing();
}

final class LyricsTranslationFailed extends LyricsTranslationResult {
  const LyricsTranslationFailed(this.message);
  final String message;
}

/// The lyrics half of Android's YoinRepository: load order, the automatic
/// fallback chain and its cache, the user's picks, and translation (the
/// lyrics source's own, a switch to one that has it, then the AI service set up).
class LyricsRepository {
  LyricsRepository({
    required this.registry,
    required LyricsCacheStore? cache,
    required Future<AiSettings> Function() aiSettings,
    required AiService ai,
    NetEaseTranslator? netEaseTranslator,
    int Function()? clock,
  }) : _cache = cache,
       _budget = cache == null ? null : LyricsCacheBudget(cache),
       _aiSettings = aiSettings,
       _ai = ai,
       _netEase = netEaseTranslator,
       _clock = clock ?? (() => DateTime.now().millisecondsSinceEpoch);

  final LyricsProviderRegistry registry;
  final LyricsCacheStore? _cache;
  final LyricsCacheBudget? _budget;
  final Future<AiSettings> Function() _aiSettings;
  final AiService _ai;
  final NetEaseTranslator? _netEase;
  final int Function() _clock;
  final StreamController<String> _changes = StreamController<String>.broadcast();
  final Map<String, Future<LoadedLyrics?>> _inFlightLoads = {};

  /// Emits a song key (`MediaId.toString()`) when user-chosen lyrics are saved
  /// via [applyLyrics] or [applySwitch].
  Stream<String> get changes => _changes.stream;

  void dispose() {
    if (!_changes.isClosed) _changes.close();
  }

  void _notifyChanged(String songKey) {
    if (!_changes.isClosed) _changes.add(songKey);
  }

  /// The `lyricsProvider` of Subsonic server lyrics (not a registry provider: no translation of its own).
  static const String subsonicProvider = MediaId.providerSubsonic;

  /// Load order: the user's pick (typed, a search result, an accepted
  /// switch) → the Subsonic server's lyrics → a cached automatic row within
  /// 30 days → the fallback chain (QQ → NetEase → LRCLIB), whose raw LRC is
  /// cached. With Subsonic and no pick, only the server's lyrics count — no
  /// fallback — and the server's failure is thrown. The fallback needs a title and an artist; when the shown names
  /// find nothing, see [_fetchUnderAlternateNames].
  Future<LoadedLyrics?> getLoadedLyrics(Track track, MusicSource source) {
    final loadKey = '${track.id}|${source.provider}';
    final existing = _inFlightLoads[loadKey];
    if (existing != null) return existing;
    final future = _runTrackedLoad(loadKey, track, source);
    _inFlightLoads[loadKey] = future;
    return future;
  }

  Future<LoadedLyrics?> _runTrackedLoad(
    String loadKey,
    Track track,
    MusicSource source,
  ) async {
    try {
      return await _loadLyrics(track, source);
    } finally {
      _inFlightLoads.remove(loadKey);
    }
  }

  Future<LoadedLyrics?> _loadLyrics(Track track, MusicSource source) async {
    final key = track.id.toString();
    final now = _clock();
    final cached = await _readCache(key);
    if (cached != null && cached.userChosen) {
      if (LyricsCachePolicy.needsRecencyRefresh(cached, now)) {
        try {
          await _cache?.markUsed(key, now);
        } catch (_) {}
      }
      return _loaded(cached);
    }
    if (source.provider == MediaId.providerSubsonic) {
      if (!source.capabilities.contains(Capability.lyrics)) return null;
      // A server or network failure is thrown, as on Android: the up-next
      // prefetch retries it instead of keeping "no lyrics" for the song.
      final lyrics = await source.metadata.getLyrics(track);
      if (lyrics == null) return null;
      return LoadedLyrics(sheet: lyricSheetOf(lyrics), providerName: subsonicProvider, providerSongId: track.id.rawId);
    }
    if (cached != null && LyricsCachePolicy.isUsable(cached, now)) return _loaded(cached);

    final t = track.title?.trim() ?? '';
    final a = track.artist?.trim() ?? '';
    if (t.isEmpty || a.isEmpty) return null;
    final durationMs = track.durationSec == null ? null : track.durationSec! * 1000;
    final hit =
        await registry.fetchLyric(t, a, durationMs: durationMs) ??
        await _fetchUnderAlternateNames(source, track, t, a, durationMs);
    if (hit == null) return null;
    // The chain takes seconds: a pick the user applied meanwhile (search,
    // editor) stays theirs — never overwritten by the automatic row.
    final latest = await _readCache(key);
    if (latest != null && latest.userChosen) return _loaded(latest);
    await _write(key, hit.providerName, hit.providerSongId, hit.lrc, userChosen: false);
    return LoadedLyrics(
      sheet: lyricSheetOf(LrcParser.parse(hit.lrc)),
      providerName: hit.providerName,
      providerSongId: hit.providerSongId,
    );
  }

  /// A storefront in another language shows names no lyrics source has
  /// (Apple Music Japan writes Kim Petras as キム・ペトラス). When the shown
  /// names found nothing and carry non-Latin letters, search once more under
  /// the service's English names. The shown names always go first: a Japanese
  /// song's English names are translations sources don't have.
  Future<LyricsHit?> _fetchUnderAlternateNames(
    MusicSource source,
    Track track,
    String title,
    String artist,
    int? durationMs,
  ) async {
    if (track.id.provider != source.provider) return null;
    if (!_hasNonLatinLetter(title) && !_hasNonLatinLetter(artist)) return null;
    final TrackNames? names;
    try {
      names = await source.metadata.alternateNames(track);
    } catch (_) {
      return null;
    }
    if (names == null || (names.title == title && names.artist == artist)) return null;
    return registry.fetchLyric(names.title, names.artist, durationMs: durationMs);
  }

  static final RegExp _letter = RegExp(r'\p{L}', unicode: true);
  static final RegExp _latin = RegExp(r'\p{Script=Latin}', unicode: true);

  static bool _hasNonLatinLetter(String text) =>
      text.runes.map(String.fromCharCode).any((c) => _letter.hasMatch(c) && !_latin.hasMatch(c));

  /// Typed or pasted lyrics (or a search result's) the user chose for the
  /// song: they never expire and win over everything on load. Null when
  /// there's nothing to apply.
  Future<LoadedLyrics?> applyLyrics(
    Track track,
    String rawLrc, {
    String provider = LyricsCachePolicy.manualProvider,
    String? providerSongId,
  }) async {
    final trimmed = rawLrc.trim();
    if (trimmed.isEmpty) return null;
    final songId = provider == LyricsCachePolicy.manualProvider ? null : providerSongId;
    final key = track.id.toString();
    await _write(key, provider, songId, trimmed, userChosen: true);
    _notifyChanged(key);
    return LoadedLyrics(sheet: lyricSheetOf(LrcParser.parse(trimmed)), providerName: provider, providerSongId: songId);
  }

  /// The user took the switch: this set is theirs and won't be swapped back by the fallback after 30 days.
  Future<void> applySwitch(Track track, LyricsSwitchOffer offer) async {
    final key = track.id.toString();
    await _write(key, offer.providerName, offer.providerSongId, offer.rawLrc, userChosen: true);
    _notifyChanged(key);
  }

  /// The Translate key's lookup for [sheet] (YoinRepository.translateLyrics):
  /// lines already in the target language → said so; the lyrics source's
  /// own translation when it has one ([_translateWithProvider]); else the AI
  /// service (cached per lines, language and model). With no AI key, NetEase's
  /// translation lined up by timestamp is tried before saying the key is missing.
  Future<LyricsTranslationResult> translateLyrics({
    required Track track,
    required LyricSheet sheet,
    required String? currentProviderName,
    required String? currentProviderSongId,
  }) async {
    final title = track.title?.trim() ?? '';
    final artist = track.artist?.trim() ?? '';
    // The model sees the lines with words; their row indices map its answer back.
    final rows = [
      for (var i = 0; i < sheet.rows.length; i++)
        if (sheet.rows[i].text.trim().isNotEmpty) i,
    ];
    final sourceLines = [for (final i in rows) sheet.rows[i].text.trim()];
    if (sourceLines.isEmpty) return const LyricsTranslationFailed('No lyrics to translate');
    final ai = await _aiSettings();
    final targetLanguage = AiLanguages.normalize(ai.targetLanguage);
    if (isChineseTargetLanguage(targetLanguage) && lyricsAppearMostlyChinese(sourceLines)) {
      return LyricsAlreadyTargetLanguage(targetLanguage);
    }

    final viaProvider = await _translateWithProvider(
      track: track,
      title: title,
      artist: artist,
      targetLanguage: targetLanguage,
      currentProviderName: currentProviderName,
      currentProviderSongId: currentProviderSongId,
      fallbackLines: sourceLines,
    );
    if (viaProvider != null) return viaProvider;

    final key = track.id.toString();
    final sourceHash = lyricsTranslationSourceHash(title, artist, sourceLines);
    // Read once: the model looked up is the one that translates and that the result is kept under.
    final model = ai.modelId;
    final cached = await _readTranslation(key, sourceHash, targetLanguage, model, sourceLines.length);
    if (cached != null) return LyricsTranslated(_toRows(cached, rows));

    if (!ai.isReady) {
      final aligned = await _alignedNetEase(track, sheet, title, artist, currentProviderName, currentProviderSongId);
      return aligned ?? const LyricsApiKeyMissing();
    }
    try {
      final translations = await _ai.translateLyricLines(
        ai,
        title: title.isEmpty ? 'Unknown song' : title,
        artist: artist.isEmpty ? 'Unknown artist' : artist,
        lines: sourceLines,
        targetLanguage: targetLanguage,
      );
      final cleaned = {for (final e in translations.entries) e.key: cleanLineTranslation(e.value)};
      try {
        await _cache?.upsertTranslation(
          songKey: key,
          sourceHash: sourceHash,
          targetLanguage: targetLanguage,
          model: model,
          lines: [for (var i = 0; i < sourceLines.length; i++) cleaned[i] ?? ''],
          cachedAt: _clock(),
        );
      } catch (_) {}
      return LyricsTranslated(_toRows(cleaned, rows));
    } catch (e) {
      final message = e is AiException ? e.message : '$e';
      return LyricsTranslationFailed(message.isEmpty ? 'Failed to translate lyrics' : message);
    }
  }

  static Map<int, String> _toRows(Map<int, String> bySourceLine, List<int> rows) => {
    for (final e in bySourceLine.entries)
      if (e.key >= 0 && e.key < rows.length && e.value.trim().isNotEmpty) rows[e.key]: e.value,
  };

  /// The lyrics source's own translation (translateLyricsWithProvider). When
  /// the current source translates into [targetLanguage], its translation is
  /// used and the original is taken from the same fetch; when it has none for
  /// this song, the next sources in the registry's order that can are asked,
  /// and the first with one is offered as a whole set. A source that never
  /// translates (LRCLIB, typed lyrics, Subsonic) → null: Gemini's turn.
  Future<LyricsTranslationResult?> _translateWithProvider({
    required Track track,
    required String title,
    required String artist,
    required String targetLanguage,
    required String? currentProviderName,
    required String? currentProviderSongId,
    required List<String> fallbackLines,
  }) async {
    final current = currentProviderName;
    if (current == null || !registry.servesNativeTranslation(current, targetLanguage)) return null;
    final own = await _fetchProviderTranslation(
      track: track,
      providerName: current,
      providerSongId: currentProviderSongId,
      title: title,
      artist: artist,
      targetLanguage: targetLanguage,
      fallbackLines: fallbackLines,
      persistLyrics: true,
    );
    if (own != null) {
      return LyricsTranslated(
        own.translations,
        sheet: own.sheet,
        providerName: own.providerName,
        providerSongId: own.providerSongId,
      );
    }
    for (final candidate in registry.translationSwitchCandidates(current, targetLanguage)) {
      final offered = await _fetchProviderTranslation(
        track: track,
        providerName: candidate,
        providerSongId: null,
        title: title,
        artist: artist,
        targetLanguage: targetLanguage,
        fallbackLines: fallbackLines,
        persistLyrics: false,
      );
      if (offered == null) continue;
      return LyricsSwitchAvailable(offered);
    }
    return null;
  }

  Future<LyricsSwitchOffer?> _fetchProviderTranslation({
    required Track track,
    required String providerName,
    required String? providerSongId,
    required String title,
    required String artist,
    required String targetLanguage,
    required List<String> fallbackLines,
    required bool persistLyrics,
  }) async {
    final hit = providerSongId != null && registry.canFetch(providerName, providerSongId)
        ? await registry.fetchSelectedLyricWithTranslation(providerName, providerSongId)
        : await registry.searchAndFetchLyricWithTranslation(providerName, title, artist);
    if (hit == null) return null;
    final translatedLrc = hit.translatedLrc;
    if (translatedLrc == null || translatedLrc.trim().isEmpty) return null;
    final sheet = lyricSheetOf(LrcParser.parse(hit.lrc));
    final key = track.id.toString();
    if (persistLyrics) {
      // Fetched again from the source shown: a row the user chose stays chosen.
      final existing = await _readCache(key);
      await _write(
        key,
        hit.providerName,
        hit.providerSongId,
        hit.lrc,
        userChosen: existing != null && existing.lyricsProvider == hit.providerName && existing.userChosen,
      );
    }
    final lines = sheet.rows.isEmpty ? fallbackLines : [for (final r in sheet.rows) r.text];
    final sourceHash = lyricsTranslationSourceHash(title, artist, lines);
    final model = '${LyricsCachePolicy.providerTranslationModelPrefix}${hit.providerName}';
    final cached = await _readTranslation(key, sourceHash, targetLanguage, model, lines.length);
    if (cached != null) {
      return LyricsSwitchOffer(
        providerName: hit.providerName,
        providerSongId: hit.providerSongId,
        sheet: sheet,
        rawLrc: hit.lrc,
        translations: cached,
      );
    }
    final translations = providerTranslationsForSheet(sheet, translatedLrc);
    if (translations.isEmpty) return null;
    try {
      await _cache?.upsertTranslation(
        songKey: key,
        sourceHash: sourceHash,
        targetLanguage: targetLanguage,
        model: model,
        lines: [for (var i = 0; i < lines.length; i++) translations[i] ?? ''],
        cachedAt: _clock(),
      );
      // The free translation goes with the song's row in the budget.
      await _budget?.onWrite(key);
    } catch (_) {}
    return LyricsSwitchOffer(
      providerName: hit.providerName,
      providerSongId: hit.providerSongId,
      sheet: sheet,
      rawLrc: hit.lrc,
      translations: translations,
    );
  }

  /// No Gemini key: NetEase's translation lined up with our lines by
  /// timestamp (the HarmonyOS stopgap, owner 2026-11-15), kept per song.
  Future<LyricsTranslationResult?> _alignedNetEase(
    Track track,
    LyricSheet sheet,
    String title,
    String artist,
    String? currentProviderName,
    String? currentProviderSongId,
  ) async {
    final netEase = _netEase;
    if (netEase == null) return null;
    final key = track.id.toString();
    final texts = [for (final r in sheet.rows) r.text];
    final sourceHash = lyricsTranslationSourceHash(title, artist, texts);
    const model = '${LyricsCachePolicy.providerTranslationModelPrefix}netease-aligned';
    const language = 'Simplified Chinese';
    final cached = await _readTranslation(key, sourceHash, language, model, texts.length);
    if (cached != null && cached.isNotEmpty) return LyricsTranslated(cached);
    Map<int, String> translations;
    try {
      translations = await netEase.translate(
        lines: [for (final r in sheet.rows) (startMs: r.startMs, text: r.text)],
        title: title,
        artist: artist,
        durationMs: track.durationSec == null ? null : track.durationSec! * 1000,
        providerSongId: currentProviderName == 'netease' ? currentProviderSongId : null,
      );
    } catch (_) {
      return null;
    }
    if (translations.isEmpty) return null;
    try {
      await _cache?.upsertTranslation(
        songKey: key,
        sourceHash: sourceHash,
        targetLanguage: language,
        model: model,
        lines: [for (var i = 0; i < texts.length; i++) translations[i] ?? ''],
        cachedAt: _clock(),
      );
      await _budget?.onWrite(key);
    } catch (_) {}
    return LyricsTranslated(translations);
  }

  Future<Map<int, String>?> _readTranslation(
    String key,
    String sourceHash,
    String targetLanguage,
    String model,
    int expectedLines,
  ) async {
    final List<String>? lines;
    try {
      lines = await _cache?.getTranslation(
        songKey: key,
        sourceHash: sourceHash,
        targetLanguage: targetLanguage,
        model: model,
      );
    } catch (_) {
      return null;
    }
    if (lines == null || lines.length != expectedLines) return null;
    return {
      for (var i = 0; i < lines.length; i++)
        if (cleanLineTranslation(lines[i]).trim().isNotEmpty) i: cleanLineTranslation(lines[i]),
    };
  }

  Future<LyricsCacheEntry?> _readCache(String key) async {
    try {
      return await _cache?.get(key, now: _clock());
    } catch (e) {
      debugPrint('lyrics cache read failed: ${e.runtimeType}');
      return null;
    }
  }

  /// One row, then the budget. A [userChosen] row never expires (see [LyricsCachePolicy]).
  Future<void> _write(
    String key,
    String provider,
    String? providerSongId,
    String lrc, {
    required bool userChosen,
  }) async {
    final cache = _cache;
    if (cache == null) return;
    await cache.upsert(
      LyricsCachePolicy.entry(
        songKey: key,
        lyricsProvider: provider,
        lyricsProviderSongId: providerSongId,
        lrc: lrc,
        cachedAt: _clock(),
        userChosen: userChosen,
      ),
    );
    await _budget?.onWrite(key);
  }

  LoadedLyrics _loaded(LyricsCacheEntry entry) => LoadedLyrics(
    sheet: lyricSheetOf(LrcParser.parse(entry.lrc)),
    providerName: entry.lyricsProvider,
    providerSongId: entry.providerSongId,
  );
}

/// The translation cache's key for these lines (buildLyricsTranslationSourceHash).
String lyricsTranslationSourceHash(String title, String artist, List<String> lines) {
  final b = StringBuffer()
    ..write(title)
    ..write('\u001F')
    ..write(artist)
    ..write('\u001F');
  for (final line in lines) {
    b
      ..write(line.length)
      ..write(':')
      ..write(line)
      ..write('\u001E');
  }
  return sha256.convert(utf8.encode(b.toString())).toString();
}

/// A provider's translation LRC on [sheet]'s rows (buildProviderTranslations
/// over the rows Now Playing shows, credits already out).
Map<int, String> providerTranslationsForSheet(LyricSheet sheet, String translatedLrc) {
  final rows = sheet.rows;
  final Lyrics lyrics = rows.isNotEmpty && rows.every((r) => r.startMs != null)
      ? SyncedLyrics([for (final r in rows) LyricLine(r.startMs!, r.text)])
      : UnsyncedLyrics(rows.map((r) => r.text).join('\n'));
  return {
    for (final e in buildProviderTranslations(lyrics, translatedLrc).entries)
      if (cleanLineTranslation(e.value).isNotEmpty) e.key: cleanLineTranslation(e.value),
  };
}

/// The lyrics cache; tests without a key-value store get none.
final lyricsCacheStoreProvider = Provider<LyricsCacheStore?>((ref) {
  try {
    return LyricsCacheStore(ref.watch(cacheStoreProvider), legacy: ref.watch(keyValueStoreProvider));
  } catch (_) {
    return null;
  }
});

/// The providers in priority order (tests override with fakes).
final lyricsRegistryProvider = Provider<LyricsProviderRegistry>((ref) => LyricsProviderRegistry());

/// The Search key's search over the same registry.
final lyricsSearchProvider = Provider<LyricsSearch>((ref) => LyricsSearch(registry: ref.watch(lyricsRegistryProvider)));

final lyricsRepositoryProvider = Provider<LyricsRepository>((ref) {
  final repo = LyricsRepository(
    registry: ref.watch(lyricsRegistryProvider),
    cache: ref.watch(lyricsCacheStoreProvider),
    aiSettings: () async {
      try {
        return await ref.read(aiSettingsProvider.future);
      } catch (_) {
        return const AiSettings();
      }
    },
    ai: ref.watch(aiServiceProvider),
    netEaseTranslator: ref.watch(netEaseTranslatorProvider),
  );
  ref.onDispose(repo.dispose);
  return repo;
});
