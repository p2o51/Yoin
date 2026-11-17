/// Which way the song changed, for the Now Playing cover's directional
/// halftone reveal (NowPlayingViewModel.kt skipDirection).
library;

import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/model/model.dart';
import '../playback/playback_controller.dart';
import 'transport_pulse.dart';

/// How long −1 is held once a PREVIOUS tap's song change has landed, while the
/// new cover composes with it (SKIP_DIRECTION_HOLD_MS).
const skipDirectionHold = Duration(milliseconds: 1000);

/// +1 = moved forward (tap NEXT, or played on), −1 = back. Only a display hint
/// for the cover's ride-in. −1 belongs to the one song change a PREVIOUS tap
/// causes: it returns to +1 [skipDirectionHold] after that change lands, or as
/// soon as the tap expires ([transportTapValidityMs]) without one (a restart,
/// or nothing before the first song), so a later auto-advance rides in forward.
class CoverSkipDirection extends Notifier<int> {
  Timer? _timer;
  ProviderSubscription<MediaId?>? _watch;

  @override
  int build() {
    ref.onDispose(_cancel);
    return 1;
  }

  void forward() {
    _cancel();
    state = 1;
  }

  void back() {
    final tappedOn = ref.read(playbackControllerProvider).current?.id;
    _cancel();
    state = -1;
    _timer = Timer(const Duration(milliseconds: transportTapValidityMs), forward);
    _watch = ref.listen(playbackControllerProvider.select((s) => s.current?.id), (_, id) {
      if (id == null || id == tappedOn) return;
      _cancel();
      _timer = Timer(skipDirectionHold, forward);
    });
  }

  void _cancel() {
    _timer?.cancel();
    _timer = null;
    _watch?.close();
    _watch = null;
  }
}

final coverSkipDirectionProvider = NotifierProvider<CoverSkipDirection, int>(CoverSkipDirection.new);
