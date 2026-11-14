/// The Memories bubble's clock (Android HomeMemoryBubble.kt's effects): it
/// takes what the widget sees — Home readable or not, a touch, the bubble
/// fully said, a finger holding it, the app backgrounded — and runs every
/// change of what the bubble says through [MemoryBubbleTurn.after]. No
/// widgets here; the widget only animates [MemoryBubbleDirector.reason] and
/// [MemoryBubbleDirector.say].
library;

import 'dart:async';
import 'dart:convert';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../memories/home_memory_pill.dart';
import '../platform/storage/stores.dart';
import 'home_bubble_tips.dart';
import 'memory_bubble_turn.dart';

/// What the bubble remembers across launches: the last news key it spoke per
/// scope, how far its reminder got through each scope's recall, and which
/// tip comes next. Loaded once; writes go through in order.
class MemoryBubbleSeenStore {
  MemoryBubbleSeenStore(KeyValueStore kv) : _kv = kv {
    ready = _load();
  }

  /// No persistence (tests, previews).
  MemoryBubbleSeenStore.inMemory() : _kv = null {
    ready = Future.value();
  }

  static const String storageKey = 'home.memoryBubble.v1';

  final KeyValueStore? _kv;
  late final Future<void> ready;
  final Map<String, String> _seen = {};
  final Map<String, int> _recall = {};
  int _tip = 0;
  Future<void> _writes = Future.value();

  Future<void> _load() async {
    try {
      final raw = await _kv?.read(storageKey);
      if (raw == null) return;
      final json = jsonDecode(raw) as Map<String, dynamic>;
      _seen.addAll((json['seen'] as Map? ?? const {}).cast<String, String>());
      _recall.addAll((json['recall'] as Map? ?? const {}).cast<String, int>());
      _tip = json['tip'] as int? ?? 0;
    } catch (e) {
      debugPrint('YoinHome: bubble store unreadable: ${e.runtimeType}');
    }
  }

  void _save() {
    final kv = _kv;
    if (kv == null) return;
    final value = jsonEncode({'seen': _seen, 'recall': _recall, 'tip': _tip});
    _writes = _writes.then((_) => kv.write(storageKey, value)).catchError((Object _) {});
  }

  String? lastSeen(String scope) => _seen[scope];

  void markSeen(String scope, String newsKey) {
    if (_seen[scope] == newsKey) return;
    _seen[scope] = newsKey;
    _save();
  }

  /// The idle reminder's turn in [scope]: 0, 1, 2… one more each time it is asked.
  int nextRecallTurn(String scope) {
    final turn = _recall[scope] ?? 0;
    _recall[scope] = turn + 1;
    _save();
    return turn;
  }

  /// Which tip comes next: 0, 1, 2… one more each time it is asked.
  int nextTipTurn() {
    final turn = _tip++;
    _save();
    return turn;
  }
}

final memoryBubbleSeenStoreProvider = Provider<MemoryBubbleSeenStore>(
  (ref) => MemoryBubbleSeenStore(ref.watch(keyValueStoreProvider)),
);

/// The rhythm, pinned for tests and harnesses; null = a fresh pick in the owner's range each time.
@immutable
final class MemoryBubbleTimings {
  const MemoryBubbleTimings({
    this.idleMs,
    this.chatterDwellMs,
    this.newsDwellMs = memoryBubbleNewsDwellMs,
    this.newsDelayMs = memoryBubbleNewsDelayMs,
    this.chainChance = memoryBubbleChainChance,
  });

  final int? idleMs;
  final int? chatterDwellMs;
  final int newsDwellMs;
  final int newsDelayMs;
  final double chainChance;
}

class MemoryBubbleDirector extends ChangeNotifier {
  MemoryBubbleDirector({
    required this.store,
    this.tips = HomeBubbleTip.values,
    this.timings = const MemoryBubbleTimings(),
    math.Random? random,
  }) : _random = random ?? math.Random() {
    store.ready.then((_) {
      _storeReady = true;
      if (!_disposed) _checkNews();
    });
  }

  final MemoryBubbleSeenStore store;
  final List<HomeBubbleTip> tips;
  final MemoryBubbleTimings timings;
  final math.Random _random;

  HomeMemoryPillState? _pill;
  BubbleReason? _reason;
  bool _spoke = false;
  BubbleSay? _say;
  int _sayCount = 0;
  bool _chained = false;
  int _idleTurns = 0;

  bool _readable = false;
  bool _fullySaid = false;
  bool _held = false;
  bool _dwellDue = false;
  bool _storeReady = false;
  bool _disposed = false;

  Timer? _idle;
  Timer? _dwell;
  Timer? _news;

  /// What it is saying for (null = tucked away).
  BubbleReason? get reason => _reason;
  bool get speaking => _reason != null;

  /// What it says; kept while it tucks away, so it says the same thing all the way back.
  BubbleSay? get say => _say;

  /// One more each time it says something new — a chained line too.
  int get sayCount => _sayCount;

  bool get readable => _readable;

  void setPill(HomeMemoryPillState pill) {
    _pill = pill;
    // News is about a memory: with none left, it goes. Tips need none.
    if (!pill.hasSomethingToSay && _reason == BubbleReason.news) _cue(MemoryBubbleCue.dwellOver);
    _checkNews();
  }

