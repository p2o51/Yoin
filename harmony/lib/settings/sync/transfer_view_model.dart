/// Transfer Memories, one step at a time (Android ui/settings/sync/
/// TransferViewModel.kt; prototype s5–s7): source → target → summary →
/// matching → review → copy → done. Copy, never move: nothing is written
/// before the copy step, and the copy finishes even if the page closes.
///
/// Sources are this device's accounts, accounts only in the cloud (Cloud sync
/// on), and the accounts of a backup file the user picks (a backup's Spotify
/// account included: HarmonyOS has no Spotify, its Memories are copied into a
/// local account by matching). Matching searches the target account's own
/// service ([SourceMemoryCatalog]); without one (signed out, no credentials)
/// every song and album that needs matching comes back not found.
library;

import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/model/media_id.dart';
import '../../data/portability/backup_file_io.dart';
import '../../data/portability/backup_files.dart';
import '../../data/portability/backup_format.dart';
import '../../data/portability/memories/memory_matching.dart';
import '../../data/portability/memories/transfer_models.dart';
import '../../data/portability/memories/transfer_rules.dart';
import '../../data/portability/memories_portability.dart';
import '../../data/portability/portability_providers.dart';
import '../../data/sync/cloud_data_views.dart' show CloudScopeCounts;
import '../../data/transfer/restore_review.dart';
import '../../platform/share/document_files.dart';
import '../settings_view_model.dart' show settingsViewModelProvider;
import 'backup_view_model.dart' show BackupReadFailure, backupReadFailureOf, countsOfKinds;
import 'cloud_sync_view_model.dart' show cloudSyncControllerProvider;

/// Where a transfer starts: any of these may be preset by the entry (the account's cloud data page).
@immutable
final class TransferArgs {
  const TransferArgs({this.sourceProfileId, this.sourceCloudScope, this.targetProfileId, this.sourceProvider});

  final String? sourceProfileId;
  final String? sourceCloudScope;
  final String? targetProfileId;

  /// The cloud source's service, when the entry knows it: used if the replica doesn't list
  /// [sourceCloudScope], so the target list still tells a same-service copy.
  final String? sourceProvider;

  @override
  bool operator ==(Object other) =>
      other is TransferArgs &&
      other.sourceProfileId == sourceProfileId &&
      other.sourceCloudScope == sourceCloudScope &&
      other.targetProfileId == targetProfileId &&
      other.sourceProvider == sourceProvider;

  @override
  int get hashCode => Object.hash(sourceProfileId, sourceCloudScope, targetProfileId, sourceProvider);
}

enum TransferStep { loading, source, target, summary, matching, review, copying, done, failed }

/// How the pair copies: same ids ([direct]), mostly the same (Apple Music library songs, [partial]) or [match].
enum TransferKind { direct, partial, match }

enum TransferFailure { readFailed, targetUnreachable, runFailed }

/// One account the flow can copy from or into. [counts] when known (the cloud replica, a backup's manifest).
@immutable
final class TransferAccountUi {
  const TransferAccountUi({required this.key, required this.source, this.counts, this.fromDevice, this.hint});

  final String key;
  final TransferSource source;
  final CloudScopeCounts? counts;
  final String? fromDevice;
  final String? hint;

  TransferAccount get account => source.account;
  bool get cloudOnly => source.kind == TransferSourceKind.cloudAccount;
  bool get fromBackup => source.kind == TransferSourceKind.backupAccount;
}

/// What the flow tells in a passing message (Android TransferMessage), or why a backup didn't read.
sealed class TransferNotice {
  const TransferNotice(this.seq);

  /// Tells two equal notices apart.
  final int seq;
}

final class TransferBusyNotice extends TransferNotice {
  const TransferBusyNotice(super.seq);
}

final class TransferCleanedNotice extends TransferNotice {
  const TransferCleanedNotice(super.seq, this.sourceName, {required this.ok});
  final String sourceName;
  final bool ok;
}

final class TransferBackupNotice extends TransferNotice {
  const TransferBackupNotice(super.seq, this.reason);
  final BackupReadFailure reason;
}

