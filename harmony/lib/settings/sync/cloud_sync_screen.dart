/// Settings › Cloud sync (Android ui/settings/sync/CloudSyncScreen.kt): turn
/// Google Drive sync on, see how it's doing, manage accounts and devices.
///
/// Back surface: PushPage (a Settings sub-page, [SettingsSubPage]'s back
/// button and system back pop it); on a wide window it is the Settings
/// list-detail's right pane ([inPane]).
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../../app/providers.dart';
import '../../data/model/media_id.dart';
import '../../data/sync/cloud_sync_state.dart';
import '../../nowplaying/haptics.dart';
import '../../theme/motion.dart';
import '../../ui/component/spring_cross_fade.dart';
import '../../ui/component/yoin_dialog.dart';
import '../../ui/component/yoin_dropdown_menu.dart';
import '../../ui/component/yoin_loading_indicator.dart';
import '../../ui/navigation/yoin_push_route.dart';
import '../account_identity.dart';
import '../apple_music_setup_screen.dart';
import '../settings_components.dart';
import '../settings_metrics.dart';
import '../settings_page.dart';
import '../settings_rows.dart';
import '../subsonic_setup_screen.dart';
import 'cloud_sync_strings.dart';
import 'cloud_sync_texts.dart';
import 'cloud_sync_view_model.dart';

/// Wall-clock time that moves every 30 s, so "2 min ago" keeps up while shown.
class SyncClock extends StatefulWidget {
  const SyncClock({super.key, required this.builder, this.now});

  final Widget Function(BuildContext context, int nowMs) builder;

  /// Fixed time (tests, previews).
  final int? now;

  @override
  State<SyncClock> createState() => _SyncClockState();
}

class _SyncClockState extends State<SyncClock> {
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    if (widget.now == null) _timer = Timer.periodic(const Duration(seconds: 30), (_) => setState(() {}));
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => widget.builder(context, widget.now ?? DateTime.now().millisecondsSinceEpoch);
}

class CloudSyncScreen extends ConsumerWidget {
  const CloudSyncScreen({super.key, this.inPane = false, this.now});

  final bool inPane;

  /// Fixed time (tests).
  final int? now;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = CloudSyncStrings.of(context);
    final page = ref.watch(cloudSyncViewModelProvider);
    final enabled = page.sync.enabled;
    return SyncClock(
      now: now,
      builder: (context, nowMs) => SettingsSubPage(
        title: strings.title,
        inPane: inPane,
        lead: SpringReveal(
          visible: !enabled,
          child: Padding(
            padding: const EdgeInsets.only(top: SettingsMetrics.syncLeadTop),
            child: Text(
              strings.lead,
              style: Theme.of(
                context,
              ).textTheme.bodyLarge!.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant),
            ),
          ),
        ),
        children: [
          // Only On ⇄ Off swaps the page; updates inside either just rebuild.
          SpringCrossFade(
            childKey: enabled,
            fit: StackFit.loose,
            child: enabled ? _OnContent(page: page, now: nowMs) : _OffContent(page: page),
          ),
        ],
      ),
    );
  }
}

// ── Off ──────────────────────────────────────────────────────────────

class _OffContent extends ConsumerWidget {
  const _OffContent({required this.page});

  final CloudSyncPageState page;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final s = CloudSyncStrings.of(context);
    final phase = page.sync.phase;
    final vm = ref.read(cloudSyncViewModelProvider.notifier);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (phase is SyncResetElsewhere) ...[
          SettingsSegments(
            children: [
              _TextRow(
                icon: YoinSymbols.info,
                text: phase.deviceName != null ? s.resetOnDevice(phase.deviceName!) : s.resetOtherDevice,
                emphasized: true,
              ),
            ],
          ),
          const SizedBox(height: SettingsMetrics.sectionGap),
        ],
        SettingsGroup(
          title: s.sectionWhatYouGet,
          children: [
            SettingsItem(icon: YoinSymbols.editNote, title: s.benefitNotes),
            SettingsItem(icon: YoinSymbols.settings, title: s.benefitSettings),
            SettingsItem(icon: YoinSymbols.cloudOffline, title: s.benefitOffline),
          ],
        ),
        const SizedBox(height: SettingsMetrics.sectionGap),
        SettingsGroup(
          title: s.sectionGoodToKnow,
          children: [
            _TextRow(icon: YoinSymbols.folder, text: s.knowDrive),
            _TextRow(icon: YoinSymbols.person, text: s.knowSecrets),
            _TextRow(icon: YoinSymbols.insights, text: s.knowHistory),
          ],
        ),
        const SizedBox(height: SettingsMetrics.sectionGap),
        // The action changes with what this device can do; keyed on the kind.
        SpringCrossFade(
          childKey: phase is SyncMisconfiguredPhase,
          fit: StackFit.loose,
          child: switch (phase) {
            SyncMisconfiguredPhase(:final packageName) => Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                SettingsSegments(
                  children: [
                    _TextRow(icon: YoinSymbols.error, text: s.unavailable, emphasized: true),
                    _BuildDetailsRow(packageName: packageName),
                  ],
                ),
                const SizedBox(height: SettingsMetrics.syncActionsGap),
                // Once this build's client is registered with Google, trying again works.
                OutlinedButton(
                  onPressed: page.turningOn ? null : () => vm.turnOn(s),
                  child: Text(page.turningOn ? s.waitingRetry : s.tryAgain),
                ),
              ],
            ),
            _ => FilledButton(
              key: const ValueKey('cloud_sync_turn_on'),
              onPressed: page.turningOn
                  ? null
                  : () {
                      YoinHaptics.confirm();
                      vm.turnOn(s);
                    },
              child: Text(page.turningOn ? s.waitingTurnOn : s.turnOn),
            ),
          },
        ),
      ],
    );
  }
}

