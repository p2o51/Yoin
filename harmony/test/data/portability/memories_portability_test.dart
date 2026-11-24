// Export → zip → import and Transfer Memories end to end on HarmonyOS stores,
// with Cloud sync never turned on (no sync store): the case current HarmonyOS
// builds ship in.
import 'dart:convert';
import 'dart:typed_data';

import 'package:archive/archive.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/memory/track_album_links.dart';
import 'package:yoin/data/model/media_id.dart';
import 'package:yoin/data/portability/backup_files.dart';
import 'package:yoin/data/portability/backup_format.dart';
import 'package:yoin/data/portability/backup_import.dart';
import 'package:yoin/data/portability/backup_ports.dart';
import 'package:yoin/data/portability/memories/memory_matching.dart';
import 'package:yoin/data/portability/memories/transfer_models.dart';
import 'package:yoin/data/portability/memories_portability.dart';
import 'package:yoin/data/sync/sync_database.dart';
import 'package:yoin/playback/local_ratings.dart';

import 'portability_device.dart';

const _alice = PortabilityProfile(
  id: 'p1',
  provider: 'subsonic',
  displayName: 'Alice',
  createdAt: 1000,
  subsonicServerUrl: 'https://music.example.com',
  subsonicUsername: 'alice',
);

const _bob = PortabilityProfile(
  id: 'p2',
  provider: 'subsonic',
  displayName: 'Bob',
  createdAt: 2000,
  subsonicServerUrl: 'https://other.example.com',
  subsonicUsername: 'bob',
);

MemoriesPortability _portability(PortabilityDevice device, {int now = 1795400000000}) => MemoriesPortability(
  stores: device.stores,
  profiles: () async => device.profiles,
  adapters: (_) => [],
  run: <T>(Future<T> Function(PortabilityContext) body) =>
      body(const PortabilityContext(replica: null, deviceId: null, deviceName: 'Mate 70', syncOn: false)),
  appVersionName: '0.6.5',
  appVersionCode: 1,
  zone: BackupZone.utc,
  clock: () => now,
);

Future<MemoriesPortability> _withAdapters(PortabilityDevice device) async {
  final adapters = await device.adapters();
  final base = _portability(device);
  return MemoriesPortability(
    stores: base.stores,
    profiles: base.profiles,
    adapters: (_) => adapters,
    run: base.run,
    appVersionName: base.appVersionName,
    appVersionCode: base.appVersionCode,
    zone: BackupZone.utc,
    clock: () => 1795400000000,
  );
}

Future<void> _seedAlice(PortabilityDevice device) async {
  final memory = device.stores.memory('p1');
  await memory.putSongNote(
    SongNote(
      id: 'n1',
      trackId: MediaId('subsonic', 't1'),
      content: 'rain [@Rain](yoin:album/subsonic:al1)',
      createdAt: 2000,
      updatedAt: 2000,
      title: 'Song One',
      artist: 'Artist',
      positionMs: 61000,
    ),
  );
  await memory.syncEdit((rows) {
    final id = MediaId('subsonic', 'al1');
    rows.albumRatings[id] = AlbumRatingRow(
      albumId: id,
      rating: 8,
      review: 'Great.',
      updatedAt: 2300,
      reviewUpdatedAt: 2300,
    );
  });
  await device.stores.ratings.edit('p1', (rows) {
    rows[MediaId('subsonic', 't1')] = const LocalRating(
      profileId: 'p1',
      songId: 't1',
      provider: 'subsonic',
      rating: 9,
      serverRating: 5,
      updatedAt: 2100,
    );
  });
  await device.stores.links('p1').putAll([
    TrackAlbumLink(trackId: MediaId('subsonic', 't1'), albumId: MediaId('subsonic', 'al1'), updatedAt: 2700),
  ]);
}

