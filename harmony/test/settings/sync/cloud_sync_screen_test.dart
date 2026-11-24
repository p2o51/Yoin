import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/media_id.dart';
import 'package:yoin/data/sync/cloud_data_views.dart';
import 'package:yoin/data/sync/cloud_sync_state.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/settings/settings_screen.dart';
import 'package:yoin/settings/sync/cloud_account_page.dart';
import 'package:yoin/settings/sync/cloud_sync_screen.dart';
import 'package:yoin/settings/sync/cloud_sync_strings.dart';
import 'package:yoin/settings/sync/cloud_sync_view_model.dart';

import '../../support/fake_music_source.dart';

class FakeCloudSync implements CloudSyncController {
  FakeCloudSync([CloudSyncState initial = const CloudSyncState()]) : _state = ValueNotifier(initial);

  final ValueNotifier<CloudSyncState> _state;
  final calls = <String>[];
  TurnOnStep next = const TurnOnStarted();
  Future<TurnOnStep>? pendingTurnOn;
  bool waiting = false;

  set value(CloudSyncState s) => _state.value = s;

  @override
  ValueListenable<CloudSyncState> get state => _state;

  @override
  Future<void> ensureInitialized() async => calls.add('init');

  @override
  Future<TurnOnStep> turnOn() async {
    calls.add('turnOn');
    if (pendingTurnOn != null) return pendingTurnOn!;
    if (next is TurnOnStarted) _state.value = const CloudSyncState(phase: SyncUpToDate());
    return next;
  }

  @override
  void cancelTurnOn() => calls.add('cancel');

  @override
  bool get turnOnWaitingForBrowser => waiting;

  @override
  void syncNow() => calls.add('syncNow');

  @override
  void turnOff() {
    calls.add('turnOff');
    _state.value = const CloudSyncState();
  }

  @override
  Future<void> deleteCloudData() async => calls.add('delete');

  @override
  void resolveReview({required bool restore}) => calls.add('review:$restore');

  @override
  void uploadAgain() => calls.add('uploadAgain');

  @override
  void startSyncingAccount(String localProfileId) => calls.add('start:$localProfileId');

  @override
  void linkAccount(String localProfileId, String syncProfileId) => calls.add('link:$localProfileId>$syncProfileId');

  @override
  void keepAccountAsBefore(String localProfileId) => calls.add('keep:$localProfileId');

  @override
  void startFreshAccount(String localProfileId) => calls.add('fresh:$localProfileId');

  @override
  void resumeAccountSync(String localProfileId) => calls.add('resume:$localProfileId');

  @override
  void removeDevice(String deviceId) => calls.add('remove:$deviceId');

  /// What the next removal does: null = it goes through.
  Object? removeError;
  final contents = <String, CloudAccountContent>{};

  @override
  Future<void> removeAccountFromCloud(String syncProfileId) async {
    calls.add('removeFromCloud:$syncProfileId');
    if (removeError != null) throw removeError!;
  }

  @override
  Future<CloudAccountContent> cloudAccountContent(String syncProfileId) async =>
      contents[syncProfileId] ?? const CloudAccountContent();
}

