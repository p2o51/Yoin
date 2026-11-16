/// One playlist backup (Android PlaylistBackupScreen; docs/playlist-transfer.md
/// §7): where it came from and when, Restore and Back up now, its versions,
/// its restore history, what couldn't be backed up and why, and the songs it
/// holds. A PushPage with Settings' bar and back button; on a wide window it
/// opens over the backups list in the right pane, and back returns there.
/// Delete is in the bar's overflow menu.
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../../data/backup/playlist_backup_models.dart';
import '../../detail/detail_page.dart' show DetailBackButton;
import '../../home/widgets/seam.dart' show SeamFade;
import '../../nowplaying/haptics.dart';
import '../../shell/bar_suppression.dart';
import '../../ui/component/spring_cross_fade.dart';
import '../../ui/component/yoin_dialog.dart';
import '../../ui/component/yoin_dropdown_menu.dart';
import '../../ui/component/yoin_loading_indicator.dart';
import '../material_shapes.dart';
import '../settings_colors.dart';
import '../settings_components.dart';
import '../settings_metrics.dart';
import '../settings_pane.dart';
import '../settings_rows.dart';
import 'playlist_backup_metrics.dart';
import 'playlist_backup_strings.dart';
import 'playlist_backup_view_model.dart';
import 'playlist_backup_widgets.dart';
import 'playlist_restore_sheet.dart';

class PlaylistBackupScreen extends ConsumerStatefulWidget {
  const PlaylistBackupScreen({super.key, required this.backupId, this.inPane = false, this.now});

  final String backupId;

  /// In the wide window's right pane, over the backups list (back returns there).
  final bool inPane;

  /// Fixed time (tests).
  final int? now;

  @override
  ConsumerState<PlaylistBackupScreen> createState() => _PlaylistBackupScreenState();
}

class _PlaylistBackupScreenState extends ConsumerState<PlaylistBackupScreen> {
  final _scroll = ScrollController();

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  void _leave() {
    final openInPane = SettingsPaneScope.maybeOf(context);
    if (widget.inPane && openInPane != null) {
      openInPane(const PlaylistBackupsPane());
    } else {
      Navigator.of(context).maybePop();
    }
  }

  Future<void> _confirmDelete() async {
    final strings = PlaylistBackupStrings.of(context);
    final confirmed = await showYoinDialog<bool>(
      context: context,
      builder: (dialog) => AlertDialog(
        title: Text(strings.deleteTitle),
        content: Text(strings.deleteBody),
        actions: [
          TextButton(onPressed: () => Navigator.of(dialog).pop(false), child: Text(strings.cancel)),
          TextButton(
            onPressed: () {
              YoinHaptics.reject();
              Navigator.of(dialog).pop(true);
            },
            style: TextButton.styleFrom(foregroundColor: Theme.of(dialog).colorScheme.error),
            child: Text(strings.delete),
          ),
        ],
      ),
    );
    if (confirmed == true) await ref.read(playlistBackupPageProvider(widget.backupId).notifier).delete();
  }

  Future<void> _restore([int? versionSince]) async {
    final notifier = ref.read(playlistBackupPageProvider(widget.backupId).notifier);
    notifier.openRestore(versionSince);
    await showPlaylistRestoreSheet(context, backupId: widget.backupId);
    if (mounted) notifier.dismissSheet();
  }

