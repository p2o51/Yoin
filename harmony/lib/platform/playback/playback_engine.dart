/// The native player (ArkTS AVPlayer + AVSession + background task) as seen
/// from Dart, through `yoin/playback` (methods) and `yoin/playback/events`
/// (state stream). The engine owns the queue the system controls (lock
/// screen, 播控中心, headset keys) so it keeps playing with the UI gone.
library;

import '../../data/model/model.dart' show AudioFormat, StreamDelivery;

/// One queue entry, everything the native side needs without calling back.
final class EngineItem {
  const EngineItem({
    required this.id,
    required this.uri,
    this.headers = const {},
    this.localUri,
    this.cacheUri,
    required this.title,
    this.artist,
    this.album,
    this.artworkUri,
    this.durationMs,
    this.entryId,
    this.extra,
    this.gain = 1.0,
    this.delivery,
    this.fileDelivery,
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

  /// The stream cache's loopback address for [uri] (Android CacheDataSource):
  /// the native player streams through it, so the song is stored as it plays
  /// and plays from storage next time. A downloaded [localUri] still comes
  /// first, and [uri] plays when this address fails (the cache's server went
  /// with the Flutter engine).
  final String? cacheUri;
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

  /// Volume leveling: the player's volume for this entry, 0–1 (1 = the file
  /// as it is). Applied by the native side when the entry starts, so a
  /// gapless hand-over changes it with the song.
  final double gain;

  /// What [uri] (and [cacheUri], the same bytes) sends: the file or a
  /// transcode; null when unknown. Opaque to the native side, echoed by
  /// [QueuePlaybackEngine.snapshot] and in [PlayingFormatEvent], so Now
  /// Playing's format tags say what arrives.
  final StreamDelivery? delivery;

  /// What the downloaded [localUri] holds (the quality it was kept at);
  /// null without a download.
  final StreamDelivery? fileDelivery;

  Map<String, Object?> toMap() => {
        'id': id,
        'uri': uri,
        'headers': headers,
        if (localUri != null) 'localUri': localUri,
        if (cacheUri != null) 'cacheUri': cacheUri,
        'title': title,
        'artist': artist,
        'album': album,
        'artworkUri': artworkUri,
        'durationMs': durationMs,
        'entryId': entryId,
        'extra': extra,
        'gain': gain,
        if (delivery != null) 'delivery': delivery!.toMap(),
        if (fileDelivery != null) 'fileDelivery': fileDelivery!.toMap(),
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
        gain: switch (map['gain']) {
          final num n => n.toDouble(),
          _ => 1.0,
        },
        delivery: StreamDelivery.fromMap(map['delivery']),
        fileDelivery: StreamDelivery.fromMap(map['fileDelivery']),
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
        'playingFormat' => PlayingFormatEvent.fromMap(map),
        'trace' => EngineTraceEvent.fromMap(map),
        'command' => switch (map['name']) {
            'toggleFavorite' => ToggleFavoriteCommand(map['id'] as String?),
            final String name when SessionTransportCommand.names.contains(name) =>
              SessionTransportCommand(name, map['id'] as String?),
            _ => null,
          },
        _ => null,
      };
}

/// A system control (播控中心, lock screen, headset keys) pressed while the
/// session mirrors a remote renderer ([RemoteSessionMirror]): the native
/// engine is paused and hands the key to Dart, where the output router
/// sends it to the speaker. [value]: the seek target in ms for `seek`,
/// `repeat|shuffle` (e.g. `all|true`) for `playMode`.
final class SessionTransportCommand extends EngineEvent {
  const SessionTransportCommand(this.name, this.value);
  final String name;
  final String? value;

  static const names = {'play', 'pause', 'stop', 'next', 'previous', 'seek', 'playMode'};
}

/// What the system media session shows while another renderer plays (a DLNA
/// speaker, the system's cast device): the song, where it is and whether it
/// plays, as the remote queue engine reports it.
final class RemoteSessionState {
  const RemoteSessionState({
    required this.item,
    required this.phase,
    required this.isPlaying,
    required this.playWhenReady,
    required this.positionMs,
    required this.sampledAtMs,
    this.durationMs,
    required this.repeat,
    required this.shuffle,
  });

  final EngineItem item;
  final PlaybackPhase phase;
  final bool isPlaying;
  final bool playWhenReady;
  final int positionMs;
  final int sampledAtMs;
  final int? durationMs;
  final EngineRepeat repeat;
  final bool shuffle;

  /// No stream URL or headers: the session only shows the song.
  Map<String, Object?> toMap() => {
        'id': item.id,
        'entryId': item.entryId,
        'title': item.title,
        'artist': item.artist,
        'album': item.album,
        'artworkUri': item.artworkUri,
        'phase': phase.name,
        'isPlaying': isPlaying,
        'playWhenReady': playWhenReady,
        'positionMs': positionMs,
        'sampledAtMs': sampledAtMs,
        'durationMs': durationMs,
        'repeat': repeat.name,
        'shuffle': shuffle,
      };
}

/// An engine whose system session can show a remote renderer in place of
/// its own (paused) player and route the system's keys to Dart while it does.
abstract interface class RemoteSessionMirror {
  /// [state] null hands the session back to the engine's own player.
  Future<void> setRemoteSession(RemoteSessionState? state);
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

/// What the native player decodes for the entry it just opened (Android
/// PlaybackService.playingFormat, from AVPlayer's track description), and
/// whether it opened the downloaded file ([fromFile]) or the stream. Sent
/// once per opened source; [decoded] fields are null where the player
/// doesn't say.
final class PlayingFormatEvent extends EngineEvent {
  const PlayingFormatEvent({required this.id, this.entryId, required this.fromFile, required this.decoded});

  final String id;
  final String? entryId;
  final bool fromFile;
  final AudioFormat decoded;

  /// The delivery of the source that opened: the download's or the stream's.
  StreamDelivery? deliveryOf(EngineItem item) => fromFile ? item.fileDelivery : item.delivery;

  static PlayingFormatEvent? fromMap(Map<Object?, Object?> map) {
    final id = map['id'];
    if (id is! String) return null;
    final bitRate = _int(map['bitRate']);
    return PlayingFormatEvent(
      id: id,
      entryId: map['entryId'] as String?,
      fromFile: map['fromFile'] == true,
      decoded: AudioFormat(
        contentType: map['mime'] as String?,
        // bit/s → kbps; 0 = unknown.
        bitRateKbps: bitRate == null || bitRate <= 0 ? null : (bitRate / 1000).round(),
        sampleRateHz: _positive(map['sampleRateHz']),
        bitDepth: _positive(map['bitDepth']),
        channels: _positive(map['channels']),
      ),
    );
  }

  @override
  bool operator ==(Object other) =>
      other is PlayingFormatEvent &&
      other.id == id &&
      other.entryId == entryId &&
      other.fromFile == fromFile &&
      other.decoded.contentType == decoded.contentType &&
      other.decoded.bitRateKbps == decoded.bitRateKbps &&
      other.decoded.sampleRateHz == decoded.sampleRateHz &&
      other.decoded.bitDepth == decoded.bitDepth &&
      other.decoded.channels == decoded.channels;

  @override
  int get hashCode => Object.hash(id, entryId, fromFile, decoded.contentType, decoded.channels);
}

int? _positive(Object? v) => switch (_int(v)) {
  final int i when i > 0 => i,
  _ => null,
};

/// A line for the playback log (Dart's PlayTrace, Settings › Help and about
/// › Export log): what the native engine did — an open, a gapless swap, a
/// player error, a focus change, a stall, a key from the system. [line]
/// carries no URL; [detail] is free text (an error's message) to mask before
/// it is kept. [atMs]: when it happened (a line queued while no Dart listened
/// arrives later).
final class EngineTraceEvent extends EngineEvent {
  const EngineTraceEvent(this.line, {this.detail, this.atMs});

  final String line;
  final String? detail;
  final int? atMs;

  static EngineTraceEvent? fromMap(Map<Object?, Object?> map) {
    final line = map['line'];
    if (line is! String || line.isEmpty) return null;
    final detail = map['detail'];
    return EngineTraceEvent(
      line,
      detail: detail is String && detail.isNotEmpty ? detail : null,
      atMs: _int(map['atMs']),
    );
  }
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

  /// New [EngineItem.gain]s by entry id (volume leveling changed): the entry
  /// playing takes its new volume at once, the rest when they start.
  Future<void> setGains(Map<String, double> gainsByEntryId);

  /// What the native host holds now; null when it can't answer.
  Future<EngineSnapshot?> snapshot();
}

/// A native session that can show synced LRC lyrics (HarmonyOS AVSession
/// `AVMetadata.lyric`, displayed on the Lock Screen / 播控中心). Kept apart from
/// [PlaybackEngine] / [QueuePlaybackEngine] so existing test fakes keep working.
abstract interface class SystemLyricsMirror {
  /// Updates the system session's synced LRC [lyric] for track [id]; null clears it.
  Future<void> setLyric(String id, String? lyric);
}

int? _int(Object? v) => switch (v) {
      final int i => i,
      final double d => d.round(),
      _ => null,
    };
