import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/activity/activity.dart';
import 'package:yoin/data/highlight/lyric_highlight.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/home/home_grid_pool_store.dart';
import 'package:yoin/home/home_selection.dart';
import 'package:yoin/home/lyric_highlight_view_model.dart';

/// Home's cards start songs that Now Playing opens the artist and album of: where the id is known
/// when the card's row is kept, it is kept with it.
void main() {
  test('should_keepArtistAndAlbumName_when_jumpBackInPoolsAreCached', () {
    final pools = HomeGridPools(
      cachedAtMs: 1,
      tracks: [
        Track(
          id: MediaId.subsonic('t'),
          title: 'T',
          artist: 'A',
          artistId: MediaId.subsonic('ar'),
          album: 'Al',
          albumId: MediaId.subsonic('al'),
        ),
        Track(id: MediaId.subsonic('slim')),
      ],
    );
    final back = HomeGridPools.fromJson(pools.toJson(), provider: MediaId.providerSubsonic);
    expect(back.tracks.first.artistId, MediaId.subsonic('ar'));
    expect(back.tracks.first.album, 'Al');
    expect(back.tracks.first.albumId, MediaId.subsonic('al'));
    expect(back.tracks.last.artistId, isNull, reason: 'a song without one stays without (Now Playing asks)');
  });

  test('should_readOldPoolRows_when_theyHaveNoArtist', () {
    final back = HomeGridPools.fromJson({
      'v': 1,
      'at': 1,
      'tracks': [
        {'id': 't', 't': 'T', 'a': 'A', 'al': 'al', 'd': 100},
      ],
    }, provider: MediaId.providerSubsonic);
    expect(back.tracks.single.artistId, isNull);
    expect(back.tracks.single.albumId, MediaId.subsonic('al'));
  });

  LyricHighlightRow row({String artistId = ''}) => LyricHighlightRow(
    songId: 's',
    title: 'S',
    artist: 'A',
    album: 'B',
    albumId: 'al',
    coverArtId: null,
    durationMs: 200000,
    text: 'line',
    startMs: 1000,
    model: lyricHighlightUserPick,
    attemptedAt: 0,
    artistId: artistId,
  );

  test('should_carryArtistToPlayedSong_when_lyricCardKeptIt', () {
    final kept = LyricHighlightRow.fromJson('s', row(artistId: 'ar').toJson())!;
    expect(kept.artistId, 'ar');
    expect(kept.shownAt(5).artistId, 'ar', reason: 'putting it on show keeps it');
    final track = HomeLyricHighlight.fromRow(MediaId.providerSubsonic, kept, LyricHighlightBucket.user).asTrack();
    expect(track.artistId, MediaId.subsonic('ar'));
    expect(track.albumId, MediaId.subsonic('al'));
  });

  test('should_leaveArtistEmpty_when_lyricRowWasKeptBefore', () {
    final json = row().toJson();
    expect(json.containsKey('ri'), isFalse);
    final track = HomeLyricHighlight.fromRow(
      MediaId.providerSubsonic,
      LyricHighlightRow.fromJson('s', json)!,
      LyricHighlightBucket.user,
    ).asTrack();
    expect(track.artistId, isNull);
  });

  test('should_carryRecordedArtist_when_activitySongIsPlayed', () {
    final event = ActivityEvent(
      entityType: ActivityEntityType.song,
      actionType: ActivityActionType.played,
      entityId: 's',
      title: 'S',
      subtitle: 'A',
      songId: 's',
      albumId: 'al',
      artistId: 'ar',
      timestampMs: 0,
    );
    final track = activityAsTrack(event, MediaId.providerSubsonic);
    expect(track.artistId, MediaId.subsonic('ar'));
    expect(track.albumId, MediaId.subsonic('al'));
  });
}
