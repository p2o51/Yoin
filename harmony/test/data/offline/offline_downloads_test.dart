import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/offline/offline_downloads.dart';
import 'package:yoin/data/offline/offline_model.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/platform/network/network_bearer.dart';
import 'package:yoin/platform/storage/app_files.dart';
import 'package:yoin/platform/storage/stores.dart';

/// Signs addresses like Subsonic's stream.view, with the account's name in them.
final class _Signer implements MusicDownloads {
  _Signer(this.account);
  final String account;

  @override
  Uri? downloadUri(MediaId trackId, {int? maxBitRateKbps, String? format}) => Uri.parse(
    'https://srv.test/rest/stream?u=$account&id=${trackId.rawId}'
    '${maxBitRateKbps == null ? '' : '&maxBitRate=$maxBitRateKbps&format=$format'}',
  );
}

Track _track(String id, {int? no, int? disc}) => Track(
  id: MediaId.subsonic(id),
  title: 'Song $id',
  artist: 'Mono',
  album: 'Hymn',
  albumId: MediaId.subsonic('al1'),
  coverArt: const CoverSourceRelative('al-1'),
  durationSec: 200,
  trackNumber: no,
  discNumber: disc,
);

final _album = OfflineCollection.album(
  Album(id: MediaId.subsonic('al1'), name: 'Hymn', artist: 'Mono', coverArt: const CoverSourceRelative('al-1')),
);
final _playlist = OfflineCollection.playlist(
  Playlist(id: MediaId.subsonic('pl1'), name: 'Night', owner: 'me', coverArt: const CoverSourceRelative('pl-1')),
);

