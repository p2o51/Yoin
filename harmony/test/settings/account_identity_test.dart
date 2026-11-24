import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/settings/account_identity.dart';
import 'package:yoin/settings/settings_colors.dart';
import 'package:yoin/ui/shapes/shape_path.dart';

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

  test('should_showScopeHintAndOptionalHost_when_buildingAccountCardLine', () {
    expect(
      accountCardLine('Subsonic', 'gpo', 'demo.navidrome.org', 'Quality and downloads'),
      (host: 'demo.navidrome.org', scope: 'Quality and downloads'),
    );
    expect(
      accountCardLine('Apple Music', 'Apple Music', null, 'Song and artist names'),
      (host: null, scope: 'Song and artist names'),
    );
  });

  test('should_useAndroidSilhouettes_when_pickingAvatarPolygon', () {
    expect([for (var i = 0; i < avatarShapeCount; i++) avatarPolygon(i)], [
      MaterialShapes.cookie6Sided,
      MaterialShapes.clover4Leaf,
      MaterialShapes.gem,
      MaterialShapes.arch,
      MaterialShapes.slanted,
      MaterialShapes.ghostish,
      MaterialShapes.sunny,
      MaterialShapes.oval,
    ]);
    expect(avatarPolygon(avatarShapeCount + 2), same(MaterialShapes.gem));
  });

  test('should_fitUnitSquare_when_shapesNormalized', () {
    for (final polygon in [for (var i = 0; i < avatarShapeCount; i++) avatarPolygon(i), MaterialShapes.cookie9Sided]) {
      final path = polygon.toPath();
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

  testWidgets('should_morphFromOldShape_when_avatarSlotChanges', (tester) async {
    Widget avatar(int slot) => MaterialApp(
      home: Center(
        child: AccountAvatar(
          monogram: '',
          shapeIndex: slot,
          identity: ServiceIdentity.subsonic,
          ringColor: Colors.white,
        ),
      ),
    );
    ShapeBorder shape() =>
        (tester.widget<DecoratedBox>(find.byType(DecoratedBox).first).decoration as ShapeDecoration).shape;

    await tester.pumpWidget(avatar(0));
    final rest = shape() as MorphBorder;
    expect(rest.morph.end, same(MaterialShapes.cookie6Sided));
    expect(rest.progress, 1);

    await tester.pumpWidget(avatar(1));
    await tester.pump(const Duration(milliseconds: 16));
    final moving = shape() as MorphBorder;
    expect(moving.morph.start, same(MaterialShapes.cookie6Sided));
    expect(moving.morph.end, same(MaterialShapes.clover4Leaf));
    expect(moving.progress, inExclusiveRange(0, 1));

    await tester.pumpAndSettle();
    expect((shape() as MorphBorder).progress, closeTo(1, 1e-3));
  });
}
