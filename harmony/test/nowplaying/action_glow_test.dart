import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/nowplaying/action_glow.dart';
import 'package:yoin/nowplaying/edge_dot_band.dart';
import 'package:yoin/playback/play_mode.dart';

// ActionGlowTest.kt, ported; plus the edge band's life and lattice (EdgeDotBand.kt).

void main() {
  group('action lights', () {
    test('should_lightShuffle_when_roundKeyTurnsShuffleOn', () {
      expect(playModeGlowKind(PlayMode.repeatAll, PlayMode.repeatAll.next()), ActionGlowKind.shuffleOn);
    });

    test('should_lightEachRepeatState_when_repeatSegmentSteps', () {
      expect(playModeGlowKind(PlayMode.shuffle, PlayMode.shuffle.nextRepeat()), ActionGlowKind.repeatOne);
      expect(playModeGlowKind(PlayMode.repeatOne, PlayMode.repeatOne.nextRepeat()), ActionGlowKind.repeatOff);
      expect(playModeGlowKind(PlayMode.repeatOff, PlayMode.repeatOff.nextRepeat()), ActionGlowKind.repeatAll);
    });

    test('should_lightShuffleOff_when_shuffleSegmentTurnsItOff', () {
      expect(playModeGlowKind(PlayMode.shuffle, PlayMode.shuffle.withShuffle(false)), ActionGlowKind.shuffleOff);
    });

    test('should_lightNothing_when_modeUnchanged', () {
      expect(playModeGlowKind(PlayMode.repeatOne, PlayMode.repeatOne), isNull);
    });

    test('should_drain_when_ratingCleared', () {
      expect(ratingGlowKind(0), ActionGlowKind.ratingClear);
      expect(ratingGlowKind(null), ActionGlowKind.ratingClear);
      expect(ratingGlowKind(7.5), ActionGlowKind.ratingCommit);
    });

    test('should_runLonger_when_ratingIsFullMarks', () {
      expect(ActionGlowKind.ratingCommit.durationFor(8), 1300);
      expect(ActionGlowKind.ratingCommit.durationFor(10), 1700);
      expect(ActionGlowKind.favoriteOn.durationFor(10), ActionGlowKind.favoriteOn.durationMs);
    });

    test('should_placeRatingFromBottom_when_barIsVertical', () {
      const bar = Rect.fromLTRB(100, 0, 150, 200);
      expect(ratingPoint(bar, 0, vertical: true, rtl: false), const Offset(125, 200));
      expect(ratingPoint(bar, 5, vertical: true, rtl: false), const Offset(125, 100));
      expect(ratingPoint(bar, 10, vertical: true, rtl: false), const Offset(125, 0));
    });

    test('should_placeRatingFromStart_when_barIsHorizontal', () {
      const bar = Rect.fromLTRB(0, 0, 200, 40);
      expect(ratingPoint(bar, 2.5, vertical: false, rtl: false), const Offset(50, 20));
      expect(ratingPoint(bar, 2.5, vertical: false, rtl: true), const Offset(150, 20));
    });

    test('should_retireRunningPlayModeLight_when_keyTappedAgain', () {
      const key = Rect.fromLTRB(0, 0, 48, 48);
      final glows = [const ActionGlow(kind: ActionGlowKind.shuffleOn, anchorRoot: key, bornUptimeMs: 1000)];
      addHandingOver(glows, const ActionGlow(kind: ActionGlowKind.repeatOne, anchorRoot: key, bornUptimeMs: 1300));
      expect(glows[0].retiredUptimeMs, 1300);
      expect(glows[1].retired, isFalse);
      expect(glows[0].isDone(1300 + glowHandOverMs), isFalse);
      expect(glows[0].isDone(1301 + glowHandOverMs), isTrue);
      expect(glows[1].isDone(1301 + glowHandOverMs), isFalse);
    });

    test('should_leaveOtherLightsRunning_when_playModeLightFires', () {
      const key = Rect.fromLTRB(0, 0, 48, 48);
      final glows = [const ActionGlow(kind: ActionGlowKind.favoriteOn, anchorRoot: key, bornUptimeMs: 1000)];
      addHandingOver(glows, const ActionGlow(kind: ActionGlowKind.shuffleOn, anchorRoot: key, bornUptimeMs: 1100));
      expect(glows[0].retired, isFalse);
      expect(glows, hasLength(2));
    });

    test('should_keepPlayModeLightsShort_when_fired', () {
      final kinds = ActionGlowKind.values.where((k) => k.isPlayMode).toList();
      expect(kinds, hasLength(5));
      for (final k in kinds) {
        expect(k.durationMs, lessThanOrEqualTo(800), reason: '$k');
      }
    });

    test('should_drawEveryKind_when_runningThroughItsLife', () {
      // Every kind paints at its start, middle and end without throwing.
      final pal = GlowPalette(Colors.deepPurple, Colors.teal, Colors.pink, dark: true);
      for (final kind in ActionGlowKind.values) {
        final glow = ActionGlow(
          kind: kind,
          anchorRoot: const Rect.fromLTWH(300, 400, 48, 160),
          bornUptimeMs: 0,
          value: 10,
          targetY: 500,
          areaRoot: const Rect.fromLTWH(0, 300, 400, 300),
        );
        for (final t in [0.0, glow.durationMs / 2, glow.durationMs.toDouble()]) {
          final recorder = ui.PictureRecorder();
          paintActionGlow(Canvas(recorder), const Size(400, 800), glow, t, Offset.zero, pal, BlendMode.screen);
          recorder.endRecording().dispose();
        }
      }
    });
  });

  group('edge band', () {
    test('should_burstThenRecedeAndBeGone_when_bornOnFocus', () {
      final (b0, f0) = edgeBandLife(0);
      expect(b0, 0);
      expect(f0, 1);
      final (peak, _) = edgeBandLife(0.22);
      expect(peak, closeTo(1, 0.001));
      final (settled, held) = edgeBandLife(1.5);
      expect(settled, lessThan(0.05));
      expect(held, 1, reason: 'recedes only from 2 s');
      final (_, receding) = edgeBandLife(5);
      expect(receding, inExclusiveRange(0, 1));
      final (_, gone) = edgeBandLife(bandGoneS);
      expect(gone, 0);
    });

    test('should_slowTheWaves_when_paused', () {
      final playing = EdgeBandState()
        ..advance(0, 1)
        ..advance(1000, 1);
      final paused = EdgeBandState()
        ..advance(0, 0)
        ..advance(1000, 0);
      expect(playing.phase, closeTo(0.25, 1e-9), reason: 'a frame gap counts at most 0.25 s');
      expect(paused.phase, closeTo(0.25 * pausedWaveRate, 1e-9));
    });

    test('should_liveForEightSeconds_when_reborn', () {
      final band = EdgeBandState()..enter(1000);
      expect(band.alive(1000 + bandGoneS * 1000 - 1), isTrue);
      expect(band.alive(1000 + bandGoneS * 1000), isFalse);
      band.songChange(20000);
      expect(band.alive(25000), isTrue);
      expect(band.songPulseAtMs, 20000);
    });

    test('should_dotOnlyTheEdges_when_theBandIsDrawn', () {
      final band = EdgeBandState()..enter(0);
      final dots = DotLayers(bandSegments);
      final recorder = ui.PictureRecorder();
      paintEdgeBand(
        Canvas(recorder),
        const Size(400, 800),
        state: band,
        dots: dots,
        nowMs: 1000,
        focus: 1,
        playing: 1,
        cover: const Rect.fromLTWH(50, 100, 300, 300),
        lyrics: null,
        palette: const [0xFFE53935, 0xFF1E88E5, 0xFF43A047, 0xFFFDD835],
        primary: Colors.deepPurple,
        dark: true,
      );
      recorder.endRecording().dispose();
      expect(dots.count, greaterThan(0));
      // The lattice skips the room's middle: far fewer dots than lattice points.
      const lattice = (400 / bandPitch) * (800 / (bandPitch * 0.866));
      expect(dots.count, lessThan(lattice / 2));
    });

    test('should_ringTheKeyThenFade_when_aTransportKeyIsTapped', () {
      final dots = DotLayers(1);
      const key = Rect.fromLTWH(100, 100, 120, 56);
      final recorder = ui.PictureRecorder();
      final canvas = Canvas(recorder);
      paintTapDots(canvas, dots, key, 1, 0.3, Colors.deepPurple);
      final mid = dots.count;
      paintTapDots(canvas, dots, key, 1, 0, Colors.deepPurple);
      expect(dots.count, mid, reason: 'nothing at the very start: the layers keep the last draw');
      recorder.endRecording().dispose();
      expect(mid, greaterThan(0));
    });
  });
}
