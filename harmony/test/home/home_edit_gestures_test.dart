import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/home/home_edit_gestures.dart';
import 'package:yoin/home/home_metrics.dart';
import 'package:yoin/home/home_section.dart';

/// Android HomeEditGestures.kt's machine, ported: a pure state machine fed
/// one finger and its timers.
void main() {
  const t0 = 1000;
  const longPress = 500;
  const slop = 18.0;
  const block = HomeEditHitBlock(HomeSection.jumpBackIn, Offset(40, 30), Size(300, 200));

  HomeEditGestureMachine machine() => HomeEditGestureMachine(longPressMs: longPress, slopPx: slop);

  HomeEditDown down(
    HomeEditHit hit, {
    bool editing = false,
    bool busy = false,
    bool fling = false,
    bool secondary = false,
  }) => HomeEditDown(
    hit: hit,
    position: const Offset(100, 300),
    editing: editing,
    carryBusy: busy,
    flingInProgress: fling,
    t: t0,
    secondaryClick: secondary,
  );

  List<Type> types(List<HomeEditGestureAction> actions) => [for (final a in actions) a.runtimeType];

  group('normal mode', () {
    test('should_chargeAtHalfTThenEnterWithTheBlockLifted_when_heldForT', () {
      final m = machine();
      expect(m.handle(down(block)), isEmpty);
      expect(m.state, HomeEditGestureState.pressing);
      // T/2: the charge.
      expect(m.pendingTimer!.kind, HomeEditTimer.halfT);
      expect(m.pendingTimer!.atMs, t0 + longPress ~/ 2);
      final charge = m.handle(const HomeEditTimerFired(HomeEditTimer.halfT));
      expect(types(charge), [HomeEditStartCharge]);
      // T: the threshold frame, in order: the plate latches the charge before it is released, the lift needs edit mode.
      expect(m.pendingTimer!.atMs, t0 + longPress);
      final threshold = m.handle(const HomeEditTimerFired(HomeEditTimer.longPress));
      expect(types(threshold), [
        HomeEditLongPressHaptic,
        HomeEditEnter,
        HomeEditLatchPlate,
        HomeEditReleaseCharge,
        HomeEditLift,
        HomeEditConsumeRest,
        HomeEditPointerDown,
      ]);
      final enter = threshold[1] as HomeEditEnter;
      expect(enter.origin, HomeSection.jumpBackIn);
      expect(enter.lifted, isTrue);
      expect(m.state, HomeEditGestureState.lifted);
      // Within the slop nothing moves; past it the carry starts.
      expect(m.handle(const HomeEditMove(Offset(100, 310), t0 + 600)), isEmpty);
      final carry = m.handle(const HomeEditMove(Offset(100, 330), t0 + 616));
      expect(types(carry), [HomeEditStartCarry]);
      expect((carry.single as HomeEditStartCarry).y, 330);
      expect(m.state, HomeEditGestureState.carry);
      expect(types(m.handle(const HomeEditMove(Offset(100, 400), t0 + 632))), [HomeEditCarryMove]);
      final up = m.handle(const HomeEditUp(t0 + 650));
      expect(types(up), [HomeEditPointerDown, HomeEditTouch, HomeEditCarryRelease, HomeEditOpenPlaceholders]);
      expect((up[2] as HomeEditCarryRelease).cancelled, isFalse);
      expect(m.state, HomeEditGestureState.idle);
    });

    test('should_letTheListHaveIt_when_movedPastTheSlopBeforeT', () {
      final m = machine()..handle(down(block));
      m.handle(const HomeEditTimerFired(HomeEditTimer.halfT));
      final moved = m.handle(const HomeEditMove(Offset(100, 330), t0 + 300));
      expect(types(moved), [HomeEditReleaseCharge, HomeEditAbandon]);
      expect(m.state, HomeEditGestureState.idle);
      expect(m.pendingTimer, isNull);
    });

    test('should_abandonWithoutAKick_when_tappedBeforeT', () {
      final m = machine()..handle(down(block));
      expect(types(m.handle(const HomeEditUp(t0 + 100))), [HomeEditAbandon]);
      expect(m.state, HomeEditGestureState.idle);
    });

    test('should_dropTheLiftWithATapKick_when_releasedWithoutMoving', () {
      final m = machine()..handle(down(block));
      m.handle(const HomeEditTimerFired(HomeEditTimer.longPress));
      final up = m.handle(const HomeEditUp(t0 + 700));
      expect(types(up), [HomeEditPointerDown, HomeEditTouch, HomeEditDropLift, HomeEditOpenPlaceholders]);
      expect((up[2] as HomeEditDropLift).kick, HomeWiggleMetrics.kickTap);
    });

    test('should_enterUnliftedFromTheNearest_when_blankIsHeldForT', () {
      final m = machine()..handle(down(const HomeEditHitBlank(HomeSection.activities)));
      // No charge off a block.
      expect(m.pendingTimer!.kind, HomeEditTimer.longPress);
      final threshold = m.handle(const HomeEditTimerFired(HomeEditTimer.longPress));
      final enter = threshold.whereType<HomeEditEnter>().single;
      expect(enter.origin, HomeSection.activities);
      expect(enter.lifted, isFalse);
      expect(threshold.whereType<HomeEditLift>(), isEmpty);
      expect(m.state, HomeEditGestureState.done);
    });

    test('should_enterAtOnce_when_secondaryClicked', () {
      final m = machine();
      final actions = m.handle(down(block, secondary: true));
      expect(types(actions), [HomeEditEnter, HomeEditConsumeRest, HomeEditPointerDown]);
      expect((actions.first as HomeEditEnter).lifted, isFalse);
    });

    test('should_ignore_when_excluded', () {
      final m = machine();
      expect(m.handle(down(const HomeEditHitExcluded())), isEmpty);
      expect(m.state, HomeEditGestureState.idle);
    });
  });

  group('edit mode', () {
    test('should_liftAfterTheBodyHold_when_aBlockIsHeld', () {
      final m = machine();
      expect(types(m.handle(down(block, editing: true))), [HomeEditTouch, HomeEditPointerDown]);
      expect(m.state, HomeEditGestureState.holding);
      // max(150 ms, T/2).
      expect(m.pendingTimer!.atMs, t0 + 250);
      final lift = m.handle(const HomeEditTimerFired(HomeEditTimer.bodyHold));
      expect(types(lift), [HomeEditDragStartHaptic, HomeEditLift, HomeEditConsumeRest]);
      expect(m.state, HomeEditGestureState.lifted);
    });

    test('should_holdAtLeast150Ms_when_theLongPressIsShort', () {
      final m = HomeEditGestureMachine(longPressMs: 200, slopPx: slop)..handle(down(block, editing: true));
      expect(m.pendingTimer!.atMs, t0 + 150);
    });

    test('should_kickNotExit_when_aBlockIsTapped', () {
      final m = machine()..handle(down(block, editing: true));
      final up = m.handle(const HomeEditUp(t0 + 80));
      expect(types(up), [
        HomeEditPointerDown,
        HomeEditTouch,
        HomeEditBlockTap,
        HomeEditConsumeRest,
        HomeEditOpenPlaceholders,
      ]);
      expect((up[2] as HomeEditBlockTap).local, block.local);
    });

    test('should_scrollAndWatchForTheUp_when_aBodyPressMovesPastTheSlop', () {
      final m = machine()..handle(down(block, editing: true));
      expect(types(m.handle(const HomeEditMove(Offset(100, 340), t0 + 50))), [HomeEditAbandon]);
      expect(m.state, HomeEditGestureState.passive);
      expect(types(m.handle(const HomeEditUp(t0 + 300))), [
        HomeEditPointerDown,
        HomeEditTouch,
        HomeEditOpenPlaceholders,
      ]);
    });

    test('should_liftAndCarryAtTheFirstSlop_when_aHandleIsDragged', () {
      final m = machine()
        ..handle(down(const HomeEditHitHandle(HomeSection.activities, Offset(300, 10)), editing: true));
      expect(m.state, HomeEditGestureState.handleDown);
      expect(m.pendingTimer, isNull);
      final carry = m.handle(const HomeEditMove(Offset(100, 330), t0 + 40));
      expect(types(carry), [HomeEditDragStartHaptic, HomeEditLift, HomeEditStartCarry, HomeEditConsumeRest]);
      expect(m.state, HomeEditGestureState.carry);
    });

    test('should_kickTheBlock_when_aHandleIsTapped', () {
      final m = machine()
        ..handle(down(const HomeEditHitHandle(HomeSection.activities, Offset(300, 10)), editing: true));
      final tap = m.handle(const HomeEditUp(t0 + 60)).whereType<HomeEditBlockTap>().single;
      // A handle has no card under it: the block kicks.
      expect(tap.local, isNull);
    });

    test('should_exit_when_blankIsTapped', () {
      final m = machine()..handle(down(const HomeEditHitBlank(null), editing: true));
      expect(types(m.handle(const HomeEditUp(t0 + 60))), [
        HomeEditPointerDown,
        HomeEditTouch,
        HomeEditExitBlank,
        HomeEditConsumeRest,
        HomeEditOpenPlaceholders,
      ]);
    });

    test('should_notExit_when_theBlankTapStopsAFlingOrIsHeldForT', () {
      final fling = machine()..handle(down(const HomeEditHitBlank(null), editing: true, fling: true));
      expect(fling.handle(const HomeEditUp(t0 + 60)).whereType<HomeEditExitBlank>(), isEmpty);
      final held = machine()..handle(down(const HomeEditHitBlank(null), editing: true));
      held.handle(const HomeEditTimerFired(HomeEditTimer.blankHold));
      expect(held.handle(const HomeEditUp(t0 + 600)).whereType<HomeEditExitBlank>(), isEmpty);
    });

    test('should_catchTheStripOrSwallowThePress_when_theCarryIsBusy', () {
      final m = machine();
      final actions = m.handle(down(block, editing: true, busy: true));
      expect(types(actions), [HomeEditTouch, HomeEditPointerDown, HomeEditTryRegrab, HomeEditConsumeRest]);
      expect(m.state, HomeEditGestureState.done);
      m.handle(const HomeEditRegrabbed());
      expect(m.state, HomeEditGestureState.carry);
      expect(types(m.handle(const HomeEditMove(Offset(100, 320), t0 + 16))), [HomeEditCarryMove]);
    });

    test('should_neverLiftInNormalMode_when_editEndsUnderTheFinger', () {
      final m = machine()..handle(down(block, editing: true));
      expect(types(m.handle(const HomeEditEditEnded())), [HomeEditConsumeRest]);
      expect(m.state, HomeEditGestureState.done);
      expect(m.handle(const HomeEditTimerFired(HomeEditTimer.bodyHold)), isEmpty);
    });

    test('should_releaseAsCancelled_when_theCarryIsCancelled', () {
      final m = machine()..handle(down(const HomeEditHitHandle(HomeSection.activities, Offset.zero), editing: true));
      m.handle(const HomeEditMove(Offset(100, 340), t0 + 30));
      final release = m.handle(const HomeEditCancel(t0 + 50)).whereType<HomeEditCarryRelease>().single;
      expect(release.cancelled, isTrue);
    });

    test('should_consumeOtherFingers_when_oneIsTracked', () {
      final m = machine();
      expect(m.handle(const HomeEditOtherPointer()), isEmpty);
      m.handle(down(block, editing: true));
      expect(types(m.handle(const HomeEditOtherPointer())), [HomeEditConsumeOther]);
    });
  });

  group('hit testing', () {
    const bands = [
      SectionBand(HomeSection.activities, 100, 300),
      SectionBand(HomeSection.recentlyAdded, 340, 500, bleeds: true),
    ];

    HomeEditHit hit(Offset p, {bool editing = false, HomeSection? handle}) => resolveHomeEditHit(
      position: p,
      bands: bands,
      contentLeft: 16,
      contentRight: 376,
      outset: const Size(12, 12),
      editing: editing,
      handle: handle,
    );

    test('should_resolveTheBlockInContentPx_when_insideABand', () {
      final b = hit(const Offset(50, 150)) as HomeEditHitBlock;
      expect(b.section, HomeSection.activities);
      expect(b.local, const Offset(34, 50));
      expect(b.size, const Size(360, 200));
    });

    test('should_countThePlateOutset_when_editing', () {
      expect(hit(const Offset(8, 150)), isA<HomeEditHitBlank>());
      expect(hit(const Offset(8, 150), editing: true), isA<HomeEditHitBlock>());
      expect(hit(const Offset(100, 310), editing: true), isA<HomeEditHitBlock>());
    });

    test('should_takeTheMargin_when_theShelfBleeds', () {
      expect((hit(const Offset(2, 400)) as HomeEditHitBlock).section, HomeSection.recentlyAdded);
    });

    test('should_nameTheNearest_when_blank', () {
      expect((hit(const Offset(100, 320)) as HomeEditHitBlank).nearest, HomeSection.activities);
      expect((hit(const Offset(100, 900)) as HomeEditHitBlank).nearest, HomeSection.recentlyAdded);
    });

    test('should_preferTheHandle_when_oneIsUnderTheFinger', () {
      expect(hit(const Offset(370, 105), editing: true, handle: HomeSection.activities), isA<HomeEditHitHandle>());
    });
  });
}
