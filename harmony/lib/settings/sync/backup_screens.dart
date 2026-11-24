/// Export all data and Import from backup (Android ui/settings/sync/
/// BackupScreens.kt BackupPage, CloudSyncOnContent.kt BackupGroup): the Cloud
/// sync page's backup group, and the page each row opens.
///
/// Export: the page builds the zip, then the system's document picker keeps it
/// where the user chooses. Import: the picker first (nothing happens when the
/// user backs out of it), then the page reads and verifies the file, shows the
/// review (where each account goes), imports and tells what it did.
///
/// Back surface: PushPage ([PortabilityPage]'s back button and system back pop
/// it), except while a step is busy (writing the file, reading it, importing):
/// back then only says "Working on it…", as Android refuses it.
library;

import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../../data/portability/backup_file_io.dart';
import '../../data/portability/backup_format.dart';
import '../../data/portability/backup_import.dart';
import '../../data/sync/sync_contracts.dart' show SyncKinds;
import '../../nowplaying/haptics.dart';
import '../../platform/share/document_files.dart';
import '../../shell/app_messages.dart';
import '../../ui/component/expressive_segment_chip.dart';
import '../../ui/component/meta_line.dart';
import '../../ui/component/spring_cross_fade.dart';
import '../../ui/navigation/yoin_push_route.dart';
import '../settings_metrics.dart';
import '../settings_rows.dart';
import 'backup_view_model.dart';
import 'cloud_account_page.dart' show SyncNote, scopeStatGroups;
import 'cloud_sync_strings.dart';
import 'portability_metrics.dart';
import 'portability_strings.dart';
import 'portability_widgets.dart';
import 'transfer_screens.dart' show openTransfer;
import 'transfer_view_model.dart' show TransferArgs;

/// "Download all data" (sync off) / "Download all cloud data" (sync on): the backup page builds and saves it.
Future<void> openBackupExport(BuildContext context) =>
    Navigator.of(context).push(YoinPushRoute<void>(builder: (_) => const BackupScreen.export()));

/// "Import from backup": the system's picker, then the backup page with the picked file.
Future<void> openBackupImport(BuildContext context, WidgetRef ref) async {
  YoinHaptics.click();
  final bytes = await pickBackupFile(ref.read(documentFilesProvider));
  if (bytes == null || !context.mounted) return;
  await Navigator.of(context).push(YoinPushRoute<void>(builder: (_) => BackupScreen.import(bytes)));
}

/// The Cloud sync page's group (BackupGroup): export, import and, when [includeTransfer], Transfer Memories.
class BackupGroup extends ConsumerWidget {
  const BackupGroup({super.key, required this.title, required this.exportTitle, this.includeTransfer = true});

  final String title;
  final String exportTitle;
  final bool includeTransfer;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final s = PortabilityStrings.of(context);
    final chevron = Icon(YoinSymbols.chevronRight, color: Theme.of(context).colorScheme.onSurfaceVariant);
    return SettingsGroup(
      title: title,
      children: [
        SettingsItem(
          key: const ValueKey('cloud_sync_export'),
          icon: YoinSymbols.download,
          title: exportTitle,
          summary: s.exportSummary,
          trailing: chevron,
          onTap: () {
            YoinHaptics.click();
            openBackupExport(context);
          },
        ),
        SettingsItem(
          key: const ValueKey('cloud_sync_import'),
          icon: YoinSymbols.folder,
          title: s.importTitle,
          summary: s.importSummary,
          trailing: chevron,
          onTap: () => openBackupImport(context, ref),
        ),
        if (includeTransfer)
          SettingsItem(
            key: const ValueKey('cloud_sync_transfer'),
            icon: YoinSymbols.forward,
            title: s.transferTitle,
            summary: s.transferSummary,
            trailing: chevron,
            onTap: () {
              YoinHaptics.click();
              openTransfer(context, const TransferArgs());
            },
          ),
      ],
    );
  }
}

class BackupScreen extends ConsumerStatefulWidget {
  /// Builds a backup and asks where to keep it.
  const BackupScreen.export({super.key}) : bytes = null;

  /// Reads [bytes] (a picked file) and reviews the import.
  const BackupScreen.import(Uint8List this.bytes, {super.key});

  final Uint8List? bytes;

  @override
  ConsumerState<BackupScreen> createState() => _BackupScreenState();
}

