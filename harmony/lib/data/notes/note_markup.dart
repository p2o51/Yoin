/// Yoin's note text: a pure-Dart port of `:shared` `notes/NoteMarkup.kt`
/// (notes revamp, owner-approved prototype 2026-11-22). Song notes, album
/// notes and album reviews stay plain text, with light Markdown and mentions,
/// so the text reads as text anywhere (sync, backups, NeoDB, an older app) and
/// renders where Yoin understands it.
///
///     # Heading            (also ## / ###)
///     - item               (also "* " and "• ")
///     - [ ] to-do / - [x] done
///     **bold**, _italic_ (or *italic*)
///     [@Title](yoin:<kind>/<provider>:<id>?a=<artist>&al=<album>)
///
/// Anything that doesn't parse stays text; nothing is ever dropped, and
/// reading never rewrites stored text. Offsets are UTF-16 code units, as in
/// Kotlin. Checked against the Kotlin by the shared golden vectors
/// (`shared/src/commonTest/resources/golden/note-markup.json`,
/// `test/data/notes/note_markup_golden_test.dart`): change both together.
library;

import 'dart:convert';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';

/// What a mention points at.
enum MentionKind {
  track('track'),
  album('album'),
  artist('artist');

  const MentionKind(this.token);

  final String token;

  /// "song" (the prototype's spelling) reads as [track].
  static MentionKind? fromToken(String token) => switch (token.toLowerCase()) {
    'track' || 'song' => track,
    'album' => album,
    'artist' => artist,
    _ => null,
  };
}

/// A mention's identity, without its display words.
@immutable
final class MentionRef {
  const MentionRef(this.kind, this.provider, this.id);

  final MentionKind kind;
  final String provider;
  final String id;

  /// `provider:id`, MediaId's string form.
  String get mediaId => '$provider:$id';

  @override
  bool operator ==(Object other) =>
      other is MentionRef && other.kind == kind && other.provider == provider && other.id == id;

  @override
  int get hashCode => Object.hash(kind, provider, id);

  @override
  String toString() => 'MentionRef(${kind.token}, $mediaId)';
}

/// One mention: the item ([kind], [provider], [id]) and the words it was
/// written with. [title] is the name shown; [artist] and [album] are the
/// name-matching fallback when the id means nothing to the account reading it.
@immutable
final class NoteMention {
  NoteMention({
    required this.kind,
    required this.provider,
    required this.id,
    required this.title,
    this.artist,
    this.album,
  }) {
    if (!isValidProvider(provider)) throw ArgumentError.value(provider, 'provider', 'Invalid mention provider');
    if (id.isEmpty) throw ArgumentError.value(id, 'id', 'Mention id must not be empty');
  }

  final MentionKind kind;
  final String provider;
  final String id;
  final String title;
  final String? artist;
  final String? album;

  MentionRef get ref => MentionRef(kind, provider, id);

  /// `provider:id`, MediaId's string form.
  String get mediaId => '$provider:$id';

  NoteMention copyWith({MentionKind? kind, String? provider, String? id, String? title}) => NoteMention(
    kind: kind ?? this.kind,
    provider: provider ?? this.provider,
    id: id ?? this.id,
    title: title ?? this.title,
    artist: artist,
    album: album,
  );

  /// A MediaId provider token: letters, digits, `.`, `_`, `-`.
  static bool isValidProvider(String provider) {
    if (provider.isEmpty) return false;
    for (var i = 0; i < provider.length; i++) {
      final c = provider.codeUnitAt(i);
      if (!(isLetterOrDigit(c) || c == _dot || c == _underscore || c == _dash)) return false;
    }
    return true;
  }

  @override
  bool operator ==(Object other) =>
      other is NoteMention &&
      other.kind == kind &&
      other.provider == provider &&
      other.id == id &&
      other.title == title &&
      other.artist == artist &&
      other.album == album;

  @override
  int get hashCode => Object.hash(kind, provider, id, title, artist, album);

  @override
  String toString() => 'NoteMention(${kind.token}, $mediaId, $title, $artist, $album)';
}

/// Inline content of one line.
sealed class NoteInline {
  const NoteInline();
}

/// Literal characters (never empty in a parsed document).
final class NoteText extends NoteInline {
  const NoteText(this.text);

  final String text;

  @override
  bool operator ==(Object other) => other is NoteText && other.text == text;

  @override
  int get hashCode => text.hashCode;

  @override
  String toString() => 'Text($text)';
}

/// `**…**`.
final class NoteBold extends NoteInline {
  const NoteBold(this.children);

  final List<NoteInline> children;