  @override
  Widget build(BuildContext context) {
    final provider = playlistBackupPageProvider(widget.backupId);
    ref.listen(provider.select((s) => s.deleted), (_, deleted) {
      if (deleted) _leave();
    });
    final page = ref.watch(provider);
    final strings = PlaylistBackupStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final backup = switch (page.backup) {
      PlaylistBackupContentState(:final backup) => backup,
      _ => null,
    };
    final title = backup?.name ?? strings.title;
    final now = widget.now ?? DateTime.now().millisecondsSinceEpoch;
    final overflow = backup == null ? null : _Overflow(onDelete: _confirmDelete);
    final body = SpringCrossFade(
      childKey: page.backup.runtimeType,
      child: switch (page.backup) {
        PlaylistBackupLoading() => const Center(child: YoinLoadingIndicator()),
        PlaylistBackupMissing() => const SizedBox.expand(),
        PlaylistBackupContentState(:final backup) => _BackupList(
          controller: _scroll,
          backup: backup,
          now: now,
          backingUp: page.backingUp,
          paneLead: widget.inPane
              ? Row(
                  children: [
                    Padding(
                      padding: const EdgeInsetsDirectional.only(
                        start: SettingsMetrics.backStart - SettingsMetrics.pageHorizontal,
                      ),
                      child: DetailBackButton(container: colors.surfaceContainerHighest, content: colors.onSurface),
                    ),
                    const Spacer(),
                    ?overflow,
                  ],
                )
              : null,
          onRestore: () => _restore(),
          onRestoreVersion: _restore,
          onBackUpNow: () => ref.read(provider.notifier).backUpAgain(),
        ),
      },
    );
    if (widget.inPane) return SafeArea(top: false, bottom: false, child: body);
    return ChromeBarSuppression(
      child: Scaffold(
        backgroundColor: settingsPageColor(colors),
        appBar: SettingsBar(
          title: title,
          controller: _scroll,
          titleTop: SettingsBar.headlineOffset,
          titleHeight: SettingsBar.lineHeight(context, Theme.of(context).textTheme.displaySmall!),
          actions: [?overflow],
        ),
        body: SafeArea(
          top: false,
          bottom: false,
          child: SettingsSeam(controller: _scroll, child: body),
        ),
      ),
    );
  }
}

class _Overflow extends StatelessWidget {
  const _Overflow({required this.onDelete});

  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) {
    final strings = PlaylistBackupStrings.of(context);
    return Builder(
      builder: (anchor) => IconButton(
        tooltip: strings.cdMore,
        icon: const Icon(YoinSymbols.moreVertical),
        onPressed: () async {
          YoinHaptics.click();
          final choice = await showYoinDropdownMenu<bool>(
            context: anchor,
            anchor: globalRectOf(anchor),
            semanticLabel: strings.cdMore,
            items: [YoinMenuItem(value: true, label: strings.delete, icon: YoinSymbols.delete)],
          );
          if (choice == true) onDelete();
        },
      ),
    );
  }
}

/// The page's scroll: the large title, then every section; songs built as they scroll in.
class _BackupList extends StatelessWidget {
  const _BackupList({
    required this.controller,
    required this.backup,
    required this.now,
    required this.backingUp,
    required this.paneLead,
    required this.onRestore,
    required this.onRestoreVersion,
    required this.onBackUpNow,
  });

  final ScrollController controller;
  final PlaylistBackupRecord backup;
  final int now;
  final bool backingUp;
  final Widget? paneLead;
  final VoidCallback onRestore;
  final ValueChanged<int> onRestoreVersion;
  final VoidCallback onBackUpNow;

  @override
  Widget build(BuildContext context) {
    final strings = PlaylistBackupStrings.of(context);
    const sectionTop = SizedBox(height: PlaylistBackupMetrics.sectionTop);
    final head = <Widget>[
      ?paneLead,
      SettingsHeadline(title: backup.name),
      _StatusCard(backup: backup, now: now),
      _Actions(backup: backup, backingUp: backingUp, onRestore: onRestore, onBackUpNow: onBackUpNow),
      if (backup.versions.isNotEmpty) ...[
        sectionTop,
        CountedGroupLabel(title: strings.sectionVersions, count: backup.versions.length + 1),
        const SizedBox(height: SettingsMetrics.groupLabelGap),
        SettingsSegments(
          children: [
            _VersionRow(
              key: const ValueKey('version-current'),
              since: backup.lastChangedAt,
              songs: backup.content.tracks,
              older: backup.versions.first.content.tracks,
              current: true,
              now: now,
              onRestore: onRestore,
            ),
            for (final (i, version) in backup.versions.indexed)
              _VersionRow(
                key: ValueKey('version-${version.since}'),
                since: version.since,
                songs: version.content.tracks,
                older: i + 1 < backup.versions.length ? backup.versions[i + 1].content.tracks : null,
                current: false,
                now: now,
                onRestore: () => onRestoreVersion(version.since),
              ),
          ],
        ),
      ],
      if (backup.restores.isNotEmpty) ...[
        sectionTop,
        SettingsGroup(
          title: strings.sectionRestores,
          // Newest first, like the backups themselves.
          children: [for (final record in backup.restores.reversed) _RestoreHistoryRow(record: record, now: now)],
        ),
      ],
      if (backup.content.unexported.isNotEmpty) ...[
        sectionTop,
        CountedGroupLabel(title: strings.sectionUnexported, count: backup.unexportedCount),
        const SizedBox(height: PlaylistBackupMetrics.sectionLabelBottom),
        for (final item in backup.content.unexported) _UnexportedRow(item: item),
      ],
      sectionTop,
      CountedGroupLabel(title: strings.sectionSongs, count: backup.trackCount),
      const SizedBox(height: PlaylistBackupMetrics.sectionLabelBottom),
    ];
    final tracks = backup.content.tracks;
    return ListView.builder(
      controller: controller,
      clipBehavior: Clip.hardEdge,
      padding: EdgeInsets.fromLTRB(
        SettingsMetrics.pageHorizontal,
        SettingsMetrics.pageTop,
        SettingsMetrics.pageHorizontal,
        SettingsMetrics.pageBottom + MediaQuery.paddingOf(context).bottom,
      ),
      itemCount: head.length + tracks.length,
      itemBuilder: (context, i) =>
          i < head.length ? head[i] : BackupTrackRow(index: i - head.length, track: tracks[i - head.length]),
    );
  }
}

