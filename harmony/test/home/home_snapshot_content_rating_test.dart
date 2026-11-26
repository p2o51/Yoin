// The explicit / clean mark through Home's snapshot and Jump Back In's pools (Android HomeSnapshotDtos +
// HomeGridPoolContentRatingTest, PR #343): kept on albums and songs, and a snapshot or pool from before it
// still reads, every mark unknown until Home refreshes.
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/home/home_grid_pool_store.dart';
import 'package:yoin/home/home_snapshot_mapping.dart';
import 'package:yoin/home/home_ui_state.dart';

MediaId _id(String raw) => MediaId.subsonic(raw);

Album _album(String id, ContentRating? rating) =>
    Album(id: _id(id), name: 'Album $id', releaseType: ReleaseType.ep, contentRating: rating);

Track _track(String id, ContentRating? rating) => Track(id: _id(id), title: 'Song $id', contentRating: rating);

/// [json] with every 'cr' taken out, as a build from before PR #343 wrote it.
Object? _withoutRatings(Object? json) => switch (json) {
  final Map<String, Object?> m => {
    for (final e in m.entries)
      if (e.key != 'cr') e.key: _withoutRatings(e.value),
  },
  final List<Object?> l => [for (final e in l) _withoutRatings(e)],
  _ => json,
};

void main() {
  HomeContent feed() => HomeContent(
    scopeKey: 'subsonic|p',
    provider: MediaId.providerSubsonic,
    grid: [
      HomeWidgetCard(
        stableId: 'album:g',
        kind: HomeCardKind.album,
        title: 'Grid',
        subtitle: 'Artist',
        target: HomeOpenAlbum(_album('g', ContentRating.explicit)),
      ),
      HomeWidgetCard(
        stableId: 'song:s',
        kind: HomeCardKind.song,
        title: 'Song',
        subtitle: 'Artist',
        target: HomePlaySong(_track('s', ContentRating.explicit)),
      ),
    ],
    recentlyAddedTracks: [_track('t1', ContentRating.explicit), _track('t2', ContentRating.clean)],
    recentlyAddedAlbums: [_album('a1', ContentRating.explicit)],
    recentlyPlayedAll: [_album('r1', null)],
  );

  test('should_keepRatings_when_snapshotRoundTrips', () {
    final back = homeContentFromSnapshot(
      homeSnapshotFeed(feed()),
      scopeKey: 'subsonic|p',
      provider: 'subsonic',
      nowMs: 0,
    );
    expect((back.grid[0].target as HomeOpenAlbum).album.isExplicit, isTrue);
    expect((back.grid[1].target as HomePlaySong).track.isExplicit, isTrue);
    expect([for (final t in back.recentlyAddedTracks) t.contentRating], [ContentRating.explicit, ContentRating.clean]);
    expect(back.recentlyAddedAlbums.single.contentRating, ContentRating.explicit);
    expect(back.recentlyAddedAlbums.single.releaseType, ReleaseType.ep);
    expect(back.recentlyPlayedAll.single.contentRating, isNull);
  });

  test('should_readUnknown_when_snapshotPredatesRatings', () {
    final old = _withoutRatings(homeSnapshotFeed(feed()))! as Map<String, Object?>;
    final back = homeContentFromSnapshot(old, scopeKey: 'subsonic|p', provider: 'subsonic', nowMs: 0);
    expect(back.grid, hasLength(2));
    expect((back.grid[0].target as HomeOpenAlbum).album.contentRating, isNull);
    expect([for (final t in back.recentlyAddedTracks) t.contentRating], [null, null]);
    expect(back.recentlyAddedAlbums.single.contentRating, isNull);
  });

  test('should_keepRatings_when_gridPoolsRoundTrip', () {
    final pools = HomeGridPools(
      albums: [_album('a1', ContentRating.explicit), _album('a2', null)],
      tracks: [_track('t1', ContentRating.clean)],
      cachedAtMs: 5,
    );
    final back = HomeGridPools.fromJson(pools.toJson(), provider: MediaId.providerSubsonic);
    expect([for (final a in back.albums) a.contentRating], [ContentRating.explicit, null]);
    expect(back.tracks.single.contentRating, ContentRating.clean);
  });

  test('should_readUnknown_when_gridPoolsPredateRatingsOrHoldOddValues', () {
    final json = HomeGridPools(
      albums: [_album('a1', ContentRating.explicit)],
      tracks: [_track('t1', ContentRating.explicit)],
      cachedAtMs: 5,
    ).toJson();
    final old = _withoutRatings(json)! as Map<String, Object?>;
    final back = HomeGridPools.fromJson(old, provider: MediaId.providerSubsonic);
    expect(back.albums.single.contentRating, isNull);
    expect(back.tracks.single.contentRating, isNull);
    final odd = {
      ...json,
      'albums': [
        {'id': 'a1', 'n': 'A', 'cr': 7},
      ],
    };
    expect(HomeGridPools.fromJson(odd, provider: MediaId.providerSubsonic).albums.single.contentRating, isNull);
  });
}
