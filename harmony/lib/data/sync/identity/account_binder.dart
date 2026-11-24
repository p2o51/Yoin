/// Keeps the local profile <-> sync scope bindings (Android
/// identity/AccountBinder.kt, spec §4 step 4, §5).
///
/// Bindings are frozen once made: a scope id is minted from the account
/// fingerprint at first bind (Subsonic) or at random on an explicit user
/// action (Apple Music, which never auto-binds) and only changes through the
/// explicit actions below. A changed fingerprint pauses the binding, a
/// vanished profile unbinds it; neither ever creates tombstones. HarmonyOS has
/// no Spotify accounts, so Android's Spotify identity step has no counterpart.
library;

import 'dart:math';

import 'package:flutter/foundation.dart';

import '../../model/media_id.dart';
import '../../profile/profile.dart';
import '../cloud_sync_state.dart';
import '../sync_contracts.dart';
import '../sync_database.dart';
import 'account_fingerprints.dart';
import 'cloud_account_descriptor.dart';

/// Result of one [AccountBinder.refresh].
@immutable
final class BindingSnapshot {
  const BindingSnapshot({required this.active, required this.rows, required this.cloudOnly, required this.newlyBound});

  /// Bindings to capture/apply this cycle: ACTIVE and verified.
  final List<ActiveBinding> active;

  /// One row per local profile, oldest first, for the Accounts group.
  final List<SyncAccountRow> rows;

  /// Accounts described in Drive that no local profile is bound to.
  final List<CloudAccountRow> cloudOnly;

  /// Bindings created by this refresh.
  final List<ActiveBinding> newlyBound;
}

/// A "Start fresh as this account" in progress (see [AccountBinder.prepareStartFresh]).
@immutable
final class StartFreshPlan {
  const StartFreshPlan({
    required this.localProfileId,
    required this.provider,
    required this.oldScope,
    required this.newSyncProfileId,
    required this.newFingerprintHash,
    required this.pendingChanges,
  });

  final String localProfileId;
  final String provider;
  final String oldScope;

  /// Scope to bind to afterwards; null = stay unbound.
  final String? newSyncProfileId;
  final String? newFingerprintHash;

  /// Unpublished local changes in [oldScope].
  final int pendingChanges;
}

/// Reads a profile's credentials; throws or answers null when they can't be read.
typedef CredentialsReader = Future<ProfileCredentials?> Function(Profile profile);

class AccountBinder {
  AccountBinder({
    required SyncDatabase db,
    required Future<List<Profile>> Function() profiles,
    required CredentialsReader credentials,
    int Function()? clock,
    Random? random,
  }) : _db = db,
       _profiles = profiles,
       _credentials = credentials,
       _clock = clock ?? (() => DateTime.now().millisecondsSinceEpoch),
       _random = random ?? Random.secure();

  final SyncDatabase _db;
  final Future<List<Profile>> Function() _profiles;
  final CredentialsReader _credentials;
  final int Function() _clock;
  final Random _random;

  static const appleScopePrefix = 'a-';

  Future<List<Profile>> _sortedProfiles() async {
    final list = [...await _profiles()]
      ..sort((a, b) {
        final byCreated = a.createdAt.compareTo(b.createdAt);
        return byCreated != 0 ? byCreated : a.id.compareTo(b.id);
      });
    return list;
  }

