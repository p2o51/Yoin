/// The album About table <-> [SyncKinds.albumAbout] (Android
/// adapters/AlbumAboutSyncAdapter.kt): an album's AI credits and paragraph,
/// and the questions asked about it. The same rules as [SongAboutSyncAdapter],
/// for the same reasons: global (reference material, keyed by the album's
/// provider + id rather than by account), every record stamped with the AI
/// language it was written in and applied only by devices reading that
/// language, deletes never propagate (a language switch clears the table
/// locally; Drive keeps the records for their language), last write wins.
/// Every row is the AI's: there are no listener-written rows to stamp
/// language-free.
library;

import '../../about/album_about_store.dart';
import '../sync_contracts.dart';
import 'song_about_sync_adapter.dart' show SongAboutSyncAdapter;
import 'sync_projections.dart';

class AlbumAboutSyncAdapter extends SyncAdapter {
  AlbumAboutSyncAdapter({required AlbumAboutStore store, required Future<String> Function() language})
    : _store = store,
      _language = language;

  final AlbumAboutStore _store;

  /// The answer language now (normalised).
  final Future<String> Function() _language;

  static const languageField = 'targetLanguage';

  @override
  String get kind => SyncKinds.albumAbout;
  @override
  int get kindVersion => 1;
  @override
  FileClass get fileClass => FileClass.artifacts;
  @override
  bool get perAccount => false;
  @override
  bool get propagatesDeletes => false;
  @override
  ConflictPolicy get conflictPolicy => ConflictPolicy.lww;

  static String keyOf(String language, AlbumAboutEntry row) =>
      SyncFormat.compositeKey([language, row.provider, row.albumId, row.kind, row.entryKey]);

  static JsonMap projection(String language, AlbumAboutEntry row) => {
    languageField: language,
    'provider': row.provider,
    'albumId': row.albumId,
    'albumDisplay': row.albumDisplay,
    'artistDisplay': row.artistDisplay,
    'kind': row.kind,
    'entryKey': row.entryKey,
    'promptText': row.promptText,
    'titleText': row.titleText,
    'answerText': row.answerText,
    'createdAt': row.createdAt,
    'updatedAt': row.updatedAt,
  };

  static AlbumAboutEntry? entryOf(JsonMap payload) {
    final provider = requiredString(payload, 'provider');
    final albumId = requiredString(payload, 'albumId');
    final albumDisplay = requiredString(payload, 'albumDisplay');
    final artistDisplay = requiredString(payload, 'artistDisplay');
    final kind = requiredString(payload, 'kind');
    final entryKey = requiredString(payload, 'entryKey');
    final prompt = optionalString(payload, 'promptText');
    final title = optionalString(payload, 'titleText');
    final answer = requiredString(payload, 'answerText');
    final createdAt = requiredLong(payload, 'createdAt');
    final updatedAt = requiredLong(payload, 'updatedAt');
    if (provider == null ||
        albumId == null ||
        albumDisplay == null ||
        artistDisplay == null ||
        kind == null ||
        entryKey == null ||
        answer == null ||
        createdAt == null ||
        updatedAt == null) {
      return null;
    }
    if (prompt is! FieldOk<String?> || title is! FieldOk<String?>) return null;
    return AlbumAboutEntry(
      provider: provider,
      albumId: albumId,
      albumDisplay: albumDisplay,
      artistDisplay: artistDisplay,
      kind: kind,
      entryKey: entryKey,
      promptText: prompt.value,
      titleText: title.value,
      answerText: answer,
      createdAt: createdAt,
      updatedAt: updatedAt,
    );
  }

  @override
  Future<List<DomainRow>> readAll(String? localProfileId) async {
    final language = await _language();
    return [
      for (final row in await _store.all())
        DomainRow(key: keyOf(language, row), projection: projection(language, row), rowTs: row.updatedAt),
    ];
  }

  @override
  JsonMap? project(JsonMap payload) {
    final language = requiredString(payload, languageField);
    final entry = entryOf(payload);
    return language == null || entry == null ? null : projection(language, entry);
  }

  @override
  Future<ApplyOutcome> apply(
    String? localProfileId,
    String key,
    JsonMap payload,
    int versionTs,
    String? expectedLocalHash,
  ) async {
    final language = requiredString(payload, languageField);
    final entry = entryOf(payload);
    if (language == null || entry == null || key != keyOf(language, entry)) {
      return const Skipped(SkipReason.unsupported, null);
    }
    final local = await _language();
    // Another language's About stays in Drive for the devices reading it; retried after a switch.
    if (language != local) return const Skipped(SkipReason.changedLocally, null);
    return _store.edit((rows) {
      final existing = rows[entry.ref];
      final currentHash = existing == null ? null : hashOrNull(projection(local, existing));
      if (currentHash != expectedLocalHash) return (Skipped(SkipReason.changedLocally, currentHash), false);
      rows[entry.ref] = entry;
      return (Applied(hashOrNull(projection(local, entry))), true);
    });
  }

  @override
  Future<ApplyOutcome> delete(String? localProfileId, String key, String? expectedLocalHash) async =>
      Skipped(SkipReason.policy, expectedLocalHash);
}
