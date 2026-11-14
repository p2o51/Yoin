import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/model/model.dart';
import '../data/source/music_source.dart';
import '../ui/component/add_to_playlist_sheet.dart';
import '../ui/component/component_strings.dart';
import 'playlist_writes.dart';

/// Whether the active account can file songs into playlists (Add to playlist shows).
bool canAddToPlaylists(WidgetRef ref) => playlistAbilities(ref.read(activeSourceProvider).value).canAdd;

/// Add to playlist for [tracks] (Android NowPlayingViewModel addTargets /
/// createPlaylistAndAddTargets): the sheet lists the playlists this account
/// can write; a pick appends, "Create new playlist…" makes one holding them.
/// [onMessage] says how it went.
Future<void> addToPlaylistFlow(BuildContext context, List<Track> tracks, {required ValueChanged<String> onMessage}) {
  final container = ProviderScope.containerOf(context, listen: false);
  final strings = ComponentStrings.of(context);
  final writes = container.read(playlistWritesProvider);
  return showAddToPlaylistSheet(
    context,
    loadWritablePlaylists: () async {
      final source = await container.read(activeSourceProvider.future);
      final writes = source?.playlistWrites;
      if (source == null || writes == null) return const [];
      try {
        return [
          for (final p in await source.library.getPlaylists())
            if (writes.acceptsTracks(p)) p,
        ];
      } catch (_) {
        return const [];
      }
    },
    onAddToExisting: (playlist) async {
      try {
        await writes.add(playlist.id, tracks);
        onMessage(strings.addedToPlaylist);
      } catch (_) {
        onMessage(strings.couldntAddToPlaylist);
      }
    },
    onCreateAndAdd: (name) async {
      try {
        await writes.create(name, tracks: tracks);
        onMessage(strings.addedToPlaylist);
      } catch (_) {
        onMessage(strings.couldntCreate(name));
      }
    },
  );
}
