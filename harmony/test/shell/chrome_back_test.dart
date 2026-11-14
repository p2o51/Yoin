import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/shell/chrome_back.dart';

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
}
