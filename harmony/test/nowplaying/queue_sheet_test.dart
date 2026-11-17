import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/nowplaying/queue_sheet.dart';
import 'package:yoin/playback/now_playing_state.dart';
import 'package:yoin/theme/tokens.dart';

import '../support/nowplaying_fakes.dart';

/// Records the queue edits the sheet sends.
class _EditingController extends NowPlayingFakeController {
  _EditingController(super.initial);

  @override
  Future<void> removeQueueItem(int index) async => calls.add('remove:$index');
  @override
  Future<void> moveQueueItem(int from, int to) async => calls.add('move:$from>$to');
  @override
  Future<void> clearUserQueue() async => calls.add('clearUserQueue');
}

NowPlayingState _state({int n = 5, Set<int> userQueued = const {}, bool shuffle = false, List<int>? upcoming}) {
  final base = playingState(nowPlayingTracks(n));
  return base.copyWith(
    entryIds: [for (var i = 0; i < n; i++) 'e$i'],
    userQueued: userQueued,
    shuffle: shuffle,
    upcoming: upcoming ?? base.upcoming,
  );
}

Finder _handle(String label) => find.byWidgetPredicate((w) => w is Icon && w.semanticLabel == label);

void main() {
  group('queueSectionsOf', () {
    test('should_splitUserQueuedFromContext_when_queueHasPlayNextEntries', () {
      final s = queueSectionsOf(_state(userQueued: {1, 2}));
      expect(s.now!.index, 0);
      expect([for (final r in s.queued) r.index], [1, 2]);
      expect([for (final r in s.next) r.index], [3, 4]);
      expect(s.queued.first.entryId, 'e1');
    });

    test('should_followPlayOrder_when_shuffled', () {
      final s = queueSectionsOf(_state(shuffle: true, upcoming: [4, 2, 3, 1]));
      expect([for (final r in s.next) r.index], [4, 2, 3, 1]);
    });

    test('should_leaveOutPlayedSongs_when_currentIsMidQueue', () {
      final state = _state().copyWith(index: 2, upcoming: [3, 4]);
      final s = queueSectionsOf(state);
      expect(s.now!.index, 2);
      expect([for (final r in s.next) r.index], [3, 4]);
    });
  });

  group('reorder math', () {
    test('should_moveOneSlotPerRowHeight_when_dragged', () {
      const h = SheetTokens.queueRowHeight;
      expect(reorderSlot(from: 1, offset: h * 1.6, count: 5), 3);
      expect(reorderSlot(from: 1, offset: -h * 4, count: 5), 0);
      expect(reorderSlot(from: 1, offset: h * 10, count: 5), 4);
    });

    test('should_makeWayTowardTheGap_when_aRowIsHeld', () {
      const h = SheetTokens.queueRowHeight;
      // Row 1 held over slot 3: rows 2 and 3 step up, 0 and 4 stay.
      expect(reorderShift(position: 2, dragging: 1, target: 3), -h);
      expect(reorderShift(position: 3, dragging: 1, target: 3), -h);
      expect(reorderShift(position: 4, dragging: 1, target: 3), 0);
      // Row 3 held over slot 1: rows 1 and 2 step down.
      expect(reorderShift(position: 1, dragging: 3, target: 1), h);
      expect(reorderShift(position: 0, dragging: 3, target: 1), 0);
      expect(reorderShift(position: 1, dragging: -1, target: -1), isNull);
    });

    test('should_showDroppedOrder_when_pendingOrderMatchesRows', () {
      final rows = queueSectionsOf(_state()).next;
      final shown = rowsWithPendingOrder(rows, ['e3', 'e1', 'e2', 'e4']);
      expect([for (final r in shown) r.index], [3, 1, 2, 4]);
      // A list that changed under the drop: the player's list wins.
      expect(rowsWithPendingOrder(rows, ['e3', 'e1']), rows);
    });
  });

  group('QueueSheet', () {
    late _EditingController controller;

    Future<void> pump(WidgetTester tester, NowPlayingState state) async {
      controller = _EditingController(state);
      await tester.pumpWidget(
        ProviderScope(
          overrides: nowPlayingOverrides(controller: controller),
          child: MaterialApp(
            home: Scaffold(body: QueueSheet(onSkip: (i) => controller.calls.add('skip:$i'))),
          ),
        ),
      );
      await tester.pumpAndSettle();
    }

    testWidgets('should_clearUserQueue_when_clearTapped', (tester) async {
      await pump(tester, _state(userQueued: {1}));
      expect(find.text('Next in queue'), findsOneWidget);
      await tester.tap(find.text('Clear'));
      await tester.pump();
      expect(controller.calls, contains('clearUserQueue'));
    });

    testWidgets('should_removeEntry_when_rowSwipedLeft', (tester) async {
      await pump(tester, _state());
      await tester.drag(find.text('Song 2'), const Offset(-600, 0));
      await tester.pumpAndSettle();
      expect(controller.calls, contains('remove:2'));
    });

    testWidgets('should_springBack_when_swipeTooShort', (tester) async {
      await pump(tester, _state());
      await tester.timedDrag(find.text('Song 2'), const Offset(-40, 0), const Duration(seconds: 1));
      await tester.pumpAndSettle();
      expect(controller.calls.where((c) => c.startsWith('remove')), isEmpty);
    });

    testWidgets('should_moveEntry_when_handleDraggedDown', (tester) async {
      await pump(tester, _state());
      final handle = _handle('Move Song 1');
      await tester.timedDrag(
        handle,
        const Offset(0, SheetTokens.queueRowHeight * 2),
        const Duration(milliseconds: 600),
      );
      await tester.pumpAndSettle();
      expect(controller.calls, contains('move:1>3'));
    });

    testWidgets('should_hideHandlesAndShowHint_when_shuffleOn', (tester) async {
      await pump(tester, _state(shuffle: true, upcoming: [3, 1, 4, 2]));
      expect(_handle('Move Song 1'), findsNothing);
      expect(find.text('Shuffle is on: turn it off to reorder songs.'), findsOneWidget);
    });

    testWidgets('should_skipToRow_when_rowTapped', (tester) async {
      await pump(tester, _state());
      await tester.tap(find.text('Song 3'));
      await tester.pump();
      expect(controller.calls, contains('skip:3'));
    });
  });
}
