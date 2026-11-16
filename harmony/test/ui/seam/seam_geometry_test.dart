import 'dart:ui';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/theme/tokens.dart';
import 'package:yoin/ui/component/seam/seam_cookie.dart';
import 'package:yoin/ui/component/seam/seam_dissolve.dart';
import 'package:yoin/ui/component/seam/seam_halftone.dart';
import 'package:yoin/ui/component/seam/seam_style.dart';

SeamMaskShape? _mask(
  SeamTopStyle style, {
  required double offset,
  Size size = const Size(100, 100),
  double reveal = 1,
  double stretch = 0,
  double disorder = 0,
  bool round = false,
}) => seamMaskFor(
  style: style,
  offsetFromSeam: offset,
  printSize: size,
  origin: Offset.zero,
  size: size,
  printLeft: 0,
  reveal: reveal,
  stretch: stretch,
  disorder: disorder,
  lagPx: 0,
  travelPx: 0,
  reduced: false,
  round: round,
  seed: 1.5,
);

void main() {
  group('dots (curve C)', () {
    test('should_restAtZeroDisorder_when_notScrolling', () {
      expect(seamDisorder(0), 0);
      expect(seamDisorder(200), closeTo(1 - 0.36788, 1e-4));
      expect(seamDisorder(100000), closeTo(1, 1e-6));
    });

    test('should_keepAtLeastFourColumns_when_elementIsNarrow', () {
      final lattice = SeamLattice.of(10);
      expect(lattice.columns, 4);
      expect(lattice.rowHeight, closeTo(lattice.cellWidth * 0.866, 1e-9));
      // A whole dot covers its cell: past the staggered lattice's covering radius.
      expect(lattice.fullRadius, greaterThan(lattice.cellWidth * 0.577));
    });

    test('should_leaveElementUntouched_when_farBelowTheSeam', () {
      final far = SeamTokens.band + SeamTokens.front + SeamTokens.pitch + 1;
      expect(SeamDotsSeam.configure(offsetFromSeam: far, reveal: 1, stretch: 0, lagPx: 0), isNull);
      expect(SeamDotsSeam.configure(offsetFromSeam: 0, reveal: 1, stretch: 0, lagPx: 0), isNotNull);
    });

    test('should_openNoBand_when_pageAtRest', () {
      expect(SeamDotsSeam.configure(offsetFromSeam: 0, reveal: 0, stretch: 0, lagPx: 0), isNull);
    });

    test('should_shrinkDotsToNothing_when_atTheSeam', () {
      // The seam 30px into a 100px cover: dots above it are gone, dots past the band whole.
      final top = SeamDotsSeam.configure(offsetFromSeam: -30, reveal: 1, stretch: 0, lagPx: 0)!;
      final lattice = SeamLattice.of(100);
      SeamDot dotAt(int row) =>
          seamDot(lattice: lattice, width: 100, height: 100, column: 3, row: row, seed: 1, disorder: 0, top: top);
      final above = (20 / lattice.rowHeight).floor();
      final below = ((30 + top.solid) / lattice.rowHeight).ceil() + 1;
      expect(dotAt(above).radius, 0);
      expect(dotAt(below).radius, lattice.fullRadius);
      final inBand = ((30 + top.band / 2) / lattice.rowHeight).round();
      expect(dotAt(inBand).radius, inInclusiveRange(0.01, lattice.fullRadius));
    });

    test('should_keepDotsOnTheirSites_when_disorderIsZero', () {
      final top = SeamDotsSeam.configure(offsetFromSeam: -20, reveal: 1, stretch: 0, lagPx: 3)!;
      final lattice = SeamLattice.of(80);
      final dot = seamDot(lattice: lattice, width: 80, height: 80, column: 2, row: 4, seed: 2, disorder: 0, top: top);
      expect(dot.x, closeTo((2 + 0.5) * lattice.cellWidth, 1e-9));
      expect(dot.y, closeTo(4.5 * lattice.rowHeight, 1e-9));
    });

    test('should_printSolidBelowTheBand_when_maskBuilt', () {
      final top = SeamDotsSeam.configure(offsetFromSeam: -10, reveal: 1, stretch: 0, lagPx: 0)!;
      final mask = SeamDotsMask.build(width: 60, height: 60, top: top, seed: 1, disorder: 0);
      expect(mask.solidFrom, greaterThan(10 + top.solid));
      expect(mask.dots, isNotEmpty);
      expect(mask.dots.every((d) => d.radius >= 0.3), isTrue);
    });
  });

  group('cookie wave', () {
    test('should_measureExitFromEntryToBottom_when_itemCrossesTheSeam', () {
      expect(seamCookieExitProgress(-SeamTokens.cookieEntry, 100, SeamTokens.cookieEntry), 0);
      expect(seamCookieExitProgress(100, 100, SeamTokens.cookieEntry), 1);
      expect(seamCookieExitProgress(44, 100, SeamTokens.cookieEntry), closeTo(0.5, 1e-9));
    });

    test('should_flattenEnvelope_when_exitStartsOrEnds', () {
      expect(seamCookieEnvelope(0), 0);
      expect(seamCookieEnvelope(1), 0);
      expect(seamCookieEnvelope(0.5), 1);
    });

    test('should_morphCookieToSoftBurst_when_exitProgresses', () {
      expect(seamCookieCharacter(0), closeTo(SeamTokens.cookieCharacterFrom, 1e-9));
      expect(seamCookieCharacter(1), closeTo(SeamTokens.cookieCharacterTo, 1e-9));
      expect(seamCookieCharacter(0.5), inExclusiveRange(SeamTokens.cookieCharacterFrom, SeamTokens.cookieCharacterTo));
    });

    test('should_keepTwoLobesAtLeast_when_itemIsSmall', () {
      expect(seamCookieLobeCount(20, SeamTokens.cookieLobe), 2);
      expect(seamCookieLobeCount(150, SeamTokens.cookieLobe), 5);
    });

    test('should_capSharpness_when_waveIsTall', () {
      expect(seamCookieTipFloor(1.8, 30, 0, 4), 1.8);
      expect(seamCookieTipFloor(1.8, 10, 6, 4), lessThan(1.8));
    });

    test('should_leaveItemUntouched_when_lipAndVeilAreAbove', () {
      // The item's top is 30px below the seam: past the lip's reach.
      expect(
        SeamLip.configure(
          seam: -30,
          width: 100,
          height: 100,
          centreX: 50,
          stretch: 0,
          disorder: 0,
          lagPx: 0,
          travelPx: 0,
          reveal: 1,
          reduced: false,
        ),
        isNull,
      );
    });

    test('should_cutBelowTheSeam_when_itemIsHalfwayOut', () {
      final lip = SeamLip.configure(
        seam: 40,
        width: 120,
        height: 120,
        centreX: 60,
        stretch: 0,
        disorder: 0,
        lagPx: 0,
        travelPx: 0,
        reveal: 1,
        reduced: true,
      )!;
      expect(lip.phase, 0, reason: 'no roll under reduced motion');
      for (var x = 0.0; x <= 120; x += 10) {
        final front = lip.front(120, 120, x, round: false);
        expect(front, inInclusiveRange(0, lip.reach), reason: 'at $x');
      }
      final keep = lip.keep(120, 120, round: false, bottom: 100);
      expect(keep.contains(const Offset(60, 90)), isTrue);
      expect(keep.contains(const Offset(60, 30)), isFalse);
    });

    test('should_dropShoulders_when_itemIsRound', () {
      final lip = SeamLip.configure(
        seam: 40,
        width: 100,
        height: 100,
        centreX: 50,
        stretch: 0,
        disorder: 0,
        lagPx: 0,
        travelPx: 0,
        reveal: 1,
        reduced: true,
      )!;
      expect(lip.front(100, 100, 0, round: true), lessThan(lip.front(100, 100, 0, round: false)));
    });
  });

  group('mask', () {
    test('should_maskNothing_when_styleIsTide', () {
      expect(_mask(SeamTopStyle.tide, offset: 0), isNull);
    });

    test('should_dropElement_when_whollyAboveTheSeam', () {
      expect(_mask(SeamTopStyle.dots, offset: -120), isA<SeamMaskGone>());
      expect(_mask(SeamTopStyle.cookie, offset: -120), isA<SeamMaskGone>());
    });

    test('should_maskNothing_when_farBelowTheSeam', () {
      expect(_mask(SeamTopStyle.dots, offset: seamDissolveReach + 1), isNull);
      expect(_mask(SeamTopStyle.cookie, offset: 40), isNull);
    });

    test('should_breakIntoDotsOrLip_when_crossingTheSeam', () {
      expect(_mask(SeamTopStyle.dots, offset: -20), isA<SeamMaskDots>());
      final lip = _mask(SeamTopStyle.cookie, offset: -20, round: true);
      expect(lip, isA<SeamMaskLip>());
      expect((lip! as SeamMaskLip).round, isTrue);
    });
  });
}
