/// One music account's data in the cloud (Android ui/settings/sync/
/// CloudDataScreens.kt CloudAccountPage): who it is, what the cloud holds for
/// it (notes, album ratings and reviews, song ratings, Memories titles; read
/// from this device's replica, read-only), and "Remove from cloud" with
/// Android's confirmation (CloudSyncOnContent.kt RemoveFromCloudDialog), and
/// "Transfer Memories" with this account as the source.
///
/// Back surface: PushPage ([SettingsSubPage]'s back button and system back
/// pop it). A removal that went through closes it, as Android's activity
/// finishes.
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../../data/sync/cloud_data_views.dart';
import '../../data/sync/cloud_sync_state.dart';
import '../../nowplaying/haptics.dart';
import '../../ui/component/expressive_segment_chip.dart';
import '../../ui/component/meta_line.dart';
import '../../ui/component/yoin_dialog.dart';
import '../../ui/component/yoin_loading_indicator.dart';
import '../../ui/navigation/yoin_push_route.dart';
import '../../ui/shapes/shape_path.dart';
import '../account_identity.dart';
import '../settings_metrics.dart';
import '../settings_page.dart';
import '../settings_rows.dart';
import 'cloud_sync_screen.dart' show SyncClock, WhileCloudSync;
import 'cloud_sync_strings.dart';
import 'cloud_sync_texts.dart';
import 'cloud_sync_view_model.dart';
import 'portability_strings.dart';
import 'transfer_screens.dart' show openTransfer;
import 'transfer_view_model.dart' show TransferArgs;

/// One account in the cloud as the page and its dialog pass it around
/// (Android CloudAccountRef): a profile here that syncs (or is paused), or an
/// account only in the cloud.
@immutable
final class CloudAccountRef {
  const CloudAccountRef({
    required this.syncProfileId,
    required this.provider,
    required this.displayName,
    this.hint,
    this.fromDevice,
    this.cloudOnly = false,
    this.localProfileId,
  });

  factory CloudAccountRef.cloud(CloudAccountRow row) => CloudAccountRef(
    syncProfileId: row.syncProfileId,
    provider: row.provider,
    displayName: row.displayName,
    hint: row.hint,
    fromDevice: row.fromDeviceName,
    cloudOnly: true,
  );

  /// Null for a profile with no account in the cloud (not bound, or removed from it).
  static CloudAccountRef? local(SyncAccountRow row) {
    final scope = row.syncProfileId;
    final inCloud = row.status is AccountSyncing || row.status is AccountChanged;
    if (scope == null || !inCloud) return null;
    return CloudAccountRef(
      syncProfileId: scope,
      provider: row.provider,
      displayName: row.displayName,
      localProfileId: row.localProfileId,
    );
  }

  final String syncProfileId;
  final String provider;
  final String displayName;
  final String? hint;
  final String? fromDevice;
  final bool cloudOnly;
  final String? localProfileId;

  /// Still in the cloud as [sync] stands: a removal (from here or any device) takes it out.
  bool listedIn(CloudSyncState sync) => cloudOnly
      ? sync.cloudOnlyAccounts.any((a) => a.syncProfileId == syncProfileId)
      : sync.accounts.any((a) => a.localProfileId == localProfileId && CloudAccountRef.local(a) != null);
}

/// "128 notes, 43 album ratings & reviews and 89 song ratings" for a removal's confirmation.
String countsSentence(CloudSyncStrings s, CloudScopeCounts? counts) {
  if (counts == null || counts.total == 0) return s.removeAllData;
  final parts = [
    if (counts.notes > 0) s.countNotes(counts.notes),
    if (counts.albums > 0) s.countAlbums(counts.albums),
    if (counts.songRatings > 0) s.countSongRatings(counts.songRatings),
    if (counts.memoryTitles > 0) s.countTitles(counts.memoryTitles),
  ];
  if (parts.length == 1) return parts.single;
  return s.listLast(parts.sublist(0, parts.length - 1).join(s.listSeparator), parts.last);
}