  @override
  bool operator ==(Object other) => other is NoteBold && listEquals(other.children, children);

  @override
  int get hashCode => Object.hashAll(children);

  @override
  String toString() => 'Bold($children)';
}

/// `_…_` or `*…*` ([marker] says which).
final class NoteItalic extends NoteInline {
  const NoteItalic(this.children, {this.marker = '_'});

  final List<NoteInline> children;
  final String marker;

  @override
  bool operator ==(Object other) =>
      other is NoteItalic && other.marker == marker && listEquals(other.children, children);

  @override
  int get hashCode => Object.hash(marker, Object.hashAll(children));

  @override
  String toString() => 'Italic($marker, $children)';
}

final class NoteMentionInline extends NoteInline {
  const NoteMentionInline(this.mention);

  final NoteMention mention;

  @override
  bool operator ==(Object other) => other is NoteMentionInline && other.mention == mention;

  @override
  int get hashCode => mention.hashCode;

  @override
  String toString() => 'Mention($mention)';
}

/// One line. Lines are separated by `\n`; an empty line is an empty [NoteParagraph].
sealed class NoteBlock {
  const NoteBlock(this.inlines);

  final List<NoteInline> inlines;
}

final class NoteParagraph extends NoteBlock {
  const NoteParagraph(super.inlines);

  @override
  bool operator ==(Object other) => other is NoteParagraph && listEquals(other.inlines, inlines);

  @override
  int get hashCode => Object.hashAll(inlines);
}

/// `# `, `## ` or `### ` ([level] 1–3).
final class NoteHeading extends NoteBlock {
  const NoteHeading(this.level, List<NoteInline> inlines) : super(inlines);

  final int level;

  @override
  bool operator ==(Object other) => other is NoteHeading && other.level == level && listEquals(other.inlines, inlines);

  @override
  int get hashCode => Object.hash(level, Object.hashAll(inlines));
}

/// `- `, `* ` or `• ` ([marker]).
final class NoteBullet extends NoteBlock {
  const NoteBullet(super.inlines, {this.marker = '-'});

  final String marker;

  @override
  bool operator ==(Object other) => other is NoteBullet && other.marker == marker && listEquals(other.inlines, inlines);

  @override
  int get hashCode => Object.hash(marker, Object.hashAll(inlines));
}

/// `- [ ] ` / `- [x] ` ([upperX]: written `[X]`).
final class NoteChecklist extends NoteBlock {
  const NoteChecklist(this.checked, List<NoteInline> inlines, {this.marker = '-', this.upperX = false})
    : super(inlines);

  final bool checked;
  final String marker;
  final bool upperX;

  @override
  bool operator ==(Object other) =>
      other is NoteChecklist &&
      other.checked == checked &&
      other.marker == marker &&
      other.upperX == upperX &&
      listEquals(other.inlines, inlines);

  @override
  int get hashCode => Object.hash(checked, marker, upperX, Object.hashAll(inlines));
}

@immutable
final class NoteDocument {
  const NoteDocument(this.blocks);

  static const empty = NoteDocument([NoteParagraph([])]);

  final List<NoteBlock> blocks;

  /// Every mention, in reading order.
  List<NoteMention> get mentions => [for (final block in blocks) ..._mentionsOf(block.inlines)];

  @override
  bool operator ==(Object other) => other is NoteDocument && listEquals(other.blocks, blocks);

  @override
  int get hashCode => Object.hashAll(blocks);
}

enum InlineRangeKind { bold, italic, mention }

/// One inline token's source range ([start]..[end]) and its words' range inside the delimiters.
@immutable
final class InlineRange {
  const InlineRange(this.kind, this.start, this.end, this.innerStart, this.innerEnd);

  final InlineRangeKind kind;
  final int start;
  final int end;
  final int innerStart;
  final int innerEnd;

  InlineRange _shift(int by) => InlineRange(kind, start + by, end + by, innerStart + by, innerEnd + by);
}

/// One line as [NoteMarkup.layout] reads it.
@immutable
final class LineLayout {
  const LineLayout({
    required this.block,
    required this.start,
    required this.end,
    required this.contentStart,
    required this.inlines,
  });

  final NoteBlock block;
  final int start;
  final int end;

  /// After the block marker (`- `, `# `, `- [ ] `).
  final int contentStart;
  final List<InlineRange> inlines;
}

/// Where one mention sits in a text: `text.substring(start, end)` is its link.
@immutable
final class MentionSpan {
  const MentionSpan(this.start, this.end, this.mention);

  final int start;
  final int end;
  final NoteMention mention;
}

/// A one-line snippet and where each mention's name sits in it.
@immutable
final class Snippet {
  const Snippet(this.text, this.mentions);