/// The package the OAuth client is registered for, for the "not available" case.
class _BuildDetailsRow extends StatelessWidget {
  const _BuildDetailsRow({required this.packageName});

  final String packageName;

  @override
  Widget build(BuildContext context) {
    final s = CloudSyncStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return ConstrainedBox(
      constraints: const BoxConstraints(minHeight: SettingsMetrics.rowMinHeight),
      child: Padding(
        padding: const EdgeInsets.symmetric(
          horizontal: SettingsMetrics.rowPaddingHorizontal,
          vertical: SettingsMetrics.rowPaddingVertical,
        ),
        child: Row(
          children: [
            const SettingsRowIcon(icon: YoinSymbols.code),
            const SizedBox(width: SettingsMetrics.rowIconGap),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(s.details, style: theme.textTheme.titleMedium!.copyWith(color: colors.onSurface)),
                  const SizedBox(height: SettingsMetrics.rowTextGap),
                  Text(s.unrecognized, style: theme.textTheme.bodyMedium!.copyWith(color: colors.onSurfaceVariant)),
                  const SizedBox(height: SettingsMetrics.rowTextGap),
                  Text(
                    packageName,
                    style: theme.textTheme.bodyMedium!.copyWith(color: colors.onSurface, fontFamily: 'monospace'),
                  ),
                ],
              ),
            ),
            IconButton(
              tooltip: s.cdCopyBuild,
              icon: Icon(YoinSymbols.copy, color: colors.onSurfaceVariant),
              onPressed: () {
                YoinHaptics.click();
                Clipboard.setData(ClipboardData(text: s.clipBody(packageName, s.certUnknown)));
              },
            ),
          ],
        ),
      ),
    );
  }
}

// ── On ───────────────────────────────────────────────────────────────

class _OnContent extends ConsumerWidget {
  const _OnContent({required this.page, required this.now});

