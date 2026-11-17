import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/ui/component/expressive/expressive_split_button.dart';

Widget _split({bool checked = false, VoidCallback? onTrailing, double height = 48}) => MaterialApp(
  home: Scaffold(
    body: Center(
      child: SizedBox(
        width: 156,
        // A taller slot: the halves must still be exactly [height] (v1 squashed them to the text).
        height: 80,
        child: ExpressiveSplitButton(
          height: height,
          containerColor: Colors.pink,
          contentColor: Colors.black,
          onLeadingTap: () {},
          leading: const Text('Play'),
          onTrailingTap: onTrailing ?? () {},
          trailingChecked: checked,
          trailingSemanticLabel: 'More',
          trailing: ExpandChevron(expanded: checked),
        ),
      ),
    ),
  ),
);

/// The outline actually painted and clipped: the PhysicalShape Material
/// builds, not the shape handed to Material (which it could tween behind).
BorderRadiusDirectional _radius(WidgetTester tester, Finder material) {
  final physical = tester.widget<PhysicalShape>(find.descendant(of: material, matching: find.byType(PhysicalShape)).first);
  final shape = (physical.clipper as ShapeBorderClipper).shape as RoundedRectangleBorder;
  return shape.borderRadius as BorderRadiusDirectional;
}

BorderRadiusDirectional _input(WidgetTester tester, Finder material) =>
    (tester.widget<Material>(material).shape! as RoundedRectangleBorder).borderRadius as BorderRadiusDirectional;

Finder get _materials => find.descendant(of: find.byType(ExpressiveSplitButton), matching: find.byType(Material));

void main() {
  testWidgets('should_fillButtonHeight_when_slotIsTaller', (tester) async {
    await tester.pumpWidget(_split());
    final leading = tester.getSize(_materials.at(0));
    final trailing = tester.getSize(_materials.at(1));
    expect(leading.height, 48);
    expect(trailing.height, 48);
    // ▾ = 14 + 22 + 14; Play takes the rest of 156 after the 2 dp gap.
    expect(trailing.width, 50);
    expect(leading.width, 156 - 2 - 50);
  });

  testWidgets('should_use44_when_centredBar', (tester) async {
    await tester.pumpWidget(_split(height: 44));
    expect(tester.getSize(_materials.at(0)).height, 44);
    expect(tester.getSize(_materials.at(1)).height, 44);
  });

  testWidgets('should_restAtFourDpInnerCorners_when_idle', (tester) async {
    await tester.pumpWidget(_split());
    final l = _radius(tester, _materials.at(0));
    expect(l.topStart.x, 24);
    expect(l.topEnd.x, 4);
    final t = _radius(tester, _materials.at(1));
    expect(t.topStart.x, 4);
    expect(t.bottomEnd.x, 24);
  });

  testWidgets('should_springInnerCornersTo12_when_leadingHeld', (tester) async {
    await tester.pumpWidget(_split());
    final gesture = await tester.startGesture(tester.getCenter(_materials.at(0)));
    await tester.pumpAndSettle();
    expect(_radius(tester, _materials.at(0)).topEnd.x, closeTo(12, 0.01));
    await gesture.up();
    await tester.pumpAndSettle();
    expect(_radius(tester, _materials.at(0)).topEnd.x, closeTo(4, 0.01));
  });

  testWidgets('should_becomeCircle_when_trailingChecked', (tester) async {
    await tester.pumpWidget(_split());
    await tester.pumpWidget(_split(checked: true));
    await tester.pump(const Duration(milliseconds: 16));
    // Mid-spring: on its way from 4 dp, not snapped.
    expect(_radius(tester, _materials.at(1)).topStart.x, inExclusiveRange(4, 24));
    // The drawn outline is the spring itself, with no Material tween lagging behind it.
    for (var frame = 0; frame < 6; frame++) {
      expect(_radius(tester, _materials.at(1)).topStart.x, closeTo(_input(tester, _materials.at(1)).topStart.x, 1e-9));
      await tester.pump(const Duration(milliseconds: 16));
    }
    await tester.pumpAndSettle();
    final t = _radius(tester, _materials.at(1));
    expect(t.topStart.x, closeTo(24, 0.01));
    expect(t.bottomEnd.x, closeTo(24, 0.01));
  });

  testWidgets('should_dropTrailingHalf_when_noMenu', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Center(
          child: SizedBox(
            width: 156,
            child: ExpressiveSplitButton(
              height: 48,
              containerColor: Colors.pink,
              contentColor: Colors.black,
              onLeadingTap: () {},
              leading: const Text('Play'),
            ),
          ),
        ),
      ),
    );
    expect(_materials, findsOneWidget);
    final r = _radius(tester, _materials.at(0));
    expect(r.topEnd.x, 24);
    expect(tester.getSize(_materials.at(0)).width, 156);
  });
}
