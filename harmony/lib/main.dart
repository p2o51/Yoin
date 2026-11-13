import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'app/providers.dart';
import 'data/profile/profile.dart';
import 'data/subsonic/subsonic_music_source.dart';
import 'platform/playback/channel_playback_engine.dart';
import 'platform/storage/channel_stores.dart';
import 'shell/app_chrome.dart';
import 'shell/shell_screen.dart';
import 'shell/shell_state.dart';
import 'theme/theme.dart';
import 'ui/adaptive/yoin_window_info.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  // Edge to edge: content runs under the status and navigation bars.
  SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
  runApp(
    ProviderScope(
      overrides: [
        keyValueStoreProvider.overrideWithValue(const ChannelKeyValueStore()),
        secretStoreProvider.overrideWithValue(const ChannelSecretStore()),
        playbackEngineProvider.overrideWithValue(ChannelPlaybackEngine()),
        musicSourceFactoryProvider.overrideWithValue(
          (credentials) => switch (credentials) {
            SubsonicCredentials() => SubsonicMusicSource(credentials),
          },
        ),
      ],
      child: const YoinApp(),
    ),
  );
}

class YoinApp extends StatelessWidget {
  const YoinApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Yoin',
      debugShowCheckedModeBanner: false,
      theme: yoinTheme(Brightness.light),
      darkTheme: yoinTheme(Brightness.dark),
      themeMode: ThemeMode.system,
      supportedLocales: const [Locale('en'), Locale('zh')],
      localizationsDelegates: GlobalMaterialLocalizations.delegates,
      navigatorKey: appNavigatorKey,
      builder: (context, child) => YoinWindowScope.measured(child: AppChrome(child: child!)),
      home: const ShellScreen(),
    );
  }
}
