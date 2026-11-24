/// The note editor's text rules (Android ui/notes/editor/NoteEditorText.kt),
/// pure so they're unit-tested on their own.
///
/// Android edits the stored text and shows each mention link as one chip
/// through an output transformation. Flutter's field has no such mapping, so
/// here the editor's text holds one U+FFFC (the object replacement character
/// a WidgetSpan stands for) per mention, and an [EditorAtom] list says which
/// mention each one is: the caret steps over a chip and Backspace takes it
/// whole because it is one character. [NoteEditorText.encode] /
/// [NoteEditorText.decode] go between the stored text and the editor's, the
/// stored words byte for byte. Every other rule (the @ trigger, Enter picking
/// the popup's row, list continuation, the shortcuts, the formatting row)
/// reads the editor's text as Android reads the stored one.
library;

import 'package:flutter/foundation.dart';

import 'note_markup.dart';

/// The object replacement character a chip stands on.
const String objectReplacement = '￼';
const int _objectReplacement = 0xFFFC;

/// A text and its selection ([selectionStart]..[selectionEnd], a caret when equal).
@immutable
final class NoteEdit {
  const NoteEdit(this.text, this.selectionStart, [int? selectionEnd]) : selectionEnd = selectionEnd ?? selectionStart;

  final String text;
  final int selectionStart;
  final int selectionEnd;

  @override
  bool operator ==(Object other) =>
      other is NoteEdit &&
      other.text == text &&
      other.selectionStart == selectionStart &&
      other.selectionEnd == selectionEnd;

  @override
  int get hashCode => Object.hash(text, selectionStart, selectionEnd);

  @override
  String toString() => 'NoteEdit(${text.replaceAll('\n', r'\n')}, $selectionStart, $selectionEnd)';
}

/// An `@` being typed: the `@` at [start], the words after it up to the caret at [end].
@immutable
final class MentionTrigger {
  const MentionTrigger(this.start, this.end, this.query);

  final int start;
  final int end;
  final String query;

  @override
  bool operator ==(Object other) =>
      other is MentionTrigger && other.start == start && other.end == end && other.query == query;

  @override
  int get hashCode => Object.hash(start, end, query);

  @override
  String toString() => 'MentionTrigger($start, $end, $query)';
}

/// What the formatting row can do.
enum NoteTool { bold, italic, heading, bullet, checklist, mention }

/// One U+FFFC in the editor's text: a mention chip ([mention], stored as
/// [raw], its link exactly as written), or a U+FFFC the stored text itself
/// held ([mention] null, [raw] the character).
@immutable
final class EditorAtom {
  const EditorAtom(this.raw, this.mention);

  EditorAtom.of(NoteMention mention) : this(NoteMarkup.serializeMention(mention), mention);

  static const literal = EditorAtom(objectReplacement, null);

  final String raw;
  final NoteMention? mention;

  @override
  bool operator ==(Object other) => other is EditorAtom && other.raw == raw && other.mention == mention;

  @override
  int get hashCode => Object.hash(raw, mention);
}

/// The editor's text with its atoms.
@immutable
final class EditorText {
  const EditorText(this.text, this.atoms);

  final String text;
  final List<EditorAtom> atoms;
}

/// An edit to the editor's text: the text and selection, and its atoms.
@immutable
final class EditorEdit {
  const EditorEdit(this.edit, this.atoms);

  final NoteEdit edit;
  final List<EditorAtom> atoms;
}

abstract final class NoteEditorText {
  /// The longest query the @ popup follows; past it the `@` is just a character.
  static const maxQuery = 24;

  static const _at = 0x40;
  static const _fullWidthAt = 0xFF20;

  static bool _isTrigger(int c) => c == _at || c == _fullWidthAt;

  // ── stored text ⇄ the editor's ───────────────────────────────────────

  /// [stored] as the editor holds it: every mention link one U+FFFC.
  static EditorText encode(String stored) {
    final spans = NoteMarkup.mentionSpans(stored);
    final b = StringBuffer();
    final atoms = <EditorAtom>[];
    var at = 0;
    void plain(int end) {
      for (var i = at; i < end; i++) {
        final c = stored.codeUnitAt(i);
        if (c == _objectReplacement) atoms.add(EditorAtom.literal);
        b.writeCharCode(c);
      }
    }

    for (final span in spans) {
      plain(span.start);
      b.write(objectReplacement);
      atoms.add(EditorAtom(stored.substring(span.start, span.end), span.mention));
      at = span.end;
    }
    plain(stored.length);
    return EditorText(b.toString(), List.unmodifiable(atoms));
  }

