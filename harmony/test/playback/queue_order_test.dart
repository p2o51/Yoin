import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/playback/now_playing_state.dart';
import 'package:yoin/playback/queue_order.dart';

void main() {
  group('upcomingOf', () {
    test('should_listRestOfPlayOrderWithoutWrap_when_shuffled', () {
      expect(upcomingOf([2, 0, 3, 1], 0), [3, 1]);
      expect(upcomingOf([2, 0, 3, 1], 1), isEmpty);
    });

    test('should_beEmpty_when_currentNotInOrder', () {
      expect(upcomingOf([0, 1], 5), isEmpty);
    });
  });

  group('nextIndexOf', () {
    test('should_followPlayOrder_when_notAtEnd', () {
      expect(nextIndexOf([2, 0, 3, 1], 0, RepeatMode.all), 3);
    });

    test('should_wrap_when_repeatAllOrOneAtEnd', () {
      expect(nextIndexOf([2, 0, 3, 1], 1, RepeatMode.all), 2);
      // Repeat one navigates like repeat all (Media3), so the outro shows the real next song.
      expect(nextIndexOf([2, 0, 3, 1], 1, RepeatMode.one), 2);
    });

    test('should_beNone_when_repeatOffAtEndOrQueueOfOne', () {
      expect(nextIndexOf([0, 1, 2], 2, RepeatMode.off), -1);
      expect(nextIndexOf([0], 0, RepeatMode.all), -1);
    });
  });

  group('edits', () {
    test('should_dropAndShift_when_removed', () {
      expect(orderAfterRemove([3, 0, 2, 1], 1), [2, 0, 1]);
    });

    test('should_stayIdentity_when_insertedWithoutShuffle', () {
      expect(orderAfterInsert([0, 1, 2], 1, 2, shuffle: false, current: 0), [0, 1, 2, 3, 4]);
    });

    test('should_playInsertedRightAfterCurrent_when_insertedWhileShuffled', () {
      // current is queue index 2 (shifts to 3 after inserting 1 entry at 1).
      expect(orderAfterInsert([2, 0, 1], 1, 1, shuffle: true, current: 2), [3, 1, 0, 2]);
    });

    test('should_remapIndices_when_moved', () {
      expect(orderAfterMove([0, 1, 2, 3], 0, 2, shuffle: false), [0, 1, 2, 3]);
      expect(orderAfterMove([3, 0, 2, 1], 0, 2, shuffle: true), [3, 2, 1, 0]);
      expect(indexAfterMove(1, 0, 2), 0);
      expect(indexAfterMove(0, 0, 2), 2);
      expect(indexAfterMove(1, 3, 0), 2);
    });
  });
}