/// An account's counts as meta groups ("128 notes", "43 album ratings & reviews"…), the empty ones left out.
List<MetaGroup> scopeStatGroups(CloudSyncStrings s, CloudScopeCounts counts) => [
  if (counts.notes > 0) MetaStat('${counts.notes}', s.unitNotes(counts.notes)),
  if (counts.albums > 0) MetaStat('${counts.albums}', s.unitAlbums(counts.albums)),
  if (counts.songRatings > 0) MetaStat('${counts.songRatings}', s.unitSongRatings(counts.songRatings)),
  if (counts.memoryTitles > 0) MetaStat('${counts.memoryTitles}', s.unitTitles(counts.memoryTitles)),
];

/// The account's service line, as on the Cloud sync page's rows, then "From `device`".
List<MetaGroup> accountLineGroups(CloudSyncStrings s, CloudAccountRef account) {
  final identity = ServiceIdentity.forProvider(account.provider);
  final line = serviceLine(identity.name, account.displayName, account.hint);
  final hint = account.hint;
  return [
    if (line != null) ...[
      if (line.kind != null) MetaPlain(line.kind!),
      if (line.detail != null) MetaPlain(line.detail!),
    ] else if (hint != null && hint.trim().isNotEmpty)
      MetaPlain(hint),
    if (account.fromDevice != null && account.fromDevice!.trim().isNotEmpty)
      MetaMuted(s.fromDevice(account.fromDevice!)),
  ];
}

/// Transfer Memories from [account] (Android transferArgsFor): the profile here, or the account in the cloud.
TransferArgs transferArgsFor(CloudAccountRef account) => account.localProfileId != null
    ? TransferArgs(sourceProfileId: account.localProfileId)
    : TransferArgs(sourceCloudScope: account.syncProfileId, sourceProvider: account.provider);

/// Opens [account]'s cloud data page.
Future<void> openCloudAccountPage(BuildContext context, CloudAccountRef account) =>
    Navigator.of(context).push(YoinPushRoute<void>(builder: (_) => CloudAccountPage(account: account)));

/// What the cloud holds for one account, read again after every sync.
final cloudAccountContentProvider = FutureProvider.autoDispose.family<CloudAccountContent, String>((ref, scope) {
  ref.watch(cloudSyncViewModelProvider.select((page) => page.sync.lastSyncAt));
  return ref.watch(cloudSyncControllerProvider).cloudAccountContent(scope);
});

/// "Remove from cloud", confirmed (RemoveFromCloudDialog): what goes (the account's counts), what happens
/// on other devices, what stays. Runs the removal and returns true when it went through.
Future<bool> confirmRemoveFromCloud(BuildContext context, WidgetRef ref, CloudAccountRef account) async {
  final s = CloudSyncStrings.of(context);
  final counts = ref.read(cloudSyncViewModelProvider).sync.scopeCounts[account.syncProfileId];
  final from = account.fromDevice;
  final confirmed = await showYoinDialog<bool>(
    context: context,
    builder: (context) => WhileCloudSync(
      // The dialog belongs to the state that offered it: sync on and the account still in the cloud.
      holds: (page) => page.sync.enabled && account.listedIn(page.sync),
      child: AlertDialog(
        icon: const Icon(YoinSymbols.delete),
        title: Text(s.removeTitle(account.displayName)),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(s.removeBody(s.googleDrive, countsSentence(s, counts))),
            const SizedBox(height: SettingsMetrics.syncDialogGap),
            Text(
              !account.cloudOnly
                  ? s.removeBodyLocal
                  : from != null && from.trim().isNotEmpty
                  ? s.removeBodyCloudDevice(from)
                  : s.removeBodyCloud,
            ),
            const SizedBox(height: SettingsMetrics.syncDialogGap),
            Text(s.removeBodyTransferred),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(context).pop(false), child: Text(s.removeCancel)),
          TextButton(
            style: TextButton.styleFrom(foregroundColor: Theme.of(context).colorScheme.error),
            onPressed: () {
              YoinHaptics.reject();
              Navigator.of(context).pop(true);
            },
            child: Text(s.removeConfirm),
          ),
        ],
      ),
    ),
  );
  if (confirmed != true) return false;
  return ref.read(cloudSyncViewModelProvider.notifier).removeFromCloud(account.syncProfileId, account.displayName, s);
}