  /// The stored text for the editor's [text] and its [atoms] (in order).
  static String decode(String text, List<EditorAtom> atoms) {
    if (atoms.isEmpty) return text.replaceAll(objectReplacement, '');
    final b = StringBuffer();
    var next = 0;
    for (var i = 0; i < text.length; i++) {
      final c = text.codeUnitAt(i);
      if (c == _objectReplacement) {
        if (next < atoms.length) b.write(atoms[next].raw);
        next++;
      } else {
        b.writeCharCode(c);
      }
    }
    return b.toString();
  }

  /// A stored offset ([offset] into [stored]) as one in the editor's text (inside a link: its end).
  static int toEditorOffset(String stored, int offset) {
    var shift = 0;
    for (final span in NoteMarkup.mentionSpans(stored)) {
      if (offset <= span.start) break;
      if (offset < span.end) return span.start - shift + 1;
      shift += span.end - span.start - 1;
    }
    return offset - shift;
  }

  /// [newText]'s atoms after an edit from [oldText] / [oldAtoms]: the atoms of
  /// what was removed go, a U+FFFC that came in takes the next of [incoming]
  /// (a mention the editor itself is inserting) or, with none, is dropped from
  /// the text (a pasted placeholder means nothing). [caret] (the new caret)
  /// settles where the change began when the characters around it repeat.
  static EditorEdit syncAtoms({
    required String oldText,
    required List<EditorAtom> oldAtoms,
    required NoteEdit edited,
    List<EditorAtom> incoming = const [],
  }) {
    final newText = edited.text;
    if (oldText == newText) return EditorEdit(edited, oldAtoms);
    EditorEdit? attempt(int? cap) {
      var prefix = 0;
      final maxPrefix = oldText.length < newText.length ? oldText.length : newText.length;
      while (prefix < maxPrefix && oldText.codeUnitAt(prefix) == newText.codeUnitAt(prefix)) {
        prefix++;
      }
      if (cap != null && cap >= 0 && cap < prefix) prefix = cap;
      var suffix = 0;
      while (suffix < maxPrefix - prefix &&
          oldText.codeUnitAt(oldText.length - 1 - suffix) == newText.codeUnitAt(newText.length - 1 - suffix)) {
        suffix++;
      }
      final before = _count(oldText, 0, prefix);
      final removed = _count(oldText, prefix, oldText.length - suffix);
      final insertedCount = _count(newText, prefix, newText.length - suffix);
      if (cap != null && insertedCount > incoming.length) return null;
      final queue = [...incoming];
      final insertedAtoms = <EditorAtom>[];
      final strip = <int>[];
      for (var i = prefix; i < newText.length - suffix; i++) {
        if (newText.codeUnitAt(i) != _objectReplacement) continue;
        if (queue.isNotEmpty) {
          insertedAtoms.add(queue.removeAt(0));
        } else {
          strip.add(i);
        }
      }
      final atoms = [
        ...oldAtoms.take(before),
        ...insertedAtoms,
        ...oldAtoms.skip((before + removed).clamp(0, oldAtoms.length)),
      ];
      if (strip.isEmpty) return EditorEdit(edited, List.unmodifiable(atoms));
      final b = StringBuffer();
      var from = 0;
      for (final i in strip) {
        b.write(newText.substring(from, i));
        from = i + 1;
      }
      b.write(newText.substring(from));
      int shift(int offset) => offset - strip.where((i) => i < offset).length;
      return EditorEdit(
        NoteEdit(b.toString(), shift(edited.selectionStart), shift(edited.selectionEnd)),
        List.unmodifiable(atoms),
      );
    }

    final collapsed = edited.selectionStart == edited.selectionEnd;
    return (collapsed ? attempt(edited.selectionStart) : null) ?? attempt(null)!;
  }

  static int _count(String text, int from, int to) {
    var n = 0;
    for (var i = from; i < to; i++) {
      if (text.codeUnitAt(i) == _objectReplacement) n++;
    }
    return n;
  }

  // ── @ ────────────────────────────────────────────────────────────────

