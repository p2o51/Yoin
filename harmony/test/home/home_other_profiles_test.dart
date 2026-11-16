import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/activity/activity.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/profile/profile_repository.dart';
import 'package:yoin/home/home_other_profiles.dart';
import 'package:yoin/home/home_view_model.dart';
import 'package:yoin/memories/memories_ui_state.dart';
import 'package:yoin/memories/other_profiles.dart';
import 'package:yoin/platform/storage/stores.dart';

import '../support/library_fake_source.dart';

const _ann = MemoryOtherProfile(id: 'p-ann', name: 'ann', provider: MediaId.providerSubsonic);
const _ben = MemoryOtherProfile(id: 'p-ben', name: 'ben', provider: MediaId.providerSubsonic, shape: 3);

ActivityEvent _played(
  ActivityEntityType type,
  String id,
  int at, {
  ActivityActionType action = ActivityActionType.played,
}) => ActivityEvent(
  entityType: type,
  actionType: action,
  entityId: id,
  title: 'T $id',
  timestampMs: at,
  coverArtKey: 'c-$id',
);

OtherProfilePlays _row(MemoryOtherProfile p, String id, int count, {int at = 0}) => OtherProfilePlays(
  profile: p,
  kind: OtherProfileItemKind.album,
  id: MediaId(p.provider, id),
  title: id,
  subtitle: '',
  playCount: count,
  lastAt: at,
);

void main() {
  group('otherProfilePlays', () {
    test('should_countAlbumPlaysAndSkipVisitsAndOld_when_aggregating', () {
      final rows = otherProfilePlays(
        profile: _ann,
        log: [
          _played(ActivityEntityType.album, 'new', 900),
          _played(ActivityEntityType.playlist, 'mix', 800),
          _played(ActivityEntityType.album, 'loved', 700),
          _played(ActivityEntityType.album, 'seen', 600, action: ActivityActionType.visited),
          _played(ActivityEntityType.song, 'one', 500),
          _played(ActivityEntityType.album, 'old', 50),
        ],
        albumPlays: {MediaId.subsonic('loved'): 9, MediaId.subsonic('new'): 2},
        sinceMs: 100,
      );
      expect([for (final r in rows) r.id.rawId], ['loved', 'new', 'mix']);
      expect(rows.first.playCount, 9);
      expect(rows.last.kind, OtherProfileItemKind.playlist);
      expect(rows.first.cover, const CoverSourceRelative('c-loved'));
    });

    test('should_resolveCoverThroughThatAccount_when_coverForGiven', () {
      final rows = otherProfilePlays(
        profile: _ann,
        log: [_played(ActivityEntityType.album, 'x', 900)],
        albumPlays: const {},
        sinceMs: 0,
        coverFor: (c) => CoverUrl('https://ann.test/${c!.storageKey}'),
      );
      expect(rows.single.cover, const CoverUrl('https://ann.test/c-x'));
    });
  });

  group('buildOtherProfileItems', () {
    test('should_takeTurnsCappedPerAccount_when_twoAccountsPlayed', () {
      final items = buildOtherProfileItems(
        [
          [for (var i = 0; i < 8; i++) _row(_ann, 'a$i', 5 - i)],
          [_row(_ben, 'b0', 20), _row(_ben, 'b1', 1)],
        ],
        perProfile: 3,
        total: 5,
      );
      // ben's top item played most: ben leads the turns; ann is capped at 3.
      expect([for (final i in items) i.id.rawId], ['b0', 'a0', 'b1', 'a1', 'a2']);
    });

    test('should_dropDuplicatesAndEmptyAccounts_when_building', () {
      final items = buildOtherProfileItems([
        [_row(_ann, 'x', 3), _row(_ann, 'x', 2)],
        const [],
      ]);
      expect(items, hasLength(1));
      expect(items.single.stableId, 'other-profile:p-ann:album:x');
    });
  });

  group('provider and shelf', () {
    late InMemoryStore kv;
    late ProfileRepository repo;
    late Profile other;
    final now = DateTime.utc(2026, 11, 14);

    setUp(() async {
      kv = InMemoryStore();
      repo = ProfileRepository(kv, InMemoryStore());
      other = await repo.add(
        provider: MediaId.providerSubsonic,
        displayName: 'Ben',
        credentials: const SubsonicCredentials(baseUrl: 'https://b.test', username: 'ben', password: 'p'),
      );
      await repo.add(
        provider: MediaId.providerSubsonic,
        displayName: 'Ann',
        credentials: const SubsonicCredentials(baseUrl: 'https://a.test', username: 'ann', password: 'p'),
      );
      final at = now.millisecondsSinceEpoch - 86400000;
      await StoredActivityLog(kv, profileId: other.id).record(
        ActivityEvent(
          entityType: ActivityEntityType.album,
          actionType: ActivityActionType.played,
          entityId: 'x',
          title: 'Night Drive',
          subtitle: 'Ben Artist',
          timestampMs: at,
          coverArtKey: 'cx',
        ),
      );
      await MemoryStore(kv, profileId: other.id).recordAlbumPlay(MediaId.subsonic('x'), atMs: at);
    });

    List<Override> overrides() => [
      keyValueStoreProvider.overrideWithValue(kv),
      profileRepositoryProvider.overrideWithValue(repo),
      musicSourceFactoryProvider.overrideWithValue((_) => LibraryFakeSource()),
      activeSourceProvider.overrideWith((ref) async => LibraryFakeSource()),
      homeClockProvider.overrideWithValue(() => now),
    ];

    test('should_listOtherAccountsAlbums_when_theyPlayedLately', () async {
      final c = ProviderContainer(overrides: overrides());
      addTearDown(c.dispose);
      final items = await c.read(otherProfileItemsProvider.future);
      final item = items.single;
      expect(item.title, 'Night Drive');
      expect(item.profile.id, other.id);
      expect(item.profile.name, 'ben');
      expect(item.cover, const CoverUrl('https://covers.test/cx?size=320'));
    });

    testWidgets('should_askBeforeSwitching_when_shelfCardTappedFirstTime', (tester) async {
      final c = ProviderContainer(overrides: overrides());
      addTearDown(c.dispose);
      final items = await tester.runAsync(() => c.read(otherProfileItemsProvider.future));
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: c,
          child: MaterialApp(
            home: Scaffold(body: OtherProfileCard(item: items!.single)),
          ),
        ),
      );
      expect(find.text('Night Drive'), findsOneWidget);
      expect(find.text('ben'), findsOneWidget);
      await tester.tap(find.byType(OtherProfileCard));
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
      await tester.pumpAndSettle();
      expect(find.text('Switch to ben?'), findsOneWidget);
      expect(find.text('Switch and play'), findsOneWidget);
      await tester.tap(find.text('Cancel'));
      await tester.pumpAndSettle();
      expect(find.text('Switch to ben?'), findsNothing);
      // Cancelled: nothing switched, and the notice still counts as unseen.
      expect((await tester.runAsync(repo.activeProfile))!.id, isNot(other.id));
      expect(await tester.runAsync(() => OtherProfilesNoticeStore(kv).seen()), isFalse);
    });
  });
}
