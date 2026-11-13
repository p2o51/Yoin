import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/model/model.dart';
import '../data/source/music_source.dart';
import '../platform/playback/playback_engine.dart';
import 'now_playing_state.dart';

/// The one way the app plays music (Android PlaybackManager). Screens call
/// these methods; nothing else talks to [PlaybackEngine].
///
/// The native engine owns the queue the system controls (lock screen,
/// 播控中心, headset keys) so playback outlives the UI; this mirrors it as
/// [Track]s and turns each into an [EngineItem] through the active
/// [MusicSource] (stream URL, artwork URL).
class PlaybackController extends Notifier<NowPlayingState> {
  StreamSubscription<EngineState>? _engineStates;

  PlaybackEngine get _engine => ref.read(playbackEngineProvider);

  @override
  NowPlayingState build() {
    _engineStates = _engine.states.listen(_onEngineState);
    ref.onDispose(() => _engineStates?.cancel());
    return NowPlayingState.idle;
  }

  /// Replaces the queue with [tracks] and plays from [startIndex].
  Future<void> playTracks(List<Track> tracks, {int startIndex = 0}) async {
    if (tracks.isEmpty) return;
    final source = await ref.read(activeSourceProvider.future);
    if (source == null) return;
    final start = startIndex.clamp(0, tracks.length - 1);
    final items = <EngineItem>[];
    final playable = <Track>[];
    for (final track in tracks) {
      final item = await _itemFor(source, track);
      if (item == null) continue;
      items.add(item);
      playable.add(track);
    }
    if (items.isEmpty) return;
    final playableStart = playable.indexOf(tracks[start]).clamp(0, playable.length - 1);
    state = state.copyWith(
      queue: playable,
      index: playableStart,
      phase: PlaybackPhase.loading,
      isPlaying: true,
      positionMs: 0,
      sampledAtMs: DateTime.now().millisecondsSinceEpoch,
      clearDuration: true,
      clearError: true,
    );
    await _engine.setQueue(items, startIndex: playableStart);
  }

  Future<void> play() => _engine.play();
  Future<void> pause() => _engine.pause();
  Future<void> togglePlayPause() => state.isPlaying ? pause() : play();
  Future<void> next() => _engine.next();
  Future<void> previous() => _engine.previous();
  Future<void> skipTo(int index) => _engine.skipTo(index);

  Future<void> seekTo(int positionMs) {
    // Show the new position at once; the engine's next sample confirms it.
    state = state.copyWith(positionMs: positionMs, sampledAtMs: DateTime.now().millisecondsSinceEpoch);
    return _engine.seekTo(positionMs);
  }

  Future<void> stop() => _engine.stop();

  Future<EngineItem?> _itemFor(MusicSource source, Track track) async {
    final handle = await source.playback.handleFor(track);
    if (handle is! DirectStream) return null; // External controllers arrive with Spotify.
    final cover = track.coverArt;
    return EngineItem(
      id: track.id.toString(),
      uri: handle.uri,
      headers: handle.headers,
      title: track.title ?? '',
      artist: track.artist,
      album: track.album,
      artworkUri: cover == null ? null : source.resolveCoverUrl(cover, size: 600),
      durationMs: track.durationSec == null ? null : track.durationSec! * 1000,
    );
  }

  void _onEngineState(EngineState engine) {
    var index = engine.index;
    // Trust the id over the index when they disagree (the native queue is authoritative).
    if (engine.currentId != null) {
      final byId = state.queue.indexWhere((t) => t.id.toString() == engine.currentId);
      if (byId >= 0) index = byId;
    }
    state = state.copyWith(
      index: index,
      phase: engine.phase,
      isPlaying: engine.isPlaying,
      positionMs: engine.positionMs,
      sampledAtMs: engine.sampledAtMs,
      durationMs: engine.durationMs,
      clearDuration: engine.durationMs == null,
      errorMessage: engine.errorMessage,
      clearError: engine.errorMessage == null,
    );
  }
}

final playbackControllerProvider = NotifierProvider<PlaybackController, NowPlayingState>(PlaybackController.new);
