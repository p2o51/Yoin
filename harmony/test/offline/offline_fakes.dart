import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/offline/offline_downloads.dart';
import 'package:yoin/data/offline/offline_model.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/platform/network/network_bearer.dart';
import 'package:yoin/platform/storage/app_files.dart';

import '../support/nowplaying_fakes.dart';

/// A Subsonic-like source that downloads (Capability.offlineDownload).
class DownloadingFakeSource extends NowPlayingFakeSource implements MusicDownloads {
  @override
  Set<Capability> get capabilities => const {
    Capability.lyrics,
    Capability.favorites,
    Capability.rating,
    Capability.offlineDownload,
  };

  @override
  Uri? downloadUri(MediaId trackId, {int? maxBitRateKbps, String? format}) =>
      Uri.parse('https://srv.test/stream?id=${trackId.rawId}');
}

/// Downloads with no files: records what the UI asks and shows [fake] as its state.
class FakeOfflineDownloads extends OfflineDownloads {
  FakeOfflineDownloads({List<OfflineSong> songs = const [], this.waitsOnWifi = false})
    : fake = OfflineState(songs: songs, activeProfileId: 'p1', ready: true),
      super(files: const NoAppFilesHost(), account: () async => null, bearer: () async => NetworkBearer.wifi);

  OfflineState fake;
  bool waitsOnWifi;
  final List<String> calls = [];

  @override
  OfflineState get state => fake;

  void show(OfflineState next) {
    fake = next;
    notifyListeners();
  }

  @override
  Future<void> start() async {}

  @override
  Future<int> download(List<Track> tracks, OfflineCollection collection) async {
    calls.add('download ${collection.key} ${tracks.map((t) => t.id.rawId).join(',')}');
    show(
      OfflineState(
        songs: [
          ...fake.songs,
          for (final t in tracks) offlineSong(t, [collection], state: OfflineSongState.queued),
        ],
        activeProfileId: fake.activeProfileId,
        settings: fake.settings,
        ready: true,
      ),
    );
    return tracks.length;
  }

  @override
  Future<void> remove(String collectionKey) async => calls.add('remove $collectionKey');

  @override
  Future<void> removeAll() async => calls.add('removeAll');

  @override
  Future<void> setQuality(DownloadQuality quality) async {
    calls.add('quality ${quality.key}');
    show(
      OfflineState(
        songs: fake.songs,
        activeProfileId: 'p1',
        settings: fake.settings.copyWith(quality: quality),
      ),
    );
  }

  @override
  Future<void> setOverMobileData(bool allowed) async {
    calls.add('mobile $allowed');
    show(
      OfflineState(
        songs: fake.songs,
        activeProfileId: 'p1',
        settings: fake.settings.copyWith(overMobileData: allowed),
      ),
    );
  }

  @override
  Future<bool> waitsForWifi() async => waitsOnWifi;
}

OfflineSong offlineSong(
  Track track,
  List<OfflineCollection> collections, {
  OfflineSongState state = OfflineSongState.done,
  int bytes = 10,
  int order = 0,
}) => OfflineSong(
  profileId: 'p1',
  songId: track.id.toString(),
  track: track,
  quality: DownloadQuality.kbps320,
  collections: collections,
  order: order,
  state: state,
  bytes: bytes,
);
