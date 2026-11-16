import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/activity/activity.dart';
import 'package:yoin/data/memory/album_memory_candidate.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/playback/local_ratings.dart';

Album _album(String id, int tracks) => Album(
  id: MediaId.subsonic(id),
  name: 'Album $id',
  artist: 'Artist',
  songCount: tracks,
  tracks: [
    for (var i = 1; i <= tracks; i++)
      Track(id: MediaId.subsonic('$id-t$i'), title: 'Song $i', trackNumber: i, albumId: MediaId.subsonic(id)),
  ],
);

LocalRating _rating(String songId, double? r, {int at = 1}) => LocalRating(
  profileId: 'p1',
  songId: songId,
  provider: MediaId.providerSubsonic,
  rating: r,
  serverRating: serverRatingFor(r),
  updatedAt: at,
);

AlbumPlays _plays(String id, {int n = 3, int first = 100, int last = 500}) =>
    AlbumPlays(albumId: MediaId.subsonic(id), playCount: n, firstPlayedAt: first, lastPlayedAt: last);

SongNote _note(String id, String trackId, String albumId, {int at = 1}) => SongNote(
  id: id,
  trackId: MediaId.subsonic(trackId),
  albumId: MediaId.subsonic(albumId),
  content: 'note $id',
  createdAt: at,
  updatedAt: at,
);

