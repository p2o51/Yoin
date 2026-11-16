import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/memory/track_album_links.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/playback/local_ratings.dart';

MediaId _s(String id) => MediaId.subsonic(id);

LocalRating _rating(String songId, double? r, {int at = 1, String provider = MediaId.providerSubsonic}) => LocalRating(
  profileId: 'p1',
  songId: songId,
  provider: provider,
  rating: r,
  serverRating: serverRatingFor(r),
  updatedAt: at,
);

SongNote _note(String id, MediaId track, {MediaId? album, int at = 1, String title = ''}) =>
    SongNote(id: id, trackId: track, albumId: album, content: 'note $id', createdAt: at, updatedAt: at, title: title);

/// A song lookup that answers from [songs]; ids in [failing] throw, as an
/// offline server does.
class _Lookup implements MusicTrackLookup {
  _Lookup(this.songs, {this.failing = const {}});

  final Map<MediaId, Track> songs;
  final Set<MediaId> failing;
  final asked = <MediaId>[];

  @override
  Future<Track?> getTrack(MediaId id) async {
    asked.add(id);
    if (failing.contains(id)) throw const SourceNetworkException('offline');
    return songs[id];
  }
}

void main() {
  group('TrackAlbumLinkStore', () {
    test('should_keepEveryAnswerAndFireOnce_when_linksStored', () async {
      final kv = InMemoryStore();
      final store = TrackAlbumLinkStore(kv, profileId: 'p1');
      var changes = 0;
      final sub = store.changes.listen((_) => changes++);
      final found = TrackAlbumLink(
        trackId: _s('t1'),
        albumId: _s('a1'),
        updatedAt: 5,
        title: 'Song',
        albumName: 'Album',
        artist: 'Artist',
        coverArtKey: 'cov',
      );
      final none = TrackAlbumLink(trackId: _s('t2'), updatedAt: 6);
      await store.putAll([found, none]);
      // The same answers again: no write, no event.
      await store.putAll([found]);
      await Future<void>.delayed(Duration.zero);
      expect(changes, 1);
      final reread = await TrackAlbumLinkStore(kv, profileId: 'p1').links();
      expect(reread[_s('t1')], found);
      expect(reread[_s('t2')]!.albumId, isNull);
      // Another account's links are its own.
      expect(await TrackAlbumLinkStore(kv, profileId: 'p2').links(), isEmpty);
      await sub.cancel();
    });

    test('should_dropAnotherServicesAlbum_when_answerIsOdd', () {
      final link = TrackAlbumLink.answer(
        _s('t1'),
        Track(id: _s('t1'), title: 'x', albumId: MediaId(MediaId.providerAppleMusic, 'z')),
        nowMs: 1,
      );
      expect(link.albumId, isNull);
      expect(link.isNews, isTrue);
      expect(TrackAlbumLink.answer(_s('t1'), null, nowMs: 1).isNews, isFalse);
    });
  });

  group('unlinkedTracks', () {
    test('should_listOldestFirst_when_notesAndScoresHaveNoAlbum', () {
      final ids = unlinkedTracks(
        provider: MediaId.providerSubsonic,
        notes: [
          _note('n1', _s('noted'), at: 30),
          _note('n2', _s('noted'), at: 10),
          _note('n3', _s('withAlbum'), album: _s('a'), at: 1),
        ],
        ratings: {
          _s('scored'): _rating('scored', 7, at: 20),
          _s('cleared'): _rating('cleared', null, at: 2),
          _s('linked'): _rating('linked', 8, at: 3),
          _s('played'): _rating('played', 8, at: 4),
          _s('withAlbum'): _rating('withAlbum', 8, at: 5),
          MediaId(MediaId.providerAppleMusic, 'am'): _rating('am', 8, at: 1, provider: MediaId.providerAppleMusic),
        },
        links: {_s('linked'): TrackAlbumLink(trackId: _s('linked'), updatedAt: 1)},
        plays: [TrackPlays(trackId: _s('played'), playCount: 1, lastPlayedAt: 1, albumId: _s('a'))],
      );
      expect(ids, [_s('noted'), _s('scored')]);
    });

    test('should_preferTheLinkOverAPlay_when_indexing', () {
      final index = trackAlbumIndex(
        links: {
          _s('t1'): TrackAlbumLink(trackId: _s('t1'), albumId: _s('fromLink'), updatedAt: 1, albumName: 'L'),
          _s('t3'): TrackAlbumLink(trackId: _s('t3'), updatedAt: 1),
        },
        plays: [
          TrackPlays(trackId: _s('t1'), playCount: 1, lastPlayedAt: 1, albumId: _s('fromPlay')),
          TrackPlays(trackId: _s('t2'), playCount: 1, lastPlayedAt: 1, albumId: _s('fromPlay'), album: 'P'),
        ],
        notes: [_note('n', _s('t4'), album: _s('fromNote'))],
      );
      expect(index[_s('t1')]!.albumId, _s('fromLink'));
      expect(index[_s('t2')]!.albumName, 'P');
      expect(index.containsKey(_s('t3')), isFalse);
      expect(index[_s('t4')]!.albumId, _s('fromNote'));
    });
  });

  group('TrackAlbumLinker', () {
    late InMemoryStore kv;
    late MemoryStore memory;
    late TrackAlbumLinkStore links;
    late Map<MediaId, LocalRating> ratings;
    var active = 'p1';

    setUp(() {
      kv = InMemoryStore();
      memory = MemoryStore(kv, profileId: 'p1');
      links = TrackAlbumLinkStore(kv, profileId: 'p1');
      ratings = {};
      active = 'p1';
    });

    TrackAlbumAccount account(MusicTrackLookup lookup, {String provider = MediaId.providerSubsonic}) =>
        TrackAlbumAccount(
          profileId: 'p1',
          provider: provider,
          lookup: lookup,
          memory: memory,
          links: links,
          ratings: () async => ratings,
        );

    TrackAlbumLinker linker(TrackAlbumAccount? a, {int budget = trackAlbumServiceBudget}) => TrackAlbumLinker(
      activeAccount: () async => a,
      isActive: (id) => id == active,
      clock: () => 99,
      spacing: Duration.zero,
      serviceBudget: budget,
    );

    test('should_linkEverySongAndNameItsNotes_when_enricherPasses', () async {
      await memory.putSongNote(_note('n1', _s('t1'), at: 1));
      ratings = {_s('t2'): _rating('t2', 9, at: 2), _s('gone'): _rating('gone', 4, at: 3)};
      final lookup = _Lookup({
        _s('t1'): Track(id: _s('t1'), title: 'First', artist: 'Ann', album: 'Blue', albumId: _s('a1')),
        _s('t2'): Track(id: _s('t2'), title: 'Second', albumId: _s('a2')),
      });
      expect(await linker(account(lookup)).runPass(), 3);
      final stored = await links.links();
      expect(stored[_s('t1')]!.albumId, _s('a1'));
      expect(stored[_s('t1')]!.albumName, 'Blue');
      expect(stored[_s('t2')]!.albumId, _s('a2'));
      // A song the server doesn't know is kept as asked, never asked again.
      expect(stored[_s('gone')]!.albumId, isNull);
      final note = (await memory.songNotes()).single;
      expect(note.title, 'First');
      expect(note.artist, 'Ann');
      expect(note.updatedAt, 1);
      lookup.asked.clear();
      expect(await linker(account(lookup)).runPass(), 0);
      expect(lookup.asked, isEmpty);
    });

    test('should_endThePassAndResumeLater_when_lookupFails', () async {
      ratings = {_s('t1'): _rating('t1', 9, at: 1), _s('t2'): _rating('t2', 8, at: 2)};
      final failing = _Lookup(
        {_s('t2'): Track(id: _s('t2'), albumId: _s('a'))},
        failing: {_s('t1')},
      );
      expect(await linker(account(failing)).runPass(), 0);
      expect(await links.links(), isEmpty);
      final back = _Lookup({
        _s('t1'): Track(id: _s('t1'), albumId: _s('a')),
        _s('t2'): Track(id: _s('t2'), albumId: _s('a')),
      });
      expect(await linker(account(back)).runPass(), 2);
    });

    test('should_stopThePass_when_accountSwitches', () async {
      ratings = {_s('t1'): _rating('t1', 9, at: 1), _s('t2'): _rating('t2', 8, at: 2)};
      final lookup = _Lookup({});
      final l = linker(account(lookup));
      active = 'p2';
      expect(await l.runPass(), 0);
      expect(lookup.asked, isEmpty);
    });

    test('should_leaveAppleMusicToPlaysAndPages_when_enricherPasses', () async {
      ratings = {MediaId(MediaId.providerAppleMusic, 'x'): _rating('x', 9, provider: MediaId.providerAppleMusic)};
      final lookup = _Lookup({});
      expect(await linker(account(lookup, provider: MediaId.providerAppleMusic)).runPass(), 0);
      expect(lookup.asked, isEmpty);
    });

    test('should_coalesceKicksIntoOneMorePass_when_kickedMidPass', () async {
      ratings = {_s('t1'): _rating('t1', 9)};
      final gate = Completer<void>();
      var passes = 0;
      final l = TrackAlbumLinker(
        activeAccount: () async {
          passes++;
          await gate.future;
          return null;
        },
        isActive: (_) => true,
        spacing: Duration.zero,
      );
      l
        ..kick()
        ..kick()
        ..kick();
      gate.complete();
      await l.awaitIdle();
      expect(passes, 2);
    });

    test('should_askEverySongOnTheServer_when_ledgerResolves', () async {
      final lookup = _Lookup({
        _s('t1'): Track(id: _s('t1'), title: 'One', albumId: _s('a')),
        _s('t2'): Track(id: _s('t2'), title: 'Two'),
      });
      final pass = await linker(null).resolve([
        TrackAlbumWanted('p1', _s('t1')),
        TrackAlbumWanted('p1', _s('t2')),
        TrackAlbumWanted('p1', _s('t3')),
        TrackAlbumWanted('p1', _s('t1')),
      ], accountFor: (id) async => id == 'p1' ? account(lookup) : null);
      expect(pass.answered, 3);
      expect(pass.found, isTrue);
      expect(lookup.asked.toSet(), {_s('t1'), _s('t2'), _s('t3')});
      expect((await links.links()).length, 3);
    });

    test('should_keepAServersAnswersABatchAtATime_when_manySongsResolve', () async {
      final songs = [for (var i = 0; i < 60; i++) _s('t$i')];
      final lookup = _Lookup({for (final id in songs) id: Track(id: id, title: 'Song', albumId: _s('a'))});
      var writes = 0;
      final sub = links.changes.listen((_) => writes++);
      final pass = await linker(null).resolve([
        for (final id in songs) TrackAlbumWanted('p1', id),
      ], accountFor: (id) async => account(lookup));
      await Future<void>.delayed(Duration.zero);
      expect(pass.answered, 60);
      expect((await links.links()).length, 60);
      // 60 answers, 25 to a write: three writes of the account's links, not sixty.
      expect(writes, (60 / trackAlbumServerBatch).ceil());
      await sub.cancel();
    });

    test('should_askAStreamingServiceAFewSongsOnly_when_budgetSpent', () async {
      MediaId am(String id) => MediaId(MediaId.providerAppleMusic, id);
      final lookup = _Lookup({for (var i = 0; i < 5; i++) am('s$i'): Track(id: am('s$i'), albumId: am('a'))});
      final l = linker(null, budget: 2);
      final wanted = [for (var i = 0; i < 5; i++) TrackAlbumWanted('p1', am('s$i'))];
      Future<TrackAlbumAccount?> accountFor(String id) async => account(lookup, provider: MediaId.providerAppleMusic);
      expect((await l.resolve(wanted, accountFor: accountFor)).answered, 2);
      // The process budget is spent: the next pass (the songs still unknown) asks nothing.
      expect((await l.resolve(wanted.skip(2).toList(), accountFor: accountFor)).answered, 0);
      expect(lookup.asked, hasLength(2));
      // Another account's streaming service is never asked from the ledger.
      active = 'p2';
      final other = _Lookup({});
      await linker(
        null,
      ).resolve(wanted, accountFor: (id) async => account(other, provider: MediaId.providerAppleMusic));
      expect(other.asked, isEmpty);
    });
  });

  test('should_fillOnlyMissingNames_when_songNamed', () async {
    final memory = MemoryStore(InMemoryStore(), profileId: 'p1');
    await memory.putSongNote(_note('blank', _s('t1'), at: 1));
    await memory.putSongNote(_note('named', _s('t1'), at: 2, title: 'Mine'));
    var changes = 0;
    final sub = memory.changes.listen((_) => changes++);
    await memory.fillMissingSong(_s('t1'), title: 'Server', artist: 'Band');
    await memory.fillMissingSong(_s('t1'), title: 'Server', artist: 'Band');
    await Future<void>.delayed(Duration.zero);
    final notes = {for (final n in await memory.songNotes()) n.id: n};
    expect(notes['blank']!.title, 'Server');
    expect(notes['blank']!.artist, 'Band');
    expect(notes['named']!.title, 'Mine');
    // The second fill found nothing to do: one change only.
    expect(changes, 1);
    await sub.cancel();
  });
}
