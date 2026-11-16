import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/model/model.dart';
import '../library/add_to_playlist_flow.dart';
import '../library/playlist_writes.dart';
import '../ui/component/track_menu_sheet.dart';
import 'detail_page.dart';
import 'detail_routes.dart';

/// A song row's long-press (TrackMenuHost on Android): the song sheet with
/// this window's navigation. [onAlbumPage] leaves Go to album out (the row is
/// on that album); there [onViewInScrapbook] moves the page to its scrapbook
/// at the song (elsewhere View in Scrapbook opens the album there). Add to
/// playlist shows when the account can file songs; [onRemoveFromPlaylist]
/// (the playlist page, on a playlist the account can edit) adds Remove from
/// playlist. Messages go to the page's snackbar.
Future<void> openTrackMenu(
  BuildContext context,
  Track track, {
  bool onAlbumPage = false,
  ValueChanged<MediaId>? onViewInScrapbook,
  VoidCallback? onRemoveFromPlaylist,
}) {
  void message(String text) {
    if (context.mounted) showDetailMessage(context, text);
  }

  final source = ProviderScope.containerOf(context, listen: false).read(activeSourceProvider).value;
  return showTrackMenuSheet(
    context,
    track: track,
    onGoToAlbum: onAlbumPage ? null : (id) => DetailRoutes.openAlbum(context, id),
    onGoToArtist: (id) => DetailRoutes.openArtist(context, id, name: track.artist),
    onViewInScrapbook: onAlbumPage ? onViewInScrapbook : null,
    onAddToPlaylist: playlistAbilities(source).canAdd
        ? (tracks) {
            if (context.mounted) addToPlaylistFlow(context, tracks, onMessage: message);
          }
        : null,
    onRemoveFromPlaylist: onRemoveFromPlaylist,
    onMessage: message,
  );
}
