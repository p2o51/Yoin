import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:yoin/data/lyrics/lyrics_translation.dart';
import 'package:yoin/data/lyrics/lyrics_registry.dart';
import 'package:yoin/data/lyrics/lyrics_repository.dart' show lyricsRegistryProvider;
import 'package:yoin/data/lyrics/netease_provider.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/nowplaying/lyric_tools.dart';
import 'package:yoin/nowplaying/lyrics_selection.dart';
import 'package:yoin/nowplaying/now_playing_screen.dart';
import 'package:yoin/nowplaying/now_playing_view_model.dart';
import 'package:yoin/nowplaying/overlay_controller.dart';
import 'package:yoin/platform/share/image_share.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

import '../support/nowplaying_fakes.dart';

class _RecordingShare implements ImageShare {
  int shares = 0;
  Uint8List? last;

  @override
  bool get canSave => true;

  @override
  Future<bool> save(Uint8List png, {required String fileName}) async => true;

  @override
  Future<bool> share(Uint8List png, {required String fileName, String? caption}) async {
    shares++;
    last = png;
    return true;
  }
}

Future<NowPlayingOverlayController> _pumpExpanded(
  WidgetTester tester, {
  ImageShare? share,
  Size size = const Size(412, 892),
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final tracks = [
    for (var i = 0; i < 2; i++)
      Track(id: MediaId.subsonic('t$i'), title: 'Song $i', artist: 'Artist $i', album: 'Album', durationSec: 200),
  ];
  final playback = NowPlayingFakeController(playingState(tracks));
  final source = NowPlayingFakeSource(
    lyrics: {
      't0': SyncedLyrics([for (var i = 0; i < 30; i++) LyricLine(i * 4000, 'line $i')]),
    },
  );
  final controller = NowPlayingOverlayController(vsync: tester);
  addTearDown(controller.dispose);
  controller.window = YoinWindowInfo(size.width, size.height);
  controller.height = size.height;
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        ...nowPlayingOverrides(controller: playback, source: source),
        upNextPrefetchDelayProvider.overrideWithValue(Duration.zero),
        imageShareProvider.overrideWithValue(share ?? _RecordingShare()),
        netEaseTranslatorProvider.overrideWithValue(_netEaseWithTranslation()),
        // No online lyric sources: their real HTTP (answered 400 by the test binding at whatever moment
        // the machine gets to it) set frames off mid-settle, so a loaded run spent past the tools' 3 s
        // idle on the test clock and they hid before the first tap.
        lyricsRegistryProvider.overrideWithValue(LyricsProviderRegistry(providers: const [])),
      ],
      child: MaterialApp(
        home: Material(
          child: NowPlayingOverlayScope(
            controller: controller,
            child: NowPlayingScreen(controller: controller),
          ),
        ),
      ),
    ),
  );
  controller.openPlayer();
  await _settle(tester);
  if (size.width > 1000 && !controller.fullscreen) {
    controller.toggleFullscreen();
    await _settle(tester);
  }
  controller.expandStage();
  await _settle(tester);
  // Every test starts from a player just touched: the tools up, their idle count from now.
  _tools(tester).idle.onInteraction();
  await tester.pump();
  return controller;
}

/// NetEase knows "Song 0" and translates every "line i" as "译 i".
NetEaseTranslator _netEaseWithTranslation() => NetEaseTranslator(
  provider: NetEaseLyricsProvider(
    client: MockClient((request) async {
      String stamp(int i) =>
          '[${(i * 4 ~/ 60).toString().padLeft(2, '0')}:${(i * 4 % 60).toString().padLeft(2, '0')}.00]';
      final body = request.url.path == '/cloudsearch'
          ? {
              'result': {
                'songs': [
                  {
                    'id': 5,
                    'name': 'Song 0',
                    'ar': [
                      {'name': 'Artist 0'},
                    ],
                  },
                ],
              },
            }
          : {
              'lrc': {
                'lyric': [for (var i = 0; i < 30; i++) '${stamp(i)}line $i'].join('\n'),
              },
              'tlyric': {
                'lyric': [for (var i = 0; i < 30; i++) '${stamp(i)}译 $i'].join('\n'),
              },
            };
      return http.Response.bytes(utf8.encode(jsonEncode(body)), 200);
    }),
  ),
);

