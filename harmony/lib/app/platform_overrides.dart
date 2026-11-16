/// Wires a [YoinPlatform] bundle into the dependency graph: one override per
/// platform-backed provider, wherever that provider lives. main.dart is the
/// only caller; tests override the providers they need one by one.
library;

import 'package:flutter_riverpod/misc.dart' show Override;

import '../data/sync/cloud_sync_providers.dart';
import '../offline/offline_providers.dart';
import '../platform/cards/cards_channel.dart';
import '../platform/i18n/name_transliterator.dart';
import '../platform/share/image_share.dart';
import '../platform/storage/observed_store.dart';
import '../platform/window/window_controls.dart';
import '../platform/yoin_platform.dart';
import '../settings/stream_quality.dart';
import 'providers.dart';

List<Override> platformOverrides(YoinPlatform platform) => [
  // Observed, so cloud sync notices local changes to the rows it syncs.
  keyValueStoreProvider.overrideWithValue(ObservedKeyValueStore(platform.keyValueStore)),
  cacheStoreProvider.overrideWithValue(platform.cacheStore),
  secretStoreProvider.overrideWithValue(platform.secretStore),
  playbackEngineProvider.overrideWithValue(platform.playbackEngine),
  appleMusicAuthProvider.overrideWithValue(platform.appleMusicAuth),
  cardsHostProvider.overrideWithValue(platform.cardsHost),
  networkBearerProvider.overrideWithValue(platform.networkBearer),
  nameTransliteratorProvider.overrideWithValue(platform.nameTransliterator),
  windowControlsProvider.overrideWithValue(platform.windowControls),
  imageShareProvider.overrideWithValue(platform.imageShare),
  appFilesHostProvider.overrideWithValue(platform.appFiles),
  backgroundTransferHostProvider.overrideWithValue(platform.backgroundTransfer),
  cloudSyncHostProvider.overrideWithValue(platform.cloudSyncHost),
];
