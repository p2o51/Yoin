import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/home/home_snapshot_store.dart';

void main() {
  late Directory temp;
  setUp(() => temp = Directory.systemTemp.createTempSync('yoin_home_snapshot'));
  tearDown(() => temp.deleteSync(recursive: true));

  HomeSnapshotStore store({int settleMs = 0, int minWriteIntervalMs = 0}) =>
      HomeSnapshotStore(() async => temp, clock: () => 42, settleMs: settleMs, minWriteIntervalMs: minWriteIntervalMs);

  test('should_readBackFeed_when_saved', () async {
    final s = store();
    s.save(
      'p1',
      'subsonic',
      feed: () => {
        'playlists': ['a'],
      },
    );
    await s.whenIdle();
    final read = await store().read('p1');
    expect(read!.provider, 'subsonic');
    expect(read.savedAt, 42);
    expect(read.feed['playlists'], ['a']);
    expect(await store().read('p2'), isNull);
  });

  test('should_writeLatestOnce_when_burstSettles', () async {
    final s = store(settleMs: 15);
    var built = 0;
    for (var i = 0; i < 5; i++) {
      s.save(
        'p1',
        'subsonic',
        feed: () {
          built++;
          return {'n': i};
        },
      );
    }
    await s.whenIdle();
    expect(built, 1);
    expect((await s.read('p1'))!.feed['n'], 4);
  });

  test('should_keepDisksBlock_when_unread', () async {
    final s = store();
    s.save(
      'p1',
      'subsonic',
      feed: () => {
        HomeFeedKeys.playlists: ['old'],
        HomeFeedKeys.recentlyPlayed: ['r'],
      },
    );
    await s.whenIdle();
    s.save(
      'p1',
      'subsonic',
      unread: {HomeNetworkBlock.playlists},
      feed: () => {
        HomeFeedKeys.playlists: <Object>[],
        HomeFeedKeys.recentlyPlayed: ['r2'],
      },
    );
    await s.whenIdle();
    final feed = (await s.read('p1'))!.feed;
    expect(feed[HomeFeedKeys.playlists], ['old']);
    expect(feed[HomeFeedKeys.recentlyPlayed], ['r2']);
  });

  test('should_notKeepOtherProvidersBlock_when_unread', () async {
    final s = store();
    s.save(
      'p1',
      'subsonic',
      feed: () => {
        HomeFeedKeys.playlists: ['old'],
      },
    );
    await s.whenIdle();
    s.save('p1', 'applemusic', unread: {HomeNetworkBlock.playlists}, feed: () => {HomeFeedKeys.playlists: <Object>[]});
    await s.whenIdle();
    expect((await s.read('p1'))!.feed[HomeFeedKeys.playlists], isEmpty);
  });

  test('should_dropFile_when_versionOrProfileWrong', () async {
    final dir = Directory('${temp.path}/${HomeSnapshotStore.directoryName}')..createSync();
    final file = File('${dir.path}/p1.json')
      ..writeAsStringSync('{"version":99,"profileId":"p1","provider":"x","savedAt":1,"feed":{}}');
    expect(await store().read('p1'), isNull);
    expect(file.existsSync(), isFalse);
    File('${dir.path}/p1.json').writeAsStringSync('{not json');
    expect(await store().read('p1'), isNull);
  });

  test('should_stopWriting_when_profileDeleted', () async {
    final s = store(settleMs: 20);
    s.save('p1', 'subsonic', feed: () => {'a': 1});
    await s.delete('p1');
    await s.whenIdle();
    expect(await s.read('p1'), isNull);
  });

  test('should_hashStem_when_profileIdUnsafe', () {
    expect(HomeSnapshotStore.fileStem('abc-123_X'), 'abc-123_X');
    expect(HomeSnapshotStore.fileStem('a/b'), matches(RegExp(r'^p[0-9a-f]{16}$')));
  });

  group('credentialFreeCoverKey', () {
    test('should_keepIdsAndPlainUrls_when_noCredentials', () {
      expect(credentialFreeCoverKey('al-1'), 'al-1');
      expect(credentialFreeCoverKey('https://is1.mzstatic.com/a/600x600bb.jpg'), isNotNull);
      expect(credentialFreeCoverKey('https://x.test/c?size=300'), 'https://x.test/c?size=300');
    });

    test('should_dropUrl_when_itCarriesAuth', () {
      expect(credentialFreeCoverKey('https://music.test/rest/getCoverArt?id=1&u=me&t=abc&s=salt'), isNull);
      expect(credentialFreeCoverKey('https://x.test/c?apiKey=1'), isNull);
      expect(credentialFreeCoverKey('  '), isNull);
    });
  });
}
