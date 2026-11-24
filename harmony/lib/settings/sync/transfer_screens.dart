/// Transfer Memories (Android ui/settings/sync/TransferScreens.kt
/// TransferFlow): one page that walks source → target → summary → matching →
/// review → copy → done, with "Step n of m" over each step.
///
/// Back surface: PushPage ([PortabilityPage]). Back steps back inside the flow
/// (the first step closes it); while matching or copying it only says
/// "Working on it…"; on Done it finishes (removing the original's card when
/// asked). Cancel during matching or the review asks first: nothing has been
/// written yet.
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../../data/portability/backup_format.dart';
import '../../data/portability/memories/memory_matching.dart' show TrackMatch;
import '../../data/sync/cloud_data_views.dart' show CloudScopeCounts;
import '../../data/transfer/restore_review.dart';
import '../../nowplaying/haptics.dart';
import '../../shell/app_messages.dart';
import '../../theme/motion.dart';
import '../../ui/component/meta_line.dart' show MetaStat, formatTrackDuration;
import '../../ui/component/spring_cross_fade.dart';
import '../../ui/component/yoin_dialog.dart';
import '../../ui/navigation/yoin_push_route.dart';
import '../backup/playlist_backup_metrics.dart';
import '../backup/playlist_backup_widgets.dart' show SongLines;
import '../backup/playlist_restore_sheet.dart' show CandidateRow, ConnectedToggle, RestoreSummaryBar, ReviewMark;
import '../settings_components.dart';
import '../settings_metrics.dart';
import '../settings_rows.dart';
import '../settings_screen.dart' show openAddAccount;
import 'backup_view_model.dart' show BackupReadFailure;
import 'cloud_account_page.dart' show SyncNote, countsSentence, scopeStatGroups;
import 'cloud_sync_strings.dart';
import 'portability_metrics.dart';
import 'portability_strings.dart';
import 'portability_widgets.dart';
import 'transfer_view_model.dart';

/// Opens Transfer Memories, preset by [args] (the account's cloud data page names its source).
Future<void> openTransfer(BuildContext context, TransferArgs args) =>
    Navigator.of(context).push(YoinPushRoute<void>(builder: (_) => TransferScreen(args: args)));

class TransferScreen extends ConsumerWidget {
  const TransferScreen({super.key, this.args = const TransferArgs()});

