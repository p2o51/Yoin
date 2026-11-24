/// The note editor's field (Android ui/notes/editor/NoteMarkupField.kt): the
/// editor's text shown through [buildNoteEditorSpan] (chips, list glyphs,
/// styled Markdown) and edited through [NoteEditorController]. Typing `@` (or
/// `＠`) opens [MentionPopup]: what's playing and recent plays at once, each
/// letter filtering what's known straight away, then the account's search
/// ranked in ([mentionRowsWhileSearching] / [mentionRowsAfterSearch]). ↑ ↓ /
/// Enter / Tab / Esc on a keyboard; a soft keyboard's Enter picks the top (or
/// highlighted) row. The popup never takes focus and a tap on it is a tap in
/// the field; a tap anywhere else, the caret leaving the `@` word, the field
/// losing focus, Esc or a pick end mention mode. The host draws the frame.
library;

import 'dart:async';

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/notes/mention_ranking.dart';
import '../../data/notes/note_markup.dart';
import '../../nowplaying/haptics.dart';
import '../../theme/tokens.dart';
import 'mention_candidates_source.dart';
import 'mention_popup.dart';
import 'note_editor_controller.dart';
import 'note_editor_display.dart';

class NoteMarkupField extends ConsumerStatefulWidget {
  const NoteMarkupField({
    super.key,
    required this.controller,
    this.focusNode,
    this.style,
    this.colors,
    this.decoration = const InputDecoration.collapsed(hintText: null),
    this.minLines,
    this.maxLines,
    this.keyboardType = TextInputType.multiline,
    this.textCapitalization = TextCapitalization.sentences,
    this.cursorColor,
    this.autofocus = false,
    this.enabled = true,
    this.onTapOutside,
    this.candidates,
    this.mentionPopup = true,
    this.romanizer = PinyinMentionRomanizer.romanize,
  });

  final NoteEditorController controller;
  final FocusNode? focusNode;
  final TextStyle? style;
  final NoteEditorColors? colors;
  final InputDecoration decoration;
  final int? minLines;
  final int? maxLines;
  final TextInputType keyboardType;
  final TextCapitalization textCapitalization;
  final Color? cursorColor;
  final bool autofocus;
  final bool enabled;

  /// A tap outside the field (and its popup): mention mode has already ended.
  final TapRegionCallback? onTapOutside;

  /// Defaults to the account in use ([mentionCandidatesProvider]).
  final MentionCandidateSource? candidates;

  /// False: no @ popup.
  final bool mentionPopup;
  final MentionRomanizer romanizer;

  @override
  ConsumerState<NoteMarkupField> createState() => _NoteMarkupFieldState();
}

class _NoteMarkupFieldState extends ConsumerState<NoteMarkupField> {
  final OverlayPortalController _portal = OverlayPortalController();
  final GlobalKey _fieldKey = GlobalKey();
  FocusNode? _ownFocus;
  MentionCandidates _rows = MentionCandidates.empty;
  MentionCandidates _pool = MentionCandidates.empty;
  MentionCandidateSource? _poolSource;
  bool _loading = false;
  bool _searchOpen = false;
  String? _query;
  int _generation = 0;

  FocusNode get _focus => widget.focusNode ?? (_ownFocus ??= FocusNode(debugLabel: 'noteMarkupField'));

  NoteEditorController get _controller => widget.controller;

  @override
  void initState() {
    super.initState();
    _controller.addListener(_onChange);
    _focus.addListener(_onFocus);
  }