class _BackupScreenState extends ConsumerState<BackupScreen> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      final vm = ref.read(backupProvider.notifier);
      final bytes = widget.bytes;
      if (bytes != null) {
        vm.read(bytes);
      } else {
        vm.export(language: Localizations.maybeLocaleOf(context)?.toLanguageTag() ?? 'en');
      }
    });
  }

  void _close() => Navigator.of(context).maybePop();

  @override
  Widget build(BuildContext context) {
    final s = PortabilityStrings.of(context);
    final state = ref.watch(backupProvider);
    final vm = ref.read(backupProvider.notifier);
    final barTitle = switch (state) {
      BackupExported() => s.exportDoneBar,
      BackupExporting() || BackupExportFailed() => s.exportRunning,
      BackupIdle() when widget.bytes == null => s.exportRunning,
      _ => s.importTitle,
    };
    final Widget? footer = switch (state) {
      BackupReview() => FlowButtons(
        key: const ValueKey('backup_review_footer'),
        primary: s.importConfirm,
        onPrimary: vm.confirmImport,
        primaryEnabled: state.canImport,
        secondary: s.importCancel,
        onSecondary: _close,
      ),
      BackupExported() || BackupExportFailed() || BackupReadFailed() || BackupImported() || BackupImportFailed() =>
        FlowButtons(key: const ValueKey('backup_done_footer'), primary: s.done, onPrimary: _close),
      _ => null,
    };
    return PortabilityPage(
      barTitle: barTitle,
      ownsBack: () => ref.read(backupProvider.notifier).busy,
      onBack: () => ref.read(appMessageSinkProvider)(s.busy),
      footer: footer == null ? null : SpringCrossFade(childKey: footer.key!, fit: StackFit.loose, child: footer),
      children: [
        SpringCrossFade(childKey: state.runtimeType, fit: StackFit.loose, child: _content(context, s, state, vm)),
      ],
    );
  }

  Widget _content(BuildContext context, PortabilityStrings s, BackupUiState state, BackupNotifier vm) =>
      switch (state) {
        BackupIdle() => const ProgressHero(),
        BackupExporting(:final fromCloud) => Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            FlowHeadline(
              headline: s.exportRunning,
              lead: fromCloud ? s.exportRunningSummary : s.exportRunningSummaryLocal,
            ),
            const ProgressHero(),
          ],
        ),
        BackupExported() => _ExportedContent(state: state),
        BackupExportFailed() => OutcomeHero(
          icon: YoinSymbols.close,
          title: s.exportFailed,
          body: s.exportFailedSummary,
          failed: true,
        ),
        BackupReading() => Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            FlowHeadline(headline: s.importReading),
            const ProgressHero(),
          ],
        ),
        BackupReadFailed(:final reason) => OutcomeHero(
          icon: YoinSymbols.close,
          title: switch (reason) {
            BackupReadFailure.notABackup => s.readNotBackup,
            BackupReadFailure.tooNew => s.readTooNew,
            BackupReadFailure.checksum => s.readChecksum,
            BackupReadFailure.unreadable => s.readFailed,
          },
          failed: true,
        ),
        BackupReview() => _ImportReview(state: state, onChoose: vm.choose),
        BackupImporting(:final review) => Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            FlowHeadline(
              headline: s.importRunning,
              lead: mergeTarget(s, intoCloud: review.intoCloud),
            ),
            const ProgressHero(),
          ],
        ),
        BackupImported() => _ImportedContent(state: state),
        BackupImportFailed() => OutcomeHero(
          icon: YoinSymbols.close,
          title: s.importFailed,
          body: s.importFailedSummary,
          failed: true,
        ),
      };
}

/// "Merge into Google Drive" / "Merge into this device".
String mergeTarget(PortabilityStrings s, {required bool intoCloud}) =>
    s.importMergeInto(intoCloud ? s.googleDrive : s.importThisDeviceInline);

/// Kinds as the person knows them ("Notes and Ratings").
String kindLabels(PortabilityStrings s, Iterable<String> kinds) => s.joined(
  <String>{
    for (final kind in kinds)
      switch (kind) {
        SyncKinds.songNote || SyncKinds.albumNote => s.kindNotes,
        SyncKinds.trackRating || SyncKinds.albumRating => s.kindRatings,
        SyncKinds.albumReview => s.kindReviews,
        SyncKinds.memoryTitle || SyncKinds.albumTitle || SyncKinds.memoryCopy => s.kindTitles,
        _ => s.kindOther,
      },
  }.toList(),
);