  final TransferArgs args;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final s = PortabilityStrings.of(context);
    final provider = transferProvider(args);
    final state = ref.watch(provider);
    final vm = ref.read(provider.notifier);
    ref.listen(provider.select((st) => st.notice), (_, notice) {
      if (notice == null) return;
      ref.read(appMessageSinkProvider)(switch (notice) {
        TransferBusyNotice() => s.busy,
        TransferCleanedNotice(:final sourceName, :final ok) => ok ? s.cleaned(sourceName) : s.removeFailed(sourceName),
        TransferBackupNotice(:final reason) => switch (reason) {
          BackupReadFailure.notABackup => s.readNotBackup,
          BackupReadFailure.tooNew => s.readTooNew,
          BackupReadFailure.checksum => s.readChecksum,
          BackupReadFailure.unreadable => s.readFailed,
        },
      });
    });
    ref.listen(provider.select((st) => st.finished), (_, finished) {
      if (finished == null) return;
      switch (finished) {
        case TransferCopied(:final sourceName):
          ref.read(appMessageSinkProvider)(s.finished(sourceName));
        case TransferCardRemoved(:final sourceName):
          ref.read(appMessageSinkProvider)(s.cardRemoved(sourceName));
        case TransferClosed():
          break;
      }
      if (ModalRoute.of(context)?.isActive ?? false) Navigator.of(context).pop();
    });
    final step = state.step;
    final footer = _footer(context, s, state, vm);
    return PortabilityPage(
      barTitle: step == TransferStep.done ? s.doneBar : s.transferTitle,
      ownsBack: () => ref.read(provider.notifier).handlesBack,
      onBack: () => ref.read(provider.notifier).back(),
      footer: footer == null ? null : SpringCrossFade(childKey: footer.key!, fit: StackFit.loose, child: footer),
      children: [
        SpringCrossFade(
          childKey: step,
          fit: StackFit.loose,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (step != TransferStep.done && step != TransferStep.failed && step != TransferStep.loading)
                _StepBar(state: state, step: step),
              _stepContent(context, s, state, vm),
            ],
          ),
        ),
      ],
    );
  }

  Widget? _footer(BuildContext context, PortabilityStrings s, TransferUiState state, TransferNotifier vm) {
    switch (state.step) {
      case TransferStep.source:
        return FlowButtons(
          key: const ValueKey('xfer_next_source'),
          primary: s.next,
          onPrimary: vm.next,
          primaryEnabled: state.sourceKey != null,
        );
      case TransferStep.target:
        return FlowButtons(
          key: const ValueKey('xfer_next_target'),
          primary: s.next,
          onPrimary: vm.next,
          primaryEnabled: state.targetKey != null,
        );
      case TransferStep.summary:
        final ready = state.draft != null && !state.draftLoading;
        if (ready && state.remainingItems == 0) {
          return FlowButtons(
            key: const ValueKey('xfer_nothing'),
            primary: s.nothingNew,
            onPrimary: () {},
            primaryEnabled: false,
          );
        }
        return FlowButtons(
          key: const ValueKey('xfer_start'),
          primary: state.needsMatching ? s.startMatch : s.startCopy,
          onPrimary: vm.start,
          primaryEnabled: ready,
          badge: ready ? state.remainingItems : null,
        );
      case TransferStep.matching:
        return Row(
          key: const ValueKey('xfer_matching'),
          children: [
            const Spacer(),
            SizedBox(
              height: PortabilityMetrics.buttonHeight,
              child: TextButton(onPressed: () => _confirmCancel(context, s, vm), child: Text(s.cancel)),
            ),
          ],
        );
      case TransferStep.review:
        final toCopy = state.songsToCopy;
        return FlowButtons(
          key: const ValueKey('xfer_review'),
          primary: s.startCopy,
          onPrimary: vm.copyReviewed,
          primaryEnabled: state.canCopyReviewed,
          badge: toCopy > 0 ? toCopy : null,
          secondary: s.cancel,
          onSecondary: () => _confirmCancel(context, s, vm),
        );
      case TransferStep.done:
        return FlowButtons(
          key: const ValueKey('xfer_done'),
          primary: s.done,
          onPrimary: vm.finish,
          primaryEnabled: !state.finishing,
        );
      case TransferStep.failed:
        return FlowButtons(key: const ValueKey('xfer_failed'), primary: s.done, onPrimary: vm.cancel);
      case TransferStep.loading || TransferStep.copying:
        return null;
    }
  }

  Future<void> _confirmCancel(BuildContext context, PortabilityStrings s, TransferNotifier vm) async {
    final cancel = await showYoinDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(s.cancelTitle),
        content: Text(s.cancelBody),
        actions: [
          TextButton(onPressed: () => Navigator.of(context).pop(false), child: Text(s.cancelKeep)),
          TextButton(
            style: TextButton.styleFrom(foregroundColor: Theme.of(context).colorScheme.error),
            onPressed: () {
              YoinHaptics.reject();
              Navigator.of(context).pop(true);
            },
            child: Text(s.cancelConfirm),
          ),
        ],
      ),
    );
    if (cancel == true) vm.cancel();
  }

  Widget _stepContent(BuildContext context, PortabilityStrings s, TransferUiState state, TransferNotifier vm) {
    final source = state.source;
    final target = state.target;
    switch (state.step) {
      case TransferStep.loading:
        return const ProgressHero();
      case TransferStep.source:
        return _SourceStep(state: state, vm: vm);
      case TransferStep.target:
        return _TargetStep(state: state, vm: vm);
      case TransferStep.summary:
        return _SummaryStep(state: state);
      case TransferStep.matching:
        return ProgressHero(
          progress: state.progressTotal > 0 ? state.progressDone / state.progressTotal : 0,
          count: s.progress(state.progressDone, state.progressTotal),
          caption: _ServiceLine(
            provider: target?.account.provider ?? '',
            text: s.matchingOn(serviceNameOf(s, target?.account.provider)),
          ),
        );
      case TransferStep.review:
        return _ReviewStep(state: state, vm: vm);
      case TransferStep.copying:
        return ProgressHero(
          caption: _ServiceLine(
            provider: target?.account.provider ?? '',
            text: s.copying(target?.account.displayName ?? ''),
          ),
        );
      case TransferStep.done:
        return _DoneStep(state: state, vm: vm);
      case TransferStep.failed:
        return OutcomeHero(
          icon: YoinSymbols.close,
          title: switch (state.failure) {
            TransferFailure.readFailed => s.xferReadFailed(source?.account.displayName ?? ''),
            TransferFailure.targetUnreachable => s.targetUnreachable(target?.account.displayName ?? ''),
            _ => s.runFailed,
          },
          failed: true,
        );
    }
  }
}

/// Progress segments and "Step n of m: name".
class _StepBar extends StatelessWidget {
  const _StepBar({required this.state, required this.step});

  final TransferUiState state;
  final TransferStep step;

