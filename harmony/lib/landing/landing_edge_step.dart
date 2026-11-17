import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../nowplaying/haptics.dart';
import '../settings/appearance/appearance_strings.dart';
import '../settings/appearance/screen_corner_control.dart';
import '../settings/appearance/scroll_edge_preview.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/component/seam/seam_style.dart';
import 'landing_scenes.dart';

/// The scroll-edge step (LandingEdgeStep.kt): the three styles side by side,
/// each a live preview, the chosen one outlined; under them one line on the
/// chosen style. That line's box is as tall as the longest of the three, so
/// picking never changes the window's height (owner 2026-10-09: no jump
/// between options). The choice applies at once ([seamTopStyleProvider]),
/// the same as Settings › Scroll edge. [playing] runs the previews' drift
/// (the step is the one arriving).
class ScrollEdgeScene extends ConsumerWidget {
  const ScrollEdgeScene({super.key, required this.playing, required this.onSelect});

  final bool playing;
  final ValueChanged<SeamTopStyle> onSelect;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final selected = ref.watch(seamTopStyleProvider);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final strings = AppearanceStrings.of(context);
    final body = theme.textTheme.bodyLarge!.copyWith(color: colors.onSurface);
    return LandingSceneColumn(
      children: [
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            for (final (i, style) in SeamTopStyle.values.indexed) ...[
              if (i > 0) const SizedBox(width: ScrollEdgeTokens.landingGap),
              Expanded(
                child: _EdgeOption(
                  key: ValueKey(style),
                  style: style,
                  selected: style == selected,
                  playing: playing,
                  onTap: () {
                    if (style != selected) YoinHaptics.contextClick();
                    onSelect(style);
                  },
                ),
              ),
            ],
          ],
        ),
        const SizedBox(height: ScrollEdgeTokens.landingLineTop),
        DecoratedBox(
          decoration: ShapeDecoration(
            color: colors.surfaceBright,
            shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(ScrollEdgeTokens.landingLineRadius)),
          ),
          child: Padding(
            padding: const EdgeInsets.symmetric(
              horizontal: ScrollEdgeTokens.landingLinePaddingH,
              vertical: ScrollEdgeTokens.landingLinePaddingV,
            ),
            // All three lines are laid out in one cell; only the chosen one shows.
            child: Stack(
              children: [
                for (final style in SeamTopStyle.values)
                  _EdgeLine(
                    key: ValueKey(style),
                    shown: style == selected,
                    text: TextSpan(
                      style: body,
                      children: [
                        TextSpan(
                          text: strings.styleName(style),
                          style: const TextStyle(fontWeight: FontWeight.w600),
                        ),
                        const TextSpan(text: '  '),
                        TextSpan(text: strings.styleShort(style)),
                      ],
                    ),
                  ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

/// One line under the options, fading on the effects spring; only the shown one is read out.
class _EdgeLine extends StatefulWidget {
  const _EdgeLine({super.key, required this.shown, required this.text});

  final bool shown;
  final TextSpan text;

  @override
  State<_EdgeLine> createState() => _EdgeLineState();
}

class _EdgeLineState extends State<_EdgeLine> with SingleTickerProviderStateMixin {
  late final SpringValue _alpha = SpringValue(
    vsync: this,
    initial: widget.shown ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );

  @override
  void didUpdateWidget(_EdgeLine old) {
    super.didUpdateWidget(old);
    if (old.shown == widget.shown) return;
    final target = widget.shown ? 1.0 : 0.0;
    if (MediaQuery.disableAnimationsOf(context)) {
      _alpha.snapTo(target);
    } else {
      _alpha.animateTo(target);
    }
  }

  @override
  void dispose() {
    _alpha.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => ExcludeSemantics(
    excluding: !widget.shown,
    child: AnimatedBuilder(
      animation: _alpha.animation,
      builder: (context, child) => Opacity(opacity: _alpha.value.clamp(0.0, 1.0), child: child),
      child: Text.rich(widget.text),
    ),
  );
}

/// One style: its live preview (rounder and ringed in primary when chosen) and its name.
class _EdgeOption extends StatefulWidget {
  const _EdgeOption({
    super.key,
    required this.style,
    required this.selected,
    required this.playing,
    required this.onTap,
  });

  final SeamTopStyle style;
  final bool selected;
  final bool playing;
  final VoidCallback onTap;

  @override
  State<_EdgeOption> createState() => _EdgeOptionState();
}

class _EdgeOptionState extends State<_EdgeOption> with TickerProviderStateMixin {
  late final SpringValue _corner = SpringValue(
    vsync: this,
    initial: widget.selected ? ScrollEdgeTokens.landingCornerSelected : ScrollEdgeTokens.landingCorner,
    spring: YoinSprings.defaultSpatial,
  );
  late final SpringValue _chosen = SpringValue(
    vsync: this,
    initial: widget.selected ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );

  @override
  void didUpdateWidget(_EdgeOption old) {
    super.didUpdateWidget(old);
    if (old.selected == widget.selected) return;
    final corner = widget.selected ? ScrollEdgeTokens.landingCornerSelected : ScrollEdgeTokens.landingCorner;
    final chosen = widget.selected ? 1.0 : 0.0;
    if (MediaQuery.disableAnimationsOf(context)) {
      _corner.snapTo(corner);
      _chosen.snapTo(chosen);
    } else {
      _corner.animateTo(corner);
      _chosen.animateTo(chosen);
    }
  }

  @override
  void dispose() {
    _corner.dispose();
    _chosen.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final strings = AppearanceStrings.of(context);
    return Semantics(
      selected: widget.selected,
      inMutuallyExclusiveGroup: true,
      button: true,
      label: strings.styleName(widget.style),
      excludeSemantics: true,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: widget.onTap,
        child: AnimatedBuilder(
          animation: Listenable.merge([_corner.animation, _chosen.animation]),
          builder: (context, _) {
            final chosen = _chosen.value.clamp(0.0, 1.0);
            final corner = _corner.value;
            return Column(
              children: [
                AspectRatio(
                  aspectRatio: ScrollEdgeTokens.landingAspect,
                  child: Stack(
                    fit: StackFit.expand,
                    children: [
                      ScrollEdgePreview(
                        style: widget.style,
                        playing: widget.playing,
                        // The landing window's colour (LandingWindow) lies behind the corners.
                        cornerFill: colors.surfaceContainer,
                        corner: corner,
                      ),
                      IgnorePointer(
                        child: DecoratedBox(
                          decoration: BoxDecoration(
                            borderRadius: BorderRadius.circular(corner),
                            border: Border.all(
                              color: colors.primary.withValues(alpha: chosen),
                              width: ScrollEdgeTokens.landingRing,
                            ),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: ScrollEdgeTokens.landingNameTop),
                Text(
                  strings.styleName(widget.style),
                  maxLines: 1,
                  overflow: TextOverflow.visible,
                  softWrap: false,
                  style: theme.textTheme.labelLarge!.copyWith(
                    color: Color.lerp(colors.onSurfaceVariant, colors.primary, chosen),
                  ),
                ),
              ],
            );
          },
        ),
      ),
    );
  }
}

/// The screen-corner step: the control on its own (Android LandingSceneColumn { ScreenCornerControl() }).
class ScreenCornersScene extends StatelessWidget {
  const ScreenCornersScene({super.key});

  @override
  Widget build(BuildContext context) => const LandingSceneColumn(children: [ScreenCornerControl()]);
}
