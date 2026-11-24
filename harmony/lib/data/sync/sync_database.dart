/// Cloud sync's own store (Android data/sync/SyncDatabase.kt): the replica of
/// what is in Drive plus this device's bookkeeping. Android keeps five Room
/// tables in no_backup/yoin-sync.db; here the same five tables live in memory
/// and are written as one gzip JSON snapshot (`yoin-sync.json.gz`) through a
/// [SyncDbFile], replaced atomically. The app's own storage never changes
/// shape for sync.
///
/// [transaction] gives Room's withTransaction semantics: a body that throws
/// leaves every table as it was before it started. Writes are kept in memory
/// at once and saved a moment later, coalesced (a sync applying a thousand
/// rows writes the snapshot once, not a thousand times); [flush] saves now.
/// The manager flushes after every step that must survive a kill (a merged
/// file, an upload, a reset marker); a crash in between is repaired by the
/// next capture, as on Android.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';

import 'sync_contracts.dart';

/// The replicated state: exactly what this device publishes.
@immutable
final class SyncRecordEntity {
  const SyncRecordEntity({
    required this.kind,
    required this.scope,
    required this.key,
    required this.kindVersion,
    required this.ts,
    required this.device,
    this.prevTs,
    this.prevDevice,
    required this.deleted,
    this.payload,
    this.payloadHash,
  });

  final String kind;
  final String scope;
  final String key;
  final int kindVersion;
  final int ts;
  final String device;
  final int? prevTs;
  final String? prevDevice;
  final bool deleted;

  /// Exact JSON object text (unknown keys preserved); null for tombstones.
  final String? payload;

  /// CanonicalJson hash of [payload]; null for tombstones.
  final String? payloadHash;

  RecordVersion get version => RecordVersion(ts, device);

  RecordVersion? get prev => prevTs != null && prevDevice != null ? RecordVersion(prevTs!, prevDevice!) : null;

  SyncRecordEntity copyWith({int? ts, String? device, int? prevTs, String? prevDevice}) => SyncRecordEntity(
    kind: kind,
    scope: scope,
    key: key,
    kindVersion: kindVersion,
    ts: ts ?? this.ts,
    device: device ?? this.device,
    prevTs: prevTs ?? this.prevTs,
    prevDevice: prevDevice ?? this.prevDevice,
    deleted: deleted,
    payload: payload,
    payloadHash: payloadHash,
  );

  Map<String, Object?> toJson() => {
    'k': kind,
    's': scope,
    'id': key,
    'kv': kindVersion,
    'ts': ts,
    'd': device,
    'pts': prevTs,
    'pd': prevDevice,
    'del': deleted,
    'p': payload,
    'h': payloadHash,
  };

  static SyncRecordEntity fromJson(Map<String, Object?> j) => SyncRecordEntity(
    kind: j['k']! as String,
    scope: j['s']! as String,
    key: j['id']! as String,
    kindVersion: j['kv']! as int,
    ts: j['ts']! as int,
    device: j['d']! as String,
    prevTs: j['pts'] as int?,
    prevDevice: j['pd'] as String?,
    deleted: j['del']! as bool,
    payload: j['p'] as String?,
    payloadHash: j['h'] as String?,
  );
}

/// What this device's storage holds for a record under the CURRENT binding.
@immutable
final class SyncLocalStateEntity {
  const SyncLocalStateEntity({
    required this.kind,
    required this.scope,
    required this.key,
    required this.boundProfileId,
    this.localHash,
    this.appliedTs,
    this.appliedDevice,
    required this.pending,
    this.pendingPrevTs,
    this.pendingPrevDevice,
  });

  final String kind;
  final String scope;
  final String key;

  /// Local profile the row lives under; "" for global kinds.
  final String boundProfileId;

  /// Hash of the domain projection as last read back; null when the row is known absent.
  final String? localHash;
  final int? appliedTs;
  final String? appliedDevice;

  /// A local change exists in the replica that has not been uploaded yet.
  final bool pending;
  final int? pendingPrevTs;
  final String? pendingPrevDevice;