  @override
  void didUpdateWidget(NoteMarkupField oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != widget.controller) {
      oldWidget.controller.removeListener(_onChange);
      widget.controller.addListener(_onChange);
    }
    if (oldWidget.focusNode != widget.focusNode) {
      (oldWidget.focusNode ?? _ownFocus)?.removeListener(_onFocus);
      _focus.addListener(_onFocus);
    }
  }

  @override
  void dispose() {
    _controller.removeListener(_onChange);
    _focus.removeListener(_onFocus);
    _ownFocus?.dispose();
    _controller.enterPick = null;
    super.dispose();
  }

  void _onFocus() {
    if (_focus.hasFocus) {
      // The pinyin tables parse once, before the first `@` needs them.
      WidgetsBinding.instance.addPostFrameCallback((_) => PinyinMentionRomanizer.warmUp());
    }
    if (mounted) setState(() {});
  }

  void _onChange() {
    if (!mounted) return;
    final query = _controller.query;
    if (query != _query) {
      _query = query;
      unawaited(_lookUp(query));
    }
    setState(() {});
  }

  MentionCandidateSource get _source => widget.candidates ?? ref.read(mentionCandidatesProvider);

  Future<void> _lookUp(String? query) async {
    final generation = ++_generation;
    if (query == null) return;
    final source = _source;
    if (!identical(source, _poolSource)) {
      _poolSource = source;
      _pool = MentionCandidates.empty;
    }
    setState(() => _loading = true);
    if (query.isNotEmpty) {
      setState(() {
        _rows = mentionRowsWhileSearching(query, _pool, widget.romanizer);
        _controller.highlighted = 0;
      });
      await Future<void>.delayed(const Duration(milliseconds: NoteEditorTokens.queryDebounceMs));
      if (!mounted || generation != _generation) return;
    }
    final found = await source.candidates(query, mentionPopupRowsPerKind);
    if (!mounted || generation != _generation) return;
    setState(() {
      _pool = _pool.merge(found);
      _rows = mentionRowsAfterSearch(query, found, _pool, widget.romanizer);
      _controller.highlighted = 0;
      _loading = false;
    });
  }

  bool get _popupOpen =>
      widget.mentionPopup && widget.enabled && _controller.trigger != null && _focus.hasFocus && !_searchOpen;

  void _pick(NoteMention mention) {
    YoinHaptics.confirm();
    _controller.insertMention(mention);
  }

  void _move(int delta) {
    final all = _rows.all;
    if (all.isEmpty) return;
    setState(() => _controller.highlighted = (_controller.highlighted + delta + all.length) % all.length);
  }

  void _pickHighlighted() {
    final row = _rows.all.elementAtOrNull(_controller.highlighted);
    if (row != null) _pick(row.mention);
  }

  Future<void> _expand() async {
    YoinHaptics.tick();
    final trigger = _controller.trigger;
    setState(() => _searchOpen = true);
    final picked = await showMentionSearchSheet(context, initialQuery: trigger?.query ?? '', candidates: _source);
    if (!mounted) return;
    setState(() => _searchOpen = false);
    if (picked != null) {
      _focus.requestFocus();
      _pick(picked);
    }
  }

  void _tapOutside(PointerDownEvent event) {
    _controller.dismissPopup();
    final host = widget.onTapOutside;
    if (host != null) {
      host(event);
    } else if (event.kind == PointerDeviceKind.mouse) {
      _focus.unfocus();
    }
  }

  /// The field's and the caret's `@` rects in [overlay]'s coordinates.
  (Rect, Rect)? _anchor(RenderBox overlay) {
    final box = _fieldKey.currentContext?.findRenderObject() as RenderBox?;
    if (box == null || !box.attached || !box.hasSize) return null;
    final field = MatrixUtils.transformRect(box.getTransformTo(overlay), Offset.zero & box.size);
    final editable = _findEditable(box);
    final trigger = _controller.trigger;
    if (editable == null || trigger == null) return (field, field);
    final local = editable.getLocalRectForCaret(TextPosition(offset: trigger.start));
    final caret = MatrixUtils.transformRect(editable.getTransformTo(overlay), local);
    return (field, caret);
  }

  static RenderEditable? _findEditable(RenderObject root) {
    if (root is RenderEditable) return root;
    RenderEditable? found;
    root.visitChildren((child) => found ??= _findEditable(child));
    return found;
  }

  Widget _overlay(BuildContext overlayContext) {
    final overlay = Overlay.of(overlayContext).context.findRenderObject() as RenderBox?;
    final trigger = _controller.trigger;
    if (overlay == null || trigger == null) return const SizedBox.shrink();
    final anchor = _anchor(overlay);
    if (anchor == null) return const SizedBox.shrink();
    final (field, caret) = anchor;
    // The window's own status bar and keyboard (not the MediaQuery here: a Scaffold body or a nested
    // navigator above this overlay may have taken the keyboard's inset out), in the overlay's coordinates.
    final (topLimit, bottomLimit) = mentionPopupLimits(MediaQueryData.fromView(View.of(overlayContext)), overlay);
    return Positioned.fill(
      child: CustomSingleChildLayout(
        delegate: MentionPopupLayout(field: field, caret: caret, topLimit: topLimit, bottomLimit: bottomLimit),
        // A touch on the popup is a touch in the field: it keeps focus and @ mode.
        child: TextFieldTapRegion(
          child: MentionPopup(
            rows: _rows,
            loading: _loading,
            query: trigger.query,
            highlighted: _controller.highlighted,
            onPick: _pick,
            onExpand: _expand,
            listMaxHeight: mentionPopupListMax(
              caretTop: caret.top,
              caretBottom: caret.bottom,
              topLimit: topLimit,
              bottomLimit: bottomLimit,
            ),
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = widget.colors ?? NoteEditorColors.of(context, text: widget.style?.color);
    _controller.spanBuilder = (context, style, value, atoms) =>
        buildNoteEditorSpan(value: value, atoms: atoms, style: style, colors: colors);
    final open = _popupOpen;
    // What a soft keyboard's Enter picks (its newline becomes this mention).
    _controller.enterPick = open ? _rows.all.elementAtOrNull(_controller.highlighted)?.mention : null;
    if (open != _portal.isShowing) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) return;
        _popupOpen ? _portal.show() : _portal.hide();
      });
    }
    final field = TextField(
      key: _fieldKey,
      controller: _controller,
      focusNode: _focus,
      enabled: widget.enabled,
      autofocus: widget.autofocus,
      minLines: widget.minLines,
      maxLines: widget.maxLines,
      keyboardType: widget.keyboardType,
      textCapitalization: widget.textCapitalization,
      style: widget.style?.copyWith(color: colors.text) ?? TextStyle(color: colors.text),
      cursorColor: widget.cursorColor ?? theme.colorScheme.primary,
      inputFormatters: [_controller.inputFormatter],
      onTapOutside: _tapOutside,
      decoration: widget.decoration,
    );
    return Shortcuts(
      shortcuts: open
          ? const {
              SingleActivator(LogicalKeyboardKey.arrowDown): _MoveIntent(1),
              SingleActivator(LogicalKeyboardKey.arrowUp): _MoveIntent(-1),
              SingleActivator(LogicalKeyboardKey.enter): _PickIntent(),
              SingleActivator(LogicalKeyboardKey.numpadEnter): _PickIntent(),
              SingleActivator(LogicalKeyboardKey.tab): _PickIntent(),
              SingleActivator(LogicalKeyboardKey.escape): _DismissIntent(),
            }
          : const <ShortcutActivator, Intent>{},
      child: Actions(
        actions: {
          _MoveIntent: CallbackAction<_MoveIntent>(onInvoke: (i) => _move(i.delta)),
          _PickIntent: CallbackAction<_PickIntent>(onInvoke: (_) => _pickHighlighted()),
          _DismissIntent: CallbackAction<_DismissIntent>(onInvoke: (_) => _controller.dismissPopup()),
        },
        child: OverlayPortal(controller: _portal, overlayChildBuilder: _overlay, child: field),
      ),
    );
  }
}

/// The status bar's bottom and the keyboard's top in [overlay]'s coordinates, from the window's own
/// insets ([window]: [MediaQueryData.fromView]).
(double, double) mentionPopupLimits(MediaQueryData window, RenderBox overlay) {
  final top = overlay.globalToLocal(Offset(0, window.padding.top)).dy;
  final bottom = overlay.globalToLocal(Offset(0, window.size.height - window.viewInsets.bottom)).dy;
  return (top.clamp(0.0, overlay.size.height), bottom.clamp(0.0, overlay.size.height));
}

class _MoveIntent extends Intent {
  const _MoveIntent(this.delta);

  final int delta;
}

class _PickIntent extends Intent {
  const _PickIntent();
}

class _DismissIntent extends Intent {
  const _DismissIntent();
}
