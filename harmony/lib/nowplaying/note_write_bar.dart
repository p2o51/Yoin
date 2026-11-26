import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../notes/editor/note_format_toolbar.dart';
import '../notes/editor/note_markup_field.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import 'haptics.dart';
import 'note_controller.dart';
import 'note_line.dart';
import 'note_logic.dart';
import 'nowplaying_strings.dart';
import 'playhead_clock.dart';
import 'share_content.dart';
import 'share_sheet.dart';

typedef _N = NowPlayingNoteTokens;

/// Now Playing's one note writer (Android NoteWriteBar), docked in the
/// Expanded Note page's accessory slot. At rest a 56 capsule: the live
/// playhead in a stamp, "Write a note". Writing (focus, or words waiting) it
/// opens into a card that grows with the words (2–6 lines, then it scrolls
/// inside), corners 28 → 20 on the spatial spring. The stamp is the moment
/// the note will be filed at; tapping it aligns that moment to now, and once
/// words are down and the playhead has wandered ≥ 2 s a realign glyph slides
/// in — no hint line. Save files the note and keeps the keyboard up for the
/// next one. The keyboard going down over an empty draft returns the capsule.
///
/// The bar takes the height its slot gives it and reports its natural height
/// through [onNaturalHeight] (the slot springs to it); one field instance
/// throughout, so the morph never drops focus.
class NoteWriteBar extends StatefulWidget {
  const NoteWriteBar({super.key, required this.notes, required this.clock, this.onNaturalHeight});

  final NowPlayingNotes notes;
  final PlayheadClock clock;
  final ValueChanged<double>? onNaturalHeight;

  @override
  State<NoteWriteBar> createState() => _NoteWriteBarState();
}

class _NoteWriteBarState extends State<NoteWriteBar> with TickerProviderStateMixin {
  late final SpringValue _corner = SpringValue(
    vsync: this,
    initial: widget.notes.open ? _N.barOpenCorner : _N.barIdleCorner,
    spring: YoinSprings.defaultSpatial,
  );
  bool _imeShown = false;
  double _reported = -1;

  NowPlayingNotes get _notes => widget.notes;

  @override
  void initState() {
    super.initState();
    _notes.addListener(_onNotes);
  }

