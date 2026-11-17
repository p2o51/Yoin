import 'dart:math' as math;
import 'dart:ui' show lerpDouble;

import 'package:flutter/foundation.dart';
import 'package:flutter/painting.dart';

/// M3 Expressive shape and size tokens, ported from androidx.compose.material3
/// 1.5.0-alpha27 (the version Android Yoin ships): `tokens/ShapeTokens`,
/// `tokens/SplitButtonSmallTokens`, `tokens/SmallIconButtonTokens`,
/// `tokens/MediumIconButtonTokens`, `SplitButtonDefaults`,
/// `HorizontalCenterOptically`. Read from the compiled classes, not guessed.
abstract final class M3eShapeTokens {
  /// ShapeTokens.CornerValue* (dp).
  static const double cornerExtraSmall = 4;
  static const double cornerSmall = 8;
  static const double cornerMedium = 12;
  static const double cornerLarge = 16;
  static const double cornerExtraLarge = 28;

  /// ShapeKeyTokens.CornerFull (50 % of the shorter side): a stadium end.
  static const double cornerFull = double.infinity;
}

/// ButtonDefaults: every M3 Button's default minimum width (its intrinsic
/// width never drops below it) and the icon → label gap.
abstract final class M3eButtonTokens {
  static const double minWidth = 58;
  static const double iconSpacing = 8;
}

/// SplitButtonDefaults at its default size (SplitButtonSmallTokens): what
/// `SplitButtonDefaults.LeadingButton` / `TrailingButton` use when no
/// `shapes` / `contentPadding` are passed, as Yoin's PlaySplitButton does.
abstract final class M3eSplitButtonTokens {
  /// SplitButtonDefaults.Spacing (BetweenSpace).
  static const double spacing = 2;

  /// InnerCornerCornerSize = CornerValueExtraSmall.
  static const double innerCorner = M3eShapeTokens.cornerExtraSmall;

  /// InnerPressedCornerCornerSize = CornerValueMedium.
  static const double innerPressedCorner = M3eShapeTokens.cornerMedium;

  /// LeadingIconSize = ButtonSmallTokens.IconSize; TrailingIconSize.
  static const double leadingIconSize = 20;
  static const double trailingIconSize = 22;

  /// LeadingButtonMinWidth / TrailingButtonMinWidth.
  static const double leadingMinWidth = 48;
  static const double trailingMinWidth = 48;

  /// The checked trailing button's state layer (drawWithContent, 0.1 of the content colour).
  static const double checkedStateLayerAlpha = 0.1;
}

/// IconButtonDefaults.shapes(): the pressed corner by button size
/// (SmallIconButtonTokens.PressedContainerShape = CornerSmall at 40 dp,
/// MediumIconButtonTokens = CornerMedium at 56 dp).
abstract final class M3eIconButtonTokens {
  static const double smallPressedCorner = M3eShapeTokens.cornerSmall;
  static const double mediumPressedCorner = M3eShapeTokens.cornerMedium;

  /// The pressed corner for a button [height] tall (≤ 48 small, else medium).
  static double pressedCornerFor(double height) => height <= 48 ? smallPressedCorner : mediumPressedCorner;
}

/// HorizontalCenterOptically's coefficient.
const double m3eOpticalCenteringCoefficient = 0.11;

/// A rounded rectangle's four corner radii in logical (start / end) terms,
/// like Compose's RoundedCornerShape. [M3eShapeTokens.cornerFull] (infinity)
/// means "50 %": it resolves to half the shorter side.
@immutable
final class CornerRadii {
  const CornerRadii({required this.topStart, required this.topEnd, required this.bottomEnd, required this.bottomStart});

  const CornerRadii.all(double r) : topStart = r, topEnd = r, bottomEnd = r, bottomStart = r;

  /// Start corners [start], end corners [end].
  const CornerRadii.horizontal({required double start, required double end})
    : topStart = start,
      bottomStart = start,
      topEnd = end,
      bottomEnd = end;

  static const CornerRadii full = CornerRadii.all(M3eShapeTokens.cornerFull);

  final double topStart;
  final double topEnd;
  final double bottomEnd;
  final double bottomStart;

  List<double> get values => [topStart, topEnd, bottomEnd, bottomStart];