  @override
  Widget build(BuildContext context) {
    final s = PortabilityStrings.of(context);
    final theme = Theme.of(context);
    final index = state.stepIndexOf(step);
    final count = state.stepCount;
    final names = [s.stepSource, s.stepTarget, s.stepSummary, if (count == 5) s.stepReview, s.stepCopy];
    return Padding(
      padding: const EdgeInsets.only(
        left: PortabilityMetrics.headInset,
        right: PortabilityMetrics.headInset,
        top: PortabilityMetrics.stepTop,
        bottom: PortabilityMetrics.stepBottom,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              for (var i = 0; i < count; i++) ...[
                if (i > 0) const SizedBox(width: PortabilityMetrics.stepSegmentGap),
                Expanded(child: _StepSegment(on: i <= index)),
              ],
            ],
          ),
          const SizedBox(height: PortabilityMetrics.stepGap),
          Text(
            s.step(index + 1, count, names[index.clamp(0, names.length - 1)]),
            style: theme.textTheme.labelLarge!.copyWith(color: theme.colorScheme.onSurfaceVariant),
          ),
        ],
      ),
    );
  }
}

class _StepSegment extends StatefulWidget {
  const _StepSegment({required this.on});

  final bool on;

  @override
  State<_StepSegment> createState() => _StepSegmentState();
}

class _StepSegmentState extends State<_StepSegment> with SingleTickerProviderStateMixin {
  late final SpringValue _on = SpringValue(vsync: this, initial: widget.on ? 1 : 0, spring: YoinSprings.defaultEffects);

  @override
  void didUpdateWidget(_StepSegment old) {
    super.didUpdateWidget(old);
    if (MediaQuery.maybeDisableAnimationsOf(context) ?? false) {
      _on.snapTo(widget.on ? 1 : 0);
    } else {
      _on.animateTo(widget.on ? 1 : 0);
    }
  }

  @override
  void dispose() {
    _on.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return AnimatedBuilder(
      animation: _on.animation,
      builder: (context, _) => Container(
        height: PortabilityMetrics.stepSegmentHeight,
        decoration: ShapeDecoration(
          color: Color.lerp(colors.surfaceContainerHighest, colors.primary, _on.value.clamp(0.0, 1.0)),
          shape: const StadiumBorder(),
        ),
      ),
    );
  }
}

class _Groups extends StatelessWidget {
  const _Groups({required this.children, this.gap = PortabilityMetrics.groupGap});

  final List<Widget> children;
  final double gap;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      for (final (i, c) in children.indexed) ...[if (i > 0) SizedBox(height: gap), c],
    ],
  );
}

class _SourceStep extends StatelessWidget {
  const _SourceStep({required this.state, required this.vm});

  final TransferUiState state;
  final TransferNotifier vm;

  @override
  Widget build(BuildContext context) {
    final s = PortabilityStrings.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        FlowHeadline(headline: s.headSource),
        SettingsGroup(
          title: s.sourceGroup,
          children: [
            for (final account in state.sources)
              AccountPick(
                key: ValueKey('xfer_source_${account.key}'),
                account: account,
                selected: state.sourceKey == account.key,
                onTap: () => vm.pickSource(account.key),
              ),
            // A backup file's accounts can be copied from too (a Spotify account kept in a backup, say).
            SettingsItem(
              key: const ValueKey('xfer_source_file'),
              icon: YoinSymbols.folder,
              title: s.sectionBackupFile,
              summary: s.importSummary,
              trailing: Icon(YoinSymbols.chevronRight, color: Theme.of(context).colorScheme.onSurfaceVariant),
              onTap: () {
                YoinHaptics.click();
                vm.pickBackup();
              },
            ),
          ],
        ),
      ],
    );
  }
}

class _TargetStep extends ConsumerWidget {
  const _TargetStep({required this.state, required this.vm});

  final TransferUiState state;
  final TransferNotifier vm;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final s = PortabilityStrings.of(context);
    final theme = Theme.of(context);
    final source = state.source;
    if (source == null) return const SizedBox.shrink();
    final stats = source.counts == null
        ? const <Never>[]
        : scopeStatGroups(CloudSyncStrings.of(context), source.counts!);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        FlowHeadline(headline: s.headTarget),
        _Groups(
          children: [
            SettingsSegments(
              children: [
                SettingsItem(
                  leading: AccountGlyph(provider: source.account.provider),
                  title: s.from(source.account.displayName),
                  summaryContent: stats.isEmpty ? null : MetaFlow(groups: stats, style: theme.textTheme.bodyMedium),
                  onTap: () {
                    YoinHaptics.click();
                    vm.changeSource();
                  },
                  trailing: Text(
                    s.change,
                    style: theme.textTheme.labelLarge!.copyWith(color: theme.colorScheme.primary),
                  ),
                ),
              ],
            ),
            _Groups(
              gap: PortabilityMetrics.noteGap,
              children: [
                SettingsGroup(
                  title: s.targetGroup,
                  children: [
                    for (final account in state.targets)
                      AccountPick(
                        key: ValueKey('xfer_target_${account.key}'),
                        account: account,
                        selected: state.targetKey == account.key,
                        badge: transferKindOf(source.account, account.account),
                        onTap: () => vm.pickTarget(account.key),
                      ),
                    Builder(
                      builder: (anchor) => SettingsItem(
                        icon: YoinSymbols.personAdd,
                        title: s.addAccount,
                        summary: s.addAccountSummary,
                        onTap: () async {
                          await openAddAccount(anchor);
                          await vm.refreshAccounts();
                        },
                      ),
                    ),
                  ],
                ),
                if (state.targets.isEmpty) SyncNote(icon: YoinSymbols.info, text: s.noTargets),
                if (source.cloudOnly) SyncNote(icon: YoinSymbols.info, text: s.cloudNote(source.account.displayName)),
              ],
            ),
          ],
        ),
      ],
    );
  }
}

