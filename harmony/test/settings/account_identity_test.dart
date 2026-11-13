import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/settings/account_identity.dart';
import 'package:yoin/settings/material_shapes.dart';
import 'package:yoin/settings/settings_colors.dart';

void main() {
  test('should_matchJavaHashSlot_when_assigningShapes', () {
    // Java: "a".hashCode() == 97 → floorMod(97, 8) == 1.
    expect(assignAvatarShapes(['a']), {'a': 1});
  });

  test('should_probeToNextFreeSlot_when_hashesCollide', () {
    // "a" (97) and "i" (105) both land on slot 1; the newer one moves on.
    expect(assignAvatarShapes(['a', 'i']), {'a': 1, 'i': 2});
  });

  test('should_handleNegativeHash_when_idHashesBelowZero', () {
    final id = 'f' * 12; // overflows to a negative Java hash
    final slot = assignAvatarShapes([id])[id]!;
    expect(slot, inInclusiveRange(0, avatarShapeCount - 1));
  });

  test('should_skipServiceName_when_monogramOfTitledAccount', () {
    expect(monogramOf('gpo'), 'G');
    expect(monogramOf('@home'), 'H');
    expect(monogramOf('陈'), '陈');
    expect(monogramOf('Subsonic 31x', 'Subsonic'), '3');
    expect(monogramOf('🎵'), '');
  });

  test('should_dropRepeatedParts_when_buildingServiceLine', () {
    expect(serviceLine('Subsonic', 'gpo', 'home.test'), (kind: 'Subsonic', detail: 'home.test'));
    expect(serviceLine('Subsonic', 'home.test', 'home.test'), (kind: 'Subsonic', detail: null));
    expect(serviceLine('Subsonic', 'My Subsonic', null), isNull);
  });

  test('should_fitUnitSquare_when_shapesNormalized', () {
    for (final path in [...avatarShapes, cookie9Sided]) {
      final b = path.getBounds();
      expect(b.left, greaterThanOrEqualTo(-0.01));
      expect(b.top, greaterThanOrEqualTo(-0.01));
      expect(b.right, lessThanOrEqualTo(1.01));
      expect(b.bottom, lessThanOrEqualTo(1.01));
      expect(b.longestSide, closeTo(1, 0.02));
      expect(path.contains(const Offset(0.5, 0.5)), isTrue);
    }
  });

  test('should_capHarmonizeTurn_when_huesFarApart', () {
    expect(harmonizeHue(256, 100), closeTo(246, 0.001)); // 156° apart: turns the max 10°
    expect(harmonizeHue(256, 266), closeTo(261, 0.001)); // 10° apart: turns half
  });
}
