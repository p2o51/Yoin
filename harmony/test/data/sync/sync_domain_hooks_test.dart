import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/profile/profile_repository.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/data/sync/cloud_sync_providers.dart';
import 'package:yoin/home/home_section.dart';
import 'package:yoin/platform/storage/observed_store.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/playback/local_ratings.dart';

import '../../support/playback_fakes.dart';

/// The hooks cloud sync writes through, so what's on screen follows a sync.
void main() {
  late InMemoryStore values;
  late ProfileRepository repo;
  late Profile profile;

  setUp(() async {
    values = InMemoryStore();
    repo = ProfileRepository(values, InMemoryStore());
    profile = await repo.add(
      provider: MediaId.providerSubsonic,
      displayName: 'Home',
      credentials: const SubsonicCredentials(baseUrl: 'https://m.test', username: 'u', password: 'p'),
    );
  });

  ProviderContainer container({Set<Capability>? capabilities}) {
    final c = ProviderContainer(
      overrides: [
        keyValueStoreProvider.overrideWithValue(values),
        profileRepositoryProvider.overrideWithValue(repo),
        activeSourceProvider.overrideWith(
          (ref) async => capabilities == null ? PlaybackFakeSource() : PlaybackFakeSource(capabilities: capabilities),
        ),
      ],
    );
    addTearDown(c.dispose);
    return c;
  }

  test('should_showSyncedRatingAtOnce_when_activeAccountEditedBySync', () async {
    final c = container();
    c.listen(localRatingsProvider, (_, _) {});
    await c.read(activeSourceProvider.future);
    await pumpEventQueue();
    final song = MediaId.subsonic('s1');
    await c.read(localRatingsProvider.notifier).syncEdit(profile.id, (rows) {
      rows[song] = LocalRating(
        profileId: profile.id,
        songId: 's1',
        provider: 'subsonic',
        rating: 9,
        serverRating: 5,
        updatedAt: 1,
      );
    });
    expect(c.read(localRatingsProvider)[song]?.rating, 9);
    expect((await LocalRatingStore(values).read(profile.id))[song]?.rating, 9);
  });

  test('should_keepSyncedRatingOnDisk_when_ratingWrittenWhileSyncEdits', () async {
    // No provider push afterwards, whose own save would hide a lost write.
    final c = container(capabilities: const {});
    c.listen(localRatingsProvider, (_, _) {});
    await c.read(activeSourceProvider.future);
    await pumpEventQueue();
    final ratings = c.read(localRatingsProvider.notifier);
    final synced = MediaId.subsonic('s1');
    final mine = MediaId.subsonic('s2');
    // The sync step is queued first; the user's rating lands on screen before it runs.
    final edit = ratings.syncEdit(profile.id, (rows) {
      rows[synced] = LocalRating(
        profileId: profile.id,
        songId: 's1',
        provider: 'subsonic',
        rating: 8,
        serverRating: 4,
        updatedAt: 1,
      );
    });
    final write = ratings.setRating(mine, 6);
    await edit;
    await write;
    await pumpEventQueue();
    final stored = await LocalRatingStore(values).read(profile.id);
    expect(stored[synced]?.rating, 8);
    expect(stored[mine]?.rating, 6);
    expect(c.read(localRatingsProvider)[synced]?.rating, 8);
  });

  test('should_shareOneStore_when_screensAndSyncReachSameAccount', () async {
    final c = container();
    final screens = await c.read(memoryStoreProvider.future);
    final sync = MemoryStore.shared(values, profile.id);
    expect(identical(screens, sync), isTrue);
    // A row the screens cached comes back changed after a sync edit, with no reload.
    final album = MediaId.subsonic('al1');
    await screens!.setTitle(album, 'Mine', nowMs: 1);
    expect((await screens.titleOf(album))?.title, 'Mine');
    await sync.syncEdit((rows) => rows.titles.remove(album));
    expect(await screens.titleOf(album), isNull);
  });

  test('should_deleteSavedLayoutAndStampEdits_when_resetOrEdited', () async {
    final c = container();
    c.listen(homeLayoutProvider, (_, _) {});
    await c.read(activeSourceProvider.future);
    await pumpEventQueue();
    final key = HomeLayoutNotifier.keyFor('subsonic', profile.id);
    final stamp = HomeLayoutNotifier.updatedAtKeyFor('subsonic', profile.id);
    c
        .read(homeLayoutProvider.notifier)
        .set(withSectionEnabled(HomeLayout.defaultLayout, HomeSection.jumpBackIn, false));
    await pumpEventQueue();
    expect(await values.read(key), isNotNull);
    expect(int.tryParse(await values.read(stamp) ?? ''), isNotNull);
    c.read(homeLayoutProvider.notifier).clear();
    await pumpEventQueue();
    expect(await values.read(key), isNull);
    expect(await values.read(stamp), isNull);
  });

  test('should_reportSyncedKeysOnly_when_storeWritten', () async {
    final observed = ObservedKeyValueStore(InMemoryStore());
    final keys = <String>[];
    final sub = observed.writes.where(isSyncedKey).listen(keys.add);
    addTearDown(sub.cancel);
    await observed.write('song_notes.v1.p', '[]');
    await observed.write('album_notes.v1.p', '[]');
    await observed.write('nowPlaying.keepScreenOn', 'true');
    await observed.write('local_ratings.p', '{}');
    await observed.write(ProfileRepository.profilesKey, '[]');
    await pumpEventQueue();
    expect(keys, ['song_notes.v1.p', 'album_notes.v1.p', 'local_ratings.p', ProfileRepository.profilesKey]);
  });
}
