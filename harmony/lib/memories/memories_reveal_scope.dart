/// The handle the shell, Home and the chrome share for Memories' reveal
/// (Android: the shell's memoriesReveal RevealState, read by Home's pull,
/// the bar's hide and the back resolver). The shell owns the controller
/// (it has the ticker) and registers it here.
library;

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'memories_motion.dart';

class MemoriesRevealHandle {
  MemoriesRevealController? _controller;

  /// 0 = the bar shows, 1 = Memories is fully open (the bar has left with it).
  final ValueNotifier<double> barHide = ValueNotifier(0);

  /// The open deck's back (diary → card first); null = the reveal closes.
  VoidCallback? deckBack;

  /// The Home pill / a memory card asks for this memory first; read once by the deck.
  int? pendingFocus;

  MemoriesRevealController? get controller => _controller;

  void attach(MemoriesRevealController controller) {
    _controller?.removeListener(_sync);
    _controller = controller;
    controller.addListener(_sync);
    _sync();
  }

  void detach(MemoriesRevealController controller) {
    if (!identical(_controller, controller)) return;
    controller.removeListener(_sync);
    _controller = null;
    barHide.value = 0;
  }

  void _sync() {
    final q = _controller?.fraction ?? 1;
    barHide.value = (1 - q).clamp(0.0, 1.0);
  }

  /// Opens Memories, [focus] (a memory's session id) first when given.
  void open({int? focus}) {
    if (focus != null) pendingFocus = focus;
    _controller?.open();
  }

  /// System back while Memories owns it.
  void back() {
    final deck = deckBack;
    if (deck != null) {
      deck();
    } else {
      _controller?.close();
    }
  }
}

final memoriesRevealHandleProvider = Provider<MemoriesRevealHandle>((ref) {
  final handle = MemoriesRevealHandle();
  ref.onDispose(handle.barHide.dispose);
  return handle;
});
