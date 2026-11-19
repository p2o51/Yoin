import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/lyrics/lyrics_registry.dart';
import 'package:yoin/data/lyrics/lyrics_repository.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/profile/profile_repository.dart';
import 'package:yoin/nowplaying/lyrics_logic.dart';
import 'package:yoin/platform/playback/channel_playback_engine.dart';
import 'package:yoin/platform/playback/playback_engine.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/playback/output/playback_router.dart';
import 'package:yoin/playback/playback_controller.dart';

import '../support/nowplaying_fakes.dart';
import '../support/playback_fakes.dart';

const _creds = SubsonicCredentials(baseUrl: 'https://m.test', username: 'u', password: 'p');

final class _LyricsMirrorEngine extends PlaybackFakeQueueEngine implements SystemLyricsMirror {
  final List<(String id, String? lyric)> lyrics = [];

  @override
  Future<void> setLyric(String id, String? lyric) async => lyrics.add((id, lyric));
}

final class _GateableLyricsSource extends NowPlayingFakeSource {
  _GateableLyricsSource({required super.lyrics, this.gateForRawId, this.gate});

  final String? gateForRawId;
  final Future<void>? gate;
  int getLyricsCalls = 0;

  @override
  Future<Lyrics?> getLyrics(Track track) async {
    getLyricsCalls += 1;
    if (track.id.rawId == gateForRawId && gate != null) {
      await gate;
    }
    return super.getLyrics(track);
  }
}