  final CloudSyncPageState page;
  final int now;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final s = CloudSyncStrings.of(context);
    final sync = page.sync;
    final vm = ref.read(cloudSyncViewModelProvider.notifier);
    final account = sync.account;
    final accountTitle = account?.email ?? account?.displayName ?? s.googleAccount;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SettingsSegments(
          children: [
            _StatusRow(state: sync, now: now, onSyncNow: vm.syncNow),
            if (sync.phase is SyncNeedsReauth)
              _ActionPanel(
                text: s.reauthBody,
                actions: [
                  FilledButton(
                    onPressed: page.turningOn
                        ? null
                        : () {
                            YoinHaptics.confirm();
                            vm.turnOn(s);
                          },
                    child: Text(s.reconnect),
                  ),
                ],
              ),
            if (sync.phase case final SyncNeedsReview review)
              SettingsItem(
                icon: YoinSymbols.note,
                title: s.reviewRowTitle(review.count),
                summary: review.accountName,
                onTap: () => showReviewDialog(context, ref),
                trailing: Icon(YoinSymbols.chevronRight, color: Theme.of(context).colorScheme.onSurfaceVariant),
              ),
            if (sync.phase is SyncCloudCopyRemoved)
              _ActionPanel(
                text: s.copyRemovedBody,
                actions: [
                  FilledButton(
                    onPressed: () {
                      YoinHaptics.confirm();
                      vm.uploadAgain();
                    },
                    child: Text(s.uploadAgain),
                  ),
                  OutlinedButton(
                    onPressed: () {
                      YoinHaptics.click();
                      vm.turnOff();
                    },
                    child: Text(s.turnOffShort),
                  ),
                ],
              ),
            SettingsItem(
              icon: YoinSymbols.person,
              title: accountTitle,
              summary: accountTitle == s.googleAccount ? null : s.googleAccount,
            ),
          ],
        ),
        if (sync.accounts.isNotEmpty) ...[
          const SizedBox(height: SettingsMetrics.sectionGap),
          SettingsGroup(
            title: s.sectionAccounts,
            children: [for (final a in sync.accounts) _AccountRow(key: ValueKey(a.localProfileId), account: a)],
          ),
        ],
        if (sync.cloudOnlyAccounts.isNotEmpty) ...[
          const SizedBox(height: SettingsMetrics.sectionGap),
          SettingsGroup(
            title: s.sectionCloudOnly,
            children: [
              for (final cloud in sync.cloudOnlyAccounts)
                _CloudAccountItem(
                  key: ValueKey(cloud.syncProfileId),
                  cloud: cloud,
                  // Apple Music never auto-binds: a local Apple account that isn't syncing can take this one over.
                  linkTargets: [
                    for (final local in sync.accounts)
                      if (local.status case AccountNotSynced(
                        :final linkCandidates,
                      ) when linkCandidates.any((c) => c.syncProfileId == cloud.syncProfileId))
                        local,
                  ],
                ),
            ],
          ),
        ],
        if (sync.devices.isNotEmpty) ...[
          const SizedBox(height: SettingsMetrics.sectionGap),
          SettingsGroup(
            title: s.sectionDevices,
            children: [
              for (final d in [...sync.devices]..sort((a, b) => (b.isThisDevice ? 1 : 0) - (a.isThisDevice ? 1 : 0)))
                _DeviceItem(key: ValueKey(d.deviceId), device: d, now: now),
            ],
          ),
        ],
        const SizedBox(height: SettingsMetrics.sectionGap),
        SettingsSegments(
          children: [
            const _WhatSyncsItem(),
            SettingsItem(
              icon: YoinSymbols.cloudOffline,
              title: s.turnOffTitle,
              summary: s.turnOffSummary,
              onTap: vm.turnOff,
            ),
            _DestructiveItem(
              icon: YoinSymbols.delete,
              title: s.deleteTitle,
              summary: page.deleting ? s.deleting : null,
              enabled: !page.deleting,
              onTap: () => _confirmDelete(context, ref),
            ),
          ],
        ),
      ],
    );
  }

  Future<void> _confirmDelete(BuildContext context, WidgetRef ref) async {
    final s = CloudSyncStrings.of(context);
    final confirmed = await showYoinDialog<bool>(
      context: context,
      builder: (context) => _WhileEnabled(
        child: AlertDialog(
          title: Text(s.deleteDialogTitle),
          content: Text(s.deleteDialogBody),
          actions: [
            TextButton(onPressed: () => Navigator.of(context).pop(false), child: Text(s.deleteCancel)),
            TextButton(
              style: TextButton.styleFrom(foregroundColor: Theme.of(context).colorScheme.error),
              onPressed: () {
                YoinHaptics.reject();
                Navigator.of(context).pop(true);
              },
              child: Text(s.deleteConfirm),
            ),
          ],
        ),
      ),
    );
    if (confirmed == true) await ref.read(cloudSyncViewModelProvider.notifier).deleteCloudData(s);
  }
}

/// Icon look by state.
enum _StatusVisual {
  syncing(YoinSymbols.refresh, isProblem: false),
  upToDate(YoinSymbols.check, isProblem: false),
  pending(YoinSymbols.cloud, isProblem: false),
  offline(YoinSymbols.cloudOffline, isProblem: false),
  attention(YoinSymbols.info, isProblem: false),
  problem(YoinSymbols.error, isProblem: true),
  storage(YoinSymbols.storage, isProblem: true),
  removed(YoinSymbols.cloudOffline, isProblem: true);

  const _StatusVisual(this.icon, {required this.isProblem});
  final IconData icon;
  final bool isProblem;

  static _StatusVisual of(CloudSyncState state) => switch (state.phase) {
    SyncSyncing() => syncing,
    SyncUpToDate() => state.pendingChanges ? pending : upToDate,
    SyncOffline() => offline,
    SyncStorageFullPhase() => storage,
    SyncCloudCopyRemoved() => removed,
    SyncNeedsReview() || SyncUpdateRequired() => attention,
    _ => problem,
  };
}

/// Where sync stands: icon by state, what's happening, when it last synced, and Sync now.
class _StatusRow extends StatefulWidget {
  const _StatusRow({required this.state, required this.now, required this.onSyncNow});

  final CloudSyncState state;
  final int now;
  final VoidCallback onSyncNow;

  @override
  State<_StatusRow> createState() => _StatusRowState();
}

class _StatusRowState extends State<_StatusRow> with SingleTickerProviderStateMixin {
  late final SpringValue _problem = SpringValue(
    vsync: this,
    initial: _StatusVisual.of(widget.state).isProblem ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );

  @override
  void didUpdateWidget(_StatusRow old) {
    super.didUpdateWidget(old);
    final target = _StatusVisual.of(widget.state).isProblem ? 1.0 : 0.0;
    if (MediaQuery.maybeDisableAnimationsOf(context) ?? false) {
      _problem.snapTo(target);
    } else {
      _problem.animateTo(target);
    }
  }

