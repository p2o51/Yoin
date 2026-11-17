import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/memory/memory_copy_cache.dart';
import 'package:yoin/data/model/media_id.dart';
import 'package:yoin/data/sync/adapters/memory_copy_sync_adapters.dart';
import 'package:yoin/data/sync/canonical_json.dart';
import 'package:yoin/data/sync/cloud_sync_providers.dart';
import 'package:yoin/data/sync/sync_contracts.dart';
import 'package:yoin/platform/storage/stores.dart';

void main() {
  late InMemoryStore kv;
  MemoryCopyCache cache(String id) => MemoryCopyCache.shared(kv, id);
  final album = MediaId.subsonic('a');
  // Android's key: provider ␟ entityType ␟ entityId.
  const key = 'subsonic\u001Falbum\u001Fa';

  setUp(() => kv = InMemoryStore());

  group('keys', () {
    test('should_matchAndroidsCompositeKey_when_albumKeyed', () {
      expect(memoryCopyKey(album), key);
      expect(memoryCopyKeyAlbum(key), album);
    });

    test('should_declineKey_when_itNamesAnotherEntityOrIsMalformed', () {
      expect(memoryCopyKeyAlbum('subsonic\u001Fplaylist\u001Fa'), isNull);
      expect(memoryCopyKeyAlbum('subsonic\u001Fa'), isNull);
      expect(memoryCopyKeyAlbum('\u001Falbum\u001Fa'), isNull);
      expect(memoryCopyKeyAlbum('sub:sonic\u001Falbum\u001Fa'), isNull);
    });
  });

  group('MemoryCopySyncAdapter', () {
    test('should_projectAndroidsFields_when_narrationRead', () async {
      await cache('p').put(
        album,
        (_) => const MemoryCopyRow(
          copy: 'Rain on the roof.\nWhat stayed?',
          promptHash: 'narration-v2:42',
          generatedAt: 77,
          title: 'The rain year',
          titlePromptHash: '9',
        ),
      );
      final rows = await MemoryCopySyncAdapter(cache).readAll('p');
      expect(rows.single.key, key);
      expect(rows.single.rowTs, 77);
      expect(rows.single.projection, {
        'copy': 'Rain on the roof.\nWhat stayed?',
        'promptHash': 'narration-v2:42',
        'generatedAt': 77,
      });
    });

    test('should_readNothing_when_rowHasOnlyATitle', () async {
      await cache('p').put(album, (_) => const MemoryCopyRow(title: 'The rain year'));
      expect(await MemoryCopySyncAdapter(cache).readAll('p'), isEmpty);
      expect(await MemoryTitleSyncAdapter(cache).readAll('p'), hasLength(1));
    });

    test('should_keepTheTitle_when_peersNarrationApplied', () async {
      await cache('p').put(album, (_) => const MemoryCopyRow(title: 'The rain year', titlePromptHash: '9'));
      final changes = <MediaId>[];
      final sub = cache('p').changes.listen(changes.add);
      addTearDown(sub.cancel);
      final payload = {'copy': 'Late trains.', 'promptHash': 'narration-v2:1', 'generatedAt': 500};
      final outcome = await MemoryCopySyncAdapter(cache).apply('p', key, payload, 500, null);
      expect(outcome, isA<Applied>());
      expect((outcome as Applied).localHash, CanonicalJson.hash(payload));
      final row = await cache('p').get(album);
      expect(
        row,
        const MemoryCopyRow(
          copy: 'Late trains.',
          promptHash: 'narration-v2:1',
          generatedAt: 500,
          title: 'The rain year',
          titlePromptHash: '9',
        ),
      );
      await pumpEventQueue();
      expect(changes, [album]);
      // What the next capture reads back hashes the same: nothing to publish.
      final read = await MemoryCopySyncAdapter(cache).readAll('p');
      expect(CanonicalJson.hash(read.single.projection), outcome.localHash);
    });

    test('should_skipChangedLocally_when_narrationChangedSinceCapture', () async {
      await cache('p').put(album, (_) => const MemoryCopyRow(copy: 'Mine.', promptHash: 'k', generatedAt: 3));
      final outcome = await MemoryCopySyncAdapter(
        cache,
      ).apply('p', key, {'copy': 'Theirs.', 'promptHash': 'k2', 'generatedAt': 4}, 4, null);
      expect(outcome, isA<Skipped>());
      expect((outcome as Skipped).reason, SkipReason.changedLocally);
      expect(outcome.localHash, CanonicalJson.hash({'copy': 'Mine.', 'promptHash': 'k', 'generatedAt': 3}));
      expect((await cache('p').get(album))!.copy, 'Mine.');
    });

    test('should_rejectPayload_when_fieldMissingOrBlank', () {
      final adapter = MemoryCopySyncAdapter(cache);
      expect(adapter.project({'copy': '  ', 'promptHash': 'k', 'generatedAt': 1}), isNull);
      expect(adapter.project({'copy': 'x', 'generatedAt': 1}), isNull);
      expect(adapter.project({'copy': 'x', 'promptHash': 'k', 'generatedAt': 1.5}), isNull);
      expect(adapter.project({'copy': 'x', 'promptHash': 'k', 'generatedAt': 1, 'extra': true}), {
        'copy': 'x',
        'promptHash': 'k',
        'generatedAt': 1,
      });
    });

    test('should_declineUnsupported_when_keyIsNotAnAlbum', () async {
      final outcome = await MemoryCopySyncAdapter(
        cache,
      ).apply('p', 'subsonic\u001Fplaylist\u001Fx', {'copy': 'x', 'promptHash': 'k', 'generatedAt': 1}, 1, null);
      expect(outcome, isA<Skipped>());
      expect((outcome as Skipped).reason, SkipReason.unsupported);
      expect(await cache('p').syncRead(), isEmpty);
    });

    test('should_neverDelete_when_tombstoneArrives', () async {
      await cache('p').put(album, (_) => const MemoryCopyRow(copy: 'Mine.', promptHash: 'k', generatedAt: 3));
      final outcome = await MemoryCopySyncAdapter(cache).delete('p', key, 'h');
      expect(outcome, isA<Skipped>());
      expect((outcome as Skipped).reason, SkipReason.policy);
      expect(outcome.localHash, 'h');
      expect((await cache('p').get(album))!.copy, 'Mine.');
    });
  });

  group('MemoryTitleSyncAdapter', () {
    test('should_projectTitleAndHash_when_titleRead', () async {
      await cache(
        'p',
      ).put(album, (_) => const MemoryCopyRow(copy: 'x', promptHash: 'k', generatedAt: 12, title: 'The rain year'));
      final rows = await MemoryTitleSyncAdapter(cache).readAll('p');
      expect(rows.single.key, key);
      expect(rows.single.rowTs, 12);
      // An absent hash travels as JSON null, as Android's jsonString(null).
      expect(rows.single.projection, {'title': 'The rain year', 'titlePromptHash': null});
    });

    test('should_startRowWithEmptyNarration_when_titleArrivesFirst', () async {
      final payload = {'title': 'Night line', 'titlePromptHash': '-12'};
      final outcome = await MemoryTitleSyncAdapter(cache).apply('p', key, payload, 9, null);
      expect((outcome as Applied).localHash, CanonicalJson.hash(payload));
      expect(await cache('p').get(album), const MemoryCopyRow(title: 'Night line', titlePromptHash: '-12'));
      // Home and the album page read it at once; the deck writes its own narration later.
      expect(await cache('p').title(album), 'Night line');
      expect(await MemoryCopySyncAdapter(cache).readAll('p'), isEmpty);
    });

    test('should_keepTheNarration_when_peersTitleApplied', () async {
      await cache('p').put(album, (_) => const MemoryCopyRow(copy: 'Mine.', promptHash: 'k', generatedAt: 3));
      await MemoryTitleSyncAdapter(cache).apply('p', key, {'title': 'Night line', 'titlePromptHash': null}, 9, null);
      expect(
        await cache('p').get(album),
        const MemoryCopyRow(copy: 'Mine.', promptHash: 'k', generatedAt: 3, title: 'Night line'),
      );
    });

    test('should_dropOldHash_when_peersTitleHasNone', () async {
      await cache('p').put(album, (_) => const MemoryCopyRow(title: 'Old', titlePromptHash: '5'));
      final expected = CanonicalJson.hash({'title': 'Old', 'titlePromptHash': '5'});
      await MemoryTitleSyncAdapter(cache).apply('p', key, {'title': 'New'}, 9, expected);
      final row = await cache('p').get(album);
      expect(row!.title, 'New');
      expect(row.titlePromptHash, isNull);
    });

    test('should_rejectPayload_when_titleBlankOrHashMalformed', () {
      final adapter = MemoryTitleSyncAdapter(cache);
      expect(adapter.project({'title': ' '}), isNull);
      expect(adapter.project({'title': 'x', 'titlePromptHash': 5}), isNull);
      expect(adapter.project({'title': 'x'}), {'title': 'x', 'titlePromptHash': null});
    });

    test('should_skipChangedLocally_when_titleChangedSinceCapture', () async {
      await cache('p').put(album, (_) => const MemoryCopyRow(title: 'Mine'));
      final outcome = await MemoryTitleSyncAdapter(cache).apply('p', key, {'title': 'Theirs'}, 9, null);
      expect((outcome as Skipped).reason, SkipReason.changedLocally);
      expect(await cache('p').title(album), 'Mine');
    });

    test('should_keepAccountsApart_when_twoAccountsHoldTheSameAlbum', () async {
      await MemoryTitleSyncAdapter(cache).apply('p1', key, {'title': 'One'}, 9, null);
      await MemoryTitleSyncAdapter(cache).apply('p2', key, {'title': 'Two'}, 9, null);
      expect(await cache('p1').title(album), 'One');
      expect(await cache('p2').title(album), 'Two');
    });
  });

  group('registration', () {
    test('should_reportCacheWrites_when_storeObserved', () {
      expect(isSyncedKey(MemoryCopyCache.keyFor('p')), isTrue);
    });
  });
}
