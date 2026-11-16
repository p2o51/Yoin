import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

/// Who wins a system back when several window-level owners are open at
/// once (adaptive-principles principle 8: priority by gating, not by mount
/// order). The first-run landing covers everything. The shell's owners
/// (Now Playing, Home edit, Memories, the detail column) each own back only
/// while the window's `ShellBackLedger` names them — side-by-side surfaces
/// close newest first — so of those at most one owns at a time and their
/// priorities only break a tie that cannot happen.
abstract final class ChromeBackPriority {
  /// The first-run landing covers the whole window, bar and player included.
  static const int landing = 40;

  /// A card over the shell that Android shows as a dialog window (the
  /// account switcher): it takes back before every shell owner.
  static const int dialog = 35;
  static const int nowPlaying = 30;
  static const int homeEdit = 25;
  static const int memories = 20;
  static const int detailColumn = 10;

  /// A pushed page's own level (Settings' pane page): below a player opened
  /// over that page.
  static const int page = 0;
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
/// is ignored. When none of them owns it (Android: no shell owner) and no
/// unarbitrated entry shares the route, the back goes where it would with
/// no entry at all: the route pops, or at the root the app goes to the
/// background. Entries without [owns] are called as before.
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
  static final List<ChromeBackEntry> _unarbitrated = [];
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
    if (route == null || _unarbitrated.any((e) => identical(e._route, route))) return;
    // Not synchronously: this runs inside the navigator's own maybePop.
    scheduleMicrotask(() => _passOn(route));
  }

  /// No owner here: the back the route would have had without entries.
  static void _passOn(ModalRoute<Object?> route) {
    if (!route.isActive || !route.isCurrent) return;
    if (route.isFirst) {
      SystemNavigator.pop();
    } else {
      route.navigator?.pop();
    }
  }

  bool get isAttached => _route != null;

  /// Whether some registered entry would take a back arriving now: an
  /// unarbitrated one always does, an arbitrated one while it [owns] it
  /// (Android: a shell back owner other than None).
  static bool get anyTakesBack => _unarbitrated.isNotEmpty || _arbitrated.any((e) => e.owns!());

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
    (owns != null ? _arbitrated : _unarbitrated).add(this);
  }

  void detach() {
    _route?.unregisterPopEntry(this);
    _route = null;
    _arbitrated.remove(this);
    _unarbitrated.remove(this);
  }

  void dispose() {
    detach();
    _canPop.dispose();
  }
}
