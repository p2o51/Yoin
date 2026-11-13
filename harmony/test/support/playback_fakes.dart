import 'dart:async';

import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/platform/playback/playback_engine.dart';

/// A [QueuePlaybackEngine] that records calls and mimics the native queue
/// closely enough for controller tests: tests push states with [emit] (or
/// [echo], which fills in the queue fields the native side would send).
class PlaybackFakeQueueEngine implements QueuePlaybackEngine {
  final _states = StreamController<EngineState>.broadcast();
  final _events = StreamController<EngineEvent>.broadcast();
  final List<String> calls = [];
  List<EngineItem> queue = [];
  int startIndex = -1;
  int startPositionMs = 0;
  bool playWhenReady = false;
  EngineRepeat repeat = EngineRepeat.all;
  bool shuffle = false;
  int revision = 0;
  final Map<String, bool?> hearts = {};
  EngineSnapshot? snapshotToReturn;

  void emit(EngineState state) => _states.add(state);
  void event(EngineEvent e) => _events.add(e);

  /// A state for queue index [index] as the native side would report it.
  void echo({
    required int index,
    PlaybackPhase phase = PlaybackPhase.ready,
    bool isPlaying = true,
    int positionMs = 0,
    int sampledAtMs = 1000,
    int? durationMs = 200000,
    int repeatPlays = 0,
    List<int>? order,
    bool tick = false,
    int? revisionOverride,
    String? errorMessage,
  }) =>
      emit(EngineState(
        phase: phase,
        isPlaying: isPlaying,
        playWhenReady: isPlaying || phase == PlaybackPhase.loading,
        index: index,
        currentId: index >= 0 && index < queue.length ? queue[index].id : null,
        currentEntryId: index >= 0 && index < queue.length ? queue[index].entryId : null,
        positionMs: positionMs,
        sampledAtMs: sampledAtMs,
        durationMs: durationMs,
        repeat: repeat,
        shuffle: shuffle,
        revision: revisionOverride ?? revision,
        repeatPlays: repeatPlays,
        order: tick ? null : (order ?? List<int>.generate(queue.length, (i) => i)),
        tick: tick,
        errorMessage: errorMessage,
      ));

  @override
  Stream<EngineState> get states => _states.stream;
  @override
  Stream<EngineEvent> get events => _events.stream;

  @override
  Future<void> setQueue(List<EngineItem> items, {int startIndex = 0, int startPositionMs = 0, bool playWhenReady = true}) async {
    queue = [...items];
    this.startIndex = startIndex;
    calls.add('setQueue');
  }

  @override
  Future<void> startQueue(
    List<EngineItem> items, {
    required int revision,
    int startIndex = 0,
    int startPositionMs = 0,
    bool playWhenReady = true,
    EngineRepeat repeat = EngineRepeat.all,
    bool shuffle = false,
  }) async {
    queue = [...items];
    this.revision = revision;
    this.startIndex = startIndex;
    this.startPositionMs = startPositionMs;
    this.playWhenReady = playWhenReady;
    this.repeat = repeat;
    this.shuffle = shuffle;
    calls.add('startQueue');
  }

  @override
  Future<void> insert(int at, List<EngineItem> items, {required int revision}) async {
    queue.insertAll(at, items);
    this.revision = revision;
    calls.add('insert:$at:${items.length}');
  }

  @override
  Future<void> removeAt(int index, {required int revision}) async {
    queue.removeAt(index);
    this.revision = revision;
    calls.add('removeAt:$index');
  }

  @override
  Future<void> move(int from, int to, {required int revision}) async {
    queue.insert(to, queue.removeAt(from));
    this.revision = revision;
    calls.add('move:$from:$to');
  }

  @override
  Future<void> setRepeat(EngineRepeat repeat) async {
    this.repeat = repeat;
    calls.add('setRepeat:${repeat.name}');
  }

  @override
  Future<void> setShuffle(bool shuffle) async {
    this.shuffle = shuffle;
    calls.add('setShuffle:$shuffle');
  }

  @override
  Future<void> setFavorite(String id, bool? favorite) async {
    hearts[id] = favorite;
    calls.add('setFavorite:$id:$favorite');
  }

  @override
  Future<EngineSnapshot?> snapshot() async => snapshotToReturn;

  @override
  Future<void> play() async => calls.add('play');
  @override
  Future<void> pause() async => calls.add('pause');
  @override
  Future<void> seekTo(int positionMs) async => calls.add('seekTo:$positionMs');
  @override
  Future<void> skipTo(int index) async => calls.add('skipTo:$index');
  @override
  Future<void> next() async => calls.add('next');
  @override
  Future<void> previous() async => calls.add('previous');
  @override
  Future<void> stop() async {
    queue = [];
    calls.add('stop');
  }
}

/// A Subsonic-like source for playback tests: every capability the chain
/// uses, recorded writes and scrobbles, scripted failures.
class PlaybackFakeSource implements MusicSource, MusicPlayback, MusicWriteActions {
  PlaybackFakeSource({this.capabilities = const {Capability.favorites, Capability.rating, Capability.playReporting}});

  @override
  final Set<Capability> capabilities;

  final List<(MediaId, bool)> favoriteWrites = [];
  final List<(MediaId, int)> ratingWrites = [];
  final List<MediaId> nowPlaying = [];
  final List<(MediaId, DateTime)> submitted = [];

  /// Thrown by the next writes / submits while set.
  Object? writeFailure;
  Object? submitFailure;
  Completer<void>? writeGate;

  @override
  String get provider => MediaId.providerSubsonic;
  @override
  MusicLibrary get library => throw const UnsupportedSourceOperation('library');
  @override
  MusicMetadata get metadata => throw const UnsupportedSourceOperation('metadata');
  @override
  MusicWriteActions get writeActions => this;
  @override
  MusicPlayback get playback => this;
  @override
  String? resolveCoverUrl(CoverRef ref, {int? size}) => 'https://covers.test/${ref.storageKey}?size=$size';
  @override
  void dispose() {}

  @override
  Future<PlaybackHandle> handleFor(Track track) async => DirectStream('https://stream.test/${track.id.rawId}');

  @override
  Future<void> reportNowPlaying(MediaId trackId) async => nowPlaying.add(trackId);

  @override
  Future<void> submitPlay(MediaId trackId, DateTime playedAt) async {
    final failure = submitFailure;
    if (failure != null) throw failure;
    submitted.add((trackId, playedAt));
  }

  @override
  Future<void> setFavorite(MediaId id, bool favorite) async {
    await writeGate?.future;
    final failure = writeFailure;
    if (failure != null) throw failure;
    favoriteWrites.add((id, favorite));
  }

  @override
  Future<void> setRating(MediaId trackId, int rating) async {
    final failure = writeFailure;
    if (failure != null) throw failure;
    ratingWrites.add((trackId, rating));
  }
}

List<Track> playbackTracks(int n, {String prefix = 't'}) => [
      for (var i = 0; i < n; i++)
        Track(
          id: MediaId.subsonic('$prefix$i'),
          title: 'Song $i',
          artist: 'A',
          artistId: MediaId.subsonic('ar'),
          album: 'Al',
          albumId: MediaId.subsonic('al'),
          durationSec: 200,
          coverArt: CoverSourceRelative('c$i'),
        ),
    ];
