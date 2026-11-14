import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/nowplaying/now_playing_presentation.dart';
import 'package:yoin/nowplaying/now_playing_screen.dart';
import 'package:yoin/nowplaying/now_playing_view_model.dart';
import 'package:yoin/nowplaying/overlay_controller.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

import '../support/nowplaying_fakes.dart';

/// Pumps the phone column with a track whose lyrics are synced, opens the
/// player and returns its controller.
Future<NowPlayingOverlayController> _pumpPhone(WidgetTester tester, {Size size = const Size(412, 892)}) async {
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
      't0': SyncedLyrics([for (var i = 0; i < 12; i++) LyricLine(i * 4000, 'line $i')]),
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

void main() {
  testWidgets('should_offerFocusAndStepBack_when_phoneCoverTapped', (tester) async {
    final c = await _pumpPhone(tester);
    expect(c.focusOffered, isTrue, reason: 'a 412 × 892 phone grows its cover ≥ max(40, 12 %)');
    final cover = find.bySemanticsLabel(RegExp('cover', caseSensitive: false)).first;
    final before = tester.getSize(cover);
    c.toggleFocus();
    await tester.pumpAndSettle();
    expect(c.stage, NowPlayingStage.immersive);
    expect(tester.takeException(), isNull);
    final after = tester.getSize(cover);
    expect(after.width, greaterThan(before.width + 39));

    // Back leaves focus first.
    expect(c.handleBack(), isTrue);
    await tester.pumpAndSettle();
    expect(c.stage, NowPlayingStage.compact);
    expect(c.expanded, isTrue);
    expect(tester.getSize(cover).width, closeTo(before.width, 0.5));
  });

  testWidgets('should_layOutWithoutOverflow_when_focusThenExpanded', (tester) async {
    final c = await _pumpPhone(tester);
    c.toggleFocus();
    for (var i = 0; i < 6; i++) {
      await tester.pump(const Duration(milliseconds: 32));
    }
    await tester.pumpAndSettle();
    c.expandStage();
    for (var i = 0; i < 6; i++) {
      await tester.pump(const Duration(milliseconds: 32));
    }
    await tester.pumpAndSettle();
    expect(c.stage, NowPlayingStage.expanded);
    expect(tester.takeException(), isNull);
  });

  testWidgets('should_dropFocus_when_windowTooShortToGrow', (tester) async {
    final c = await _pumpPhone(tester, size: const Size(412, 660));
    // Either the short-window focus (lyrics only) or none: never a broken layout.
    if (c.focusOffered) {
      c.toggleFocus();
      await tester.pumpAndSettle();
      expect(c.stage, NowPlayingStage.immersive);
    }
    expect(tester.takeException(), isNull);
  });

  testWidgets('should_focusWithoutOverflow_when_landscapeHandset', (tester) async {
    final c = await _pumpPhone(tester, size: const Size(892, 412));
    expect(c.focusOffered, isTrue, reason: 'every landscape window focuses');
    c.toggleFocus();
    await tester.pumpAndSettle();
    expect(c.stage, NowPlayingStage.immersive);
    expect(tester.takeException(), isNull);
    c.handleBack();
    await tester.pumpAndSettle();
    expect(c.stage, NowPlayingStage.compact);
  });

  testWidgets('should_growCoverIntoStack_when_dualPaneFocuses', (tester) async {
    final c = await _pumpPhone(tester, size: const Size(1280, 800));
    if (!c.fullscreen) {
      c.toggleFullscreen();
      await tester.pumpAndSettle();
    }
    expect(c.frame.presentation, NowPlayingPresentation.dualPane);
    expect(c.focusOffered, isTrue);
    c.toggleFocus();
    await tester.pumpAndSettle();
    expect(c.stage, NowPlayingStage.immersive);
    expect(tester.takeException(), isNull);
  });
}
