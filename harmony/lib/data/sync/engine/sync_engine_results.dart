/// What the engine reports (Android engine/SyncEngineResults.kt).
library;

import 'dart:convert';
import 'package:flutter/foundation.dart';

import '../sync_contracts.dart';

/// Identity of a replicated record.
@immutable
final class RecordKey {
  const RecordKey(this.kind, this.scope, this.key);
  final String kind;
  final String scope;
  final String key;

  @override
  bool operator ==(Object other) =>
      other is RecordKey && other.kind == kind && other.scope == scope && other.key == key;

  @override
  int get hashCode => Object.hash(kind, scope, key);
}

/// scope -> kind -> count.
typedef ScopeKindCounts = Map<String, Map<String, int>>;

int totalOf(ScopeKindCounts counts) =>
    counts.values.fold(0, (sum, byKind) => sum + byKind.values.fold(0, (a, b) => a + b));

int countOf(ScopeKindCounts counts, String scope, String kind) => counts[scope]?[kind] ?? 0;

/// One serialized per-device file, ready to upload if [contentHash] differs from the last uploaded one.
final class OutgoingFile {
  OutgoingFile({
    required this.fileKind,
    required this.shard,
    required this.bytes,
    required this.contentHash,
    required this.includedVersions,
  });

  final String fileKind;
  final int shard;

  /// gzip JSON envelope.
  final Uint8List bytes;

  /// Hash over the sorted records only (never writtenAt / deviceName).
  final String contentHash;
  final Map<RecordKey, RecordVersion> includedVersions;

  int get recordCount => includedVersions.length;
}

/// A (kind, scope) whose domain read or write failed this cycle; everything else kept going.
final class KindScopeFailure {
  const KindScopeFailure(this.kind, this.scope, this.error);
  final String kind;
  final String scope;
  final Object error;
}

/// A held mass delete, kept in meta until the user resolves it.
@immutable
final class HeldReview {
  const HeldReview({required this.kind, required this.scope, required this.keys});

  final String kind;
  final String scope;
  final List<String> keys;

  int get count => keys.length;

  String encode() => jsonEncode({'kind': kind, 'scope': scope, 'keys': keys});

  static HeldReview? decode(String? text) {
    if (text == null) return null;
    try {
      final json = jsonDecode(text);
      if (json is! Map) return null;
      final kind = json['kind'];
      final scope = json['scope'];
      final keys = json['keys'];
      if (kind is! String || scope is! String || keys is! List) return null;
      return HeldReview(kind: kind, scope: scope, keys: [for (final k in keys) k as String]);
    } catch (_) {
      return null;
    }
  }
}

final class MergeResult {
  const MergeResult({
    required this.fileId,
    required this.deviceId,
    required this.deviceName,
    required this.received,
    required this.inserted,
    required this.replaced,
    required this.conflicts,
    required this.keptBoth,
    required this.localChangesDropped,
    required this.opaque,
    required this.malformed,
    required this.changedByScope,
  });

  final String fileId;
  final String deviceId;
  final String deviceName;
  final int received;
  final int inserted;
  final int replaced;
  final int conflicts;
  final int keptBoth;
  final int localChangesDropped;
  final int opaque;
  final int malformed;
  final ScopeKindCounts changedByScope;

  bool get changed => inserted + replaced + keptBoth > 0;
}

final class CaptureResult {
  const CaptureResult({
    required this.newVersions,
    required this.tombstonesCreated,
    required this.keptBoth,
    required this.adopted,
    required this.repaired,
    required this.cloudWins,
    required this.droppedLocalStates,
    required this.domainResets,
    required this.heldTombstones,
    required this.heldReview,
    required this.failures,
  });

  final ScopeKindCounts newVersions;
  final ScopeKindCounts tombstonesCreated;
  final int keptBoth;
  final int adopted;
  final int repaired;
  final int cloudWins;
  final int droppedLocalStates;
  final List<String> domainResets;
  final int heldTombstones;
  final HeldReview? heldReview;
  final List<KindScopeFailure> failures;

  int get totalNewVersions => totalOf(newVersions);
  int get totalTombstones => totalOf(tombstonesCreated);
  bool get changed => totalNewVersions + totalTombstones > 0;
}

final class ApplyResult {
  const ApplyResult({
    required this.appliedLive,
    required this.removed,
    required this.skippedChangedLocally,
    required this.skipped,
    required this.unsupported,
    required this.failures,
  });

  final ScopeKindCounts appliedLive;
  final ScopeKindCounts removed;
  final int skippedChangedLocally;
  final Map<SkipReason, int> skipped;
  final int unsupported;
  final List<KindScopeFailure> failures;

  int get totalApplied => totalOf(appliedLive);
  int get totalRemoved => totalOf(removed);

  /// Kinds that wrote anything into this device's storage (to refresh what's on screen).
  Set<String> get touchedKinds => {
    for (final byKind in appliedLive.values) ...byKind.keys,
    for (final byKind in removed.values) ...byKind.keys,
  };
}

class ScopeKindCounter {
  final Map<String, Map<String, int>> _counts = {};

  void add(String scope, String kind, [int delta = 1]) {
    final byKind = _counts.putIfAbsent(scope, () => {});
    byKind[kind] = (byKind[kind] ?? 0) + delta;
  }

  void addAll(ScopeKindCounter other) {
    other._counts.forEach((scope, byKind) => byKind.forEach((kind, count) => add(scope, kind, count)));
  }

  ScopeKindCounts snapshot() => {for (final e in _counts.entries) e.key: Map.unmodifiable(e.value)};
}