/// Where the backup stands, on its service's colour: the account, whether it still follows the playlist
/// (or the playlist is gone and it is kept), and its dates as tiles.
class _StatusCard extends StatelessWidget {
  const _StatusCard({required this.backup, required this.now});

  final PlaylistBackupRecord backup;
  final int now;

  @override
  Widget build(BuildContext context) {
    final strings = PlaylistBackupStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final service = serviceOf(backup.sourceProvider);
    final tone = serviceTone(context, backup.sourceProvider);
    final following = backup.following;
    final stateLine = !following
        ? strings.sourceGone
        : backup.sourceProfileId == null
        ? strings.notHere
        : strings.following;
    final created = parseServiceDate(backup.playlistCreatedAt);
    final tiles = <(String, int)>[
      if (created != null) (strings.created, created),
      (strings.since, backup.firstBackedUpAt),
      (strings.last, backup.lastBackedUpAt),
      (strings.changed, backup.lastChangedAt),
    ];
    final label = theme.textTheme.labelLarge!;
    return DecoratedBox(
      decoration: ShapeDecoration(
        color: tone.container,
        shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(PlaylistBackupMetrics.cardRadius)),
      ),
      child: Padding(
        padding: const EdgeInsets.all(PlaylistBackupMetrics.cardPadding),
        child: DefaultTextStyle.merge(
          style: TextStyle(color: tone.onContainer),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Container(
                    width: PlaylistBackupMetrics.cardMark,
                    height: PlaylistBackupMetrics.cardMark,
                    decoration: ShapeDecoration(color: tone.accent, shape: PolygonBorder(cookie9Sided)),
                    child: Icon(service.glyph, color: tone.onAccent, size: PlaylistBackupMetrics.cardGlyph),
                  ),
                  const SizedBox(width: PlaylistBackupMetrics.cardMarkGap),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          backup.sourceProfileLabel,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: theme.textTheme.titleLarge!.copyWith(color: tone.onContainer),
                        ),
                        if (service.name != backup.sourceProfileLabel)
                          Text(
                            service.name,
                            style: theme.textTheme.bodyMedium!.copyWith(
                              color: tone.onContainer.withValues(alpha: PlaylistBackupMetrics.tileLabelAlpha),
                            ),
                          ),
                      ],
                    ),
                  ),
                ],
              ),
              const SizedBox(height: PlaylistBackupMetrics.cardGap),
              Row(
                children: [
                  Container(
                    width: PlaylistBackupMetrics.cardDot,
                    height: PlaylistBackupMetrics.cardDot,
                    decoration: BoxDecoration(color: following ? tone.accent : colors.outline, shape: BoxShape.circle),
                  ),
                  const SizedBox(width: PlaylistBackupMetrics.cardDotGap),
                  Text(stateLine, style: label.copyWith(color: tone.onContainer)),
                  if (!following && backup.sourceGoneAt != null) ...[
                    const SizedBox(width: PlaylistBackupMetrics.cardDotGap),
                    Text(
                      formatBackupTime(context, backup.sourceGoneAt!, now),
                      style: label.copyWith(
                        color: tone.onContainer.withValues(alpha: PlaylistBackupMetrics.goneTimeAlpha),
                      ),
                    ),
                  ],
                ],
              ),
              for (var i = 0; i < tiles.length; i += 2) ...[
                const SizedBox(height: PlaylistBackupMetrics.cardGap),
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(
                      child: _DateTile(label: tiles[i].$1, at: tiles[i].$2, now: now, tone: tone),
                    ),
                    const SizedBox(width: PlaylistBackupMetrics.tileGap),
                    Expanded(
                      child: i + 1 < tiles.length
                          ? _DateTile(label: tiles[i + 1].$1, at: tiles[i + 1].$2, now: now, tone: tone)
                          : const SizedBox.shrink(),
                    ),
                  ],
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

