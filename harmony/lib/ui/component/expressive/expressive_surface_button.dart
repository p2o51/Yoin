import 'package:flutter/material.dart';

import '../../../theme/motion.dart';
import '../desktop_input.dart';
import 'expressive_shapes.dart';

/// An M3 Expressive button surface whose corners spring between its
/// [InteractionShapes] — rest, pressed, checked — the way Compose's
/// SplitButton / IconButton `shapes` overloads do (rememberAnimatedShape on
/// the motion scheme's DefaultEffects spring, each corner animated in dp from
/// where it is). With [InteractionShapes.fixed] it is a plain filled button.
///
/// The surface is [height] tall and at least [minWidth] wide; full (50 %)
/// corners resolve against [height], so the button must be at least as wide
/// as it is tall (true of every M3 button). Ripple and press come from one
/// InkWell clipped to the animated outline. [opticalCentering] shifts the
/// content as Compose's horizontalCenterOptically (the split trailing
/// button); [checkedStateLayer] lays the content colour at 10 % over a
/// checked button (SplitButtonDefaults.TrailingButton).
class ExpressiveSurfaceButton extends StatefulWidget {
  const ExpressiveSurfaceButton({
    super.key,
    required this.shapes,
    required this.height,
    required this.color,
    required this.contentColor,
    required this.child,
    this.onTap,
    this.onLongPress,
    this.onPressChanged,
    this.checked = false,
    this.padding = EdgeInsetsDirectional.zero,
    this.minWidth = 0,
    this.opticalCentering = false,
    this.checkedStateLayer = false,
    this.ripple = true,
    this.semanticLabel,
    this.semanticValue,
  });

  final InteractionShapes shapes;
  final double height;
  final Color color;
  final Color contentColor;
  final Widget child;
  final VoidCallback? onTap;
  final VoidCallback? onLongPress;

  /// Reports the press (for width echoes and hoisted press state).
  final ValueChanged<bool>? onPressChanged;
  final bool checked;
  final EdgeInsetsDirectional padding;
  final double minWidth;
  final bool opticalCentering;
  final bool checkedStateLayer;

  /// False for a press without a ripple (the bar's Library button).
  final bool ripple;
  final String? semanticLabel;
  final String? semanticValue;

  @override
  State<ExpressiveSurfaceButton> createState() => _ExpressiveSurfaceButtonState();
}

class _ExpressiveSurfaceButtonState extends State<ExpressiveSurfaceButton> with TickerProviderStateMixin {
  bool _pressed = false;
  late final List<SpringValue> _corners;

  CornerRadii get _target => widget.shapes
      .resolve(isPressed: _pressed, isChecked: widget.checked)
      .resolve(Size(double.infinity, widget.height));

  @override
  void initState() {
    super.initState();
    final start = _target.values;
    _corners = [for (final v in start) SpringValue(vsync: this, initial: v, spring: YoinSprings.defaultEffects)];
  }

  @override
  void dispose() {
    for (final c in _corners) {
      c.dispose();
    }
    super.dispose();
  }

  void _setPressed(bool down) {
    if (_pressed == down) return;
    setState(() => _pressed = down);
    widget.onPressChanged?.call(down);
  }

  void _retarget(bool reduce) {
    final target = _target.values;
    for (var i = 0; i < 4; i++) {
      reduce ? _corners[i].snapTo(target[i]) : _corners[i].animateTo(target[i]);
    }
  }

  @override
  Widget build(BuildContext context) {
    _retarget(MediaQuery.disableAnimationsOf(context));
    final rtl = Directionality.of(context) == TextDirection.rtl;
    final padding = widget.padding;
    final interactive = widget.onTap != null || widget.onLongPress != null;

    final surface = AnimatedBuilder(
      animation: Listenable.merge([for (final c in _corners) c.animation]),
      builder: (context, child) {
        final radii = CornerRadii(
          topStart: _corners[0].value,
          topEnd: _corners[1].value,
          bottomEnd: _corners[2].value,
          bottomStart: _corners[3].value,
        ).resolve(Size(double.infinity, widget.height));
        final shape = RoundedRectangleBorder(borderRadius: radii.toBorderRadius());
        final dx = widget.opticalCentering
            ? opticalCenterOffset(radii, startPadding: padding.start, endPadding: padding.end)
            : 0.0;
        // Duration.zero: Material otherwise tweens every new shape over
        // 200 ms (ShapeBorderTween on fastOutSlowIn), restarting each spring
        // tick, so the drawn outline would trail the spring and disagree with
        // the InkWell's customBorder. The spring is the only animator.
        return Material(
          animationDuration: Duration.zero,
          color: widget.color,
          shape: shape,
          clipBehavior: Clip.antiAlias,
          child: InkWell(
            customBorder: shape,
            onTap: widget.onTap,
            onLongPress: widget.onLongPress,
            onSecondaryTap: widget.onLongPress,
            onHighlightChanged: interactive ? _setPressed : null,
            splashFactory: widget.ripple ? null : NoSplash.splashFactory,
            highlightColor: widget.ripple ? null : Colors.transparent,
            splashColor: widget.ripple ? widget.contentColor.withValues(alpha: 0.1) : Colors.transparent,
            hoverColor: widget.contentColor.withValues(alpha: 0.08),
            child: Stack(
              fit: StackFit.passthrough,
              children: [
                Transform.translate(offset: Offset(rtl ? -dx : dx, 0), child: child),
                if (widget.checkedStateLayer && widget.checked)
                  Positioned.fill(
                    child: IgnorePointer(
                      child: ColoredBox(
                        color: widget.contentColor.withValues(alpha: M3eSplitButtonTokens.checkedStateLayerAlpha),
                      ),
                    ),
                  ),
              ],
            ),
          ),
        );
      },
      child: ConstrainedBox(
        constraints: BoxConstraints(minWidth: widget.minWidth, minHeight: widget.height, maxHeight: widget.height),
        child: Padding(
          padding: padding,
          child: IconTheme.merge(
            data: IconThemeData(color: widget.contentColor),
            child: DefaultTextStyle.merge(
              style: TextStyle(color: widget.contentColor),
              child: Center(widthFactor: 1, child: widget.child),
            ),
          ),
        ),
      ),
    );

    return Semantics(
      button: true,
      toggled: widget.checkedStateLayer ? widget.checked : null,
      label: widget.semanticLabel,
      value: widget.semanticValue,
      excludeSemantics: widget.semanticLabel != null,
      // Keyboard traversal on a PC window: a visible ring (the cover-tinted
      // pages wash out InkWell's own focus layer).
      child: YoinFocusRing(child: SizedBox(height: widget.height, child: surface)),
    );
  }
}
