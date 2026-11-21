/// The handle the shell, Home and the chrome share for Memories' reveal
/// (Android: the shell's memoriesReveal RevealState, read by Home's pull,
/// the bar's hide and the back resolver). The shell owns the controller
/// (it has the ticker) and registers it here.
library;

import 'package:flutter/animation.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'memories_motion.dart';

class MemoriesRevealHandle {
  MemoriesRevealController? _controller;
  Animation<double>? _uncovered;

  /// 0 = the bar shows, 1 = Memories is fully open (the bar has left with it).
  final ValueNotifier<double> barHide = ValueNotifier(0);

  /// Pointers that started on a header control (such as the account switcher)
  /// or while its hold-to-fan menu owns the touch: [MemoriesPullDetector]
  /// leaves them alone so sliding down from the switcher never pulls Memories.
  final Set<int> _suppressedPointers = {};
  bool _pullSuppressed = false;

  /// Suppresses Memories pull for [pointer] until it lifts or cancels.
  void suppressPullForPointer(int pointer) => _suppressedPointers.add(pointer);

  /// Releases pointer-scoped pull suppression when [pointer] ends.
  void releasePullForPointer(int pointer) => _suppressedPointers.remove(pointer);

  /// Suppresses Memories pull while an exclusive gesture (like account fan) runs.
  void setPullSuppressed(bool suppressed) => _pullSuppressed = suppressed;

  /// Whether [MemoriesPullDetector] must ignore [pointer].
  bool isPullSuppressed(int pointer) => _pullSuppressed || _suppressedPointers.contains(pointer);

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
    _uncovered?.removeListener(_sync);
    _uncovered = null;
    _controller = null;
    barHide.value = 0;
  }

  /// 1 while the shell is the top page, 0 while a page pushed from Memories
  /// (Go to album) covers it: that page has the bar, so Memories lets it go.
  void attachCover(Animation<double> uncovered) {
    _uncovered?.removeListener(_sync);
    _uncovered = uncovered..addListener(_sync);
    _sync();
  }

  void _sync() {
    final q = _controller?.fraction ?? 1;
    final shown = (_uncovered?.value ?? 1).clamp(0.0, 1.0);
    barHide.value = ((1 - q) * shown).clamp(0.0, 1.0);
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
