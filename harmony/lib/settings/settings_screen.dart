import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import '../ui/component/corner_mask.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../data/backup/playlist_backup_providers.dart';
import '../data/model/media_id.dart';
import '../neodb/neodb_settings_screen.dart';
import '../offline/downloads_screen.dart';
import '../offline/offline_providers.dart';
import '../offline/offline_strings.dart';
import '../offline/stream_cache_providers.dart';
import '../playback/playback_controller.dart';
import '../shell/bar_suppression.dart';
import '../shell/chrome_back.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/component/meta_line.dart';
import '../ui/component/spring_cross_fade.dart';
import '../ui/component/yoin_dialog.dart';
import '../ui/component/yoin_dropdown_menu.dart';
import '../ui/component/yoin_loading_indicator.dart';
import '../ui/adaptive/yoin_window_info.dart';
import '../ui/navigation/yoin_push_route.dart';
import 'account_identity.dart';
import 'account_switch_overlay.dart';
import 'about_screen.dart';
import 'add_account_sheet.dart';
import 'apple_music_account_screen.dart';
import 'ai_settings_screen.dart';
import 'ai_sub_pages.dart';
import 'apple_music_setup_screen.dart';
import 'backup/playlist_backup_screen.dart';
import 'backup/playlist_backup_strings.dart';
import 'backup/playlist_backups_screen.dart';
import 'appearance/appearance_group.dart';
import 'appearance/screen_corners_screen.dart';
import 'appearance/scroll_edge_screen.dart';
import 'service_intro.dart' show ServiceIntroStrings;
import 'settings_colors.dart';
import 'settings_components.dart';
import 'licenses.dart';
import '../ui/shapes/shape_path.dart';
import 'playback_cache_row.dart';
import 'settings_metrics.dart';
import 'settings_page.dart';
import 'settings_pane.dart';
import 'settings_rows.dart';
import 'settings_strings.dart';
import 'settings_view_model.dart';
import 'stream_quality_screen.dart';
import 'subsonic_setup_screen.dart';
import 'sync/cloud_sync_entry.dart';
import 'sync/cloud_sync_screen.dart';
import 'sync/cloud_sync_strings.dart';
import 'sync/cloud_sync_texts.dart';
import 'sync/cloud_sync_view_model.dart';
import 'tips.dart';
import 'volume_leveling_screen.dart';

export 'settings_pane.dart';

/// Opens Settings (PushPage). The Home avatar, Home's and Library's error
/// "Settings" and Library's settings button all come here.
Future<void> openSettings(BuildContext context) => Navigator.of(context).push(
  YoinPushRoute<void>(settings: const RouteSettings(name: settingsRouteName), builder: (_) => const SettingsScreen()),
);

/// Opens Settings with [pane]'s page on top (Android SettingsActivity's
/// focusSection, e.g. "neodb" from the album sheet's "Sign in to sync"):
/// Settings stays underneath, so back lands in Settings.
Future<void> openSettingsAt(BuildContext context, SettingsPane pane) => Navigator.of(context, rootNavigator: true).push(
  YoinPushRoute<void>(
    settings: const RouteSettings(name: settingsRouteName),
    builder: (_) => SettingsScreen(initialPane: pane),
  ),
);

/// Settings' route name (Ctrl+, doesn't stack a second Settings on the first).
const settingsRouteName = 'settings';

/// Add an account (Android AddAccountSheet): the service-choice sheet, then
/// that service's setup page. [anchorContext] is the tapped control's context.
Future<void> openAddAccount(BuildContext anchorContext) async {
  final navigator = Navigator.of(anchorContext);
  final choice = await showAddAccountSheet(anchorContext);
  switch (choice) {
    case AddAccountService.subsonic:
      await navigator.push(YoinPushRoute<void>(builder: (_) => const SubsonicSetupScreen()));
    case AddAccountService.appleMusic:
      await navigator.push(YoinPushRoute<void>(builder: (_) => const AppleMusicSetupScreen()));
    case null:
      break;
  }
}

/// Settings (Android SettingsScreen): the account switcher, then Help and
/// about. Tap an account to use it (the one in use, or one that needs
/// attention, opens its page); its menu manages, uses or removes it; Add
/// opens the service's setup page. Its own page with no bar.
///
/// On a wide window (≥ 840) it is a list-detail like Android's SettingsTablet:
/// the list on the left (a third), the page it opens on the right, and an
/// empty seal until something is picked.
class SettingsScreen extends ConsumerStatefulWidget {
  const SettingsScreen({super.key, this.initialPane});

  /// A page to open over (or, wide, beside) the list at once ([openSettingsAt]).
  final SettingsPane? initialPane;

