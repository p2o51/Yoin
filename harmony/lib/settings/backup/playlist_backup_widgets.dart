/// Small pieces the backup pages and the restore sheet share.
library;

import 'package:flutter/material.dart';

import '../../data/backup/playlist_backup_models.dart';
import '../../home/widgets/seam.dart' show SeamFade;
import '../../ui/component/meta_line.dart' show formatTrackDuration;
import '../account_identity.dart';
import '../settings_colors.dart';
import '../settings_metrics.dart';
import 'playlist_backup_metrics.dart';

/// The service a backup or restore target belongs to: its name, Yoin Symbol and hue, as Settings shows accounts.
ServiceIdentity serviceOf(String provider) => ServiceIdentity.forProvider(provider);

SettingsTone serviceTone(BuildContext context, String provider) =>
    serviceOf(provider).hue.tone(Theme.of(context).brightness);

/// A row's leading service mark on Pixel's pastel circle in the service's hue (SettingsRowIcon with a tone).
class ServiceRowIcon extends StatelessWidget {
  const ServiceRowIcon({super.key, required this.provider});

  final String provider;

  @override
  Widget build(BuildContext context) {
    final tone = serviceTone(context, provider);
    return SizedBox.square(
      dimension: SettingsMetrics.rowIconBox,
      child: DecoratedBox(
        decoration: BoxDecoration(color: tone.iconContainer, shape: BoxShape.circle),
        child: Icon(serviceOf(provider).glyph, size: SettingsMetrics.rowIcon, color: tone.iconContent),
      ),
    );
  }
}

/// A group's label with a count at its end (SettingsGroupLabel trailingLabel).
class CountedGroupLabel extends StatelessWidget {
  const CountedGroupLabel({super.key, required this.title, this.count});

  final String title;
  final int? count;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final style = theme.textTheme.labelLarge!;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: SettingsMetrics.labelInset),
      child: Row(
        children: [
          Expanded(
            child: Semantics(
              header: true,
              child: SeamFade(
                fontSize: style.fontSize ?? 14,
                child: Text(title, style: style.copyWith(color: theme.colorScheme.primary)),
              ),
            ),
          ),
          if (count != null)
            SeamFade(
              fontSize: style.fontSize ?? 14,
              child: Text('$count', style: style.copyWith(color: theme.colorScheme.onSurfaceVariant)),
            ),
        ],
      ),
    );
  }
}

/// A backed-up song: its number, title over its artists, its length.
class BackupTrackRow extends StatelessWidget {
  const BackupTrackRow({super.key, required this.index, required this.track});

  final int index;
  final BackupTrack track;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final label = theme.textTheme.labelLarge!.copyWith(color: colors.onSurfaceVariant);
    return ConstrainedBox(
      constraints: const BoxConstraints(minHeight: PlaylistBackupMetrics.songRowHeight),
      child: Padding(
        padding: const EdgeInsets.all(PlaylistBackupMetrics.songRowPadding),
        child: Row(
          children: [
            ConstrainedBox(
              constraints: const BoxConstraints(minWidth: PlaylistBackupMetrics.songNumberWidth),
              child: Text('${index + 1}', style: label),
            ),
            const SizedBox(width: PlaylistBackupMetrics.songRowGap),
            Expanded(
              child: SongLines(title: track.title, artist: track.artists.join(', ')),
            ),
            if (track.durationSec case final seconds?) ...[
              const SizedBox(width: PlaylistBackupMetrics.songRowGap),
              Text(formatTrackDuration(seconds), style: label),
            ],
          ],
        ),
      ),
    );
  }
}

/// A song's title over its artist (bodyLarge / bodyMedium, one line each); [dimmed] greys the title.
class SongLines extends StatelessWidget {
  const SongLines({super.key, required this.title, required this.artist, this.dimmed = false});

  final String title;
  final String artist;
  final bool dimmed;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          title,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: theme.textTheme.bodyLarge!.copyWith(color: dimmed ? colors.onSurfaceVariant : colors.onSurface),
        ),
        if (artist.isNotEmpty)
          Text(
            artist,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: theme.textTheme.bodyMedium!.copyWith(color: colors.onSurfaceVariant),
          ),
      ],
    );
  }
}