void main() {
  final albums = {
    for (final a in [_album('a', 5), _album('b', 10), _album('c', 2), _album('d', 4)]) a.id: a,
  };
  Future<Album?> load(MediaId id) async => albums[id];

  test('should_match_android_when_hashingSessionId', () {
    // Kotlin: stableAlbumMemorySessionId("p", "subsonic", "a") with Long overflow.
    var expected = 1125899906842597;
    for (final c in 'p|subsonic|a'.codeUnits) {
      expected = (31 * expected + c).toSigned(64);
    }
    expect(stableAlbumMemorySessionId('p', 'subsonic', 'a'), expected);
    expect(stableAlbumMemorySessionId('p', 'subsonic', 'a'), isNot(stableAlbumMemorySessionId('q', 'subsonic', 'a')));
  });

  test('should_admit_when_sixtyPercentOfTracksRated', () async {
    final pool = await buildAlbumMemoryCandidates(
      AlbumMemoryInputs(
        profileId: 'p1',
        provider: MediaId.providerSubsonic,
        albumPlays: [_plays('a'), _plays('b')],
        trackRatings: {
          for (final r in [_rating('a-t1', 7), _rating('a-t2', 0), _rating('a-t3', 9), _rating('b-t1', 8)])
            r.trackId: r,
        },
      ),
      loadAlbum: load,
    );
    expect(pool.map((c) => c.albumId.rawId), ['a']);
    expect(pool.single.ratingCoverage, closeTo(0.6, 1e-9));
    expect(pool.single.averageSongRating, closeTo(16 / 3, 1e-9));
  });

  test('should_admit_when_reviewOrTwoNotes', () async {
    final pool = await buildAlbumMemoryCandidates(
      AlbumMemoryInputs(
        profileId: 'p1',
        provider: MediaId.providerSubsonic,
        albumRatings: {
          MediaId.subsonic('b'): AlbumRatingRow(albumId: MediaId.subsonic('b'), review: 'Yes.', updatedAt: 9),
        },
        songNotes: [_note('n1', 'd-t1', 'd'), _note('n2', 'd-t2', 'd')],
      ),
      loadAlbum: load,
    );
    // Review first, then notes.
    expect(pool.map((c) => c.albumId.rawId), ['b', 'd']);
    expect(pool.last.noteCount, 2);
  });

  test('should_reject_when_albumHasFewerThanFourTracks', () async {
    final pool = await buildAlbumMemoryCandidates(
      AlbumMemoryInputs(
        profileId: 'p1',
        provider: MediaId.providerSubsonic,
        albumRatings: {
          MediaId.subsonic('c'): AlbumRatingRow(albumId: MediaId.subsonic('c'), review: 'Short.', updatedAt: 9),
        },
      ),
      loadAlbum: load,
    );
    expect(pool, isEmpty);
  });

  test('should_deferToGates_when_albumDetailFails', () async {
    final pool = await buildAlbumMemoryCandidates(
      AlbumMemoryInputs(
        profileId: 'p1',
        provider: MediaId.providerSubsonic,
        albumRatings: {
          MediaId.subsonic('zz'): AlbumRatingRow(albumId: MediaId.subsonic('zz'), review: 'Kept.', updatedAt: 9),
        },
      ),
      loadAlbum: (_) async => throw StateError('offline'),
    );
    expect(pool.single.albumId.rawId, 'zz');
    expect(pool.single.isMemoryEligible, isTrue);
  });

  test('should_notCountClearedRating_when_coverageComputed', () async {
    final pool = await buildAlbumMemoryCandidates(
      AlbumMemoryInputs(
        profileId: 'p1',
        provider: MediaId.providerSubsonic,
        albumPlays: [_plays('d')],
        trackRatings: {
          for (final r in [_rating('d-t1', 5), _rating('d-t2', 6), _rating('d-t3', null)]) r.trackId: r,
        },
      ),
      loadAlbum: load,
      includeIneligible: true,
    );
    expect(pool.single.ratedTrackCount, 2);
    expect(pool.single.isMemoryEligible, isFalse);
  });

  test('should_carryPlaysOnly_when_albumAlsoVisited', () async {
    final pool = await buildAlbumMemoryCandidates(
      AlbumMemoryInputs(
        profileId: 'p1',
        provider: MediaId.providerSubsonic,
        albumPlays: [_plays('b', n: 4, first: 100, last: 200)],
        activity: [
          ActivityEvent(
            entityType: ActivityEntityType.album,
            actionType: ActivityActionType.visited,
            entityId: 'b',
            title: 'Album b',
            timestampMs: 900,
          ),
        ],
        albumRatings: {
          MediaId.subsonic('b'): AlbumRatingRow(albumId: MediaId.subsonic('b'), review: 'r', updatedAt: 9),
        },
      ),
      loadAlbum: load,
    );
    final c = pool.single;
    expect(c.lastPlayedAt, 900); // ranking key takes the visit
    expect(c.plays!.lastPlayedAt, 200); // the "heard" facts never do
    expect(c.plays!.playCount, 4);
  });

  test('should_rankByCoverageThenNotes_when_noReviews', () async {
    final pool = await buildAlbumMemoryCandidates(
      AlbumMemoryInputs(
        profileId: 'p1',
        provider: MediaId.providerSubsonic,
        albumPlays: [_plays('a', last: 900), _plays('d', last: 100)],
        songNotes: [_note('n1', 'a-t1', 'a'), _note('n2', 'a-t2', 'a')],
        trackRatings: {
          for (final r in [_rating('d-t1', 5), _rating('d-t2', 6), _rating('d-t3', 7), _rating('d-t4', 8)])
            r.trackId: r,
        },
      ),
      loadAlbum: load,
    );
    expect(pool.map((c) => c.albumId.rawId), ['d', 'a']);
  });

  test('should_boundConcurrentAlbumLoads_when_manySeeds', () async {
    var inFlight = 0;
    var peak = 0;
    final pool = await buildAlbumMemoryCandidates(
      AlbumMemoryInputs(
        profileId: 'p1',
        provider: MediaId.providerSubsonic,
        albumPlays: [for (var i = 0; i < 20; i++) _plays('x$i')],
      ),
      loadAlbum: (id) async {
        inFlight++;
        if (inFlight > peak) peak = inFlight;
        await Future<void>.delayed(Duration.zero);
        inFlight--;
        return null;
      },
      includeIneligible: true,
    );
    expect(pool, hasLength(20));
    expect(peak, lessThanOrEqualTo(4));
  });

  test('should_placeNotesThroughTheirSongs_when_notesCarryNoAlbum', () async {
    SongNote loose(String id, String track) =>
        SongNote(id: id, trackId: MediaId.subsonic(track), content: 'loose $id', createdAt: 1, updatedAt: 1);
    final pool = await buildAlbumMemoryCandidates(
      AlbumMemoryInputs(
        profileId: 'p1',
        provider: MediaId.providerSubsonic,
        songNotes: [loose('n1', 'elsewhere-1'), loose('n2', 'elsewhere-2')],
        // A synced note's songs, linked to album b by a lookup.
        trackAlbums: {
          MediaId.subsonic('elsewhere-1'): MediaId.subsonic('b'),
          MediaId.subsonic('elsewhere-2'): MediaId.subsonic('b'),
        },
      ),
      loadAlbum: load,
    );
    expect(pool.single.albumId, MediaId.subsonic('b'));
    expect(pool.single.noteCount, 2);
    expect(pool.single.isMemoryEligible, isTrue);
  });

  test('should_addScoredAlbumsForRediscoverOnly_when_trackSignalsAsked', () async {
    final inputs = AlbumMemoryInputs(
      profileId: 'p1',
      provider: MediaId.providerSubsonic,
      // Scores pulled from the server: every track of a, never played here.
      trackRatings: {for (var i = 1; i <= 5; i++) MediaId.subsonic('a-t$i'): _rating('a-t$i', 8, at: i)},
      trackAlbums: {for (var i = 1; i <= 5; i++) MediaId.subsonic('a-t$i'): MediaId.subsonic('a')},
    );
    expect(await buildAlbumMemoryCandidates(inputs, loadAlbum: load, includeIneligible: true), isEmpty);
    final pool = await buildAlbumMemoryCandidates(
      inputs,
      loadAlbum: load,
      includeIneligible: true,
      withTrackSignals: true,
    );
    final a = pool.single;
    expect(a.albumId, MediaId.subsonic('a'));
    expect(a.averageSongRating, 8);
    expect(a.ratingCoverage, 1);
    // Outside the Memory scan: never a Memory (Android inMemoryScan = false).
    expect(a.isMemoryEligible, isFalse);
    expect(await buildAlbumMemoryCandidates(inputs, loadAlbum: load), isEmpty);
  });

  test('should_rankTrackSignalAlbums_when_signalsDiffer', () {
    final inputs = AlbumMemoryInputs(
      profileId: 'p1',
      provider: MediaId.providerSubsonic,
      songNotes: [_note('n1', 'x', 'c', at: 9)],
      trackRatings: {
        MediaId.subsonic('b-t1'): _rating('b-t1', 7, at: 1),
        MediaId.subsonic('b-t2'): _rating('b-t2', 7, at: 2),
        MediaId.subsonic('d-t1'): _rating('d-t1', null, at: 3),
      },
      trackAlbums: {
        MediaId.subsonic('b-t1'): MediaId.subsonic('b'),
        MediaId.subsonic('b-t2'): MediaId.subsonic('b'),
        MediaId.subsonic('d-t1'): MediaId.subsonic('d'),
      },
    );
    // Two scores beat one note; a cleared score is no signal; a scanned album is left out.
    expect(trackSignalAlbums(inputs), [MediaId.subsonic('b'), MediaId.subsonic('c')]);
    expect(trackSignalAlbums(inputs, exclude: {'b'}), [MediaId.subsonic('c')]);
  });
}