  @override
  void dispose() {
    _problem.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final s = CloudSyncStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final text = cloudSyncStatusText(widget.state, widget.now, s, date: localizedSyncDate(context));
    final visual = _StatusVisual.of(widget.state);
    final secondary = theme.textTheme.bodyMedium!.copyWith(color: colors.onSurfaceVariant);
    final summary = widget.state.lastSummaryCount;
    return ConstrainedBox(
      constraints: const BoxConstraints(minHeight: SettingsMetrics.rowMinHeight),
      child: Padding(
        padding: const EdgeInsetsDirectional.fromSTEB(
          SettingsMetrics.rowPaddingHorizontal,
          SettingsMetrics.rowPaddingVertical,
          SettingsMetrics.syncStatusEnd,
          SettingsMetrics.rowPaddingVertical,
        ),
        child: Row(
          children: [
            SizedBox.square(
              dimension: SettingsMetrics.rowIconBox,
              child: SpringCrossFade(
                childKey: visual,
                child: Center(
                  child: visual == _StatusVisual.syncing
                      ? const YoinLoadingIndicator(size: SettingsMetrics.syncLoading)
                      : AnimatedBuilder(
                          animation: _problem.animation,
                          builder: (context, _) => Icon(
                            visual.icon,
                            size: SettingsMetrics.rowIcon,
                            color: Color.lerp(colors.onSurfaceVariant, colors.error, _problem.value.clamp(0.0, 1.0)),
                          ),
                        ),
                ),
              ),
            ),
            const SizedBox(width: SettingsMetrics.rowIconGap),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(text.title, style: theme.textTheme.titleMedium!.copyWith(color: colors.onSurface)),
                  for (final line in text.lines) ...[
                    const SizedBox(height: SettingsMetrics.rowTextGap),
                    Text(line, style: secondary),
                  ],
                  if (summary != null) ...[
                    const SizedBox(height: SettingsMetrics.rowTextGap),
                    Text(
                      s.syncedOtherDevicesCount(summary),
                      style: theme.textTheme.bodyMedium!.copyWith(color: colors.primary),
                    ),
                  ],
                ],
              ),
            ),
            IconButton(
              tooltip: s.cdSyncNow,
              icon: const Icon(YoinSymbols.refresh),
              onPressed: widget.state.phase is SyncSyncing
                  ? null
                  : () {
                      YoinHaptics.click();
                      widget.onSyncNow();
                    },
            ),
          ],
        ),
      ),
    );
  }
}

/// A problem that needs a decision: what happened, then the buttons.
class _ActionPanel extends StatelessWidget {
  const _ActionPanel({required this.text, required this.actions});

  final String text;
  final List<Widget> actions;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsetsDirectional.fromSTEB(
        SettingsMetrics.rowTextInset,
        SettingsMetrics.syncPanelTop,
        SettingsMetrics.rowPaddingHorizontal,
        SettingsMetrics.syncPanelBottom,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(text, style: theme.textTheme.bodyMedium!.copyWith(color: theme.colorScheme.onSurface)),
          const SizedBox(height: SettingsMetrics.syncPanelGap),
          Wrap(spacing: SettingsMetrics.syncButtonGap, runSpacing: SettingsMetrics.syncButtonGap, children: actions),
        ],
      ),
    );
  }
}

// ── Accounts ─────────────────────────────────────────────────────────

String accountStatusText(CloudSyncStrings s, SyncAccountStatus status) => switch (status) {
  AccountSyncing() => s.accountSyncing,
  AccountNeedsReconnect() => s.accountReconnect,
  AccountDuplicateOf(:final otherDisplayName) => s.accountDuplicate(otherDisplayName),
  AccountNotSynced() => s.accountNotSynced,
  AccountChanged() => s.accountChanged,
};

/// A row with a service's mark, title and lines under it, the actions below.
class _ServiceRow extends StatelessWidget {
  const _ServiceRow({
    required this.provider,
    required this.title,
    required this.lines,
    this.trailing,
    this.actions = const [],
  });

  final String provider;
  final String title;
  final List<Widget> lines;
  final Widget? trailing;
  final List<Widget> actions;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final identity = ServiceIdentity.forProvider(provider);
    final tone = identity.hue.tone(theme.brightness);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        ConstrainedBox(
          constraints: const BoxConstraints(minHeight: SettingsMetrics.rowMinHeight),
          child: Padding(
            padding: const EdgeInsets.symmetric(
              horizontal: SettingsMetrics.rowPaddingHorizontal,
              vertical: SettingsMetrics.rowPaddingVertical,
            ),
            child: Row(
              children: [
                // SettingsRowIcon(tone = …): the service's mark on its own disc.
                DecoratedBox(
                  decoration: ShapeDecoration(color: tone.iconContainer, shape: const CircleBorder()),
                  child: SizedBox.square(
                    dimension: SettingsMetrics.rowIconBox,
                    child: Icon(identity.glyph, size: SettingsMetrics.rowIcon, color: tone.iconContent),
                  ),
                ),
                const SizedBox(width: SettingsMetrics.rowIconGap),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(title, style: theme.textTheme.titleMedium!.copyWith(color: theme.colorScheme.onSurface)),
                      for (final line in lines) ...[const SizedBox(height: SettingsMetrics.rowTextGap), line],
                    ],
                  ),
                ),
                if (trailing != null) ...[const SizedBox(width: SettingsMetrics.rowTrailingGap), trailing!],
              ],
            ),
          ),
        ),
        SpringReveal(
          visible: actions.isNotEmpty,
          child: actions.isEmpty
              ? null
              : Padding(
                  padding: const EdgeInsetsDirectional.only(
                    start: SettingsMetrics.rowTextInset,
                    end: SettingsMetrics.rowPaddingHorizontal,
                    bottom: SettingsMetrics.syncPanelBottom,
                  ),
                  child: Align(
                    alignment: AlignmentDirectional.centerStart,
                    child: Wrap(
                      spacing: SettingsMetrics.syncButtonGap,
                      runSpacing: SettingsMetrics.syncButtonGap,
                      children: actions,
                    ),
                  ),
                ),
        ),
      ],
    );
  }
}

