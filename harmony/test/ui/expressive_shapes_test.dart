import 'package:flutter/painting.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/ui/component/expressive/expressive_shapes.dart';
import 'package:yoin/ui/component/expressive/expressive_split_button.dart';

void main() {
  const bar = Size(double.infinity, 48);
  const centred = Size(double.infinity, 44);

  test('should_matchComposeSplitTokens_when_defaultSize', () {
    expect(M3eSplitButtonTokens.spacing, 2);
    expect(M3eSplitButtonTokens.innerCorner, 4);
    expect(M3eSplitButtonTokens.innerPressedCorner, 12);
    expect(M3eSplitButtonTokens.leadingIconSize, 20);
    expect(M3eSplitButtonTokens.trailingIconSize, 22);
    expect(M3eButtonTokens.minWidth, 58);
  });

  test('should_roundOuterAndSquareInnerCorners_when_leadingAtRest', () {
    final r = m3eSplitLeadingShapes.resolve(isPressed: false).resolve(bar);
    expect(r, const CornerRadii(topStart: 24, topEnd: 4, bottomEnd: 4, bottomStart: 24));
  });

  test('should_growInnerCornersTo12_when_leadingPressed', () {
    final r = m3eSplitLeadingShapes.resolve(isPressed: true).resolve(bar);
    expect(r, const CornerRadii(topStart: 24, topEnd: 12, bottomEnd: 12, bottomStart: 24));
  });

  test('should_keepRestingShape_when_leadingChecked', () {
    final r = m3eSplitLeadingShapes.resolve(isPressed: false, isChecked: true);
    expect(r, m3eSplitLeadingShapes.rest);
  });

  test('should_becomeCircle_when_trailingChecked', () {
    final r = m3eSplitTrailingShapes.resolve(isPressed: false, isChecked: true).resolve(centred);
    expect(r, const CornerRadii.all(22));
  });

  test('should_preferPressedOverChecked_when_trailingBoth', () {
    final r = m3eSplitTrailingShapes.resolve(isPressed: true, isChecked: true).resolve(bar);
    expect(r, const CornerRadii(topStart: 12, topEnd: 24, bottomEnd: 24, bottomStart: 12));
  });

  test('should_capFullCornersAtHalfShorterSide_when_resolved', () {
    expect(CornerRadii.full.resolve(const Size(156, 48)), const CornerRadii.all(24));
    expect(CornerRadii.full.resolve(const Size(30, 48)), const CornerRadii.all(15));
    expect(const CornerRadii.all(4).resolve(const Size(156, 48)), const CornerRadii.all(4));
  });

  test('should_shiftContentTowardSharpSide_when_trailingAtRest', () {
    final r = m3eSplitTrailingShapes.resolve(isPressed: false).resolve(bar);
    // 0.11 × (4 − 24) = −2.2: toward the start (inner) edge.
    expect(opticalCenterOffset(r, startPadding: 14, endPadding: 14), closeTo(-2.2, 1e-9));
  });

  test('should_centreContent_when_trailingCircle', () {
    final r = m3eSplitTrailingShapes.resolve(isPressed: false, isChecked: true).resolve(bar);
    expect(opticalCenterOffset(r, startPadding: 14, endPadding: 14), 0);
  });

  test('should_clampOpticalOffsetToPadding_when_paddingSmall', () {
    const r = CornerRadii.horizontal(start: 0, end: 100);
    expect(opticalCenterOffset(r, startPadding: 3, endPadding: 3), -3);
    const l = CornerRadii.horizontal(start: 100, end: 0);
    expect(opticalCenterOffset(l, startPadding: 3, endPadding: 5), 5);
  });

  test('should_pickPressedCornerBySize_when_iconButton', () {
    expect(M3eIconButtonTokens.pressedCornerFor(40), 8);
    expect(M3eIconButtonTokens.pressedCornerFor(48), 8);
    expect(M3eIconButtonTokens.pressedCornerFor(56), 12);
    final shapes = m3eIconButtonShapes(height: 56);
    expect(shapes.resolve(isPressed: true), const CornerRadii.all(12));
    expect(shapes.resolve(isPressed: false), CornerRadii.full);
  });

  test('should_lerpEachCorner_when_morphing', () {
    final mid = CornerRadii.lerp(const CornerRadii.all(4), const CornerRadii.all(12), 0.5);
    expect(mid, const CornerRadii.all(8));
  });

  test('should_flipChevronThroughFlatHinge_when_expanding', () {
    expect(expandChevronScaleY(0), 1);
    expect(expandChevronScaleY(1), -1);
    expect(expandChevronScaleY(0.25), closeTo(0.5, 1e-9));
    expect(expandChevronScaleY(0.5).abs(), closeTo(0.12, 1e-9));
    expect(expandChevronScaleY(0.49), closeTo(0.12, 1e-9));
    expect(expandChevronScaleY(0.51), closeTo(-0.12, 1e-9));
  });
}
