import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/about/album_about_repository.dart';
import '../data/about/album_about_store.dart';
import '../data/about/song_about_repository.dart';
import '../data/about/song_about_store.dart';
import '../data/ai/ai_client.dart';
import '../data/ai/ai_service.dart';
import '../data/ai/ai_settings.dart';
import '../data/memory/memory_store.dart';
import '../data/model/model.dart';
import '../data/sync/cloud_sync_providers.dart';

/// What the AI page's controls do (Android AiSettingsActions): the stored
/// settings in the app, local state in the Tips demo.
abstract interface class AiSettingsActions {
  void setProvider(AiProvider provider);
  void setTier(AiTier tier);

  /// The key for [provider], as typed.
  void setKey(AiProvider provider, String key);
  void setCustomFormat(AiApiFormat format);
  void setCustomBaseUrl(String url);
  void setCustomModel(String model);
  void setCustomThinking(AiThinking thinking);
  void setSearchService(AiSearchService service);

  /// The key for search [service], as typed. Each service keeps its own.
  void setSearchKey(AiSearchService service, String key);
  void setTargetLanguage(String language);

  /// 「拉取」: ask the Custom endpoint which models it has.
  void pullModels();
}

/// [AiSettingsActions] over the stored settings ([aiSettingsProvider]) and
/// the page's model list.
final class StoredAiSettingsActions implements AiSettingsActions {
  const StoredAiSettingsActions(this._ref);

  final WidgetRef _ref;

  AiSettingsNotifier get _settings => _ref.read(aiSettingsProvider.notifier);

  @override
  void setProvider(AiProvider provider) => _settings.setProvider(provider);

  @override
  void setTier(AiTier tier) => _settings.setTier(tier);

  @override
  void setKey(AiProvider provider, String key) => _settings.setKey(provider, key);

  @override
  void setCustomFormat(AiApiFormat format) {
    _settings.setCustomFormat(format);
    // The list was the other format's answer.
    _ref.read(aiModelListProvider.notifier).reset();
  }

  @override
  void setCustomBaseUrl(String url) => _settings.setCustomBaseUrl(url);

  @override
  void setCustomModel(String model) => _settings.setCustomModel(model);

  @override
  void setCustomThinking(AiThinking thinking) => _settings.setCustomThinking(thinking);

  @override
  void setSearchService(AiSearchService service) => _settings.setSearchService(service);

  @override
  void setSearchKey(AiSearchService service, String key) => _settings.setSearchKey(service, key);

  @override
  void setTargetLanguage(String language) {
    final before = _ref.read(aiSettingsProvider).value?.targetLanguage;
    // Read before the write: the page (and its WidgetRef) may be gone by the time it lands.
    SongAboutStore? about;
    AlbumAboutStore? albumAbout;
    try {
      about = _ref.read(songAboutStoreProvider);
      albumAbout = _ref.read(albumAboutStoreProvider);
    } catch (_) {
      // No app storage (a harness): nothing to clear.
    }
    unawaited(
      _settings.setTargetLanguage(language).then((_) async {
        // Answers the AI wrote in the old language would linger otherwise (AiSettingsViewModel
        // clearGeneratedAbout); the listener's own About entries stay.
        if (before != null && AiLanguages.normalize(before) != AiLanguages.normalize(language)) {
          await about?.deleteGenerated();
          // Every album About row is the AI's (AiSettingsViewModel: albumAboutEntryDao().deleteAll()).
          await albumAbout?.deleteAll();
        }
      }),
    );
  }

  @override
  void pullModels() => _ref.read(aiModelListProvider.notifier).pull();
}

/// The model ids 「拉取」 brought back (ModelList).
@immutable
final class AiModelList {
  const AiModelList({this.ids = const [], this.loading = false, this.error});

  final List<String> ids;
  final bool loading;
  final String? error;
}

/// Custom's model list for as long as the AI page is open.
class AiModelListNotifier extends Notifier<AiModelList> {
  int _run = 0;

  @override
  AiModelList build() => const AiModelList();

  void reset() {
    _run++;
    state = const AiModelList();
  }

  /// Only lists them. The model is the user's to pick: the first id of an
  /// aggregator's hundreds is nobody's choice, and it would start billing the key.
  Future<void> pull() async {
    final ai = ref.read(aiSettingsProvider).value;
    if (ai == null || ai.customBaseUrl.trim().isEmpty || ai.customKey.trim().isEmpty) return;
    final run = ++_run;
    state = const AiModelList(loading: true);
    AiModelList next;
    try {
      next = AiModelList(ids: await ref.read(aiServiceProvider).listCustomModels(ai));
    } catch (e) {
      next = AiModelList(error: e is AiException ? e.message : '$e');
    }
    if (ref.mounted && run == _run) state = next;
  }
}

final aiModelListProvider = NotifierProvider.autoDispose<AiModelListNotifier, AiModelList>(AiModelListNotifier.new);

/// A song for 「拿一首歌试试」 (TestSong).
@immutable
final class AiTrySong {
  const AiTrySong(this.title, this.artist, this.album);

  final String title;
  final String artist;
  final String album;

  @override
  bool operator ==(Object other) =>
      other is AiTrySong && other.title == title && other.artist == artist && other.album == album;

  @override
  int get hashCode => Object.hash(title, artist, album);

