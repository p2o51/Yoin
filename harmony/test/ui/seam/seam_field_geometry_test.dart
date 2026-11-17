import 'dart:ui';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/theme/tokens.dart';
import 'package:yoin/ui/component/seam/seam_dissolve.dart';
import 'package:yoin/ui/component/seam/seam_halftone.dart';
import 'package:yoin/ui/component/seam/seam_style.dart';

// A phone window 800 tall, the bar 68 tall sitting 24 above its bottom edge.
const _bar = Rect.fromLTRB(16, 708, 376, 776);
const _page = Color(0xFFF0F0F0);
const _barColor = Color(0xFFE0E0E0);

SeamTail? _tail({
  double top = 650,
  double height = 120,
  double reveal = 1,
  double remaining = double.infinity,
  double stretch = 0,
}) => SeamTail.configure(
  printTopLeft: Offset(20, top),
  printSize: Size(160, height),
  bar: _bar,
  barRadius: 28,
  barColor: _barColor,
  rootHeight: 800,
  reveal: reveal,
  remaining: remaining,
  stretch: stretch,
  lagPx: 0,
  pageAtBar: _page,
  pageAtEdge: _page,
);

SeamMaskShape? _mask(SeamTopStyle style, SeamTail? tail, {double offsetFromSeam = 500}) => seamMaskFor(
  style: style,
  offsetFromSeam: offsetFromSeam,
  printSize: const Size(160, 120),
  origin: Offset.zero,
  size: const Size(160, 120),
  printLeft: 20,
  reveal: 1,
  stretch: 0,
  disorder: 0,
  lagPx: 0,
  travelPx: 0,
  reduced: false,
  round: false,
  seed: 1.5,
  tail: tail,
);