  RecordVersion? get applied =>
      appliedTs != null && appliedDevice != null ? RecordVersion(appliedTs!, appliedDevice!) : null;

  RecordVersion? get pendingPrev =>
      pendingPrevTs != null && pendingPrevDevice != null ? RecordVersion(pendingPrevTs!, pendingPrevDevice!) : null;

  SyncLocalStateEntity copyWith({
    Object? localHash = _keep,
    Object? appliedTs = _keep,
    Object? appliedDevice = _keep,
    bool? pending,
    Object? pendingPrevTs = _keep,
    Object? pendingPrevDevice = _keep,
  }) => SyncLocalStateEntity(
    kind: kind,
    scope: scope,
    key: key,
    boundProfileId: boundProfileId,
    localHash: identical(localHash, _keep) ? this.localHash : localHash as String?,
    appliedTs: identical(appliedTs, _keep) ? this.appliedTs : appliedTs as int?,
    appliedDevice: identical(appliedDevice, _keep) ? this.appliedDevice : appliedDevice as String?,
    pending: pending ?? this.pending,
    pendingPrevTs: identical(pendingPrevTs, _keep) ? this.pendingPrevTs : pendingPrevTs as int?,
    pendingPrevDevice: identical(pendingPrevDevice, _keep) ? this.pendingPrevDevice : pendingPrevDevice as String?,
  );

  SyncLocalStateEntity clearPending() => copyWith(pending: false, pendingPrevTs: null, pendingPrevDevice: null);

  SyncLocalStateEntity withPending(RecordVersion? base) =>
      copyWith(pending: true, pendingPrevTs: base?.ts, pendingPrevDevice: base?.device);

  Map<String, Object?> toJson() => {
    'k': kind,
    's': scope,
    'id': key,
    'b': boundProfileId,
    'h': localHash,
    'at': appliedTs,
    'ad': appliedDevice,
    'p': pending,
    'ppt': pendingPrevTs,
    'ppd': pendingPrevDevice,
  };

  static SyncLocalStateEntity fromJson(Map<String, Object?> j) => SyncLocalStateEntity(
    kind: j['k']! as String,
    scope: j['s']! as String,
    key: j['id']! as String,
    boundProfileId: j['b']! as String,
    localHash: j['h'] as String?,
    appliedTs: j['at'] as int?,
    appliedDevice: j['ad'] as String?,
    pending: j['p']! as bool,
    pendingPrevTs: j['ppt'] as int?,
    pendingPrevDevice: j['ppd'] as String?,
  );
}

const Object _keep = Object();

/// Injective map local profile <-> sync scope.
@immutable
final class SyncBindingEntity {
  const SyncBindingEntity({
    required this.localProfileId,
    required this.syncProfileId,
    required this.provider,
    this.fingerprintHash,
    required this.state,
    this.observedFingerprintHash,
    required this.boundAt,
  });

  static const stateActive = 'active';
  static const statePausedMismatch = 'paused_mismatch';

  /// Removed from the cloud (a scope purge marker): not synced until resumed on this device. Sticky.
  static const statePurged = 'purged';

  final String localProfileId;
  final String syncProfileId;
  final String provider;

  /// sha256 of the account fingerprint this binding was made for; null for Apple Music (explicit links).
  final String? fingerprintHash;

  /// [stateActive], [statePausedMismatch] or [statePurged].
  final String state;

  /// Fingerprint hash observed when the state became paused.
  final String? observedFingerprintHash;
  final int boundAt;

  SyncBindingEntity copyWith({
    Object? fingerprintHash = _keep,
    String? state,
    Object? observedFingerprintHash = _keep,
  }) => SyncBindingEntity(
    localProfileId: localProfileId,
    syncProfileId: syncProfileId,
    provider: provider,
    fingerprintHash: identical(fingerprintHash, _keep) ? this.fingerprintHash : fingerprintHash as String?,
    state: state ?? this.state,
    observedFingerprintHash: identical(observedFingerprintHash, _keep)
        ? this.observedFingerprintHash
        : observedFingerprintHash as String?,
    boundAt: boundAt,
  );

