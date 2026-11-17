import 'dart:ui' show Offset, Rect;

import 'package:flutter/foundation.dart';

// What the Now Playing background answers on a committed playback change
// (TransportPulse.kt; D4 §1, owner pick 2026-10-05: option B + the P0 corrections).
//
//  - Play/pause keeps the approved breath — a ring (play) or sink (pause) from
//    the PLAY key that was tapped, or from the neutral point when nothing was
//    tapped (headset, notification, audio focus).
//  - A song change while the lyrics are the primary surface flows the new
//    song's colours in WITH the lyric stream (next: up from below, previous:
//    down from above) — from any source, tapped or not, except when the outro
//    already handed the next song over in place (that reveal IS the transition).
//  - A song change while the cover is the primary surface flows the new
//    colours in from the tapped side (NEXT: from the end, PREVIOUS: from the
//    start) — only for a fresh tap. Untapped changes never reuse an old key:
//    the colours just cross over in place.

/// What the background does for one committed change.
sealed class TransportPulse {
  const TransportPulse();
}

/// Nothing moves.
final class PulseNone extends TransportPulse {
  const PulseNone();

  @override
  bool operator ==(Object other) => other is PulseNone;

  @override
  int get hashCode => 0;
}

/// Ring (play) or sink (pause) from [focalRoot]; null = the neutral point above the controls.
final class PulseBurst extends TransportPulse {
  const PulseBurst({required this.isPlay, this.focalRoot});
  final bool isPlay;
  final Offset? focalRoot;

  @override
  bool operator ==(Object other) => other is PulseBurst && other.isPlay == isPlay && other.focalRoot == focalRoot;

  @override
  int get hashCode => Object.hash(isPlay, focalRoot);
}

/// The colours flow in with the lyric stream; [forward] = rising from below (next / auto-advance).
final class PulseFlowLight extends TransportPulse {
  const PulseFlowLight({required this.forward});
  final bool forward;

  @override
  bool operator ==(Object other) => other is PulseFlowLight && other.forward == forward;

  @override
  int get hashCode => forward.hashCode;
}

/// The colours flow in sideways; [fromEnd] = from the end side (NEXT), else from the start (PREVIOUS).
final class PulseSideFlow extends TransportPulse {
  const PulseSideFlow({required this.fromEnd});
  final bool fromEnd;

  @override
  bool operator ==(Object other) => other is PulseSideFlow && other.fromEnd == fromEnd;

  @override
  int get hashCode => fromEnd.hashCode;
}

/// The committed state the pulse reacts to — never the playhead, so ticks can't fire it.
@immutable
final class TransportPulseKey {
  const TransportPulseKey({required this.songId, required this.isPlaying, required this.queueIndex});
  final Object songId;
  final bool isPlaying;
  final int queueIndex;

  @override
  bool operator ==(Object other) =>
      other is TransportPulseKey &&
      other.songId == songId &&
      other.isPlaying == isPlaying &&
      other.queueIndex == queueIndex;

  @override
  int get hashCode => Object.hash(songId, isPlaying, queueIndex);
}

enum TransportTapKind { playPause, skipNext, skipPrevious }

/// [TransportTap.positionMs] when the playhead wasn't known at the tap.
const int unknownTapPositionMs = -1;

/// A transport key tap, stamped when it happened ([uptimeMs], monotonic). The
/// player commits hundreds of ms later, so the pulse only trusts a tap
/// younger than [transportTapValidityMs] and uses it once. [songId] /
/// [positionMs]: the song on screen and its playhead when tapped, so a
/// PREVIOUS that only restarts that song can be told apart ([restartedInPlace]).
@immutable
final class TransportTap {
  const TransportTap({
    required this.centerRoot,
    required this.uptimeMs,
    required this.kind,
    this.songId,
    this.positionMs = unknownTapPositionMs,
  });

  final Offset centerRoot;
  final int uptimeMs;
  final TransportTapKind kind;
  final Object? songId;
  final int positionMs;

  /// A PREVIOUS tap that only sent the playhead back on the song it was tapped
  /// on: spent the moment the playhead is seen back there ([previousRestartJumpMs]
  /// or more is a seek, never jitter).
  bool restartedInPlace(Object? songId, int positionMs) =>
      kind == TransportTapKind.skipPrevious &&
      this.songId != null &&
      this.songId == songId &&
      this.positionMs != unknownTapPositionMs &&
      positionMs <= this.positionMs - previousRestartJumpMs;
}

/// How far back the playhead must land on the tapped song to read as a restart.
const int previousRestartJumpMs = 1000;

/// The open lyrics page staged [toSongId] in place of [fromSongId] (its
/// up-next block handed over at the top), so that song change swaps without motion.
@immutable
final class LyricsHandover {
  const LyricsHandover({required this.fromSongId, required this.toSongId});
  final Object fromSongId;
  final Object toSongId;
}

/// Where the lyrics column sits (global px) and whether it is what the eye is on.
@immutable
final class LyricsSurface {
  const LyricsSurface({
    required this.owner,
    required this.boundsRoot,
    required this.primary,
    required this.landingFromTopPx,
    required this.clipLight,
  });
  final Object owner;
  final Rect boundsRoot;
  final bool primary;
  final double landingFromTopPx;
  final bool clipLight;
}

