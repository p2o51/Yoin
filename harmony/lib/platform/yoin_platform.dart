/// Every platform seam in one bundle, chosen in ONE place (main.dart:
/// [YoinPlatform.forHost]). The UI and the playback chain only see the
/// interfaces; HarmonyOS fills them with the ArkTS channel clients, the
/// Windows build (not built yet) with the pure-Dart stand-ins in
/// `windows/windows_platform.dart`. harmony/docs/windows.md has the table.
library;

import 'dart:io' show Directory, Platform;

import 'applemusic/apple_music_auth.dart';
import 'cards/cards_channel.dart';
import 'i18n/name_transliterator.dart';
import 'network/network_bearer.dart';
import 'playback/channel_playback_engine.dart';
import 'playback/playback_engine.dart';
import 'storage/channel_stores.dart';
import 'storage/stores.dart';
import 'window/window_caption.dart';
import 'window/window_controls.dart';
import 'windows/windows_platform.dart';

/// Which host the app runs on.
enum YoinHost { ohos, windows }

/// The platform's implementation of every seam.
final class YoinPlatform {
  const YoinPlatform({
    required this.host,
    required this.keyValueStore,
    required this.secretStore,
    required this.playbackEngine,
    required this.appleMusicAuth,
    required this.cardsHost,
    required this.networkBearer,
    required this.nameTransliterator,
    required this.windowControls,
    required this.windowCaption,
  });

  final YoinHost host;

  /// Plain settings and app data (OHOS Preferences; Windows a JSON file).
  final KeyValueStore keyValueStore;

  /// Account credentials (OHOS Asset Store Kit; Windows DPAPI later).
  final SecretStore secretStore;

  /// The audio engine + system media session (OHOS AVPlayer + AVSession).
  final PlaybackEngine playbackEngine;

  /// Apple Music sign-in (OHOS ArkWeb + MusicKit JS).
  final AppleMusicAuth appleMusicAuth;

  /// Home-screen service cards (OHOS Form Kit).
  final CardsHost cardsHost;

  /// Wi-Fi vs mobile data, for streaming quality.
  final NetworkBearerSource networkBearer;

  /// Han → pinyin sort forms for the Library (OHOS LocalizationKit).
  final NameTransliterator nameTransliterator;

  /// Keep the screen on, the system toast.
  final WindowControls windowControls;

  /// The window's caption (title bar hidden, buttons floating) and moving it.
  final WindowCaptionHost windowCaption;

  /// HarmonyOS: the ArkTS plugins in ohos/entry/src/main/ets/yoin/.
  factory YoinPlatform.ohos() => YoinPlatform(
    host: YoinHost.ohos,
    keyValueStore: const ChannelKeyValueStore(),
    secretStore: const ChannelSecretStore(),
    playbackEngine: ChannelPlaybackEngine(),
    appleMusicAuth: const ChannelAppleMusicAuth(),
    cardsHost: const ChannelCardsHost(),
    networkBearer: ChannelNetworkBearer(),
    nameTransliterator: ChannelNameTransliterator(),
    windowControls: const ChannelWindowControls(),
    windowCaption: const ChannelWindowCaptionHost(),
  );

  /// Windows (and any desktop without plugins): pure Dart, no channels.
  factory YoinPlatform.windows({Directory? appData}) => YoinPlatform(
    host: YoinHost.windows,
    keyValueStore: FileKeyValueStore(appData ?? desktopAppDataDirectory()),
    secretStore: SessionSecretStore(),
    playbackEngine: const UnsupportedPlaybackEngine(),
    appleMusicAuth: const UnsupportedAppleMusicAuth(),
    cardsHost: const NoCardsHost(),
    networkBearer: const FixedNetworkBearer(),
    nameTransliterator: const IdentityNameTransliterator(),
    windowControls: const NoWindowControls(),
    windowCaption: const SystemTitleBarHost(),
  );

  /// The bundle for [operatingSystem] (default: this process's). HarmonyOS
  /// reports `ohos`; Windows, Linux and macOS get the desktop stand-ins;
  /// anything else keeps the HarmonyOS channels (today's only target).
  factory YoinPlatform.forHost([String? operatingSystem]) => switch (operatingSystem ?? Platform.operatingSystem) {
    'windows' || 'linux' || 'macos' => YoinPlatform.windows(),
    _ => YoinPlatform.ohos(),
  };
}
