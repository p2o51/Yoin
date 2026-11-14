/// Wires a [YoinPlatform] bundle into the dependency graph: one override per
/// platform-backed provider, wherever that provider lives. main.dart is the
/// only caller; tests override the providers they need one by one.
library;

import 'package:flutter_riverpod/misc.dart' show Override;

import '../platform/cards/cards_channel.dart';
import '../platform/i18n/name_transliterator.dart';
import '../platform/window/window_controls.dart';
import '../platform/yoin_platform.dart';
import '../settings/stream_quality.dart';
import 'providers.dart';

List<Override> platformOverrides(YoinPlatform platform) => [
  keyValueStoreProvider.overrideWithValue(platform.keyValueStore),
  secretStoreProvider.overrideWithValue(platform.secretStore),
  playbackEngineProvider.overrideWithValue(platform.playbackEngine),
  appleMusicAuthProvider.overrideWithValue(platform.appleMusicAuth),
  cardsHostProvider.overrideWithValue(platform.cardsHost),
  networkBearerProvider.overrideWithValue(platform.networkBearer),
  nameTransliteratorProvider.overrideWithValue(platform.nameTransliterator),
  windowControlsProvider.overrideWithValue(platform.windowControls),
];