/// A radio row for an account (AccountPick): glyph, name with its pill ("Cloud only", or the pair's
/// badge), service line, counts and the radio; the row takes secondaryContainer when chosen.
class AccountPick extends StatefulWidget {
  const AccountPick({super.key, required this.account, required this.selected, required this.onTap, this.badge});

  final TransferAccountUi account;
  final bool selected;
  final VoidCallback onTap;
  final TransferKind? badge;

  @override
  State<AccountPick> createState() => _AccountPickState();
}

class _AccountPickState extends State<AccountPick> with SingleTickerProviderStateMixin {
  late final SpringValue _on = SpringValue(
    vsync: this,
    initial: widget.selected ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );

  @override
  void didUpdateWidget(AccountPick old) {
    super.didUpdateWidget(old);
    if (old.selected == widget.selected) return;
    if (MediaQuery.maybeDisableAnimationsOf(context) ?? false) {
      _on.snapTo(widget.selected ? 1 : 0);
    } else {
      _on.animateTo(widget.selected ? 1 : 0);
    }
  }

  @override
  void dispose() {
    _on.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final s = PortabilityStrings.of(context);
    final cloud = CloudSyncStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final account = widget.account;
    final a = account.account;
    final line = flowAccountLine(
      s,
      a.provider,
      a.displayName,
      account.hint,
      fromDevice: account.fromDevice,
      fromLabel: cloud.fromDevice,
    );
    final counts = account.counts;
    final stats = counts == null ? null : scopeStatGroups(cloud, counts);
    final pill = account.cloudOnly
        ? FlowPill(text: s.cloudOnly)
        : switch (widget.badge) {
            TransferKind.direct => FlowPill(text: s.badgeDirect, tone: FlowPillTone.primary),
            TransferKind.partial => FlowPill(text: s.badgePartial),
            TransferKind.match => FlowPill(text: s.badgeMatch, tone: FlowPillTone.muted),
            null => null,
          };
    return Semantics(
      inMutuallyExclusiveGroup: true,
      checked: widget.selected,
      child: AnimatedBuilder(
        animation: _on.animation,
        builder: (context, child) => ColoredBox(
          color: colors.secondaryContainer.withValues(alpha: _on.value.clamp(0.0, 1.0)),
          child: child,
        ),
        child: InkWell(
          onTap: () {
            YoinHaptics.tick();
            widget.onTap();
          },
          child: ConstrainedBox(
            constraints: const BoxConstraints(minHeight: SettingsMetrics.rowMinHeight),
            child: Padding(
              padding: const EdgeInsets.symmetric(
                horizontal: SettingsMetrics.rowPaddingHorizontal,
                vertical: SettingsMetrics.rowPaddingVertical,
              ),
              child: Row(
                children: [
                  AccountGlyph(provider: a.provider),
                  const SizedBox(width: SettingsMetrics.rowIconGap),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        TitleWithPill(title: a.displayName, pill: pill),
                        if (line.isNotEmpty) ...[
                          const SizedBox(height: SettingsMetrics.rowTextGap),
                          MetaFlow(groups: line, style: theme.textTheme.bodyMedium),
                        ],
                        if (stats != null) ...[
                          const SizedBox(height: SettingsMetrics.rowTextGap),
                          stats.isEmpty
                              ? Text(
                                  s.noData,
                                  style: theme.textTheme.bodySmall!.copyWith(
                                    color: colors.onSurfaceVariant.withValues(alpha: PortabilityMetrics.mutedAlpha),
                                  ),
                                )
                              : MetaFlow(groups: stats, style: theme.textTheme.bodySmall),
                        ],
                      ],
                    ),
                  ),
                  const SizedBox(width: SettingsMetrics.rowTrailingGap),
                  _RadioDot(on: _on),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// The radio: an outline ring that turns primary with a dot in it, on the effects spring.
class _RadioDot extends StatelessWidget {
  const _RadioDot({required this.on});

  final SpringValue on;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return AnimatedBuilder(
      animation: on.animation,
      builder: (context, _) {
        final v = on.value.clamp(0.0, 1.0);
        return Container(
          width: PortabilityMetrics.radio,
          height: PortabilityMetrics.radio,
          padding: const EdgeInsets.all(PortabilityMetrics.radioRing),
          decoration: BoxDecoration(color: Color.lerp(colors.outline, colors.primary, v), shape: BoxShape.circle),
          child: Container(
            padding: const EdgeInsets.all(PortabilityMetrics.radioDot),
            decoration: BoxDecoration(
              color: Color.lerp(colors.surfaceBright, colors.secondaryContainer, v),
              shape: BoxShape.circle,
            ),
            child: DecoratedBox(
              decoration: BoxDecoration(
                color: colors.primary.withValues(alpha: v),
                shape: BoxShape.circle,
              ),
            ),
          ),
        );
      },
    );
  }
}

/// A service's glyph and a line about it (ServiceLine).
class _ServiceLine extends StatelessWidget {
  const _ServiceLine({required this.provider, required this.text});

  final String provider;
  final String text;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        AccountGlyph(provider: provider),
        const SizedBox(width: PortabilityMetrics.serviceGap),
        Flexible(
          child: Text(
            text,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: theme.textTheme.titleMedium!.copyWith(color: theme.colorScheme.onSurfaceVariant),
          ),
        ),
      ],
    );
  }
}

class _SummaryStep extends StatelessWidget {
  const _SummaryStep({required this.state});

  final TransferUiState state;

  @override
  Widget build(BuildContext context) {
    final s = PortabilityStrings.of(context);
    final theme = Theme.of(context);
    final source = state.source;
    final target = state.target;
    final draft = state.draft;
    if (source == null || target == null) return const SizedBox.shrink();
    final head = FlowHeadline(headline: s.summaryTitle);
    if (draft == null) {
      return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [head, const ProgressHero()]);
    }
    final items = draft.items;
    final albums = {for (final r in items.albumRatings) r.album, for (final r in items.albumReviews) r.album}.length;
    final kind = !state.needsMatching
        ? TransferKind.direct
        : state.directSongs > 0
        ? TransferKind.partial
        : TransferKind.match;
    Widget countRow(IconData icon, String title, int count, String unit, {String? summary}) => SettingsItem(
      icon: icon,
      title: title,
      summary: summary,
      trailing: MetaFlow(groups: [MetaStat('$count', unit)], style: theme.textTheme.bodyLarge),
    );
    final aiTitles = items.memoryTitles.length > items.memoryCopies.length
        ? items.memoryTitles.length
        : items.memoryCopies.length;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        head,
        _Groups(
          children: [
            _PairCard(source: source, target: target),
            if (draft.previouslyCopied > 0)
              _SkipBanner(previous: draft.previouslyCopied, remaining: state.remainingItems),
            Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                SettingsGroupLabel(title: s.what, trailingLabel: s.total(state.totalItems)),
                const SizedBox(height: SettingsMetrics.groupLabelGap),
                SettingsSegments(
                  children: [
                    countRow(
                      YoinSymbols.note,
                      s.rowNotes,
                      items.songNotes.length + items.albumNotes.length,
                      s.unitCount(items.songNotes.length + items.albumNotes.length),
                    ),
                    countRow(
                      YoinSymbols.star,
                      s.rowSongRatings,
                      items.trackRatings.length,
                      s.unitCount(items.trackRatings.length),
                    ),
                    countRow(YoinSymbols.reviews, s.rowAlbums, albums, s.unitCount(albums)),
                    countRow(
                      YoinSymbols.edit,
                      s.rowCustomTitles,
                      items.albumTitles.length,
                      s.unitTitleCount(items.albumTitles.length),
                    ),
                    countRow(YoinSymbols.sparkle, s.rowAiTitles, aiTitles, s.unitTitleCount(aiTitles)),
                    countRow(
                      YoinSymbols.album,
                      s.rowLinks,
                      items.tracks.length,
                      s.unitSongs(items.tracks.length),
                      summary: s.rowLinksSummary,
                    ),
                  ],
                ),
              ],
            ),
            SettingsGroup(
              title: s.matching,
              children: [
                switch (kind) {
                  TransferKind.direct => SettingsItem(
                    icon: YoinSymbols.checkCircle,
                    title: s.methodDirect,
                    summary: s.methodDirectSummary(serviceNameOf(s, source.account.provider)),
                  ),
                  TransferKind.partial => SettingsItem(
                    icon: YoinSymbols.checkCircle,
                    title: s.methodPartial,
                    summaryContent: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        MetaFlow(
                          style: theme.textTheme.bodyMedium,
                          groups: [
                            MetaStat('${state.directSongs}', s.directSongs),
                            MetaStat('${draft.tracksToMatch.length}', s.matchSongs),
                          ],
                        ),
                        Text(
                          s.methodPartialSummary,
                          style: theme.textTheme.bodyMedium!.copyWith(color: theme.colorScheme.onSurfaceVariant),
                        ),
                      ],
                    ),
                  ),
                  TransferKind.match => SettingsItem(
                    icon: YoinSymbols.search,
                    title: s.methodMatch,
                    summary: s.methodMatchSummary(source.account.displayName),
                  ),
                },
              ],
            ),
            _Groups(
              gap: PortabilityMetrics.noteGap,
              children: [
                SettingsGroup(
                  title: s.duplicates,
                  children: [
                    SettingsItem(icon: YoinSymbols.note, title: s.dupNotes, summary: s.dupNotesSummary),
                    SettingsItem(icon: YoinSymbols.star, title: s.dupRatings),
                    SettingsItem(
                      icon: YoinSymbols.reviews,
                      title: s.dupReviews,
                      summary: s.dupReviewsSummary(source.account.displayName),
                    ),
                  ],
                ),
                SyncNote(icon: YoinSymbols.info, text: s.copyOnly(source.account.displayName)),
              ],
            ),
          ],
        ),
      ],
    );
  }
}

