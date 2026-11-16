/// memory_copy and memory_title (Android adapters/MemorySyncAdapters.kt
/// MemoryCopySyncAdapter, MemoryTitleSyncAdapter): two synced kinds over ONE
/// [MemoryCopyCache] row, Yoin's AI narration and Yoin's AI Memory title.
/// They are separate kinds on purpose: the narration is written again
/// whenever the listening facts move, and as one record a peer's newer
/// narration without a title would win last-writer-wins and wipe a title
/// written elsewhere. Each adapter reads and writes only its own fields; the
/// hashes travel along so the receiving device reuses the text instead of
/// asking the model again (its own ratings and notes are synced too, so the
/// hashes normally match). Nothing is ever deleted: the cache has no delete
/// path.
///
/// Keys and payloads are Android's: `provider␟album␟rawId`, `copy` /
/// `promptHash` / `generatedAt`, and `title` / `titlePromptHash`. HarmonyOS
/// keeps album rows only (Android's `entityType` is always "album" today);
/// another entity type is declined as unsupported and carried untouched.
library;

import '../../memory/memory_copy_cache.dart';
import '../../model/media_id.dart';
import '../sync_contracts.dart';
import 'sync_projections.dart';

/// [profileId]'s AI words (the live cache the screens read).
typedef MemoryCopyCacheFor = MemoryCopyCache Function(String profileId);

/// Android MemoryCopyCache.ENTITY_ALBUM.
const memoryCopyEntityAlbum = 'album';

String memoryCopyKey(MediaId albumId) =>
    SyncFormat.compositeKey([albumId.provider, memoryCopyEntityAlbum, albumId.rawId]);

/// The album a key names; null for a key that isn't one this app can hold.
MediaId? memoryCopyKeyAlbum(String key) {
  final parts = splitKey(key, 3);
  if (parts == null) return null;
  final [provider, entityType, rawId] = parts;
  if (entityType != memoryCopyEntityAlbum) return null;
  if (provider.isEmpty || provider.contains(':') || rawId.isEmpty) return null;
  return MediaId(provider, rawId);
}

String _requireProfile(String? id, String kind) => id ?? (throw ArgumentError('$kind is per-account'));

// ---------------------------------------------------------------- memory_copy

/// The narration half of the row.
class MemoryCopySyncAdapter extends SyncAdapter {
  MemoryCopySyncAdapter(this._cache);

  final MemoryCopyCacheFor _cache;

  @override
  String get kind => SyncKinds.memoryCopy;
  @override
  int get kindVersion => 1;
  @override
  FileClass get fileClass => FileClass.state;
  @override
  bool get perAccount => true;
  @override
  bool get propagatesDeletes => false;
  @override
  ConflictPolicy get conflictPolicy => ConflictPolicy.lww;

  static const _copy = 'copy';
  static const _promptHash = 'promptHash';
  static const _generatedAt = 'generatedAt';

  static JsonMap _projection(String copy, String promptHash, int generatedAt) => {
    _copy: copy,
    _promptHash: promptHash,
    _generatedAt: generatedAt,
  };

  static JsonMap? _rowProjection(MemoryCopyRow? row) =>
      row == null || row.copy.trim().isEmpty ? null : _projection(row.copy, row.promptHash, row.generatedAt);

  @override
  Future<List<DomainRow>> readAll(String? localProfileId) async {
    final rows = await _cache(_requireProfile(localProfileId, kind)).syncRead();
    return [
      for (final e in rows.entries)
        if (_rowProjection(e.value) case final projection?)
          DomainRow(key: memoryCopyKey(e.key), projection: projection, rowTs: e.value.generatedAt),
    ];
  }

  @override
  JsonMap? project(JsonMap payload) {
    final copy = requiredString(payload, _copy);
    if (copy == null || copy.trim().isEmpty) return null;
    final promptHash = requiredString(payload, _promptHash);
    final generatedAt = requiredLong(payload, _generatedAt);
    if (promptHash == null || generatedAt == null) return null;
    return _projection(copy, promptHash, generatedAt);
  }

