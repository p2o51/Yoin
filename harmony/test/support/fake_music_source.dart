import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/source/music_source.dart';

/// A MusicSource for view-model tests: albums in memory, everything else unsupported.
class FakeMusicSource implements MusicSource, MusicLibrary, MusicPlayback {
  FakeMusicSource({this.albums = const [], this.failWith});

  final List<Album> albums;
  Object? failWith;
  final List<({AlbumListType type, int size, int offset})> albumListCalls = [];
  bool disposed = false;

  static List<Album> numberedAlbums(int count) => [
        for (var i = 0; i < count; i++) Album(id: MediaId.subsonic('al-$i'), name: 'Album $i'),
      ];

  @override
  String get provider => MediaId.providerSubsonic;
  @override
  Set<Capability> get capabilities => const {};
  @override
  MusicLibrary get library => this;
  @override
  MusicMetadata get metadata => throw const UnsupportedSourceOperation('metadata');
  @override
  MusicWriteActions get writeActions => throw const UnsupportedSourceOperation('writeActions');
  @override
  MusicPlayback get playback => this;
  @override
  String? resolveCoverUrl(CoverRef ref, {int? size}) => 'https://covers.test/${ref.storageKey}?size=$size';

  final List<MediaId> nowPlayingReports = [];
  final List<MediaId> submittedPlays = [];

  @override
  Future<PlaybackHandle> handleFor(Track track) async => DirectStream('https://stream.test/${track.id.rawId}');
  @override
  Future<void> reportNowPlaying(MediaId trackId) async => nowPlayingReports.add(trackId);
  @override
  Future<void> submitPlay(MediaId trackId, DateTime playedAt) async => submittedPlays.add(trackId);
  @override
  void dispose() => disposed = true;

  @override
  Future<List<Album>> getAlbumList(AlbumListType type, {int size = 20, int offset = 0}) async {
    albumListCalls.add((type: type, size: size, offset: offset));
    if (failWith != null) throw failWith!;
    return albums.skip(offset).take(size).toList();
  }

  @override
  Future<bool> ping() async => failWith == null;
  @override
  Future<Album?> getAlbum(MediaId id) async => null;
  @override
  Future<List<ArtistIndex>> getArtists() async => const [];
  @override
  Future<ArtistDetail?> getArtist(MediaId id) async => null;
  @override
  Future<List<Playlist>> getPlaylists() async => const [];
  @override
  Future<Playlist?> getPlaylist(MediaId id) async => null;
  @override
  Future<Starred> getStarred() async => const Starred();
  @override
  Future<List<Track>> getRandomSongs({int size = 20}) async => const [];
  @override
  Future<SearchResults> search(String query, {int trackCount = 20, int albumCount = 20, int artistCount = 20}) async =>
      const SearchResults();
}
