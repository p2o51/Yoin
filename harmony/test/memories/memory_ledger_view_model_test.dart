import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/memory/memory_grid.dart';
import 'package:yoin/data/memory/memory_ledger.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/profile/profile_repository.dart';
import 'package:yoin/memories/ledger/memory_ledger_view_model.dart';
import 'package:yoin/memories/memories_view_model.dart';
import 'package:yoin/platform/storage/stores.dart';

import '../support/library_fake_source.dart';

const _creds = SubsonicCredentials(baseUrl: 'https://m.test', username: 'u', password: 'p');

void main() {
  final now = DateTime.utc(2026, 11, 14, 12).millisecondsSinceEpoch;
  late InMemoryStore kv;
  late ProfileRepository repo;
  late Profile profile;
  late LibraryFakeSource source;

  setUp(() async {
    kv = InMemoryStore();
    repo = ProfileRepository(kv, InMemoryStore());
    profile = await repo.add(provider: MediaId.providerSubsonic, displayName: 'Home', credentials: _creds);
    source = LibraryFakeSource(
      albumDetails: {
        MediaId.subsonic('a'): Album(
          id: MediaId.subsonic('a'),
          name: 'Album a',
          artist: 'Artist a',
          songCount: 2,
          tracks: [
            Track(id: MediaId.subsonic('a-t1'), title: 'Song 1', albumId: MediaId.subsonic('a')),
            Track(id: MediaId.subsonic('a-t2'), title: 'Song 2', albumId: MediaId.subsonic('a')),
          ],
        ),
      },
    );
  });

  ProviderContainer container() {
    final c = ProviderContainer(
      overrides: [
        keyValueStoreProvider.overrideWithValue(kv),
        profileRepositoryProvider.overrideWithValue(repo),
        activeSourceProvider.overrideWith((ref) async => source),
        memoriesClockProvider.overrideWithValue(() => now),
        memoriesDayOfProvider.overrideWithValue((ms) {
          final t = DateTime.fromMillisecondsSinceEpoch(ms, isUtc: true);
          return DateTime.utc(t.year, t.month, t.day);
        }),
        memoryLedgerFirstDayProvider.overrideWithValue(DateTime.monday),
      ],
    );
    addTearDown(c.dispose);
    return c;
  }

  test('should_nameAlbumsFromDetail_when_ledgerLoaded', () async {
    final store = MemoryStore(kv, profileId: profile.id);
    await store.setAlbumReview(MediaId.subsonic('a'), 'Rain again.', nowMs: now - 86400000);
    await store.setTitle(MediaId.subsonic('a'), 'The rain year', nowMs: now);
    await store.putSongNote(
      SongNote(
        id: 'n1',
        trackId: MediaId.subsonic('a-t2'),
        content: 'the bridge',
        createdAt: now - 2 * 86400000,
        updatedAt: now - 2 * 86400000,
      ),
    );
    final c = container();
    final state = await c.read(memoryLedgerProvider.future);
    expect(state.entries.map((e) => e.kind), [
      MemoryLedgerKind.title,
      MemoryLedgerKind.review,
      MemoryLedgerKind.songNote,
    ]);
    expect(state.entries.first.album!.name, 'Album a');
    // A note written without its album finds it through the album's tracks.
    expect(state.entries.last.album!.albumId, MediaId.subsonic('a'));
    expect(state.entries.last.trackTitle, 'Song 2');
    expect(state.stats.writings, 2);
    expect(state.grid.scale, MemoryGridScale.month);
  });

  test('should_showNothing_when_nothingKept', () async {
    final c = container();
    final state = await c.read(memoryLedgerProvider.future);
    expect(state.isEmpty, isTrue);
  });

  group('every account', () {
    const credsB = SubsonicCredentials(baseUrl: 'https://b.test', username: 'ben', password: 'p');
    late InMemoryStore secrets;
    late Profile b;
    late LibraryFakeSource sourceB;

    setUp(() async {
      kv = InMemoryStore();
      secrets = InMemoryStore();
      repo = ProfileRepository(kv, secrets);
      b = await repo.add(provider: MediaId.providerSubsonic, displayName: 'B', credentials: credsB);
      // The newest account is the one in use.
      profile = await repo.add(provider: MediaId.providerSubsonic, displayName: 'Home', credentials: _creds);
      sourceB = LibraryFakeSource();
    });

    ProviderContainer accounts() {
      final c = ProviderContainer(
        overrides: [
          keyValueStoreProvider.overrideWithValue(kv),
          secretStoreProvider.overrideWithValue(secrets),
          profileRepositoryProvider.overrideWithValue(repo),
          musicSourceFactoryProvider.overrideWithValue((credentials) => sourceB),
          activeSourceProvider.overrideWith((ref) async => source),
          memoriesClockProvider.overrideWithValue(() => now),
          memoriesDayOfProvider.overrideWithValue((ms) {
            final t = DateTime.fromMillisecondsSinceEpoch(ms, isUtc: true);
            return DateTime.utc(t.year, t.month, t.day);
          }),
          memoryLedgerFirstDayProvider.overrideWithValue(DateTime.monday),
        ],
      );
      addTearDown(c.dispose);
      return c;
    }

    Future<void> otherAccountKeptAReview() async {
      final storeB = MemoryStore.shared(kv, b.id);
      await storeB.recordAlbumPlay(
        MediaId.subsonic('x'),
        atMs: now - 86400000,
        albumName: 'Album x',
        artistName: 'Artist x',
        coverArtKey: 'cover-x',
      );
      await storeB.setAlbumReview(MediaId.subsonic('x'), 'Late trains.', nowMs: now - 3600000);
    }

    test('should_showOtherAccountsMemories_when_ledgerLoaded', () async {
      await MemoryStore.shared(kv, profile.id).setAlbumReview(MediaId.subsonic('a'), 'Rain again.', nowMs: now);
      await otherAccountKeptAReview();
      final state = await accounts().read(memoryLedgerProvider.future);
      expect(state.entries.map((e) => (e.kind, e.profileId)), [
        (MemoryLedgerKind.review, profile.id),
        (MemoryLedgerKind.review, b.id),
      ]);
      final theirs = state.entries.last.album!;
      expect(theirs.name, 'Album x');
      expect(theirs.profileId, b.id);
      // Its cover is that account's own server's.
      expect(theirs.cover, const CoverUrl('https://covers.test/cover-x?size=600'));
      expect(state.activeProfileId, profile.id);
      expect(state.profiles.map((p) => p.id), [profile.id, b.id]);
      expect(state.profiles.last.name, 'ben');
      expect(state.profiles.last.canSwitch, isTrue);
      // Only another account's memory says whose it is.
      expect(state.fromProfile(b.id)?.name, 'ben');
      expect(state.fromProfile(profile.id), isNull);
      expect(state.opens(theirs), isTrue);
      expect(state.isOwn(theirs), isFalse);
      expect(state.stats.albums, 2);
    });

    test('should_sayNoOnesName_when_onlyOneAccountHasMemories', () async {
      await otherAccountKeptAReview();
      final state = await accounts().read(memoryLedgerProvider.future);
      expect(state.profiles.map((p) => p.id), [b.id]);
      expect(state.fromProfile(b.id), isNull);
    });

    test('should_keepShutAndCoverless_when_accountCantBeSwitchedTo', () async {
      await otherAccountKeptAReview();
      await secrets.write(ProfileRepository.credentialsKey(b.id), null);
      final state = await accounts().read(memoryLedgerProvider.future);
      final theirs = state.entries.single.album!;
      expect(theirs.name, 'Album x');
      // A cover id needs that account's server: none to ask.
      expect(theirs.cover, isNull);
      expect(state.profile(b.id)!.canSwitch, isFalse);
      expect(state.opens(theirs), isFalse);
    });

    test('should_leaveOutRows_when_theirAccountIsGone', () async {
      await MemoryStore.shared(kv, 'removed').setAlbumReview(MediaId.subsonic('x'), 'Gone.', nowMs: now);
      final state = await accounts().read(memoryLedgerProvider.future);
      expect(state.isEmpty, isTrue);
    });

    test('should_reload_when_otherAccountsRowsWritten', () async {
      final c = accounts();
      c.listen(memoryLedgerProvider, (_, _) {});
      expect((await c.read(memoryLedgerProvider.future)).isEmpty, isTrue);
      await otherAccountKeptAReview();
      for (var i = 0; i < 20 && (c.read(memoryLedgerProvider).value?.isEmpty ?? true); i++) {
        await Future<void>.delayed(const Duration(milliseconds: 50));
      }
      expect(c.read(memoryLedgerProvider).value!.entries.single.profileId, b.id);
    });
  });
}
