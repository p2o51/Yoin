/// A restore's progress outside the app (Android RestoreLiveUpdate, owner
/// 2026-10-19: "灵动岛的感觉"). While the backup is matched on the target, and
/// again while the playlist is written, the app holds the data-transfer
/// continuous task (shared with downloads), so the restore goes on with Yoin
/// behind other apps; the task's live-view notification says what it is doing,
/// how far it has come ("12 / 45" in the status-bar capsule) and over which
/// backup. Matching done, it goes: the review waits in the app. Written, a
/// plain notification says what came of it and can be swiped away. A new
/// restore takes the last one's result away. Nothing shows without the
/// notification permission, which is asked for as a restore starts.
///
/// HarmonyOS can't draw Android's per-song coloured segments: a system live
/// view has one progress bar (NotificationProgress), and Live View Kit's
/// richer layouts are only for scenarios Huawei grants. The bar fills with the
/// songs matched so far.
library;

import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/backup/playlist_backup_models.dart';
import '../../data/backup/playlist_backup_service.dart';
import '../../offline/offline_providers.dart';
import '../../platform/background/background_transfer.dart';
import '../../shell/shell_state.dart' show appNavigatorKey;
import '../../ui/navigation/yoin_push_route.dart';
import 'playlist_backup_screen.dart';
import 'playlist_backup_strings.dart';

/// Where a restore's progress shows outside the app (Android RestoreProgressSink).
abstract interface class RestoreProgressSink {
  /// A restore starts: the notification permission is asked for, once (Android's POST_NOTIFICATIONS request).
  Future<void> askToNotify();

  /// [done] of [total] songs of [backup] matched on [target].
  void matching(PlaylistBackupRecord backup, RestoreTarget target, int done, int total);

  /// [count] songs being written to a new playlist on [target].
  void writing(PlaylistBackupRecord backup, RestoreTarget target, int count);

  /// Written: what came of it, left in the notifications.
  void finished(PlaylistBackupRecord backup, RestoreTarget target, PlaylistRestoreRecord record);

  /// Nothing to show any more (matched, cancelled, deleted): the progress goes, and the last result with it.
  void clear();

  /// The restore's owner went: the task is let go; a result stays.
  void dispose();
}

/// Shows nothing (tests, previews).
final class NoRestoreProgress implements RestoreProgressSink {
  const NoRestoreProgress();

  @override
  Future<void> askToNotify() async {}

  @override
  void matching(PlaylistBackupRecord backup, RestoreTarget target, int done, int total) {}

  @override
  void writing(PlaylistBackupRecord backup, RestoreTarget target, int count) {}

  @override
  void finished(PlaylistBackupRecord backup, RestoreTarget target, PlaylistRestoreRecord record) {}

  @override
  void clear() {}

  @override
  void dispose() {}
}

/// The restore's progress in the continuous task's live view, through a lease of the shared task.
class RestoreLiveUpdate implements RestoreProgressSink {
  RestoreLiveUpdate({
    required this.host,
    PlaylistBackupStrings Function()? strings,
    this.updateInterval = const Duration(seconds: 1),
    this.retryInterval = const Duration(seconds: 30),
    this.keepAliveInterval = const Duration(minutes: 3),
    DateTime Function()? now,
  }) : _strings = strings ?? _systemStrings,
       _now = now ?? DateTime.now {
    _ended = host.ended.listen(_onEnded);
  }

  /// The restore's lease of the shared continuous task.
  final BackgroundTransferHost host;
  final PlaylistBackupStrings Function() _strings;
  final DateTime Function() _now;

  /// The notification is redrawn at most this often while songs are matched (a new step at once).
  final Duration updateInterval;

  /// A refused hold (the app was behind, an older system) is asked for again after this.
  final Duration retryInterval;

  /// Published again this often while nothing changes (one slow song): the
  /// system ends a data-transfer task whose progress isn't updated for 10 minutes.
  final Duration keepAliveInterval;

  /// The finished restore's notification (Android's NOTIFICATION_ID): one for every backup, a new result replaces it.
  static const resultNoticeId = 0x706c72;

  static const _linkPrefix = 'playlistBackup:';

  /// What tapping a restore's notification opens: its backup's page (Android's PlaylistBackupActivity intent).
  static String linkTo(String backupId) => '$_linkPrefix$backupId';

  /// The backup a tapped notification's [link] opens; null when it isn't a restore's.
  static String? backupOf(String link) {
    if (!link.startsWith(_linkPrefix)) return null;
    final id = link.substring(_linkPrefix.length);
    return id.isEmpty ? null : id;
  }

  late final StreamSubscription<bool> _ended;
  TransferNotice? _wanted;
  TransferNotice? _shown;
  bool _active = false;
  bool _declined = false;
  bool _disposed = false;
  DateTime _lastSent = DateTime.fromMillisecondsSinceEpoch(0);
  DateTime? _retryAfter;
  Timer? _flush;
  Timer? _keepAlive;
  Future<void> _calls = Future.value();

  /// What the live view was last asked to show, for tests.
  @visibleForTesting
  TransferNotice? get shown => _shown;

  /// The pending host calls, for tests.
  @visibleForTesting
  Future<void> get settled => _calls;

  @override
  Future<void> askToNotify() async {
    try {
      await host.notificationsAllowed(ask: true);
    } catch (e) {
      debugPrint('RestoreLiveUpdate: permission not asked ($e)');
    }
  }

  @override
  void matching(PlaylistBackupRecord backup, RestoreTarget target, int done, int total) {
    final strings = _strings();
    final percent = total <= 0 ? 0 : (done * 100 ~/ total).clamp(0, 100);
    _show(
      TransferNotice(
        title: strings.liveMatching(target.displayName),
        text: backup.name,
        percent: percent,
        chip: strings.progress(done, total),
        link: linkTo(backup.id),
      ),
      // The first count and the last one show at once; the rest at most every updateInterval.
      immediate: done == 0 || done >= total,
    );
  }