  final String text;
  final List<MentionSpan> mentions;
}

abstract final class NoteMarkup {
  /// The cheap test before parsing: text without it has no mention.
  static const mentionNeedle = '](yoin:';

  static NoteDocument parse(String text) => NoteDocument([for (final line in text.split('\n')) _parseLine(line)]);

  static String serialize(NoteDocument document) => document.blocks.map(_serializeBlock).join('\n');

  static String serializeInlines(List<NoteInline> inlines) {
    final out = _Out();
    _appendInlines(inlines, out);
    return out.toString();
  }

  /// The canonical link for [mention].
  static String serializeMention(NoteMention mention) {
    final out = StringBuffer()
      ..write('[@')
      ..write(_escapeTitle(mention.title))
      ..write('](yoin:')
      ..write(mention.kind.token)
      ..write('/')
      ..write(mention.provider)
      ..write(':')
      ..write(PercentCoding.encode(mention.id, keep: ':'));
    var separator = '?';
    final artist = mention.artist;
    if (artist != null && !isBlank(artist)) {
      out
        ..write(separator)
        ..write('a=')
        ..write(PercentCoding.encode(artist));
      separator = '&';
    }
    final album = mention.album;
    if (album != null && !isBlank(album)) {
      out
        ..write(separator)
        ..write('al=')
        ..write(PercentCoding.encode(album));
    }
    out.write(')');
    return out.toString();
  }

  static bool hasMentions(String? text) => text != null && _indexOfNeedle(text) >= 0;

  /// Every mention in [text], in order.
  static List<NoteMention> mentions(String? text) => [for (final span in mentionSpans(text)) span.mention];

  /// Every mention link in [text] and where it sits. Finds exactly the mentions [parse] finds.
  static List<MentionSpan> mentionSpans(String? text) {
    if (text == null || !hasMentions(text)) return const [];
    final out = <MentionSpan>[];
    var i = 0;
    var lineEnd = -1;
    while (i < text.length - 1) {
      if (i > lineEnd) {
        final nl = text.indexOf('\n', i);
        lineEnd = nl < 0 ? text.length : nl;
      }
      if (text.codeUnitAt(i) == _openBracket && text.codeUnitAt(i + 1) == _at) {
        final match = _MentionSyntax.match(text, i, lineEnd);
        if (match != null) {
          out.add(MentionSpan(i, match.end, match.mention));
          i = match.end;
          continue;
        }
      }
      i++;
    }
    return out;
  }

  /// [text] with each mention replaced by what [transform] returns for it
  /// (serialized canonically); null keeps the link exactly as written.
  static String rewriteMentions(String text, NoteMention? Function(NoteMention) transform) =>
      _replaceMentions(text, (span) {
        final replacement = transform(span.mention);
        return replacement == null || replacement == span.mention ? null : serializeMention(replacement);
      });

  /// [rewriteMentions] by identity: mentions whose ref is in [mapping] point at the mapped item, words kept.
  static String rewriteMentionRefs(String text, Map<MentionRef, MentionRef> mapping) {
    if (mapping.isEmpty) return text;
    return rewriteMentions(text, (mention) {
      final to = mapping[mention.ref];
      return to == null ? null : mention.copyWith(kind: to.kind, provider: to.provider, id: to.id);
    });
  }

  /// What NeoDB gets: the text as written, each mention flattened to its name.
  static String toNeoDb(String text) => _replaceMentions(text, (span) => span.mention.title);

  /// The words alone, for search, previews, accessibility: mentions as their
  /// names, no Markdown marks, lines joined by [lineSeparator].
  static String plainText(String text, {String lineSeparator = '\n'}) {
    if (!_mayHaveMarkup(text)) return lineSeparator == '\n' ? text : text.replaceAll('\n', lineSeparator);
    return plainTextOf(parse(text), lineSeparator: lineSeparator);
  }

  static String plainTextOf(NoteDocument document, {String lineSeparator = '\n'}) =>
      document.blocks.map((block) => plainInlines(block.inlines)).join(lineSeparator);

  /// One line for a clipping: the non-blank lines' words joined by " · ".
  static String snippet(String text) =>
      plainTextOf(parse(text)).split('\n').map(trimKotlin).where((line) => line.isNotEmpty).join(' · ');

