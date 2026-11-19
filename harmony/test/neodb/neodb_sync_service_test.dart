import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/neodb/neodb_api.dart';
import 'package:yoin/data/neodb/neodb_config.dart';
import 'package:yoin/data/neodb/neodb_models.dart';
import 'package:yoin/data/neodb/neodb_sync_service.dart';
import 'package:yoin/platform/storage/stores.dart';

/// A tiny NeoDB: one item, the user's Mark and Review, every request logged.
class FakeNeoDb {
  FakeNeoDb({this.searchHits});

  final requests = <http.Request>[];
  List<Map<String, Object?>>? searchHits;
  Map<String, Object?>? mark;
  Map<String, Object?>? review;
  int searchStatus = 200;
  String? searchError;

  /// Runs during the Mark POST (an edit made while the push is in flight).
  Future<void> Function()? duringMarkPost;

  late final client = MockClient((request) async {
    requests.add(request);
    final path = request.url.path;
    if (path == '/api/catalog/search') {
      if (searchStatus != 200) return _json({'detail': searchError ?? 'nope'}, searchStatus);
      return _json({'data': searchHits ?? []});
    }
    if (path == '/api/me/shelf/item/u1') {
      if (request.method == 'GET') return mark == null ? http.Response('', 404) : _json(mark!);
      mark = jsonDecode(request.body) as Map<String, Object?>;
      await duringMarkPost?.call();
      return _json({'message': 'OK'});
    }
    if (path == '/api/me/review/item/u1') {
      switch (request.method) {
        case 'GET':
          return review == null ? http.Response('', 404) : _json(review!);
        case 'POST':
          review = jsonDecode(request.body) as Map<String, Object?>;
          return _json({'message': 'OK'});
        case 'DELETE':
          if (review == null) return http.Response('', 404);
          review = null;
          return http.Response('', 204);
      }
    }
    return http.Response('', 500);
  });

  static http.Response _json(Object body, [int status = 200]) =>
      http.Response(jsonEncode(body), status, headers: {'content-type': 'application/json'});
}

final _album = Album(id: MediaId.subsonic('al1'), name: 'OK Computer', artist: 'Radiohead');
final _hit = {
  'uuid': 'u1',
  'title': 'OK Computer',
  'artist': ['Radiohead'],
  'rating_count': 510,
};

