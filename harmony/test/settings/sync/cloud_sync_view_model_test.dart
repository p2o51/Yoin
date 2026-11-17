import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/sync/cloud_sync_state.dart';
import 'package:yoin/settings/sync/cloud_sync_strings.dart';
import 'package:yoin/settings/sync/cloud_sync_view_model.dart';

import 'cloud_sync_screen_test.dart' show FakeCloudSync;

void main() {
  const en = CloudSyncStrings.forLanguage(chinese: false);

  testWidgets('should_cancelTheWait_when_userComesBackWithoutFinishing', (tester) async {
    final sync = FakeCloudSync()..waiting = true;
    sync.pendingTurnOn = Future.delayed(const Duration(minutes: 5), () => const TurnOnCanceled());
    final c = ProviderContainer(
      overrides: [
        cloudSyncControllerProvider.overrideWithValue(sync),
        cloudSyncMessengerProvider.overrideWithValue((_) {}),
      ],
    );
    addTearDown(c.dispose);
    c.listen(cloudSyncViewModelProvider, (_, _) {});
    final done = c.read(cloudSyncViewModelProvider.notifier).turnOn(en);
    await tester.pump();
    expect(c.read(cloudSyncViewModelProvider).turningOn, isTrue);
    // The app comes back to the front with Google's page still open.
    for (final s in [AppLifecycleState.hidden, AppLifecycleState.inactive, AppLifecycleState.resumed]) {
      tester.binding.handleAppLifecycleStateChanged(s);
    }
    await tester.pump(const Duration(seconds: 1));
    expect(sync.calls, isNot(contains('cancel')));
    await tester.pump(browserReturnGrace);
    expect(sync.calls, contains('cancel'));
    await tester.pump(const Duration(minutes: 5));
    await done;
  });

  test('should_sayCouldntReachGoogle_when_turnOnFails', () async {
    final sync = FakeCloudSync()..next = const TurnOnFailed(TurnOnFailed.unreachable);
    final messages = <String>[];
    final c = ProviderContainer(
      overrides: [
        cloudSyncControllerProvider.overrideWithValue(sync),
        cloudSyncMessengerProvider.overrideWithValue(messages.add),
      ],
    );
    addTearDown(c.dispose);
    c.listen(cloudSyncViewModelProvider, (_, _) {});
    await c.read(cloudSyncViewModelProvider.notifier).turnOn(en);
    expect(messages, ["Couldn't reach Google. Try again in a moment."]);
    expect(c.read(cloudSyncViewModelProvider).turningOn, isFalse);
  });

  test('should_followController_when_stateChanges', () {
    final sync = FakeCloudSync();
    final c = ProviderContainer(
      overrides: [
        cloudSyncControllerProvider.overrideWithValue(sync),
        cloudSyncMessengerProvider.overrideWithValue((_) {}),
      ],
    );
    addTearDown(c.dispose);
    c.listen(cloudSyncViewModelProvider, (_, _) {});
    sync.value = const CloudSyncState(phase: SyncOffline());
    expect(c.read(cloudSyncViewModelProvider).sync.phase, isA<SyncOffline>());
    expect(sync.calls, contains('init'));
  });
}
