import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/memories/copy/memory_copy.dart';
import 'package:yoin/memories/emblem/groove_colors.dart';
import 'package:yoin/memories/emblem/groove_geometry.dart';
import 'package:yoin/memories/emblem/groove_haptics.dart';
import 'package:yoin/memories/emblem/groove_script.dart';
import 'package:yoin/memories/palette/memory_palette.dart';
import 'package:flutter/material.dart';

List<bool> tracks(int n, int rated) => List.generate(n, (i) => i < rated);

void main() {
  group('geometry', () {
    test('should_capRings_when_sizeShrinks', () {
      expect(grooveRingCap(124), 12);
      expect(grooveRingCap(110), 12);
      expect(grooveRingCap(109), 8);
      expect(grooveRingCap(88), 8);
      expect(grooveRingCap(87), 6);
      expect(grooveRingCap(64), 6);
      expect(grooveRingCap(63), 3);
      final t = tracks(30, 30);
      int rings(double s) => grooveGeometry(t, s, GrooveScoreKind.album, GrooveSurface.cover).rings.length;
      // the band also has to hold each ring at the minimum pitch (1.72dp, 1.3 when tiny)
      for (final size in [48.0, 64.0, 72.0, 88.0, 96.0, 110.0, 124.0, 200.0]) {
        final g = grooveGeometry(t, size, GrooveScoreKind.album, GrooveSurface.cover);
        final byBand = ((g.grooveOuter - g.grooveInner) / (g.tiny ? 1.3 : 1.72)).floor();
        expect(rings(size), [grooveRingCap(size), byBand].reduce((a, b) => a < b ? a : b), reason: '$size');
      }
      expect(rings(200), 12);
      expect(rings(48), 2); // cap 3, but a 48dp band only holds two
      expect(grooveGeometry(t, 48, GrooveScoreKind.album, GrooveSurface.cover).tiny, isTrue);
      expect(grooveGeometry(t, 64, GrooveScoreKind.album, GrooveSurface.cover).tiny, isFalse);
    });

    test('should_useOneRingPerTrack_when_fewerTracksThanBudget', () {
      expect(grooveGeometry(tracks(4, 2), 96, GrooveScoreKind.album, GrooveSurface.cover).rings.length, 4);
    });

    test('should_mergeTracks_when_moreTracksThanRings', () {
      final g = grooveGeometry(tracks(10, 4), 72, GrooveScoreKind.album, GrooveSurface.cover);
      expect([for (final r in g.rings) (r.firstTrack, r.endTrack)], [(0, 3), (3, 5), (5, 8), (8, 10)]);
      expect(g.rings[0].runs, [const GrooveRun(-90, 270)]);
      expect(g.rings[1].runs, [const GrooveRun(-90, 90)]);
      expect(g.rings[2].runs, isEmpty);
      expect(g.ratedRings, [0, 1]);
      expect(g.ringOrder(1), 1);
      expect(g.ringOrder(3), 0);
    });

    test('should_cutNothing_when_unrated', () {
      final g = grooveGeometry(tracks(12, 12), 96, GrooveScoreKind.none, GrooveSurface.cover);
      expect(g.rings.every((r) => r.runs.isEmpty), isTrue);
      expect(g.showsUnratedWord, isTrue);
    });

    test('should_splitRuns_when_ratedTracksAreApart', () {
      expect(grooveCutRuns([true, false, true, true]), [const GrooveRun(-90, 0), const GrooveRun(90, 270)]);
    });

    test('should_insetRimLess_when_onBar', () {
      expect(grooveGeometry(tracks(8, 8), 48, GrooveScoreKind.album, GrooveSurface.bar).rimRadius, 23.5);
      expect(grooveGeometry(tracks(8, 8), 48, GrooveScoreKind.album, GrooveSurface.cover).rimRadius, 23);
    });
  });

  group('tier', () {
    test('should_pickTierFromShownScore_when_rounded', () {
      expect(grooveTierOf(GrooveScoreKind.album, MemoryScores.text(9.95)), 4);
      expect(grooveTierOf(GrooveScoreKind.album, '10.0'), 4);
      expect(grooveTierOf(GrooveScoreKind.album, '9.9'), 3);
      expect(grooveTierOf(GrooveScoreKind.average, '8.0'), 3);
      expect(grooveTierOf(GrooveScoreKind.average, '7.9'), 2);
      expect(grooveTierOf(GrooveScoreKind.album, '6.0'), 2);
      expect(grooveTierOf(GrooveScoreKind.album, MemoryScores.text(5.95)), 2);
      expect(grooveTierOf(GrooveScoreKind.album, '5.9'), 1);
      expect(grooveTierOf(GrooveScoreKind.album, '0.0'), 1);
    });

    test('should_haveNoTier_when_unrated', () {
      expect(grooveTierOf(GrooveScoreKind.none, '9.0'), 0);
      expect(grooveTierOf(GrooveScoreKind.album, null), 0);
    });
  });

  group('script', () {
    GrooveScript script(int tier, {int n = 10, int rated = 5, double size = 96}) =>
        grooveScriptFor(tier: tier, kind: GrooveScoreKind.album, trackRated: tracks(n, rated), size: size)!;

    test('should_restAtZero_when_scriptEnds', () {
      for (final tier in [1, 2, 3, 4]) {
        final s = script(tier);
        expect(s.disc(s.durationS * 3).abs(), lessThan(1), reason: 'tier $tier');
        expect((s.label(s.durationS * 3) - 1).abs(), lessThan(0.01));
        expect(s.cut(0, s.durationS), closeTo(1, 0.01));
        expect(s.cut(0, 0), 0);
      }
    });

    test('should_keepAtMostSevenBeats_when_awardBeatsArePlanned', () {
      for (final tier in [1, 2, 3, 4]) {
        for (final size in [48.0, 96.0, 124.0]) {
          final s = script(tier, n: 24, rated: 24, size: size);
          final beats = grooveAwardBeats(s, GrooveBeatMode.first, tiny: size < 60);
          expect(beats.length, inInclusiveRange(1, 7), reason: 'tier $tier size $size');
          for (var i = 1; i < s.beats.length; i++) {
            expect(s.beats[i].atMs - s.beats[i - 1].atMs, greaterThanOrEqualTo(GrooveScript.mergeWindowMs));
          }
        }
      }
    });

    test('should_dropQuickRise_when_tierFour', () {
      final beats = grooveAwardBeats(script(4), GrooveBeatMode.first, tiny: false);
      expect(beats.any((b) => b.primitive == GroovePrimitive.quickRise), isFalse);
      expect(strongestBeat(beats).primitive, GroovePrimitive.thud);
    });

    test('should_playOneBeatAtReveal_when_reducedMotion', () {
      final beats = grooveAwardBeats(script(3), GrooveBeatMode.first, tiny: false, reducedMotion: true);
      expect(beats.length, 1);
      expect(beats.single.atMs, GrooveBeatTimes.revealMs);
    });

    test('should_halveStrongestBeat_when_nodding', () {
      final s = script(2);
      final strongest = strongestBeat(grooveAwardBeats(s, GrooveBeatMode.first, tiny: false));
      final nod = grooveAwardBeats(s, GrooveBeatMode.nod, tiny: true).single;
      expect(nod.scale, closeTo(strongest.scale / 2, 0.006));
      expect(nod.atMs, GrooveBeatTimes.nodMs);
      expect(GrooveBeatTimes.nodMs, inInclusiveRange(60, 200));
    });

    test('should_mergeCloseBeats_when_within45ms', () {
      const a = GrooveBeat(atMs: 100, primitive: GroovePrimitive.tick, scale: 0.4, fallback: null, what: 'a');
      const b = GrooveBeat(atMs: 130, primitive: GroovePrimitive.click, scale: 0.9, fallback: null, what: 'b');
      const c = GrooveBeat(atMs: 200, primitive: GroovePrimitive.tick, scale: 0.4, fallback: null, what: 'c');
      expect(mergeCloseBeats([c, a, b]).map((x) => x.what), ['b', 'c']);
    });
  });

  test('should_matchKotlinColours_when_m1Album', () {
    const neutrals = GrooveNeutrals(
      outline: Colors.grey,
      surface: Colors.white,
      onSurfaceVariant: Colors.black,
      dark: false,
    );
    final light = grooveColors(
      MemoryPaletteSamples.m1,
      GrooveScoreKind.album,
      dark: false,
      surface: GrooveSurface.cover,
      neutrals: neutrals,
    );
    expect(light.ground.toARGB32(), 0xFFDDD7F3);
    final dark = grooveColors(
      MemoryPaletteSamples.m1,
      GrooveScoreKind.album,
      dark: true,
      surface: GrooveSurface.cover,
      neutrals: neutrals,
    );
    expect(dark.cut.toARGB32(), 0xFFD1C0C3);
  });
}
