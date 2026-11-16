import 'dart:async';

import 'package:flutter/widgets.dart';

import '../../nowplaying/haptics.dart';
import '../../theme/motion.dart';
import '../../theme/tokens.dart';

/// "Score bloom" (Android component/RatingBloom.kt): a celebratory pop
/// ([RatingBloomTokens.fromScale] → overshoot → 1 on the slow expressive
/// spatial spring, rising a little while small) plus a confirm haptic, when
/// a rating COMMIT lands downstream on a badge and changes [trigger].
///
/// The bloom only arms once the badge has been built and quiet for [settle]:
/// the page-open data resolve (the score arrives async, a few hundred ms in)
/// finishes inside that window, so opening an already-rated page never
/// blooms — a commit seconds later does. Pass [Duration.zero] for a
/// commit-point display, which must pop on the very first commit.
///
/// [trigger] compares by value (a record of the score's kind and number).
/// Reduced motion keeps the haptic and leaves the badge still.
class RatingBloom extends StatefulWidget {
  const RatingBloom({super.key, required this.trigger, this.settle = RatingBloomTokens.settle, required this.child});

  final Object? trigger;
  final Duration settle;
  final Widget child;

  @override
  State<RatingBloom> createState() => _RatingBloomState();
}

class _RatingBloomState extends State<RatingBloom> with SingleTickerProviderStateMixin {
  // Slow spatial spring: the fast one settles in ~100 ms and the pop reads as a flicker, not a bloom.
  late final SpringValue _scale = SpringValue(vsync: this, initial: 1, spring: YoinSprings.slowSpatial);
  Timer? _settle;
  bool _settled = false;
  Object? _last;

  @override
  void initState() {
    super.initState();
    _last = widget.trigger;
    _arm();
  }

  /// A change inside the settle window restarts it (Android's LaunchedEffect(trigger) relaunch).
  void _arm() {
    _settle?.cancel();
    if (widget.settle <= Duration.zero) {
      _settled = true;
      return;
    }
    _settle = Timer(widget.settle, () {
      _settled = true;
      _last = widget.trigger;
    });
  }

  @override
  void didUpdateWidget(RatingBloom oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.trigger == oldWidget.trigger) return;
    if (!_settled) {
      _arm();
      return;
    }
    if (widget.trigger == _last) return;
    _last = widget.trigger;
    YoinHaptics.confirm();
    if (MediaQuery.disableAnimationsOf(context)) return;
    _scale.snapTo(RatingBloomTokens.fromScale);
    _scale.animateTo(1);
  }

  @override
  void dispose() {
    _settle?.cancel();
    _scale.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: _scale.animation,
    child: widget.child,
    builder: (context, child) {
      final s = _scale.value;
      return Transform.translate(
        offset: Offset(0, -(1 - s) * RatingBloomTokens.lift),
        child: Transform.scale(scale: s, child: child),
      );
    },
  );
}
