import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../data/model/model.dart';
import '../data/offline/offline_model.dart';
import '../detail/detail_routes.dart';
import '../nowplaying/haptics.dart';
import '../settings/settings_metrics.dart';
import '../settings/settings_page.dart';
import '../settings/settings_pane.dart';
import '../settings/settings_rows.dart';
import '../settings/settings_strings.dart';
import '../ui/component/yoin_dialog.dart';
import '../ui/navigation/yoin_push_route.dart';
import 'offline_providers.dart';
import 'offline_strings.dart';

/// "Original" or "320 kbps" (downloadQualityLabel).
String downloadQualityLabel(SettingsStrings strings, DownloadQuality quality) {
  final kbps = quality.maxBitRateKbps;
  return kbps == null ? strings.streamQualityOriginal : strings.streamQualityKbps(kbps);
}

/// The account page's Storage row (ProfileServiceSettings): the download
/// quality and what the downloads take, opening Downloads. Only on the page
/// of the account in use, which is the one whose downloads it lists.
class DownloadsRow extends ConsumerWidget {
  const DownloadsRow({super.key, required this.profileId});

  final String profileId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = OfflineStrings.of(context);
    final state = ref.watch(offlineStateProvider);
    final quality = downloadQualityLabel(SettingsStrings.of(context), state.settings.quality);
    final bytes = state.downloadBytes;
    return SettingsItem(
      icon: YoinSymbols.download,
      title: strings.title,
      summary: bytes <= 0 ? strings.summaryEmpty(quality) : strings.summary(quality, strings.bytes(bytes)),
      onTap: () {
        // In the wide window's right pane, the page opens there; otherwise it is pushed.
        final openInPane = SettingsPaneScope.maybeOf(context);
        if (openInPane != null) {
          openInPane(DownloadsPane(profileId));
        } else {
          Navigator.of(context).push(YoinPushRoute<void>(builder: (_) => const DownloadsScreen()));
        }
      },
    );
  }
}

/// Settings › account › Storage › Downloads (Android DownloadsPage, owner
/// 2026-11-04: "default a value you can change, like Spotify's settings"), a
/// PushPage like Streaming quality: Download quality (320 by default),
/// Download using mobile data (off), how much the downloads take, and what is
/// downloaded. Each album or playlist opens its page, which plays its songs
/// from the downloads. On a wide window it is the Settings list-detail's
/// right pane ([inPane]).
class DownloadsScreen extends ConsumerWidget {
  const DownloadsScreen({super.key, this.inPane = false});

