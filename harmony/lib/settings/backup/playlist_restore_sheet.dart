/// A backup's restore in one sheet (Android PlaylistRestoreSheet;
/// docs/playlist-transfer.md §7): pick the account, watch the matching,
/// review every song, create the playlist, see what came of it. A song the
/// match didn't settle opens a search on the target's own service, in a
/// sheet of its own. Back and drag close a sheet; the matching and the review
/// stay in [PlaylistBackupNotifier], and Restore reopens them.
library;

import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../../data/backup/playlist_backup_models.dart';
import '../../data/backup/playlist_backup_service.dart';
import '../../data/model/model.dart';
import '../../data/transfer/restore_review.dart';
import '../../nowplaying/haptics.dart';
import '../../shell/bar_suppression.dart';
import '../../theme/motion.dart';
import '../../ui/component/expressive/loading_indicator.dart' show MaterialShapes, ShapeMorph;
import '../../ui/component/expressive_text_field.dart';
import '../../ui/component/meta_line.dart' show formatTrackDuration;
import '../../ui/component/spring_cross_fade.dart';
import '../../ui/component/yoin_loading_indicator.dart';
import '../material_shapes.dart';
import '../settings_components.dart';
import 'playlist_backup_metrics.dart';
import 'playlist_backup_strings.dart';
import 'playlist_backup_view_model.dart';
import 'playlist_backup_widgets.dart';

/// Shows the restore sheet for backup [backupId]; completes when it closes.
Future<void> showPlaylistRestoreSheet(BuildContext context, {required String backupId}) => showModalBottomSheet<void>(
  context: context,
  useSafeArea: false,
  isScrollControlled: true,
  showDragHandle: true,
  backgroundColor: Theme.of(context).colorScheme.surfaceContainerLow,
  // The window's bar sits above the Navigator: a sheet sends it away.
  builder: (_) => ChromeBarSuppression(child: _RestoreSheetHost(backupId: backupId)),
);

class _RestoreSheetHost extends ConsumerWidget {
  const _RestoreSheetHost({required this.backupId});

  final String backupId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final provider = playlistBackupPageProvider(backupId);
    final page = ref.watch(provider);
    final notifier = ref.read(provider.notifier);
    final backup = page.restoreBackup;
    if (backup == null) return const SizedBox.shrink();
    return PlaylistRestoreSheetContent(
      state: page.restore,
      backup: backup,
      targets: page.targets,
      versionSince: page.restoreVersion,
      onChooseTarget: notifier.restoreTo,
      onCheck: notifier.setChecked,
      onChoose: notifier.choose,
      onOpenSearch: (position, query) async {
        notifier.openSearch(position, query);
        await _showSearchSheet(context, backupId);
        if (context.mounted) notifier.closeSearch();
      },
      onCreate: notifier.createPlaylist,
      onCancel: () {
        notifier.cancelRestore();
        Navigator.of(context).maybePop();
      },
      onDone: () => Navigator.of(context).maybePop(),
    );
  }
}

/// The sheet's steps, cross-fading as the restore moves on (Review → Writing keeps the list in place).
class PlaylistRestoreSheetContent extends StatelessWidget {
  const PlaylistRestoreSheetContent({
    super.key,
    required this.state,
    required this.backup,
    required this.targets,
    required this.onChooseTarget,
    required this.onCheck,
    required this.onChoose,
    required this.onOpenSearch,
    required this.onCreate,
    required this.onCancel,
    required this.onDone,
    this.versionSince,
    this.now,
  });

  final RestoreUiState state;
  final PlaylistBackupRecord backup;
  final List<RestoreTarget> targets;
  final int? versionSince;
  final ValueChanged<RestoreTarget> onChooseTarget;
  final void Function(int position, bool checked) onCheck;
  final void Function(int position, int index) onChoose;
  final void Function(int position, String query) onOpenSearch;
  final VoidCallback onCreate;
  final VoidCallback onCancel;
  final VoidCallback onDone;

  /// Fixed time (tests).
  final int? now;

  @override
  Widget build(BuildContext context) {
    final state = this.state;
    final key = switch (state) {
      RestoreReviewing() || RestoreWriting() => 'review',
      RestoreMatching() => 'matching',
      RestoreDone() => 'done',
      RestoreFailed() => 'failed',
      RestoreIdle() || RestoreChoosingTarget() => 'targets',
    };
    return SpringCrossFade(
      childKey: key,
      fit: StackFit.loose,
      child: switch (state) {
        RestoreIdle() || RestoreChoosingTarget() => _TargetList(
          backup: backup,
          targets: targets,
          versionSince: versionSince,
          now: now ?? DateTime.now().millisecondsSinceEpoch,
          onChooseTarget: onChooseTarget,
        ),
        RestoreMatching() => _MatchingProgress(state: state),
        RestoreReviewing() || RestoreWriting() => _Review(
          review: state is RestoreWriting ? state.review : state as RestoreReviewing,
          tracks: backup.content.tracks,
          writing: state is RestoreWriting,
          onCheck: onCheck,
          onChoose: onChoose,
          onOpenSearch: onOpenSearch,
          onCreate: onCreate,
          onCancel: onCancel,
        ),
        RestoreDone(:final target, :final record) => _Outcome(target: target, record: record, onDone: onDone),
        RestoreFailed(:final target) => _Failure(target: target, onDone: onDone),
      },
    );
  }
}

class _SheetTitle extends StatelessWidget {
  const _SheetTitle(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(
        horizontal: PlaylistBackupMetrics.sheetHorizontal,
        vertical: PlaylistBackupMetrics.sheetTitleVertical,
      ),
      child: Semantics(
        header: true,
        child: Text(
          text,
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          style: theme.textTheme.headlineSmall!.copyWith(color: theme.colorScheme.onSurface),
        ),
      ),
    );
  }
}

// ── Choose the account ─────────────────────────────────────────────────

class _TargetList extends StatelessWidget {
  const _TargetList({
    required this.backup,
    required this.targets,
    required this.versionSince,
    required this.now,
    required this.onChooseTarget,
  });

  final PlaylistBackupRecord backup;
  final List<RestoreTarget> targets;
  final int? versionSince;
  final int now;
  final ValueChanged<RestoreTarget> onChooseTarget;

