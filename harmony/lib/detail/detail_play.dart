import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/model/model.dart';
import '../playback/now_playing_state.dart';
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
}) => player.controller.playWhenLoaded(
  () => loadArtistTracks(releases, player.cache.album),
  context: ArtistPlaybackContext(artistId: artist.id, name: artist.name, cover: cover),
  shuffle: shuffle,
);

/// Play next ([next]) / Add to queue from a page's ▾ menu (Android
/// DetailMenu queue → PlaybackManager.queueWhenLoaded): reads [load] (an
/// artist's whole discography may take a moment), queues what came back and
/// returns how many entries the player actually took — 0 when nothing went
/// in, so the caller confirms only a real success.
///
/// PlaybackController.addToQueue returns nothing and can quietly add fewer
/// tracks or none (no account, no queue-capable engine, tracks without a
/// stream), so the count is the queue's new entry ids, never [load]'s length.
/// A failure anywhere (the read, the source, the engine) is logged and
/// reported as 0; it never escapes as an unhandled async error.
Future<int> queueFromDetail(DetailPlayer player, Future<List<Track>> Function() load, {required bool next}) async {
  try {
    final tracks = await load();
    if (tracks.isEmpty) return 0;
    final before = player.readState().entryIds.toSet();
    await player.controller.addToQueue(tracks, next: next);
    final added = player.readState().entryIds.where((id) => !before.contains(id)).length;
    // A start elsewhere that replaced the queue meanwhile can't inflate the count.
    return math.min(added, tracks.length);
  } catch (error, stack) {
    debugPrint('YoinDetail: queue from page failed: $error\n$stack');
    return 0;
  }
}

/// The playback controller and detail cache the play helpers use.
final class DetailPlayer {
  DetailPlayer.widget(WidgetRef ref) : this.container(ref.container);
  DetailPlayer.ref(Ref ref) : this.container(ref.container);

  // The container, not the page's ref: a queue edit that outlives the page
  // (an artist's discography read) still counts what went in.
  DetailPlayer.container(ProviderContainer container)
    : controller = container.read(playbackControllerProvider.notifier),
      cache = container.read(detailCacheProvider),
      readState = (() => container.read(playbackControllerProvider));

  final PlaybackController controller;
  final DetailCache cache;

  /// The playback state now (queueFromDetail counts the entries a queue edit added).
  final NowPlayingState Function() readState;
}