LyricToolsController _tools(WidgetTester tester) =>
    LyricToolsScope.maybeOf(tester.element(find.byType(LyricToolsBar).first))!;

/// Lets the springs land on the test's clock: a fixed span of 16 ms frames,
/// never "until nothing animates". Now Playing has effects timed on the real
/// clock (the room's lights, the seam's read-backs), so settling until idle
/// spent a different span of test time on every machine and load — past the
/// tools' 3 s idle on some runs, and they hid before the first tap.
Future<void> _settle(WidgetTester tester, {Duration span = const Duration(milliseconds: 1200)}) async {
  for (var t = Duration.zero; t < span; t += const Duration(milliseconds: 16)) {
    await tester.pump(const Duration(milliseconds: 16));
  }
}

/// Lets work that runs on real time (the translator's HTTP, the share card's
/// raster) go on, with a frame after each slice, until [done]: bounded by a
/// generous real-time limit instead of a guess, so a loaded machine only
/// makes it slower, never flaky.
Future<void> _until(WidgetTester tester, bool Function() done) async {
  final deadline = DateTime.now().add(const Duration(seconds: 30));
  while (!done()) {
    if (DateTime.now().isAfter(deadline)) fail('timed out waiting on real-time work');
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 10)));
    await tester.pump();
  }
}

bool _enabled(WidgetTester tester, String key) =>
    tester.widget<Semantics>(find.byKey(ValueKey(key))).properties.enabled ?? false;

