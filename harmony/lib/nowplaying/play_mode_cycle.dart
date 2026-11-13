import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../playback/play_mode.dart';

/// Notices taps on the round play-mode button that go all the way round its
/// cycle ([PlayMode.next]) and back to where they started: four quick taps,
/// each within [maxGapMs] of the last. Someone stepping through every mode is
/// looking for one the cycle doesn't hold, so the button splits once to show
/// its two axes (Android ui/nowplaying/PlayModeCycle.kt, owner 2026-10-21).
class PlayModeCycle {
  PlayModeCycle({this.maxGapMs = playModeCycleGapMs});

  final int maxGapMs;
  PlayMode? _start;
  int? _lastTapMs;

  /// A tap moving the button from [from] to [to] at [nowMs]; true when it closes a full cycle.
  bool onTap(PlayMode from, PlayMode to, int nowMs) {
    final last = _lastTapMs;
    final quick = last != null && nowMs - last <= maxGapMs;
    _lastTapMs = nowMs;
    if (!quick || _start == null) _start = from;
    // The cycle comes back to a mode only after all four of its taps.
    final closed = to == _start;
    if (closed) {
      _start = null;
      _lastTapMs = null;
    }
    return closed;
  }
}

/// The longest pause between two taps that still counts as one run round the cycle (PlayModeCycleGapMs).
const int playModeCycleGapMs = 1500;

/// Split, the pill merges back into the circle after this long without a tap (PlayModeSplitHoldMs).
const int playModeSplitHoldMs = 3000;

/// Whether a run round the cycle should still split the button to show it
/// can. Android gates this on its Play modes tip being unread; Harmony has no
/// tips yet, so the "shown once" flag lives in the key-value store here.
/// Loads as true (show) and turns false once shown.
class PlayModeSplitHint extends Notifier<bool> {
  static const storeKey = 'nowplaying.playModeSplitShown';

  @override
  bool build() {
    _load();
    return true;
  }

  Future<void> _load() async {
    try {
      final shown = await ref.read(keyValueStoreProvider).read(storeKey);
      if (ref.mounted && shown == '1') state = false;
    } catch (_) {
      // No store (tests): the hint stays pending for this session.
    }
  }

  /// The split was shown: never again.
  void markShown() {
    if (!state) return;
    state = false;
    try {
      ref.read(keyValueStoreProvider).write(storeKey, '1').ignore();
    } catch (_) {}
  }
}

final playModeSplitHintProvider = NotifierProvider<PlayModeSplitHint, bool>(PlayModeSplitHint.new);
