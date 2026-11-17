import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/activity/activity.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/profile/profile_repository.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/platform/playback/playback_engine.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/platform/window/window_controls.dart';
import 'package:yoin/playback/now_playing_state.dart';
import 'package:yoin/playback/play_mode.dart';
import 'package:yoin/playback/playback_context.dart';
import 'package:yoin/playback/playback_controller.dart';
import 'package:yoin/playback/playback_strings.dart';
import 'package:yoin/playback/playhead.dart';
import 'package:yoin/playback/scrobble.dart';
import 'package:yoin/playback/system_requests.dart';
import 'package:yoin/playback/track_codec.dart';

import '../support/fake_music_source.dart';
import '../support/fake_playback_engine.dart';
import '../support/playback_fakes.dart';

final class _Window implements WindowControls {
  final toasts = <String>[];
  final keepOn = <bool>[];
  @override
  Future<void> showToast(String message) async => toasts.add(message);
  @override
  Future<void> setKeepScreenOn(bool on) async => keepOn.add(on);
}

final class _Clock implements PlaybackClock {
  int wall = 1700000000000;
  int mono = 0;
  @override
  int wallMs() => wall;
  @override
  int monotonicMs() => mono;
}

const _creds = SubsonicCredentials(baseUrl: 'https://m.test', username: 'u', password: 'p');

