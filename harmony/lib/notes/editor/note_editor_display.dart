/// How the editor shows its text (Android ui/notes/editor/NoteEditorDisplay.kt
/// + NoteMarkupField's output transformation): each mention is one "@Title"
/// chip (a WidgetSpan on its U+FFFC: it never breaks inside, and a name
/// longer than the line ends in an ellipsis), a list marker's dash reads "•"
/// and a to-do's "☐" / "☑"; Markdown delimiters stay, quietly, and their
/// words are styled. Every character keeps its place, so the caret and the
/// IME see the field's own text.
library;

import 'package:flutter/material.dart';

import '../../data/notes/note_editor_text.dart';
import '../../data/notes/note_markup.dart';
import '../../theme/tokens.dart';

/// The editor's span colours (the chip's tint follows the host's palette).
@immutable
class NoteEditorColors {
  const NoteEditorColors({required this.text, required this.chip, required this.chipText});

  factory NoteEditorColors.of(BuildContext context, {Color? text, Color? chip, Color? chipText}) {
    final scheme = Theme.of(context).colorScheme;
    return NoteEditorColors(
      text: text ?? scheme.onSurface,
      chip: chip ?? scheme.secondaryContainer,
      chipText: chipText ?? scheme.onSecondaryContainer,
    );
  }

  final Color text;
  final Color chip;
  final Color chipText;

  Color get marker => text.withValues(alpha: NoteEditorTokens.markerAlpha);

  @override
  bool operator ==(Object other) =>
      other is NoteEditorColors && other.text == text && other.chip == chip && other.chipText == chipText;

  @override
  int get hashCode => Object.hash(text, chip, chipText);
}

const _bold = 1;
const _italic = 2;
const _heading = 4;
const _marker = 8;
const _done = 16;
const _composing = 32;
const _glyph = 64;

/// What one character of the editor's text draws as instead of itself.
sealed class _Shown {
  const _Shown();
}

final class _Chip extends _Shown {
  const _Chip(this.mention);

  final NoteMention mention;
}

final class _Glyph extends _Shown {
  const _Glyph(this.glyph);

  final String glyph;
}

/// Zero width (the rest of a to-do's "[ ]" behind its box).
final class _Hidden extends _Shown {
  const _Hidden();
}