  @override
  void didUpdateWidget(NoteWriteBar oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.notes != widget.notes) {
      oldWidget.notes.removeListener(_onNotes);
      widget.notes.addListener(_onNotes);
    }
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // Keyboard dismissed (back, swipe) over an empty draft → back to the capsule.
    final imeVisible = MediaQuery.viewInsetsOf(context).bottom > 0;
    if (!_notes.focused) {
      _imeShown = false;
    } else if (imeVisible) {
      _imeShown = true;
    } else {
      if (shouldCollapseNoteWriteBar(
            imeWasShown: _imeShown,
            imeVisible: imeVisible,
            draftEmpty: _notes.draft.text.isEmpty,
          ) ||
          // An edit put away unchanged closes (the bar returns to its capsule, the note as it was).
          (_imeShown && _notes.draft.isUntouchedEdit)) {
        WidgetsBinding.instance.addPostFrameCallback((_) => _notes.focus.unfocus());
      }
      _imeShown = false;
    }
  }

  @override
  void dispose() {
    _notes.removeListener(_onNotes);
    _corner.dispose();
    super.dispose();
  }

  void _onNotes() {
    if (!mounted) return;
    _corner.animateTo(_notes.open ? _N.barOpenCorner : _N.barIdleCorner);
    setState(() {});
  }

  void _report(Size size) {
    if (size.height == _reported) return;
    _reported = size.height;
    widget.onNaturalHeight?.call(size.height);
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final strings = NowPlayingStrings.of(context);
    final open = _notes.open;
    final draft = _notes.draft;
    final hasWords = draft.text.trim().isNotEmpty;
    final canSave = draft.canSave;
    // Share as image (Android NoteWriteBar onShareEditing): only inside Now Playing.
    final share = NowPlayingShareScope.maybeOf(context);
    final shareId = draft.isUntouchedEdit ? draft.editingNoteId : null;
    final field = NoteMarkupField(
      controller: _notes.text,
      focusNode: _notes.focus,
      minLines: open ? _N.barOpenMinLines : 1,
      maxLines: open ? _N.barOpenMaxLines : 1,
      style: text.bodyLarge!.copyWith(color: colors.onSurface),
      cursorColor: colors.primary,
      mentionPopup: open,
      // A click / tap away from the bar over an empty draft puts the field
      // down (no soft keyboard to dismiss on a 2in1 with its keyboard on).
      onTapOutside: (_) {
        if (_notes.focused && _notes.draft.text.trim().isEmpty) _notes.focus.unfocus();
      },
      decoration: InputDecoration.collapsed(
        hintText: strings.notePlaceholder,
        hintStyle: text.bodyLarge!.copyWith(color: colors.onSurfaceVariant.withValues(alpha: 0.8)),
      ),
    );
    final content = Padding(
      padding: EdgeInsetsDirectional.only(
        start: _N.barPadStart,
        end: _N.barPadEnd,
        top: _N.barPadV,
        bottom: _N.barPadV,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            crossAxisAlignment: open ? CrossAxisAlignment.start : CrossAxisAlignment.center,
            children: [
              _StampChip(
                open: open,
                anchorMs: draft.anchorMs,
                hasWords: hasWords,
                clock: widget.clock,
                onTap: open ? _notes.realignDraft : _notes.focus.requestFocus,
              ),
              const SizedBox(width: _N.barItemGap),
              Expanded(
                child: Padding(
                  // The first line sits centred on the stamp.
                  padding: EdgeInsets.only(
                    top: open
                        ? ((_N.stampChipHeight - (text.bodyLarge!.fontSize! * (text.bodyLarge!.height ?? 1.25))) / 2)
                              .clamp(0.0, _N.stampChipHeight)
                        : 0,
                  ),
                  child: field,
                ),
              ),
              if (!open) ...[
                const SizedBox(width: _N.barItemGap),
                Icon(YoinSymbols.edit, size: _N.barWriteGlyph, color: colors.onSurfaceVariant),
              ] else if (share != null)
                // The open card's top-right corner, beside the first line: a note opened for editing and not yet
                // changed offers Share there — never in the formatting row, whose tools keep their room.
                _NoteShareSlot(
                  visible: shareId != null,
                  label: strings.shareSheetTitle,
                  onTap: () {
                    final id = shareId;
                    if (id == null) return;
                    YoinHaptics.contextClick();
                    // The keyboard going closes the unchanged edit (NowPlayingNotes), then the sheet opens on it.
                    _notes.focus.unfocus();
                    share(ShareRequest(ShareTab.note, noteId: id));
                  },
                ),
            ],
          ),
          if (open)
            Padding(
              padding: const EdgeInsetsDirectional.only(top: _N.barFooterGap, start: _N.barFooterInset),
              child: Row(
                children: [
                  // The formatting row beside Save (Android NoteWriteBar + NoteFormatToolbar).
                  Expanded(
                    child: SingleChildScrollView(
                      scrollDirection: Axis.horizontal,
                      child: NoteFormatToolbar(controller: _notes.text),
                    ),
                  ),
                  const SizedBox(width: _N.barItemGap),
                  _SavePill(enabled: canSave, label: strings.noteSave, onTap: _notes.save),
                ],
              ),
            ),
        ],
      ),
    );
    // The whole bar (stamp, Save) counts as inside the field for onTapOutside.
    return TextFieldTapRegion(
      child: Semantics(
        textField: !open,
        label: open ? null : strings.notePlaceholder,
        child: GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: open ? null : _notes.focus.requestFocus,
          child: AnimatedBuilder(
            animation: _corner.animation,
            builder: (context, child) => ClipRRect(
              borderRadius: BorderRadius.circular(_corner.value),
              child: ColoredBox(
                color: colors.surfaceContainerHigh.withValues(alpha: _N.barVeilAlpha),
                child: child,
              ),
            ),
            // However short the slot (mid-spring), the card keeps its natural
            // layout; the clip reveals it as the slot grows.
            child: OverflowBox(
              alignment: Alignment.topCenter,
              minHeight: 0,
              maxHeight: double.infinity,
              child: _SizeReporter(onSize: _report, child: content),
            ),
          ),
        ),
      ),
    );
  }
}