  @override
  ConsumerState<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends ConsumerState<SettingsScreen> {
  final _scroll = ScrollController();
  SettingsPane? _pane;

  /// While the pane shows a page opened from another pane page (Streaming
  /// quality from an account), back belongs to the pane: the page's back
  /// button (maybePop) and system back both return to [SettingsPane.parent]
  /// instead of leaving Settings. Shared infrastructure, no PopScope.
  /// Arbitrated: a player opened over Settings answers first (its entry
  /// shares this route), and one back never does both.
  late final ChromeBackEntry _paneBack = ChromeBackEntry(
    () => scheduleMicrotask(_paneUp),
    owns: () => true,
    priority: ChromeBackPriority.page,
  );

  @override
  void initState() {
    super.initState();
    final initial = widget.initialPane;
    if (initial != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _open(initial);
      });
    }
  }

  @override
  void dispose() {
    _paneBack.dispose();
    _scroll.dispose();
    super.dispose();
  }

  void _paneUp() {
    final parent = _pane?.parent;
    if (!mounted || parent == null) return;
    setState(() => _pane = parent);
  }

  /// Holds back exactly while a wide window shows a pane sub-page.
  void _syncPaneBack(SettingsPane? shown) {
    final route = ModalRoute.of(context);
    final wantsBack = shown?.parent != null && (route?.isCurrent ?? false);
    if (wantsBack && !_paneBack.isAttached) {
      _paneBack.attach(Navigator.of(context));
    } else if (!wantsBack && _paneBack.isAttached) {
      _paneBack.detach();
    }
  }

  bool get _wide {
    final window = YoinWindowInfo.of(context);
    return !window.isCompactHeight && window.layoutMode == LayoutMode.wide;
  }

  /// The listed account's provider (its page follows it); null when not listed.
  String? _providerOf(String profileId) => switch (ref.read(settingsViewModelProvider)) {
    SettingsContent(:final accounts) => accounts.where((a) => a.id == profileId).firstOrNull?.provider,
    _ => null,
  };

  void _open(SettingsPane pane) {
    if (_wide) {
      setState(() => _pane = pane);
      return;
    }
    final navigator = Navigator.of(context);
    switch (pane) {
      case AccountPane(:final profileId):
        if (_providerOf(profileId) == MediaId.providerAppleMusic) {
          openAppleMusicManage(context, profileId);
        } else {
          openSubsonicManage(context, profileId);
        }
      case AiPane():
        navigator.push(YoinPushRoute<void>(builder: (_) => const AiSettingsScreen()));
      case AiTiersPane():
        navigator.push(YoinPushRoute<void>(builder: (_) => const AiTiersScreen()));
      case AiTryPane():
        navigator.push(YoinPushRoute<void>(builder: (_) => const AiTryScreen()));
      case AppearancePane():
        navigator.push(YoinPushRoute<void>(builder: (_) => const AppearanceScreen()));
      case SyncAndBackupsPane():
        navigator.push(YoinPushRoute<void>(builder: (_) => const SyncAndBackupsScreen()));
      case StoragePane():
        navigator.push(YoinPushRoute<void>(builder: (_) => const StorageScreen()));
      case HelpAndFeedbackPane():
        navigator.push(YoinPushRoute<void>(builder: (_) => const HelpAndFeedbackScreen()));
      case TipsPane():
        navigator.push(YoinPushRoute<void>(builder: (_) => const TipsScreen()));
      case LicensesPane():
        navigator.push(YoinPushRoute<void>(builder: (_) => const LicensesScreen()));
      case AboutPane():
        navigator.push(YoinPushRoute<void>(builder: (_) => const AboutScreen()));
      case CloudSyncPane():
        navigator.push(YoinPushRoute<void>(builder: (_) => const CloudSyncScreen()));
      case PlaylistBackupsPane():
        navigator.push(YoinPushRoute<void>(builder: (_) => const PlaylistBackupsScreen()));
      case PlaylistBackupPane(:final backupId):
        navigator.push(YoinPushRoute<void>(builder: (_) => PlaylistBackupScreen(backupId: backupId)));
      case NeoDbPane():
        navigator.push(YoinPushRoute<void>(builder: (_) => const NeoDbSettingsScreen()));
      case StreamQualityPane(:final profileId):
        navigator.push(YoinPushRoute<void>(builder: (_) => StreamQualityScreen(profileId: profileId)));
      case DownloadsPane():
        navigator.push(YoinPushRoute<void>(builder: (_) => const DownloadsScreen()));
      case VolumeLevelingPane():
        navigator.push(YoinPushRoute<void>(builder: (_) => const VolumeLevelingScreen()));
      case ScrollEdgePane():
        navigator.push(YoinPushRoute<void>(builder: (_) => const ScrollEdgeScreen()));
      case ScreenCornersPane():
        navigator.push(YoinPushRoute<void>(builder: (_) => const ScreenCornersScreen()));
    }
  }

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final state = ref.watch(settingsViewModelProvider);
    final wide = _wide;
    // An account that went away takes its page with it.
    var pane = wide ? _pane : null;
    final paneProfile = switch (pane) {
      AccountPane(:final profileId) ||
      StreamQualityPane(:final profileId) ||
      DownloadsPane(:final profileId) ||
      VolumeLevelingPane(:final profileId) => profileId,
      _ => null,
    };
    if (paneProfile != null && state is SettingsContent && !state.accounts.any((a) => a.id == paneProfile)) {
      pane = null;
    }
    final shownPane = pane;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _syncPaneBack(shownPane);
    });
    final list = ListView(
      controller: _scroll,
      padding: EdgeInsets.fromLTRB(
        SettingsMetrics.pageHorizontal,
        SettingsMetrics.pageTop,
        SettingsMetrics.pageHorizontal,
        SettingsMetrics.pageBottom + MediaQuery.paddingOf(context).bottom,
      ),
      children: [
        SettingsHeadline(title: strings.title),
        SpringCrossFade(
          childKey: state is SettingsContent,
          fit: StackFit.loose,
          child: switch (state) {
            SettingsLoading() => const Center(child: YoinLoadingIndicator()),
            SettingsContent() => Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  _AccountsSection(state: state, onOpen: _open),
                  const SizedBox(height: SettingsMetrics.sectionGap),
                  _FeaturesGroup(open: pane, listDetail: wide, onOpen: _open),
                  const SizedBox(height: SettingsMetrics.sectionGap),
                  _SyncStorageGroup(state: state, open: pane, listDetail: wide, onOpen: _open),
                  const SizedBox(height: SettingsMetrics.sectionGap),
                  _HelpAboutGroup(open: pane, listDetail: wide, onOpen: _open),
                ],
              ),
          },
        ),
      ],
    );
    final page = ChromeBarSuppression(
      child: Scaffold(
        backgroundColor: wide ? settingsListPaneColor(colors) : settingsPageColor(colors),
        appBar: SettingsBar(
          title: strings.title,
          controller: _scroll,
          titleTop: SettingsBar.headlineOffset,
          titleHeight: SettingsBar.lineHeight(context, Theme.of(context).textTheme.displaySmall!),
        ),
        body: SafeArea(
          top: false,
          bottom: false,
          child: wide
              ? _ListDetail(
                  list: SettingsSeam(controller: _scroll, child: list),
                  pane: pane,
                  paneProvider: paneProfile == null ? null : _providerOf(paneProfile),
                  onOpen: _open,
                  onDone: () => setState(() => _pane = null),
                )
              : SettingsSeam(controller: _scroll, child: list),
        ),
      ),
    );
    // The account switch's blocking overlay covers the whole page, both panes too.
    return Stack(fit: StackFit.expand, children: [page, const AccountSwitchOverlay()]);
  }
}

