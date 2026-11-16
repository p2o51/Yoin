import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/offline/offline_downloads.dart';
import 'package:yoin/data/offline/offline_model.dart';
import 'package:yoin/offline/offline_background.dart';
import 'package:yoin/offline/offline_strings.dart';
import 'package:yoin/platform/background/background_transfer.dart';

import 'offline_fakes.dart';

const _album = OfflineCollection(kind: OfflineCollectionKind.album, id: 'subsonic:al1', title: 'Album');

OfflineSong _song(String id, OfflineSongState state, {int bytes = 0, int? total}) => OfflineSong(
  profileId: 'p1',
  songId: MediaId.subsonic(id).toString(),
  track: Track(id: MediaId.subsonic(id)),
  quality: DownloadQuality.kbps320,
  collections: const [_album],
  order: 0,
  state: state,
  bytes: bytes,
  totalBytes: total,
);

class _Host implements BackgroundTransferHost {
  final notices = <TransferNotice>[];
  int releases = 0;
  bool refuse = false;
  final _ended = StreamController<void>.broadcast();

  void end() => _ended.add(null);

  @override
  Future<bool> hold(TransferNotice notice) async {
    if (refuse) return false;
    notices.add(notice);
    return true;
  }

  @override
  Future<void> release() async => releases++;

  @override
  Stream<void> get ended => _ended.stream;
}

OfflineState _state(List<OfflineSong> songs) => OfflineState(songs: songs, activeProfileId: 'p1', ready: true);