class _DateTile extends StatelessWidget {
  const _DateTile({required this.label, required this.at, required this.now, required this.tone});

  final String label;
  final int at;
  final int now;
  final SettingsTone tone;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return DecoratedBox(
      decoration: ShapeDecoration(
        color: tone.onContainer.withValues(alpha: PlaylistBackupMetrics.tileFillAlpha),
        shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(PlaylistBackupMetrics.tileRadius)),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(
          horizontal: PlaylistBackupMetrics.tileHorizontal,
          vertical: PlaylistBackupMetrics.tileVertical,
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              label,
              style: theme.textTheme.labelMedium!.copyWith(
                color: tone.onContainer.withValues(alpha: PlaylistBackupMetrics.tileLabelAlpha),
              ),
            ),
            Text(
              formatBackupTime(context, at, now),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: theme.textTheme.titleSmall!.copyWith(color: tone.onContainer),
            ),
          ],
        ),
      ),
    );
  }
}

/// Restore (filled, wide) and Back up now (tonal), the latter only where this device follows the playlist.
class _Actions extends StatelessWidget {
  const _Actions({required this.backup, required this.backingUp, required this.onRestore, required this.onBackUpNow});

  final PlaylistBackupRecord backup;
  final bool backingUp;
  final VoidCallback onRestore;
  final VoidCallback onBackUpNow;

