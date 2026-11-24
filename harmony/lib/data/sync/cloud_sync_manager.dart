/// App-scoped owner of cloud sync: the only place that runs sync cycles
/// (Android data/sync/CloudSyncManager.kt).
///
/// Construction does no I/O; everything starts from [onAppForeground] (the
/// app coming to the front), the Settings row or page ([ensureInitialized]),
/// or a UI action. Every cycle — network or local capture — runs under one
/// lock, and its failures become a status instead of an exception.
library;

import 'dart:async';
import 'dart:math';

import 'package:flutter/foundation.dart';

import 'auth/google_oauth.dart';
import 'cloud_data_views.dart';
import 'cloud_sync_state.dart';
import 'engine/snapshot_codec.dart';
import 'engine/sync_engine.dart';
import 'engine/sync_engine_results.dart';
import 'identity/account_binder.dart';
import 'identity/cloud_account_descriptor.dart';
import 'sync_contracts.dart';
import 'sync_database.dart';

/// A Drive client bound to [tokens].
typedef SyncTransportFactory = SyncTransport Function(SyncTokenProvider tokens);

class CloudSyncManager implements CloudSyncController {
  CloudSyncManager({
    required SyncDatabase db,
    required GoogleSyncAuthorizer auth,
    required SyncTransportFactory transportFor,
    required List<SyncAdapter> Function(SyncDatabase db) adapters,
    required AccountBinder Function(SyncDatabase db) binder,
    required Future<String> Function() deviceName,
    required this.packageName,
    required this.versionCode,
    int Function()? clock,
    Random? random,
    this.onDomainChanged,
    Stream<void>? localChanges,
    this.foregroundThrottle = const Duration(seconds: 60),
    this.localDebounce = const Duration(seconds: 8),
  }) : _db = db,
       _auth = auth,
       _transportFor = transportFor,
       _adaptersFor = adapters,
       _binderFor = binder,
       _deviceNameSource = deviceName,
       _clock = clock ?? (() => DateTime.now().millisecondsSinceEpoch),
       _random = random ?? Random.secure(),
       _localChanges = localChanges;

  final SyncDatabase _db;
  final GoogleSyncAuthorizer _auth;
  final SyncTransportFactory _transportFor;
  final List<SyncAdapter> Function(SyncDatabase) _adaptersFor;
  final AccountBinder Function(SyncDatabase) _binderFor;
  final Future<String> Function() _deviceNameSource;
  final int Function() _clock;
  final Random _random;
  final Stream<void>? _localChanges;

  /// Shown on the "not available in this build" details row.
  final String packageName;

  /// Written into every uploaded file (Android's versionCode).
  final int versionCode;

  /// Called after a sync wrote into this device's storage, with the kinds it
  /// touched, so screens holding those rows read them again.
  final void Function(Set<String> kinds)? onDomainChanged;

  final Duration foregroundThrottle;
  final Duration localDebounce;

  final _lock = _Lock();
  final _stateLock = _Lock();
  final _state = ValueNotifier<CloudSyncState>(const CloudSyncState());

  @override
  ValueListenable<CloudSyncState> get state => _state;

  late final SyncTransport _transport = _transportFor(_auth);
  late final AccountBinder _binder = _binderFor(_db);
  SyncEngine? _engineInstance;

  bool _initialized = false;
  Future<void>? _initializing;
  int _lastCycleStartedAt = 0;
  BindingSnapshot? _lastBindings;
  String? _updateRequiredFrom;
  String _deviceName = '';
  String? _deviceId;
  StreamSubscription<void>? _observer;
  Timer? _debounce;
  Future<void>? _launchedCycle;
  bool _disposed = false;

  // LAST_ERROR codes
  static const _codeReauth = 'reauth';
  static const _codeAccountMismatch = 'account_mismatch';
  static const _codeOffline = 'offline';
  static const _codeStorage = 'storage';
  static const _codeMisconfigured = 'misconfigured';
  static const _codeReview = 'review';
  static const _codeCloudCopyRemoved = 'cloud_copy_removed';
  static const _codeDeletedHere = 'deleted_here';
  static const _codeResetPrefix = 'reset:';
  static const _codeUpdatePrefix = 'update:';
  static const _errorPrefix = 'error:';

  static const _yoinFileKinds = {
    SyncFileProps.kindState,
    SyncFileProps.kindArtifacts,
    SyncFileProps.kindReset,
    SyncFileProps.kindPurge,
  };

  SyncEngine _engine() => _engineInstance ??= SyncEngine(
    db: _db,
    adapters: _adaptersFor(_db),
    deviceId: _deviceId!,
    deviceName: () => _deviceName,
    clock: _clock,
  );

  /// Runs [body] detached; a failure becomes the Error phase, never a crash.
  void _launch(Future<void> Function() body) {
    unawaited(
      body().catchError((Object error) {
        debugPrint('YoinSync: job failed (${error.runtimeType})');
        _setState(_state.value.copyWith(phase: SyncErrorPhase(error.toString())));
      }),
    );
  }

  // ---- lifecycle hooks

  /// [localProfileId]'s binding as this device keeps it (the sync store is
  /// read first if it isn't yet), or null: what an account's cross-device id
  /// is read from (AccountKeys).
  Future<SyncBindingEntity?> bindingFor(String localProfileId) async {
    await _db.open();
    return _db.bindingFor(localProfileId);
  }

  /// The app came to the front: sync if on, at most once a minute.
  void onAppForeground() => _launch(() async {
    // Never turned on: don't even create the sync store.
    if (!_initialized && !await _db.exists()) return;
    await ensureInitialized();
    if (!_isEnabled()) return;
    if (_clock() - _lastCycleStartedAt < foregroundThrottle.inMilliseconds) return;
    await _runCycle();
  });