/// The bar's first column: the live playhead at rest, the draft's moment
/// while writing (with a realign glyph once the playhead has drifted ≥ 2 s
/// from it and words are down). Reads the clock, rebuilding once a second.
class _StampChip extends StatefulWidget {
  const _StampChip({
    required this.open,
    required this.anchorMs,
    required this.hasWords,
    required this.clock,
    required this.onTap,
  });

  final bool open;
  final int? anchorMs;
  final bool hasWords;
  final PlayheadClock clock;
  final VoidCallback onTap;

  @override
  State<_StampChip> createState() => _StampChipState();
}

class _StampChipState extends State<_StampChip> {
  late int _second = widget.clock.positionMs() ~/ 1000;

  @override
  void initState() {
    super.initState();
    widget.clock.addListener(_onTick);
  }

  @override
  void didUpdateWidget(_StampChip oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.clock != widget.clock) {
      oldWidget.clock.removeListener(_onTick);
      widget.clock.addListener(_onTick);
    }
  }

  @override
  void dispose() {
    widget.clock.removeListener(_onTick);
    super.dispose();
  }

  void _onTick() {
    final second = widget.clock.positionMs() ~/ 1000;
    if (second != _second) setState(() => _second = second);
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final strings = NowPlayingStrings.of(context);
    final style = noteWeighted(
      Theme.of(context).textTheme.labelLarge!,
      600,
    ).copyWith(color: colors.primary, fontFeatures: const [FontFeature.tabularFigures()]);
    final anchor = widget.anchorMs;
    final showAnchor = widget.open && anchor != null;
    final drifted = showAnchor && widget.hasWords && showNoteRealignHint(anchor ~/ 1000 * 1000, _second * 1000);
    return Semantics(
      button: true,
      label: widget.open ? strings.noteStampRealign : strings.noteStampWrite,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: widget.onTap,
        child: Container(
          height: _N.stampChipHeight,
          padding: const EdgeInsets.symmetric(horizontal: _N.stampChipPadH),
          decoration: ShapeDecoration(
            color: colors.primary.withValues(alpha: _N.stampChipTintAlpha),
            shape: const StadiumBorder(),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (drifted) ...[
                Icon(YoinSymbols.refresh, size: _N.stampChipGlyph, color: colors.primary),
                const SizedBox(width: _N.stampChipGlyphGap),
              ],
              ExcludeSemantics(child: Text(formatNotePosition(showAnchor ? anchor : _second * 1000), style: style)),
            ],
          ),
        ),
      ),
    );
  }
}

/// The capsule Save pill (Android JournalSavePill + NoteSavePill): primary
/// when there are words, a quiet surface otherwise, on the effects spring.
class _SavePill extends StatefulWidget {
  const _SavePill({required this.enabled, required this.label, required this.onTap});

  final bool enabled;
  final String label;
  final VoidCallback onTap;

  @override
  State<_SavePill> createState() => _SavePillState();
}

class _SavePillState extends State<_SavePill> with TickerProviderStateMixin {
  late final SpringValue _on = SpringValue(
    vsync: this,
    initial: widget.enabled ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );

  @override
  void didUpdateWidget(_SavePill oldWidget) {
    super.didUpdateWidget(oldWidget);
    _on.animateTo(widget.enabled ? 1 : 0);
  }

