/// What a started track writes to the activity log (Home spec §18, Android
/// YoinRepository.buildPlaybackActivity), as a pure function.
library;

import '../data/activity/activity.dart';
import '../data/model/model.dart';
import 'playback_context.dart';

/// The PLAYED row for [track] started from [context] at [nowMs].
ActivityEvent playedActivity(Track track, PlaybackContext context, int nowMs) {
  final trackCover = track.coverArt?.storageKey;
  // Subsonic serves an album's cover under the album id: the last fallback.
  final albumFallback = track.id.provider == MediaId.providerSubsonic ? track.albumId?.rawId : null;
  String? coverOr(CoverRef? contextCover) => contextCover?.storageKey ?? trackCover ?? albumFallback;
  final songId = track.id.rawId;
  return switch (context) {
    AlbumPlaybackContext(:final albumId, :final name, :final artist, :final artistId, :final cover) => ActivityEvent(
        entityType: ActivityEntityType.album,
        actionType: ActivityActionType.played,
        entityId: albumId.rawId,
        title: name,
        subtitle: _nonBlank(artist) ?? track.artist ?? '',
        coverArtKey: coverOr(cover),
        songId: songId,
        albumId: albumId.rawId,
        artistId: (artistId ?? track.artistId)?.rawId,
        timestampMs: nowMs,
      ),
    ArtistPlaybackContext(:final artistId, :final name, :final cover) => ActivityEvent(
        entityType: ActivityEntityType.artist,
        actionType: ActivityActionType.played,
        entityId: artistId.rawId,
        title: name,
        subtitle: 'Artist', // Android writes the literal.
        coverArtKey: coverOr(cover),
        songId: songId,
        albumId: track.albumId?.rawId,
        artistId: artistId.rawId,
        timestampMs: nowMs,
      ),
    PlaylistPlaybackContext(:final playlistId, :final name, :final owner, :final cover) => ActivityEvent(
        entityType: ActivityEntityType.playlist,
        actionType: ActivityActionType.played,
        entityId: playlistId.rawId,
        title: name,
        subtitle: _nonBlank(owner) ?? 'Playlist', // Android: owner.ifBlank { "Playlist" }
        coverArtKey: coverOr(cover),
        songId: songId,
        albumId: track.albumId?.rawId,
        artistId: track.artistId?.rawId,
        timestampMs: nowMs,
      ),
    NoPlaybackContext() || FavoritesPlaybackContext() => ActivityEvent(
        entityType: ActivityEntityType.song,
        actionType: ActivityActionType.played,
        entityId: songId,
        title: track.title ?? '',
        subtitle: track.artist ?? '',
        coverArtKey: coverOr(null),
        songId: songId,
        albumId: track.albumId?.rawId,
        artistId: track.artistId?.rawId,
        timestampMs: nowMs,
      ),
  };
}

String? _nonBlank(String? s) => s == null || s.trim().isEmpty ? null : s;