  /// The app went to the background: flush any debounced local capture and upload what's waiting.
  void onAppBackground() => _launch(() async {
    if (!_initialized || !_isEnabled()) return;
    if (_debounce != null) {
      _debounce?.cancel();
      _debounce = null;
      await _runLocalCapture();
    }
    if (_engine().hasPendingChanges()) await _runCycle();
  });

  @override
  Future<void> ensureInitialized() {
    if (_initialized) return Future.value();
    return _initializing ??= _initialize().whenComplete(() {
      _initializing = null;
    });
  }

  Future<void> _initialize() async {
    await _lock.run(() async {
      await _db.open();
      _deviceId = _ensureDeviceId();
      try {
        final name = (await _deviceNameSource()).trim();
        _deviceName = name.length > 40 ? name.substring(0, 40) : name;
      } catch (_) {
        _deviceName = '';
      }
      _initialized = true;
      await _db.flush();
      // Account rows need no network: show them even when the first cycle can't reach Google.
      if (_isEnabled()) {
        try {
          await _refreshBindings();
        } catch (e) {
          debugPrint('YoinSync: binding refresh failed (${e.runtimeType})');
        }
      }
    });
    if (_isEnabled()) _startObservers();
    await _refreshState();
    if (_db.meta(SyncMetaKeys.pendingReset) != null) _launch(_resumePendingReset);
  }

  // ---- CloudSyncController

  @override
  Future<TurnOnStep> turnOn() async {
    await ensureInitialized();
    if (!_auth.isConfigured) {
      await _refreshState();
      return const TurnOnFailed(TurnOnFailed.misconfigured);
    }
    final result = await _auth.authorizeInteractively(loginHint: _db.meta(SyncMetaKeys.googleEmail));
    switch (result) {
      case GoogleAuthToken(:final accessToken):
        return _completeEnable(accessToken);
      case GoogleAuthCanceled():
        return const TurnOnCanceled();
      case GoogleAuthMisconfigured():
        await _markMisconfigured();
        return const TurnOnFailed(TurnOnFailed.misconfigured);
      case GoogleAuthFailure():
        return const TurnOnFailed(TurnOnFailed.unreachable);
    }
  }

  @override
  void cancelTurnOn() => _auth.cancelWaiting();

  @override
  bool get turnOnWaitingForBrowser => _auth.isWaitingForBrowser;

  Future<TurnOnStep> _completeEnable(String accessToken) async {
    // Identify the account just chosen with ITS token.
    final GoogleAccountInfo about;
    try {
      about = await _transportFor(_FixedToken(accessToken)).about();
    } on SyncMisconfigured {
      await _markMisconfigured();
      return const TurnOnFailed(TurnOnFailed.misconfigured);
    } on SyncTransportException {
      return const TurnOnFailed(TurnOnFailed.unreachable);
    }
    await _lock.run(() async {
      final previousOwner = _db.meta(SyncMetaKeys.googlePermissionId);
      final replicaOwner = _db.meta(SyncMetaKeys.replicaPermissionId);
      final otherDrive =
          (previousOwner != null && previousOwner != about.permissionId) ||
          (replicaOwner != null && replicaOwner != about.permissionId);
      if (otherDrive) {
        // Different Google account: the replica describes another Drive. Domain data stays.
        await _engine().clearReplica();
        _resetPublishBookkeeping();
        _db.deleteMeta(SyncMetaKeys.participated);
        _db.deleteMeta(SyncMetaKeys.lastSummary);
      }
      // The reset epoch belongs to one Drive; the first cycle adopts this Drive's.
      if (previousOwner != about.permissionId) _db.deleteMeta(SyncMetaKeys.epoch);
      _db.putMeta(SyncMetaKeys.googlePermissionId, about.permissionId);
      _db.putMeta(SyncMetaKeys.replicaPermissionId, about.permissionId);
      if (about.email != null) {
        _db.putMeta(SyncMetaKeys.googleEmail, about.email!);
      } else {
        _db.deleteMeta(SyncMetaKeys.googleEmail);
      }
      if (about.displayName != null) _db.putMeta(SyncMetaKeys.googleDisplayName, about.displayName!);
      _db.deleteMeta(SyncMetaKeys.misconfiguredFor);
      _db.deleteMeta(SyncMetaKeys.lastError);
      _db.putMeta(SyncMetaKeys.enabled, 'true');
      await _db.flush();
    });
    _startObservers();
    await _refreshState();
    _launchedCycle = _runCycle(force: true).catchError((Object _) {});
    return const TurnOnStarted();
  }

  @override
  void syncNow() {
    _launchedCycle = () async {
      await ensureInitialized();
      if (_isEnabled()) await _runCycle(force: true);
    }().catchError((Object _) {});
  }

  @override
  void turnOff() => _launch(() async {
    await ensureInitialized();
    await _lock.run(() async {
      _db.putMeta(SyncMetaKeys.enabled, 'false');
      _db.deleteMeta(SyncMetaKeys.lastError);
      await _db.flush();
    });
    _stopObservers();
    try {
      await _auth.forget();
    } catch (_) {
      // Nothing stored.
    }
    await _refreshState();
  });