/// The service and where it lives, as on the account cards ("Subsonic  music.example.com").
Widget? _serviceLine(BuildContext context, String provider, String title, String? detail) {
  final identity = ServiceIdentity.forProvider(provider);
  final line = serviceLine(identity.name, title, detail);
  if (line == null) return null;
  final theme = Theme.of(context);
  return AccountServiceLine(
    kind: line.kind,
    detail: line.detail,
    color: theme.colorScheme.onSurfaceVariant,
    style: theme.textTheme.bodyMedium,
  );
}

class _AccountRow extends ConsumerWidget {
  const _AccountRow({super.key, required this.account});

  final SyncAccountRow account;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final s = CloudSyncStrings.of(context);
    final theme = Theme.of(context);
    final vm = ref.read(cloudSyncViewModelProvider.notifier);
    final status = account.status;
    final service = _serviceLine(context, account.provider, account.displayName, null);
    return _ServiceRow(
      provider: account.provider,
      title: account.displayName,
      lines: [
        ?service,
        Text(
          accountStatusText(s, status),
          style: theme.textTheme.bodyMedium!.copyWith(
            color: theme.colorScheme.onSurfaceVariant.withValues(alpha: SettingsMetrics.syncSummaryAlpha),
          ),
        ),
      ],
      actions: switch (status) {
        AccountNotSynced(:final linkCandidates) => [
          FilledButton.tonal(
            onPressed: () {
              YoinHaptics.confirm();
              vm.startSyncingAccount(account.localProfileId);
            },
            child: Text(s.startSyncing),
          ),
          if (linkCandidates.isNotEmpty)
            _LinkButton(
              candidates: linkCandidates,
              onLink: (cloud) => vm.linkAccount(account.localProfileId, cloud.syncProfileId),
            ),
        ],
        AccountChanged() => [
          FilledButton.tonal(
            onPressed: () {
              YoinHaptics.confirm();
              vm.keepAccountAsBefore(account.localProfileId);
            },
            child: Text(s.keepBefore),
          ),
          OutlinedButton(
            onPressed: () {
              YoinHaptics.click();
              _confirmStartFresh(context, ref);
            },
            child: Text(s.startFresh),
          ),
        ],
        _ => const [],
      },
    );
  }

  Future<void> _confirmStartFresh(BuildContext context, WidgetRef ref) async {
    final s = CloudSyncStrings.of(context);
    final id = account.localProfileId;
    final confirmed = await showYoinDialog<bool>(
      context: context,
      builder: (context) => _WhileTrue(
        // The dialog belongs to the state that offered it.
        holds: (page) => page.sync.accounts.any((a) => a.localProfileId == id && a.status is AccountChanged),
        child: AlertDialog(
          title: Text(s.freshTitle),
          content: Text(s.freshBody(account.displayName)),
          actions: [
            TextButton(onPressed: () => Navigator.of(context).pop(false), child: Text(s.freshCancel)),
            TextButton(
              style: TextButton.styleFrom(foregroundColor: Theme.of(context).colorScheme.error),
              onPressed: () {
                YoinHaptics.reject();
                Navigator.of(context).pop(true);
              },
              child: Text(s.freshConfirm),
            ),
          ],
        ),
      ),
    );
    if (confirmed == true) ref.read(cloudSyncViewModelProvider.notifier).startFreshAccount(id);
  }
}

/// "Apple Music from Pixel 9": Apple accounts are usually all called "Apple Music".
String _linkLabel(CloudSyncStrings s, CloudAccountRow candidate) {
  final device = candidate.fromDeviceName;
  return device == null || device.trim().isEmpty ? candidate.displayName : s.linkFrom(candidate.displayName, device);
}

/// "Link to `account`" for one candidate; "Link…" with a menu for several.
class _LinkButton extends StatelessWidget {
  const _LinkButton({required this.candidates, required this.onLink});

  final List<CloudAccountRow> candidates;
  final ValueChanged<CloudAccountRow> onLink;

