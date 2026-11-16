import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../detail/detail_page.dart';
import '../home/home_metrics.dart' show HomeSeamMetrics;
import '../home/widgets/seam.dart';
import '../theme/motion.dart';
import '../theme/system_bars.dart';
import '../theme/tokens.dart';
import 'settings_colors.dart';
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
        child: SeamFade(
          fontSize: theme.textTheme.labelLarge!.fontSize ?? 14,
          child: Text(title, style: theme.textTheme.labelLarge!.copyWith(color: theme.colorScheme.primary)),
        ),
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
          // Display type fades over 0.75 × its size, so it fades instead of looking sliced.
          child: SeamFade(
            fontSize: theme.textTheme.displaySmall!.fontSize ?? 36,
            child: Text(title, style: theme.textTheme.displaySmall!.copyWith(color: theme.colorScheme.onSurface)),
          ),
        ),
      ),
    );
  }
}

/// The seam under Settings' bar (Android seamDissolveViewport(top =
/// SeamTop.Chrome) in its default tide-line style; memory "Seam dissolve"):
/// scrolled content goes under a two-wave water line just below the bar —
/// the back wave half clear — instead of being cut straight under the back
/// button, and text below the line fades out over max(10dp, 0.75 × its size)
/// on the 1 − (1 − t)² curve ([SeamFade] on the rows' text: the mask lives
/// in the item). At rest nothing is cut; the line comes down from above the
/// edge over the first [HomeSeamMetrics.revealDistance] of scroll. The
/// waves' phase follows the scroll and its afterglow, their height the
/// scroll speed (one [SeamFlow], as Home's status-bar tide); at rest the line
/// holds still.
///
/// Android cuts the content with the waves as a mask; Settings' page is one
/// flat colour (the Scaffold's), so the waves are painted in it over the
/// content — the same pixels, without a layer that would have to hold the
/// scrolling list.
class SettingsSeam extends StatefulWidget {
  const SettingsSeam({super.key, required this.controller, required this.child});

  final ScrollController controller;
  final Widget child;

  /// 0 at rest .. 1 once scrolled the reveal distance.
  static double reveal(double offset) => seamReveal(offset);

  @override
  State<SettingsSeam> createState() => _SettingsSeamState();
}

class _SettingsSeamState extends State<SettingsSeam> with SingleTickerProviderStateMixin {
  late final SeamFlow _flow = SeamFlow(vsync: this);
  final _box = GlobalKey();
  double _top = 0;

  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_onScroll);
    WidgetsBinding.instance.addPostFrameCallback((_) => _measure());
  }

  @override
  void didUpdateWidget(SettingsSeam oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != widget.controller) {
      oldWidget.controller.removeListener(_onScroll);
      widget.controller.addListener(_onScroll);
    }
    WidgetsBinding.instance.addPostFrameCallback((_) => _measure());
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _flow.reduced = MediaQuery.disableAnimationsOf(context);
  }

  @override
  void dispose() {
    widget.controller.removeListener(_onScroll);
    _flow.dispose();
    super.dispose();
  }

  // A push transition can briefly attach the page twice: read the last.
  void _onScroll() {
    if (widget.controller.hasClients) _flow.onScroll(widget.controller.positions.last.pixels);
  }

  /// Where the viewport starts on screen, for the text under the line ([SeamScope.lineY]).
  void _measure() {
    final box = _box.currentContext?.findRenderObject();
    if (!mounted || box is! RenderBox || !box.hasSize) return;
    final top = box.localToGlobal(Offset.zero).dy;
    if ((top - _top).abs() >= 0.5) setState(() => _top = top);
  }

  @override
  Widget build(BuildContext context) {
    final page = Scaffold.maybeOf(context)?.widget.backgroundColor ?? settingsPageColor(Theme.of(context).colorScheme);
    return SeamScope(
      flow: _flow,
      // Text fades below the line's rest: a crest under the edge (tideTextSeamPx at full reveal).
      lineY: _top + settingsTideTextSeam(1),
      child: CustomPaint(
        key: _box,
        foregroundPainter: SettingsTidePainter(flow: _flow, color: page),
        child: widget.child,
      ),
    );
  }
}

