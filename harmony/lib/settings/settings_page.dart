import 'package:flutter/material.dart';

import '../shell/bar_suppression.dart';
import 'settings_colors.dart';
import 'settings_components.dart';
import 'settings_metrics.dart';

/// A Settings sub-page (Pixel's: the page title set large, then its groups).
/// Pushed, it has Settings' bar — the back button, the title handed off as
/// the large one scrolls away. In the wide window's right pane ([inPane]) it
/// is the scroll alone: the pane has no back of its own.
class SettingsSubPage extends StatefulWidget {
  const SettingsSubPage({super.key, required this.title, required this.children, this.inPane = false});

  final String title;
  final List<Widget> children;
  final bool inPane;

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
    final list = ListView(
      controller: _scroll,
      padding: EdgeInsets.fromLTRB(
        SettingsMetrics.pageHorizontal,
        SettingsMetrics.pageTop,
        SettingsMetrics.pageHorizontal,
        SettingsMetrics.pageBottom + MediaQuery.paddingOf(context).bottom,
      ),
      children: [SettingsHeadline(title: widget.title), ...widget.children],
    );
    if (widget.inPane) return SafeArea(top: false, bottom: false, child: list);
    final colors = Theme.of(context).colorScheme;
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