void main() {
  group('LyricsActionBar', () {
    Future<void> pump(WidgetTester tester, {bool busy = false, bool canSelect = true, bool canRecenter = false}) =>
        tester.pumpWidget(
          MaterialApp(
            home: Material(
              child: Center(
                child: LyricsActionBar(
                  busy: busy,
                  canSelect: canSelect,
                  canRecenter: canRecenter,
                  onSearch: (_) {},
                  onSelect: () {},
                  onRecenter: () {},
                ),
              ),
            ),
          ),
        );

    testWidgets('should_showFourKeysAt52_when_atRest', (tester) async {
      await pump(tester);
      for (final key in ['lyrics-tool-search', 'lyrics-tool-translate', 'lyrics-tool-select', 'lyrics-tool-recenter']) {
        expect(tester.getSize(find.byKey(ValueKey(key))), const Size(52, 52));
      }
      expect(_enabled(tester, 'lyrics-tool-recenter'), isFalse);
      expect(_enabled(tester, 'lyrics-tool-select'), isTrue);
      expect(_enabled(tester, 'lyrics-tool-translate'), isFalse, reason: 'no lines to translate');
      final gap =
          tester.getTopLeft(find.byKey(const ValueKey('lyrics-tool-translate'))).dx -
          tester.getTopRight(find.byKey(const ValueKey('lyrics-tool-search'))).dx;
      expect(gap, LyricToolsMetrics.keyGap);
    });

    testWidgets('should_waitAllKeys_when_actionRunning', (tester) async {
      await pump(tester, busy: true, canRecenter: true);
      expect(_enabled(tester, 'lyrics-tool-search'), isFalse);
      expect(_enabled(tester, 'lyrics-tool-select'), isFalse);
      expect(_enabled(tester, 'lyrics-tool-recenter'), isFalse);
    });

    testWidgets('should_keepTranslateLit_when_itsTranslationRuns', (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: Material(
            child: Center(
              child: LyricsActionBar(
                busy: true,
                translating: true,
                canTranslate: true,
                canSelect: true,
                canRecenter: true,
                onSearch: (_) {},
                onSelect: () {},
                onRecenter: () {},
              ),
            ),
          ),
        ),
      );
      expect(_enabled(tester, 'lyrics-tool-translate'), isTrue);
      expect(_enabled(tester, 'lyrics-tool-search'), isFalse);
      expect(_enabled(tester, 'lyrics-tool-select'), isFalse);
    });

    testWidgets('should_labelSelectAsAdd_when_noLyrics', (tester) async {
      await pump(tester, canSelect: false);
      expect(find.bySemanticsLabel('Add lyrics'), findsOneWidget);
    });
  });

  group('select mode', () {
    testWidgets('should_showTranslationUnderLines_when_translateTapped', (tester) async {
      await _pumpExpanded(tester);
      expect(find.text('译 2'), findsNothing);
      await tester.runAsync(() => tester.tap(find.byKey(const ValueKey('lyrics-tool-translate'))));
      // The translation's read runs on real time: waited for, not guessed (100 ms ran out in loaded full runs).
      final c = ProviderScope.containerOf(tester.element(find.byType(NowPlayingScreen)));
      await _until(
        tester,
        () => switch (c.read(nowPlayingViewModelProvider)) {
          NowPlayingContent(:final showTranslation) => showTranslation,
          _ => false,
        },
      );
      await _settle(tester);
      expect(find.text('译 2'), findsOneWidget);
      expect(find.text('Lyrics translated'), findsNothing, reason: 'snackbar lives in the shell');

      // Again: the layer hides (the translation stays for the song).
      await tester.tap(find.byKey(const ValueKey('lyrics-tool-translate')));
      await _settle(tester);
      expect(find.text('译 2'), findsNothing);
    });

    testWidgets('should_fitFourKeysAt52_when_phone', (tester) async {
      await _pumpExpanded(tester);
      final keys = ['lyrics-tool-search', 'lyrics-tool-translate', 'lyrics-tool-select', 'lyrics-tool-recenter'];
      for (final k in keys) {
        expect(tester.getSize(find.byKey(ValueKey(k))), const Size(52, 52));
      }
      expect(tester.getTopRight(find.byKey(const ValueKey('lyrics-tool-recenter'))).dx, lessThanOrEqualTo(412 - 24));
    });

    testWidgets('should_showToolsInExpandedAccessory_when_lyricsPage', (tester) async {
      await _pumpExpanded(tester);
      expect(find.byKey(const ValueKey('lyrics-tool-search')), findsOneWidget);
      expect(find.byKey(const ValueKey('lyrics-tool-select')), findsOneWidget);
      expect(find.byKey(const ValueKey('lyrics-select-close')), findsNothing);
    });

    testWidgets('should_pickLinesAndCopyWithCredit_when_checkTapped', (tester) async {
      String? copied;
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
        if (call.method == 'Clipboard.setData') copied = (call.arguments as Map)['text'] as String;
        return null;
      });
      addTearDown(() => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, null));
      await _pumpExpanded(tester);

      await tester.tap(find.byKey(const ValueKey('lyrics-tool-select')));
      await _settle(tester);
      final tools = _tools(tester);
      expect(tools.selection.active, isTrue);
      expect(find.byKey(const ValueKey('lyrics-select-close')), findsOneWidget);
      expect(find.text('Select lines'), findsOneWidget);
      expect(_enabled(tester, 'lyrics-select-copy'), isFalse);

      await tester.tap(find.text('line 2').last);
      await _settle(tester);
      await tester.tap(find.text('line 1').last);
      await _settle(tester);
      expect(tools.selection.selected, {1, 2});
      expect(find.text('2 / 15'), findsOneWidget);
      expect(_enabled(tester, 'lyrics-select-copy'), isTrue);

      await tester.tap(find.byKey(const ValueKey('lyrics-select-copy')));
      await _settle(tester);
      expect(copied, 'line 1\nline 2\n\n— Song 0\nArtist 0');
      expect(tools.selection.active, isFalse, reason: 'copy leaves select mode');
    });

    testWidgets('should_refuseSixteenthLine_when_pickFull', (tester) async {
      await _pumpExpanded(tester);
      await tester.tap(find.byKey(const ValueKey('lyrics-tool-select')));
      await _settle(tester);
      final tools = _tools(tester);
      for (var i = 10; i < 25; i++) {
        tools.selection.toggle(i);
      }
      await _settle(tester);
      expect(tools.selection.selected.length, maxSelectedLyricLines);
      expect(find.text('15 / 15'), findsOneWidget);

      await tester.tap(find.text('line 1').last);
      await tester.pump();
      expect(tools.selection.selected.contains(1), isFalse);
      expect(tools.selection.selected.length, maxSelectedLyricLines);
      await _settle(tester);
    });

    testWidgets('should_leaveSelectModeFirst_when_systemBack', (tester) async {
      final c = await _pumpExpanded(tester);
      await tester.tap(find.byKey(const ValueKey('lyrics-tool-select')));
      await _settle(tester);
      expect(_tools(tester).selection.active, isTrue);

      expect(c.handleBack(), isTrue);
      await _settle(tester);
      expect(_tools(tester).selection.active, isFalse);
      expect(c.stage, NowPlayingStage.expanded, reason: 'back only left select mode');

      c.handleBack();
      await _settle(tester);
      expect(c.stage, NowPlayingStage.compact);
    });

    testWidgets('should_pickInRightColumn_when_dualPane', (tester) async {
      await _pumpExpanded(tester, size: const Size(1280, 800));
      expect(find.byKey(const ValueKey('lyrics-tool-select')), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('lyrics-tool-select')));
      await _settle(tester);
      await tester.tap(find.text('line 3').last);
      await _settle(tester);
      expect(_tools(tester).selection.selected, {3});
      expect(find.text('1 / 15'), findsOneWidget);
    });

    testWidgets('should_tapToSeek_when_notSelecting', (tester) async {
      await _pumpExpanded(tester);
      await tester.tap(find.text('line 2').last);
      await _settle(tester);
      expect(_tools(tester).selection.selected, isEmpty);
    });

    testWidgets('should_shareCardAndLeaveSelectMode_when_shared', (tester) async {
      final share = _RecordingShare();
      await _pumpExpanded(tester, share: share);
      await tester.tap(find.byKey(const ValueKey('lyrics-tool-select')));
      await _settle(tester);
      await tester.tap(find.text('line 2').last);
      await _settle(tester);

      await tester.tap(find.byKey(const ValueKey('lyrics-select-share')));
      await _settle(tester);
      expect(find.byKey(const ValueKey('lyrics-share-card')), findsOneWidget);
      expect(find.text('Card'), findsOneWidget);
      expect(find.text('Story'), findsOneWidget);
      expect(_tools(tester).selection.active, isTrue, reason: 'the pick waits behind the open sheet');

      await tester.runAsync(() => tester.tap(find.byKey(const ValueKey('lyrics-share-share'))));
      // The card's raster runs on real time: waited for, not guessed (200 ms ran out in loaded full runs).
      await _until(tester, () => share.shares > 0);
      await _settle(tester);
      expect(share.shares, 1);
      expect(share.last, isNotEmpty);
      expect(find.byKey(const ValueKey('lyrics-share-card')), findsNothing);
      expect(_tools(tester).selection.active, isFalse);
    });

    testWidgets('should_hideToolsAfterIdle_when_playingSyncedLyrics', (tester) async {
      await _pumpExpanded(tester);
      final tools = _tools(tester);
      await tester.pump(const Duration(milliseconds: LyricToolsMetrics.idleMs + 100));
      expect(tools.idle.idle, isTrue);
      // The first tap where the tools stood only wakes them.
      expect(find.byKey(const ValueKey('lyrics-tools-wake-guard')), findsOneWidget);
      await tester.tapAt(tester.getBottomLeft(find.byType(NowPlayingScreen)) + const Offset(40, -20));
      await tester.pump();
      expect(tools.idle.idle, isFalse);
      await _settle(tester);
    });

    testWidgets('should_resetAutoFollow_when_reenteringExpandedStage', (tester) async {
      final c = await _pumpExpanded(tester);
      // Scroll away so auto-follow turns off and the Recenter button becomes active.
      await tester.drag(find.byType(Scrollable).last, const Offset(0, -300));
      await _settle(tester);
      _tools(tester).idle.onInteraction();
      await tester.pump();
      expect(_enabled(tester, 'lyrics-tool-recenter'), isTrue, reason: 'scrolling away disables auto-follow');

      // Collapse back to compact and re-enter expanded: auto-follow must re-enable automatically.
      c.handleBack();
      await _settle(tester);
      expect(c.stage, NowPlayingStage.compact);
      c.expandStage();
      await _settle(tester);
      _tools(tester).idle.onInteraction();
      await tester.pump();
      expect(_enabled(tester, 'lyrics-tool-recenter'), isFalse, reason: 're-entering Expanded resets auto-follow');
    });
  });
}
