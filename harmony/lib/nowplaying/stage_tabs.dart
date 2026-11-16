import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/component/yoin_loading_indicator.dart';
import 'haptics.dart';
import 'note_line.dart';
import 'note_logic.dart';
import 'nowplaying_strings.dart';

/// The stage's page tabs (Android StageTabs): Compact shows the text tabs
/// (Lyrics | About | Note), Expanded the full-width tab group; the two
/// cross-fade on the stage's detail progress (the text tabs lift away, the
/// group settles in). Each is composed only while it shows, so the invisible
/// one never takes a tap.
class StageTabs extends StatelessWidget {
  const StageTabs({
    super.key,
    required this.selected,
    required this.detail,
    required this.onSelect,
    this.tools,
    this.textOnly = false,
    this.aboutBusy = false,
  });

  final NowPlayingDetailPage selected;
  final Animation<double> detail;
  final ValueChanged<NowPlayingDetailPage> onSelect;

  /// The lyric tools at the end of the text tabs (the enlarged phone at rest;
  /// the 16:9 expanded page), vertically centred on the row.
  final Widget? tools;

  /// 16:9 (断点交接 §3.1): the expanded page keeps the TEXT tabs instead of
  /// the tab group, with [tools] at the row's end.
  final bool textOnly;

  /// The AI is loading About over credits / facts already shown: a small
  /// indicator opens between the About and Note tabs (Android 11-16).
  final bool aboutBusy;

  @override
  Widget build(BuildContext context) {
    Widget textRow({required bool top}) => Padding(
      padding: const EdgeInsets.symmetric(horizontal: NowPlayingTokens.columnPadding),
      child: tools == null
          ? Align(
              alignment: top ? AlignmentDirectional.topStart : AlignmentDirectional.centerStart,
              child: _TextTabs(selected: selected, onSelect: onSelect, busy: aboutBusy),
            )
          : Row(
              children: [
                _TextTabs(selected: selected, onSelect: onSelect, busy: aboutBusy),
                // Four keys beside the tabs: a narrow row scales them down rather than overflow.
                Expanded(
                  child: Align(
                    alignment: AlignmentDirectional.centerEnd,
                    child: FittedBox(fit: BoxFit.scaleDown, child: tools),
                  ),
                ),
              ],
            ),
    );
    if (textOnly) return ClipRect(child: textRow(top: false));
    return AnimatedBuilder(
      animation: detail,
      builder: (context, _) {
        final d = detail.value.clamp(0.0, 1.0);
        return ClipRect(
          child: Stack(
            fit: StackFit.expand,
            children: [
              if (d < 1)
                Opacity(
                  opacity: 1 - d,
                  child: Transform.translate(
                    offset: Offset(0, -NowPlayingTokens.tabCompactLift * d),
                    child: textRow(top: true),
                  ),
                ),
              if (d > 0)
                Opacity(
                  opacity: d,
                  child: Transform.translate(
                    offset: Offset(0, NowPlayingTokens.tabGroupDrop * (1 - d)),
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: NowPlayingTokens.columnPadding),
                      child: _TabGroup(selected: selected, onSelect: onSelect, busy: aboutBusy),
                    ),
                  ),
                ),
            ],
          ),
        );
      },
    );
  }
}

String _label(NowPlayingStrings strings, NowPlayingDetailPage page) => switch (page) {
  NowPlayingDetailPage.lyrics => strings.tabLyrics,
  NowPlayingDetailPage.about => strings.tabAbout,
  NowPlayingDetailPage.note => strings.tabNote,
};

/// CompactTextTabs: left-anchored labels, the selected one bold, full alpha
/// and stretched × 1.08 from its leading edge; a press dips it to 0.92. While
/// [busy], a small loading indicator opens from nothing before the Note tab.
class _TextTabs extends StatelessWidget {
  const _TextTabs({required this.selected, required this.onSelect, required this.busy});

  final NowPlayingDetailPage selected;
  final ValueChanged<NowPlayingDetailPage> onSelect;
  final bool busy;

