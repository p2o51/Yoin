/// Failed covers ask again (Android ArtworkRetry.kt + ArtworkForegroundRetry.kt
/// + YoinApplication.registerArtworkNetworkRetry): on a backoff while a
/// transient failure stays on screen, and whenever [ArtworkRetrySignal] moves —
/// the network recovers ([ArtworkNetworkRecovery]) or the app comes back to
/// the foreground ([ArtworkForegroundRetry]). A cover scrolled away and back
/// asks again on its own: a failed load leaves the image cache.
library;

import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';

import '../../data/cache/cover_cache.dart';
import '../../platform/network/network_bearer.dart';

/// The app-wide "ask failed artwork again" signal: a generation that moves
/// when the network recovers and when the app returns to the foreground. Only
/// artwork whose load has failed listens to it; healthy artwork never does.
abstract final class ArtworkRetrySignal {
  static final ValueNotifier<int> _generation = ValueNotifier(0);

  static ValueListenable<int> get generation => _generation;

  static void bump() => _generation.value++;
}

enum ArtworkFailureKind {
  /// An I/O failure (offline, timeout, reset) or HTTP 5xx: retried on a backoff
  /// while the artwork stays on screen, and on every signal; a signal also
  /// starts the backoff over.
  transient,

  /// 404/410 and every other non-5xx HTTP error: only a signal retries it.
  awaitSignal,

  /// The bytes came back but made no image. The cover cache has already
  /// dropped its stored copy; only a signal retries it.
  undecodable,
}

abstract final class ArtworkRetryPolicy {
  /// The automatic retries of a [ArtworkFailureKind.transient] failure, in order; then signals only.
  static const List<Duration> backoff = [Duration(seconds: 3), Duration(seconds: 15), Duration(seconds: 60)];

  static ArtworkFailureKind classify(Object error) => switch (error) {
    ArtworkHttpException(:final statusCode) =>
      statusCode >= 500 && statusCode <= 599 ? ArtworkFailureKind.transient : ArtworkFailureKind.awaitSignal,
    ArtworkUndecodableException() => ArtworkFailureKind.undecodable,
    IOException() || TimeoutException() => ArtworkFailureKind.transient,
    _ => ArtworkFailureKind.undecodable,
  };
}

/// One artwork's load-and-retry state, per url. [failure] is null while a
/// request is out (or has succeeded). [fallbackUnder] holds the type icon from
/// the first failure until a retried image has fully revealed over it, so a
/// recovery never cuts from icon to cover.
@immutable
final class ArtworkRetryState {
  const ArtworkRetryState({
    required this.requestSignal,
    this.failure,
    this.backoffRetries = 0,
    this.fallbackUnder = false,
  });

  /// [ArtworkRetrySignal]'s generation when the current request went out.
  final int requestSignal;
  final ArtworkFailureKind? failure;
  final int backoffRetries;
  final bool fallbackUnder;

  bool get requesting => failure == null;

  ArtworkRetryState failed(Object error) => _copy(failure: ArtworkRetryPolicy.classify(error), fallbackUnder: true);

  /// How long until this failure retries on its own; null when only a signal can retry it.
  Duration? backoff() => switch (failure) {
    ArtworkFailureKind.transient =>
      backoffRetries < ArtworkRetryPolicy.backoff.length ? ArtworkRetryPolicy.backoff[backoffRetries] : null,
    _ => null,
  };

  /// A signal that moved after the request went out counts, even if it moved before the failure landed.
  bool signalledBy(int generation) => generation != requestSignal;

  /// A signal retry starts the backoff over: each recovery gets every backoff retry again.
  ArtworkRetryState retried({required int signal, required bool byBackoff}) => ArtworkRetryState(
    requestSignal: signal,
    backoffRetries: byBackoff ? backoffRetries + 1 : 0,
    fallbackUnder: fallbackUnder,
  );

  ArtworkRetryState revealed() => _copy(fallbackUnder: false);

  ArtworkRetryState _copy({ArtworkFailureKind? failure, bool? fallbackUnder}) => ArtworkRetryState(
    requestSignal: requestSignal,
    failure: failure ?? this.failure,
    backoffRetries: backoffRetries,
    fallbackUnder: fallbackUnder ?? this.fallbackUnder,
  );

  @override
  bool operator ==(Object other) =>
      other is ArtworkRetryState &&
      other.requestSignal == requestSignal &&
      other.failure == failure &&
      other.backoffRetries == backoffRetries &&
      other.fallbackUnder == fallbackUnder;

  @override
  int get hashCode => Object.hash(requestSignal, failure, backoffRetries, fallbackUnder);

  @override
  String toString() => 'ArtworkRetryState($requestSignal, $failure, $backoffRetries, $fallbackUnder)';
}

/// Waits a failed [ArtworkRetryState] out (Android awaitArtworkRetry): calls
/// back with the state its retry starts from when the backoff elapses or
/// [signal] moves, whichever is first. [cancel] it when the artwork goes.
final class ArtworkRetryWait {
  ArtworkRetryWait(this.state, this.onRetry, {ValueListenable<int>? signal})
    : _signal = signal ?? ArtworkRetrySignal.generation {
    if (state.signalledBy(_signal.value)) {
      // The signal moved while the request was out: retry now (on the next
      // microtask, so the caller is not called back from its own constructor).
      scheduleMicrotask(() => _fire(byBackoff: false));
      return;
    }
    _signal.addListener(_onSignal);
    final backoff = state.backoff();
    if (backoff != null) _timer = Timer(backoff, () => _fire(byBackoff: true));
  }

  final ArtworkRetryState state;
  final void Function(ArtworkRetryState next) onRetry;
  final ValueListenable<int> _signal;
  Timer? _timer;
  bool _done = false;

