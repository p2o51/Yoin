/// One continuous task, several kinds of work. HarmonyOS gives an ability one
/// data-transfer task (TransferPlugin holds one): downloads and a playlist
/// restore both keep the app running through it, and one of them finishing
/// must not end it for the other. Each takes a [SharedBackgroundTransfer.lease],
/// a [BackgroundTransferHost] of its own: the task is held while any lease
/// holds it and let go when the last one releases; its notification shows the
/// lease that began holding last (a restore started during downloads shows
/// over them, and the downloads' progress comes back when it ends). Android
/// keeps them apart with two notifications (YoinDownloadService, RestoreLiveUpdate).
library;

import 'dart:async';

import 'package:flutter/foundation.dart';

import 'background_transfer.dart';

final class SharedBackgroundTransfer {
  SharedBackgroundTransfer(this.host);

  /// The platform's one task.
  final BackgroundTransferHost host;

  /// The leases holding the task, in the order they began holding (the last one's notice shows).
  final _holding = <_Lease>[];
  StreamSubscription<bool>? _ended;
  Future<void> _calls = Future.value();

  /// A user of the task; [name] is for logs.
  BackgroundTransferHost lease(String name) => _Lease(this, name);

  /// The notice the task shows now, for tests.
  @visibleForTesting
  TransferNotice? get shown => _holding.isEmpty ? null : _holding.last.notice;

  /// The pending host calls, for tests.
  @visibleForTesting
  Future<void> get settled => _calls;

  void dispose() {
    unawaited(_ended?.cancel());
    _ended = null;
    for (final lease in _holding) {
      lease.notice = null;
    }
    _holding.clear();
  }

  Future<bool> _hold(_Lease lease, TransferNotice notice) {
    _ended ??= host.ended.listen(_onEnded);
    if (!_holding.contains(lease)) _holding.add(lease);
    lease.notice = notice;
    return _enqueue(() async {
      // Released (or the task ended) before this call's turn: nothing to show for it.
      if (!_holding.contains(lease)) return false;
      final ok = await host.hold(_holding.last.notice!);
      if (!ok) {
        // Not held (the app is behind): this lease asks again; the others keep their own turn.
        _holding.remove(lease);
        lease.notice = null;
      }
      return ok;
    }, false);
  }

  Future<void> _release(_Lease lease) {
    if (!_holding.remove(lease)) return _calls;
    lease.notice = null;
    return _enqueue(() async {
      if (_holding.isEmpty) {
        await host.release();
      } else {
        // The task stays for the others: it shows theirs again.
        await host.hold(_holding.last.notice!);
      }
    }, null);
  }

  /// The task ended (the user removed its notification, or the system stopped it): every lease holding it hears.
  void _onEnded(bool byUser) {
    final leases = [..._holding];
    _holding.clear();
    for (final lease in leases) {
      lease.notice = null;
      lease._ended.add(byUser);
    }
  }

  Future<T> _enqueue<T>(Future<T> Function() call, T failed) {
    final done = Completer<T>();
    _calls = _calls.then((_) async {
      try {
        done.complete(await call());
      } catch (e) {
        debugPrint('YoinTransfer: $e');
        done.complete(failed);
      }
    });
    return done.future;
  }
}

final class _Lease implements BackgroundTransferHost {
  _Lease(this._shared, this.name);

  final SharedBackgroundTransfer _shared;
  final String name;

  /// What this lease shows while it holds the task; null when it doesn't.
  TransferNotice? notice;

  final _ended = StreamController<bool>.broadcast();

  @override
  Future<bool> hold(TransferNotice notice) => _shared._hold(this, notice);

  @override
  Future<void> release() => _shared._release(this);

  @override
  Stream<bool> get ended => _ended.stream;

  @override
  Future<bool> notificationsAllowed({bool ask = false}) => _shared.host.notificationsAllowed(ask: ask);

  @override
  Future<void> notify(int id, TransferNotice notice) => _shared.host.notify(id, notice);

  @override
  Future<void> cancelNotice(int id) => _shared.host.cancelNotice(id);

  @override
  String toString() => 'TransferLease($name)';
}
