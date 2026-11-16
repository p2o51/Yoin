import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/memories/memories_morph.dart';
import 'package:yoin/memories/memories_motion.dart';
import 'package:yoin/theme/tokens.dart';

/// The card ⇄ diary morph's choreography (Android MemoriesMorphTest), in dp:
/// morph 320, card cover 256, bar cover 40.
void main() {
  group('choreography', () {
    test('should_holdTheTitle_when_pBelowPoint2', () {
      // A slow pull never sends the title (and the emblem with it) across the still-visible cover.
      expect(morphTitleTravel(0, frozen: false), 0);
      expect(morphTitleTravel(0.1, frozen: false), 0);
      expect(morphTitleTravel(0.2, frozen: false), 0);
      expect(morphTitleTravel(0.6, frozen: false), closeTo(0.5, 1e-9));
      expect(morphTitleTravel(1, frozen: false), 1);
      // Frozen: the title never travels, it waits on the card.
      expect(morphTitleTravel(0.9, frozen: true), 0);
    });

    test('should_handTheTitleToItsTwin_when_betweenPoint42AndPoint78OfItsTravel', () {
      const card = TextAnchor(cx: 206, y: 470, fontSize: 26);
      const diary = TextAnchor(cx: 150, y: 110, fontSize: 22);
      final (atRest, twinAtRest) = morphTextPair(card, diary, 0);
      expect(atRest.dx, 0);
      expect(atRest.alpha, 1);
      expect(twinAtRest.alpha, 0);
      // The twin waits ON the card copy's spot, scaled to its size, from its top centre.
      expect(twinAtRest.dx, closeTo(card.cx - diary.cx, 1e-9));
      expect(twinAtRest.dy, closeTo(card.y - diary.y, 1e-9));
      expect(twinAtRest.scale, closeTo(26 / 22, 1e-9));
      expect(twinAtRest.origin, Alignment.topCenter);
      final (landed, twinLanded) = morphTextPair(card, diary, 1);
      expect(landed.dx, closeTo(diary.cx - card.cx, 1e-9));
      expect(landed.alpha, 0);
      expect(twinLanded.dx, 0);
      expect(twinLanded.scale, 1);
      expect(twinLanded.alpha, 1);
      // Half way through the swap window both show.
      final (mid, twinMid) = morphTextPair(card, diary, 0.6);
      expect(mid.alpha, closeTo(0.5, 1e-9));
      expect(twinMid.alpha, closeTo(0.5, 1e-9));
    });

    test('should_keepTheBarCoverHidden_when_fpBelowPoint62', () {
      // Whatever the chase says, the 40 never shows before fp .62 (it never flies at 2.4×) …
      expect(barCoverShown(1, 0.3), 0);
      expect(barCoverShown(1, 0.62), 0);
      // … and the page's own cover is gone exactly at fp 1, however late the chase is.
      expect(barCoverShown(0, 1), 1);
      // Inside the window the chase decides.
      expect(barCoverShown(0.4, 0.8), closeTo(0.4, 1e-9));
      expect(barCoverShown(0, 0.9), greaterThan(0));
      expect(barCoverTarget(0.95), 1);
    });

    test('should_leadPositionWithSize_when_theCoverFlies', () {
      // Half way, it is already 3/4 of the way to 40 dp.
      final pose = morphCoverPose(
        fp: 0.5,
        coverCenter: const Offset(206, 283),
        barCenter: const Offset(84, 64),
        coverSize: 256,
        barCoverSize: 40,
        parallax: 0,
        cardCorner: 8,
        barCorner: 4,
      );
      expect(pose.dx, closeTo((84 - 206) * 0.5, 1e-9));
      expect(pose.dy, closeTo((64 - 283) * 0.5, 1e-9));
      expect(pose.scale, closeTo(1 + (40 / 256 - 1) * 0.75, 1e-9));
      expect(pose.corner, closeTo(6, 1e-9));
      // It rides the bar's parallax as it joins it.
      final riding = morphCoverPose(
        fp: 0.5,
        coverCenter: const Offset(206, 283),
        barCenter: const Offset(84, 64),
        coverSize: 256,
        barCoverSize: 40,
        parallax: 100,
        cardCorner: 8,
        barCorner: 4,
      );
      expect(riding.dx - pose.dx, closeTo(50, 1e-9));
    });

    test('should_delayTheCoverFlight_when_frozen', () {
      // Collapsing from deep in the diary: the cover waits in the bar until the sinking text is gone (p .55).
      expect(morphCoverProgress(0.8, frozen: true), 1);
      expect(morphCoverProgress(0.55, frozen: true), 1);
      expect(morphCoverProgress(0.275, frozen: true), closeTo(0.5, 1e-9));
      expect(morphCoverProgress(0.8, frozen: false), closeTo(0.8, 1e-9));
      // The text sinks as one block (no stagger) and is gone by .55; scroll-0 blocks rise staggered.
      expect(diaryBlockOffset(0.5, 3, lag: 400, stagger: 28, frozen: true), 0);
      expect(diaryFrozenSink(0.5, 400, frozen: true), closeTo(200, 1e-9));
      expect(diaryFrozenSink(0.5, 400, frozen: false), 0);
      expect(diaryBlockAlpha(0.55, 0, frozen: true), 0);
      expect(diaryBlockOffset(0.5, 3, lag: 400, stagger: 28, frozen: false), closeTo(0.5 * (400 + 3 * 28), 1e-9));
      // k is capped at 6.
      expect(
        diaryBlockOffset(0.3, 6, lag: 400, stagger: 28, frozen: false),
        diaryBlockOffset(0.3, 9, lag: 400, stagger: 28, frozen: false),
      );
      expect(diaryBlockAlpha(0.6, 6, frozen: false), diaryBlockAlpha(0.6, 9, frozen: false));
    });

    test('should_staggerTheBlocks_when_theDiaryRises', () {
      // A later block starts later and travels further, and all have landed at p 1.
      expect(diaryBlockAlpha(0.4, 0, frozen: false), greaterThan(diaryBlockAlpha(0.4, 3, frozen: false)));
      expect(
        diaryBlockOffset(0.4, 3, lag: 200, stagger: 28, frozen: false),
        greaterThan(diaryBlockOffset(0.4, 0, lag: 200, stagger: 28, frozen: false)),
      );
      expect(diaryBlockAlpha(1, 6, frozen: false), 1);
      expect(diaryBlockOffset(1, 6, lag: 200, stagger: 28, frozen: false), 0);
    });

    test('should_trailTheTitlesRemainingTravel_when_measuringTheLag', () {
      // (470 − 110) ÷ .8 + 36; without a diary title the card title's height joins it.
      expect(diaryBlockLag(cardTitleTop: 470, diaryTop: 110, extra: 36), closeTo(486, 1e-9));
      expect(diaryBlockLag(cardTitleTop: 470, diaryTop: 110, extra: 36, cardTitleHeight: 30), closeTo(516, 1e-9));
      expect(diaryBlockLag(cardTitleTop: 100, diaryTop: 110, extra: 36), 36);
    });

    test('should_fadeThroughWithoutOverlap_when_reducedMotion', () {
      for (var p = 0.0; p <= 1; p += 0.01) {
        // One layer of text at a time: the card is gone by .5, the diary starts there.
        expect(reducedCardAlpha(p) < 1e-9 || reducedDiaryAlpha(p) < 1e-9, isTrue, reason: 'p $p');
      }
      expect(reducedCardAlpha(0), 1);
      expect(reducedDiaryAlpha(1), 1);
    });

    test('should_hand96ToTheNative48_when_theTitleFlies', () {
      const seal = Offset(300, 400);
      const dem = Offset(360, 120);
      final start = morphEmblemPose(te: 0, sealCenter: seal, demCenter: dem, sealSize: 96, demSize: 48);
      expect(start.seal.alpha, 1);
      expect(start.dem.alpha, 0);
      expect(start.captionAlpha, 1);
      // The 48 rides the same flight at the 96's live size (×2 at the start).
      expect(start.dem.scale, closeTo(2, 1e-9));
      expect(start.dem.dx, closeTo(seal.dx - dem.dx, 1e-9));
      final landed = morphEmblemPose(te: 1, sealCenter: seal, demCenter: dem, sealSize: 96, demSize: 48);
      expect(landed.seal.alpha, 0);
      expect(landed.dem.alpha, 1);
      expect(landed.dem.dx, 0);
      expect(landed.dem.scale, 1);
      expect(landed.seal.scale, closeTo(0.5, 1e-9));
      expect(landed.seal.dx, closeTo(dem.dx - seal.dx, 1e-9));
      // The caption has left by .4 of the travel.
      expect(morphEmblemPose(te: 0.4, sealCenter: seal, demCenter: dem, sealSize: 96, demSize: 48).captionAlpha, 0);
    });

    test('should_pullTheCardDown_when_pBelowZero', () {
      expect(cardRubberBand(0.4), 0);
      expect(cardRubberBand(-0.25), closeTo(0.25 * MemoriesTokens.diaryMorphDistance, 1e-9));
    });

    test('should_rideTheBarsParallax_when_aPageIsOffCentre', () {
      // Android's sign: rel = deck position − page; the slots add 0.6·W·rel.
      expect(morphParallax(0.5, 400), closeTo(120, 1e-9));
      expect(morphParallax(-1, 400), closeTo(-240, 1e-9));
    });

    test('should_changeThePhaseOnlyAtItsLines_when_pMoves', () {
      expect(MemoriesDiaryPhase.of(0), const MemoriesDiaryPhase(composed: false, open: false, atCard: true));
      expect(MemoriesDiaryPhase.of(-0.2), const MemoriesDiaryPhase(composed: false, open: false, atCard: true));
      expect(MemoriesDiaryPhase.of(0.01), const MemoriesDiaryPhase(composed: true, open: false, atCard: true));
      expect(MemoriesDiaryPhase.of(0.3), const MemoriesDiaryPhase(composed: true, open: false, atCard: false));
      expect(MemoriesDiaryPhase.of(0.5), const MemoriesDiaryPhase(composed: true, open: true, atCard: false));
      expect(MemoriesDiaryPhase.of(0.3), MemoriesDiaryPhase.of(0.4));
    });
  });

  group('layer', () {
    test('should_scaleAboutItsOrigin_when_posed', () {
      const pose = MorphPose(dx: 10, dy: 20, scale: 0.5, origin: Alignment.topCenter);
      final m = pose.matrixFor(const Size(100, 40));
      // The top centre stays put (then moves by dx, dy); the bottom-left comes in.
      expect(MatrixUtils.transformPoint(m, const Offset(50, 0)), const Offset(60, 20));
      expect(MatrixUtils.transformPoint(m, const Offset(0, 40)), const Offset(35, 40));
    });

    testWidgets('should_paintAndHitWithThePose_when_transformed', (tester) async {
      final pose = ValueNotifier(const MorphPose(dx: 100, dy: 50, scale: 0.5, origin: Alignment.topLeft));
      addTearDown(pose.dispose);
      var taps = 0;
      await tester.pumpWidget(
        Directionality(
          textDirection: TextDirection.ltr,
          child: Align(
            alignment: Alignment.topLeft,
            child: MorphLayer(
              listenable: pose,
              pose: () => pose.value,
              child: GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTap: () => taps++,
                child: const SizedBox(key: ValueKey('box'), width: 200, height: 100),
              ),
            ),
          ),
        ),
      );
      // Laid out untouched, painted moved and halved.
      expect(tester.getRect(find.byKey(const ValueKey('box'))), const Rect.fromLTWH(100, 50, 100, 50));
      await tester.tapAt(const Offset(150, 75));
      expect(taps, 1);
      // Where it was laid out (but no longer painted) takes nothing.
      await tester.tapAt(const Offset(20, 20));
      expect(taps, 1);
      // The listenable repaints it without a rebuild.
      pose.value = const MorphPose(alpha: 0.5);
      await tester.pump();
      final layer = tester.renderObject<RenderMorphLayer>(find.byType(MorphLayer));
      expect(layer.debugPainted, const MorphPose(alpha: 0.5));
      expect(tester.getRect(find.byKey(const ValueKey('box'))), const Rect.fromLTWH(0, 0, 200, 100));
      // Invisible, it is not hit.
      pose.value = const MorphPose(alpha: 0);
      await tester.pump();
      await tester.tapAt(const Offset(20, 20));
      expect(taps, 1);
    });

    testWidgets('should_measureTheLayout_when_ancestorsAreTransformedAndScrolled', (tester) async {
      final root = GlobalKey();
      final target = GlobalKey();
      final scroll = ScrollController();
      addTearDown(scroll.dispose);
      final pose = ValueNotifier(const MorphPose(dx: 40, dy: -30, scale: 2));
      addTearDown(pose.dispose);
      await tester.pumpWidget(
        Directionality(
          textDirection: TextDirection.ltr,
          child: Stack(
            key: root,
            children: [
              Positioned(
                left: 10,
                top: 20,
                width: 300,
                height: 400,
                child: Transform.translate(
                  offset: const Offset(55, 66),
                  child: SingleChildScrollView(
                    controller: scroll,
                    child: Padding(
                      padding: const EdgeInsets.only(top: 500, left: 7),
                      child: Align(
                        alignment: Alignment.topLeft,
                        child: MorphLayer(
                          listenable: pose,
                          pose: () => pose.value,
                          child: SizedBox(key: target, width: 20, height: 20),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      );
      scroll.jumpTo(300);
      await tester.pump();
      final box = target.currentContext!.findRenderObject()! as RenderBox;
      final rootBox = root.currentContext!.findRenderObject()!;
      // The rest position: no translate, no scroll, no pose.
      expect(morphLayoutOffset(box, rootBox), const Offset(17, 520));
      // An unrelated ancestor is not found.
      expect(morphLayoutOffset(rootBox as RenderBox, box), isNull);
    });
  });

  test('should_keepPRangeForTheMorph_when_tokensRead', () {
    // The choreography's windows sit inside p's travel.
    expect(MemoriesTokens.coverSwapFrom, lessThan(MemoriesTokens.coverSwapTo));
    expect(MemoriesTokens.coverFloorFrom, greaterThan(MemoriesTokens.coverSwapFrom));
    expect(smoothstep(MemoriesTokens.titleSwapFrom, MemoriesTokens.titleSwapTo, 1), 1);
  });
}
