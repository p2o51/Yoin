import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/nowplaying/overlay_controller.dart';
import 'package:yoin/shell/app_chrome.dart';
import 'package:yoin/shell/shell_state.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

import '../support/nowplaying_fakes.dart';

void main() {
  Future<NowPlayingOverlayController> openPlayer(WidgetTester tester, Size size) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final playback = NowPlayingFakeController(playingState(nowPlayingTracks(2)));
    await tester.pumpWidget(ProviderScope(
      overrides: nowPlayingOverrides(controller: playback),
      child: MaterialApp(
        navigatorKey: appNavigatorKey,
        builder: (context, child) => YoinWindowScope.measured(child: AppChrome(child: child!)),
        home: const Scaffold(body: Text('shell content')),
      ),
    ));
    await tester.pumpAndSettle();
    final np = NowPlayingOverlayScope.read(tester.element(find.text('shell content')))!;
    np.openPlayer();
    await tester.pumpAndSettle();
    return np;
  }

  testWidgets('should_keepBarAndContentInA11yTree_when_sidePanelIsOpen', (tester) async {
    final semantics = tester.ensureSemantics();
    final np = await openPlayer(tester, const Size(1600, 1000));
    expect(np.panelOpen, isTrue);
    expect(find.semantics.byLabel('Library'), findsOne, reason: 'the bar is on screen beside the panel');
    expect(find.semantics.byLabel('shell content'), findsOne);
    expect(find.semantics.byLabel('Close Now Playing'), findsOne);
    semantics.dispose();
  });

  testWidgets('should_hideWhatIsUnderneath_when_playerIsFullWindow', (tester) async {
    final semantics = tester.ensureSemantics();
    final np = await openPlayer(tester, const Size(400, 860));
    expect(np.panelOpen, isFalse);
    expect(find.semantics.byLabel('shell content'), findsNothing);
    expect(find.semantics.byLabel('Close Now Playing'), findsOne);
    semantics.dispose();
  });
}
