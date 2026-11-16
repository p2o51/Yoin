import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/cache/stream_cache.dart';

const _song = StreamCacheSong(provider: 'subsonic', songId: 'subsonic:s1', title: 'One', artist: 'A', album: 'B');

StreamCacheSong _songOf(String id) => StreamCacheSong(provider: 'subsonic', songId: 'subsonic:$id');

void main() {
  late Directory root;
  var clock = DateTime(2026, 11, 16, 12);
  final made = <StreamCache>[];

  setUp(() async {
    root = await Directory.systemTemp.createTemp('yoin_stream_cache');
    clock = DateTime(2026, 11, 16, 12);
  });

  tearDown(() async {
    for (final c in made) {
      await c.saved;
    }
    made.clear();
    if (await root.exists()) await root.delete(recursive: true);
  });

  StreamCache make({int budget = StreamCache.defaultBudgetBytes}) {
    final cache = StreamCache(directory: () async => root, budgetBytes: budget, now: () => clock);
    made.add(cache);
    return cache;
  }

  Future<File> part(StreamCache cache, String key, int bytes) async {
    final file = cache.newPartFile(key)!;
    await file.writeAsBytes(List<int>.filled(bytes, 7));
    return file;
  }

  test('should_dropTheSignature_when_keyingASubsonicStream', () {
    const a =
        'https://music.test/rest/stream.view?id=s1&maxBitRate=320&format=mp3&u=me&t=aaa&s=salt1&v=1.16.1&c=Yoin&f=json';
    const b =
        'https://music.test/rest/stream.view?id=s1&maxBitRate=320&format=mp3&u=me&t=bbb&s=salt2&v=1.16.1&c=Yoin&f=json';
    expect(StreamCache.keyOf('subsonic', a), StreamCache.keyOf('subsonic', b));
    expect(
      StreamCache.keyOf('subsonic', a),
      'subsonic|https://music.test/rest/stream.view?id=s1&maxBitRate=320&format=mp3&u=me',
    );
    // Another quality and another provider are other entries.
    expect(StreamCache.keyOf('subsonic', a), isNot(StreamCache.keyOf('subsonic', a.replaceFirst('320', '128'))));
    expect(StreamCache.keyOf('subsonic', a), isNot(StreamCache.keyOf('other', a)));
    // The length estimate doesn't change the bytes.
    expect(StreamCache.keyOf('subsonic', '$a&estimateContentLength=true'), StreamCache.keyOf('subsonic', a));
  });

  test('should_keepNothing_when_thereIsNoCacheFolder', () async {
    final cache = StreamCache(directory: () async => null);
    await cache.start();
    expect(cache.available, isFalse);
    expect(cache.newPartFile('k'), isNull);
    expect(await cache.open('k'), isNull);
  });

  test('should_storeTheSongWithItsProviderAndMetadata_when_committed', () async {
    final cache = make();
    await cache.start();
    final entry = await cache.commit(
      'k1',
      _song,
      await part(cache, 'k1', 1000),
      generation: cache.generation,
      contentType: 'audio/flac',
      suffix: 'flac',
    );
    expect(entry, isNotNull);
    expect(entry!.provider, 'subsonic');
    expect(entry.songId, 'subsonic:s1');
    expect(entry.fileName, endsWith('.flac'));
    expect(cache.sizeBytes, 1000);
    final hit = await cache.open('k1');
    expect(await hit!.$1.length(), 1000);
    expect(hit.$2.contentType, 'audio/flac');
    // The index lists it with no network after a restart.
    await cache.saved;
    final again = make();
    await again.start();
    expect(again.contains('k1'), isTrue);
    expect(again.entries.single.title, 'One');
  });

  test('should_deleteTheOldFile_when_theSongIsStoredAgainAsAnotherKind', () async {
    final cache = make();
    await cache.start();
    final first = await cache.commit('k1', _song, await part(cache, 'k1', 100), generation: cache.generation);
    final second = await cache.commit(
      'k1',
      _song,
      await part(cache, 'k1', 200),
      generation: cache.generation,
      suffix: 'mp3',
    );
    expect(first!.fileName, isNot(second!.fileName));
    expect(await File('${root.path}/${StreamCache.folderName}/${first.fileName}').exists(), isFalse);
    expect(cache.sizeBytes, 200);
  });

  test('should_evictTheLeastRecentlyPlayed_when_overTheBudget', () async {
    final cache = make(budget: 2500);
    await cache.start();
    await cache.commit('a', _songOf('a'), await part(cache, 'a', 1000), generation: cache.generation);
    clock = clock.add(const Duration(minutes: 1));
    await cache.commit('b', _songOf('b'), await part(cache, 'b', 1000), generation: cache.generation);
    clock = clock.add(const Duration(minutes: 1));
    // Played again: now the most recent.
    await cache.open('a');
    clock = clock.add(const Duration(minutes: 1));
    await cache.commit('c', _songOf('c'), await part(cache, 'c', 1000), generation: cache.generation);
    expect(cache.contains('b'), isFalse);
    expect(cache.contains('a'), isTrue);
    expect(cache.contains('c'), isTrue);
    expect(cache.sizeBytes, 2000);
  });

  test('should_notStore_when_aSongAloneIsOverTheBudget', () async {
    final cache = make(budget: 500);
    await cache.start();
    final p = await part(cache, 'big', 1000);
    expect(await cache.commit('big', _song, p, generation: cache.generation), isNull);
    expect(await p.exists(), isFalse);
    expect(cache.sizeBytes, 0);
  });

  test('should_emptyTheCacheAndRefuseOlderFetches_when_cleared', () async {
    final cache = make();
    await cache.start();
    await cache.commit('a', _songOf('a'), await part(cache, 'a', 100), generation: cache.generation);
    final before = cache.generation;
    final stale = await part(cache, 'b', 100);
    var notified = 0;
    cache.addListener(() => notified++);
    await cache.clear();
    expect(cache.sizeBytes, 0);
    expect(notified, greaterThan(0));
    expect(
      await cache.commit('b', _songOf('b'), stale, generation: before),
      isNull,
      reason: 'fetched before the clear',
    );
    expect(await stale.exists(), isFalse);
    final files = await Directory(
      '${root.path}/${StreamCache.folderName}',
    ).list().where((e) => e.path.endsWith('.audio')).toList();
    expect(files, isEmpty);
  });

  test('should_forgetMissingFilesAndSweepStrays_when_started', () async {
    final folder = Directory('${root.path}/${StreamCache.folderName}')..createSync(recursive: true);
    File('${folder.path}/kept.mp3').writeAsStringSync('x');
    File('${folder.path}/stray.1.part').writeAsStringSync('x');
    File('${folder.path}/${StreamCache.indexName}').writeAsStringSync(
      jsonEncode({
        'version': 1,
        'entries': [
          {'key': 'kept', 'provider': 'subsonic', 'songId': 'subsonic:k', 'file': 'kept.mp3', 'size': 1},
          {'key': 'lost', 'provider': 'subsonic', 'songId': 'subsonic:l', 'file': 'missing.mp3', 'size': 5},
        ],
      }),
    );
    final cache = make();
    await cache.start();
    expect(cache.entries.map((e) => e.key), ['kept']);
    expect(cache.sizeBytes, 1);
    expect(File('${folder.path}/stray.1.part').existsSync(), isFalse);
  });

  test('should_forgetTheRow_when_itsFileWasRemovedBehindItsBack', () async {
    final cache = make();
    await cache.start();
    final entry = await cache.commit('a', _songOf('a'), await part(cache, 'a', 10), generation: cache.generation);
    await File('${root.path}/${StreamCache.folderName}/${entry!.fileName}').delete();
    expect(await cache.open('a'), isNull);
    expect(cache.sizeBytes, 0);
  });
}
