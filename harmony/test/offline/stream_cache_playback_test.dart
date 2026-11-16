import 'dart:io';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/offline/offline_providers.dart';
import 'package:yoin/offline/stream_cache_providers.dart';
import 'package:yoin/platform/storage/app_files.dart';
import 'package:yoin/playback/playback_controller.dart';

import '../support/fake_music_source.dart';
import '../support/fake_playback_engine.dart';
import '../support/playback_fakes.dart';
import 'offline_fakes.dart';

void main() {
  late Directory root;

  setUp(() async => root = await Directory.systemTemp.createTemp('yoin_stream_cache_playback'));

  tearDown(() async {
    if (await root.exists()) await root.delete(recursive: true);
  });

  test('should_streamThroughTheCache_when_theAppKeepsACache', () async {
    final engine = FakePlaybackEngine();
    final c = ProviderContainer(
      overrides: [
        playbackEngineProvider.overrideWithValue(engine),
        activeSourceProvider.overrideWith((ref) async => FakeMusicSource()),
        offlineDownloadsProvider.overrideWithValue(FakeOfflineDownloads()),
        appFilesHostProvider.overrideWithValue(DirectoryAppFilesHost(root)),
      ],
    );
    addTearDown(c.dispose);
    c.listen(playbackControllerProvider, (_, _) {});
    await c.read(playbackControllerProvider.notifier).playTracks(playbackTracks(2));
    final origin = c.read(streamCacheProxyProvider).origin!;
    expect(engine.queue.every((i) => i.cacheUri!.startsWith('$origin/')), isTrue);
    expect(engine.queue[0].cacheUri, isNot(engine.queue[1].cacheUri));
    // The server's address stays beside it: the native player falls back to it.
    expect(engine.queue.map((i) => i.uri), ['https://stream.test/t0', 'https://stream.test/t1']);
    expect(engine.queue[0].toMap(), containsPair('cacheUri', engine.queue[0].cacheUri));
    await c.read(streamCacheProvider).saved;
  });

  test('should_streamDirectly_when_thereIsNoCacheFolder', () async {
    final engine = FakePlaybackEngine();
    final c = ProviderContainer(
      overrides: [
        playbackEngineProvider.overrideWithValue(engine),
        activeSourceProvider.overrideWith((ref) async => FakeMusicSource()),
        offlineDownloadsProvider.overrideWithValue(FakeOfflineDownloads()),
      ],
    );
    addTearDown(c.dispose);
    c.listen(playbackControllerProvider, (_, _) {});
    await c.read(playbackControllerProvider.notifier).playTracks(playbackTracks(2));
    expect(engine.queue.every((i) => i.cacheUri == null), isTrue);
    expect(engine.queue[0].toMap().containsKey('cacheUri'), isFalse);
  });
}
