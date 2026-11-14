import 'package:flutter/material.dart';

import '../theme/motion.dart';
import 'settings_colors.dart';
import 'settings_components.dart';
import 'settings_metrics.dart';

// Settings rows (SettingsComponents.kt SettingsSegments / SettingsItem /
// SettingsRowIcon, SettingsScreen.kt SelectableSegment): each row its own
// segment on the row surface, a plain line icon, title and summary.

/// The open row in the two-pane list takes the detail pane's colour
/// (SettingsSurfaces.rowSelected); the list pane steps down to surfaceDim.
Color settingsRowSelectedColor(ColorScheme scheme) => scheme.surfaceContainer;
Color settingsListPaneColor(ColorScheme scheme) => scheme.surfaceDim;

/// A group's rows without the label: each one a segment shaped by its place
/// in the stack ([settingsSegmentShape]), 2 apart. A row whose index is in
/// [selected] is the open one (list-detail): it takes the pane's colour on
/// the effects spring and rounds all its corners on the spatial spring.
class SettingsSegments extends StatelessWidget {
  const SettingsSegments({super.key, required this.children, this.selected = const {}});

  final List<Widget> children;
  final Set<int> selected;

  @override
  Widget build(BuildContext context) {
    final count = children.length;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        for (var i = 0; i < count; i++) ...[
          if (i > 0) const SizedBox(height: SettingsMetrics.segmentGap),
          _Segment(index: i, count: count, selected: selected.contains(i), child: children[i]),
        ],
      ],
    );
  }
}

/// A labelled group: the primary label, then its rows.
class SettingsGroup extends StatelessWidget {
  const SettingsGroup({super.key, required this.title, required this.children, this.selected = const {}});

  final String title;
  final List<Widget> children;
  final Set<int> selected;

  @override
  Widget build(BuildContext context) => Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SettingsGroupLabel(title: title),
          const SizedBox(height: SettingsMetrics.groupLabelGap),
          SettingsSegments(selected: selected, children: children),
        ],
      );
}

class _Segment extends StatefulWidget {
  const _Segment({required this.index, required this.count, required this.selected, required this.child});

  final int index;
  final int count;
  final bool selected;
  final Widget child;

  @override
  State<_Segment> createState() => _SegmentState();
}

class _SegmentState extends State<_Segment> with TickerProviderStateMixin {
  late final SpringValue _colour = SpringValue(
    vsync: this,
    initial: widget.selected ? 1 : 0,
    spring: SettingsSprings.effects,
  );
  late final SpringValue _corners = SpringValue(
    vsync: this,
    initial: widget.selected ? 1 : 0,
    spring: SettingsSprings.spatial,
  );

  @override
  void didUpdateWidget(_Segment old) {
    super.didUpdateWidget(old);
    if (old.selected == widget.selected) return;
    final target = widget.selected ? 1.0 : 0.0;
    if (MediaQuery.maybeDisableAnimationsOf(context) ?? false) {
      _colour.snapTo(target);
      _corners.snapTo(target);
    } else {
      _colour.animateTo(target);
      _corners.animateTo(target);
    }
  }

  @override
  void dispose() {
    _colour.dispose();
    _corners.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return AnimatedBuilder(
      animation: Listenable.merge([_colour.animation, _corners.animation]),
      builder: (context, child) {
        final c = _colour.value.clamp(0.0, 1.0);
        final rest = settingsSegmentShape(widget.index, widget.count);
        const open = RoundedRectangleBorder(
          borderRadius: BorderRadius.all(Radius.circular(SettingsMetrics.segmentOuterRadius)),
        );
        final shape = ShapeBorder.lerp(rest, open, _corners.value.clamp(0.0, 1.0))!;
        return Semantics(
          selected: widget.selected ? true : null,
          child: Material(
            color: Color.lerp(settingsRowColor(colors), settingsRowSelectedColor(colors), c),
            shape: shape,
            clipBehavior: Clip.antiAlias,
            child: child,
          ),
        );
      },
      child: widget.child,
    );
  }
}

/// The icon that leads a row: a plain line icon in a 40dp column.
class SettingsRowIcon extends StatelessWidget {
  const SettingsRowIcon({super.key, required this.icon});