  @override
  Future<ApplyOutcome> apply(
    String? localProfileId,
    String key,
    JsonMap payload,
    int versionTs,
    String? expectedLocalHash,
  ) async {
    final cache = _cache(_requireProfile(localProfileId, kind));
    final albumId = memoryCopyKeyAlbum(key);
    if (albumId == null) return const Skipped(SkipReason.unsupported, null);
    return cache.syncEdit((rows) {
      final existing = rows[albumId];
      final currentHash = hashOrNull(_rowProjection(existing));
      final incoming = project(payload);
      if (incoming == null) return Skipped(SkipReason.unsupported, currentHash);
      if (currentHash != expectedLocalHash) return Skipped(SkipReason.changedLocally, currentHash);
      final copy = incoming[_copy]! as String;
      final promptHash = incoming[_promptHash]! as String;
      final generatedAt = incoming[_generatedAt]! as int;
      // Field-level: the title half of the row belongs to memory_title.
      rows[albumId] = MemoryCopyRow(
        copy: copy,
        promptHash: promptHash,
        generatedAt: generatedAt,
        title: existing?.title,
        titlePromptHash: existing?.titlePromptHash,
      );
      return Applied(hashOrNull(_projection(copy, promptHash, generatedAt)));
    });
  }

  @override
  Future<ApplyOutcome> delete(String? localProfileId, String key, String? expectedLocalHash) async =>
      Skipped(SkipReason.policy, expectedLocalHash);
}

// ---------------------------------------------------------------- memory_title

/// The title half of the row.
class MemoryTitleSyncAdapter extends SyncAdapter {
  MemoryTitleSyncAdapter(this._cache);

  final MemoryCopyCacheFor _cache;

  @override
  String get kind => SyncKinds.memoryTitle;
  @override
  int get kindVersion => 1;
  @override
  FileClass get fileClass => FileClass.state;
  @override
  bool get perAccount => true;
  @override
  bool get propagatesDeletes => false;
  @override
  ConflictPolicy get conflictPolicy => ConflictPolicy.lww;

  static const _title = 'title';
  static const _titlePromptHash = 'titlePromptHash';

  static JsonMap _projection(String title, String? titlePromptHash) => {
    _title: title,
    _titlePromptHash: titlePromptHash,
  };

  static JsonMap? _rowProjection(MemoryCopyRow? row) {
    final title = row?.title;
    return title == null || title.trim().isEmpty ? null : _projection(title, row!.titlePromptHash);
  }

  @override
  Future<List<DomainRow>> readAll(String? localProfileId) async {
    final rows = await _cache(_requireProfile(localProfileId, kind)).syncRead();
    return [
      for (final e in rows.entries)
        if (_rowProjection(e.value) case final projection?)
          // The title has no time of its own; the row's is the closest for first-join ordering.
          DomainRow(key: memoryCopyKey(e.key), projection: projection, rowTs: e.value.generatedAt),
    ];
  }

  @override
  JsonMap? project(JsonMap payload) {
    final title = requiredString(payload, _title);
    if (title == null || title.trim().isEmpty) return null;
    final titlePromptHash = optionalString(payload, _titlePromptHash);
    if (titlePromptHash is! FieldOk<String?>) return null;
    return _projection(title, titlePromptHash.value);
  }

  @override
  Future<ApplyOutcome> apply(
    String? localProfileId,
    String key,
    JsonMap payload,
    int versionTs,
    String? expectedLocalHash,
  ) async {
    final cache = _cache(_requireProfile(localProfileId, kind));
    final albumId = memoryCopyKeyAlbum(key);
    if (albumId == null) return const Skipped(SkipReason.unsupported, null);
    return cache.syncEdit((rows) {
      final existing = rows[albumId];
      final currentHash = hashOrNull(_rowProjection(existing));
      final incoming = project(payload);
      if (incoming == null) return Skipped(SkipReason.unsupported, currentHash);
      if (currentHash != expectedLocalHash) return Skipped(SkipReason.changedLocally, currentHash);
      final title = incoming[_title]! as String;
      final titlePromptHash = incoming[_titlePromptHash] as String?;
      // Home reads a title from any row; with no narration yet the row starts
      // with an empty one and a prompt key that never matches, so Memories
      // writes it or falls back to the template.
      rows[albumId] = MemoryCopyRow(
        copy: existing?.copy ?? '',
        promptHash: existing?.promptHash ?? '',
        generatedAt: existing?.generatedAt ?? 0,
        title: title,
        titlePromptHash: titlePromptHash,
      );
      return Applied(hashOrNull(_projection(title, titlePromptHash)));
    });
  }

  @override
  Future<ApplyOutcome> delete(String? localProfileId, String key, String? expectedLocalHash) async =>
      Skipped(SkipReason.policy, expectedLocalHash);
}
