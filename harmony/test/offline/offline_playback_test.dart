import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/detail/detail_cache.dart';
import 'package:yoin/offline/offline_providers.dart';
import 'package:yoin/playback/playback_controller.dart';

import '../support/fake_music_source.dart';
import '../support/fake_playback_engine.dart';
import '../support/playback_fakes.dart';
import 'offline_fakes.dart';

/// A source that downloads, and whose server can be unreachable.
class _Source extends FakeMusicSource implements MusicDownloads {
  _Source({this.server});
  final Album? server;

  @override
  Set<Capability> get capabilities => const {Capability.offlineDownload};

  @override
  Uri? downloadUri(MediaId trackId, {int? maxBitRateKbps, String? format}) => null;

  @override
  Future<Album?> getAlbum(MediaId id) async {
    final album = server;
    if (album == null) throw const SourceNetworkException('offline');
    return album;
  }
}

/// Downloads that hold t1 and one album.
class _Kept extends FakeOfflineDownloads {
  @override
  String? playableUri(MediaId songId) =>
      songId == MediaId.subsonic('t1') ? 'file:///data/storage/files/offline_media/t1.mp3' : null;

  @override
  Album? offlineAlbum(MediaId id) => id == MediaId.subsonic('al1')
      ? Album(
          id: id,
          name: 'Kept',
          tracks: [Track(id: MediaId.subsonic('t1'))],
        )
      : null;
}

void main() {
  test('should_playTheDownloadedFile_when_aSongIsKeptOnTheDevice', () async {
    final engine = FakePlaybackEngine();
    final c = ProviderContainer(
      overrides: [
        playbackEngineProvider.overrideWithValue(engine),
        activeSourceProvider.overrideWith((ref) async => _Source()),
        offlineDownloadsProvider.overrideWithValue(_Kept()),
      ],
    );
    addTearDown(c.dispose);
    c.listen(playbackControllerProvider, (_, _) {});
    await c.read(playbackControllerProvider.notifier).playTracks(playbackTracks(3));
    expect(engine.queue.map((i) => i.uri), [
      'https://stream.test/t0',
      'file:///data/storage/files/offline_media/t1.mp3',
      'https://stream.test/t2',
    ]);
  });

  test('should_streamEverything_when_theSourceCannotDownload', () async {
    final engine = FakePlaybackEngine();
    final c = ProviderContainer(
      overrides: [
        playbackEngineProvider.overrideWithValue(engine),
        activeSourceProvider.overrideWith((ref) async => FakeMusicSource()),
        offlineDownloadsProvider.overrideWithValue(_Kept()),
      ],
    );
    addTearDown(c.dispose);
    c.listen(playbackControllerProvider, (_, _) {});
    await c.read(playbackControllerProvider.notifier).playTracks(playbackTracks(2));
    expect(engine.queue.map((i) => i.uri).every((u) => u.startsWith('https://')), isTrue);
  });

  test('should_openTheDownloadedAlbum_when_theServerCannotBeReached', () async {
    final c = ProviderContainer(
      overrides: [
        activeSourceProvider.overrideWith((ref) async => _Source()),
        offlineDownloadsProvider.overrideWithValue(_Kept()),
      ],
    );
    addTearDown(c.dispose);
    final cache = c.read(detailCacheProvider);
    final album = await cache.album(MediaId.subsonic('al1'));
    expect(album!.name, 'Kept');
    // Not kept as the server's answer: the next read asks the server again.
    expect(cache.cachedAlbum(MediaId.subsonic('al1')), isNull);
    // An album that wasn't downloaded still fails.
    await expectLater(cache.album(MediaId.subsonic('other')), throwsA(isA<SourceNetworkException>()));
  });

  test('should_preferTheServer_when_itAnswers', () async {
    final server = Album(id: MediaId.subsonic('al1'), name: 'Server');
    final c = ProviderContainer(
      overrides: [
        activeSourceProvider.overrideWith((ref) async => _Source(server: server)),
        offlineDownloadsProvider.overrideWithValue(_Kept()),
      ],
    );
    addTearDown(c.dispose);
    expect((await c.read(detailCacheProvider).album(MediaId.subsonic('al1')))!.name, 'Server');
  });
}