  /// Home can be read: in front, focused, at its top, nothing over it, nobody pulling Memories.
  void setReadable(bool readable) {
    if (readable == _readable) return;
    _readable = readable;
    if (!readable) {
      _idle?.cancel();
      _news?.cancel();
      _cue(MemoryBubbleCue.outOfReach);
    } else {
      _armIdle();
      _checkNews();
    }
  }

  /// A press anywhere (but on the bubble or the arrow), or a wheel / trackpad scroll.
  void touch() {
    _cue(MemoryBubbleCue.touch);
    _armIdle();
    if (_news?.isActive ?? false) {
      _news?.cancel();
      _checkNews();
    }
  }

  /// The app went to the background: put away unseen, the clocks stopped.
  void backgrounded() {
    _idle?.cancel();
    _news?.cancel();
    _dwell?.cancel();
    _dwellDue = false;
    _fullySaid = false;
    _readable = false;
    _cue(MemoryBubbleCue.backgrounded);
  }

  /// Said in full: out, its spring at rest, not flying away ([memoryBubbleFullySaid]).
  void setFullySaid(bool fullySaid) {
    if (fullySaid == _fullySaid) return;
    _fullySaid = fullySaid;
    _armDwell();
  }

  /// A finger is down on the bubble or the arrow: it never leaves from under it.
  void setHeld(bool held) {
    _held = held;
    if (!held && _dwellDue) _finishDwell();
  }

  void _checkNews() {
    final pill = _pill;
    if (!_storeReady || pill == null || pill.scope.isEmpty) return;
    final lastSeen = store.lastSeen(pill.scope);
    if (!isMemoryNews(pill, lastSeen)) {
      _news?.cancel();
      // A deletion moved the key back: remember the lower one, so the next real write still counts.
      if (pill.newsKey.isNotEmpty && pill.newsKey != lastSeen) store.markSeen(pill.scope, pill.newsKey);
      return;
    }
    if (!_readable || (_news?.isActive ?? false)) return;
    _news = Timer(Duration(milliseconds: timings.newsDelayMs), () {
      final now = _pill;
      if (_disposed || !_readable || now == null || !isMemoryNews(now, store.lastSeen(now.scope))) return;
      _cue(MemoryBubbleCue.news);
      store.markSeen(now.scope, now.newsKey);
    });
  }

  void _armIdle() {
    _idle?.cancel();
    if (!_readable || _reason != null) return;
    final ms = timings.idleMs ?? pickMemoryBubbleMs(memoryBubbleIdleMinMs, memoryBubbleIdleMaxMs, _random);
    _idle = Timer(Duration(milliseconds: ms), () {
      if (_disposed) return;
      if (_readable && _reason == null) _cue(MemoryBubbleCue.idleElapsed);
      // Nothing to say this time: the clock simply starts over.
      if (_reason == null) _armIdle();
    });
  }

  void _armDwell() {
    _dwell?.cancel();
    _dwellDue = false;
    final reason = _reason;
    if (reason == null || !_fullySaid) return;
    final ms = reason == BubbleReason.news
        ? timings.newsDwellMs
        : (timings.chatterDwellMs ??
              pickMemoryBubbleMs(memoryBubbleChatterDwellMinMs, memoryBubbleChatterDwellMaxMs, _random));
    _dwell = Timer(Duration(milliseconds: ms), () {
      if (_disposed) return;
      if (_held) {
        _dwellDue = true;
      } else {
        _finishDwell();
      }
    });
  }

  void _finishDwell() {
    _dwellDue = false;
    if (_reason == BubbleReason.idle && !_chained && _readable && _random.nextDouble() < timings.chainChance) {
      final next = _nextIdleSay();
      if (next != null && next != _say) {
        _chained = true;
        _say = next;
        _sayCount++;
        notifyListeners();
        _armDwell();
        return;
      }
    }
    _cue(MemoryBubbleCue.dwellOver);
  }

  BubbleSay? _nextIdleSay() {
    final pill = _pill;
    final sayMemory = _idleTurns.isEven;
    _idleTurns++;
    final memory = (sayMemory || tips.isEmpty) && (pill?.hasSomethingToSay ?? false);
    final memoryTurn = memory && pill!.recall.isNotEmpty ? store.nextRecallTurn(pill.scope) : 0;
    final tipTurn = memory || tips.isEmpty ? 0 : store.nextTipTurn();
    return idleBubbleSay(pill, sayMemory: memory, memoryTurn: memoryTurn, tipTurn: tipTurn, tips: tips);
  }

  /// Every change of what it says goes through the rules.
  void _cue(MemoryBubbleCue cue) {
    final next = MemoryBubbleTurn(reason: _reason, spokeThisForeground: _spoke).after(cue);
    final starts = next.reason;
    final was = _reason;
    if (starts != null && starts != was) {
      // Nothing left to say (no tips, no memory): it stays quiet.
      final line = starts == BubbleReason.news ? BubbleSayMemory(_pill?.latest) : _nextIdleSay();
      if (line == null) return;
      _say = line;
      _sayCount++;
      _chained = false;
    }
    _reason = next.reason;
    _spoke = next.spokeThisForeground;
    if (_reason == null) {
      _dwell?.cancel();
      _dwellDue = false;
      if (was != null) _armIdle();
    } else {
      _idle?.cancel();
      if (_reason != was) _armDwell();
    }
    if (_reason != was || starts != null) notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    _idle?.cancel();
    _dwell?.cancel();
    _news?.cancel();
    super.dispose();
  }
}
