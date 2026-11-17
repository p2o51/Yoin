import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/about/song_about_store.dart';
import 'package:yoin/data/sync/adapters/song_about_sync_adapter.dart';
import 'package:yoin/data/sync/adapters/sync_projections.dart';
import 'package:yoin/data/sync/sync_contracts.dart';
import 'package:yoin/platform/storage/stores.dart';

SongAboutEntry _row(String kind, String key, String answer) => SongAboutEntry(
  titleKey: 'blue',
  artistKey: 'joni',
  albumKey: 'blue',
  titleDisplay: 'Blue',
  artistDisplay: 'Joni',
  albumDisplay: 'Blue',
  kind: kind,
  entryKey: key,
  promptText: kind == SongAboutKinds.ask ? 'Why?' : null,
  titleText: kind == SongAboutKinds.canonical ? null : 'Heading',
  answerText: answer,
  createdAt: 10,
  updatedAt: 20,
);

/// Android SongAboutSyncAdapter: the AI's rows stamped with the answer
/// language, the listener's own with "*"; never a delete.
void main() {
  late SongAboutStore store;
  late String language;
  late SongAboutSyncAdapter adapter;

  setUp(() {
    store = SongAboutStore(InMemoryStore());
    language = 'English';
    adapter = SongAboutSyncAdapter(store: store, language: () async => language);
  });

  test('should_stampAiRowsWithLanguageAndOwnWithStar_when_read', () async {
    await store.upsertAll([_row(SongAboutKinds.ask, 'why', 'Because.'), _row(SongAboutKinds.own, 'mine', 'Mine.')]);
    final rows = await adapter.readAll(null);
    final byKind = {for (final r in rows) r.projection['kind']: r};
    expect(byKind[SongAboutKinds.ask]!.projection[SongAboutSyncAdapter.languageField], 'English');
    expect(byKind[SongAboutKinds.ask]!.key.split(SyncFormat.keySeparator).first, 'English');
    expect(byKind[SongAboutKinds.own]!.projection[SongAboutSyncAdapter.languageField], '*');
    expect(byKind[SongAboutKinds.ask]!.rowTs, 20);
    expect(adapter.fileClass, FileClass.artifacts);
    expect(adapter.perAccount, isFalse);
    expect(adapter.propagatesDeletes, isFalse);
  });

  test('should_writeRow_when_payloadInDevicesLanguageApplied', () async {
    final row = _row(SongAboutKinds.canonical, 'lyricist', 'Joni Mitchell');
    final payload = SongAboutSyncAdapter.projection('English', row);
    final outcome = await adapter.apply(null, SongAboutSyncAdapter.keyOf('English', row), payload, 20, null);
    expect(outcome, isA<Applied>());
    expect((await store.get(row.ref))!.answerText, 'Joni Mitchell');
    expect(adapter.project(payload), payload);
  });

  test('should_waitForASwitch_when_payloadInAnotherLanguage', () async {
    final row = _row(SongAboutKinds.ask, 'why', 'Parce que.');
    final payload = SongAboutSyncAdapter.projection('French', row);
    final outcome = await adapter.apply(null, SongAboutSyncAdapter.keyOf('French', row), payload, 20, null);
    expect(outcome, isA<Skipped>());
    expect((outcome as Skipped).reason, SkipReason.changedLocally);
    expect(await store.all(), isEmpty);
    // After switching to French, the same record applies.
    language = 'French';
    expect(await adapter.apply(null, SongAboutSyncAdapter.keyOf('French', row), payload, 20, null), isA<Applied>());
  });

  test('should_applyOwnEntry_when_anyLanguage', () async {
    final row = _row(SongAboutKinds.own, 'mine', 'Mine.');
    final payload = SongAboutSyncAdapter.projection('*', row);
    language = 'Japanese';
    expect(await adapter.apply(null, SongAboutSyncAdapter.keyOf('*', row), payload, 20, null), isA<Applied>());
  });

  test('should_skipChangedLocally_when_rowChangedSinceCapture', () async {
    final row = _row(SongAboutKinds.ask, 'why', 'Local.');
    await store.upsert(row);
    final remote = _row(SongAboutKinds.ask, 'why', 'Remote.');
    final outcome = await adapter.apply(
      null,
      SongAboutSyncAdapter.keyOf('English', remote),
      SongAboutSyncAdapter.projection('English', remote),
      30,
      null,
    );
    expect(outcome, isA<Skipped>());
    expect((outcome as Skipped).localHash, hashOrNull(SongAboutSyncAdapter.projection('English', row)));
    expect((await store.get(row.ref))!.answerText, 'Local.');
  });

  test('should_neverDelete_when_tombstoneArrives', () async {
    final outcome = await adapter.delete(null, 'k', 'h');
    expect(outcome, isA<Skipped>());
    expect((outcome as Skipped).reason, SkipReason.policy);
  });

  test('should_rejectPayload_when_fieldMissing', () {
    final payload = SongAboutSyncAdapter.projection('English', _row(SongAboutKinds.ask, 'why', 'x'))
      ..remove('answerText');
    expect(adapter.project(payload), isNull);
  });
}
