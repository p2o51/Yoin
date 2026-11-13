import 'package:flutter/material.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../l10n/strings.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import 'yoin_section.dart';

/// The floating bottom bar, idle pose (nothing playing): Home and Library as
/// two labelled halves (YoinButtonGroup.kt resolveBarGeometry with idle = 1:
/// each half is (inner − 2 × gap) / 2, the folded pill's two gaps between).
///
/// The surface is surfaceContainerHigh, 28 corner, no shadow; the selected
/// half is primaryContainer, the other surfaceContainerHighest, crossing on
/// the effects spring.
class YoinBottomBar extends StatefulWidget {
  const YoinBottomBar({
    super.key,
    required this.selected,
    required this.onSelect,
  });

  final YoinSection selected;
  final ValueChanged<YoinSection> onSelect;

  @override
  State<YoinBottomBar> createState() => _YoinBottomBarState();
}

class _YoinBottomBarState extends State<YoinBottomBar> with TickerProviderStateMixin {
  /// 1 = Home selected, 0 = Library selected.
  late final SpringValue _homeSelection = SpringValue(
    vsync: this,
    initial: widget.selected == YoinSection.home ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );

  @override
  void didUpdateWidget(YoinBottomBar oldWidget) {
    super.didUpdateWidget(oldWidget);
    _homeSelection.animateTo(widget.selected == YoinSection.home ? 1 : 0);
  }

  @override
  void dispose() {
    _homeSelection.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final strings = YoinStrings.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(
        horizontal: BarTokens.horizontalMargin,
        vertical: BarTokens.portraitVerticalMargin,
      ),
      child: Container(
        height: BarTokens.height,
        padding: const EdgeInsets.all(BarTokens.rowPadding),
        decoration: ShapeDecoration(
          color: colors.surfaceContainerHigh,
          shape: RoundedSuperellipseBorder(
            borderRadius: BorderRadius.circular(BarTokens.corner),
          ),
        ),
        child: AnimatedBuilder(
          animation: _homeSelection.animation,
          builder: (context, _) {
            final home = _homeSelection.value.clamp(0.0, 1.0);
            return Row(
              children: [
                Expanded(
                  child: _BarHalf(
                    icon: YoinSymbols.home,
                    label: strings.barLabelHome,
                    selection: home,
                    onTap: () => widget.onSelect(YoinSection.home),
                  ),
                ),
                const SizedBox(width: BarTokens.itemGap * 2),
                Expanded(
                  child: _BarHalf(
                    icon: YoinSymbols.library,
                    label: strings.barLabelLibrary,
                    selection: 1 - home,
                    onTap: () => widget.onSelect(YoinSection.library),
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

class _BarHalf extends StatelessWidget {
  const _BarHalf({
    required this.icon,
    required this.label,
    required this.selection,
    required this.onTap,
  });

  final IconData icon;
  final String label;

  /// 0 = resting, 1 = selected.
  final double selection;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final container = Color.lerp(colors.surfaceContainerHighest, colors.primaryContainer, selection)!;
    final content = Color.lerp(colors.onSurfaceVariant, colors.onPrimaryContainer, selection)!;
    return Semantics(
      button: true,
      selected: selection > 0.5,
      label: label,
      excludeSemantics: true,
      child: Material(
        color: container,
        shape: const StadiumBorder(),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: onTap,
          child: SizedBox(
            height: BarTokens.buttonHeight,
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(icon, size: BarTokens.iconSize, color: content),
                const SizedBox(width: BarTokens.iconLabelGap),
                Flexible(
                  child: Text(
                    label,
                    maxLines: 1,
                    softWrap: false,
                    overflow: TextOverflow.fade,
                    style: Theme.of(context).textTheme.labelLarge?.copyWith(color: content),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