/// The front line's rest under fixed chrome: a crest below the edge, so no
/// crest is cut straight by it (SeamViewportNode's restPx with inset 0).
double settingsTideRest(double amplitude) => HomeSeamMetrics.tideRest + amplitude * (1 + HomeSeamMetrics.tideHarmonic);

/// How far the back wave reaches below the front line's base (tideDepthPx).
double settingsTideDepth(double amplitude) =>
    HomeSeamMetrics.tideBackDrop + amplitude * HomeSeamMetrics.tideBackAmplitude * (1 + HomeSeamMetrics.tideHarmonic);

/// The front line's base at [reveal]: its rest once revealed, high enough above the edge before
/// that not even the back wave reaches in (reveal 0 = nothing covered; the first pixel adds none).
double settingsTideBase(double reveal, double amplitude) {
  final hidden = math.max(HomeSeamMetrics.tideHidden, settingsTideDepth(amplitude) + 1);
  return settingsTideRest(amplitude) * reveal - hidden * (1 - reveal);
}

/// Where text starts to fade, below the viewport's top (tideTextSeamPx): never above it.
double settingsTideTextSeam(double reveal) => math.max(0, settingsTideBase(reveal, HomeSeamMetrics.tideAmplitude));

/// The chrome tide over the scrolling page: two waves of the page colour
/// from above the viewport down to the line, the back one at
/// [HomeSeamMetrics.tideBackAlpha]. Kept inside the viewport (the bar's
/// title sits right above it). Nothing at rest.
class SettingsTidePainter extends CustomPainter {
  SettingsTidePainter({required this.flow, required this.color}) : super(repaint: flow);

  final SeamFlow flow;
  final Color color;
  final Path _wave = Path();

  @override
  void paint(Canvas canvas, Size size) {
    final reveal = flow.reveal;
    if (reveal <= 0) return;
    final amplitude =
        HomeSeamMetrics.tideAmplitude + HomeSeamMetrics.tideAmplitudeStretched * (flow.reduced ? 0 : flow.stretch);
    final base = settingsTideBase(reveal, amplitude);
    if (base + settingsTideDepth(amplitude) <= 0) return;
    final phase = flow.reduced ? 0.0 : (flow.travelPx + flow.lagPx) / HomeSeamMetrics.tidePhaseTravel;
    canvas.save();
    canvas.clipRect(Offset.zero & size);
    _setWave(
      size.width,
      base + HomeSeamMetrics.tideBackDrop,
      amplitude * HomeSeamMetrics.tideBackAmplitude,
      HomeSeamMetrics.tideBackWavelength,
      0.37 - phase * 0.7,
    );
    canvas.drawPath(_wave, Paint()..color = color.withValues(alpha: color.a * HomeSeamMetrics.tideBackAlpha));
    _setWave(size.width, base, amplitude, HomeSeamMetrics.tideFrontWavelength, phase);
    canvas.drawPath(_wave, Paint()..color = color);
    canvas.restore();
  }

  /// Closes [_wave] from above the top edge down to the wave line.
  void _setWave(double width, double base, double amplitude, double wavelength, double phase) {
    const margin = HomeSeamMetrics.tideMargin;
    _wave
      ..reset()
      ..moveTo(-margin, -margin);
    var x = -margin;
    while (true) {
      _wave.lineTo(x, tideWaveY(x: x, base: base, amplitude: amplitude, wavelength: wavelength, phase: phase));
      if (x >= width + margin) break;
      x = math.min(x + HomeSeamMetrics.tideStep, width + margin);
    }
    _wave
      ..lineTo(width + margin, -margin)
      ..close();
  }

  @override
  bool shouldRepaint(SettingsTidePainter old) => old.flow != flow || old.color != color;
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
      // A transparent bar reads as dark to AppBar (light icons on a light page); follow the page instead.
      systemOverlayStyle: yoinStatusBarStyle(Theme.of(context).brightness),
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
