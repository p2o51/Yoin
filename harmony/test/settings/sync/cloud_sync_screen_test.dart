import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/media_id.dart';
import 'package:yoin/data/sync/cloud_sync_state.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/settings/settings_screen.dart';
import 'package:yoin/settings/sync/cloud_sync_screen.dart';
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
  void removeDevice(String deviceId) => calls.add('remove:$deviceId');
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
    expect(find.text('Cloud sync'), findsOneWidget);
    expect(find.text('On'), findsOneWidget);
    await tester.tap(find.text('Cloud sync'));
    await tester.pumpAndSettle();
    expect(find.byType(CloudSyncScreen), findsOneWidget);
  });
}