void main() {
  const en = OfflineStrings.forLanguage(chinese: false);

  group('transferProgress', () {
    test('should_reportNothing_when_noSongRuns', () {
      final songs = [_song('a', OfflineSongState.done), _song('b', OfflineSongState.waiting)];
      expect(transferProgress(songs, {}), isNull);
    });

    test('should_countFinishedAndPartlyDownloadedSongs_when_aBatchRuns', () {
      final songs = [
        _song('a', OfflineSongState.done),
        _song('b', OfflineSongState.downloading, bytes: 50, total: 100),
        _song('c', OfflineSongState.queued),
        _song('d', OfflineSongState.done),
      ];
      // a ran in this batch; d was downloaded long before and doesn't count.
      final batch = {songs[0].key, songs[1].key, songs[2].key};
      expect(transferProgress(songs, batch), const TransferProgress(done: 1, total: 3, percent: 50));
    });

    test('should_stayBelowAHundred_when_theLastSongIsAlmostDone', () {
      final songs = [_song('a', OfflineSongState.downloading, bytes: 999, total: 1000)];
      expect(transferProgress(songs, {songs[0].key})!.percent, 99);
    });
  });

  group('OfflineTransferKeeper', () {
    late FakeOfflineDownloads downloads;
    late _Host host;
    late OfflineTransferKeeper keeper;
    var clock = DateTime(2026, 11, 16);

    setUp(() {
      clock = DateTime(2026, 11, 16);
      downloads = FakeOfflineDownloads();
      host = _Host();
      keeper = OfflineTransferKeeper(
        downloads: downloads,
        host: host,
        strings: () => en,
        updateInterval: const Duration(seconds: 1),
        now: () => clock,
      )..start();
    });

    tearDown(() => keeper.dispose());

    test('should_holdTheTaskWithItsProgress_when_songsStartDownloading', () async {
      expect(keeper.held, isFalse);
      downloads.show(_state([_song('a', OfflineSongState.downloading), _song('b', OfflineSongState.queued)]));
      await keeper.settled;
      expect(keeper.held, isTrue);
      expect(host.notices.single.title, 'Downloading for offline listening');
      expect(host.notices.single.text, 'Downloading 0 / 2');
      expect(host.notices.single.percent, 0);
    });

    test('should_showAFinishedSongAtOnce_and_throttleByteProgress', () async {
      downloads.show(
        _state([_song('a', OfflineSongState.downloading, total: 100), _song('b', OfflineSongState.queued)]),
      );
      await keeper.settled;
      // Bytes within the second: not redrawn.
      downloads.show(
        _state([_song('a', OfflineSongState.downloading, bytes: 40, total: 100), _song('b', OfflineSongState.queued)]),
      );
      await keeper.settled;
      expect(host.notices, hasLength(1));
      // A song finished: shown at once.
      downloads.show(_state([_song('a', OfflineSongState.done), _song('b', OfflineSongState.downloading)]));
      await keeper.settled;
      expect(host.notices.last.text, 'Downloading 1 / 2');
      expect(host.notices.last.percent, 50);
      // A second later, bytes show too.
      clock = clock.add(const Duration(seconds: 2));
      downloads.show(
        _state([_song('a', OfflineSongState.done), _song('b', OfflineSongState.downloading, bytes: 50, total: 100)]),
      );
      await keeper.settled;
      expect(host.notices.last.percent, 75);
    });

    test('should_releaseTheTask_when_theBatchIsOver', () async {
      downloads.show(_state([_song('a', OfflineSongState.downloading)]));
      await keeper.settled;
      downloads.show(_state([_song('a', OfflineSongState.done)]));
      await keeper.settled;
      expect(keeper.held, isFalse);
      expect(host.releases, 1);
    });

    test('should_releaseTheTask_when_everythingWaitsForWifi', () async {
      downloads.show(_state([_song('a', OfflineSongState.downloading)]));
      await keeper.settled;
      downloads.show(_state([_song('a', OfflineSongState.waiting)]));
      await keeper.settled;
      expect(keeper.held, isFalse, reason: 'a stalled data-transfer task would be cancelled by the system');
      expect(host.releases, 1);
    });

    test('should_notAskAgain_when_theListenerRemovedTheNotification', () async {
      downloads.show(_state([_song('a', OfflineSongState.downloading), _song('b', OfflineSongState.queued)]));
      await keeper.settled;
      host.end();
      await Future<void>.delayed(Duration.zero);
      expect(keeper.held, isFalse);
      downloads.show(_state([_song('a', OfflineSongState.done), _song('b', OfflineSongState.downloading)]));
      await keeper.settled;
      expect(host.notices, hasLength(1), reason: 'not until this batch is over');
      // The batch ends; the next one holds the task again.
      downloads.show(_state([_song('a', OfflineSongState.done), _song('b', OfflineSongState.done)]));
      downloads.show(_state([_song('c', OfflineSongState.downloading)]));
      await keeper.settled;
      expect(host.notices, hasLength(2));
      expect(keeper.held, isTrue);
    });

    test('should_askAgainLater_when_theSystemRefusedTheTask', () async {
      host.refuse = true;
      downloads.show(_state([_song('a', OfflineSongState.downloading)]));
      await keeper.settled;
      expect(keeper.held, isFalse);
      host.refuse = false;
      downloads.show(_state([_song('a', OfflineSongState.downloading, bytes: 1, total: 10)]));
      await keeper.settled;
      expect(host.notices, isEmpty, reason: 'waits out the retry interval');
      clock = clock.add(const Duration(seconds: 31));
      downloads.show(_state([_song('a', OfflineSongState.downloading, bytes: 2, total: 10)]));
      await keeper.settled;
      expect(keeper.held, isTrue);
      expect(host.notices, hasLength(1));
    });

    test('should_releaseTheTask_when_disposed', () async {
      downloads.show(_state([_song('a', OfflineSongState.downloading)]));
      await keeper.settled;
      keeper.dispose();
      await keeper.settled;
      expect(host.releases, 1);
    });
  });

  test('should_sayTheProgressInChinese_when_theSystemSpeaksChinese', () {
    const zh = OfflineStrings.forLanguage(chinese: true);
    expect(zh.notificationDownloading, '正在下载，供离线收听');
    expect(zh.downloading(1, 3), '正在下载 1 / 3');
  });

  test('should_needNoTask_when_theDesktopKeepsRunning', () async {
    const host = NoBackgroundTransferHost();
    expect(await host.hold(const TransferNotice(title: 't', text: 'x', percent: 1)), isTrue);
    await host.release();
  });
}
