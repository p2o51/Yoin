import 'dart:convert';
import 'dart:io';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/neodb/neodb_restore_guard.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/profile/profile_repository.dart';
import 'package:yoin/neodb/neodb_providers.dart';
import 'package:yoin/offline/offline_providers.dart';
import 'package:yoin/platform/storage/app_files.dart';
import 'package:yoin/platform/storage/stores.dart';

void main() {
  late Directory files;

  setUp(() async => files = await Directory.systemTemp.createTemp('yoin_restore_guard'));
  tearDown(() async {
    if (await files.exists()) await files.delete(recursive: true);
  });

  group('NeoDbRestoreGuard', () {
    test('should_leaveDirtyFlagsAlone_when_noRestoreHappened', () async {
      var cleared = 0;
      await NeoDbRestoreGuard.clearIfRestored(files, () async => cleared++);
      expect(cleared, 0);
    });

    test('should_clearFlagsOnce_when_aRestoreLeftItsMarker', () async {
      await NeoDbRestoreGuard.markRestored(files);
      var cleared = 0;
      await NeoDbRestoreGuard.clearIfRestored(files, () async => cleared++);
      await NeoDbRestoreGuard.clearIfRestored(files, () async => cleared++);
      expect(cleared, 1);
      expect(NeoDbRestoreGuard.marker(files).existsSync(), isFalse);
    });

    test('should_keepMarkerForNextLaunch_when_theClearFails', () async {
      await NeoDbRestoreGuard.markRestored(files);
      await expectLater(
        NeoDbRestoreGuard.clearIfRestored(files, () async => throw StateError('db busy')),
        throwsStateError,
      );
      expect(NeoDbRestoreGuard.marker(files).existsSync(), isTrue);
    });

    test('should_readTheMarkerTheArkTsExtensionWrites_when_pathsCompared', () {
      final scope = File('ohos/entry/src/main/ets/yoin/backup/BackupScope.ets').readAsStringSync();
      final marker = RegExp(r"RESTORE_MARKER: string = '([^']+)'").firstMatch(scope)?.group(1);
      expect(marker, NeoDbRestoreGuard.markerPath);
      // The marker must never travel: its folder is not one backup_config.json includes.
      final config = jsonDecode(
        File('ohos/entry/src/main/resources/base/profile/backup_config.json').readAsStringSync(),
      );
      final includes = (config as Map)['includes'] as List;
      final markerFolder = '/data/storage/el2/base/files/${NeoDbRestoreGuard.markerPath.split('/').first}/';
      expect(includes.any((i) => markerFolder.startsWith(i as String)), isFalse);
    });
  });

  group('MemoryStore.clearAllNeoDbPending', () {
    final album = MediaId.subsonic('al1');
    final other = MediaId.subsonic('al2');

    test('should_dropBothFlagsAndKeepWords_when_rowsWereDirty', () async {
      final kv = InMemoryStore();
      final store = MemoryStore(kv, profileId: 'p1');
      await store.setAlbumRating(album, 8, nowMs: 10);
      await store.setAlbumReview(album, 'Late-night record.', nowMs: 20);
      await store.markReviewPushed(album, sentReview: 'Late-night record.', reviewUuid: 'r-1');
      await store.setAlbumReview(album, 'Late-night record, again.', nowMs: 30);

      await store.clearAllNeoDbPending();

      final row = (await MemoryStore(kv, profileId: 'p1').albumRatingRow(album))!;
      expect(row.ratingNeedsSync, isFalse);
      expect(row.reviewNeedsSync, isFalse);
      expect(row.rating, 8);
      expect(row.review, 'Late-night record, again.');
      expect(row.neoDbReviewUuid, 'r-1');
      expect(row.updatedAt, 30);
    });

    test('should_writeNothing_when_noRowWasDirty', () async {
      final kv = _CountingStore();
      final store = MemoryStore(kv, profileId: 'p1');
      await store.setAlbumRating(other, 6, nowMs: 10);
      await store.markRatingPushed(other, sentRating: 6);
      final writes = kv.writes;

      await store.clearAllNeoDbPending();

      expect(kv.writes, writes);
    });
  });

  group('neoDbRestoreGuardProvider', () {
    final album = MediaId.subsonic('al1');

    Future<ProviderContainer> launch(InMemoryStore kv) async {
      final c = ProviderContainer(
        overrides: [
          keyValueStoreProvider.overrideWithValue(kv),
          secretStoreProvider.overrideWithValue(InMemoryStore()),
          appFilesHostProvider.overrideWithValue(DirectoryAppFilesHost(files)),
        ],
      );
      addTearDown(c.dispose);
      await c.read(neoDbRestoreGuardProvider.future);
      return c;
    }

    Future<InMemoryStore> restoredStore() async {
      final kv = InMemoryStore();
      await kv.write(
        ProfileRepository.profilesKey,
        jsonEncode([
          const Profile(id: 'p1', provider: MediaId.providerSubsonic, displayName: 'Home', createdAt: 1).toJson(),
          const Profile(id: 'p2', provider: MediaId.providerSubsonic, displayName: 'Work', createdAt: 2).toJson(),
        ]),
      );
      for (final id in ['p1', 'p2']) {
        final store = MemoryStore(kv, profileId: id);
        await store.setAlbumRating(album, 9, nowMs: 10);
        await store.setAlbumReview(album, 'Old phone words', nowMs: 20);
      }
      return kv;
    }

    test('should_clearEveryAccountsFlagsAndTheMarker_when_launchedAfterARestore', () async {
      final kv = await restoredStore();
      await NeoDbRestoreGuard.markRestored(files);

      await launch(kv);

      for (final id in ['p1', 'p2']) {
        final row = (await MemoryStore(kv, profileId: id).albumRatingRow(album))!;
        expect(row.ratingNeedsSync, isFalse, reason: id);
        expect(row.reviewNeedsSync, isFalse, reason: id);
        expect(row.review, 'Old phone words', reason: id);
      }
      expect(NeoDbRestoreGuard.marker(files).existsSync(), isFalse);
    });

    test('should_keepThisPhonesOwnFlags_when_launchedWithoutARestore', () async {
      final kv = await restoredStore();

      await launch(kv);

      final row = (await MemoryStore(kv, profileId: 'p1').albumRatingRow(album))!;
      expect(row.ratingNeedsSync, isTrue);
      expect(row.reviewNeedsSync, isTrue);
    });

    test('should_doNothing_when_theHostKeepsNoFiles', () async {
      final kv = await restoredStore();
      final c = ProviderContainer(
        overrides: [
          keyValueStoreProvider.overrideWithValue(kv),
          secretStoreProvider.overrideWithValue(InMemoryStore()),
        ],
      );
      addTearDown(c.dispose);
      await c.read(neoDbRestoreGuardProvider.future);
      final row = (await MemoryStore(kv, profileId: 'p1').albumRatingRow(album))!;
      expect(row.ratingNeedsSync, isTrue);
    });
  });
}

final class _CountingStore implements KeyValueStore {
  final _inner = InMemoryStore();
  int writes = 0;

  @override
  Future<String?> read(String key) => _inner.read(key);

  @override
  Future<void> write(String key, String? value) {
    writes++;
    return _inner.write(key, value);
  }
}
