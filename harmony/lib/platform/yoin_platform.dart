/// Every platform seam in one bundle, chosen in ONE place (main.dart:
/// [YoinPlatform.forHost]). The UI and the playback chain only see the
/// interfaces; HarmonyOS fills them with the ArkTS channel clients, the
/// Windows build (not built yet) with the pure-Dart stand-ins in
/// `windows/windows_platform.dart`. harmony/docs/windows.md has the table.
library;

import 'dart:io' show Directory, Platform;

import 'applemusic/apple_music_auth.dart';
import 'cards/cards_channel.dart';
import 'cast/cast_host.dart';
import 'i18n/name_transliterator.dart';
import 'network/network_bearer.dart';
import 'playback/channel_playback_engine.dart';
import 'playback/playback_engine.dart';
import 'share/image_share.dart';
import 'storage/app_files.dart';
import 'storage/channel_stores.dart';
import 'storage/stores.dart';
import 'sync/cloud_sync_host.dart';
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
    required this.cacheStore,
    required this.secretStore,
    required this.playbackEngine,
    required this.appleMusicAuth,
    required this.cardsHost,
    required this.networkBearer,
    required this.nameTransliterator,
    required this.windowControls,
    required this.windowCaption,
    required this.imageShare,
    required this.appFiles,
    required this.cloudSyncHost,
    required this.castHost,
  });

  final YoinHost host;

  /// Plain settings and app data (OHOS Preferences; Windows a JSON file).
  final KeyValueStore keyValueStore;

  /// Re-fetchable caches (lyrics and their translations, ~10 MiB) apart from
  /// the settings, so neither store's writes rewrite the other (OHOS a second
  /// Preferences file; Windows `cache.json`).
  final KeyValueStore cacheStore;

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

  /// The share sheet and saving an image into the gallery (OHOS Share Kit + photoAccessHelper).
  final ImageShare imageShare;

  /// The folder for files the app keeps (offline downloads): OHOS filesDir.
  final AppFilesHost appFiles;

  /// Cloud sync's browser sign-in, device name and files folder (OHOS
  /// `yoin/cloud_sync`: Want + transient task; Windows: the shell and app data).
  final CloudSyncHost cloudSyncHost;

  /// The system's output / cast panel and cast session (OHOS AVCastPicker +
  /// AVCastController; Windows: none, DLNA only).
  final CastHost castHost;

  /// HarmonyOS: the ArkTS plugins in ohos/entry/src/main/ets/yoin/.
  factory YoinPlatform.ohos() => YoinPlatform(
    host: YoinHost.ohos,
    keyValueStore: const ChannelKeyValueStore(),
    cacheStore: const ChannelKeyValueStore(file: ChannelKeyValueStore.cacheFile),
    secretStore: const ChannelSecretStore(),
    playbackEngine: ChannelPlaybackEngine(),
    appleMusicAuth: const ChannelAppleMusicAuth(),
    cardsHost: const ChannelCardsHost(),
    networkBearer: ChannelNetworkBearer(),
    nameTransliterator: ChannelNameTransliterator(),
    windowControls: const ChannelWindowControls(),
    windowCaption: const ChannelWindowCaptionHost(),
    imageShare: const ChannelImageShare(),
    appFiles: const ChannelAppFilesHost(),
    cloudSyncHost: const ChannelCloudSyncHost(),
    castHost: ChannelCastHost(),
  );

  /// Windows (and any desktop without plugins): pure Dart, no channels.
  factory YoinPlatform.windows({Directory? appData}) => _desktop(appData ?? desktopAppDataDirectory());

  static YoinPlatform _desktop(Directory folder) => YoinPlatform(
    host: YoinHost.windows,
    keyValueStore: FileKeyValueStore(folder),
    cacheStore: FileKeyValueStore(folder, fileName: 'cache.json'),
    secretStore: SessionSecretStore(),
    playbackEngine: const UnsupportedPlaybackEngine(),
    appleMusicAuth: const UnsupportedAppleMusicAuth(),
    cardsHost: const NoCardsHost(),
    networkBearer: const FixedNetworkBearer(),
    nameTransliterator: const IdentityNameTransliterator(),
    windowControls: const NoWindowControls(),
    windowCaption: const SystemTitleBarHost(),
    imageShare: const NoImageShare(),
    appFiles: DirectoryAppFilesHost(folder),
    cloudSyncHost: DesktopCloudSyncHost(folder),
    castHost: const NoCastHost(),
  );

  /// The bundle for [operatingSystem] (default: this process's). HarmonyOS
  /// reports `ohos`; Windows, Linux and macOS get the desktop stand-ins;
  /// anything else keeps the HarmonyOS channels (today's only target).
  factory YoinPlatform.forHost([String? operatingSystem]) => switch (operatingSystem ?? Platform.operatingSystem) {
    'windows' || 'linux' || 'macos' => YoinPlatform.windows(),
    _ => YoinPlatform.ohos(),
  };
}
