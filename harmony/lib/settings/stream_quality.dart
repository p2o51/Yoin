/// How much of a Subsonic song the player asks for (Android
/// player/StreamQuality.kt, SubsonicStreamResolver.kt StreamDelivery).
///
/// Harmony keeps one choice per account (Android has one for Wi‑Fi and one for
/// mobile data, decided by the network): Dart has no way to tell the network
/// kind without a `GET_NETWORK_INFO` permission and a native channel, so the
/// split waits for those. Android's Auto (follows the audio output) is left
/// out for the same reason.
library;

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/model/model.dart';
import '../data/subsonic/subsonic_music_source.dart';
import '../platform/storage/stores.dart';

enum StreamQuality {
  original('original', null),
  kbps320('320', 320),
  kbps192('192', 192),
  kbps128('128', 128);

  const StreamQuality(this.key, this.maxBitRateKbps);

  /// Stored value (Android's keys).
  final String key;

  /// The transcode cap; null = the file as it is.
  final int? maxBitRateKbps;

  static StreamQuality? fromKey(String? key) => values.where((q) => q.key == key).firstOrNull;

  /// Wi‑Fi's default on Android: no reason to send less than the file.
  static const fallback = original;
}

/// The format a transcode asks for: mp3 on every server. A constant-bit-rate
/// mp3 maps a time to a byte, so it seeks; an Ogg/opus transcode does not
/// (SubsonicServerTraits, measured 2026-11-03).
const transcodeFormat = 'mp3';

/// What the player asks for one song: null = the file; else a transcode
/// capped at [quality]. The file also when it is already at or under the cap
/// ([sourceKbps]): the server would send it as it is, and a file seeks by byte.
/// A transcode asks the server to estimate its length (Navidrome's
/// `estimateContentLength`) only when the file's bit rate is known: Navidrome
/// estimates from it, and declares too short a length for a file it lists at 0.
SubsonicStreamPlan? planStream(StreamQuality quality, int? sourceKbps) {
  final cap = quality.maxBitRateKbps;
  if (cap == null) return null;
  if (sourceKbps != null && sourceKbps > 0 && sourceKbps <= cap) return null;
  return (maxBitRateKbps: cap, format: transcodeFormat, estimateLength: sourceKbps != null && sourceKbps > 0);
}

/// One choice per account in [KeyValueStore].
final class StreamQualityStore {
  const StreamQualityStore(this._values);

  final KeyValueStore _values;

  static String key(String profileId) => 'stream_quality.v1.$profileId';

  Future<StreamQuality> read(String profileId) async =>
      StreamQuality.fromKey(await _values.read(key(profileId))) ?? StreamQuality.fallback;

  Future<void> write(String profileId, StreamQuality quality) => _values.write(key(profileId), quality.key);
}

/// For [SubsonicMusicSource.streamPlanner]: the active account's choice, read
/// each time a song opens, so a change applies from the next song. Anything
/// that goes wrong plays the file.
SubsonicStreamPlanner subsonicStreamPlanner(Ref ref) => (Track track) async {
      try {
        final profile = await ref.read(profileRepositoryProvider).activeProfile();
        if (profile == null) return null;
        final quality = await StreamQualityStore(ref.read(keyValueStoreProvider)).read(profile.id);
        return planStream(quality, track.audioFormat?.bitRateKbps);
      } catch (_) {
        return null;
      }
    };

/// One account's choice, for the Streaming quality page and its row. null while it loads.
class StreamQualityViewModel extends Notifier<StreamQuality?> {
  StreamQualityViewModel(this.profileId);

  final String profileId;

  @override
  StreamQuality? build() {
    _load();
    return null;
  }

  Future<void> _load() async {
    final quality = await StreamQualityStore(ref.read(keyValueStoreProvider)).read(profileId);
    // A choice made while it loaded wins.
    if (ref.mounted && state == null) state = quality;
  }

  Future<void> select(StreamQuality quality) async {
    if (state == quality) return;
    state = quality;
    await StreamQualityStore(ref.read(keyValueStoreProvider)).write(profileId, quality);
  }
}

final streamQualityProvider =
    NotifierProvider.autoDispose.family<StreamQualityViewModel, StreamQuality?, String>(StreamQualityViewModel.new);