  @override
  Future<void> deleteCloudData() async {
    await ensureInitialized();
    Object? failure;
    try {
      await _lock.run(() async {
        // Never act on whatever account a token happens to come from.
        final about = await _transport.about();
        final owner = _db.meta(SyncMetaKeys.googlePermissionId);
        if (owner == null || owner != about.permissionId) {
          throw StateError('Reconnect Google before deleting cloud data.');
        }
        final files = await _transport.list();
        final pending = _parsePendingReset(_db.meta(SyncMetaKeys.pendingReset));
        // Strictly newer than every marker any device has seen, whatever the local clock says.
        final epoch =
            pending?.$1 ??
            [
              _clock(),
              files.map(_resetEpoch).whereType<int>().fold(0, max) + 1,
              (int.tryParse(_db.meta(SyncMetaKeys.epoch) ?? '') ?? 0) + 1,
            ].reduce(max);
        final markerId = pending?.$2 ?? (await _transport.generateIds(1)).first;
        _db.putMeta(SyncMetaKeys.pendingReset, '$epoch|$markerId');
        await _db.flush();
        try {
          await _transport.create(
            fileId: markerId,
            name: SyncFileProps.resetFileName(epoch, _deviceId!),
            appProperties: {
              SyncFileProps.kind: SyncFileProps.kindReset,
              SyncFileProps.epoch: '$epoch',
              SyncFileProps.deviceId: _deviceId!,
            },
            mimeType: 'application/json',
            bytes: SnapshotCodec.encodeReset(
              ResetMarker(epoch: epoch, at: _clock(), deviceId: _deviceId!, deviceName: _deviceName),
            ),
          );
        } on SyncAlreadyExists {
          // A retry of an interrupted delete: the marker is already there.
        }
        // Local side first: from here on this device is off and in the new epoch.
        await _engine().clearReplica();
        _resetPublishBookkeeping();
        _db.deleteMeta(SyncMetaKeys.participated);
        _db.deleteMeta(SyncMetaKeys.lastSummary);
        _db.deleteMeta(SyncMetaKeys.lastError);
        _db.deleteMeta(SyncMetaKeys.replicaPermissionId);
        _db.putMeta(SyncMetaKeys.epoch, '$epoch');
        _db.putMeta(SyncMetaKeys.enabled, 'false');
        await _db.flush();
        await _deleteYoinFilesExcept(files, markerId);
        _db.deleteMeta(SyncMetaKeys.pendingReset);
        await _db.flush();
      });
      // No revoke: other devices need the grant to read the marker and turn themselves off.
      _auth.invalidateCached();
    } catch (e) {
      failure = e;
    }
    // Even a half-finished delete has already turned this device off.
    if (!_isEnabled()) _stopObservers();
    await _refreshState();
    if (failure != null) throw failure;
  }

  /// Finishes a "delete cloud data" that was interrupted after its marker was written.
  Future<void> _resumePendingReset() async {
    try {
      await _lock.run(() async {
        final pending = _parsePendingReset(_db.meta(SyncMetaKeys.pendingReset));
        if (pending == null) return;
        final about = await _transport.about();
        if (about.permissionId != _db.meta(SyncMetaKeys.googlePermissionId)) return;
        await _deleteYoinFilesExcept(await _transport.list(), pending.$2);
        _db.deleteMeta(SyncMetaKeys.pendingReset);
        await _db.flush();
      });
    } catch (e) {
      debugPrint('YoinSync: resuming cloud data deletion failed (${e.runtimeType})');
    }
  }

  Future<void> _deleteYoinFilesExcept(List<RemoteFile> files, String keepId) async {
    for (final file in files) {
      if (!_isYoinFile(file) || file.id == keepId) continue;
      if (!await _transport.delete(file.id)) throw StateError("Couldn't delete ${file.name}");
    }
  }

  static (int, String)? _parsePendingReset(String? value) {
    if (value == null) return null;
    final bar = value.indexOf('|');
    if (bar < 0) return null;
    final epoch = int.tryParse(value.substring(0, bar));
    final id = value.substring(bar + 1);
    if (epoch == null || id.isEmpty) return null;
    return (epoch, id);
  }

  @override
  void resolveReview({required bool restore}) => _launch(() async {
    await ensureInitialized();
    await _lock.run(() async {
      await _engine().resolveHeldReview(restore: restore);
      _db.deleteMeta(SyncMetaKeys.lastError);
    });
    await _runCycle(force: true);
  });

  @override
  void uploadAgain() => _launch(() async {
    await ensureInitialized();
    await _lock.run(() async {
      _resetPublishBookkeeping();
      _db.deleteMeta(SyncMetaKeys.lastError);
    });
    await _runCycle(force: true);
  });

  @override
  void startSyncingAccount(String localProfileId) => _accountAction(() => _binder.startSyncing(localProfileId));

  @override
  void linkAccount(String localProfileId, String syncProfileId) =>
      _accountAction(() => _binder.link(localProfileId, syncProfileId));

  @override
  void keepAccountAsBefore(String localProfileId) => _accountAction(() => _binder.keepAsBefore(localProfileId));

  @override
  void startFreshAccount(String localProfileId) => _launch(() async {
    await ensureInitialized();
    // Publish anything still pending for the old account first, so nothing of it is lost.
    if (_engine().hasPendingChanges()) await _runCycle(force: true);
    await _lock.run(() async {
      final plan = await _binder.prepareStartFresh(localProfileId);
      if (plan == null) return;
      // The cycle above didn't get them up: leave the account as it is.
      if (plan.pendingChanges > 0) {
        debugPrint('YoinSync: start fresh put off: ${plan.pendingChanges} changes not uploaded');
        return;
      }
      final purged = await _engine().purgeScopeLocally(plan.oldScope, localProfileId);
      await _binder.completeStartFresh(plan);
      if (purged > 0) {
        _notifyDomain(const {SyncKinds.songNote, SyncKinds.albumNote, SyncKinds.trackRating, SyncKinds.albumRating});
      }
    });
    await _runCycle(force: true);
  });