/// The two panes: the list a third of the window (never under 360), the page
/// on the right on its own panel in the page colour, cross-fading as the
/// pick changes.
class _ListDetail extends StatelessWidget {
  const _ListDetail({
    required this.list,
    required this.pane,
    required this.onOpen,
    required this.onDone,
    this.paneProvider,
  });

  final Widget list;
  final SettingsPane? pane;

  /// The pane account's provider: an account's page is its service's.
  final String? paneProvider;
  final OpenSettingsPane onOpen;
  final VoidCallback onDone;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final window = YoinWindowInfo.of(context);
    final listWidth = math.max(SettingsMetrics.splitListMinWidth, window.width * SettingsMetrics.splitListFraction);
    final pane = this.pane;
    final page = switch (pane) {
      null => const _PanePlaceholder(),
      AccountPane(:final profileId) when paneProvider == MediaId.providerAppleMusic => AppleMusicAccountScreen(
        key: ValueKey(profileId),
        profileId: profileId,
        inPane: true,
      ),
      AccountPane(:final profileId) => SubsonicSetupScreen(
        key: ValueKey(profileId),
        profileId: profileId,
        inPane: true,
        onDone: onDone,
      ),
      AiPane() => const AiSettingsScreen(inPane: true),
      AiTiersPane() => const AiTiersScreen(inPane: true),
      AiTryPane() => const AiTryScreen(inPane: true),
      AppearancePane() => const AppearanceScreen(inPane: true),
      SyncAndBackupsPane() => const SyncAndBackupsScreen(inPane: true),
      StoragePane() => const StorageScreen(inPane: true),
      HelpAndFeedbackPane() => const HelpAndFeedbackScreen(inPane: true),
      TipsPane() => const TipsScreen(inPane: true),
      LicensesPane() => const LicensesScreen(inPane: true),
      AboutPane() => const AboutScreen(inPane: true),
      CloudSyncPane() => const CloudSyncScreen(inPane: true),
      PlaylistBackupsPane() => const PlaylistBackupsScreen(inPane: true),
      PlaylistBackupPane(:final backupId) =>
        PlaylistBackupScreen(key: ValueKey(('backup', backupId)), backupId: backupId, inPane: true),
      NeoDbPane() => const NeoDbSettingsScreen(inPane: true),
      StreamQualityPane(:final profileId) => StreamQualityScreen(key: ValueKey(('quality', profileId)), profileId: profileId, inPane: true),
      DownloadsPane(:final profileId) => DownloadsScreen(key: ValueKey(('downloads', profileId)), inPane: true),
      VolumeLevelingPane(:final profileId) => VolumeLevelingScreen(key: ValueKey(('leveling', profileId)), inPane: true),
      ScrollEdgePane() => const ScrollEdgeScreen(inPane: true),
      ScreenCornersPane() => const ScreenCornersScreen(inPane: true),
    };
    return Row(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SizedBox(width: listWidth, child: list),
        Expanded(
          child: Padding(
            padding: EdgeInsets.only(
              right: SettingsMetrics.pageHorizontal,
              bottom: SettingsMetrics.pageHorizontal + MediaQuery.paddingOf(context).bottom,
            ),
            // The page scrolls under the panel's corners: clipped by the
            // superellipse clip itself (as covers are), with the panel colour
            // painted inside it. Material's shape clip left the rows square
            // past the corners on the MateBook, with a dark seam at the edge.
            child: SettingsPanePanel(
              color: settingsPageColor(colors),
              child: YoinWindowScope(
                info: window.forPaneWidth(window.width - listWidth),
                // A page here opens the next one here too (Streaming quality).
                child: SettingsPaneScope(
                  open: onOpen,
                  child: SpringCrossFade(childKey: pane ?? 'placeholder', child: page),
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }
}

/// The right pane's panel: [child] clipped to the panel's superellipse, on
/// [color] painted inside the same clip (no edge where the two could part).
class SettingsPanePanel extends StatelessWidget {
  const SettingsPanePanel({super.key, required this.color, required this.child});

  final Color color;
  final Widget child;

  @override
  // Corners painted with the list pane's colour instead of a clip: on
  // HarmonyOS clipping the pane left a dark seam along the clip edge and let
  // row layers past the round (QA r2_29, r3_14).
  Widget build(BuildContext context) => ColoredBox(
    color: color,
    child: CornerMask(
      radius: ShapeTokens.panel,
      topStart: true,
      bottomStart: true,
      topEnd: true,
      bottomEnd: true,
      fill: settingsListPaneColor(Theme.of(context).colorScheme),
      // Ink (row splashes) still needs a Material; a transparent one paints nothing.
      child: Material(type: MaterialType.transparency, child: child),
    ),
  );
}

/// The right pane before anything is picked (SettingsPlaceholderPane): an
/// outlined twelve-sided cookie and one quiet line.
class _PanePlaceholder extends StatelessWidget {
  const _PanePlaceholder();

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          DecoratedBox(
            decoration: ShapeDecoration(
              shape: PolygonBorder(
                MaterialShapes.cookie12Sided,
                side: BorderSide(color: colors.outlineVariant, width: SettingsMetrics.placeholderStroke),
              ),
            ),
            child: const SizedBox.square(dimension: SettingsMetrics.placeholderMark),
          ),
          const SizedBox(height: SettingsMetrics.placeholderGap),
          Text(
            SettingsStrings.of(context).placeholder,
            style: theme.textTheme.bodyMedium!.copyWith(color: colors.onSurfaceVariant),
          ),
        ],
      ),
    );
  }
}

/// Features and appearance (v4): AI features and Appearance.
class _FeaturesGroup extends StatelessWidget {
  const _FeaturesGroup({required this.open, required this.listDetail, required this.onOpen});