  ActiveBinding toActive() =>
      ActiveBinding(localProfileId: localProfileId, syncProfileId: syncProfileId, provider: provider);

  @override
  bool operator ==(Object other) =>
      other is SyncBindingEntity &&
      other.localProfileId == localProfileId &&
      other.syncProfileId == syncProfileId &&
      other.provider == provider &&
      other.fingerprintHash == fingerprintHash &&
      other.state == state &&
      other.observedFingerprintHash == observedFingerprintHash &&
      other.boundAt == boundAt;

  @override
  int get hashCode =>
      Object.hash(localProfileId, syncProfileId, provider, fingerprintHash, state, observedFingerprintHash, boundAt);

  Map<String, Object?> toJson() => {
    'l': localProfileId,
    's': syncProfileId,
    'p': provider,
    'f': fingerprintHash,
    'st': state,
    'o': observedFingerprintHash,
    'b': boundAt,
  };

  static SyncBindingEntity fromJson(Map<String, Object?> j) => SyncBindingEntity(
    localProfileId: j['l']! as String,
    syncProfileId: j['s']! as String,
    provider: j['p']! as String,
    fingerprintHash: j['f'] as String?,
    state: j['st']! as String,
    observedFingerprintHash: j['o'] as String?,
    boundAt: j['b']! as int,
  );
}

/// Every Yoin file seen in Drive (ours and other devices').
@immutable
final class SyncRemoteFileEntity {
  const SyncRemoteFileEntity({
    required this.fileId,
    required this.deviceId,
    required this.fileKind,
    required this.shard,
    required this.version,
    this.modifiedTime,
    this.deviceName,
    this.writtenAt,
    required this.own,
  });

  final String fileId;
  final String deviceId;
  final String fileKind;
  final int shard;

  /// Drive `version` merged (or uploaded, for own files) last.
  final int version;
  final int? modifiedTime;
  final String? deviceName;
  final int? writtenAt;
  final bool own;

  Map<String, Object?> toJson() => {
    'id': fileId,
    'd': deviceId,
    'k': fileKind,
    's': shard,
    'v': version,
    'm': modifiedTime,
    'n': deviceName,
    'w': writtenAt,
    'o': own,
  };

  static SyncRemoteFileEntity fromJson(Map<String, Object?> j) => SyncRemoteFileEntity(
    fileId: j['id']! as String,
    deviceId: j['d']! as String,
    fileKind: j['k']! as String,
    shard: j['s']! as int,
    version: j['v']! as int,
    modifiedTime: j['m'] as int?,
    deviceName: j['n'] as String?,
    writtenAt: j['w'] as int?,
    own: j['o']! as bool,
  );
}

/// Well-known meta keys (Android SyncMetaKeys).
abstract final class SyncMetaKeys {
  static const deviceId = 'device_id';
  static const enabled = 'enabled';
  static const googlePermissionId = 'google_permission_id';
  static const googleEmail = 'google_email';
  static const googleDisplayName = 'google_display_name';
  static const replicaPermissionId = 'replica_permission_id';
  static const epoch = 'epoch';
  static const lastSyncAt = 'last_sync_at';
  static const lastError = 'last_error';

  /// The last notable sync's count of items restored from other devices.
  static const lastSummary = 'last_summary';
  static const misconfiguredFor = 'misconfigured_for';
  static const heldReview = 'held_review';
  static const hasPublished = 'has_published';
  static const participated = 'participated';
  static const pendingReset = 'pending_reset';
  static const lastUploadedHashPrefix = 'last_uploaded_hash:';
  static const pendingFileIdPrefix = 'pending_file_id:';

  /// Prefix: `purged_scope:<syncProfileId>` = epoch of the newest scope purge applied here.
  static const purgedScopePrefix = 'purged_scope:';
}