  @override
  void resumeAccountSync(String localProfileId) => _accountAction(() => _binder.resumeSyncing(localProfileId));

  @override
  void removeDevice(String deviceId) => _launch(() async {
    await ensureInitialized();
    await _lock.run(() async {
      try {
        for (final file in _db.remoteFiles().where((f) => f.deviceId == deviceId && !f.own)) {
          if (await _transport.delete(file.fileId)) _db.deleteRemoteFile(file.fileId);
        }
      } catch (e) {
        debugPrint('YoinSync: remove device failed (${e.runtimeType})');
      }
    });
    await _refreshState();
  });

  @override
  Future<void> removeAccountFromCloud(String syncProfileId) async {
    await ensureInitialized();
    Object? failure;
    try {
      if (syncProfileId.isEmpty) throw ArgumentError('No account to remove.');
      await _lock.run(() async {
        if (!_isEnabled()) throw StateError('Turn on Cloud sync to remove an account from the cloud.');
        // Never act on whatever account a token happens to come from.
        final about = await _transport.about();
        final owner = _db.meta(SyncMetaKeys.googlePermissionId);
        if (owner == null || owner != about.permissionId) {
          throw StateError('Reconnect Google before removing an account.');
        }
        final files = await _transport.list();
        final engine = _engine();
        final epoch = purgeEpoch(
          now: _clock(),
          scopeRecordTimes: [
            for (final r in _db.recordsInScope(syncProfileId)) r.ts,
            ?_db.getRecord(SyncKinds.account, SyncFormat.globalScope, syncProfileId)?.ts,
          ],
          markers: files,
          scope: syncProfileId,
          appliedEpoch: engine.purgedScopes()[syncProfileId],
          resetEpoch: int.tryParse(_db.meta(SyncMetaKeys.epoch) ?? '') ?? 0,
        );
        final markerId = (await _transport.generateIds(1)).first;
        await _transport.create(
          fileId: markerId,
          name: SyncFileProps.purgeFileName(epoch, _deviceId!),
          appProperties: {
            SyncFileProps.kind: SyncFileProps.kindPurge,
            SyncFileProps.epoch: '$epoch',
            SyncFileProps.scope: syncProfileId,
            SyncFileProps.deviceId: _deviceId!,
          },
          mimeType: 'application/json',
          bytes: SnapshotCodec.encodePurge(
            ScopePurgeMarker(
              scope: syncProfileId,
              epoch: epoch,
              at: _clock(),
              deviceId: _deviceId!,
              deviceName: _deviceName,
            ),
          ),
        );
        // The marker is out: from here a crash is repaired by the next cycle applying our own marker.
        await engine.applyScopePurge(syncProfileId, epoch);
        await _binder.markPurged(syncProfileId);
        await _refreshBindings();
        await _db.flush();
      });
      // Re-publish this device's files without the account.
      await _runCycle(force: true);
    } catch (e) {
      failure = e;
    }
    await _refreshState();
    if (failure != null) throw failure;
  }

  /// The epoch of a new purge marker for [scope] (Android CloudSyncManager.removeAccountFromCloud): at or
  /// after every version of the scope this device knows ([scopeRecordTimes]: its records and its account
  /// descriptor), after any earlier marker of it in Drive ([markers]) and any purge of it applied here
  /// ([appliedEpoch]), and not before the current reset epoch.
  @visibleForTesting
  static int purgeEpoch({
    required int now,
    required Iterable<int> scopeRecordTimes,
    required Iterable<RemoteFile> markers,
    required String scope,
    required int? appliedEpoch,
    required int resetEpoch,
  }) {
    final newestRecord = scopeRecordTimes.fold(0, max);
    final previousMarker = markers
        .where(
          (f) =>
              f.appProperties[SyncFileProps.kind] == SyncFileProps.kindPurge &&
              f.appProperties[SyncFileProps.scope] == scope,
        )
        .map((f) => int.tryParse(f.appProperties[SyncFileProps.epoch] ?? '') ?? 0)
        .fold(0, max);
    return [now, newestRecord, previousMarker + 1, (appliedEpoch ?? 0) + 1, resetEpoch].reduce(max);
  }

  @override
  Future<CloudAccountContent> cloudAccountContent(String syncProfileId) async {
    await ensureInitialized();
    return CloudDataViews.account(_db.recordsInScope(syncProfileId), syncProfileId);
  }

  /// Runs [body] while no sync cycle runs (export, import from a backup,
  /// Transfer Memories; Android holds the sync lock the same way). [replica]
  /// is the sync store when Cloud sync ever ran on this device, else null (and
  /// nothing is created); [deviceId] is the sync device id then.
  Future<T> runPortability<T>(
    Future<T> Function(SyncDatabase? replica, String? deviceId, String deviceName, bool syncOn) body,
  ) async {
    if (!_initialized && !await _db.exists()) {
      String name;
      try {
        name = (await _deviceNameSource()).trim();
      } catch (_) {
        name = '';
      }
      if (name.length > 40) name = name.substring(0, 40);
      return _lock.run(() => body(null, null, name, false));
    }
    await ensureInitialized();
    final result = await _lock.run(() => body(_db, _deviceId, _deviceName, _isEnabled()));
    await _db.flush();
    return result;
  }

  void _accountAction(Future<Object?> Function() action) => _launch(() async {
    await ensureInitialized();
    await _lock.run(() async => action());
    await _runLocalCapture();
    await _runCycle(force: true);
  });

  // ---- cycles