  Future<BindingSnapshot> refresh(List<CloudAccountDescriptor> cloudAccounts) async {
    final localProfiles = await _sortedProfiles();
    final identities = {for (final p in localProfiles) p.id: await _identityOf(p)};
    final now = _clock();

    return _db.transaction(() async {
      final profileIds = {for (final p in localProfiles) p.id};
      for (final binding in _db.bindings()) {
        if (!profileIds.contains(binding.localProfileId)) _unbind(binding);
      }
      final byProfile = {for (final b in _db.bindings()) b.localProfileId: b};
      final byScope = {for (final b in byProfile.values) b.syncProfileId: b};
      final purgedScopes = {
        for (final e in _db.metaWithPrefix(SyncMetaKeys.purgedScopePrefix))
          e.key.substring(SyncMetaKeys.purgedScopePrefix.length),
      };
      final active = <ActiveBinding>[];
      final newlyBound = <ActiveBinding>[];
      // null = Apple Music "not synced", filled in once the cloud-only list is final.
      final statuses = <Profile, SyncAccountStatus?>{};

      for (final profile in localProfiles) {
        final identity = identities[profile.id]!;
        final binding = byProfile[profile.id];
        if (binding?.state == SyncBindingEntity.statePurged) {
          // Removed from the cloud: stays out of sync until the user resumes it here.
          statuses[profile] = const AccountRemovedFromCloud();
          continue;
        }
        if (binding != null) {
          final (status, updated) = _verify(binding, identity);
          if (updated != binding) {
            if (updated.state != binding.state) _forgetDescriptorState(updated.syncProfileId);
            _db.upsertBinding(updated);
            byProfile[profile.id] = updated;
            byScope[updated.syncProfileId] = updated;
          }
          if (status is AccountSyncing) active.add(updated.toActive());
          statuses[profile] = status;
          continue;
        }
        switch (identity) {
          case _NoCredentials() || _Unusable():
            statuses[profile] = const AccountNeedsReconnect();
          case _Explicit():
            statuses[profile] = null;
          case _Known(:final fingerprint):
            final scope = AccountFingerprints.deterministicId(fingerprint);
            final holder = byScope[scope];
            if (holder != null) {
              final holderName = localProfiles.where((p) => p.id == holder.localProfileId).firstOrNull?.displayName;
              statuses[profile] = AccountDuplicateOf(holderName ?? '');
            } else {
              // An account removed from the cloud is bound but not synced: a device that joins later
              // must not upload it again.
              final purged = purgedScopes.contains(scope);
              final created = _bind(
                profile,
                scope,
                AccountFingerprints.fingerprintHash(fingerprint),
                now,
                state: purged ? SyncBindingEntity.statePurged : SyncBindingEntity.stateActive,
              );
              byProfile[profile.id] = created;
              byScope[scope] = created;
              if (purged) {
                statuses[profile] = const AccountRemovedFromCloud();
              } else {
                active.add(created.toActive());
                newlyBound.add(created.toActive());
                statuses[profile] = const AccountSyncing();
              }
            }
        }
      }

      final seen = <String>{};
      final cloudOnly = [
        for (final d in cloudAccounts)
          if (seen.add(d.syncProfileId) && !byScope.containsKey(d.syncProfileId))
            CloudAccountRow(
              syncProfileId: d.syncProfileId,
              provider: d.provider,
              displayName: d.displayName,
              hint: d.hint,
              fromDeviceName: d.deviceName,
            ),
      ];
      final appleCandidates = [
        for (final c in cloudOnly)
          if (c.provider == MediaId.providerAppleMusic) c,
      ];
      final rows = [
        for (final e in statuses.entries)
          SyncAccountRow(
            localProfileId: e.key.id,
            provider: e.key.provider,
            displayName: e.key.displayName,
            status: e.value ?? AccountNotSynced(appleCandidates),
          ),
      ];
      return BindingSnapshot(active: active, rows: rows, cloudOnly: cloudOnly, newlyBound: newlyBound);
    });
  }

  /// Apple Music "Start syncing": binds to a new random scope. Null if not an unbound Apple Music profile.
  Future<ActiveBinding?> startSyncing(String localProfileId) async {
    final profile = (await _profiles()).where((p) => p.id == localProfileId).firstOrNull;
    if (profile == null || profile.provider != MediaId.providerAppleMusic) return null;
    return _db.transaction(() async {
      if (_db.bindingFor(localProfileId) != null) return null;
      return _bind(profile, '$appleScopePrefix${_uuid()}', null, _clock()).toActive();
    });
  }

  /// "Link" an unbound local profile to an account already in Drive. Refused
  /// unless the replica holds that account's descriptor for the same provider
  /// and no other local profile is bound to it.
  Future<bool> link(String localProfileId, String syncProfileId) async {
    final profile = (await _profiles()).where((p) => p.id == localProfileId).firstOrNull;
    if (profile == null) return false;
    final identity = await _identityOf(profile);
    final fingerprintHash = identity is _Known ? AccountFingerprints.fingerprintHash(identity.fingerprint) : null;
    return _db.transaction(() async {
      if (_db.bindingFor(localProfileId) != null) return false;
      if (_db.bindingForScope(syncProfileId) != null) return false;
      final record = _db.getRecord(SyncKinds.account, SyncFormat.globalScope, syncProfileId);
      final descriptor = record == null ? null : CloudAccountDescriptor.fromRecord(record);
      if (descriptor?.provider != profile.provider) return false;
      _bind(profile, syncProfileId, fingerprintHash, _clock());
      return true;
    });
  }