  @override
  Widget build(BuildContext context) {
    final strings = NowPlayingStrings.of(context);
    return _BusyProgress(
      busy: busy,
      builder: (context, p) => Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          for (final page in NowPlayingDetailPage.values) ...[
            if (page == NowPlayingDetailPage.note && p > 0)
              _BusySlot(
                width: NowPlayingTokens.tabBusySlot * p,
                indicator: NowPlayingTokens.tabBusyIndicator,
                progress: p,
                alignment: AlignmentDirectional.centerEnd,
              ),
            if (page.index > 0) const SizedBox(width: NowPlayingTokens.tabGap),
            _TextTab(
              key: ValueKey(page),
              label: _label(strings, page),
              selected: page == selected,
              onTap: () {
                if (page == selected) return;
                YoinHaptics.tick();
                onSelect(page);
              },
            ),
          ],
        ],
      ),
    );
  }
}

/// The About-busy indicator's open progress, 0 → 1 on the standard default
/// spatial spring (Android animateFloatAsState "aboutBusy" / "tabBusy").
class _BusyProgress extends StatefulWidget {
  const _BusyProgress({required this.busy, required this.builder});

  final bool busy;
  final Widget Function(BuildContext context, double progress) builder;

  @override
  State<_BusyProgress> createState() => _BusyProgressState();
}

class _BusyProgressState extends State<_BusyProgress> with SingleTickerProviderStateMixin {
  late final SpringValue _p = SpringValue(
    vsync: this,
    initial: widget.busy ? 1 : 0,
    spring: YoinSprings.standardDefaultSpatial,
  );

  @override
  void didUpdateWidget(_BusyProgress oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.busy == widget.busy) return;
    if (MediaQuery.disableAnimationsOf(context)) {
      _p.snapTo(widget.busy ? 1 : 0);
    } else {
      _p.animateTo(widget.busy ? 1 : 0);
    }
  }

  @override
  void dispose() {
    _p.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: _p.animation,
    builder: (context, _) => widget.builder(context, math.max(0.0, _p.value)),
  );
}

/// The indicator's slot: [width] wide (it opens from nothing), the indicator
/// at its own size, scaled and faded in with [progress].
class _BusySlot extends StatelessWidget {
  const _BusySlot({required this.width, required this.indicator, required this.progress, required this.alignment});

  final double width;
  final double indicator;
  final double progress;
  final AlignmentGeometry alignment;

  @override
  Widget build(BuildContext context) {
    final p = progress.clamp(0.0, 1.0);
    return SizedBox(
      width: width,
      child: OverflowBox(
        alignment: alignment,
        minWidth: indicator,
        maxWidth: indicator,
        minHeight: indicator,
        maxHeight: indicator,
        child: Opacity(
          opacity: p,
          child: Transform.scale(
            scale: p,
            child: YoinLoadingIndicator(size: indicator),
          ),
        ),
      ),
    );
  }
}

class _TextTab extends StatefulWidget {
  const _TextTab({super.key, required this.label, required this.selected, required this.onTap});

  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  State<_TextTab> createState() => _TextTabState();
}

class _TextTabState extends State<_TextTab> with TickerProviderStateMixin {
  bool _pressed = false;
  late final SpringValue _stretch = SpringValue(vsync: this, initial: _target, spring: YoinSprings.defaultSpatial);
  late final SpringValue _alpha = SpringValue(
    vsync: this,
    initial: widget.selected ? 1 : NowPlayingTokens.tabIdleAlpha,
    spring: YoinSprings.defaultEffects,
  );

  double get _target =>
      _pressed ? NowPlayingTokens.tabPressedStretch : (widget.selected ? NowPlayingTokens.tabSelectedStretch : 1);

  void _retarget() {
    _stretch.animateTo(_target);
    _alpha.animateTo(widget.selected ? 1 : NowPlayingTokens.tabIdleAlpha);
  }

  @override
  void didUpdateWidget(_TextTab oldWidget) {
    super.didUpdateWidget(oldWidget);
    _retarget();
  }

  @override
  void dispose() {
    _stretch.dispose();
    _alpha.dispose();
    super.dispose();
  }