  Future<void> _runCycle({bool force = false}) async {
    if (!force && _lock.isLocked) return;
    await _lock.run(() async {
      if (!_isEnabled()) return;
      _lastCycleStartedAt = _clock();
      _setState(_state.value.copyWith(phase: const SyncSyncing()));
      String? outcome;
      try {
        outcome = await _cycle();
      } on SyncTransportException catch (e) {
        outcome = _handleTransportError(e);
      } catch (e) {
        debugPrint('YoinSync: cycle failed (${e.runtimeType})');
        outcome = '$_errorPrefix$e';
      }
      if (outcome == null) {
        _db.deleteMeta(SyncMetaKeys.lastError);
      } else {
        _db.putMeta(SyncMetaKeys.lastError, outcome);
      }
      await _db.flush();
    });
    await _refreshState();
    if (!_isEnabled()) _stopObservers();
  }

  /// One network cycle. Returns a LAST_ERROR code, or null when everything went through.
  Future<String?> _cycle() async {
    final engine = _engine();
    final me = _deviceId!;

    final about = await _transport.about();
    final owner = _db.meta(SyncMetaKeys.googlePermissionId);
    if (owner != null && owner != about.permissionId) return _codeAccountMismatch;

    final files = await _transport.list();

    // A newer "delete cloud data" from any device wins over everything.
    RemoteFile? newest;
    var newestEpoch = 0;
    for (final f in files) {
      if (f.appProperties[SyncFileProps.kind] != SyncFileProps.kindReset) continue;
      final epoch = int.tryParse(f.appProperties[SyncFileProps.epoch] ?? '') ?? 0;
      if (newest == null || epoch > newestEpoch) {
        newest = f;
        newestEpoch = epoch;
      }
    }
    var myEpoch = int.tryParse(_db.meta(SyncMetaKeys.epoch) ?? '') ?? 0;
    if (newestEpoch < myEpoch) {
      // The epoch belongs to the Drive: a lower one means a different (or wiped) Drive.
      _db.putMeta(SyncMetaKeys.epoch, '$newestEpoch');
      myEpoch = newestEpoch;
    }
    if (newestEpoch > myEpoch) {
      final ownMarker = newest?.appProperties[SyncFileProps.deviceId] == me;
      final participated =
          _db.meta(SyncMetaKeys.participated) == 'true' || _db.meta(SyncMetaKeys.hasPublished) == 'true';
      if (participated || ownMarker) {
        // We hold data from before that reset: stop instead of uploading it back.
        ResetMarker? by;
        if (!ownMarker && newest != null) {
          try {
            final bytes = await _transport.download(newest.id);
            by = bytes == null ? null : SnapshotCodec.decodeReset(bytes);
          } catch (_) {
            by = null;
          }
        }
        await engine.clearReplica();
        _resetPublishBookkeeping();
        _db.deleteMeta(SyncMetaKeys.participated);
        _db.deleteMeta(SyncMetaKeys.lastSummary);
        _db.putMeta(SyncMetaKeys.epoch, '$newestEpoch');
        _db.putMeta(SyncMetaKeys.enabled, 'false');
        return ownMarker ? _codeDeletedHere : '$_codeResetPrefix${by?.deviceName ?? ''}';
      }
      // Joining after an old reset: nothing of ours predates it. Start clean in the new epoch.
      await engine.clearReplica();
      _resetPublishBookkeeping();
      _db.putMeta(SyncMetaKeys.epoch, '$newestEpoch');
      myEpoch = newestEpoch;
    }

    await _applyPurgeMarkers(files, myEpoch);

    final dataFiles = files.where((f) {
      final kind = f.appProperties[SyncFileProps.kind];
      return kind == SyncFileProps.kindState || kind == SyncFileProps.kindArtifacts;
    });
    final ownFiles = dataFiles.where((f) => f.appProperties[SyncFileProps.deviceId] == me).toList();
    final peerFiles = dataFiles.where((f) => f.appProperties[SyncFileProps.deviceId] != me).toList();

    // Own files: keep the newest per (kind, shard); a crashed create may have left a duplicate.
    var keptOwn = <String, RemoteFile>{};
    final grouped = <String, List<RemoteFile>>{};
    for (final f in ownFiles) {
      grouped.putIfAbsent(_fileKey(f), () => []).add(f);
    }
    for (final e in grouped.entries) {
      final sorted = e.value..sort((a, b) => b.version.compareTo(a.version));
      for (final dup in sorted.skip(1)) {
        try {
          await _transport.delete(dup.id);
        } catch (_) {
          // Next cycle.
        }
      }
      keptOwn[e.key] = sorted.first;
    }
    final hasPublished = _db.meta(SyncMetaKeys.hasPublished) == 'true';
    if (hasPublished && keptOwn['${SyncFileProps.kindState}:0'] == null) {
      final othersStillThere = peerFiles.any((f) => f.appProperties[SyncFileProps.kind] == SyncFileProps.kindState);
      // Only our files are gone: another device removed us. Re-publish. With every device's files gone, ask.
      if (!othersStillThere) return _codeCloudCopyRemoved;
      _resetPublishBookkeeping();
      keptOwn = {};
    }

    // Merge peers that changed since we last merged them.
    _updateRequiredFrom = null;
    final listedIds = {for (final f in files) f.id};
    for (final file in peerFiles) {
      final seen = _db.remoteFile(file.id);
      if (seen != null && seen.version == file.version) continue;
      final bytes = await _transport.download(file.id);
      if (bytes == null) continue;
      final SyncEnvelope envelope;
      try {
        envelope = SnapshotCodec.decode(bytes);
      } on SyncSchemaTooNewException catch (e) {
        _updateRequiredFrom = e.deviceName ?? e.deviceId ?? file.appProperties[SyncFileProps.deviceId];
        continue;
      } on MalformedSyncFileException {
        debugPrint('YoinSync: skipping an unreadable sync file');
        continue;
      }
      // Written before the newest "delete cloud data": ignore, and remember the version.
      if (envelope.epoch < myEpoch) {
        _db.upsertRemoteFile(
          SyncRemoteFileEntity(
            fileId: file.id,
            deviceId: envelope.deviceId,
            fileKind: file.appProperties[SyncFileProps.kind] ?? '',
            shard: int.tryParse(file.appProperties[SyncFileProps.shard] ?? '') ?? 0,
            version: file.version,
            modifiedTime: file.modifiedTime,
            deviceName: envelope.deviceName,
            writtenAt: envelope.writtenAt,
            own: false,
          ),
        );
        continue;
      }
      if (envelope.deviceId == me) continue;
      try {
        await engine.mergeRemote(file, envelope);
        _db.putMeta(SyncMetaKeys.participated, 'true');
      } on SyncSchemaTooNewException catch (e) {
        _updateRequiredFrom = e.deviceName ?? envelope.deviceName;
      } on MalformedSyncFileException {
        debugPrint('YoinSync: skipping a malformed sync file');
      }
    }
    // Forget files that are gone (removed devices, deleted duplicates).
    for (final f in _db.remoteFiles()) {
      if (!listedIds.contains(f.fileId) && !f.own) _db.deleteRemoteFile(f.fileId);
    }

    final bindings = await _refreshBindings();
    await engine.capture(bindings.active);
    final applied = await engine.apply(bindings.active);
    _summarize(applied);
    await _db.flush();

    // Publish what changed.
    final now = _clock();
    final outgoing = engine.outgoingFiles(epoch: myEpoch, writtenAt: now, appVersionCode: versionCode);
    for (final file in outgoing) {
      final existing = keptOwn['${file.fileKind}:${file.shard}'];
      if (existing != null && engine.lastUploadedHash(file.fileKind, file.shard) == file.contentHash) continue;
      // Don't create empty artifact shards; the state file always exists once we publish.
      if (existing == null && file.recordCount == 0 && file.fileKind != SyncFileProps.kindState) continue;
      final uploaded = await _upload(existing, file, me);
      if (uploaded == null) continue;
      _db.putMeta(SyncMetaKeys.participated, 'true');
      if (file.fileKind == SyncFileProps.kindState) _db.putMeta(SyncMetaKeys.hasPublished, 'true');
      await engine.markUploaded(file);
      _db.upsertRemoteFile(
        SyncRemoteFileEntity(
          fileId: uploaded.id,
          deviceId: me,
          fileKind: file.fileKind,
          shard: file.shard,
          version: uploaded.version,
          modifiedTime: uploaded.modifiedTime,
          deviceName: _deviceName,
          writtenAt: now,
          own: true,
        ),
      );
    }
    _db.putMeta(SyncMetaKeys.hasPublished, 'true');
    _db.putMeta(SyncMetaKeys.lastSyncAt, '$now');

    if (engine.heldReview() != null) return _codeReview;
    final updateFrom = _updateRequiredFrom;
    if (updateFrom != null) return '$_codeUpdatePrefix$updateFrom';
    return null;
  }

