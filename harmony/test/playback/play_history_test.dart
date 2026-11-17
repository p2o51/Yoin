import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/activity/activity.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/playback/play_history.dart';
import 'package:yoin/playback/playback_context.dart';

final _track = Track(
  id: MediaId.subsonic('s1'),
  title: 'Song',
  artist: 'Singer',
  artistId: MediaId.subsonic('ar1'),
  album: 'Record',
  albumId: MediaId.subsonic('al1'),
  coverArt: const CoverSourceRelative('cov-s1'),
);

void main() {
  test('should_recordAlbumRow_when_playedFromAlbum', () {
    final e = playedActivity(
      _track,
      AlbumPlaybackContext(albumId: MediaId.subsonic('al9'), name: 'Album', artist: 'Band', cover: const CoverSourceRelative('cov-al9')),
      42,
    );
    expect(e.entityType, ActivityEntityType.album);
    expect(e.actionType, ActivityActionType.played);
    expect(e.entityId, 'al9');
    expect(e.title, 'Album');
    expect(e.subtitle, 'Band');
    expect(e.coverArtKey, 'cov-al9');
    expect(e.albumId, 'al9');
    expect(e.artistId, 'ar1');
    expect(e.songId, 's1');
    expect(e.timestampMs, 42);
  });

  test('should_fallBackToTrackArtist_when_albumContextHasNoArtist', () {
    final e = playedActivity(_track, AlbumPlaybackContext(albumId: MediaId.subsonic('al1'), name: 'Record', artist: ' '), 1);
    expect(e.subtitle, 'Singer');
    expect(e.coverArtKey, 'cov-s1', reason: 'context cover, else the track cover');
  });

  test('should_useLiteralArtistSubtitle_when_playedFromArtist', () {
    final e = playedActivity(_track, ArtistPlaybackContext(artistId: MediaId.subsonic('ar1'), name: 'Singer'), 1);
    expect(e.entityType, ActivityEntityType.artist);
    expect(e.subtitle, 'Artist');
    expect(e.albumId, 'al1');
  });

  test('should_useOwnerElsePlaylist_when_playedFromPlaylist', () {
    final named = playedActivity(_track, PlaylistPlaybackContext(playlistId: MediaId.subsonic('pl'), name: 'Mix', owner: 'me'), 1);
    final unnamed = playedActivity(_track, PlaylistPlaybackContext(playlistId: MediaId.subsonic('pl'), name: 'Mix', owner: ''), 1);
    expect(named.entityType, ActivityEntityType.playlist);
    expect(named.subtitle, 'me');
    expect(unnamed.subtitle, 'Playlist');
  });

  test('should_recordSongRow_when_noContextOrFavorites', () {
    for (final ctx in const [NoPlaybackContext(), FavoritesPlaybackContext()]) {
      final e = playedActivity(_track, ctx, 1);
      expect(e.entityType, ActivityEntityType.song);
      expect(e.entityId, 's1');
      expect(e.title, 'Song');
      expect(e.subtitle, 'Singer');
    }
  });

  test('should_useAlbumIdAsCover_when_subsonicTrackHasNoCover', () {
    final bare = Track(id: MediaId.subsonic('s2'), albumId: MediaId.subsonic('al2'));
    expect(playedActivity(bare, const NoPlaybackContext(), 1).coverArtKey, 'al2');
  });
}
