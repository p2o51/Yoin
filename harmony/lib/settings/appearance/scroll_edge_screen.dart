import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../nowplaying/haptics.dart';
import '../../theme/motion.dart';
import '../../theme/tokens.dart';
import '../../ui/component/seam/seam.dart';
import '../settings_colors.dart';
import '../settings_components.dart';
import '../settings_page.dart';
import 'appearance_strings.dart';
import 'scroll_edge_preview.dart';

/// Settings › Appearance › Scroll edge as a page (ScrollEdgePage.kt; owner
/// 2026-10-09: a full page with pictures and words instead of a menu). One
/// card per style: a live preview of it, its name, what it does. The choice
/// applies at once and repaints every open seam ([seamTopStyleProvider],
/// stored under Android's synced key). On a wide window it is the Settings
/// list-detail's right pane ([inPane]).
class ScrollEdgeScreen extends ConsumerWidget {
  const ScrollEdgeScreen({super.key, this.inPane = false});

  final bool inPane;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = AppearanceStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final selected = ref.watch(seamTopStyleProvider);
    final note = theme.textTheme.bodyLarge!.copyWith(color: colors.onSurfaceVariant);
    final foot = theme.textTheme.bodySmall!.copyWith(color: colors.onSurfaceVariant);
    return SettingsSubPage(
      title: strings.scrollEdge,
      inPane: inPane,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(
            ScrollEdgeTokens.textInset,
            0,
            ScrollEdgeTokens.textInset,
            ScrollEdgeTokens.introBottom,
          ),
          child: SeamFade(
            fontSize: note.fontSize ?? 16,
            child: Text(strings.scrollEdgeIntro, style: note),
          ),
        ),
        for (final (i, style) in SeamTopStyle.values.indexed) ...[
          if (i > 0) const SizedBox(height: ScrollEdgeTokens.cardGap),
          ScrollEdgeStyleCard(
            key: ValueKey(style),
            style: style,
            selected: style == selected,
            onTap: () {
              if (style == selected) return;
              YoinHaptics.contextClick();
              ref.read(seamTopStyleProvider.notifier).select(style);
            },
          ),
        ],
        Padding(
          padding: const EdgeInsets.fromLTRB(
            ScrollEdgeTokens.textInset,
            ScrollEdgeTokens.footTop,
            ScrollEdgeTokens.textInset,
            0,
          ),
          child: SeamFade(
            fontSize: foot.fontSize ?? 12,
            child: Text(strings.scrollEdgeFoot, style: foot),
          ),
        ),
      ],
    );
  }
}

/// One style's card: the live preview, a radio with the style's name (and
/// "Default" on the tide), what it does. The chosen card takes
/// primaryContainer on the effects spring and rounds further on the spatial one.
class ScrollEdgeStyleCard extends StatefulWidget {
  const ScrollEdgeStyleCard({super.key, required this.style, required this.selected, required this.onTap});

  final SeamTopStyle style;
  final bool selected;
  final VoidCallback onTap;

  @override
  State<ScrollEdgeStyleCard> createState() => _ScrollEdgeStyleCardState();
}

class _ScrollEdgeStyleCardState extends State<ScrollEdgeStyleCard> with TickerProviderStateMixin {
  late final SpringValue _chosen = SpringValue(
    vsync: this,
    initial: widget.selected ? 1 : 0,
    spring: SettingsSprings.effects,
  );
  late final SpringValue _corner = SpringValue(
    vsync: this,
    initial: widget.selected ? ScrollEdgeTokens.cardCornerSelected : ScrollEdgeTokens.cardCorner,
    spring: SettingsSprings.spatial,
  );

  @override
  void didUpdateWidget(ScrollEdgeStyleCard old) {
    super.didUpdateWidget(old);
    if (old.selected == widget.selected) return;
    final chosen = widget.selected ? 1.0 : 0.0;
    final corner = widget.selected ? ScrollEdgeTokens.cardCornerSelected : ScrollEdgeTokens.cardCorner;
    if (MediaQuery.disableAnimationsOf(context)) {
      _chosen.snapTo(chosen);
      _corner.snapTo(corner);
    } else {
      _chosen.animateTo(chosen);
      _corner.animateTo(corner);
    }
  }