void main() {
  group('with the base engine', () {
    late FakePlaybackEngine engine;
    late ProviderContainer c;

    setUp(() {
      engine = FakePlaybackEngine();
      c = ProviderContainer(overrides: [
        playbackEngineProvider.overrideWithValue(engine),
        activeSourceProvider.overrideWith((ref) async => FakeMusicSource()),
      ]);
      c.listen(playbackControllerProvider, (_, _) {});
    });
    tearDown(() => c.dispose());

    test('should_sendStreamItemsWithLockScreenArtwork_when_playTracks', () async {
      final tracks = playbackTracks(3);
      await c.read(playbackControllerProvider.notifier).playTracks(tracks, startIndex: 1);

      expect(engine.startIndex, 1);
      expect(engine.queue.map((i) => i.uri), ['https://stream.test/t0', 'https://stream.test/t1', 'https://stream.test/t2']);
      expect(engine.queue[1].artworkUri, 'https://covers.test/c1?size=512');
      expect(engine.queue[1].durationMs, 200000);
      expect(engine.queue.map((i) => i.entryId).toSet(), hasLength(3), reason: 'one entry id per entry');
      final state = c.read(playbackControllerProvider);
      expect(state.current!.id, tracks[1].id);
      expect(state.phase, PlaybackPhase.loading);
      expect(state.isPlaying, isFalse, reason: 'optimistic state never claims audio');
      expect(state.playWhenReady, isTrue);
    });

    test('should_followNativeTrack_when_engineAdvances', () async {
      final tracks = playbackTracks(3);
      await c.read(playbackControllerProvider.notifier).playTracks(tracks);

      engine.emit(EngineState(phase: PlaybackPhase.ready, isPlaying: true, index: 2, currentId: tracks[2].id.toString(), positionMs: 1000, sampledAtMs: 5000, durationMs: 199000));
      await pumpEventQueue();

      final state = c.read(playbackControllerProvider);
      expect(state.current!.id, tracks[2].id);
      expect(state.isPlaying, isTrue);
      expect(state.positionAt(6500), 2500); // extrapolated from the sample
    });

    test('should_notExtrapolate_when_paused', () async {
      await c.read(playbackControllerProvider.notifier).playTracks(playbackTracks(1));
      engine.emit(const EngineState(phase: PlaybackPhase.ready, isPlaying: false, index: 0, positionMs: 1000, sampledAtMs: 5000));
      await pumpEventQueue();
      expect(c.read(playbackControllerProvider).positionAt(9000), 1000);
    });

    test('should_pickTappedEntry_when_sameSongQueuedTwice', () async {
      final t = playbackTracks(2);
      await c.read(playbackControllerProvider.notifier).playTracks([t[0], t[1], t[0]], startIndex: 2);
      expect(engine.startIndex, 2);
    });
  });

  group('with the v1 engine', () {
    late PlaybackFakeQueueEngine engine;
    late PlaybackFakeSource source;
    late InMemoryStore values;
    late ProfileRepository repo;
    late _Clock clock;
    late ProviderContainer c;
    late Profile profile;
    late List<NowPlayingState> published;
    late _Window window;

    PlaybackController ctl() => c.read(playbackControllerProvider.notifier);
    NowPlayingState st() => c.read(playbackControllerProvider);

    setUp(() async {
      engine = PlaybackFakeQueueEngine();
      source = PlaybackFakeSource();
      values = InMemoryStore();
      repo = ProfileRepository(values, InMemoryStore());
      profile = await repo.add(provider: MediaId.providerSubsonic, displayName: 'Home', credentials: _creds);
      clock = _Clock();
      window = _Window();
      c = ProviderContainer(overrides: [
        windowControlsProvider.overrideWithValue(window),
        playbackEngineProvider.overrideWithValue(engine),
        keyValueStoreProvider.overrideWithValue(values),
        profileRepositoryProvider.overrideWithValue(repo),
        activeSourceProvider.overrideWith((ref) async => source),
        playbackClockProvider.overrideWithValue(clock),
      ]);
      published = [];
      c.listen(playbackControllerProvider, (_, next) => published.add(next));
      await c.read(activeSourceProvider.future);
      await pumpEventQueue();
    });
    tearDown(() => c.dispose());

    Future<void> echo(int index, {bool playing = true, int position = 0, int sampledAt = 1000, int repeatPlays = 0, List<int>? order, bool tick = false, PlaybackPhase phase = PlaybackPhase.ready}) async {
      engine.echo(index: index, isPlaying: playing, positionMs: position, sampledAtMs: sampledAt, repeatPlays: repeatPlays, order: order, tick: tick, phase: phase);
      await pumpEventQueue();
    }

    test('should_writePlayModeWithQueue_when_starting', () async {
      await ctl().setMode(PlayMode.shuffleRepeatOne);
      await ctl().playTracks(playbackTracks(3), shuffled: true);
      expect(engine.calls, ['startQueue']);
      expect(engine.repeat, EngineRepeat.one);
      expect(engine.shuffle, isTrue);
      expect(st().playMode, PlayMode.shuffleRepeatOne);
    });

    test('should_writeOnlyChangedAxis_when_modeChangesWhilePlaying', () async {
      await ctl().playTracks(playbackTracks(3));
      engine.calls.clear();
      await ctl().setMode(PlayMode.repeatAll.next()); // shuffle on, repeat stays all
      expect(engine.calls, ['setShuffle:true']);
      await ctl().setPlayMode(shuffle: true, repeat: RepeatMode.one);
      expect(engine.calls, ['setShuffle:true', 'setRepeat:one']);
    });

    test('should_followSystemLoopMode_when_engineReportsIt', () async {
      await ctl().playTracks(playbackTracks(2));
      engine.repeat = EngineRepeat.off;
      engine.shuffle = true;
      await echo(0);
      expect(st().playMode, PlayMode.shuffleRepeatOff);
    });

    test('should_publishPlayOrder_when_shuffled', () async {
      await ctl().playTracks(playbackTracks(4));
      await echo(1, order: [1, 3, 0, 2]);
      expect(st().upcoming, [3, 0, 2]);
      expect(st().nextIndex, 3);
      expect(st().nextTrack!.id, MediaId.subsonic('t3'));
      await echo(2, order: [1, 3, 0, 2]);
      expect(st().upcoming, isEmpty);
      expect(st().nextIndex, 1, reason: 'repeat all wraps to the top of the play order');
    });

    test('should_ignoreStaleRevision_when_oldQueueStillReporting', () async {
      await ctl().playTracks(playbackTracks(3, prefix: 'a'));
      final oldRevision = engine.revision;
      await ctl().playTracks(playbackTracks(2, prefix: 'b'), startIndex: 1);
      engine.echo(index: 2, revisionOverride: oldRevision);
      await pumpEventQueue();
      expect(st().index, 1);
      expect(st().current!.id, MediaId.subsonic('b1'));
    });

    test('should_findEntryByEntryId_when_indexDisagrees', () async {
      await ctl().playTracks(playbackTracks(3));
      engine.emit(EngineState(
        phase: PlaybackPhase.ready,
        index: 0,
        currentEntryId: engine.queue[2].entryId,
        revision: engine.revision,
      ));
      await pumpEventQueue();
      expect(st().index, 2);
    });

    test('should_notRepublish_when_onlyPositionTicks', () async {
      await ctl().playTracks(playbackTracks(1));
      await echo(0, position: 0, sampledAt: 1000);
      final count = published.length;
      await echo(0, position: 1000, sampledAt: 2000, tick: true);
      await echo(0, position: 2010, sampledAt: 3000, tick: true);
      expect(published.length, count, reason: 'ticks go to the playhead only');
      expect(c.read(playheadProvider).positionMs, 2010);
      await echo(0, position: 60000, sampledAt: 4000, tick: true); // a seek elsewhere (lock screen)
      expect(published.length, count + 1);
      expect(st().positionAt(4000), 60000);
    });

    test('should_holdPauseLabel_when_bufferingWithIntent', () async {
      await ctl().playTracks(playbackTracks(1));
      engine.emit(EngineState(phase: PlaybackPhase.loading, index: 0, playWhenReady: true, revision: engine.revision, currentEntryId: engine.queue[0].entryId));
      await pumpEventQueue();
      expect(st().playWhenReady, isTrue);
      expect(st().isPlaying, isFalse);
      ctl().togglePlayPause();
      expect(engine.calls.last, 'pause');
    });

    test('should_showError_when_engineFails_andKeepTrack', () async {
      await ctl().playTracks(playbackTracks(2));
      engine.echo(index: 1, phase: PlaybackPhase.error, isPlaying: false, errorMessage: '5400101: io');
      await pumpEventQueue();
      expect(st().phase, PlaybackPhase.error);
      expect(st().errorKind, PlaybackErrorKind.interrupted);
      expect(st().current!.id, MediaId.subsonic('t1'));
    });

    test('should_showPlayFailed_when_startThrows', () async {
      final failing = _ThrowingSource();
      c.dispose();
      c = ProviderContainer(overrides: [
        playbackEngineProvider.overrideWithValue(engine),
        activeSourceProvider.overrideWith((ref) async => failing),
      ]);
      c.listen(playbackControllerProvider, (_, _) {});
      await c.read(playbackControllerProvider.notifier).playTracks(playbackTracks(1));
      expect(c.read(playbackControllerProvider).errorKind, PlaybackErrorKind.playFailed);
    });

    group('activity log', () {
      Future<List<ActivityEvent>> rows() async => (await c.read(activityLogProvider.future))!.recent();

      test('should_recordOncePerTrackStart_when_playing', () async {
        final album = AlbumPlaybackContext(albumId: MediaId.subsonic('al'), name: 'Al', artist: 'A');
        await ctl().playTracks(playbackTracks(3), context: album);
        await echo(0, playing: false, phase: PlaybackPhase.loading);
        expect(await rows(), isEmpty, reason: 'never while loading or paused');
        await echo(0);
        await echo(0, position: 1000, tick: true);
        await echo(0, repeatPlays: 1); // repeat one replays the same id
        final r = await rows();
        expect(r, hasLength(1));
        expect(r.single.entityType, ActivityEntityType.album);
        expect(r.single.entityId, 'al');
      });

      test('should_recordSongRows_when_noContext', () async {
        await ctl().playTracks(playbackTracks(2));
        await echo(0);
        await echo(1);
        final r = await rows();
        expect(r.map((e) => e.entityId), ['t1', 't0']);
        expect(r.first.entityType, ActivityEntityType.song);
      });

      test('should_recordAgain_when_skippedBackToSameTrack', () async {
        await ctl().playTracks(playbackTracks(1));
        await echo(0);
        clock.wall += 5000;
        await ctl().skipTo(0);
        await echo(0);
        expect((await rows()).single.timestampMs, clock.wall);
      });
    });

    group('scrobbling', () {
      test('should_reportNowPlayingAndSubmit_when_listenedHalf', () async {
        await ctl().playTracks(playbackTracks(1));
        await echo(0);
        expect(source.nowPlaying, [MediaId.subsonic('t0')]);
        clock.mono += 100000;
        await echo(0, position: 100000, sampledAt: 101000, tick: true);
        await pumpEventQueue();
        expect(source.submitted.single.$1, MediaId.subsonic('t0'));
        expect(source.submitted.single.$2.millisecondsSinceEpoch, clock.wall);
      });

      test('should_countRepeatAgain_when_repeatPlaysBumps', () async {
        await ctl().playTracks(playbackTracks(1));
        await echo(0);
        clock.mono += 100000;
        await echo(0, position: 100000, tick: true);
        await echo(0, repeatPlays: 1);
        clock.mono += 100000;
        await echo(0, repeatPlays: 1, position: 100000, tick: true);
        await pumpEventQueue();
        expect(source.submitted, hasLength(2));
        expect(source.nowPlaying, hasLength(2));
      });

      test('should_reportLiveStatesAndStop_when_serverTakesPlaybackReport', () async {
        final reporting = _StateReportingSource();
        source = reporting;
        c.invalidate(activeSourceProvider);
        await c.read(activeSourceProvider.future);
        await ctl().playTracks(playbackTracks(1));
        await echo(0, sampledAt: clock.wall);
        clock.mono += 5000;
        clock.wall += 5000;
        await echo(0, playing: false, position: 5000, sampledAt: clock.wall);
        // Moved while paused: the server hears the new position.
        clock.mono += 1000;
        clock.wall += 1000;
        await echo(0, playing: false, position: 120000, sampledAt: clock.wall);
        await ctl().disconnect();
        await pumpEventQueue();
        final t0 = MediaId.subsonic('t0');
        expect(reporting.nowPlaying, isEmpty);
        expect(reporting.reports, [
          (t0, PlaybackReportState.starting, 0),
          (t0, PlaybackReportState.playing, 0),
          (t0, PlaybackReportState.paused, 5000),
          (t0, PlaybackReportState.paused, 120000),
          (t0, PlaybackReportState.stopped, 120000),
        ]);
      });

      test('should_notScrobble_when_sourceLacksPlayReporting', () async {
        source = PlaybackFakeSource(capabilities: const {});
        c.invalidate(activeSourceProvider);
        await c.read(activeSourceProvider.future);
        await ctl().playTracks(playbackTracks(1));
        await echo(0);
        expect(source.nowPlaying, isEmpty);
      });

      test('should_queueOffline_when_submitFails', () async {
        source.submitFailure = const SourceNetworkException('offline');
        await ctl().playTracks(playbackTracks(1));
        await echo(0);
        clock.mono += 100000;
        await echo(0, position: 100000, tick: true);
        await pumpEventQueue();
        expect(PendingScrobbles.decode(values.values[PendingScrobbles.storeKey]), hasLength(1));
      });

      test('should_flushQueuedPlays_when_networkComesBack', () async {
        source.submitFailure = const SourceNetworkException('offline');
        await ctl().playTracks(playbackTracks(1));
        await echo(0);
        clock.mono += 100000;
        await echo(0, position: 100000, tick: true);
        await pumpEventQueue();
        expect(PendingScrobbles.decode(values.values[PendingScrobbles.storeKey]), hasLength(1));

        source.submitFailure = null;
        engine.event(const NetworkAvailableEvent());
        await pumpEventQueue();
        expect(source.submitted.single.$1, MediaId.subsonic('t0'));
        expect(values.values[PendingScrobbles.storeKey], isNull);
      });
    });

    group('favourite and rating', () {
      test('should_flipOptimisticallyAndMirrorSessionHeart_when_toggled', () async {
        await ctl().playTracks(playbackTracks(2));
        await echo(0);
        expect(engine.hearts['subsonic:t0'], isFalse);
        final ok = await ctl().toggleFavorite();
        expect(ok, isTrue);
        expect(st().current!.isStarred, isTrue);
        expect(source.favoriteWrites.single, (MediaId.subsonic('t0'), true));
        expect(engine.hearts['subsonic:t0'], isTrue);
      });

      test('should_revertAndNotify_when_writeFails', () async {
        await ctl().playTracks(playbackTracks(1));
        await echo(0);
        final notices = <PlaybackNotice>[];
        ctl().notices.listen(notices.add);
        source.writeFailure = const SourceNetworkException('offline');
        expect(await ctl().toggleFavorite(), isFalse);
        expect(st().current!.isStarred, isFalse);
        expect(engine.hearts['subsonic:t0'], isFalse);
        await pumpEventQueue();
        expect((notices.single as FavoriteWriteFailed).favorite, isTrue);
      });

      test('should_ignoreSecondTap_when_writeInFlight', () async {
        await ctl().playTracks(playbackTracks(1));
        await echo(0);
        source.writeGate = Completer<void>();
        final first = ctl().toggleFavorite();
        expect(await ctl().toggleFavorite(), isFalse);
        source.writeGate!.complete();
        expect(await first, isTrue);
        expect(source.favoriteWrites, hasLength(1));
      });

      test('should_toastInsteadOfSnackbar_when_systemHeartFailsInBackground', () async {
        await ctl().playTracks(playbackTracks(1));
        await echo(0);
        final notices = <PlaybackNotice>[];
        ctl().notices.listen(notices.add);
        ctl().debugForeground = false;
        source.writeFailure = const SourceNetworkException('offline');
        engine.event(const ToggleFavoriteCommand('subsonic:t0'));
        await pumpEventQueue();
        expect(window.toasts, [PlaybackStrings.en.couldntSaveFavorite]);
        expect(notices, isEmpty);
        expect(engine.hearts['subsonic:t0'], isFalse);
      });

      test('should_useSnackbar_when_systemHeartFailsInForeground', () async {
        await ctl().playTracks(playbackTracks(1));
        await echo(0);
        final notices = <PlaybackNotice>[];
        ctl().notices.listen(notices.add);
        ctl().debugForeground = true;
        source.writeFailure = const SourceNetworkException('offline');
        engine.event(const ToggleFavoriteCommand('subsonic:t0'));
        await pumpEventQueue();
        expect(window.toasts, isEmpty);
        expect((notices.single as FavoriteWriteFailed).favorite, isTrue);
      });

      test('should_toggleFromLockScreen_when_sessionHeartTapped', () async {
        await ctl().playTracks(playbackTracks(1));
        await echo(0);
        engine.event(const ToggleFavoriteCommand('subsonic:t0'));
        await pumpEventQueue();
        expect(source.favoriteWrites.single, (MediaId.subsonic('t0'), true));
      });

      test('should_updateQueueAndSessionHeart_when_rowHeartsQueuedTrack', () async {
        await ctl().playTracks(playbackTracks(2));
        await echo(0);
        final ok = await c
            .read(favoriteOverridesProvider.notifier)
            .setFavorite(MediaId.subsonic('t0'), target: true, previous: false);
        expect(ok, isTrue);
        expect(st().current!.isStarred, isTrue);
        expect(engine.hearts['subsonic:t0'], isTrue);
      });

      test('should_updateOverrides_when_nowPlayingHeartToggled', () async {
        await ctl().playTracks(playbackTracks(1));
        await echo(0);
        await ctl().toggleFavorite();
        expect(c.read(favoriteOverridesProvider)[MediaId.subsonic('t0')], isTrue);
        // The next tap anywhere flips from the shared value, not a stale one.
        await ctl().toggleFavorite();
        expect(c.read(favoriteOverridesProvider)[MediaId.subsonic('t0')], isFalse);
        expect(source.favoriteWrites.last, (MediaId.subsonic('t0'), false));
      });

      test('should_queueWithOverriddenHeart_when_staleListStartsPlayback', () async {
        await c
            .read(favoriteOverridesProvider.notifier)
            .setFavorite(MediaId.subsonic('t0'), target: true, previous: false);
        await ctl().playTracks(playbackTracks(1)); // Tracks still say unstarred.
        expect(st().current!.isStarred, isTrue);
      });

      test('should_hideSessionHeart_when_sourceCantFavourite', () async {
        source = PlaybackFakeSource(capabilities: const {Capability.playReporting});
        c.invalidate(activeSourceProvider);
        await c.read(activeSourceProvider.future);
        await ctl().playTracks(playbackTracks(1));
        await echo(0);
        expect(engine.hearts.containsKey('subsonic:t0'), isTrue);
        expect(engine.hearts['subsonic:t0'], isNull);
      });

      test('should_setAndRevertRating_when_writeFails', () async {
        await ctl().playTracks(playbackTracks(1));
        await echo(0);
        expect(await ctl().setRating(4), isTrue);
        expect(st().current!.userRating, 4);
        source.writeFailure = const SourceNetworkException('offline');
        expect(await ctl().setRating(2), isFalse);
        expect(st().current!.userRating, 4);
        expect(source.ratingWrites.single, (MediaId.subsonic('t0'), 4));
      });
    });

    group('queue edits', () {
      test('should_insertAfterCurrent_when_playNext', () async {
        await ctl().playTracks(playbackTracks(3));
        await echo(0);
        await ctl().addToQueue(playbackTracks(1, prefix: 'n'), next: true);
        expect(engine.calls.last, 'insert:1:1');
        expect(st().queue.map((t) => t.id.rawId), ['t0', 'n0', 't1', 't2']);
        expect(st().userQueued, {1});
        expect(st().nextTrack!.id.rawId, 'n0');
      });

      test('should_appendAfterUserQueuedRun_when_addToQueue', () async {
        await ctl().playTracks(playbackTracks(3));
        await echo(0);
        await ctl().addToQueue(playbackTracks(1, prefix: 'n'), next: true);
        await ctl().addToQueue(playbackTracks(1, prefix: 'q'));
        expect(st().queue.map((t) => t.id.rawId), ['t0', 'n0', 'q0', 't1', 't2']);
        expect(st().userQueued, {1, 2});
      });

      test('should_startPaused_when_addingToEmptyQueue', () async {
        await ctl().addToQueue(playbackTracks(2));
        expect(engine.calls, ['startQueue']);
        expect(engine.playWhenReady, isFalse);
      });

      test('should_keepCurrent_when_removingIt', () async {
        await ctl().playTracks(playbackTracks(3));
        await echo(1);
        await ctl().removeQueueItem(1);
        expect(engine.calls.where((x) => x.startsWith('removeAt')), isEmpty);
        await ctl().removeQueueItem(0);
        expect(engine.calls.last, 'removeAt:0');
        expect(st().index, 0);
        expect(st().current!.id.rawId, 't1');
      });

      test('should_dropUserQueued_when_clearUserQueue', () async {
        await ctl().playTracks(playbackTracks(2));
        await echo(0);
        await ctl().addToQueue(playbackTracks(2, prefix: 'q'));
        await ctl().clearUserQueue();
        expect(st().queue.map((t) => t.id.rawId), ['t0', 't1']);
      });

      test('should_goIdle_when_clearQueue', () async {
        await ctl().playTracks(playbackTracks(2));
        await echo(0);
        await ctl().clearQueue();
        expect(engine.calls.last, 'stop');
        expect(st().current, isNull);
        expect(st().phase, PlaybackPhase.idle);
      });
    });

    group('system', () {
      test('should_requestNowPlaying_when_systemCardTapped', () async {
        final before = c.read(nowPlayingOpenRequestsProvider);
        engine.event(const OpenNowPlayingEvent());
        await pumpEventQueue();
        expect(c.read(nowPlayingOpenRequestsProvider), before + 1);
      });

      test('should_stopAndGoIdle_when_profileSwitches', () async {
        await ctl().playTracks(playbackTracks(2));
        await echo(0);
        final other = await repo.add(provider: MediaId.providerSubsonic, displayName: 'Work', credentials: _creds);
        expect(other.id, isNot(profile.id));
        c.invalidate(activeSourceProvider);
        await c.read(activeSourceProvider.future);
        await pumpEventQueue();
        expect(engine.calls.last, 'stop');
        expect(st().current, isNull);
      });

      test('should_dropInFlightStart_when_profileSwitchesMidStart', () async {
        final gate = Completer<void>();
        source = _GatedSource(gate);
        c.invalidate(activeSourceProvider);
        await c.read(activeSourceProvider.future);
        final start = ctl().playTracks(playbackTracks(2));
        await pumpEventQueue();
        await repo.add(provider: MediaId.providerSubsonic, displayName: 'Work', credentials: _creds);
        gate.complete();
        await start;
        expect(engine.calls.where((x) => x == 'startQueue'), isEmpty);
      });

      test('should_rebuildQueueFromHost_when_uiRecreated', () async {
        final tracks = playbackTracks(2);
        c.dispose();
        final host = PlaybackFakeQueueEngine()
          ..queue = [
            for (var i = 0; i < 2; i++)
              EngineItem(
                id: tracks[i].id.toString(),
                uri: '',
                title: 'x',
                entryId: 'e$i',
                extra: PlaybackContextCodec.attach(
                  TrackCodec.encode(tracks[i]),
                  PlaylistPlaybackContext(playlistId: MediaId.subsonic('pl'), name: 'Road'),
                ),
              ),
          ]
          ..revision = 7;
        host.snapshotToReturn = EngineSnapshot(
          items: host.queue,
          state: EngineState(phase: PlaybackPhase.ready, isPlaying: true, index: 1, currentEntryId: 'e1', revision: 7, order: const [0, 1], playWhenReady: true),
        );
        c = ProviderContainer(overrides: [
          playbackEngineProvider.overrideWithValue(host),
          keyValueStoreProvider.overrideWithValue(values),
          profileRepositoryProvider.overrideWithValue(repo),
          activeSourceProvider.overrideWith((ref) async => source),
          playbackClockProvider.overrideWithValue(clock),
        ]);
        c.listen(playbackControllerProvider, (_, _) {});
        await pumpEventQueue();
        final s = c.read(playbackControllerProvider);
        expect(s.current!.id, tracks[1].id);
        expect(s.current!.album, 'Al', reason: 'full track from the item extra');
        expect(s.entryIds, ['e0', 'e1']);
        expect(s.isPlaying, isTrue);
        expect((s.context as PlaylistPlaybackContext).name, 'Road', reason: 'PLAYING FROM survives the rebuild');
        expect(host.calls.where((x) => !x.startsWith('setFavorite')), isEmpty, reason: 'rehydration never restarts playback');
      });
    });
  });
}

class _ThrowingSource extends PlaybackFakeSource {
  @override
  Future<PlaybackHandle> handleFor(Track track) async => throw StateError('boom');
}

class _GatedSource extends PlaybackFakeSource {
  _GatedSource(this.gate);
  final Completer<void> gate;
  @override
  Future<PlaybackHandle> handleFor(Track track) async {
    await gate.future;
    return super.handleFor(track);
  }
}

/// A server with OpenSubsonic `playbackReport`.
class _StateReportingSource extends PlaybackFakeSource implements MusicPlaybackStateReports {
  final List<(MediaId, PlaybackReportState, int)> reports = [];

  @override
  Future<bool> supportsPlaybackStateReport() async => true;

  @override
  Future<void> reportPlaybackState(MediaId trackId, PlaybackReportState state, int positionMs) async =>
      reports.add((trackId, state, positionMs));
}
