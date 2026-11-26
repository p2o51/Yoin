import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/home/home_edit_lift.dart';
import 'package:yoin/theme/tokens.dart';

/// Android HomeEditGeometry.kt §Press, ported: the press square, the charge and the lift.
void main() {
  test('should_clipTheSquareToTheContent_when_pressingNearAnEdge', () {
    final r = pressRect(const Offset(10, 300), const Size(360, 320), 1);
    expect(r, const Rect.fromLTRB(-4, 248, 62, 324));
    expect(chargeScale(1), 1 - HomeCarryTokens.chargeScale);
    expect(liftScale(1), 1 + HomeCarryTokens.liftScale);
    expect(chargePlateAlpha(1), HomeCarryTokens.chargePlateAlpha);
  });

  test('should_keepTheLatchedPreShow_untilThePlatePassesIt', () {
    expect(latchedPlateAlpha(1, 0), HomeCarryTokens.chargePlateAlpha);
    expect(latchedPlateAlpha(1, 0.9), 0.9);
    expect(latchedPlateAlpha(0, 0.2), 0.2);
  });
}
