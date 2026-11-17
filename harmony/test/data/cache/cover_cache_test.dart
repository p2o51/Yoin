import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/cache/cover_cache.dart';

const _signed =
    'https://music.example.com/rest/getCoverArt.view?u=ana&t=abc123&s=salt01&v=1.16.1&c=Yoin&f=json&id=al-7&size=320';

CoverResponse _png(int length) =>
    CoverResponse(statusCode: 200, bytes: Uint8List(length)..[0] = 0x89, contentType: 'image/png');

void main() {
  group('coverCacheKey', () {
    test('should_dropSignatureAndProtocol_when_urlIsSignedSubsonic', () {
      expect(coverCacheKey(_signed), 'https://music.example.com/rest/getCoverArt.view?u=ana&id=al-7&size=320');
    });

    test('should_matchAcrossSalts_when_sameCoverSignedTwice', () {
      final other = _signed.replaceAll('t=abc123', 't=ffff').replaceAll('s=salt01', 's=salt99');
      expect(coverCacheKey(other), coverCacheKey(_signed));
    });

    test('should_keepSizeAndUser_when_twoSizesOrAccounts', () {
      expect(coverCacheKey(_signed.replaceAll('size=320', 'size=640')), isNot(coverCacheKey(_signed)));
      expect(coverCacheKey(_signed.replaceAll('u=ana', 'u=bo')), isNot(coverCacheKey(_signed)));
    });

    test('should_keepUrl_when_notASignedSubsonicUrl', () {
      const spotify = 'https://i.scdn.co/image/ab67616d0000b273';
      expect(coverCacheKey(spotify), spotify);
      const unsigned = 'https://music.example.com/rest/getCoverArt.view?u=ana&id=al-7';
      expect(coverCacheKey(unsigned), unsigned);
    });
  });

  group('isShowable', () {
    bool showable(int code, String? type, {String? length, int body = 10}) =>
        CoverDiskCache.isShowable(statusCode: code, contentType: type, contentLength: length, bodyLength: body);

    test('should_store_when_imageOrGenericType', () {
      expect(showable(200, 'image/jpeg'), isTrue);
      expect(showable(200, null), isTrue);
      expect(showable(200, 'application/octet-stream'), isTrue);
      expect(showable(200, 'text/plain; charset=utf-8'), isTrue);
    });

    test('should_notStore_when_subsonicErrorOrPortalPage', () {
      expect(showable(200, 'application/json'), isFalse);
      expect(showable(200, 'text/xml'), isFalse);
      expect(showable(200, 'text/html; charset=utf-8'), isFalse);
    });

    test('should_notStore_when_emptyOrNot2xx', () {
      expect(showable(204, 'image/png'), isFalse);
      expect(showable(200, 'image/png', length: '0'), isFalse);
      expect(showable(200, 'image/png', body: 0), isFalse);
      expect(showable(404, 'image/png'), isFalse);
    });
  });

  test('should_followCoilBudget_when_freeSpaceKnownOrNot', () {
    expect(CoverDiskCache.budgetFor(null), CoverDiskCache.minBudgetBytes);
    expect(CoverDiskCache.budgetFor(100 * 1024 * 1024), CoverDiskCache.minBudgetBytes);
    expect(CoverDiskCache.budgetFor(1000 * 1024 * 1024), (1000 * 1024 * 1024 * 0.02).round());
    expect(CoverDiskCache.budgetFor(100 * 1024 * 1024 * 1024), CoverDiskCache.maxBudgetBytes);
  });

  group('CoverDiskCache', () {
    late Directory dir;
    setUp(() => dir = Directory.systemTemp.createTempSync('yoin_cover_cache'));
    tearDown(() => dir.deleteSync(recursive: true));

    test('should_serveStoredCover_when_offlineInANewProcess', () async {
      var fetches = 0;
      final first = CoverDiskCache(
        directory: () async => dir,
        fetch: (_) async {
          fetches++;
          return _png(64);
        },
      );
      expect(await first.load(_signed), hasLength(64));
      final offline = CoverDiskCache(
        directory: () async => dir,
        fetch: (_) async => throw const SocketException('offline'),
      );
      final resigned = _signed.replaceAll('s=salt01', 's=newsalt');
      expect(await offline.load(resigned), hasLength(64), reason: 'a new salt finds what the last process stored');
      expect(fetches, 1);
    });

    test('should_shareOneFetch_when_requestedTwiceAtOnce', () async {
      var fetches = 0;
      final cache = CoverDiskCache(
        directory: () async => dir,
        fetch: (_) async {
          fetches++;
          await Future<void>.delayed(const Duration(milliseconds: 5));
          return _png(8);
        },
      );
      await Future.wait([cache.load(_signed), cache.load(_signed)]);
      expect(fetches, 1);
    });

    test('should_throwHttpAndNotStore_when_serverAnswers404', () async {
      final cache = CoverDiskCache(
        directory: () async => dir,
        fetch: (_) async => CoverResponse(statusCode: 404, bytes: Uint8List(0)),
      );
      await expectLater(
        cache.load(_signed),
        throwsA(isA<ArtworkHttpException>().having((e) => e.statusCode, 'statusCode', 404)),
      );
      expect(cache.contains(_signed), isFalse);
    });

    test('should_throwUndecodableAndNotStore_when_bodyIsJsonError', () async {
      final cache = CoverDiskCache(
        directory: () async => dir,
        fetch: (_) async => CoverResponse(
          statusCode: 200,
          bytes: Uint8List.fromList('{"error":1}'.codeUnits),
          contentType: 'application/json',
        ),
      );
      await expectLater(cache.load(_signed), throwsA(isA<ArtworkUndecodableException>()));
      expect(cache.contains(_signed), isFalse);
    });

    test('should_fetchAgain_when_storedCoverRemoved', () async {
      var fetches = 0;
      final cache = CoverDiskCache(
        directory: () async => dir,
        fetch: (_) async {
          fetches++;
          return _png(8);
        },
      );
      await cache.load(_signed);
      await cache.remove(_signed);
      expect(cache.contains(_signed), isFalse);
      await cache.load(_signed);
      expect(fetches, 2);
    });

    test('should_evictLeastRecentlyUsed_when_overBudget', () async {
      var clock = DateTime(2026, 11, 17);
      final cache = CoverDiskCache(
        directory: () async => dir,
        budgetBytes: 250,
        now: () => clock = clock.add(const Duration(seconds: 1)),
        fetch: (_) async => _png(100),
      );
      await cache.load('https://a.example/1.jpg');
      await cache.load('https://a.example/2.jpg');
      // Reading 1 makes 2 the least recently used.
      await cache.load('https://a.example/1.jpg');
      await cache.load('https://a.example/3.jpg');
      expect(cache.contains('https://a.example/1.jpg'), isTrue);
      expect(cache.contains('https://a.example/2.jpg'), isFalse);
      expect(cache.contains('https://a.example/3.jpg'), isTrue);
      expect(cache.sizeBytes, lessThanOrEqualTo(250));
    });

    test('should_sweepHalfWrittenFiles_when_started', () async {
      final folder = Directory('${dir.path}/${CoverDiskCache.folderName}')..createSync();
      File('${folder.path}/abc.tmp3').writeAsBytesSync([1, 2, 3]);
      final cache = CoverDiskCache(directory: () async => dir);
      await cache.start();
      expect(cache.available, isTrue);
      expect(cache.sizeBytes, 0);
      expect(File('${folder.path}/abc.tmp3').existsSync(), isFalse);
    });

    test('should_fetchWithoutStoring_when_noFolder', () async {
      var fetches = 0;
      final cache = CoverDiskCache(
        fetch: (_) async {
          fetches++;
          return _png(8);
        },
      );
      await cache.load(_signed);
      await cache.load(_signed);
      expect(cache.available, isFalse);
      expect(fetches, 2);
    });
  });
}
