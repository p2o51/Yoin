/// Playlists' By You sub-chip, after Spotify's Your Library (Android
/// LibraryPlaylistsByYou.kt): narrows Playlists to the ones the user made
/// ([Playlist.ownedByMe]).
library;

import 'package:flutter/foundation.dart';

import '../data/model/model.dart';

/// [available]: the loaded playlists are mixed ([hasMixedOwnership]), so By
/// You narrows the list and never empties it. Unmixed (one person's server,
/// or a service that doesn't say), there is no sub-chip. [selected]: the
/// user turned it on; the view model keeps it through chips and refreshes
/// and drops it with the account, never persisted. It narrows the list only
/// while [available].
@immutable
final class PlaylistsByYou {
  const PlaylistsByYou({this.available = false, this.selected = false});

  final bool available;
  final bool selected;

  /// By You is narrowing Playlists now.
  bool get filtering => available && selected;

  @override
  bool operator ==(Object other) =>
      other is PlaylistsByYou && other.available == available && other.selected == selected;

  @override
  int get hashCode => Object.hash(available, selected);
}

/// Some playlists are the user's and some are someone else's. One whose
/// ownership the service doesn't say counts as neither.
bool hasMixedOwnership(Iterable<Playlist> playlists) =>
    playlists.any((p) => p.ownedByMe == true) && playlists.any((p) => p.ownedByMe == false);

/// [playlists] as the Playlists view lists them under By You: only the
/// user's own, so one of unknown ownership is left out too.
List<Playlist> playlistsByYou(Iterable<Playlist> playlists) => [
  for (final p in playlists)
    if (p.ownedByMe == true) p,
];
