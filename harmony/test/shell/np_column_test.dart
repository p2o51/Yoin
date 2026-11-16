import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin/settings/appearance/screen_corners.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/ui/component/corner_mask.dart';
import 'package:yoin/nowplaying/now_playing_overlay.dart';
import 'package:yoin/nowplaying/now_playing_presentation.dart';
import 'package:yoin/nowplaying/now_playing_screen.dart';
import 'package:yoin/nowplaying/overlay_controller.dart';
import 'package:yoin/shell/app_chrome.dart';
import 'package:yoin/shell/chrome_back.dart';
import 'package:yoin/shell/shell_back_order.dart';
import 'package:yoin/shell/shell_state.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';
import 'package:yoin/ui/navigation/pane/detail_pane.dart';

import '../support/nowplaying_fakes.dart';

/// The Wide detail column and the Now Playing side panel together
/// (adaptive-principles §3–§5, QA mb_41–45, mb_51–53): Now Playing never
/// drops or rearranges the column, and system back is Now Playing's first.

/// The shell's column wiring (ShellScreen): the window's pane, its back
/// entry arbitrated by the window's back order, and the hand-over on narrowing.
class _Shell extends ConsumerStatefulWidget {
  const _Shell();
  @override
  ConsumerState<_Shell> createState() => _ShellState();
}

class _ShellState extends ConsumerState<_Shell> with TickerProviderStateMixin {
  late final pane = DetailPaneController(vsync: this);
  late final ShellBackLedger backOrder = ref.read(shellBackLedgerProvider);
  late final ChromeBackEntry _back = ChromeBackEntry(
    pane.handleBack,
    owns: () => backOrder.owns(ShellBackOwner.detailColumn),
    priority: ChromeBackPriority.detailColumn,
  );
  int handOvers = 0;

  @override
  void initState() {
    super.initState();
    DetailPaneController.active = pane;
    pane.addListener(_sync);
    backOrder.bindShell(homeSelected: () => true, memoriesOpen: () => false, detailColumnOpen: () => pane.hasEntries);
    pane.onPageShown = backOrder.columnPageShown;
  }

  void _sync() {
    backOrder.syncShell();
    if (pane.hasEntries) _back.attach(appNavigatorKey.currentState!);
  }

