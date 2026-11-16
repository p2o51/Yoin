import 'dart:math' as math;

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/activity/activity.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/home/home_feed_density.dart';
import 'package:yoin/home/home_grid_pool_store.dart';
import 'package:yoin/home/home_row_presets.dart';
import 'package:yoin/home/home_section.dart';
import 'package:yoin/home/home_selection.dart';
import 'package:yoin/home/home_strings.dart';
import 'package:yoin/home/home_ui_state.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

import 'home_fakes.dart';

void main() {
  group('greeting', () {
    test('should_pickDayPartByHourBoundaries_when_hourGiven', () {
      expect(homeDayPartAt(4), HomeDayPart.evening);
      expect(homeDayPartAt(5), HomeDayPart.morning);
      expect(homeDayPartAt(10), HomeDayPart.morning);
      expect(homeDayPartAt(11), HomeDayPart.noon);
      expect(homeDayPartAt(12), HomeDayPart.noon);
      expect(homeDayPartAt(13), HomeDayPart.afternoon);
      expect(homeDayPartAt(17), HomeDayPart.afternoon);
      expect(homeDayPartAt(18), HomeDayPart.evening);
    });

    test('should_waitUntilTopOfNextHourPlusSlack_when_scheduling', () {
      expect(untilNextHour(DateTime(2026, 1, 1, 9, 59, 30)), const Duration(seconds: 30, milliseconds: 500));
      expect(untilNextHour(DateTime.utc(2026, 1, 1, 9, 30)), const Duration(minutes: 30, milliseconds: 500));
    });

    test('should_sayGoodAfternoonForNoonInEnglish_when_localeEnglish', () {
      const en = HomeStrings.forLanguage(chinese: false);
      const zh = HomeStrings.forLanguage(chinese: true);
      expect(en.greeting(HomeDayPart.noon), 'Good afternoon');
      expect(zh.greeting(HomeDayPart.noon), '中午好');
      expect(zh.greeting(HomeDayPart.afternoon), '下午好');
    });
  });

  group('timeAgo', () {
    int ago(Duration d) => homeTestNow.millisecondsSinceEpoch - d.inMilliseconds;
    TimeAgo of(Duration d) => timeAgoOf(nowMs: homeTestNow.millisecondsSinceEpoch, timestampMs: ago(d));

    test('should_bucketByMinutesHoursDaysWeeks_when_formatting', () {
      expect(of(const Duration(seconds: 30)), const TimeAgoJustNow());
      expect(of(const Duration(minutes: 59)), const TimeAgoMinutes(59));
      expect(of(const Duration(hours: 23)), const TimeAgoHours(23));
      expect(of(const Duration(days: 6)), const TimeAgoDays(6));
      expect(of(const Duration(days: 365)), const TimeAgoWeeks(52));
    });

    test('should_clampFutureToJustNow_when_timestampAhead', () {
      expect(timeAgoOf(nowMs: 0, timestampMs: 60000), const TimeAgoJustNow());
    });

    test('should_formatBothLanguages_when_stringsAsked', () {
      const en = HomeStrings.forLanguage(chinese: false);
      const zh = HomeStrings.forLanguage(chinese: true);
      expect(en.timeAgo(const TimeAgoMinutes(5)), '5m ago');
      expect(zh.timeAgo(const TimeAgoWeeks(2)), '2 周前');
      expect(en.playlistSongs(1), '1 song');
      expect(en.playlistSongs(4), '4 songs');
      expect(zh.playlistSongs(4), '4 首');
      expect(en.songsUnit(1), 'song');
      expect(en.songsUnit(2), 'songs');
    });
  });

  group('activities', () {
    test('should_keepNewestPerActionAndEntity_when_collapsing', () {
      final rows = [
        albumEvent('a', minutesAgo: 10),
        albumEvent('a', minutesAgo: 1),
        albumEvent('a', minutesAgo: 3, action: ActivityActionType.visited),
        albumEvent('subsonic:a', minutesAgo: 20),
      ];
      final collapsed = collapseToLatestUnique(rows);
      expect(collapsed.length, 2);
      expect(collapsed.first.timestampMs, rows[1].timestampMs);
    });

    test('should_dropSongsAndDedupeByEntity_when_selecting', () {
      final rows = [songEvent('s'), albumEvent('a', minutesAgo: 1), albumEvent('a', minutesAgo: 2, action: ActivityActionType.visited)];
      final selected = selectHomeActivities(rows);
      expect(selected.map((e) => e.entityId), ['a']);
    });

    test('should_stripLegacyProviderPrefix_when_buildingEntries', () {
      final entries = buildActivityEntries([albumEvent('subsonic:42')], provider: MediaId.providerSubsonic);
      final target = entries.single.target as HomeOpenAlbum;
      expect(target.album.id, MediaId.subsonic('42'));
      expect(entries.single.cover, const CoverSourceRelative('42'));
    });

    test('should_useStoredCoverKey_when_present', () {
      final e = ActivityEvent(
        entityType: ActivityEntityType.playlist,
        actionType: ActivityActionType.played,
        entityId: 'p',
        title: 'P',
        coverArtKey: 'https://x/y.jpg',
        timestampMs: 0,
      );
      expect(activityCover(e, MediaId.providerSubsonic), const CoverUrl('https://x/y.jpg'));
    });

    test('should_pickFirstAlbumOrPlaylistAsHero_when_artistIsNewest', () {
      final entries = buildActivityEntries([artistEvent('ar', minutesAgo: 1), playlistEvent('p', minutesAgo: 2), albumEvent('a', minutesAgo: 3)],
          provider: MediaId.providerSubsonic);
      expect(selectHeroIndex(entries), 1);
      expect(heroKeyOf(entries), 'playlist:p');
    });

    test('should_haveNoHero_when_onlyArtists', () {
      final entries = buildActivityEntries([artistEvent('1'), artistEvent('2')], provider: MediaId.providerSubsonic);
      expect(selectHeroIndex(entries), -1);
    });

    test('should_listAlbumIdsShown_when_dedupingRecentlyPlayed', () {
      final entries = buildActivityEntries([albumEvent('a'), playlistEvent('p')], provider: MediaId.providerSubsonic);
      final ids = activityAlbumIds(entries);
      expect(notShownIn([album('a'), album('b')], ids).map((a) => a.id.rawId), ['b']);
    });
  });

  group('bento rows', () {
    List<HomeActivityEntry> entries(int albums, {int artists = 0}) => buildActivityEntries([
          for (var i = 0; i < albums; i++) albumEvent('a$i', minutesAgo: i + 1),
          for (var i = 0; i < artists; i++) artistEvent('r$i', minutesAgo: 100 + i),
        ], provider: MediaId.providerSubsonic);

    test('should_layHeroSmallWideStrip_when_phoneHasFourEntries', () {
      final rows = bentoRows(entries(4), BentoRecipe.phone);
      expect(rows.map((r) => r.map((c) => c.size).toList()).toList(), [
        [BentoCardSize.hero],
        [BentoCardSize.small, BentoCardSize.wide],
        [BentoCardSize.strip],
      ]);
      expect(rows[1].map((c) => c.weight), [1, 2]);
    });

    test('should_useTwoEqualSmalls_when_phoneNarrow', () {
      final rows = bentoRows(entries(4), BentoRecipe.phoneNarrow);
      expect(rows[1].map((c) => (c.size, c.weight)), [(BentoCardSize.small, 1.0), (BentoCardSize.small, 1.0)]);
    });

    test('should_growLastCardAndDropEmptyRows_when_supplyShort', () {
      final rows = bentoRows(entries(2), BentoRecipe.phone);
      expect(rows.length, 2);
      expect(rows[1].single.size, BentoCardSize.wide);
      expect(rows[1].single.weight, 3);
    });

    test('should_startWithSupportingRow_when_noHero', () {
      final rows = bentoRows(entries(0, artists: 3), BentoRecipe.phone);
      expect(rows.first.map((c) => c.size), [BentoCardSize.small, BentoCardSize.wide]);
      expect(rows.last.single.size, BentoCardSize.strip);
    });

    test('should_layOneRow_when_landscape', () {
      final rows = bentoRows(entries(4), BentoRecipe.landscape);
      expect(rows.single.map((c) => c.size), [BentoCardSize.hero, BentoCardSize.small, BentoCardSize.wide]);
    });

    test('should_beEmpty_when_noEntries', () {
      expect(bentoRows(const [], BentoRecipe.phone), isEmpty);
    });
  });

  group('jump back in', () {
    test('should_interleaveRoundRobin_when_buildingGrid', () {
      final pools = HomeGridPools(
        cachedAtMs: 0,
        albums: [for (var i = 0; i < 4; i++) album('a$i')],
        tracks: [for (var i = 0; i < 3; i++) track('t$i')],
        playlists: [for (var i = 0; i < 4; i++) playlist('p$i')],
      );
      final grid = buildWidgetGrid(pools);
      expect(grid.take(8).map((c) => c.kind), [
        HomeCardKind.album,
        HomeCardKind.song,
        HomeCardKind.playlist,
        HomeCardKind.album,
        HomeCardKind.song,
        HomeCardKind.playlist,
        HomeCardKind.album,
        HomeCardKind.playlist,
      ]);
      expect(grid.length, 11);
      expect(grid.first.stableId, 'grid-album:a0');
    });

    test('should_putArtlessCardsLast_when_sorting', () {
      final pools = HomeGridPools(cachedAtMs: 0, playlists: [
        Playlist(id: MediaId.subsonic('p1'), name: 'no art'),
        playlist('p2'),
      ]);
      final grid = buildWidgetGrid(pools);
      expect(grid.map((c) => c.title), ['Playlist p2', 'no art']);
    });

    test('should_fallBackToAlbumCover_when_trackHasNone', () {
      expect(songCard(track('t')).cover, const CoverSourceRelative('al-t'));
    });

    test('should_capAtTwentyEight_when_poolsLarge', () {
      final pools = HomeGridPools(cachedAtMs: 0, albums: [for (var i = 0; i < 40; i++) album('a$i')]);
      expect(buildWidgetGrid(pools).length, homeGridMaxCompacts);
    });

    test('should_shuffleDistinctAndTake_when_buildingPools', () {
      final rng = math.Random(1);
      final albums = albumPoolFrom([for (var i = 0; i < 30; i++) album('a${i % 20}')], rng);
      expect(albums.length, gridAlbumPool);
      expect(albums.map((a) => a.id).toSet().length, gridAlbumPool);
    });

    test('should_roundTripPools_when_serialised', () {
      final pools = HomeGridPools(cachedAtMs: 7, albums: [album('a')], tracks: [track('t')], playlists: [playlist('p')]);
      final back = HomeGridPools.fromJson(pools.toJson(), provider: MediaId.providerSubsonic);
      expect(back.cachedAtMs, 7);
      expect(back.tracks.single.albumId, MediaId.subsonic('al-t'));
      expect(back.playlists.single.coverArt, const CoverSourceRelative('pl-p'));
    });
  });

  group('recently added', () {
    test('should_parseIsoVariants_when_readingTimestamps', () {
      final utc = DateTime.utc(2026, 11, 1, 12).millisecondsSinceEpoch;
      expect(parseAddedAtMs('2026-11-01T12:00:00Z'), utc);
      expect(parseAddedAtMs('2026-11-01T12:00:00.000Z'), utc);
      expect(parseAddedAtMs('2026-11-01T14:00:00+02:00'), utc);
      expect(parseAddedAtMs('2026-11-01T12:00:00'), utc);
      expect(parseAddedAtMs('2026-11-01'), DateTime.utc(2026, 11, 1).millisecondsSinceEpoch);
      expect(parseAddedAtMs('yesterday'), isNull);
      expect(parseAddedAtMs(null), isNull);
    });

    test('should_keepOnlyLast30DaysNewestFirst_when_filtering', () {
      final items = [
        track('old', starred: '2026-09-01T00:00:00Z'),
        track('mid', starred: '2026-11-01T00:00:00Z'),
        track('new', starred: '2026-11-12T00:00:00Z'),
        track('bad', starred: 'nope'),
      ];
      final out = recentlyAddedFrom<Track>(items,
          nowMs: homeTestNow.millisecondsSinceEpoch, starredAt: (t) => t.starredAt, id: (t) => t.id, cap: 16);
      expect(out.map((t) => t.id.rawId), ['new', 'mid']);
    });

    test('should_useTwoColumns_when_oneToFourTracks', () {
      expect(recentlyAddedGridColumns(1), 2);
      expect(recentlyAddedGridColumns(4), 2);
    });
  });

  group('geometry', () {
    /// The frame of a resting [width] × [height] container.
    HomeFeedFrame frameAt(double width, {double height = 1000}) =>
        homeFeedFrame(width, HomeFeedDensity.of(YoinWindowInfo(width, height)));

    test('should_useSixteenMargins_when_phonePortrait', () {
      final frame = frameAt(360);
      expect((frame.start, frame.content, frame.end, frame.landscape, frame.units), (16.0, 328.0, 16.0, false, 2));
    });

    test('should_capAndCentre_when_containerMedium', () {
      final frame = frameAt(800);
      expect(frame.start, 56);
      expect(frame.content, 688);
      expect(frame.end, 56);
    });

    test('should_fillCanvasInside32Margins_when_containerWide', () {
      final wide = frameAt(1280);
      expect((wide.start, wide.content, wide.end), (32.0, 1216.0, 32.0));
      expect(wide.density.frameClass, FeedFrameClass.wide);
    });

    test('should_useTwentyFourMarginsFullWidth_when_landscapeHandset', () {
      final frame = frameAt(800, height: 400);
      expect((frame.start, frame.content, frame.landscape), (24.0, 752.0, true));
      expect(frame.itemSpacing, 10);
    });

    test('should_pickRecipeByFeedUnits_when_bento', () {
      expect(bentoRecipeFor(frameAt(320)), BentoRecipe.phoneNarrow);
      expect(bentoRecipeFor(frameAt(360)), BentoRecipe.phone);
      expect(bentoRecipeFor(frameAt(479)), BentoRecipe.phone);
      expect(bentoRecipeFor(frameAt(480)), BentoRecipe.units);
      expect(bentoRecipeFor(frameAt(800)), BentoRecipe.units);
      expect(bentoRecipeFor(frameAt(800, height: 400)), BentoRecipe.landscape);
    });

    test('should_layThreeByFourCappedAt100_when_phone', () {
      final g = jbiGeometry(frameAt(360));
      expect((g.columns, g.rows, g.cells, g.templated), (3, 4, 12, false));
      expect(g.coverSide, 100);
    });

    test('should_templateMoreLanesNotBiggerCovers_when_containerWide', () {
      final medium = jbiGeometry(frameAt(800));
      expect((medium.columns, medium.rows, medium.templated), (5, 3, true));
      expect(medium.coverSide, inInclusiveRange(100, 128));
      final wide = jbiGeometry(frameAt(1280));
      expect((wide.columns, wide.rows, wide.templated), (9, 2, true));
      expect(wide.coverSide, inInclusiveRange(100, 128));
    });

    test('should_useTwoRowsUpToSixColumns_when_landscape', () {
      final g = jbiGeometry(frameAt(900, height: 400));
      expect(g.rows, 2);
      expect(g.columns, 6);
      expect(g.templated, isFalse);
      expect(g.landscape, isTrue);
    });

    test('should_keepThePhoneShelfAndItsPresets_when_shortWindowTooNarrowForSix', () {
      // Android jbiGridSpec / jbiPresetRows: fewer than six across is the phone's shelf.
      final g = jbiGeometry(frameAt(480, height: 400));
      expect(g.columns, inInclusiveRange(3, 5));
      expect((g.rows, g.landscape, g.templated), (4, false, false));
      expect([for (final p in HomeRowPreset.values) jbiPresetRows(g, p)], [2, 3, 4, 6]);
    });

    test('should_clampTrackGridWidth_when_phone', () {
      expect(recentlyAddedGridWidth(328, compactHeight: false), closeTo((328 - 14) * 2.6 / 3.6, 0.01));
      expect(recentlyAddedGridWidth(688, compactHeight: false), 340);
    });

    test('should_countCoverColumns_when_contentGiven', () {
      expect(feedCoverColumns(328), 3);
      expect(feedCoverColumns(688), 5);
    });
  });
}