  final SettingsPane? open;
  final bool listDetail;
  final OpenSettingsPane onOpen;

  @override
  Widget build(BuildContext context) {
    final chevron = listDetail
        ? Icon(YoinSymbols.chevronRight, color: Theme.of(context).colorScheme.onSurfaceVariant)
        : null;
    return SettingsGroup(
      title: SettingsStrings.of(context).sectionFeatures,
      selected: {
        if (open is AiPane || open is AiTiersPane || open is AiTryPane) 0,
        if (open is AppearancePane || open is ScrollEdgePane || open is ScreenCornersPane) 1,
      },
      children: [
        AiSettingsRow(trailing: chevron, onOpen: () => onOpen(const AiPane())),
        AppearanceEntryRow(trailing: chevron, onOpen: () => onOpen(const AppearancePane())),
      ],
    );
  }
}

/// Settings › Sync and storage (v4): Sync and backups + Storage.
class _SyncStorageGroup extends ConsumerWidget {
  const _SyncStorageGroup({
    required this.state,
    required this.open,
    required this.listDetail,
    required this.onOpen,
  });

  final SettingsContent state;
  final SettingsPane? open;
  final bool listDetail;
  final OpenSettingsPane onOpen;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = SettingsStrings.of(context);
    final cloudStrings = CloudSyncStrings.of(context);
    final sync = ref.watch(cloudSyncViewModelProvider.select((p) => p.sync));
    final backupsCount = ref.watch(playlistBackupsProvider).value?.length ?? 0;
    final cacheBytes = ref.watch(streamCacheSizeProvider);
    final downloadBytes = ref.watch(offlineStateProvider.select((s) => s.downloadBytes));
    final offlineStrings = OfflineStrings.of(context);
    final activeSubsonic = state.accounts
        .where((a) => a.isActive && a.provider == MediaId.providerSubsonic)
        .isNotEmpty;
    final hasDownloads = activeSubsonic && downloadBytes > 0;
    final chevron = listDetail
        ? Icon(YoinSymbols.chevronRight, color: Theme.of(context).colorScheme.onSurfaceVariant)
        : null;
    return SettingsGroup(
      title: cloudStrings.sectionSyncStorage,
      selected: {
        if (open is SyncAndBackupsPane ||
            open is CloudSyncPane ||
            open is NeoDbPane ||
            open is PlaylistBackupsPane ||
            open is PlaylistBackupPane)
          0,
        if (open is StoragePane || (open is DownloadsPane && (open as DownloadsPane).fromStorage)) 1,
      },
      children: [
        SyncClock(
          builder: (context, nowMs) {
            final text = cloudSyncEntrySummary(sync, nowMs, cloudStrings, date: localizedSyncDate(context));
            final syncLabel = text.detail ?? text.status;
            return SettingsItem(
              icon: YoinSymbols.cloud,
              title: strings.syncBackupsTitle,
              summaryContent: MetaLine(
                groups: [
                  MetaPlain(syncLabel),
                  if (backupsCount > 0)
                    MetaStat('$backupsCount', strings.syncUnitBackups(backupsCount)),
                ],
                style: Theme.of(context).textTheme.bodyMedium,
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
              trailing: chevron,
              onTap: () => onOpen(const SyncAndBackupsPane()),
            );
          },
        ),
        SettingsItem(
          icon: YoinSymbols.storage,
          title: strings.storageTitle,
          summary: hasDownloads
              ? null
              : (cacheBytes > 0
                    ? strings.storagePlaybackCacheSummary(offlineStrings.bytes(cacheBytes))
                    : offlineStrings.bytes(0)),
          summaryContent: hasDownloads
              ? MetaLine(
                  groups: [
                    MetaPlain(strings.storageDownloadedShort(offlineStrings.bytes(downloadBytes))),
                    MetaMuted(strings.storageCacheShort(offlineStrings.bytes(cacheBytes))),
                  ],
                  style: Theme.of(context).textTheme.bodyMedium,
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                )
              : null,
          trailing: chevron,
          onTap: () => onOpen(const StoragePane()),
        ),
      ],
    );
  }
}

/// Settings › Sync and storage › Sync and backups (v4): Cloud sync, NeoDB, and
/// Playlist cloud backup in one segmented group.
class SyncAndBackupsScreen extends StatelessWidget {
  const SyncAndBackupsScreen({super.key, this.inPane = false});