  /// [snippet] with where each mention's name landed in it.
  static Snippet snippetWithMentions(String text) {
    final out = StringBuffer();
    final marks = <MentionSpan>[];
    for (final block in parse(text).blocks) {
      final line = StringBuffer();
      final lineMarks = <MentionSpan>[];
      void walk(List<NoteInline> nodes) {
        for (final node in nodes) {
          switch (node) {
            case NoteText(:final text):
              line.write(text);
            case NoteBold(:final children):
              walk(children);
            case NoteItalic(:final children):
              walk(children);
            case NoteMentionInline(:final mention):
              final start = line.length;
              line.write(mention.title);
              lineMarks.add(MentionSpan(start, line.length, mention));
          }
        }
      }

      walk(block.inlines);
      final lineText = line.toString();
      var lead = -1;
      for (var i = 0; i < lineText.length; i++) {
        if (!isWhitespace(lineText.codeUnitAt(i))) {
          lead = i;
          break;
        }
      }
      if (lead < 0) continue;
      final trimmed = trimKotlin(lineText);
      if (out.isNotEmpty) out.write(' · ');
      final base = out.length - lead;
      out.write(trimmed);
      for (final mark in lineMarks) {
        final start = math.max(base + mark.start, base + lead);
        final end = math.min(base + mark.end, out.length);
        if (end > start) marks.add(MentionSpan(start, end, mark.mention));
      }
    }
    return Snippet(out.toString(), marks);
  }

  static String plainInlines(List<NoteInline> inlines) {
    final out = StringBuffer();
    void walk(List<NoteInline> nodes) {
      for (final node in nodes) {
        switch (node) {
          case NoteText(:final text):
            out.write(text);
          case NoteBold(:final children):
            walk(children);
          case NoteItalic(:final children):
            walk(children);
          case NoteMentionInline(:final mention):
            out.write(mention.title);
        }
      }
    }

    walk(inlines);
    return out.toString();
  }

  /// Where each line's block marker and inline tokens sit in [text] (absolute offsets).
  static List<LineLayout> layout(String text) {
    final out = <LineLayout>[];
    var lineStart = 0;
    for (final line in text.split('\n')) {
      final block = _parseLine(line);
      final markerLength = switch (block) {
        NoteParagraph() => 0,
        NoteHeading(:final level) => level + 1,
        NoteBullet() => 2,
        NoteChecklist() => line.length > 5 ? 6 : 5,
      };
      final ranges = <InlineRange>[];
      final content = line.substring(markerLength);
      if (content.isNotEmpty) _InlineParser(content, ranges).parse(0, content.length);
      final shift = lineStart + markerLength;
      out.add(
        LineLayout(
          block: block,
          start: lineStart,
          end: lineStart + line.length,
          contentStart: shift,
          inlines: [for (final r in ranges) r._shift(shift)],
        ),
      );
      lineStart += line.length + 1;
    }
    return out;
  }

  /// [text] with the to-do on line [lineIndex] ticked or unticked; every
  /// other character as written. Not a to-do line (or out of range): unchanged.
  static String toggleChecklist(String text, int lineIndex) {
    final lines = text.split('\n');
    if (lineIndex < 0 || lineIndex >= lines.length) return text;
    final line = lines[lineIndex];
    final block = _parseLine(line);
    if (block is! NoteChecklist) return text;
    final mark = block.checked ? ' ' : 'x';
    lines[lineIndex] = line.substring(0, 3) + mark + line.substring(4);
    return lines.join('\n');
  }

  // ── internals ─────────────────────────────────────────────────────

  static const _markupChars = '[*_#-•';

  static bool _mayHaveMarkup(String text) {
    for (var i = 0; i < text.length; i++) {
      if (_markupChars.contains(text[i])) return true;
    }
    return false;
  }

  /// `contains(MENTION_NEEDLE, ignoreCase = true)` as Kotlin compares chars:
  /// equal, or equal upper-cased, or equal lower-cased (so `İ` and `ı` match `i`).
  static int _indexOfNeedle(String text) {
    const needle = mentionNeedle;
    for (var i = 0; i + needle.length <= text.length; i++) {
      if (_regionMatchesIgnoreCase(text, i, needle)) return i;
    }
    return -1;
  }

  static String _replaceMentions(String text, String? Function(MentionSpan) replacement) {
    final spans = mentionSpans(text);
    if (spans.isEmpty) return text;
    final out = StringBuffer();
    var last = 0;
    for (final span in spans) {
      final replaced = replacement(span);
      if (replaced == null) continue;
      out
        ..write(text.substring(last, span.start))
        ..write(replaced);
      last = span.end;
    }
    if (last == 0) return text;
    out.write(text.substring(last));
    return out.toString();
  }

  static bool _isBulletMarker(int c) => c == _dash || c == _star || c == _bulletDot;