  final IconData icon;

  @override
  Widget build(BuildContext context) => SizedBox.square(
        dimension: SettingsMetrics.rowIconBox,
        child: Icon(icon, size: SettingsMetrics.rowIcon, color: Theme.of(context).colorScheme.onSurfaceVariant),
      );
}

/// One row: icon, title over summary, an optional trailing control. With
/// [onTap] the whole row is the target.
class SettingsItem extends StatelessWidget {
  const SettingsItem({
    super.key,
    required this.title,
    this.icon,
    this.leading,
    this.summary,
    this.summaryDetail,
    this.onTap,
    this.trailing,
    this.semanticsRadio = false,
  });

  final String title;
  final IconData? icon;

  /// In place of [icon] (a radio).
  final Widget? leading;
  final String? summary;
  final String? summaryDetail;
  final VoidCallback? onTap;
  final Widget? trailing;

  /// A radio row reads as one control.
  final bool semanticsRadio;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final secondary = theme.textTheme.bodyMedium!.copyWith(color: colors.onSurfaceVariant);
    final lead = leading ?? (icon == null ? null : SettingsRowIcon(icon: icon!));
    final row = ConstrainedBox(
      constraints: const BoxConstraints(minHeight: SettingsMetrics.rowMinHeight),
      child: Padding(
        padding: const EdgeInsets.symmetric(
          horizontal: SettingsMetrics.rowPaddingHorizontal,
          vertical: SettingsMetrics.rowPaddingVertical,
        ),
        child: Row(
          children: [
            if (lead != null) ...[lead, const SizedBox(width: SettingsMetrics.rowIconGap)],
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(title, style: theme.textTheme.titleMedium!.copyWith(color: colors.onSurface)),
                  if (summary != null) ...[
                    const SizedBox(height: SettingsMetrics.rowTextGap),
                    Text(summary!, style: secondary, maxLines: 2, overflow: TextOverflow.ellipsis),
                  ],
                  if (summaryDetail != null)
                    Text(summaryDetail!, style: secondary, maxLines: 2, overflow: TextOverflow.ellipsis),
                ],
              ),
            ),
            if (trailing != null) ...[const SizedBox(width: SettingsMetrics.rowTrailingGap), trailing!],
          ],
        ),
      ),
    );
    if (onTap == null) return row;
    return InkWell(
      onTap: onTap,
      child: semanticsRadio ? MergeSemantics(child: row) : row,
    );
  }
}

/// A short note in secondary container colour (InlineNotice).
class SettingsNotice extends StatelessWidget {
  const SettingsNotice({super.key, required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return DecoratedBox(
      decoration: ShapeDecoration(
        color: colors.secondaryContainer,
        shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(SettingsMetrics.noticeRadius)),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(
          horizontal: SettingsMetrics.noticeHorizontal,
          vertical: SettingsMetrics.noticeVertical,
        ),
        child: Text(text, style: theme.textTheme.bodyMedium!.copyWith(color: colors.onSecondaryContainer)),
      ),
    );
  }
}

/// Fades [child] in and out on the effects spring, keeping its room.
class SpringFade extends StatefulWidget {
  const SpringFade({super.key, required this.visible, required this.child});

  final bool visible;
  final Widget child;

  @override
  State<SpringFade> createState() => _SpringFadeState();
}

class _SpringFadeState extends State<SpringFade> with SingleTickerProviderStateMixin {
  late final SpringValue _alpha = SpringValue(
    vsync: this,
    initial: widget.visible ? 1 : 0,
    spring: SettingsSprings.effects,
  );

  @override
  void didUpdateWidget(SpringFade old) {
    super.didUpdateWidget(old);
    if (old.visible == widget.visible) return;
    final target = widget.visible ? 1.0 : 0.0;
    if (MediaQuery.maybeDisableAnimationsOf(context) ?? false) {
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
  Widget build(BuildContext context) => AnimatedBuilder(
        animation: _alpha.animation,
        builder: (context, child) => Opacity(opacity: _alpha.value.clamp(0.0, 1.0), child: child),
        child: widget.child,
      );
}
