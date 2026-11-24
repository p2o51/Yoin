/// What the Cloud sync UI can see (Android data/sync/CloudSyncController.kt).
/// HarmonyOS has no Google Play services, so Android's "Unavailable" phase has
/// no counterpart: a build without a Google OAuth client is [SyncMisconfigured].
library;

import 'package:flutter/foundation.dart';

import 'cloud_data_views.dart';

@immutable
final class CloudSyncState {
  const CloudSyncState({
    this.phase = const SyncOff(),
    this.account,
    this.lastSyncAt,
    this.pendingChanges = false,
    this.accounts = const [],
    this.cloudOnlyAccounts = const [],
    this.devices = const [],
    this.lastSummaryCount,
    this.scopeCounts = const {},
  });

  final CloudSyncPhase phase;
  final GoogleAccountUi? account;
  final int? lastSyncAt;

  /// Local changes not yet uploaded.
  final bool pendingChanges;
  final List<SyncAccountRow> accounts;
  final List<CloudAccountRow> cloudOnlyAccounts;
  final List<SyncDeviceRow> devices;

  /// Items the last notable sync brought from other devices (the status row's
  /// "Synced from other devices" line); null when there is nothing to say.
  final int? lastSummaryCount;

  /// What each account (syncProfileId) holds in the cloud, from this device's
  /// replica (Android's CloudDataSummary.scopes); an account holding nothing
  /// is absent. Empty while sync is off.
  final Map<String, CloudScopeCounts> scopeCounts;

  bool get enabled => phase.enabled;

  CloudSyncState copyWith({CloudSyncPhase? phase}) => CloudSyncState(
    phase: phase ?? this.phase,
    account: account,
    lastSyncAt: lastSyncAt,
    pendingChanges: pendingChanges,
    accounts: accounts,
    cloudOnlyAccounts: cloudOnlyAccounts,
    devices: devices,
    lastSummaryCount: lastSummaryCount,
    scopeCounts: scopeCounts,
  );

  @override
  bool operator ==(Object other) =>
      other is CloudSyncState &&
      other.phase == phase &&
      other.account == account &&
      other.lastSyncAt == lastSyncAt &&
      other.pendingChanges == pendingChanges &&
      listEquals(other.accounts, accounts) &&
      listEquals(other.cloudOnlyAccounts, cloudOnlyAccounts) &&
      listEquals(other.devices, devices) &&
      other.lastSummaryCount == lastSummaryCount &&
      mapEquals(other.scopeCounts, scopeCounts);

  @override
  int get hashCode => Object.hash(
    phase,
    account,
    lastSyncAt,
    pendingChanges,
    Object.hashAll(accounts),
    Object.hashAll(cloudOnlyAccounts),
    Object.hashAll(devices),
    lastSummaryCount,
    Object.hashAllUnordered(scopeCounts.entries.map((e) => Object.hash(e.key, e.value))),
  );
}

@immutable
final class GoogleAccountUi {
  const GoogleAccountUi({this.email, this.displayName});
  final String? email;
  final String? displayName;

  @override
  bool operator ==(Object other) =>
      other is GoogleAccountUi && other.email == email && other.displayName == displayName;

  @override
  int get hashCode => Object.hash(email, displayName);
}

sealed class CloudSyncPhase {
  const CloudSyncPhase();
  bool get enabled;
}

// ---- Off states

final class SyncOff extends CloudSyncPhase {
  const SyncOff();
  @override
  bool get enabled => false;
  @override
  bool operator ==(Object other) => other is SyncOff;
  @override
  int get hashCode => 1;
}

/// No Google OAuth client in this build, or Google refused it; [packageName]
/// for the details row.
final class SyncMisconfiguredPhase extends CloudSyncPhase {
  const SyncMisconfiguredPhase(this.packageName);
  final String packageName;
  @override
  bool get enabled => false;
  @override
  bool operator ==(Object other) => other is SyncMisconfiguredPhase && other.packageName == packageName;
  @override
  int get hashCode => Object.hash(2, packageName);
}

/// Another device deleted the Drive data; this device turned itself off.
final class SyncResetElsewhere extends CloudSyncPhase {
  const SyncResetElsewhere(this.deviceName);
  final String? deviceName;
  @override
  bool get enabled => false;
  @override
  bool operator ==(Object other) => other is SyncResetElsewhere && other.deviceName == deviceName;
  @override
  int get hashCode => Object.hash(3, deviceName);
}

// ---- On states

final class SyncSyncing extends CloudSyncPhase {
  const SyncSyncing();
  @override
  bool get enabled => true;
  @override
  bool operator ==(Object other) => other is SyncSyncing;
  @override
  int get hashCode => 4;
}

final class SyncUpToDate extends CloudSyncPhase {
  const SyncUpToDate();
  @override
  bool get enabled => true;
  @override
  bool operator ==(Object other) => other is SyncUpToDate;
  @override
  int get hashCode => 5;
}

