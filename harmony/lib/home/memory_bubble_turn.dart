/// The Memories bubble's pure rules (Android ui/home/HomeMemoryBubble.kt and
/// HomeBubbleSay.kt): when it speaks, when it goes, and what it says.
///
/// design.md 主页「气泡什么时候说、什么时候走」: news (a write this account
/// hasn't been told) is always told; left alone 4–5 s it speaks, holds 2–3 s
/// and flies back into the arrow, and speaks again once it is left alone
/// again. Now and then (35 %) chatter turns into its next line in place
/// first. Memories (in recall order) and tips take turns; with no memory,
/// tips only. Any touch, scroll, pull or cover tucks it away; the background
/// starts the next time in the foreground afresh.
library;

import 'dart:math' as math;

import 'package:flutter/foundation.dart';

import '../data/memory/album_memory_candidate.dart';
import '../memories/home_memory_pill.dart';
import 'home_bubble_tips.dart';

enum BubbleReason { news, idle }

/// What happens to the bubble; the widget turns each event into one of these.
enum MemoryBubbleCue {
  /// Something written that this account hasn't been told, and Home can be read.
  news,

  /// Home sat readable and untouched for the idle window.
  idleElapsed,

  /// Said in full, it held for the dwell.
  dwellOver,

  /// A press anywhere — Home, the bar, the Now Playing pill — or a wheel / trackpad scroll; a tap on it too.
  touch,

  /// Home went out of reach: covered, scrolled, pulled toward Memories, another window in front.
  outOfReach,

  /// The app went to the background.
  backgrounded,
}

/// What the bubble is saying (null = tucked away), and whether it has spoken
/// since the app came to the foreground.
@immutable
final class MemoryBubbleTurn {
  const MemoryBubbleTurn({this.reason, this.spokeThisForeground = false});

  final BubbleReason? reason;
  final bool spokeThisForeground;

  /// The rules. News is always told. Left alone, it speaks again each time it
  /// has gone quiet — never over a bubble already speaking. Everything else
  /// tucks it away; the background also starts the next foreground afresh.
  MemoryBubbleTurn after(MemoryBubbleCue cue) => switch (cue) {
    MemoryBubbleCue.news => const MemoryBubbleTurn(reason: BubbleReason.news, spokeThisForeground: true),
    MemoryBubbleCue.idleElapsed =>
      reason == null ? const MemoryBubbleTurn(reason: BubbleReason.idle, spokeThisForeground: true) : this,
    MemoryBubbleCue.dwellOver ||
    MemoryBubbleCue.touch ||
    MemoryBubbleCue.outOfReach => MemoryBubbleTurn(spokeThisForeground: spokeThisForeground),
    MemoryBubbleCue.backgrounded => const MemoryBubbleTurn(),
  };

  @override
  bool operator ==(Object other) =>
      other is MemoryBubbleTurn && other.reason == reason && other.spokeThisForeground == spokeThisForeground;

  @override
  int get hashCode => Object.hash(reason, spokeThisForeground);

  @override
  String toString() => 'MemoryBubbleTurn($reason, spoke: $spokeThisForeground)';
}

/// One thing the bubble says: a memory or a tip.
@immutable
sealed class BubbleSay {
  const BubbleSay();
}

/// A memory ([latest]); null = notes kept but no memory yet ("Memories").
final class BubbleSayMemory extends BubbleSay {
  const BubbleSayMemory(this.latest);
  final AlbumMemoryCandidate? latest;

  @override
  bool operator ==(Object other) => other is BubbleSayMemory && other.latest?.sessionId == latest?.sessionId;

  @override
  int get hashCode => latest?.sessionId.hashCode ?? 0;
}

final class BubbleSayTip extends BubbleSay {
  const BubbleSayTip(this.tip);
  final HomeBubbleTip tip;

  @override
  bool operator ==(Object other) => other is BubbleSayTip && other.tip == tip;

  @override
  int get hashCode => tip.hashCode;
}

// ── The rhythm (owner 2026-10-17: 4–5 s 不动就出现，2–3 s 消失，带一点随机的脉冲) ──

/// Untouched this long (picked afresh each time), it speaks…
const int memoryBubbleIdleMinMs = 4000;
const int memoryBubbleIdleMaxMs = 5000;