  /// Applies every scope purge marker ("remove this account from the cloud",
  /// written by Android or iOS) this device hasn't applied yet, newest per
  /// scope, before any peer file is merged (CloudSyncCore.applyPurgeMarkers,
  /// docs/cloud-sync.md).
  Future<void> _applyPurgeMarkers(List<RemoteFile> files, int resetEpoch) async {
    final markers = files.where((f) => f.appProperties[SyncFileProps.kind] == SyncFileProps.kindPurge).toList();
    if (markers.isEmpty) return;
    final engine = _engine();
    final applied = engine.purgedScopes();
    final newest = <String, int>{};
    for (final file in markers) {
      var scope = file.appProperties[SyncFileProps.scope];
      if (scope != null && scope.isEmpty) scope = null;
      var epoch = int.tryParse(file.appProperties[SyncFileProps.epoch] ?? '');
      if (scope == null || epoch == null) {
        ScopePurgeMarker? marker;
        try {
          final bytes = await _transport.download(file.id);
          marker = bytes == null ? null : SnapshotCodec.decodePurge(bytes);
        } catch (_) {
          marker = null;
        }
        if (marker == null) continue;
        scope = marker.scope;
        epoch = marker.epoch;
      }
      if (scope == SyncFormat.globalScope || epoch < resetEpoch) continue;
      final seen = newest[scope];
      if (seen == null || epoch > seen) newest[scope] = epoch;
    }
    for (final MapEntry(key: scope, value: epoch) in newest.entries) {
      final known = applied[scope];
      if (known != null && known >= epoch) continue;
      await engine.applyScopePurge(scope, epoch);
      await _binder.markPurged(scope);
    }
  }

  Future<RemoteFile?> _upload(RemoteFile? existing, OutgoingFile file, String me) async {
    if (existing != null) {
      final updated = await _transport.update(existing.id, file.bytes);
      if (updated != null) return updated;
    }
    final pendingKey = '${SyncMetaKeys.pendingFileIdPrefix}${file.fileKind}:${file.shard}';
    var id = _db.meta(pendingKey);
    if (id == null) {
      id = (await _transport.generateIds(1)).first;
      _db.putMeta(pendingKey, id);
      await _db.flush();
    }
    final name = file.fileKind == SyncFileProps.kindState
        ? SyncFileProps.stateFileName(me)
        : SyncFileProps.artifactFileName(me, file.shard);
    RemoteFile? created;
    try {
      created = await _transport.create(
        fileId: id,
        name: name,
        appProperties: {
          SyncFileProps.kind: file.fileKind,
          SyncFileProps.deviceId: me,
          SyncFileProps.shard: '${file.shard}',
          SyncFileProps.schema: '${SyncFormat.schema}',
        },
        mimeType: 'application/gzip',
        bytes: file.bytes,
      );
    } on SyncAlreadyExists {
      created = await _transport.update(id, file.bytes);
    } on SyncServerError catch (e) {
      // Drive refused our pre-allocated id: allocate a new one next time.
      if (e.code == 400) _db.deleteMeta(pendingKey);
      rethrow;
    }
    if (created != null) _db.deleteMeta(pendingKey);
    return created;
  }

