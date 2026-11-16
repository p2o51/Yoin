/// One backup's page and its restore (Android PlaylistBackupViewModel). The
/// restore lives here, not in the sheet: matching carries on while the sheet
/// is closed, the review waits for the user to come back to it, and a write
/// once started finishes (and is recorded) even if the page closes. While a
/// restore is under way (matching, reviewing, writing) this state outlives the
/// page too: leaving it and coming back finds the restore where it is, live.
/// Matching and writing show their progress outside the app and keep it
/// running in the background ([RestoreProgressSink], restore_live_update.dart).
library;

import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show KeepAliveLink;

import '../../data/backup/playlist_backup_models.dart';
import '../../data/backup/playlist_backup_providers.dart';
import '../../data/backup/playlist_backup_service.dart';
import '../../data/model/model.dart';
import '../../data/transfer/restore_review.dart';
import 'restore_live_update.dart';

/// The backup itself. [PlaylistBackupMissing]: deleted here or on another device.
sealed class PlaylistBackupUiState {
  const PlaylistBackupUiState();
}

final class PlaylistBackupLoading extends PlaylistBackupUiState {
  const PlaylistBackupLoading();
}

final class PlaylistBackupMissing extends PlaylistBackupUiState {
  const PlaylistBackupMissing();
}

final class PlaylistBackupContentState extends PlaylistBackupUiState {
  const PlaylistBackupContentState(this.backup);
  final PlaylistBackupRecord backup;
}

/// A restore, from choosing where to through what came of it (docs/playlist-transfer.md §7).
sealed class RestoreUiState {
  const RestoreUiState();
}

final class RestoreIdle extends RestoreUiState {
  const RestoreIdle();
}

final class RestoreChoosingTarget extends RestoreUiState {
  const RestoreChoosingTarget();
}

final class RestoreMatching extends RestoreUiState {
  const RestoreMatching(this.target, this.done, this.total);
  final RestoreTarget target;
  final int done;
  final int total;
}

/// Nothing written yet: the user checks the rows to write.
final class RestoreReviewing extends RestoreUiState {
  const RestoreReviewing(this.target, this.rows);
  final RestoreTarget target;
  final List<ReviewRow> rows;
}

final class RestoreWriting extends RestoreUiState {
  const RestoreWriting(this.target, this.review);
  final RestoreTarget target;
  final RestoreReviewing review;
}

final class RestoreDone extends RestoreUiState {
  const RestoreDone(this.target, this.record);
  final RestoreTarget target;
  final PlaylistRestoreRecord record;
}

/// Matching couldn't reach the target.
final class RestoreFailed extends RestoreUiState {
  const RestoreFailed(this.target);
  final RestoreTarget target;
}

/// The review's search for one song on the target: what was asked, and what came back.
@immutable
final class RestoreSearchState {
  const RestoreSearchState({
    required this.position,
    required this.query,
    this.results = const [],
    this.loading = false,
    this.failed = false,
  });

  final int position;
  final String query;
  final List<Track> results;
  final bool loading;

  /// The last search couldn't reach the target.
  final bool failed;

  RestoreSearchState copyWith({String? query, List<Track>? results, bool? loading, bool? failed}) => RestoreSearchState(
    position: position,
    query: query ?? this.query,
    results: results ?? this.results,
    loading: loading ?? this.loading,
    failed: failed ?? this.failed,
  );
}

/// [backup] with the contents of its version that began at [versionSince] (null, or no such version: as it is).
PlaylistBackupRecord restoreSnapshot(PlaylistBackupRecord backup, int? versionSince) {
  if (versionSince == null) return backup;
  final version = backup.versions.where((v) => v.since == versionSince).firstOrNull;
  return version == null ? backup : backup.copyWith(content: version.content);
}

@immutable
final class PlaylistBackupPageState {
  const PlaylistBackupPageState({
    this.backup = const PlaylistBackupLoading(),
    this.restore = const RestoreIdle(),
    this.targets = const [],
    this.search,
    this.backingUp = false,
    this.restoreVersion,
    this.deleted = false,
  });

  final PlaylistBackupUiState backup;
  final RestoreUiState restore;
  final List<RestoreTarget> targets;
  final RestoreSearchState? search;
  final bool backingUp;

  /// Which version a restore takes: null = the current one, else that version's [BackupVersion.since].
  final int? restoreVersion;

  /// Deleted from this page: it closes.
  final bool deleted;

  /// The backup as the restore sees it: its current contents, or the chosen earlier version's.
  PlaylistBackupRecord? get restoreBackup => switch (backup) {
    PlaylistBackupContentState(:final backup) => restoreSnapshot(backup, restoreVersion),
    _ => null,
  };

  static const _keep = Object();

