import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/platform/posture/display_posture.dart';
import 'package:yoin/playback/playback_context.dart';
import 'package:yoin/shell/app_chrome.dart';
import 'package:yoin/shell/shell_state.dart';
import 'package:yoin/theme/tokens.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

import '../support/nowplaying_fakes.dart';

/// Half folded on the kickstand, the crease across the middle of a 1280 × 800 window.
const _halfFold = DisplayPosture(foldStatus: FoldStatus.halfFolded, creaseRects: [Rect.fromLTWH(0, 396, 1280, 8)]);

Widget _app(List<Override> overrides, DisplayPosture posture) => ProviderScope(
  overrides: overrides,
  child: MaterialApp(
    navigatorKey: appNavigatorKey,
    builder: (context, child) => YoinWindowScope.measured(
      posture: posture,
      child: AppChrome(child: child!),
    ),
    home: const Scaffold(body: SizedBox.expand()),
  ),
);

final _album = AlbumPlaybackContext(albumId: MediaId.subsonic('al'), name: 'Album');

Finder get _close => find.byKey(const ValueKey('np-tabletop-close'));
Finder get _expand => find.byKey(const ValueKey('np-tabletop-expand-lyrics'));

Future<void> _settle(WidgetTester tester) async {
  // Marquees and the wave never settle: pump a fixed time.
  for (var i = 0; i < 90; i++) {
    await tester.pump(const Duration(milliseconds: 16));
  }
}

void main() {
  late NowPlayingFakeController controller;

  Future<void> openOnKickstand(WidgetTester tester, {DisplayPosture posture = _halfFold}) async {
    tester.view.physicalSize = const Size(1280, 800) * 2;
    tester.view.devicePixelRatio = 2;
    addTearDown(tester.view.reset);
    controller = NowPlayingFakeController(playingState(nowPlayingTracks(2), context: _album));
    await tester.pumpWidget(_app(nowPlayingOverrides(controller: controller), posture));
    await tester.pump(const Duration(seconds: 1));
    await tester.tap(find.bySemanticsLabel(RegExp('Open player')));
    await _settle(tester);
  }

  testWidgets('should_splitPlayerOnTheHinge_when_halfFolded', (tester) async {
    await openOnKickstand(tester);
    expect(_close, findsOneWidget);
    expect(find.text('PLAYING FROM ALBUM'), findsOneWidget);
    // The control deck sits below the crease, the display above it.
    expect(tester.getRect(_expand).top, greaterThan(404));
    expect(tester.getRect(find.text('Song 0')).bottom, lessThan(396));
    expect(tester.getSize(_expand), const Size.square(TabletopTokens.controlSize));
    expect(tester.takeException(), isNull);
  });

  testWidgets('should_fitDeckWithoutOverflow_when_flipPhoneIsHalfFolded', (tester) async {
    // A flip phone on its kickstand: 400 × 880, the crease across the middle.
    tester.view.physicalSize = const Size(400, 880) * 2;
    tester.view.devicePixelRatio = 2;
    addTearDown(tester.view.reset);
    controller = NowPlayingFakeController(playingState(nowPlayingTracks(2), context: _album));
    const flip = DisplayPosture(foldStatus: FoldStatus.halfFolded, creaseRects: [Rect.fromLTWH(0, 436, 400, 8)]);
    await tester.pumpWidget(_app(nowPlayingOverrides(controller: controller), flip));
    await tester.pump(const Duration(seconds: 1));
    await tester.tap(find.bySemanticsLabel(RegExp('Open player')));
    await _settle(tester);
    expect(_close, findsOneWidget);
    expect(tester.getSize(_expand).width, lessThan(TabletopTokens.controlSize));
    expect(tester.getRect(_expand).right, lessThanOrEqualTo(400));
    expect(tester.takeException(), isNull, reason: 'the transport row never overflows');
  });

  testWidgets('should_shiftEmphasisToLyrics_when_expandToggled', (tester) async {
    await openOnKickstand(tester);
    double titleSize() => tester.widget<Text>(find.text('Song 0')).style!.fontSize!;
    expect(titleSize(), TabletopTokens.titleSize);
    expect(find.bySemanticsLabel('Expand lyrics'), findsOneWidget);
    await tester.tap(_expand);
    await _settle(tester);
    expect(titleSize(), closeTo(TabletopTokens.titleSize - TabletopTokens.titleShrink, 0.05));
    expect(find.bySemanticsLabel('Collapse lyrics'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('should_closeFromChevron_when_tapped', (tester) async {
    await openOnKickstand(tester);
    await tester.tap(_close);
    await _settle(tester);
    expect(_close, findsNothing);
    expect(find.bySemanticsLabel(RegExp('Song 0, Open player')), findsOneWidget);
  });

  testWidgets('should_swapPostures_when_deviceFoldsWhileOpen', (tester) async {
    tester.view.physicalSize = const Size(1280, 800) * 2;
    tester.view.devicePixelRatio = 2;
    addTearDown(tester.view.reset);
    controller = NowPlayingFakeController(playingState(nowPlayingTracks(2), context: _album));
    final overrides = nowPlayingOverrides(controller: controller);
    // Flat: the side panel opens.
    await tester.pumpWidget(_app(overrides, DisplayPosture.flat));
    await tester.pump(const Duration(seconds: 1));
    await tester.tap(find.bySemanticsLabel(RegExp('Open player')));
    await _settle(tester);
    expect(_close, findsNothing);
    // Folded halfway while open: the hinge layout fades and scales in.
    await tester.pumpWidget(_app(overrides, _halfFold));
    await tester.pump(const Duration(milliseconds: 32));
    expect(_close, findsOneWidget);
    await _settle(tester);
    expect(_close, findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
