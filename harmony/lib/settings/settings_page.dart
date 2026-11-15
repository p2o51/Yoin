import 'package:flutter/material.dart';

import '../detail/detail_page.dart' show DetailBackButton;
import '../shell/bar_suppression.dart';
import 'settings_colors.dart';
import 'settings_components.dart';
import 'settings_metrics.dart';

/// A Settings sub-page (Pixel's: the page title set large, then its groups).
/// Pushed, it has Settings' bar — the back button, the title handed off as
/// the large one scrolls away. In the wide window's right pane ([inPane]) it
/// is the scroll alone; a page opened from another pane page ([paneBack])
/// leads with Settings' back button, which goes back inside the pane (the
/// list-detail holds system back for it too).
class SettingsSubPage extends StatefulWidget {
  const SettingsSubPage({
    super.key,
    required this.title,
    required this.children,
    this.inPane = false,
    this.paneBack = false,
  });

  final String title;
  final List<Widget> children;
  final bool inPane;
  final bool paneBack;

  @override
  State<SettingsSubPage> createState() => _SettingsSubPageState();
}

class _SettingsSubPageState extends State<SettingsSubPage> {
  final _scroll = ScrollController();

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final list = ListView(
      controller: _scroll,
      // In the pane the panel's own clip bounds the scroll: a second clip on
      // the same bottom edge left a dark hairline there on the MateBook.
      clipBehavior: Clip.hardEdge,
      padding: EdgeInsets.fromLTRB(
        SettingsMetrics.pageHorizontal,
        SettingsMetrics.pageTop,
        SettingsMetrics.pageHorizontal,
        SettingsMetrics.pageBottom + MediaQuery.paddingOf(context).bottom,
      ),
      children: [
        if (widget.inPane && widget.paneBack)
          Padding(
            padding: const EdgeInsetsDirectional.only(start: SettingsMetrics.backStart - SettingsMetrics.pageHorizontal),
            child: Align(
              alignment: AlignmentDirectional.centerStart,
              child: DetailBackButton(container: colors.surfaceContainerHighest, content: colors.onSurface),
            ),
          ),
        SettingsHeadline(title: widget.title),
        ...widget.children,
      ],
    );
    if (widget.inPane) return SafeArea(top: false, bottom: false, child: list);
    return ChromeBarSuppression(
      child: Scaffold(
        backgroundColor: settingsPageColor(colors),
        appBar: SettingsBar(
          title: widget.title,
          controller: _scroll,
          titleTop: SettingsBar.headlineOffset,
          titleHeight: SettingsBar.lineHeight(context, Theme.of(context).textTheme.displaySmall!),
        ),
        body: SafeArea(top: false, bottom: false, child: list),
      ),
    );
  }
}
