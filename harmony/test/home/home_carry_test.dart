import 'dart:ui';

import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/home/home_carry.dart';
import 'package:yoin/home/home_edit_wiggle.dart';
import 'package:yoin/home/home_section.dart';
import 'package:yoin/nowplaying/haptics.dart';
import 'package:yoin/platform/haptics/haptics_host.dart';
import 'package:yoin/theme/tokens.dart';

/// Android HomeEditGeometry.kt (§Strips) and HomeCarryEngine.kt, ported.
void main() {
  group('geometry', () {
    test('should_centreTheCarriedStripOnTheFinger_when_theStackFits', () {
      final m = stripMetrics(
        count: 5,
        carriedIndex: 2,
        fingerY: 400,
        safeTop: 50,
        safeBottom: 750,
        contentLeft: 16,
        contentWidth: 380,
      );
      // (700 − 4·8) / 5 = 133.6 → clamped to 64.
      expect(m.height, HomeCarryTokens.stripMaxH);
      expect(m.pitch, 72);
      expect(m.stackHeight, 5 * 72 - 8);
      expect(m.slotY(2) + m.height / 2, closeTo(400, 1e-9));
      expect(m.coverSize, 36);
      expect(m.width, 380);
    });

    test('should_clampTheStackIntoTheSafeArea_when_theFingerIsNearAnEdge', () {
      final top = stripMetrics(
        count: 5,
        carriedIndex: 4,
        fingerY: 60,
        safeTop: 50,
        safeBottom: 750,
        contentLeft: 0,
        contentWidth: 900,
      );
      expect(top.top, 50);
      expect(top.width, HomeCarryTokens.stripMaxW);
      final bottom = stripMetrics(
        count: 5,
        carriedIndex: 0,
        fingerY: 740,
        safeTop: 50,
        safeBottom: 750,
        contentLeft: 0,
        contentWidth: 300,
      );
      expect(bottom.top, 750 - bottom.stackHeight);
    });

    test('should_shrinkStripsToTheMinimum_when_manySectionsShare_aShortWindow', () {
      final m = stripMetrics(
        count: 10,
        carriedIndex: 0,
        fingerY: 100,
        safeTop: 40,
        safeBottom: 440,
        contentLeft: 0,
        contentWidth: 300,
      );
      expect(m.height, HomeCarryTokens.stripMinH);
      expect(m.coverSize, HomeCarryTokens.stripCoverMin);
    });

    test('should_startFromTheVisiblePlate_orAnInvisibleStripAtItsEdge', () {
      final m = stripMetrics(
        count: 3,
        carriedIndex: 0,
        fingerY: 200,
        safeTop: 50,
        safeBottom: 750,
        contentLeft: 16,
        contentWidth: 380,
      );
      final partly = stripStart(const Rect.fromLTRB(8, 20, 404, 300), m, above: false);
      expect(partly.rect, const Rect.fromLTRB(8, 50, 404, 300));
      expect(partly.alpha, 1);
      final gone = stripStart(const Rect.fromLTRB(8, -400, 404, 10), m, above: false);
      expect(gone.alpha, 0);
      expect(gone.rect.top, closeTo(50 + m.height * 0.02, 1e-9));
      expect(gone.rect.width, closeTo(m.width * 0.96, 1e-9));
      final below = stripStart(null, m, above: false);
      expect(below.rect.bottom, closeTo(750 - m.height * 0.02, 1e-9));
    });

    test('should_runFromThePlateToTheSlot_when_folding', () {
      final m = stripMetrics(
        count: 3,
        carriedIndex: 0,
        fingerY: 200,
        safeTop: 50,
        safeBottom: 750,
        contentLeft: 16,
        contentWidth: 380,
      );
      const start = StripStart(Rect.fromLTRB(8, 100, 404, 500), 1);
      final open = stripFrame(start: start, slotTop: m.slotY(1), metrics: m, fold: 0, carried: false, lift: 0);
      expect(open.rect, start.rect);
      expect(open.plateAlpha, 0);
      expect(open.labelAlpha, 0);
      final folded = stripFrame(start: start, slotTop: m.slotY(1), metrics: m, fold: 1, carried: false, lift: 0);
      expect(folded.rect, Rect.fromLTWH(16, m.slotY(1), 380, m.height));
      expect(folded.plateAlpha, 1);
      expect(folded.labelAlpha, 1);
      expect(folded.tint, HomeCarryTokens.stripTint);
      expect(folded.radius, HomeCarryTokens.plateRadius);
      // The carried strip grows by the lift and casts its shadow.
      final carried = stripFrame(start: start, slotTop: m.slotY(1), metrics: m, fold: 1, carried: true, lift: 1);
      expect(carried.rect.width, closeTo(380 * 1.02, 1e-9));
      expect(carried.shadowAlpha, 1);
      // The feed fades as the exact complement of the plates.
      for (final f in [0.0, 0.1, 0.3, 0.45, 0.8]) {
        expect(
          feedFoldAlpha(f) +
              stripFrame(start: start, slotTop: 0, metrics: m, fold: f, carried: false, lift: 0).plateAlpha,
          closeTo(1, 1e-9),
        );
      }
    });

    test('should_invertTheRubberBand_when_regrabbing', () {
      expect(rubber(0), 0);
      expect(rubber(1000), lessThan(HomeCarryTokens.rubberD));
      for (final x in [1.0, 10.0, 40.0, 120.0]) {
        expect(unrubber(rubber(x)), closeTo(x, 1e-6));
      }
    });

    test('should_clipTheSquareToTheContent_when_pressingNearAnEdge', () {
      final r = pressRect(const Offset(10, 300), const Size(360, 320), 1);
      expect(r, const Rect.fromLTRB(-4, 248, 62, 324));
      expect(chargeScale(1), 1 - HomeCarryTokens.chargeScale);
      expect(liftScale(1), 1 + HomeCarryTokens.liftScale);
      expect(chargePlateAlpha(1), HomeCarryTokens.chargePlateAlpha);
    });

    test('should_measureTheReleaseSpeed_when_fingerKeepsMoving', () {
      final v = HomeEditVelocity()
        ..add(0, 0)
        ..add(16, 32)
        ..add(32, 64);
      expect(v.velocity(32), closeTo(2000, 1e-6));
      // Rested before lifting: 0.
      expect(v.velocity(32 + HomeCarryTokens.velocityStaleMs + 1), 0);
      final fast = HomeEditVelocity()
        ..add(0, 0)
        ..add(10, 500);
      expect(fast.velocity(10), HomeCarryTokens.maxV);
    });

    test('should_tintTowardTheCover_when_folded', () {
      const plate = Color(0xFF000000), lifted = Color(0xFF808080), cover = Color(0xFFFFFFFF);
      expect(
        stripPlateColor(carried: false, liftTint: 1, plate: plate, liftedPlate: lifted, coverBase: null, tint: .3),
        plate,
      );
      expect(
        stripPlateColor(carried: true, liftTint: 1, plate: plate, liftedPlate: lifted, coverBase: null, tint: .3),
        lifted,
      );
      expect(
        stripPlateColor(carried: false, liftTint: 0, plate: plate, liftedPlate: lifted, coverBase: cover, tint: 0),
        plate,
      );
      expect(
        stripPlateColor(carried: false, liftTint: 0, plate: plate, liftedPlate: lifted, coverBase: cover, tint: .3),
        Color.lerp(plate, cover, .3),
      );
    });

    test('should_placeAnUnbuiltSectionAbove_when_itPrecedesTheLastVisible', () {
      const order = [HomeSection.activities, HomeSection.jumpBackIn, HomeSection.recentlyAdded, HomeSection.rediscover];
      expect(carryIsAbove(HomeSection.activities, order, const [HomeSection.recentlyAdded], pastHeader: false), isTrue);
      expect(carryIsAbove(HomeSection.rediscover, order, const [HomeSection.recentlyAdded], pastHeader: true), isFalse);
      expect(carryIsAbove(HomeSection.rediscover, order, const [], pastHeader: true), isTrue);
    });
  });

  group('engine', () {
    late RecordingHapticsHost haptics;
    setUp(() {
      haptics = RecordingHapticsHost();
      YoinHaptics.host = haptics;
    });
    tearDown(() => YoinHaptics.host = const NoHapticsHost());

    const order = [HomeSection.activities, HomeSection.jumpBackIn, HomeSection.recentlyAdded, HomeSection.rediscover];

    ({HomeCarryEngine engine, _FakeHost host, List<List<HomeSection>> commits, List<bool> exits}) build() {
      final commits = <List<HomeSection>>[];
      final exits = <bool>[];
      final host = _FakeHost(order);
      final engine = HomeCarryEngine(
        vsync: const TestVSync(),
        specs: () => HomeCarrySpecs(reduced: false),
        wiggle: HomeEditWiggle(vsync: const TestVSync()),
        commitOrder: (o) {
          commits.add(o);
          return true;
        },
        finishDeferredExit: ({required done}) => exits.add(done),
        uptimeMs: () => 0,
      )..host = host;
      return (engine: engine, host: host, commits: commits, exits: exits);
    }

    Future<void> settle(WidgetTester tester) async {
      for (var i = 0; i < 120; i++) {
        await tester.pump(const Duration(milliseconds: 16));
      }
    }

    testWidgets('should_swapEachHalfPitchAndCommitTheNewOrder_when_carriedAndDropped', (tester) async {
      final r = build();
      final engine = r.engine;
      engine.liftBlock(HomeSection.jumpBackIn, const Offset(20, 20));
      engine.start(300, 0);
      final s = engine.session!;
      expect(s.k, 1);
      expect(s.metrics.slotY(1) + s.metrics.height / 2, closeTo(300, 1e-9));
      final pitch = s.metrics.pitch;
      // Just under half a pitch: no swap.
      engine.move(300 + pitch / 2 - 1, 16);
      expect(s.ids, order);
      // Past half a pitch: one swap, a segment tick, the hole follows.
      engine.move(300 + pitch / 2 + 1, 32);
      expect(s.ids, [
        HomeSection.activities,
        HomeSection.recentlyAdded,
        HomeSection.jumpBackIn,
        HomeSection.rediscover,
      ]);
      expect(s.k, 2);
      expect(haptics.performed.where((h) => h == YoinHaptic.segmentTick), hasLength(1));
      await tester.pump(const Duration(milliseconds: 200));
      engine.release(cancelled: true, uptimeMs: 300);
      expect(s.phase, CarryPhase.settle);
      expect(engine.isBusy, isTrue);
      await settle(tester);
      expect(r.commits.single, s.ids);
      expect(r.host.anchored, HomeSection.jumpBackIn);
      expect(engine.session, isNull);
      expect(engine.fold.value, 0);
      expect(engine.liftSection, isNull);
    });

    testWidgets('should_rubberBandAndTick_when_draggedPastTheLastSlot', (tester) async {
      final r = build();
      final engine = r.engine;
      engine.liftBlock(HomeSection.rediscover, Offset.zero);
      engine.start(300, 0);
      final s = engine.session!;
      engine.move(300 + 100, 16);
      expect(s.display, closeTo(rubber(100), 1e-9));
      expect(haptics.performed, contains(YoinHaptic.threshold));
      engine.release(cancelled: true, uptimeMs: 32);
      await settle(tester);
      // Nothing moved: nothing written.
      expect(r.commits, isEmpty);
    });

    testWidgets('should_projectOneMoreSlot_when_releasedFast', (tester) async {
      final r = build();
      final engine = r.engine;
      engine.liftBlock(HomeSection.activities, Offset.zero);
      engine.start(200, 0);
      final s = engine.session!;
      engine
        ..move(205, 10)
        ..move(240, 30);
      engine.release(cancelled: false, uptimeMs: 30);
      expect(s.k, 1);
      await settle(tester);
      expect(r.commits.single.indexOf(HomeSection.activities), 1);
    });

    testWidgets('should_catchTheStrip_when_pressedWhileItSettles', (tester) async {
      final r = build();
      final engine = r.engine;
      engine.liftBlock(HomeSection.jumpBackIn, Offset.zero);
      engine.start(300, 0);
      final s = engine.session!;
      engine.release(cancelled: true, uptimeMs: 10);
      final top = s.metrics.slotY(s.k);
      expect(engine.tryRegrab(Offset(s.metrics.left - 1, top + 10), 20), isFalse);
      expect(engine.tryRegrab(Offset(s.metrics.left + 10, top + 10), 20), isTrue);
      expect(s.phase, CarryPhase.drag);
      expect(haptics.performed.last, YoinHaptic.dragStart);
      await settle(tester);
      // The settle that was caught never commits.
      expect(engine.session, isNotNull);
      engine.release(cancelled: true, uptimeMs: 3000);
      await settle(tester);
      expect(engine.session, isNull);
    });

    testWidgets('should_runTheExitAfterTheUnfold_when_itArrivesMidCarry', (tester) async {
      final r = build();
      final engine = r.engine;
      engine.liftBlock(HomeSection.jumpBackIn, Offset.zero);
      engine.start(300, 0);
      engine.deferExit(done: true);
      expect(engine.session!.phase, CarryPhase.settle);
      expect(r.exits, isEmpty);
      await settle(tester);
      expect(r.exits, [true]);
      // With no carry it runs at once.
      engine.deferExit(done: false);
      expect(r.exits, [true, false]);
    });

    testWidgets('should_commitAndRest_when_abortedMidDrag', (tester) async {
      final r = build();
      final engine = r.engine;
      engine.liftBlock(HomeSection.activities, Offset.zero);
      engine.start(200, 0);
      engine.move(200 + engine.session!.metrics.pitch, 16);
      engine.abortNow();
      expect(r.commits.single.first, HomeSection.jumpBackIn);
      expect(engine.session, isNull);
      expect(engine.fold.value, 0);
      expect(engine.liftSection, isNull);
      await settle(tester);
    });

    testWidgets('should_letGoOfTheLift_when_droppedWithoutACarry', (tester) async {
      final r = build();
      final engine = r.engine;
      engine.liftBlock(HomeSection.activities, Offset.zero);
      await settle(tester);
      expect(engine.lift.value, 1);
      engine.release(cancelled: false, uptimeMs: 0);
      await settle(tester);
      expect(engine.lift.value, 0);
      expect(engine.liftSection, isNull);
    });
  });
}

class _FakeHost implements CarryHost {
  _FakeHost(this.order);

  final List<HomeSection> order;
  HomeSection? anchored;

  @override
  List<HomeSection> displayedOrder() => order;

  @override
  Rect? plateRect(HomeSection section) {
    final i = order.indexOf(section);
    return Rect.fromLTWH(8, 60 + i * 260, 396, 240);
  }

  @override
  bool isAbove(HomeSection section) => false;

  @override
  (double, double) safeArea() => (50, 750);

  @override
  double contentLeft() => 16;

  @override
  double contentWidth() => 380;

  @override
  Future<void> anchorAndAwaitLayout(HomeSection dropped, List<HomeSection> order, double slotTop) async {
    anchored = dropped;
  }
}