  @override
  void writing(PlaylistBackupRecord backup, RestoreTarget target, int count) {
    // A new step: whatever the matching was told no longer holds.
    _declined = false;
    _retryAfter = null;
    _show(
      TransferNotice(
        title: _strings().writing(target.displayName),
        text: backup.name,
        percent: null,
        chip: '$count',
        link: linkTo(backup.id),
      ),
      immediate: true,
    );
  }

  @override
  void finished(PlaylistBackupRecord backup, RestoreTarget target, PlaylistRestoreRecord record) {
    if (_disposed) return;
    _stop();
    final strings = _strings();
    final text = strings.restoreAdded(record.added);
    final notice = TransferNotice(
      title: record.failed ? strings.notCreated(target.displayName) : strings.done(target.displayName),
      text: text,
      percent: null,
      subText: backup.name == text ? null : backup.name,
      link: linkTo(backup.id),
    );
    _enqueue(() => host.notify(resultNoticeId, notice));
  }

  @override
  void clear() {
    if (_disposed) return;
    _stop();
    _enqueue(() => host.cancelNotice(resultNoticeId));
  }

  @override
  void dispose() {
    if (_disposed) return;
    _stop();
    _disposed = true;
    unawaited(_ended.cancel());
  }

  void _show(TransferNotice notice, {required bool immediate}) {
    if (_disposed) return;
    if (!_active) {
      _active = true;
      // A new restore: the last one's result goes (Android posts both under one id).
      _enqueue(() => host.cancelNotice(resultNoticeId));
    }
    _wanted = notice;
    if (_declined) return;
    final now = _now();
    final retryAfter = _retryAfter;
    if (retryAfter != null && now.isBefore(retryAfter)) return;
    if (_shown == notice) return;
    if (immediate || _shown == null || now.difference(_lastSent) >= updateInterval) {
      _send(notice);
    } else {
      _flush ??= Timer(updateInterval - now.difference(_lastSent), () {
        _flush = null;
        final wanted = _wanted;
        if (wanted != null && _active && !_declined && _shown != wanted) _send(wanted);
      });
    }
  }

  void _send(TransferNotice notice) {
    _shown = notice;
    _lastSent = _now();
    _flush?.cancel();
    _flush = null;
    _keepAlive?.cancel();
    _keepAlive = Timer(keepAliveInterval, () {
      _keepAlive = null;
      final shown = _shown;
      if (shown != null && _active && !_declined) _send(shown);
    });
    _enqueue(() async {
      if (!_active || _disposed) return;
      final ok = await host.hold(notice);
      if (!ok && _active && identical(_shown, notice)) {
        // Not held: asked for again a little later, with whatever is wanted then.
        _shown = null;
        _retryAfter = _now().add(retryInterval);
      }
    });
  }

  /// The progress goes: the task is let go (downloads keep it if they hold it too).
  void _stop() {
    _flush?.cancel();
    _flush = null;
    _keepAlive?.cancel();
    _keepAlive = null;
    final wasActive = _active || _shown != null;
    _active = false;
    _wanted = null;
    _shown = null;
    _declined = false;
    _retryAfter = null;
    _lastSent = DateTime.fromMillisecondsSinceEpoch(0);
    if (wasActive) _enqueue(host.release);
  }

  void _onEnded(bool byUser) {
    if (!_active) return;
    _shown = null;
    _keepAlive?.cancel();
    _keepAlive = null;
    if (byUser) {
      // The listener removed the notification: not shown again for this step.
      _declined = true;
    } else {
      // The system ended it: asked for again a little later.
      _retryAfter = _now().add(retryInterval);
    }
  }

  void _enqueue(Future<void> Function() call) {
    _calls = _calls.then((_) => call()).catchError((Object e) => debugPrint('RestoreLiveUpdate: $e'));
  }

  static PlaylistBackupStrings _systemStrings() {
    try {
      final chinese = WidgetsBinding.instance.platformDispatcher.locale.languageCode == 'zh';
      return PlaylistBackupStrings.forLanguage(chinese: chinese);
    } catch (_) {
      return const PlaylistBackupStrings.forLanguage(chinese: false);
    }
  }
}

/// Makes each backup page's restore its progress sink: a lease of the continuous task the downloads share.
final restoreProgressFactoryProvider = Provider<RestoreProgressSink Function(String backupId)>((ref) {
  final shared = ref.watch(sharedBackgroundTransferProvider);
  return (backupId) => RestoreLiveUpdate(host: shared.lease('restore $backupId'));
});

/// A restore's notification tapped (also the tap that started the app): its backup's page opens, as Android's
/// contentIntent does. Lives with the app (main.dart).
final restoreNoticeTapsProvider = Provider<void>((ref) {
  final subscription = ref.watch(sharedBackgroundTransferProvider).host.taps.listen((link) {
    final backupId = RestoreLiveUpdate.backupOf(link);
    if (backupId != null) unawaited(_openBackup(backupId));
  });
  ref.onDispose(subscription.cancel);
});

Future<void> _openBackup(String backupId) async {
  // On a cold start the navigator comes up a few frames after the tap arrives.
  for (var i = 0; i < 40 && appNavigatorKey.currentState == null; i++) {
    await Future<void>.delayed(const Duration(milliseconds: 100));
  }
  await appNavigatorKey.currentState?.push(
    YoinPushRoute<void>(builder: (_) => PlaylistBackupScreen(backupId: backupId)),
  );
}
