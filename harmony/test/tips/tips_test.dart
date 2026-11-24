import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/applemusic/apple_music_name_region.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/home/home_bubble_tips.dart';
import 'package:yoin/home/home_lyric_count_handle.dart' show HomeLyricCountPicker;
import 'package:yoin/home/lyric_highlight_section.dart' show LyricHighlightSection;
import 'package:yoin/memories/ledger/memory_grid_view.dart';
import 'package:yoin/memories/memories_aurora.dart';
import 'package:yoin/memories/palette/memory_palette.dart' show MemoryPaletteSamples;
import 'package:yoin/memories/other_profiles.dart' show AccountSwitchNoticeSheet;
import 'package:yoin/platform/motion/system_motion.dart';
import 'package:yoin/nowplaying/now_playing_screen.dart' show TabletopExpandLyricsKey;
import 'package:yoin/playback/spatial_audio.dart' show SpatialAudioMode;
import 'package:yoin/settings/stream_quality_screen.dart' show SpatialAudioGroup;
import 'package:yoin/nowplaying/devices/devices_sheet.dart';
import 'package:yoin/settings/appearance/screen_corners_screen.dart';
import 'package:yoin/settings/appearance/scroll_edge_screen.dart';
import 'package:yoin/ui/component/seam/seam_style.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/detail/detail_track_rows.dart' show TrackCountLine;
import 'package:yoin/detail/reshape_scaffold.dart' show DetailBarHeader;
import 'package:yoin/shell/bar_suppression.dart';
import 'package:yoin/shell/yoin_section.dart';
import 'package:yoin/settings/tips.dart';
import 'package:yoin/shell/yoin_bottom_bar.dart';
import 'package:yoin/theme/theme.dart';
import 'package:yoin/tips/demo_phone.dart';
import 'package:yoin/tips/tip_illustration.dart';
import 'package:yoin/tips/tip_screen.dart';
import 'package:yoin/ui/component/artwork_image.dart';
import 'package:yoin/ui/component/system_reduce_motion_scope.dart';

import '../support/fake_music_source.dart';

