import 'package:flutter/material.dart';

import '../theme/motion.dart';
import '../theme/tokens.dart';
import 'haptics.dart';
import 'note_line.dart';
import 'note_logic.dart';
import 'nowplaying_strings.dart';

/// The stage's page tabs (Android StageTabs): Compact shows the text tabs
/// (Lyrics | Note), Expanded the full-width tab group; the two cross-fade on
/// the stage's detail progress (the text tabs lift away, the group settles
/// in). Each is composed only while it shows, so the invisible one never
/// takes a tap. Android's About tab needs Gemini and is not on HarmonyOS.
class StageTabs extends StatelessWidget {
  const StageTabs({super.key, required this.selected, required this.detail, required this.onSelect});

  final NowPlayingDetailPage selected;
  final Animation<double> detail;
  final ValueChanged<NowPlayingDetailPage> onSelect;

  @override
  Widget build(BuildContext context) {
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
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: NowPlayingTokens.columnPadding),
                      child: Align(
                        alignment: AlignmentDirectional.topStart,
                        child: _TextTabs(selected: selected, onSelect: onSelect),
                      ),
                    ),
                  ),
                ),
              if (d > 0)
                Opacity(
                  opacity: d,
                  child: Transform.translate(
                    offset: Offset(0, NowPlayingTokens.tabGroupDrop * (1 - d)),
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: NowPlayingTokens.columnPadding),
                      child: _TabGroup(selected: selected, onSelect: onSelect),
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
  NowPlayingDetailPage.note => strings.tabNote,
};

/// CompactTextTabs: left-anchored labels, the selected one bold, full alpha
/// and stretched × 1.08 from its leading edge; a press dips it to 0.92.
class _TextTabs extends StatelessWidget {
  const _TextTabs({required this.selected, required this.onSelect});

  final NowPlayingDetailPage selected;
  final ValueChanged<NowPlayingDetailPage> onSelect;

  @override
  Widget build(BuildContext context) {
    final strings = NowPlayingStrings.of(context);
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        for (final page in NowPlayingDetailPage.values) ...[
          if (page.index > 0) const SizedBox(width: NowPlayingTokens.tabGap),
          _TextTab(
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
    );
  }
}

class _TextTab extends StatefulWidget {
  const _TextTab({required this.label, required this.selected, required this.onTap});

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
/// primary / onPrimary, the rest primaryContainer; colours on the effects spring.
class _TabGroup extends StatelessWidget {
  const _TabGroup({required this.selected, required this.onSelect});

  final NowPlayingDetailPage selected;
  final ValueChanged<NowPlayingDetailPage> onSelect;

  @override
  Widget build(BuildContext context) {
    final strings = NowPlayingStrings.of(context);
    return Align(
      alignment: Alignment.topCenter,
      child: SizedBox(
        height: NowPlayingTokens.expandedTabRow,
        child: Row(
          children: [
            for (final page in NowPlayingDetailPage.values) ...[
              if (page.index > 0) const SizedBox(width: NowPlayingTokens.tabGroupGap),
              Expanded(
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
            ],
          ],
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
