import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../settings/settings_view_model.dart';
import 'landing_models.dart';
import 'landing_screen.dart';
import 'landing_store.dart';
import 'landing_view_model.dart';

/// Re-run requests from Settings › Help and about › Welcome guide
/// (Android LandingGate.requestRerun): each bump opens a fresh landing.
class LandingRerunRequests extends Notifier<int> {
  @override
  int build() => 0;

  void request() => state++;
}

final landingRerunProvider = NotifierProvider<LandingRerunRequests, int>(LandingRerunRequests.new);

/// Settings › Welcome guide: back to Home with the landing running again (accounts kept).
void openWelcomeGuide(BuildContext context, WidgetRef ref) {
  Navigator.of(context).popUntil((route) => route.isFirst);
  ref.read(landingRerunProvider.notifier).request();
}

/// The first-run landing's host (Android LandingHost + LandingGate), mounted
/// ABOVE the app chrome so it covers the bar and the player too. Shows on a
/// first launch with no account, and when Settings asks for a re-run; it
/// removes itself after the hand-off into Home.
///
/// Until the first-run check is made, the window shows only the page colour
/// (Android holds the splash), so an existing user never sees the landing
/// flash and a new one never sees the shell. While a first-run landing shows,
/// the shell stays offstage and still (nothing in it is reachable, and
/// painting it under an opaque landing is wasted work) until the landing
/// nears its end and the shell warms up beneath it for the hand-off.
class LandingHost extends ConsumerStatefulWidget {
  const LandingHost({super.key, required this.child, this.decisionTimeout = splashHoldLimit});

  /// The app chrome (bar, player, Navigator).
  final Widget child;

  /// The page colour never waits on the check longer than this; a landing
  /// decided later fades in over the shell.
  final Duration decisionTimeout;

  static const splashHoldLimit = Duration(milliseconds: 1500);

  @override
  ConsumerState<LandingHost> createState() => _LandingHostState();
}

enum _Phase { deciding, hidden, showing }

class _LandingHostState extends ConsumerState<LandingHost> {
  _Phase _phase = _Phase.deciding;
  bool _holdShell = true;
  bool _timedOut = false;
  bool _fadeIn = false;
  LandingSession? _session;
  int _serial = 0;
  int _handledRerun = 0;
  Timer? _timeout;
  bool _checking = false;

  @override
  void initState() {
    super.initState();
    _handledRerun = ref.read(landingRerunProvider);
    _timeout = Timer(widget.decisionTimeout, () {
      if (!mounted || _phase != _Phase.deciding) return;
      setState(() {
        _timedOut = true;
        _holdShell = false;
      });
    });
    _decide();
  }

  @override
  void dispose() {
    _timeout?.cancel();
    super.dispose();
  }

  Future<void> _decide() async {
    bool done;
    try {
      done = await ref.read(landingStoreProvider).isDone();
    } catch (_) {
      done = false;
    }
    if (!mounted || _phase != _Phase.deciding) return;
    if (done) {
      _settle(show: false);
      return;
    }
    _checking = true;
    _checkAccounts(ref.read(settingsViewModelProvider));
  }

  void _checkAccounts(SettingsUiState accounts) {
    if (!_checking || _phase != _Phase.deciding || accounts is! SettingsContent) return;
    _checking = false;
    if (accounts.accounts.isEmpty) {
      _settle(show: true);
    } else {
      // An existing install: it never sees the landing, even after deleting every account.
      unawaited(ref.read(landingStoreProvider).markDone().catchError((Object _) {}));
      _settle(show: false);
    }
  }

  void _settle({required bool show}) {
    _timeout?.cancel();
    setState(() {
      if (show) {
        _phase = _Phase.showing;
        _fadeIn = _timedOut;
        _session = LandingSession(LandingMode.firstRun, ++_serial);
        // Nothing in the shell is reachable meanwhile.
        _holdShell = !_timedOut;
      } else {
        _phase = _Phase.hidden;
        _holdShell = false;
      }
    });
  }

  void _rerun() {
    setState(() {
      _timeout?.cancel();
      _checking = false;
      _phase = _Phase.showing;
      _fadeIn = true;
      _holdShell = false;
      _session = LandingSession(LandingMode.rerun, ++_serial);
    });
  }

  void _onShellWanted() {
    if (_holdShell && mounted) setState(() => _holdShell = false);
  }

  void _onClosed() {
    if (!mounted) return;
    setState(() {
      _phase = _Phase.hidden;
      _holdShell = false;
      _session = null;
    });
  }

  @override
  Widget build(BuildContext context) {
    ref.listen(settingsViewModelProvider, (_, next) => _checkAccounts(next));
    ref.listen(landingRerunProvider, (_, next) {
      if (next > _handledRerun) {
        _handledRerun = next;
        _rerun();
      }
    });
    final session = _session;
    final showing = _phase == _Phase.showing && session != null;
    final hold = _holdShell;
    return Stack(
      fit: StackFit.expand,
      children: [
        ExcludeSemantics(
          excluding: showing,
          child: Offstage(
            offstage: hold,
            child: TickerMode(enabled: !hold, child: widget.child),
          ),
        ),
        if (_phase == _Phase.deciding && !_timedOut)
          ColoredBox(color: Theme.of(context).colorScheme.surface, child: const SizedBox.expand()),
        if (showing)
          LandingScreen(
            key: ValueKey(session),
            session: session,
            fadeIn: _fadeIn,
            onShellWanted: _onShellWanted,
            onClosed: _onClosed,
          ),
      ],
    );
  }
}