  void _press(bool down) {
    _pressed = down;
    _retarget();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final base = Theme.of(context).textTheme.labelLarge!;
    final style = (widget.selected ? noteWeighted(base, 700) : base).copyWith(color: colors.onSurfaceVariant);
    return Semantics(
      button: true,
      selected: widget.selected,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTapDown: (_) => _press(true),
        onTapUp: (_) => _press(false),
        onTapCancel: () => _press(false),
        onTap: widget.onTap,
        child: AnimatedBuilder(
          animation: Listenable.merge([_stretch.animation, _alpha.animation]),
          builder: (context, child) => Opacity(
            opacity: _alpha.value.clamp(0.0, 1.0),
            child: Transform.scale(scaleX: _stretch.value, alignment: AlignmentDirectional.centerStart, child: child),
          ),
          child: Text(widget.label, maxLines: 1, style: style),
        ),
      ),
    );
  }
}

/// FullscreenTabGroup (YoinTabGroup): equal-width capsules, the selected one
/// primary / onPrimary, the rest primaryContainer; colours on the effects
/// spring. While [busy], a loading indicator opens between About and Note and
/// the capsules make room for it.
class _TabGroup extends StatelessWidget {
  const _TabGroup({required this.selected, required this.onSelect, required this.busy});

  final NowPlayingDetailPage selected;
  final ValueChanged<NowPlayingDetailPage> onSelect;
  final bool busy;

  @override
  Widget build(BuildContext context) {
    final strings = NowPlayingStrings.of(context);
    return Align(
      alignment: Alignment.topCenter,
      child: SizedBox(
        height: NowPlayingTokens.expandedTabRow,
        child: _BusyProgress(
          busy: busy,
          builder: (context, p) => Row(
            children: [
              for (final page in NowPlayingDetailPage.values) ...[
                if (page.index > 0) const SizedBox(width: NowPlayingTokens.tabGroupGap),
                Expanded(
                  key: ValueKey(page),
                  child: _GroupTab(
                    label: _label(strings, page),
                    selected: page == selected,
                    onTap: () {
                      if (page == selected) return;
                      YoinHaptics.tick();
                      onSelect(page);
                    },
                  ),
                ),
                if (page == NowPlayingDetailPage.about && p > 0) ...[
                  SizedBox(width: NowPlayingTokens.tabGroupGap * p),
                  _BusySlot(
                    width: NowPlayingTokens.tabGroupBusy * p,
                    indicator: NowPlayingTokens.tabGroupBusy,
                    progress: p,
                    alignment: Alignment.center,
                  ),
                ],
              ],
            ],
          ),
        ),
      ),
    );
  }
}

class _GroupTab extends StatefulWidget {
  const _GroupTab({required this.label, required this.selected, required this.onTap});

  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  State<_GroupTab> createState() => _GroupTabState();
}

class _GroupTabState extends State<_GroupTab> with TickerProviderStateMixin {
  late final SpringValue _on = SpringValue(
    vsync: this,
    initial: widget.selected ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );

  @override
  void didUpdateWidget(_GroupTab oldWidget) {
    super.didUpdateWidget(oldWidget);
    _on.animateTo(widget.selected ? 1 : 0);
  }

  @override
  void dispose() {
    _on.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final base = Theme.of(context).textTheme.labelLarge!;
    return Semantics(
      button: true,
      selected: widget.selected,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: widget.onTap,
        child: AnimatedBuilder(
          animation: _on.animation,
          builder: (context, _) {
            final on = _on.value.clamp(0.0, 1.0);
            return DecoratedBox(
              decoration: ShapeDecoration(
                color: Color.lerp(colors.primaryContainer, colors.primary, on),
                shape: const StadiumBorder(),
              ),
              child: Center(
                child: Text(
                  widget.label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: noteWeighted(
                    base,
                    widget.selected ? 700 : 500,
                  ).copyWith(color: Color.lerp(colors.onPrimaryContainer, colors.onPrimary, on)),
                ),
              ),
            );
          },
        ),
      ),
    );
  }
}
