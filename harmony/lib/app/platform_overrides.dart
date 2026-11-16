/// Wires a [YoinPlatform] bundle into the dependency graph: one override per
/// platform-backed provider, wherever that provider lives. main.dart is the
/// only caller; tests override the providers they need one by one.
library;

import 'package:flutter_riverpod/misc.dart' show Override;

import '../continueon/continue_on_controller.dart';
import '../data/sync/cloud_sync_providers.dart';
import '../offline/offline_providers.dart';
import '../platform/browser/url_opener.dart';
import '../platform/cards/cards_channel.dart';
import '../platform/i18n/name_transliterator.dart';
import '../platform/sensors/motion_sensors.dart';
import '../platform/playback/playback_engine.dart';
import '../platform/share/image_share.dart';
import '../platform/share/text_share.dart';
import '../platform/storage/observed_store.dart';
import '../platform/window/display_corners.dart';
import '../platform/window/window_controls.dart';
import '../platform/yoin_platform.dart';
import '../playback/output/output_providers.dart';
import '../playback/output/playback_router.dart';
import '../settings/stream_quality.dart';
import 'providers.dart';

List<Override> platformOverrides(YoinPlatform platform) {
  // The output router stands in front of the phone's engine: DLNA speakers
  // and the system's cast device play the same queue (Devices pill).
  final engine = platform.playbackEngine;
  final router = engine is QueuePlaybackEngine ? PlaybackRouter(engine, castHost: platform.castHost) : null;
  return [
    // Observed, so cloud sync notices local changes to the rows it syncs.
    keyValueStoreProvider.overrideWithValue(ObservedKeyValueStore(platform.keyValueStore)),
    cacheStoreProvider.overrideWithValue(platform.cacheStore),
    secretStoreProvider.overrideWithValue(platform.secretStore),
    playbackEngineProvider.overrideWithValue(router ?? engine),
    playbackRouterProvider.overrideWithValue(router),
    appleMusicAuthProvider.overrideWithValue(platform.appleMusicAuth),
    cardsHostProvider.overrideWithValue(platform.cardsHost),
    networkBearerProvider.overrideWithValue(platform.networkBearer),
    nameTransliteratorProvider.overrideWithValue(platform.nameTransliterator),
    windowControlsProvider.overrideWithValue(platform.windowControls),
    imageShareProvider.overrideWithValue(platform.imageShare),
    textShareProvider.overrideWithValue(platform.textShare),
    appFilesHostProvider.overrideWithValue(platform.appFiles),
    backgroundTransferHostProvider.overrideWithValue(platform.backgroundTransfer),
    cloudSyncHostProvider.overrideWithValue(platform.cloudSyncHost),
    urlOpenerProvider.overrideWithValue(platform.urlOpener),
    displayCornersProvider.overrideWithValue(platform.displayCorners),
    motionSensorsProvider.overrideWithValue(platform.motionSensors),
    continueOnHostProvider.overrideWithValue(platform.continueOnHost),
  ];
}