  /// The `@` the caret is typing after, or null. The `@` (or `＠`) must start
  /// a word (line start, or after anything but an ASCII letter, digit, `_` or
  /// `.`: an e-mail address isn't a mention), the words after it hold no space
  /// and no other `@`, and no chip. A space inside the keyboard's composing
  /// range ([composingStart]..[composingEnd]) doesn't end it; a committed one does.
  static MentionTrigger? trigger(String text, int caret, {int? composingStart, int? composingEnd}) {
    if (caret < 1 || caret > text.length) return null;
    final lineStart = text.lastIndexOf('\n', caret - 1) + 1;
    var i = caret - 1;
    while (i >= lineStart && caret - i - 1 <= maxQuery) {
      final c = text.codeUnitAt(i);
      if (_isTrigger(c)) {
        if (i > lineStart) {
          final before = text.codeUnitAt(i - 1);
          if (_isAsciiWordChar(before) || before == 0x2E) return null;
        }
        return MentionTrigger(i, caret, text.substring(i + 1, caret));
      }
      final composing = composingStart != null && composingEnd != null && i >= composingStart && i < composingEnd;
      if ((_isWhitespace(c) && !composing) || c == 0x200B || c == 0x0A || c == _objectReplacement) return null;
      i--;
    }
    return null;
  }

  /// Whether the edit from [original] to [edited] is Enter at an open popup:
  /// it inserts a newline inside the `@` token or at its end and changes
  /// nothing past it (a bare newline; the composing word committed or
  /// corrected together with the newline; the word replaced by it; a newline
  /// next to one already after the token).
  static bool isEnterAtTrigger(String original, String edited, MentionTrigger trigger) {
    if (trigger.end > original.length || trigger.start >= trigger.end) return false;
    if (!_isTrigger(original.codeUnitAt(trigger.start))) return false;
    var prefix = 0;
    final maxPrefix = [original.length, edited.length, trigger.end].reduce((a, b) => a < b ? a : b);
    while (prefix < maxPrefix && original.codeUnitAt(prefix) == edited.codeUnitAt(prefix)) {
      prefix++;
    }
    final common = original.length < edited.length ? original.length : edited.length;
    var suffix = 0;
    while (suffix < common - prefix &&
        original.codeUnitAt(original.length - 1 - suffix) == edited.codeUnitAt(edited.length - 1 - suffix)) {
      suffix++;
    }
    final removedEnd = original.length - suffix;
    final inserted = edited.substring(prefix, edited.length - suffix);
    if (!inserted.contains('\n')) return false;
    return !(prefix <= trigger.start || prefix > trigger.end || removedEnd > trigger.end);
  }

  /// [mention]'s chip in place of [trigger] (or at the selection), followed by
  /// a space; caret after it. [atoms] are the editor's; the result's include the new one.
  static EditorEdit insertMention(
    String text,
    List<EditorAtom> atoms,
    int selectionStart,
    int selectionEnd,
    MentionTrigger? trigger,
    NoteMention mention,
  ) {
    final int from;
    final int to;
    if (trigger != null && trigger.end <= text.length && _isTrigger(text.codeUnitAt(trigger.start))) {
      from = trigger.start;
      to = trigger.end;
    } else {
      from = selectionStart < selectionEnd ? selectionStart : selectionEnd;
      to = selectionStart < selectionEnd ? selectionEnd : selectionStart;
    }
    final spacer = to < text.length && text.codeUnitAt(to) == 0x20 ? '' : ' ';
    final out = text.substring(0, from) + objectReplacement + spacer + text.substring(to);
    final caret = (from + 2).clamp(0, out.length);
    final before = _count(text, 0, from);
    final dropped = _count(text, from, to);
    final next = [...atoms.take(before), EditorAtom.of(mention), ...atoms.skip(before + dropped)];
    return EditorEdit(NoteEdit(out, caret), List.unmodifiable(next));
  }

  // ── keyboard edits ───────────────────────────────────────────────────