void main() {
  late InMemoryStore kv;
  late InMemoryStore secrets;
  late FakeNeoDb neodb;
  late MemoryStore memory;
  late NeoDbSyncService service;
  late NeoDbConfigStore config;

  setUp(() async {
    kv = InMemoryStore();
    secrets = InMemoryStore();
    neodb = FakeNeoDb(searchHits: [_hit]);
    memory = MemoryStore(kv, profileId: 'p1');
    config = NeoDbConfigStore(kv, secrets);
    await config.setInstance('https://neodb.social');
    await config.writeToken('tok');
    service = NeoDbSyncService(
      api: NeoDbApi(neodb.client),
      config: config,
      mappings: NeoDbMappings(kv),
      memory: (_) => memory,
      clock: () => 1000,
    );
  });

  test('should_sendShortCommentInMarkAndDropReview_when_textIsShort', () async {
    neodb.mark = {
      'shelf_type': 'progress',
      'visibility': 1,
      'tags': ['jazz'],
      'comment_text': 'old',
    };
    neodb.review = {'title': 'old', 'body': 'old body'};
    await memory.setAlbumRating(_album.id, 7.6, nowMs: 1);
    await memory.setAlbumReview(_album.id, 'Rain on the window.', nowMs: 1);

    await service.pushAlbum('p1', _album);

    expect(neodb.mark, {
      'shelf_type': 'progress',
      'visibility': 1,
      'rating_grade': 8,
      'comment_text': 'Rain on the window.',
      'tags': ['jazz'],
    });
    expect(neodb.review, isNull);
    final row = await memory.albumRatingRow(_album.id);
    expect(row!.ratingNeedsSync, isFalse);
    expect(row.reviewNeedsSync, isFalse);
    // Bearer token on every call.
    expect(neodb.requests.every((r) => r.headers['Authorization'] == 'Bearer tok'), isTrue);
  });

  test('should_postReviewAndClearComment_when_textIsLong', () async {
    final long = 'x' * (neoDbShortCommentMax + 1);
    await memory.setAlbumReview(_album.id, long, nowMs: 1);

    await service.pushAlbum('p1', _album);

    expect(neodb.mark!['comment_text'], '');
    expect(neodb.mark!['shelf_type'], 'complete');
    // An unflagged score: the remote has none, so the local 0 goes.
    expect(neodb.mark!['rating_grade'], 0);
    expect(neodb.mark!['visibility'], 0);
    expect(neodb.review, {'visibility': 0, 'title': 'OK Computer', 'body': long});
    expect((await memory.albumRatingRow(_album.id))!.reviewNeedsSync, isFalse);
  });

  test('should_sendNothing_when_nothingWaits', () async {
    await memory.setAlbumRating(_album.id, 6, nowMs: 1);
    await service.pushAlbum('p1', _album);
    final first = neodb.requests.length;
    // Nothing waits any more: no call at all.
    await service.pushAlbum('p1', _album);
    expect(neodb.requests.length, first);
  });

  test('should_keepRatingFlag_when_scoreChangesDuringPush', () async {
    await memory.setAlbumRating(_album.id, 5, nowMs: 1);
    neodb.duringMarkPost = () => memory.setAlbumRating(_album.id, 9, nowMs: 2);

    await service.pushAlbum('p1', _album);

    final row = await memory.albumRatingRow(_album.id);
    expect(row!.rating, 9);
    expect(row.ratingNeedsSync, isTrue);
  });

  test('should_cacheItemMapping_when_searchFoundTheAlbum', () async {
    await memory.setAlbumRating(_album.id, 5, nowMs: 1);
    await service.pushAlbum('p1', _album);
    await memory.setAlbumRating(_album.id, 6, nowMs: 2);
    await service.pushAlbum('p1', _album);
    expect(neodb.requests.where((r) => r.url.path == '/api/catalog/search'), hasLength(1));
    expect(await NeoDbMappings(kv).find(_album.id), 'u1');
  });

  test('should_retryLegacyCategory_when_instanceRejectsMusic', () async {
    neodb.searchStatus = 422;
    neodb.searchError = 'category: invalid';
    final api = NeoDbApi(neodb.client);
    await expectLater(api.searchAlbum('https://neodb.social', 'tok', 'q'), throwsA(isA<NeoDbException>()));
    final categories = [for (final r in neodb.requests) r.url.queryParameters['category']];
    expect(categories, ['music', 'album']);
  });

  test('should_failWithAlbumNotFound_when_searchHasNoMatch', () async {
    neodb.searchHits = [
      {
        'uuid': 'z',
        'title': 'Something Else',
        'artist': ['Radiohead'],
      },
    ];
    await memory.setAlbumRating(_album.id, 5, nowMs: 1);
    await expectLater(service.pushAlbum('p1', _album), throwsA(isA<NeoDbAlbumNotFound>()));
    expect((await memory.albumRatingRow(_album.id))!.ratingNeedsSync, isTrue);
  });

  test('should_throwNotSignedIn_when_noToken', () async {
    await config.clearToken();
    await memory.setAlbumRating(_album.id, 5, nowMs: 1);
    await expectLater(service.pushAlbum('p1', _album), throwsA(isA<NeoDbNotSignedIn>()));
    expect(neodb.requests, isEmpty);
    expect(await service.isConfigured(), isFalse);
  });

  test('should_keepFlag_when_reviewDeleteFails', () async {
    final failing = MockClient((request) async {
      if (request.url.path == '/api/catalog/search') {
        return FakeNeoDb._json({
          'data': [_hit],
        });
      }
      if (request.method == 'DELETE') return FakeNeoDb._json({'detail': 'down'}, 503);
      if (request.method == 'GET') return http.Response('', 404);
      return FakeNeoDb._json({'message': 'OK'});
    });
    final s = NeoDbSyncService(
      api: NeoDbApi(failing),
      config: config,
      mappings: NeoDbMappings(kv),
      memory: (_) => memory,
    );
    await memory.setAlbumReview(_album.id, 'short', nowMs: 1);
    await expectLater(s.pushAlbum('p1', _album), throwsA(isA<NeoDbException>()));
    expect((await memory.albumRatingRow(_album.id))!.reviewNeedsSync, isTrue);
  });

  test('should_takeReviewOverComment_when_pulling', () async {
    neodb.mark = {'shelf_type': 'complete', 'rating_grade': 9, 'comment_text': 'short one'};
    neodb.review = {'uuid': 'r1', 'body': 'The long review.'};

    final row = await service.pullAlbum('p1', _album);

    expect(row!.rating, 9);
    expect(row.review, 'The long review.');
    expect(row.neoDbReviewUuid, 'r1');
    expect(row.reviewUpdatedAt, 1000);
    expect((await memory.albumRatingRow(_album.id))!.review, 'The long review.');
  });

  test('should_keepWaitingLocalChange_when_pulling', () async {
    neodb.mark = {'rating_grade': 3, 'comment_text': 'remote'};
    await memory.setAlbumRating(_album.id, 8.5, nowMs: 1);
    await memory.setAlbumReview(_album.id, 'local draft', nowMs: 1);

    final row = await service.pullAlbum('p1', _album);

    expect(row!.rating, 8.5);
    expect(row.review, 'local draft');
    expect(row.ratingNeedsSync, isTrue);
  });

  test('should_preserveLocalDecimalRating_when_remoteIntegerMatchesRounded', () async {
    await memory.setAlbumRating(_album.id, 8.7, nowMs: 1);
    await memory.markRatingPushed(_album.id, sentRating: 8.7);
    neodb.mark = {'shelf_type': 'complete', 'rating_grade': 9};

    final row = await service.pullAlbum('p1', _album);

    expect(row!.rating, 8.7);
  });

  test('should_writeNothing_when_bothSidesEmpty', () async {
    expect(await service.pullAlbum('p1', _album), isNull);
    expect(await memory.albumRatingRow(_album.id), isNull);
  });

  test('should_readServerWords_when_errorBodyHasDetailList', () {
    expect(
      NeoDbApi.parseErrorMessage(
        jsonEncode({
          'detail': [
            {
              'loc': ['body', 'mark', 'visibility'],
              'msg': 'Field required',
            },
          ],
        }),
      ),
      'body.mark.visibility: Field required',
    );
    expect(NeoDbApi.parseErrorMessage(jsonEncode({'error': 'invalid_token'})), 'invalid_token');
    expect(NeoDbApi.parseErrorMessage('<html>'), isNull);
  });

  test('should_omitDefaults_when_encodingMark', () {
    expect(const ShelfMarkRequest(shelfType: 'complete', visibility: 0).toJson(), {
      'shelf_type': 'complete',
      'visibility': 0,
    });
  });

  group('MemoryStore NeoDB flags', () {
    test('should_flagRatingAndReview_when_userWrites', () async {
      await memory.setAlbumRating(_album.id, 4, nowMs: 1);
      var row = (await memory.albumRatingRow(_album.id))!;
      expect(row.ratingNeedsSync, isTrue);
      expect(row.reviewNeedsSync, isFalse);
      await memory.setAlbumReview(_album.id, 'Words', nowMs: 2);
      row = (await memory.albumRatingRow(_album.id))!;
      expect(row.reviewNeedsSync, isTrue);
      expect(row.ratingNeedsSync, isTrue);
    });

    test('should_keepEmptiedRowFlagged_when_scoreCleared', () async {
      await memory.setAlbumRating(_album.id, 4, nowMs: 1);
      await memory.setAlbumRating(_album.id, null, nowMs: 2);
      expect(await memory.albumRating(_album.id), isNull);
      expect((await memory.albumRatingRow(_album.id))!.ratingNeedsSync, isTrue);
    });

    test('should_surviveReload_when_flagsStored', () async {
      await memory.setAlbumReview(_album.id, 'Words', nowMs: 2);
      await memory.markReviewPushed(_album.id, sentReview: 'Words', reviewUuid: 'r9');
      await memory.setAlbumRating(_album.id, 3, nowMs: 3);
      final reloaded = (await MemoryStore(kv, profileId: 'p1').albumRatingRow(_album.id))!;
      expect(reloaded.ratingNeedsSync, isTrue);
      expect(reloaded.reviewNeedsSync, isFalse);
      expect(reloaded.neoDbReviewUuid, 'r9');
    });

    test('should_keepReviewFlag_when_wordsChangedSincePush', () async {
      await memory.setAlbumReview(_album.id, 'New words', nowMs: 2);
      await memory.markReviewPushed(_album.id, sentReview: 'Old words');
      expect((await memory.albumRatingRow(_album.id))!.reviewNeedsSync, isTrue);
    });
  });

  group('normalizeNeoDbInstance', () {
    test('should_addHttpsAndDropPath_when_userTypesHost', () {
      expect(normalizeNeoDbInstance('neodb.social/'), 'https://neodb.social');
      expect(normalizeNeoDbInstance(' https://eggplant.place/users/me '), 'https://eggplant.place');
      expect(normalizeNeoDbInstance('http://localhost:8000'), 'http://localhost:8000');
    });

    test('should_useDefault_when_blankAndAllowed', () {
      expect(normalizeNeoDbInstance(''), NeoDbConfigStore.defaultInstance);
      expect(normalizeNeoDbInstance('', blankAsDefault: false), isNull);
    });

    test('should_returnNull_when_noReadableHost', () {
      expect(normalizeNeoDbInstance('://'), isNull);
      expect(normalizeNeoDbInstance('bad host name'), isNull);
    });
  });
}