class _Groups extends StatelessWidget {
  const _Groups({required this.children});

  final List<Widget> children;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      for (final (i, c) in children.indexed) ...[if (i > 0) const SizedBox(height: PortabilityMetrics.groupGap), c],
    ],
  );
}

class _ExportedContent extends StatelessWidget {
  const _ExportedContent({required this.state});

  final BackupExported state;

  @override
  Widget build(BuildContext context) {
    final s = PortabilityStrings.of(context);
    final theme = Theme.of(context);
    final manifest = state.summary.manifest;
    int sum(Map<String, int> m) => m.values.fold(0, (a, b) => a + b);
    final items = manifest.accounts.fold(0, (a, account) => a + sum(account.counts)) + sum(manifest.globalCounts);
    final dirs = <String>{for (final a in manifest.accounts) ?a.readableDir};
    return _Groups(
      children: [
        OutcomeHero(
          icon: YoinSymbols.downloadDone,
          title: s.exportSaved,
          body: state.fileName,
          extra: Center(
            child: MetaFlow(
              style: theme.textTheme.bodyLarge,
              groups: [
                sizeStat(s, state.bytes),
                MetaStat('${manifest.accounts.length}', s.unitAccounts(manifest.accounts.length)),
                MetaStat('$items', s.unitItems(items)),
              ],
            ),
          ),
        ),
        SettingsGroup(
          title: s.exportContents,
          children: [
            _TreeRow(name: BackupFormat.manifestPath, note: s.exportManifest),
            _TreeRow(name: BackupFormat.snapshotPath, note: s.exportData),
            Padding(
              padding: const EdgeInsets.only(bottom: PortabilityMetrics.treeBottom),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  _TreeRow(name: BackupFormat.readableDir, note: s.exportReadable, folder: true),
                  // readableDir is the zip path ("readable/<account>/"): the folder under readable/.
                  for (final dir in dirs)
                    _TreeRow(
                      name:
                          '${_trimEnd(dir.substring(dir.startsWith(BackupFormat.readableDir) ? BackupFormat.readableDir.length : 0))}/',
                      depth: 1,
                      folder: true,
                    ),
                ],
              ),
            ),
          ],
        ),
        SettingsGroup(
          title: s.exportAbout,
          children: [
            SettingsItem(icon: YoinSymbols.checkCircle, title: s.exportLossless, summary: s.exportLosslessSummary),
            SettingsItem(icon: YoinSymbols.article, title: s.exportOffline, summary: s.exportOfflineSummary),
            SettingsItem(icon: YoinSymbols.lock, title: s.exportPrivate, summary: s.exportPrivateSummary),
          ],
        ),
        if (state.summary.readFailures.isNotEmpty)
          SyncNote(icon: YoinSymbols.info, text: s.exportReadFailures(kindLabels(s, state.summary.readFailures))),
      ],
    );
  }

  static String _trimEnd(String path) => path.endsWith('/') ? _trimEnd(path.substring(0, path.length - 1)) : path;
}

/// One entry of the zip: a file or folder name in monospace, what it holds under it.
class _TreeRow extends StatelessWidget {
  const _TreeRow({required this.name, this.note, this.depth = 0, this.folder = false});