enum _AccountTab { notes, albums, songs, titles }

class CloudAccountPage extends ConsumerStatefulWidget {
  const CloudAccountPage({super.key, required this.account, this.now});

  final CloudAccountRef account;

  /// Fixed time (tests).
  final int? now;

  @override
  ConsumerState<CloudAccountPage> createState() => _CloudAccountPageState();
}

class _CloudAccountPageState extends ConsumerState<CloudAccountPage> {
  final _query = TextEditingController();
  var _tab = _AccountTab.notes;

  @override
  void initState() {
    super.initState();
    _query.addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    _query.dispose();
    super.dispose();
  }

  Future<void> _remove() async {
    YoinHaptics.click();
    final removed = await confirmRemoveFromCloud(context, ref, widget.account);
    if (removed && mounted) Navigator.of(context).maybePop();
  }

  @override
  Widget build(BuildContext context) {
    final s = CloudSyncStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final account = widget.account;
    final removing = ref.watch(cloudSyncViewModelProvider.select((p) => p.removing.contains(account.syncProfileId)));
    final content = ref.watch(cloudAccountContentProvider(account.syncProfileId));
    final identity = ServiceIdentity.forProvider(account.provider);
    final tone = identity.hue.tone(theme.brightness);
    final line = accountLineGroups(s, account);
    return SyncClock(
      now: widget.now,
      builder: (context, now) => SettingsSubPage(
        title: account.displayName,
        lead: Padding(
          padding: const EdgeInsets.only(top: SettingsMetrics.syncAccountHeaderTop),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              DecoratedBox(
                decoration: ShapeDecoration(
                  color: tone.iconContainer,
                  shape: PolygonBorder(MaterialShapes.cookie6Sided),
                ),
                child: SizedBox.square(
                  dimension: SettingsMetrics.syncAccountGlyph,
                  child: ServiceGlyphIcon(
                    identity.glyph,
                    size: SettingsMetrics.syncAccountGlyph * SettingsMetrics.syncAccountGlyphIcon,
                    color: tone.iconContent,
                  ),
                ),
              ),
              if (line.isNotEmpty) ...[
                const SizedBox(height: SettingsMetrics.syncAccountHeaderGap),
                MetaLine(groups: line, style: theme.textTheme.bodyMedium),
              ],
              if (account.cloudOnly) ...[
                const SizedBox(height: SettingsMetrics.syncAccountHeaderGap),
                SyncPill(text: s.cloudOnly),
              ],
              const SizedBox(height: SettingsMetrics.syncAccountHeaderGap * 2),
              Wrap(
                spacing: SettingsMetrics.syncButtonGap,
                runSpacing: SettingsMetrics.syncButtonGap,
                children: [
                  FilledButton.tonal(
                    key: const ValueKey('cloud_account_transfer'),
                    onPressed: () {
                      YoinHaptics.click();
                      openTransfer(context, transferArgsFor(account));
                    },
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const Icon(YoinSymbols.forward, size: SettingsMetrics.syncNoteIcon),
                        const SizedBox(width: SettingsMetrics.syncNoteGap),
                        Text(PortabilityStrings.of(context).transferTitle),
                      ],
                    ),
                  ),
                  OutlinedButton(
                    key: const ValueKey('cloud_account_remove'),
                    onPressed: removing ? null : _remove,
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        if (removing) ...[
                          const YoinLoadingIndicator(size: SettingsMetrics.syncNoteIcon, contained: false),
                          const SizedBox(width: SettingsMetrics.syncNoteGap),
                        ],
                        Text(s.removeAction),
                      ],
                    ),
                  ),
                ],
              ),
              const SizedBox(height: SettingsMetrics.syncAccountHeaderGap),
              SyncNote(icon: YoinSymbols.visibility, text: s.browserReadonly(s.googleDrive)),
              const SizedBox(height: SettingsMetrics.syncAccountHeaderBottom),
            ],
          ),
        ),
        children: [
          SyncSearchField(controller: _query, placeholder: s.browserSearch),
          const SizedBox(height: SettingsMetrics.syncContentGap),
          switch (content) {
            AsyncData(:final value) => _browser(context, s, value, now),
            AsyncError() => SettingsSegments(
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(
                    SettingsMetrics.rowPaddingHorizontal,
                    SettingsMetrics.syncPanelTop,
                    SettingsMetrics.rowPaddingHorizontal,
                    SettingsMetrics.syncPanelBottom,
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(s.filesFailed, style: theme.textTheme.bodyLarge!.copyWith(color: colors.onSurface)),
                      const SizedBox(height: SettingsMetrics.syncPanelGap),
                      FilledButton.tonal(
                        onPressed: () => ref.invalidate(cloudAccountContentProvider(account.syncProfileId)),
                        child: Text(s.tryAgain),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            _ => _LoadingRow(text: s.loading),
          },
        ],
      ),
    );
  }

  Widget _browser(BuildContext context, CloudSyncStrings s, CloudAccountContent data, int now) {
    final q = _query.text.trim().toLowerCase();
    bool matches(List<String?> fields) => q.isEmpty || fields.any((f) => f != null && f.toLowerCase().contains(q));
    final notes = [
      for (final n in data.notes)
        if (matches([n.title, n.artist, n.content])) n,
    ];
    final albums = [
      for (final a in data.albums)
        if (matches([a.name, a.artist, a.review])) a,
    ];
    final songs = [
      for (final r in data.songRatings)
        if (matches([r.title, r.artist])) r,
    ];
    final titles = [
      for (final t in data.titles)
        if (matches([t.title, t.subject])) t,
    ];
    final date = localizedSyncDate(context);
    final colors = Theme.of(context).colorScheme;
    final items = switch (_tab) {
      _AccountTab.notes => [
        for (final n in notes)
          _BrowserItem(
            title: n.title.trim().isEmpty ? s.unknownSong : n.title,
            meta: [if (n.artist.trim().isNotEmpty) MetaPlain(n.artist)],
            body: n.content,
            stamp: n.positionMs == null ? null : formatTrackDuration(n.positionMs! ~/ 1000),
            footer: formatRelativeTime(n.updatedAt, now, s, date: date),
          ),
      ],
      _AccountTab.albums => [
        for (final a in albums)
          _BrowserItem(
            title: a.name ?? s.unknownAlbum,
            meta: [
              ?(a.artist == null ? null : MetaPlain(a.artist!)),
              MetaMuted(formatRelativeTime(a.updatedAt, now, s, date: date)),
            ],
            body: a.review ?? s.ratingOnly,
            bodyMuted: a.review == null,
            score: a.rating,
          ),
      ],
      _AccountTab.songs => [
        for (final r in songs)
          _BrowserItem(
            title: r.title ?? s.unknownSong,
            meta: [if (r.artist != null && r.artist!.trim().isNotEmpty) MetaPlain(r.artist!)],
            score: r.rating,
          ),
      ],
      _AccountTab.titles => [
        for (final t in titles)
          _BrowserItem(
            title: t.title,
            icon: YoinSymbols.sparkle,
            meta: [
              ?(t.subject == null ? null : MetaPlain(t.subject!)),
              MetaKind(
                t.custom ? s.titleCustom : s.titleAi,
                color: t.custom ? colors.primary : colors.onSurfaceVariant,
              ),
            ],
          ),
      ],
    };
    final tabs = [
      (_AccountTab.notes, s.tabNotes, notes.length),
      (_AccountTab.albums, s.tabAlbums, albums.length),
      (_AccountTab.songs, s.tabSongs, songs.length),
      (_AccountTab.titles, s.tabTitles, titles.length),
    ];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          child: Row(
            children: [
              for (final (i, (tab, label, count)) in tabs.indexed) ...[
                if (i > 0) const SizedBox(width: SettingsMetrics.syncTabGap),
                ExpressiveSegmentChip(
                  label: '$label  $count',
                  selected: tab == _tab,
                  onTap: () {
                    YoinHaptics.tick();
                    setState(() => _tab = tab);
                  },
                ),
              ],
            ],
          ),
        ),
        const SizedBox(height: SettingsMetrics.syncContentGap),
        if (items.isEmpty)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: SettingsMetrics.syncEmptyV),
            child: Text(
              q.isEmpty ? s.noData : s.noMatches,
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.bodyLarge!.copyWith(color: colors.onSurfaceVariant),
            ),
          )
        else
          SettingsSegments(children: items),
      ],
    );
  }
}

