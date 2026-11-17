import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../data/model/model.dart';
import '../data/offline/offline_downloads.dart';
import '../data/offline/offline_model.dart';
import 'offline_providers.dart';
import 'offline_strings.dart';

/// The Download / Remove download row of an album's ▾ menu, a playlist's ▾
/// menu and a song's sheet (Android OfflineToggle): what it says now and what
/// a tap does. Downloading, a tap removes what came so far.
@immutable
final class OfflineToggle {
  const OfflineToggle({required this.status, required this.label, required this.icon, required this.onTap});

  final OfflineStatus status;
  final String label;
  final IconData icon;
  final VoidCallback onTap;
}

/// The row's label and symbol for [status] (rememberOfflineToggle).
(String, IconData) offlineToggleFace(OfflineStatus status, OfflineStrings strings) => switch (status) {
  OfflineNone() => (strings.download, YoinSymbols.download),
  OfflineDownloading(:final done, :final total) => (strings.downloading(done, total), YoinSymbols.close),
  OfflineDownloaded() => (strings.removeDownload, YoinSymbols.downloadDone),
  OfflineFailed() => (strings.retry, YoinSymbols.download),
};

/// What a tap on the row does with [status]: download (or retry) [tracks] as
/// [collection], or remove it. [onMessage] says what was queued or removed;
/// the work runs in [downloads], so a page closed right after the tap still
/// queues.
Future<void> runOfflineToggle({
  required OfflineDownloads downloads,
  required OfflineStatus status,
  required OfflineCollection collection,
  required Future<List<Track>> Function() tracks,
  required OfflineStrings strings,
  required ValueChanged<String> onMessage,
}) async {
  switch (status) {
    case OfflineNone() || OfflineFailed():
      List<Track> list;
      try {
        list = await tracks();
      } catch (e) {
        debugPrint('YoinOffline: no songs to download: $e');
        return;
      }
      final queued = await downloads.download(list, collection);
      if (queued <= 0) return;
      onMessage(await downloads.waitsForWifi() ? strings.queuedWaitingWifi(queued) : strings.queued(queued));
    case OfflineDownloading() || OfflineDownloaded():
      await downloads.remove(collection.key);
      onMessage(strings.removed);
  }
}

/// [collection]'s toggle, or null where the active account can't download
/// (Apple Music) or the page hasn't loaded what to download. Watches the
/// download's status, so call it from a build. [tracks] is read on tap.
OfflineToggle? watchOfflineToggle(
  WidgetRef ref,
  BuildContext context, {
  required OfflineCollection? collection,
  required Future<List<Track>> Function() tracks,
  required ValueChanged<String> onMessage,
}) {
  if (collection == null || !ref.watch(canDownloadProvider)) return null;
  final status = ref.watch(offlineLibraryProvider.select((library) => library.statusOf(collection.key)));
  final strings = OfflineStrings.of(context);
  final downloads = ref.read(offlineDownloadsProvider);
  final (label, icon) = offlineToggleFace(status, strings);
  return OfflineToggle(
    status: status,
    label: label,
    icon: icon,
    onTap: () => runOfflineToggle(
      downloads: downloads,
      status: status,
      collection: collection,
      tracks: tracks,
      strings: strings,
      onMessage: onMessage,
    ),
  );
}