  /// Local-only: bind newly connected accounts and capture/apply without the network.
  Future<void> _runLocalCapture() async {
    await _lock.run(() async {
      if (!_isEnabled()) return;
      try {
        final bindings = await _refreshBindings();
        final engine = _engine();
        await engine.capture(bindings.active);
        final applied = await engine.apply(bindings.active);
        _summarize(applied);
        await _db.flush();
      } catch (e) {
        debugPrint('YoinSync: local capture failed (${e.runtimeType})');
      }
    });
    await _refreshState();
  }

  Future<BindingSnapshot> _refreshBindings() async {
    final cloud = CloudAccountDescriptor.fromRecords(_db.recordsOfKind(SyncKinds.account));
    return _lastBindings = await _binder.refresh(cloud);
  }

  void _summarize(ApplyResult applied) {
    final touched = applied.touchedKinds;
    if (touched.isNotEmpty) _notifyDomain(touched);
    _db.deleteMeta(SyncMetaKeys.lastSummary);
    int count(String kind) => applied.appliedLive.values.fold(0, (sum, byKind) => sum + (byKind[kind] ?? 0));
    final restored =
        count(SyncKinds.songNote) +
        count(SyncKinds.albumNote) +
        count(SyncKinds.trackRating) +
        count(SyncKinds.albumRating);
    if (restored == 0) return;
    _db.putMeta(SyncMetaKeys.lastSummary, '$restored');
  }

  void _notifyDomain(Set<String> kinds) {
    try {
      onDomainChanged?.call(kinds);
    } catch (e) {
      debugPrint('YoinSync: refreshing screens failed (${e.runtimeType})');
    }
  }

  // ---- observers

  void _startObservers() {
    if (_observer != null) return;
    final changes = _localChanges;
    if (changes == null) return;
    _observer = changes.listen((_) {
      _debounce?.cancel();
      _debounce = Timer(localDebounce, () {
        _launch(() async {
          await _runLocalCapture();
          if (_isEnabled() && _engine().hasPendingChanges()) await _runCycle();
        });
      });
    });
  }

  void _stopObservers() {
    _debounce?.cancel();
    _debounce = null;
    unawaited(_observer?.cancel());
    _observer = null;
  }

  // ---- state

  /// Computes and publishes as one step, so two refreshes never publish out of order.
  Future<void> _refreshState() => _stateLock.run(() async => _publishState());

  void _publishState() {
    final enabled = _isEnabled();
    final lastError = _db.meta(SyncMetaKeys.lastError);
    final CloudSyncPhase phase;
    if (!enabled) {
      phase = _offPhase(lastError);
    } else if (_lock.isLocked && _state.value.phase is SyncSyncing) {
      phase = const SyncSyncing();
    } else {
      phase = _onPhase(lastError);
    }
    final me = _deviceId ?? '';
    final thisDevice = SyncDeviceRow(
      deviceId: me,
      name: _deviceName,
      isThisDevice: true,
      lastSyncAt: int.tryParse(_db.meta(SyncMetaKeys.lastSyncAt) ?? ''),
    );
    final peers = [
      for (final f in _db.remoteFiles())
        if (!f.own && f.deviceId != me && f.fileKind == SyncFileProps.kindState)
          SyncDeviceRow(
            deviceId: f.deviceId,
            name: f.deviceName ?? 'Unknown device',
            isThisDevice: false,
            lastSyncAt: f.writtenAt ?? f.modifiedTime,
          ),
    ]..sort((a, b) => (b.lastSyncAt ?? 0).compareTo(a.lastSyncAt ?? 0));
    final bindings = _lastBindings;
    final permission = _db.meta(SyncMetaKeys.googlePermissionId);
    _setState(
      CloudSyncState(
        phase: phase,
        account: permission == null
            ? null
            : GoogleAccountUi(
                email: _db.meta(SyncMetaKeys.googleEmail),
                displayName: _db.meta(SyncMetaKeys.googleDisplayName),
              ),
        lastSyncAt: thisDevice.lastSyncAt,
        pendingChanges: enabled && _db.pendingCount() > 0,
        accounts: bindings?.rows ?? const [],
        cloudOnlyAccounts: bindings?.cloudOnly ?? const [],
        devices: enabled || peers.isNotEmpty ? [thisDevice, ...peers] : const [],
        lastSummaryCount: int.tryParse(_db.meta(SyncMetaKeys.lastSummary) ?? ''),
        scopeCounts: enabled && _initialized ? CloudDataViews.summary(_db.allRecords()) : const {},
      ),
    );
  }

  /// A job still running when the app's container goes away has no one to tell.
  void _setState(CloudSyncState next) {
    if (!_disposed) _state.value = next;
  }

  CloudSyncPhase _offPhase(String? lastError) {
    if (lastError == _codeDeletedHere) return const SyncOff();
    if (lastError != null && lastError.startsWith(_codeResetPrefix)) {
      final name = lastError.substring(_codeResetPrefix.length);
      return SyncResetElsewhere(name.trim().isEmpty ? null : name);
    }
    if (!_auth.isConfigured || _db.meta(SyncMetaKeys.misconfiguredFor) == _misconfiguredKey()) {
      return SyncMisconfiguredPhase(packageName);
    }
    return const SyncOff();
  }

