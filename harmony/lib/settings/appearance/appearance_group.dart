import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../../ui/component/meta_line.dart';
import '../../ui/component/seam/seam_style.dart';
import '../settings_pane.dart';
import '../settings_rows.dart';
import 'appearance_strings.dart';
import 'screen_corners.dart';

/// Settings › Appearance (SettingsScreen.kt's settings_section_appearance),
/// between Features and Sync and storage: Scroll edge and Screen corners,
/// each opening its page (in the right pane on a wide window, marked while open).
class AppearanceGroup extends StatelessWidget {
  const AppearanceGroup({super.key, required this.open, required this.listDetail, required this.onOpen});

  final SettingsPane? open;
  final bool listDetail;
  final OpenSettingsPane onOpen;

  @override
  Widget build(BuildContext context) {
    final chevron = listDetail
        ? Icon(YoinSymbols.chevronRight, color: Theme.of(context).colorScheme.onSurfaceVariant)
        : null;
    return SettingsGroup(
      title: AppearanceStrings.of(context).sectionAppearance,
      selected: {if (open is ScrollEdgePane) 0, if (open is ScreenCornersPane) 1},
      children: [
        ScrollEdgeRow(trailing: chevron, onOpen: () => onOpen(const ScrollEdgePane())),
        ScreenCornersRow(trailing: chevron, onOpen: () => onOpen(const ScreenCornersPane())),
      ],
    );
  }
}

/// How scrolling content meets the fixed chrome at the top of a page: the
/// chosen style's name as the summary.
class ScrollEdgeRow extends ConsumerWidget {
  const ScrollEdgeRow({super.key, required this.onOpen, this.trailing});

  final VoidCallback onOpen;
  final Widget? trailing;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = AppearanceStrings.of(context);
    return SettingsItem(
      icon: YoinSymbols.unfoldLess,
      title: strings.scrollEdge,
      summary: strings.styleName(ref.watch(seamTopStyleProvider)),
      trailing: trailing,
      onTap: onOpen,
    );
  }
}

/// The screen's corner: "Match screen" with "N dp" beside it as a MetaLine
/// group (no dot) while following the screen, else "N dp".
class ScreenCornersRow extends ConsumerWidget {
  const ScreenCornersRow({super.key, required this.onOpen, this.trailing});

  final VoidCallback onOpen;
  final Widget? trailing;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = AppearanceStrings.of(context);
    final corners = ref.watch(screenCornersProvider);
    final radius = corners.radius.round();
    final value = strings.screenCornersValue(radius);
    return SettingsItem(
      icon: YoinSymbols.smartphone,
      title: strings.screenCorners,
      summary: corners.following ? null : value,
      summaryContent: corners.following
          ? MetaLine(
              groups: [MetaPlain(strings.screenCornersFollow), MetaStat(value, '')],
              style: Theme.of(context).textTheme.bodyMedium,
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            )
          : null,
      trailing: trailing,
      onTap: onOpen,
    );
  }
}
