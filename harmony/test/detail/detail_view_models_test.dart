import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/activity/activity.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/detail/album_detail_view_model.dart';
import 'package:yoin/detail/artist_detail_view_model.dart';
import 'package:yoin/detail/detail_cache.dart';
import 'package:yoin/detail/detail_play.dart';
import 'package:yoin/detail/playlist_detail_view_model.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/playback/playback_controller.dart';

import '../support/fake_playback_engine.dart';
import '../support/library_fake_source.dart';

Future<void> _pump() async {
  for (var i = 0; i < 20; i++) {
    await Future<void>.delayed(Duration.zero);
  }
}

void main() {
  late LibraryFakeSource source;
  late StoredActivityLog log;
  late FakePlaybackEngine engine;
  late ProviderContainer c;

  final album = LibraryFixtures.album(
    'al1',
    name: 'Blue',
    artist: 'Joni',
    tracks: [
      LibraryFixtures.track('t1', artist: 'Joni'),
      LibraryFixtures.track('t2', artist: 'JONI'),
      LibraryFixtures.track('t3', artist: 'James Taylor'),
    ],
  );

  setUp(() {
    source = LibraryFakeSource(albumDetails: {album.id: album});
    log = StoredActivityLog(InMemoryStore(), profileId: 'p');
    engine = FakePlaybackEngine();
    c = ProviderContainer(
      overrides: [
        activeSourceProvider.overrideWith((ref) async => source),
        activityLogProvider.overrideWith((ref) async => log),
        playbackEngineProvider.overrideWithValue(engine),
      ],
    );
  });
  tearDown(() => c.dispose());

  group('album', () {
    test('should_loadAndRecordVisit_when_opened', () async {
      c.listen(albumDetailProvider(album.id), (_, _) {});
      await _pump();

      final state = c.read(albumDetailProvider(album.id));
      expect(state, isA<AlbumDetailContent>());
      final events = await log.recent();
      expect(events.single.entityType, ActivityEntityType.album);
      expect(events.single.actionType, ActivityActionType.visited);
      expect(events.single.entityId, 'al1');
      expect(events.single.subtitle, 'Joni');
    });

    test('should_creditOnlyOtherArtists_when_caseDiffers', () async {
      c.listen(albumDetailProvider(album.id), (_, _) {});
      await _pump();
      final content = c.read(albumDetailProvider(album.id)) as AlbumDetailContent;
      expect(content.tracks.map(content.featArtistOf), [null, null, 'James Taylor']);
      expect(content.trackCount, 3);
      expect(content.totalDurationSec, 540);
    });

    test('should_reportNotFound_when_sourceHasNoAlbum', () async {
      final missing = MediaId.subsonic('nope');
      c.listen(albumDetailProvider(missing), (_, _) {});
      await _pump();
      expect(
        c.read(albumDetailProvider(missing)),
        isA<AlbumDetailError>().having((e) => e.notFound, 'notFound', isTrue),
      );
    });

    test('should_reportLoadError_when_readThrows', () async {
      source.failures['getAlbum'] = const SourceNetworkException('down');
      c.listen(albumDetailProvider(album.id), (_, _) {});
      await _pump();
      expect(
        c.read(albumDetailProvider(album.id)),
        isA<AlbumDetailError>().having((e) => e.notFound, 'notFound', isFalse),
      );

      source.failures.clear();
      await c.read(albumDetailProvider(album.id).notifier).retry();
      expect(c.read(albumDetailProvider(album.id)), isA<AlbumDetailContent>());
    });

    test('should_keepCauseAndDiagnostics_when_readThrows', () async {
      const failure = SourceNetworkException('down', kind: NetworkFailureKind.timeout);
      source.failures['getAlbum'] = failure;
      c.listen(albumDetailProvider(album.id), (_, _) {});
      await _pump();
      final error = c.read(albumDetailProvider(album.id)) as AlbumDetailError;
      expect(error.cause, same(failure));
      expect(error.diagnostics, contains('Network timeout'));
      expect(error.diagnostics, startsWith('Yoin '));
    });

    test('should_haveNoDiagnostics_when_albumNotFound', () async {
      c.listen(albumDetailProvider(MediaId.subsonic('missing')), (_, _) {});
      await _pump();
      final error = c.read(albumDetailProvider(MediaId.subsonic('missing'))) as AlbumDetailError;
      expect(error.notFound, isTrue);
      expect(error.diagnostics, isNull);
    });
  });

  group('detail cache', () {
    test('should_joinOneRead_when_prefetchAndPageLoadOverlap', () async {
      final gate = Completer<void>();
      source.albumGate = gate;
      final cache = c.read(detailCacheProvider);
      final a = cache.album(album.id);
      final b = cache.album(album.id);
      gate.complete();
      expect(await a, same(await b));
      expect(source.calls.where((call) => call == 'getAlbum'), hasLength(1));

      await cache.album(album.id);
      expect(source.calls.where((call) => call == 'getAlbum'), hasLength(1), reason: 'served from memory');
    });
  });

  group('artist', () {
    final releases = [
      LibraryFixtures.album('r1', year: 2016),
      LibraryFixtures.album('r2'),
      LibraryFixtures.album('r3', year: 2020),
      LibraryFixtures.album('r4', year: 2025),
    ];

    test('should_putNewestFirstUndatedLast_when_listCameOldestFirst', () {
      expect(newestReleasesFirst(releases).map((a) => a.id.rawId), ['r4', 'r3', 'r1', 'r2']);
      expect(artistActiveSpan(releases), '2016 – 2025');
      expect(artistActiveSpan([LibraryFixtures.album('x', year: 2001)]), '2001');
      expect(artistActiveSpan([LibraryFixtures.album('x')]), isNull);
    });

    test('should_recordVisitAndPrefetch_when_loaded', () async {
      final artist = ArtistDetail(id: MediaId.subsonic('ar'), name: 'Joni', albums: releases);
      source.artistDetails[artist.id] = artist;
      c.listen(artistDetailProvider(artist.id), (_, _) {});
      await _pump();

      final state = c.read(artistDetailProvider(artist.id)) as ArtistDetailContent;
      expect(state.releases.first.id.rawId, 'r4');
      expect((await log.recent()).single.entityType, ActivityEntityType.artist);
      expect(source.calls.where((call) => call == 'getAlbum'), hasLength(4));
    });

    test('should_readThreeAlbumsAtATimeInOrder_when_artistPlays', () async {
      final albums = <Album>[];
      for (var i = 0; i < 7; i++) {
        final a = LibraryFixtures.album('p$i', tracks: [LibraryFixtures.track('pt$i')]);
        source.albumDetails[a.id] = a;
        albums.add(a);
      }
      source.failures['getAlbum:p2'] = const SourceNetworkException('down');
      final gate = Completer<void>();
      source.albumGate = gate;
      final future = loadArtistTracks(albums, source.getAlbum);
      await _pump();
      expect(source.albumReadsInFlight, 3);
      gate.complete();
      final tracks = await future;
      expect(tracks.map((t) => t.id.rawId), ['pt0', 'pt1', 'pt3', 'pt4', 'pt5', 'pt6']);
    });

    test('should_keepNewerQueue_when_songStartedElsewhereDuringArtistRead', () async {
      final a = LibraryFixtures.album('q0', tracks: [LibraryFixtures.track('qt0')]);
      source.albumDetails[a.id] = a;
      final artist = ArtistDetail(id: MediaId.subsonic('ar'), name: 'Joni', albums: [a]);
      final playerProvider = Provider((ref) => DetailPlayer.ref(ref));
      final player = c.read(playerProvider);
      c.listen(playbackControllerProvider, (_, _) {});
      final gate = Completer<void>();
      source.albumGate = gate;

      final artistPlay = playArtist(player, artist: artist, releases: [a], cover: null);
      await _pump();
      // A Library / Home / search row starts a song while the albums load.
      await player.controller.playTracks([LibraryFixtures.track('elsewhere')]);
      gate.complete();
      await artistPlay;
      await _pump();

      expect(engine.queue.map((i) => i.id), ['subsonic:elsewhere']);
    });
  });

  group('load errors', () {
    test('should_keepCauseAndDiagnostics_when_artistReadThrows', () async {
      const failure = SourceAuthException('Wrong username or password', code: 40);
      source.failures['getArtist'] = failure;
      final id = MediaId.subsonic('ar1');
      c.listen(artistDetailProvider(id), (_, _) {});
      await _pump();
      final error = c.read(artistDetailProvider(id)) as ArtistDetailError;
      expect(error.notFound, isFalse);
      expect(error.cause, same(failure));
      expect(error.diagnostics, contains('Subsonic auth 40'));
      expect(error.diagnostics, isNot(contains('Wrong username')));
    });

    test('should_keepCauseAndDiagnostics_when_playlistReadThrows', () async {
      const failure = SourceNetworkException('x', kind: NetworkFailureKind.tls);
      source.failures['getPlaylist'] = failure;
      final id = MediaId.subsonic('pl1');
      c.listen(playlistDetailProvider(id), (_, _) {});
      await _pump();
      final error = c.read(playlistDetailProvider(id)) as PlaylistDetailError;
      expect(error.cause, same(failure));
      expect(error.diagnostics, contains('Network tls'));
    });
  });

  group('playlist', () {
    test('should_keyRowsAndPickSleeves_when_loaded', () async {
      final shared = LibraryFixtures.track('s', albumId: 'x');
      final playlist = Playlist(
        id: MediaId.subsonic('pl'),
        name: 'Mix',
        coverArt: const CoverSourceRelative('pl'),
        tracks: [
          shared,
          shared,
          LibraryFixtures.track('t', albumId: 'y'),
          LibraryFixtures.track('u', albumId: 'z'),
        ],
      );
      source.playlistDetails[playlist.id] = playlist;
      c.listen(playlistDetailProvider(playlist.id), (_, _) {});
      await _pump();

      final state = c.read(playlistDetailProvider(playlist.id)) as PlaylistDetailContent;
      expect(state.entries.map((e) => e.rowKey), ['row-0', 'row-1', 'row-2', 'row-3']);
      expect(state.heroCover, const CoverSourceRelative('pl'));
      expect(playlistSleeveCovers(playlist), [const CoverSourceRelative('x'), const CoverSourceRelative('y')]);
    });
  });

  group('favorite overrides', () {
    test('should_showTargetThenRevert_when_writeFails', () async {
      final id = MediaId.subsonic('t1');
      source.failures['setFavorite'] = const SourceNetworkException('down');
      final overrides = c.read(favoriteOverridesProvider.notifier);
      c.listen(favoriteOverridesProvider, (_, _) {});

      final write = overrides.setFavorite(id, target: true, previous: false);
      expect(c.read(favoriteOverridesProvider)[id], isTrue);
      expect(await write, isFalse);
      expect(c.read(favoriteOverridesProvider)[id], isFalse);
    });

    test('should_keepTarget_when_writeLands', () async {
      final id = MediaId.subsonic('t1');
      c.listen(favoriteOverridesProvider, (_, _) {});
      expect(await c.read(favoriteOverridesProvider.notifier).setFavorite(id, target: true, previous: false), isTrue);
      expect(c.read(favoriteOverridesProvider)[id], isTrue);
      expect(source.favoriteWrites.single, (id, true));
    });
  });
}