  /// The account bound to [scope] (if any) was removed from the cloud: its
  /// binding stops syncing until [resumeSyncing]. Returns its local profile id.
  Future<String?> markPurged(String scope) => _db.transaction(() async {
    final binding = _db.bindingForScope(scope);
    if (binding == null) return null;
    if (binding.state != SyncBindingEntity.statePurged) {
      _db.upsertBinding(binding.copyWith(state: SyncBindingEntity.statePurged, observedFingerprintHash: null));
      _forgetDescriptorState(scope);
    }
    return binding.localProfileId;
  });

  /// "Resume syncing" after the account was removed from the cloud: JOINs its scope afresh.
  Future<bool> resumeSyncing(String localProfileId) => _db.transaction(() async {
    final binding = _db.bindingFor(localProfileId);
    if (binding == null || binding.state != SyncBindingEntity.statePurged) return false;
    _db.deleteLocalStatesInScope(binding.syncProfileId);
    _forgetDescriptorState(binding.syncProfileId);
    _db.upsertBinding(binding.copyWith(state: SyncBindingEntity.stateActive, observedFingerprintHash: null));
    return true;
  });

  /// "Keep syncing as before": the changed fingerprint is the same account (new address).
  Future<bool> keepAsBefore(String localProfileId) => _db.transaction(() async {
    final binding = _db.bindingFor(localProfileId);
    final observed = binding?.observedFingerprintHash;
    if (binding == null || binding.state != SyncBindingEntity.statePausedMismatch || observed == null) return false;
    _db.upsertBinding(
      binding.copyWith(fingerprintHash: observed, state: SyncBindingEntity.stateActive, observedFingerprintHash: null),
    );
    return true;
  });

  /// First half of "Start fresh as this account"; null unless the binding is paused on a mismatch.
  Future<StartFreshPlan?> prepareStartFresh(String localProfileId) async {
    final binding = _db.bindingFor(localProfileId);
    if (binding == null || binding.state != SyncBindingEntity.statePausedMismatch) return null;
    final profile = (await _profiles()).where((p) => p.id == localProfileId).firstOrNull;
    if (profile == null) return null;
    final identity = await _identityOf(profile);
    final fingerprint = identity is _Known ? identity.fingerprint : null;
    String? newScope;
    if (fingerprint != null) {
      final scope = AccountFingerprints.deterministicId(fingerprint);
      final holder = _db.bindingForScope(scope);
      if (holder == null || holder.localProfileId == localProfileId) newScope = scope;
    }
    return StartFreshPlan(
      localProfileId: localProfileId,
      provider: profile.provider,
      oldScope: binding.syncProfileId,
      newSyncProfileId: newScope,
      newFingerprintHash: newScope == null || fingerprint == null
          ? null
          : AccountFingerprints.fingerprintHash(fingerprint),
      pendingChanges: _db.localStatesInScope(binding.syncProfileId).where((s) => s.pending).length,
    );
  }

  /// Second half: drops the old scope's local state, unbinds, and binds to the
  /// plan's new scope if it is still free. False when the plan is stale or
  /// local changes are still waiting to upload.
  Future<bool> completeStartFresh(StartFreshPlan plan) => _db.transaction(() async {
    final binding = _db.bindingFor(plan.localProfileId);
    if (binding == null || binding.syncProfileId != plan.oldScope) return false;
    if (_db.localStatesInScope(plan.oldScope).any((s) => s.pending)) return false;
    _db.deleteLocalStatesInScope(plan.oldScope);
    _db.deleteLocalStatesForProfile(plan.localProfileId);
    _forgetDescriptorState(plan.oldScope);
    _db.deleteBinding(plan.localProfileId);
    final newScope = plan.newSyncProfileId;
    if (newScope != null && plan.newFingerprintHash != null && _db.bindingForScope(newScope) == null) {
      _db.deleteLocalStatesInScope(newScope);
      _forgetDescriptorState(newScope);
      _db.upsertBinding(
        SyncBindingEntity(
          localProfileId: plan.localProfileId,
          syncProfileId: newScope,
          provider: plan.provider,
          fingerprintHash: plan.newFingerprintHash,
          state: SyncBindingEntity.stateActive,
          boundAt: _clock(),
        ),
      );
    }
    return true;
  });

