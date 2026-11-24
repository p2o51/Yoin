/// Export all data and Import from backup (Android ui/settings/sync/
/// BackupViewModel.kt):
/// export → [BackupExporting] → [BackupExported] | [BackupExportFailed];
/// import → [BackupReading] → [BackupReview] (where each account goes) →
/// [BackupImporting] → [BackupImported] | [BackupImportFailed].
///
/// HarmonyOS builds the zip first and then asks where to keep it (the system's
/// document picker, `saveBackupFile`); Android asks first. A file picked for
/// import is read here: `pickBackupFile` hands over its bytes (no name).
/// Works with Cloud sync off: this device's accounts and the file are all it needs.
library;

import 'dart:async';
import 'dart:math' as math;
import 'dart:typed_data';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/portability/backup_file_io.dart';
import '../../data/portability/backup_files.dart';
import '../../data/portability/backup_import.dart';
import '../../data/portability/memories_portability.dart';
import '../../data/portability/portability_providers.dart';
import '../../data/sync/cloud_data_views.dart' show CloudScopeCounts;
import '../../data/sync/sync_contracts.dart' show SyncKinds;
import '../../offline/offline_providers.dart' show appFilesHostProvider;
import '../../platform/share/document_files.dart';
import 'cloud_sync_view_model.dart' show cloudSyncControllerProvider;

/// Why a backup file couldn't be read (Android BackupReadFailure).
enum BackupReadFailure { notABackup, tooNew, checksum, unreadable }

BackupReadFailure backupReadFailureOf(Object error) => switch (error) {
  BackupReadException(kind: BackupErrorKind.notABackup) => BackupReadFailure.notABackup,
  BackupReadException(kind: BackupErrorKind.tooNew) => BackupReadFailure.tooNew,
  BackupReadException(kind: BackupErrorKind.checksumMismatch) => BackupReadFailure.checksum,
  _ => BackupReadFailure.unreadable,
};

sealed class BackupUiState {
  const BackupUiState();
}

final class BackupIdle extends BackupUiState {
  const BackupIdle();
}

/// [fromCloud]: Cloud sync is on (the copy is the cloud's), else this device's data.
final class BackupExporting extends BackupUiState {
  const BackupExporting({this.fromCloud = false});
  final bool fromCloud;
}

final class BackupExported extends BackupUiState {
  const BackupExported(this.fileName, this.summary, this.bytes);
  final String fileName;
  final ExportSummary summary;
  final int bytes;
}

final class BackupExportFailed extends BackupUiState {
  const BackupExportFailed();
}

final class BackupReading extends BackupUiState {
  const BackupReading();
}

final class BackupReadFailed extends BackupUiState {
  const BackupReadFailed(this.reason);
  final BackupReadFailure reason;
}

/// [choices]: scope → where it goes, for every account of [plan] (defaults filled in).
final class BackupReview extends BackupUiState {
  const BackupReview({required this.plan, required this.choices, required this.intoCloud});

  final ImportPlan plan;
  final Map<String, ImportTarget> choices;

  /// Cloud sync is on here: "Merge into Google Drive", else "into this device".
  final bool intoCloud;

  ImportTarget choiceOf(ImportAccountPlan account) => choices[account.account.scope] ?? account.target;

  /// Something would be imported: an account not skipped, or global data.
  bool get canImport =>
      choices.values.any((c) => c is! ImportSkip) || plan.globalCounts.values.fold(0, (a, b) => a + b) > 0;

  BackupReview withChoice(String scope, ImportTarget target) =>
      BackupReview(plan: plan, choices: {...choices, scope: target}, intoCloud: intoCloud);
}

final class BackupImporting extends BackupUiState {
  const BackupImporting(this.review);
  final BackupReview review;
}

final class BackupImported extends BackupUiState {
  const BackupImported(this.review, this.result);
  final BackupReview review;
  final ImportResult result;
}

final class BackupImportFailed extends BackupUiState {
  const BackupImportFailed(this.review);
  final BackupReview review;
}

