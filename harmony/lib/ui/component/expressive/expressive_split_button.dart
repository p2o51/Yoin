import 'package:flutter/material.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../../../theme/motion.dart';
import 'expressive_shapes.dart';
import 'expressive_surface_button.dart';

/// Compose's M3 split button as Yoin composes it (PlaySplitButton.kt): the
/// real `SplitButtonDefaults.LeadingButton` + `TrailingButton(checked)` side
/// by side in a plain row, [M3eSplitButtonTokens.spacing] apart, both
/// [height] tall in one colour pair (ButtonDefaults.buttonColors).
///
/// - Leading: full outer corners, 4 dp inner corners that press to 12 dp;
///   with [fillLeading] it takes the row's slack (`weight(1f)`).
/// - Trailing: 4 → 12 dp inner corners on press, a full circle while
///   [trailingChecked] (its menu is open), with the 10 % checked state layer
///   and optically centred content.
/// Shape morphs ride the DefaultEffects spring (see [ExpressiveSurfaceButton]).
/// Without [onTrailingTap] there is no trailing half and the leading button
/// is a full stadium.
class ExpressiveSplitButton extends StatelessWidget {
  const ExpressiveSplitButton({
    super.key,
    required this.height,
    required this.containerColor,
    required this.contentColor,
    required this.leading,
    required this.onLeadingTap,
    this.leadingPadding = 16,
    this.trailing,
    this.onTrailingTap,
    this.trailingChecked = false,
    this.trailingPadding = 14,
    this.trailingSemanticLabel,
    this.fillLeading = true,
  });

  final double height;
  final Color containerColor;
  final Color contentColor;
  final Widget leading;
  final VoidCallback onLeadingTap;

  /// Horizontal content padding of each half (Yoin's compact bar: 16 / 14).
  final double leadingPadding;
  final Widget? trailing;
  final VoidCallback? onTrailingTap;
  final bool trailingChecked;
  final double trailingPadding;
  final String? trailingSemanticLabel;
  final bool fillLeading;

  @override
  Widget build(BuildContext context) {
    final hasTrailing = onTrailingTap != null && trailing != null;
    final leadingButton = ExpressiveSurfaceButton(
      shapes: hasTrailing ? m3eSplitLeadingShapes : const InteractionShapes.fixed(CornerRadii.full),
      height: height,
      color: containerColor,
      contentColor: contentColor,
      minWidth: M3eSplitButtonTokens.leadingMinWidth,
      padding: EdgeInsetsDirectional.symmetric(horizontal: leadingPadding),
      onTap: onLeadingTap,
      child: leading,
    );
    return Row(
      mainAxisSize: fillLeading ? MainAxisSize.max : MainAxisSize.min,
      children: [
        if (fillLeading) Expanded(child: leadingButton) else leadingButton,
        if (hasTrailing) ...[
          const SizedBox(width: M3eSplitButtonTokens.spacing),
          ExpressiveSurfaceButton(
            shapes: m3eSplitTrailingShapes,
            height: height,
            color: containerColor,
            contentColor: contentColor,
            minWidth: M3eSplitButtonTokens.trailingMinWidth,
            padding: EdgeInsetsDirectional.symmetric(horizontal: trailingPadding),
            checked: trailingChecked,
            checkedStateLayer: true,
            opticalCentering: true,
            onTap: onTrailingTap,
            semanticLabel: trailingSemanticLabel,
            child: trailing!,
          ),
        ],
      ],
    );
  }
}

/// The ▾ that folds over like a hinge while [expanded] (Yoin Symbols'
/// rememberExpandSymbolPainter): its points travel straight from
/// chevron-down to chevron-up, which is a vertical flip of the glyph about
/// its centre line. Driven by the symbol spatial spring (0.8 / 380). The
/// glyph never collapses below [_minFold] of its height, so its stroke stays
/// visible at the hinge's midpoint as the stroked Android path does.
class ExpandChevron extends StatefulWidget {
  const ExpandChevron({
    super.key,
    required this.expanded,
    this.size = M3eSplitButtonTokens.trailingIconSize,
    this.color,
  });

  final bool expanded;
  final double size;
  final Color? color;

  @override
  State<ExpandChevron> createState() => _ExpandChevronState();
}

const double _minFold = 0.12;

/// The ▾ hinge's vertical scale at progress [t] (0 = down, 1 = up).
double expandChevronScaleY(double t) {
  final s = 1 - 2 * t;
  if (s.abs() >= _minFold) return s;
  return t < 0.5 ? _minFold : -_minFold;
}

class _ExpandChevronState extends State<ExpandChevron> with TickerProviderStateMixin {
  late final SpringValue _t = SpringValue(
    vsync: this,
    initial: widget.expanded ? 1 : 0,
    spring: YoinSprings.defaultSpatial,
  );

  @override
  void dispose() {
    _t.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final target = widget.expanded ? 1.0 : 0.0;
    MediaQuery.disableAnimationsOf(context) ? _t.snapTo(target) : _t.animateTo(target);
    return AnimatedBuilder(
      animation: _t.animation,
      builder: (context, child) => Transform(
        alignment: Alignment.center,
        transform: Matrix4.diagonal3Values(1, expandChevronScaleY(_t.value), 1),
        child: child,
      ),
      child: Icon(YoinSymbols.chevronDown, size: widget.size, color: widget.color),
    );
  }
}
