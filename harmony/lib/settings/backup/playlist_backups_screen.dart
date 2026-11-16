/// Settings › Playlist cloud backup (Android PlaylistBackupsPage): every
/// backup, newest change first. A row opens the backup's own page: pushed,
/// or over the list in the wide window's right pane.
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/backup/playlist_backup_models.dart';
import '../../data/backup/playlist_backup_providers.dart';
import '../../home/widgets/seam.dart' show SeamFade;
import '../../ui/component/spring_cross_fade.dart';
import '../../ui/component/yoin_loading_indicator.dart';
import '../../ui/navigation/yoin_push_route.dart';
import '../settings_page.dart';
import '../settings_pane.dart';
import '../settings_rows.dart';
import 'playlist_backup_metrics.dart';
import 'playlist_backup_screen.dart';
import 'playlist_backup_strings.dart';
import 'playlist_backup_widgets.dart';

class PlaylistBackupsScreen extends ConsumerWidget {
  const PlaylistBackupsScreen({super.key, this.inPane = false, this.now});

  final bool inPane;

  /// Fixed time (tests).
  final int? now;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = PlaylistBackupStrings.of(context);
    final backups = ref.watch(playlistBackupsProvider);
    return SettingsSubPage(
      title: strings.title,
      inPane: inPane,
      children: [
        PlaylistBackupsList(
          backups: backups.value,
          now: now ?? DateTime.now().millisecondsSinceEpoch,
          onOpen: (backup) => openPlaylistBackup(context, backup.id),
        ),
      ],
    );
  }
}

/// Opens backup [backupId]'s page: in the wide window's right pane when this is one, else pushed.
void openPlaylistBackup(BuildContext context, String backupId) {
  final openInPane = SettingsPaneScope.maybeOf(context);
  if (openInPane != null) {
    openInPane(PlaylistBackupPane(backupId));
  } else {
    Navigator.of(context).push(YoinPushRoute<void>(builder: (_) => PlaylistBackupScreen(backupId: backupId)));
  }
}

/// Loading, "No backups", or the backups as segments (PlaylistBackupsList), cross-fading between them.
class PlaylistBackupsList extends StatelessWidget {
  const PlaylistBackupsList({super.key, required this.backups, required this.now, required this.onOpen});

  /// null while loading.
  final List<PlaylistBackupRecord>? backups;
  final int now;
  final ValueChanged<PlaylistBackupRecord> onOpen;

  @override
  Widget build(BuildContext context) {
    final strings = PlaylistBackupStrings.of(context);
    final theme = Theme.of(context);
    final backups = this.backups;
    final key = backups == null ? 0 : (backups.isEmpty ? 1 : 2);
    return SpringCrossFade(
      childKey: key,
      fit: StackFit.loose,
      child: switch (backups) {
        null => const Center(child: YoinLoadingIndicator()),
        [] => Padding(
          padding: const EdgeInsets.symmetric(
            horizontal: PlaylistBackupMetrics.emptyHorizontal,
            vertical: PlaylistBackupMetrics.emptyVertical,
          ),
          child: SeamFade(
            fontSize: theme.textTheme.bodyLarge!.fontSize ?? 16,
            child: Text(
              strings.empty,
              style: theme.textTheme.bodyLarge!.copyWith(color: theme.colorScheme.onSurfaceVariant),
            ),
          ),
        ),
        _ => SettingsSegments(
          children: [
            for (final backup in backups)
              _BackupRow(key: ValueKey(backup.id), backup: backup, now: now, onTap: () => onOpen(backup)),
          ],
        ),
      },
    );
  }
}

class _BackupRow extends StatelessWidget {
  const _BackupRow({super.key, required this.backup, required this.now, required this.onTap});

  final PlaylistBackupRecord backup;
  final int now;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final strings = PlaylistBackupStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final secondary = theme.textTheme.bodyMedium!.copyWith(color: colors.onSurfaceVariant);
    final tone = serviceTone(context, backup.sourceProvider);
    return SettingsItem(
      leading: ServiceRowIcon(provider: backup.sourceProvider),
      title: backup.name,
      onTap: onTap,
      summaryContent: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(backup.sourceProfileLabel, style: secondary, maxLines: 1, overflow: TextOverflow.ellipsis),
          // Following (a dot in the service's colour) or kept after its playlist went, then the last backup.
          Row(
            children: [
              Container(
                width: PlaylistBackupMetrics.dot,
                height: PlaylistBackupMetrics.dot,
                decoration: BoxDecoration(
                  color: backup.following ? tone.accent : colors.outline,
                  shape: BoxShape.circle,
                ),
              ),
              const SizedBox(width: PlaylistBackupMetrics.dotGap),
              Flexible(
                child: Text(
                  backup.following ? formatBackupTime(context, backup.lastBackedUpAt, now) : strings.sourceGone,
                  style: secondary,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
            ],
          ),
          if (backup.unexportedCount > 0)
            Text(
              strings.unexported(backup.unexportedCount),
              style: secondary.copyWith(color: colors.error),
              maxLines: 1,
            ),
        ],
      ),
      trailing: SeamFade(
        fontSize: theme.textTheme.labelLarge!.fontSize ?? 14,
        child: Text(
          strings.songs(backup.trackCount),
          style: theme.textTheme.labelLarge!.copyWith(color: colors.onSurfaceVariant),
        ),
      ),
    );
  }
}
