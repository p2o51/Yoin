import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';

/// Who wins a system back when several window-level owners are open at
/// once (adaptive-principles principle 8: priority by gating, not by mount
/// order). Now Playing sits above everything; Memories (over the shell
/// column) above the detail column.
abstract final class ChromeBackPriority {
  /// The first-run landing covers the whole window, bar and player included.
  static const int landing = 40;
  static const int nowPlaying = 30;
  static const int memories = 20;
  static const int detailColumn = 10;
}

/// Shared back infrastructure for chrome-level overlays (Now Playing): the
/// chrome sits ABOVE the app Navigator, so system back (HarmonyOS side
/// swipe / back key; no progress, it commits) would otherwise pop whatever
/// route is under the overlay. While an overlay is open it registers a
/// [PopEntry] on the Navigator's current top route, so the Navigator's
/// maybePop hands the back to [onBack] instead of popping. Feature code never
/// writes PopScope; this is the one place that does the equivalent.
///
/// An entry given [owns] is arbitrated: the route calls every pop entry in
/// registration order, so a handler that closes its surface would otherwise
/// let the next one see "nothing above me" and act on the same back. Of the
/// arbitrated entries on the route, only the highest-[priority] one whose
/// [owns] is true when the back arrives gets it; the rest of that dispatch
/// is ignored. Entries without [owns] are called as before.
class ChromeBackEntry extends PopEntry<Object?> {
  ChromeBackEntry(this.onBack, {this.owns, this.priority = 0});

  /// Called for every system back while registered (arbitrated: when chosen).
  final VoidCallback onBack;

  /// Whether this owner holds back right now (see the class doc).
  final bool Function()? owns;
  final int priority;

  final ValueNotifier<bool> _canPop = ValueNotifier(false);
  ModalRoute<Object?>? _route;

  static final List<ChromeBackEntry> _arbitrated = [];
  static bool _dispatching = false;

  @override
  ValueListenable<bool> get canPopNotifier => _canPop;

  @override
  void onPopInvokedWithResult(bool didPop, Object? result) {
    if (didPop) return;
    if (owns == null) {
      onBack();
      return;
    }
    // One back, one owner: the first arbitrated entry the route reaches
    // decides for all of them, from the state before anyone acted.
    if (_dispatching) return;
    _dispatching = true;
    scheduleMicrotask(() => _dispatching = false);
    final route = _route;
    final candidates = _arbitrated.where((e) => identical(e._route, route)).toList()
      ..sort((a, b) => b.priority.compareTo(a.priority));
    for (final entry in candidates) {
      if (entry.owns!()) {
        entry.onBack();
        return;
      }
    }
  }

  bool get isAttached => _route != null;

  /// Registers on [navigator]'s top route (no-op if already attached).
  void attach(NavigatorState navigator) {
    if (_route != null) return;
    ModalRoute<Object?>? top;
    // popUntil with a predicate true at the top route pops nothing: it is
    // the public way to read the current route.
    navigator.popUntil((route) {
      if (route is ModalRoute<Object?>) top = route;
      return true;
    });
    final route = top;
    if (route == null) return;
    route.registerPopEntry(this);
    _route = route;
    if (owns != null) _arbitrated.add(this);
  }

  void detach() {
    _route?.unregisterPopEntry(this);
    _route = null;
    _arbitrated.remove(this);
  }

  void dispose() {
    detach();
    _canPop.dispose();
  }
}