  @override
  void dispose() {
    backOrder.unbindShell();
    pane.onPageShown = null;
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
  const _Page({this.depth = 0});

  final int depth;

  @override
  Widget build(BuildContext context) => Column(
    children: [
      Text('page${depth > 0 ? ' $depth' : ''} ${YoinWindowInfo.of(context).width.round()}'),
      TextButton(
        onPressed: () => DetailPane.open(context, (_) => _Page(depth: depth + 1)),
        child: Text('push $depth'),
      ),
    ],
  );
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

  /// Closes the column [pump] opened and opens the side panel beside the shell.
  Future<_ShellState> panelThenColumn(WidgetTester tester, {Size size = const Size(1280, 800)}) async {
    final shell = await pump(tester, size);
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(shell.pane.holdsColumn, isFalse);
    np(tester).openPlayer();
    await tester.pumpAndSettle();
    expect(np(tester).panelOpen, isTrue);
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    expect(np(tester).panelOpen, isTrue, reason: 'at 1280 the panel fits beside two columns');
    expect(shell.pane.hasEntries, isTrue);
    return shell;
  }

  testWidgets('should_closeTheColumnBeforeThePanel_when_theColumnOpenedLast', (tester) async {
    final shell = await panelThenColumn(tester);
    expect(shell.backOrder.recency, [ShellBackOwner.nowPlaying, ShellBackOwner.detailColumn]);

    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(shell.pane.holdsColumn, isFalse, reason: 'back undoes the newest step: the column');
    expect(np(tester).panelOpen, isTrue, reason: 'the panel opened before it stays');

    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(np(tester).expanded, isFalse);
  });

  testWidgets('should_popTheColumnsPushedPageFirst_when_pushedBesideAnOpenPanel', (tester) async {
    // Opened: column, panel — then a page pushed inside the column.
    final shell = await pump(tester, const Size(1280, 800));
    np(tester).openPlayer();
    await tester.pumpAndSettle();
    expect(shell.backOrder.recency.last, ShellBackOwner.nowPlaying);
    await tester.tap(find.text('push 0'));
    await tester.pumpAndSettle();
    expect(find.text('page 1 460'), findsOneWidget);
    expect(shell.backOrder.recency.last, ShellBackOwner.detailColumn, reason: 'going deeper in the column');

    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(find.text('page 1 460'), findsNothing, reason: 'the pushed page leaves first');
    expect(shell.pane.hasEntries, isTrue);
    expect(np(tester).panelOpen, isTrue);

    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(shell.pane.holdsColumn, isFalse, reason: 'the column is still the newest surface');
    expect(np(tester).panelOpen, isTrue);
  });

  testWidgets('should_closeTheColumnFirst_when_aPageWasShownInItAfterThePanelOpened', (tester) async {
    // Opened: column, panel — then another page from the shell swaps the open column's root.
    final shell = await pump(tester, const Size(1280, 800));
    np(tester).openPlayer();
    await tester.pumpAndSettle();
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    expect(np(tester).panelOpen, isTrue);
    expect(shell.backOrder.recency, [ShellBackOwner.nowPlaying, ShellBackOwner.detailColumn]);

    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(shell.pane.holdsColumn, isFalse);
    expect(np(tester).panelOpen, isTrue);
  });

  for (final deeper in ['lyrics', 'focus']) {
    testWidgets('should_stepThePlayerBackFirst_when_${deeper}EnteredBesideANewerColumn', (tester) async {
      // Tall enough for the panel's cover to grow (focus is offered there).
      final shell = await panelThenColumn(tester, size: Size(1280, deeper == 'focus' ? 1400 : 800));
      final player = np(tester);
      if (deeper == 'lyrics') {
        player.expandStage();
      } else {
        player.focusOffered = true;
        player.toggleFocus();
      }
      await tester.pumpAndSettle();
      expect(player.stage, isNot(NowPlayingStage.compact));
      expect(shell.backOrder.recency.last, ShellBackOwner.nowPlaying, reason: 'going deeper in the player');

      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(player.stage, NowPlayingStage.compact, reason: 'back undoes the $deeper first');
      expect(shell.pane.hasEntries, isTrue);

      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(player.expanded, isFalse, reason: 'the player is still the newest surface');
      expect(shell.pane.hasEntries, isTrue);

      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(shell.pane.holdsColumn, isFalse);
    });
  }

  testWidgets('should_closeThePlayersSheetFirst_when_itOpenedInThePanelBesideANewerColumn', (tester) async {
    // Opened: side panel, column — then a sheet over the panel (Android: a
    // ModalBottomSheet window, which takes back before anything under it).
    final shell = await panelThenColumn(tester);
    final player = np(tester);
    final screen = tester.element(find.byType(NowPlayingScreen));
    showModalBottomSheet<void>(context: screen, builder: (_) => const Text('sheet'));
    await tester.pumpAndSettle();
    expect(find.text('sheet'), findsOneWidget);
    expect(shell.backOrder.owner, ShellBackOwner.nowPlaying);

    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(find.text('sheet'), findsNothing, reason: 'the sheet over the player leaves first');
    expect(shell.pane.hasEntries, isTrue);
    expect(player.panelOpen, isTrue);
    expect(shell.backOrder.recency.last, ShellBackOwner.detailColumn, reason: 'a sheet is not going deeper');

    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(shell.pane.holdsColumn, isFalse, reason: 'then the newer column');
    expect(player.panelOpen, isTrue);
  });

  testWidgets('should_paintPanelCornersWithoutClipOrPhysicalShadow_when_panelOpen', (tester) async {
    // QA r2_33 / r3_03: PhysicalShape and then an anti-aliased clip around
    // the player's navigator both rasterised a black wedge in the
    // bottom-start corner under a sheet. The corners are painted over now.
    await pump(tester, const Size(1280, 800));
    np(tester).openPlayer();
    await tester.pumpAndSettle();
    final npNavigator = find.descendant(of: find.byType(NowPlayingLayer), matching: find.byType(Navigator)).first;
    expect(find.ancestor(of: npNavigator, matching: find.byType(PhysicalShape)), findsNothing);
    // Only the player's transparent Material sits there, and it doesn't clip.
    final clips = find
        .ancestor(of: npNavigator, matching: find.byType(ClipPath))
        .evaluate()
        .map((e) => e.widget as ClipPath)
        .where((c) => c.clipBehavior != Clip.none);
    expect(clips, isEmpty, reason: 'nothing inside the layer clips the player');
    expect(find.ancestor(of: npNavigator, matching: find.byType(CornerMask)), findsOneWidget);
  });

  testWidgets('should_roundPanelWithScreenCorner_when_userSetOne', (tester) async {
    await pump(tester, const Size(1280, 800));
    final container = ProviderScope.containerOf(tester.element(find.byType(_Shell)));
    await container.read(screenCornersProvider.notifier).select(40, persist: false);
    np(tester).openPlayer();
    await tester.pumpAndSettle();
    final npNavigator = find.descendant(of: find.byType(NowPlayingLayer), matching: find.byType(Navigator)).first;
    final mask = tester.widget<CornerMask>(find.ancestor(of: npNavigator, matching: find.byType(CornerMask)));
    expect(mask.radius, 40, reason: 'Settings › Appearance › Screen corners, not a fixed 28');
  });
}