/// The flow is over (Android TransferEvent.Finished): close, after the message (if any) is shown.
sealed class TransferFinish {
  const TransferFinish();
}

final class TransferClosed extends TransferFinish {
  const TransferClosed();
}

final class TransferCopied extends TransferFinish {
  const TransferCopied(this.sourceName);
  final String sourceName;
}

final class TransferCardRemoved extends TransferFinish {
  const TransferCardRemoved(this.sourceName);
  final String sourceName;
}

const _unset = Object();

@immutable
final class TransferUiState {
  const TransferUiState({
    this.step = TransferStep.loading,
    this.firstStep = TransferStep.source,
    this.accounts = const [],
    this.backup,
    this.sourceKey,
    this.targetKey,
    this.draft,
    this.draftLoading = false,
    this.trackMatches = const [],
    this.albumMatches = const [],
    this.rows = const [],
    this.progressDone = 0,
    this.progressTotal = 0,
    this.result,
    this.removeCard = false,
    this.cleaned = false,
    this.cleaning = false,
    this.finishing = false,
    this.failure,
    this.cloudSyncOn = false,
    this.notice,
    this.finished,
  });

  final TransferStep step;

  /// The step Back closes the flow from (the first one the entry didn't preset).
  final TransferStep firstStep;
  final List<TransferAccountUi> accounts;

  /// The backup file picked as a source, when one was.
  final ParsedBackup? backup;
  final String? sourceKey;
  final String? targetKey;

  /// The plan before matching: what would be copied, what was copied before, what needs matching.
  final TransferPlan? draft;
  final bool draftLoading;

  /// After matching: each song / album that needed it, in the draft's order.
  final List<TrackMatch> trackMatches;
  final List<AlbumMatch> albumMatches;

  /// The review, one row per [trackMatches] entry (position = its index).
  final List<ReviewRow> rows;
  final int progressDone;
  final int progressTotal;
  final TransferResult? result;
  final bool removeCard;
  final bool cleaned;

  /// Removing the source's cloud data right now.
  final bool cleaning;

  /// Done was confirmed: the card removal (if any) is running, the flow closes after it.
  final bool finishing;
  final TransferFailure? failure;

  /// Cloud sync is on here: the done page offers removing the source's cloud data.
  final bool cloudSyncOn;
  final TransferNotice? notice;
  final TransferFinish? finished;

  TransferAccountUi? get source => accounts.where((a) => a.key == sourceKey).firstOrNull;
  TransferAccountUi? get target => accounts.where((a) => a.key == targetKey).firstOrNull;

  /// Accounts Memories can come from.
  List<TransferAccountUi> get sources => accounts;

  /// Accounts on this device other than the source.
  List<TransferAccountUi> get targets => [
    for (final a in accounts)
      if (a.account.localProfileId != null && a.key != sourceKey) a,
  ];

  /// Whether the pair needs a matching pass (known once the draft is read).
  bool get needsMatching => draft?.needsMatching ?? true;

  int get totalItems => draft == null ? 0 : TransferRules.countItems(draft!.items);

  int get remainingItems {
    final d = draft;
    if (d == null) return 0;
    final left = totalItems - d.previouslyCopied;
    return left < 0 ? 0 : left;
  }

  /// Songs whose ids carry over as they are and still have something to copy (a rerun leaves out what an
  /// earlier run copied).
  int get directSongs {
    final d = draft;
    if (d == null) return 0;
    final pending = TransferRules.pendingItems(d.items, d.ledger);
    final matching = {for (final t in d.tracksToMatch) t.source, for (final p in d.reusedTracks) p.source};
    return pending.tracks.where((t) => !matching.contains(t)).length;
  }

  /// Songs whose data the run will copy: the direct ones, the ones matched by an earlier run, the checked rows.
  int get songsToCopy => directSongs + (draft?.reusedTracks.length ?? 0) + rows.where((r) => r.checked).length;

  /// The review's copy button has something to copy: a song's data, or an album matched by its songs or by search.
  bool get canCopyReviewed =>
      songsToCopy > 0 || (draft?.reusedAlbums.isNotEmpty ?? false) || albumMatches.any((a) => a.status.copies);

