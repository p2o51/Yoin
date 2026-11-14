import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/nowplaying/now_playing_presentation.dart';
import 'package:yoin/nowplaying/overlay_controller.dart';
import 'package:yoin/shell/app_chrome.dart';
import 'package:yoin/shell/chrome_back.dart';
import 'package:yoin/shell/shell_state.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';
import 'package:yoin/ui/navigation/pane/detail_pane.dart';

import '../support/nowplaying_fakes.dart';

/// The Wide detail column and the Now Playing side panel together
/// (adaptive-principles §3–§5, QA mb_41–45, mb_51–53): Now Playing never
/// drops or rearranges the column, and system back is Now Playing's first.

/// The shell's column wiring (ShellScreen): the window's pane, its back
/// entry arbitrated below Now Playing, and the hand-over on narrowing.
class _Shell extends StatefulWidget {
  const _Shell();
  @override
  State<_Shell> createState() => _ShellState();
}

class _ShellState extends State<_Shell> with TickerProviderStateMixin {
  late final pane = DetailPaneController(vsync: this);
  late final ChromeBackEntry _back = ChromeBackEntry(
    pane.handleBack,
    owns: () => pane.hasEntries,
    priority: ChromeBackPriority.detailColumn,
  );
  int handOvers = 0;

  @override
  void initState() {
    super.initState();
    DetailPaneController.active = pane;
    pane.addListener(_sync);
  }

  void _sync() {
    if (pane.hasEntries) _back.attach(appNavigatorKey.currentState!);
  }

  @override
  void dispose() {
    pane.removeListener(_sync);
    _back.dispose();
    pane.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final window = NowPlayingHostWindowScope.of(context);
    if (pane.holdsColumn && window.layoutMode != LayoutMode.wide) handOvers++;
    return DetailPaneHost(
      controller: pane,
      shell: Builder(
        builder: (context) => Column(
          children: [
            Text('shell ${YoinWindowInfo.of(context).width.round()}'),
            TextButton(
              onPressed: () => DetailPane.open(context, (_) => const _Page()),
              child: const Text('open'),
            ),
          ],
        ),
      ),
    );
  }
}

class _Page extends StatelessWidget {
  const _Page();
  @override
  Widget build(BuildContext context) => Text('page ${YoinWindowInfo.of(context).width.round()}');
}

void main() {
  late NowPlayingFakeController playback;

  Future<_ShellState> pump(WidgetTester tester, Size size) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    playback = NowPlayingFakeController(playingState(nowPlayingTracks(2)));
    await tester.pumpWidget(
      ProviderScope(
        overrides: nowPlayingOverrides(controller: playback),
        child: MaterialApp(
          navigatorKey: appNavigatorKey,
          builder: (context, child) => YoinWindowScope.measured(child: AppChrome(child: child!)),
          home: const Scaffold(body: _Shell()),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    return tester.state<_ShellState>(find.byType(_Shell));
  }

  NowPlayingOverlayController np(WidgetTester tester) =>
      NowPlayingOverlayScope.read(tester.element(find.byType(_Shell)))!;

  testWidgets('should_openFullAndKeepColumn_when_panelCannotSitBesideTwoColumns', (tester) async {
    final shell = await pump(tester, const Size(1100, 800));
    expect(find.text('page 476'), findsOneWidget);
    np(tester).openPlayer();
    await tester.pumpAndSettle();
    expect(np(tester).presentation, NowPlayingPresentation.dualPane, reason: '1100 − 420 < 744: straight to Full');
    expect(np(tester).panelOpen, isFalse);
    expect(shell.pane.holdsColumn, isTrue, reason: 'the column is never dropped for Now Playing');
    expect(shell.handOvers, 0);
    expect(find.text('page 476', skipOffstage: false), findsOneWidget, reason: 'nor re-laid');

    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(np(tester).expanded, isFalse, reason: 'back closes the player first');
    expect(shell.pane.hasEntries, isTrue, reason: 'and leaves the column as it was');
    expect(find.text('page 476'), findsOneWidget);
    expect(find.text('shell 600'), findsOneWidget, reason: 'with Home / Library beside it');

    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(shell.pane.holdsColumn, isFalse);
  });

  testWidgets('should_narrowBothColumnsAndBackClosesPanelFirst_when_panelFits', (tester) async {
    final shell = await pump(tester, const Size(1280, 800));
    expect(find.text('page 656'), findsOneWidget);
    np(tester).openPlayer();
    await tester.pumpAndSettle();
    expect(np(tester).panelOpen, isTrue);
    expect(shell.pane.holdsColumn, isTrue);
    expect(shell.handOvers, 0, reason: 'the panel narrowing the content to 860 never hands the column over');
    expect(find.text('page 460'), findsOneWidget, reason: 'both columns re-read 860: 376 | 24 | 460');

    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(np(tester).expanded, isFalse, reason: 'system back: the panel before the column (principle 8)');
    expect(shell.pane.hasEntries, isTrue);
    expect(find.text('page 656'), findsOneWidget);
  });

  testWidgets('should_keepColumn_when_panelGoesFullAndBack', (tester) async {
    final shell = await pump(tester, const Size(1280, 800));
    np(tester).openPlayer();
    await tester.pumpAndSettle();
    np(tester).toggleFullscreen();
    await tester.pumpAndSettle();
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(np(tester).panelOpen, isTrue);
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(np(tester).expanded, isFalse);
    expect(shell.pane.hasEntries, isTrue, reason: 'panel → Full → panel → closed: the column is still there');
    expect(find.text('page 656'), findsOneWidget);
  });

  testWidgets('should_closePanelAndOpenColumn_when_pageOpenedBesidePanelThatCannotStay', (tester) async {
    final shell = await pump(tester, const Size(1100, 800));
    // Close the column, open the panel beside the shell, then open a page.
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    np(tester).openPlayer();
    await tester.pumpAndSettle();
    expect(np(tester).panelOpen, isTrue);
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    expect(np(tester).expanded, isFalse, reason: 'the page asked for must be visible');
    expect(shell.pane.hasEntries, isTrue);
    expect(find.text('page 476'), findsOneWidget);
  });
}
