/// Downloads go on with the app behind (Android YoinDownloadService): while a
/// song downloads or waits for a free slot, the app holds the data-transfer
/// continuous task, whose notification shows how far the batch has come.
/// Songs only waiting (for Wi‑Fi, a network, another account) don't hold it:
/// the system cancels a data-transfer task whose progress stalls, and those
/// carry on when the app is next in front. If the listener removes the
/// notification the task isn't asked for again until the batch is over.
library;

import 'dart:async';

import 'package:flutter/widgets.dart';

import '../data/offline/offline_downloads.dart';
import '../data/offline/offline_model.dart';
import '../platform/background/background_transfer.dart';
import 'offline_strings.dart';

/// Where a batch stands: songs finished out of all of them, and the overall percent.
@immutable
final class TransferProgress {
  const TransferProgress({required this.done, required this.total, required this.percent});

  final int done;
  final int total;

  /// 0–99 while anything runs (100 would read as finished).
  final int percent;

  @override
  bool operator ==(Object other) =>
      other is TransferProgress && other.done == done && other.total == total && other.percent == percent;

  @override
  int get hashCode => Object.hash(done, total, percent);

  @override
  String toString() => 'TransferProgress($done/$total, $percent%)';
}

/// Whether [song] is being downloaded now or is next in line.
bool transferRuns(OfflineSong song) =>
    song.state == OfflineSongState.downloading || song.state == OfflineSongState.queued;

/// The batch's progress over [songs]: [batch] holds the keys of every song
/// that ran since the batch began (a song removed meanwhile no longer counts).
/// Null when nothing in [songs] runs.
TransferProgress? transferProgress(List<OfflineSong> songs, Set<String> batch) {
  if (!songs.any(transferRuns)) return null;
  var total = 0;
  var done = 0;
  var parts = 0.0;
  for (final s in songs) {
    if (!batch.contains(s.key) && !transferRuns(s)) continue;
    total++;
    if (s.state == OfflineSongState.done) {
      done++;
    } else if (s.state == OfflineSongState.downloading) {
      final p = s.percent;
      if (p > 0) parts += p / 100;
    }
  }
  if (total == 0) return null;
  final percent = ((done + parts) * 100 / total).floor().clamp(0, 99);
  return TransferProgress(done: done, total: total, percent: percent);
}

class OfflineTransferKeeper {
  OfflineTransferKeeper({
    required this.downloads,
    required this.host,
    OfflineStrings Function()? strings,
    this.updateInterval = const Duration(seconds: 1),
    this.retryInterval = const Duration(seconds: 30),
    DateTime Function()? now,
  }) : _strings = strings ?? _systemStrings,
       _now = now ?? DateTime.now;

  final OfflineDownloads downloads;
  final BackgroundTransferHost host;
  final OfflineStrings Function() _strings;
  final DateTime Function() _now;

  /// The notification is redrawn at most this often (the songs-done count at once).
  final Duration updateInterval;

  /// A refused hold (the app was behind) is asked for again after this.
  final Duration retryInterval;

  final _batch = <String>{};
  bool _held = false;
  bool _declined = false;
  bool _disposed = false;
  TransferNotice? _shown;
  int _shownDone = -1;
  DateTime _lastSent = DateTime.fromMillisecondsSinceEpoch(0);
  DateTime? _retryAfter;
  Future<void> _calls = Future.value();
  StreamSubscription<void>? _ended;
  Timer? _flush;

  /// Whether the continuous task is held now, for tests.
  @visibleForTesting
  bool get held => _held;

  void start() {
    downloads.addListener(_changed);
    _ended = host.ended.listen((_) => _onEnded());
    _changed();
  }

  void dispose() {
    _disposed = true;
    _flush?.cancel();
    downloads.removeListener(_changed);
    unawaited(_ended?.cancel());
    if (_held) {
      _held = false;
      _enqueue(host.release);
    }
  }

  void _changed() {
    if (_disposed) return;
    final songs = downloads.state.songs;
    for (final s in songs) {
      if (transferRuns(s)) _batch.add(s.key);
    }
    final progress = transferProgress(songs, _batch);
    if (progress == null) {
      // Nothing runs: the batch is over (or everything waits for a network).
      _batch.clear();
      _declined = false;
      _retryAfter = null;
      _shown = null;
      _shownDone = -1;
      _flush?.cancel();
      if (_held) {
        _held = false;
        _enqueue(host.release);
      }
      return;
    }
    if (_declined) return;
    final strings = _strings();
    final notice = TransferNotice(
      title: strings.notificationDownloading,
      text: strings.downloading(progress.done, progress.total),
      percent: progress.percent,
    );
    if (_held && notice == _shown) return;
    final now = _now();
    if (!_held) {
      final retryAfter = _retryAfter;
      if (retryAfter != null && now.isBefore(retryAfter)) return;
      _send(notice, progress.done);
      return;
    }
    // Throttled, but a finished song shows at once.
    if (progress.done != _shownDone || now.difference(_lastSent) >= updateInterval) {
      _send(notice, progress.done);
    } else {
      _flush ??= Timer(updateInterval - now.difference(_lastSent), () {
        _flush = null;
        _changed();
      });
    }
  }

  void _send(TransferNotice notice, int done) {
    _shown = notice;
    _shownDone = done;
    _lastSent = _now();
    _flush?.cancel();
    _flush = null;
    final wasHeld = _held;
    _held = true;
    _enqueue(() async {
      final ok = await host.hold(notice);
      if (_disposed) return;
      if (!ok) {
        _held = false;
        _shown = null;
        _retryAfter = _now().add(retryInterval);
        if (wasHeld) debugPrint('YoinTransfer: task lost');
      } else {
        _retryAfter = null;
      }
    });
  }

  void _onEnded() {
    if (_disposed || !_held) return;
    _held = false;
    _shown = null;
    // The listener removed the notification: not asked for again until this batch is over.
    _declined = true;
  }

  void _enqueue(Future<void> Function() call) {
    _calls = _calls.then((_) => call()).catchError((Object e) => debugPrint('YoinTransfer: $e'));
  }

  /// The pending host calls, for tests.
  @visibleForTesting
  Future<void> get settled => _calls;

  static OfflineStrings _systemStrings() {
    try {
      final chinese = WidgetsBinding.instance.platformDispatcher.locale.languageCode == 'zh';
      return OfflineStrings.forLanguage(chinese: chinese);
    } catch (_) {
      return const OfflineStrings.forLanguage(chinese: false);
    }
  }
}