  /// Steps in this flow, for the "Step n of m" line: the review only when something needs matching.
  int get stepCount => draft != null && !needsMatching ? 4 : 5;

  int stepIndexOf(TransferStep step) => switch (step) {
    TransferStep.loading || TransferStep.source => 0,
    TransferStep.target => 1,
    TransferStep.summary => 2,
    TransferStep.matching || TransferStep.review => 3,
    TransferStep.copying || TransferStep.done || TransferStep.failed => stepCount - 1,
  };

  TransferUiState copyWith({
    TransferStep? step,
    TransferStep? firstStep,
    List<TransferAccountUi>? accounts,
    Object? backup = _unset,
    Object? sourceKey = _unset,
    Object? targetKey = _unset,
    Object? draft = _unset,
    bool? draftLoading,
    List<TrackMatch>? trackMatches,
    List<AlbumMatch>? albumMatches,
    List<ReviewRow>? rows,
    int? progressDone,
    int? progressTotal,
    Object? result = _unset,
    bool? removeCard,
    bool? cleaned,
    bool? cleaning,
    bool? finishing,
    Object? failure = _unset,
    bool? cloudSyncOn,
    Object? notice = _unset,
    Object? finished = _unset,
  }) => TransferUiState(
    step: step ?? this.step,
    firstStep: firstStep ?? this.firstStep,
    accounts: accounts ?? this.accounts,
    backup: identical(backup, _unset) ? this.backup : backup as ParsedBackup?,
    sourceKey: identical(sourceKey, _unset) ? this.sourceKey : sourceKey as String?,
    targetKey: identical(targetKey, _unset) ? this.targetKey : targetKey as String?,
    draft: identical(draft, _unset) ? this.draft : draft as TransferPlan?,
    draftLoading: draftLoading ?? this.draftLoading,
    trackMatches: trackMatches ?? this.trackMatches,
    albumMatches: albumMatches ?? this.albumMatches,
    rows: rows ?? this.rows,
    progressDone: progressDone ?? this.progressDone,
    progressTotal: progressTotal ?? this.progressTotal,
    result: identical(result, _unset) ? this.result : result as TransferResult?,
    removeCard: removeCard ?? this.removeCard,
    cleaned: cleaned ?? this.cleaned,
    cleaning: cleaning ?? this.cleaning,
    finishing: finishing ?? this.finishing,
    failure: identical(failure, _unset) ? this.failure : failure as TransferFailure?,
    cloudSyncOn: cloudSyncOn ?? this.cloudSyncOn,
    notice: identical(notice, _unset) ? this.notice : notice as TransferNotice?,
    finished: identical(finished, _unset) ? this.finished : finished as TransferFinish?,
  );
}

/// The pair's kind from the providers alone, for the target list's badge.
TransferKind transferKindOf(TransferAccount source, TransferAccount target) {
  if (source.provider != target.provider) return TransferKind.match;
  if (source.provider == MediaId.providerSpotify) return TransferKind.direct;
  if (source.provider == MediaId.providerAppleMusic) return TransferKind.partial;
  final key = source.serverKey;
  if (source.provider == MediaId.providerSubsonic &&
      key != null &&
      key.toLowerCase() == target.serverKey?.toLowerCase()) {
    return TransferKind.direct;
  }
  return TransferKind.match;
}

/// The review's rows for [matches]: matched checked, unsure with its best candidate preselected but
/// unchecked, not found empty (Android reviewRowsOf).
List<ReviewRow> reviewRowsOf(List<TrackMatch> matches) => [
  for (final (i, m) in matches.indexed)
    switch (m.status) {
      MatchStatus.matched || MatchStatus.picked || MatchStatus.direct => ReviewRow(
        position: i,
        status: m.status == MatchStatus.picked ? ReviewStatus.picked : ReviewStatus.matched,
        candidates: m.chosen != null ? [m.chosen!] : m.candidates.take(1).toList(),
        chosen: 0,
        checked: m.chosen != null || m.candidates.isNotEmpty,
      ),
      MatchStatus.uncertain => ReviewRow(
        position: i,
        status: ReviewStatus.uncertain,
        candidates: m.candidates,
        chosen: 0,
        checked: false,
      ),
      MatchStatus.unmatched => ReviewRow(
        position: i,
        status: ReviewStatus.notFound,
        candidates: const [],
        chosen: 0,
        checked: false,
      ),
    },
];