  final bool inPane;

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final openInPane = SettingsPaneScope.maybeOf(context);
    final chevron = openInPane != null
        ? Icon(YoinSymbols.chevronRight, color: Theme.of(context).colorScheme.onSurfaceVariant)
        : null;
    void open(SettingsPane pane, Widget Function() page) {
      if (openInPane != null) {
        openInPane(pane);
      } else {
        Navigator.of(context).push(YoinPushRoute<void>(builder: (_) => page()));
      }
    }

    return SettingsSubPage(
      title: strings.syncBackupsTitle,
      inPane: inPane,
      children: [
        SettingsSegments(
          children: [
            CloudSyncEntryRow(
              showChevron: openInPane != null,
              onTap: () => open(const CloudSyncPane(), () => const CloudSyncScreen()),
            ),
            NeoDbSettingsRow(
              trailing: chevron,
              onOpen: () => open(const NeoDbPane(), () => const NeoDbSettingsScreen()),
            ),
            SettingsItem(
              icon: YoinSymbols.playlist,
              title: PlaylistBackupStrings.of(context).title,
              trailing: chevron,
              onTap: () => open(const PlaylistBackupsPane(), () => const PlaylistBackupsScreen()),
            ),
          ],
        ),
      ],
    );
  }
}

/// Settings › Sync and storage › Storage (v4): Playback cache and the active
/// Subsonic account's Downloads.
class StorageScreen extends ConsumerWidget {
  const StorageScreen({super.key, this.inPane = false});

  final bool inPane;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = SettingsStrings.of(context);
    final offlineStrings = OfflineStrings.of(context);
    final settingsState = ref.watch(settingsViewModelProvider);
    final activeSubsonic = switch (settingsState) {
      SettingsContent(:final accounts) =>
        accounts.where((a) => a.isActive && a.provider == MediaId.providerSubsonic).firstOrNull,
      _ => null,
    };
    final offline = ref.watch(offlineStateProvider);
    final openInPane = SettingsPaneScope.maybeOf(context);
    return SettingsSubPage(
      title: strings.storageTitle,
      inPane: inPane,
      children: [
        SettingsSegments(
          children: [
            const PlaybackCacheRow(),
            if (activeSubsonic != null)
              SettingsItem(
                icon: YoinSymbols.download,
                title: offlineStrings.title,
                summary: downloadQualityLabel(strings, offline.settings.quality),
                summaryDetail: downloadsSummaryDetail(offlineStrings, offline.downloadBytes),
                trailing: openInPane != null
                    ? Icon(YoinSymbols.chevronRight, color: Theme.of(context).colorScheme.onSurfaceVariant)
                    : null,
                onTap: () {
                  if (openInPane != null) {
                    openInPane(DownloadsPane(activeSubsonic.id, fromStorage: true));
                  } else {
                    Navigator.of(context).push(YoinPushRoute<void>(builder: (_) => const DownloadsScreen()));
                  }
                },
              ),
          ],
        ),
      ],
    );
  }
}