  @override
  void dispose() {
    _chosen.dispose();
    _corner.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final strings = AppearanceStrings.of(context);
    final style = widget.style;
    return Semantics(
      selected: widget.selected,
      inMutuallyExclusiveGroup: true,
      button: true,
      child: AnimatedBuilder(
        animation: Listenable.merge([_chosen.animation, _corner.animation]),
        builder: (context, _) {
          final c = _chosen.value.clamp(0.0, 1.0);
          final container = Color.lerp(settingsRowColor(colors), colors.primaryContainer, c)!;
          final content = Color.lerp(colors.onSurface, colors.onPrimaryContainer, c)!;
          final shape = RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(_corner.value));
          // Painted, not clipped (HarmonyOS clip seams over the preview's scroll); the ink keeps to the shape.
          return Material(
            color: container,
            shape: shape,
            child: InkWell(
              customBorder: shape,
              onTap: widget.onTap,
              child: Padding(
                padding: const EdgeInsets.fromLTRB(
                  ScrollEdgeTokens.cardPadding,
                  ScrollEdgeTokens.cardPadding,
                  ScrollEdgeTokens.cardPadding,
                  ScrollEdgeTokens.cardPaddingBottom,
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    // Covers stay cover-sized: a wide pane gets more columns, not stretched ones.
                    LayoutBuilder(
                      builder: (context, constraints) => SizedBox(
                        height: ScrollEdgeTokens.previewHeight,
                        child: ScrollEdgePreview(
                          style: style,
                          playing: true,
                          cornerFill: container,
                          columns: (constraints.maxWidth / ScrollEdgeTokens.previewColumnWidth).floor().clamp(
                            ScrollEdgeTokens.previewMinColumns,
                            ScrollEdgeTokens.previewMaxColumns,
                          ),
                        ),
                      ),
                    ),
                    Padding(
                      padding: const EdgeInsetsDirectional.only(
                        top: ScrollEdgeTokens.cardNameTop,
                        end: ScrollEdgeTokens.textInset,
                      ),
                      child: Row(
                        children: [
                          // The card is the control; the radio only shows the choice.
                          ExcludeSemantics(
                            child: IgnorePointer(
                              child: RadioGroup<bool>(
                                groupValue: true,
                                onChanged: (_) {},
                                child: Radio<bool>(value: widget.selected),
                              ),
                            ),
                          ),
                          const SizedBox(width: ScrollEdgeTokens.cardNameGap),
                          Expanded(
                            child: Text(
                              strings.styleName(style),
                              style: theme.textTheme.titleMedium!.copyWith(color: content),
                            ),
                          ),
                          if (style == SeamTopStyle.defaultStyle)
                            Padding(
                              padding: const EdgeInsetsDirectional.only(start: ScrollEdgeTokens.textInset),
                              child: DecoratedBox(
                                decoration: BoxDecoration(
                                  color: colors.secondaryContainer,
                                  borderRadius: BorderRadius.circular(ScrollEdgeTokens.defaultBadgeRadius),
                                ),
                                child: Padding(
                                  padding: const EdgeInsets.symmetric(
                                    horizontal: ScrollEdgeTokens.defaultBadgePaddingH,
                                    vertical: ScrollEdgeTokens.defaultBadgePaddingV,
                                  ),
                                  child: Text(
                                    strings.scrollEdgeDefault,
                                    style: theme.textTheme.labelMedium!.copyWith(color: colors.onSecondaryContainer),
                                  ),
                                ),
                              ),
                            ),
                        ],
                      ),
                    ),
                    Padding(
                      padding: const EdgeInsetsDirectional.only(
                        start: ScrollEdgeTokens.cardDescriptionStart,
                        end: ScrollEdgeTokens.textInset,
                      ),
                      child: Text(
                        strings.styleDescription(style),
                        style: theme.textTheme.bodyMedium!.copyWith(
                          color: content.withValues(alpha: ScrollEdgeTokens.cardDescriptionAlpha),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}