/// Source → target, each a large glyph over its name and service (PairCard).
class _PairCard extends StatelessWidget {
  const _PairCard({required this.source, required this.target});

  final TransferAccountUi source;
  final TransferAccountUi target;

  @override
  Widget build(BuildContext context) => SettingsSegments(
    children: [
      Padding(
        padding: const EdgeInsets.symmetric(
          horizontal: SettingsMetrics.rowPaddingHorizontal,
          vertical: PortabilityMetrics.pairVertical,
        ),
        child: Row(
          children: [
            Expanded(child: _PairSide(account: source)),
            Icon(YoinSymbols.forward, size: PortabilityMetrics.pairArrow, color: Theme.of(context).colorScheme.primary),
            Expanded(child: _PairSide(account: target)),
          ],
        ),
      ),
    ],
  );
}

class _PairSide extends StatelessWidget {
  const _PairSide({required this.account});

  final TransferAccountUi account;

  @override
  Widget build(BuildContext context) {
    final s = PortabilityStrings.of(context);
    final theme = Theme.of(context);
    final a = account.account;
    final service = serviceNameOf(s, a.provider);
    // No "Apple Music / Apple Music": the service only when the name doesn't already say it.
    final subtitle = account.cloudOnly
        ? s.cloudOnly
        : account.hint ?? (a.displayName.toLowerCase() == service.toLowerCase() ? null : service);
    return Column(
      children: [
        AccountGlyph(provider: a.provider, size: PortabilityMetrics.pairGlyph),
        const SizedBox(height: PortabilityMetrics.pairLineGap),
        Text(
          a.displayName,
          textAlign: TextAlign.center,
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          style: theme.textTheme.titleMedium!.copyWith(color: theme.colorScheme.onSurface),
        ),
        if (subtitle != null && subtitle.isNotEmpty) ...[
          const SizedBox(height: PortabilityMetrics.pairLineGap),
          Text(
            subtitle,
            textAlign: TextAlign.center,
            style: theme.textTheme.bodySmall!.copyWith(color: theme.colorScheme.onSurfaceVariant),
          ),
        ],
      ],
    );
  }
}