/// The choices an account offers (Android targetOptions): its local profiles, cloud-only (when possible),
/// skip; the same account here: it or skip. A backup's Spotify account has no profile here to go into,
/// so it offers cloud-only (when possible) and skip.
List<ImportTarget> importTargetOptions(ImportAccountPlan plan) {
  if (plan.sameAccount) return [plan.target, ImportTarget.skip];
  return [
    for (final o in plan.options) ImportIntoProfile(o.localProfileId),
    if (plan.cloudOnlyPossible && !plan.wasPurged) ImportTarget.cloudOnly,
    ImportTarget.skip,
  ];
}

class BackupNotifier extends Notifier<BackupUiState> {
  @override
  BackupUiState build() => const BackupIdle();

  MemoriesPortability get _portability => ref.read(memoriesPortabilityProvider);

  bool get _syncOn => ref.read(cloudSyncControllerProvider).state.value.enabled;

  /// Busy steps refuse Back: closing mid-write would leave a half file or a half import.
  bool get busy => switch (state) {
    BackupExporting() || BackupReading() || BackupImporting() => true,
    _ => false,
  };

  void _set(BackupUiState next) {
    if (ref.mounted) state = next;
  }

  /// Builds the backup ([language]: the readable files' labels, "zh…" = Chinese), then asks where to keep it.
  /// False from the picker (cancelled, or the copy failed) reads as "nothing complete was saved".
  Future<void> export({required String language}) async {
    if (state is! BackupIdle) return;
    state = BackupExporting(fromCloud: _syncOn);
    final files = ref.read(documentFilesProvider);
    final cache = ref.read(appFilesHostProvider).cacheDirectory;
    BackupUiState next;
    try {
      final export = await _portability.export(language: language);
      final saved = await saveBackupFile(export, cache: cache, files: files);
      next = saved ? BackupExported(export.fileName, export.summary, export.bytes.length) : const BackupExportFailed();
    } catch (_) {
      next = const BackupExportFailed();
    }
    _set(next);
  }

  /// Reads and checks [bytes] (a picked file), then plans where each account goes.
  Future<void> read(Uint8List bytes) async {
    if (state is! BackupIdle) return;
    state = const BackupReading();
    BackupUiState next;
    try {
      final portability = _portability;
      final plan = await portability.planImport(portability.read(bytes));
      next = BackupReview(
        plan: plan,
        choices: {for (final a in plan.accounts) a.account.scope: a.target},
        intoCloud: _syncOn,
      );
    } catch (error) {
      next = BackupReadFailed(backupReadFailureOf(error));
    }
    _set(next);
  }

  /// Where the account [scope] goes; ignored when [target] isn't one of its options.
  void choose(String scope, ImportTarget target) {
    final review = state;
    if (review is! BackupReview) return;
    final account = review.plan.accounts.where((a) => a.account.scope == scope).firstOrNull;
    if (account == null) return;
    if (!importTargetOptions(account).contains(target) && target != account.target) return;
    state = review.withChoice(scope, target);
  }

  /// Merge and import. Global data (settings, translations, About, playlist backups) always goes along, as
  /// on Android. With Cloud sync on, a sync follows so other devices get it.
  Future<void> confirmImport() async {
    final review = state;
    if (review is! BackupReview || !review.canImport) return;
    state = BackupImporting(review);
    BackupUiState next;
    try {
      final result = await _portability.applyImport(review.plan, review.choices, importGlobal: true);
      next = BackupImported(review, result);
      if (result.changed && _syncOn) ref.read(cloudSyncControllerProvider).syncNow();
    } catch (_) {
      next = BackupImportFailed(review);
    }
    _set(next);
  }
}

/// One backup page's flow; it goes with the page.
final backupProvider = NotifierProvider.autoDispose<BackupNotifier, BackupUiState>(BackupNotifier.new);

/// A backup account's per-kind counts as the page counts them: notes, albums (rated or reviewed), song
/// ratings and Memories titles (Android accountCountGroups).
CloudScopeCounts countsOfKinds(Map<String, int> counts) {
  int c(String kind) => counts[kind] ?? 0;
  return CloudScopeCounts(
    notes: c(SyncKinds.songNote) + c(SyncKinds.albumNote),
    albums: math.max(c(SyncKinds.albumRating), c(SyncKinds.albumReview)),
    songRatings: c(SyncKinds.trackRating),
    memoryTitles: c(SyncKinds.memoryTitle) + c(SyncKinds.albumTitle),
  );
}
