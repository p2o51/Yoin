/// Whose turn Jump Back In's memory 1×2s are (Android ui/home/JbiMemoryTurn.kt,
/// owner 2026-11-20): one more each time the app starts, and each time it
/// comes back after [JbiMemoryTurn.awayMs] in the background — a player lives
/// in the background for hours, so a cold start alone would hardly ever come.
/// Coming back sooner (a reply to a message) keeps the turn, so nothing
/// changes under the user's eyes. Kept on this device.
library;

import 'dart:async';

import 'package:flutter/widgets.dart' show AppLifecycleListener;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../platform/storage/stores.dart';
import 'home_view_model.dart';

/// The rules, without the lifecycle: [start] once per process, [hidden] and
/// [shown] as the app leaves and comes back.
final class JbiMemoryTurn {
  JbiMemoryTurn(this._store, {required int Function() clock, this.awayMs = defaultAwayMs}) : _clock = clock;

  static const int defaultAwayMs = 30 * 60 * 1000;

  /// The counter's key (Android SharedPreferences yoin_home_jbi › memory_turn).
  static const String key = 'home_jbi.memory_turn';

  final KeyValueStore _store;
  final int Function() _clock;
  final int awayMs;
  int? _backgroundAt;
  int _last = 0;

  /// The app started: the next turn.
  Future<int> start() => _advance();

  void hidden() => _backgroundAt = _clock();

  /// The app is back: the next turn after [awayMs] away, else null (the turn stands).
  Future<int?> shown() async {
    final away = _backgroundAt;
    if (away == null) return null;
    _backgroundAt = null;
    if (_clock() - away < awayMs) return null;
    return _advance();
  }

  Future<int> _advance() async {
    int stored;
    try {
      stored = int.tryParse(await _store.read(key) ?? '') ?? 0;
    } catch (_) {
      stored = _last; // Unreadable: count on in memory.
    }
    final next = (stored > _last ? stored : _last) + 1;
    _last = next;
    try {
      await _store.write(key, '$next');
    } catch (_) {
      // Not saved: this run still turns.
    }
    return next;
  }
}

/// The current turn (0 until the start's read lands; [JbiMemoryTurnNotifier.ready]
/// waits for it). Read by Home's memory signals; a new one deals the memory
/// cards again.
class JbiMemoryTurnNotifier extends Notifier<int> {
  late final JbiMemoryTurn _turn;
  late final Future<int> _started;
  AppLifecycleListener? _lifecycle;

  @override
  int build() {
    _turn = JbiMemoryTurn(
      ref.read(keyValueStoreProvider),
      clock: () => ref.read(homeClockProvider)().millisecondsSinceEpoch,
    );
    _started = _turn.start().then((turn) {
      if (ref.mounted) state = turn;
      return turn;
    });
    try {
      _lifecycle = AppLifecycleListener(onHide: _turn.hidden, onShow: _onShow);
    } catch (_) {
      _lifecycle = null; // No binding (plain unit tests).
    }
    ref.onDispose(() => _lifecycle?.dispose());
    return 0;
  }

  /// The turn once the start's turn is known.
  Future<int> ready() async {
    await _started;
    return state;
  }

  Future<void> _onShow() async {
    final next = await _turn.shown();
    if (next != null && ref.mounted) state = next;
  }
}

final jbiMemoryTurnProvider = NotifierProvider<JbiMemoryTurnNotifier, int>(JbiMemoryTurnNotifier.new);
