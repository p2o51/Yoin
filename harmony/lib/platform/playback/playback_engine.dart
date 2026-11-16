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
    this.localUri,
    required this.title,
    this.artist,
    this.album,
    this.artworkUri,
    this.durationMs,
    this.entryId,
    this.extra,
  });

  /// [MediaId] string, echoed back in [EngineState.currentId].
  final String id;
  final String uri;
  final Map<String, String> headers;

  /// A downloaded copy (`file://`, offline downloads) the native player opens
  /// in place of [uri] while the file is there; [uri] streams when it has
  /// gone (removed after the queue was built), as Android's
  /// OfflineFirstDataSource decides as the song opens.
  final String? localUri;
  final String title;
  final String? artist;
  final String? album;
  final String? artworkUri;
  final int? durationMs;

  /// Stable per queue entry (the same song queued twice is two entries);
  /// echoed back in [EngineState.currentEntryId].
  final String? entryId;

  /// Opaque to the native side (the full Track as JSON), echoed by
  /// [QueuePlaybackEngine.snapshot] so a re-created UI can rebuild its queue.
  final String? extra;

  Map<String, Object?> toMap() => {
        'id': id,
        'uri': uri,
        'headers': headers,
        if (localUri != null) 'localUri': localUri,
        'title': title,
        'artist': artist,
        'album': album,
        'artworkUri': artworkUri,
        'durationMs': durationMs,
        'entryId': entryId,
        'extra': extra,
      };

  factory EngineItem.fromMap(Map<Object?, Object?> map) => EngineItem(
        id: map['id']! as String,
        uri: (map['uri'] as String?) ?? '',
        title: (map['title'] as String?) ?? '',
        artist: map['artist'] as String?,
        album: map['album'] as String?,
        artworkUri: map['artworkUri'] as String?,
        durationMs: _int(map['durationMs']),
        entryId: map['entryId'] as String?,
        extra: map['extra'] as String?,
      );
}

enum PlaybackPhase { idle, loading, ready, ended, error }

/// The engine's repeat axis (Media3 OFF / ONE / ALL). Sent by name.
enum EngineRepeat { off, one, all }

/// A snapshot from the native side. Position is a sample: extrapolate with
/// `positionMs + (now - sampledAtMs)` while [isPlaying] (Android keeps
/// positionMs out of UI state for the same reason).
///
/// Fields after [errorMessage] are only sent by the v1 engine
/// ([QueuePlaybackEngine]); null means "not reported".
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
    this.playWhenReady,
    this.repeat,
    this.shuffle,
    this.revision,
    this.repeatPlays = 0,
    this.order,
    this.currentEntryId,
    this.errorCode,
    this.tick = false,
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

  /// The user's intent, false while idle / ended / suppressed by a transient
  /// focus loss (Android playWhenReadyOf).
  final bool? playWhenReady;
  final EngineRepeat? repeat;
  final bool? shuffle;

  /// The queue revision this state describes (assigned by Dart with each
  /// queue change); an event older than Dart's own revision is stale.
  final int? revision;

  /// How many times the current entry auto-repeated (repeat one).
  final int repeatPlays;

  /// Play order as queue indices; sent when the queue or shuffle changes,
  /// absent on position ticks.
  final List<int>? order;
  final String? currentEntryId;
  final int? errorCode;

  /// A pure position sample (nothing else changed).
  final bool tick;

  factory EngineState.fromMap(Map<Object?, Object?> map) => EngineState(
        phase: PlaybackPhase.values.byName(map['phase']! as String),
        isPlaying: map['isPlaying']! as bool,
        index: _int(map['index']) ?? -1,
        currentId: map['currentId'] as String?,
        positionMs: _int(map['positionMs']) ?? 0,
        sampledAtMs: _int(map['sampledAtMs']) ?? 0,
        durationMs: _int(map['durationMs']),
        errorMessage: map['errorMessage'] as String?,
        playWhenReady: map['playWhenReady'] as bool?,
        repeat: switch (map['repeat']) {
          final String name => EngineRepeat.values.asNameMap()[name],
          _ => null,
        },
        shuffle: map['shuffle'] as bool?,
        revision: _int(map['revision']),
        repeatPlays: _int(map['repeatPlays']) ?? 0,
        order: switch (map['order']) {
          final List<Object?> list => [for (final i in list) _int(i) ?? 0],
          _ => null,
        },
        currentEntryId: map['currentEntryId'] as String?,
        errorCode: _int(map['errorCode']),
        tick: (map['tick'] as bool?) ?? false,
      );
}

