import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/memories/memories_layout.dart';
import 'package:yoin/memories/memories_motion.dart';
import 'package:yoin/memories/memories_screen.dart';

void main() {
  group('release rules', () {
    test('should_commitDismiss_when_pastTriggerOrFastUp', () {
      expect(chooseDismissTarget(112, 0, DismissRule.body), 1);
      expect(chooseDismissTarget(40, 600, DismissRule.body), 1);
      expect(chooseDismissTarget(60, 0, DismissRule.body), 0);
      expect(chooseDismissTarget(56, 0, DismissRule.bar), 1);
    });

    test('should_stayOpen_when_flickedBack', () {
      // A flick back the other way returns even past the commit distance.
      expect(chooseDismissTarget(300, -350, DismissRule.body), 0);
    });

    test('should_shortenCommit_when_slopAteTravel', () {
      expect(DismissRule.body.afterSlop(18).commitPx, 94);
      expect(DismissRule.bar.afterSlop(80).commitPx, 0);
    });

    test('should_openPull_when_pastHalfOrFlungDown', () {
      expect(chooseRevealTarget(0.4, 0), 0);
      expect(chooseRevealTarget(0.6, 0), 1);
      expect(chooseRevealTarget(0.9, -2), 0);
      expect(chooseRevealTarget(0.1, 2), 1);
    });

    test('should_rubberBand_when_pastClosed', () {
      expect(revealRubberBand(-0.2), 0);
      expect(revealRubberBand(0.5), 0.5);
      final over = revealRubberBand(3);
      expect(over, greaterThan(1));
      expect(over, lessThan(1.3));
    });

    test('should_settleDiary_when_released', () {
      double target(double f, double v, {bool scrolled = false}) => chooseDiaryReleaseTarget(
            fraction: f,
            openVelocity: v,
            distance: 320,
            band: 24,
            flick: 350,
            fromScrolled: scrolled,
          );
      expect(target(-0.1, 1000), 0); // the card's rubber band always returns
      expect(target(0.6, 0), 1);
      expect(target(0.4, 0), 0);
      expect(target(0.9, -400), 0); // a flick down closes
      // A fling "to the top" out of scrolled text stays in the diary.
      expect(target(0.97, -2000, scrolled: true), 1);
    });

    test('should_halveFirstBand_when_pullComesOutOfText', () {
      // Banded: the first 24 px past the top move the morph at half speed.
      expect(diaryPullToMorph(320, 320, 24, true), 320);
      expect(diaryPullToMorph(310, 320, 24, true), 315);
      for (final m in [0.0, 100.0, 310.0, 320.0]) {
        expect(diaryPullToMorph(diaryMorphToPull(m, 320, 24, true), 320, 24, true), closeTo(m, 1e-9));
      }
      // Below the card the rubber band applies.
      expect(diaryPullToMorph(-100, 320, 24, false), -30);
    });
  });

  group('layout', () {
    test('should_useShortExhibit_when_shortPhone', () {
      final tall = memoriesLayoutFor(392, 850);
      expect(tall.tier, MemoriesTier.phone);
      expect(tall.cover, 256);
      expect(tall.seal, 96);
      final short = memoriesLayoutFor(392, 700);
      expect(short.cover, 168);
      expect(short.seal, 72);
    });

    test('should_growCover_when_medium', () {
      final m = memoriesLayoutFor(800, 1280);
      expect(m.tier, MemoriesTier.medium);
      expect(m.cover, 360);
      expect(m.seal, 124);
    });

    test('should_fadeNeighbourSlots_when_paging', () {
      expect(barVisibility(0), 1);
      expect(barVisibility(0.5), 0);
      expect(barParallax(1, 400), 240);
      expect(nearestDot(40, 5, 18, 16), 1);
      expect(nearestDot(500, 5, 18, 16), 4);
    });
  });

  testWidgets('should_keepHomeState_when_revealMoves', (tester) async {
    late MemoriesRevealController reveal;
    await tester.pumpWidget(
      MaterialApp(
        home: _Host(onReveal: (r) => reveal = r, child: const _Counter()),
      ),
    );
    await tester.tap(find.text('0'));
    await tester.pump();
    expect(find.text('1'), findsOneWidget);
    // Opening Memories reshapes nothing under Home: its state survives.
    reveal.dragBy(200, 800);
    await tester.pump();
    reveal.snapTo(1);
    await tester.pump();
    expect(find.text('1'), findsOneWidget);
  });
}

class _Host extends StatefulWidget {
  const _Host({required this.onReveal, required this.child});

  final void Function(MemoriesRevealController) onReveal;
  final Widget child;

  @override
  State<_Host> createState() => _HostState();
}

class _HostState extends State<_Host> with TickerProviderStateMixin {
  late final MemoriesRevealController _reveal = MemoriesRevealController(vsync: this);

  @override
  void initState() {
    super.initState();
    widget.onReveal(_reveal);
  }

  @override
  void dispose() {
    _reveal.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => MemoriesHomeBehind(reveal: _reveal, child: widget.child);
}

class _Counter extends StatefulWidget {
  const _Counter();

  @override
  State<_Counter> createState() => _CounterState();
}

class _CounterState extends State<_Counter> {
  int _n = 0;

  @override
  Widget build(BuildContext context) => TextButton(onPressed: () => setState(() => _n++), child: Text('$_n'));
}
