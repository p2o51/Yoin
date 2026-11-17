import 'dart:math' as math;

import 'package:flutter/foundation.dart';

import '../theme/tokens.dart';
import 'lyrics_logic.dart';

typedef _F = NowPlayingFocusTokens;

/// The song that plays next with its lyrics, prefetched during the current
/// song so the lyrics can reveal it in the outro and hand over without a
/// loading beat (Android UpNextLyrics).
@immutable
final class UpNextLyrics {
  const UpNextLyrics({required this.songKey, required this.title, required this.artist, required this.sheet});

  final String songKey;
  final String title;
  final String artist;
  final LyricSheet sheet;

  @override
  bool operator ==(Object other) =>
      other is UpNextLyrics &&
      other.songKey == songKey &&
      other.title == title &&
      other.artist == artist &&
      other.sheet == sheet;

  @override
  int get hashCode => Object.hash(songKey, title, artist, sheet);
}

/// The outro choreography, in playhead time (UpNextTiming.kt): from
/// [revealStartMs] the last line stretches and the next song's title rises
/// below it; at [handoffAtMs] the stretch lets go, the list glides the next
/// title to where the next song's own list opens and this song's lines fade.
@immutable
final class UpNextTiming {
  const UpNextTiming({required this.revealStartMs, required this.handoffAtMs});

  final int revealStartMs;
  final int handoffAtMs;

  /// How far into the outro window [positionMs] is, 0..1, quantized to
  /// [steps] so a tick rebuilds only when the outro visibly advances.
  double outroProgress(int positionMs, {int steps = _F.upNextRevealSteps}) {
    final span = math.max(1, handoffAtMs - revealStartMs);
    final p = ((positionMs - revealStartMs) / span).clamp(0.0, 1.0);
    return (p * steps).round() / steps;
  }

  @override
  bool operator ==(Object other) =>
      other is UpNextTiming && other.revealStartMs == revealStartMs && other.handoffAtMs == handoffAtMs;

  @override
  int get hashCode => Object.hash(revealStartMs, handoffAtMs);
}

/// The last line's outro window, with or without a next song to stage. Null
/// for untimed lyrics, an unknown duration, or a last line inside the final
/// [NowPlayingFocusTokens.upNextHandoffMinLeadMs].
UpNextTiming? lastLineOutroFor(List<LyricRow> rows, int? durationMs) {
  if (durationMs == null || durationMs <= 0) return null;
  int? lastStart;
  for (final r in rows.reversed) {
    if (r.startMs != null) {
      lastStart = r.startMs;
      break;
    }
  }
  if (lastStart == null) return null;
  if (durationMs - lastStart < _F.upNextHandoffMinLeadMs) return null;
  // The last line keeps the stage for its own beat when there is room;
  // otherwise the hand-over waits as late as the song-change check allows.
  final handoffAt = math.max(
    durationMs - _F.upNextHandoffLeadMs,
    math.min(lastStart + _F.upNextMinLastLineMs, durationMs - _F.upNextHandoffMinLeadMs),
  );
  final revealStart = math.max(durationMs - _F.upNextRevealLeadMs, lastStart);
  return UpNextTiming(revealStartMs: revealStart, handoffAtMs: handoffAt);
}

/// The outro with a next song staged: needs the next song's TIMED lyrics
/// (its own list opens timed, so the swap can match).
UpNextTiming? upNextTimingFor(List<LyricRow> rows, UpNextLyrics? upNext, int? durationMs) {
  if (upNext == null || !upNext.sheet.isSynced) return null;
  return lastLineOutroFor(rows, durationMs);
}

/// Whether a list that stopped at [atPositionMs] had already staged [toKey]
/// so the song change swaps in place (only while it followed the playhead).
bool upNextHandedOver({
  required List<LyricRow> rows,
  required UpNextLyrics? upNext,
  required int? durationMs,
  required bool followedPlayhead,
  required String toKey,
  required int atPositionMs,
}) {
  if (!followedPlayhead || upNext?.songKey != toKey) return false;
  final handoffAt = upNextTimingFor(rows, upNext, durationMs)?.handoffAtMs;
  if (handoffAt == null) return false;
  return atPositionMs >= handoffAt - _F.upNextHandoffTickSlackMs;
}

/// I-2: the held last line's X scale for its outro [progress] (0 = rest).
double lastLineStretchScale(double progress) => 1 + _F.lastLineStretchMax * progress;
