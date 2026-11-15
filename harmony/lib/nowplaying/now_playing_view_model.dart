import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/lyrics/lrc_parser.dart';
import '../data/lyrics/lyrics_translation.dart';
import '../data/lyrics/user_lyrics_store.dart';
import '../data/model/model.dart';
import '../data/source/music_source.dart';
import '../platform/playback/playback_engine.dart';
import '../playback/local_ratings.dart';
import '../playback/playback_context.dart';
import '../playback/playback_controller.dart';
import 'lyrics_logic.dart';
import '../playback/play_mode.dart';
import 'player_snapshot.dart';
import 'up_next_timing.dart';
import '../theme/tokens.dart';

/// How long the next song waits before its lyrics are fetched ahead (tests
/// override it with zero).
final upNextPrefetchDelayProvider = Provider<Duration>(
  (ref) => const Duration(milliseconds: NowPlayingFocusTokens.upNextPrefetchDelayMs),
);

/// The lyrics of the playing song.
sealed class LyricsState {
  const LyricsState();
}

final class LyricsLoading extends LyricsState {
  const LyricsLoading();
  @override
  bool operator ==(Object other) => other is LyricsLoading;
  @override
  int get hashCode => 1;
}

final class LyricsReady extends LyricsState {
  const LyricsReady(this.sheet);
  final LyricSheet sheet;
  @override
  bool operator ==(Object other) => other is LyricsReady && other.sheet == sheet;
  @override
  int get hashCode => sheet.hashCode;
}

/// Now Playing's UI state (Android NowPlayingUiState, Subsonic subset).
/// Positions never live here: they come from the playback clock.
sealed class NowPlayingUiState {
  const NowPlayingUiState();
}

/// The queue emptied while the player was open (transient).
final class NowPlayingIdle extends NowPlayingUiState {
  const NowPlayingIdle();
  @override
  bool operator ==(Object other) => other is NowPlayingIdle;
  @override
  int get hashCode => 0;
}

@immutable
final class NowPlayingContent extends NowPlayingUiState {
  const NowPlayingContent({
    required this.track,
    required this.isPlaying,
    required this.durationMs,
    required this.rating,
    required this.isStarred,
    required this.canFavorite,
    required this.canRate,
    required this.lyrics,
    required this.queue,
    required this.currentIndex,
    required this.upcoming,
    required this.playMode,
    required this.context,
    required this.isWavePlaying,
    this.upNext,
    this.showTranslation = false,
    this.translating = false,
  });

  /// The lyric translation layer shows under each line (Android showLyricsTranslation; off after a song change).
  final bool showTranslation;

  /// A translation is being fetched (Android lyricsActionInFlight == Translate).
  final bool translating;

  final Track track;

  /// The EAGER play intent (playWhenReady): what PLAY|PAUSE shows.
  final bool isPlaying;

  /// Audio is actually moving (waves travel, the hero breathes).
  final bool isWavePlaying;
  final int? durationMs;

  /// Local 0.0–10.0 rating, null = unrated.
  final double? rating;
  final bool isStarred;
  final bool canFavorite;
  final bool canRate;
  final LyricsState lyrics;
  final List<Track> queue;
  final int currentIndex;
  final List<int> upcoming;
  final PlayMode playMode;
  final PlaybackContext context;

  /// The next song's lyrics, fetched ahead for the outro hand-over.
  final UpNextLyrics? upNext;

  String get songKey => track.id.toString();

  @override
  bool operator ==(Object other) =>
      other is NowPlayingContent &&
      identical(other.track, track) &&
      other.isPlaying == isPlaying &&
      other.isWavePlaying == isWavePlaying &&
      other.durationMs == durationMs &&
      other.rating == rating &&
      other.isStarred == isStarred &&
      other.canFavorite == canFavorite &&
      other.canRate == canRate &&
      other.lyrics == lyrics &&
      listEquals(other.queue, queue) &&
      other.currentIndex == currentIndex &&
      listEquals(other.upcoming, upcoming) &&
      other.playMode == playMode &&
      identical(other.context, context) &&
      other.upNext == upNext &&
      other.showTranslation == showTranslation &&
      other.translating == translating;

