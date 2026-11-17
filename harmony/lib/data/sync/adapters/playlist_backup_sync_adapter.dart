/// Playlist backups <-> [SyncKinds.playlistBackup] (Android
/// adapters/PlaylistBackupSyncAdapter.kt; docs/playlist-transfer.md §7): one
/// record per backup, keyed by its id (a UUID).
///
/// Global, not per account: a backup is restored into any profile, and Apple
/// Music accounts have no sync id to scope one by. Artifacts, not state: a
/// backup is large. It changes when the playlist it follows changes (at most
/// every few hours), when the playlist is found gone, and when a restore adds
/// to its history.
///
/// `content`, `versions` and `restores` travel as the exact JSON text the
/// backup holds, so a newer device's extra fields survive a round trip and
/// the projection hashes alike everywhere; apply checks they still decode.
/// `updatedAt` is a synced field and is written back as received.
/// `sourceAccount` travels only when known. `sourceProfileId` is this
/// device's own profile id: it never travels, and apply keeps the local one.
///
/// Deleting a backup is a user action, so deletes propagate as tombstones.
/// Conflicts: [ConflictPolicy.lwwLiveWins]: the later change wins; a delete
/// that raced a restore elsewhere loses to it, because a backup can't be made
/// again once its playlist is gone.
library;

import '../../backup/playlist_backup_models.dart';
import '../../backup/playlist_backup_store.dart';
import '../sync_contracts.dart';
import 'sync_projections.dart';

class PlaylistBackupSyncAdapter extends SyncAdapter {
  PlaylistBackupSyncAdapter(this._store);

  final PlaylistBackupStore _store;

  @override
  String get kind => SyncKinds.playlistBackup;
  @override
  int get kindVersion => 1;
  @override
  FileClass get fileClass => FileClass.artifacts;
  @override
  bool get perAccount => false;
  @override
  bool get propagatesDeletes => true;
  @override
  ConflictPolicy get conflictPolicy => ConflictPolicy.lwwLiveWins;

  static const _name = 'name';
  static const _sourceProvider = 'sourceProvider';
  static const _sourceProfileLabel = 'sourceProfileLabel';
  static const _sourcePlaylistId = 'sourcePlaylistId';
  static const _sourceAccount = 'sourceAccount';
  static const _playlistCreatedAt = 'playlistCreatedAt';
  static const _firstBackedUpAt = 'firstBackedUpAt';
  static const _lastBackedUpAt = 'lastBackedUpAt';
  static const _lastChangedAt = 'lastChangedAt';
  static const _sourceGoneAt = 'sourceGoneAt';
  static const _updatedAt = 'updatedAt';
  static const _content = 'content';
  static const _restores = 'restores';
  static const _versions = 'versions';

  @override
  Future<List<DomainRow>> readAll(String? localProfileId) async => [
    for (final row in await _store.rows()) DomainRow(key: row.id, projection: projection(row), rowTs: row.updatedAt),
  ];

  @override
  JsonMap? project(JsonMap payload) {
    final row = rowOf('', payload);
    return row == null ? null : projection(row);
  }

  @override
  Future<ApplyOutcome> apply(
    String? localProfileId,
    String key,
    JsonMap payload,
    int versionTs,
    String? expectedLocalHash,
  ) => _store.transaction(() async {
    final current = await _store.row(key);
    final currentHash = current == null ? null : hashOrNull(projection(current));
    final row = rowOf(key, payload, keepProfileId: current?.sourceProfileId);
    if (row == null) return Skipped(SkipReason.unsupported, currentHash);
    if (currentHash != expectedLocalHash) return Skipped(SkipReason.changedLocally, currentHash);
    await _store.putRowUnlocked(row);
    final written = await _store.row(key);
    return Applied(written == null ? null : hashOrNull(projection(written)));
  });

  @override
  Future<ApplyOutcome> delete(String? localProfileId, String key, String? expectedLocalHash) =>
      _store.transaction(() async {
        final current = await _store.row(key);
        if (current == null) return const Applied(null);
        final currentHash = hashOrNull(projection(current));
        if (currentHash != expectedLocalHash) return Skipped(SkipReason.changedLocally, currentHash);
        await _store.deleteUnlocked(key);
        return const Applied(null);
      });

  static JsonMap projection(PlaylistBackupRow row) => {
    _name: row.name,
    _sourceProvider: row.sourceProvider,
    _sourceProfileLabel: row.sourceProfileLabel,
    _sourcePlaylistId: row.sourcePlaylistId,
    _playlistCreatedAt: row.playlistCreatedAt,
    _firstBackedUpAt: row.firstBackedUpAt,
    _lastBackedUpAt: row.lastBackedUpAt,
    _lastChangedAt: row.lastChangedAt,
    _sourceGoneAt: row.sourceGoneAt,
    _updatedAt: row.updatedAt,
    _content: row.contentJson,
    _versions: row.versionsJson,
    _restores: row.restoresJson,
    if (row.sourceAccount != null) _sourceAccount: row.sourceAccount,
  };

  /// The row [payload] describes, or null when a field is missing or its JSON doesn't decode.
  static PlaylistBackupRow? rowOf(String key, JsonMap payload, {String? keepProfileId}) {
    final contentJson = requiredString(payload, _content);
    final restoresJson = requiredString(payload, _restores);
    if (contentJson == null || restoresJson == null) return null;
    final PlaylistBackupContent content;
    try {
      content = PlaylistBackupCodec.decodeContent(contentJson);
      PlaylistBackupCodec.decodeRestores(restoresJson);
    } catch (_) {
      return null;
    }
    // Older payloads had no versions: none, then.
    final versionsField = optionalString(payload, _versions);
    if (versionsField is! FieldOk<String?>) return null;
    final versionsJson = versionsField.value ?? '[]';
    try {
      PlaylistBackupCodec.decodeVersions(versionsJson);
    } catch (_) {
      return null;
    }
    final name = requiredString(payload, _name);
    final provider = requiredString(payload, _sourceProvider);
    final label = requiredString(payload, _sourceProfileLabel);
    final playlistId = requiredString(payload, _sourcePlaylistId);
    final account = optionalString(payload, _sourceAccount);
    final created = optionalString(payload, _playlistCreatedAt);
    final first = requiredLong(payload, _firstBackedUpAt);
    final last = requiredLong(payload, _lastBackedUpAt);
    final changed = requiredLong(payload, _lastChangedAt);
    final gone = optionalLong(payload, _sourceGoneAt);
    final updated = requiredLong(payload, _updatedAt);
    if (name == null || provider == null || provider.isEmpty || label == null || playlistId == null) return null;
    if (account is! FieldOk<String?> || created is! FieldOk<String?> || gone is! FieldOk<int?>) return null;
    if (first == null || last == null || changed == null || updated == null) return null;
    final accountValue = account.value;
    return PlaylistBackupRow(
      id: key,
      name: name,
      sourceProvider: provider,
      sourceProfileLabel: label,
      sourcePlaylistId: playlistId,
      // This device's own: kept from the local row, never synced.
      sourceProfileId: keepProfileId,
      sourceAccount: accountValue == null || accountValue.isEmpty ? null : accountValue,
      playlistCreatedAt: created.value,
      firstBackedUpAt: first,
      lastBackedUpAt: last,
      lastChangedAt: changed,
      sourceGoneAt: gone.value,
      trackCount: content.tracks.length,
      unexportedCount: content.unexported.length,
      contentJson: contentJson,
      versionsJson: versionsJson,
      restoresJson: restoresJson,
      updatedAt: updated,
    );
  }
}