  /// Each corner clamped to half the shorter side of [size] (as a
  /// CornerBasedShape does when it builds its outline).
  CornerRadii resolve(Size size) {
    final cap = math.max(0.0, math.min(size.width, size.height) / 2);
    double r(double v) => v.clamp(0.0, cap);
    return CornerRadii(topStart: r(topStart), topEnd: r(topEnd), bottomEnd: r(bottomEnd), bottomStart: r(bottomStart));
  }

  double get averageStart => (topStart + bottomStart) / 2;
  double get averageEnd => (topEnd + bottomEnd) / 2;

  static CornerRadii lerp(CornerRadii a, CornerRadii b, double t) => CornerRadii(
    topStart: lerpDouble(a.topStart, b.topStart, t)!,
    topEnd: lerpDouble(a.topEnd, b.topEnd, t)!,
    bottomEnd: lerpDouble(a.bottomEnd, b.bottomEnd, t)!,
    bottomStart: lerpDouble(a.bottomStart, b.bottomStart, t)!,
  );

  BorderRadiusDirectional toBorderRadius() => BorderRadiusDirectional.only(
    topStart: Radius.circular(topStart),
    topEnd: Radius.circular(topEnd),
    bottomEnd: Radius.circular(bottomEnd),
    bottomStart: Radius.circular(bottomStart),
  );

  @override
  bool operator ==(Object other) =>
      other is CornerRadii &&
      other.topStart == topStart &&
      other.topEnd == topEnd &&
      other.bottomEnd == bottomEnd &&
      other.bottomStart == bottomStart;

  @override
  int get hashCode => Object.hash(topStart, topEnd, bottomEnd, bottomStart);

  @override
  String toString() => 'CornerRadii($topStart, $topEnd, $bottomEnd, $bottomStart)';
}

/// A button's shapes by interaction (SplitButtonShapes / IconButtonShapes):
/// [pressed] and [checked] fall back to [rest] when null.
@immutable
final class InteractionShapes {
  const InteractionShapes({required this.rest, this.pressed, this.checked});

  /// One shape whatever happens (no morph).
  const InteractionShapes.fixed(this.rest) : pressed = null, checked = null;

  final CornerRadii rest;
  final CornerRadii? pressed;
  final CornerRadii? checked;

  /// SplitButtonKt.shapeByInteraction / IconButtonKt.shapeForInteraction:
  /// pressed wins over checked, either falls back to the resting shape.
  CornerRadii resolve({required bool isPressed, bool isChecked = false}) {
    if (isPressed) return pressed ?? rest;
    if (isChecked) return checked ?? rest;
    return rest;
  }
}

/// SplitButtonDefaults.leadingButtonShapes at the default size: full outer
/// (start) corners, 4 dp inner (end) corners that press to 12 dp. No checked shape.
const InteractionShapes m3eSplitLeadingShapes = InteractionShapes(
  rest: CornerRadii.horizontal(start: M3eShapeTokens.cornerFull, end: M3eSplitButtonTokens.innerCorner),
  pressed: CornerRadii.horizontal(start: M3eShapeTokens.cornerFull, end: M3eSplitButtonTokens.innerPressedCorner),
);

/// SplitButtonDefaults.trailingButtonShapes at the default size: 4 dp inner
/// (start) corners pressing to 12 dp, full outer corners; checked (menu
/// open) it is a CircleShape — every corner 50 %.
const InteractionShapes m3eSplitTrailingShapes = InteractionShapes(
  rest: CornerRadii.horizontal(start: M3eSplitButtonTokens.innerCorner, end: M3eShapeTokens.cornerFull),
  pressed: CornerRadii.horizontal(start: M3eSplitButtonTokens.innerPressedCorner, end: M3eShapeTokens.cornerFull),
  checked: CornerRadii.full,
);

/// IconButtonDefaults.shapes(shape): [rest] at rest, the size's pressed corner while held.
InteractionShapes m3eIconButtonShapes({required double height, CornerRadii rest = CornerRadii.full}) =>
    InteractionShapes(rest: rest, pressed: CornerRadii.all(M3eIconButtonTokens.pressedCornerFor(height)));

/// HorizontalCenterOptically: content shifts 0.11 × (mean start corner −
/// mean end corner) toward the end, clamped to [−startPadding, endPadding].
/// [radii] are resolved (px / dp) corners of the current, animated outline.
double opticalCenterOffset(CornerRadii radii, {required double startPadding, required double endPadding}) {
  final offset = m3eOpticalCenteringCoefficient * (radii.averageStart - radii.averageEnd);
  return offset.clamp(-startPadding, endPadding);
}