/// Settings › Help and about (v4): Help and feedback + About Yoin.
class _HelpAboutGroup extends ConsumerWidget {
  const _HelpAboutGroup({required this.open, required this.listDetail, required this.onOpen});

  final SettingsPane? open;
  final bool listDetail;
  final OpenSettingsPane onOpen;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = SettingsStrings.of(context);
    final read = ref.watch(tipReadProvider);
    final chevron = listDetail
        ? Icon(YoinSymbols.chevronRight, color: Theme.of(context).colorScheme.onSurfaceVariant)
        : null;
    return SettingsGroup(
      title: strings.sectionHelpAbout,
      selected: {
        if (open is HelpAndFeedbackPane || open is TipsPane) 0,
        if (open is AboutPane || open is LicensesPane) 1,
      },
      children: [
        SettingsItem(
          icon: YoinSymbols.comment,
          title: strings.helpFeedbackTitle,
          summary: helpTipsSummary(strings, read),
          trailing: chevron,
          onTap: () => onOpen(const HelpAndFeedbackPane()),
        ),
        SettingsItem(
          icon: YoinSymbols.info,
          title: strings.aboutYoinTitle,
          summary: strings.aboutVersion(yoinVersionName),
          trailing: chevron,
          onTap: () => onOpen(const AboutPane()),
        ),
      ],
    );
  }
}

class _AccountsSection extends StatelessWidget {
  const _AccountsSection({required this.state, required this.onOpen});

  final SettingsContent state;
  final OpenSettingsPane onOpen;

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SettingsGroupLabel(
          title: strings.sectionAccounts,
          // How many of the five accounts are kept (settings_accounts_count).
          trailingLabel: state.accounts.isEmpty
              ? null
              : ServiceIntroStrings.of(context).accountsCount(state.accounts.length, SettingsContent.maxProfiles),
        ),
        const SizedBox(height: SettingsMetrics.groupLabelGap),
        if (state.accounts.isEmpty)
          const _EmptyAccountsCard()
        else
          _AccountCardRow(accounts: state.accounts, canAdd: state.canAddProfile, onOpen: onOpen),
      ],
    );
  }
}