/// [matches] with the review's decisions: checked rows take their chosen candidate, unchecked ones copy nothing.
List<TrackMatch> applyReview(List<TrackMatch> matches, List<ReviewRow> rows) {
  final out = [...matches];
  for (final row in rows) {
    if (row.position < 0 || row.position >= out.length) continue;
    final match = out[row.position];
    final choice = row.choice;
    final unchanged = row.checked && choice != null && choice.id == match.chosen?.id && match.status.copies;
    if (unchanged) continue;
    if (row.checked && choice != null) {
      out[row.position] = match.pick(choice);
    } else if (match.chosen != null) {
      out[row.position] = match.pick(null);
    }
  }
  return out;
}

/// A catalogue over the target account's service for one matching pass; [dispose] after it.
typedef OpenedMemoryCatalog = ({MemoryCatalog catalog, void Function() dispose});

/// Opens the target's catalogue (and the source's, when it is an account here); null without credentials.
typedef MemoryCatalogOpener = Future<OpenedMemoryCatalog?> Function(String targetProfileId, {String? sourceProfileId});

final memoryCatalogOpenerProvider = Provider<MemoryCatalogOpener>(
  (ref) => (target, {sourceProfileId}) async {
    final catalog = await SourceMemoryCatalog.open(ref, target, sourceProfileId: sourceProfileId);
    return catalog == null ? null : (catalog: catalog, dispose: catalog.dispose);
  },
);

/// Removes an account's card from this device, as Settings › account › Remove does; false when it stayed.
final transferRemoveProfileProvider = Provider<Future<bool> Function(String profileId)>(
  (ref) =>
      (profileId) => ref.read(settingsViewModelProvider.notifier).remove(profileId),
);

class TransferNotifier extends Notifier<TransferUiState> {
  TransferNotifier(this.args);

  final TransferArgs args;

  /// Bumped by every step that drops a running job (back, cancel): a late answer is ignored.
  var _run = 0;
  var _seq = 0;

  static const _unknownProvider = 'unknown';

  static String localKey(String profileId) => 'local:$profileId';
  static String cloudKey(String scope) => 'cloud:$scope';
  static String backupKey(String scope) => 'backup:$scope';

  static String keyOf(TransferSource source) => switch (source.kind) {
    TransferSourceKind.localProfile => localKey(source.account.localProfileId ?? source.account.scope),
    TransferSourceKind.cloudAccount => cloudKey(source.account.scope),
    TransferSourceKind.backupAccount => backupKey(source.account.scope),
  };

  MemoriesPortability get _portability => ref.read(memoriesPortabilityProvider);

  @override
  TransferUiState build() {
    // A page that goes away mid-match stops the search; a copy already started still finishes.
    ref.onDispose(() => _run++);
    scheduleMicrotask(_load);
    return const TransferUiState();
  }

  void _set(TransferUiState Function(TransferUiState s) change) {
    if (ref.mounted) state = change(state);
  }

  /// This device's accounts, then (Cloud sync on) the ones only in the cloud.
  Future<List<TransferAccountUi>> _accounts() async {
    List<TransferSource> sources;
    try {
      sources = await _portability.transferSources();
    } catch (_) {
      sources = const [];
    }
    final sync = ref.read(cloudSyncControllerProvider).state.value;
    final cloudRows = {for (final c in sync.cloudOnlyAccounts) c.syncProfileId: c};
    CloudScopeCounts? countsOf(String scope) {
      // Counts come from the cloud replica: only meaningful while Cloud sync is on here.
      if (!sync.enabled || !BackupFormat.isSyncScope(scope)) return null;
      return sync.scopeCounts[scope] ?? const CloudScopeCounts();
    }

    final accounts = [
      for (final s in sources)
        TransferAccountUi(
          key: keyOf(s),
          source: s,
          counts: countsOf(s.account.scope),
          fromDevice: s.kind == TransferSourceKind.cloudAccount ? cloudRows[s.account.scope]?.fromDeviceName : null,
          hint: s.kind == TransferSourceKind.cloudAccount ? cloudRows[s.account.scope]?.hint : null,
        ),
    ];
    // A preset cloud source the list doesn't name (its descriptor not in the replica yet): read it anyway.
    final scope = args.sourceCloudScope;
    if (scope != null && sync.enabled && accounts.every((a) => a.key != cloudKey(scope))) {
      final row = cloudRows[scope];
      accounts.add(
        TransferAccountUi(
          key: cloudKey(scope),
          source: TransferSource(
            TransferSourceKind.cloudAccount,
            TransferAccount(
              scope: scope,
              provider: args.sourceProvider ?? row?.provider ?? _unknownProvider,
              displayName: row?.displayName ?? scope,
            ),
          ),
          counts: countsOf(scope),
          fromDevice: row?.fromDeviceName,
          hint: row?.hint,
        ),
      );
    }
    return accounts;
  }