  void _onSignal() {
    if (state.signalledBy(_signal.value)) _fire(byBackoff: false);
  }

  void _fire({required bool byBackoff}) {
    if (_done) return;
    cancel();
    onRetry(state.retried(signal: _signal.value, byBackoff: byBackoff));
  }

  void cancel() {
    _done = true;
    _timer?.cancel();
    _signal.removeListener(_onSignal);
  }
}

/// A read of a cover (its palette, its seed) that asks again like failed
/// artwork (Android ExpressiveBackdropPalette's retry loop): on a backoff for
/// a transient failure, and whenever [ArtworkRetrySignal] moves.
final class ArtworkReadRetry {
  ArtworkRetryState? _state;
  ArtworkRetryWait? _wait;

  /// A read went out (the first one starts the state; a retry's keeps it).
  void requested() => _state ??= ArtworkRetryState(requestSignal: ArtworkRetrySignal.generation.value);

  /// The read failed with [error]: [retry] runs when it should be read again.
  void failed(Object error, VoidCallback retry) {
    final state = (_state ?? ArtworkRetryState(requestSignal: ArtworkRetrySignal.generation.value)).failed(error);
    _wait?.cancel();
    _wait = ArtworkRetryWait(state, (next) {
      _state = next;
      retry();
    });
  }

  /// Another cover: start over.
  void reset() {
    _wait?.cancel();
    _wait = null;
    _state = null;
  }
}

/// Decides which default-network events retry failed artwork (Android
/// ArtworkNetworkRecovery). A recovery is a different default network
/// arriving, the current one regaining validation, or this app's traffic on it
/// being unblocked. Registering replays the network that was current: that
/// delivery, its first capabilities and its first blocked state only set the
/// baseline. [N] is the network's id.
class ArtworkNetworkRecovery<N extends Object> {
  ArtworkNetworkRecovery(N? initialNetwork) : _network = initialNetwork;

  N? _network;

  // Null until the current network's first capabilities arrive.
  bool? _validated;
  bool _blocked = false;

  /// True when [network] is a recovery: a default network other than the one already tracked.
  bool onAvailable(N network) {
    if (network == _network) return false;
    _track(network);
    return true;
  }

  /// True when the tracked network regains validation after losing it or starting without it.
  bool onCapabilitiesChanged(N network, {required bool validated}) {
    if (network != _network) return false;
    final regained = _validated == false && validated;
    _validated = validated;
    return regained;
  }

  /// True when this app's traffic on the tracked network is unblocked.
  bool onBlockedStatusChanged(N network, {required bool blocked}) {
    if (network != _network) return false;
    final unblocked = _blocked && !blocked;
    _blocked = blocked;
    return unblocked;
  }

  void onLost(N network) {
    if (network == _network) _track(null);
  }

  /// Feeds one platform event; true when it is a recovery.
  bool onEvent(DefaultNetworkEvent<N> event) {
    switch (event) {
      case DefaultNetworkAvailable(:final network):
        return onAvailable(network);
      case DefaultNetworkCapabilities(:final network, :final validated):
        return onCapabilitiesChanged(network, validated: validated);
      case DefaultNetworkBlocked(:final network, :final blocked):
        return onBlockedStatusChanged(network, blocked: blocked);
      case DefaultNetworkLost(:final network):
        onLost(network);
        return false;
    }
  }

  void _track(N? network) {
    _network = network;
    _validated = null;
    _blocked = false;
  }
}

/// Retry failed artwork when the app returns to the foreground (Android
/// ArtworkForegroundRetry on the process lifecycle). The first resume is not a
/// recovery; only one after the app was hidden is.
class ArtworkForegroundRetry {
  ArtworkForegroundRetry([this._retry = ArtworkRetrySignal.bump]);

  final VoidCallback _retry;
  bool _hidden = false;

  void onHide() => _hidden = true;

  void onResume() {
    if (!_hidden) return;
    _hidden = false;
    _retry();
  }
}

/// Moves [ArtworkRetrySignal] for the whole app: the default network's
/// recoveries ([NetworkBearerSource.defaultNetworkEvents]) and every return to
/// the foreground. Started once from main.dart.
final class ArtworkRetryWatcher {
  ArtworkRetryWatcher._(this._subscription, this._lifecycle);

  final StreamSubscription<DefaultNetworkEvent<int>>? _subscription;
  final AppLifecycleListener _lifecycle;

  static Future<ArtworkRetryWatcher> start(NetworkBearerSource network) async {
    final foreground = ArtworkForegroundRetry();
    final lifecycle = AppLifecycleListener(onHide: foreground.onHide, onResume: foreground.onResume);
    StreamSubscription<DefaultNetworkEvent<int>>? subscription;
    try {
      // Listen before reading the current network, so a change between the
      // two is not lost: events that arrive first wait for the baseline.
      ArtworkNetworkRecovery<int>? recovery;
      final early = <DefaultNetworkEvent<int>>[];
      subscription = network.defaultNetworkEvents.listen((event) {
        final tracking = recovery;
        if (tracking == null) {
          early.add(event);
        } else if (tracking.onEvent(event)) {
          ArtworkRetrySignal.bump();
        }
      });
      final tracking = recovery = ArtworkNetworkRecovery<int>(await network.defaultNetwork());
      if (early.map(tracking.onEvent).toList().any((recovered) => recovered)) ArtworkRetrySignal.bump();
    } catch (e) {
      // The foreground retry still covers a host that sends no network events.
      debugPrint('YoinArtwork: no network events for artwork retries: $e');
    }
    return ArtworkRetryWatcher._(subscription, lifecycle);
  }

  void dispose() {
    _subscription?.cancel();
    _lifecycle.dispose();
  }
}
