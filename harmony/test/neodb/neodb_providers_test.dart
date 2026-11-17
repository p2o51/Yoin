import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/neodb/neodb_config.dart';
import 'package:yoin/neodb/album_neodb_sync.dart';
import 'package:yoin/neodb/neodb_lines.dart';
import 'package:yoin/neodb/neodb_providers.dart';
import 'package:yoin/neodb/neodb_strings.dart';
import 'package:yoin/platform/storage/stores.dart';

final _album = Album(id: MediaId.subsonic('al1'), name: 'Blue', artist: 'Joni Mitchell');

void main() {
  late InMemoryStore kv;
  late InMemoryStore secrets;
  late MemoryStore memory;
  late List<String> messages;
  late bool neoDbDown;
  late List<Object?> markGrades;

  ProviderContainer container() {
    final c = ProviderContainer(
      overrides: [
        keyValueStoreProvider.overrideWithValue(kv),
        secretStoreProvider.overrideWithValue(secrets),
        memoryStoreProvider.overrideWith((ref) async => memory),
        neoDbMessengerProvider.overrideWithValue((message, {actionLabel, onAction}) => messages.add(message)),
        neoDbHttpClientProvider.overrideWithValue(
          MockClient((request) async {
            if (neoDbDown) return http.Response('{"detail":"down"}', 503);
            if (request.method == 'POST' && request.url.path.startsWith('/api/me/shelf/item/')) {
              markGrades.add((jsonDecode(request.body) as Map)['rating_grade']);
            }
            if (request.url.path == '/api/catalog/search') {
              return http.Response(
                jsonEncode({
                  'data': [
                    {
                      'uuid': 'u1',
                      'title': 'Blue',
                      'artist': ['Joni Mitchell'],
                    },
                  ],
                }),
                200,
              );
            }
            if (request.method == 'GET') return http.Response('', 404);
            return http.Response('{"message":"OK"}', 200);
          }),
        ),
      ],
    );
    addTearDown(c.dispose);
    return c;
  }

  Future<void> signIn() async {
    await kv.write(NeoDbConfigStore.instanceKey, 'https://neodb.social');
    await secrets.write(NeoDbConfigStore.tokenKey, 'tok');
  }

  Future<AlbumNeoDbSync> settle(ProviderContainer c) async {
    final sub = c.listen(albumNeoDbProvider(_album.id), (_, _) {});
    addTearDown(sub.close);
    for (var i = 0; i < 10; i++) {
      await Future<void>.delayed(Duration.zero);
    }
    return c.read(albumNeoDbProvider(_album.id));
  }

  setUp(() {
    kv = InMemoryStore();
    secrets = InMemoryStore();
    // The store the app's screens and NeoDB's pushes share (MemoryStore.shared).
    memory = MemoryStore.shared(kv, 'p1');
    messages = [];
    neoDbDown = false;
    markGrades = [];
  });

  test('should_sendOnce_when_pushAskedTwiceAtOnce', () async {
    await signIn();
    final c = container();
    await memory.setAlbumRating(_album.id, 8, nowMs: 1);
    final push = c.read(neoDbPushProvider.notifier);

    final first = push.push(_album.id, failureMessage: 'x', album: _album);
    final second = await push.push(_album.id, failureMessage: 'x', album: _album);

    expect(second, NeoDbPushOutcome.busy);
    expect(await first, NeoDbPushOutcome.pushed);
    expect(await settle(c), AlbumNeoDbSync.synced);
    // The follow-up found nothing waiting: one Mark went, not two.
    expect(markGrades, [8]);
  });

  test('should_sendNewerChange_when_itLandsWhilePushing', () async {
    await signIn();
    final c = container();
    await memory.setAlbumRating(_album.id, 8, nowMs: 1);
    final push = c.read(neoDbPushProvider.notifier);

    final first = push.push(_album.id, failureMessage: 'x', album: _album);
    // The sheet closes on a new score while the first push is on its way.
    await memory.setAlbumRating(_album.id, 9, nowMs: 2);
    expect(await push.push(_album.id, failureMessage: 'x', album: _album), NeoDbPushOutcome.busy);
    await first;

    expect(await settle(c), AlbumNeoDbSync.synced);
    expect(markGrades.last, 9);
    expect((await memory.albumRatingRow(_album.id))!.ratingNeedsSync, isFalse);
    expect(messages, isEmpty);
  });

  test('should_readSignedOut_when_noToken', () async {
    final c = container();
    expect(await settle(c), AlbumNeoDbSync.signedOut);
  });

  test('should_readPendingThenSynced_when_scoreSetAndPushed', () async {
    await signIn();
    final c = container();
    expect(await settle(c), AlbumNeoDbSync.idle);
    await memory.setAlbumRating(_album.id, 8, nowMs: 1);
    expect(await settle(c), AlbumNeoDbSync.pending);

    final outcome = await c.read(neoDbPushProvider.notifier).push(_album.id, failureMessage: 'x', album: _album);

    expect(outcome, NeoDbPushOutcome.pushed);
    expect(await settle(c), AlbumNeoDbSync.synced);
    expect(messages, isEmpty);
  });

  test('should_readFailedAndReportOnce_when_pushFails', () async {
    await signIn();
    neoDbDown = true;
    final c = container();
    await memory.setAlbumReview(_album.id, 'Words', nowMs: 1);

    final outcome = await c
        .read(neoDbPushProvider.notifier)
        .push(_album.id, failureMessage: 'Couldn\'t sync', album: _album);

    expect(outcome, NeoDbPushOutcome.failed);
    expect(messages, ["Couldn't sync"]);
    expect(await settle(c), AlbumNeoDbSync.failed);

    // Back online, the retry lands and the failure goes.
    neoDbDown = false;
    await c.read(neoDbPushProvider.notifier).push(_album.id, failureMessage: 'x', album: _album);
    expect(await settle(c), AlbumNeoDbSync.synced);
  });

  test('should_sendNothing_when_signedOut', () async {
    final c = container();
    await memory.setAlbumRating(_album.id, 8, nowMs: 1);
    final outcome = await c.read(neoDbPushProvider.notifier).push(_album.id, failureMessage: 'x', album: _album);
    expect(outcome, NeoDbPushOutcome.notConfigured);
    expect((await memory.albumRatingRow(_album.id))!.ratingNeedsSync, isTrue);
  });

  test('should_skipPush_when_nothingWaits', () async {
    await signIn();
    final c = container();
    final outcome = await c.read(neoDbPushProvider.notifier).push(_album.id, failureMessage: 'x', album: _album);
    expect(outcome, NeoDbPushOutcome.nothingToPush);
  });

  test('should_signInAndOut_when_tokenSavedThenCleared', () async {
    final c = container();
    final account = c.read(neoDbAccountProvider.notifier);
    await c.read(neoDbAccountProvider.future);
    expect(await account.saveConfig('eggplant.place', '  tok  '), isTrue);
    var state = c.read(neoDbAccountProvider).value!;
    expect(state.instance, 'https://eggplant.place');
    expect(state.configured, isTrue);
    expect(await secrets.read(NeoDbConfigStore.tokenKey), 'tok');

    await account.clearToken();
    state = c.read(neoDbAccountProvider).value!;
    expect(state.signedIn, isFalse);
    // The instance stays for the next sign-in.
    expect(state.instance, 'https://eggplant.place');
    expect(await secrets.read(NeoDbConfigStore.tokenKey), isNull);
  });

  test('should_refuseSave_when_instanceHasNoHost', () async {
    final c = container();
    await c.read(neoDbAccountProvider.future);
    expect(await c.read(neoDbAccountProvider.notifier).saveConfig('bad host', 'tok'), isFalse);
    expect(await secrets.read(NeoDbConfigStore.tokenKey), isNull);
  });

  test('should_showPendingInsteadOfSynced_when_wordsUnsaved', () {
    expect(albumNeoDbShown(AlbumNeoDbSync.synced, unsaved: true), AlbumNeoDbSync.pending);
    expect(albumNeoDbShown(AlbumNeoDbSync.idle, unsaved: true), AlbumNeoDbSync.pending);
    expect(albumNeoDbShown(AlbumNeoDbSync.signedOut, unsaved: true), AlbumNeoDbSync.signedOut);
    expect(albumNeoDbShown(AlbumNeoDbSync.synced, unsaved: false), AlbumNeoDbSync.synced);
  });

  test('should_sayNothing_when_pendingOrIdleOnSheetLine', () {
    const en = NeoDbStrings.forLanguage(chinese: false);
    expect(albumNeoDbLabel(AlbumNeoDbSync.pending, en), '');
    expect(albumNeoDbLabel(AlbumNeoDbSync.idle, en), '');
    expect(albumNeoDbLabel(AlbumNeoDbSync.signedOut, en), 'Sign in to sync');
    expect(albumNeoDbLabel(AlbumNeoDbSync.failed, en), "Couldn't sync");
    expect(diaryNeoDbText(AlbumNeoDbSync.pending, en), 'Sync to NeoDB');
    expect(diaryNeoDbText(AlbumNeoDbSync.signedOut, en), '');
    const zh = NeoDbStrings.forLanguage(chinese: true);
    expect(diaryNeoDbText(AlbumNeoDbSync.synced, zh), '已同步到 NeoDB');
  });

  test('should_decideStateFromFlags_when_signedIn', () {
    final id = MediaId.subsonic('x');
    expect(albumNeoDbSync(configured: false, row: null), AlbumNeoDbSync.signedOut);
    expect(albumNeoDbSync(configured: true, row: null), AlbumNeoDbSync.idle);
    expect(
      albumNeoDbSync(configured: true, row: AlbumRatingRow(albumId: id, rating: 6, updatedAt: 1)),
      AlbumNeoDbSync.synced,
    );
    expect(
      albumNeoDbSync(configured: true, row: AlbumRatingRow(albumId: id, updatedAt: 1, reviewNeedsSync: true)),
      AlbumNeoDbSync.pending,
    );
  });
}