  @override
  int get hashCode => Object.hash(
    track,
    isPlaying,
    isWavePlaying,
    durationMs,
    rating,
    isStarred,
    canFavorite,
    canRate,
    lyrics,
    Object.hashAll(queue),
    currentIndex,
    Object.hashAll(upcoming),
    playMode,
    context,
    upNext,
    showTranslation,
    translating,
  );
}

/// What the Translate key did (Android translateLyrics' outcomes, NetEase only).
enum LyricsTranslateOutcome {
  /// The song already had translations: the layer was shown or hidden.
  toggled,

  /// NetEase's translation arrived and shows.
  translated,

  /// The lines already read as Chinese (Android AlreadyTargetLanguage).
  alreadyChinese,

  /// NetEase has no translation that lines up with these lyrics, or the lookup failed.
  failed,

  /// Nothing to do (no lyrics, busy, the song moved on).
  ignored,
}

/// Now Playing's view model (Android NowPlayingViewModel): projects the
/// player snapshot, fetches lyrics per song (cancelled on skip, cached for
/// the last three songs), keeps the optimistic heart and the local 0–10
/// rating, and forwards transport commands to the [PlaybackController].
class NowPlayingViewModel extends Notifier<NowPlayingUiState> {
  final Map<String, LyricSheet> _lyricsCache = {};
  final List<String> _lyricsOrder = [];
  String? _lyricsFetchFor;
  int _lyricsGeneration = 0;

  /// The next song whose lyrics are being fetched ahead, and its wait. A
  /// rebuild (or disposal) cancels a wait that hasn't fired; the rebuild
  /// schedules it again.
  String? _prefetchFor;
  Timer? _prefetchTimer;

  PlaybackController get _controller => ref.read(playbackControllerProvider.notifier);

  /// The song whose translation layer shows, and the one being translated.
  String? _showTranslationFor;
  String? _translatingFor;
  String? _lastSongKey;

  /// NetEase song ids of lyrics the user applied from NetEase (song key → id).
  final Map<String, String> _netEaseIds = {};

  @override
  NowPlayingUiState build() {
    final snapshot = ref.watch(playerSnapshotProvider);
    final source = ref.watch(activeSourceProvider).value;
    final track = snapshot.track;
    if (track == null) return const NowPlayingIdle();
    final key = track.id.toString();
    final caps = source?.capabilities ?? const <Capability>{};
    // A song change turns the translation layer off (Android resets showLyricsTranslation).
    if (key != _lastSongKey) {
      _lastSongKey = key;
      _showTranslationFor = null;
    }

    ref.onDispose(() {
      if (_prefetchTimer?.isActive ?? false) {
        _prefetchTimer!.cancel();
        _prefetchFor = null;
      }
    });
    final cached = _lyricsCache[key];
    // No source yet (still loading): wait for it, build re-runs when it lands.
    if (cached == null && _lyricsFetchFor != key && source != null) {
      _fetchLyrics(track, source, caps);
    }
    final next = snapshot.upcoming.isEmpty ? null : _at(snapshot.queue, snapshot.upcoming.first);
    final upNext = _upNextFor(next, source, caps);
    return NowPlayingContent(
      track: track,
      isPlaying: snapshot.playWhenReady,
      isWavePlaying: snapshot.isPlaying,
      durationMs: snapshot.durationMs,
      // Local ratings only (local_ratings, per account): Android never seeds
      // the slider from the server's userRating, so an unrated song shows an
      // empty bar and no number.
      rating: ref.watch(localRatingsProvider.select((m) => m[track.id]?.rating)),
      // One heart owner (favoriteOverridesProvider): album rows, the system
      // heart and this one stay in step.
      isStarred: ref.watch(favoriteOverridesProvider.select((m) => m[track.id])) ?? track.isStarred,
      canFavorite: caps.contains(Capability.favorites),
      canRate: caps.contains(Capability.rating),
      lyrics: cached == null ? const LyricsLoading() : LyricsReady(cached),
      queue: snapshot.queue,
      currentIndex: snapshot.index,
      upcoming: snapshot.upcoming,
      playMode: snapshot.playMode,
      context: snapshot.context,
      upNext: upNext,
      showTranslation: _showTranslationFor == key,
      translating: _translatingFor == key,
    );
  }

  static Track? _at(List<Track> queue, int index) => index >= 0 && index < queue.length ? queue[index] : null;

