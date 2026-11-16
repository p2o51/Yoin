/// Offline downloads in the dependency graph (Android AppContainer
/// offlineDownloads): one [OfflineDownloads] for the app, following the
/// active account, and the state the UI reads.
library;

import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/model/model.dart';
import '../data/offline/offline_downloads.dart';
import '../data/offline/offline_model.dart';
import '../data/source/music_source.dart';
import '../platform/background/background_transfer.dart';
import '../platform/storage/app_files.dart';
import '../settings/stream_quality.dart' show networkBearerProvider;
import 'offline_background.dart';

/// The app's files folder. No folder by default (tests, previews): downloads
/// stay off. main() wires the platform's (`platformOverrides`).
final appFilesHostProvider = Provider<AppFilesHost>((ref) => const NoAppFilesHost());

/// What keeps the app running while songs download (HarmonyOS: a data-transfer
/// continuous task). Nothing to hold by default (tests, desktop); main() wires
/// the platform's (`platformOverrides`).
final backgroundTransferHostProvider = Provider<BackgroundTransferHost>((ref) => const NoBackgroundTransferHost());

/// The app's downloads, every account's. Started as it is first read (the
/// app reads it as it starts, so queued downloads carry on).
final offlineDownloadsProvider = Provider<OfflineDownloads>((ref) {
  final downloads = OfflineDownloads(
    files: ref.watch(appFilesHostProvider),
    settingsValues: () => ref.read(keyValueStoreProvider),
    bearer: () => ref.read(networkBearerProvider).current(),
    account: () => activeOfflineAccount(ref),
  );
  ref.onDispose(downloads.dispose);
  // The active account decides whose downloads are listed and run.
  Future<void> follow() async {
    try {
      final profile = await ref.read(profileRepositoryProvider).activeProfile();
      if (ref.mounted) downloads.setActiveProfile(profile?.id);
    } catch (_) {
      // No store (tests): nothing is listed.
    }
  }

  ref.listen(activeSourceProvider, (_, _) => unawaited(follow()), fireImmediately: true);
  // Downloads go on with the app behind, with their progress notification (YoinDownloadService).
  final keeper = OfflineTransferKeeper(downloads: downloads, host: ref.watch(backgroundTransferHostProvider))..start();
  ref.onDispose(keeper.dispose);
  unawaited(downloads.start());
  return downloads;
});

/// The active account when it can download; null otherwise (Apple Music, none).
Future<OfflineAccount?> activeOfflineAccount(Ref ref) async {
  final source = await ref.read(activeSourceProvider.future);
  final signer = source?.downloads;
  if (source == null || signer == null) return null;
  final profile = await ref.read(profileRepositoryProvider).activeProfile();
  if (profile == null) return null;
  return OfflineAccount(profileId: profile.id, provider: source.provider, downloads: signer);
}

/// What the downloads know now; rebuilt as they change.
class OfflineStateNotifier extends Notifier<OfflineState> {
  @override
  OfflineState build() {
    final downloads = ref.watch(offlineDownloadsProvider);
    void changed() => state = downloads.state;
    downloads.addListener(changed);
    ref.onDispose(() => downloads.removeListener(changed));
    return downloads.state;
  }
}

final offlineStateProvider = NotifierProvider<OfflineStateNotifier, OfflineState>(OfflineStateNotifier.new);

/// The active account's downloads.
final offlineLibraryProvider = Provider<OfflineLibrary>((ref) => ref.watch(offlineStateProvider).library);

/// Whether the active account downloads (Capability.offlineDownload) and
/// this build keeps files; the Download rows show only then.
final canDownloadProvider = Provider<bool>(
  (ref) =>
      ref.watch(activeSourceProvider).value?.downloads != null &&
      ref.watch(offlineStateProvider.select((s) => !s.ready || s.available)),
);

/// The downloaded copy [songId] plays from, when the active account has it
/// all; null otherwise (and wherever downloads aren't set up).
String? offlinePlayableUri(Ref ref, MusicSource source, MediaId songId) => offlinePlayable(ref, source, songId)?.uri;

/// [offlinePlayableUri] with what the file holds: the quality it was kept at
/// (Android pins the download's StreamDelivery when the file opens).
({String uri, StreamDelivery delivery})? offlinePlayable(Ref ref, MusicSource source, MediaId songId) {
  if (source.downloads == null) return null;
  try {
    final downloads = ref.read(offlineDownloadsProvider);
    final uri = downloads.playableUri(songId);
    if (uri == null) return null;
    final quality = downloads.playableQuality(songId) ?? DownloadSettings.defaultQuality;
    return (uri: uri, delivery: StreamDelivery(maxBitRateKbps: quality.maxBitRateKbps, format: quality.format));
  } catch (_) {
    return null;
  }
}
