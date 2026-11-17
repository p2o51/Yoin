import 'dart:async';

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
import 'package:yoin/offline/offline_providers.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/settings/backup/playlist_backup_screen.dart';
import 'package:yoin/settings/backup/playlist_backup_view_model.dart';
import 'package:yoin/settings/backup/restore_live_update.dart';
import 'package:yoin/shell/shell_state.dart' show appNavigatorKey;

import '../../support/backup_fakes.dart';
import '../../support/fake_music_source.dart';
import '../../support/transfer_fakes.dart';

/// A target whose searches wait for [gate] (a slow network).
class _GatedSource extends FakeTransferSource {
  _GatedSource({super.provider, super.catalogue});

  Completer<void>? gate;

  @override
  Future<SearchResults> search(String query, {int trackCount = 20, int albumCount = 20, int artistCount = 20}) async {
    final waiting = gate;
    if (waiting != null) await waiting.future;
    return super.search(query, trackCount: trackCount, albumCount: albumCount, artistCount: artistCount);
  }
}

PlaylistBackupRecord _backup() => PlaylistBackupRecord(
  id: 'b1',
  name: 'Night drive',
  sourceProvider: MediaId.providerSubsonic,
  sourceProfileLabel: 'Home',
  sourcePlaylistId: 'subsonic:p1',
  sourceProfileId: 'home',
  playlistCreatedAt: null,
  firstBackedUpAt: DateTime(2026, 10, 1, 9).millisecondsSinceEpoch,
  lastBackedUpAt: DateTime(2026, 11, 15, 14, 5).millisecondsSinceEpoch,
  lastChangedAt: DateTime(2026, 11, 14, 8).millisecondsSinceEpoch,
  sourceGoneAt: null,
  content: const PlaylistBackupContent(
    tracks: [
      BackupTrack(title: 'Entropy', artists: ['Beach Bunny'], durationSec: 221, sourceTrackId: 'subsonic:t1'),
      BackupTrack(title: 'Lemon', artists: ['Kenshi Yonezu'], durationSec: 255, sourceTrackId: 'subsonic:t2'),
    ],
  ),
  restores: const [],
  updatedAt: 1,
);

void main() {
  testWidgets('should_keepMatchingInTheBackgroundAndShowItLive_when_theSheetClosesAndReopens', (tester) async {
    tester.view.physicalSize = const Size(800, 2400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    // Made in the test's own fake time.
    final store = PlaylistBackupStore(InMemoryPlaylistBackupStorage());
    await store.save(_backup());
    final kv = InMemoryStore();
    final host = RecordingTransferHost()..allowed = true;
    final am = _GatedSource(
      provider: MediaId.providerAppleMusic,
      catalogue: [song('am1', 'Entropy', 'Beach Bunny', durationSec: 221, provider: MediaId.providerAppleMusic)],
    )..gate = Completer<void>();
    final profiles = FakeBackupProfiles(
      all: const [
        Profile(id: 'home', provider: MediaId.providerSubsonic, displayName: 'Home'),
        Profile(id: 'am', provider: MediaId.providerAppleMusic, displayName: 'Apple Music'),
      ],
      activeId: 'home',
      sources: {'home': FakeTransferSource(), 'am': am},
    );
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          keyValueStoreProvider.overrideWithValue(kv),
          secretStoreProvider.overrideWithValue(kv),
          musicSourceFactoryProvider.overrideWithValue((_) => FakeMusicSource()),
          playlistBackupStoreProvider.overrideWithValue(store),
          playlistBackupServiceProvider.overrideWithValue(PlaylistBackupService(profiles: profiles, store: store)),
          backgroundTransferHostProvider.overrideWithValue(host),
        ],
        child: const MaterialApp(home: PlaylistBackupScreen(backupId: 'b1')),
      ),
    );
    await tester.pumpAndSettle();
    final container = ProviderScope.containerOf(tester.element(find.byType(PlaylistBackupScreen)));
    RestoreUiState restore() => container.read(playlistBackupPageProvider('b1')).restore;

    await tester.tap(find.text('Restore'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Apple Music').last);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));
    expect(find.text('Matching on Apple Music'), findsOneWidget);
    // The permission is asked as the restore starts, and the match holds the task with its progress.
    expect(host.calls, containsAllInOrder(['ask', 'hold Matching on Apple Music']));

    // The sheet goes; the match doesn't.
    Navigator.of(tester.element(find.text('Matching on Apple Music'))).pop();
    await tester.pump();
    await tester.pump(const Duration(seconds: 1));
    expect(find.text('Matching on Apple Music'), findsNothing);
    expect(restore(), isA<RestoreMatching>());
    expect(host.calls, isNot(contains('release')));

    // Restore again: the same match, live.
    await tester.tap(find.text('Restore'));
    await tester.pump();
    await tester.pump(const Duration(seconds: 1));
    expect(find.text('Matching on Apple Music'), findsOneWidget);
    expect(find.text('0 / 2'), findsOneWidget);
    am.gate!.complete();
    await tester.pump();
    await tester.pump(const Duration(seconds: 1));
    await tester.pumpAndSettle();
    expect(restore(), isA<RestoreReviewing>());
    expect(find.text('Create playlist'), findsOneWidget);
    // Matched: the task and its notification go; the review waits in the app.
    expect(host.calls.last, 'cancel 7367794');
    expect(host.calls, contains('release'));
  });

  testWidgets('should_openTheBackupsPage_when_itsRestoreNotificationIsTapped', (tester) async {
    tester.view.physicalSize = const Size(800, 2400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final store = PlaylistBackupStore(InMemoryPlaylistBackupStorage());
    await store.save(_backup());
    final kv = InMemoryStore();
    final host = RecordingTransferHost();
    final profiles = FakeBackupProfiles(
      all: const [Profile(id: 'home', provider: MediaId.providerSubsonic, displayName: 'Home')],
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
          backgroundTransferHostProvider.overrideWithValue(host),
        ],
        child: Consumer(
          builder: (context, ref, _) {
            ref.listen(restoreNoticeTapsProvider, (_, _) {});
            return MaterialApp(navigatorKey: appNavigatorKey, home: const SizedBox());
          },
        ),
      ),
    );
    // Another notification's tap opens nothing; a restore's opens its backup.
    host.tapped.add('download:1');
    await tester.pumpAndSettle();
    expect(find.byType(PlaylistBackupScreen), findsNothing);
    host.tapped.add(RestoreLiveUpdate.linkTo('b1'));
    await tester.pumpAndSettle();
    expect(find.byType(PlaylistBackupScreen), findsOneWidget);
    expect(find.text('Night drive'), findsWidgets);
  });
}