/// What the native host still holds (the UI was re-created while the
/// process, and maybe the music, lived on).
final class EngineSnapshot {
  const EngineSnapshot({required this.items, required this.state});

  final List<EngineItem> items;
  final EngineState state;

  factory EngineSnapshot.fromMap(Map<Object?, Object?> map) => EngineSnapshot(
        items: [
          for (final item in (map['items'] as List<Object?>?) ?? const [])
            if (item is Map) EngineItem.fromMap(item),
        ],
        state: EngineState.fromMap((map['state']! as Map).cast<Object?, Object?>()),
      );
}

/// Something the system asked for that the native side can't answer alone.
sealed class EngineEvent {
  const EngineEvent();

  /// null for an unknown event type (newer native side).
  static EngineEvent? fromMap(Map<Object?, Object?> map) => switch (map['type']) {
        'openNowPlaying' => const OpenNowPlayingEvent(),
        'networkAvailable' => const NetworkAvailableEvent(),
        'command' => switch (map['name']) {
            'toggleFavorite' => ToggleFavoriteCommand(map['id'] as String?),
            _ => null,
          },
        _ => null,
      };
}

/// The user tapped Yoin's card in 播控中心 / the lock screen / the
/// continuous-task notification.
final class OpenNowPlayingEvent extends EngineEvent {
  const OpenNowPlayingEvent();
}

/// A network became usable (ArkTS `NetConnection` 'netAvailable'; Android
/// YoinApplication's ConnectivityManager onAvailable): plays queued offline
/// can go out now.
final class NetworkAvailableEvent extends EngineEvent {
  const NetworkAvailableEvent();
}

/// The system heart was tapped; [id] is the item's MediaId string.
final class ToggleFavoriteCommand extends EngineEvent {
  const ToggleFavoriteCommand(this.id);
  final String? id;
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

/// The v1 engine contract: queue edits, play mode, the session heart, the
/// host snapshot and system events. Kept apart from [PlaybackEngine] so
/// simpler engines (test fakes) still satisfy the base contract.
///
/// Every queue change carries a [revision] chosen by Dart; the engine echoes
/// the latest one in [EngineState.revision].
abstract interface class QueuePlaybackEngine implements PlaybackEngine {
  /// System requests (open Now Playing, the session heart) and system news
  /// (a network came up).
  Stream<EngineEvent> get events;

  Future<void> startQueue(
    List<EngineItem> items, {
    required int revision,
    int startIndex = 0,
    int startPositionMs = 0,
    bool playWhenReady = true,
    EngineRepeat repeat = EngineRepeat.all,
    bool shuffle = false,
  });

  /// Inserts [items] before queue index [at] (== length appends).
  Future<void> insert(int at, List<EngineItem> items, {required int revision});
  Future<void> removeAt(int index, {required int revision});
  Future<void> move(int from, int to, {required int revision});
  Future<void> setRepeat(EngineRepeat repeat);
  Future<void> setShuffle(bool shuffle);

  /// The session heart for the item [id]; [favorite] null hides it.
  Future<void> setFavorite(String id, bool? favorite);

  /// What the native host holds now; null when it can't answer.
  Future<EngineSnapshot?> snapshot();
}

int? _int(Object? v) => switch (v) {
      final int i => i,
      final double d => d.round(),
      _ => null,
    };
