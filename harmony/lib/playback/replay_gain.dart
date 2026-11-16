/// Volume leveling (Android player/ReplayGain.kt, owner task 2026-11-04):
/// which of a song's ReplayGain values the player applies, and how much.
/// Subsonic only (Capability.replayGain); a song without tags always plays as
/// it is.
///
/// Android multiplies the samples in its audio sink, so a song can also be
/// made louder. HarmonyOS's AVPlayer only takes a volume of 0–1: the same
/// gain is worked out here, then played as that volume ([engineGain]), so a
/// quiet song that would need more than its own level plays as it is.
library;

import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/model/model.dart';
import '../platform/storage/stores.dart';

enum ReplayGainMode {
  off('off'),
  track('track'),

  /// The album gain, or the track gain where the song has none: an album
  /// mixed from different sources (《再生（全景声）》) is tagged per track on
  /// purpose, and levels out this way.
  album('album');

  const ReplayGainMode(this.key);

  /// Stored value (Android's keys).
  final String key;

  static ReplayGainMode? fromKey(String? key) => values.where((m) => m.key == key).firstOrNull;
}

@immutable
final class ReplayGainSettings {
  const ReplayGainSettings({this.mode = defaultMode, this.preampDb = defaultPreampDb});

  /// By album (owner, 2026-11-04): keeps each album's own dynamics, and an
  /// album without album gain (《再生（全景声）》) falls back to track gain.
  static const defaultMode = ReplayGainMode.album;

  /// Added to every tagged song: −18 LUFS (the tags' reference) is quiet on a
  /// phone. About −14 LUFS, where streaming services sit; the peak guard keeps
  /// it from clipping.
  static const defaultPreampDb = 4.0;

  final ReplayGainMode mode;
  final double preampDb;

  ReplayGainSettings copyWith({ReplayGainMode? mode}) =>
      ReplayGainSettings(mode: mode ?? this.mode, preampDb: preampDb);

  @override
  bool operator ==(Object other) => other is ReplayGainSettings && other.mode == mode && other.preampDb == preampDb;

  @override
  int get hashCode => Object.hash(mode, preampDb);

  @override
  String toString() => 'ReplayGainSettings(${mode.key}, preamp $preampDb dB)';
}

/// Which value a gain came from.
enum ReplayGainSource { none, track, album, albumFromTrack }

/// The gain the player applies to a song: [linear] multiplies every sample.
@immutable
final class AppliedReplayGain {
  const AppliedReplayGain({required this.db, required this.linear, required this.source, required this.peakLimited});

  static const unity = AppliedReplayGain(db: 0, linear: 1, source: ReplayGainSource.none, peakLimited: false);

  final double db;
  final double linear;
  final ReplayGainSource source;

  /// The gain was lowered so the peak stays at full scale.
  final bool peakLimited;

  @override
  bool operator ==(Object other) =>
      other is AppliedReplayGain &&
      other.db == db &&
      other.linear == linear &&
      other.source == source &&
      other.peakLimited == peakLimited;

  @override
  int get hashCode => Object.hash(db, linear, source, peakLimited);

  @override
  String toString() => source == ReplayGainSource.none
      ? 'none (untagged or off)'
      : '${db >= 0 ? '+' : ''}${db.toStringAsFixed(2)} dB ×${linear.toStringAsFixed(4)} '
            '(${source.name}${peakLimited ? ', peak-limited' : ''})';
}

/// The gain for [gain] under [settings]: the chosen value plus the pre-amp,
/// lowered where the song's peak would go past full scale.
/// [AppliedReplayGain.unity] when leveling is off or the song has no tag for
/// it: an untagged song is never guessed at, and gets no pre-amp either.
AppliedReplayGain appliedReplayGain(ReplayGain? gain, ReplayGainSettings settings) {
  if (gain == null) return AppliedReplayGain.unity;
  final (double, ReplayGainSource)? chosen = switch (settings.mode) {
    ReplayGainMode.off => null,
    ReplayGainMode.track => gain.trackGainDb == null ? null : (gain.trackGainDb!, ReplayGainSource.track),
    ReplayGainMode.album =>
      gain.albumGainDb != null
          ? (gain.albumGainDb!, ReplayGainSource.album)
          : gain.trackGainDb != null
          ? (gain.trackGainDb!, ReplayGainSource.albumFromTrack)
          : null,
  };
  if (chosen == null) return AppliedReplayGain.unity;
  final (db, source) = chosen;
  // This song's own peak is what clips; the album's (≥ it) only where the track's is missing.
  final peak = gain.trackPeak ?? gain.albumPeak;
  final wanted = dbToLinear(db + settings.preampDb);
  final ceiling = peak != null && peak > 0 ? 1 / peak : null;
  final linear = ceiling != null ? math.min(wanted, ceiling) : wanted;
  final limited = ceiling != null && wanted > ceiling;
  return AppliedReplayGain(
    db: limited ? linearToDb(linear) : db + settings.preampDb,
    linear: linear,
    source: source,
    peakLimited: limited,
  );
}

/// What the native player is told for a song: [appliedReplayGain]'s factor
/// as an AVPlayer volume, which stops at 1 (the file's own level).
double engineGain(ReplayGain? gain, ReplayGainSettings settings) =>
    appliedReplayGain(gain, settings).linear.clamp(0.0, 1.0).toDouble();

double dbToLinear(double db) => math.pow(10, db / 20).toDouble();

double linearToDb(double linear) => 20 * math.log(linear) / math.ln10;

/// The stored choice, for this device (Android ReplayGainPreference: one
/// value in yoin_playback_preferences, not per account).
final class ReplayGainStore {
  const ReplayGainStore(this._values);

  final KeyValueStore _values;

  static const key = 'replay_gain_mode.v1';

  Future<ReplayGainSettings> read() async =>
      ReplayGainSettings(mode: ReplayGainMode.fromKey(await _values.read(key)) ?? ReplayGainSettings.defaultMode);

  Future<void> write(ReplayGainMode mode) => _values.write(key, mode.key);
}

/// The listener's choice; null while it loads. The player reads it for every
/// queue it builds and is told when it changes, so a change applies at once,
/// to the song playing too.
class ReplayGainPreference extends Notifier<ReplayGainSettings?> {
  late Future<ReplayGainSettings> _loading;

  @override
  ReplayGainSettings? build() {
    _loading = _load();
    return null;
  }

  Future<ReplayGainSettings> _load() async {
    await Future<void>.value();
    var settings = const ReplayGainSettings();
    try {
      settings = await ReplayGainStore(ref.read(keyValueStoreProvider)).read();
    } catch (_) {
      // No storage (tests, a broken store): the default.
    }
    if (!ref.mounted) return settings;
    // A choice made while it loaded wins.
    final current = state;
    if (current != null) return current;
    state = settings;
    return settings;
  }

  /// The choice once loaded.
  Future<ReplayGainSettings> ready() async => state ?? await _loading;

  Future<void> setMode(ReplayGainMode mode) async {
    final current = state ?? await _loading;
    if (!ref.mounted || (state != null && current.mode == mode)) return;
    state = current.copyWith(mode: mode);
    try {
      await ReplayGainStore(ref.read(keyValueStoreProvider)).write(mode);
    } catch (_) {
      // Kept for this run; the next start reads the old value.
    }
  }
}

final replayGainProvider = NotifierProvider<ReplayGainPreference, ReplayGainSettings?>(ReplayGainPreference.new);
