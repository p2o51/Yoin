/// The system's reduce-motion settings as the app's `disableAnimations`.
///
/// Every reduced path in Yoin reads `MediaQuery.disableAnimationsOf` (Android's
/// MotionProfile.AdaptiveReduced: fade in place instead of moving). The
/// flutter_ohos embedder never sets that flag, so the app root folds in what
/// [systemMotionProvider] reads (HarmonyOS 减弱动画, or the animator duration
/// scale at 0) — live, so turning it on or off while Yoin runs takes effect at
/// once (Android origin/main 7b8a470f8). A flag the embedder does report
/// (Windows, tests) still counts.
library;

import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../platform/motion/system_motion.dart';

/// Below this widget `MediaQuery.disableAnimationsOf` is also true while the
/// system asks for less motion.
class SystemReduceMotionScope extends ConsumerWidget {
  const SystemReduceMotionScope({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final reduces = ref.watch(systemMotionProvider.select((s) => s.value?.reducesMotion ?? false));
    return ReduceMotionOverride(reduce: reduces, child: child);
  }
}

/// [child] with `disableAnimations` raised while [reduce] holds (never lowered:
/// what the platform already reports stays).
class ReduceMotionOverride extends StatelessWidget {
  const ReduceMotionOverride({super.key, required this.reduce, required this.child});

  final bool reduce;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final data = MediaQuery.of(context);
    // Always a MediaQuery, so the app below keeps its place in the tree when the setting flips.
    return MediaQuery(
      data: reduce && !data.disableAnimations ? data.copyWith(disableAnimations: true) : data,
      child: child,
    );
  }
}