  final bool inPane;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = OfflineStrings.of(context);
    final settingsStrings = SettingsStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final state = ref.watch(offlineStateProvider);
    final downloads = ref.read(offlineDownloadsProvider);
    final settings = state.settings;
    final library = state.library;
    final note = theme.textTheme.bodyMedium!.copyWith(color: colors.onSurfaceVariant);
    final canRemoveAll = state.downloadBytes > 0 || state.songs.isNotEmpty;
    return SettingsSubPage(
      title: strings.title,
      inPane: inPane,
      // Opened from the account's page: back returns there.
      paneBack: inPane,
      children: [
        RadioGroup<DownloadQuality>(
          groupValue: settings.quality,
          onChanged: (q) {
            if (q != null) _selectQuality(downloads.setQuality, settings.quality, q);
          },
          child: SettingsGroup(
            title: strings.quality,
            children: [
              for (final quality in DownloadQuality.values)
                SettingsItem(
                  key: ValueKey(quality),
                  title: downloadQualityLabel(settingsStrings, quality),
                  summary: quality == DownloadQuality.original ? strings.qualityOriginalDetail : null,
                  semanticsRadio: true,
                  leading: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: SettingsMetrics.radioInset),
                    child: Radio<DownloadQuality>(value: quality),
                  ),
                  onTap: () => _selectQuality(downloads.setQuality, settings.quality, quality),
                ),
            ],
          ),
        ),
        const SizedBox(height: SettingsMetrics.sectionGap),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: SettingsMetrics.labelInset),
          child: Text(strings.qualityNote, style: note),
        ),
        const SizedBox(height: SettingsMetrics.sectionGap),
        SettingsSegments(
          children: [
            SettingsItem(
              icon: YoinSymbols.download,
              title: strings.mobileData,
              summary: settings.overMobileData ? null : strings.mobileDataDetail,
              onTap: () {
                YoinHaptics.contextClick();
                unawaited(downloads.setOverMobileData(!settings.overMobileData));
              },
              trailing: Switch(
                value: settings.overMobileData,
                onChanged: (on) {
                  YoinHaptics.contextClick();
                  unawaited(downloads.setOverMobileData(on));
                },
              ),
            ),
          ],
        ),
        const SizedBox(height: SettingsMetrics.sectionGap),
        SettingsGroup(
          title: strings.storage,
          children: [
            SettingsItem(icon: YoinSymbols.storage, title: strings.used, summary: strings.bytes(state.downloadBytes)),
            SettingsItem(
              key: const ValueKey('remove-all'),
              icon: YoinSymbols.deleteSweep,
              title: strings.removeAll,
              summary: strings.removeAllDetail,
              onTap: canRemoveAll ? () => _confirmRemoveAll(context, ref) : null,
            ),
          ],
        ),
        const SizedBox(height: SettingsMetrics.sectionGap),
        SettingsGroup(
          title: strings.list,
          children: [
            if (library.entries.isEmpty)
              Padding(
                padding: const EdgeInsets.all(SettingsMetrics.rowPaddingHorizontal),
                child: Text(strings.listEmpty, style: note),
              ),
            for (final entry in library.entries) DownloadEntryRow(key: ValueKey(entry.collection.key), entry: entry),
          ],
        ),
      ],
    );
  }

  static void _selectQuality(
    Future<void> Function(DownloadQuality) set,
    DownloadQuality current,
    DownloadQuality quality,
  ) {
    if (quality == current) return;
    YoinHaptics.contextClick();
    unawaited(set(quality));
  }

  Future<void> _confirmRemoveAll(BuildContext context, WidgetRef ref) async {
    final strings = OfflineStrings.of(context);
    final downloads = ref.read(offlineDownloadsProvider);
    final confirmed = await showYoinDialog<bool>(
      context: context,
      builder: (dialog) => AlertDialog(
        title: Text(strings.removeAll),
        content: Text(strings.removeAllBody),
        actions: [
          TextButton(onPressed: () => Navigator.of(dialog).pop(false), child: Text(strings.cancel)),
          TextButton(
            onPressed: () {
              YoinHaptics.reject();
              Navigator.of(dialog).pop(true);
            },
            style: TextButton.styleFrom(foregroundColor: Theme.of(dialog).colorScheme.error),
            child: Text(strings.removeAllConfirm),
          ),
        ],
      ),
    );
    if (confirmed == true) await downloads.removeAll();
  }
}

/// One downloaded album, playlist or song (DownloadEntryRow): its kind's
/// symbol, its name, its owner and how far it is; the album or playlist opens
/// its page, and the close button removes it.
class DownloadEntryRow extends ConsumerWidget {
  const DownloadEntryRow({super.key, required this.entry});

  final OfflineEntry entry;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = OfflineStrings.of(context);
    final collection = entry.collection;
    final icon = switch (collection.kind) {
      OfflineCollectionKind.album => YoinSymbols.album,
      OfflineCollectionKind.playlist => YoinSymbols.playlist,
      OfflineCollectionKind.song => YoinSymbols.musicNote,
    };
    final status = switch (entry.status) {
      OfflineDownloading(:final done, :final total) => strings.entryProgress(done, total),
      OfflineFailed() => strings.entryFailed,
      _ => strings.entrySongs(entry.total, strings.bytes(entry.bytes)),
    };
    final subtitle = collection.subtitle?.trim();
    final id = MediaId.tryParse(collection.id);
    final VoidCallback? open = switch ((collection.kind, id)) {
      (OfflineCollectionKind.album, final id?) => () => DetailRoutes.openAlbum(context, id),
      (OfflineCollectionKind.playlist, final id?) => () => DetailRoutes.openPlaylist(context, id),
      _ => null,
    };
    return SettingsItem(
      icon: icon,
      title: collection.title,
      summary: [if (subtitle != null && subtitle.isNotEmpty) subtitle, status].join(' · '),
      onTap: open,
      trailing: IconButton(
        tooltip: strings.entryRemove,
        icon: const Icon(YoinSymbols.close),
        onPressed: () {
          YoinHaptics.reject();
          unawaited(ref.read(offlineDownloadsProvider).remove(collection.key));
        },
      ),
    );
  }
}
