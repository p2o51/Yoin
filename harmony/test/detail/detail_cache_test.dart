import 'dart:async';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/applemusic/apple_music_source.dart';
import 'package:yoin/data/cache/detail_cache_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/detail/detail_cache.dart';

import '../support/library_fake_source.dart';

Future<void> settle() async {
  for (var i = 0; i < 30; i++) {
    await Future<void>.delayed(Duration.zero);
  }
}

void main() {
  late Directory temp;
  late LibraryFakeSource source;
  late DetailCacheStore disk;
  var now = 5000000;
  final id = MediaId.subsonic('al1');
  final pid = MediaId.subsonic('pl1');

  setUp(() {
    temp = Directory.systemTemp.createTempSync('yoin_detail_cache');
    now = 5000000;
    source = LibraryFakeSource(
      albumDetails: {id: Album(id: id, name: 'Blue')},
      playlistDetails: {pid: Playlist(id: pid, name: 'Mix')},
    );
    disk = DetailCacheStore(() async => temp, clock: () => now);
  });
  tearDown(() => temp.deleteSync(recursive: true));

  DetailCache cache({Future<String?> Function()? profileId}) =>
      DetailCache(() async => source, disk: disk, profileId: profileId ?? () async => 'p1', clock: () => now);

  int reads(String name) => source.calls.where((c) => c == name).length;

  test('should_shareOneRead_when_prefetchAndPageRace', () async {
    source.albumGate = Completer();
    final c = cache();
    final a = c.album(id);
    final b = c.album(id);
    source.albumGate!.complete();
    expect((await a)!.name, 'Blue');
    expect((await b)!.name, 'Blue');
    expect(reads('getAlbum'), 1);
  });

  test('should_serveDiskWithoutNetwork_when_coldStartWithinWeek', () async {
    await cache().album(id);
    await settle();
    await disk.flush();
    // a new launch: empty memory, the server unreachable
    source.failures['getAlbum'] = const SocketException('down');
    now += 3 * 24 * 60 * 60 * 1000;
    final fresh = DetailCache(
      () async => source,
      disk: DetailCacheStore(() async => temp, clock: () => now),
      profileId: () async => 'p1',
      clock: () => now,
    );
    expect((await fresh.album(id))!.name, 'Blue');
    expect(reads('getAlbum'), 1); // the first fetch only (+ the background refresh past 2h, which failed)
  });

  test('should_refreshInBackground_when_diskCopyPastRevalidateAge', () async {
    await cache().album(id);
    await settle();
    source.albumDetails[id] = Album(id: id, name: 'Blue (Remastered)');
    now += DetailCache.revalidateAfterMs + 1;
    final next = cache();
    expect((await next.album(id))!.name, 'Blue'); // served from disk at once
    await settle();
    expect(next.cachedAlbum(id)!.name, 'Blue (Remastered)'); // converged in the background
  });

  test('should_pauseRevalidation_when_appleSourceRateLimits', () async {
    final id2 = MediaId.subsonic('al2');
    source.albumDetails[id2] = Album(id: id2, name: 'Green');
    final first = cache();
    await first.album(id);
    await first.album(id2);
    await settle();
    await disk.flush();
    now += DetailCache.revalidateAfterMs + 1;
    // As AppleMusicSource throws it: the interface's exception, Apple's refusal its cause.
    source.failures['getAlbum'] = sourceExceptionFor(
      const AppleMusicApiException(AppleMusicFailure.rateLimited, status: 429),
    );
    final next = cache();
    expect((await next.album(id))!.name, 'Blue');
    await settle();
    expect(reads('getAlbum'), 3); // the background refresh, refused
    expect((await next.album(id2))!.name, 'Green');
    await settle();
    expect(reads('getAlbum'), 3); // paused: no refresh sent while Apple says it's busy
  });

  test('should_revalidatePlaylist_when_reopenedAfterHandoff', () async {
    final c = cache();
    await c.playlist(pid);
    await c.playlist(pid); // within the hand-off: the tap's own fetch
    expect(reads('getPlaylist'), 1);
    now += DetailCache.handoffMs + 1;
    await c.playlist(pid);
    expect(reads('getPlaylist'), 2);
  });

  test('should_fallBackToDisk_when_playlistFetchFails', () async {
    await cache().playlist(pid);
    await settle();
    source.failures['getPlaylist'] = const SocketException('down');
    now += DetailCache.handoffMs + 1;
    expect((await cache().playlist(pid))!.name, 'Mix');
  });

  test('should_serveCopy_when_noSourceComesUp', () async {
    await cache().playlist(pid);
    await settle();
    now += DetailCache.handoffMs + 1;
    // A cold start whose account never came up: the disk copy, not an error page.
    final sourceless = DetailCache(() async => null, disk: disk, profileId: () async => 'p1', clock: () => now);
    expect((await sourceless.playlist(pid))!.name, 'Mix');
    // Nothing kept anywhere: still the "no account" error.
    await expectLater(sourceless.album(MediaId.subsonic('none')), throwsA(isA<NoActiveSourceException>()));
  });

  test('should_notWriteBack_when_evictedWhileInFlight', () async {
    source.albumGate = Completer();
    final c = cache();
    final load = c.album(id);
    c.evict('album', id);
    source.albumGate!.complete();
    await load;
    await settle();
    expect(c.cachedAlbum(id), isNull);
    expect(await disk.readAlbum('p1', id.toString()), isNull);
  });

  test('should_dropDiskCopy_when_evicted', () async {
    final c = cache();
    await c.album(id);
    await settle();
    c.evict('album', id);
    await settle();
    expect(await disk.readAlbum('p1', id.toString()), isNull);
    await c.album(id);
    expect(reads('getAlbum'), 2);
  });

  test('should_keepAccountsApart_when_cleared', () async {
    var profile = 'p1';
    final c = cache(profileId: () async => profile);
    await c.album(id);
    await settle();
    c.clear();
    profile = 'p2';
    source.albumDetails[id] = Album(id: id, name: 'Other account');
    expect((await c.album(id))!.name, 'Other account');
  });
}
