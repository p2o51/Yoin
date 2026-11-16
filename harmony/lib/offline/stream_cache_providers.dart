/// The stream cache in the dependency graph (Android AppContainer
/// offlineDownloads.streamCache): one [StreamCache] and its loopback
/// [StreamCacheProxy] for the app, what Settings shows of it, and the address
/// a queue entry plays from.
library;

import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/cache/stream_cache.dart';
import '../data/cache/stream_cache_proxy.dart';
import '../data/model/model.dart';
import 'offline_providers.dart';

/// The cache, in the app's cache folder (none in tests and previews: songs stream directly).
final streamCacheProvider = Provider<StreamCache>((ref) {
  final files = ref.watch(appFilesHostProvider);
  final cache = StreamCache(directory: files.cacheDirectory);
  ref.onDispose(cache.dispose);
  unawaited(cache.start());
  return cache;
});

/// The loopback server the player reads streams through. Started on first use.
final streamCacheProxyProvider = Provider<StreamCacheProxy>((ref) {
  final proxy = StreamCacheProxy(cache: ref.watch(streamCacheProvider));
  ref.onDispose(() => unawaited(proxy.close()));
  return proxy;
});

/// What the stream cache takes now, bytes (Settings › Playback cache); 0 until it is read.
class StreamCacheSizeNotifier extends Notifier<int> {
  @override
  int build() {
    final cache = ref.watch(streamCacheProvider);
    void changed() => state = cache.sizeBytes;
    cache.addListener(changed);
    ref.onDispose(() => cache.removeListener(changed));
    return cache.sizeBytes;
  }

  /// Empties the stream cache. Downloads stay: Settings › Downloads removes them.
  Future<void> clear() => ref.read(streamCacheProvider).clear();
}

final streamCacheSizeProvider = NotifierProvider<StreamCacheSizeNotifier, int>(StreamCacheSizeNotifier.new);

/// How long a queue waits for the proxy to come up before it streams directly.
const streamCacheStartTimeout = Duration(seconds: 2);

/// The loopback address [track] plays from through the stream cache, for the
/// server address [uri]; null when there is no cache (tests, a build without
/// a cache folder) or the address can't be cached. The native player opens a
/// downloaded file before this, and the server's address when this fails.
Future<String?> streamCacheAddress(Ref ref, Track track, String uri, Map<String, String> headers) async {
  final parsed = Uri.tryParse(uri);
  if (parsed == null || !(parsed.isScheme('http') || parsed.isScheme('https'))) return null;
  try {
    final proxy = ref.read(streamCacheProxyProvider);
    if (!await proxy.start().timeout(streamCacheStartTimeout, onTimeout: () => false)) return null;
    return proxy.addressFor(
      StreamCacheSource(
        uri: parsed,
        headers: headers,
        song: StreamCacheSong(
          provider: track.id.provider,
          songId: track.id.toString(),
          title: track.title,
          artist: track.artist,
          album: track.album,
        ),
        suffix: _suffixOf(parsed, track),
      ),
    );
  } catch (_) {
    return null;
  }
}

/// A transcode's format (`format=mp3` in the address), else the file's own.
String? _suffixOf(Uri uri, Track track) => uri.queryParameters['format'] ?? track.audioFormat?.suffix;
