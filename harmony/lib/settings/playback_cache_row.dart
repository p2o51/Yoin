import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../nowplaying/haptics.dart';
import '../offline/offline_strings.dart';
import '../offline/stream_cache_providers.dart';
import 'settings_rows.dart';
import 'settings_strings.dart';

/// Settings › Sync and storage › Playback cache (Android SettingsScreen
/// CacheItem): what the stream cache takes, and Clear, which empties it
/// (downloads stay; Settings › Downloads removes them). Clear is off while the
/// cache is empty.
class PlaybackCacheRow extends ConsumerWidget {
  const PlaybackCacheRow({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = SettingsStrings.of(context);
    final bytes = ref.watch(streamCacheSizeProvider);
    return SettingsItem(
      icon: YoinSymbols.storage,
      title: strings.storageCache,
      // settings_cache_empty / _bytes / _kb / _mb / _gb (formatBytes).
      summary: OfflineStrings.of(context).bytes(bytes),
      trailing: TextButton(
        onPressed: bytes > 0
            ? () {
                YoinHaptics.reject();
                unawaited(ref.read(streamCacheSizeProvider.notifier).clear());
              }
            : null,
        child: Text(strings.storageClear),
      ),
    );
  }
}