/// No account yet: a primary-container panel with the one thing to do.
class _EmptyAccountsCard extends StatelessWidget {
  const _EmptyAccountsCard();

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return Material(
      color: colors.primaryContainer,
      shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(ShapeTokens.panel)),
      child: Padding(
        padding: const EdgeInsets.all(SettingsMetrics.emptyPadding),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(strings.accountsEmptyTitle, style: theme.textTheme.titleLarge!.copyWith(color: colors.onPrimaryContainer)),
            const SizedBox(height: SettingsMetrics.emptyGap),
            Builder(
              builder: (anchorContext) => FilledButton.icon(
                onPressed: () => openAddAccount(anchorContext),
                icon: const Icon(YoinSymbols.add, size: SettingsMetrics.emptyButtonIcon),
                label: Text(strings.accountsAdd),
                style: FilledButton.styleFrom(iconSize: SettingsMetrics.emptyButtonIcon),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// The account switcher. It runs edge to edge of the screen, past the page
/// padding, so cards are only ever cut by the screen edge; resting, the
/// first card sits on the page margin.
class _AccountCardRow extends StatelessWidget {
  const _AccountCardRow({required this.accounts, required this.canAdd, required this.onOpen});

  final List<AccountCard> accounts;

  /// Fewer than five accounts: the Add tile ends the row (it folds away at the limit).
  final bool canAdd;
  final OpenSettingsPane onOpen;

  /// Card height from its content, so text never clips at large font scales.
  static double _height(BuildContext context) {
    final type = Theme.of(context).textTheme;
    final scaler = MediaQuery.textScalerOf(context);
    double line(TextStyle s) => scaler.scale(s.fontSize! * (s.height ?? 1));
    return SettingsMetrics.cardFixedHeight +
        line(type.titleMedium!) * 2 +
        line(type.bodyMedium!) +
        line(type.labelMedium!);
  }

  @override
  Widget build(BuildContext context) {
    const bleed = SettingsMetrics.pageHorizontal;
    return LayoutBuilder(
      builder: (context, constraints) => SizedBox(
        height: _height(context),
        child: OverflowBox(
          minWidth: constraints.maxWidth + bleed * 2,
          maxWidth: constraints.maxWidth + bleed * 2,
          child: ListView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: bleed, vertical: SettingsMetrics.cardRowVertical),
            children: [
              for (final (i, card) in accounts.indexed) ...[
                if (i > 0) const SizedBox(width: SettingsMetrics.cardGap),
                _AccountCardTile(key: ValueKey(card.id), card: card, onOpen: onOpen),
              ],
              SpringReveal(
                key: const ValueKey('add'),
                axis: Axis.horizontal,
                visible: canAdd,
                child: const Row(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [SizedBox(width: SettingsMetrics.cardGap), _AddAccountTile()],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

enum _AccountAction { manage, use, remove }

/// One account in the switcher (ProfileCardTile). The service is the colour
/// (the account in use fills with it) plus the mark on the avatar and its
/// name on the line under the account; the avatar's shape and letter tell
/// two accounts on one service apart. The others rest a little smaller.
class _AccountCardTile extends ConsumerStatefulWidget {
  const _AccountCardTile({super.key, required this.card, required this.onOpen});

  final AccountCard card;
  final OpenSettingsPane onOpen;

  @override
  ConsumerState<_AccountCardTile> createState() => _AccountCardTileState();
}

class _AccountCardTileState extends ConsumerState<_AccountCardTile> with TickerProviderStateMixin {
  late final SpringValue _active = SpringValue(
    vsync: this,
    initial: widget.card.isActive ? 1 : 0,
    spring: SettingsSprings.effects,
  );
  late final SpringValue _scale = SpringValue(
    vsync: this,
    initial: widget.card.isActive ? 1 : SettingsMetrics.cardInactiveScale,
    spring: SettingsSprings.spatial,
  );

  @override
  void didUpdateWidget(_AccountCardTile old) {
    super.didUpdateWidget(old);
    final active = widget.card.isActive;
    final scale = active ? 1.0 : SettingsMetrics.cardInactiveScale;
    if (MediaQuery.maybeDisableAnimationsOf(context) ?? false) {
      _active.snapTo(active ? 1 : 0);
      _scale.snapTo(scale);
    } else {
      _active.animateTo(active ? 1 : 0);
      _scale.animateTo(scale);
    }
  }

  @override
  void dispose() {
    _active.dispose();
    _scale.dispose();
    super.dispose();
  }

  /// The page's overlay shows the switch's stages and a failure.
  Future<void> _switch() => ref.read(settingsViewModelProvider.notifier).switchTo(widget.card.id);

  Future<void> _options(BuildContext anchorContext) async {
    final strings = SettingsStrings.of(context);
    final card = widget.card;
    final choice = await showYoinDropdownMenu<_AccountAction>(
      context: anchorContext,
      anchor: globalRectOf(anchorContext),
      semanticLabel: strings.cdAccountOptionsCard(card.title),
      items: [
        if (card.canManage) YoinMenuItem(value: _AccountAction.manage, label: strings.accountsManage),
        if (!card.isActive) YoinMenuItem(value: _AccountAction.use, label: strings.accountsUse),
        YoinMenuItem(value: _AccountAction.remove, label: strings.accountsRemoveMenu),
      ],
    );
    if (!mounted) return;
    switch (choice) {
      case _AccountAction.manage:
        widget.onOpen(AccountPane(card.id));
      case _AccountAction.use:
        await _switch();
      case _AccountAction.remove:
        final confirmed = await _confirmRemove();
        if (confirmed != true || !mounted) break;
        final refused = strings.playerDlnaStopFailed;
        final removed = await ref.read(settingsViewModelProvider.notifier).remove(card.id);
        if (!removed && mounted) ref.read(playbackControllerProvider.notifier).showMessage(refused);
      case null:
        break;
    }
  }

  Future<bool?> _confirmRemove() {
    final strings = SettingsStrings.of(context);
    return showYoinDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(strings.removeTitle),
        content: Text(strings.removeBody(widget.card.displayName)),
        actions: [
          TextButton(onPressed: () => Navigator.of(context).pop(false), child: Text(strings.removeCancel)),
          TextButton(onPressed: () => Navigator.of(context).pop(true), child: Text(strings.removeConfirm)),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final card = widget.card;
    final identity = card.identity;
    final tone = identity.hue.tone(theme.brightness);
    final scopeHint = card.provider == MediaId.providerAppleMusic
        ? strings.accountScopeApple
        : strings.accountScopeSubsonic;
    final line = accountCardLine(identity.name, card.title, card.host, scopeHint);
    final opensPage = card.tapOpensPage;
    return AnimatedBuilder(
      animation: Listenable.merge([_active.animation, _scale.animation]),
      builder: (context, _) {
        final a = _active.value.clamp(0.0, 1.0);
        final fill = Color.lerp(settingsRowColor(colors), tone.container, a)!;
        final ink = Color.lerp(colors.onSurface, tone.onContainer, a)!;
        // Secondary text at full strength on the tinted card; the usual variant on a plain one.
        final secondary = Color.lerp(colors.onSurfaceVariant, tone.onContainer, a)!;
        return Transform.scale(
          scale: _scale.value,
          child: SizedBox(
            width: SettingsMetrics.cardWidth,
            child: Semantics(
              selected: card.isActive,
              button: opensPage || !card.isActive,
              onTapHint: opensPage ? strings.cdManageAccount : (card.isActive ? null : strings.cdSwitchAccount),
              child: Material(
                color: fill,
                shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(ShapeTokens.card)),
                clipBehavior: Clip.antiAlias,
                child: InkWell(
                  // The one in use, or one that needs attention, opens its page; any other switches.
                  onTap: opensPage ? () => widget.onOpen(AccountPane(card.id)) : (card.isActive ? null : _switch),
                  child: Padding(
                    padding: const EdgeInsetsDirectional.fromSTEB(
                      SettingsMetrics.cardPaddingStart,
                      SettingsMetrics.cardPaddingTop,
                      SettingsMetrics.cardPaddingEnd,
                      SettingsMetrics.cardPaddingBottom,
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            AccountAvatar(
                              monogram: monogramOf(card.title, identity.name),
                              shapeIndex: card.avatarShape,
                              identity: identity,
                              ringColor: fill,
                            ),
                            const Spacer(),
                            Transform.translate(
                              offset: const Offset(0, -SettingsMetrics.cardMenuLift),
                              child: Builder(
                                builder: (anchorContext) => IconButton(
                                  tooltip: strings.cdAccountOptionsCard(card.title),
                                  icon: Icon(YoinSymbols.moreVertical, size: SettingsMetrics.cardMenuIcon, color: secondary),
                                  onPressed: () => _options(anchorContext),
                                ),
                              ),
                            ),
                          ],
                        ),
                        const Spacer(),
                        // Name and service read as one cluster.
                        Padding(
                          padding: const EdgeInsetsDirectional.only(end: SettingsMetrics.cardTextEnd),
                          child: Text(
                            card.title,
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: theme.textTheme.titleMedium!.copyWith(color: ink),
                          ),
                        ),
                        if (line != null)
                          Padding(
                            padding: const EdgeInsetsDirectional.only(end: SettingsMetrics.cardTextEnd),
                            child: AccountCardScopeLine(host: line.host, scope: line.scope, color: secondary),
                          ),
                        Padding(
                          padding: const EdgeInsets.only(top: SettingsMetrics.pillTop),
                          child: card.credentialsMissing
                              ? _StatusPill(label: strings.accountCredentialsMissing)
                              : _ManagePill(
                                  label: strings.accountPillManage,
                                  isActive: card.isActive,
                                  tone: tone,
                                  onTap: () => widget.onOpen(AccountPane(card.id)),
                                ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        );
      },
    );
  }
}

/// The switcher's last tile: Add, in primary on a plain row surface.
class _AddAccountTile extends StatelessWidget {
  const _AddAccountTile();

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return SizedBox(
      width: SettingsMetrics.addTileWidth,
      child: Material(
        color: settingsRowColor(colors),
        shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(ShapeTokens.card)),
        clipBehavior: Clip.antiAlias,
        child: Builder(
          builder: (anchorContext) => InkWell(
          onTap: () => openAddAccount(anchorContext),
          child: Semantics(
            button: true,
            label: strings.accountsAdd,
            excludeSemantics: true,
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(YoinSymbols.add, size: SettingsMetrics.addTileIcon, color: colors.primary),
                const SizedBox(height: SettingsMetrics.addTileGap),
                Text(strings.accountsAddShort, style: theme.textTheme.labelLarge!.copyWith(color: colors.primary)),
              ],
            ),
          ),
          ),
        ),
      ),
    );
  }
}

/// An account's one problem, in error colours (AccountStatusPill).
class _StatusPill extends StatelessWidget {
  const _StatusPill({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return DecoratedBox(
      decoration: ShapeDecoration(color: colors.errorContainer, shape: const StadiumBorder()),
      child: Padding(
        padding: const EdgeInsets.symmetric(
          horizontal: SettingsMetrics.pillHorizontal,
          vertical: SettingsMetrics.pillVertical,
        ),
        child: Text(
          label,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: theme.textTheme.labelMedium!.copyWith(color: colors.onErrorContainer),
        ),
      ),
    );
  }
}

/// Persistent clickable "Settings ›" pill in the bottom status-pill slot when
/// an account has no error (v4): opens the account's page even on an inactive card.
class _ManagePill extends StatelessWidget {
  const _ManagePill({
    required this.label,
    required this.isActive,
    required this.tone,
    required this.onTap,
  });

  final String label;
  final bool isActive;
  final SettingsTone tone;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final fill = isActive ? tone.onContainer.withValues(alpha: 0.14) : colors.surfaceContainerHighest;
    final ink = isActive ? tone.onContainer : colors.onSurfaceVariant;
    return Material(
      color: fill,
      shape: const StadiumBorder(),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        customBorder: const StadiumBorder(),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(
            horizontal: SettingsMetrics.pillHorizontal,
            vertical: SettingsMetrics.pillVertical,
          ),
          child: Text(
            label,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: theme.textTheme.labelMedium!.copyWith(color: ink),
          ),
        ),
      ),
    );
  }
}