  @override
  Widget build(BuildContext context) {
    final strings = PlaylistBackupStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    const horizontal = PlaylistBackupMetrics.sheetHorizontal;
    return Padding(
      padding: EdgeInsets.only(bottom: PlaylistBackupMetrics.sheetBottom + MediaQuery.paddingOf(context).bottom),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _SheetTitle(strings.restoreTitle),
          Padding(
            padding: EdgeInsets.only(
              left: horizontal,
              right: horizontal,
              bottom: versionSince == null
                  ? PlaylistBackupMetrics.sheetBottom
                  : PlaylistBackupMetrics.versionLineBottom,
            ),
            child: Text(
              backup.name,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: theme.textTheme.titleMedium!.copyWith(color: colors.onSurfaceVariant),
            ),
          ),
          // An earlier version: which one.
          if (versionSince != null)
            Padding(
              padding: const EdgeInsets.only(
                left: horizontal,
                right: horizontal,
                bottom: PlaylistBackupMetrics.sheetBottom,
              ),
              child: Text(
                formatBackupTime(context, versionSince!, now),
                style: theme.textTheme.labelLarge!.copyWith(color: colors.primary),
              ),
            ),
          if (targets.isEmpty)
            Padding(
              padding: const EdgeInsets.symmetric(
                horizontal: horizontal,
                vertical: PlaylistBackupMetrics.sheetTitleVertical,
              ),
              child: Text(
                strings.noTargets,
                style: theme.textTheme.bodyLarge!.copyWith(color: colors.onSurfaceVariant),
              ),
            )
          else
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: PlaylistBackupMetrics.sheetListHorizontal),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  for (final (i, target) in targets.indexed) ...[
                    if (i > 0) const SizedBox(height: PlaylistBackupMetrics.segmentGap),
                    _TargetRow(
                      target: target,
                      shape: settingsSegmentShape(i, targets.length),
                      onTap: () {
                        YoinHaptics.confirm();
                        onChooseTarget(target);
                      },
                    ),
                  ],
                ],
              ),
            ),
        ],
      ),
    );
  }
}

class _TargetRow extends StatelessWidget {
  const _TargetRow({required this.target, required this.shape, required this.onTap});

  final RestoreTarget target;
  final ShapeBorder shape;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final service = serviceOf(target.provider);
    return Material(
      color: colors.surfaceContainerHigh,
      shape: shape,
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: ConstrainedBox(
          constraints: const BoxConstraints(minHeight: PlaylistBackupMetrics.targetRowHeight),
          child: Padding(
            padding: const EdgeInsets.symmetric(
              horizontal: PlaylistBackupMetrics.targetRowHorizontal,
              vertical: PlaylistBackupMetrics.targetRowVertical,
            ),
            child: Row(
              children: [
                ServiceRowIcon(provider: target.provider),
                const SizedBox(width: PlaylistBackupMetrics.targetRowGap),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        target.displayName,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: theme.textTheme.titleMedium!.copyWith(color: colors.onSurface),
                      ),
                      if (service.name != target.displayName)
                        Text(
                          service.name,
                          maxLines: 1,
                          style: theme.textTheme.bodyMedium!.copyWith(color: colors.onSurfaceVariant),
                        ),
                    ],
                  ),
                ),
                const SizedBox(width: PlaylistBackupMetrics.targetRowGap),
                Icon(YoinSymbols.chevronRight, color: colors.onSurfaceVariant),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

// ── Matching ───────────────────────────────────────────────────────────

/// A determinate loading shape (circle filling into a soft burst as songs are matched) on a 9-sided
/// cookie, the count, a bar, and the account it matches on.
class _MatchingProgress extends StatefulWidget {
  const _MatchingProgress({required this.state});

  final RestoreMatching state;

  @override
  State<_MatchingProgress> createState() => _MatchingProgressState();
}

class _MatchingProgressState extends State<_MatchingProgress> with SingleTickerProviderStateMixin {
  late final SpringValue _progress = SpringValue(vsync: this, initial: _fraction, spring: YoinSprings.defaultSpatial);

  double get _fraction => widget.state.total > 0 ? widget.state.done / widget.state.total : 0;

  @override
  void didUpdateWidget(_MatchingProgress old) {
    super.didUpdateWidget(old);
    if (MediaQuery.maybeDisableAnimationsOf(context) ?? false) {
      _progress.snapTo(_fraction);
    } else {
      _progress.animateTo(_fraction);
    }
  }

  @override
  void dispose() {
    _progress.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final strings = PlaylistBackupStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final state = widget.state;
    return Padding(
      padding: EdgeInsets.only(
        left: PlaylistBackupMetrics.sheetHorizontal,
        right: PlaylistBackupMetrics.sheetHorizontal,
        top: PlaylistBackupMetrics.matchingTop,
        bottom: PlaylistBackupMetrics.matchingBottom + MediaQuery.paddingOf(context).bottom,
      ),
      child: AnimatedBuilder(
        animation: _progress.animation,
        builder: (context, _) {
          final p = _progress.value.clamp(0.0, 1.0);
          return Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Container(
                width: PlaylistBackupMetrics.matchingCookie,
                height: PlaylistBackupMetrics.matchingCookie,
                decoration: ShapeDecoration(color: colors.primaryContainer, shape: PolygonBorder(cookie9Sided)),
                alignment: Alignment.center,
                child: SizedBox.square(
                  dimension: PlaylistBackupMetrics.matchingIndicator,
                  child: CustomPaint(painter: _DeterminatePainter(p, colors.onPrimaryContainer)),
                ),
              ),
              const SizedBox(height: PlaylistBackupMetrics.matchingGap),
              Text(
                strings.progress(state.done, state.total),
                style: theme.textTheme.displaySmall!.copyWith(color: colors.onSurface),
              ),
              const SizedBox(height: PlaylistBackupMetrics.matchingGap),
              // Rounded by the indicator itself: a clip over the sheet leaves black corners on HarmonyOS.
              LinearProgressIndicator(
                value: p,
                minHeight: PlaylistBackupMetrics.progressHeight,
                borderRadius: BorderRadius.circular(PlaylistBackupMetrics.progressHeight),
                color: colors.primary,
                backgroundColor: colors.secondaryContainer,
              ),
              const SizedBox(height: PlaylistBackupMetrics.matchingGap),
              Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  ServiceRowIcon(provider: state.target.provider),
                  const SizedBox(width: PlaylistBackupMetrics.matchingServiceGap),
                  Flexible(
                    child: Text(
                      strings.matching(state.target.displayName),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: theme.textTheme.titleMedium!.copyWith(color: colors.onSurfaceVariant),
                    ),
                  ),
                ],
              ),
            ],
          );
        },
      ),
    );
  }
}

/// M3's determinate LoadingIndicator: a circle that turns into a soft burst as [progress] fills, turning with it.
class _DeterminatePainter extends CustomPainter {
  _DeterminatePainter(this.progress, this.color);

