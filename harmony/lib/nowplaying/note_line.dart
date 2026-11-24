import 'package:flutter/material.dart';

import '../notes/note_markup_text.dart';
import '../theme/motion.dart';
import '../theme/theme.dart';
import '../theme/tokens.dart';
import 'haptics.dart';
import 'note_logic.dart';
import 'nowplaying_strings.dart';

typedef _N = NowPlayingNoteTokens;

/// [base] at GSF weight [weight], its size, line height and tracking kept.
TextStyle noteWeighted(TextStyle base, int weight) =>
    base.merge(gsf(base.fontSize!, weight: weight, height: base.height, letterSpacing: base.letterSpacing ?? 0));

/// One note, read like a lyric line (Android NoteLine): its moment in a fixed
/// stamp column, then the words. The line the playhead is inside lights up in
/// primary; the others recede by [emphasis]. A legacy note with no moment
/// shows a small hollow dot. [meta] is a small line under the words (the
/// date, in the journal order). Shared by the Compact window and the
/// Expanded page — no cards, no rail, no per-row buttons. Colour and alpha on
/// the default effects spring, the scale on the default spatial spring.
class NoteLine extends StatefulWidget {
  const NoteLine({
    super.key,
    required this.stamp,
    required this.text,
    required this.active,
    this.emphasis = NoteLineEmphasis.page,
    this.distance = 0,
    this.meta,
    this.editing = false,
    this.interactive = true,
    this.onToggleChecklist,
  });

  final String? stamp;
  final String text;
  final bool active;
  final NoteLineEmphasis emphasis;
  final int? distance;
  final String? meta;

  /// Open in the write bar for rewriting: the line recedes to [NowPlayingNoteTokens.editingAlpha].
  final bool editing;

  /// False (Now Playing collapsed): mentions and to-dos take no touch; the host's tap expands.
  final bool interactive;

  /// A tap on a to-do's box (its line index).
  final void Function(int lineIndex)? onToggleChecklist;

  @override
  State<NoteLine> createState() => _NoteLineState();
}

class _NoteLineState extends State<NoteLine> with TickerProviderStateMixin {
  late final SpringValue _on = SpringValue(
    vsync: this,
    initial: widget.active ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );
  late final SpringValue _alpha = SpringValue(vsync: this, initial: _targetAlpha, spring: YoinSprings.defaultEffects);
  late final SpringValue _scale = SpringValue(vsync: this, initial: _targetScale, spring: YoinSprings.defaultSpatial);

  bool get _glance => widget.emphasis == NoteLineEmphasis.glance;

  double get _targetAlpha =>
      noteLineAlpha(active: widget.active, distance: widget.distance, emphasis: widget.emphasis) *
      (widget.editing ? _N.editingAlpha : 1);

  double get _targetScale => widget.active ? 1 : (_glance ? _N.glanceInactiveScale : _N.pageInactiveScale);

  @override
  void didUpdateWidget(NoteLine oldWidget) {
    super.didUpdateWidget(oldWidget);
    _on.animateTo(widget.active ? 1 : 0);
    _alpha.animateTo(_targetAlpha);
    _scale.animateTo(_targetScale);
  }

