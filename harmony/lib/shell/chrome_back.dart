import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';

/// Shared back infrastructure for chrome-level overlays (Now Playing): the
/// chrome sits ABOVE the app Navigator, so system back (HarmonyOS side
/// swipe / back key; no progress, it commits) would otherwise pop whatever
/// route is under the overlay. While an overlay is open it registers a
/// [PopEntry] on the Navigator's current top route, so the Navigator's
/// maybePop hands the back to [onBack] instead of popping. Feature code never
/// writes PopScope; this is the one place that does the equivalent.
class ChromeBackEntry extends PopEntry<Object?> {
  ChromeBackEntry(this.onBack);

  /// Called for every system back while registered.
  final VoidCallback onBack;
  final ValueNotifier<bool> _canPop = ValueNotifier(false);
  ModalRoute<Object?>? _route;

  @override
  ValueListenable<bool> get canPopNotifier => _canPop;

  @override
  void onPopInvokedWithResult(bool didPop, Object? result) {
    if (!didPop) onBack();
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
  }

  void detach() {
    _route?.unregisterPopEntry(this);
    _route = null;
  }

  void dispose() {
    detach();
    _canPop.dispose();
  }
}
