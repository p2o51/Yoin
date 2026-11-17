import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/memories/emblem/groove_award.dart';
import 'package:yoin/memories/emblem/groove_emblem.dart';
import 'package:yoin/memories/emblem/groove_geometry.dart';
import 'package:yoin/memories/emblem/groove_script.dart';
import 'package:yoin/memories/palette/memory_palette.dart';

Widget host(Widget child, {bool reduced = false}) => MaterialApp(
  home: MediaQuery(
    data: MediaQueryData(disableAnimations: reduced),
    child: Scaffold(body: Center(child: child)),
  ),
);

GrooveEmblem emblem(
  double size,
  GrooveAwardController? award, {
  String score = '10.0',
  GrooveScoreKind kind = GrooveScoreKind.album,
  GrooveSurface surface = GrooveSurface.cover,
}) => GrooveEmblem(
  size: size,
  ratedTracks: List.generate(10, (i) => i < 5),
  kind: kind,
  scoreText: score,
  palette: MemoryPaletteSamples.m1,
  surface: surface,
  award: award,
);

Future<void> runFrames(WidgetTester tester, int ms) async {
  for (var t = 0; t < ms; t += 16) {
    await tester.pump(const Duration(milliseconds: 16));
  }
}

void main() {
  testWidgets('should_paintAllKinds_when_pumpedAt96And48', (tester) async {
    for (final kind in GrooveScoreKind.values) {
      await tester.pumpWidget(
        host(
          Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              emblem(96, null, kind: kind, score: '7.8'),
              emblem(48, null, kind: kind, score: '7.8', surface: GrooveSurface.bar),
            ],
          ),
        ),
      );
      expect(find.byType(GrooveEmblem), findsNWidgets(2));
      expect(tester.takeException(), isNull);
    }
    expect(find.bySemanticsLabel('Not rated'), findsWidgets);
  });

  testWidgets('should_finishAward_when_playedToCompletion', (tester) async {
    final beats = <GrooveBeat>[];
    final award = GrooveAwardController(onBeat: beats.add)..setPending(true);
    await tester.pumpWidget(host(emblem(96, award)));
    expect(award.tier, 4);
    expect(award.cutProgress(0), 0); // waits at frame 0: nothing engraved
    award.play();
    await tester.pump();
    expect(award.isAnimating, isTrue);
    await runFrames(tester, 2500);
    expect(award.isAnimating, isFalse);
    expect(award.isAwarded, isTrue);
    expect(award.cutProgress(0), 1);
    expect(award.discDegrees, 0);
    expect(beats.length, inInclusiveRange(2, 7));
    award.dispose();
  });

  testWidgets('should_notCount_when_revokedBeforeClimax', (tester) async {
    final award = GrooveAwardController(onBeat: null);
    await tester.pumpWidget(host(emblem(96, award, score: '8.6')));
    award.play();
    await runFrames(tester, 160);
    expect(award.revoke(), isTrue);
    await runFrames(tester, 1000);
    expect(award.isAwarded, isFalse);
    expect(award.isAnimating, isFalse);
    award.dispose();
  });

  testWidgets('should_dropBeats_when_pausedDuringDrag', (tester) async {
    final beats = <GrooveBeat>[];
    final award = GrooveAwardController(onBeat: beats.add);
    await tester.pumpWidget(host(emblem(96, award)));
    award
      ..play()
      ..pause();
    await runFrames(tester, 2500);
    expect(beats, isEmpty);
    expect(award.isAwarded, isTrue); // the picture kept playing
    award.dispose();
  });

  testWidgets('should_nodBarEmblem_when_diaryShowsIt', (tester) async {
    final beats = <GrooveBeat>[];
    final award = GrooveAwardController(onBeat: beats.add)..setPending(true);
    await tester.pumpWidget(host(emblem(48, award, score: '7.2', surface: GrooveSurface.bar)));
    expect(award.emblemScale, GrooveAwardController.barWaitScale);
    expect(award.discDegrees, GrooveAwardController.barWaitTurn);
    award.nod();
    await runFrames(tester, 800);
    expect(award.isAnimating, isFalse);
    expect(award.emblemScale, 1);
    expect(award.discDegrees, 0);
    expect(award.cutProgress(0), 0); // still uncut: the card's award cuts it
    expect(beats.length, 1);
    award.dispose();
  });

  testWidgets('should_revealByAlpha_when_reducedMotion', (tester) async {
    final beats = <GrooveBeat>[];
    final award = GrooveAwardController(onBeat: beats.add)..setPending(true);
    await tester.pumpWidget(host(emblem(96, award), reduced: true));
    expect(award.cutAlpha(0), 0);
    expect(award.cutProgress(0), 1);
    award.play();
    await runFrames(tester, 600);
    expect(award.isAnimating, isFalse);
    expect(award.cutAlpha(0), 1);
    expect(award.discDegrees, 0);
    expect(beats.single.atMs, 200);
    award.dispose();
  });

  testWidgets('should_restFinished_when_finishInstantly', (tester) async {
    final award = GrooveAwardController(onBeat: null)..setPending(true);
    await tester.pumpWidget(host(emblem(96, award)));
    award.play();
    await runFrames(tester, 300);
    award.finishInstantly();
    expect(award.isAnimating, isFalse);
    expect(award.cutProgress(0), 1);
    expect(award.labelScale, 1);
    award.dispose();
  });
}
