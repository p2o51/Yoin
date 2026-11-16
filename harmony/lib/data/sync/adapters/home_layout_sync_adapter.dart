/// Home layout <-> [SyncKinds.homeLayout] (Android adapters/HomeLayoutSyncAdapter.kt):
/// one record per account, key [key]. The sections JSON travels as an opaque
/// string (the versioned document both apps write; it keeps unknown section
/// ids, so a newer device's sections survive an older one).
///
/// A missing layout means "default": Restore default deletes it, so the
/// delete propagates as a tombstone and every device goes back to default.
library;

import '../../../home/home_section.dart' show HomeLayoutNotifier;
import '../../../platform/storage/stores.dart';
import '../../profile/profile.dart';
import '../sync_contracts.dart';
import 'sync_projections.dart';

class HomeLayoutSyncAdapter extends SyncAdapter {
  HomeLayoutSyncAdapter({required KeyValueStore store, required Future<List<Profile>> Function() profiles})
    : _store = store,
      _profiles = profiles;

  final KeyValueStore _store;
  final Future<List<Profile>> Function() _profiles;
  Future<void> _tail = Future.value();

  static const key = 'layout';

  @override
  String get kind => SyncKinds.homeLayout;
  @override
  int get kindVersion => 1;
  @override
  FileClass get fileClass => FileClass.state;
  @override
  bool get perAccount => true;
  @override
  bool get propagatesDeletes => true;
  @override
  ConflictPolicy get conflictPolicy => ConflictPolicy.lww;

  static JsonMap _projection(String sectionsJson) => {'sectionsJson': sectionsJson};

  Future<(String, String)> _keys(String? localProfileId) async {
    final profileId = localProfileId ?? (throw ArgumentError('home_layout is per-account'));
    final profile = (await _profiles()).where((p) => p.id == profileId).firstOrNull;
    if (profile == null) throw StateError('No account $profileId');
    return (
      HomeLayoutNotifier.keyFor(profile.provider, profileId),
      HomeLayoutNotifier.updatedAtKeyFor(profile.provider, profileId),
    );
  }

  Future<R> _serial<R>(Future<R> Function() body) {
    final done = _tail.then((_) => body());
    _tail = done.then((_) {}, onError: (Object _) {});
    return done;
  }

  @override
  Future<List<DomainRow>> readAll(String? localProfileId) async {
    final (layoutKey, updatedKey) = await _keys(localProfileId);
    final raw = await _store.read(layoutKey);
    if (raw == null) return const [];
    final updatedAt = int.tryParse(await _store.read(updatedKey) ?? '');
    return [DomainRow(key: key, projection: _projection(raw), rowTs: updatedAt)];
  }

  @override
  JsonMap? project(JsonMap payload) {
    final sections = requiredString(payload, 'sectionsJson');
    return sections == null ? null : _projection(sections);
  }

  @override
  Future<ApplyOutcome> apply(
    String? localProfileId,
    String key,
    JsonMap payload,
    int versionTs,
    String? expectedLocalHash,
  ) async {
    final (layoutKey, updatedKey) = await _keys(localProfileId);
    return _serial(() async {
      final current = await _store.read(layoutKey);
      final currentHash = current == null ? null : hashOrNull(_projection(current));
      final sections = requiredString(payload, 'sectionsJson');
      if (key != HomeLayoutSyncAdapter.key || sections == null) return Skipped(SkipReason.unsupported, currentHash);
      if (currentHash != expectedLocalHash) return Skipped(SkipReason.changedLocally, currentHash);
      await _store.write(layoutKey, sections);
      await _store.write(updatedKey, '$versionTs');
      return Applied(hashOrNull(_projection(sections)));
    });
  }

  @override
  Future<ApplyOutcome> delete(String? localProfileId, String key, String? expectedLocalHash) async {
    final (layoutKey, updatedKey) = await _keys(localProfileId);
    return _serial(() async {
      final current = await _store.read(layoutKey);
      if (current == null || key != HomeLayoutSyncAdapter.key) return const Applied(null);
      final currentHash = hashOrNull(_projection(current));
      if (currentHash != expectedLocalHash) return Skipped(SkipReason.changedLocally, currentHash);
      await _store.write(layoutKey, null);
      await _store.write(updatedKey, null);
      return const Applied(null);
    });
  }
}