void main() {
  final now = DateTime(2026, 11, 16, 12).millisecondsSinceEpoch;
  late FakeCloudSync sync;
  late List<String> messages;

  Future<void> pump(WidgetTester tester, Widget home) async {
    tester.view.physicalSize = const Size(800, 3000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final store = InMemoryStore();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          keyValueStoreProvider.overrideWithValue(store),
          secretStoreProvider.overrideWithValue(store),
          musicSourceFactoryProvider.overrideWithValue((_) => FakeMusicSource()),
          cloudSyncControllerProvider.overrideWithValue(sync),
          cloudSyncMessengerProvider.overrideWithValue(messages.add),
        ],
        child: MaterialApp(home: home),
      ),
    );
    await tester.pumpAndSettle();
  }

  setUp(() {
    sync = FakeCloudSync();
    messages = [];
  });

  testWidgets('should_offerTurnOn_when_syncOff', (tester) async {
    await pump(tester, CloudSyncScreen(now: now));
    expect(find.text('Sync notes, ratings and translations'), findsOneWidget);
    expect(find.text('Notes, ratings and reviews'), findsOneWidget);
    expect(find.text('Turn on with Google'), findsOneWidget);

    await tester.tap(find.text('Turn on with Google'));
    await tester.pumpAndSettle();
    expect(sync.calls, contains('turnOn'));
    // On: the status row and the Google account replace the pitch.
    expect(find.text('Up to date'), findsOneWidget);
    expect(find.text('Google account'), findsOneWidget);
    expect(find.text('Turn on with Google'), findsNothing);
  });

  testWidgets('should_sayWaiting_when_googlesPageIsOpen', (tester) async {
    sync.pendingTurnOn = Future.delayed(const Duration(seconds: 1), () => const TurnOnCanceled());
    await pump(tester, CloudSyncScreen(now: now));
    await tester.tap(find.text('Turn on with Google'));
    await tester.pump();
    expect(find.text('Waiting for Google…'), findsOneWidget);
    await tester.pump(const Duration(seconds: 1));
    await tester.pumpAndSettle();
    expect(find.text('Turn on with Google'), findsOneWidget);
    expect(messages, isEmpty);
  });

  testWidgets('should_explainAndShowPackage_when_buildHasNoGoogleClient', (tester) async {
    sync.value = const CloudSyncState(phase: SyncMisconfiguredPhase('com.gpo.yoin'));
    await pump(tester, CloudSyncScreen(now: now));
    expect(find.text("Cloud sync isn't available in this build of Yoin."), findsOneWidget);
    expect(find.text('com.gpo.yoin'), findsOneWidget);
    sync.next = const TurnOnFailed(TurnOnFailed.misconfigured);
    await tester.tap(find.text('Try again'));
    await tester.pumpAndSettle();
    expect(messages, ["Cloud sync isn't available in this build of Yoin."]);
  });

  testWidgets('should_showAccountsDevicesAndActions_when_on', (tester) async {
    sync.value = CloudSyncState(
      phase: const SyncUpToDate(),
      account: const GoogleAccountUi(email: 'me@example.com'),
      lastSyncAt: now - 2 * 60 * 1000,
      lastSummaryCount: 4,
      accounts: const [
        SyncAccountRow(
          localProfileId: 'p1',
          provider: MediaId.providerSubsonic,
          displayName: 'Home',
          status: AccountSyncing(),
        ),
        SyncAccountRow(
          localProfileId: 'p2',
          provider: MediaId.providerSubsonic,
          displayName: 'Work',
          status: AccountChanged(),
        ),
      ],
      cloudOnlyAccounts: const [
        CloudAccountRow(
          syncProfileId: 's-9',
          provider: MediaId.providerSubsonic,
          displayName: 'Cabin',
          hint: 'bob @ cabin.example.com',
          fromDeviceName: 'Pixel 9',
        ),
      ],
      devices: [
        const SyncDeviceRow(deviceId: 'me', name: 'Mate 70 Air', isThisDevice: true),
        SyncDeviceRow(deviceId: 'px', name: 'Pixel 9', isThisDevice: false, lastSyncAt: now - 3 * 60 * 60 * 1000),
      ],
    );
    await pump(tester, CloudSyncScreen(now: now));
    expect(find.text('Up to date'), findsOneWidget);
    expect(find.text('Last synced 2 min ago'), findsOneWidget);
    expect(find.text('4\nSynced from other devices'), findsOneWidget);
    expect(find.text('me@example.com'), findsOneWidget);
    expect(find.text('Syncing'), findsOneWidget);
    expect(find.text('In your Drive, not on this device'), findsOneWidget);
    expect(find.text('Last synced 3 hr ago'), findsOneWidget);
    expect(find.text('This device'), findsOneWidget);

    await tester.tap(find.byTooltip('Sync now'));
    expect(sync.calls, contains('syncNow'));

    await tester.tap(find.text('Keep syncing as before'));
    expect(sync.calls, contains('keep:p2'));

    await tester.tap(find.text('Start fresh'));
    await tester.pumpAndSettle();
    expect(find.text('Start fresh as this account?'), findsOneWidget);
    await tester.tap(find.widgetWithText(TextButton, 'Start fresh'));
    await tester.pumpAndSettle();
    expect(sync.calls, contains('fresh:p2'));
  });

  testWidgets('should_deleteOnlyAfterConfirming_when_deleteCloudDataTapped', (tester) async {
    sync.value = const CloudSyncState(phase: SyncUpToDate());
    await pump(tester, CloudSyncScreen(now: now));
    await tester.tap(find.text('Delete cloud data…'));
    await tester.pumpAndSettle();
    expect(find.text('Delete cloud data?'), findsOneWidget);
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();
    expect(sync.calls, isNot(contains('delete')));

    await tester.tap(find.text('Delete cloud data…'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(TextButton, 'Delete'));
    await tester.pumpAndSettle();
    expect(sync.calls, contains('delete'));
    expect(messages, ["Deleted Yoin's data from your Google Drive"]);
  });

  testWidgets('should_restoreOrDelete_when_heldReviewAnswered', (tester) async {
    sync.value = const CloudSyncState(phase: SyncNeedsReview('Home', 3));
    await pump(tester, CloudSyncScreen(now: now));
    expect(find.text('Needs your review'), findsOneWidget);
    await tester.tap(find.text('3 notes removed on this device'));
    await tester.pumpAndSettle();
    expect(find.text('3 notes removed'), findsOneWidget);
    await tester.tap(find.text('Restore here'));
    await tester.pumpAndSettle();
    expect(sync.calls, contains('review:true'));
  });

  testWidgets('should_closeReview_when_reviewNoLongerNeeded', (tester) async {
    sync.value = const CloudSyncState(phase: SyncNeedsReview('Home', 3));
    await pump(tester, CloudSyncScreen(now: now));
    await tester.tap(find.text('3 notes removed on this device'));
    await tester.pumpAndSettle();
    expect(find.text('Restore here'), findsOneWidget);
    sync.value = const CloudSyncState(phase: SyncUpToDate());
    await tester.pumpAndSettle();
    expect(find.text('Restore here'), findsNothing);
  });

  testWidgets('should_expandWhatSyncs_when_rowTapped', (tester) async {
    sync.value = const CloudSyncState(phase: SyncUpToDate());
    await pump(tester, CloudSyncScreen(now: now));
    expect(find.text('Notes on songs'), findsNothing);
    await tester.tap(find.text('What syncs'));
    await tester.pumpAndSettle();
    expect(find.text('Notes on songs'), findsOneWidget);
    expect(find.text('Listening history'), findsOneWidget);
  });

  testWidgets('should_listCloudSyncUnderSyncAndStorage_when_settingsOpen', (tester) async {
    sync.value = CloudSyncState(phase: const SyncUpToDate(), lastSyncAt: now);
    await pump(tester, const SettingsScreen());
    expect(find.text('Sync and storage'), findsOneWidget);
    expect(find.text('Sync and backups'), findsOneWidget);
    await tester.tap(find.text('Sync and backups'));
    await tester.pumpAndSettle();
    expect(find.text('Cloud sync'), findsOneWidget);
    expect(find.text('On'), findsOneWidget);
    await tester.tap(find.text('Cloud sync'));
    await tester.pumpAndSettle();
    expect(find.byType(CloudSyncScreen), findsOneWidget);
  });

  group('cloud data of one account', () {
    const home = SyncAccountRow(
      localProfileId: 'p1',
      provider: MediaId.providerSubsonic,
      displayName: 'Home',
      status: AccountSyncing(),
      syncProfileId: 's-1',
    );
    const cabin = CloudAccountRow(
      syncProfileId: 's-9',
      provider: MediaId.providerSubsonic,
      displayName: 'Cabin',
      hint: 'bob @ cabin.example.com',
      fromDeviceName: 'Pixel 9',
    );
    const counts = CloudScopeCounts(notes: 128, albums: 43, songRatings: 89);

    void on({List<SyncAccountRow> accounts = const [home], List<CloudAccountRow> cloudOnly = const []}) =>
        sync.value = CloudSyncState(
          phase: const SyncUpToDate(),
          accounts: accounts,
          cloudOnlyAccounts: cloudOnly,
          scopeCounts: const {'s-1': counts},
        );

    Future<void> chooseRemove(WidgetTester tester, String name) async {
      await tester.tap(find.byTooltip('More options for $name'));
      await tester.pumpAndSettle();
      expect(find.text('View cloud data'), findsOneWidget);
      await tester.tap(find.text('Remove from cloud…'));
      await tester.pumpAndSettle();
    }

    testWidgets('should_showWhatTheCloudHolds_when_anAccountSyncs', (tester) async {
      on(cloudOnly: const [cabin]);
      await pump(tester, CloudSyncScreen(now: now));
      expect(find.text('128'), findsOneWidget);
      expect(find.text('album ratings & reviews'), findsOneWidget);
      expect(find.text('No data yet'), findsOneWidget, reason: 'Cabin holds nothing in this replica');
      expect(find.byTooltip('More options for Cabin'), findsOneWidget);
    });

    testWidgets('should_removeOnlyAfterConfirming_when_removeFromCloudChosen', (tester) async {
      on();
      await pump(tester, CloudSyncScreen(now: now));
      await chooseRemove(tester, 'Home');
      expect(find.text('Remove Home from the cloud?'), findsOneWidget);
      expect(
        find.text(
          'This deletes 128 notes, 43 album ratings & reviews and 89 song ratings of this account from Google Drive.',
        ),
        findsOneWidget,
      );
      expect(find.text('Data on this device stays, but this account stops syncing.'), findsOneWidget);
      await tester.tap(find.text('Cancel'));
      await tester.pumpAndSettle();
      expect(sync.calls, isNot(contains('removeFromCloud:s-1')));

      await chooseRemove(tester, 'Home');
      await tester.tap(find.widgetWithText(TextButton, 'Remove'));
      await tester.pumpAndSettle();
      expect(sync.calls, contains('removeFromCloud:s-1'));
      expect(messages, ['Removed Home from the cloud']);
    });

    testWidgets('should_sayItCouldntRemove_when_theRemovalFails', (tester) async {
      on();
      sync.removeError = StateError('offline');
      await pump(tester, CloudSyncScreen(now: now));
      await chooseRemove(tester, 'Home');
      await tester.tap(find.widgetWithText(TextButton, 'Remove'));
      await tester.pumpAndSettle();
      expect(messages, ["Couldn't remove Home from the cloud. Check your connection and try again."]);
    });

    testWidgets('should_nameTheDeviceItCameFrom_when_removingAnAccountOnlyInTheCloud', (tester) async {
      on(accounts: const [], cloudOnly: const [cabin]);
      await pump(tester, CloudSyncScreen(now: now));
      await chooseRemove(tester, 'Cabin');
      expect(find.text('This deletes all its data of this account from Google Drive.'), findsOneWidget);
      expect(
        find.text('Other devices still signed in to this account (like Pixel 9) also stop uploading its data.'),
        findsOneWidget,
      );
      // The account leaves the cloud elsewhere meanwhile: the dialog no longer applies.
      on(accounts: const []);
      await tester.pumpAndSettle();
      expect(find.text('Remove Cabin from the cloud?'), findsNothing);
    });

    testWidgets('should_browseAndRemove_when_theAccountPageIsOpen', (tester) async {
      on();
      sync.contents['s-1'] = const CloudAccountContent(
        notes: [
          CloudNote(
            id: 'n1',
            title: 'Paper Lanterns',
            artist: 'Kiri Ono',
            content: 'the bridge',
            positionMs: 61000,
            updatedAt: 0,
          ),
          CloudNote(id: 'n2', title: '', artist: '', content: 'hum', positionMs: null, updatedAt: 0),
        ],
        songRatings: [CloudSongRating(trackId: 't', title: 'Moon', artist: null, rating: 8.5, updatedAt: 0)],
      );
      await pump(tester, CloudSyncScreen(now: now));
      await tester.tap(find.text('Home'));
      await tester.pumpAndSettle();
      expect(find.byType(CloudAccountPage), findsOneWidget);
      expect(find.text('Cloud copy on Google Drive (read-only)'), findsOneWidget);
      expect(find.text('Notes  2'), findsOneWidget);
      expect(find.text('Song ratings  1'), findsOneWidget);
      expect(find.text('the bridge'), findsOneWidget);
      expect(find.text('1:01'), findsOneWidget);
      expect(find.text('Unknown song'), findsOneWidget);

      await tester.enterText(find.byType(TextField), 'bridge');
      await tester.pumpAndSettle();
      expect(find.text('Notes  1'), findsOneWidget);
      expect(find.text('hum'), findsNothing);

      await tester.tap(find.text('Song ratings  0'));
      await tester.pumpAndSettle();
      expect(find.text('No matches'), findsOneWidget);
      await tester.enterText(find.byType(TextField), '');
      await tester.pumpAndSettle();
      expect(find.text('Moon'), findsOneWidget);
      expect(find.text('8.5'), findsOneWidget);

      await tester.tap(find.byKey(const ValueKey('cloud_account_remove')));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(TextButton, 'Remove'));
      await tester.pumpAndSettle();
      expect(sync.calls, contains('removeFromCloud:s-1'));
      expect(find.byType(CloudAccountPage), findsNothing, reason: 'a removal closes the page, as on Android');
    });

    test('should_joinCountsLikeAndroid_when_writingTheSentence', () {
      const en = CloudSyncStrings.forLanguage(chinese: false);
      const zh = CloudSyncStrings.forLanguage(chinese: true);
      expect(countsSentence(en, null), 'all its data');
      expect(countsSentence(en, const CloudScopeCounts(notes: 1)), '1 note');
      expect(countsSentence(en, const CloudScopeCounts(notes: 2, memoryTitles: 1)), '2 notes and 1 Memories title');
      expect(countsSentence(zh, counts), '128 条笔记、43 条专辑评分与乐评和89 条单曲评分');
      expect(countsSentence(zh, const CloudScopeCounts()), '全部数据');
    });
  });
}
