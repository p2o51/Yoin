import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import '../../theme/motion.dart';
import '../../theme/tokens.dart';
import '../../ui/component/desktop_input.dart';
import '../home_metrics.dart';

/// elasticPress (PressFeedback.kt): the content scales to 0.97 while pressed
/// and lifts to 1.01 while a mouse / trackpad / stylus hovers (press wins),
/// both on the Standard default spatial spring. No ripple. Descendants read
/// the pressed state through [PressedScope] (the backdrop morph). Reduced
/// motion: no scale; the pressed state still reaches descendants.
class ElasticPress extends StatefulWidget {
  const ElasticPress({super.key, required this.onTap, required this.child, this.semanticLabel});

  final VoidCallback? onTap;
  final Widget child;
  final String? semanticLabel;

  @override
  State<ElasticPress> createState() => _ElasticPressState();
}

class _ElasticPressState extends State<ElasticPress> with SingleTickerProviderStateMixin {
  late final SpringValue _scale = SpringValue(vsync: this, initial: 1, spring: YoinSprings.standardDefaultSpatial);

  @override
  void initState() {
    super.initState();
    // Create eagerly: a lazy field first read in dispose() would build a ticker on a dead element.
    _scale;
  }
  final ValueNotifier<bool> _pressed = ValueNotifier(false);
  bool _hovered = false;

  void _update() {
    final reduced = MediaQuery.disableAnimationsOf(context);
    final target = reduced
        ? 1.0
        : _pressed.value
            ? HomePressMetrics.pressedScale
            : (_hovered ? HomePressMetrics.hoverScale : 1.0);
    _scale.animateTo(target);
  }

  void _setPressed(bool value) {
    if (_pressed.value == value) return;
    _pressed.value = value;
    _update();
  }

  @override
  void dispose() {
    _scale.dispose();
    _pressed.dispose();
    super.dispose();
  }

  void _setHovered(bool value) {
    _hovered = value;
    _update();
  }

  @override
  Widget build(BuildContext context) => Semantics(
        button: widget.onTap != null,
        label: widget.semanticLabel,
        // Mouse / trackpad: the click cursor, the hover state layer (and the
        // lift above), the keyboard focus ring and Enter.
        child: YoinHover(
          onTap: widget.onTap,
          onHoverChanged: _setHovered,
          focusRadius: BorderRadius.circular(ShapeTokens.card),
          child: GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTapDown: widget.onTap == null ? null : (_) => _setPressed(true),
            onTapUp: widget.onTap == null ? null : (_) => _setPressed(false),
            onTapCancel: () => _setPressed(false),
            onTap: widget.onTap,
            child: PressedScope(
              pressed: _pressed,
              child: ScaleTransition(scale: _scale.animation, child: widget.child),
            ),
          ),
        ),
      );
}

/// The nearest [ElasticPress]'s pressed state.
class PressedScope extends InheritedNotifier<ValueNotifier<bool>> {
  const PressedScope({super.key, required ValueNotifier<bool> pressed, required super.child}) : super(notifier: pressed);

  /// Listenable pressed state; never rebuilds the caller (listen to it instead).
  static ValueListenable<bool>? maybeOf(BuildContext context) =>
      context.getInheritedWidgetOfExactType<PressedScope>()?.notifier;
}
