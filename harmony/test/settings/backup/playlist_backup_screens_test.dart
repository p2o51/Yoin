import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/backup/playlist_backup_models.dart';
import 'package:yoin/data/backup/playlist_backup_providers.dart';
import 'package:yoin/data/backup/playlist_backup_service.dart';
import 'package:yoin/data/backup/playlist_backup_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/transfer/restore_review.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/settings/backup/playlist_backup_screen.dart';
import 'package:yoin/settings/backup/playlist_backup_view_model.dart';
import 'package:yoin/settings/backup/playlist_backups_screen.dart';
import 'package:yoin/settings/backup/playlist_restore_sheet.dart';

import '../../support/backup_fakes.dart';
import '../../support/fake_music_source.dart';

PlaylistBackupRecord _backup({String id = 'b1', int unexported = 1, int restores = 0, bool gone = false}) =>
    PlaylistBackupRecord(
      id: id,
      name: 'Night drive',
      sourceProvider: MediaId.providerSubsonic,
      sourceProfileLabel: 'Home',
      sourcePlaylistId: 'subsonic:p1',
      sourceProfileId: 'home',
      playlistCreatedAt: '2024-03-01T10:00:00Z',
      firstBackedUpAt: DateTime(2026, 10, 1, 9).millisecondsSinceEpoch,
      lastBackedUpAt: DateTime(2026, 11, 15, 14, 5).millisecondsSinceEpoch,
      lastChangedAt: DateTime(2026, 11, 14, 8).millisecondsSinceEpoch,
      sourceGoneAt: gone ? DateTime(2026, 11, 16, 8).millisecondsSinceEpoch : null,
      content: PlaylistBackupContent(
        tracks: const [
          BackupTrack(title: 'Entropy', artists: ['Beach Bunny'], durationSec: 221, sourceTrackId: 'subsonic:t1'),
          BackupTrack(title: 'Lemon', artists: ['Kenshi Yonezu'], durationSec: 255, sourceTrackId: 'subsonic:t2'),
        ],
        unexported: [
          for (var i = 0; i < unexported; i++)
            BackupUnexported(position: 2 + i, title: 'Voice memo $i', reason: 'local_file'),
        ],
      ),
      restores: [
        for (var i = 0; i < restores; i++)
          const PlaylistRestoreRecord(
            at: 1763200000000,
            targetProvider: MediaId.providerAppleMusic,
            targetLabel: 'Apple Music',
            added: 2,
            skipped: 0,
            notFound: 1,
          ),
      ],
      updatedAt: 1,
    );