  @override
  void dispose() {
    _on.dispose();
    _alpha.dispose();
    _scale.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final glance = _glance;
    final active = widget.active;
    final body = glance && !active ? text.bodyMedium! : text.bodyLarge!;
    final bodyStyle = noteWeighted(body, switch ((glance, active)) {
      (true, true) => 700,
      (true, false) => 500,
      (false, true) => 600,
      (false, false) => 400,
    });
    final stampBase = glance ? text.labelSmall! : text.labelMedium!;
    final stampStyle = noteWeighted(
      stampBase,
      active ? 600 : 500,
    ).copyWith(fontFeatures: const [FontFeature.tabularFigures()]);
    final rest = glance ? colors.onSurfaceVariant : colors.onSurface;
    final lineHeight = (bodyStyle.fontSize ?? 16) * (bodyStyle.height ?? 1.25);
    return AnimatedBuilder(
      animation: Listenable.merge([_on.animation, _alpha.animation, _scale.animation]),
      builder: (context, _) {
        final on = _on.value.clamp(0.0, 1.0);
        final stampColor = Color.lerp(colors.onSurfaceVariant, colors.primary, on)!;
        final textColor = Color.lerp(rest, colors.primary, on)!;
        return Padding(
          padding: EdgeInsets.symmetric(vertical: glance ? _N.glanceRowPadV : _N.pageRowPadV),
          child: Opacity(
            opacity: _alpha.value.clamp(0.0, 1.0),
            child: Transform.scale(
              scale: _scale.value,
              alignment: AlignmentDirectional.centerStart,
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.baseline,
                textBaseline: TextBaseline.alphabetic,
                children: [
                  SizedBox(
                    width: _N.stampColumn,
                    child: widget.stamp != null
                        ? Text(widget.stamp!, maxLines: 1, style: stampStyle.copyWith(color: stampColor))
                        // No moment (a legacy note): a quiet hollow dot on the first line.
                        : SizedBox(
                            height: lineHeight,
                            child: Align(
                              alignment: AlignmentDirectional.centerStart,
                              child: Padding(
                                padding: const EdgeInsetsDirectional.only(start: _N.hollowDotInset),
                                child: Container(
                                  width: _N.hollowDot,
                                  height: _N.hollowDot,
                                  decoration: BoxDecoration(
                                    shape: BoxShape.circle,
                                    border: Border.all(color: colors.onSurfaceVariant, width: _N.hollowDotStroke),
                                  ),
                                ),
                              ),
                            ),
                          ),
                  ),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        NoteMarkupText(
                          widget.text,
                          style: bodyStyle.copyWith(color: textColor),
                          interactive: widget.interactive,
                          onToggleChecklist: widget.onToggleChecklist,
                        ),
                        if (widget.meta != null)
                          Padding(
                            padding: const EdgeInsets.only(top: _N.metaGap),
                            child: Text(widget.meta!, style: text.labelSmall!.copyWith(color: colors.onSurfaceVariant)),
                          ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }
}

/// The Timeline ⇄ Created switch on the Note page (Android NoteSortToggle): a
/// micro segmented capsule; the selected option fills primary on the effects spring.
class NoteSortToggle extends StatelessWidget {
  const NoteSortToggle({super.key, required this.mode, required this.onChanged});

  final NoteSortMode mode;
  final ValueChanged<NoteSortMode> onChanged;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final strings = NowPlayingStrings.of(context);
    Widget option(NoteSortMode m, String label) => _SortOption(
      label: label,
      selected: mode == m,
      onTap: () {
        if (mode == m) return;
        YoinHaptics.tick();
        onChanged(m);
      },
    );
    return DecoratedBox(
      decoration: ShapeDecoration(color: colors.surfaceContainerHigh, shape: const StadiumBorder()),
      child: Padding(
        padding: const EdgeInsets.all(_N.sortInset),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            option(NoteSortMode.timeline, strings.noteSortTimeline),
            option(NoteSortMode.created, strings.noteSortCreated),
          ],
        ),
      ),
    );
  }
}

class _SortOption extends StatefulWidget {
  const _SortOption({required this.label, required this.selected, required this.onTap});

  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  State<_SortOption> createState() => _SortOptionState();
}

class _SortOptionState extends State<_SortOption> with TickerProviderStateMixin {
  late final SpringValue _on = SpringValue(
    vsync: this,
    initial: widget.selected ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );

  @override
  void didUpdateWidget(_SortOption oldWidget) {
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
    final style = Theme.of(context).textTheme.labelSmall!;
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
                color: colors.primary.withValues(alpha: on),
                shape: const StadiumBorder(),
              ),
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: _N.sortOptionPadH, vertical: _N.sortOptionPadV),
                child: Text(
                  widget.label,
                  style: style.copyWith(color: Color.lerp(colors.onSurfaceVariant, colors.onPrimary, on)),
                ),
              ),
            );
          },
        ),
      ),
    );
  }
}
