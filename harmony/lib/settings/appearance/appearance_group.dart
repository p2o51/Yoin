import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../../nowplaying/haptics.dart';
import '../../theme/tokens.dart';
import '../../ui/component/meta_line.dart';
import '../../ui/component/seam/seam.dart';
import '../../ui/navigation/yoin_push_route.dart';
import '../settings_components.dart';
import '../settings_metrics.dart';
import '../settings_page.dart';
import '../settings_pane.dart';
import '../settings_rows.dart';
import 'appearance_strings.dart';
import 'screen_corners.dart';
import 'screen_corners_screen.dart';
import 'scroll_edge_screen.dart';

/// Root Settings › Features and appearance › Appearance entry row (v4):
/// shows the chosen scroll edge and screen corner setting on one MetaLine.
class AppearanceEntryRow extends ConsumerWidget {
  const AppearanceEntryRow({super.key, required this.onOpen, this.trailing});

  final VoidCallback onOpen;
  final Widget? trailing;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = AppearanceStrings.of(context);
    final edge = strings.styleName(ref.watch(seamTopStyleProvider));
    final corners = ref.watch(screenCornersProvider);
    final cornersLabel =
        corners.following ? strings.screenCornersFollow : strings.screenCornersValue(corners.radius.round());
    return SettingsItem(
      icon: YoinSymbols.palette,
      title: strings.sectionAppearance,
      summaryContent: MetaLine(
        groups: [MetaPlain(edge), MetaMuted(cornersLabel)],
        style: Theme.of(context).textTheme.bodyMedium,
        color: Theme.of(context).colorScheme.onSurfaceVariant,
      ),
      trailing: trailing,
      onTap: onOpen,
    );
  }
}

/// Settings › Features and appearance › Appearance (v4): inline Scroll edge
/// live preview cards above, Screen corners row below.
class AppearanceScreen extends ConsumerWidget {
  const AppearanceScreen({super.key, this.inPane = false});

  final bool inPane;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = AppearanceStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final selected = ref.watch(seamTopStyleProvider);
    final foot = theme.textTheme.bodySmall!.copyWith(color: colors.onSurfaceVariant);
    final openInPane = SettingsPaneScope.maybeOf(context);
    return SettingsSubPage(
      title: strings.sectionAppearance,
      inPane: inPane,
      children: [
        SettingsGroupLabel(title: strings.scrollEdge),
        const SizedBox(height: SettingsMetrics.groupLabelGap),
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
        const SizedBox(height: SettingsMetrics.sectionGap),
        SettingsSegments(
          children: [
            ScreenCornersRow(
              trailing: openInPane != null ? Icon(YoinSymbols.chevronRight, color: colors.onSurfaceVariant) : null,
              onOpen: () {
                if (openInPane != null) {
                  openInPane(const ScreenCornersPane());
                } else {
                  Navigator.of(context).push(YoinPushRoute<void>(builder: (_) => const ScreenCornersScreen()));
                }
              },
            ),
          ],
        ),
      ],
    );
  }
}

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
