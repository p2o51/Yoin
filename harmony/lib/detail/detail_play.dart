import 'dart:async';
import 'dart:math' as math;

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/model/model.dart';
import '../playback/playback_context.dart';
import '../playback/playback_controller.dart';
import 'detail_cache.dart';

/// Album reads an artist Play keeps in flight (Android ARTIST_ALBUM_LOADS_IN_FLIGHT).
const artistAlbumLoadsInFlight = 3;

AlbumPlaybackContext albumPlaybackContext(Album album) => AlbumPlaybackContext(
  albumId: album.id,
  name: album.name,
  artist: album.artist,
  artistId: album.artistId,
  cover: album.coverArt,
);

PlaylistPlaybackContext playlistPlaybackContext(Playlist playlist, CoverRef? cover) =>
    PlaylistPlaybackContext(playlistId: playlist.id, name: playlist.name, owner: playlist.owner, cover: cover);

/// Plays [tracks] from [startIndex] (or a shuffled copy from 0) with [context].
Future<void> playFromDetail(
  DetailPlayer player,
  List<Track> tracks, {
  int startIndex = 0,
  bool shuffle = false,
  required PlaybackContext context,
  math.Random? random,
}) {
  if (tracks.isEmpty) return Future.value();
  final queue = shuffle ? ([...tracks]..shuffle(random)) : tracks;
  return player.controller.playTracks(queue, startIndex: shuffle ? 0 : startIndex, context: context, shuffled: shuffle);
}

/// Every track of [releases] in discography order, reading at most
/// [inFlight] albums at a time (a finished read frees its slot at once;
/// albums start in order). An album that can't be read contributes nothing.
Future<List<Track>> loadArtistTracks(
  List<Album> releases,
  Future<Album?> Function(MediaId id) openAlbum, {
  int inFlight = artistAlbumLoadsInFlight,
}) async {
  final results = List<List<Track>>.filled(releases.length, const []);
  var next = 0;
  Future<void> worker() async {
    while (next < releases.length) {
      final i = next++;
      try {
        results[i] = (await openAlbum(releases[i].id))?.tracks ?? const [];
      } catch (_) {
        results[i] = const [];
      }
    }
  }

  await Future.wait([for (var w = 0; w < math.min(inFlight, releases.length); w++) worker()]);
  return [for (final r in results) ...r];
}

/// Artist Play / Shuffle: the whole discography (Android playArtist). The
/// read survives a page rebuild; any newer start anywhere in the app (a row
/// in Library, Home, search …) cancels it (PlaybackController.playWhenLoaded).
Future<void> playArtist(
  DetailPlayer player, {
  required ArtistDetail artist,
  required List<Album> releases,
  required CoverRef? cover,
  bool shuffle = false,
}) =>
    player.controller.playWhenLoaded(
      () => loadArtistTracks(releases, player.cache.album),
      context: ArtistPlaybackContext(artistId: artist.id, name: artist.name, cover: cover),
      shuffle: shuffle,
    );

/// The playback controller and detail cache the play helpers use.
final class DetailPlayer {
  DetailPlayer.widget(WidgetRef ref)
    : controller = ref.read(playbackControllerProvider.notifier),
      cache = ref.read(detailCacheProvider);
  DetailPlayer.ref(Ref ref)
    : controller = ref.read(playbackControllerProvider.notifier),
      cache = ref.read(detailCacheProvider);

  final PlaybackController controller;
  final DetailCache cache;
}
