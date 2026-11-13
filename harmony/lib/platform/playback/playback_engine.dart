/// The native player (ArkTS AVPlayer + AVSession + background task) as seen
/// from Dart, through `yoin/playback` (methods) and `yoin/playback/events`
/// (state stream). The engine owns the queue the system controls (lock
/// screen, 播控中心, headset keys) so it keeps playing with the UI gone.
library;

/// One queue entry, everything the native side needs without calling back.
final class EngineItem {
  const EngineItem({
    required this.id,
    required this.uri,
    this.headers = const {},
    required this.title,
    this.artist,
    this.album,
    this.artworkUri,
    this.durationMs,
  });

  /// [MediaId] string, echoed back in [EngineState.currentId].
  final String id;
  final String uri;
  final Map<String, String> headers;
  final String title;
  final String? artist;
  final String? album;
  final String? artworkUri;
  final int? durationMs;

  Map<String, Object?> toMap() => {
        'id': id,
        'uri': uri,
        'headers': headers,
        'title': title,
        'artist': artist,
        'album': album,
        'artworkUri': artworkUri,
        'durationMs': durationMs,
      };
}

enum PlaybackPhase { idle, loading, ready, ended, error }

/// A snapshot from the native side. Position is a sample: extrapolate with
/// `positionMs + (now - sampledAtMs)` while [isPlaying] (Android keeps
/// positionMs out of UI state for the same reason).
final class EngineState {
  const EngineState({
    this.phase = PlaybackPhase.idle,
    this.isPlaying = false,
    this.index = -1,
    this.currentId,
    this.positionMs = 0,
    this.sampledAtMs = 0,
    this.durationMs,
    this.errorMessage,
  });

  final PlaybackPhase phase;
  final bool isPlaying;

  /// Index into the queue last set, -1 when empty.
  final int index;
  final String? currentId;
  final int positionMs;

  /// Epoch ms when [positionMs] was read.
  final int sampledAtMs;
  final int? durationMs;
  final String? errorMessage;

  factory EngineState.fromMap(Map<Object?, Object?> map) => EngineState(
        phase: PlaybackPhase.values.byName(map['phase']! as String),
        isPlaying: map['isPlaying']! as bool,
        index: (map['index'] as int?) ?? -1,
        currentId: map['currentId'] as String?,
        positionMs: (map['positionMs'] as int?) ?? 0,
        sampledAtMs: (map['sampledAtMs'] as int?) ?? 0,
        durationMs: map['durationMs'] as int?,
        errorMessage: map['errorMessage'] as String?,
      );
}

abstract interface class PlaybackEngine {
  /// Current state first, then every change (and a position sample about once a second while playing).
  Stream<EngineState> get states;

  /// Replaces the queue and starts [startIndex] at [startPositionMs]; plays if [playWhenReady].
  Future<void> setQueue(List<EngineItem> items, {int startIndex = 0, int startPositionMs = 0, bool playWhenReady = true});
  Future<void> play();
  Future<void> pause();
  Future<void> seekTo(int positionMs);
  Future<void> skipTo(int index);
  Future<void> next();
  Future<void> previous();

  /// Stops playback, ends the background task and the AVSession.
  Future<void> stop();
}