  /// The edit from [original] to [edited] corrected, or null when it stands
  /// as typed: Enter on a list item or to-do continues the list, on an empty
  /// one it leaves the list; a space after `[] `, `[ ] `, `【】 ` or `[x] `
  /// at a line start makes a to-do, after `＃` a heading. (A chip is atomic by
  /// being one character.)
  static NoteEdit? reconcile(String original, String edited) {
    if (original == edited) return null;
    var prefix = 0;
    final maxPrefix = original.length < edited.length ? original.length : edited.length;
    while (prefix < maxPrefix && original.codeUnitAt(prefix) == edited.codeUnitAt(prefix)) {
      prefix++;
    }
    var suffix = 0;
    while (suffix < maxPrefix - prefix &&
        original.codeUnitAt(original.length - 1 - suffix) == edited.codeUnitAt(edited.length - 1 - suffix)) {
      suffix++;
    }
    final removedEnd = original.length - suffix;
    final inserted = edited.substring(prefix, edited.length - suffix);
    if (removedEnd == prefix && inserted == '\n') {
      final fix = _continueList(original, prefix);
      if (fix != null) return fix;
    }
    if (removedEnd == prefix && inserted == ' ') {
      final fix = _blockShortcut(original, prefix);
      if (fix != null) return fix;
    }
    return null;
  }

  static NoteEdit? _continueList(String original, int at) {
    final start = at == 0 ? 0 : original.lastIndexOf('\n', at - 1) + 1;
    final nl = original.indexOf('\n', at);
    final lineEnd = nl < 0 ? original.length : nl;
    final line = original.substring(start, lineEnd);
    final marker = blockMarker(line);
    if (marker == null || marker.kind == NoteBlockKind.heading) return null;
    final content = line.substring(marker.length);
    if (content.trim().isEmpty && at == lineEnd) {
      final out = original.substring(0, start) + original.substring(start + marker.length);
      return NoteEdit(out, start);
    }
    final next = marker.kind == NoteBlockKind.checklist ? '${line[0]} [ ] ' : '${line[0]} ';
    final out = '${original.substring(0, at)}\n$next${original.substring(at)}';
    return NoteEdit(out, at + 1 + next.length);
  }

  static const _shortcuts = {
    '[]': '- [ ] ',
    '[ ]': '- [ ] ',
    '【】': '- [ ] ',
    '[x]': '- [x] ',
    '[X]': '- [x] ',
    '＃': '# ',
  };

  static NoteEdit? _blockShortcut(String original, int at) {
    final lineStart = at == 0 ? 0 : original.lastIndexOf('\n', at - 1) + 1;
    final replacement = _shortcuts[original.substring(lineStart, at)];
    if (replacement == null) return null;
    final out = original.substring(0, lineStart) + replacement + original.substring(at);
    return NoteEdit(out, lineStart + replacement.length);
  }

  // ── the formatting row ───────────────────────────────────────────────

  /// The formatting row's action on [text] with the given selection.
  static NoteEdit apply(NoteTool tool, String text, int selectionStart, int selectionEnd) {
    final from = (selectionStart < selectionEnd ? selectionStart : selectionEnd).clamp(0, text.length);
    final to = (selectionStart < selectionEnd ? selectionEnd : selectionStart).clamp(0, text.length);
    switch (tool) {
      case NoteTool.bold:
        return _wrap(text, from, to, '**');
      case NoteTool.italic:
        final before = from > 0 ? text.codeUnitAt(from - 1) : null;
        final after = to < text.length ? text.codeUnitAt(to) : null;
        final marker = (before != null && _isAsciiLetterOrDigit(before)) || (after != null && _isAsciiLetterOrDigit(after))
            ? '*'
            : '_';
        return _wrap(text, from, to, marker);
      case NoteTool.heading:
        return _setBlock(text, from, to, NoteBlockKind.heading);
      case NoteTool.bullet:
        return _setBlock(text, from, to, NoteBlockKind.bullet);
      case NoteTool.checklist:
        return _setBlock(text, from, to, NoteBlockKind.checklist);
      case NoteTool.mention:
        final before = from > 0 ? text.codeUnitAt(from - 1) : null;
        final at = before != null && (_isAsciiWordChar(before) || before == 0x2E) ? ' @' : '@';
        return NoteEdit(text.substring(0, from) + at + text.substring(to), from + at.length);
    }
  }

  /// Which block the line at [caret] is (for the row's pressed state).
  static NoteTool? blockAt(String text, int caret) {
    final at = caret.clamp(0, text.length);
    final lineStart = at == 0 ? 0 : text.lastIndexOf('\n', at - 1) + 1;
    final nl = text.indexOf('\n', at);
    return switch (blockMarker(text.substring(lineStart, nl < 0 ? text.length : nl))?.kind) {
      NoteBlockKind.heading => NoteTool.heading,
      NoteBlockKind.bullet => NoteTool.bullet,
      NoteBlockKind.checklist => NoteTool.checklist,
      null => null,
    };
  }

