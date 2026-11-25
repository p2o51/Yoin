import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/about/album_about_store.dart';
import 'package:yoin/data/sync/adapters/album_about_sync_adapter.dart';
import 'package:yoin/data/sync/adapters/sync_projections.dart';
import 'package:yoin/data/sync/cloud_sync_providers.dart';
import 'package:yoin/data/sync/engine/sync_engine.dart';
import 'package:yoin/data/sync/sync_contracts.dart';
import 'package:yoin/platform/storage/stores.dart';

AlbumAboutEntry _row(String kind, String key, String answer, {int updatedAt = 20}) => AlbumAboutEntry(
  provider: 'subsonic',
  albumId: 'al1',
  albumDisplay: 'Blue',
  artistDisplay: 'Joni Mitchell',
  kind: kind,
  entryKey: key,
  promptText: kind == 'ask' ? 'Why?' : null,
  titleText: kind == 'ask' ? 'Heading' : null,
  answerText: answer,
  createdAt: 10,
  updatedAt: updatedAt,
);

/// Android AlbumAboutSyncAdapter: `album_about`, global, artifacts, every record stamped with the AI
/// language and applied only in that language, never a delete, last write wins; the wire shape is Android's.
void main() {
  late AlbumAboutStore store;
  late String language;
  late AlbumAboutSyncAdapter adapter;

  setUp(() {
    store = AlbumAboutStore(InMemoryStore());
    language = 'English';
    adapter = AlbumAboutSyncAdapter(store: store, language: () async => language);
  });

  test('should_matchAndroidsContract_when_declared', () {
    expect(adapter.kind, 'album_about');
    expect(adapter.kindVersion, 1);
    expect(adapter.fileClass, FileClass.artifacts);
    expect(adapter.perAccount, isFalse);
    expect(adapter.propagatesDeletes, isFalse);
    expect(adapter.conflictPolicy, ConflictPolicy.lww);
    expect(SyncEngine.knownArtifactKinds, contains(SyncKinds.albumAbout));
  });

  test('should_writeAndroidsWireShape_when_projected', () {
    final row = _row('canonical', 'credits', '{"producers":["Joni Mitchell"]}');
    expect(AlbumAboutSyncAdapter.projection('English', row), {
      'targetLanguage': 'English',
      'provider': 'subsonic',
      'albumId': 'al1',
      'albumDisplay': 'Blue',
      'artistDisplay': 'Joni Mitchell',
      'kind': 'canonical',
      'entryKey': 'credits',
      'promptText': null,
      'titleText': null,
      'answerText': '{"producers":["Joni Mitchell"]}',
      'createdAt': 10,
      'updatedAt': 20,
    });
    expect(
      AlbumAboutSyncAdapter.keyOf('English', row),
      ['English', 'subsonic', 'al1', 'canonical', 'credits'].join(SyncFormat.keySeparator),
    );
  });

  test('should_stampEveryRowWithTheLanguage_when_read', () async {
    await store.upsertAll([_row('canonical', 'blurb', 'A record.'), _row('ask', 'why?', 'Because.')]);
    final rows = await adapter.readAll(null);
    expect(rows, hasLength(2));
    for (final r in rows) {
      expect(r.projection[AlbumAboutSyncAdapter.languageField], 'English');
      expect(r.key.split(SyncFormat.keySeparator).first, 'English');
      expect(r.rowTs, 20);
    }
  });

  test('should_writeRow_when_payloadInDevicesLanguageApplied', () async {
    final row = _row('ask', 'why?', 'Because.');
    final payload = AlbumAboutSyncAdapter.projection('English', row);
    final outcome = await adapter.apply(null, AlbumAboutSyncAdapter.keyOf('English', row), payload, 20, null);
    expect(outcome, isA<Applied>());
    expect(await store.get(row.ref), row);
    expect(adapter.project(payload), payload);
  });

  test('should_waitForASwitch_when_payloadInAnotherLanguage', () async {
    final row = _row('canonical', 'blurb', 'Un disque.');
    final payload = AlbumAboutSyncAdapter.projection('French', row);
    final key = AlbumAboutSyncAdapter.keyOf('French', row);
    final outcome = await adapter.apply(null, key, payload, 20, null);
    expect((outcome as Skipped).reason, SkipReason.changedLocally);
    expect(await store.all(), isEmpty);
    language = 'French';
    expect(await adapter.apply(null, key, payload, 20, null), isA<Applied>());
  });

  test('should_skipChangedLocally_when_rowChangedSinceCapture', () async {
    final row = _row('canonical', 'blurb', 'Local.');
    await store.upsert(row);
    final remote = _row('canonical', 'blurb', 'Remote.', updatedAt: 30);
    final payload = AlbumAboutSyncAdapter.projection('English', remote);
    final key = AlbumAboutSyncAdapter.keyOf('English', remote);
    final stale = await adapter.apply(null, key, payload, 30, 'not-the-hash');
    expect(stale, isA<Skipped>());
    expect((await store.get(row.ref))!.answerText, 'Local.');
    final current = hashOrNull(AlbumAboutSyncAdapter.projection('English', row));
    expect(await adapter.apply(null, key, payload, 30, current), isA<Applied>());
    expect((await store.get(row.ref))!.answerText, 'Remote.');
  });

  test('should_rejectPayload_when_keyOrFieldsDontMatch', () async {
    final row = _row('canonical', 'blurb', 'A record.');
    final payload = AlbumAboutSyncAdapter.projection('English', row);
    final wrongKey = await adapter.apply(null, 'English\u001Fother', payload, 20, null);
    expect((wrongKey as Skipped).reason, SkipReason.unsupported);
    final missing = Map<String, Object?>.of(payload)..remove('answerText');
    final broken = await adapter.apply(null, AlbumAboutSyncAdapter.keyOf('English', row), missing, 20, null);
    expect((broken as Skipped).reason, SkipReason.unsupported);
    expect(adapter.project(missing), isNull);
  });

  test('should_neverDelete_when_remoteDeletes', () async {
    final row = _row('canonical', 'blurb', 'Kept.');
    await store.upsert(row);
    final outcome = await adapter.delete(null, AlbumAboutSyncAdapter.keyOf('English', row), 'h');
    expect((outcome as Skipped).reason, SkipReason.policy);
    expect(await store.get(row.ref), row);
  });

  test('should_registerTheAdapterAndWatchItsKey_when_wired', () {
    expect(isSyncedKey(AlbumAboutStore.key), isTrue);
  });
}