  /// The next song's prefetched lyrics, or null — and a fetch ahead
  /// scheduled when they aren't cached yet (Android _prefetchedLyrics:
  /// wait, then try up to three times; the outro is minutes away).
  UpNextLyrics? _upNextFor(Track? next, MusicSource? source, Set<Capability> caps) {
    if (next == null) {
      _prefetchTimer?.cancel();
      _prefetchFor = null;
      return null;
    }
    final key = next.id.toString();
    final sheet = _lyricsCache[key];
    if (sheet != null) {
      return sheet.isEmpty
          ? null
          : UpNextLyrics(songKey: key, title: next.title ?? '', artist: next.artist ?? '', sheet: sheet);
    }
    if (_prefetchFor != key && source != null && caps.contains(Capability.lyrics)) {
      _prefetchTimer?.cancel();
      _prefetchFor = key;
      _schedulePrefetch(next, source, key, attempt: 0, delay: ref.read(upNextPrefetchDelayProvider));
    }
    return null;
  }

  void _schedulePrefetch(Track next, MusicSource source, String key, {required int attempt, required Duration delay}) {
    _prefetchTimer = Timer(delay, () async {
      if (!ref.mounted || _prefetchFor != key) return;
      LyricSheet? sheet;
      try {
        sheet = lyricSheetOf(await source.metadata.getLyrics(next));
      } catch (_) {
        if (!ref.mounted || _prefetchFor != key) return;
        if (attempt < NowPlayingFocusTokens.upNextPrefetchRetries) {
          _schedulePrefetch(
            next,
            source,
            key,
            attempt: attempt + 1,
            delay: Duration(milliseconds: NowPlayingFocusTokens.upNextPrefetchRetryMs * (attempt + 1)),
          );
        }
        return;
      }
      if (!ref.mounted) return;
      _cacheLyrics(key, sheet);
      // Landed: an eviction later may fetch it again.
      if (_prefetchFor == key) _prefetchFor = null;
      final current = state;
      if (current is NowPlayingContent && !sheet.isEmpty) {
        final upcoming = current.upcoming.isEmpty ? null : _at(current.queue, current.upcoming.first);
        if (upcoming?.id.toString() == key) {
          state = _copy(
            current,
            upNext: UpNextLyrics(
              songKey: key,
              title: upcoming!.title ?? '',
              artist: upcoming.artist ?? '',
              sheet: sheet,
            ),
          );
        }
      }
    });
  }

  void _cacheLyrics(String key, LyricSheet sheet) {
    _lyricsCache[key] = sheet;
    _lyricsOrder
      ..remove(key)
      ..add(key);
    while (_lyricsOrder.length > 3) {
      _lyricsCache.remove(_lyricsOrder.removeAt(0));
    }
  }

  void _fetchLyrics(Track track, MusicSource source, Set<Capability> caps) {
    final key = track.id.toString();
    _lyricsFetchFor = key;
    final generation = ++_lyricsGeneration;
    final userStore = ref.read(userLyricsStoreProvider);
    if (!caps.contains(Capability.lyrics) && userStore == null) {
      // Done synchronously would mutate state during build: finish next microtask.
      scheduleMicrotask(() => _landLyrics(generation, key, LyricSheet.empty));
      return;
    }
    () async {
      // The user's own pick first: it wins over the server's lyrics (Android's
      // load order — the chosen lyrics_cache row, then the source).
      try {
        final chosen = await userStore?.read(key);
        if (chosen != null) {
          final id = chosen.providerSongId;
          if (chosen.provider == 'netease' && id != null) _netEaseIds[key] = id;
          _landLyrics(generation, key, lyricSheetOf(LrcParser.parse(chosen.lrc)));
          return;
        }
      } catch (e) {
        debugPrint('user lyrics read failed: ${e.runtimeType}');
      }
      if (!caps.contains(Capability.lyrics)) {
        _landLyrics(generation, key, LyricSheet.empty);
        return;
      }
      LyricSheet sheet;
      try {
        sheet = lyricSheetOf(await source.metadata.getLyrics(track));
      } catch (_) {
        sheet = LyricSheet.empty;
      }
      _landLyrics(generation, key, sheet);
    }();
  }

