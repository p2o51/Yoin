/// Pure play-order arithmetic shared by the controller and its tests. The
/// native engine owns the real order (shuffle permutation); Dart reads it
/// back from [EngineState.order] and derives what the UI shows.
library;

import 'now_playing_state.dart';

/// The identity order for a queue of [length].
List<int> identityOrder(int length) => List<int>.generate(length, (i) => i, growable: false);

/// Queue indices still to play after [current], in play order, without
/// wrapping (Android `upcomingOrder`, built with REPEAT_MODE_OFF).
List<int> upcomingOf(List<int> order, int current) {
  final pos = order.indexOf(current);
  if (pos < 0) return const [];
  return List<int>.unmodifiable(order.sublist(pos + 1));
}

/// The queue index that a manual "next" (and the lyrics outro) lands on, or
/// -1. Repeat one navigates like repeat all (Media3
/// getRepeatModeForNavigation); only repeat off stops at the end. A queue
/// of one has no next.
int nextIndexOf(List<int> order, int current, RepeatMode repeat) {
  final pos = order.indexOf(current);
  if (pos < 0 || order.length < 2) return -1;
  if (pos + 1 < order.length) return order[pos + 1];
  return repeat == RepeatMode.off ? -1 : order.first;
}

/// [order] after the queue entry at [removed] is gone.
List<int> orderAfterRemove(List<int> order, int removed) => [
      for (final i in order)
        if (i != removed) i > removed ? i - 1 : i,
    ];

/// [order] after [count] entries were inserted at queue index [at]. Without
/// shuffle the order stays the identity; with shuffle the new entries are
/// guessed to play right after [current] (the engine's echo has the truth).
List<int> orderAfterInsert(List<int> order, int at, int count, {required bool shuffle, required int current}) {
  final shifted = [for (final i in order) i >= at ? i + count : i];
  if (!shuffle) return identityOrder(order.length + count);
  final added = [for (var k = 0; k < count; k++) at + k];
  final cur = current >= at ? current + count : current;
  final pos = shifted.indexOf(cur);
  shifted.insertAll(pos < 0 ? shifted.length : pos + 1, added);
  return shifted;
}

/// [order] after the entry at [from] moved to [to].
List<int> orderAfterMove(List<int> order, int from, int to, {required bool shuffle}) {
  if (!shuffle) return identityOrder(order.length);
  int map(int i) {
    if (i == from) return to;
    if (from < to && i > from && i <= to) return i - 1;
    if (to < from && i >= to && i < from) return i + 1;
    return i;
  }

  return [for (final i in order) map(i)];
}

/// The index of [current] after the entry at [from] moved to [to].
int indexAfterMove(int current, int from, int to) {
  if (current == from) return to;
  if (from < to && current > from && current <= to) return current - 1;
  if (to < from && current >= to && current < from) return current + 1;
  return current;
}
