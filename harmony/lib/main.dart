import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'app/platform_overrides.dart';
import 'app/providers.dart';
import 'cards/play_card.dart';
import 'data/applemusic/apple_music_name_region.dart';
import 'continueon/continue_on_card.dart';
import 'continueon/continue_on_controller.dart';
import 'data/applemusic/apple_music_source.dart';
import 'data/cache/cover_cache.dart';
import 'data/backup/playlist_backup_providers.dart';
import 'data/sync/cloud_sync_providers.dart';
import 'data/profile/profile.dart';
import 'data/subsonic/subsonic_music_source.dart';
import 'home/home_bubble_tips.dart';
import 'landing/landing_host.dart';
import 'neodb/neodb_providers.dart';
import 'nowplaying/haptics.dart';
import 'offline/offline_providers.dart';
import 'platform/motion/system_motion.dart';
import 'platform/posture/display_posture.dart';
import 'platform/window/window_caption.dart';
import 'platform/yoin_platform.dart';
import 'playback/play_trace.dart';
import 'playback/playback_controller.dart';
import 'settings/backup/restore_live_update.dart';
import 'settings/stream_quality.dart';
import 'shell/app_chrome.dart';
import 'shell/shell_screen.dart';
import 'shell/shell_state.dart';
import 'theme/playback_theme.dart';
import 'theme/system_bars.dart';
import 'ui/adaptive/edge_split.dart';
import 'ui/adaptive/yoin_window_info.dart';
import 'ui/component/artwork_image.dart';
import 'ui/component/artwork_retry.dart';
import 'ui/component/system_reduce_motion_scope.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // Edge to edge: content runs under the status and navigation bars.
  SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
  // The ONE place the platform is chosen: HarmonyOS channels, or the
  // Windows / desktop stand-ins (docs/windows.md).
  final platform = YoinPlatform.forHost();
  // Named haptics are static calls (YoinHaptics); they play on the platform's vibrator.
  YoinHaptics.install(platform.haptics);
  // Settings › Help and about › Export log: from here on, playback events are kept (cacheDir/playtrace.log).
  PlayTrace.shared.install(platform.appFiles.cacheDirectory);
  PlayTrace.shared.log('app launch');
  // Every cover reads through one disk cache under the cache folder, and a
  // failed one asks again when the network recovers or the app comes back.
  ArtworkImages.install(
    CoverDiskCache(directory: platform.appFiles.cacheDirectory, freeBytes: platform.appFiles.cacheFreeBytes),
  );
  ArtworkRetryWatcher.start(platform.networkBearer);
  // Reduce motion is known before the first frame, so a cold start under 减弱动画 or power saving skips
  // Home's intro bloom (Android reads it synchronously); a plugin that never answers can't hold the launch.
  await platform.systemMotion.read().timeout(
    systemMotionFirstReadTimeout,
    onTimeout: () => SystemMotionSettings.normal,
  );
  runApp(
    ProviderScope(
      overrides: [
        ...platformOverrides(platform),
        musicSourceFactoryProvider.overrideWith(
          (ref) => (credentials) => switch (credentials) {
            // Settings › Streaming quality decides each song's stream.
            SubsonicCredentials() => SubsonicMusicSource(credentials, streamPlanner: subsonicStreamPlanner(ref)),
            // Settings › the account's page › Song names decides its names.
            AppleMusicCredentials() => AppleMusicSource(
              credentials,
              nameRegion: appleMusicNameRegionReader(
                () => ref.read(profileRepositoryProvider),
                () => ref.read(keyValueStoreProvider),
                credentials,
              ),
            ),
          },
        ),
      ],
      child: YoinApp(windowCaption: platform.windowCaption),
    ),
  );
}

class YoinApp extends ConsumerWidget {
  const YoinApp({super.key, this.windowCaption = const ChannelWindowCaptionHost()});

  /// The platform's window caption host (HarmonyOS 2in1: title bar hidden).
  final WindowCaptionHost windowCaption;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // The playback chain lives as long as the app, with or without a bar or
    // player on screen: scrobbling, play history, rehydrating the queue and
    // the system's "open Now Playing" all run in it.
    ref.listen(playbackControllerProvider, (_, _) {});
    // The Play service card follows the active account's listening for as
    // long as the app runs, and delivers card taps (lib/cards/play_card.dart).
    ref.listen(playCardPublisherProvider, (_, _) {});
    // Downloads left queued carry on as the app starts (Android ensureStarted).
    ref.listen(offlineDownloadsProvider, (_, _) {});
    // Cloud sync runs with the app (a cycle on coming to the front, an upload on leaving).
    ref.listen(cloudSyncLifecycleProvider, (_, _) {});
    // Playlist backups follow their playlists each time the app comes to the front (never in the background).
    ref.listen(playlistBackupFollowerProvider, (_, _) {});
    // A playlist restore's notification, tapped, opens its backup's page.
    ref.listen(restoreNoticeTapsProvider, (_, _) {});
    // After a Phone Clone or cloud restore, the old phone's NeoDB dirty flags go (once per launch).
    ref.listen(neoDbRestoreGuardProvider, (_, _) {});
    // Continue On lives with the app: it offers the hand-off while a song is
    // loaded and takes one this device is started or brought back with.
    ref.listen(continueOnProvider, (_, _) {});
    // The playing cover themes the whole app, in ONE cut (no theme tween:
    // owner 2026-11-04); equal schemes keep the same ThemeData instance.
    final seed = ref.watch(playbackThemeProvider.select((t) => t.seed));
    return MaterialApp(
      title: 'Yoin',
      debugShowCheckedModeBanner: false,
      theme: playbackThemeData(seed, Brightness.light),
      darkTheme: playbackThemeData(seed, Brightness.dark),
      themeMode: ThemeMode.system,
      themeAnimationDuration: Duration.zero,
      supportedLocales: const [Locale('en'), Locale('zh')],
      localizationsDelegates: GlobalMaterialLocalizations.delegates,
      navigatorKey: appNavigatorKey,
      // Inside the theme (the builder's own context is above it), so the
      // status-bar icons follow light / dark.
      builder: (context, child) => Builder(
        // The system's reduce motion (HarmonyOS 减弱动画, animations off) as disableAnimations for every
        // reduced path below, followed while the app runs (Android MotionCapabilityProvider).
        builder: (context) => SystemReduceMotionScope(
          child: YoinWindowCaption(
            host: windowCaption,
            child: YoinSystemBars(
              // The first-run landing covers the whole chrome (bar and player included).
              child: _PostureWindowScope(
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                    LandingHost(child: AppChrome(child: child!)),
                    // A Continue On hand-off's card, over everything, the first-run landing included (Android
                    // MainActivity draws it above LandingHost): a device without the account still says so.
                    const ContinueOnCardHost(),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
      home: const ShellScreen(),
    );
  }
}

/// The window, measured, with the display's posture (yoin/posture): Tabletop
/// on a half fold with a horizontal crease, and the side cutouts the
/// landscape Button Group stands beside.
class _PostureWindowScope extends ConsumerWidget {
  const _PostureWindowScope({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final posture = ref.watch(displayPostureProvider).value ?? DisplayPosture.flat;
    if (posture.foldable case final folds?) HomeBubbleTip.deviceFolds = folds;
    return DisplayPostureScope(
      posture: posture,
      child: YoinWindowScope.measured(posture: posture, child: child),
    );
  }
}
