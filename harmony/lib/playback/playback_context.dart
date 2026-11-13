import '../data/model/model.dart';

/// Where a play started (Android ActivityContext): drives the player's
/// "PLAYING FROM …" label and what the activity log records. Only
/// PlaybackController.playTracks sets it (Android: activityContext is
/// written by play() alone).
sealed class PlaybackContext {
  const PlaybackContext();
}

final class NoPlaybackContext extends PlaybackContext {
  const NoPlaybackContext();
}

final class AlbumPlaybackContext extends PlaybackContext {
  const AlbumPlaybackContext({required this.albumId, required this.name, this.artist, this.artistId, this.cover});
  final MediaId albumId;
  final String name;
  final String? artist;
  final MediaId? artistId;
  final CoverRef? cover;
}

final class ArtistPlaybackContext extends PlaybackContext {
  const ArtistPlaybackContext({required this.artistId, required this.name, this.cover});
  final MediaId artistId;
  final String name;
  final CoverRef? cover;
}

final class PlaylistPlaybackContext extends PlaybackContext {
  const PlaylistPlaybackContext({required this.playlistId, required this.name, this.owner, this.cover});
  final MediaId playlistId;
  final String name;
  final String? owner;
  final CoverRef? cover;
}

/// Starred songs (Subsonic getStarred2 songs).
final class FavoritesPlaybackContext extends PlaybackContext {
  const FavoritesPlaybackContext();
}
