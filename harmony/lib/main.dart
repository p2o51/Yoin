import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'app/platform_overrides.dart';
import 'app/providers.dart';
import 'cards/play_card.dart';
import 'data/applemusic/apple_music_source.dart';
import 'data/profile/profile.dart';
import 'data/subsonic/subsonic_music_source.dart';
import 'offline/offline_providers.dart';
import 'platform/window/window_caption.dart';
import 'platform/yoin_platform.dart';
import 'playback/playback_controller.dart';
import 'settings/stream_quality.dart';
import 'shell/app_chrome.dart';
import 'shell/shell_screen.dart';
import 'shell/shell_state.dart';
import 'theme/playback_theme.dart';
import 'theme/system_bars.dart';
import 'ui/adaptive/yoin_window_info.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  // Edge to edge: content runs under the status and navigation bars.
  SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
  // The ONE place the platform is chosen: HarmonyOS channels, or the
  // Windows / desktop stand-ins (docs/windows.md).
  final platform = YoinPlatform.forHost();
  runApp(
    ProviderScope(
      overrides: [
        ...platformOverrides(platform),
        musicSourceFactoryProvider.overrideWith(
          (ref) => (credentials) => switch (credentials) {
            // Settings › Streaming quality decides each song's stream.
            SubsonicCredentials() => SubsonicMusicSource(credentials, streamPlanner: subsonicStreamPlanner(ref)),
            AppleMusicCredentials() => AppleMusicSource(credentials),
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
        builder: (context) => YoinWindowCaption(
          host: windowCaption,
          child: YoinSystemBars(
            child: YoinWindowScope.measured(child: AppChrome(child: child!)),
          ),
        ),
      ),
      home: const ShellScreen(),
    );
  }
}
