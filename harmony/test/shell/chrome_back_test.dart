import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/shell/chrome_back.dart';
import 'package:yoin/shell/shell_back_order.dart';

/// Principle 8: with the detail column and Now Playing both open, system
/// back is Now Playing's, whichever registered first (QA mb_44: the column
/// popped while the side panel stayed).
void main() {
  final nav = GlobalKey<NavigatorState>();

  Future<void> pumpApp(WidgetTester tester) => tester.pumpWidget(
    MaterialApp(navigatorKey: nav, home: const Scaffold(body: Text('shell'))),
  );

  for (final npFirst in [false, true]) {
    testWidgets('should_closeNowPlayingNotColumn_when_backWithBothOpen_${npFirst ? 'npFirst' : 'columnFirst'}', (
      tester,
    ) async {
      await pumpApp(tester);
      var npOpen = true;
      var columnOpen = true;
      var columnBacks = 0;
      // Each owner's handler closes its own surface, as the real ones do.
      final np = ChromeBackEntry(
        () => npOpen = false,
        owns: () => npOpen,
        priority: ChromeBackPriority.nowPlaying,
      );
      final column = ChromeBackEntry(
        () {
          columnBacks++;
          columnOpen = false;
        },
        owns: () => columnOpen,
        priority: ChromeBackPriority.detailColumn,
      );
      addTearDown(np.dispose);
      addTearDown(column.dispose);
      if (npFirst) {
        np.attach(nav.currentState!);
        column.attach(nav.currentState!);
      } else {
        column.attach(nav.currentState!);
        np.attach(nav.currentState!);
      }

      await tester.binding.handlePopRoute();
      await tester.pump();
      expect(npOpen, isFalse, reason: 'Now Playing takes the first back');
      expect(columnBacks, 0, reason: 'and the column keeps its page');
      expect(find.text('shell'), findsOneWidget);

      await tester.binding.handlePopRoute();
      await tester.pump();
      expect(columnBacks, 1, reason: 'the next back is the column\'s');
    });
  }

  /// Each surface's entry owns back while the window's back order names it
  /// (ShellScreen / AppChrome / HomeScreen wiring), and its handler closes
  /// only its own surface and notes it closed.
  ({ShellBackLedger ledger, Map<ShellBackOwner, bool> open, List<ShellBackOwner> closed}) arbitrated(
    WidgetTester tester,
  ) {
    final open = {
      ShellBackOwner.nowPlaying: false,
      ShellBackOwner.detailColumn: false,
      ShellBackOwner.memories: false,
      ShellBackOwner.homeEdit: false,
    };
    final closed = <ShellBackOwner>[];
    final ledger = ShellBackLedger()
      ..bindNowPlaying(open: () => open[ShellBackOwner.nowPlaying]!, isPanel: () => true)
      ..bindShell(
        homeSelected: () => true,
        memoriesOpen: () => open[ShellBackOwner.memories]!,
        detailColumnOpen: () => open[ShellBackOwner.detailColumn]!,
      )
      ..bindHomeEdit(() => open[ShellBackOwner.homeEdit]!);
    const priorities = {
      ShellBackOwner.nowPlaying: ChromeBackPriority.nowPlaying,
      ShellBackOwner.homeEdit: ChromeBackPriority.homeEdit,
      ShellBackOwner.memories: ChromeBackPriority.memories,
      ShellBackOwner.detailColumn: ChromeBackPriority.detailColumn,
    };
    for (final surface in priorities.keys) {
      final entry = ChromeBackEntry(
        () {
          open[surface] = false;
          closed.add(surface);
          if (surface != ShellBackOwner.homeEdit) ledger.note(surface, open: false);
        },
        owns: () => ledger.owns(surface),
        priority: priorities[surface]!,
      );
      addTearDown(entry.dispose);
      entry.attach(nav.currentState!);
    }
    return (ledger: ledger, open: open, closed: closed);
  }

  Future<void> back(WidgetTester tester) async {
    await tester.binding.handlePopRoute();
    await tester.pump();
  }

  testWidgets('should_closeSurfacesNewestFirst_when_panelColumnAndMemoriesOpened', (tester) async {
    await pumpApp(tester);
    final s = arbitrated(tester);
    // Opened: the column, the side panel, Memories.
    for (final surface in [ShellBackOwner.detailColumn, ShellBackOwner.nowPlaying, ShellBackOwner.memories]) {
      s.open[surface] = true;
      s.ledger.note(surface, open: true);
    }
    await back(tester);
    await back(tester);
    await back(tester);
    expect(s.closed, [ShellBackOwner.memories, ShellBackOwner.nowPlaying, ShellBackOwner.detailColumn]);
    expect(find.text('shell'), findsOneWidget, reason: 'the shell route itself never pops');
  });

  testWidgets('should_closeTheColumnFirst_when_itOpenedAfterTheSidePanel', (tester) async {
    await pumpApp(tester);
    final s = arbitrated(tester);
    for (final surface in [ShellBackOwner.nowPlaying, ShellBackOwner.detailColumn]) {
      s.open[surface] = true;
      s.ledger.note(surface, open: true);
    }
    await back(tester);
    expect(s.closed, [ShellBackOwner.detailColumn], reason: 'one back, one surface: the newer column');
    await back(tester);
    expect(s.closed, [ShellBackOwner.detailColumn, ShellBackOwner.nowPlaying]);
  });

  testWidgets('should_leaveEditModeBeforeTheColumn_when_editingBesideIt', (tester) async {
    await pumpApp(tester);
    final s = arbitrated(tester);
    s.open[ShellBackOwner.detailColumn] = true;
    s.ledger.note(ShellBackOwner.detailColumn, open: true);
    s.open[ShellBackOwner.homeEdit] = true;
    await back(tester);
    expect(s.closed, [ShellBackOwner.homeEdit]);
    // Beside the side panel the player answers first, edit mode next.
    s.open[ShellBackOwner.nowPlaying] = true;
    s.ledger.note(ShellBackOwner.nowPlaying, open: true);
    s.open[ShellBackOwner.homeEdit] = true;
    await back(tester);
    await back(tester);
    await back(tester);
    expect(s.closed, [
      ShellBackOwner.homeEdit,
      ShellBackOwner.nowPlaying,
      ShellBackOwner.homeEdit,
      ShellBackOwner.detailColumn,
    ]);
  });

  testWidgets('should_goToTheBackground_when_noShellOwnerHoldsBackAtTheRoot', (tester) async {
    // Android returns ShellBackOwner.None and the system takes the back
    // (Memories open while Library is in front, after a shortcut).
    await pumpApp(tester);
    var systemPops = 0;
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
      if (call.method == 'SystemNavigator.pop') systemPops++;
      return null;
    });
    addTearDown(() => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, null));
    final ledger = ShellBackLedger()
      ..bindShell(homeSelected: () => false, memoriesOpen: () => true, detailColumnOpen: () => false)
      ..syncShell();
    var memoriesBacks = 0;
    final memories = ChromeBackEntry(
      () => memoriesBacks++,
      owns: () => ledger.owns(ShellBackOwner.memories),
      priority: ChromeBackPriority.memories,
    );
    addTearDown(memories.dispose);
    memories.attach(nav.currentState!);

    await back(tester);
    expect(memoriesBacks, 0);
    expect(systemPops, 1, reason: 'the back is not swallowed');
  });

  testWidgets('should_popThePushedPage_when_noArbitratedEntryOnItOwnsBack', (tester) async {
    await pumpApp(tester);
    nav.currentState!.push(MaterialPageRoute<void>(builder: (_) => const Text('page')));
    await tester.pumpAndSettle();
    final entry = ChromeBackEntry(() {}, owns: () => false, priority: ChromeBackPriority.nowPlaying);
    addTearDown(entry.dispose);
    entry.attach(nav.currentState!);

    await back(tester);
    await tester.pumpAndSettle();
    expect(find.text('page'), findsNothing);
    expect(find.text('shell'), findsOneWidget);
  });

  testWidgets('should_closeOnlyTheDialogCard_when_itIsOpenOverAShellOwner', (tester) async {
    await pumpApp(tester);
    final s = arbitrated(tester);
    s.open[ShellBackOwner.detailColumn] = true;
    s.ledger.note(ShellBackOwner.detailColumn, open: true);
    var cardOpen = true;
    final card = ChromeBackEntry(() => cardOpen = false, owns: () => cardOpen, priority: ChromeBackPriority.dialog);
    addTearDown(card.dispose);
    card.attach(nav.currentState!);

    await back(tester);
    expect(cardOpen, isFalse);
    expect(s.closed, isEmpty, reason: 'one back, one surface: the card, as Android\'s dialog window');
    await back(tester);
    expect(s.closed, [ShellBackOwner.detailColumn]);
  });

  testWidgets('should_stillCallUnarbitratedEntry_when_back', (tester) async {
    await pumpApp(tester);
    var legacy = 0;
    final entry = ChromeBackEntry(() => legacy++);
    addTearDown(entry.dispose);
    entry.attach(nav.currentState!);
    await tester.binding.handlePopRoute();
    await tester.pump();
    expect(legacy, 1);
  });

  testWidgets('should_rebindToNewTopRoute_when_attachCalledAfterPush', (tester) async {
    await pumpApp(tester);
    var hits = 0;
    final entry = ChromeBackEntry(() => hits++, owns: () => true, priority: ChromeBackPriority.nowPlaying);
    addTearDown(entry.dispose);
    entry.attach(nav.currentState!);

    nav.currentState!.push(MaterialPageRoute<void>(builder: (_) => const Text('settings')));
    await tester.pumpAndSettle();
    // Re-attaching must move the entry onto the newly pushed route so back reaches it first.
    entry.attach(nav.currentState!);

    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(hits, 1);
    expect(find.text('settings'), findsOneWidget);
  });
}