/// Where the snapshot lives. Tests use [MemorySyncDbFile].
abstract interface class SyncDbFile {
  /// The saved bytes, or null when nothing was saved yet.
  Future<Uint8List?> read();

  Future<void> write(Uint8List bytes);

  /// Whether anything was ever saved (no read of the content).
  Future<bool> exists();
}

final class MemorySyncDbFile implements SyncDbFile {
  Uint8List? bytes;
  int writes = 0;

  @override
  Future<Uint8List?> read() async => bytes;

  @override
  Future<void> write(Uint8List bytes) async {
    this.bytes = bytes;
    writes++;
  }

  @override
  Future<bool> exists() async => bytes != null;
}

/// The snapshot in a real file, replaced atomically (temp file + rename).
final class IoSyncDbFile implements SyncDbFile {
  IoSyncDbFile(this.directory);

  /// Resolved lazily: the platform answers where its files go.
  final Future<Directory?> Function() directory;

  static const fileName = 'yoin-sync.json.gz';

  Future<File?> _file() async {
    final dir = await directory();
    if (dir == null) return null;
    return File('${dir.path}${Platform.pathSeparator}$fileName');
  }

  @override
  Future<bool> exists() async => (await _file())?.exists() ?? false;

  @override
  Future<Uint8List?> read() async {
    final file = await _file();
    if (file == null || !await file.exists()) return null;
    return file.readAsBytes();
  }

  @override
  Future<void> write(Uint8List bytes) async {
    final file = await _file();
    if (file == null) return;
    await file.parent.create(recursive: true);
    final temp = File('${file.path}.tmp');
    await temp.writeAsBytes(bytes, flush: true);
    await temp.rename(file.path);
  }
}

/// The five tables, in memory, with transactions and snapshot persistence.
class SyncDatabase {
  SyncDatabase(this._file, {this.saveDelay = const Duration(seconds: 2)});

  /// How long changes wait before they are saved (coalescing).
  final Duration saveDelay;
  Timer? _saveTimer;

  final SyncDbFile _file;

  final Map<String, SyncRecordEntity> _records = {};
  final Map<String, SyncLocalStateEntity> _states = {};
  final Map<String, SyncBindingEntity> _bindings = {};
  final Map<String, SyncRemoteFileEntity> _files = {};
  final Map<String, String> _meta = {};

  bool _loaded = false;
  Future<void>? _loading;
  int _depth = 0;
  bool _dirty = false;
  Future<void> _saving = Future.value();
  bool _saveQueued = false;

  static String _k(String kind, String scope, String key) => '$kind\u0000$scope\u0000$key';

  /// Reads the snapshot once. A damaged snapshot starts empty (the replica is
  /// rebuilt from Drive on the next cycle).
  Future<void> open() {
    if (_loaded) return Future.value();
    return _loading ??= _load();
  }

  Future<void> _load() async {
    try {
      final bytes = await _file.read();
      if (bytes != null) _restore(jsonDecode(utf8.decode(gzip.decode(bytes))) as Map<String, Object?>);
    } catch (e) {
      debugPrint('YoinSync: sync store unreadable, starting empty (${e.runtimeType})');
      _clearAll();
    }
    _loaded = true;
  }

  /// Whether a snapshot was ever written (Android: the sync DB file exists).
  Future<bool> exists() => _file.exists();

  void _clearAll() {
    _records.clear();
    _states.clear();
    _bindings.clear();
    _files.clear();
    _meta.clear();
  }

  void _restore(Map<String, Object?> json) {
    _clearAll();
    for (final r in (json['records'] as List? ?? const [])) {
      final e = SyncRecordEntity.fromJson((r as Map).cast<String, Object?>());
      _records[_k(e.kind, e.scope, e.key)] = e;
    }
    for (final s in (json['states'] as List? ?? const [])) {
      final e = SyncLocalStateEntity.fromJson((s as Map).cast<String, Object?>());
      _states[_k(e.kind, e.scope, e.key)] = e;
    }
    for (final b in (json['bindings'] as List? ?? const [])) {
      final e = SyncBindingEntity.fromJson((b as Map).cast<String, Object?>());
      _bindings[e.localProfileId] = e;
    }
    for (final f in (json['files'] as List? ?? const [])) {
      final e = SyncRemoteFileEntity.fromJson((f as Map).cast<String, Object?>());
      _files[e.fileId] = e;
    }
    (json['meta'] as Map? ?? const {}).forEach((k, v) => _meta[k as String] = v as String);
  }