  @override
  void dispose() {
    _on.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final style = Theme.of(context).textTheme.labelMedium!;
    return Semantics(
      button: true,
      enabled: widget.enabled,
      label: widget.label,
      excludeSemantics: true,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: widget.enabled ? widget.onTap : null,
        child: AnimatedBuilder(
          animation: _on.animation,
          builder: (context, _) {
            final on = _on.value.clamp(0.0, 1.0);
            final content = Color.lerp(colors.onSurfaceVariant.withValues(alpha: 0.5), colors.onPrimary, on)!;
            return DecoratedBox(
              decoration: ShapeDecoration(
                color: Color.lerp(colors.surfaceContainerHighest, colors.primary, on),
                shape: const StadiumBorder(),
              ),
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: _N.savePillPadH, vertical: _N.savePillPadV),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(YoinSymbols.send, size: _N.savePillGlyph, color: content),
                    const SizedBox(width: _N.savePillGap),
                    Text(widget.label, style: style.copyWith(color: content)),
                  ],
                ),
              ),
            );
          },
        ),
      ),
    );
  }
}

/// Reports its child's laid-out size after layout.
class _SizeReporter extends SingleChildRenderObjectWidget {
  const _SizeReporter({required this.onSize, required super.child});

  final ValueChanged<Size> onSize;

  @override
  RenderObject createRenderObject(BuildContext context) => _RenderSizeReporter(onSize);

  @override
  void updateRenderObject(BuildContext context, _RenderSizeReporter renderObject) => renderObject.onSize = onSize;
}

class _RenderSizeReporter extends RenderProxyBox {
  _RenderSizeReporter(this.onSize);

  ValueChanged<Size> onSize;
  Size? _last;

  @override
  void performLayout() {
    super.performLayout();
    if (size != _last) {
      _last = size;
      final reported = size;
      WidgetsBinding.instance.addPostFrameCallback((_) => onSize(reported));
    }
  }
}

/// The open card's Share key (a note opened for editing, not yet changed):
/// it slides in and out on the spatial spring, fading on the effects spring.
class _NoteShareSlot extends StatefulWidget {
  const _NoteShareSlot({required this.visible, required this.label, required this.onTap});

  final bool visible;
  final String label;
  final VoidCallback onTap;

  @override
  State<_NoteShareSlot> createState() => _NoteShareSlotState();
}

class _NoteShareSlotState extends State<_NoteShareSlot> with TickerProviderStateMixin {
  late final SpringValue _width = SpringValue(
    vsync: this,
    initial: widget.visible ? 1 : 0,
    spring: YoinSprings.standardDefaultSpatial,
  );
  late final SpringValue _alpha = SpringValue(
    vsync: this,
    initial: widget.visible ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );

  @override
  void didUpdateWidget(_NoteShareSlot oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.visible == widget.visible) return;
    final target = widget.visible ? 1.0 : 0.0;
    if (MediaQuery.disableAnimationsOf(context)) {
      _width.snapTo(target);
      _alpha.animateTo(target);
    } else {
      _width.animateTo(target);
      _alpha.animateTo(target);
    }
  }

  @override
  void dispose() {
    _width.dispose();
    _alpha.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return AnimatedBuilder(
      animation: Listenable.merge([_width.animation, _alpha.animation]),
      builder: (context, child) {
        final w = _width.value.clamp(0.0, 1.0);
        if (w <= 0.001 && _alpha.value <= 0.01) return const SizedBox.shrink();
        return ClipRect(
          child: Align(
            alignment: AlignmentDirectional.centerEnd,
            widthFactor: w,
            child: Opacity(opacity: _alpha.value.clamp(0.0, 1.0), child: child),
          ),
        );
      },
      child: Padding(
        padding: const EdgeInsetsDirectional.only(start: _N.barItemGap),
        child: SizedBox.square(
          dimension: _N.stampChipHeight,
          child: IconButton(
            key: const ValueKey('note-share'),
            tooltip: widget.label,
            padding: EdgeInsets.zero,
            onPressed: widget.visible ? widget.onTap : null,
            icon: Icon(YoinSymbols.share, size: _N.barWriteGlyph, color: colors.onSurfaceVariant),
          ),
        ),
      ),
    );
  }
}
