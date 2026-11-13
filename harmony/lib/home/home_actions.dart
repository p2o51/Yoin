import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../detail/detail_routes.dart';
import 'home_ui_state.dart';
import 'home_view_model.dart';

/// What a Home card tap does: details open as PushPages (DetailRoutes, the
/// only way in); a song plays alone at once (no navigation, no haptic).
void openHomeTarget(BuildContext context, WidgetRef ref, HomeTarget target) {
  switch (target) {
    case HomeOpenAlbum(:final album):
      DetailRoutes.openAlbum(context, album.id, preview: album);
    case HomeOpenPlaylist(:final playlist):
      DetailRoutes.openPlaylist(context, playlist.id, preview: playlist);
    case HomeOpenArtist(:final id, :final name):
      DetailRoutes.openArtist(context, id, name: name);
    case HomePlaySong(:final track):
      ref.read(homeViewModelProvider.notifier).playSong(track);
  }
}