  /// Applies lyrics the user chose for the playing song (Android
  /// YoinRepository.applyLyrics / applyLyricsSearchResult): typed or pasted
  /// ([UserLyrics.manualProvider]) or a search result's. They are stored as
  /// the user's pick for this song and shown at once; false when there is
  /// nothing to apply or the song moved on.
  Future<bool> applyLyrics(String rawLrc, {String provider = UserLyrics.manualProvider, String? providerSongId}) async {
    final current = state;
    final trimmed = rawLrc.trim();
    if (current is! NowPlayingContent || trimmed.isEmpty) return false;
    final key = current.songKey;
    final sheet = lyricSheetOf(LrcParser.parse(trimmed));
    // Whatever was still loading for this song is superseded.
    _lyricsGeneration++;
    _lyricsFetchFor = null;
    try {
      await ref
          .read(userLyricsStoreProvider)
          ?.write(key, UserLyrics(lrc: trimmed, provider: provider, providerSongId: providerSongId));
    } catch (e) {
      debugPrint('user lyrics write failed: ${e.runtimeType}');
      return false;
    }
    if (!ref.mounted) return false;
    if (provider == 'netease' && providerSongId != null) {
      _netEaseIds[key] = providerSongId;
    } else {
      _netEaseIds.remove(key);
    }
    _cacheLyrics(key, sheet);
    if (_showTranslationFor == key) _showTranslationFor = null;
    final now = state;
    if (now is! NowPlayingContent || now.songKey != key) return false;
    state = _copy(now, lyrics: LyricsReady(sheet), showTranslation: false);
    return true;
  }

  /// The Translate key (Android NowPlayingViewModel.translateLyrics), with
  /// NetEase's own translations as the only source for now: a song that
  /// already has them shows / hides the layer; otherwise NetEase is asked —
  /// by the NetEase id when the lyrics shown came from NetEase, else by
  /// title + artist — its translation is put onto these lines by timestamp,
  /// kept for the song, and shown.
  Future<LyricsTranslateOutcome> translateLyrics() async {
    final current = state;
    if (current is! NowPlayingContent || current.lyrics is! LyricsReady || _translatingFor != null) {
      return LyricsTranslateOutcome.ignored;
    }
    final key = current.songKey;
    final sheet = (current.lyrics as LyricsReady).sheet;
    if (sheet.isEmpty) return LyricsTranslateOutcome.ignored;
    if (sheet.rows.any((r) => r.translation?.trim().isNotEmpty ?? false)) {
      _showTranslationFor = _showTranslationFor == key ? null : key;
      state = _copy(current, showTranslation: _showTranslationFor == key);
      return LyricsTranslateOutcome.toggled;
    }
    final texts = [for (final r in sheet.rows) r.text];
    if (lyricsAppearMostlyChinese(texts)) return LyricsTranslateOutcome.alreadyChinese;

    _translatingFor = key;
    state = _copy(current, translating: true);
    Map<int, String> translations = const {};
    try {
      final store = ref.read(lyricsTranslationStoreProvider);
      translations = await store?.read(key, texts) ?? const {};
      if (translations.isEmpty) {
        translations = await ref
            .read(netEaseTranslatorProvider)
            .translate(
              lines: [for (final r in sheet.rows) (startMs: r.startMs, text: r.text)],
              title: current.track.title ?? '',
              artist: current.track.artist ?? '',
              durationMs: current.durationMs,
              providerSongId: _netEaseIds[key],
            );
        if (translations.isNotEmpty) await store?.write(key, texts, translations);
      }
    } catch (e) {
      debugPrint('translate failed: ${e.runtimeType}');
      translations = const {};
    }
    if (!ref.mounted) return LyricsTranslateOutcome.ignored;
    if (_translatingFor == key) _translatingFor = null;
    final now = state;
    if (now is! NowPlayingContent || now.songKey != key) return LyricsTranslateOutcome.ignored;
    final nowSheet = now.lyrics is LyricsReady ? (now.lyrics as LyricsReady).sheet : null;
    if (translations.isEmpty || nowSheet != sheet) {
      state = _copy(now, translating: false);
      return translations.isEmpty ? LyricsTranslateOutcome.failed : LyricsTranslateOutcome.ignored;
    }
    final translated = LyricSheet([
      for (var i = 0; i < sheet.rows.length; i++)
        LyricRow(sheet.rows[i].text, startMs: sheet.rows[i].startMs, translation: translations[i]),
    ]);
    _cacheLyrics(key, translated);
    _showTranslationFor = key;
    state = _copy(now, lyrics: LyricsReady(translated), translating: false, showTranslation: true);
    return LyricsTranslateOutcome.translated;
  }

