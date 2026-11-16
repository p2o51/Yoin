import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/memory/album_memory_candidate.dart';
import 'package:yoin/data/memory/memory_copy_cache.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/home/home_grid_pool_store.dart';
import 'package:yoin/home/home_memory_signals.dart';
import 'package:yoin/home/home_screen.dart';
import 'package:yoin/home/home_selection.dart';
import 'package:yoin/home/home_ui_state.dart';
import 'package:yoin/home/home_view_model.dart';
import 'package:yoin/home/jump_back_in.dart';
import 'package:yoin/home/rediscover_section.dart';
import 'package:yoin/home/rediscover_selection.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/theme/theme.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

import 'home_fakes.dart';

const int _day = 24 * 60 * 60 * 1000;
final int _now = homeTestNow.millisecondsSinceEpoch;

AlbumMemoryCandidate candidate(
  String id, {
  double? albumRating,
  double? average,
  double coverage = 0,
  bool review = false,
  int notes = 0,
  int rated = 0,
  int total = 10,
  bool eligible = true,
  AlbumPlays? plays,
  int? lastWrittenAt,
}) =>
    AlbumMemoryCandidate(
      profileId: 'p',
      albumId: MediaId.subsonic(id),
      albumName: 'Album $id',
      artistName: 'Artist',
      totalTracks: total,
      ratedTrackCount: rated,
      ratingCoverage: coverage,
      averageSongRating: average,
      albumRating: albumRating,
      hasAlbumReview: review,
      noteCount: notes,
      lastPlayedAt: plays?.lastPlayedAt,
      firstPlayedAt: plays?.firstPlayedAt,
      lastWrittenAt: lastWrittenAt,
      isMemoryEligible: eligible,
      plays: plays,
    );

AlbumPlays playedDaysAgo(String id, int days, {int count = 3}) => AlbumPlays(
      albumId: MediaId.subsonic(id),
      playCount: count,
      firstPlayedAt: _now - (days + 30) * _day,
      lastPlayedAt: _now - days * _day,
    );

SongNote note(String id, String track, {int minutesAgo = 1, String content = 'the bridge', String? album}) => SongNote(
      id: id,
      trackId: MediaId.subsonic(track),
      albumId: album == null ? null : MediaId.subsonic(album),
      content: content,
      createdAt: _now - minutesAgo * 60000,
      updatedAt: _now - minutesAgo * 60000,
      title: 'Song $track',
      artist: 'Artist',
    );