  @override
  Widget build(BuildContext context) {
    final s = CloudSyncStrings.of(context);
    if (candidates.length == 1) {
      return OutlinedButton(
        onPressed: () {
          YoinHaptics.confirm();
          onLink(candidates.single);
        },
        child: Text(s.linkTo(_linkLabel(s, candidates.single))),
      );
    }
    return Builder(
      builder: (anchor) => OutlinedButton(
        onPressed: () async {
          YoinHaptics.tick();
          final picked = await showYoinDropdownMenu<CloudAccountRow>(
            context: anchor,
            anchor: globalRectOf(anchor),
            items: [for (final c in candidates) YoinMenuItem(value: c, label: _linkLabel(s, c))],
          );
          if (picked != null) {
            YoinHaptics.contextClick();
            onLink(picked);
          }
        },
        child: Text(s.linkMenu),
      ),
    );
  }
}

class _CloudAccountItem extends ConsumerWidget {
  const _CloudAccountItem({super.key, required this.cloud, required this.linkTargets});

  final CloudAccountRow cloud;
  final List<SyncAccountRow> linkTargets;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final s = CloudSyncStrings.of(context);
    final theme = Theme.of(context);
    final vm = ref.read(cloudSyncViewModelProvider.notifier);
    final service = _serviceLine(context, cloud.provider, cloud.displayName, cloud.hint);
    final device = cloud.fromDeviceName;
    final canAdd = cloud.provider == MediaId.providerSubsonic || cloud.provider == MediaId.providerAppleMusic;
    Widget? trailing;
    if (linkTargets.isNotEmpty) {
      trailing = Builder(
        builder: (anchor) => TextButton(
          onPressed: () async {
            if (linkTargets.length == 1) {
              YoinHaptics.confirm();
              vm.linkAccount(linkTargets.single.localProfileId, cloud.syncProfileId);
              return;
            }
            YoinHaptics.tick();
            final picked = await showYoinDropdownMenu<SyncAccountRow>(
              context: anchor,
              anchor: globalRectOf(anchor),
              items: [for (final l in linkTargets) YoinMenuItem(value: l, label: l.displayName)],
            );
            if (picked != null) {
              YoinHaptics.contextClick();
              vm.linkAccount(picked.localProfileId, cloud.syncProfileId);
            }
          },
          child: Text(s.link),
        ),
      );
    } else if (canAdd) {
      trailing = TextButton(
        onPressed: () {
          YoinHaptics.click();
          _add(context, ref);
        },
        child: Text(s.add),
      );
    }
    return _ServiceRow(
      provider: cloud.provider,
      title: cloud.displayName,
      lines: [
        if (service != null)
          service
        else if (cloud.hint != null && cloud.hint!.trim().isNotEmpty)
          Text(cloud.hint!, style: theme.textTheme.bodyMedium!.copyWith(color: theme.colorScheme.onSurfaceVariant)),
        if (device != null && device.trim().isNotEmpty)
          Text(
            device,
            style: theme.textTheme.bodyMedium!.copyWith(
              color: theme.colorScheme.onSurfaceVariant.withValues(alpha: SettingsMetrics.syncSummaryAlpha),
            ),
          ),
      ],
      trailing: trailing,
    );
  }

  /// The service's own setup page; once an account was saved, sync pulls its data down at once.
  Future<void> _add(BuildContext context, WidgetRef ref) async {
    final repo = ref.read(profileRepositoryProvider);
    final before = (await repo.profiles()).length;
    if (!context.mounted) return;
    await Navigator.of(context).push(
      YoinPushRoute<void>(
        builder: (_) =>
            cloud.provider == MediaId.providerAppleMusic ? const AppleMusicSetupScreen() : const SubsonicSetupScreen(),
      ),
    );
    if ((await repo.profiles()).length > before) ref.read(cloudSyncViewModelProvider.notifier).onAccountAdded();
  }
}

// ── Devices ──────────────────────────────────────────────────────────

bool _looksLikeTablet(String name) {
  final lower = name.toLowerCase();
  return ['tablet', 'tab ', 'pad'].any(lower.contains);
}

class _DeviceItem extends ConsumerWidget {
  const _DeviceItem({super.key, required this.device, required this.now});

