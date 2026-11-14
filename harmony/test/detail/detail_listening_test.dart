import 'dart:ui';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/detail/album_hero_marks.dart';
import 'package:yoin/detail/album_spectrum.dart';
import 'package:yoin/detail/artist_detail_view_model.dart';
import 'package:yoin/detail/detail_listening.dart';
import 'package:yoin/detail/detail_strings.dart';
import 'package:yoin/detail/dot_dissolve.dart';
import 'package:yoin/memories/album_score.dart';
import 'package:yoin/platform/playback/playback_engine.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/playback/playback_controller.dart';

import '../support/fake_playback_engine.dart';
import '../support/library_fake_source.dart';

Future<void> _pump() async {
  for (var i = 0; i < 20; i++) {
    await Future<void>.delayed(Duration.zero);
  }
}

Track _track(String id, {String? albumId, String? artist, String provider = MediaId.providerSubsonic, int? sec}) =>
    Track(
      id: MediaId(provider, id),
      title: 'Song $id',
      artist: artist,
      album: albumId == null ? null : 'Album $albumId',
      albumId: albumId == null ? null : MediaId(provider, albumId),
      durationSec: sec,
    );

void main() {
  group('track plays', () {
    test('should_countAndKeepLatestFacts_when_songPlayedTwice', () async {
      final store = MemoryStore(InMemoryStore(), profileId: 'p1');
      await store.recordTrackPlay(_track('s1', albumId: 'a', artist: 'Joni', sec: 200), atMs: 10);
      await store.recordTrackPlay(_track('s1', albumId: 'a', artist: ' ', sec: 180), atMs: 30);
      await store.recordTrackPlay(_track('s2', artist: 'Joni'), atMs: 20);

      final rows = await store.trackPlays();
      expect(rows.map((r) => r.trackId.rawId), ['s1', 's2']);
      expect(rows.first.playCount, 2);
      expect(rows.first.lastPlayedAt, 30);
      expect(rows.first.artist, 'Joni', reason: 'a blank name never overwrites');
      expect(rows.first.durationSec, 200, reason: 'MAX(durationMs)');
    });

    test('should_keepAccountsApartAndSurviveReread_when_twoProfilesPlay', () async {
      final kv = InMemoryStore();
      await MemoryStore(kv, profileId: 'p1').recordTrackPlay(_track('s1', albumId: 'a'), atMs: 1);
      expect(await MemoryStore(kv, profileId: 'p2').trackPlays(), isEmpty);
      final reread = await MemoryStore(kv, profileId: 'p1').trackPlays();
      expect(reread.single.albumId, MediaId.subsonic('a'));
      expect(reread.single.title, 'Song s1');
    });

    test('should_recordSongAndAlbum_when_playbackStarts', () async {
      final engine = FakePlaybackEngine();
      final store = MemoryStore(InMemoryStore(), profileId: 'p');
      final c = ProviderContainer(
        overrides: [
          activeSourceProvider.overrideWith((ref) async => LibraryFakeSource()),
          activityLogProvider.overrideWith((ref) async => null),
          memoryStoreProvider.overrideWith((ref) async => store),
          playbackEngineProvider.overrideWithValue(engine),
        ],
      );
      addTearDown(c.dispose);
      c.listen(playbackControllerProvider, (_, _) {});
      final tracks = [_track('s1', albumId: 'a', artist: 'Joni'), _track('s2', artist: 'Joni')];
      await c.read(playbackControllerProvider.notifier).playTracks(tracks);
      engine.emit(
        EngineState(phase: PlaybackPhase.ready, isPlaying: true, index: 0, currentId: tracks[0].id.toString()),
      );
      await _pump();
      engine.emit(
        EngineState(phase: PlaybackPhase.ready, isPlaying: true, index: 1, currentId: tracks[1].id.toString()),
      );
      await _pump();

      final plays = await store.trackPlays();
      expect(plays.map((r) => r.trackId.rawId).toSet(), {'s1', 's2'}, reason: 'a song without an album counts too');
      expect((await store.playsOf(MediaId.subsonic('a')))!.playCount, 1);
    });
  });

  group('artist listening', () {
    final artist = MediaId.subsonic('ar');
    TrackPlays row(String id, int n, int at, {String? album, String artist = '', String provider = 'subsonic'}) =>
        TrackPlays(
          trackId: MediaId(provider, id),
          playCount: n,
          lastPlayedAt: at,
          albumId: album == null ? null : MediaId(provider, album),
          artist: artist,
        );

    test('should_matchReleasesOrExactName_when_listening', () {
      final listening = artistListeningFrom(
        [
          row('a', 3, 10, album: 'r1'),
          row('b', 3, 20, artist: 'Joni'),
          row('c', 9, 5, artist: 'joni'),
          row('d', 9, 5, album: 'r1', provider: 'spotify'),
          row('e', 1, 1, album: 'r2'),
        ],
        artistId: artist,
        artistName: 'Joni',
        albumIds: [MediaId.subsonic('r1'), MediaId.subsonic('r2'), MediaId('spotify', 'r1')],
      );
      expect(listening.mostPlayed.map((r) => r.trackId.rawId), ['b', 'a', 'e'], reason: 'count, then latest');
      expect(listening.playCount, 7);
    });

    test('should_listTopFive_when_manySongsPlayed', () {
      final listening = artistListeningFrom(
        [for (var i = 0; i < 8; i++) row('s$i', i + 1, i, artist: 'Joni')],
        artistId: artist,
        artistName: 'Joni',
        albumIds: const [],
      );
      expect(listening.mostPlayed, hasLength(artistMostPlayedLimit));
      expect(listening.mostPlayed.first.trackId.rawId, 's7');
      expect(listening.playCount, 36);
    });

    test('should_beEmpty_when_noPlays', () {
      expect(
        artistListeningFrom(const [], artistId: artist, artistName: 'Joni', albumIds: const []),
        ArtistListening.empty,
      );
    });

    test('should_showNewPlay_when_songPlayedWhilePageOpen', () async {
      final releases = [LibraryFixtures.album('r1', year: 2020)];
      final detail = ArtistDetail(id: artist, name: 'Joni', albums: releases);
      final source = LibraryFakeSource()..artistDetails[artist] = detail;
      final store = MemoryStore(InMemoryStore(), profileId: 'p');
      final c = ProviderContainer(
        overrides: [
          activeSourceProvider.overrideWith((ref) async => source),
          activityLogProvider.overrideWith((ref) async => null),
          memoryStoreProvider.overrideWith((ref) async => store),
        ],
      );
      addTearDown(c.dispose);
      c.listen(artistListeningProvider(artist), (_, _) {});
      await _pump();
      expect(c.read(artistListeningProvider(artist)).mostPlayed, isEmpty);

      await store.recordTrackPlay(_track('t1', albumId: 'r1', artist: 'Someone'), atMs: 5);
      await _pump();
      final listening = c.read(artistListeningProvider(artist));
      expect(listening.mostPlayed.single.trackId.rawId, 't1');
      expect(c.read(artistDetailProvider(artist)), isA<ArtistDetailContent>());
      final queue = listening.tracks(artistId: artist, artistName: 'Joni');
      expect(queue.single.artist, 'Joni');
      expect(queue.single.albumId, MediaId.subsonic('r1'));
    });
  });

  group('last play', () {
    final now = DateTime(2026, 11, 15, 20, 0);
    int at(DateTime t) => t.millisecondsSinceEpoch;

    test('should_sayTodayYesterdayOrDaysAgo_when_withinAWeek', () {
      const s = DetailStrings.en;
      expect(lastPlayLabels(at(DateTime(2026, 11, 15, 9, 5)), s, now: now), ('Today', '09:05'));
      expect(lastPlayLabels(at(DateTime(2026, 11, 14, 23, 59)), s, now: now), ('Yesterday', '23:59'));
      expect(lastPlayLabels(at(DateTime(2026, 11, 13, 16, 4)), s, now: now).$1, '2 days ago');
      expect(lastPlayLabels(at(DateTime(2026, 11, 9, 16, 4)), s, now: now).$1, '6 days ago');
    });

    test('should_sayMonthAndDay_when_aWeekOrMoreAgo', () {
      expect(lastPlayLabels(at(DateTime(2026, 11, 8)), DetailStrings.en, now: now).$1, 'Nov 8');
      expect(lastPlayLabels(at(DateTime(2025, 3, 2)), DetailStrings.en, now: now).$1, 'Mar 2');
    });

    test('should_followAlbumPlays_when_songOfAlbumStarts', () async {
      final store = MemoryStore(InMemoryStore(), profileId: 'p');
      final album = MediaId.subsonic('al');
      final c = ProviderContainer(overrides: [memoryStoreProvider.overrideWith((ref) async => store)]);
      addTearDown(c.dispose);
      c.listen(albumLastPlayedProvider(album), (_, _) {});
      await _pump();
      expect(c.read(albumLastPlayedProvider(album)), isNull);

      await store.recordAlbumPlay(album, atMs: 42);
      await _pump();
      expect(c.read(albumLastPlayedProvider(album)), 42);
    });
  });

  group('rated coverage', () {
    test('should_showCoverage_when_scoreIsTrackAverage', () {
      const s = DetailStrings.en;
      expect(albumRatedCoverageText(const AlbumScore(average: 7, ratedTracks: [true, false, true]), s), '2/3 rated');
      expect(
        albumRatedCoverageText(const AlbumScore(albumRating: 9, average: 7, ratedTracks: [true, false]), s),
        isNull,
        reason: 'a manual album score stands alone',
      );
      expect(albumRatedCoverageText(const AlbumScore(ratedTracks: [false, false]), s), isNull);
    });
  });

  group('spectrum', () {
    const n = 28;
    List<int> twoTone() => [
      for (var y = 0; y < n; y++)
        for (var x = 0; x < n; x++) x < 10 ? 0xFFE53935 : 0xFF1E88E5,
    ];

    test('should_bandEveryStripIntoTheBar_when_coverHasTwoColours', () {
      final s = computeAlbumSpectrum(twoTone(), n);
      expect(s.columns, n);
      expect(s.target.toSet(), {for (var i = 0; i < n; i++) i}, reason: 'every slot taken once');
      expect(s.color.toSet(), hasLength(2));
      expect(computeAlbumSpectrum(twoTone(), n).target, s.target, reason: 'deterministic');
      // The red strips (a third of the cover) land as one contiguous band.
      final redSlots = [for (var x = 0; x < 10; x++) s.target[x]]..sort();
      expect(redSlots.last - redSlots.first, 9);
    });

    test('should_splitIntoTwoBands_when_coverIsOneColour', () {
      final s = computeAlbumSpectrum(List.filled(n * n, 0xFF4CAF50), n);
      expect(s.color.toSet(), hasLength(2));
    });

    test('should_holdWhiteTextContrast_when_toneLocked', () {
      for (final c in [0xFFFFFFFF, 0xFFB5FFBE, 0xFF000000, 0xFFFFEB3B, 0xFF00E5FF]) {
        expect(contrastWithWhite(lockTone(c)), greaterThanOrEqualTo(AlbumSpectrumTokens.minContrast - 0.05));
      }
    });

    test('should_fillEqualBandsInPlace_when_fallback', () {
      final s = AlbumSpectrum.fallback(const [Color(0xFFFF0000), Color(0xFF00FF00)], columns: 4);
      expect(s.target, [0, 1, 2, 3]);
      expect(s.color[0], s.color[1]);
      expect(s.color[1], isNot(s.color[2]));
    });

    test('should_landBandsInSlotOrder_when_pullUpAdvances', () {
      final s = AlbumSpectrum.fallback(const [Color(0xFF336699)], columns: 10);
      expect(albumSpectrumLanded(s, 0), 0);
      expect(albumSpectrumLanded(s, 0.6), 1);
      expect(albumSpectrumLanded(s, 1), 10);
    });
  });

  group('dot dissolve', () {
    test('should_shrinkDotsThenFade_when_leaving', () {
      expect(dotDissolveShrink(0), 1);
      expect(dotDissolveShrink(1 / 1.35), closeTo(0, 1e-9));
      expect(dotDissolveShrink(0.3), inExclusiveRange(0, 1));
      expect(dotDissolveAlpha(0.5), 1);
      expect(dotDissolveAlpha(1), 0);
    });
  });
}
