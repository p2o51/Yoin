import 'package:flutter/material.dart';
import 'package:flutter/physics.dart';

import '../../theme/motion.dart';

/// The shared PushPage route (AGENTS.md "Back Surface Types": PushPage).
/// Detail pages and Settings open with this and nothing else, so every
/// pushed page speaks the same back language.
///
/// Motion: shared-axis X like Android's in-window push (96 dp + fade), on the
/// expressive default spatial spring instead of a tween. Opening and closing
/// run the same spring through the route's own controller, so the HarmonyOS
/// back gesture / key (which has no progress, see harmony/README.md "Back")
/// commits through exactly the path the back button uses: Navigator.pop.
class YoinPushRoute<T> extends PageRoute<T> {
  YoinPushRoute({required this.builder, super.settings});

  final WidgetBuilder builder;

  /// Distance the pages travel along X (Android pane push, AOSP 96 dp).
  static const double travel = 96;

  @override
  bool get opaque => true;

  @override
  bool get maintainState => true;

  @override
  Color? get barrierColor => null;

  @override
  String? get barrierLabel => null;

  /// Unused while [createSimulation] drives the controller; kept positive for the framework's checks.
  @override
  Duration get transitionDuration => const Duration(milliseconds: 450);

  @override
  Simulation? createSimulation({required bool forward}) => SpringSimulation(
        YoinSprings.defaultSpatial,
        controller?.value ?? (forward ? 0 : 1),
        forward ? 1 : 0,
        controller?.velocity ?? 0,
        tolerance: const Tolerance(distance: 0.001, velocity: 0.01),
      );

  @override
  Widget buildPage(BuildContext context, Animation<double> animation, Animation<double> secondaryAnimation) =>
      Semantics(scopesRoute: true, explicitChildNodes: true, child: builder(context));

  @override
  Widget buildTransitions(
    BuildContext context,
    Animation<double> animation,
    Animation<double> secondaryAnimation,
    Widget child,
  ) {
    if (MediaQuery.disableAnimationsOf(context)) return child;
    return AnimatedBuilder(
      animation: Listenable.merge([animation, secondaryAnimation]),
      child: child,
      builder: (context, child) {
        final enter = animation.value;
        final cover = secondaryAnimation.value;
        final rtl = Directionality.of(context) == TextDirection.rtl ? -1.0 : 1.0;
        // Incoming: from +96 to 0, fading in over the first 60 %. Covered: to −96, fading out.
        final dx = rtl * (travel * (1 - enter) - travel * cover);
        final opacity = (enter / 0.6).clamp(0.0, 1.0) * (1 - (cover / 0.6).clamp(0.0, 1.0));
        return Transform.translate(
          offset: Offset(dx, 0),
          child: Opacity(opacity: opacity, child: child),
        );
      },
    );
  }
}