/// Couldn't reach Google; changes wait on this device.
final class SyncOffline extends CloudSyncPhase {
  const SyncOffline();
  @override
  bool get enabled => true;
  @override
  bool operator ==(Object other) => other is SyncOffline;
  @override
  int get hashCode => 6;
}

final class SyncNeedsReauth extends CloudSyncPhase {
  const SyncNeedsReauth();
  @override
  bool get enabled => true;
  @override
  bool operator ==(Object other) => other is SyncNeedsReauth;
  @override
  int get hashCode => 7;
}

final class SyncStorageFullPhase extends CloudSyncPhase {
  const SyncStorageFullPhase();
  @override
  bool get enabled => true;
  @override
  bool operator ==(Object other) => other is SyncStorageFullPhase;
  @override
  int get hashCode => 8;
}

/// Mass delete held for confirmation.
final class SyncNeedsReview extends CloudSyncPhase {
  const SyncNeedsReview(this.accountName, this.count);
  final String accountName;
  final int count;
  @override
  bool get enabled => true;
  @override
  bool operator ==(Object other) =>
      other is SyncNeedsReview && other.accountName == accountName && other.count == count;
  @override
  int get hashCode => Object.hash(9, accountName, count);
}

/// Our own Drive copy disappeared without a reset marker.
final class SyncCloudCopyRemoved extends CloudSyncPhase {
  const SyncCloudCopyRemoved();
  @override
  bool get enabled => true;
  @override
  bool operator ==(Object other) => other is SyncCloudCopyRemoved;
  @override
  int get hashCode => 10;
}

/// A device with a newer Yoin wrote data this version can't read.
final class SyncUpdateRequired extends CloudSyncPhase {
  const SyncUpdateRequired(this.deviceName);
  final String? deviceName;
  @override
  bool get enabled => true;
  @override
  bool operator ==(Object other) => other is SyncUpdateRequired && other.deviceName == deviceName;
  @override
  int get hashCode => Object.hash(11, deviceName);
}

final class SyncErrorPhase extends CloudSyncPhase {
  const SyncErrorPhase(this.message);
  final String message;
  @override
  bool get enabled => true;
  @override
  bool operator ==(Object other) => other is SyncErrorPhase && other.message == message;
  @override
  int get hashCode => Object.hash(12, message);
}

/// A local music account and how it syncs.
@immutable
final class SyncAccountRow {
  const SyncAccountRow({
    required this.localProfileId,
    required this.provider,
    required this.displayName,
    required this.status,
    this.syncProfileId,
  });

  final String localProfileId;
  final String provider;
  final String displayName;
  final SyncAccountStatus status;

  /// The cross-device account it is bound to; null while unbound (not synced, needs reconnect, a duplicate).
  final String? syncProfileId;

  @override
  bool operator ==(Object other) =>
      other is SyncAccountRow &&
      other.localProfileId == localProfileId &&
      other.provider == provider &&
      other.displayName == displayName &&
      other.status == status &&
      other.syncProfileId == syncProfileId;

  @override
  int get hashCode => Object.hash(localProfileId, provider, displayName, status, syncProfileId);
}

sealed class SyncAccountStatus {
  const SyncAccountStatus();
}

final class AccountSyncing extends SyncAccountStatus {
  const AccountSyncing();
  @override
  bool operator ==(Object other) => other is AccountSyncing;
  @override
  int get hashCode => 1;
}

/// Credentials missing on this device.
final class AccountNeedsReconnect extends SyncAccountStatus {
  const AccountNeedsReconnect();
  @override
  bool operator ==(Object other) => other is AccountNeedsReconnect;
  @override
  int get hashCode => 2;
}

final class AccountDuplicateOf extends SyncAccountStatus {
  const AccountDuplicateOf(this.otherDisplayName);
  final String otherDisplayName;
  @override
  bool operator ==(Object other) => other is AccountDuplicateOf && other.otherDisplayName == otherDisplayName;
  @override
  int get hashCode => Object.hash(3, otherDisplayName);
}

/// Apple Music: only syncs after an explicit choice. [linkCandidates] = unbound Apple accounts in Drive.
final class AccountNotSynced extends SyncAccountStatus {
  const AccountNotSynced(this.linkCandidates);
  final List<CloudAccountRow> linkCandidates;
  @override
  bool operator ==(Object other) => other is AccountNotSynced && listEquals(other.linkCandidates, linkCandidates);
  @override
  int get hashCode => Object.hash(4, Object.hashAll(linkCandidates));
}

/// Signed in as a different account than the one this profile synced as.
final class AccountChanged extends SyncAccountStatus {
  const AccountChanged();
  @override
  bool operator ==(Object other) => other is AccountChanged;
  @override
  int get hashCode => 5;
}

