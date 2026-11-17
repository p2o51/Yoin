/// Every platform seam in one bundle, chosen in ONE place (main.dart:
/// [YoinPlatform.forHost]). The UI and the playback chain only see the
/// interfaces; HarmonyOS fills them with the ArkTS channel clients, the
/// Windows build (not built yet) with the pure-Dart stand-ins in
/// `windows/windows_platform.dart`. harmony/docs/windows.md has the table.
library;

import 'dart:io' show Directory, Platform;

import 'applemusic/apple_music_auth.dart';
import 'audio/audio_host.dart';
import 'browser/url_opener.dart';
import 'background/background_transfer.dart';
import 'cards/cards_channel.dart';
import 'haptics/haptics_host.dart';
import 'motion/system_motion.dart';
import 'cast/cast_host.dart';
import 'continueon/continue_on_host.dart';
import 'i18n/name_transliterator.dart';
import 'network/network_bearer.dart';
import 'playback/channel_playback_engine.dart';
import 'playback/playback_engine.dart';
import 'posture/display_posture.dart';
import 'sensors/motion_sensors.dart';
import 'share/file_share.dart';
import 'share/image_share.dart';
import 'share/text_share.dart';
import 'storage/app_files.dart';
import 'storage/channel_stores.dart';
import 'storage/stores.dart';
import 'sync/cloud_sync_host.dart';
import 'window/display_corners.dart';
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
    required this.textShare,
    required this.fileShare,
    required this.appFiles,
    required this.cloudSyncHost,
    required this.urlOpener,
    required this.displayCorners,
    required this.haptics,
    required this.motionSensors,
    required this.backgroundTransfer,
    required this.castHost,
    required this.continueOnHost,
    required this.displayPosture,
    required this.audio,
    required this.volumeKeys,
    required this.systemMotion,
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

  /// The share sheet for a name and its link (OHOS Share Kit).
  final TextShare textShare;

  /// The share sheet for a file the app wrote: Help and about's Export log
  /// (OHOS Share Kit; Windows: none yet).
  final FileShare fileShare;

  /// The folder for files the app keeps (offline downloads): OHOS filesDir.
  final AppFilesHost appFiles;

  /// Cloud sync's browser sign-in, device name and files folder (OHOS
  /// `yoin/cloud_sync`: Want + transient task; Windows: the shell and app data).
  final CloudSyncHost cloudSyncHost;

  /// A web page in the system browser, through [cloudSyncHost]'s browser
  /// hand-off (the one seam both use).
  final UrlOpener urlOpener;

  /// The panel's corner radius, for "Match screen" (OHOS `display.getRoundedCorner`).
  final DisplayCorners displayCorners;

  /// The vibrator: Yoin's named haptics and composed beat plans (OHOS
  /// `yoin/haptics` → @ohos.vibrator presets and patterns; Windows: none).
  final HapticsHost haptics;

  /// The rotation-vector sensor for the groove emblem's tilt (OHOS
  /// `yoin/sensors` → @ohos.sensor; Windows: none).
  final MotionSensors motionSensors;

  /// Keeping the app running while songs download or a playlist restores,
  /// with their progress notification (OHOS `yoin/transfer`: a data-transfer
  /// continuous task, shared through SharedBackgroundTransfer; Windows:
  /// nothing to hold).
  final BackgroundTransferHost backgroundTransfer;

  /// The system's output / cast panel and cast session (OHOS AVCastPicker +
  /// AVCastController; Windows: none, DLNA only).
  final CastHost castHost;

  /// Continue On: offering this app on the user's other devices and taking a
  /// hand-off from one (OHOS 应用接续, `yoin/continue_on`; Windows: none).
  final ContinueOnHost continueOnHost;

  /// The window's fold status, crease and side cutouts: Tabletop and where the
  /// landscape Button Group stands (OHOS `yoin/posture` → @ohos.display fold
  /// status / crease region + the window's cutout avoid area; Windows: flat).
  final DisplayPostureHost displayPosture;
  /// The sound output (streaming quality's Auto), the system's spatial audio
  /// and Yoin's spatial switch (OHOS `yoin/audio`: the routing manager,
  /// AudioSpatializationManager, AVPlayer's audio effect mode; Windows: a
  /// speaker, no spatial audio).
  final AudioHost audio;

  /// The volume keys, taken over while a DLNA speaker plays (OHOS
  /// `yoin/volume_keys` → inputConsumer 'keyPressed'; Windows: none).
  final VolumeKeyHost volumeKeys;

  /// The system's reduce-motion settings, folded into the app root's
  /// `disableAnimations` (OHOS `yoin/motion` → accessibility 减弱动画, the
  /// animator duration scale and power saving; Windows: none, Flutter's own
  /// flag only).
  final SystemMotionHost systemMotion;

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
    textShare: const ChannelTextShare(),
    fileShare: const ChannelFileShare(),
    appFiles: const ChannelAppFilesHost(),
    cloudSyncHost: const ChannelCloudSyncHost(),
    urlOpener: const BrowserUrlOpener(ChannelCloudSyncHost()),
    displayCorners: const ChannelDisplayCorners(),
    haptics: ChannelHapticsHost(),
    motionSensors: ChannelMotionSensors(),
    backgroundTransfer: ChannelBackgroundTransferHost(),
    castHost: ChannelCastHost(),
    continueOnHost: ChannelContinueOnHost(),
    displayPosture: ChannelDisplayPostureHost(),
    audio: ChannelAudioHost(),
    volumeKeys: ChannelVolumeKeyHost(),
    systemMotion: ChannelSystemMotionHost(),
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
    textShare: const NoTextShare(),
    fileShare: const NoFileShare(),
    appFiles: DirectoryAppFilesHost(folder),
    cloudSyncHost: DesktopCloudSyncHost(folder),
    urlOpener: BrowserUrlOpener(DesktopCloudSyncHost(folder)),
    displayCorners: const NoDisplayCorners(),
    haptics: const NoHapticsHost(),
    motionSensors: const NoMotionSensors(),
    backgroundTransfer: const NoBackgroundTransferHost(),
    castHost: const NoCastHost(),
    continueOnHost: const NoContinueOnHost(),
    displayPosture: const NoDisplayPostureHost(),
    audio: const FixedAudioHost(),
    volumeKeys: const NoVolumeKeyHost(),
    systemMotion: const NoSystemMotionHost(),
  );

  /// The bundle for [operatingSystem] (default: this process's). HarmonyOS
  /// reports `ohos`; Windows, Linux and macOS get the desktop stand-ins;
  /// anything else keeps the HarmonyOS channels (today's only target).
  factory YoinPlatform.forHost([String? operatingSystem]) => switch (operatingSystem ?? Platform.operatingSystem) {
    'windows' || 'linux' || 'macos' => YoinPlatform.windows(),
    _ => YoinPlatform.ohos(),
  };
}