/// "Skipping 12 items already transferred" (SkipBanner).
class _SkipBanner extends StatelessWidget {
  const _SkipBanner({required this.previous, required this.remaining});

  final int previous;
  final int remaining;

  @override
  Widget build(BuildContext context) {
    final s = PortabilityStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return DecoratedBox(
      decoration: ShapeDecoration(
        color: colors.secondaryContainer,
        shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(PortabilityMetrics.bannerRadius)),
      ),
      child: Padding(
        padding: const EdgeInsets.all(PortabilityMetrics.bannerPadding),
        child: Row(
          children: [
            Icon(YoinSymbols.history, color: colors.onSecondaryContainer),
            const SizedBox(width: PortabilityMetrics.bannerGap),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    s.skipTitle(previous),
                    style: theme.textTheme.titleMedium!.copyWith(color: colors.onSecondaryContainer),
                  ),
                  Text(
                    remaining > 0 ? s.skipSome : s.skipAll,
                    style: theme.textTheme.bodyMedium!.copyWith(color: colors.onSecondaryContainer),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

enum _Filter { all, toCheck, notFound }

class _ReviewStep extends StatefulWidget {
  const _ReviewStep({required this.state, required this.vm});

  final TransferUiState state;
  final TransferNotifier vm;

  @override
  State<_ReviewStep> createState() => _ReviewStepState();
}

class _ReviewStepState extends State<_ReviewStep> {
  var _filter = _Filter.all;
  final _open = <int>{};

  @override
  Widget build(BuildContext context) {
    final s = PortabilityStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final state = widget.state;
    final source = state.source;
    final target = state.target;
    if (source == null || target == null) return const SizedBox.shrink();
    final rows = state.rows;
    final summary = RestoreReview.summary(rows);
    final shown = switch (_filter) {
      _Filter.all => rows,
      _Filter.toCheck => [
        for (final r in rows)
          if (r.status == ReviewStatus.uncertain) r,
      ],
      _Filter.notFound => [
        for (final r in rows)
          if (r.status == ReviewStatus.notFound) r,
      ],
    };
    final filters = [
      (_Filter.all, s.filterAll, rows.length),
      (_Filter.toCheck, s.filterToCheck, summary.toCheck),
      (_Filter.notFound, s.filterNotFound, summary.notFound),
    ];
    final albums = state.albumMatches.length + (state.draft?.reusedAlbums.length ?? 0);
    final albumsMatched =
        state.albumMatches.where((a) => a.status.copies).length + (state.draft?.reusedAlbums.length ?? 0);
    final inset = const EdgeInsets.symmetric(horizontal: PortabilityMetrics.reviewInset);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const SizedBox(height: PortabilityMetrics.reviewTop),
        _Groups(
          gap: PortabilityMetrics.reviewGap,
          children: [
            Padding(
              padding: inset,
              child: Row(
                children: [
                  AccountGlyph(provider: target.account.provider),
                  const SizedBox(width: PortabilityMetrics.reviewHeaderGap),
                  Expanded(
                    child: Semantics(
                      header: true,
                      child: Text(
                        target.account.displayName,
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
              padding: inset,
              child: Text(
                state.directSongs > 0 && source.account.provider == target.account.provider
                    ? s.reviewPartialLead(state.directSongs, rows.length)
                    : s.reviewLead(source.account.displayName, rows.length, serviceNameOf(s, target.account.provider)),
                style: theme.textTheme.bodyLarge!.copyWith(color: colors.onSurfaceVariant),
              ),
            ),
            Padding(
              padding: inset,
              child: RestoreSummaryBar(summary: summary),
            ),
            Padding(
              padding: inset,
              child: Row(
                children: [
                  for (final (i, (filter, label, count)) in filters.indexed) ...[
                    if (i > 0) const SizedBox(width: PlaylistBackupMetrics.actionGap),
                    Expanded(
                      child: ConnectedToggle(
                        key: ValueKey('xfer_filter_${filter.name}'),
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
            Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                for (final (i, row) in shown.indexed) ...[
                  if (i > 0) const SizedBox(height: PortabilityMetrics.reviewRowGap),
                  _TransferReviewRow(
                    key: ValueKey('xfer_row_${row.position}'),
                    row: row,
                    match: state.trackMatches[row.position],
                    shape: settingsSegmentShape(i, shown.length),
                    expanded: _open.contains(row.position),
                    onToggleExpanded: () => setState(() {
                      if (!_open.remove(row.position)) _open.add(row.position);
                    }),
                    onCheck: (checked) {
                      YoinHaptics.click();
                      widget.vm.setChecked(row.position, checked);
                    },
                    onChoose: (index) {
                      YoinHaptics.click();
                      widget.vm.choose(row.position, index);
                    },
                  ),
                ],
              ],
            ),
            if (albums > 0)
              SyncNote(
                icon: YoinSymbols.album,
                text: s.albumsNote(albums, albumsMatched, albums - albumsMatched, source.account.displayName),
              ),
            SyncNote(icon: YoinSymbols.info, text: s.leftNote(source.account.displayName)),
          ],
        ),
      ],
    );
  }
}

/// One song of the transfer review, as playlist restore draws its rows: the mark, the source song, what the
/// target offers. An unsure one opens its candidates (up to three) to pick from.
class _TransferReviewRow extends StatelessWidget {
  const _TransferReviewRow({
    super.key,
    required this.row,
    required this.match,
    required this.shape,
    required this.expanded,
    required this.onToggleExpanded,
    required this.onCheck,
    required this.onChoose,
  });

  final ReviewRow row;
  final TrackMatch match;
  final ShapeBorder shape;
  final bool expanded;
  final VoidCallback onToggleExpanded;
  final ValueChanged<bool> onCheck;
  final ValueChanged<int> onChoose;

  @override
  Widget build(BuildContext context) {
    final s = PortabilityStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final uncertain = row.status == ReviewStatus.uncertain;
    final notFound = row.status == ReviewStatus.notFound;
    final title = match.sourceTrack?.title?.trim();
    final duration = row.choice?.durationSec ?? match.sourceTrack?.durationSec;
    return Material(
      color: colors.surfaceContainer,
      shape: shape,
      clipBehavior: Clip.antiAlias,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          InkWell(
            onTap: notFound ? null : (uncertain ? onToggleExpanded : () => onCheck(!row.checked)),
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
                      enabled: row.checkable,
                      label: s.cdCopy,
                      onToggle: () => onCheck(!row.checked),
                    ),
                    const SizedBox(width: PlaylistBackupMetrics.reviewRowGap),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          SongLines(
                            title: title == null || title.isEmpty ? s.unknownSong : title,
                            artist: match.sourceTrack?.artist ?? '',
                            dimmed: notFound,
                          ),
                          if (uncertain)
                            Text(
                              // The arrow sits around data (Android's i18n-allow).
                              row.checked ? '→ ${row.choice?.title ?? ''}' : s.chooseMatch,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: theme.textTheme.bodyMedium!.copyWith(color: colors.primary),
                            ),
                        ],
                      ),
                    ),
                    const SizedBox(width: PlaylistBackupMetrics.reviewRowGap),
                    switch (row.status) {
                      ReviewStatus.matched || ReviewStatus.picked =>
                        duration == null
                            ? const SizedBox.shrink()
                            : Text(
                                formatTrackDuration(duration),
                                style: theme.textTheme.labelLarge!.copyWith(color: colors.onSurfaceVariant),
                              ),
                      ReviewStatus.uncertain => _StatusPill(
                        text: s.filterToCheck,
                        container: colors.tertiaryContainer,
                        content: colors.onTertiaryContainer,
                      ),
                      ReviewStatus.notFound => _StatusPill(
                        text: s.stays,
                        container: colors.surfaceContainerHighest,
                        content: colors.onSurfaceVariant,
                      ),
                    },
                  ],
                ),
              ),
            ),
          ),
          SpringReveal(
            visible: uncertain && expanded,
            child: Padding(
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
                      key: ValueKey('xfer_candidate_${row.position}_$i'),
                      candidate: candidate,
                      selected: row.checked && row.chosen == i,
                      enabled: true,
                      shape: settingsSegmentShape(i, row.candidates.length),
                      onTap: () => onChoose(i),
                    ),
                  ],
                ],
              ),
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

class _DoneStep extends StatelessWidget {
  const _DoneStep({required this.state, required this.vm});

  final TransferUiState state;
  final TransferNotifier vm;

  @override
  Widget build(BuildContext context) {
    final s = PortabilityStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final result = state.result;
    final source = state.source;
    final target = state.target;
    if (result == null || source == null || target == null) return const SizedBox.shrink();
    final copied = result.copied.total + result.updated.total;
    final skipped = result.skippedPreviously + result.kept.total;
    final left = result.unmatched.total;
    final local = source.account.localProfileId != null;
    final cleanable =
        state.cloudSyncOn && !state.cleaned && !state.cleaning && BackupFormat.isSyncScope(source.account.scope);
    return _Groups(
      children: [
        OutcomeHero(
          icon: YoinSymbols.check,
          title: s.doneTitle(target.account.displayName),
          body: s.doneKept(source.account.displayName),
          extra: CountTiles(
            tiles: [
              CountTile(
                count: copied,
                label: s.tileCopied,
                container: colors.primaryContainer,
                content: colors.onPrimaryContainer,
              ),
              CountTile(
                count: skipped,
                label: s.tileSkipped,
                container: colors.surfaceContainerHigh,
                content: colors.onSurface,
              ),
              CountTile(
                count: left,
                label: s.tileLeft,
                container: colors.surfaceContainerHigh,
                content: colors.onSurface,
              ),
            ],
          ),
        ),
        _Groups(
          gap: PortabilityMetrics.noteGap,
          children: [
            if (local || cleanable)
              SettingsSegments(
                children: [
                  if (local)
                    SettingsItem(
                      key: const ValueKey('xfer_remove_card'),
                      icon: YoinSymbols.person,
                      title: s.removeCard,
                      summary: s.removeCardSummary,
                      onTap: () => vm.setRemoveCard(!state.removeCard),
                      trailing: Switch(value: state.removeCard, onChanged: vm.setRemoveCard),
                    ),
                  if (cleanable)
                    SettingsItem(
                      icon: YoinSymbols.deleteSweep,
                      title: s.clean,
                      summary: s.cleanSummary(target.account.displayName, source.account.displayName),
                      trailing: Icon(YoinSymbols.chevronRight, color: colors.onSurfaceVariant),
                      onTap: () => _confirmClean(context, s, source, target),
                    ),
                ],
              ),
            SyncNote(icon: YoinSymbols.info, text: s.neodbNote),
          ],
        ),
      ],
    );
  }

  Future<void> _confirmClean(
    BuildContext context,
    PortabilityStrings s,
    TransferAccountUi source,
    TransferAccountUi target,
  ) async {
    YoinHaptics.click();
    final what = countsSentence(CloudSyncStrings.of(context), source.counts ?? const CloudScopeCounts());
    final confirmed = await showYoinDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(s.cleanTitle(source.account.displayName)),
        content: Text(s.cleanBody(source.account.displayName, what, target.account.displayName)),
        actions: [
          TextButton(onPressed: () => Navigator.of(context).pop(false), child: Text(s.cleanLater)),
          TextButton(
            style: TextButton.styleFrom(foregroundColor: Theme.of(context).colorScheme.error),
            onPressed: () {
              YoinHaptics.reject();
              Navigator.of(context).pop(true);
            },
            child: Text(s.cleanNow),
          ),
        ],
      ),
    );
    if (confirmed == true) await vm.cleanSource();
  }
}