  final SyncDeviceRow device;
  final int now;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final s = CloudSyncStrings.of(context);
    final tablet = device.isThisDevice ? MediaQuery.sizeOf(context).shortestSide >= 600 : _looksLikeTablet(device.name);
    final last = device.lastSyncAt;
    return SettingsItem(
      icon: tablet ? YoinSymbols.tablet : YoinSymbols.smartphone,
      title: device.name,
      summary: device.isThisDevice
          ? s.thisDevice
          : last != null
          ? s.deviceLastSynced(formatRelativeTime(last, now, s, date: localizedSyncDate(context)))
          : s.deviceNever,
      trailing: device.isThisDevice
          ? null
          : Builder(
              builder: (anchor) => IconButton(
                tooltip: s.cdDeviceOptions(device.name),
                icon: Icon(YoinSymbols.moreVertical, color: Theme.of(context).colorScheme.onSurfaceVariant),
                onPressed: () async {
                  YoinHaptics.tick();
                  final picked = await showYoinDropdownMenu<bool>(
                    context: anchor,
                    anchor: globalRectOf(anchor),
                    items: [YoinMenuItem(value: true, label: s.removeDeviceMenu, icon: YoinSymbols.delete)],
                  );
                  if (picked == true && context.mounted) {
                    YoinHaptics.contextClick();
                    await _confirmRemove(context, ref);
                  }
                },
              ),
            ),
    );
  }

  Future<void> _confirmRemove(BuildContext context, WidgetRef ref) async {
    final s = CloudSyncStrings.of(context);
    final id = device.deviceId;
    final confirmed = await showYoinDialog<bool>(
      context: context,
      builder: (context) => _WhileTrue(
        holds: (page) => page.sync.devices.any((d) => d.deviceId == id && !d.isThisDevice),
        child: AlertDialog(
          title: Text(s.removeDeviceTitle(device.name)),
          content: Text(s.removeDeviceBody),
          actions: [
            TextButton(onPressed: () => Navigator.of(context).pop(false), child: Text(s.removeDeviceCancel)),
            TextButton(
              style: TextButton.styleFrom(foregroundColor: Theme.of(context).colorScheme.error),
              onPressed: () {
                YoinHaptics.reject();
                Navigator.of(context).pop(true);
              },
              child: Text(s.removeDeviceConfirm),
            ),
          ],
        ),
      ),
    );
    if (confirmed == true) ref.read(cloudSyncViewModelProvider.notifier).removeDevice(id);
  }
}

// ── Footer ───────────────────────────────────────────────────────────

class _WhatSyncsItem extends StatefulWidget {
  const _WhatSyncsItem();

  @override
  State<_WhatSyncsItem> createState() => _WhatSyncsItemState();
}

class _WhatSyncsItemState extends State<_WhatSyncsItem> with SingleTickerProviderStateMixin {
  bool _expanded = false;
  late final SpringValue _turn = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultSpatial);

  @override
  void dispose() {
    _turn.dispose();
    super.dispose();
  }

  void _toggle() {
    YoinHaptics.click();
    setState(() => _expanded = !_expanded);
    if (MediaQuery.maybeDisableAnimationsOf(context) ?? false) {
      _turn.snapTo(_expanded ? 1 : 0);
    } else {
      _turn.animateTo(_expanded ? 1 : 0);
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = CloudSyncStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Semantics(
          expanded: _expanded,
          child: SettingsItem(
            icon: YoinSymbols.info,
            title: s.whatTitle,
            summary: s.whatSummary,
            onTap: _toggle,
            trailing: AnimatedBuilder(
              animation: _turn.animation,
              builder: (context, child) => Transform.rotate(angle: _turn.value * 3.141592653589793, child: child),
              child: Icon(
                YoinSymbols.chevronDown,
                color: colors.onSurfaceVariant,
                semanticLabel: _expanded ? s.cdCollapse : s.cdExpand,
              ),
            ),
          ),
        ),
        SpringReveal(
          visible: _expanded,
          child: Padding(
            padding: const EdgeInsetsDirectional.only(
              start: SettingsMetrics.rowTextInset,
              end: SettingsMetrics.rowPaddingHorizontal,
              bottom: SettingsMetrics.syncExpandBottom,
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _WhatSyncsList(title: s.whatSyncs, lines: [s.whatNotes, s.whatRatings, s.whatHome, s.whatAccounts]),
                const SizedBox(height: SettingsMetrics.syncExpandGap),
                _WhatSyncsList(title: s.whatStays, lines: [s.whatHistory, s.whatSecrets, s.whatCaches]),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

class _WhatSyncsList extends StatelessWidget {
  const _WhatSyncsList({required this.title, required this.lines});

  final String title;
  final List<String> lines;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SettingsGroupLabel(title: title),
        for (final line in lines) ...[
          const SizedBox(height: SettingsMetrics.syncListLineGap),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: SettingsMetrics.labelInset),
            child: Text(line, style: theme.textTheme.bodyMedium!.copyWith(color: theme.colorScheme.onSurfaceVariant)),
          ),
        ],
      ],
    );
  }
}

/// A row whose action can't be undone: icon and title in the error colour.
class _DestructiveItem extends StatefulWidget {
  const _DestructiveItem({
    required this.icon,
    required this.title,
    required this.summary,
    required this.enabled,
    required this.onTap,
  });

  final IconData icon;
  final String title;
  final String? summary;
  final bool enabled;
  final VoidCallback onTap;

  @override
  State<_DestructiveItem> createState() => _DestructiveItemState();
}

class _DestructiveItemState extends State<_DestructiveItem> with SingleTickerProviderStateMixin {
  late final SpringValue _on = SpringValue(
    vsync: this,
    initial: widget.enabled ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );

