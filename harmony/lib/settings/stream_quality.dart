/// How much of a Subsonic song the player asks for (Android
/// player/StreamQuality.kt, SubsonicStreamResolver.kt StreamDelivery).
///
/// Two choices per account, one for Wi‑Fi and one for mobile data; the
/// network a song opens on decides which (`yoin/network`). Android's Auto
/// (follows the audio output) is left out: Harmony doesn't read the output
/// yet, so mobile data defaults to a fixed 320 kbps instead.
library;

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/model/model.dart';
import '../data/subsonic/subsonic_music_source.dart';
import '../platform/network/network_bearer.dart';
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
}

/// The two kinds of network a choice is made for.
enum StreamNetwork { wifi, mobile }

/// One account's two choices (StreamQualitySettings).
@immutable
final class StreamQualitySettings {
  const StreamQualitySettings({this.wifi = defaultWifi, this.mobile = defaultMobile});

  /// Wi‑Fi has no reason to send less than the file (Android's default).
  static const defaultWifi = StreamQuality.original;

  /// Android's mobile default is Auto (follows the headphones); without the
  /// output, the Bluetooth answer: 320 kbps.
  static const defaultMobile = StreamQuality.kbps320;

  final StreamQuality wifi;
  final StreamQuality mobile;

  StreamQuality operator [](StreamNetwork network) => switch (network) {
        StreamNetwork.wifi => wifi,
        StreamNetwork.mobile => mobile,
      };

  /// Everything but mobile data counts as Wi‑Fi (Ethernet on a PC too).
  StreamQuality forBearer(NetworkBearer bearer) => bearer.isUnmetered ? wifi : mobile;

  StreamQualitySettings copyWith(StreamNetwork network, StreamQuality quality) => switch (network) {
        StreamNetwork.wifi => StreamQualitySettings(wifi: quality, mobile: mobile),
        StreamNetwork.mobile => StreamQualitySettings(wifi: wifi, mobile: quality),
      };

  @override
  bool operator ==(Object other) => other is StreamQualitySettings && other.wifi == wifi && other.mobile == mobile;

  @override
  int get hashCode => Object.hash(wifi, mobile);
}

/// What the default network runs over; main() gets the channel, tests override.
final networkBearerProvider = Provider<NetworkBearerSource>((ref) => ChannelNetworkBearer());

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

/// Each account's two choices in [KeyValueStore].
final class StreamQualityStore {
  const StreamQualityStore(this._values);

  final KeyValueStore _values;

  static String key(String profileId, StreamNetwork network) => 'stream_quality.${network.name}.v1.$profileId';

  Future<StreamQualitySettings> read(String profileId) async {
    final wifi = StreamQuality.fromKey(await _values.read(key(profileId, StreamNetwork.wifi)));
    final mobile = StreamQuality.fromKey(await _values.read(key(profileId, StreamNetwork.mobile)));
    return StreamQualitySettings(
      wifi: wifi ?? StreamQualitySettings.defaultWifi,
      mobile: mobile ?? StreamQualitySettings.defaultMobile,
    );
  }

  Future<void> write(String profileId, StreamNetwork network, StreamQuality quality) =>
      _values.write(key(profileId, network), quality.key);
}

/// For [SubsonicMusicSource.streamPlanner]: the active account's choice for
/// the network it opens on, read each time a song opens, so a change (of
/// setting or of network) applies from the next song. Anything that goes
/// wrong plays the file.
SubsonicStreamPlanner subsonicStreamPlanner(Ref ref) => (Track track) async {
      try {
        final profile = await ref.read(profileRepositoryProvider).activeProfile();
        if (profile == null) return null;
        final settings = await StreamQualityStore(ref.read(keyValueStoreProvider)).read(profile.id);
        final bearer = await ref.read(networkBearerProvider).current();
        return planStream(settings.forBearer(bearer), track.audioFormat?.bitRateKbps);
      } catch (_) {
        return null;
      }
    };

/// One account's choices, for the Streaming quality page and its row. null while they load.
class StreamQualityViewModel extends Notifier<StreamQualitySettings?> {
  StreamQualityViewModel(this.profileId);

  final String profileId;
  bool _touched = false;

  @override
  StreamQualitySettings? build() {
    _load();
    return null;
  }

  Future<void> _load() async {
    final settings = await StreamQualityStore(ref.read(keyValueStoreProvider)).read(profileId);
    // A choice made while they loaded wins.
    if (ref.mounted && !_touched) state = settings;
  }

  Future<void> select(StreamNetwork network, StreamQuality quality) async {
    final current = state ?? await StreamQualityStore(ref.read(keyValueStoreProvider)).read(profileId);
    if (current[network] == quality && state != null) return;
    _touched = true;
    if (ref.mounted) state = current.copyWith(network, quality);
    await StreamQualityStore(ref.read(keyValueStoreProvider)).write(profileId, network, quality);
  }
}

final streamQualityProvider = NotifierProvider.autoDispose
    .family<StreamQualityViewModel, StreamQualitySettings?, String>(StreamQualityViewModel.new);