  Map<String, Object?> _snapshot() => {
    'v': 1,
    'records': [for (final r in _records.values) r.toJson()],
    'states': [for (final s in _states.values) s.toJson()],
    'bindings': [for (final b in _bindings.values) b.toJson()],
    'files': [for (final f in _files.values) f.toJson()],
    'meta': Map.of(_meta),
  };

  void _assertOpen() {
    if (!_loaded) throw StateError('SyncDatabase.open() first');
  }

  /// Runs [body] atomically: if it throws, every table goes back to how it was.
  Future<T> transaction<T>(Future<T> Function() body) async {
    _assertOpen();
    if (_depth > 0) return body();
    final records = Map.of(_records);
    final states = Map.of(_states);
    final bindings = Map.of(_bindings);
    final files = Map.of(_files);
    final meta = Map.of(_meta);
    _depth++;
    try {
      final result = await body();
      _depth--;
      _scheduleDelayedSave();
      return result;
    } catch (_) {
      _depth--;
      _records
        ..clear()
        ..addAll(records);
      _states
        ..clear()
        ..addAll(states);
      _bindings
        ..clear()
        ..addAll(bindings);
      _files
        ..clear()
        ..addAll(files);
      _meta
        ..clear()
        ..addAll(meta);
      rethrow;
    }
  }

  void _changed() {
    _dirty = true;
    if (_depth == 0) _scheduleDelayedSave();
  }

  void _scheduleDelayedSave() {
    if (!_dirty || _saveTimer != null) return;
    _saveTimer = Timer(saveDelay, () {
      _saveTimer = null;
      _scheduleSave();
    });
  }

  void _scheduleSave() {
    if (!_dirty || _saveQueued) return;
    _saveQueued = true;
    _saving = _saving.then((_) async {
      _saveQueued = false;
      if (!_dirty) return;
      _dirty = false;
      try {
        final bytes = Uint8List.fromList(gzip.encode(utf8.encode(jsonEncode(_snapshot()))));
        await _file.write(bytes);
      } catch (e) {
        _dirty = true;
        debugPrint('YoinSync: saving the sync store failed (${e.runtimeType})');
      }
    });
  }

  /// Saves every change so far and waits until it is on disk.
  Future<void> flush() async {
    _saveTimer?.cancel();
    _saveTimer = null;
    _scheduleSave();
    await _saving;
  }

  /// Saves what's waiting now instead of after [saveDelay] (the owner goes away).
  void close() {
    if (_saveTimer == null) return;
    _saveTimer!.cancel();
    _saveTimer = null;
    _scheduleSave();
  }

  // ---- records

  SyncRecordEntity? getRecord(String kind, String scope, String key) => _records[_k(kind, scope, key)];

  List<SyncRecordEntity> recordsFor(String kind, String scope) => [
    for (final r in _records.values)
      if (r.kind == kind && r.scope == scope) r,
  ];

  List<SyncRecordEntity> allRecords() => _records.values.toList();

  List<SyncRecordEntity> recordsInScope(String scope) => [
    for (final r in _records.values)
      if (r.scope == scope) r,
  ];

  List<SyncRecordEntity> recordsOfKind(String kind) => [
    for (final r in _records.values)
      if (r.kind == kind) r,
  ];

  int liveCount(String kind, String scope) =>
      _records.values.where((r) => r.kind == kind && r.scope == scope && !r.deleted).length;

  void upsertRecord(SyncRecordEntity record) {
    _assertOpen();
    _records[_k(record.kind, record.scope, record.key)] = record;
    _changed();
  }

