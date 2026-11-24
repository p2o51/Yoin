/// Export / import / Transfer Memories in the dependency graph.
library;

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/providers.dart';
import '../../settings/licenses.dart' show yoinVersionCode, yoinVersionName;
import '../model/model.dart';
import '../profile/profile.dart';
import '../source/music_source.dart';
import '../sync/cloud_sync_providers.dart';
import 'backup_ports.dart';
import 'harmony_stores.dart';
import 'memories/memory_matching.dart';
import 'memories/transfer_models.dart';
import 'memories_portability.dart';

final memoriesPortabilityProvider = Provider<MemoriesPortability>((ref) {
  final repo = ref.watch(profileRepositoryProvider);
  Future<List<Profile>> profiles() => repo.profiles();
  final manager = ref.watch(cloudSyncProvider);
  return MemoriesPortability(
    stores: HarmonyMemoryStores(
      store: ref.watch(keyValueStoreProvider),
      ratings: ref.watch(syncTrackRatingAccessProvider),
      profileIds: () async => [for (final p in await profiles()) p.id],
    ),
    profiles: () async => [
      for (final p in await profiles()) await portabilityProfileOf(p, () => repo.credentials(p.id)),
    ],
    adapters: ref.watch(syncAdaptersProvider),
    run: <T>(Future<T> Function(PortabilityContext) body) => manager.runPortability(
      (replica, deviceId, deviceName, syncOn) =>
          body(PortabilityContext(replica: replica, deviceId: deviceId, deviceName: deviceName, syncOn: syncOn)),
    ),
    appVersionName: yoinVersionName,
    appVersionCode: yoinVersionCode,
  );
});

/// [profile] as the core sees it; a Subsonic account's server and user come
/// from its credentials (its fingerprint scope), unreadable ones leave it device-local.
Future<PortabilityProfile> portabilityProfileOf(
  Profile profile,
  Future<ProfileCredentials?> Function() credentials,
) async {
  ProfileCredentials? read;
  try {
    read = await credentials();
  } catch (_) {
    read = null;
  }
  return PortabilityProfile(
    id: profile.id,
    provider: profile.provider,
    displayName: profile.displayName,
    createdAt: profile.createdAt,
    subsonicServerUrl: read is SubsonicCredentials ? read.baseUrl : null,
    subsonicUsername: read is SubsonicCredentials ? read.username : null,
  );
}

/// The target account's catalogue for matching, built for the transfer and
/// disposed after it. [source] describes source songs when the source is an
/// account on this device.
final class SourceMemoryCatalog implements MemoryCatalog {
  SourceMemoryCatalog(this.target, {this.source});

  final MusicSource target;
  final MusicSource? source;

  /// Opens [targetProfileId]'s source (and [sourceProfileId]'s, when given); null without credentials.
  static Future<SourceMemoryCatalog?> open(Ref ref, String targetProfileId, {String? sourceProfileId}) async {
    final repo = ref.read(profileRepositoryProvider);
    final factory = ref.read(musicSourceFactoryProvider);
    final targetCredentials = await repo.credentials(targetProfileId);
    if (targetCredentials == null) return null;
    final sourceCredentials = sourceProfileId == null ? null : await repo.credentials(sourceProfileId);
    return SourceMemoryCatalog(
      factory(targetCredentials),
      source: sourceCredentials == null ? null : factory(sourceCredentials),
    );
  }

  void dispose() {
    target.dispose();
    source?.dispose();
  }

  @override
  Future<Track?> describeSourceTrack(SourceRef ref) async {
    final lookup = source?.trackLookup;
    if (lookup == null || ref.provider.isEmpty || ref.provider.contains(':') || ref.id.isEmpty) return null;
    return lookup.getTrack(MediaId(ref.provider, ref.id));
  }

  @override
  String? isrcOf(Track track) => source?.trackCodes?.isrcOf(track.id);

  @override
  Future<Map<String, List<Track>>> tracksByIsrc(List<String> isrcs) async =>
      await target.trackCodes?.tracksByIsrc(isrcs) ?? const {};

  @override
  Future<List<Track>> searchTracks(String query) async =>
      (await target.library.search(query, albumCount: 0, artistCount: 0)).tracks;

  @override
  Future<List<Album>> searchAlbums(String query) async =>
      (await target.library.search(query, trackCount: 0, artistCount: 0)).albums;
}