/// A small status pill ("Cloud only"; SyncPill, Neutral tone).
class SyncPill extends StatelessWidget {
  const SyncPill({super.key, required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return DecoratedBox(
      decoration: ShapeDecoration(color: theme.colorScheme.secondaryContainer, shape: const StadiumBorder()),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: SettingsMetrics.syncPillH, vertical: SettingsMetrics.syncPillV),
        child: Text(
          text,
          maxLines: 1,
          style: theme.textTheme.labelMedium!.copyWith(color: theme.colorScheme.onSecondaryContainer),
        ),
      ),
    );
  }
}

/// A sentence with a small icon in muted text (SyncNote: read-only hints).
class SyncNote extends StatelessWidget {
  const SyncNote({super.key, required this.icon, required this.text});

  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final ink = theme.colorScheme.onSurfaceVariant;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: SettingsMetrics.labelInset, vertical: SettingsMetrics.syncNoteV),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: SettingsMetrics.syncNoteIcon, color: ink),
          const SizedBox(width: SettingsMetrics.syncNoteGap),
          Expanded(
            child: Text(text, style: theme.textTheme.bodyMedium!.copyWith(color: ink)),
          ),
        ],
      ),
    );
  }
}

/// The pill-shaped search field over the browser (SyncSearchField).
class SyncSearchField extends StatelessWidget {
  const SyncSearchField({super.key, required this.controller, required this.placeholder});