  void deleteRecord(String kind, String scope, String key) {
    if (_records.remove(_k(kind, scope, key)) != null) _changed();
  }

  void clearRecords() {
    _records.clear();
    _changed();
  }

  // ---- local state

  SyncLocalStateEntity? getLocalState(String kind, String scope, String key) => _states[_k(kind, scope, key)];

  List<SyncLocalStateEntity> localStatesFor(String kind, String scope) => [
    for (final s in _states.values)
      if (s.kind == kind && s.scope == scope) s,
  ];

  List<SyncLocalStateEntity> localStatesInScope(String scope) => [
    for (final s in _states.values)
      if (s.scope == scope) s,
  ];

  List<SyncLocalStateEntity> pendingLocalStates() => [
    for (final s in _states.values)
      if (s.pending) s,
  ];

  int pendingCount() => _states.values.where((s) => s.pending).length;

  void upsertLocalState(SyncLocalStateEntity state) {
    _assertOpen();
    _states[_k(state.kind, state.scope, state.key)] = state;
    _changed();
  }

  void deleteLocalState(String kind, String scope, String key) {
    if (_states.remove(_k(kind, scope, key)) != null) _changed();
  }

  void deleteLocalStatesInScope(String scope) {
    final before = _states.length;
    _states.removeWhere((_, s) => s.scope == scope);
    if (_states.length != before) _changed();
  }

  void deleteLocalStatesForProfile(String localProfileId) {
    final before = _states.length;
    _states.removeWhere((_, s) => s.boundProfileId == localProfileId);
    if (_states.length != before) _changed();
  }

  void clearLocalStates() {
    _states.clear();
    _changed();
  }

  // ---- bindings

  List<SyncBindingEntity> bindings() => _bindings.values.toList();

  SyncBindingEntity? bindingFor(String localProfileId) => _bindings[localProfileId];

  SyncBindingEntity? bindingForScope(String syncProfileId) {
    for (final b in _bindings.values) {
      if (b.syncProfileId == syncProfileId) return b;
    }
    return null;
  }

  /// Unique on syncProfileId, as Android's index: a binding taking a scope
  /// another profile holds is refused.
  void upsertBinding(SyncBindingEntity binding) {
    _assertOpen();
    final holder = bindingForScope(binding.syncProfileId);
    if (holder != null && holder.localProfileId != binding.localProfileId) {
      throw StateError('Scope ${binding.syncProfileId} is already bound');
    }
    _bindings[binding.localProfileId] = binding;
    _changed();
  }

  void deleteBinding(String localProfileId) {
    if (_bindings.remove(localProfileId) != null) _changed();
  }

  /// Every binding in [fromState] moves to [state].
  void updateBindingStates(String fromState, String state) {
    for (final binding in _bindings.values.toList()) {
      if (binding.state == fromState) {
        _bindings[binding.localProfileId] = binding.copyWith(state: state);
        _changed();
      }
    }
  }

  // ---- remote files

  List<SyncRemoteFileEntity> remoteFiles() => _files.values.toList();

  SyncRemoteFileEntity? remoteFile(String fileId) => _files[fileId];

  void upsertRemoteFile(SyncRemoteFileEntity file) {
    _assertOpen();
    _files[file.fileId] = file;
    _changed();
  }

  void deleteRemoteFile(String fileId) {
    if (_files.remove(fileId) != null) _changed();
  }

  void clearRemoteFiles() {
    _files.clear();
    _changed();
  }

  // ---- meta

  String? meta(String key) => _meta[key];

  List<MapEntry<String, String>> metaWithPrefix(String prefix) => [
    for (final e in _meta.entries)
      if (e.key.startsWith(prefix)) e,
  ];

  void putMeta(String key, String value) {
    _assertOpen();
    if (_meta[key] == value) return;
    _meta[key] = value;
    _changed();
  }

  void deleteMeta(String key) {
    if (_meta.remove(key) != null) _changed();
  }
}
