import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/model/media_id.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/sync/adapters/account_descriptor_sync_adapter.dart';
import 'package:yoin/data/sync/adapters/home_layout_sync_adapter.dart';
import 'package:yoin/data/sync/adapters/memory_sync_adapters.dart';
import 'package:yoin/data/sync/adapters/track_rating_sync_adapter.dart';
import 'package:yoin/data/sync/canonical_json.dart';
import 'package:yoin/data/sync/sync_contracts.dart';
import 'package:yoin/data/sync/sync_database.dart';
import 'package:yoin/home/home_section.dart';
import 'package:yoin/playback/local_ratings.dart';
import 'package:yoin/platform/storage/stores.dart';

void main() {
  late InMemoryStore kv;
  MemoryStore memory(String id) => MemoryStore.shared(kv, id);

  setUp(() => kv = InMemoryStore());

  group('SongNoteSyncAdapter', () {
    test('should_projectAndroidsFields_when_noteRead', () async {
      final store = memory('p');
      await store.putSongNote(
        SongNote(
          id: 'n1',
          trackId: MediaId.subsonic('t1'),
          albumId: MediaId.subsonic('al1'),
          content: 'hi',
          createdAt: 10,
          updatedAt: 20,
          title: 'Song',
          artist: 'Band',
          positionMs: 3000,
        ),
      );
      final rows = await SongNoteSyncAdapter(memory).readAll('p');
      expect(rows.single.key, 'n1');
      expect(rows.single.rowTs, 20);
      expect(rows.single.projection, {
        'trackId': 't1',
        'provider': 'subsonic',
        'content': 'hi',
        'createdAt': 10,
        'updatedAt': 20,
        'title': 'Song',
        'artist': 'Band',
        'positionMs': 3000,
        'albumId': 'subsonic:al1',
      });
    });

    test('should_writeNoteAndTellScreens_when_androidPayloadApplied', () async {
      final store = memory('p');
      final changes = <MemoryStoreChange>[];
      final sub = store.changes.listen(changes.add);
      addTearDown(sub.cancel);
      final payload = {
        'trackId': 't9',
        'provider': 'subsonic',
        'content': 'from phone',
        'createdAt': 1,
        'updatedAt': 2,
        'title': 'T',
        'artist': 'A',
        'positionMs': null,
      };
      final outcome = await SongNoteSyncAdapter(memory).apply('p', 'n9', payload, 2, null);
      expect(outcome, isA<Applied>());
      expect((outcome as Applied).localHash, CanonicalJson.hash(payload));
      final notes = await store.songNotes();
      expect(notes.single.content, 'from phone');
      expect(notes.single.trackId, MediaId.subsonic('t9'));
      await Future<void>.delayed(Duration.zero);
      expect(changes, contains(MemoryStoreChange.songNotes));
    });

    test('should_skipChangedLocally_when_rowChangedSinceCapture', () async {
      final store = memory('p');
      await store.putSongNote(
        SongNote(id: 'n1', trackId: MediaId.subsonic('t'), content: 'mine', createdAt: 1, updatedAt: 1),
      );
      final outcome = await SongNoteSyncAdapter(memory).apply(
        'p',
        'n1',
        {
          'trackId': 't',
          'provider': 'subsonic',
          'content': 'theirs',
          'createdAt': 1,
          'updatedAt': 5,
          'title': '',
          'artist': '',
        },
        5,
        'stale',
      );
      expect(outcome, isA<Skipped>());
      expect((outcome as Skipped).reason, SkipReason.changedLocally);
      expect((await store.songNotes()).single.content, 'mine');
    });

    test('should_rejectPayload_when_fieldMissing', () {
      expect(SongNoteSyncAdapter(memory).project({'trackId': 't'}), isNull);
    });
  });

  group('Album rating and review', () {
    test('should_shareOneRow_when_ratingAndReviewApplied', () async {
      final store = memory('p');
      final key = albumKey('subsonic', 'al1');
      await AlbumRatingSyncAdapter(
        memory,
      ).apply('p', key, {'albumId': 'al1', 'provider': 'subsonic', 'rating': 8}, 100, null);
      await AlbumReviewSyncAdapter(
        memory,
      ).apply('p', key, {'albumId': 'al1', 'provider': 'subsonic', 'review': 'Great'}, 200, null);
      final row = await store.albumRating(MediaId.subsonic('al1'));
      expect(row?.rating, 8);
      expect(row?.review, 'Great');
      expect(row?.updatedAt, 200);
      expect(row?.reviewUpdatedAt, 200);
    });

    test('should_projectRatingAsDouble_when_peerSentInteger', () {
      final p = AlbumRatingSyncAdapter(memory).project({'albumId': 'a', 'provider': 'subsonic', 'rating': 7});
      expect(CanonicalJson.canonical(p), '{"albumId":"a","provider":"subsonic","rating":7.0}');
    });

    test('should_keepEmptyRowButHideIt_when_scoreCleared', () async {
      final store = memory('p');
      final id = MediaId.subsonic('al1');
      await store.setAlbumRating(id, 6, nowMs: 1);
      await store.setAlbumRating(id, null, nowMs: 2);
      expect(await store.albumRating(id), isNull);
      // Sync still sees the row (score 0), so a cleared score syncs as a value.
      final rows = await AlbumRatingSyncAdapter(memory).readAll('p');
      expect(rows.single.projection['rating'], 0.0);
      expect(rows.single.rowTs, 0);
    });

    test('should_mergeBothTexts_when_reviewsConflict', () {
      final merged = AlbumReviewSyncAdapter(memory).mergeConcurrent(
        {'albumId': 'a', 'provider': 'subsonic', 'review': 'Mine'},
        {'albumId': 'a', 'provider': 'subsonic', 'review': 'Theirs'},
        'Pixel 9 · 2026-11-01',
      );
      expect(merged?['review'], 'Mine\n\n— Pixel 9 · 2026-11-01 —\nTheirs');
    });
  });

  group('AlbumTitleSyncAdapter', () {
    test('should_restoreYoinsTitle_when_tombstoneApplied', () async {
      final store = memory('p');
      final id = MediaId.subsonic('al1');
      await store.setTitle(id, 'Summer', nowMs: 1);
      final adapter = AlbumTitleSyncAdapter(memory);
      final rows = await adapter.readAll('p');
      final outcome = await adapter.delete('p', rows.single.key, CanonicalJson.hash(rows.single.projection));
      expect(outcome, isA<Applied>());
      expect(await store.titleOf(id), isNull);
    });
  });

  group('TrackRatingSyncAdapter', () {
    test('should_markRealZero_when_zeroScoreProjected', () {
      expect(TrackRatingSyncAdapter.projection('s', 'subsonic', 0), {
        'songId': 's',
        'provider': 'subsonic',
        'rating': 0.0,
        'zero': true,
      });
      expect(TrackRatingSyncAdapter.projection('s', 'subsonic', null), {
        'songId': 's',
        'provider': 'subsonic',
        'rating': 0.0,
      });
    });

    test('should_storeAsAlreadyPushed_when_peerRatingApplied', () async {
      final access = StoreTrackRatingAccess(LocalRatingStore(kv));
      final adapter = TrackRatingSyncAdapter(access);
      final key = SyncFormat.compositeKey(['subsonic', 's1']);
      final outcome = await adapter.apply('p', key, {'songId': 's1', 'provider': 'subsonic', 'rating': 7.5}, 99, null);
      expect(outcome, isA<Applied>());
      final row = (await LocalRatingStore(kv).read('p'))[MediaId.subsonic('s1')]!;
      expect(row.rating, 7.5);
      expect(row.serverRating, 4);
      expect(row.needsSync, isFalse);
      expect(row.updatedAt, 99);
    });

    test('should_readNotRated_when_bareZeroArrives', () async {
      final access = StoreTrackRatingAccess(LocalRatingStore(kv));
      final key = SyncFormat.compositeKey(['subsonic', 's1']);
      await TrackRatingSyncAdapter(
        access,
      ).apply('p', key, {'songId': 's1', 'provider': 'subsonic', 'rating': 0}, 5, null);
      expect((await LocalRatingStore(kv).read('p'))[MediaId.subsonic('s1')]!.rating, isNull);
    });
  });

  group('HomeLayoutSyncAdapter', () {
    const profile = Profile(id: 'p', provider: 'subsonic', displayName: 'Home');

    test('should_writeLayoutVerbatim_when_applied', () async {
      final adapter = HomeLayoutSyncAdapter(store: kv, profiles: () async => [profile]);
      const sections = '{"version":1,"sections":[{"id":"x","enabled":false}]}';
      await adapter.apply('p', HomeLayoutSyncAdapter.key, {'sectionsJson': sections}, 77, null);
      expect(await kv.read(HomeLayoutNotifier.keyFor('subsonic', 'p')), sections);
      final rows = await adapter.readAll('p');
      expect(rows.single.rowTs, 77);
    });

    test('should_goBackToDefault_when_tombstoneApplied', () async {
      final adapter = HomeLayoutSyncAdapter(store: kv, profiles: () async => [profile]);
      await kv.write(HomeLayoutNotifier.keyFor('subsonic', 'p'), '{}');
      final rows = await adapter.readAll('p');
      await adapter.delete('p', HomeLayoutSyncAdapter.key, CanonicalJson.hash(rows.single.projection));
      expect(await kv.read(HomeLayoutNotifier.keyFor('subsonic', 'p')), isNull);
    });
  });

  group('AccountDescriptorSyncAdapter', () {
    test('should_describeOnlyUserAtHost_when_subsonicBound', () async {
      final db = SyncDatabase(MemorySyncDbFile());
      await db.open();
      db.upsertBinding(
        const SyncBindingEntity(
          localProfileId: 'p',
          syncProfileId: 's-1',
          provider: 'subsonic',
          state: 'active',
          boundAt: 1,
        ),
      );
      final adapter = AccountDescriptorSyncAdapter(
        db: db,
        profiles: () async => [const Profile(id: 'p', provider: 'subsonic', displayName: 'Home', createdAt: 5)],
        credentials: (_) async =>
            const SubsonicCredentials(baseUrl: 'https://music.example.com', username: 'alice', password: 'secret'),
      );
      final rows = await adapter.readAll(null);
      expect(rows.single.key, 's-1');
      expect(rows.single.projection, {
        'provider': 'subsonic',
        'displayName': 'Home',
        'createdAt': 5,
        'hint': 'alice @ music.example.com',
        'storefront': null,
      });
      expect(jsonEncode(rows.single.projection), isNot(contains('secret')));
      expect(adapter.decoratePayload(rows.single.projection, 'Mate 70')['deviceName'], 'Mate 70');
      final applied = await adapter.apply(null, 's-1', rows.single.projection, 1, null);
      expect((applied as Skipped).reason, SkipReason.policy);
    });
  });
}
