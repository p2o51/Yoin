/// Note text as Yoin draws it (Android ui/component/NoteMarkupText.kt):
/// headings, list items, to-dos, bold, italic and mentions, from the stored
/// Markdown ([NoteMarkup]). A mention shows its name: in the mention colour,
/// a touch heavier and tappable when it resolves for the account in use;
/// as plain words in the text's own colour when it doesn't (owner
/// 2026-11-22: not grey). Read-only: the write fields edit the stored text
/// as it is.
library;

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/notes/note_markup.dart';
import 'note_mentions.dart';

/// Glyphs the read-only text draws for list items and to-dos.
abstract final class NoteMarkupGlyphs {
  static const bullet = '•  ';
  static const todo = '☐  ';
  static const done = '☑  ';
}

/// The mention's weight (Android noteMentionStyle: SemiBold).
const int noteMentionWeight = 600;
const int _boldWeight = 700;

/// [document] as one span. A resolved mention ([isResolved]) wears
/// [mentionColor] at [noteMentionWeight], with [recognizerFor]'s recognizer
/// when it gives one; an unresolved one is plain text. [base] is the style the
/// span is drawn in (its size and font axes set headings' and bold's).
TextSpan buildNoteSpan(
  NoteDocument document, {
  required TextStyle base,
  required Color mentionColor,
  required Color glyphColor,
  bool Function(NoteMention mention)? isResolved,
  GestureRecognizer? Function(NoteMention mention)? recognizerFor,
}) {
  List<InlineSpan> inlines(List<NoteInline> nodes) => [
    for (final node in nodes)
      switch (node) {
        NoteText(:final text) => TextSpan(text: text),
        NoteBold(:final children) => TextSpan(style: _weighted(base, _boldWeight), children: inlines(children)),
        NoteItalic(:final children) => TextSpan(
          style: const TextStyle(fontStyle: FontStyle.italic),
          children: inlines(children),
        ),
        NoteMentionInline(:final mention) =>
          (isResolved?.call(mention) ?? true)
              ? TextSpan(
                  text: mention.title,
                  style: _weighted(base, noteMentionWeight).copyWith(color: mentionColor),
                  recognizer: recognizerFor?.call(mention),
                )
              : TextSpan(text: mention.title),
      },
  ];

  final glyph = TextStyle(color: glyphColor);
  final spans = <InlineSpan>[];
  for (final (index, block) in document.blocks.indexed) {
    if (index > 0) spans.add(const TextSpan(text: '\n'));
    switch (block) {
      case NoteParagraph():
        spans.addAll(inlines(block.inlines));
      case NoteHeading(:final level):
        final size = (base.fontSize ?? 14) * (level == 1 ? 1.15 : 1.05);
        spans.add(
          TextSpan(
            style: _weighted(base, _boldWeight).copyWith(fontSize: size),
            children: inlines(block.inlines),
          ),
        );
      case NoteBullet():
        spans
          ..add(const TextSpan(text: NoteMarkupGlyphs.bullet))
          ..addAll(inlines(block.inlines));
      case NoteChecklist(:final checked):
        spans
          ..add(TextSpan(text: checked ? NoteMarkupGlyphs.done : NoteMarkupGlyphs.todo, style: glyph))
          ..add(
            TextSpan(
              style: checked ? const TextStyle(decoration: TextDecoration.lineThrough) : null,
              children: inlines(block.inlines),
            ),
          );
    }
  }
  return TextSpan(children: spans);
}

/// [base]'s font at [weight]: the weight and, for a variable font, its `wght` axis.
TextStyle _weighted(TextStyle base, int weight) {
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

/// A note, album note or review drawn from its stored text. Without
/// [isMentionResolved] the account in use decides ([noteMentionsProvider]), and
/// a resolved mention opens what it names unless [interactive] is false.
class NoteMarkupText extends ConsumerStatefulWidget {
  const NoteMarkupText(
    this.text, {
    super.key,
    this.style,
    this.mentionColor,
    this.maxLines,
    this.overflow,
    this.textAlign,
    this.interactive = true,
    this.isMentionResolved,
    this.onMentionTap,
  });

  final String text;
  final TextStyle? style;

  /// Defaults to the scheme's primary (Android NoteMarkupText).
  final Color? mentionColor;
  final int? maxLines;
  final TextOverflow? overflow;
  final TextAlign? textAlign;
  final bool interactive;
  final bool Function(NoteMention mention)? isMentionResolved;

  /// Replaces opening the mention (tests, previews).
  final void Function(NoteMention mention)? onMentionTap;

  @override
  ConsumerState<NoteMarkupText> createState() => _NoteMarkupTextState();
}

class _NoteMarkupTextState extends ConsumerState<NoteMarkupText> {
  late NoteDocument _document = NoteMarkup.parse(widget.text);
  final List<TapGestureRecognizer> _recognizers = [];

  @override
  void didUpdateWidget(NoteMarkupText oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.text != widget.text) _document = NoteMarkup.parse(widget.text);
  }

  @override
  void dispose() {
    _disposeRecognizers();
    super.dispose();
  }

  void _disposeRecognizers() {
    for (final r in _recognizers) {
      r.dispose();
    }
    _recognizers.clear();
  }

  void _open(NoteMention mention) {
    final tap = widget.onMentionTap;
    if (tap != null) {
      tap(mention);
      return;
    }
    ref.read(noteMentionsProvider.notifier).open(context, mention);
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final hasMentions = _document.mentions.isNotEmpty;
    bool Function(NoteMention)? resolved = widget.isMentionResolved;
    if (hasMentions && resolved == null) {
      // Redraw when an answer lands or the account changes.
      ref.watch(noteMentionsProvider);
      ref.watch(activeSourceProvider);
      resolved = ref.read(noteMentionsProvider.notifier).isResolved;
    }
    _disposeRecognizers();
    final base = DefaultTextStyle.of(context).style.merge(widget.style);
    final span = buildNoteSpan(
      _document,
      base: base,
      mentionColor: widget.mentionColor ?? scheme.primary,
      glyphColor: scheme.primary,
      isResolved: resolved,
      recognizerFor: widget.interactive
          ? (mention) {
              final recognizer = TapGestureRecognizer()..onTap = () => _open(mention);
              _recognizers.add(recognizer);
              return recognizer;
            }
          : null,
    );
    return Text.rich(
      span,
      style: widget.style,
      maxLines: widget.maxLines,
      overflow: widget.overflow,
      textAlign: widget.textAlign,
    );
  }
}