  static NoteBlock _parseLine(String line) {
    final n = line.length;
    if (n >= 5 &&
        _isBulletMarker(line.codeUnitAt(0)) &&
        line.codeUnitAt(1) == _space &&
        line.codeUnitAt(2) == _openBracket &&
        line.codeUnitAt(4) == _closeBracket &&
        (line.codeUnitAt(3) == _space || line[3] == 'x' || line[3] == 'X') &&
        (n == 5 || line.codeUnitAt(5) == _space)) {
      return NoteChecklist(
        line.codeUnitAt(3) != _space,
        _parseInlines(n > 6 ? line.substring(6) : ''),
        marker: line[0],
        upperX: line[3] == 'X',
      );
    }
    if (n >= 2 && _isBulletMarker(line.codeUnitAt(0)) && line.codeUnitAt(1) == _space) {
      return NoteBullet(_parseInlines(line.substring(2)), marker: line[0]);
    }
    if (n >= 2 && line.codeUnitAt(0) == _hash) {
      var level = 0;
      while (level < n && line.codeUnitAt(level) == _hash) {
        level++;
      }
      if (level <= 3 && level < n && line.codeUnitAt(level) == _space) {
        return NoteHeading(level, _parseInlines(line.substring(level + 1)));
      }
    }
    return NoteParagraph(_parseInlines(line));
  }

  static List<NoteInline> _parseInlines(String content) =>
      content.isEmpty ? const [] : _InlineParser(content).parse(0, content.length);

  static String _serializeBlock(NoteBlock block) {
    final out = _Out();
    switch (block) {
      case NoteParagraph():
        break;
      case NoteHeading(:final level):
        out.write('#' * level.clamp(1, 3));
        out.write(' ');
      case NoteBullet(:final marker):
        out.write(_bulletOr(marker));
        out.write(' ');
      case NoteChecklist(:final marker, :final checked, :final upperX):
        out.write(_bulletOr(marker));
        out.write(' [');
        out.write(!checked ? ' ' : (upperX ? 'X' : 'x'));
        out.write('] ');
    }
    _appendInlines(block.inlines, out);
    return out.toString();
  }

  static String _bulletOr(String marker) => marker.length == 1 && _isBulletMarker(marker.codeUnitAt(0)) ? marker : '-';

  static void _appendInlines(List<NoteInline> inlines, _Out out) {
    for (var index = 0; index < inlines.length; index++) {
      final node = inlines[index];
      switch (node) {
        case NoteText(:final text):
          out.write(text);
        case NoteBold(:final children):
          out.write('**');
          _appendInlines(children, out);
          out.write('**');
        case NoteItalic(:final children, marker: final requested):
          // `_` against an ASCII letter or digit doesn't read as italic; `*`
          // does (except against a digit), so it's the fallback spelling.
          final before = out.last;
          final after = index + 1 < inlines.length ? _firstChar(inlines[index + 1]) : null;
          final underscoreReads = !italicBlockedBy(before, _underscore) && !italicBlockedBy(after, _underscore);
          final marker = requested == '_' && underscoreReads ? '_' : '*';
          out.write(marker);
          _appendInlines(children, out);
          out.write(marker);
        case NoteMentionInline(:final mention):
          out.write(serializeMention(mention));
      }
    }
  }

  /// `[` and `]` always take a backslash; a backslash only where it would
  /// otherwise read as one (before `\`, `[`, `]`, or last).
  static String _escapeTitle(String title) {
    final out = StringBuffer();
    for (var i = 0; i < title.length; i++) {
      final c = title.codeUnitAt(i);
      if (c == _openBracket || c == _closeBracket) {
        out
          ..writeCharCode(_backslash)
          ..writeCharCode(c);
      } else if (c == _backslash) {
        final next = i + 1 < title.length ? title.codeUnitAt(i + 1) : null;
        if (next == null || _isTitleEscapable(next)) out.writeCharCode(_backslash);
        out.writeCharCode(c);
      } else if (c == _newline || c == _carriageReturn) {
        out.writeCharCode(_space);
      } else {
        out.writeCharCode(c);
      }
    }
    return out.toString();
  }
}

/// Whether [neighbour] (the code unit before an opening or after a closing
/// [marker]) keeps [marker] from reading as italic. ASCII only, so CJK text
/// italicises without spaces: `_` refuses ASCII letters and digits, `*`
/// refuses ASCII digits; neither sits against its own kind.
bool italicBlockedBy(int? neighbour, int marker) {
  if (neighbour == null) return false;
  if (neighbour == marker) return true;
  if (marker == _underscore) return _isAsciiLetterOrDigit(neighbour);
  return neighbour >= 0x30 && neighbour <= 0x39;
}