  final double progress;
  final Color color;

  static final ShapeMorph _morph = ShapeMorph(MaterialShapes.circle, MaterialShapes.softBurst);

  @override
  void paint(Canvas canvas, Size size) {
    final path = _morph.toPath(progress);
    canvas.save();
    canvas.translate(size.width / 2, size.height / 2);
    canvas.rotate(progress * math.pi);
    canvas.translate(-size.width / 2, -size.height / 2);
    canvas.scale(size.width, size.height);
    canvas.drawPath(path, Paint()..color = color);
    canvas.restore();
  }

  @override
  bool shouldRepaint(_DeterminatePainter old) => old.progress != progress || old.color != color;
}

// ── Review ─────────────────────────────────────────────────────────────

enum _ReviewFilter { all, toCheck, notFound }

class _Review extends StatefulWidget {
  const _Review({
    required this.review,
    required this.tracks,
    required this.writing,
    required this.onCheck,
    required this.onChoose,
    required this.onOpenSearch,
    required this.onCreate,
    required this.onCancel,
  });

  final RestoreReviewing review;
  final List<BackupTrack> tracks;
  final bool writing;
  final void Function(int position, bool checked) onCheck;
  final void Function(int position, int index) onChoose;
  final void Function(int position, String query) onOpenSearch;
  final VoidCallback onCreate;
  final VoidCallback onCancel;

  @override
  State<_Review> createState() => _ReviewState();
}

class _ReviewState extends State<_Review> {
  var _filter = _ReviewFilter.all;
  final _expanded = <int>{};