  final String name;
  final String? note;
  final int depth;
  final bool folder;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return Padding(
      padding: EdgeInsetsDirectional.only(
        start: SettingsMetrics.rowPaddingHorizontal + PortabilityMetrics.treeIndent * depth,
        end: SettingsMetrics.rowPaddingHorizontal,
        top: PortabilityMetrics.treeVertical,
        bottom: PortabilityMetrics.treeVertical,
      ),
      child: Row(
        children: [
          Icon(
            folder ? YoinSymbols.folder : YoinSymbols.article,
            size: PortabilityMetrics.treeIcon,
            color: colors.onSurfaceVariant,
          ),
          const SizedBox(width: SettingsMetrics.rowIconGap),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  name,
                  style: theme.textTheme.bodyLarge!.copyWith(color: colors.onSurface, fontFamily: 'monospace'),
                ),
                if (note != null)
                  Text(note!, style: theme.textTheme.bodySmall!.copyWith(color: colors.onSurfaceVariant)),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// The review: what the file is, where each account goes, what else comes along, and how it merges.
class _ImportReview extends StatelessWidget {
  const _ImportReview({required this.state, required this.onChoose});

  final BackupReview state;
  final void Function(String scope, ImportTarget target) onChoose;

  @override
  Widget build(BuildContext context) {
    final s = PortabilityStrings.of(context);
    final theme = Theme.of(context);
    final manifest = state.plan.backup.manifest;
    final material = MaterialLocalizations.of(context);
    final exported = DateTime.fromMillisecondsSinceEpoch(manifest.exportedAt);
    final global = state.plan.globalCounts;
    int count(String kind) => global[kind] ?? 0;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        FlowHeadline(headline: s.importReview),
        _Groups(
          children: [
            SettingsGroup(
              title: s.importInfo,
              children: [
                SettingsItem(
                  icon: YoinSymbols.history,
                  title:
                      '${material.formatShortDate(exported)} ${material.formatTimeOfDay(TimeOfDay.fromDateTime(exported))}',
                  summary: s.importExported,
                ),
                SettingsItem(
                  icon: YoinSymbols.cloud,
                  title: switch (manifest.source.location) {
                    BackupFormat.locationGoogleDrive => s.googleDrive,
                    // An iPhone / iPad backup made while syncing with iCloud.
                    BackupFormat.locationICloud => s.icloud,
                    _ => s.importThisDevice,
                  },
                  summaryContent: manifest.source.deviceName.trim().isEmpty
                      ? null
                      : MetaLine(groups: [MetaMuted(manifest.source.deviceName)], style: theme.textTheme.bodyMedium),
                ),
                SettingsItem(icon: YoinSymbols.checkCircle, title: s.importVerified, summary: s.importVerifiedSummary),
              ],
            ),
            SettingsGroup(
              title: mergeTarget(s, intoCloud: state.intoCloud),
              children: [
                for (final account in state.plan.accounts)
                  ImportAccountRow(
                    key: ValueKey('import_${account.account.scope}'),
                    plan: account,
                    choice: state.choiceOf(account),
                    onChoose: (target) => onChoose(account.account.scope, target),
                  ),
                if (global.values.fold(0, (a, b) => a + b) > 0)
                  SettingsItem(
                    icon: YoinSymbols.library,
                    title: s.importGlobal,
                    summaryContent: MetaFlow(
                      style: theme.textTheme.bodyMedium,
                      groups: [
                        MetaStat('${count(SyncKinds.playlistBackup)}', s.unitBackups(count(SyncKinds.playlistBackup))),
                        MetaStat(
                          '${count(SyncKinds.lyricsTranslation)}',
                          s.unitTranslations(count(SyncKinds.lyricsTranslation)),
                        ),
                        MetaStat('${count(SyncKinds.songAbout)}', s.unitAbout(count(SyncKinds.songAbout))),
                      ],
                    ),
                  ),
              ],
            ),
            SettingsGroup(
              title: s.importRules,
              children: [
                SettingsItem(icon: YoinSymbols.note, title: s.importRuleAdd, summary: s.importRuleAddSummary),
                SettingsItem(icon: YoinSymbols.star, title: s.importRuleRating, summary: s.importRuleRatingSummary),
                SettingsItem(icon: YoinSymbols.reviews, title: s.importRuleReview, summary: s.importRuleReviewSummary),
              ],
            ),
          ],
        ),
      ],
    );
  }
}

/// One account of the backup (ImportAccountRow): its service and counts, and where it goes — asked as
/// chips: a profile of the same service here, "Keep in the cloud only" (when possible), "Skip". An account
/// that is the same one here offers only it and Skip; a removed-from-the-cloud one says so under the chips.
class ImportAccountRow extends StatelessWidget {
  const ImportAccountRow({super.key, required this.plan, required this.choice, required this.onChoose});

  final ImportAccountPlan plan;
  final ImportTarget choice;
  final ValueChanged<ImportTarget> onChoose;

