import 'package:yoin/data/backup/playlist_backup_service.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/source/music_source.dart';

import 'fake_music_source.dart';

/// A target/source for playlist backup and transfer tests: playlists in
/// memory, a title search answered from a catalogue, optional ISRC lookups,
/// and playlist creation (Android FakeTransferTarget).
class FakeTransferSource extends FakeMusicSource implements MusicWriteActions, MusicPlaylistWrites {
  FakeTransferSource({
    this.provider = MediaId.providerSubsonic,
    List<Track> catalogue = const [],
    this.playlists = const {},
    this.failSearch = false,
    this.failPlaylists = false,
    this.createFailure,
  }) : catalogue = [...catalogue];

  @override
  final String provider;
  final List<Track> catalogue;
  Map<MediaId, Playlist> playlists;
  bool failSearch;
  bool failPlaylists;
  Object? createFailure;

  final List<String> searches = [];
  final List<({String name, List<MediaId> tracks})> created = [];

  @override
  Set<Capability> get capabilities => const {Capability.search, Capability.playlistsRead, Capability.playlistAdd};

  @override
  MusicWriteActions get writeActions => this;

  @override
  Future<List<Playlist>> getPlaylists() async {
    if (failPlaylists) throw const SourceNetworkException('offline');
    return playlists.values.toList();
  }

  @override
  Future<Playlist?> getPlaylist(MediaId id) async {
    if (failPlaylists) throw const SourceNetworkException('offline');
    return playlists[id];
  }

  @override
  Future<SearchResults> search(String query, {int trackCount = 20, int albumCount = 20, int artistCount = 20}) async {
    searches.add(query);
    if (failSearch) throw const SourceNetworkException('offline');
    final words = query.toLowerCase().split(' ').where((w) => w.isNotEmpty).toList();
    return SearchResults(
      tracks: [
        for (final t in catalogue)
          if (words.every((w) => '${t.title} ${t.artist}'.toLowerCase().contains(w))) t,
      ],
    );
  }

  @override
  Future<Playlist> createPlaylist(String name, {List<MediaId> tracks = const []}) async {
    if (createFailure != null) throw createFailure!;
    created.add((name: name, tracks: tracks));
    return Playlist(id: MediaId(provider, 'new-${created.length}'), name: name, songCount: tracks.length);
  }

  @override
  bool acceptsTracks(Playlist playlist) => true;

  @override
  Future<void> addTracksToPlaylist(MediaId playlistId, List<MediaId> tracks) async {}

  @override
  Future<void> deletePlaylist(MediaId playlistId) async {}

  @override
  Future<void> removeFromPlaylist(MediaId playlistId, List<int> positions) async {}

  @override
  Future<void> renamePlaylist(MediaId playlistId, String name) async {}

  @override
  Future<void> setPlaylistTracks(MediaId playlistId, List<MediaId> tracks) async {}

  @override
  Future<void> setFavorite(MediaId id, bool favorite) async {}

  @override
  Future<void> setRating(MediaId trackId, int rating) async {}
}

/// [FakeTransferSource] that also knows ISRCs.
class FakeIsrcSource extends FakeTransferSource implements MusicTrackCodes {
  FakeIsrcSource({super.provider, super.catalogue, super.playlists, this.isrcs = const {}, this.byIsrc = const {}});

  /// ISRCs of the tracks this source hands out.
  final Map<MediaId, String> isrcs;

  /// What an ISRC lookup answers.
  final Map<String, List<Track>> byIsrc;
  final List<List<String>> isrcLookups = [];

  @override
  MusicLibrary get library => this;

  @override
  String? isrcOf(MediaId trackId) => isrcs[trackId];

  @override
  Future<Map<String, List<Track>>> tracksByIsrc(List<String> isrcs) async {
    isrcLookups.add(isrcs);
    return {
      for (final i in isrcs)
        if (byIsrc[i] != null) i: byIsrc[i]!,
    };
  }
}

/// Profiles for [PlaylistBackupService] tests: sources by profile id, an active one, account keys.
class FakeBackupProfiles implements BackupProfiles {
  FakeBackupProfiles({required this.all, this.activeId, this.sources = const {}, this.accounts = const {}});

  List<Profile> all;
  String? activeId;
  Map<String, FakeTransferSource> sources;
  Map<String, String> accounts;
  final List<String> opened = [];

  @override
  Future<List<Profile>> profiles() async => all;

  @override
  Future<Profile?> activeProfile() async => all.where((p) => p.id == activeId).firstOrNull;

  @override
  Future<T?> withSource<T>(String profileId, Future<T> Function(MusicSource source) block) async {
    final source = sources[profileId];
    if (source == null) return null;
    opened.add(profileId);
    return block(source);
  }

  @override
  Future<String?> accountKey(Profile profile) async => accounts[profile.id];
}

Track song(
  String id,
  String title,
  String artist, {
  int? durationSec,
  String? album,
  String provider = MediaId.providerSubsonic,
}) => Track(id: MediaId(provider, id), title: title, artist: artist, durationSec: durationSec, album: album);
