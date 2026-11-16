/// The About table <-> [SyncKinds.songAbout] (Android
/// adapters/SongAboutSyncAdapter.kt): the AI's About rows and the questions
/// asked with their answers (global, keyed by song metadata like the table),
/// and the listener's own entries.
///
/// The rows carry no language, and switching the answer language wipes the
/// AI's rows. So every record is stamped with the language it was written in
/// (the device's current one) and that language is part of the key; a device
/// only applies records in its own language, retrying the rest after a
/// switch. Deletes never propagate: the wipe is local housekeeping. The
/// listener's own entries are theirs, not the AI's: they are stamped
/// [anyLanguage] and every device applies them.
library;

import '../../about/song_about_store.dart';
import '../sync_contracts.dart';
import 'sync_projections.dart';

class SongAboutSyncAdapter extends SyncAdapter {
  SongAboutSyncAdapter({required SongAboutStore store, required Future<String> Function() language})
    : _store = store,
      _language = language;

  final SongAboutStore _store;

  /// The answer language now (normalised).
  final Future<String> Function() _language;

  static const languageField = 'targetLanguage';

  /// The language stamp of the listener's own entries.
  static const anyLanguage = '*';

  @override
  String get kind => SyncKinds.songAbout;
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

  /// The language a row is filed under: the AI's rows the device's, the listener's own none.
  static String stampOf(SongAboutEntry row, String language) => row.isOwn ? anyLanguage : language;

  static String keyOf(String language, SongAboutEntry row) =>
      SyncFormat.compositeKey([language, row.titleKey, row.artistKey, row.albumKey, row.kind, row.entryKey]);

  static JsonMap projection(String language, SongAboutEntry row) => {
    languageField: language,
    'titleKey': row.titleKey,
    'artistKey': row.artistKey,
    'albumKey': row.albumKey,
    'titleDisplay': row.titleDisplay,
    'artistDisplay': row.artistDisplay,
    'albumDisplay': row.albumDisplay,
    'kind': row.kind,
    'entryKey': row.entryKey,
    'promptText': row.promptText,
    'titleText': row.titleText,
    'answerText': row.answerText,
    'createdAt': row.createdAt,
    'updatedAt': row.updatedAt,
  };

  static SongAboutEntry? entryOf(JsonMap payload) {
    final titleKey = requiredString(payload, 'titleKey');
    final artistKey = requiredString(payload, 'artistKey');
    final albumKey = requiredString(payload, 'albumKey');
    final titleDisplay = requiredString(payload, 'titleDisplay');
    final artistDisplay = requiredString(payload, 'artistDisplay');
    final albumDisplay = requiredString(payload, 'albumDisplay');
    final kind = requiredString(payload, 'kind');
    final entryKey = requiredString(payload, 'entryKey');
    final prompt = optionalString(payload, 'promptText');
    final title = optionalString(payload, 'titleText');
    final answer = requiredString(payload, 'answerText');
    final createdAt = requiredLong(payload, 'createdAt');
    final updatedAt = requiredLong(payload, 'updatedAt');
    if (titleKey == null ||
        artistKey == null ||
        albumKey == null ||
        titleDisplay == null ||
        artistDisplay == null ||
        albumDisplay == null ||
        kind == null ||
        entryKey == null ||
        answer == null ||
        createdAt == null ||
        updatedAt == null) {
      return null;
    }
    if (prompt is! FieldOk<String?> || title is! FieldOk<String?>) return null;
    return SongAboutEntry(
      titleKey: titleKey,
      artistKey: artistKey,
      albumKey: albumKey,
      titleDisplay: titleDisplay,
      artistDisplay: artistDisplay,
      albumDisplay: albumDisplay,
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
        DomainRow(
          key: keyOf(stampOf(row, language), row),
          projection: projection(stampOf(row, language), row),
          rowTs: row.updatedAt,
        ),
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
    final local = stampOf(entry, await _language());
    // Another language's answers stay in Drive for devices reading it: "not now"
    // (changedLocally, not policy) so the engine retries them after a switch.
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