/// [value]'s text as the editor shows it, in [style].
InlineSpan buildNoteEditorSpan({
  required TextEditingValue value,
  required List<EditorAtom> atoms,
  required TextStyle? style,
  required NoteEditorColors colors,
}) {
  final text = value.text;
  if (text.isEmpty) return TextSpan(style: style, text: '');
  final flags = List<int>.filled(text.length, 0);
  final shown = <int, _Shown>{};
  void mark(int from, int to, int flag) {
    for (var i = from.clamp(0, text.length); i < to.clamp(0, text.length); i++) {
      flags[i] |= flag;
    }
  }

  var atom = 0;
  for (var i = 0; i < text.length; i++) {
    if (text.codeUnitAt(i) != 0xFFFC) continue;
    final mention = atom < atoms.length ? atoms[atom].mention : null;
    if (mention != null) shown[i] = _Chip(mention);
    atom++;
  }

  for (final line in NoteMarkup.layout(text)) {
    switch (line.block) {
      case NoteBullet():
        shown[line.start] = const _Glyph('•');
        mark(line.start, line.contentStart, _glyph);
      case NoteChecklist(:final checked):
        shown[line.start] = _Glyph(checked ? '☑' : '☐');
        // "- [ ] ": the box stands on the dash; the brackets take no room.
        for (var i = line.start + 1; i < line.contentStart - 1; i++) {
          shown[i] = const _Hidden();
        }
        mark(line.start, line.contentStart, _glyph);
        if (checked) mark(line.contentStart, line.end, _done);
      case NoteHeading():
        mark(line.start, line.contentStart, _marker);
        mark(line.contentStart, line.end, _heading);
      case NoteParagraph():
        break;
    }
    for (final range in line.inlines) {
      switch (range.kind) {
        case InlineRangeKind.bold || InlineRangeKind.italic:
          mark(range.start, range.innerStart, _marker);
          mark(range.innerStart, range.innerEnd, range.kind == InlineRangeKind.bold ? _bold : _italic);
          mark(range.innerEnd, range.end, _marker);
        case InlineRangeKind.mention:
          break;
      }
    }
  }
  final composing = value.composing;
  if (composing.isValid && !composing.isCollapsed) mark(composing.start, composing.end, _composing);

  final base = style ?? const TextStyle();
  TextStyle styleFor(int f) {
    var s = const TextStyle();
    if (f & _bold != 0 || f & _heading != 0) s = s.merge(_weight(base, 700));
    if (f & _italic != 0) s = s.copyWith(fontStyle: FontStyle.italic);
    if (f & _heading != 0) s = s.copyWith(fontSize: (base.fontSize ?? 16) * NoteEditorTokens.headingScale);
    if (f & _marker != 0 || f & _done != 0) s = s.copyWith(color: colors.marker);
    if (f & _done != 0) s = s.copyWith(decoration: TextDecoration.lineThrough);
    if (f & _glyph != 0) s = s.merge(_weight(base, 700)).copyWith(color: colors.chipText);
    if (f & _composing != 0) {
      s = s.copyWith(
        decoration: f & _done != 0
            ? TextDecoration.combine([TextDecoration.lineThrough, TextDecoration.underline])
            : TextDecoration.underline,
      );
    }
    return s;
  }

  final children = <InlineSpan>[];
  var runStart = 0;
  void flush(int end) {
    if (end > runStart) children.add(TextSpan(text: text.substring(runStart, end), style: styleFor(flags[runStart])));
    runStart = end;
  }

  for (var i = 0; i < text.length; i++) {
    final s = shown[i];
    if (s != null) {
      flush(i);
      children.add(_placeholder(s, base.merge(styleFor(flags[i])), colors));
      runStart = i + 1;
      continue;
    }
    if (i > runStart && flags[i] != flags[runStart]) flush(i);
  }
  flush(text.length);
  return TextSpan(style: style, children: children);
}

WidgetSpan _placeholder(_Shown shown, TextStyle style, NoteEditorColors colors) => switch (shown) {
  _Chip(:final mention) => WidgetSpan(
    alignment: PlaceholderAlignment.middle,
    child: NoteEditorChip(mention: mention, style: style, colors: colors),
  ),
  _Glyph(:final glyph) => WidgetSpan(
    alignment: PlaceholderAlignment.baseline,
    baseline: TextBaseline.alphabetic,
    child: Text(glyph, style: style),
  ),
  _Hidden() => const WidgetSpan(child: SizedBox.shrink()),
};

/// One mention in the editor: "@Title" on the chip's tint, held on one line
/// (a name longer than the line ends in an ellipsis).
class NoteEditorChip extends StatelessWidget {
  const NoteEditorChip({super.key, required this.mention, required this.style, required this.colors});

  final NoteMention mention;
  final TextStyle style;
  final NoteEditorColors colors;

  @override
  Widget build(BuildContext context) => DecoratedBox(
    decoration: BoxDecoration(
      color: colors.chip,
      borderRadius: BorderRadius.circular(NoteEditorTokens.chipCorner),
    ),
    child: Padding(
      padding: const EdgeInsets.symmetric(horizontal: NoteEditorTokens.chipPadH),
      child: Text(
        '@${mention.title}',
        maxLines: 1,
        softWrap: false,
        overflow: TextOverflow.ellipsis,
        style: style.merge(_weight(style, 500)).copyWith(color: colors.chipText, decoration: TextDecoration.none),
      ),
    ),
  );
}

/// [base]'s font at [weight]: the weight and, for a variable font, its `wght` axis.
TextStyle _weight(TextStyle base, int weight) {
  final axes = base.fontVariations;
  return TextStyle(
    fontWeight: FontWeight.values.firstWhere((w) => w.value == weight, orElse: () => FontWeight.w700),
    fontVariations: axes == null
        ? null
        : [
            for (final axis in axes)
              if (axis.axis != 'wght') axis,
            FontVariation.weight(weight.toDouble()),
          ],
  );
}
