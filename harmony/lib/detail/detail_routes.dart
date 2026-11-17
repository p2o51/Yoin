import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/model/model.dart';
import '../ui/navigation/pane/detail_pane.dart';
import '../ui/navigation/yoin_push_route.dart';
import 'album_detail_screen.dart';
import 'artist_detail_screen.dart';
import 'detail_cache.dart';
import 'playlist_detail_screen.dart';

/// The only way to open a detail page (PushPage, YoinPushRoute). Home,
/// Library, Now Playing and search call these. Each starts the page's load
/// at tap time (single-flight: the page joins it, or reads the cache).
/// [preview] / [name] are accepted for callers' convenience; Android does
/// not seed the header from them (it shows once the read lands).
abstract final class DetailRoutes {
  static Future<void> openAlbum(BuildContext context, MediaId id, {Album? preview}) {
    _cache(context)?.prefetchAlbum(id);
    return _open(context, (_) => AlbumDetailScreen(albumId: id));
  }

  static Future<void> openArtist(BuildContext context, MediaId id, {String? name}) {
    _cache(context)?.prefetchArtist(id);
    return _open(context, (_) => ArtistDetailScreen(artistId: id));
  }

  static Future<void> openPlaylist(BuildContext context, MediaId id, {Playlist? preview}) {
    _cache(context)?.prefetchPlaylist(id);
    return _open(context, (_) => PlaylistDetailScreen(playlistId: id));
  }

  /// A Wide, tall window opens the page as the shell's detail column
  /// (adaptive-principles §3); everywhere else it is pushed.
  static Future<void> _open(BuildContext context, WidgetBuilder builder) {
    if (DetailPane.open(context, builder)) return Future.value();
    return Navigator.of(context).push(YoinPushRoute<void>(builder: builder));
  }

  static DetailCache? _cache(BuildContext context) {
    try {
      return ProviderScope.containerOf(context, listen: false).read(detailCacheProvider);
    } catch (_) {
      return null;
    }
  }
}