  @override
  Widget build(BuildContext context) {
    final s = PortabilityStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final account = plan.account;
    final line = flowAccountLine(s, account.provider, account.displayName, account.hint);
    final counts = scopeStatGroups(CloudSyncStrings.of(context), countsOfKinds(plan.counts));
    final muted = theme.textTheme.bodyMedium!.copyWith(
      color: colors.onSurfaceVariant.withValues(alpha: PortabilityMetrics.mutedAlpha),
    );
    final note = plan.sameAccount && choice is! ImportSkip
        ? s.importSameAccount
        : choice is ImportCloudOnly
        ? s.importCloudOnlyNote
        : s.importChoose;
    final options = importTargetOptions(plan);
    String label(ImportTarget option) => switch (option) {
      ImportIntoProfile(:final localProfileId) => s.importIntoProfile(
        plan.options.where((o) => o.localProfileId == localProfileId).firstOrNull?.displayName ?? '',
      ),
      ImportCloudOnly() => s.importKeepCloud,
      ImportSkip() => s.importSkip,
    };
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SettingsItem(
          leading: AccountGlyph(provider: account.provider),
          title: account.displayName,
          summaryContent: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (line.isNotEmpty) MetaLine(groups: line, style: theme.textTheme.bodyMedium),
              if (counts.isEmpty)
                Text(s.noData, style: muted)
              else
                MetaFlow(groups: counts, style: theme.textTheme.bodyMedium),
              SpringCrossFade(
                childKey: note,
                fit: StackFit.loose,
                child: Text(note, style: muted),
              ),
            ],
          ),
        ),
        Padding(
          padding: const EdgeInsetsDirectional.only(
            start: SettingsMetrics.rowTextInset,
            end: SettingsMetrics.rowPaddingHorizontal,
            bottom: SettingsMetrics.rowPaddingHorizontal,
          ),
          child: Wrap(
            spacing: PortabilityMetrics.chipGap,
            runSpacing: PortabilityMetrics.chipGap,
            children: [
              for (final option in options)
                ExpressiveSegmentChip(
                  key: ValueKey('import_${account.scope}_${label(option)}'),
                  label: label(option),
                  selected: option == choice,
                  onTap: () {
                    YoinHaptics.tick();
                    onChoose(option);
                  },
                ),
            ],
          ),
        ),
        if (plan.wasPurged)
          Padding(
            padding: const EdgeInsetsDirectional.only(
              start: SettingsMetrics.rowTextInset,
              end: SettingsMetrics.rowPaddingHorizontal,
              bottom: SettingsMetrics.rowPaddingHorizontal,
            ),
            child: Text(
              s.importPurgedNote,
              style: theme.textTheme.bodyMedium!.copyWith(color: colors.onSurfaceVariant),
            ),
          ),
      ],
    );
  }
}

class _ImportedContent extends StatelessWidget {
  const _ImportedContent({required this.state});

  final BackupImported state;

  @override
  Widget build(BuildContext context) {
    final s = PortabilityStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final result = state.result;
    final all = result.byScope.values.fold(result.global, (a, c) => a + c);
    final written = all.written + all.keptBoth;
    final skipped = all.unchanged + all.keptLocal + all.tombstones + all.skipped;
    final failed = all.failedKinds;
    final refused = [
      for (final scope in result.cloudOnlyRefused)
        state.review.plan.accounts.where((a) => a.account.scope == scope).firstOrNull?.account.displayName ?? scope,
    ];
    return _Groups(
      children: [
        OutcomeHero(
          icon: YoinSymbols.check,
          title: s.importDone,
          body: state.review.intoCloud ? s.importDoneSummary(s.googleDrive) : s.importDoneSummaryLocal,
          extra: CountTiles(
            tiles: [
              CountTile(
                count: written,
                label: s.importWritten,
                container: colors.primaryContainer,
                content: colors.onPrimaryContainer,
              ),
              CountTile(
                count: skipped,
                label: s.importSkipped,
                container: colors.surfaceContainerHigh,
                content: colors.onSurface,
              ),
              if (result.cloudOnlyMerged > 0)
                CountTile(
                  count: result.cloudOnlyMerged,
                  label: s.importCloud,
                  container: colors.surfaceContainerHigh,
                  content: colors.onSurface,
                ),
            ],
          ),
        ),
        if (failed.isNotEmpty || refused.isNotEmpty)
          SettingsSegments(
            children: [
              if (failed.isNotEmpty)
                SettingsItem(icon: YoinSymbols.error, title: s.importFailedKinds(kindLabels(s, failed))),
              if (refused.isNotEmpty) SettingsItem(icon: YoinSymbols.info, title: s.importRefused(s.joined(refused))),
            ],
          ),
      ],
    );
  }
}
