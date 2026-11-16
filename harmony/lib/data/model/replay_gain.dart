/// A song's ReplayGain as the service reports it (Android
/// data/model/ReplayGain.kt, OpenSubsonic `replayGain`): gains in dB, peaks as
/// linear sample values (1.0 = full scale). Every field is optional; only
/// Subsonic fills it on a Track. Apple Music levels the volume itself.
///
/// [baseGainDb] (an Opus header's output gain) is applied by the decoder
/// already, and [fallbackGainDb] is the server's guess for untagged songs:
/// both are kept as reported but never applied (an untagged song plays as it
/// is, owner 2026-11-04).
library;

import 'package:flutter/foundation.dart';

@immutable
final class ReplayGain {
  const ReplayGain({
    this.trackGainDb,
    this.albumGainDb,
    this.trackPeak,
    this.albumPeak,
    this.baseGainDb,
    this.fallbackGainDb,
  });

  final double? trackGainDb;
  final double? albumGainDb;
  final double? trackPeak;
  final double? albumPeak;
  final double? baseGainDb;
  final double? fallbackGainDb;

  /// Whether the song carries a track or album gain at all.
  bool get isTagged => trackGainDb != null || albumGainDb != null;

  /// The server's values with "not tagged" read as absent: a server that
  /// always sends the object writes 0 for a missing gain and 0 for its peak
  /// (a real peak of 0 is silence), so a 0 dB gain with no peak above 0 is no
  /// tag. Null when nothing is left.
  static ReplayGain? of({
    double? trackGainDb,
    double? albumGainDb,
    double? trackPeak,
    double? albumPeak,
    double? baseGainDb,
    double? fallbackGainDb,
  }) {
    final trackPeakOrNull = trackPeak != null && trackPeak > 0 && trackPeak.isFinite ? trackPeak : null;
    final albumPeakOrNull = albumPeak != null && albumPeak > 0 && albumPeak.isFinite ? albumPeak : null;
    double? gain(double? db, double? peak) => db != null && db.isFinite && (db != 0 || peak != null) ? db : null;
    double? nonZero(double? db) => db != null && db.isFinite && db != 0 ? db : null;
    final value = ReplayGain(
      trackGainDb: gain(trackGainDb, trackPeakOrNull),
      albumGainDb: gain(albumGainDb, albumPeakOrNull),
      trackPeak: trackPeakOrNull,
      albumPeak: albumPeakOrNull,
      baseGainDb: nonZero(baseGainDb),
      fallbackGainDb: nonZero(fallbackGainDb),
    );
    return value == const ReplayGain() ? null : value;
  }

  /// Compact JSON for the queue item's extra (absent fields left out).
  Map<String, double> toJson() => {
    'tg': ?trackGainDb,
    'ag': ?albumGainDb,
    'tp': ?trackPeak,
    'ap': ?albumPeak,
    'bg': ?baseGainDb,
    'fg': ?fallbackGainDb,
  };

  /// [toJson] read back; null when [json] holds nothing usable.
  static ReplayGain? fromJson(Object? json) {
    if (json is! Map) return null;
    double? read(String key) => switch (json[key]) {
      final num n => n.toDouble(),
      _ => null,
    };
    final value = ReplayGain(
      trackGainDb: read('tg'),
      albumGainDb: read('ag'),
      trackPeak: read('tp'),
      albumPeak: read('ap'),
      baseGainDb: read('bg'),
      fallbackGainDb: read('fg'),
    );
    return value == const ReplayGain() ? null : value;
  }

  @override
  bool operator ==(Object other) =>
      other is ReplayGain &&
      other.trackGainDb == trackGainDb &&
      other.albumGainDb == albumGainDb &&
      other.trackPeak == trackPeak &&
      other.albumPeak == albumPeak &&
      other.baseGainDb == baseGainDb &&
      other.fallbackGainDb == fallbackGainDb;

  @override
  int get hashCode => Object.hash(trackGainDb, albumGainDb, trackPeak, albumPeak, baseGainDb, fallbackGainDb);

  @override
  String toString() =>
      'ReplayGain(track: $trackGainDb dB / $trackPeak, album: $albumGainDb dB / $albumPeak, '
      'base: $baseGainDb, fallback: $fallbackGainDb)';
}