  CloudSyncPhase _onPhase(String? lastError) {
    if (lastError == null) return const SyncUpToDate();
    if (lastError == _codeReauth || lastError == _codeAccountMismatch) return const SyncNeedsReauth();
    if (lastError == _codeOffline) return const SyncOffline();
    if (lastError == _codeStorage) return const SyncStorageFullPhase();
    if (lastError == _codeCloudCopyRemoved) return const SyncCloudCopyRemoved();
    if (lastError == _codeMisconfigured) {
      return const SyncErrorPhase("Google Drive isn't set up for this build of Yoin.");
    }
    if (lastError == _codeReview) {
      final held = _engine().heldReview();
      if (held == null) return const SyncUpToDate();
      final bindings = _lastBindings;
      String? name;
      for (final row in bindings?.rows ?? const <SyncAccountRow>[]) {
        final bound = bindings!.active.any(
          (b) => b.syncProfileId == held.scope && b.localProfileId == row.localProfileId,
        );
        if (bound) {
          name = row.displayName;
          break;
        }
      }
      return SyncNeedsReview(name ?? 'an account', held.count);
    }
    if (lastError.startsWith(_codeUpdatePrefix)) {
      final name = lastError.substring(_codeUpdatePrefix.length);
      return SyncUpdateRequired(name.trim().isEmpty ? null : name);
    }
    if (lastError.startsWith(_errorPrefix)) return SyncErrorPhase(lastError.substring(_errorPrefix.length));
    return SyncErrorPhase(lastError);
  }

  String _handleTransportError(SyncTransportException error) => switch (error) {
    SyncUnauthorized() => _codeReauth,
    // Can be transient (API just enabled, client just created): keep sync on and retry later.
    SyncMisconfigured() => _codeMisconfigured,
    SyncStorageFull() => _codeStorage,
    SyncRateLimited() || SyncNetworkError() => _codeOffline,
    SyncAlreadyExists() || SyncServerError() => '$_errorPrefix${_describe(error)}',
  };

  static String _describe(SyncTransportException error) => switch (error) {
    SyncNetworkError() => "Couldn't reach Google. Check your connection.",
    SyncStorageFull() => 'Your Google storage is full.',
    SyncMisconfigured() => "Cloud sync isn't available in this build of Yoin.",
    SyncUnauthorized() => 'Google needs your permission again.',
    SyncRateLimited() => 'Google asked Yoin to slow down. Trying again later.',
    SyncServerError(:final code) => 'Google Drive error ($code).',
    SyncAlreadyExists() => 'Google Drive conflict.',
  };

  Future<void> _markMisconfigured() async {
    await _lock.run(() async {
      _db.putMeta(SyncMetaKeys.misconfiguredFor, _misconfiguredKey());
      await _db.flush();
    });
    await _refreshState();
  }

  /// Re-offered after an update: the version and the client this build has.
  String _misconfiguredKey() => '$versionCode|${_auth.isConfigured}';

  // ---- meta helpers

  bool _isEnabled() => _db.meta(SyncMetaKeys.enabled) == 'true';

  void _resetPublishBookkeeping() {
    _db.deleteMeta(SyncMetaKeys.hasPublished);
    for (final e in _db.metaWithPrefix(SyncMetaKeys.lastUploadedHashPrefix)) {
      _db.deleteMeta(e.key);
    }
    for (final e in _db.metaWithPrefix(SyncMetaKeys.pendingFileIdPrefix)) {
      _db.deleteMeta(e.key);
    }
    for (final f in _db.remoteFiles()) {
      if (f.own) _db.deleteRemoteFile(f.fileId);
    }
  }

  String _ensureDeviceId() {
    final stored = _db.meta(SyncMetaKeys.deviceId);
    if (stored != null) return stored;
    final id = List.generate(16, (_) => _random.nextInt(16).toRadixString(16)).join();
    _db.putMeta(SyncMetaKeys.deviceId, id);
    return id;
  }

  static bool _isYoinFile(RemoteFile f) => _yoinFileKinds.contains(f.appProperties[SyncFileProps.kind]);

  static int? _resetEpoch(RemoteFile f) => f.appProperties[SyncFileProps.kind] == SyncFileProps.kindReset
      ? int.tryParse(f.appProperties[SyncFileProps.epoch] ?? '')
      : null;

  static String _fileKey(RemoteFile f) =>
      '${f.appProperties[SyncFileProps.kind]}:${f.appProperties[SyncFileProps.shard] ?? '0'}';

  /// Test-only: one full cycle, after any cycle a turn-on started.
  @visibleForTesting
  Future<void> syncNowAndWait() async {
    await ensureInitialized();
    await _launchedCycle;
    await _runCycle(force: true);
  }

  void dispose() {
    _disposed = true;
    _stopObservers();
    _db.close();
    _state.dispose();
  }
}

/// A token provider bound to exactly one access token (identity checks).
final class _FixedToken implements SyncTokenProvider {
  const _FixedToken(this._token);
  final String _token;

  @override
  Future<String> token() async => _token;

  @override
  Future<void> invalidate(String token) async {}
}

/// A FIFO async lock; [isLocked] while anything holds or waits for it.
final class _Lock {
  Future<void> _last = Future.value();
  int _count = 0;

  bool get isLocked => _count > 0;

  Future<T> run<T>(Future<T> Function() body) async {
    final previous = _last;
    final released = Completer<void>();
    _last = released.future;
    _count++;
    try {
      await previous;
      return await body();
    } finally {
      _count--;
      released.complete();
    }
  }
}