void main() {
  late InMemoryStore store;

  List<Override> overrides() => [
    keyValueStoreProvider.overrideWithValue(store),
    secretStoreProvider.overrideWithValue(store),
    musicSourceFactoryProvider.overrideWithValue((_) => FakeMusicSource()),
  ];

  Future<void> frames(WidgetTester tester, Duration total) async {
    for (var t = Duration.zero; t < total; t += const Duration(milliseconds: 16)) {
      await tester.pump(const Duration(milliseconds: 16));
    }
  }

  setUp(() => store = InMemoryStore());

  group('read tips', () {
    test('should_keepTipRead_when_itsDayIsUnchanged', () {
      final read = {HomeBubbleTip.name, HomeBubbleTip.lyricsTools};
      expect(decodeReadTips(encodeReadTips(read)), read);
      expect(encodeReadTips({HomeBubbleTip.name}), ['name@2026-10-17']);
    });

    test('should_bringTipBackUnread_when_itChangedAfterItWasRead', () {
      expect(decodeReadTips(['name@2026-10-01', 'downloads@2026-11-04']), {HomeBubbleTip.downloads});
    });

    test('should_readBareNamesAsRead_when_tipsPredateDays', () {
      expect(decodeReadTips(['playModes', 'lyricsTools', 'gone']), {HomeBubbleTip.playModes});
    });

    test('should_findNextUnreadAfterCurrent_when_somePagesAreRead', () {
      const tips = [HomeBubbleTip.name, HomeBubbleTip.editHome, HomeBubbleTip.playModes];
      expect(nextUnreadTip(HomeBubbleTip.name, {HomeBubbleTip.editHome}, tips), HomeBubbleTip.playModes);
      expect(nextUnreadTip(HomeBubbleTip.playModes, const {}, tips), HomeBubbleTip.name);
      expect(nextUnreadTip(HomeBubbleTip.name, {HomeBubbleTip.editHome, HomeBubbleTip.playModes}, tips), isNull);
      expect(unreadTips({HomeBubbleTip.editHome}, tips), [HomeBubbleTip.name, HomeBubbleTip.playModes]);
    });

    test('should_leaveServiceWidgetsContinueOnAndTabletopOut_when_onADesktop', () {
      expect(HomeBubbleTip.available('ohos'), HomeBubbleTip.values);
      expect(HomeBubbleTip.available('windows'), isNot(contains(HomeBubbleTip.homeScreenWidgets)));
      expect(HomeBubbleTip.available('windows'), isNot(contains(HomeBubbleTip.continueOn)));
      expect(HomeBubbleTip.available('windows'), isNot(contains(HomeBubbleTip.tabletop)));
      expect(HomeBubbleTip.available('windows').length, HomeBubbleTip.values.length - 3);
    });

    test('should_bringCloudSyncAndAllAccountsBackUnread_when_readBeforeRound3', () {
      // Read on 11-16: Cloud sync now lists Memory words, and All Memories holds every account.
      expect(decodeReadTips(['cloudSync@2026-11-16', 'memoriesAllAccounts@2026-11-16', 'lyricsTools@2026-11-16']), {
        HomeBubbleTip.lyricsTools,
      });
    });

    test('should_bringAllMemoriesBackUnread_when_itWasReadBeforeTheLift', () {
      // Read on 11-16, before the grid flew into the bar: the tip shows something new now.
      expect(decodeReadTips(['memoryLedger@2026-11-12', 'aiServices@2026-11-17']), {HomeBubbleTip.aiServices});
    });

    test('should_bringAiFeaturesBackUnread_when_readBeforeTheChartOpenedOnCost', () {
      // Read on 11-16: the AI page's chart now opens on cost against the overall score.
      expect(decodeReadTips(['aiServices@2026-11-16', 'lyricsTools@2026-11-16']), {HomeBubbleTip.lyricsTools});
    });

    test('should_bringKeyboardShortcutsBackUnread_when_readBeforeCtrl1And2', () {
      // Read on 11-13: the demo now goes to Library and Home with Ctrl + 2 / Ctrl + 1.
      expect(decodeReadTips(['keyboardShortcuts@2026-11-13', 'name@2026-10-17']), {HomeBubbleTip.name});
    });

    test('should_bringMemoriesTipsBackUnread_when_readBeforeTheDeckAurora', () {
      // Read before 11-17: the Memories demos now stand on the deck's real aurora.
      expect(decodeReadTips(['pullForMemories@2026-10-17', 'memoriesDiary@2026-11-16', 'lyricsTools@2026-11-16']), {
        HomeBubbleTip.lyricsTools,
      });
    });

    test('should_writeBothLanguages_when_anyTipIsShown', () {
      for (final tip in HomeBubbleTip.values) {
        for (final zh in [false, true]) {
          expect(tip.title(zh: zh), isNotEmpty);
          expect(tip.text(zh: zh), isNotEmpty);
        }
      }
    });

    test('should_paintDemoCovers_when_urlIsADemoCover', () {
      expect(DemoArtwork.parse(DemoArtwork.url(3)), const DemoArtwork(3));
      expect(DemoArtwork.parse('https://example.com/cover.jpg'), isNull);
    });
  });

  group('tip page', () {
    Future<void> pumpPage(WidgetTester tester, List<HomeBubbleTip> tips, HomeBubbleTip start) async {
      tester.view.physicalSize = const Size(800, 1600);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(
        ProviderScope(
          overrides: overrides(),
          child: MaterialApp(
            theme: yoinTheme(Brightness.light),
            home: Builder(
              builder: (context) => Scaffold(
                body: Center(
                  child: TextButton(
                    onPressed: () => Navigator.of(context).push(
                      MaterialPageRoute<void>(
                        builder: (_) => TipScreen(startTip: start, tips: tips),
                      ),
                    ),
                    child: const Text('open'),
                  ),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('open'));
      await frames(tester, const Duration(seconds: 1));
    }

    testWidgets('should_markReadAndMoveToTheNextUnread_when_nextIsTapped', (tester) async {
      const tips = [HomeBubbleTip.name, HomeBubbleTip.editHome, HomeBubbleTip.playModes];
      await pumpPage(tester, tips, HomeBubbleTip.name);
      expect(find.text('1 / 3'), findsOneWidget);
      expect(find.text('Next'), findsOneWidget);
      await tester.tap(find.text('Next'));
      await frames(tester, const Duration(seconds: 2));
      expect(find.text('2 / 3'), findsOneWidget);
      final container = ProviderScope.containerOf(tester.element(find.byType(TipScreen)));
      expect(container.read(tipReadProvider), {HomeBubbleTip.name});
    });

    testWidgets('should_suppressChromeBar_when_tipScreenIsOpen', (tester) async {
      const tips = [HomeBubbleTip.name];
      await pumpPage(tester, tips, HomeBubbleTip.name);
      final container = ProviderScope.containerOf(tester.element(find.byType(TipScreen)));
      expect(container.read(barSuppressedProvider), isTrue);
      await tester.tap(find.text('Done'));
      await frames(tester, const Duration(seconds: 1));
      expect(find.byType(TipScreen), findsNothing);
      expect(container.read(barSuppressedProvider), isFalse);
    });

    testWidgets('should_closeThePage_when_doneOnTheLastUnreadTip', (tester) async {
      const tips = [HomeBubbleTip.name, HomeBubbleTip.playModes];
      await pumpPage(tester, tips, HomeBubbleTip.playModes);
      final container = ProviderScope.containerOf(tester.element(find.byType(TipScreen)));
      await container.read(tipReadProvider.notifier).setRead(HomeBubbleTip.name, true);
      await frames(tester, const Duration(milliseconds: 300));
      expect(find.text('Done'), findsOneWidget);
      await tester.tap(find.text('Done'));
      await frames(tester, const Duration(seconds: 1));
      expect(find.byType(TipScreen), findsNothing);
      expect(container.read(tipReadProvider), {HomeBubbleTip.name, HomeBubbleTip.playModes});
    });

    testWidgets('should_toggleReadInPlace_when_readIsTapped', (tester) async {
      const tips = [HomeBubbleTip.name, HomeBubbleTip.editHome];
      await pumpPage(tester, tips, HomeBubbleTip.name);
      final container = ProviderScope.containerOf(tester.element(find.byType(TipScreen)));
      await tester.tap(find.text('Read'));
      await frames(tester, const Duration(milliseconds: 300));
      expect(container.read(tipReadProvider), {HomeBubbleTip.name});
      await tester.tap(find.text('Read'));
      await frames(tester, const Duration(milliseconds: 300));
      expect(container.read(tipReadProvider), isEmpty);
      expect(find.text('Updated Oct 17, 2026'), findsOneWidget);
    });
  });

  group('demo phone', () {
    Future<void> pumpDemo(WidgetTester tester, HomeBubbleTip tip, {bool zh = false}) async {
      tester.view.physicalSize = const Size(800, 1400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(
        ProviderScope(
          overrides: overrides(),
          child: MaterialApp(
            theme: yoinTheme(Brightness.light),
            locale: zh ? const Locale('zh') : const Locale('en'),
            supportedLocales: const [Locale('en'), Locale('zh')],
            localizationsDelegates: GlobalMaterialLocalizations.delegates,
            home: Scaffold(
              body: Center(child: TipIllustration(tip: tip, playing: true)),
            ),
          ),
        ),
      );
    }

    for (final (tip, zh) in [
      for (final tip in HomeBubbleTip.values) ...[(tip, false), (tip, true)],
    ]) {
      testWidgets('should_playItsScriptWithoutErrors_when_${tip.name}DemoIsShown${zh ? 'InChinese' : ''}', (
        tester,
      ) async {
        await pumpDemo(tester, tip, zh: zh);
        // Two runs of the script: every step finds its part and every state change lands.
        await frames(tester, const Duration(seconds: 14));
        expect(tester.takeException(), isNull);
        // Leaving the page stops the demo cleanly.
        await tester.pumpWidget(const SizedBox());
        await frames(tester, const Duration(milliseconds: 500));
        expect(tester.takeException(), isNull);
      });
    }

    /// Plays the demo until [done] holds (at most [limit]); false if it never did.
    Future<bool> playUntil(
      WidgetTester tester,
      bool Function() done, {
      Duration limit = const Duration(seconds: 12),
    }) async {
      for (var t = Duration.zero; t < limit; t += const Duration(milliseconds: 16)) {
        await tester.pump(const Duration(milliseconds: 16));
        if (done()) return true;
      }
      return false;
    }

    /// Leaves the page: the demo stops and lets go of its timers.
    Future<void> leave(WidgetTester tester) async {
      await tester.pumpWidget(const SizedBox());
      await frames(tester, const Duration(seconds: 1));
      expect(tester.takeException(), isNull);
    }

    testWidgets('should_flyTheGridIntoTheBar_when_theAllMemoriesDemoScrolls', (tester) async {
      await pumpDemo(tester, HomeBubbleTip.memoryLedger);
      final grid = find.ancestor(of: find.byType(MemoryGridView), matching: find.byType(Opacity)).first;
      expect(tester.widget<Opacity>(grid).opacity, 1);
      // The real grid steps aside once its squares take off for the bar.
      expect(await playUntil(tester, () => tester.widget<Opacity>(grid).opacity == 0), isTrue);
      expect(tester.takeException(), isNull);
      await leave(tester);
    });

    testWidgets('should_findASpeakerAndPlayOnIt_when_theDevicesDemoPlays', (tester) async {
      await pumpDemo(tester, HomeBubbleTip.devices);
      final speaker = find.byKey(const ValueKey('device-tip-dlna'));
      expect(speaker, findsNothing);
      expect(await playUntil(tester, () => speaker.evaluate().isNotEmpty), isTrue);
      expect(find.text('Living Room'), findsOneWidget);
      expect(await playUntil(tester, () => tester.widget<DeviceRow>(speaker).device.isActive), isTrue);
      expect(tester.takeException(), isNull);
      await leave(tester);
    });

    testWidgets('should_offerContinueHere_when_theContinueOnDemoPlays', (tester) async {
      await pumpDemo(tester, HomeBubbleTip.continueOn);
      expect(await playUntil(tester, () => find.text('Continue here').evaluate().isNotEmpty), isTrue);
      expect(find.text('From HUAWEI Mate 70'), findsOneWidget);
      expect(tester.takeException(), isNull);
      await leave(tester);
    });

    testWidgets('should_takeACardAway_when_theLyricCardsDemoPullsTheCountHandle', (tester) async {
      await pumpDemo(tester, HomeBubbleTip.lyricHighlight);
      LyricHighlightSection section() => tester.widget(find.byType(LyricHighlightSection));
      expect(section().count, 3);
      // Edit Home: the real handle's dots open under the pull…
      expect(await playUntil(tester, () => find.byType(HomeLyricCountPicker).evaluate().isNotEmpty), isTrue);
      // …and the slide back up leaves two cards.
      expect(await playUntil(tester, () => section().count == 2, limit: const Duration(seconds: 16)), isTrue);
      expect(tester.takeException(), isNull);
      await leave(tester);
    });

    testWidgets('should_slideTheCornerDown_when_theScreenCornersDemoDrags', (tester) async {
      await pumpDemo(tester, HomeBubbleTip.screenCorners);
      ScreenCornersPageContent page() => tester.widget(find.byType(ScreenCornersPageContent));
      expect(page().value, 64);
      expect(page().following, isTrue);
      expect(await playUntil(tester, () => page().value < 64 && !page().following), isTrue);
      expect(tester.takeException(), isNull);
      await leave(tester);
    });

    testWidgets('should_pickAnotherStyle_when_theScrollEdgeDemoTaps', (tester) async {
      await pumpDemo(tester, HomeBubbleTip.scrollEdge);
      bool chosen(SeamTopStyle style) =>
          tester.widget<ScrollEdgeStyleCard>(find.byKey(ValueKey(style), skipOffstage: false)).selected;
      expect(chosen(SeamTopStyle.tide), isTrue);
      expect(await playUntil(tester, () => chosen(SeamTopStyle.dots)), isTrue);
      expect(await playUntil(tester, () => chosen(SeamTopStyle.cookie)), isTrue);
      expect(tester.takeException(), isNull);
      await leave(tester);
    });

    testWidgets('should_pickOriginalThenJapan_when_theAppleMusicSongNamesDemoTaps', (tester) async {
      await pumpDemo(tester, HomeBubbleTip.appleMusicSongNames);
      bool shows(String text) => find.textContaining(text, skipOffstage: false).evaluate().isNotEmpty;
      final japan = AppleMusicStorefrontOption.jp.displayName(zh: false);
      expect(shows('Song names'), isTrue);
      expect(shows(japan), isFalse);
      expect(await playUntil(tester, () => shows(japan)), isTrue);
      expect(tester.takeException(), isNull);
      await leave(tester);
    });

    testWidgets('should_showTheNewGroups_when_theSettingsRegroupedDemoIsShown', (tester) async {
      await pumpDemo(tester, HomeBubbleTip.settingsRegrouped);
      await frames(tester, const Duration(milliseconds: 300));
      expect(find.text('Features and appearance'), findsOneWidget);
      expect(find.text('Appearance'), findsOneWidget);
      expect(find.text('Sync and backups', skipOffstage: false), findsOneWidget);
      expect(find.text('Help and about', skipOffstage: false), findsOneWidget);
      await leave(tester);
    });

    testWidgets('should_swapIntoTheKickstandLayout_when_theTabletopDemoFolds', (tester) async {
      await pumpDemo(tester, HomeBubbleTip.tabletop);
      final expand = find.byKey(const ValueKey('np-tabletop-expand-lyrics'));
      expect(expand, findsNothing);
      expect(await playUntil(tester, () => expand.evaluate().isNotEmpty), isTrue);
      expect(find.text('PLAYING FROM ALBUM'), findsOneWidget);
      // The dot lets the lyrics lead.
      expect(
        await playUntil(
          tester,
          () => tester.widget<TabletopExpandLyricsKey>(find.byType(TabletopExpandLyricsKey)).expanded,
        ),
        isTrue,
      );
      // And the phone unfolds again.
      expect(await playUntil(tester, () => expand.evaluate().isEmpty), isTrue);
      expect(tester.takeException(), isNull);
      await leave(tester);
    });

    testWidgets('should_turnSpatialAudioOffAndOn_when_theSpatialAudioDemoTaps', (tester) async {
      await pumpDemo(tester, HomeBubbleTip.spatialAudio);
      // The group is further down the page: built once the dot scrolls to it.
      final group = find.byType(SpatialAudioGroup, skipOffstage: false);
      SpatialAudioMode? mode() => group.evaluate().isEmpty ? null : tester.widget<SpatialAudioGroup>(group).state.mode;
      expect(await playUntil(tester, () => mode() == SpatialAudioMode.auto), isTrue);
      expect(await playUntil(tester, () => mode() == SpatialAudioMode.off), isTrue);
      expect(find.text('Off. Multichannel songs play folded down to stereo.'), findsOneWidget);
      expect(await playUntil(tester, () => mode() == SpatialAudioMode.auto), isTrue);
      expect(tester.takeException(), isNull);
      await leave(tester);
    });

    testWidgets('should_askBeforeSwitching_when_theAllAccountsDemoGoesToTheAlbum', (tester) async {
      await pumpDemo(tester, HomeBubbleTip.memoriesAllAccounts);
      final notice = find.byType(AccountSwitchNoticeSheet);
      expect(notice, findsNothing);
      expect(await playUntil(tester, () => notice.evaluate().isNotEmpty), isTrue);
      expect(find.text('Switch and open'), findsOneWidget);
      // Its answer stays in the phone: the sheet goes, the tip page stays.
      expect(await playUntil(tester, () => notice.evaluate().isEmpty), isTrue);
      expect(find.byType(TipIllustration), findsOneWidget);
      expect(tester.takeException(), isNull);
      await leave(tester);
    });

    for (final tip in [HomeBubbleTip.pullForMemories, HomeBubbleTip.memoriesDiary]) {
      testWidgets('should_standOnTheDeckAurora_when_the${tip.name}DemoShowsMemories', (tester) async {
        await pumpDemo(tester, tip);
        final aurora = find.byType(MemoriesAurora);
        expect(aurora, findsOneWidget);
        expect(tester.widget<MemoriesAurora>(aurora).palettes, [MemoryPaletteSamples.m1]);
        await leave(tester);
      });
    }

    testWidgets('should_turnOneAuroraWithTheSwipe_when_theAllAccountsDemoDealsTheNextCard', (tester) async {
      await pumpDemo(tester, HomeBubbleTip.memoriesAllAccounts);
      // One field under both pages, read at the deck's position as the real pager does.
      final aurora = find.byType(MemoriesAurora);
      expect(aurora, findsOneWidget);
      expect(tester.widget<MemoriesAurora>(aurora).palettes, [MemoryPaletteSamples.m1, MemoryPaletteSamples.m3]);
      double position() => tester.widget<MemoriesAurora>(aurora).position();
      expect(position(), 0);
      expect(await playUntil(tester, () => position() > 0.99), isTrue);
      expect(tester.takeException(), isNull);
      await leave(tester);
    });

    testWidgets('should_takeTheRowOutAndBringItBack_when_theRemoveFromPlaylistDemoPlays', (tester) async {
      await pumpDemo(tester, HomeBubbleTip.removeFromPlaylist);
      // The third song (Salt and Glass) on the playlist page, then its sheet with the row at its foot.
      final song = find.text('Salt and Glass');
      expect(song, findsOneWidget);
      expect(await playUntil(tester, () => find.text('Remove from playlist').evaluate().isNotEmpty), isTrue);
      // The sheet goes down and the row leaves the page.
      expect(await playUntil(tester, () => song.evaluate().isEmpty), isTrue);
      expect(tester.widget<TrackCountLine>(find.byType(TrackCountLine)).count, 5);
      // Next time round it opens back in its place.
      expect(await playUntil(tester, () => song.evaluate().isNotEmpty), isTrue);
      expect(tester.takeException(), isNull);
      await leave(tester);
    });

    testWidgets('should_hideTheBarAndBringItBack_when_theBarScrollHideDemoScrolls', (tester) async {
      await pumpDemo(tester, HomeBubbleTip.barScrollHide);
      double barAlpha() => tester
          .widget<Opacity>(find.ancestor(of: find.byType(YoinBottomBar), matching: find.byType(Opacity)).first)
          .opacity;
      expect(barAlpha(), 1);
      expect(await playUntil(tester, () => barAlpha() == 0), isTrue);
      expect(await playUntil(tester, () => barAlpha() == 1), isTrue);
      expect(tester.takeException(), isNull);
      await leave(tester);
    });

    testWidgets('should_dockTheCoverAndBringItBack_when_theCoverStampDemoPlays', (tester) async {
      await pumpDemo(tester, HomeBubbleTip.coverStamp);
      double docked() => tester.widget<DetailBarHeader>(find.byType(DetailBarHeader)).reveal.barProgress;
      expect(docked(), 0);
      // The pull-up flies the cover into the bar's stamp…
      expect(await playUntil(tester, () => docked() > 0.99), isTrue);
      // …and the stamp's tap brings it back.
      expect(await playUntil(tester, () => docked() < 0.01), isTrue);
      expect(tester.takeException(), isNull);
      await leave(tester);
    });

    testWidgets('should_goToLibraryAndBackHome_when_theKeyboardDemoPressesCtrl2AndCtrl1', (tester) async {
      await pumpDemo(tester, HomeBubbleTip.keyboardShortcuts);
      YoinSection selected() => tester.widget<YoinBottomBar>(find.byType(YoinBottomBar)).selected;
      expect(selected(), YoinSection.home);
      expect(find.text('Ctrl 1'), findsOneWidget);
      expect(await playUntil(tester, () => selected() == YoinSection.library), isTrue);
      expect(await playUntil(tester, () => selected() == YoinSection.home), isTrue);
      expect(tester.takeException(), isNull);
      await leave(tester);
    });

    testWidgets('should_holdStillUntilMotionIsBack_when_theSystemAsksForLessMotion', (tester) async {
      // Power saving was on when the tip opened; the app root's scope (as in YoinApp) reads it.
      final host = _FakeMotionHost(const SystemMotionSettings(powerSave: true));
      tester.view.physicalSize = const Size(800, 1400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(
        ProviderScope(
          overrides: [...overrides(), systemMotionHostProvider.overrideWithValue(host)],
          child: MaterialApp(
            theme: yoinTheme(Brightness.light),
            builder: (context, child) => SystemReduceMotionScope(child: child!),
            home: const Scaffold(
              body: Center(child: TipIllustration(tip: HomeBubbleTip.keyboardShortcuts, playing: true)),
            ),
          ),
        ),
      );
      YoinSection selected() => tester.widget<YoinBottomBar>(find.byType(YoinBottomBar)).selected;
      // The scripted finger stays put: Ctrl 2 never goes to Library.
      expect(await playUntil(tester, () => selected() != YoinSection.home), isFalse);
      // Power saving off while the page is open: the demo starts.
      host.emit(SystemMotionSettings.normal);
      expect(await playUntil(tester, () => selected() == YoinSection.library), isTrue);
      // On again (HarmonyOS 减弱动画): it steps aside and stays where it was.
      host.emit(const SystemMotionSettings(animationReduce: true));
      await frames(tester, const Duration(milliseconds: 500));
      final held = selected();
      await frames(tester, const Duration(seconds: 8));
      expect(selected(), held);
      expect(tester.takeException(), isNull);
      await leave(tester);
    });

    testWidgets('should_stepAside_when_theUserTouchesThePhone', (tester) async {
      await pumpDemo(tester, HomeBubbleTip.name);
      await frames(tester, const Duration(seconds: 1));
      final phone = find.byType(DemoPhone);
      await tester.tapAt(tester.getTopLeft(phone) + const Offset(20, 20));
      await frames(tester, const Duration(seconds: 2));
      expect(tester.takeException(), isNull);
      // Left alone again, the demo comes back.
      await frames(tester, const Duration(seconds: 6));
      expect(tester.takeException(), isNull);
    });
  });
}

final class _FakeMotionHost implements SystemMotionHost {
  _FakeMotionHost(this.current);

  final _controller = StreamController<SystemMotionSettings>.broadcast();

  @override
  SystemMotionSettings current;

  void emit(SystemMotionSettings settings) => _controller.add(current = settings);

  @override
  Future<SystemMotionSettings> read() async => current;

  @override
  Stream<SystemMotionSettings> watch() => _controller.stream;
}