/// Removed from the cloud (a scope purge marker, from any device): not synced here until resumed.
final class AccountRemovedFromCloud extends SyncAccountStatus {
  const AccountRemovedFromCloud();
  @override
  bool operator ==(Object other) => other is AccountRemovedFromCloud;
  @override
  int get hashCode => 6;
}

/// An account whose data is in Drive but not connected on this device.
@immutable
final class CloudAccountRow {
  const CloudAccountRow({
    required this.syncProfileId,
    required this.provider,
    required this.displayName,
    this.hint,
    this.fromDeviceName,
  });

  final String syncProfileId;
  final String provider;
  final String displayName;

  /// "alice @ music.example.com" for Subsonic; null otherwise.
  final String? hint;
  final String? fromDeviceName;

  @override
  bool operator ==(Object other) =>
      other is CloudAccountRow &&
      other.syncProfileId == syncProfileId &&
      other.provider == provider &&
      other.displayName == displayName &&
      other.hint == hint &&
      other.fromDeviceName == fromDeviceName;

  @override
  int get hashCode => Object.hash(syncProfileId, provider, displayName, hint, fromDeviceName);
}

@immutable
final class SyncDeviceRow {
  const SyncDeviceRow({required this.deviceId, required this.name, required this.isThisDevice, this.lastSyncAt});

  final String deviceId;
  final String name;
  final bool isThisDevice;
  final int? lastSyncAt;

  @override
  bool operator ==(Object other) =>
      other is SyncDeviceRow &&
      other.deviceId == deviceId &&
      other.name == name &&
      other.isThisDevice == isThisDevice &&
      other.lastSyncAt == lastSyncAt;

  @override
  int get hashCode => Object.hash(deviceId, name, isThisDevice, lastSyncAt);
}

/// What a turn-on (or reconnect) came to.
sealed class TurnOnStep {
  const TurnOnStep();
}

/// Enabled; the first sync is running.
final class TurnOnStarted extends TurnOnStep {
  const TurnOnStarted();
}

/// The user backed out of Google's page: nothing changes, no message.
final class TurnOnCanceled extends TurnOnStep {
  const TurnOnCanceled();
}

final class TurnOnFailed extends TurnOnStep {
  const TurnOnFailed(this.reason);

  /// [misconfigured] or [unreachable]; the page words it.
  final String reason;

  /// No Google OAuth client in this build, or Google doesn't know it.
  static const misconfigured = 'misconfigured';

  /// Google couldn't be reached, or the sign-in went wrong on the way.
  static const unreachable = 'unreachable';
}

/// What the Cloud sync UI can do. Implemented by [CloudSyncManager]; previews and tests use fakes.
abstract interface class CloudSyncController {
  ValueListenable<CloudSyncState> get state;

  /// Opens the sync store and shows where sync stands (no network).
  Future<void> ensureInitialized();

  /// The user came back from the browser without finishing on Google's page.
  void cancelTurnOn();

  /// A turn-on is waiting for Google to send the browser back.
  bool get turnOnWaitingForBrowser;

  /// Turn on (or reconnect): opens Google's page in the browser and waits for the answer.
  Future<TurnOnStep> turnOn();

  void syncNow();

  /// This device only: stop syncing, keep local data, the replica and the Drive copy.
  void turnOff();

  /// All devices: delete Yoin's Drive data, turn off. Local data stays. Throws on failure.
  Future<void> deleteCloudData();

  /// Held mass-delete review: true = restore the items here, false = delete them on all devices.
  void resolveReview({required bool restore});

  /// Our Drive copy vanished: re-upload from this device.
  void uploadAgain();

  void startSyncingAccount(String localProfileId);

  void linkAccount(String localProfileId, String syncProfileId);

  /// Fingerprint changed (new server address): keep syncing into the same Drive data.
  void keepAccountAsBefore(String localProfileId);

  /// Fingerprint changed to a different account: drop the old account's synced items here, sync as the new one.
  void startFreshAccount(String localProfileId);

  /// An account removed from the cloud ([AccountRemovedFromCloud]) syncs again from this device.
  void resumeAccountSync(String localProfileId);

  /// Delete another device's files from Drive (it re-uploads if it is still alive).
  void removeDevice(String deviceId);

  /// All devices: remove one music account's data ([syncProfileId]) from Drive. Writes a scope purge
  /// marker, drops the account from this replica and re-uploads; every device that sees the marker stops
  /// syncing that account (local data stays everywhere). Works for accounts only in Drive and for local
  /// ones. Throws on failure.
  Future<void> removeAccountFromCloud(String syncProfileId);

  /// One account's notes, ratings, reviews and Memories titles as the cloud holds them (this device's
  /// replica; read-only, no network).
  Future<CloudAccountContent> cloudAccountContent(String syncProfileId);
}
