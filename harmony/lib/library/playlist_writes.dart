import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/model/model.dart';
import '../data/source/music_source.dart';
import '../detail/detail_cache.dart';

/// Bumped after every playlist write that landed: Library re-reads its
/// playlists, an open playlist page re-reads itself.
class PlaylistsRevision extends Notifier<int> {
  @override
  int build() => 0;

  void bump() => state++;
}

final playlistsRevisionProvider = NotifierProvider<PlaylistsRevision, int>(PlaylistsRevision.new);

/// What the active account can do to playlists (null source = nothing).
({bool canAdd, bool canEdit}) playlistAbilities(MusicSource? source) {
  final caps = source?.capabilities ?? const <Capability>{};
  final canAdd = source?.playlistWrites != null;
  return (canAdd: canAdd, canEdit: canAdd && caps.contains(Capability.playlistsWrite));
}

/// The one way the app changes playlists (Android repository playlist
/// writes): the source write, then the playlist's cached page dropped and
/// [playlistsRevisionProvider] bumped. Throws what the source throws;
/// [UnsupportedSourceOperation] when the account has no playlist writes.
class PlaylistWrites {
  PlaylistWrites(this._ref);
  final Ref _ref;

  Future<(MusicSource, MusicPlaylistWrites)> _writes() async {
    final source = await _ref.read(activeSourceProvider.future);
    final writes = source?.playlistWrites;
    if (source == null || writes == null) throw const UnsupportedSourceOperation('playlists');
    return (source, writes);
  }

  /// The songs of [tracks] this account can file (another service's are left out).
  static List<MediaId> _ids(MusicSource source, List<Track> tracks) => [
    for (final t in tracks)
      if (t.id.provider == source.provider && t.id.rawId.trim().isNotEmpty) t.id,
  ];

  void _changed(MediaId? playlist) {
    if (playlist != null) _ref.read(detailCacheProvider).evict('playlist', playlist);
    _ref.read(playlistsRevisionProvider.notifier).bump();
  }

  Future<Playlist> create(String name, {List<Track> tracks = const []}) async {
    final (source, writes) = await _writes();
    final Playlist created;
    try {
      created = await writes.createPlaylist(name.trim(), tracks: _ids(source, tracks));
    } on PartialPlaylistCreate catch (partial) {
      // Made, with the songs so far: the Library shows it, the caller still hears of the failure.
      _changed(partial.playlist.id);
      rethrow;
    }
    _changed(created.id);
    return created;
  }

  Future<void> add(MediaId playlist, List<Track> tracks) async {
    final (source, writes) = await _writes();
    await writes.addTracksToPlaylist(playlist, _ids(source, tracks));
    _changed(playlist);
  }

  Future<void> rename(MediaId playlist, String name) async {
    final (_, writes) = await _writes();
    await writes.renamePlaylist(playlist, name.trim());
    _changed(playlist);
  }

  Future<void> delete(MediaId playlist) async {
    final (_, writes) = await _writes();
    await writes.deletePlaylist(playlist);
    _changed(playlist);
  }

  Future<void> remove(MediaId playlist, List<int> positions) async {
    final (_, writes) = await _writes();
    await writes.removeFromPlaylist(playlist, positions);
    _changed(playlist);
  }

  /// The playlist's songs in [order] (a reorder).
  Future<void> reorder(MediaId playlist, List<Track> order) async {
    final (source, writes) = await _writes();
    await writes.setPlaylistTracks(playlist, _ids(source, order));
    _changed(playlist);
  }
}

final playlistWritesProvider = Provider<PlaylistWrites>(PlaylistWrites.new);
