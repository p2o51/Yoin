/// Huawei Music's line-by-line LRC, tidied and split (Android HuaweiLrc.kt), pure.
///
/// A translated file writes the Simplified Chinese line on the original's
/// own line after a `^`: `[00:27.38]original ^translation`, with or without
/// spaces around it. [huaweiLrcSplit] makes two LRCs on one timeline:
/// - the **original** keeps no `^translation` tail: it lands in the lyrics
///   cache, feeds the AI translation as source lines and is what sync counts;
/// - the **translation** has one line for every original line; an
///   untranslated one (title, credits, interjections) reads
///   [LyricPayload.untranslatedLine], so both sides hold the same number of
///   lines on one timestamp and pair by position.
///
/// Also: a BOM, CRLF, `&#digits;` entities (Korean is often written so) and
/// the instrumental placeholder (no lyrics).
library;

import 'lyric_provider.dart';

/// The `lineByLineLyric_subType` value that means "translation inline".
const String huaweiSubtypeTranslate = 'translate';

const String _bom = '\uFEFF';
const String _instrumentalPlaceholder = '此歌曲为没有填词的纯音乐';

final RegExp _leadingTimestamps = RegExp(r'^(?:\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?\])+');
final RegExp _idTag = RegExp(r'^\[[A-Za-z]+:[^\]]*\]$');
final RegExp _numericEntity = RegExp(r'&#(?:(\d{1,7})|[xX]([0-9A-Fa-f]{1,6}));');
final RegExp _lineBreak = RegExp(r'\r\n|\r|\n');
final RegExp _han = RegExp(r'\p{Script=Han}', unicode: true);
final RegExp _letterOrDigit = RegExp(r'[\p{L}\p{Nd}]', unicode: true);

/// [subTypes] are the search hit's `lineByLineLyric_subType` split on `|`:
/// holding [huaweiSubtypeTranslate] → split; known but without it → no split
/// (a `^` in the text is the text); unknown (empty) → split only when some
/// line has Han characters after a `^`.
///
/// Null: the file is empty, or only the instrumental placeholder.
LyricPayload? huaweiLrcSplit(String raw, Set<String> subTypes) {
  final text = raw.startsWith(_bom) ? raw.substring(_bom.length) : raw;
  final lines = text.split(_lineBreak);
  if (lines.every((l) => l.trim().isEmpty) || _isInstrumentalPlaceholder(lines)) return null;

  final bool inline;
  if (subTypes.contains(huaweiSubtypeTranslate)) {
    inline = true;
  } else if (subTypes.isEmpty) {
    inline = lines.any((line) {
      final stamped = _stamped(line);
      final translation = stamped == null ? null : _splitBody(stamped.body).translation;
      return translation != null && _han.hasMatch(translation);
    });
  } else {
    inline = false;
  }
  if (!inline) return LyricPayload(lyric: _decodeNumericEntities(lines.join('\n')));

  final original = <String>[];
  final translation = <String>[];
  var translatedLines = 0;
  for (final line in lines) {
    final stamped = _stamped(line);
    if (stamped == null) {
      // Tag lines (`[ti:…]`) and lines with no timestamp belong to the original only.
      original.add(line);
      continue;
    }
    final parts = _splitBody(stamped.body);
    // An empty original: the parser drops the line, so the translation must not gain one either.
    if (parts.original.isEmpty) continue;
    original.add(stamped.stamps + parts.original);
    translation.add(stamped.stamps + (parts.translation ?? LyricPayload.untranslatedLine));
    if (parts.translation != null) translatedLines += 1;
  }
  return LyricPayload(
    lyric: _decodeNumericEntities(original.join('\n')),
    translatedLyric: translatedLines > 0 ? _decodeNumericEntities(translation.join('\n')) : null,
  );
}

/// Only the last `^`, and only with a letter or digit after it: an original
/// sometimes holds `^_^`, a Chinese translation hardly ever a `^`. A dangling
/// `^` at the end (an empty translation) just goes.
({String original, String? translation}) _splitBody(String body) {
  final caret = body.lastIndexOf('^');
  if (caret < 0) return (original: body.trim(), translation: null);
  final left = body.substring(0, caret).trim();
  final right = body.substring(caret + 1).trim();
  if (_letterOrDigit.hasMatch(right)) return (original: left, translation: right);
  if (right.isEmpty && left.isNotEmpty && _endsWithLetterOrDigit(left)) return (original: left, translation: null);
  return (original: body.trim(), translation: null);
}

bool _endsWithLetterOrDigit(String text) {
  final last = String.fromCharCode(text.runes.last);
  return _letterOrDigit.hasMatch(last);
}

({String stamps, String body})? _stamped(String line) {
  final trimmed = line.trim();
  final stamps = _leadingTimestamps.firstMatch(trimmed)?.group(0);
  if (stamps == null) return null;
  return (stamps: stamps, body: trimmed.substring(stamps.length));
}

/// An instrumental is one line `[00:00:00]此歌曲为没有填词的纯音乐` (with a broken
/// stamp): nothing to follow.
bool _isInstrumentalPlaceholder(List<String> lines) {
  final texts = [
    for (final line in lines)
      if (line.trim().replaceFirst(_leadingTimestamps, '').trim() case final t when t.isNotEmpty && !_idTag.hasMatch(t))
        t,
  ];
  return texts.isNotEmpty && texts.every((t) => t.startsWith(_instrumentalPlaceholder));
}

/// `&#45684;` / `&#x1F600;` → the character. Named entities (`&amp;` …) are
/// left to [normalizeLyric].
String _decodeNumericEntities(String text) => text.replaceAllMapped(_numericEntity, (match) {
  final decimal = match.group(1);
  final hex = match.group(2);
  final codePoint = decimal != null ? int.tryParse(decimal) : int.tryParse(hex!, radix: 16);
  final valid =
      codePoint != null &&
      codePoint > 0 &&
      codePoint <= 0x10FFFF &&
      // A lone surrogate is no character.
      (codePoint < 0xD800 || codePoint > 0xDFFF);
  return valid ? String.fromCharCode(codePoint) : match.group(0)!;
});