void main() {
  group('tail', () {
    test('should_leavePrintUntouched_when_wellAboveTheBar', () {
      expect(_tail(top: 100, height: 120), isNull);
      expect(_tail(top: 650), isNotNull);
    });

    test('should_vanish_when_barIsAway', () {
      expect(_tail(reveal: 0), isNull);
    });

    test('should_startTheApproachAboveTheBar_when_contentCanScroll', () {
      final tail = _tail()!;
      // In the print's own px: the approach opens 20 above the bar and ends 28 behind its top edge.
      expect(tail.y0, closeTo(_bar.top - SeamTokens.fieldStart - 650, 1e-9));
      expect(tail.yb, closeTo(_bar.top + SeamTokens.fieldHidden - 650, 1e-9));
      expect(tail.bar, _bar.shift(const Offset(-20, -650)));
      expect(tail.end, 150);
    });

    test('should_shrinkTheApproach_when_contentNearsItsEnd', () {
      expect(_tail(remaining: 20)!.len, closeTo((SeamTokens.fieldStart + SeamTokens.fieldHidden) / 2, 1e-9));
      final end = _tail(remaining: 0)!;
      expect(end.len, 0);
      expect(end.y0, end.yb);
    });

    test('should_liftTheStart_when_flung', () {
      expect(_tail(stretch: 1)!.len, closeTo(_tail()!.len + SeamTokens.fieldStretch, 1e-9));
    });

    test('should_runStillLatticeUnderTheBar_when_atRest', () {
      final tail = _tail()!;
      final lattice = SeamLattice.of(160);
      // A row behind the bar, past the approach: the pure field's radius, thinning toward the screen's edge.
      final row = ((tail.yb + 10) / lattice.rowHeight).floor();
      final dot = seamDot(
        lattice: lattice,
        width: 160,
        height: 120,
        column: 5,
        row: row,
        seed: 1,
        disorder: 0,
        tail: tail,
      );
      final site = seamSite(lattice, 5, row);
      expect(dot.x, site.x);
      expect(dot.y, site.y);
      expect(dot.radius, lessThan(tail.k0 * lattice.cellWidth + 1e-9));
      expect(dot.radius, greaterThan(tail.k1 * lattice.cellWidth - 1e-9));
    });

    test('should_keepWholePrint_when_aboveTheApproach', () {
      final tail = _tail()!;
      final lattice = SeamLattice.of(160);
      final dot = seamDot(
        lattice: lattice,
        width: 160,
        height: 120,
        column: 3,
        row: 0,
        seed: 1,
        disorder: 0,
        tail: tail,
      );
      expect(dot.radius, lattice.fullRadius);
    });

    test('should_easeTowardPage_when_deeperInTheField', () {
      final tail = _tail()!;
      expect(tail.fadeAt(tail.y0), 0);
      expect(tail.fadeAt(tail.bar.top), closeTo(SeamTokens.fieldFadeAtBar, 1e-9));
      expect(tail.fadeAt(tail.fadeEnd), closeTo(SeamTokens.fieldFadeAtEdge, 1e-9));
    });

    test('should_measureSignedDistance_when_nearTheBarsRoundedRect', () {
      final tail = _tail()!;
      final b = tail.bar;
      expect(tail.barDistance(b.center.dx, b.top - 5), closeTo(5, 1e-9));
      expect(tail.barDistance(b.center.dx, b.center.dy), lessThan(0));
      // Past a rounded corner the distance runs to the arc, not to the box.
      expect(tail.barDistance(b.left - 3, b.top - 3), greaterThan(3 * 1.41));
    });
  });

  group('lightness yield', () {
    test('should_readCieLightness_when_givenGreys', () {
      expect(seamLightness(const Color(0xFFFFFFFF)), closeTo(100, 0.01));
      expect(seamLightness(const Color(0xFF000000)), closeTo(0, 0.01));
      expect(seamLightness(const Color(0xFF777777)), closeTo(50, 1));
    });

    test('should_giveWay_when_dotIsAsLightAsTheBar', () {
      final tail = _tail()!;
      final y = tail.bar.top - 2;
      // At the bar's lightness, right at the bar: gone.
      expect(seamYieldFactor(tail, tail.barLum, y, tail.yieldInset), closeTo(0, 1e-9));
      // Whole again a reach further out.
      expect(seamYieldFactor(tail, tail.barLum, y, tail.yieldInset + tail.yieldReach), closeTo(1, 1e-9));
      // A dark dot next to a light bar never yields.
      expect(seamYieldFactor(tail, 10, y, tail.yieldInset), 1);
    });

    test('should_shrinkDotBesideTheBar_when_lumIsKnown', () {
      final tail = _tail()!;
      final lattice = SeamLattice.of(160);
      final row = ((tail.bar.top - 2) / lattice.rowHeight).floor();
      SeamDot dot(double lum) => seamDot(
        lattice: lattice,
        width: 160,
        height: 120,
        column: 6,
        row: row,
        seed: 1,
        disorder: 0,
        tail: tail,
        lum: lum,
      );
      expect(dot(tail.barLum).radius, lessThan(dot(double.nan).radius));
      expect(dot(5).radius, dot(double.nan).radius);
    });
  });

  group('mask', () {
    test('should_maskOnlyTheField_when_styleIsTide', () {
      final shape = _mask(SeamTopStyle.tide, _tail());
      expect(shape, isA<SeamMaskDots>());
      final dots = shape! as SeamMaskDots;
      expect(dots.tail, isNotNull);
      // Whole above the field, dots below.
      expect(dots.mask.solidFrom, 0);
      expect(dots.mask.solidTo, lessThan(dots.tail!.y0));
      expect(dots.mask.dots, isNotEmpty);
    });

    test('should_maskNothing_when_noFieldAndFarFromTheSeam', () {
      expect(_mask(SeamTopStyle.dots, null), isNull);
      expect(_mask(SeamTopStyle.tide, null), isNull);
    });

    test('should_skipDotsUnderTheBar_when_barIsFullyIn', () {
      final dots = (_mask(SeamTopStyle.tide, _tail())! as SeamMaskDots).mask.dots;
      final tail = _tail()!;
      for (final d in dots) {
        expect(tail.barDistance(d.x, d.y), greaterThanOrEqualTo(-d.radius - 0.5));
      }
    });

    test('should_cutTheFieldWithTheLip_when_cookieStyleMeetsTheField', () {
      // A tall print reaching from under the chrome seam down to the bar.
      final shape = _mask(SeamTopStyle.cookie, _tail(), offsetFromSeam: -10);
      expect(shape, isA<SeamMaskLip>());
      expect((shape! as SeamMaskLip).field, isNotNull);
    });

    test('should_takeBothSeams_when_dotsStyleMeetsTheField', () {
      final shape = _mask(SeamTopStyle.dots, _tail(), offsetFromSeam: -10)! as SeamMaskDots;
      expect(shape.mask.solidFrom, greaterThan(0));
      expect(shape.mask.solidTo.isFinite, isTrue);
    });
  });
}