void main() {
  test('should_restoreEveryRow_when_importingItsOwnExportOnAFreshDevice', () async {
    final phone = PortabilityDevice(profiles: [_alice]);
    await _seedAlice(phone);
    final exported = await (await _withAdapters(phone)).export(language: 'zh-Hans');
    expect(exported.fileName, 'yoin-backup-2026-11-23.zip');
    expect(exported.summary.manifest.source.location, BackupFormat.locationThisDevice);
    // A real zip, in Kotlin's entry order.
    final names = [for (final f in ZipDecoder().decodeBytes(exported.bytes).files) f.name];
    expect(names.take(2), [BackupFormat.manifestPath, BackupFormat.snapshotPath]);
    expect(names.skip(2), everyElement(startsWith(BackupFormat.readableDir)));

    final tablet = PortabilityDevice(profiles: [_alice]);
    final portability = await _withAdapters(tablet);
    final backup = portability.read(exported.bytes);
    final plan = await portability.planImport(backup);
    expect(plan.accounts.single.sameAccount, isTrue);
    expect(plan.accounts.single.target, const ImportIntoProfile('p1'));
    final result = await portability.applyImport(plan, const {}, importGlobal: true);
    expect(result.changed, isTrue);
    expect(await tablet.domain(), await phone.domain());

    final again = await portability.applyImport(await portability.planImport(backup), const {}, importGlobal: true);
    expect(again.changed, isFalse, reason: 'importing the same file twice changes nothing');
  });

  test('should_reportEachErrorKind_when_aFileIsNotAReadableBackup', () async {
    final phone = PortabilityDevice(profiles: [_alice]);
    await _seedAlice(phone);
    final portability = await _withAdapters(phone);
    final exported = await portability.export(language: 'en');

    BackupErrorKind kindOf(Uint8List bytes) {
      try {
        portability.read(bytes);
        fail('read');
      } on BackupReadException catch (e) {
        return e.kind;
      }
    }

    Uint8List rezip(String path, List<int> Function(List<int>) edit) {
      final archive = Archive();
      for (final f in ZipDecoder().decodeBytes(exported.bytes).files) {
        final bytes = f.name == path ? edit(f.content as List<int>) : f.content as List<int>;
        archive.addFile(ArchiveFile(f.name, bytes.length, bytes));
      }
      return Uint8List.fromList(ZipEncoder().encode(archive)!);
    }

    expect(kindOf(Uint8List.fromList(utf8.encode('not a zip'))), BackupErrorKind.notABackup);
    expect(
      kindOf(rezip(BackupFormat.snapshotPath, (b) => [...b.sublist(0, b.length - 2), 0x20, 0x7d])),
      BackupErrorKind.checksumMismatch,
    );
    expect(
      kindOf(
        rezip(
          BackupFormat.manifestPath,
          (b) => utf8.encode(utf8.decode(b).replaceFirst('"formatVersion": 1', '"formatVersion": 2')),
        ),
      ),
      BackupErrorKind.tooNew,
    );
    expect(
      kindOf(rezip(BackupFormat.manifestPath, (b) => utf8.encode(utf8.decode(b).replaceFirst('yoin-backup', 'other')))),
      BackupErrorKind.notABackup,
    );
  });

  test('should_copyOnceAndSkipOnRerun_when_transferringFromABackupAccountToAnotherServer', () async {
    final phone = PortabilityDevice(profiles: [_alice]);
    await _seedAlice(phone);
    final backup = (await _withAdapters(phone)).read((await (await _withAdapters(phone)).export(language: 'en')).bytes);

    final tablet = PortabilityDevice(profiles: [_bob]);
    final portability = await _withAdapters(tablet);
    final source = portability.transferSourcesOf(backup).firstWhere((s) => s.account.provider == 'subsonic');
    expect(source.kind, TransferSourceKind.backupAccount);
    final items = await portability.memoryItems(source, backup: backup);
    final target = await portability.transferAccountOf('p2');
    final plan = await portability.planTransfer(source.account, items, target);
    // Another server: every song and album needs a counterpart.
    expect([for (final t in plan.tracksToMatch) t.source], [const SourceRef('subsonic', 't1')]);
    expect(plan.tracksToMatch.single.title, 'Song One');
    final tracks = [
      TrackMatch(
        source: const SourceRef('subsonic', 't1'),
        sourceTrack: null,
        status: MatchStatus.matched,
      ).pick(null).toPick(),
    ];
    expect(tracks.single.chosen, isNull);
    final picks = [
      TrackPick(
        const SourceRef('subsonic', 't1'),
        MatchStatus.picked,
        const TrackTarget(
          provider: 'subsonic',
          id: 'b1',
          title: 'Song One',
          artist: 'Artist',
          albumProvider: 'subsonic',
          albumId: 'bal1',
        ),
      ),
    ];
    final albums = [
      AlbumPick(
        const SourceRef('subsonic', 'al1'),
        MatchStatus.matched,
        const AlbumTarget(provider: 'subsonic', id: 'bal1', name: 'Rain'),
      ),
    ];
    final result = await portability.applyTransfer(plan, picks, albums);
    expect(result.copied.songNotes, 1);
    expect(result.copied.trackRatings, 1);
    expect(result.copied.albumRatings, 1);
    final notes = (await tablet.stores.memory('p2').syncRead()).songNotes;
    expect(notes.single.trackId, MediaId('subsonic', 'b1'));
    expect(notes.single.content, 'rain [@Rain](yoin:album/subsonic:bal1)');
    expect((await tablet.stores.ratings.read('p2'))[MediaId('subsonic', 'b1')]?.rating, 9);

    // A rerun skips what was copied and reuses the picks (the ledger lives on the key-value store without sync).
    final rerun = await portability.planTransfer(source.account, items, target);
    expect(rerun.previouslyCopied, greaterThan(0));
    expect(rerun.tracksToMatch, isEmpty);
    final second = await portability.applyTransfer(rerun, rerun.reusedTracks, rerun.reusedAlbums);
    expect(second.copied.total, 0);
  });

  test('should_carrySpotifyAccounts_when_exportingAReplicaThatHasThem', () async {
    final device = PortabilityDevice(profiles: [_alice]);
    final db = SyncDatabase(MemorySyncDbFile());
    await db.open();
    final adapters = await device.adapters();
    final portability = MemoriesPortability(
      stores: device.stores,
      profiles: () async => device.profiles,
      adapters: (_) => adapters,
      run: <T>(Future<T> Function(PortabilityContext) body) =>
          body(PortabilityContext(replica: db, deviceId: 'dev', deviceName: 'Mate 70', syncOn: true)),
      appVersionName: '0.6.5',
      appVersionCode: 1,
      zone: BackupZone.utc,
      clock: () => 5000,
    );
    db.upsertRecord(
      SyncRecordEntity(
        kind: 'account',
        scope: '',
        key: 's-carol',
        kindVersion: 1,
        ts: 1,
        device: 'phone',
        deleted: false,
        payload: '{"provider":"spotify","displayName":"Carol"}',
      ),
    );
    db.upsertRecord(
      SyncRecordEntity(
        kind: 'song_note',
        scope: 's-carol',
        key: 'c1',
        kindVersion: 1,
        ts: 1,
        device: 'phone',
        deleted: false,
        payload:
            '{"trackId":"sp1","provider":"spotify","content":"cloud","createdAt":1,"updatedAt":1,"title":"T","artist":"A","positionMs":null}',
      ),
    );
    final exported = await portability.export(language: 'en');
    expect(exported.summary.manifest.source.location, BackupFormat.locationGoogleDrive);
    final carol = exported.summary.manifest.accounts.firstWhere((a) => a.scope == 's-carol');
    expect(
      [carol.provider, carol.onDevice, carol.counts],
      [
        'spotify',
        false,
        {'song_note': 1},
      ],
    );
    // Importing it: no Spotify account here, so it stays in the file or goes cloud-only, never dropped silently.
    final plan = await portability.planImport(portability.read(exported.bytes));
    final spotify = plan.accounts.firstWhere((a) => a.account.scope == 's-carol');
    expect(spotify.options, isEmpty);
    expect(spotify.target, ImportTarget.cloudOnly);
    expect((await portability.transferSources()).map((s) => s.account.provider), contains('spotify'));
  });
}