void main() {
  Future<void> settle() async {
    for (var i = 0; i < 8; i++) {
      await Future<void>.delayed(Duration.zero);
    }
  }

  group('system lyrics sync', () {
    late _LyricsMirrorEngine engine;
    late InMemoryStore values;
    late ProviderContainer c;

    Future<void> startWith(NowPlayingFakeSource source) async {
      engine = _LyricsMirrorEngine();
      values = InMemoryStore();
      final profileRepo = ProfileRepository(values, InMemoryStore());
      await profileRepo.add(provider: MediaId.providerSubsonic, displayName: 'Home', credentials: _creds);
      c = ProviderContainer(overrides: [
        playbackEngineProvider.overrideWithValue(engine),
        keyValueStoreProvider.overrideWithValue(values),
        secretStoreProvider.overrideWithValue(values),
        profileRepositoryProvider.overrideWithValue(profileRepo),
        activeSourceProvider.overrideWith((ref) async => source),
        lyricsRegistryProvider.overrideWithValue(LyricsProviderRegistry(providers: const [])),
      ]);
      c.listen(playbackControllerProvider, (_, _) {});
      await c.read(activeSourceProvider.future);
      await pumpEventQueue();
    }

    tearDown(() => c.dispose());

    test('should_pushFormattedLrcToSystemSession_when_trackStartsPlaying', () async {
      final tracks = playbackTracks(2);
      final source = NowPlayingFakeSource(lyrics: {
        tracks[0].id.rawId: const SyncedLyrics([
          LyricLine(1200, 'First line'),
          LyricLine(5500, '第二句'),
        ]),
        tracks[1].id.rawId: const UnsyncedLyrics('Plain text only'),
      });
      await startWith(source);

      await c.read(playbackControllerProvider.notifier).playTracks(tracks);
      engine.echo(index: 0);
      await settle();

      expect(engine.lyrics, [(tracks[0].id.toString(), '[00:01.20]First line\r\n[00:05.50]第二句')]);

      engine.echo(index: 1);
      await settle();

      expect(engine.lyrics.last, (tracks[1].id.toString(), null));
    });

    test('should_updateSystemLyricsImmediately_when_userAppliesLyrics', () async {
      final tracks = playbackTracks(1);
      await startWith(NowPlayingFakeSource(lyrics: const {}));

      await c.read(playbackControllerProvider.notifier).playTracks(tracks);
      engine.echo(index: 0);
      await settle();
      expect(engine.lyrics, [(tracks[0].id.toString(), null)]);

      await c.read(lyricsRepositoryProvider).applyLyrics(tracks[0], '[00:10.00]Picked line');
      await settle();

      expect(engine.lyrics.last, (tracks[0].id.toString(), '[00:10.00]Picked line'));
    });

    test('should_dropStaleFetchResult_when_trackSkipsBeforeLyricsLand', () async {
      final tracks = playbackTracks(2);
      final slowFirst = Completer<void>();
      final source = _GateableLyricsSource(
        lyrics: {
          tracks[0].id.rawId: const SyncedLyrics([LyricLine(1000, 'Stale first')]),
          tracks[1].id.rawId: const SyncedLyrics([LyricLine(2000, 'Second track')]),
        },
        gateForRawId: tracks[0].id.rawId,
        gate: slowFirst.future,
      );
      await startWith(source);

      await c.read(playbackControllerProvider.notifier).playTracks(tracks);
      engine.echo(index: 0);
      await settle();

      engine.echo(index: 1);
      await settle();

      slowFirst.complete();
      await settle();

      expect(engine.lyrics, [(tracks[1].id.toString(), '[00:02.00]Second track')]);
    });

    test('should_deduplicateInFlightLoadAndSyncOnApplySwitch_when_concurrentlyFetched', () async {
      final tracks = playbackTracks(1);
      final gate = Completer<void>();
      final source = _GateableLyricsSource(
        lyrics: {tracks[0].id.rawId: const SyncedLyrics([LyricLine(1000, 'Original line')])},
        gateForRawId: tracks[0].id.rawId,
        gate: gate.future,
      );
      await startWith(source);

      await c.read(playbackControllerProvider.notifier).playTracks(tracks);
      engine.echo(index: 0);
      await settle();

      final secondRead = c.read(lyricsRepositoryProvider).getLoadedLyrics(tracks[0], source);
      expect(source.getLyricsCalls, 1);

      gate.complete();
      expect((await secondRead)?.sheet.rows.single.text, 'Original line');
      await settle();
      expect(engine.lyrics, [(tracks[0].id.toString(), '[00:01.00]Original line')]);

      const switchedRaw = '[00:03.00]Switched line';
      await c.read(lyricsRepositoryProvider).applySwitch(
            tracks[0],
            const LyricsSwitchOffer(
              providerName: 'netease',
              providerSongId: '99',
              sheet: LyricSheet([LyricRow('Switched line', startMs: 3000)]),
              rawLrc: switchedRaw,
              translations: {},
            ),
          );
      await settle();

      expect(engine.lyrics.last, (tracks[0].id.toString(), switchedRaw));
    });

    test('should_forwardSystemLyricsThroughPlaybackRouter_when_routerWrapsEngine', () async {
      final tracks = playbackTracks(1);
      final source = NowPlayingFakeSource(lyrics: {
        tracks[0].id.rawId: const SyncedLyrics([LyricLine(1000, 'Routed line')]),
      });
      engine = _LyricsMirrorEngine();
      values = InMemoryStore();
      final profileRepo = ProfileRepository(values, InMemoryStore());
      await profileRepo.add(provider: MediaId.providerSubsonic, displayName: 'Home', credentials: _creds);
      final router = PlaybackRouter(engine);
      c = ProviderContainer(overrides: [
        playbackEngineProvider.overrideWithValue(router),
        keyValueStoreProvider.overrideWithValue(values),
        secretStoreProvider.overrideWithValue(values),
        profileRepositoryProvider.overrideWithValue(profileRepo),
        activeSourceProvider.overrideWith((ref) async => source),
        lyricsRegistryProvider.overrideWithValue(LyricsProviderRegistry(providers: const [])),
      ]);
      c.listen(playbackControllerProvider, (_, _) {});
      await c.read(activeSourceProvider.future);
      await pumpEventQueue();

      await c.read(playbackControllerProvider.notifier).playTracks(tracks);
      engine.echo(index: 0);
      await settle();

      expect(engine.lyrics, [(tracks[0].id.toString(), '[00:01.00]Routed line')]);

      await c.read(lyricsRepositoryProvider).applyLyrics(tracks[0], '[00:02.00]Updated via router');
      await settle();

      expect(engine.lyrics.last, (tracks[0].id.toString(), '[00:02.00]Updated via router'));
    });
  });

  test('should_invokeSetLyricMethodChannel_when_channelPlaybackEngineUsed', () async {
    TestWidgetsFlutterBinding.ensureInitialized();
    const channel = MethodChannel(ChannelPlaybackEngine.methodChannelName);
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return null;
    });
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, null),
    );

    final engine = ChannelPlaybackEngine();
    await engine.setLyric('subsonic:1', '[00:01.00]Hello');
    await engine.setLyric('subsonic:1', null);

    expect(calls.map((c) => c.method), ['setLyric', 'setLyric']);
    expect(calls[0].arguments, {'id': 'subsonic:1', 'lyric': '[00:01.00]Hello'});
    expect(calls[1].arguments, {'id': 'subsonic:1', 'lyric': null});
  });
}