void main() {
  final now = DateTime(2026, 11, 16, 12).millisecondsSinceEpoch;
  late PlaylistBackupStore store;
  late InMemoryStore kv;

  setUp(() {
    store = PlaylistBackupStore(InMemoryPlaylistBackupStorage());
    kv = InMemoryStore();
  });

  Future<void> pump(WidgetTester tester, Widget home) async {
    tester.view.physicalSize = const Size(800, 2400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final profiles = FakeBackupProfiles(
      all: const [
        Profile(id: 'home', provider: MediaId.providerSubsonic, displayName: 'Home'),
        Profile(id: 'am', provider: MediaId.providerAppleMusic, displayName: 'Apple Music'),
      ],
      activeId: 'home',
      sources: {'home': FakeTransferSource()},
    );
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          keyValueStoreProvider.overrideWithValue(kv),
          secretStoreProvider.overrideWithValue(kv),
          musicSourceFactoryProvider.overrideWithValue((_) => FakeMusicSource()),
          playlistBackupStoreProvider.overrideWithValue(store),
          playlistBackupServiceProvider.overrideWithValue(PlaylistBackupService(profiles: profiles, store: store)),
        ],
        child: MaterialApp(home: home),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets('should_sayNoBackups_when_noneWasMade', (tester) async {
    await pump(tester, PlaylistBackupsScreen(now: now));
    expect(find.text('Playlist cloud backup'), findsWidgets);
    expect(find.text('No backups'), findsOneWidget);
  });

  testWidgets('should_listEachBackupWithItsCounts_when_backupsExist', (tester) async {
    await tester.runAsync(() => store.save(_backup()));
    await tester.runAsync(() => store.save(_backup(id: 'b2', unexported: 0, gone: true)));
    await pump(tester, PlaylistBackupsScreen(now: now));
    expect(find.text('Night drive'), findsNWidgets(2));
    expect(find.text('2 songs'), findsNWidgets(2));
    expect(find.text("1 couldn't be backed up"), findsOneWidget);
    expect(find.text('Original playlist gone'), findsOneWidget);
  });

  testWidgets('should_openTheBackupsPage_when_aRowIsTapped', (tester) async {
    await tester.runAsync(() => store.save(_backup()));
    await pump(tester, PlaylistBackupsScreen(now: now));
    await tester.tap(find.text('Night drive'));
    await tester.pumpAndSettle();
    expect(find.byType(PlaylistBackupScreen), findsOneWidget);
  });

  testWidgets('should_showStatusSongsAndHistory_when_aBackupIsOpen', (tester) async {
    await tester.runAsync(() => store.save(_backup(restores: 1)));
    await pump(tester, PlaylistBackupScreen(backupId: 'b1', now: now));
    expect(find.text('Following changes'), findsOneWidget);
    expect(find.text('Playlist created'), findsOneWidget);
    expect(find.text('Backed up since'), findsOneWidget);
    expect(find.text('Last backup'), findsOneWidget);
    expect(find.text('Last change'), findsOneWidget);
    expect(find.text('Restore'), findsOneWidget);
    expect(find.text('Back up now'), findsOneWidget);
    expect(find.text('Restores'), findsOneWidget);
    expect(find.text('2 added'), findsOneWidget);
    expect(find.text('1 not found'), findsOneWidget);
    expect(find.text("Couldn't be backed up"), findsOneWidget);
    expect(find.text('Local file'), findsOneWidget);
    expect(find.text('Entropy'), findsOneWidget);
    expect(find.text('Lemon'), findsOneWidget);
  });

  testWidgets('should_offerNoBackUpNow_when_thePlaylistIsGone', (tester) async {
    await tester.runAsync(() => store.save(_backup(gone: true)));
    await pump(tester, PlaylistBackupScreen(backupId: 'b1', now: now));
    expect(find.text('Original playlist gone'), findsOneWidget);
    expect(find.text('Back up now'), findsNothing);
  });

  testWidgets('should_deleteOnlyAfterConfirming_when_deleteChosen', (tester) async {
    await tester.runAsync(() => store.save(_backup()));
    await pump(tester, PlaylistBackupScreen(backupId: 'b1', now: now));
    await tester.tap(find.byTooltip('More options'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Delete'));
    await tester.pumpAndSettle();
    expect(find.text('Delete this backup?'), findsOneWidget);
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();
    expect(await tester.runAsync(() => store.get('b1')), isNotNull);
    await tester.tap(find.byTooltip('More options'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Delete'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(TextButton, 'Delete'));
    // The store's own futures finish on the real event loop.
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
    await tester.pumpAndSettle();
    expect(await tester.runAsync(() => store.get('b1')), isNull);
  });

  testWidgets('should_listTheAccounts_when_restoreIsTapped', (tester) async {
    await tester.runAsync(() => store.save(_backup()));
    await pump(tester, PlaylistBackupScreen(backupId: 'b1', now: now));
    await tester.tap(find.text('Restore'));
    await tester.pumpAndSettle();
    expect(find.text('Restore to'), findsOneWidget);
    expect(find.text('Home'), findsWidgets);
    expect(find.text('Apple Music'), findsWidgets);
  });

  group('review', () {
    const target = RestoreTarget(profileId: 'am', displayName: 'Apple Music', provider: MediaId.providerAppleMusic);
    final c1 = song('c1', 'Lemon', '米津玄師', durationSec: 255, provider: MediaId.providerAppleMusic);
    final c2 = song('c2', 'Lemon (Cover)', 'Someone', durationSec: 250, provider: MediaId.providerAppleMusic);
    final e = song('e', 'Entropy', 'Beach Bunny', durationSec: 221, provider: MediaId.providerAppleMusic);

    Future<({List<(int, bool)> checks, List<(int, int)> chosen, List<int> searched, List<String> calls})> pumpReview(
      WidgetTester tester,
      List<ReviewRow> rows,
    ) async {
      final checks = <(int, bool)>[];
      final chosen = <(int, int)>[];
      final searched = <int>[];
      final calls = <String>[];
      tester.view.physicalSize = const Size(800, 1600);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: PlaylistRestoreSheetContent(
              state: RestoreReviewing(target, rows),
              backup: _backup(unexported: 0).copyWith(
                content: const PlaylistBackupContent(
                  tracks: [
                    BackupTrack(title: 'Entropy', artists: ['Beach Bunny'], sourceTrackId: 'subsonic:t1'),
                    BackupTrack(title: 'Lemon', artists: ['Kenshi Yonezu'], sourceTrackId: 'subsonic:t2'),
                    BackupTrack(title: 'Creep', artists: ['Radiohead'], sourceTrackId: 'subsonic:t3'),
                  ],
                ),
              ),
              targets: const [target],
              onChooseTarget: (_) {},
              onCheck: (p, c) => checks.add((p, c)),
              onChoose: (p, i) => chosen.add((p, i)),
              onOpenSearch: (p, _) => searched.add(p),
              onCreate: () => calls.add('create'),
              onCancel: () => calls.add('cancel'),
              onDone: () {},
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      return (checks: checks, chosen: chosen, searched: searched, calls: calls);
    }

    List<ReviewRow> rows() => [
      ReviewRow(position: 0, status: ReviewStatus.matched, candidates: [e], chosen: 0, checked: true),
      ReviewRow(position: 1, status: ReviewStatus.uncertain, candidates: [c1, c2], chosen: 0, checked: false),
      const ReviewRow(position: 2, status: ReviewStatus.notFound, candidates: [], chosen: 0, checked: false),
    ];

    testWidgets('should_summariseAndCountWhatWillBeWritten_when_reviewShows', (tester) async {
      await pumpReview(tester, rows());
      expect(find.text('All 3'), findsOneWidget);
      expect(find.text('To check 1'), findsOneWidget);
      expect(find.text('Not found 1'), findsOneWidget);
      expect(find.text('Create playlist'), findsOneWidget);
      expect(find.text('1'), findsWidgets);
    });

    testWidgets('should_uncheckAMatch_when_itsRowIsTapped', (tester) async {
      final r = await pumpReview(tester, rows());
      await tester.tap(find.text('Entropy'));
      expect(r.checks, [(0, false)]);
    });

    testWidgets('should_showCandidatesAndChooseOne_when_anUncertainRowIsOpened', (tester) async {
      final r = await pumpReview(tester, rows());
      await tester.tap(find.text('Kenshi Yonezu'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Lemon (Cover)'));
      expect(r.chosen, [(1, 1)]);
    });

    testWidgets('should_searchTheTarget_when_aSongWasNotFound', (tester) async {
      final r = await pumpReview(tester, rows());
      await tester.tap(find.text('Search'));
      expect(r.searched, [2]);
    });

    testWidgets('should_showOnlyTheSongsToCheck_when_thatFilterIsPicked', (tester) async {
      await pumpReview(tester, rows());
      await tester.tap(find.text('To check 1'));
      await tester.pumpAndSettle();
      expect(find.text('Entropy'), findsNothing);
      expect(find.text('Kenshi Yonezu'), findsOneWidget);
    });

    testWidgets('should_createAndCancel_when_theirButtonsAreTapped', (tester) async {
      final r = await pumpReview(tester, rows());
      await tester.tap(find.text('Create playlist'));
      await tester.tap(find.text('Cancel'));
      expect(r.calls, ['create', 'cancel']);
    });
  });

  testWidgets('should_showTheCounts_when_theRestoreIsDone', (tester) async {
    tester.view.physicalSize = const Size(800, 1600);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: PlaylistRestoreSheetContent(
            state: const RestoreDone(
              RestoreTarget(profileId: 'am', displayName: 'Apple Music', provider: MediaId.providerAppleMusic),
              PlaylistRestoreRecord(
                at: 0,
                targetProvider: 'applemusic',
                targetLabel: 'Apple Music',
                added: 38,
                skipped: 4,
                notFound: 3,
              ),
            ),
            backup: _backup(),
            targets: const [],
            onChooseTarget: (_) {},
            onCheck: (_, _) {},
            onChoose: (_, _) {},
            onOpenSearch: (_, _) {},
            onCreate: () {},
            onCancel: () {},
            onDone: () {},
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('Created on Apple Music'), findsOneWidget);
    expect(find.text('38'), findsOneWidget);
    expect(find.text('Added'), findsOneWidget);
    expect(find.text('Done'), findsOneWidget);
  });
}