void main() {
  late Directory root;
  late List<Uri> requests;
  late NetworkBearer bearer;
  late OfflineAccount? account;
  late InMemoryStore values;
  late Future<http.Response> Function(http.Request request) respond;
  final made = <OfflineDownloads>[];

  OfflineDownloads make({int maxRetries = 5}) {
    final d = OfflineDownloads(
      files: DirectoryAppFilesHost(root),
      account: () async => account,
      bearer: () async => bearer,
      settingsValues: () => values,
      client: MockClient((request) {
        requests.add(request.url);
        return respond(request);
      }),
      retryDelay: const Duration(milliseconds: 20),
      maxRetries: maxRetries,
    );
    made.add(d);
    return d;
  }

  Future<void> until(bool Function() done) async {
    for (var i = 0; i < 500 && !done(); i++) {
      await Future<void>.delayed(const Duration(milliseconds: 2));
    }
    expect(done(), isTrue, reason: 'timed out');
  }

  bool allDone(OfflineDownloads d) =>
      d.state.songs.isNotEmpty && d.state.songs.every((s) => s.state == OfflineSongState.done);

  setUp(() async {
    root = await Directory.systemTemp.createTemp('yoin_offline_test');
    requests = [];
    bearer = NetworkBearer.wifi;
    account = OfflineAccount(profileId: 'p1', provider: MediaId.providerSubsonic, downloads: _Signer('alice'));
    values = InMemoryStore();
    respond = (request) async => http.Response.bytes(
      utf8.encode('audio:${request.url.queryParameters['id']}'),
      200,
      headers: {'content-type': 'audio/mpeg'},
    );
  });

  tearDown(() async {
    for (final d in made) {
      await d.saved;
      d.dispose();
    }
    made.clear();
    await root.delete(recursive: true);
  });

  test('should_keepEverySongInAFile_when_albumDownloaded', () async {
    final d = make()..setActiveProfile('p1');
    await d.start();
    final queued = await d.download([_track('s1'), _track('s2')], _album);
    expect(queued, 2);
    await until(() => allDone(d));

    expect(d.state.library.statusOf(_album.key), const OfflineDownloaded());
    expect(d.state.library.isDownloaded(MediaId.subsonic('s1').toString()), isTrue);
    final uri = d.playableUri(MediaId.subsonic('s2'));
    expect(uri, startsWith('file://'));
    expect(await File.fromUri(Uri.parse(uri!)).readAsString(), 'audio:s2');
    // 320 kbps by default: a capped mp3, as streaming asks for it.
    expect(requests.first.queryParameters, containsPair('maxBitRate', '320'));
    expect(requests.first.queryParameters, containsPair('format', 'mp3'));
    expect(d.state.downloadBytes, 'audio:s1'.length + 'audio:s2'.length);
  });

  test('should_askForTheFile_when_qualityIsOriginal', () async {
    final d = make()..setActiveProfile('p1');
    await d.start();
    await d.setQuality(DownloadQuality.original);
    await d.download([_track('s1')], _album);
    await until(() => allDone(d));
    expect(requests.single.queryParameters.containsKey('maxBitRate'), isFalse);
    // The device remembers it.
    expect(values.values[DownloadSettingsStore.qualityKey], 'original');
  });

  test('should_keepASharedSong_when_oneOfItsDownloadsIsRemoved', () async {
    final d = make()..setActiveProfile('p1');
    await d.start();
    await d.download([_track('s1'), _track('s2')], _album);
    await d.download([_track('s2'), _track('s3')], _playlist);
    await until(() => allDone(d));
    // s2 is fetched once, held by both.
    expect(requests.where((u) => u.queryParameters['id'] == 's2'), hasLength(1));

    await d.remove(_album.key);
    expect(d.state.library.statusOf(_album.key), const OfflineNone());
    expect(d.playableUri(MediaId.subsonic('s1')), isNull);
    expect(d.playableUri(MediaId.subsonic('s2')), isNotNull);
    expect(d.state.library.songsOf(_playlist.key).map((s) => s.track.id.rawId), ['s2', 's3']);
    expect(
      root
          .listSync(recursive: true)
          .whereType<File>()
          .where((f) => f.path.endsWith('.mp3') || f.path.endsWith('.part')),
      hasLength(2),
    );
  });

  test('should_waitForWifi_when_onMobileDataAndItIsNotAllowed', () async {
    bearer = NetworkBearer.cellular;
    final d = make()..setActiveProfile('p1');
    await d.start();
    await d.download([_track('s1')], _album);
    expect(await d.waitsForWifi(), isTrue);
    await until(() => d.state.songs.single.state == OfflineSongState.waiting);
    expect(requests, isEmpty);
    expect(d.state.library.statusOf(_album.key), const OfflineDownloading(0, 1));

    // Allowing mobile data lets it run.
    await d.setOverMobileData(true);
    expect(await d.waitsForWifi(), isFalse);
    await until(() => allDone(d));
  });

  test('should_startOnItsOwn_when_wifiComesBack', () async {
    bearer = NetworkBearer.none;
    final d = make()..setActiveProfile('p1');
    await d.start();
    await d.download([_track('s1')], _album);
    await until(() => d.state.songs.single.state == OfflineSongState.waiting);
    bearer = NetworkBearer.wifi;
    await until(() => allDone(d));
  });

  test('should_waitForTheAccount_when_itsSongsBelongToAnotherProfile', () async {
    final d = make()..setActiveProfile('p1');
    await d.start();
    // Queued while p1 was active, then the listener switched to p2 before it ran.
    bearer = NetworkBearer.none;
    await d.download([_track('s1')], _album);
    await until(() => d.state.songs.single.state == OfflineSongState.waiting);
    account = OfflineAccount(profileId: 'p2', provider: MediaId.providerSubsonic, downloads: _Signer('bob'));
    d.setActiveProfile('p2');
    bearer = NetworkBearer.wifi;
    await Future<void>.delayed(const Duration(milliseconds: 60));
    expect(d.state.songs.single.state, OfflineSongState.waiting);
    expect(requests, isEmpty, reason: "another account's song is never fetched with this one's credentials");
    // p2 lists none of p1's downloads.
    expect(d.state.library.entries, isEmpty);

    account = OfflineAccount(profileId: 'p1', provider: MediaId.providerSubsonic, downloads: _Signer('alice'));
    d.setActiveProfile('p1');
    await until(() => allDone(d));
    expect(requests.single.queryParameters['u'], 'alice');
  });

  test('should_queueNothing_when_theAccountCannotDownload', () async {
    account = null;
    final d = make()..setActiveProfile('p1');
    await d.start();
    expect(await d.download([_track('s1')], _album), 0);
    expect(d.state.songs, isEmpty);
  });

  test('should_sayFailedThenRetry_when_theServerRefuses', () async {
    respond = (request) async =>
        http.Response('{"subsonic-response":{"status":"failed"}}', 200, headers: {'content-type': 'application/json'});
    final d = make()..setActiveProfile('p1');
    await d.start();
    await d.download([_track('s1')], _album);
    await until(() => d.state.songs.single.state == OfflineSongState.failed);
    expect(d.state.library.statusOf(_album.key), const OfflineFailed());
    expect(d.playableUri(MediaId.subsonic('s1')), isNull);

    respond = (request) async => http.Response.bytes([1, 2, 3], 200, headers: {'content-type': 'audio/mpeg'});
    expect(await d.download([_track('s1')], _album), 1);
    await until(() => allDone(d));
  });

  test('should_tryAgain_when_theConnectionDrops', () async {
    var calls = 0;
    respond = (request) async {
      if (++calls == 1) throw http.ClientException('connection reset');
      return http.Response.bytes([1, 2, 3], 200, headers: {'content-type': 'audio/mpeg'});
    };
    final d = make()..setActiveProfile('p1');
    await d.start();
    await d.download([_track('s1')], _album);
    await until(() => allDone(d));
    expect(calls, 2);
  });

  test('should_giveUp_when_itKeepsDropping', () async {
    respond = (request) async => throw http.ClientException('connection reset');
    final d = make(maxRetries: 2)..setActiveProfile('p1');
    await d.start();
    await d.download([_track('s1')], _album);
    await until(() => d.state.songs.single.state == OfflineSongState.failed);
    expect(requests, hasLength(3));
  });

  test('should_stopAndDeleteIt_when_removedWhileDownloading', () async {
    final gate = Completer<void>();
    respond = (request) async {
      await gate.future;
      return http.Response.bytes([1, 2, 3], 200, headers: {'content-type': 'audio/mpeg'});
    };
    final d = make()..setActiveProfile('p1');
    await d.start();
    await d.download([_track('s1')], _album);
    await until(() => d.state.songs.single.state == OfflineSongState.downloading);
    await d.remove(_album.key);
    gate.complete();
    await Future<void>.delayed(const Duration(milliseconds: 30));
    expect(d.state.songs, isEmpty);
    expect(
      root
          .listSync(recursive: true)
          .whereType<File>()
          .where((f) => f.path.endsWith('.mp3') || f.path.endsWith('.part')),
      isEmpty,
    );
  });

  test('should_carryOnFromTheIndex_when_theAppStartsAgain', () async {
    bearer = NetworkBearer.none;
    final first = make()..setActiveProfile('p1');
    await first.start();
    await first.download([_track('s1'), _track('s2')], _album);
    await first.saved;
    first.dispose();
    made.remove(first);

    bearer = NetworkBearer.wifi;
    final second = make()..setActiveProfile('p1');
    await second.start();
    expect(second.state.library.statusOf(_album.key), isA<OfflineDownloading>());
    await until(() => allDone(second));
    await second.saved;

    final third = make()..setActiveProfile('p1');
    await third.start();
    expect(third.state.library.statusOf(_album.key), const OfflineDownloaded());
    expect(third.state.songs.first.track.title, 'Song s1');
    expect(third.playableUri(MediaId.subsonic('s1')), isNotNull);
  });

  test('should_emptyEveryAccount_when_removeAll', () async {
    final d = make()..setActiveProfile('p1');
    await d.start();
    await d.download([_track('s1')], _album);
    await until(() => allDone(d));
    await d.removeAll();
    expect(d.state.songs, isEmpty);
    expect(d.state.downloadBytes, 0);
    expect(d.playableUri(MediaId.subsonic('s1')), isNull);
  });

  test('should_readTheAlbumInTrackOrder_when_itIsOpenedOffline', () async {
    bearer = NetworkBearer.none;
    final d = make()..setActiveProfile('p1');
    await d.start();
    await d.download([_track('b', no: 2), _track('a', no: 1), _track('c', no: 1, disc: 2)], _album);
    final album = d.offlineAlbum(MediaId.subsonic('al1'))!;
    expect(album.name, 'Hymn');
    expect(album.artist, 'Mono');
    expect(album.coverArt, const CoverSourceRelative('al-1'));
    expect(album.tracks.map((t) => t.id.rawId), ['a', 'b', 'c']);
    expect(d.offlineAlbum(MediaId.subsonic('other')), isNull);
    expect(d.offlinePlaylist(MediaId.subsonic('pl1')), isNull);
  });

  test('should_keepNothing_when_thereIsNoFilesFolder', () async {
    final d = OfflineDownloads(files: const NoAppFilesHost(), account: () async => account, bearer: () async => bearer);
    made.add(d);
    d.setActiveProfile('p1');
    await d.start();
    expect(d.available, isFalse);
    expect(await d.download([_track('s1')], _album), 0);
  });
}
