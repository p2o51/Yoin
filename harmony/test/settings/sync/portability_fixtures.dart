// Shared setup for the export / import / Transfer Memories UI tests: real
// MemoriesPortability over in-memory HarmonyOS stores (sync never ran), a fake
// document picker and a fake catalogue for matching.
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/memory/track_album_links.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/portability/backup_format.dart';
import 'package:yoin/data/portability/backup_ports.dart';
import 'package:yoin/data/portability/memories/memory_matching.dart';
import 'package:yoin/data/portability/memories/transfer_models.dart';
import 'package:yoin/data/portability/memories_portability.dart';
import 'package:yoin/data/portability/portability_providers.dart';
import 'package:yoin/data/sync/sync_database.dart';
import 'package:yoin/offline/offline_providers.dart';
import 'package:yoin/platform/share/document_files.dart';
import 'package:yoin/platform/storage/app_files.dart';
import 'package:yoin/playback/local_ratings.dart';
import 'package:yoin/settings/sync/cloud_sync_view_model.dart';
import 'package:yoin/settings/sync/transfer_view_model.dart';
import 'package:yoin/shell/app_messages.dart';

import '../../data/portability/portability_device.dart';
import 'cloud_sync_screen_test.dart' show FakeCloudSync;

const alice = PortabilityProfile(
  id: 'p1',
  provider: 'subsonic',
  displayName: 'Alice',
  createdAt: 1000,
  subsonicServerUrl: 'https://music.example.com',
  subsonicUsername: 'alice',
);

const bob = PortabilityProfile(
  id: 'p2',
  provider: 'subsonic',
  displayName: 'Bob',
  createdAt: 2000,
  subsonicServerUrl: 'https://other.example.com',
  subsonicUsername: 'bob',
);

/// [device]'s portability service, its job run with no sync store (or [replica], sync on, when given).
Future<MemoriesPortability> portabilityOf(PortabilityDevice device, {SyncDatabase? replica}) async {
  final adapters = await device.adapters();
  return MemoriesPortability(
    stores: device.stores,
    profiles: () async => device.profiles,
    adapters: (_) => adapters,
    run: <T>(Future<T> Function(PortabilityContext) body) => body(
      PortabilityContext(
        replica: replica,
        deviceId: replica == null ? null : 'dev',
        deviceName: 'Mate 70',
        syncOn: replica != null,
      ),
    ),
    appVersionName: '0.6.5',
    appVersionCode: 1,
    zone: BackupZone.utc,
    clock: () => 1795400000000,
  );
}

/// Alice's Memories: a note and a rating on "Song One", a rated and reviewed album.
Future<void> seedAlice(PortabilityDevice device) async {
  final memory = device.stores.memory('p1');
  await memory.putSongNote(
    SongNote(
      id: 'n1',
      trackId: MediaId('subsonic', 't1'),
      content: 'rain',
      createdAt: 2000,
      updatedAt: 2000,
      title: 'Song One',
      artist: 'Artist',
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

/// The system's document picker: keeps what it is asked to (when [save]), picks [picked].
final class FakeDocumentFiles implements DocumentFiles {
  FakeDocumentFiles({this.save = true, this.picked});

  bool save;
  Uint8List? picked;
  final saved = <String, Uint8List>{};

  @override
  Future<bool> saveFile(String path, {required String name}) async {
    if (!save) return false;
    saved[name] = await File(path).readAsBytes();
    return true;
  }

  @override
  Future<String?> pickFile() async {
    final bytes = picked;
    if (bytes == null) return null;
    final dir = await Directory.systemTemp.createTemp('yoin_pick');
    final file = File('${dir.path}/picked.zip');
    await file.writeAsBytes(bytes);
    return file.path;
  }
}

/// A target catalogue whose search answers every query with [tracks].
class FakeCatalog implements MemoryCatalog {
  FakeCatalog(this.tracks, {this.albums = const []});

  final List<Track> tracks;
  final List<Album> albums;
  final queries = <String>[];

  @override
  Future<Track?> describeSourceTrack(SourceRef ref) async => null;

  @override
  String? isrcOf(Track track) => null;

  @override
  Future<Map<String, List<Track>>> tracksByIsrc(List<String> isrcs) async => const {};

  @override
  Future<List<Track>> searchTracks(String query) async {
    queries.add(query);
    return tracks;
  }

  @override
  Future<List<Album>> searchAlbums(String query) async => albums;
}

/// Overrides for the flows: the service, Cloud sync, the picker, a cache folder, messages and the catalogue.
List<Override> portabilityOverrides({
  required MemoriesPortability portability,
  required FakeCloudSync sync,
  required FakeDocumentFiles files,
  required Directory cache,
  List<String>? messages,
  MemoryCatalog? catalog,
  List<String>? removed,
}) => [
  memoriesPortabilityProvider.overrideWithValue(portability),
  cloudSyncControllerProvider.overrideWithValue(sync),
  documentFilesProvider.overrideWithValue(files),
  appFilesHostProvider.overrideWithValue(DirectoryAppFilesHost(cache)),
  appMessageSinkProvider.overrideWithValue((m) => messages?.add(m)),
  memoryCatalogOpenerProvider.overrideWithValue(
    (target, {sourceProfileId}) async => catalog == null ? null : (catalog: catalog, dispose: () {}),
  ),
  transferRemoveProfileProvider.overrideWithValue((id) async {
    removed?.add(id);
    return true;
  }),
];