  PlaylistBackupPageState copyWith({
    PlaylistBackupUiState? backup,
    RestoreUiState? restore,
    List<RestoreTarget>? targets,
    Object? search = _keep,
    bool? backingUp,
    Object? restoreVersion = _keep,
    bool? deleted,
  }) => PlaylistBackupPageState(
    backup: backup ?? this.backup,
    restore: restore ?? this.restore,
    targets: targets ?? this.targets,
    search: identical(search, _keep) ? this.search : search as RestoreSearchState?,
    backingUp: backingUp ?? this.backingUp,
    restoreVersion: identical(restoreVersion, _keep) ? this.restoreVersion : restoreVersion as int?,
    deleted: deleted ?? this.deleted,
  );
}

class PlaylistBackupNotifier extends Notifier<PlaylistBackupPageState> {
  PlaylistBackupNotifier(this.backupId);

  final String backupId;

  /// Bumped when a restore is dropped: a match still running then reports to no one.
  int _generation = 0;
  bool _matching = false;
  Timer? _searchDebounce;
  int _searchGeneration = 0;

  /// Keeps this state while a restore is under way, with the page closed.
  KeepAliveLink? _keepAlive;

  late RestoreProgressSink _progress;

  static const searchDebounce = Duration(milliseconds: 350);

  PlaylistBackupService get _service => ref.read(playlistBackupServiceProvider);

  @override
  PlaylistBackupPageState build() {
    final store = ref.watch(playlistBackupStoreProvider);
    final subscription = store.watch(backupId).listen((record) {
      if (!ref.mounted) return;
      state = state.copyWith(
        backup: record == null ? const PlaylistBackupMissing() : PlaylistBackupContentState(record),
      );
    });
    _progress = ref.read(restoreProgressFactoryProvider)(backupId);
    ref.onDispose(() {
      subscription.cancel();
      _searchDebounce?.cancel();
      _progress.dispose();
    });
    _loadTargets();
    return const PlaylistBackupPageState();
  }

  Future<void> _loadTargets() async {
    try {
      final targets = await _service.restoreTargets();
      if (ref.mounted) state = state.copyWith(targets: targets);
    } catch (_) {}
  }

  /// Restore: a fresh choice of target — of the current contents, or of the earlier version that began at
  /// [versionSince] — or back to the restore under way.
  void openRestore([int? versionSince]) {
    switch (state.restore) {
      case RestoreIdle() || RestoreDone() || RestoreFailed():
        state = state.copyWith(restoreVersion: versionSince);
        _setRestore(const RestoreChoosingTarget());
        _loadTargets();
      default:
        break;
    }
  }

  /// The sheet closed; a finished restore is put away, one under way carries on.
  void dismissSheet() {
    switch (state.restore) {
      case RestoreChoosingTarget() || RestoreDone() || RestoreFailed():
        _setRestore(const RestoreIdle(), clearSearch: true);
      default:
        break;
    }
  }

  Future<void> restoreTo(RestoreTarget target) async {
    final backup = state.restoreBackup;
    if (backup == null || _matching) return;
    // The progress notification needs the permission: asked as the user starts a restore, never waited on.
    unawaited(_progress.askToNotify());
    final generation = ++_generation;
    _matching = true;
    _setRestore(RestoreMatching(target, 0, backup.trackCount));
    _progress.matching(backup, target, 0, backup.trackCount);
    RestoreUiState next;
    try {
      final resolutions = await _service.match(
        backup,
        target,
        onProgress: (done, total) {
          if (!ref.mounted || generation != _generation || state.restore is! RestoreMatching) return;
          _setRestore(RestoreMatching(target, done, total));
          _progress.matching(backup, target, done, total);
        },
      );
      next = RestoreReviewing(target, RestoreReview.rows(resolutions));
    } catch (_) {
      next = RestoreFailed(target);
    } finally {
      if (generation == _generation) _matching = false;
    }
    // Dropped meanwhile (cancelled, deleted): that already took the progress away.
    if (!ref.mounted || generation != _generation) return;
    // Matching done: the review waits in the app, not in the notification.
    _progress.clear();
    _setRestore(next);
  }

  void setChecked(int position, bool checked) =>
      _updateReview((rows) => RestoreReview.setChecked(rows, position, checked));

  void choose(int position, int index) => _updateReview((rows) => RestoreReview.choose(rows, position, index));