  @override
  Widget build(BuildContext context) {
    final strings = PlaylistBackupStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    const shape = StadiumBorder();
    final size = const Size.fromHeight(PlaylistBackupMetrics.actionHeight);
    return Padding(
      padding: const EdgeInsets.only(top: PlaylistBackupMetrics.actionsTop),
      child: Row(
        children: [
          Expanded(
            child: FilledButton.icon(
              onPressed: backup.trackCount > 0
                  ? () {
                      YoinHaptics.confirm();
                      onRestore();
                    }
                  : null,
              style: FilledButton.styleFrom(minimumSize: size, shape: shape, textStyle: text.titleMedium),
              icon: const Icon(YoinSymbols.add),
              label: Text(strings.restore),
            ),
          ),
          // Only the device that follows the playlist can read it again.
          if (backup.following && backup.sourceProfileId != null) ...[
            const SizedBox(width: PlaylistBackupMetrics.actionGap),
            FilledButton.tonal(
              onPressed: backingUp
                  ? null
                  : () {
                      YoinHaptics.click();
                      onBackUpNow();
                    },
              style: FilledButton.styleFrom(
                minimumSize: const Size(0, PlaylistBackupMetrics.actionHeight),
                shape: shape,
              ),
              child: SpringCrossFade(
                childKey: backingUp,
                fit: StackFit.loose,
                child: backingUp
                    ? YoinLoadingIndicator(
                        size: PlaylistBackupMetrics.actionLoading,
                        color: colors.onSecondaryContainer,
                      )
                    : Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          const Icon(YoinSymbols.refresh),
                          const SizedBox(width: PlaylistBackupMetrics.actionIconGap),
                          Text(strings.backUpNow),
                        ],
                      ),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

/// One version: when it began, how many songs, what changed from the one before it (+ added, − removed),
/// and Restore. The current one says so.
class _VersionRow extends StatelessWidget {
  const _VersionRow({
    super.key,
    required this.since,
    required this.songs,
    required this.older,
    required this.current,
    required this.now,
    required this.onRestore,
  });

  final int since;
  final List<BackupTrack> songs;
  final List<BackupTrack>? older;
  final bool current;
  final int now;
  final VoidCallback onRestore;

  @override
  Widget build(BuildContext context) {
    final strings = PlaylistBackupStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final label = theme.textTheme.labelLarge!;
    final diff = older == null ? null : versionDiff(older!, songs);
    return SettingsItem(
      icon: current ? YoinSymbols.cloud : YoinSymbols.undo,
      title: formatBackupTime(context, since, now),
      summaryContent: Wrap(
        spacing: PlaylistBackupMetrics.versionGap,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          Text(
            strings.songs(songs.length),
            style: theme.textTheme.bodyMedium!.copyWith(color: colors.onSurfaceVariant),
          ),
          if (diff != null && diff.$1 > 0) Text('+${diff.$1}', style: label.copyWith(color: colors.primary)),
          if (diff != null && diff.$2 > 0) Text('−${diff.$2}', style: label.copyWith(color: colors.error)),
          if (current)
            DecoratedBox(
              decoration: ShapeDecoration(color: colors.primaryContainer, shape: const StadiumBorder()),
              child: Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: PlaylistBackupMetrics.pillHorizontal,
                  vertical: PlaylistBackupMetrics.pillVertical,
                ),
                child: Text(
                  strings.versionCurrent,
                  style: theme.textTheme.labelMedium!.copyWith(color: colors.onPrimaryContainer),
                ),
              ),
            ),
        ],
      ),
      trailing: FilledButton.tonal(
        onPressed: () {
          YoinHaptics.click();
          onRestore();
        },
        style: FilledButton.styleFrom(
          shape: const StadiumBorder(),
          padding: const EdgeInsets.symmetric(horizontal: PlaylistBackupMetrics.versionButtonHorizontal),
        ),
        child: Text(strings.restore),
      ),
    );
  }
}

class _RestoreHistoryRow extends StatelessWidget {
  const _RestoreHistoryRow({required this.record, required this.now});

  final PlaylistRestoreRecord record;
  final int now;

  @override
  Widget build(BuildContext context) {
    final strings = PlaylistBackupStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final label = theme.textTheme.labelLarge!;
    return SettingsItem(
      leading: ServiceRowIcon(provider: record.targetProvider),
      title: record.targetLabel,
      summaryContent: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            formatBackupTime(context, record.at, now),
            style: theme.textTheme.bodyMedium!.copyWith(color: colors.onSurfaceVariant),
          ),
          if (record.failed)
            Text(strings.restoreFailedRecord, style: theme.textTheme.bodyMedium!.copyWith(color: colors.error))
          else
            // The three counts side by side, each its own label: added, skipped, not found (zero ones left out).
            Wrap(
              spacing: PlaylistBackupMetrics.countsGap,
              children: [
                Text(strings.restoreAdded(record.added), style: label.copyWith(color: colors.primary)),
                if (record.skipped > 0)
                  Text(strings.restoreSkipped(record.skipped), style: label.copyWith(color: colors.onSurfaceVariant)),
                if (record.notFound > 0)
                  Text(
                    strings.restoreNotFoundCount(record.notFound),
                    style: label.copyWith(color: colors.onSurfaceVariant),
                  ),
              ],
            ),
        ],
      ),
    );
  }
}

class _UnexportedRow extends StatelessWidget {
  const _UnexportedRow({required this.item});

  final BackupUnexported item;

  @override
  Widget build(BuildContext context) {
    final strings = PlaylistBackupStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return ConstrainedBox(
      constraints: const BoxConstraints(minHeight: PlaylistBackupMetrics.songRowHeight),
      child: Padding(
        padding: const EdgeInsets.all(PlaylistBackupMetrics.songRowPadding),
        child: Row(
          children: [
            Expanded(
              child: SeamFade(
                fontSize: theme.textTheme.bodyLarge!.fontSize ?? 16,
                child: SongLines(title: item.title ?? strings.untitled, artist: item.artist ?? ''),
              ),
            ),
            const SizedBox(width: PlaylistBackupMetrics.songRowGap),
            Text(strings.reason(item.reasonKind), style: theme.textTheme.labelLarge!.copyWith(color: colors.error)),
          ],
        ),
      ),
    );
  }
}