/// Kotlin's `Char.isWhitespace()` on the JVM: Unicode space separators, line
/// and paragraph separators, and the controls U+0009–U+000D, U+001C–U+001F.
bool isWhitespace(int c) =>
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

/// Kotlin's `isBlank()`.
bool isBlank(String s) {
  for (var i = 0; i < s.length; i++) {
    if (!isWhitespace(s.codeUnitAt(i))) return false;
  }
  return true;
}

/// Kotlin's `trim()` (whitespace by [isWhitespace], not Dart's set).
String trimKotlin(String s) {
  var start = 0;
  var end = s.length;
  while (start < end && isWhitespace(s.codeUnitAt(start))) {
    start++;
  }
  while (end > start && isWhitespace(s.codeUnitAt(end - 1))) {
    end--;
  }
  return s.substring(start, end);
}

final _letterOrDigit = RegExp(r'^[\p{L}\p{Nd}]$', unicode: true);

/// Kotlin's `Char.isLetterOrDigit()` on one UTF-16 code unit (a lone surrogate is neither).
bool isLetterOrDigit(int c) {
  if (_isAsciiLetterOrDigit(c)) return true;
  if (c < 0x80 || (c >= 0xD800 && c <= 0xDFFF)) return false;
  return _letterOrDigit.hasMatch(String.fromCharCode(c));
}

bool _isAsciiLetterOrDigit(int c) => (c >= 0x61 && c <= 0x7A) || (c >= 0x41 && c <= 0x5A) || (c >= 0x30 && c <= 0x39);

const _space = 0x20;
const _newline = 0x0A;
const _carriageReturn = 0x0D;
const _openBracket = 0x5B;
const _closeBracket = 0x5D;
const _openParen = 0x28;
const _closeParen = 0x29;
const _backslash = 0x5C;
const _at = 0x40;
const _star = 0x2A;
const _underscore = 0x5F;
const _hash = 0x23;
const _dash = 0x2D;
const _dot = 0x2E;
const _bulletDot = 0x2022;
const _percent = 0x25;
const _question = 0x3F;
const _slash = 0x2F;
const _colon = 0x3A;

/// Kotlin's `Char.equals(other, ignoreCase = true)` for [text] at [offset]
/// against an ASCII-lowercase [needle]. Besides ASCII case, Kotlin folds
/// `İ` (lowercases to `i`) and `ı` (uppercases to `I`) onto `i`.
bool _regionMatchesIgnoreCase(String text, int offset, String needle) {
  if (offset < 0 || offset + needle.length > text.length) return false;
  for (var k = 0; k < needle.length; k++) {
    final c = text.codeUnitAt(offset + k);
    final n = needle.codeUnitAt(k);
    if (c == n) continue;
    if (n >= 0x61 && n <= 0x7A && c == n - 0x20) continue;
    if (n == 0x69 && (c == 0x130 || c == 0x131)) continue;
    return false;
  }
  return true;
}

bool _isTitleEscapable(int c) => c == _backslash || c == _openBracket || c == _closeBracket;

int? _firstChar(NoteInline node) => switch (node) {
  NoteText(:final text) => text.isEmpty ? null : text.codeUnitAt(0),
  NoteBold() => _star,
  NoteItalic(:final marker) => marker.codeUnitAt(0),
  NoteMentionInline() => _openBracket,
};

List<NoteMention> _mentionsOf(List<NoteInline> nodes) => [
  for (final node in nodes)
    ...switch (node) {
      NoteMentionInline(:final mention) => [mention],
      NoteBold(:final children) => _mentionsOf(children),
      NoteItalic(:final children) => _mentionsOf(children),
      NoteText() => const <NoteMention>[],
    },
];

/// A StringBuffer that knows its last code unit (Kotlin's `out.lastOrNull()`).
final class _Out {
  final _buffer = StringBuffer();
  int? last;

  void write(String s) {
    if (s.isEmpty) return;
    _buffer.write(s);
    last = s.codeUnitAt(s.length - 1);
  }

  @override
  String toString() => _buffer.toString();
}

/// Inline parsing of one line's content; every token covers exactly the characters it was read from.
final class _InlineParser {
  _InlineParser(this.s, [this.ranges]);

  final String s;
  final List<InlineRange>? ranges;