  final TextEditingController controller;
  final String placeholder;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return Container(
      constraints: const BoxConstraints(minHeight: SettingsMetrics.syncSearchMinHeight),
      padding: const EdgeInsets.symmetric(horizontal: SettingsMetrics.syncSearchPaddingH),
      decoration: ShapeDecoration(color: colors.surfaceContainerHighest, shape: const StadiumBorder()),
      child: Row(
        children: [
          Icon(YoinSymbols.search, color: colors.onSurfaceVariant),
          const SizedBox(width: SettingsMetrics.syncSearchGap),
          Expanded(
            child: TextField(
              controller: controller,
              maxLines: 1,
              style: theme.textTheme.bodyLarge!.copyWith(color: colors.onSurface),
              cursorColor: colors.primary,
              decoration: InputDecoration(
                isCollapsed: true,
                border: InputBorder.none,
                contentPadding: const EdgeInsets.symmetric(vertical: SettingsMetrics.syncSearchPaddingV),
                hintText: placeholder,
                hintStyle: theme.textTheme.bodyLarge!.copyWith(color: colors.onSurfaceVariant),
              ),
            ),
          ),
          if (controller.text.isNotEmpty)
            IconButton(
              tooltip: MaterialLocalizations.of(context).deleteButtonTooltip,
              icon: Icon(YoinSymbols.close, size: SettingsMetrics.syncSearchClear, color: colors.onSurfaceVariant),
              onPressed: controller.clear,
            ),
        ],
      ),
    );
  }
}

