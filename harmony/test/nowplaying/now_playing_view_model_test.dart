import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/nowplaying/now_playing_view_model.dart';
import 'package:yoin/nowplaying/player_snapshot.dart';
import 'package:yoin/playback/now_playing_state.dart';
import 'package:yoin/playback/play_mode.dart';
import 'package:yoin/playback/playback_context.dart';

import '../support/nowplaying_fakes.dart';

void main() {
  late NowPlayingFakeController controller;
  late NowPlayingFakeSource source;
  late ProviderContainer c;

  ProviderContainer make(NowPlayingState initial, {NowPlayingFakeSource? withSource}) {
    controller = NowPlayingFakeController(initial);
    source = withSource ?? NowPlayingFakeSource();
    final container = ProviderContainer(
      overrides: nowPlayingOverrides(controller: controller, source: source),
    );
    container.listen(nowPlayingViewModelProvider, (_, _) {});
    return container;
  }

  Future<void> settle() async {
    for (var i = 0; i < 5; i++) {
      await Future<void>.delayed(Duration.zero);
    }
  }

  tearDown(() => c.dispose());

  test('should_beIdle_when_noTrack', () {
    c = make(NowPlayingState.idle);
    expect(c.read(nowPlayingViewModelProvider), isA<NowPlayingIdle>());
  });

  test('should_projectTrackAndIntent_when_playing', () async {
    final tracks = nowPlayingTracks(3);
    final album = AlbumPlaybackContext(albumId: _albumId, name: 'Album');
    c = make(playingState(tracks, index: 1, context: album));
    await c.read(activeSourceProvider.future);
    await settle();
    final s = c.read(nowPlayingViewModelProvider) as NowPlayingContent;
    expect(s.track.title, 'Song 1');
    expect(s.isPlaying, isTrue, reason: 'PLAY|PAUSE follows playWhenReady');
    expect(s.upcoming, [2]);
    expect(s.canFavorite, isTrue);
    expect(identical(s.context, album), isTrue);
  });

  test('should_showNoRating_when_onlyServerRatingExists', () async {
    final tracks = [nowPlayingTracks(1).single.copyWith(userRating: 4)];
    c = make(playingState(tracks));
    await c.read(activeSourceProvider.future);
    await settle();
    final s = c.read(nowPlayingViewModelProvider) as NowPlayingContent;
    expect(s.rating, isNull, reason: 'Android never seeds the slider from Track.userRating');
  });

  test('should_beUnavailable_when_errorKindWithoutTrack', () {
    const s = NowPlayingState(errorKind: PlaybackErrorKind.playFailed);
    expect(isPlaybackUnavailable(PlayerSnapshot.of(s)), isTrue);
    expect(isPlaybackUnavailable(PlayerSnapshot.of(NowPlayingState.idle)), isFalse);
  });

  test('should_fetchLyricsWithoutCredits_when_songStarts', () async {
    final tracks = nowPlayingTracks(1);
    c = make(
      playingState(tracks),
      withSource: NowPlayingFakeSource(
        lyrics: {
          't0': const SyncedLyrics([
            LyricLine(0, '作词 : A'),
            LyricLine(0, '作曲 : B'),
            LyricLine(1000, 'one'),
            LyricLine(2000, 'two'),
          ]),
        },
      ),
    );
    await c.read(activeSourceProvider.future);
    await settle();
    final s = c.read(nowPlayingViewModelProvider) as NowPlayingContent;
    expect(s.lyrics, isA<LyricsReady>());
    expect((s.lyrics as LyricsReady).sheet.rows.map((r) => r.text), ['one', 'two']);
  });

  test('should_ignoreStaleLyrics_when_songSkippedMidFetch', () async {
    final gate = Completer<void>();
    final tracks = nowPlayingTracks(2);
    c = make(
      playingState(tracks),
      withSource: NowPlayingFakeSource(
        lyrics: {'t0': const UnsyncedLyrics('zero\nzero two'), 't1': const UnsyncedLyrics('one\none two')},
        lyricsDelay: gate.future,
      ),
    );
    await c.read(activeSourceProvider.future);
    await settle();
    controller.emit(playingState(tracks, index: 1));
    await settle();
    gate.complete();
    await settle();
    final s = c.read(nowPlayingViewModelProvider) as NowPlayingContent;
    expect(s.track.id.rawId, 't1');
    expect((s.lyrics as LyricsReady).sheet.rows.first.text, 'one');
  });

  test('should_pause_when_toggledWhileIntentIsPlay', () async {
    c = make(playingState(nowPlayingTracks(1)));
    await c.read(nowPlayingViewModelProvider.notifier).togglePlayPause();
    expect(controller.calls, ['pause']);
    await c.read(nowPlayingViewModelProvider.notifier).togglePlayPause();
    expect(controller.calls, ['pause', 'play']);
  });

  test('should_walkGrayCycle_when_playModeTapped', () async {
    c = make(playingState(nowPlayingTracks(1)));
    await c.read(nowPlayingViewModelProvider.notifier).cyclePlayMode();
    expect(controller.calls.last, 'setMode:${PlayMode.shuffle.name}');
    expect((c.read(nowPlayingViewModelProvider) as NowPlayingContent).playMode, PlayMode.shuffle);
  });

  test('should_keepTenthsLocallyAndSendStars_when_ratingCommitted', () async {
    c = make(playingState(nowPlayingTracks(1)));
    await c.read(activeSourceProvider.future);
    await c.read(nowPlayingViewModelProvider.notifier).commitRating(7.4);
    expect((c.read(nowPlayingViewModelProvider) as NowPlayingContent).rating, 7.4);
    expect(controller.calls.last, 'setRating:4');
  });

  test('should_seekToFractionOfDuration_when_scrubbed', () async {
    c = make(playingState(nowPlayingTracks(1)));
    await c.read(nowPlayingViewModelProvider.notifier).seekToFraction(0.5);
    expect(controller.calls.last, 'seekTo:100000');
  });

  test('should_flipHeart_when_favoriteToggled', () async {
    c = make(playingState(nowPlayingTracks(1)));
    await c.read(activeSourceProvider.future);
    await c.read(nowPlayingViewModelProvider.notifier).toggleFavorite();
    expect((c.read(nowPlayingViewModelProvider) as NowPlayingContent).isStarred, isTrue);
  });
}

final _albumId = MediaId.subsonic('al');