  @override
  Widget build(BuildContext context) {
    final strings = PlaylistBackupStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final rows = widget.review.rows;
    final summary = RestoreReview.summary(rows);
    final toWrite = RestoreReview.trackIdsToWrite(rows).length;
    final canCreate = !widget.writing && toWrite > 0;
    final shown = switch (_filter) {
      _ReviewFilter.all => rows,
      _ReviewFilter.toCheck => rows.where((r) => r.status == ReviewStatus.uncertain).toList(),
      _ReviewFilter.notFound => rows.where((r) => r.status == ReviewStatus.notFound).toList(),
    };
    final target = widget.review.target;
    final serviceName = serviceOf(target.provider).name;
    final height = MediaQuery.sizeOf(context).height * PlaylistBackupMetrics.searchSheetHeightFraction;
    final filters = [
      (_ReviewFilter.all, strings.all, rows.length),
      (_ReviewFilter.toCheck, strings.toCheck, summary.toCheck),
      (_ReviewFilter.notFound, strings.notFound, summary.notFound),
    ];
    return SizedBox(
      height: height,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.only(
              left: PlaylistBackupMetrics.sheetHorizontal,
              right: PlaylistBackupMetrics.sheetHorizontal,
              top: PlaylistBackupMetrics.reviewHeaderTop,
              bottom: PlaylistBackupMetrics.reviewHeaderBottom,
            ),
            child: Row(
              children: [
                ServiceRowIcon(provider: target.provider),
                const SizedBox(width: PlaylistBackupMetrics.reviewHeaderGap),
                Expanded(
                  child: Semantics(
                    header: true,
                    child: Text(
                      target.displayName,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: theme.textTheme.headlineSmall!.copyWith(color: colors.onSurface),
                    ),
                  ),
                ),
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: PlaylistBackupMetrics.sheetHorizontal),
            child: RestoreSummaryBar(summary: summary),
          ),
          Padding(
            padding: const EdgeInsets.only(
              left: PlaylistBackupMetrics.sheetHorizontal,
              right: PlaylistBackupMetrics.sheetHorizontal,
              top: PlaylistBackupMetrics.filtersTop,
              bottom: PlaylistBackupMetrics.filtersBottom,
            ),
            child: Row(
              children: [
                for (final (i, (filter, label, count)) in filters.indexed) ...[
                  if (i > 0) const SizedBox(width: PlaylistBackupMetrics.actionGap),
                  Expanded(
                    child: _ConnectedToggle(
                      label: '$label $count',
                      checked: _filter == filter,
                      index: i,
                      count: filters.length,
                      onTap: () {
                        YoinHaptics.tick();
                        setState(() => _filter = filter);
                      },
                    ),
                  ),
                ],
              ],
            ),
          ),
          Expanded(
            child: ListView.builder(
              padding: const EdgeInsets.symmetric(
                horizontal: PlaylistBackupMetrics.sheetListHorizontal,
                vertical: PlaylistBackupMetrics.barTop,
              ),
              itemCount: shown.length,
              itemBuilder: (context, i) {
                final row = shown[i];
                if (row.position >= widget.tracks.length) return const SizedBox.shrink();
                final track = widget.tracks[row.position];
                return Padding(
                  key: ValueKey(row.position),
                  padding: EdgeInsets.only(top: i == 0 ? 0 : PlaylistBackupMetrics.segmentGap),
                  child: _ReviewRowItem(
                    row: row,
                    track: track,
                    shape: settingsSegmentShape(i, shown.length),
                    enabled: !widget.writing,
                    expanded: _expanded.contains(row.position),
                    searchLabel: strings.search(serviceName),
                    onToggleExpanded: () => setState(() {
                      if (!_expanded.remove(row.position)) _expanded.add(row.position);
                    }),
                    onCheck: (checked) {
                      YoinHaptics.click();
                      widget.onCheck(row.position, checked);
                    },
                    onChoose: (index) {
                      YoinHaptics.click();
                      widget.onChoose(row.position, index);
                    },
                    onSearch: () {
                      YoinHaptics.click();
                      widget.onOpenSearch(row.position, '${track.title} ${track.artists.firstOrNull ?? ''}'.trim());
                    },
                  ),
                );
              },
            ),
          ),
          Padding(
            padding: EdgeInsets.only(
              left: PlaylistBackupMetrics.sheetListHorizontal,
              right: PlaylistBackupMetrics.sheetListHorizontal,
              top: PlaylistBackupMetrics.barTop,
              bottom: PlaylistBackupMetrics.barBottom + MediaQuery.paddingOf(context).bottom,
            ),
            child: Row(
              children: [
                SizedBox(
                  height: PlaylistBackupMetrics.barButtonHeight,
                  child: TextButton(onPressed: widget.writing ? null : widget.onCancel, child: Text(strings.cancel)),
                ),
                const SizedBox(width: PlaylistBackupMetrics.barGap),
                Expanded(
                  child: FilledButton(
                    onPressed: canCreate
                        ? () {
                            YoinHaptics.confirm();
                            widget.onCreate();
                          }
                        : null,
                    style: FilledButton.styleFrom(
                      minimumSize: const Size.fromHeight(PlaylistBackupMetrics.barButtonHeight),
                      shape: const StadiumBorder(),
                      textStyle: theme.textTheme.titleMedium,
                    ),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        if (widget.writing) ...[
                          YoinLoadingIndicator(size: PlaylistBackupMetrics.actionLoading, color: colors.onPrimary),
                          const SizedBox(width: PlaylistBackupMetrics.badgeGap),
                        ],
                        Flexible(
                          child: Text(
                            widget.writing ? strings.writingShort : strings.create,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                        const SizedBox(width: PlaylistBackupMetrics.badgeGap),
                        _CountBadge(count: toWrite),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _CountBadge extends StatelessWidget {
  const _CountBadge({required this.count});

  final int count;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Container(
      constraints: const BoxConstraints(minHeight: PlaylistBackupMetrics.badgeHeight),
      padding: const EdgeInsets.symmetric(
        horizontal: PlaylistBackupMetrics.badgeHorizontal,
        vertical: PlaylistBackupMetrics.badgeVertical,
      ),
      alignment: Alignment.center,
      decoration: ShapeDecoration(
        color: colors.onPrimary.withValues(alpha: PlaylistBackupMetrics.badgeAlpha),
        shape: const StadiumBorder(),
      ),
      child: Text('$count', style: Theme.of(context).textTheme.labelLarge),
    );
  }
}

/// One of the connected filter buttons (ButtonGroup ToggleButton): the group's outer corners round, the
/// inner ones tight; checked, it rounds all the way on the spatial spring and fills on the effects spring.
class _ConnectedToggle extends StatefulWidget {
  const _ConnectedToggle({
    required this.label,
    required this.checked,
    required this.index,
    required this.count,
    required this.onTap,
  });

  final String label;
  final bool checked;
  final int index;
  final int count;
  final VoidCallback onTap;

  @override
  State<_ConnectedToggle> createState() => _ConnectedToggleState();
}

class _ConnectedToggleState extends State<_ConnectedToggle> with TickerProviderStateMixin {
  late final SpringValue _fill = SpringValue(
    vsync: this,
    initial: widget.checked ? 1 : 0,
    spring: SettingsSprings.effects,
  );
  late final SpringValue _round = SpringValue(
    vsync: this,
    initial: widget.checked ? 1 : 0,
    spring: SettingsSprings.spatial,
  );

  @override
  void didUpdateWidget(_ConnectedToggle old) {
    super.didUpdateWidget(old);
    if (old.checked == widget.checked) return;
    final target = widget.checked ? 1.0 : 0.0;
    if (MediaQuery.maybeDisableAnimationsOf(context) ?? false) {
      _fill.snapTo(target);
      _round.snapTo(target);
    } else {
      _fill.animateTo(target);
      _round.animateTo(target);
    }
  }

  @override
  void dispose() {
    _fill.dispose();
    _round.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    const outer = PlaylistBackupMetrics.filterOuterRadius;
    const inner = PlaylistBackupMetrics.filterInnerRadius;
    final first = widget.index == 0;
    final last = widget.index == widget.count - 1;
    return Semantics(
      selected: widget.checked,
      button: true,
      child: AnimatedBuilder(
        animation: Listenable.merge([_fill.animation, _round.animation]),
        builder: (context, child) {
          final f = _fill.value.clamp(0.0, 1.0);
          final r = _round.value.clamp(0.0, 1.0);
          double corner(bool outerSide) => outerSide ? outer : inner + (outer - inner) * r;
          final shape = RoundedRectangleBorder(
            borderRadius: BorderRadius.horizontal(
              left: Radius.circular(corner(first)),
              right: Radius.circular(corner(last)),
            ),
          );
          final content = Color.lerp(colors.onSurfaceVariant, colors.onPrimary, f)!;
          return Material(
            color: Color.lerp(colors.surfaceContainerHighest, colors.primary, f),
            shape: shape,
            clipBehavior: Clip.antiAlias,
            child: InkWell(
              onTap: widget.onTap,
              child: ConstrainedBox(
                constraints: const BoxConstraints(minHeight: PlaylistBackupMetrics.filterHeight),
                child: Center(
                  child: Text(
                    widget.label,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.labelLarge!.copyWith(color: content),
                  ),
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}

/// The review in one bar: matched, picked, to check and not found as rounded segments sized by their
/// share — they settle on the spatial spring as the user picks songs — with the counts under them.
class RestoreSummaryBar extends StatefulWidget {
  const RestoreSummaryBar({super.key, required this.summary});

  final ReviewSummary summary;

  @override
  State<RestoreSummaryBar> createState() => _RestoreSummaryBarState();
}

class _RestoreSummaryBarState extends State<RestoreSummaryBar> with TickerProviderStateMixin {
  late final List<SpringValue> _weights = [
    for (final c in _counts(widget.summary))
      SpringValue(vsync: this, initial: c.toDouble(), spring: SettingsSprings.spatial),
  ];

  static List<int> _counts(ReviewSummary s) => [s.matched, s.picked, s.toCheck, s.notFound];

  @override
  void didUpdateWidget(RestoreSummaryBar old) {
    super.didUpdateWidget(old);
    final reduce = MediaQuery.maybeDisableAnimationsOf(context) ?? false;
    for (final (i, c) in _counts(widget.summary).indexed) {
      reduce ? _weights[i].snapTo(c.toDouble()) : _weights[i].animateTo(c.toDouble());
    }
  }

  @override
  void dispose() {
    for (final w in _weights) {
      w.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final strings = PlaylistBackupStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final parts = [
      (colors.primary, strings.matched),
      (colors.secondary, strings.picked),
      (colors.tertiary, strings.toCheck),
      (colors.outlineVariant, strings.notFound),
    ];
    final counts = _counts(widget.summary);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SizedBox(
          height: PlaylistBackupMetrics.summaryBarHeight,
          child: AnimatedBuilder(
            animation: Listenable.merge([for (final w in _weights) w.animation]),
            builder: (context, _) => LayoutBuilder(
              builder: (context, constraints) {
                final weights = [for (final w in _weights) math.max(0.0, w.value)];
                final visible = [
                  for (var i = 0; i < weights.length; i++)
                    if (weights[i] > 0.01) i,
                ];
                final sum = visible.fold<double>(0, (a, i) => a + weights[i]);
                final room =
                    constraints.maxWidth - PlaylistBackupMetrics.summaryPartGap * math.max(0, visible.length - 1);
                return Row(
                  children: [
                    for (final (k, i) in visible.indexed) ...[
                      if (k > 0) const SizedBox(width: PlaylistBackupMetrics.summaryPartGap),
                      Container(
                        width: sum <= 0 ? 0 : math.max(0.0, room * weights[i] / sum),
                        decoration: ShapeDecoration(color: parts[i].$1, shape: const StadiumBorder()),
                      ),
                    ],
                  ],
                );
              },
            ),
          ),
        ),
        const SizedBox(height: PlaylistBackupMetrics.summaryGap),
        Wrap(
          spacing: PlaylistBackupMetrics.summaryLegendGap,
          children: [
            for (final (i, (color, label)) in parts.indexed)
              // Matched always; the rest only when there are some.
              if (counts[i] > 0 || i == 0)
                Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Container(
                      width: PlaylistBackupMetrics.summaryDot,
                      height: PlaylistBackupMetrics.summaryDot,
                      decoration: BoxDecoration(color: color, shape: BoxShape.circle),
                    ),
                    const SizedBox(width: PlaylistBackupMetrics.summaryDotGap),
                    Text('${counts[i]}', style: theme.textTheme.titleMedium!.copyWith(color: colors.onSurface)),
                    const SizedBox(width: PlaylistBackupMetrics.summaryDotGap),
                    Text(label, style: theme.textTheme.labelMedium!.copyWith(color: colors.onSurfaceVariant)),
                  ],
                ),
          ],
        ),
      ],
    );
  }
}

/// One song of the review, as a segment of the list: its mark, the backup's song, and what the target
/// offers. An uncertain row opens its candidates; a song not found offers a search on the target's service.
class _ReviewRowItem extends StatelessWidget {
  const _ReviewRowItem({
    required this.row,
    required this.track,
    required this.shape,
    required this.enabled,
    required this.expanded,
    required this.searchLabel,
    required this.onToggleExpanded,
    required this.onCheck,
    required this.onChoose,
    required this.onSearch,
  });

  final ReviewRow row;
  final BackupTrack track;
  final ShapeBorder shape;
  final bool enabled;
  final bool expanded;
  final String searchLabel;
  final VoidCallback onToggleExpanded;
  final ValueChanged<bool> onCheck;
  final ValueChanged<int> onChoose;
  final VoidCallback onSearch;

  @override
  Widget build(BuildContext context) {
    final strings = PlaylistBackupStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final uncertain = row.status == ReviewStatus.uncertain;
    final notFound = row.status == ReviewStatus.notFound;
    final choice = row.choice;
    return Material(
      color: colors.surfaceContainer,
      shape: shape,
      clipBehavior: Clip.antiAlias,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          InkWell(
            onTap: enabled && !notFound ? (uncertain ? onToggleExpanded : () => onCheck(!row.checked)) : null,
            child: ConstrainedBox(
              constraints: const BoxConstraints(minHeight: PlaylistBackupMetrics.reviewRowHeight),
              child: Padding(
                padding: const EdgeInsets.only(
                  left: PlaylistBackupMetrics.reviewRowStart,
                  right: PlaylistBackupMetrics.reviewRowEnd,
                  top: PlaylistBackupMetrics.reviewRowVertical,
                  bottom: PlaylistBackupMetrics.reviewRowVertical,
                ),
                child: Row(
                  children: [
                    ReviewMark(
                      status: row.status,
                      checked: row.checked,
                      enabled: enabled && row.checkable,
                      onToggle: () => onCheck(!row.checked),
                    ),
                    const SizedBox(width: PlaylistBackupMetrics.reviewRowGap),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          SongLines(title: track.title, artist: track.artists.join(', '), dimmed: notFound),
                          // What would be written, when it isn't plainly the same song.
                          if ((uncertain || row.status == ReviewStatus.picked) && choice != null)
                            Text(
                              choice.title ?? '',
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: theme.textTheme.bodyMedium!.copyWith(color: colors.primary),
                            ),
                        ],
                      ),
                    ),
                    const SizedBox(width: PlaylistBackupMetrics.reviewRowGap),
                    switch (row.status) {
                      ReviewStatus.matched =>
                        track.durationSec == null
                            ? const SizedBox.shrink()
                            : Text(
                                formatTrackDuration(track.durationSec),
                                style: theme.textTheme.labelLarge!.copyWith(color: colors.onSurfaceVariant),
                              ),
                      ReviewStatus.uncertain => _StatusPill(
                        text: strings.toCheck,
                        container: colors.tertiaryContainer,
                        content: colors.onTertiaryContainer,
                      ),
                      ReviewStatus.picked => _StatusPill(
                        text: strings.picked,
                        container: colors.secondaryContainer,
                        content: colors.onSecondaryContainer,
                      ),
                      ReviewStatus.notFound => Semantics(
                        label: searchLabel,
                        child: FilledButton.tonalIcon(
                          onPressed: enabled ? onSearch : null,
                          style: FilledButton.styleFrom(
                            shape: const StadiumBorder(),
                            padding: const EdgeInsets.symmetric(
                              horizontal: PlaylistBackupMetrics.searchButtonHorizontal,
                            ),
                          ),
                          icon: const Icon(YoinSymbols.search, size: PlaylistBackupMetrics.searchIcon),
                          label: Text(strings.searchHint),
                        ),
                      ),
                    },
                  ],
                ),
              ),
            ),
          ),
          SpringReveal(
            visible: uncertain && expanded,
            child: _Candidates(
              row: row,
              enabled: enabled,
              searchLabel: searchLabel,
              onChoose: onChoose,
              onSearch: onSearch,
            ),
          ),
        ],
      ),
    );
  }
}

class _StatusPill extends StatelessWidget {
  const _StatusPill({required this.text, required this.container, required this.content});

  final String text;
  final Color container;
  final Color content;

  @override
  Widget build(BuildContext context) => DecoratedBox(
    decoration: ShapeDecoration(color: container, shape: const StadiumBorder()),
    child: Padding(
      padding: const EdgeInsets.symmetric(
        horizontal: PlaylistBackupMetrics.statusPillHorizontal,
        vertical: PlaylistBackupMetrics.statusPillVertical,
      ),
      child: Text(text, maxLines: 1, style: Theme.of(context).textTheme.labelMedium!.copyWith(color: content)),
    ),
  );
}

/// The row's mark: checked = a filled 9-sided cookie with a tick, unchecked = an empty circle; the shape
/// morphs between them on the spatial spring and the colour rides the effects spring. Not found: a quiet
/// circle, no tick.
class ReviewMark extends StatefulWidget {
  const ReviewMark({
    super.key,
    required this.status,
    required this.checked,
    required this.enabled,
    required this.onToggle,
  });

  final ReviewStatus status;
  final bool checked;
  final bool enabled;
  final VoidCallback onToggle;

  @override
  State<ReviewMark> createState() => _ReviewMarkState();
}

class _ReviewMarkState extends State<ReviewMark> with TickerProviderStateMixin {
  late final SpringValue _shape = SpringValue(
    vsync: this,
    initial: widget.checked ? 1 : 0,
    spring: YoinSprings.defaultSpatial,
  );
  late final SpringValue _fill = SpringValue(
    vsync: this,
    initial: widget.checked ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );

  static final ShapeMorph _morph = ShapeMorph(MaterialShapes.circle, MaterialShapes.cookie9Sided);

  @override
  void didUpdateWidget(ReviewMark old) {
    super.didUpdateWidget(old);
    if (old.checked == widget.checked && old.status == widget.status) return;
    final target = widget.checked ? 1.0 : 0.0;
    if (MediaQuery.maybeDisableAnimationsOf(context) ?? false) {
      _shape.snapTo(target);
      _fill.snapTo(target);
    } else {
      _shape.animateTo(target);
      _fill.animateTo(target);
    }
  }

  @override
  void dispose() {
    _shape.dispose();
    _fill.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final strings = PlaylistBackupStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final rest = widget.status == ReviewStatus.uncertain ? colors.tertiaryContainer : colors.surfaceContainerHighest;
    return Semantics(
      label: strings.cdCheck,
      checked: widget.checked,
      enabled: widget.enabled,
      button: true,
      child: InkResponse(
        onTap: widget.enabled ? widget.onToggle : null,
        radius: PlaylistBackupMetrics.markTouch / 2,
        child: SizedBox.square(
          dimension: PlaylistBackupMetrics.markTouch,
          child: AnimatedBuilder(
            animation: Listenable.merge([_shape.animation, _fill.animation]),
            builder: (context, _) {
              final p = _shape.value;
              final tick = p.clamp(0.0, 1.0);
              return Stack(
                alignment: Alignment.center,
                children: [
                  SizedBox.square(
                    dimension: PlaylistBackupMetrics.markShape,
                    child: CustomPaint(
                      painter: _MarkPainter(
                        morph: _morph,
                        progress: p,
                        color: Color.lerp(rest, colors.primary, _fill.value.clamp(0.0, 1.0))!,
                      ),
                    ),
                  ),
                  if (widget.checked || tick > 0.01)
                    Transform.scale(
                      scale: tick,
                      child: Icon(YoinSymbols.check, size: PlaylistBackupMetrics.markTick, color: colors.onPrimary),
                    ),
                ],
              );
            },
          ),
        ),
      ),
    );
  }
}

class _MarkPainter extends CustomPainter {
  _MarkPainter({required this.morph, required this.progress, required this.color});

  final ShapeMorph morph;
  final double progress;
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    canvas.save();
    canvas.translate(size.width / 2, size.height / 2);
    canvas.rotate(PlaylistBackupMetrics.markTurnDegrees * progress * math.pi / 180);
    canvas.translate(-size.width / 2, -size.height / 2);
    canvas.scale(size.width, size.height);
    canvas.drawPath(morph.toPath(progress), Paint()..color = color);
    canvas.restore();
  }

  @override
  bool shouldRepaint(_MarkPainter old) => old.progress != progress || old.color != color;
}

/// An uncertain row's candidates, each a card to pick; the target's search under them.
class _Candidates extends StatelessWidget {
  const _Candidates({
    required this.row,
    required this.enabled,
    required this.searchLabel,
    required this.onChoose,
    required this.onSearch,
  });

  final ReviewRow row;
  final bool enabled;
  final String searchLabel;
  final ValueChanged<int> onChoose;
  final VoidCallback onSearch;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(
      left: PlaylistBackupMetrics.candidatesInset,
      right: PlaylistBackupMetrics.candidatesInset,
      bottom: PlaylistBackupMetrics.candidatesInset,
    ),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        for (final (i, candidate) in row.candidates.indexed) ...[
          if (i > 0) const SizedBox(height: PlaylistBackupMetrics.segmentGap),
          CandidateRow(
            candidate: candidate,
            selected: row.checked && row.chosen == i,
            enabled: enabled,
            shape: settingsSegmentShape(i, row.candidates.length),
            onTap: () => onChoose(i),
          ),
        ],
        Align(
          alignment: AlignmentDirectional.centerEnd,
          child: TextButton.icon(
            onPressed: enabled ? onSearch : null,
            icon: const Icon(YoinSymbols.search, size: PlaylistBackupMetrics.searchIcon),
            label: Text(searchLabel),
          ),
        ),
      ],
    ),
  );
}

/// One of the target's songs: title, artist and album, its length, a tick when it is the one to write.
class CandidateRow extends StatefulWidget {
  const CandidateRow({
    super.key,
    required this.candidate,
    required this.selected,
    required this.enabled,
    required this.shape,
    required this.onTap,
  });

  final Track candidate;
  final bool selected;
  final bool enabled;
  final ShapeBorder shape;
  final VoidCallback onTap;

  @override
  State<CandidateRow> createState() => _CandidateRowState();
}

class _CandidateRowState extends State<CandidateRow> with SingleTickerProviderStateMixin {
  late final SpringValue _on = SpringValue(
    vsync: this,
    initial: widget.selected ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );

  @override
  void didUpdateWidget(CandidateRow old) {
    super.didUpdateWidget(old);
    if (old.selected == widget.selected) return;
    final target = widget.selected ? 1.0 : 0.0;
    if (MediaQuery.maybeDisableAnimationsOf(context) ?? false) {
      _on.snapTo(target);
    } else {
      _on.animateTo(target);
    }
  }

  @override
  void dispose() {
    _on.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final candidate = widget.candidate;
    return Semantics(
      selected: widget.selected,
      child: AnimatedBuilder(
        animation: _on.animation,
        builder: (context, child) => Material(
          color: Color.lerp(colors.surfaceContainerHigh, colors.primaryContainer, _on.value.clamp(0.0, 1.0)),
          shape: widget.shape,
          clipBehavior: Clip.antiAlias,
          child: child,
        ),
        child: InkWell(
          onTap: widget.enabled ? widget.onTap : null,
          child: ConstrainedBox(
            constraints: const BoxConstraints(minHeight: PlaylistBackupMetrics.candidateHeight),
            child: Padding(
              padding: const EdgeInsets.symmetric(
                horizontal: PlaylistBackupMetrics.candidateHorizontal,
                vertical: PlaylistBackupMetrics.candidateVertical,
              ),
              child: Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        SongLines(title: candidate.title ?? '', artist: candidate.artist ?? ''),
                        if (candidate.album case final album?)
                          Text(
                            album,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: theme.textTheme.bodySmall!.copyWith(color: colors.onSurfaceVariant),
                          ),
                      ],
                    ),
                  ),
                  if (candidate.durationSec != null) ...[
                    const SizedBox(width: PlaylistBackupMetrics.candidateGap),
                    Text(
                      formatTrackDuration(candidate.durationSec),
                      style: theme.textTheme.labelLarge!.copyWith(color: colors.onSurfaceVariant),
                    ),
                  ],
                  if (widget.selected) ...[
                    const SizedBox(width: PlaylistBackupMetrics.candidateGap),
                    Icon(YoinSymbols.check, color: colors.primary),
                  ],
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

// ── Search on the target ───────────────────────────────────────────────

Future<void> _showSearchSheet(BuildContext context, String backupId) => showModalBottomSheet<void>(
  context: context,
  useSafeArea: false,
  isScrollControlled: true,
  showDragHandle: true,
  backgroundColor: Theme.of(context).colorScheme.surfaceContainerLow,
  builder: (_) => ChromeBarSuppression(child: _RestoreSearchSheet(backupId: backupId)),
);

/// The target service's own search for one song the match didn't settle: starts from the song's title
/// and first artist; a result picked becomes the row's song, checked.
class _RestoreSearchSheet extends ConsumerStatefulWidget {
  const _RestoreSearchSheet({required this.backupId});

  final String backupId;

  @override
  ConsumerState<_RestoreSearchSheet> createState() => _RestoreSearchSheetState();
}

class _RestoreSearchSheetState extends ConsumerState<_RestoreSearchSheet> {
  late final TextEditingController _query = TextEditingController(
    text: ref.read(playlistBackupPageProvider(widget.backupId)).search?.query ?? '',
  );

  late String _last = _query.text;

  @override
  void initState() {
    super.initState();
    _query.addListener(_onQuery);
  }

  void _onQuery() {
    if (_query.text == _last) return;
    _last = _query.text;
    ref.read(playlistBackupPageProvider(widget.backupId).notifier).runSearch(_query.text);
  }

  @override
  void dispose() {
    _query.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final provider = playlistBackupPageProvider(widget.backupId);
    final page = ref.watch(provider);
    final search = page.search;
    final review = page.restore;
    final backup = page.restoreBackup;
    if (search == null || review is! RestoreReviewing || backup == null) return const SizedBox.shrink();
    final track = search.position < backup.content.tracks.length ? backup.content.tracks[search.position] : null;
    return RestoreSearchContent(
      search: search,
      target: review.target,
      track: track,
      query: _query,
      onPick: (result) {
        YoinHaptics.confirm();
        ref.read(provider.notifier).pickSearchResult(result);
        Navigator.of(context).maybePop();
      },
    );
  }
}

class RestoreSearchContent extends StatelessWidget {
  const RestoreSearchContent({
    super.key,
    required this.search,
    required this.target,
    required this.track,
    required this.query,
    required this.onPick,
  });

  final RestoreSearchState search;
  final RestoreTarget target;
  final BackupTrack? track;
  final TextEditingController query;
  final ValueChanged<Track> onPick;

  @override
  Widget build(BuildContext context) {
    final strings = PlaylistBackupStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final serviceName = serviceOf(target.provider).name;
    final track = this.track;
    final height = MediaQuery.sizeOf(context).height * PlaylistBackupMetrics.searchSheetHeightFraction;
    final bottom = MediaQuery.paddingOf(context).bottom + MediaQuery.viewInsetsOf(context).bottom;
    return SizedBox(
      height: height,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.only(
              left: PlaylistBackupMetrics.sheetHorizontal,
              right: PlaylistBackupMetrics.sheetHorizontal,
              bottom: PlaylistBackupMetrics.reviewHeaderBottom,
            ),
            child: Row(
              children: [
                ServiceRowIcon(provider: target.provider),
                const SizedBox(width: PlaylistBackupMetrics.reviewHeaderGap),
                Expanded(
                  child: Semantics(
                    header: true,
                    child: Text(
                      strings.search(serviceName),
                      style: theme.textTheme.headlineSmall!.copyWith(color: colors.onSurface),
                    ),
                  ),
                ),
              ],
            ),
          ),
          // The song being looked for, from the backup.
          if (track != null)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: PlaylistBackupMetrics.sheetListHorizontal),
              child: DecoratedBox(
                decoration: ShapeDecoration(
                  color: colors.surfaceContainerHigh,
                  shape: RoundedSuperellipseBorder(
                    borderRadius: BorderRadius.circular(PlaylistBackupMetrics.searchCardRadius),
                  ),
                ),
                child: Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: PlaylistBackupMetrics.searchCardHorizontal,
                    vertical: PlaylistBackupMetrics.searchCardVertical,
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      SongLines(title: track.title, artist: track.artists.join(', ')),
                      if (track.durationSec != null)
                        Text(
                          formatTrackDuration(track.durationSec),
                          style: theme.textTheme.labelLarge!.copyWith(color: colors.onSurfaceVariant),
                        ),
                    ],
                  ),
                ),
              ),
            ),
          Padding(
            padding: const EdgeInsets.only(
              left: PlaylistBackupMetrics.sheetListHorizontal,
              right: PlaylistBackupMetrics.sheetListHorizontal,
              top: PlaylistBackupMetrics.searchFieldTop,
              bottom: PlaylistBackupMetrics.searchFieldBottom,
            ),
            child: ExpressiveTextField(
              controller: query,
              label: strings.searchHint,
              placeholder: serviceName,
              textInputAction: TextInputAction.search,
            ),
          ),
          Expanded(
            child: SpringCrossFade(
              childKey: search.loading ? 0 : (search.failed || search.results.isEmpty ? 1 : 2),
              child: search.loading
                  ? const Align(alignment: Alignment.center, child: YoinLoadingIndicator())
                  : search.failed || search.results.isEmpty
                  ? Align(
                      alignment: Alignment.topCenter,
                      child: Padding(
                        padding: const EdgeInsets.only(top: PlaylistBackupMetrics.searchEmptyTop),
                        child: Text(
                          search.failed ? strings.failed(target.displayName) : strings.searchNone,
                          style: theme.textTheme.bodyLarge!.copyWith(color: colors.onSurfaceVariant),
                        ),
                      ),
                    )
                  : ListView.builder(
                      padding: EdgeInsets.only(
                        left: PlaylistBackupMetrics.sheetListHorizontal,
                        right: PlaylistBackupMetrics.sheetListHorizontal,
                        top: PlaylistBackupMetrics.barTop,
                        bottom: PlaylistBackupMetrics.sheetBottom + bottom,
                      ),
                      itemCount: search.results.length,
                      itemBuilder: (context, i) {
                        final result = search.results[i];
                        return Padding(
                          key: ValueKey(result.id.toString()),
                          padding: EdgeInsets.only(top: i == 0 ? 0 : PlaylistBackupMetrics.segmentGap),
                          child: CandidateRow(
                            candidate: result,
                            selected: false,
                            enabled: true,
                            shape: settingsSegmentShape(i, search.results.length),
                            onTap: () => onPick(result),
                          ),
                        );
                      },
                    ),
            ),
          ),
        ],
      ),
    );
  }
}

// ── Outcome ────────────────────────────────────────────────────────────

/// What came of it: a burst that springs in with a tick, the playlist's place, and the counts as tiles.
class _Outcome extends StatefulWidget {
  const _Outcome({required this.target, required this.record, required this.onDone});

