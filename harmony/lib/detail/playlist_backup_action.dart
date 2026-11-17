/// The playlist page's "Cloud backup" (Android PlaylistDetailViewModel.backUp
/// and PlaylistDetailActivity's snackbar): reads the playlist whole and saves
/// a backup (or backs it up again), then says how much of it came along; the
/// snackbar's View opens the backup.
library;

import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/backup/playlist_backup_models.dart';
import '../data/backup/playlist_backup_providers.dart';
import '../data/model/model.dart';
import '../playback/playback_controller.dart';
import '../settings/backup/playlist_backup_screen.dart';
import '../settings/backup/playlist_backup_strings.dart';
import '../shell/shell_state.dart' show appNavigatorKey;
import '../ui/navigation/yoin_push_route.dart';

/// Playlists being backed up now: a second tap waits for the first (Android's backingUp).
final _backingUp = <MediaId>{};

/// The snackbar after a backup: how many songs it holds, of how many entries when some couldn't come along.
String backupMessage(PlaylistBackupStrings strings, PlaylistBackupRecord backup) {
  final total = backup.trackCount + backup.unexportedCount;
  return backup.unexportedCount == 0
      ? strings.detailDone(backup.trackCount)
      : strings.detailPartial(backup.trackCount, total);
}

Future<void> backUpPlaylist(BuildContext context, WidgetRef ref, MediaId playlistId) async {
  if (!_backingUp.add(playlistId)) return;
  final strings = PlaylistBackupStrings.of(context);
  final messages = ref.read(playbackControllerProvider.notifier);
  PlaylistBackupRecord? backup;
  try {
    backup = await ref.read(playlistBackupServiceProvider).backUp(playlistId);
  } catch (_) {
    backup = null;
  } finally {
    _backingUp.remove(playlistId);
  }
  if (backup == null) {
    messages.showMessage(strings.detailFailed);
    return;
  }
  final id = backup.id;
  messages.showMessage(
    backupMessage(strings, backup),
    actionLabel: strings.detailView,
    // Long enough to reach View (a short one went before the thumb got there on Android's tablet).
    long: true,
    onAction: () =>
        appNavigatorKey.currentState?.push(YoinPushRoute<void>(builder: (_) => PlaylistBackupScreen(backupId: id))),
  );
}