  (SyncAccountStatus, SyncBindingEntity) _verify(SyncBindingEntity binding, _Identity identity) => switch (identity) {
    _NoCredentials() || _Unusable() => (const AccountNeedsReconnect(), binding),
    _Explicit() => (
      binding.state == SyncBindingEntity.stateActive ? const AccountSyncing() : const AccountChanged(),
      binding,
    ),
    _Known(:final fingerprint) => _verifyKnown(binding, AccountFingerprints.fingerprintHash(fingerprint)),
  };

  (SyncAccountStatus, SyncBindingEntity) _verifyKnown(SyncBindingEntity binding, String observed) {
    // Linked before its fingerprint was known: adopt the first one seen.
    if (binding.fingerprintHash == null) {
      return (
        const AccountSyncing(),
        binding.copyWith(
          fingerprintHash: observed,
          state: SyncBindingEntity.stateActive,
          observedFingerprintHash: null,
        ),
      );
    }
    // Same account (again): resume a paused binding.
    if (observed == binding.fingerprintHash) {
      return (
        const AccountSyncing(),
        binding.copyWith(state: SyncBindingEntity.stateActive, observedFingerprintHash: null),
      );
    }
    return (
      const AccountChanged(),
      binding.copyWith(state: SyncBindingEntity.statePausedMismatch, observedFingerprintHash: observed),
    );
  }

  SyncBindingEntity _bind(
    Profile profile,
    String scope,
    String? fingerprintHash,
    int now, {
    String state = SyncBindingEntity.stateActive,
  }) {
    // A binding always JOINs: no local state may exist for its scope (nor for its descriptor).
    _db.deleteLocalStatesInScope(scope);
    _forgetDescriptorState(scope);
    final binding = SyncBindingEntity(
      localProfileId: profile.id,
      syncProfileId: scope,
      provider: profile.provider,
      fingerprintHash: fingerprintHash,
      state: state,
      boundAt: now,
    );
    _db.upsertBinding(binding);
    return binding;
  }

  /// The profile is gone: forget the binding and its local state; replica records stay.
  void _unbind(SyncBindingEntity binding) {
    _db.deleteLocalStatesForProfile(binding.localProfileId);
    _db.deleteLocalStatesInScope(binding.syncProfileId);
    _forgetDescriptorState(binding.syncProfileId);
    _db.deleteBinding(binding.localProfileId);
  }

  /// Drops the global account-descriptor local state of [scope] whenever this
  /// device starts or stops describing it, so two at once never trip the
  /// engine's domain-reset check for the global scope.
  void _forgetDescriptorState(String scope) => _db.deleteLocalState(SyncKinds.account, SyncFormat.globalScope, scope);

  Future<_Identity> _identityOf(Profile profile) async {
    ProfileCredentials? credentials;
    try {
      credentials = await _credentials(profile);
    } catch (_) {
      credentials = null;
    }
    return switch (credentials) {
      null => const _NoCredentials(),
      SubsonicCredentials(:final baseUrl, :final username) when profile.provider == MediaId.providerSubsonic =>
        switch (AccountFingerprints.subsonic(baseUrl, username)) {
          final fp? => _Known(fp),
          null => const _Unusable(),
        },
      AppleMusicCredentials() when profile.provider == MediaId.providerAppleMusic => const _Explicit(),
      _ => const _Unusable(),
    };
  }

  String _uuid() {
    final bytes = List<int>.generate(16, (_) => _random.nextInt(256));
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    final hex = bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
    return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}';
  }
}

sealed class _Identity {
  const _Identity();
}

final class _Known extends _Identity {
  const _Known(this.fingerprint);
  final String fingerprint;
}

/// Credentials can't be read on this device.
final class _NoCredentials extends _Identity {
  const _NoCredentials();
}

/// Credentials present but no fingerprint can be derived.
final class _Unusable extends _Identity {
  const _Unusable();
}

/// Apple Music: identity only through explicit user choice.
final class _Explicit extends _Identity {
  const _Explicit();
}