  final RestoreTarget target;
  final PlaylistRestoreRecord record;
  final VoidCallback onDone;

  @override
  State<_Outcome> createState() => _OutcomeState();
}

class _OutcomeState extends State<_Outcome> with SingleTickerProviderStateMixin {
  late final SpringValue _appear = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultSpatial);

  static final Path _burst = MaterialShapes.softBurst.toPath();
  static final Path _cookie4 = MaterialShapes.cookie4Sided.toPath();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      if (MediaQuery.maybeDisableAnimationsOf(context) ?? false) {
        _appear.snapTo(1);
      } else {
        _appear.animateTo(1);
      }
    });
  }

  @override
  void dispose() {
    _appear.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final strings = PlaylistBackupStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final record = widget.record;
    final failed = record.failed;
    return Padding(
      padding: EdgeInsets.only(
        left: PlaylistBackupMetrics.sheetHorizontal,
        right: PlaylistBackupMetrics.sheetHorizontal,
        top: PlaylistBackupMetrics.outcomeTop,
        bottom: PlaylistBackupMetrics.sheetBottom + MediaQuery.paddingOf(context).bottom,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Center(
            child: AnimatedBuilder(
              animation: _appear.animation,
              builder: (context, child) => Transform.rotate(
                angle: (1 - _appear.value) * PlaylistBackupMetrics.outcomeTurnDegrees * math.pi / 180,
                child: Transform.scale(scale: math.max(0.0, _appear.value), child: child),
              ),
              child: Container(
                width: PlaylistBackupMetrics.outcomeMark,
                height: PlaylistBackupMetrics.outcomeMark,
                decoration: ShapeDecoration(
                  color: failed ? colors.errorContainer : colors.primaryContainer,
                  shape: PolygonBorder(failed ? _cookie4 : _burst),
                ),
                child: Icon(
                  failed ? YoinSymbols.close : YoinSymbols.check,
                  size: PlaylistBackupMetrics.outcomeGlyph,
                  color: failed ? colors.onErrorContainer : colors.onPrimaryContainer,
                ),
              ),
            ),
          ),
          const SizedBox(height: PlaylistBackupMetrics.outcomeGap),
          Semantics(
            header: true,
            child: Text(
              failed ? strings.notCreated(widget.target.displayName) : strings.done(widget.target.displayName),
              textAlign: TextAlign.center,
              style: theme.textTheme.headlineSmall!.copyWith(color: colors.onSurface),
            ),
          ),
          const SizedBox(height: PlaylistBackupMetrics.outcomeGap),
          Row(
            children: [
              _CountTile(
                count: record.added,
                label: strings.added,
                container: colors.primaryContainer,
                content: colors.onPrimaryContainer,
              ),
              const SizedBox(width: PlaylistBackupMetrics.countTileGap),
              _CountTile(
                count: record.skipped,
                label: strings.skipped,
                container: colors.surfaceContainerHigh,
                content: colors.onSurface,
              ),
              const SizedBox(width: PlaylistBackupMetrics.countTileGap),
              _CountTile(
                count: record.notFound,
                label: strings.notFound,
                container: colors.surfaceContainerHigh,
                content: colors.onSurface,
              ),
            ],
          ),
          const SizedBox(height: PlaylistBackupMetrics.outcomeGap),
          FilledButton(
            onPressed: widget.onDone,
            style: FilledButton.styleFrom(
              minimumSize: const Size.fromHeight(PlaylistBackupMetrics.barButtonHeight),
              shape: const StadiumBorder(),
              textStyle: theme.textTheme.titleMedium,
            ),
            child: Text(strings.doneButton),
          ),
        ],
      ),
    );
  }
}

