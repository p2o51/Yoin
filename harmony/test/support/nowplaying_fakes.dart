import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/platform/playback/playback_engine.dart';
import 'package:yoin/playback/now_playing_state.dart';
import 'package:yoin/playback/play_mode.dart';
import 'package:yoin/playback/playback_context.dart';
import 'package:yoin/playback/playback_controller.dart';
import 'package:yoin/theme/playback_theme.dart';

import 'fake_music_source.dart';

/// A PlaybackController that publishes what the test sets and records the
/// commands Now Playing sends (no engine, no source).
class NowPlayingFakeController extends PlaybackController {
  NowPlayingFakeController(this.initial);

  final NowPlayingState initial;
  final List<String> calls = [];

  @override
  NowPlayingState build() => initial;

  void emit(NowPlayingState next) => state = next;

  @override
  Future<void> play() async {
    calls.add('play');
    state = state.copyWith(playWhenReady: true);
  }

  @override
  Future<void> pause() async {
    calls.add('pause');
    state = state.copyWith(playWhenReady: false);
  }

  @override
  Future<void> next() async => calls.add('next');
  @override
  Future<void> previous() async => calls.add('previous');
  @override
  Future<void> skipTo(int index) async => calls.add('skipTo:$index');
  @override
  Future<void> seekTo(int positionMs) async => calls.add('seekTo:$positionMs');

  @override
  Future<void> setMode(PlayMode mode) async {
    calls.add('setMode:${mode.name}');
    state = state.copyWith(repeat: mode.repeat, shuffle: mode.shuffled);
  }

  @override
  Future<bool> toggleFavorite({MediaId? id}) async {
    calls.add('toggleFavorite');
    final track = state.current;
    if (track == null) return false;
    state = state.copyWith(
      queue: [for (final t in state.queue) t.id == track.id ? t.copyWith(isStarred: !t.isStarred) : t],
    );
    return true;
  }

  @override
  Future<bool> setRating(int rating) async {
    calls.add('setRating:$rating');
    return true;
  }
}

/// Tracks t0…t(n-1) on album "Album" with covers c0….
List<Track> nowPlayingTracks(int n) => [
  for (var i = 0; i < n; i++)
    Track(
      id: MediaId.subsonic('t$i'),
      title: 'Song $i',
      artist: 'Artist $i',
      album: 'Album',
      coverArt: CoverSourceRelative('c$i'),
      durationSec: 200,
    ),
];

NowPlayingState playingState(
  List<Track> tracks, {
  int index = 0,
  bool playWhenReady = true,
  PlaybackContext context = const NoPlaybackContext(),
}) => NowPlayingState(
  queue: tracks,
  index: index,
  phase: PlaybackPhase.ready,
  isPlaying: false,
  playWhenReady: playWhenReady,
  durationMs: 200000,
  repeat: RepeatMode.all,
  context: context,
  upcoming: [for (var i = index + 1; i < tracks.length; i++) i],
);

/// FakeMusicSource with lyrics, favourites and rating.
class NowPlayingFakeSource extends FakeMusicSource implements MusicMetadata {
  NowPlayingFakeSource({this.lyrics = const {}, this.lyricsDelay});

  /// Song raw id → lyrics.
  final Map<String, Lyrics?> lyrics;
  final Future<void>? lyricsDelay;
  final List<String> lyricRequests = [];

  @override
  Set<Capability> get capabilities => const {Capability.lyrics, Capability.favorites, Capability.rating};

  @override
  MusicMetadata get metadata => this;

  /// Names the fallback may retry under, by raw id.
  final Map<String, TrackNames> alternate = {};

  @override
  Future<TrackNames?> alternateNames(Track track) async => alternate[track.id.rawId];

  @override
  Future<Lyrics?> getLyrics(Track track) async {
    lyricRequests.add(track.id.rawId);
    if (lyricsDelay != null) await lyricsDelay;
    return lyrics[track.id.rawId];
  }
}

/// Never touches the network: no cover colours.
class NowPlayingFakeColorLoader implements CoverColorLoader {
  NowPlayingFakeColorLoader([this.colors]);
  final CoverColors? colors;
  final List<String> requests = [];

  @override
  Future<CoverColors?> load(String url) async {
    requests.add(url);
    return colors;
  }
}

/// Overrides for a Now Playing test.
List<Override> nowPlayingOverrides({
  required NowPlayingFakeController controller,
  MusicSource? source,
  CoverColorLoader? colors,
}) => [
  playbackControllerProvider.overrideWith(() => controller),
  activeSourceProvider.overrideWith((ref) async => source ?? NowPlayingFakeSource()),
  coverColorLoaderProvider.overrideWithValue(colors ?? NowPlayingFakeColorLoader()),
];