class _LoadingRow extends StatelessWidget {
  const _LoadingRow({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(
        horizontal: SettingsMetrics.labelInset,
        vertical: SettingsMetrics.syncLoadingRowV,
      ),
      child: Row(
        children: [
          const YoinLoadingIndicator(size: SettingsMetrics.syncLoadingRow),
          const SizedBox(width: SettingsMetrics.syncLoadingRowGap),
          Expanded(
            child: Text(text, style: theme.textTheme.bodyLarge!.copyWith(color: theme.colorScheme.onSurfaceVariant)),
          ),
        ],
      ),
    );
  }
}

/// One record of the browser: title, a meta line, optional text (a note with its time point), a score.
class _BrowserItem extends StatelessWidget {
  const _BrowserItem({
    required this.title,
    required this.meta,
    this.body,
    this.bodyMuted = false,
    this.stamp,
    this.footer,
    this.score,
    this.icon,
  });

  final String title;
  final List<MetaGroup> meta;
  final String? body;
  final bool bodyMuted;
  final String? stamp;
  final String? footer;
  final double? score;
  final IconData? icon;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return ConstrainedBox(
      constraints: const BoxConstraints(minHeight: SettingsMetrics.syncBrowserMinHeight),
      child: Padding(
        padding: const EdgeInsets.symmetric(
          horizontal: SettingsMetrics.rowPaddingHorizontal,
          vertical: SettingsMetrics.rowPaddingVertical,
        ),
        child: Row(
          crossAxisAlignment: body == null ? CrossAxisAlignment.center : CrossAxisAlignment.start,
          children: [
            if (icon != null) ...[SettingsRowIcon(icon: icon!), const SizedBox(width: SettingsMetrics.syncBrowserGap)],
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: theme.textTheme.titleMedium!.copyWith(color: colors.onSurface)),
                  if (meta.isNotEmpty) ...[
                    const SizedBox(height: SettingsMetrics.syncBrowserLineGap),
                    MetaLine(groups: meta, style: theme.textTheme.bodySmall),
                  ],
                  if (body != null) ...[
                    const SizedBox(height: SettingsMetrics.syncBrowserLineGap),
                    Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        if (stamp != null) ...[
                          _MutedPill(text: stamp!),
                          const SizedBox(width: SettingsMetrics.syncBrowserStampGap),
                        ],
                        Expanded(
                          child: Text(
                            body!,
                            style: theme.textTheme.bodyMedium!.copyWith(
                              color: bodyMuted ? colors.onSurfaceVariant : colors.onSurface,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ],
                  if (footer != null) ...[
                    const SizedBox(height: SettingsMetrics.syncBrowserLineGap),
                    MetaLine(groups: [MetaMuted(footer!)], style: theme.textTheme.bodySmall),
                  ],
                ],
              ),
            ),
            if (score != null) ...[
              const SizedBox(width: SettingsMetrics.syncBrowserGap),
              Text(
                // One decimal (Android String.format(locale, "%.1f"); English and Chinese both use a point).
                score!.toStringAsFixed(1),
                style: theme.textTheme.titleLarge!.copyWith(fontWeight: FontWeight.w500, color: colors.primary),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// The note's time point (SyncPill, Muted tone).
class _MutedPill extends StatelessWidget {
  const _MutedPill({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return DecoratedBox(
      decoration: ShapeDecoration(color: theme.colorScheme.surfaceContainerHighest, shape: const StadiumBorder()),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: SettingsMetrics.syncPillH, vertical: SettingsMetrics.syncPillV),
        child: Text(
          text,
          maxLines: 1,
          style: theme.textTheme.labelMedium!.copyWith(color: theme.colorScheme.onSurfaceVariant),
        ),
      ),
    );
  }
}
