import 'package:flutter/material.dart';

import '../detail/detail_page.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import 'settings_metrics.dart';

// Shared building blocks for Settings and its service setup page
// (SettingsComponents.kt), after Pixel Settings: a large page title, a quiet
// primary label over each group, each row its own segment, plain icons.

/// Settings' springs: the pages run in the Standard motion role (Android
/// wraps them in ProvideYoinMotionRole(Standard)); effects springs are shared.
abstract final class SettingsSprings {
  static final spatial = YoinSprings.of(MotionTokens.standardDefaultSpatial);
  static final slowSpatial = YoinSprings.of(MotionTokens.standardSlowSpatial);
  static final effects = YoinSprings.defaultEffects;
}

/// A lone segment, or one of a stack: outer corners 20, inner corners 4.
ShapeBorder settingsSegmentShape(int index, int count) {
  const outer = Radius.circular(SettingsMetrics.segmentOuterRadius);
  const inner = Radius.circular(SettingsMetrics.segmentInnerRadius);
  final top = index == 0 ? outer : inner;
  final bottom = index == count - 1 ? outer : inner;
  return RoundedRectangleBorder(
    borderRadius: BorderRadius.only(topLeft: top, topRight: top, bottomLeft: bottom, bottomRight: bottom),
  );
}

/// A group's label: labelLarge in primary, 8dp inside the rows' edge.
class SettingsGroupLabel extends StatelessWidget {
  const SettingsGroupLabel({super.key, required this.title});

  final String title;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: SettingsMetrics.labelInset),
      child: Semantics(
        header: true,
        child: Text(title, style: theme.textTheme.labelLarge!.copyWith(color: theme.colorScheme.primary)),
      ),
    );
  }
}

/// Pixel's sub-page header: the page title set large (displaySmall) on the
/// 24dp line the group labels share, with air above and below.
class SettingsHeadline extends StatelessWidget {
  const SettingsHeadline({super.key, required this.title});

  final String title;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(top: SettingsMetrics.headlineTop, bottom: SettingsMetrics.headlineBottom),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: SettingsMetrics.labelInset),
        child: Semantics(
          header: true,
          child: Text(
            title,
            style: theme.textTheme.displaySmall!.copyWith(color: theme.colorScheme.onSurface),
          ),
        ),
      ),
    );
  }
}

/// Settings' bar: transparent, Pixel's back (the arrow on a tonal circle, its
/// edge on the 24dp line), and the page title handed off from the large
/// title as it slides under the bar — 0 while it shows, 1 once it's gone,
/// tracking the scroll 1:1.
class SettingsBar extends StatelessWidget implements PreferredSizeWidget {
  const SettingsBar({
    super.key,
    required this.title,
    required this.controller,
    required this.titleTop,
    required this.titleHeight,
  });

  final String title;
  final ScrollController controller;

  /// Where the page's large title starts inside the scroll, and its height.
  final double titleTop;
  final double titleHeight;

  /// Where Settings' own large title starts: page top + the headline's air.
  static const double headlineOffset = SettingsMetrics.pageTop + SettingsMetrics.headlineTop;

  /// One line of [style] at the reader's text scale.
  static double lineHeight(BuildContext context, TextStyle style) =>
      MediaQuery.textScalerOf(context).scale(style.fontSize! * (style.height ?? 1));

  @override
  Size get preferredSize => const Size.fromHeight(kToolbarHeight);

  double _progress() {
    if (!controller.hasClients) return 0;
    return ((controller.offset - titleTop) / titleHeight.clamp(1.0, double.infinity)).clamp(0.0, 1.0);
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return AppBar(
      backgroundColor: Colors.transparent,
      surfaceTintColor: Colors.transparent,
      scrolledUnderElevation: 0,
      elevation: 0,
      automaticallyImplyLeading: false,
      leadingWidth: SettingsMetrics.backStart + SettingsMetrics.backTouch + SettingsMetrics.backEnd,
      titleSpacing: 0,
      leading: Padding(
        padding: const EdgeInsetsDirectional.only(start: SettingsMetrics.backStart, end: SettingsMetrics.backEnd),
        child: DetailBackButton(container: colors.surfaceContainerHighest, content: colors.onSurface),
      ),
      title: AnimatedBuilder(
        animation: controller,
        builder: (context, child) {
          final p = _progress();
          return Opacity(
            opacity: p,
            child: FractionalTranslation(translation: Offset(0, (1 - p) * 0.5), child: child),
          );
        },
        child: ExcludeSemantics(
          child: Text(
            title,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: Theme.of(context).textTheme.titleLarge!.copyWith(color: colors.onSurface),
          ),
        ),
      ),
    );
  }
}

/// Unfolds [child] downward on the spatial spring and folds it away again,
/// fading on the effects spring (Compose expandVertically + fadeIn). The last
/// child stays on screen while it folds.
class SpringReveal extends StatefulWidget {
  const SpringReveal({super.key, required this.visible, required this.child});

  final bool visible;
  final Widget? child;

  @override
  State<SpringReveal> createState() => _SpringRevealState();
}

class _SpringRevealState extends State<SpringReveal> with TickerProviderStateMixin {
  late final SpringValue _size;
  late final SpringValue _alpha;
  late Widget? _shown = widget.child;

  @override
  void initState() {
    super.initState();
    final initial = widget.visible ? 1.0 : 0.0;
    _size = SpringValue(vsync: this, initial: initial, spring: SettingsSprings.spatial);
    _alpha = SpringValue(vsync: this, initial: initial, spring: SettingsSprings.effects);
  }

  @override
  void didUpdateWidget(SpringReveal old) {
    super.didUpdateWidget(old);
    if (widget.visible && widget.child != null) _shown = widget.child;
    final target = widget.visible ? 1.0 : 0.0;
    if (MediaQuery.maybeDisableAnimationsOf(context) ?? false) {
      _size.snapTo(target);
      _alpha.snapTo(target);
    } else {
      _size.animateTo(target);
      _alpha.animateTo(target);
    }
  }

  @override
  void dispose() {
    _size.dispose();
    _alpha.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final shown = _shown;
    if (shown == null) return const SizedBox.shrink();
    return AnimatedBuilder(
      animation: Listenable.merge([_size.animation, _alpha.animation]),
      builder: (context, child) {
        final size = _size.value.clamp(0.0, 1.0);
        if (size <= 0.001 && !widget.visible) return const SizedBox.shrink();
        return ClipRect(
          child: Align(
            alignment: AlignmentDirectional.topStart,
            heightFactor: size,
            child: Opacity(opacity: _alpha.value.clamp(0.0, 1.0), child: child),
          ),
        );
      },
      child: shown,
    );
  }
}
