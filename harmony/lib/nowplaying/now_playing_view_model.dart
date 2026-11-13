import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/model/model.dart';
import '../data/source/music_source.dart';
import '../platform/playback/playback_engine.dart';
import '../playback/playback_context.dart';
import '../playback/playback_controller.dart';
import 'lyrics_logic.dart';
import '../playback/play_mode.dart';
import 'player_snapshot.dart';

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
  });

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
      identical(other.context, context);

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
  );
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

  /// Local ratings 0.0–10.0 by song (in memory until a ratings store lands).
  final Map<String, double> _ratings = {};

  PlaybackController get _controller => ref.read(playbackControllerProvider.notifier);

  @override
  NowPlayingUiState build() {
    final snapshot = ref.watch(playerSnapshotProvider);
    final source = ref.watch(activeSourceProvider).value;
    final track = snapshot.track;
    if (track == null) return const NowPlayingIdle();
    final key = track.id.toString();
    final caps = source?.capabilities ?? const <Capability>{};

    final cached = _lyricsCache[key];
    // No source yet (still loading): wait for it, build re-runs when it lands.
    if (cached == null && _lyricsFetchFor != key && source != null) {
      _fetchLyrics(track, source, caps);
    }
    return NowPlayingContent(
      track: track,
      isPlaying: snapshot.playWhenReady,
      isWavePlaying: snapshot.isPlaying,
      durationMs: snapshot.durationMs,
      // Local ratings only: Android never seeds the slider from the server's
      // userRating, so an unrated song shows an empty bar and no number.
      rating: _ratings[key],
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
    );
  }

  void _fetchLyrics(Track track, MusicSource source, Set<Capability> caps) {
    final key = track.id.toString();
    _lyricsFetchFor = key;
    final generation = ++_lyricsGeneration;
    if (!caps.contains(Capability.lyrics)) {
      // Done synchronously would mutate state during build: finish next microtask.
      scheduleMicrotask(() => _landLyrics(generation, key, LyricSheet.empty));
      return;
    }
    () async {
      LyricSheet sheet;
      try {
        sheet = lyricSheetOf(await source.metadata.getLyrics(track));
      } catch (_) {
        sheet = LyricSheet.empty;
      }
      _landLyrics(generation, key, sheet);
    }();
  }

  void _landLyrics(int generation, String key, LyricSheet sheet) {
    if (!ref.mounted) return;
    _lyricsCache[key] = sheet;
    _lyricsOrder
      ..remove(key)
      ..add(key);
    while (_lyricsOrder.length > 3) {
      _lyricsCache.remove(_lyricsOrder.removeAt(0));
    }
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
    final next = current.playMode.next();
    try {
      await _controller.setMode(next);
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

  /// Commits a rating from the slider (on lift): local 0.0–10.0, pushed to
  /// the server as round(r / 2) stars (YoinRepository.pushRating).
  Future<void> commitRating(double rating) async {
    final current = state;
    if (current is! NowPlayingContent) return;
    final key = current.songKey;
    final value = (rating * 10).round() / 10;
    _ratings[key] = value;
    state = _copy(current, rating: value);
    try {
      await _controller.setRating((value / 2).round().clamp(0, 5));
    } catch (e) {
      // Kept locally; the playback chain retries server sync (spec item 7).
      debugPrint('setRating failed: $e');
    }
  }

  NowPlayingContent _copy(NowPlayingContent c, {LyricsState? lyrics, bool? isStarred, double? rating}) =>
      NowPlayingContent(
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
      );
}

final nowPlayingViewModelProvider = NotifierProvider<NowPlayingViewModel, NowPlayingUiState>(NowPlayingViewModel.new);

/// Whether the engine reports an error with nothing to show (pill text).
bool isPlaybackUnavailable(PlayerSnapshot s) =>
    !s.hasTrack && (s.phase == PlaybackPhase.error || s.errorKind != null);
