import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../home/home_bubble_tips.dart';
import '../playback/play_mode.dart';
import '../settings/tips.dart';

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
/// can: while the Play modes tip is unread (Android PlaybackControls'
/// `HomeBubbleTip.PlayModes !in readTips`); showing it marks the tip read.
/// A split already shown before tips existed here (the old "shown once"
/// flag) stays shown.
class PlayModeSplitHint extends Notifier<bool> {
  static const storeKey = 'nowplaying.playModeSplitShown';

  bool _legacyShown = false;

  @override
  bool build() {
    final unread = !ref.watch(tipReadProvider).contains(HomeBubbleTip.playModes);
    if (!_legacyShown) _load();
    return unread && !_legacyShown;
  }

  Future<void> _load() async {
    try {
      final shown = await ref.read(keyValueStoreProvider).read(storeKey);
      if (ref.mounted && shown == '1') {
        _legacyShown = true;
        state = false;
      }
    } catch (_) {
      // No store (tests): the hint follows the tip alone.
    }
  }

  /// The split was shown: the Play modes tip is read, so it never splits by itself again.
  void markShown() {
    if (!state) return;
    state = false;
    ref.read(tipReadProvider.notifier).setRead(HomeBubbleTip.playModes, true);
  }
}

final playModeSplitHintProvider = NotifierProvider<PlayModeSplitHint, bool>(PlayModeSplitHint.new);
