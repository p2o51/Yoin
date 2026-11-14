import 'dart:async';

import 'package:flutter/widgets.dart';

import '../theme/tokens.dart';

/// Focus at ease (Android LyricToolsIdleState + FocusIdleMs, owner
/// 2026-11-05): [NowPlayingFocusTokens.idleMs] without a touch while synced
/// lyrics play in focus, the transport's buttons and times step away (the
/// progress bar stays); any touch brings them back. [track] starts a FRESH
/// count every time eligibility returns.
class FocusIdle extends ChangeNotifier {
  FocusIdle({this.idleMs = NowPlayingFocusTokens.idleMs});

  final int idleMs;
  bool _eligible = false;
  bool _idle = false;
  Timer? _timer;

  bool get idle => _idle;
  bool get eligible => _eligible;

  void track(bool eligible) {
    if (eligible == _eligible) return;
    _eligible = eligible;
    _timer?.cancel();
    _timer = null;
    _setIdle(false);
    if (eligible) _arm();
  }

  /// A touch anywhere on the player.
  void onInteraction() {
    if (!_eligible) return;
    _setIdle(false);
    _arm();
  }

  void _arm() {
    _timer?.cancel();
    _timer = Timer(Duration(milliseconds: idleMs), () => _setIdle(true));
  }

  void _setIdle(bool value) {
    if (_idle == value) return;
    _idle = value;
    notifyListeners();
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }
}

/// Reports every pointer down on the player to [idle] (translucent: the
/// touch still reaches what is under it).
class FocusIdleTouchTracker extends StatelessWidget {
  const FocusIdleTouchTracker({super.key, required this.idle, required this.child});

  final FocusIdle idle;
  final Widget child;

  @override
  Widget build(BuildContext context) =>
      Listener(behavior: HitTestBehavior.translucent, onPointerDown: (_) => idle.onInteraction(), child: child);
}

/// Swallows taps on [child] while [atEase] is past half, so the first tap
/// on the hidden buttons only wakes them (the tracker above sees the down
/// and brings them back; the down was absorbed at hit test, so the whole
/// tap is). Android lyricToolsInPlaceWakeGuard.
class AtEaseWakeGuard extends StatelessWidget {
  const AtEaseWakeGuard({super.key, required this.atEase, required this.child});

  final Animation<double> atEase;
  final Widget child;

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: atEase,
    child: child,
    builder: (context, child) => AbsorbPointer(absorbing: atEase.value > 0.5, child: child),
  );
}