  Future<void> _load() async {
    if (!ref.mounted) return;
    final accounts = await _accounts();
    if (!ref.mounted) return;
    final presetSource = args.sourceProfileId != null
        ? localKey(args.sourceProfileId!)
        : args.sourceCloudScope != null
        ? cloudKey(args.sourceCloudScope!)
        : null;
    final source = accounts.any((a) => a.key == presetSource) ? presetSource : null;
    final presetTarget = args.targetProfileId == null ? null : localKey(args.targetProfileId!);
    final target = presetTarget != source && accounts.any((a) => a.key == presetTarget) ? presetTarget : null;
    final first = source == null
        ? TransferStep.source
        : target == null
        ? TransferStep.target
        : TransferStep.summary;
    state = TransferUiState(
      step: first,
      firstStep: first,
      accounts: accounts,
      sourceKey: source,
      targetKey: target,
      cloudSyncOn: ref.read(cloudSyncControllerProvider).state.value.enabled,
    );
    if (first == TransferStep.summary) unawaited(_loadDraft());
  }

  /// The accounts again (one was just added), keeping the picks and any backup file's accounts.
  Future<void> refreshAccounts() async {
    final accounts = await _accounts();
    _set(
      (s) => s.copyWith(
        accounts: [
          ...accounts,
          for (final a in s.accounts)
            if (a.fromBackup) a,
        ],
      ),
    );
  }

  /// Reads a backup file the user picked; its accounts join the sources.
  Future<void> pickBackup() async {
    if (state.step != TransferStep.source) return;
    final Uint8List? bytes;
    try {
      bytes = await pickBackupFile(ref.read(documentFilesProvider));
    } catch (_) {
      return;
    }
    if (bytes == null) return;
    await readBackup(bytes);
  }

  /// [bytes] as a backup file whose accounts can be copied from.
  Future<void> readBackup(Uint8List bytes) async {
    final ParsedBackup backup;
    try {
      backup = _portability.read(bytes);
    } catch (error) {
      _set((s) => s.copyWith(notice: TransferBackupNotice(++_seq, backupReadFailureOf(error))));
      return;
    }
    final counts = {for (final a in backup.manifest.accounts) a.scope: a.counts};
    final fromFile = [
      for (final s in _portability.transferSourcesOf(backup))
        TransferAccountUi(
          key: keyOf(s),
          source: s,
          counts: countsOfKinds(counts[s.account.scope] ?? const {}),
          hint: backup.manifest.accounts.where((a) => a.scope == s.account.scope).firstOrNull?.hint,
          fromDevice: backup.manifest.source.deviceName,
        ),
    ];
    _set(
      (s) => s.copyWith(
        backup: backup,
        accounts: [
          for (final a in s.accounts)
            if (!a.fromBackup) a,
          ...fromFile,
        ],
        sourceKey: s.source?.fromBackup ?? false ? null : s.sourceKey,
        draft: null,
      ),
    );
  }

  void pickSource(String key) {
    if (state.step != TransferStep.source || state.accounts.every((a) => a.key != key)) return;
    state = state.copyWith(sourceKey: key, targetKey: state.targetKey == key ? null : state.targetKey, draft: null);
  }

