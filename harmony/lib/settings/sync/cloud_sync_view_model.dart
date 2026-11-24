/// The Cloud sync page's window onto the app's [CloudSyncController]
/// (Android ui/settings/sync/CloudSyncViewModel.kt). The sync state is the
/// controller's own, passed through; the page adds whether a turn-on or a
/// delete is in flight. Those calls outlive the page: backing out mid-way
/// never cancels a half-done turn-on or delete, and their message still shows
/// on the app's snackbar.
///
/// Turning on opens Google's page in the system browser. Coming back to Yoin
/// without finishing there (the browser closed, the back gesture) is a
/// cancel: after [browserReturnGrace] with no answer from Google, the wait ends.
library;

import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart' show AppLifecycleListener;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/sync/cloud_sync_providers.dart';
import '../../data/sync/cloud_sync_state.dart';
import '../../playback/playback_controller.dart';
import 'cloud_sync_strings.dart';

@immutable
final class CloudSyncPageState {
  const CloudSyncPageState({
    required this.sync,
    this.turningOn = false,
    this.deleting = false,
    this.removing = const {},
  });

  final CloudSyncState sync;

  /// Turn on / Sign in is in flight (waiting for Google or its page).
  final bool turningOn;
  final bool deleting;

  /// Accounts (syncProfileId) being removed from the cloud.
  final Set<String> removing;

  CloudSyncPageState copyWith({CloudSyncState? sync, bool? turningOn, bool? deleting, Set<String>? removing}) =>
      CloudSyncPageState(
        sync: sync ?? this.sync,
        turningOn: turningOn ?? this.turningOn,
        deleting: deleting ?? this.deleting,
        removing: removing ?? this.removing,
      );

  @override
  bool operator ==(Object other) =>
      other is CloudSyncPageState &&
      other.sync == sync &&
      other.turningOn == turningOn &&
      other.deleting == deleting &&
      setEquals(other.removing, removing);

  @override
  int get hashCode => Object.hash(sync, turningOn, deleting, Object.hashAllUnordered(removing));
}

/// How long after coming back from the browser a sign-in may still land
/// before the wait counts as a cancel (the redirect usually arrives first).
const Duration browserReturnGrace = Duration(seconds: 3);

/// Shows a one-line message on the app's snackbar.
typedef CloudSyncMessenger = void Function(String message);

final cloudSyncControllerProvider = Provider<CloudSyncController>((ref) => ref.watch(cloudSyncProvider));

final cloudSyncMessengerProvider = Provider<CloudSyncMessenger>(
  (ref) =>
      (message) => ref.read(playbackControllerProvider.notifier).showMessage(message),
);

class CloudSyncViewModel extends Notifier<CloudSyncPageState> {
  late CloudSyncController _controller;
  AppLifecycleListener? _lifecycle;
  Timer? _returnTimer;

  @override
  CloudSyncPageState build() {
    _controller = ref.watch(cloudSyncControllerProvider);
    final listenable = _controller.state;
    void onChange() {
      if (ref.mounted) state = state.copyWith(sync: listenable.value);
    }

    listenable.addListener(onChange);
    try {
      _lifecycle = AppLifecycleListener(onResume: _onResume);
    } catch (_) {
      _lifecycle = null; // No binding (plain unit tests).
    }
    ref.onDispose(() {
      listenable.removeListener(onChange);
      _lifecycle?.dispose();
      _returnTimer?.cancel();
    });
    unawaited(_controller.ensureInitialized().catchError((Object _) {}));
    return CloudSyncPageState(sync: listenable.value);
  }

  CloudSyncMessenger get _message => ref.read(cloudSyncMessengerProvider);

  /// "Turn on with Google", "Sign in" (reconnect) and "Try again".
  Future<void> turnOn(CloudSyncStrings strings) async {
    if (state.turningOn) return;
    state = state.copyWith(turningOn: true);
    TurnOnStep step;
    try {
      step = await _controller.turnOn();
    } catch (_) {
      // A throw is a controller bug, not a user-facing reason: never show its raw text.
      step = const TurnOnFailed(TurnOnFailed.unreachable);
    }
    _returnTimer?.cancel();
    if (!ref.mounted) return;
    state = state.copyWith(turningOn: false);
    if (step case TurnOnFailed(:final reason)) {
      _message(reason == TurnOnFailed.misconfigured ? strings.unavailable : strings.turnOnFailed);
    }
  }

  void _onResume() {
    if (!state.turningOn || !_controller.turnOnWaitingForBrowser) return;
    _returnTimer?.cancel();
    _returnTimer = Timer(browserReturnGrace, () {
      if (_controller.turnOnWaitingForBrowser) _controller.cancelTurnOn();
    });
  }

  void syncNow() => _controller.syncNow();

  void turnOff() => _controller.turnOff();

  Future<void> deleteCloudData(CloudSyncStrings strings) async {
    if (state.deleting) return;
    state = state.copyWith(deleting: true);
    var ok = true;
    try {
      await _controller.deleteCloudData();
    } catch (_) {
      ok = false;
    }
    if (ref.mounted) state = state.copyWith(deleting: false);
    _message(ok ? strings.deleted : strings.deleteFailed);
  }

  void resolveReview({required bool restore}) => _controller.resolveReview(restore: restore);

  void uploadAgain() => _controller.uploadAgain();

  void startSyncingAccount(String localProfileId) => _controller.startSyncingAccount(localProfileId);

  void linkAccount(String localProfileId, String syncProfileId) =>
      _controller.linkAccount(localProfileId, syncProfileId);

  void keepAccountAsBefore(String localProfileId) => _controller.keepAccountAsBefore(localProfileId);

  void startFreshAccount(String localProfileId) => _controller.startFreshAccount(localProfileId);

  void resumeAccountSync(String localProfileId) => _controller.resumeAccountSync(localProfileId);

  void removeDevice(String deviceId) => _controller.removeDevice(deviceId);

  /// "Remove from cloud", after the confirmation: every device stops syncing [syncProfileId]; local data
  /// stays. The call outlives the page; its message shows on the app's snackbar either way. True when
  /// the account is out of the cloud.
  Future<bool> removeFromCloud(String syncProfileId, String displayName, CloudSyncStrings strings) async {
    if (state.removing.contains(syncProfileId)) return false;
    state = state.copyWith(removing: {...state.removing, syncProfileId});
    var ok = true;
    try {
      await _controller.removeAccountFromCloud(syncProfileId);
    } catch (_) {
      ok = false;
    }
    if (ref.mounted) state = state.copyWith(removing: {...state.removing}..remove(syncProfileId));
    _message(ok ? strings.removed(displayName) : strings.removeFailed(displayName));
    return ok;
  }

  /// A music account was just added from "In your Drive": sync now so its data comes down at once.
  void onAccountAdded() {
    if (state.sync.enabled) _controller.syncNow();
  }
}

final cloudSyncViewModelProvider = NotifierProvider<CloudSyncViewModel, CloudSyncPageState>(CloudSyncViewModel.new);