  /// Writes the checked rows to the target; the backup's history records it.
  Future<void> createPlaylist() async {
    final backup = state.restoreBackup;
    final review = state.restore;
    if (backup == null || review is! RestoreReviewing) return;
    final count = RestoreReview.trackIdsToWrite(review.rows).length;
    if (count == 0) return;
    _setRestore(RestoreWriting(review.target, review));
    _progress.writing(backup, review.target, count);
    // Once started, the write and its history entry finish even if the page closes.
    final RestoreOutcome outcome;
    try {
      outcome = await _service.create(backup, review.target, review.rows);
    } catch (e) {
      // The history couldn't be written (the playlist's own failure is recorded): back to the review.
      debugPrint('PlaylistBackup: restore not recorded (${e.runtimeType})');
      _progress.clear();
      if (ref.mounted) _setRestore(review);
      return;
    }
    _progress.finished(backup, review.target, outcome.record);
    if (!ref.mounted) return;
    _setRestore(RestoreDone(review.target, outcome.record));
  }

  /// Drops the restore under way (a review nothing was written from).
  void cancelRestore() {
    if (state.restore is RestoreWriting) return;
    _generation++;
    _matching = false;
    _searchDebounce?.cancel();
    _progress.clear();
    _setRestore(const RestoreIdle(), clearSearch: true);
  }

  /// Opens the search for the song at [position] on the restore's target, starting from its title and artist.
  void openSearch(int position, String query) {
    state = state.copyWith(
      search: RestoreSearchState(position: position, query: query),
    );
    runSearch(query, debounce: false);
  }

  void runSearch(String query, {bool debounce = true}) {
    final review = state.restore;
    final current = state.search;
    if (review is! RestoreReviewing || current == null) return;
    final trimmed = query.trim();
    state = state.copyWith(
      search: current.copyWith(query: query, loading: trimmed.isNotEmpty, failed: false),
    );
    _searchDebounce?.cancel();
    final generation = ++_searchGeneration;
    if (trimmed.isEmpty) return;
    Future<void> run() async {
      List<Track>? results;
      try {
        results = await _service.search(review.target, trimmed);
      } catch (_) {
        results = null;
      }
      final now = state.search;
      if (!ref.mounted || generation != _searchGeneration || now == null || now.position != current.position) return;
      state = state.copyWith(
        search: now.copyWith(results: results ?? const [], loading: false, failed: results == null),
      );
    }

    if (debounce) {
      _searchDebounce = Timer(searchDebounce, run);
    } else {
      run();
    }
  }

  /// [track] becomes the searched row's song, checked; the search closes.
  void pickSearchResult(Track track) {
    final position = state.search?.position;
    if (position == null) return;
    _updateReview((rows) => RestoreReview.pick(rows, position, track));
    closeSearch();
  }

  void closeSearch() {
    _searchDebounce?.cancel();
    _searchGeneration++;
    state = state.copyWith(search: null);
  }

  /// Reads the playlist again now (the page's Back up now).
  Future<void> backUpAgain() async {
    // The record as stored, never the restore's view of it: with an earlier version last picked for a
    // restore, that view carries the old songs, and backing it up would file them as what it held until now.
    final current = state.backup;
    if (current is! PlaylistBackupContentState || state.backingUp) return;
    state = state.copyWith(backingUp: true);
    try {
      await _service.followNow(current.backup);
    } catch (_) {
      // Left as it was; the page keeps showing the last backup.
    } finally {
      if (ref.mounted) state = state.copyWith(backingUp: false);
    }
  }

  Future<void> delete() async {
    _generation++;
    _matching = false;
    if (state.restore is! RestoreWriting) _progress.clear();
    await ref.read(playlistBackupStoreProvider).delete(backupId);
    if (!ref.mounted) return;
    state = state.copyWith(deleted: true);
    if (state.restore is! RestoreWriting) _setRestore(const RestoreIdle(), clearSearch: true);
  }

  void _updateReview(List<ReviewRow> Function(List<ReviewRow> rows) change) {
    final review = state.restore;
    if (review is! RestoreReviewing) return;
    _setRestore(RestoreReviewing(review.target, change(review.rows)));
  }

  /// Moves the restore to [next]. Under way (matching, reviewing, writing), the state is kept with the page
  /// closed, so coming back finds it live; settled (idle, choosing, done, failed), it goes with the page.
  void _setRestore(RestoreUiState next, {bool clearSearch = false}) {
    state = clearSearch ? state.copyWith(restore: next, search: null) : state.copyWith(restore: next);
    switch (next) {
      case RestoreMatching() || RestoreReviewing() || RestoreWriting():
        _keepAlive ??= ref.keepAlive();
      case RestoreIdle() || RestoreChoosingTarget() || RestoreDone() || RestoreFailed():
        _keepAlive?.close();
        _keepAlive = null;
    }
  }
}

final playlistBackupPageProvider = NotifierProvider.autoDispose
    .family<PlaylistBackupNotifier, PlaylistBackupPageState, String>(PlaylistBackupNotifier.new);
