import 'package:flutter/material.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/nowplaying/lyrics_view.dart';
import 'package:yoin/nowplaying/now_playing_presentation.dart';
import 'package:yoin/nowplaying/now_playing_screen.dart';
import 'package:yoin/nowplaying/now_playing_view_model.dart';
import 'package:yoin/nowplaying/overlay_controller.dart';
import 'package:yoin/nowplaying/wave_progress_bar.dart';
import 'package:yoin/theme/tokens.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';
import 'package:yoin/ui/component/track_menu_sheet.dart';

import '../support/nowplaying_fakes.dart';

/// Device QA (MateBook / Mate 70 Air, 2026-11-15): Now Playing fixes.
Future<NowPlayingOverlayController> _pump(WidgetTester tester, Size size) async {
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
  await tester.pumpAndSettle();
  return controller;
}

final _cover = find.byKey(const ValueKey('np-stage-cover'));

Future<void> _expectMenuOnLongPressAndRightClick(WidgetTester tester) async {
  expect(_cover, findsOneWidget);
  await tester.longPress(_cover);
  await tester.pumpAndSettle();
  expect(find.byType(TrackMenuSheet), findsOneWidget);
  Navigator.of(tester.element(find.byType(TrackMenuSheet))).pop();
  await tester.pumpAndSettle();
  expect(find.byType(TrackMenuSheet), findsNothing);
  await tester.tap(_cover, buttons: kSecondaryButton);
  await tester.pumpAndSettle();
  expect(find.byType(TrackMenuSheet), findsOneWidget);
}

void main() {
  group('cover song menu', () {
    testWidgets('should_openSongMenu_when_phoneCoverLongPressed', (tester) async {
      await _pump(tester, const Size(412, 892));
      await _expectMenuOnLongPressAndRightClick(tester);
    });

    testWidgets('should_openSongMenu_when_landscapeCoverLongPressed', (tester) async {
      await _pump(tester, const Size(892, 412));
      await _expectMenuOnLongPressAndRightClick(tester);
    });

    testWidgets('should_openSongMenu_when_dualPaneCoverLongPressed', (tester) async {
      final c = await _pump(tester, const Size(1280, 800));
      if (!c.fullscreen) {
        c.toggleFullscreen();
        await tester.pumpAndSettle();
      }
      expect(c.frame.presentation, NowPlayingPresentation.dualPane);
      await _expectMenuOnLongPressAndRightClick(tester);
    });

    testWidgets('should_stillFocus_when_coverTapped', (tester) async {
      final c = await _pump(tester, const Size(412, 892));
      await tester.tap(_cover);
      await tester.pumpAndSettle();
      expect(c.stage, NowPlayingStage.immersive);
    });
  });

  testWidgets('should_showRecenterKeyOnlyWhileScrolledAway_when_expanded', (tester) async {
    final c = await _pump(tester, const Size(412, 892));
    c.expandStage();
    await tester.pumpAndSettle();
    expect(c.stage, NowPlayingStage.expanded);
    final key = find.byKey(const ValueKey('np-recenter-key'));
    expect(key, findsNothing, reason: 'following the playhead: no recenter key');

    await tester.drag(find.text('line 6').last, const Offset(0, -300));
    await tester.pumpAndSettle();
    expect(key, findsOneWidget);

    await tester.tap(key);
    await tester.pumpAndSettle();
    expect(key, findsNothing);
  });

  group('lyricsEdgeMask', () {
    test('should_endBottomFadeInsideWindow_when_clearBandGiven', () {
      final mask = lyricsEdgeMask(200, fadeTop: 16, fadeBottom: 24, clearBottom: 4, easeBottom: true);
      expect(mask.colors.length, mask.stops.length);
      // Transparent from 4 dp above the bottom edge down to it.
      expect(mask.stops.last, 1);
      expect(mask.colors.last.a, 0);
      expect(mask.stops[mask.stops.length - 2], closeTo(196 / 200, 1e-9));
      expect(mask.colors[mask.colors.length - 2].a, 0);
      // Opaque until the fade, which starts 24 dp above the clear band.
      final fadeStart = mask.stops.indexWhere((s) => (s - 172 / 200).abs() < 1e-9);
      expect(fadeStart, greaterThan(0));
      expect(mask.colors[fadeStart].a, 1);
      // Eased: already under half alpha at the fade's midpoint.
      for (var i = 1; i < mask.stops.length; i++) {
        expect(mask.stops[i], greaterThanOrEqualTo(mask.stops[i - 1]));
      }
      final mid = mask.stops.indexWhere((s) => (s - 184 / 200).abs() < 1e-9);
      expect(mask.colors[mid].a, lessThan(0.5));
    });

    test('should_keepSymmetricFades_when_fullList', () {
      final mask = lyricsEdgeMask(400, fadeTop: 64, fadeBottom: 64);
      expect(mask.stops, [0, 64 / 400, 336 / 400, 1]);
      expect(mask.colors.map((c) => c.a).toList(), [0, 1, 1, 0]);
    });
  });

  group('focusSeatOffset', () {
    const pad = NowPlayingTokens.lyricsListPadding;
    test('should_keepSungLineAbove_when_roomEnough', () {
      final offset = focusSeatOffset(currentTop: 300, currentHeight: 30, aboveTop: 250, viewport: 400);
      expect(offset, 250 - pad);
    });

    test('should_letSungLineGiveWay_when_currentWouldReachTransport', () {
      // A two-row sung line (64) over a 30 dp current line in a 120 dp window.
      final offset = focusSeatOffset(currentTop: 300, currentHeight: 30, aboveTop: 236, viewport: 120);
      final currentBottomInWindow = 300 + 30 - offset;
      expect(
        currentBottomInWindow,
        lessThanOrEqualTo(120 - NowPlayingTokens.lyricsFadeBottom - LyricsPaneMetrics.windowFadeClear + 1e-9),
      );
      expect(300 - offset, greaterThanOrEqualTo(pad));
    });
  });

  test('should_drawTrackInInkRole_when_anyScheme', () {
    for (final b in Brightness.values) {
      final colors = ColorScheme.fromSeed(seedColor: const Color(0xFF7A4FB0), brightness: b);
      final track = waveTrackColor(colors);
      expect(track.a, closeTo(WaveBarMetrics.trackAlpha, 1e-3));
      expect(track.withValues(alpha: 1), colors.onSurfaceVariant.withValues(alpha: 1));
    }
  });
}