/// The player owes [songId] a return to playing that is NOT a play/pause
/// (a skip or a restart buffers: it lands paused and resumes as a separate
/// commit). Absorbed, unless PLAY itself was just tapped: the resume until
/// [resumeUntilUptimeMs] (once), and a pause until [dipUntilUptimeMs] — the
/// buffering dip itself.
@immutable
final class PlaySettle {
  const PlaySettle({required this.songId, required this.resumeUntilUptimeMs, required this.dipUntilUptimeMs});

  /// After a skip / restart seen at [nowUptimeMs] whose resume is owed until [resumeUntilUptimeMs].
  factory PlaySettle.after(Object songId, int nowUptimeMs, {int? resumeUntilUptimeMs}) => PlaySettle(
    songId: songId,
    resumeUntilUptimeMs: resumeUntilUptimeMs ?? nowUptimeMs + transportTapValidityMs,
    dipUntilUptimeMs: nowUptimeMs + playSettleDipWindowMs,
  );

  final Object songId;
  final int resumeUntilUptimeMs;
  final int dipUntilUptimeMs;

  bool absorbs(Object songId, bool isPlaying, int nowUptimeMs) =>
      this.songId == songId && nowUptimeMs <= (isPlaying ? resumeUntilUptimeMs : dipUntilUptimeMs);

  @override
  bool operator ==(Object other) =>
      other is PlaySettle &&
      other.songId == songId &&
      other.resumeUntilUptimeMs == resumeUntilUptimeMs &&
      other.dipUntilUptimeMs == dipUntilUptimeMs;

  @override
  int get hashCode => Object.hash(songId, resumeUntilUptimeMs, dipUntilUptimeMs);
}

/// How long after a skip / restart is seen its buffering dip may still commit.
const int playSettleDipWindowMs = 600;

/// A user seek on [songId] while it [playing]: its buffering dip is the
/// seek's, not a play/pause. Seeking while paused owes no resume: null.
PlaySettle? seekPlaySettle(Object? songId, {required bool playing, required int nowUptimeMs}) =>
    songId != null && playing ? PlaySettle.after(songId, nowUptimeMs) : null;

/// A tap counts for the change it caused for this long (covers Spotify's round trip).
const int transportTapValidityMs = 3000;

@immutable
final class TransportPulseDecision {
  const TransportPulseDecision(this.pulse, {required this.tapUsed, this.settle});
  final TransportPulse pulse;

  /// The tap was spent on this change; clear it so nothing reuses it.
  final bool tapUsed;

  /// What the next resolution should read as [resolveTransportPulse]'s `settle`.
  final PlaySettle? settle;
}

/// The pure P0 + option-B rule. Only a change between two non-null keys
/// pulses, so entering / leaving playback and the first frame never flash.
TransportPulseDecision resolveTransportPulse({
  required TransportPulseKey? previous,
  required TransportPulseKey? current,
  required TransportTap? tap,
  required int nowUptimeMs,
  required bool lyricsPrimary,
  required LyricsHandover? handover,
  PlaySettle? settle,
}) {
  if (previous == null || current == null || previous == current) {
    return TransportPulseDecision(const PulseNone(), tapUsed: false, settle: settle);
  }
  final age = tap == null ? -1 : nowUptimeMs - tap.uptimeMs;
  final freshTap = tap != null && age >= 0 && age <= transportTapValidityMs ? tap : null;
  final skipTap = freshTap != null && freshTap.kind != TransportTapKind.playPause ? freshTap : null;
  // songId changed → a skip, whatever isPlaying did alongside it.
  if (previous.songId != current.songId) {
    final TransportPulse pulse;
    if (lyricsPrimary && handover?.fromSongId == previous.songId && handover?.toSongId == current.songId) {
      pulse = const PulseNone();
    } else if (lyricsPrimary) {
      pulse = PulseFlowLight(forward: lyricFlowForward(previous.queueIndex, current.queueIndex));
    } else if (skipTap != null) {
      pulse = PulseSideFlow(fromEnd: skipTap.kind == TransportTapKind.skipNext);
    } else {
      pulse = const PulseNone();
    }
    // Skipped away from playing: the new song's buffering dip / resume belongs to this skip.
    return TransportPulseDecision(
      pulse,
      tapUsed: skipTap != null,
      settle: previous.isPlaying ? PlaySettle.after(current.songId, nowUptimeMs) : null,
    );
  }
  if (previous.isPlaying == current.isPlaying) {
    return TransportPulseDecision(const PulseNone(), tapUsed: false, settle: settle);
  }
  // A skip in flight: the player may flicker paused → playing on its way. Not a play/pause.
  if (skipTap != null) return TransportPulseDecision(const PulseNone(), tapUsed: false, settle: settle);
  final playTap = freshTap != null && freshTap.kind == TransportTapKind.playPause ? freshTap : null;
  // The skip / restart buffering (its dip, then its resume) — unless PLAY was just tapped.
  if (playTap == null && settle != null && settle.absorbs(current.songId, current.isPlaying, nowUptimeMs)) {
    return TransportPulseDecision(const PulseNone(), tapUsed: false, settle: current.isPlaying ? null : settle);
  }
  return TransportPulseDecision(
    PulseBurst(isPlay: current.isPlaying, focalRoot: playTap?.centerRoot),
    tapUsed: playTap != null,
  );
}

/// Which way the lyric stream moves on a song change: forward (the next song
/// rises from below) unless the queue position went back.
bool lyricFlowForward(int previousQueueIndex, int queueIndex) => queueIndex >= previousQueueIndex;