  void pickTarget(String key) {
    if (state.step != TransferStep.target || state.targets.every((a) => a.key != key)) return;
    state = state.copyWith(targetKey: key, draft: null);
  }

  void next() {
    switch (state.step) {
      case TransferStep.source when state.sourceKey != null:
        state = state.copyWith(step: TransferStep.target);
      case TransferStep.target when state.targetKey != null && state.target != null:
        state = state.copyWith(step: TransferStep.summary);
        unawaited(_loadDraft());
      default:
        break;
    }
  }

  /// From the target step: choose the source again.
  void changeSource() {
    state = state.copyWith(step: TransferStep.source, firstStep: TransferStep.source);
  }

  /// Whether Back stays inside the flow (a step back, a busy step, or Done's finish); false = it closes the page.
  bool get handlesBack => switch (state.step) {
    TransferStep.loading || TransferStep.failed => false,
    TransferStep.matching || TransferStep.copying || TransferStep.done => true,
    final step => step != state.firstStep,
  };

  /// Back inside the flow (only while [handlesBack]).
  void back() {
    switch (state.step) {
      case TransferStep.matching || TransferStep.copying:
        state = state.copyWith(notice: TransferBusyNotice(++_seq));
      case TransferStep.done:
        unawaited(finish());
      case TransferStep.loading || TransferStep.failed:
        break;
      case final step:
        if (step == state.firstStep) return;
        _run++;
        state = state.copyWith(
          step: switch (step) {
            TransferStep.target => TransferStep.source,
            TransferStep.summary => TransferStep.target,
            TransferStep.review => TransferStep.summary,
            _ => state.firstStep,
          },
          draftLoading: false,
        );
    }
  }

  Future<void> _loadDraft() async {
    final source = state.source;
    final target = state.target;
    if (source == null || target == null) return;
    final run = ++_run;
    state = state.copyWith(draft: null, draftLoading: true);
    try {
      final items = await _portability.memoryItems(source.source, backup: state.backup);
      final draft = await _portability.planTransfer(source.account, items, target.account);
      if (run != _run) return;
      _set((s) => s.copyWith(draft: draft, draftLoading: false));
    } catch (_) {
      if (run != _run) return;
      _set((s) => s.copyWith(draftLoading: false, step: TransferStep.failed, failure: TransferFailure.readFailed));
    }
  }

  /// The summary's button: match (when needed) or copy straight away.
  Future<void> start() async {
    final draft = state.draft;
    if (draft == null || state.step != TransferStep.summary || state.remainingItems == 0) return;
    if (!draft.needsMatching) {
      await _copy(draft, draft.reusedTracks, draft.reusedAlbums);
      return;
    }
    final source = state.source;
    final targetId = state.target?.account.localProfileId;
    if (source == null || targetId == null) return;
    final run = ++_run;
    final total = draft.tracksToMatch.length + draft.albumsToMatch.length;
    state = state.copyWith(step: TransferStep.matching, progressDone: 0, progressTotal: total);
    OpenedMemoryCatalog? opened;
    try {
      opened = await ref.read(memoryCatalogOpenerProvider)(targetId, sourceProfileId: source.account.localProfileId);
      if (run != _run || !ref.mounted) return;
      final catalog = opened?.catalog;
      var done = 0;
      void tick() {
        done++;
        if (run == _run) _set((s) => s.step == TransferStep.matching ? s.copyWith(progressDone: done) : s);
      }

      final tracks = await MemoryMatching.matchTracks(draft.tracksToMatch, catalog, onEach: tick);
      final albums = <AlbumMatch>[];
      for (final album in draft.albumsToMatch) {
        if (run != _run) return;
        albums.add(
          await MemoryMatching.matchAlbum(
            album,
            tracks,
            (ref) => TransferRules.linkedAlbum(ref, draft.items) ?? draft.items.trackFacts[ref]?.albumId,
            catalog,
          ),
        );
        tick();
      }
      if (run != _run) return;
      _set(
        (s) => s.step != TransferStep.matching
            ? s
            : s.copyWith(
                step: TransferStep.review,
                trackMatches: tracks,
                albumMatches: albums,
                rows: reviewRowsOf(tracks),
              ),
      );
    } catch (_) {
      if (run != _run) return;
      _set((s) => s.copyWith(step: TransferStep.failed, failure: TransferFailure.targetUnreachable));
    } finally {
      opened?.dispose();
    }
  }