  @override
  String toString() => 'AiTrySong($title, $artist, $album)';
}

/// One song the test page offers (RecentTestSong): [key] is its song id,
/// [cover] its cover when it has one.
@immutable
final class AiRecentSong {
  const AiRecentSong({required this.key, required this.song, this.cover});

  final MediaId key;
  final AiTrySong song;
  final CoverRef? cover;

  @override
  bool operator ==(Object other) =>
      other is AiRecentSong && other.key == key && other.song == song && other.cover == cover;

  @override
  int get hashCode => Object.hash(key, song, cover);
}

/// How many recently played songs 「拿一首歌试试」 offers (owner 2026-11-14).
const int recentTestSongCount = 6;

/// The test page's songs from the account's plays (recentTestSongs): each
/// song once, at its newest play, newest first, at most [recentTestSongCount].
/// A play without a title is left out.
List<AiRecentSong> recentTestSongs(List<TrackPlays> plays) {
  final rows = plays.where((p) => p.title.trim().isNotEmpty).toList()
    ..sort((a, b) => b.lastPlayedAt.compareTo(a.lastPlayedAt));
  return [
    for (final row in rows.take(recentTestSongCount))
      AiRecentSong(
        key: row.trackId,
        song: AiTrySong(row.title, row.artist, row.album),
        cover: CoverRef.fromStorageKey(row.coverArtKey),
      ),
  ];
}

/// The active account's recent songs for 「拿一首歌试试」 (AiSettingsViewModel
/// recentSongs). Loading until the plays have been read; empty with none. A
/// new play while the page is open brings it to the top.
final aiRecentSongsProvider = FutureProvider.autoDispose<List<AiRecentSong>>((ref) async {
  final MemoryStore? store;
  try {
    store = await ref.watch(memoryStoreProvider.future);
  } catch (_) {
    // No account or no storage (a harness): no history.
    return const [];
  }
  if (store == null) return const [];
  final changes = store.changes.where((c) => c == MemoryStoreChange.plays).listen((_) => ref.invalidateSelf());
  ref.onDispose(changes.cancel);
  return recentTestSongs(await store.trackPlays());
});

/// One run of the test page (TestRun).
sealed class AiTryRun {
  const AiTryRun();
}

final class AiTryIdle extends AiTryRun {
  const AiTryIdle();
}

final class AiTryRunning extends AiTryRun {
  const AiTryRunning(this.song);
  final AiTrySong song;
}

final class AiTryDone extends AiTryRun {
  const AiTryDone({
    required this.song,
    required this.millis,
    required this.fields,
    required this.search,
    required this.queries,
    required this.pages,
    required this.model,
  });

  final AiTrySong song;
  final int millis;
  final Map<String, String> fields;

  /// Who searched: the model, Yoin ([queries], [pages]), or nobody.
  final AiSearchKind search;
  final List<String> queries;
  final int pages;
  final String model;
}

/// [message] null: the chosen service has no key yet.
final class AiTryFailed extends AiTryRun {
  const AiTryFailed(this.song, this.message, this.millis);
  final AiTrySong song;
  final String? message;
  final int millis;
}

/// 「拿一首歌试试」: writes About for one song with the settings as they are,
/// nothing saved. One run at a time: a tap while one is running does nothing.
class AiTryNotifier extends Notifier<AiTryRun> {
  int _run = 0;
  bool _busy = false;

  @override
  AiTryRun build() => const AiTryIdle();

  Future<void> run(AiTrySong song) async {
    if (_busy) return;
    _busy = true;
    try {
      await _runOne(song);
    } finally {
      _busy = false;
    }
  }

  Future<void> _runOne(AiTrySong song) async {
    final run = ++_run;
    final AiSettings ai;
    try {
      ai = await ref.read(aiSettingsProvider.future);
    } catch (_) {
      if (ref.mounted && run == _run) state = AiTryFailed(song, null, 0);
      return;
    }
    if (!ref.mounted || run != _run) return;
    if (!ai.isReady) {
      state = AiTryFailed(song, null, 0);
      return;
    }
    state = AiTryRunning(song);
    final watch = Stopwatch()..start();
    AiTryRun next;
    try {
      final result = await ref
          .read(aiServiceProvider)
          .runAbout(
            ai,
            title: song.title,
            artist: song.artist,
            album: song.album,
            targetLanguage: AiLanguages.normalize(ai.targetLanguage),
          );
      next = AiTryDone(
        song: song,
        millis: watch.elapsedMilliseconds,
        fields: {for (final v in result.values) v.key: v.answer},
        search: result.searchKind,
        queries: result.searchQueries,
        pages: result.pagesRead,
        model: ai.modelId,
      );
    } catch (e) {
      next = AiTryFailed(song, e is AiException ? e.message : '$e', watch.elapsedMilliseconds);
    }
    if (ref.mounted && run == _run) state = next;
  }
}

final aiTryProvider = NotifierProvider.autoDispose<AiTryNotifier, AiTryRun>(AiTryNotifier.new);

/// Opens a "Get a key" page in the system browser (the cloud sync host's
/// browser seam: a view-data Want on HarmonyOS, the shell on Windows).
final aiLinkOpenerProvider = Provider<Future<bool> Function(Uri url)>(
  (ref) => ref.watch(cloudSyncHostProvider).openBrowser,
);