  @override
  void didUpdateWidget(_DestructiveItem old) {
    super.didUpdateWidget(old);
    if (MediaQuery.maybeDisableAnimationsOf(context) ?? false) {
      _on.snapTo(widget.enabled ? 1 : 0);
    } else {
      _on.animateTo(widget.enabled ? 1 : 0);
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
    return InkWell(
      onTap: widget.enabled
          ? () {
              YoinHaptics.click();
              widget.onTap();
            }
          : null,
      child: AnimatedBuilder(
        animation: _on.animation,
        builder: (context, _) {
          final color = Color.lerp(colors.onSurface.withValues(alpha: 0.38), colors.error, _on.value.clamp(0.0, 1.0))!;
          return ConstrainedBox(
            constraints: const BoxConstraints(minHeight: SettingsMetrics.rowMinHeight),
            child: Padding(
              padding: const EdgeInsets.symmetric(
                horizontal: SettingsMetrics.rowPaddingHorizontal,
                vertical: SettingsMetrics.rowPaddingVertical,
              ),
              child: Row(
                children: [
                  SizedBox.square(
                    dimension: SettingsMetrics.rowIconBox,
                    child: Icon(widget.icon, size: SettingsMetrics.rowIcon, color: color),
                  ),
                  const SizedBox(width: SettingsMetrics.rowIconGap),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(widget.title, style: theme.textTheme.titleMedium!.copyWith(color: color)),
                        if (widget.summary != null) ...[
                          const SizedBox(height: SettingsMetrics.rowTextGap),
                          Text(
                            widget.summary!,
                            style: theme.textTheme.bodyMedium!.copyWith(color: colors.onSurfaceVariant),
                          ),
                        ],
                      ],
                    ),
                  ),
                ],
              ),
            ),
          );
        },
      ),
    );
  }
}

/// A row that's a sentence, not a setting: icon and wrapping body text.
class _TextRow extends StatelessWidget {
  const _TextRow({required this.icon, required this.text, this.emphasized = false});

  final IconData icon;
  final String text;
  final bool emphasized;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return ConstrainedBox(
      constraints: const BoxConstraints(minHeight: SettingsMetrics.rowMinHeight),
      child: Padding(
        padding: const EdgeInsets.symmetric(
          horizontal: SettingsMetrics.rowPaddingHorizontal,
          vertical: SettingsMetrics.rowPaddingVertical,
        ),
        child: Row(
          children: [
            SettingsRowIcon(icon: icon),
            const SizedBox(width: SettingsMetrics.rowIconGap),
            Expanded(
              child: Text(
                text,
                style: emphasized
                    ? theme.textTheme.bodyLarge!.copyWith(color: colors.onSurface)
                    : theme.textTheme.bodyMedium!.copyWith(color: colors.onSurfaceVariant),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ── Dialogs ──────────────────────────────────────────────────────────

/// Closes the dialog around [child] once [holds] stops being true for the
/// page's state: a dialog belongs to the state that offered it, so it never
/// confirms something that no longer applies.
class _WhileTrue extends ConsumerStatefulWidget {
  const _WhileTrue({required this.holds, required this.child});

  final bool Function(CloudSyncPageState page) holds;
  final Widget child;

  @override
  ConsumerState<_WhileTrue> createState() => _WhileTrueState();
}

class _WhileTrueState extends ConsumerState<_WhileTrue> {
  bool _closing = false;

  @override
  Widget build(BuildContext context) {
    final page = ref.watch(cloudSyncViewModelProvider);
    if (!_closing && !widget.holds(page)) {
      _closing = true;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) Navigator.of(context).maybePop();
      });
    }
    return widget.child;
  }
}

/// The delete dialog stays only while sync is on.
class _WhileEnabled extends StatelessWidget {
  const _WhileEnabled({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) => _WhileTrue(holds: (page) => page.sync.enabled, child: child);
}

/// The held mass-delete review. A sync that runs while it's open keeps it on
/// the last known review; any other state closes it.
Future<void> showReviewDialog(BuildContext context, WidgetRef ref) async {
  final s = CloudSyncStrings.of(context);
  final review = ref.read(cloudSyncViewModelProvider).sync.phase;
  if (review is! SyncNeedsReview) return;
  final restore = await showYoinDialog<bool>(
    context: context,
    builder: (context) => _WhileTrue(
      holds: (page) => page.sync.phase is SyncNeedsReview || page.sync.phase is SyncSyncing,
      child: AlertDialog(
        icon: const Icon(YoinSymbols.note),
        title: Text(s.reviewDialogTitle(review.count)),
        content: Text(s.reviewDialogBody(review.count, review.accountName)),
        actions: [
          TextButton(
            style: TextButton.styleFrom(foregroundColor: Theme.of(context).colorScheme.error),
            onPressed: () {
              YoinHaptics.reject();
              Navigator.of(context).pop(false);
            },
            child: Text(s.reviewDeleteAll),
          ),
          TextButton(
            onPressed: () {
              YoinHaptics.confirm();
              Navigator.of(context).pop(true);
            },
            child: Text(s.reviewRestore),
          ),
        ],
      ),
    ),
  );
  if (restore != null) ref.read(cloudSyncViewModelProvider.notifier).resolveReview(restore: restore);
}