void main() {
  group('signal cards', () {
    test('should_dateTheRecord_when_reviewedOrHandScored', () {
      final dated = memoryCardBasis(hasReview: true, hasManualRating: false, ratedTrackCount: 3, totalTracks: 10, lastTouchedAt: 42);
      expect(dated.basisDate, 42);
      expect(dated.trackBasis, isFalse);
      final averaged = memoryCardBasis(hasReview: false, hasManualRating: false, ratedTrackCount: 3, totalTracks: 10, lastTouchedAt: 42);
      expect(averaged.basisDate, isNull);
      expect(averaged.trackBasis, isTrue);
    });

    test('should_preferReviewedAndAvoidTheHeaderLatest_when_picking', () {
      final pool = [
        candidate('a', albumRating: 8, review: true),
        candidate('b', albumRating: 9),
        candidate('c', review: true),
        candidate('d'),
      ];
      expect(pickJbiMemoryCandidate(pool, avoidRawAlbumId: 'a')!.albumId.rawId, 'c');
      expect(pickJbiMemoryCandidate(pool, avoidRawAlbumId: 'a', preferRawAlbumId: 'b')!.albumId.rawId, 'b');
      expect(pickJbiMemoryCandidates(pool, avoidRawAlbumId: 'a', count: 3).map((c) => c.albumId.rawId), ['c', 'b']);
      expect(pickJbiMemoryCandidate([candidate('a', review: true)], avoidRawAlbumId: 'a')!.albumId.rawId, 'a');
      expect(pickJbiMemoryCandidates([candidate('d')]), isEmpty);
    });

    test('should_buildWideCardsWithScoreAndBasis_when_memoryAndNote', () {
      final memory = memoryWidgetCard(candidate('a', average: 7.25, rated: 4, coverage: 0.4));
      expect(memory.expanded, isTrue);
      expect(memory.signal!.scoreText, '7.3');
      expect(memory.signal!.basisTracks, (4, 10));
      expect(memory.target, isA<HomeOpenMemory>());
      final noted = noteWidgetCard(note('n1', 't1', album: 'al1'), rating: 8);
      expect(noted.signal!.scoreText, '8.0');
      expect(noted.signal!.comment, 'the bridge');
      expect((noted.target as HomePlaySong).track.id, MediaId.subsonic('t1'));
      expect(noteWidgetCard(note('n2', 't2')).signal!.scoreText, isNull);
      expect(memory.signal!.comment, isNull);
      final titled = memoryWidgetCard(candidate('a', review: true), title: ' Late summer ');
      expect(titled.signal!.comment, 'Late summer');
      expect(titled.signal!.commentIsHeadline, isTrue);
    });

    test('should_takeNewestNotePerSong_when_pickingNotes', () {
      final picked = pickJbiNotes([note('1', 't1'), note('2', 't1'), note('3', 't2', content: '  '), note('4', 't3'), note('5', 't4')]);
      expect(picked.map((n) => n.id), ['1', '4', '5']);
    });

    test('should_dropCoversAWideCardShows_when_seating', () {
      final covers = [albumCard(album('a')), songCard(track('t1')), albumCard(album('b'))];
      final wide = [memoryWidgetCard(candidate('a', review: true)), noteWidgetCard(note('n', 't1'))];
      final seated = withJbiSignals(covers, wide);
      expect(seated.map((c) => c.stableId), [wide[0].stableId, wide[1].stableId, 'grid-album:b']);
      expect(withJbiSignals(covers, const []), same(covers));
    });

    test('should_rankMemoryThenNoteThenExtras_when_wideCards', () {
      final m = [for (final id in ['a', 'b']) memoryWidgetCard(candidate(id, review: true))];
      final n = [noteWidgetCard(note('1', 't1')), noteWidgetCard(note('2', 't2'))];
      final s = HomeMemorySignals(memoryCards: m, noteCards: n);
      expect(s.wideCards, [m[0], n[0], m[1], n[1]]);
    });
  });

  group('rediscover', () {
    test('should_bringBackOnlyAlbumsAwayNinetyDays_when_selecting', () {
      final pool = [
        candidate('recent', albumRating: 9, plays: playedDaysAgo('recent', 30)),
        candidate('away', albumRating: 8, plays: playedDaysAgo('away', 120)),
        candidate('blank', plays: playedDaysAgo('blank', 200)),
        candidate('neverPlayed', albumRating: 7),
        candidate('single', albumRating: 9, total: 2, eligible: false, plays: playedDaysAgo('single', 200)),
      ];
      expect(selectRediscover(pool, nowMs: _now).map((p) => p.rawAlbumId), ['away']);
    });

    test('should_orderScoredBestFirstThenLongestAway_when_selecting', () {
      final pool = [
        candidate('u1', review: true, plays: playedDaysAgo('u1', 100)),
        candidate('s7', albumRating: 7, plays: playedDaysAgo('s7', 100)),
        candidate('s9a', albumRating: 9, plays: playedDaysAgo('s9a', 100)),
        candidate('s9b', albumRating: 9, plays: playedDaysAgo('s9b', 400)),
        candidate('avgLow', average: 9.5, coverage: 0.3, rated: 3, plays: playedDaysAgo('avgLow', 100)),
      ];
      expect(selectRediscover(pool, nowMs: _now).map((p) => p.rawAlbumId), ['s9b', 's9a', 's7', 'u1', 'avgLow']);
      expect(selectRediscover(pool, nowMs: _now, excludeRawAlbumIds: {'s9b'}).first.rawAlbumId, 's9a');
    });

    test('should_mixEveryThirdANoteCard_when_bothKinds', () {
      final listening = [for (var i = 0; i < 6; i++) RediscoverPick(candidate: candidate('l$i'), lastPlayedAt: 1)];
      final notes = [for (var i = 0; i < 3; i++) RediscoverPick(candidate: candidate('n$i'), lastPlayedAt: 0, fromNotes: true)];
      expect(mixRediscoverShelf(listening, notes).map((p) => p.rawAlbumId), ['l0', 'l1', 'n0', 'l2', 'l3', 'n1']);
      expect(mixRediscoverShelf(const [], notes).map((p) => p.rawAlbumId), ['n0', 'n1', 'n2']);
    });

    test('should_pickNoteCards_when_writtenLongAgoAndNeverPlayed', () {
      final pool = [
        candidate('old', notes: 2, lastWrittenAt: _now - 200 * _day),
        candidate('fresh', notes: 2, lastWrittenAt: _now - 2 * _day),
        candidate('played', notes: 2, lastWrittenAt: _now - 200 * _day, plays: playedDaysAgo('played', 10)),
      ];
      expect(selectRediscoverNotes(pool, nowMs: _now).map((p) => p.rawAlbumId), ['old']);
    });

    test('should_countWholeMonthsThenYears_when_away', () {
      expect(rediscoverAway(_now - 10 * _day, _now), (count: 1, years: false));
      expect(rediscoverAway(_now - 215 * _day, _now), (count: 7, years: false));
      expect(rediscoverAway(_now - 800 * _day, _now), (count: 2, years: true));
    });

    test('should_sayWhatWasKept_when_noBadge', () {
      HomeRediscoverItem item({double? score, bool review = false, int notes = 0, int rated = 0, bool fromNotes = false}) =>
          HomeRediscoverItem(albumId: MediaId.subsonic('a'), albumName: 'A', score: score, lastPlayedAt: 0, hasReview: review,
              noteCount: notes, ratedTrackCount: rated, fromNotes: fromNotes);
      expect(rediscoverReason(item(score: 8, review: true)), isNull);
      expect(rediscoverReason(item(score: 8, notes: 2, fromNotes: true)), RediscoverReason.notes);
      expect(rediscoverReason(item(review: true, notes: 2)), RediscoverReason.reviewed);
      expect(rediscoverReason(item(rated: 3)), RediscoverReason.tracksRated);
      expect(rediscoverVisibleCount(2, landscape: false), 6);
      expect(rediscoverVisibleCount(4, landscape: false), 2);
      expect(rediscoverVisibleCount(6, landscape: false), 3);
      expect(rediscoverVisibleCount(2, landscape: true), 2);
    });
  });

  group('on Home', () {
    setUp(() {
      HomeViewModel.clearProcessCache();
      HomeScreen.resetProcessState();
    });

    Future<ProviderContainer> memoryHome(WidgetTester tester, Size logical) async {
      tester.view.physicalSize = logical;
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final store = InMemoryStore();
      final h = await homeContainer(store: store, source: HomeFakeSource(playlists: [playlist('p1')], albums: {'g0': Album(id: MediaId.subsonic('g0'), name: 'Album g0', artist: 'Artist', songCount: 10)}));
      final profiles = jsonDecode(store.values['profiles.v1']!) as List;
      final id = (profiles.single as Map)['id'] as String;
      store.values[HomeGridPoolStore.keyFor(id, MediaId.providerSubsonic)] = jsonEncode(
        HomeGridPools(cachedAtMs: _now, albums: [for (var i = 0; i < 14; i++) album('g$i')], tracks: [
          for (var i = 0; i < 10; i++) track('t$i'),
        ]).toJson(),
      );
      final memories = MemoryStore(store, profileId: id);
      // A reviewed album (the memory card), a note (the note card), and an
      // album rated and played four months ago (Rediscover).
      await memories.setAlbumReview(MediaId.subsonic('g0'), 'Late summer', nowMs: _now - _day);
      await memories.setAlbumRating(MediaId.subsonic('g0'), 8.5, nowMs: _now - _day);
      await memories.putSongNote(note('n1', 't1', album: 'al-t1'));
      await memories.setAlbumRating(MediaId.subsonic('old'), 9, nowMs: _now - 200 * _day);
      await memories.recordAlbumPlay(MediaId.subsonic('old'), atMs: _now - 120 * _day, albumName: 'Old Friend', artistName: 'Band');
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: h.container,
          child: MaterialApp(
            theme: yoinTheme(Brightness.light),
            builder: (context, child) => YoinWindowScope.measured(child: child!),
            home: const Scaffold(body: HomeScreen()),
          ),
        ),
      );
      for (var i = 0; i < 40; i++) {
        await tester.pump(const Duration(milliseconds: 50));
      }
      return h.container;
    }

    Future<void> done(WidgetTester tester, ProviderContainer container) async {
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump(const Duration(seconds: 1));
      container.dispose();
    }

    testWidgets('should_seatMemoryAndNoteCards_when_phone', (tester) async {
      final container = await memoryHome(tester, const Size(360, 800));
      final signals = container.read(homeMemorySignalsProvider);
      expect(signals.resolved, isTrue);
      expect(signals.memoryCards.single.title, 'Album g0');
      expect(signals.noteCards.single.signal!.comment, 'the bridge');
      expect(find.byType(JbiSignalCard), findsNWidgets(2));
      expect(find.text('8.5'), findsWidgets);
      expect(find.text('the bridge'), findsOneWidget);
      // The covers the wide cards show are off the shelf.
      expect(find.byKey(const ValueKey('grid-album:g0')), findsNothing);
      // The phone packs 12 cells: two wide cards and eight covers.
      expect(find.byType(JbiCoverCell), findsNWidgets(8));
      await done(tester, container);
    });

    testWidgets('should_headlineSyncedAiTitle_when_cloudSyncBringsOne', (tester) async {
      final container = await memoryHome(tester, const Size(360, 800));
      expect(container.read(homeMemorySignalsProvider).memoryCards.single.signal!.comment, isNot('The long August'));
      final profile = await container.read(profileRepositoryProvider).activeProfile();
      final cache = MemoryCopyCache.shared(container.read(keyValueStoreProvider), profile!.id);
      await cache.syncEdit((rows) => rows[MediaId.subsonic('g0')] = const MemoryCopyRow(title: 'The long August'));
      container.read(memoryCopySyncRevisionProvider.notifier).bump();
      for (var i = 0; i < 10; i++) {
        await tester.pump(const Duration(milliseconds: 100));
      }
      final card = container.read(homeMemorySignalsProvider).memoryCards.single;
      expect(card.signal!.comment, 'The long August');
      expect(card.signal!.commentIsHeadline, isTrue);
      await done(tester, container);
    });

    testWidgets('should_seatSignalsOnTheTemplate_when_tablet', (tester) async {
      final container = await memoryHome(tester, const Size(1280, 800));
      expect(find.byType(JbiSignalCard), findsNWidgets(2));
      expect(tester.takeException(), isNull);
      await done(tester, container);
    });

    testWidgets('should_showRediscover_when_aRatedAlbumIsAway', (tester) async {
      final container = await memoryHome(tester, const Size(360, 800));
      final rediscover = container.read(homeMemorySignalsProvider).rediscover;
      expect(rediscover.map((r) => r.albumId.rawId), ['old']);
      await tester.scrollUntilVisible(find.byType(RediscoverCard), 300, scrollable: find.byType(Scrollable).first);
      expect(find.byType(RediscoverCard), findsOneWidget);
      expect(find.text('4 months away'), findsOneWidget);
      expect(find.text('9.0'), findsOneWidget);
      await done(tester, container);
    });
  });
}