  /// Matching or the review dropped: nothing was written.
  void cancel() {
    if (state.step == TransferStep.copying || state.step == TransferStep.done) return;
    _run++;
    state = state.copyWith(finished: const TransferClosed());
  }

  void setChecked(int position, bool checked) =>
      _updateRows((rows) => RestoreReview.setChecked(rows, position, checked));

  /// Picking a candidate is consent: the row is checked.
  void choose(int position, int index) => _updateRows((rows) => RestoreReview.choose(rows, position, index));

  void _updateRows(List<ReviewRow> Function(List<ReviewRow> rows) change) {
    if (state.step == TransferStep.review) state = state.copyWith(rows: change(state.rows));
  }

  /// The review's button.
  Future<void> copyReviewed() async {
    final draft = state.draft;
    if (draft == null || state.step != TransferStep.review || !state.canCopyReviewed) return;
    final tracks = applyReview(state.trackMatches, state.rows);
    await _copy(
      draft,
      [for (final m in tracks) m.toPick(), ...draft.reusedTracks],
      [for (final a in state.albumMatches) a.toPick(), ...draft.reusedAlbums],
    );
  }

  /// Once copying starts it finishes, page or not.
  Future<void> _copy(TransferPlan plan, List<TrackPick> tracks, List<AlbumPick> albums) async {
    state = state.copyWith(step: TransferStep.copying);
    TransferResult? result;
    try {
      result = await _portability.applyTransfer(plan, tracks, albums);
    } catch (_) {
      result = null;
    }
    if (result != null &&
        result.copied.total + result.updated.total + result.reviewsJoined > 0 &&
        ref.read(cloudSyncControllerProvider).state.value.enabled) {
      ref.read(cloudSyncControllerProvider).syncNow();
    }
    _set(
      (s) => result == null
          ? s.copyWith(step: TransferStep.failed, failure: TransferFailure.runFailed)
          : s.copyWith(step: TransferStep.done, result: result),
    );
  }

  void setRemoveCard(bool remove) {
    if (state.source?.account.localProfileId != null) state = state.copyWith(removeCard: remove);
  }

  /// The done page's "Remove original cloud data", after its confirmation.
  Future<void> cleanSource() async {
    final source = state.source;
    if (source == null) return;
    final scope = source.account.scope;
    if (state.cleaned || state.cleaning || !state.cloudSyncOn || !BackupFormat.isSyncScope(scope)) return;
    state = state.copyWith(cleaning: true);
    var ok = true;
    try {
      await ref.read(cloudSyncControllerProvider).removeAccountFromCloud(scope);
    } catch (_) {
      ok = false;
    }
    _set(
      (s) => s.copyWith(
        cleaning: false,
        cleaned: s.cleaned || ok,
        notice: TransferCleanedNotice(++_seq, source.account.displayName, ok: ok),
      ),
    );
  }

  /// Done: remove the source's card if asked, then close. Runs once, however often Done or Back is pressed.
  Future<void> finish() async {
    if (state.finishing) return;
    final done = state.step == TransferStep.done;
    state = state.copyWith(finishing: true);
    final source = state.source;
    final profileId = source?.account.localProfileId;
    var removed = false;
    if (done && state.removeCard && profileId != null) {
      try {
        removed = await ref.read(transferRemoveProfileProvider)(profileId);
      } catch (_) {
        removed = false;
      }
    }
    final name = source?.account.displayName ?? '';
    _set(
      (s) => s.copyWith(
        finished: !done
            ? const TransferClosed()
            : removed
            ? TransferCardRemoved(name)
            : TransferCopied(name),
      ),
    );
  }
}

/// One Transfer Memories page's flow, from its entry's [TransferArgs]; it goes with the page.
final transferProvider = NotifierProvider.autoDispose.family<TransferNotifier, TransferUiState, TransferArgs>(
  TransferNotifier.new,
);