/// …holds what it said this long, then flies back into the arrow…
const int memoryBubbleChatterDwellMinMs = 2000;
const int memoryBubbleChatterDwellMaxMs = 3000;

/// …or, this often, turns into its next line in place and holds that one too.
const double memoryBubbleChainChance = 0.35;

/// News said in full holds this long (owner 2026-10-11: 说完停 6 秒).
const int memoryBubbleNewsDwellMs = 6000;

/// News waits for Home to stay readable and untouched this long first.
const int memoryBubbleNewsDelayMs = 450;

/// A pick in [min, max] inclusive.
int pickMemoryBubbleMs(int min, int max, math.Random random) => min + random.nextInt(max - min + 1);

// ── What it says ──

/// News is always the latest memory; the idle reminder brings back one of the
/// others, the next in line on each [turn] — the latest itself when there are
/// no others yet.
AlbumMemoryCandidate? memoryBubbleSubject(HomeMemoryPillState? pill, BubbleReason reason, int turn) {
  if (pill == null) return null;
  if (reason == BubbleReason.news || pill.recall.isEmpty) return pill.latest;
  return pill.recall[turn % pill.recall.length];
}

/// What the idle bubble says on one of its turns: Memories and tips take turns
/// while there are memories ([memoryTurn] steps through [memoryBubbleSubject]);
/// with none, tips only. [tipTurn] steps through [tips] in order, so every one
/// comes round before one repeats. With no tips, memories only; with neither, null.
BubbleSay? idleBubbleSay(
  HomeMemoryPillState? pill, {
  required bool sayMemory,
  required int memoryTurn,
  required int tipTurn,
  List<HomeBubbleTip> tips = HomeBubbleTip.values,
}) {
  final memory = pill?.hasSomethingToSay ?? false;
  if (memory && (sayMemory || tips.isEmpty)) {
    return BubbleSayMemory(memoryBubbleSubject(pill, BubbleReason.idle, memoryTurn));
  }
  if (tips.isEmpty) return null;
  return BubbleSayTip(tips[tipTurn % tips.length]);
}

// ── News ──

/// A [HomeMemoryPillState.newsKey] read back: (newest write time, note count); null = unreadable.
(int, int)? parseMemoryNewsKey(String? key) {
  final parts = key?.split('#');
  if (parts == null || parts.length != 2) return null;
  final stamp = int.tryParse(parts[0]);
  final notes = int.tryParse(parts[1]);
  if (stamp == null || notes == null) return null;
  return (stamp, notes);
}

/// News = something to say that this account hasn't been told: a write newer
/// than the last one it spoke about, or more notes than it last saw. A
/// deletion moves the key backwards — that is not news.
bool isMemoryNews(HomeMemoryPillState? pill, String? lastSeen) {
  if (pill == null || !pill.hasSomethingToSay || pill.newsKey.isEmpty) return false;
  final now = parseMemoryNewsKey(pill.newsKey);
  if (now == null) return pill.newsKey != lastSeen;
  final seen = parseMemoryNewsKey(lastSeen);
  if (seen == null) return true;
  return now.$1 > seen.$1 || now.$2 > seen.$2;
}

// ── When the dwell starts ──

/// The dwell starts once it has said it all: the bubble fully out, its spring
/// at rest — and not flying away.
bool memoryBubbleFullySaid({
  required double unroll,
  required bool unrolling,
  required double flyAway,
  required bool flyingAway,
}) => unroll >= 1 - 1e-3 && !unrolling && flyAway <= 0 && !flyingAway;

// ── Where the arrow goes ──

/// The arrow's centre x, and whether it sits under the camera cutout. The
/// body runs from the title ([titleLeft]) to [reach] past the arrow, with at
/// least [minLeft] left of it, and ends before the account control
/// ([freeEnd]): under the cutout when that leaves the bubble room, else the
/// page's centre when that does, else as far left as the bubble allows.
(double, bool) memoryArrowCenterX({
  double? cutoutCenterX,
  required double pageCentre,
  required double titleLeft,
  required double freeEnd,
  required double minLeft,
  required double reach,
}) {
  bool fits(double x) => x - minLeft >= titleLeft && x + reach <= freeEnd;
  if (cutoutCenterX != null && fits(cutoutCenterX)) return (cutoutCenterX, true);
  if (fits(pageCentre)) return (pageCentre, false);
  return (titleLeft + minLeft, false);
}