  void _landLyrics(int generation, String key, LyricSheet sheet) {
    if (!ref.mounted) return;
    _cacheLyrics(key, sheet);
    // A skip moved on: keep the sheet cached, but don't touch the state.
    if (generation != _lyricsGeneration) return;
    _lyricsFetchFor = null;
    final current = state;
    if (current is NowPlayingContent && current.songKey == key) {
      state = _copy(current, lyrics: LyricsReady(sheet));
    }
  }

  // Transport ----------------------------------------------------------------

  /// PLAY|PAUSE toggles on the intent, not on isPlaying, so a buffering dip
  /// doesn't flip it. PLAY after the queue ended restarts it (controller).
  Future<void> togglePlayPause() {
    final current = state;
    if (current is NowPlayingContent && current.isPlaying) return _controller.pause();
    return _controller.play();
  }

  Future<void> next() => _controller.next();
  Future<void> previous() => _controller.previous();
  Future<void> skipTo(int index) => _controller.skipTo(index);

  Future<void> seekToFraction(double fraction) {
    final current = state;
    final duration = current is NowPlayingContent ? current.durationMs : null;
    if (duration == null || duration <= 0) return Future.value();
    return _controller.seekTo((fraction.clamp(0.0, 1.0) * duration).round());
  }

  Future<void> seekTo(int positionMs) => _controller.seekTo(positionMs);

  Future<void> cyclePlayMode() async {
    final current = state;
    if (current is! NowPlayingContent) return;
    await setPlayMode(current.playMode.next());
  }

  /// The play-mode button's choice (a round tap, or a split segment's axis).
  Future<void> setPlayMode(PlayMode mode) async {
    if (state is! NowPlayingContent) return;
    try {
      await _controller.setMode(mode);
    } catch (e) {
      debugPrint('setPlayMode failed: $e');
    }
  }

  /// The heart: the controller writes it through favoriteOverridesProvider,
  /// which flips it at once everywhere and rolls it back if the write fails.
  Future<void> toggleFavorite() async {
    final current = state;
    if (current is! NowPlayingContent || !current.canFavorite) return;
    try {
      await _controller.toggleFavorite();
    } catch (e) {
      debugPrint('toggleFavorite failed: $e');
    }
  }

  /// Commits a rating from the slider (on lift): stored locally 0.0–10.0
  /// for this account (survives restarts), then pushed to the server as
  /// round(r / 2) stars; a push that fails is retried later (LocalRatings).
  Future<void> commitRating(double rating) async {
    final current = state;
    if (current is! NowPlayingContent) return;
    final value = (rating * 10).round() / 10;
    state = _copy(current, rating: value);
    try {
      await ref.read(localRatingsProvider.notifier).setRating(current.track.id, value);
    } catch (e) {
      debugPrint('setRating failed: $e');
    }
  }

  NowPlayingContent _copy(
    NowPlayingContent c, {
    LyricsState? lyrics,
    bool? isStarred,
    double? rating,
    UpNextLyrics? upNext,
    bool? showTranslation,
    bool? translating,
  }) => NowPlayingContent(
    track: c.track,
    isPlaying: c.isPlaying,
    isWavePlaying: c.isWavePlaying,
    durationMs: c.durationMs,
    rating: rating ?? c.rating,
    isStarred: isStarred ?? c.isStarred,
    canFavorite: c.canFavorite,
    canRate: c.canRate,
    lyrics: lyrics ?? c.lyrics,
    queue: c.queue,
    currentIndex: c.currentIndex,
    upcoming: c.upcoming,
    playMode: c.playMode,
    context: c.context,
    upNext: upNext ?? c.upNext,
    showTranslation: showTranslation ?? c.showTranslation,
    translating: translating ?? c.translating,
  );
}

final nowPlayingViewModelProvider = NotifierProvider<NowPlayingViewModel, NowPlayingUiState>(NowPlayingViewModel.new);

/// Whether the engine reports an error with nothing to show (pill text).
bool isPlaybackUnavailable(PlayerSnapshot s) => !s.hasTrack && (s.phase == PlaybackPhase.error || s.errorKind != null);
