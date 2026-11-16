import 'dart:math' as math;

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/memory/memory_copy_cache.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/profile/profile_repository.dart';
import 'package:yoin/memories/copy/memory_voice.dart';
import 'package:yoin/memories/memories_ui_state.dart';
import 'package:yoin/memories/memories_view_model.dart';
import 'package:yoin/memories/other_profiles.dart';
import 'package:yoin/platform/storage/stores.dart';

import '../support/library_fake_source.dart';

const _credsA = SubsonicCredentials(baseUrl: 'https://a.test', username: 'ann', password: 'p');
const _credsB = SubsonicCredentials(baseUrl: 'https://b.test', username: 'ben', password: 'p');

Album _album(String id, int tracks) => Album(
  id: MediaId.subsonic(id),
  name: 'Album $id',
  artist: 'Artist $id',
  songCount: tracks,
  coverArt: CoverSourceRelative('cover-$id'),
  tracks: [
    for (var i = 1; i <= tracks; i++)
      Track(id: MediaId.subsonic('$id-t$i'), title: 'Song $i', trackNumber: i, albumId: MediaId.subsonic(id)),
  ],
);

void main() {
  late InMemoryStore kv;
  late InMemoryStore secrets;
  late ProfileRepository repo;
  late Profile a;
  late Profile b;
  late LibraryFakeSource sourceA;
  late LibraryFakeSource sourceB;
  final now = DateTime.utc(2026, 11, 14, 12).millisecondsSinceEpoch;

  ProviderContainer container() {
    final c = ProviderContainer(
      overrides: [
        keyValueStoreProvider.overrideWithValue(kv),
        secretStoreProvider.overrideWithValue(secrets),
        profileRepositoryProvider.overrideWithValue(repo),
        musicSourceFactoryProvider.overrideWithValue(
          (credentials) => (credentials as SubsonicCredentials).baseUrl == _credsB.baseUrl ? sourceB : sourceA,
        ),
        // The account in use's source follows the stored active account.
        activeSourceProvider.overrideWith((ref) async {
          final active = await repo.activeProfile();
          if (active == null) return null;
          return active.id == b.id ? sourceB : sourceA;
        }),
        memoriesClockProvider.overrideWithValue(() => now),
        memoriesDayOfProvider.overrideWithValue((ms) {
          final t = DateTime.fromMillisecondsSinceEpoch(ms, isUtc: true);
          return DateTime.utc(t.year, t.month, t.day);
        }),
        memoriesRandomProvider.overrideWithValue(math.Random(7)),
        memoriesAppLanguageProvider.overrideWithValue(MemoryProseLanguage.en),
      ],
    );
    addTearDown(c.dispose);
    c.listen(memoriesViewModelProvider, (_, _) {});
    return c;
  }

  Future<MemoriesUiState> settled(ProviderContainer c) async {
    for (var i = 0; i < 80; i++) {
      await pumpEventQueue();
      final s = c.read(memoriesViewModelProvider);
      if (s is! MemoriesLoading) return s;
    }
    return c.read(memoriesViewModelProvider);
  }

  setUp(() async {
    kv = InMemoryStore();
    secrets = InMemoryStore();
    repo = ProfileRepository(kv, secrets);
    b = await repo.add(provider: MediaId.providerSubsonic, displayName: 'B', credentials: _credsB);
    a = await repo.add(provider: MediaId.providerSubsonic, displayName: 'A', credentials: _credsA);
    // a is in use (add makes the newest active).
    sourceA = LibraryFakeSource(
      albumDetails: {
        for (final x in [_album('a', 5)]) x.id: x,
      },
    );
    sourceB = LibraryFakeSource(
      albumDetails: {
        for (final x in [_album('x', 6)]) x.id: x,
      },
    );
  });

  test('should_dealOtherAccountsMemory_when_itHasAReview', () async {
    await MemoryStore(kv, profileId: b.id).setAlbumReview(MediaId.subsonic('x'), 'Late trains.', nowMs: now);
    final c = container();
    final s = await settled(c) as MemoriesContent;
    final m = s.memories.single;
    expect(m.albumName, 'Album x');
    expect(m.isFromOtherProfile, isTrue);
    expect(m.otherProfile!.id, b.id);
    expect(m.otherProfile!.name, 'ben');
    expect(m.tracks, hasLength(6));
    // Its cover is that account's own server's.
    expect(m.coverArt, const CoverUrl('https://covers.test/cover-x?size=600'));
  });

  test('should_refuseWrites_when_memoryIsAnotherAccounts', () async {
    final storeB = MemoryStore(kv, profileId: b.id);
    await storeB.setAlbumReview(MediaId.subsonic('x'), 'Late trains.', nowMs: now);
    final c = container();
    final m = (await settled(c) as MemoriesContent).memories.single;
    final vm = c.read(memoriesViewModelProvider.notifier);
    expect(await vm.saveTitle(m, 'Mine now'), isFalse);
    expect(await vm.saveReview(m, 'Overwritten'), isFalse);
    expect((await storeB.albumRatings())[MediaId.subsonic('x')]!.review, 'Late trains.');
    expect(await storeB.titles(), isEmpty);
  });

  test('should_wearOtherAccountsTitle_when_itNamedTheMemory', () async {
    final storeB = MemoryStore(kv, profileId: b.id);
    await storeB.setAlbumReview(MediaId.subsonic('x'), 'Late trains.', nowMs: now);
    await storeB.setTitle(MediaId.subsonic('x'), 'Night line', nowMs: now);
    final c = container();
    final m = (await settled(c) as MemoriesContent).memories.single;
    expect(m.memoryTitle, 'Night line');
    expect(m.memoryTitleKind, MemoryTitleKind.user);
  });

  test('should_wearOtherAccountsAiTitle_when_thatAccountWasGivenOne', () async {
    await MemoryStore(kv, profileId: b.id).setAlbumReview(MediaId.subsonic('x'), 'Late trains.', nowMs: now);
    // Written by the deck while b was in use, or brought by cloud sync.
    await MemoryCopyCache.shared(kv, b.id).put(MediaId.subsonic('x'), (_) => const MemoryCopyRow(title: 'Last train'));
    final c = container();
    final m = (await settled(c) as MemoriesContent).memories.single;
    expect(m.memoryTitle, 'Last train');
    expect(m.memoryTitleKind, MemoryTitleKind.ai);
  });

  test('should_refreshInPlace_when_cloudSyncBringsAiWords', () async {
    await MemoryStore(kv, profileId: b.id).setAlbumReview(MediaId.subsonic('x'), 'Late trains.', nowMs: now);
    final c = container();
    final before = (await settled(c) as MemoriesContent).memories.single;
    expect(before.memoryTitleKind, MemoryTitleKind.album);
    await MemoryCopyCache.shared(
      kv,
      b.id,
    ).syncEdit((rows) => rows[MediaId.subsonic('x')] = const MemoryCopyRow(title: 'Last train'));
    c.read(memoryCopySyncRevisionProvider.notifier).bump();
    MemoriesContent? after;
    for (var i = 0; i < 20; i++) {
      await Future<void>.delayed(const Duration(milliseconds: 50));
      after = c.read(memoriesViewModelProvider) as MemoriesContent;
      if (after.memories.single.memoryTitle == 'Last train') break;
    }
    expect(after!.memories.single.memoryTitle, 'Last train');
    expect(after.memories.single.sessionId, before.sessionId);
  });

  test('should_leaveOutAccount_when_itsCredentialsAreLost', () async {
    await MemoryStore(kv, profileId: b.id).setAlbumReview(MediaId.subsonic('x'), 'Late trains.', nowMs: now);
    await secrets.write(ProfileRepository.credentialsKey(b.id), null);
    final c = container();
    expect(await settled(c), isA<MemoriesEmpty>());
  });

  test('should_keepTheDeck_when_theAccountSwitches', () async {
    await MemoryStore(kv, profileId: a.id).setAlbumReview(MediaId.subsonic('a'), 'Home.', nowMs: now);
    await MemoryStore(kv, profileId: b.id).setAlbumReview(MediaId.subsonic('x'), 'Away.', nowMs: now);
    final c = container();
    final before = (await settled(c) as MemoriesContent).memories;
    expect(before, hasLength(2));
    expect({for (final m in before) m.albumName: m.otherProfile?.id}, {'Album a': null, 'Album x': b.id});

    await repo.setActive(b.id);
    c.invalidate(activeSourceProvider);
    late List<MemoryEntry> after;
    for (var i = 0; i < 80; i++) {
      await pumpEventQueue();
      final s = c.read(memoriesViewModelProvider);
      if (s is MemoriesContent && s.memories.any((m) => m.albumName == 'Album a' && m.isFromOtherProfile)) {
        after = s.memories;
        break;
      }
    }
    // The same cards, in the same order, now seen from the other account.
    expect([for (final m in after) m.sessionId], [for (final m in before) m.sessionId]);
    expect({for (final m in after) m.albumName: m.otherProfile?.id}, {'Album a': a.id, 'Album x': null});
  });

  test('should_dropTheDeck_when_theLastAccountIsRemoved', () async {
    await MemoryStore(kv, profileId: a.id).setAlbumReview(MediaId.subsonic('a'), 'Home.', nowMs: now);
    final c = container();
    expect(await settled(c), isA<MemoriesContent>());
    await repo.remove(a.id);
    await repo.remove(b.id);
    c.invalidate(activeSourceProvider);
    for (var i = 0; i < 40; i++) {
      await pumpEventQueue();
    }
    // Nothing of a removed account's deck stays on screen.
    expect(c.read(memoriesViewModelProvider), isA<MemoriesLoading>());
  });

  test('should_listOnlyUsableAccounts_when_filtering', () async {
    final c = container();
    final others = await c.read(otherProfileSourcesProvider.future);
    expect([for (final o in others) o.id], [b.id]);
    expect(others.single.profile.provider, MediaId.providerSubsonic);
  });

  group('switch notice', () {
    test('should_askOnce_when_noticeSeenIsStored', () async {
      final store = OtherProfilesNoticeStore(kv);
      expect(await store.seen(), isFalse);
      await store.markSeen();
      expect(await store.seen(), isTrue);
    });
  });
}