  List<NoteInline> parse(int start, int end) {
    final out = <NoteInline>[];
    final text = StringBuffer();
    void flush() {
      if (text.isNotEmpty) {
        out.add(NoteText(text.toString()));
        text.clear();
      }
    }

    var i = start;
    while (i < end) {
      final c = s.codeUnitAt(i);
      if (c == _openBracket) {
        final match = _MentionSyntax.match(s, i, end);
        if (match != null) {
          flush();
          out.add(NoteMentionInline(match.mention));
          ranges?.add(InlineRange(InlineRangeKind.mention, i, match.end, i, match.end));
          i = match.end;
          continue;
        }
      }
      if (c == _star && i + 1 < end && s.codeUnitAt(i + 1) == _star) {
        final close = _findBoldClose(i + 2, end);
        if (close > i + 2) {
          flush();
          ranges?.add(InlineRange(InlineRangeKind.bold, i, close + 2, i + 2, close));
          out.add(NoteBold(parse(i + 2, close)));
          i = close + 2;
          continue;
        }
      }
      if ((c == _underscore || c == _star) && _canOpenItalic(i, end, c)) {
        final close = _findItalicClose(i + 1, end, c);
        if (close > i + 1) {
          flush();
          ranges?.add(InlineRange(InlineRangeKind.italic, i, close + 1, i + 1, close));
          out.add(NoteItalic(parse(i + 1, close), marker: String.fromCharCode(c)));
          i = close + 1;
          continue;
        }
      }
      text.writeCharCode(c);
      i++;
    }
    flush();
    return out;
  }

  int _findBoldClose(int from, int end) {
    var j = from;
    while (j < end - 1) {
      if (s.codeUnitAt(j) == _openBracket) {
        final match = _MentionSyntax.match(s, j, end);
        if (match != null) {
          j = match.end;
          continue;
        }
      }
      if (s.codeUnitAt(j) == _star && s.codeUnitAt(j + 1) == _star) return j;
      j++;
    }
    return -1;
  }

  bool _canOpenItalic(int i, int end, int marker) {
    if (i + 1 >= end) return false;
    final next = s.codeUnitAt(i + 1);
    if (isWhitespace(next) || next == marker) return false;
    return !italicBlockedBy(i > 0 ? s.codeUnitAt(i - 1) : null, marker);
  }

  /// The first [marker] after the opener (mentions skipped) if it can close, else -1.
  int _findItalicClose(int from, int end, int marker) {
    var j = from;
    while (j < end) {
      final c = s.codeUnitAt(j);
      if (c == _openBracket) {
        final match = _MentionSyntax.match(s, j, end);
        if (match != null) {
          j = match.end;
          continue;
        }
      }
      if (c == marker) {
        if (isWhitespace(s.codeUnitAt(j - 1))) return -1;
        return italicBlockedBy(j + 1 < s.length ? s.codeUnitAt(j + 1) : null, marker) ? -1 : j;
      }
      j++;
    }
    return -1;
  }
}

final class _MentionMatch {
  const _MentionMatch(this.mention, this.end);

  final NoteMention mention;
  final int end;
}

/// The `[@Title](yoin:…)` link.
abstract final class _MentionSyntax {
  static const _scheme = 'yoin:';

  /// The mention starting at [start] (a `[`) and ending at or before [end], or null.
  static _MentionMatch? match(String s, int start, int end) {
    if (start + 1 >= end || s.codeUnitAt(start) != _openBracket || s.codeUnitAt(start + 1) != _at) return null;
    final title = StringBuffer();
    var j = start + 2;
    while (j < end) {
      final c = s.codeUnitAt(j);
      if (c == _newline) return null;
      if (c == _backslash && j + 1 < end && _isTitleEscapable(s.codeUnitAt(j + 1))) {
        title.writeCharCode(s.codeUnitAt(j + 1));
        j += 2;
        continue;
      }
      if (c == _closeBracket) break;
      // An unescaped `[` means this isn't one (a mention may start right after it).
      if (c == _openBracket) return null;
      title.writeCharCode(c);
      j++;
    }
    final titleText = title.toString();
    if (j >= end || isBlank(titleText)) return null;
    final open = j + 1;
    if (open >= end || s.codeUnitAt(open) != _openParen || !_regionMatchesIgnoreCase(s, open + 1, _scheme)) {
      return null;
    }
    final destStart = open + 1 + _scheme.length;
    var close = destStart;
    while (close < end && s.codeUnitAt(close) != _closeParen && s.codeUnitAt(close) != _newline) {
      close++;
    }
    if (close >= end || s.codeUnitAt(close) != _closeParen) return null;
    final mention = _parseDestination(s.substring(destStart, close), titleText);
    if (mention == null) return null;
    return _MentionMatch(mention, close + 1);
  }