  /// Whether the caret / selection sits in bold or italic words (for the row's pressed state).
  static Set<NoteTool> inlineAt(String text, int selectionStart, int selectionEnd) {
    final from = selectionStart < selectionEnd ? selectionStart : selectionEnd;
    final to = selectionStart < selectionEnd ? selectionEnd : selectionStart;
    final out = <NoteTool>{};
    for (final line in NoteMarkup.layout(text)) {
      for (final range in line.inlines) {
        if (from >= range.innerStart && to <= range.innerEnd) {
          switch (range.kind) {
            case InlineRangeKind.bold:
              out.add(NoteTool.bold);
            case InlineRangeKind.italic:
              out.add(NoteTool.italic);
            case InlineRangeKind.mention:
              break;
          }
        }
      }
    }
    return out;
  }

  static NoteEdit _wrap(String text, int from, int to, String marker) {
    final n = marker.length;
    if (from >= n &&
        to + n <= text.length &&
        text.substring(from - n, from) == marker &&
        text.substring(to, to + n) == marker &&
        (marker != '*' || from - n - 1 < 0 || text[from - n - 1] != '*')) {
      final out = text.substring(0, from - n) + text.substring(from, to) + text.substring(to + n);
      return NoteEdit(out, from - n, to - n);
    }
    final out = text.substring(0, from) + marker + text.substring(from, to) + marker + text.substring(to);
    return NoteEdit(out, from + n, to + n);
  }

  static NoteEdit _setBlock(String text, int from, int to, NoteBlockKind kind) {
    final firstLine = from == 0 ? 0 : text.lastIndexOf('\n', from - 1) + 1;
    final nl = text.indexOf('\n', to);
    final lastLineEnd = nl < 0 ? text.length : nl;
    final lines = text.substring(firstLine, lastLineEnd).split('\n');
    final all = lines.every((l) => blockMarker(l)?.kind == kind);
    var delta = 0;
    var firstDelta = 0;
    final rebuilt = <String>[];
    for (final (index, line) in lines.indexed) {
      final marker = blockMarker(line);
      final content = marker == null ? line : line.substring(marker.length);
      final out = (all ? '' : kind.prefix) + content;
      final change = out.length - line.length;
      if (index == 0) firstDelta = change;
      delta += change;
      rebuilt.add(out);
    }
    final out = text.substring(0, firstLine) + rebuilt.join('\n') + text.substring(lastLineEnd);
    final start = (from + firstDelta).clamp(firstLine, out.length);
    final end = (to + delta).clamp(start, out.length);
    return NoteEdit(out, start, end);
  }

  /// The line's block marker as [NoteMarkup] reads it, or null for a paragraph.
  static BlockMarker? blockMarker(String line) => switch (NoteMarkup.parse(line).blocks.single) {
    NoteParagraph() => null,
    NoteHeading(:final level) => BlockMarker(NoteBlockKind.heading, level + 1),
    NoteBullet() => const BlockMarker(NoteBlockKind.bullet, 2),
    NoteChecklist() => BlockMarker(NoteBlockKind.checklist, line.length > 5 ? 6 : 5),
  };

  static bool _isAsciiLetterOrDigit(int c) =>
      (c >= 0x61 && c <= 0x7A) || (c >= 0x41 && c <= 0x5A) || (c >= 0x30 && c <= 0x39);

  static bool _isAsciiWordChar(int c) => _isAsciiLetterOrDigit(c) || c == 0x5F;

  /// Kotlin's Char.isWhitespace.
  static bool _isWhitespace(int c) =>
      (c >= 0x09 && c <= 0x0D) ||
      (c >= 0x1C && c <= 0x20) ||
      c == 0xA0 ||
      c == 0x1680 ||
      (c >= 0x2000 && c <= 0x200A) ||
      c == 0x2028 ||
      c == 0x2029 ||
      c == 0x202F ||
      c == 0x205F ||
      c == 0x3000;
}

/// The block kinds the formatting row sets.
enum NoteBlockKind {
  heading('# '),
  bullet('- '),
  checklist('- [ ] ');

  const NoteBlockKind(this.prefix);

  final String prefix;
}

/// A line's block marker and its length.
@immutable
final class BlockMarker {
  const BlockMarker(this.kind, this.length);

  final NoteBlockKind kind;
  final int length;

}