class _CountTile extends StatelessWidget {
  const _CountTile({required this.count, required this.label, required this.container, required this.content});

  final int count;
  final String label;
  final Color container;
  final Color content;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Expanded(
      child: DecoratedBox(
        decoration: ShapeDecoration(
          color: container,
          shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(PlaylistBackupMetrics.countTileRadius)),
        ),
        child: Padding(
          padding: const EdgeInsets.all(PlaylistBackupMetrics.countTilePadding),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('$count', style: theme.textTheme.displaySmall!.copyWith(color: content)),
              Text(
                label,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: theme.textTheme.labelLarge!.copyWith(
                  color: content.withValues(alpha: PlaylistBackupMetrics.countTileLabelAlpha),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _Failure extends StatelessWidget {
  const _Failure({required this.target, required this.onDone});

  final RestoreTarget target;
  final VoidCallback onDone;

  @override
  Widget build(BuildContext context) {
    final strings = PlaylistBackupStrings.of(context);
    return Padding(
      padding: EdgeInsets.only(bottom: PlaylistBackupMetrics.sheetTitleVertical + MediaQuery.paddingOf(context).bottom),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _SheetTitle(strings.failed(target.displayName)),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: PlaylistBackupMetrics.sheetListHorizontal),
            child: Align(
              alignment: AlignmentDirectional.centerEnd,
              child: TextButton(onPressed: onDone, child: Text(strings.doneButton)),
            ),
          ),
        ],
      ),
    );
  }
}