  static NoteMention? _parseDestination(String dest, String title) {
    final queryAt = dest.indexOf(String.fromCharCode(_question));
    final path = queryAt < 0 ? dest : dest.substring(0, queryAt);
    final slash = path.indexOf(String.fromCharCode(_slash));
    if (slash <= 0) return null;
    final kind = MentionKind.fromToken(path.substring(0, slash));
    if (kind == null) return null;
    final rest = path.substring(slash + 1);
    final colon = rest.indexOf(String.fromCharCode(_colon));
    if (colon <= 0) return null;
    final provider = rest.substring(0, colon);
    if (!NoteMention.isValidProvider(provider)) return null;
    final id = PercentCoding.decode(trimKotlin(rest.substring(colon + 1)));
    if (id.isEmpty) return null;
    String? artist;
    String? album;
    if (queryAt >= 0) {
      for (final pair in dest.substring(queryAt + 1).split('&')) {
        final eq = pair.indexOf('=');
        if (eq <= 0) continue;
        final decoded = PercentCoding.decode(pair.substring(eq + 1));
        final value = isBlank(decoded) ? null : decoded;
        switch (pair.substring(0, eq)) {
          case 'a':
            artist = value;
          case 'al':
            album = value;
        }
      }
    }
    return NoteMention(kind: kind, provider: provider, id: id, title: title, artist: artist, album: album);
  }
}

/// RFC 3986 percent-encoding over UTF-8, as the Kotlin does it.
abstract final class PercentCoding {
  static const _hex = '0123456789ABCDEF';

  static bool _isUnreserved(int c) =>
      (c >= 0x41 && c <= 0x5A) ||
      (c >= 0x61 && c <= 0x7A) ||
      (c >= 0x30 && c <= 0x39) ||
      c == _dash ||
      c == _dot ||
      c == _underscore ||
      c == 0x7E;

  /// Everything but unreserved characters (and those in [keep]) as %XX.
  static String encode(String value, {String keep = ''}) {
    bool kept(int c) => _isUnreserved(c) || keep.codeUnits.contains(c);
    if (value.codeUnits.every(kept)) return value;
    final out = StringBuffer();
    for (final b in utf8.encode(value)) {
      if (b < 0x80 && kept(b)) {
        out.writeCharCode(b);
      } else {
        out
          ..write('%')
          ..write(_hex[b >> 4])
          ..write(_hex[b & 0x0F]);
      }
    }
    return out.toString();
  }

  /// %XX sequences back to UTF-8 text; a `%` that isn't one stays as it is. `+` is a plus.
  static String decode(String value) {
    if (!value.contains('%')) return value;
    final out = StringBuffer();
    final pending = <int>[];
    void flush() {
      if (pending.isNotEmpty) {
        out.write(utf8.decode(pending, allowMalformed: true));
        pending.clear();
      }
    }

    var i = 0;
    while (i < value.length) {
      final c = value.codeUnitAt(i);
      if (c == _percent && i + 2 < value.length) {
        final hi = _hexDigit(value.codeUnitAt(i + 1));
        final lo = _hexDigit(value.codeUnitAt(i + 2));
        if (hi != null && lo != null) {
          pending.add((hi << 4) | lo);
          i += 3;
          continue;
        }
      }
      flush();
      out.writeCharCode(c);
      i++;
    }
    flush();
    return out.toString();
  }

  /// Kotlin's `Char.digitToIntOrNull(16)`: any Unicode decimal digit, ASCII
  /// and full-width Latin letters a–f.
  static int? _hexDigit(int c) {
    for (final zero in _decimalZeros) {
      if (c >= zero && c <= zero + 9) return c - zero;
    }
    if (c >= 0x41 && c <= 0x46) return c - 0x41 + 10;
    if (c >= 0x61 && c <= 0x66) return c - 0x61 + 10;
    if (c >= 0xFF21 && c <= 0xFF26) return c - 0xFF21 + 10;
    if (c >= 0xFF41 && c <= 0xFF46) return c - 0xFF41 + 10;
    return null;
  }

  /// The zero of every run of ten Unicode decimal digits (Nd) in the BMP.
  static const _decimalZeros = [
    0x0030, 0x0660, 0x06F0, 0x07C0, 0x0966, 0x09E6, 0x0A66, 0x0AE6, 0x0B66, 0x0BE6, 0x0C66, 0x0CE6, //
    0x0D66, 0x0DE6, 0x0E50, 0x0ED0, 0x0F20, 0x1040, 0x1090, 0x17E0, 0x1810, 0x1946, 0x19D0, 0x1A80, //
    0x1A90, 0x1B50, 0x1BB0, 0x1C40, 0x1C50, 0xA620, 0xA8D0, 0xA900, 0xA9D0, 0xA9F0, 0xAA50, 0xABF0, //
    0xFF10,
  ];
}
