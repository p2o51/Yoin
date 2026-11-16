import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/ai/ai_settings.dart';
import 'package:yoin/data/backup/playlist_backup_models.dart';
import 'package:yoin/data/backup/playlist_backup_store.dart';
import 'package:yoin/data/lyrics/lyrics_cache.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/sync/adapters/lyrics_translation_sync_adapter.dart';
import 'package:yoin/data/sync/adapters/playlist_backup_sync_adapter.dart';
import 'package:yoin/data/sync/adapters/settings_sync_adapter.dart';
import 'package:yoin/data/sync/canonical_json.dart';
import 'package:yoin/data/sync/sync_contracts.dart';
import 'package:yoin/platform/storage/stores.dart';

void main() {
  group('PlaylistBackupSyncAdapter', () {
    late PlaylistBackupStore store;
    late PlaylistBackupSyncAdapter adapter;

    PlaylistBackupRecord backup({String? account, String? profile = 'local-profile'}) => PlaylistBackupRecord(
      id: 'b1',
      name: 'Night drive',
      sourceProvider: MediaId.providerSubsonic,
      sourceProfileLabel: 'Home',
      sourcePlaylistId: 'subsonic:p1',
      sourceProfileId: profile,
      playlistCreatedAt: '2024-03-01T10:00:00Z',
      firstBackedUpAt: 1,
      lastBackedUpAt: 2,
      lastChangedAt: 2,
      sourceGoneAt: null,
      content: const PlaylistBackupContent(
        tracks: [
          BackupTrack(title: 'Entropy', artists: ['Beach Bunny'], sourceTrackId: 'subsonic:t1'),
        ],
      ),
      restores: const [],
      updatedAt: 3,
      sourceAccount: account,
    );

    setUp(() {
      store = PlaylistBackupStore(InMemoryPlaylistBackupStorage());
      adapter = PlaylistBackupSyncAdapter(store);
    });

    test('should_projectReadAndApplyAlike_when_backupMovesBetweenDevices', () async {
      await store.save(backup());
      final row = (await adapter.readAll(null)).single;
      expect(row.key, 'b1');
      expect(row.rowTs, 3);
      expect(row.projection.containsKey('sourceProfileId'), isFalse);
      expect(row.projection.containsKey('sourceAccount'), isFalse);
      expect(adapter.project(row.projection), row.projection);

      // Another device: no local row, so the profile id stays its own (none).
      final other = PlaylistBackupSyncAdapter(PlaylistBackupStore(InMemoryPlaylistBackupStorage()));
      final outcome = await other.apply(null, 'b1', row.projection, 3, null);
      expect(outcome, isA<Applied>());
      expect((outcome as Applied).localHash, CanonicalJson.hash(row.projection));
      final applied = (await other.readAll(null)).single;
      expect(applied.projection, row.projection);
    });

    test('should_keepTheLocalProfileId_when_aNewerVersionArrives', () async {
      await store.save(backup());
      final row = (await adapter.readAll(null)).single;
      final newer = {...row.projection, 'name': 'Renamed', 'updatedAt': 9};
      final outcome = await adapter.apply(null, 'b1', newer, 9, CanonicalJson.hash(row.projection));
      expect(outcome, isA<Applied>());
      final read = (await store.get('b1'))!;
      expect(read.name, 'Renamed');
      expect(read.sourceProfileId, 'local-profile');
    });

    test('should_carryTheAccount_when_itIsKnown', () async {
      await store.save(backup(account: 's-abc'));
      expect((await adapter.readAll(null)).single.projection['sourceAccount'], 's-abc');
    });

    test('should_keepUnknownFields_when_newerDeviceWroteContent', () async {
      final row = (await () async {
        await store.save(backup());
        return (await adapter.readAll(null)).single;
      }());
      const content = '{"description":null,"tracks":[{"title":"A","sourceId":"x:1","mood":"calm"}],"unexported":[]}';
      final payload = {...row.projection, 'content': content};
      final other = PlaylistBackupSyncAdapter(PlaylistBackupStore(InMemoryPlaylistBackupStorage()));
      await other.apply(null, 'b1', payload, 3, null);
      expect((await other.readAll(null)).single.projection['content'], content);
    });

    test('should_skipUnsupported_when_contentDoesNotDecode', () async {
      await store.save(backup());
      final row = (await adapter.readAll(null)).single;
      final outcome = await adapter.apply(
        null,
        'b1',
        {...row.projection, 'content': '[1]'},
        4,
        CanonicalJson.hash(row.projection),
      );
      expect(outcome, isA<Skipped>());
      expect((outcome as Skipped).reason, SkipReason.unsupported);
    });

    test('should_skipChangedLocally_when_restoreWasRecordedMidCycle', () async {
      await store.save(backup());
      final row = (await adapter.readAll(null)).single;
      await store.recordRestore(
        'b1',
        const PlaylistRestoreRecord(
          at: 5,
          targetProvider: 'subsonic',
          targetLabel: 'X',
          added: 1,
          skipped: 0,
          notFound: 0,
        ),
      );
      final outcome = await adapter.apply(null, 'b1', row.projection, 3, CanonicalJson.hash(row.projection));
      expect((outcome as Skipped).reason, SkipReason.changedLocally);
    });

    test('should_deleteBackup_when_tombstoneArrives', () async {
      await store.save(backup());
      final row = (await adapter.readAll(null)).single;
      expect(await adapter.delete(null, 'b1', CanonicalJson.hash(row.projection)), isA<Applied>());
      expect(await store.getAll(), isEmpty);
      expect(adapter.propagatesDeletes, isTrue);
      expect(adapter.conflictPolicy, ConflictPolicy.lwwLiveWins);
      expect(adapter.fileClass, FileClass.artifacts);
    });
  });

  group('SettingsSyncAdapter', () {
    late InMemoryStore kv;
    var tracked = <String>{};
    var aboutCount = 0;

    SettingsSyncAdapter adapter() => SettingsSyncAdapter(
      language: KeyValueTranslationLanguageGateway(kv),
      seam: KeyValueSeamStyleGateway(kv),
      tracked: (key) async => tracked.contains(key),
      songAboutCount: () async => aboutCount,
    );

    String hashOf(String value) => CanonicalJson.hash({'value': value});

    setUp(() {
      kv = InMemoryStore();
      tracked = {};
      aboutCount = 0;
    });

    test('should_captureNothing_when_everySettingIsDefault', () async {
      await kv.write(AiSettingsStore.prefsKey, jsonEncode({'tier': 'good', 'targetLanguage': 'English'}));
      expect(await adapter().readAll(null), isEmpty);
    });

    test('should_captureExplicitValues_when_userSetThem', () async {
      await kv.write(AiSettingsStore.prefsKey, jsonEncode({'tier': 'best', 'targetLanguage': 'Japanese'}));
      await kv.write(KeyValueSeamStyleGateway.storeKey, 'dots');
      final rows = {for (final r in await adapter().readAll(null)) r.key: r.projection['value']};
      expect(rows, {SyncSettingKeys.translationLanguage: 'Japanese', SyncSettingKeys.seamTopStyle: 'dots'});
    });

    test('should_applyLanguageAndKeepTheRest_when_noAboutEntriesExist', () async {
      await kv.write(AiSettingsStore.prefsKey, jsonEncode({'tier': 'best', 'targetLanguage': 'English'}));
      final outcome = await adapter().apply(null, SyncSettingKeys.translationLanguage, {'value': 'Korean'}, 1, null);
      expect(outcome, isA<Applied>());
      expect(jsonDecode((await kv.read(AiSettingsStore.prefsKey))!), {'tier': 'best', 'targetLanguage': 'Korean'});
      expect((await AiSettingsStore(kv, kv).read()).targetLanguage, 'Korean');
    });

    test('should_skipLanguageByPolicy_when_aboutEntriesExist', () async {
      aboutCount = 3;
      final outcome = await adapter().apply(null, SyncSettingKeys.translationLanguage, {'value': 'Korean'}, 1, null);
      expect((outcome as Skipped).reason, SkipReason.policy);
      expect(await kv.read(AiSettingsStore.prefsKey), isNull);
    });

    test('should_skipByPolicy_when_languageUnknown', () async {
      final outcome = await adapter().apply(null, SyncSettingKeys.translationLanguage, {'value': 'Klingon'}, 1, null);
      expect((outcome as Skipped).reason, SkipReason.policy);
    });

    test('should_skipChangedLocally_when_languageSetHereMeanwhile', () async {
      await kv.write(AiSettingsStore.prefsKey, jsonEncode({'targetLanguage': 'French'}));
      final outcome = await adapter().apply(null, SyncSettingKeys.translationLanguage, {'value': 'Korean'}, 1, null);
      expect((outcome as Skipped).reason, SkipReason.changedLocally);
      expect(outcome.localHash, hashOf('French'));
    });

    test('should_captureDefaultLanguage_when_userSwitchesBackWhileTracked', () async {
      await kv.write(AiSettingsStore.prefsKey, jsonEncode({'targetLanguage': 'English'}));
      tracked = {SyncSettingKeys.translationLanguage};
      expect((await adapter().readAll(null)).single.projection, {'value': 'English'});
    });

    test('should_applySeamThroughGateway_when_keyKnown', () async {
      final outcome = await adapter().apply(null, SyncSettingKeys.seamTopStyle, {'value': 'cookie'}, 1, null);
      expect((outcome as Applied).localHash, hashOf('cookie'));
      expect(await kv.read(KeyValueSeamStyleGateway.storeKey), 'cookie');
    });

    test('should_skipSeamByPolicy_when_keyUnknown', () async {
      final outcome = await adapter().apply(null, SyncSettingKeys.seamTopStyle, {'value': 'zigzag'}, 1, null);
      expect((outcome as Skipped).reason, SkipReason.policy);
    });

    test('should_carryTheClientId_when_thisDeviceHasNoSpotify', () async {
      final outcome = await adapter().apply(null, SyncSettingKeys.spotifyClientId, {'value': 'abc'}, 1, null);
      expect((outcome as Skipped).reason, SkipReason.unsupported);
      expect(outcome.localHash, isNull);
    });

    test('should_neverDelete_when_aTombstoneArrives', () async {
      await kv.write(KeyValueSeamStyleGateway.storeKey, 'dots');
      final outcome = await adapter().delete(null, SyncSettingKeys.seamTopStyle, hashOf('dots'));
      expect((outcome as Skipped).reason, SkipReason.policy);
      expect(await kv.read(KeyValueSeamStyleGateway.storeKey), 'dots');
    });
  });

  group('LyricsTranslationSyncAdapter', () {
    late InMemoryStore kv;
    late LyricsCacheStore cache;
    const lrc = '[00:01.00]one\n[00:02.00]two';
    final key = LyricsTranslationSyncAdapter.keyOf('subsonic', 't1', 'h1', 'Japanese', 'gemini-3.1-flash-lite');

    JsonMap payload({String translations = '["一","二"]', String model = 'gemini-3.1-flash-lite'}) => {
      'trackProvider': 'subsonic',
      'trackRawId': 't1',
      'sourceHash': 'h1',
      'targetLanguage': 'Japanese',
      'model': model,
      'translationsJson': translations,
    };

    setUp(() {
      kv = InMemoryStore();
      cache = LyricsCacheStore(kv);
    });

    Future<void> translate({String hash = 'h1', String model = 'gemini-3.1-flash-lite', int at = 100}) =>
        cache.upsertTranslation(
          songKey: 'subsonic:t1',
          sourceHash: hash,
          targetLanguage: 'Japanese',
          model: model,
          lines: const ['一', '二'],
          cachedAt: at,
        );

    test('should_readOnlyPaidRows_when_providerTranslationsExist', () async {
      await translate();
      await translate(model: 'provider:qq');
      final rows = await LyricsTranslationSyncAdapter(cache).readAll(null);
      expect(rows.single.key, key);
      expect(rows.single.projection, payload());
      expect(rows.single.rowTs, 100);
    });

    test('should_attachSnapshotOutsideProjection_when_lyricsMatchTranslation', () async {
      await cache.upsert(
        const LyricsCacheEntry(
          songKey: 'subsonic:t1',
          lyricsProvider: 'qq',
          storedSongId: '42',
          lrc: lrc,
          cachedAt: 50,
        ),
      );
      await translate();
      final adapter = LyricsTranslationSyncAdapter(cache);
      final row = (await adapter.readAll(null)).single;
      expect(row.projection.containsKey(LyricsTranslationSyncAdapter.lyricsField), isFalse);
      final decorated = adapter.decoratePayload(row.projection, 'Mate');
      expect(decorated[LyricsTranslationSyncAdapter.lyricsField], {
        'lyricsProvider': 'qq',
        'lyricsProviderSongId': '42',
        'lrc': lrc,
      });
    });

    test('should_notAttachSnapshot_when_lyricsCachedAfterTranslation', () async {
      await translate(at: 10);
      await cache.upsert(
        const LyricsCacheEntry(
          songKey: 'subsonic:t1',
          lyricsProvider: 'qq',
          storedSongId: '42',
          lrc: lrc,
          cachedAt: 50,
        ),
      );
      final adapter = LyricsTranslationSyncAdapter(cache);
      final row = (await adapter.readAll(null)).single;
      expect(
        adapter.decoratePayload(row.projection, 'Mate').containsKey(LyricsTranslationSyncAdapter.lyricsField),
        isFalse,
      );
    });

    test('should_insertTranslationAndSeedLyrics_when_trackHasNoLyricsRow', () async {
      final adapter = LyricsTranslationSyncAdapter(cache, clock: () => 1000);
      final outcome = await adapter.apply(
        null,
        key,
        {
          ...payload(),
          'lyrics': {'lyricsProvider': 'netease', 'lyricsProviderSongId': '7', 'lrc': lrc},
        },
        1,
        null,
      );
      expect((outcome as Applied).localHash, CanonicalJson.hash(payload()));
      expect(
        await cache.getTranslation(
          songKey: 'subsonic:t1',
          sourceHash: 'h1',
          targetLanguage: 'Japanese',
          model: 'gemini-3.1-flash-lite',
        ),
        ['一', '二'],
      );
      final seeded = (await cache.get('subsonic:t1'))!;
      expect(seeded.lyricsProvider, 'netease');
      expect(seeded.cachedAt, 1000);
      // And it lists it from now on, byte for byte.
      expect((await adapter.readAll(null)).single.projection, payload());
    });

    test('should_neverOverwrite_when_translationAlreadyExists', () async {
      await translate();
      final adapter = LyricsTranslationSyncAdapter(cache);
      final local = (await adapter.readAll(null)).single;
      final outcome = await adapter.apply(
        null,
        key,
        payload(translations: '["x","y"]'),
        2,
        CanonicalJson.hash(local.projection),
      );
      expect((outcome as Skipped).reason, SkipReason.policy);
      expect(
        await cache.getTranslation(
          songKey: 'subsonic:t1',
          sourceHash: 'h1',
          targetLanguage: 'Japanese',
          model: 'gemini-3.1-flash-lite',
        ),
        ['一', '二'],
      );
    });

    test('should_keepBothTranslations_when_theSongsLyricsChanged', () async {
      await translate(hash: 'old');
      await translate(hash: 'h1');
      final rows = await LyricsTranslationSyncAdapter(cache).readAll(null);
      expect({for (final r in rows) r.projection['sourceHash']}, {'old', 'h1'});
      expect(
        await cache.getTranslation(
          songKey: 'subsonic:t1',
          sourceHash: 'old',
          targetLanguage: 'Japanese',
          model: 'gemini-3.1-flash-lite',
        ),
        ['一', '二'],
      );
    });

    test('should_neverSeedOverManualLyrics_when_manualRowIsOld', () async {
      await cache.upsert(
        const LyricsCacheEntry(
          songKey: 'subsonic:t1',
          lyricsProvider: 'manual',
          storedSongId: null,
          lrc: 'mine',
          cachedAt: 1,
        ),
      );
      final adapter = LyricsTranslationSyncAdapter(cache, clock: () => 999999999999);
      await adapter.apply(
        null,
        key,
        {
          ...payload(),
          'lyrics': {'lyricsProvider': 'netease', 'lyricsProviderSongId': '7', 'lrc': lrc},
        },
        1,
        null,
      );
      expect((await cache.get('subsonic:t1'))!.lrc, 'mine');
    });

    test('should_notSeed_when_snapshotLineCountDiffers', () async {
      final adapter = LyricsTranslationSyncAdapter(cache);
      await adapter.apply(
        null,
        key,
        {
          ...payload(),
          'lyrics': {'lyricsProvider': 'netease', 'lyricsProviderSongId': '7', 'lrc': '[00:01.00]only one'},
        },
        1,
        null,
      );
      expect(await cache.get('subsonic:t1'), isNull);
    });

    test('should_skipUnsupported_when_payloadIsProviderTranslation', () async {
      final providerKey = LyricsTranslationSyncAdapter.keyOf('subsonic', 't1', 'h1', 'Japanese', 'provider:qq');
      final outcome = await LyricsTranslationSyncAdapter(
        cache,
      ).apply(null, providerKey, payload(model: 'provider:qq'), 1, null);
      expect((outcome as Skipped).reason, SkipReason.unsupported);
    });
  });
}
