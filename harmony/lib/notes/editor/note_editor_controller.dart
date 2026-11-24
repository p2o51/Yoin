/// One note editor's state (Android ui/notes/editor/NoteEditorState.kt): the
/// words, the @ being typed and the popup's highlighted row. The field's text
/// is the editor's (a mention is one U+FFFC chip, [NoteEditorText]); hosts
/// read and write the stored text through [storedText] / [setStoredText].
/// Keyboard edits pass [inputFormatter] (Enter at an open popup picks its row,
/// lists continue, the shortcuts land); every change keeps the chips' atoms in
/// step (a Backspace that takes a chip's character takes its mention).
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../data/notes/note_editor_text.dart';
import '../../data/notes/note_markup.dart';

class NoteEditorController extends TextEditingController {
  NoteEditorController({String stored = ''}) : this._(NoteEditorText.encode(stored));

  NoteEditorController._(EditorText initial)
    : _atoms = initial.atoms,
      super.fromValue(
        TextEditingValue(
          text: initial.text,
          selection: TextSelection.collapsed(offset: initial.text.length),
        ),
      );

  List<EditorAtom> _atoms;
  final List<EditorAtom> _incoming = [];
  int? _dismissedAt;

  /// Paints the field's text (chips, Markdown); set by the field.
  InlineSpan Function(BuildContext context, TextStyle? style, TextEditingValue value, List<EditorAtom> atoms)?
  spanBuilder;

  /// The popup's highlighted row (↑ ↓ move it, Enter picks it).
  int highlighted = 0;

  /// The row Enter picks while the popup is open; the field keeps it current, null while shut.
  NoteMention? enterPick;

  /// The chips' mentions, in order (a literal U+FFFC the stored text held is a null).
  List<EditorAtom> get atoms => _atoms;

  /// The stored text as it stands.
  String get storedText => NoteEditorText.decode(text, _atoms);

  /// Replaces the words (a draft cleared after saving, a note opened for editing); caret at the end.
  void setStoredText(String stored) {
    if (stored == storedText) {
      _dismissedAt = null;
      highlighted = 0;
      return;
    }
    final encoded = NoteEditorText.encode(stored);
    _atoms = encoded.atoms;
    _dismissedAt = null;
    highlighted = 0;
    super.value = TextEditingValue(
      text: encoded.text,
      selection: TextSelection.collapsed(offset: encoded.text.length),
    );
  }

  @override
  set value(TextEditingValue newValue) {
    final old = super.value;
    var next = newValue;
    if (old.text != newValue.text) {
      final sel = newValue.selection;
      final synced = NoteEditorText.syncAtoms(
        oldText: old.text,
        oldAtoms: _atoms,
        edited: NoteEdit(
          newValue.text,
          sel.isValid ? sel.start : newValue.text.length,
          sel.isValid ? sel.end : newValue.text.length,
        ),
        incoming: List.of(_incoming),
      );
      _incoming.clear();
      _atoms = synced.atoms;
      if (synced.edit.text != newValue.text) {
        next = TextEditingValue(
          text: synced.edit.text,
          selection: TextSelection(baseOffset: synced.edit.selectionStart, extentOffset: synced.edit.selectionEnd),
        );
      }
    }
    super.value = next;
    _syncDismissal();
  }

  // ---- @ ----

  /// The `@` being typed at the caret, dismissed or not (letters a keyboard is still composing count).
  MentionTrigger? get _typedTrigger {
    final sel = selection;
    if (!sel.isValid || !sel.isCollapsed) return null;
    final composing = value.composing;
    final hasComposing = composing.isValid && !composing.isCollapsed;
    return NoteEditorText.trigger(
      text,
      sel.start,
      composingStart: hasComposing ? composing.start : null,
      composingEnd: hasComposing ? composing.end : null,
    );
  }

  /// The `@` being typed at the caret, unless dismissed.
  MentionTrigger? get trigger {
    final typed = _typedTrigger;
    return typed == null || typed.start == _dismissedAt ? null : typed;
  }

  /// The words after the caret's `@` the popup searches for (empty right after the `@`).
  String? get query => trigger?.query;

  /// Esc, a tap away: the popup stays shut until another `@`.
  void dismissPopup() {
    final at = trigger?.start;
    if (at == null) return;
    _dismissedAt = at;
    notifyListeners();
  }

  void _syncDismissal() {
    final at = _dismissedAt;
    if (at != null && _typedTrigger?.start != at) _dismissedAt = null;
  }

  /// Inserts [mention] in place of the `@` being typed (or at the caret).
  void insertMention(NoteMention mention) {
    final sel = selection;
    final start = sel.isValid ? sel.start : text.length;
    final end = sel.isValid ? sel.end : text.length;
    final edit = NoteEditorText.insertMention(text, _atoms, start, end, trigger, mention);
    _atoms = edit.atoms;
    highlighted = 0;
    super.value = TextEditingValue(
      text: edit.edit.text,
      selection: TextSelection.collapsed(offset: edit.edit.selectionStart),
    );
  }

  // ---- the formatting row ----

  void apply(NoteTool tool) {
    final sel = selection;
    final start = sel.isValid ? sel.start : text.length;
    final end = sel.isValid ? sel.end : text.length;
    final edit = NoteEditorText.apply(tool, text, start, end);
    if (tool == NoteTool.mention) _dismissedAt = null;
    value = TextEditingValue(
      text: edit.text,
      selection: TextSelection(baseOffset: edit.selectionStart, extentOffset: edit.selectionEnd),
    );
  }

  /// Which tools read as pressed at the caret.
  Set<NoteTool> get activeTools {
    final sel = selection;
    if (!sel.isValid) return const {};
    final block = NoteEditorText.blockAt(text, sel.start);
    return {...NoteEditorText.inlineAt(text, sel.start, sel.end), ?block};
  }

  // ---- keyboard edits ----

  /// The field's formatter: Enter at an open popup picks [enterPick], else the editor's rules.
  late final TextInputFormatter inputFormatter = TextInputFormatter.withFunction(_format);

  TextEditingValue _format(TextEditingValue oldValue, TextEditingValue newValue) {
    if (oldValue.text == newValue.text) return newValue;
    final pick = enterPick;
    final at = trigger;
    if (pick != null && at != null && NoteEditorText.isEnterAtTrigger(oldValue.text, newValue.text, at)) {
      final edit = NoteEditorText.insertMention(oldValue.text, const [], at.end, at.end, at, pick);
      // The setter adopts the new chip's mention from the queue.
      _incoming.add(edit.atoms.firstWhere((a) => a.mention == pick));
      highlighted = 0;
      return TextEditingValue(
        text: edit.edit.text,
        selection: TextSelection.collapsed(offset: edit.edit.selectionStart),
      );
    }
    final fix = NoteEditorText.reconcile(oldValue.text, newValue.text);
    if (fix == null) return newValue;
    return TextEditingValue(
      text: fix.text,
      selection: TextSelection(baseOffset: fix.selectionStart, extentOffset: fix.selectionEnd),
    );
  }

  @override
  TextSpan buildTextSpan({required BuildContext context, TextStyle? style, required bool withComposing}) {
    final builder = spanBuilder;
    if (builder == null) return super.buildTextSpan(context: context, style: style, withComposing: withComposing);
    final shown = withComposing ? value : value.copyWith(composing: TextRange.empty);
    final span = builder(context, style, shown, _atoms);
    return span is TextSpan ? span : TextSpan(style: style, children: [span]);
  }
}
