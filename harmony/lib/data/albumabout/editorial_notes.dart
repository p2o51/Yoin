// Apple Music's editorial notes as the album's About paragraph (51,
// 2026-11-25): `standard`, else `short`, as plain text. Dart port of :shared
// `albumabout/EditorialNotes.kt`; pinned by golden/album-about.json.
import 'kt_text.dart';

abstract final class EditorialNotes {
  /// Apple's `editorialNotes` as plain text: `standard`, else `short`; null when neither has words.
  static String? pick(String? standard, String? short) {
    for (final html in [standard, short]) {
      if (html == null) continue;
      final text = plainTextFromHtml(html);
      if (text != null) return text;
    }
    return null;
  }

  static final _breakTag = RegExp('<$javaSpace*br$javaSpace*/?$javaSpace*>', caseSensitive: false);
  static final _paragraphTag = RegExp('<$javaSpace*/?$javaSpace*p($javaSpace[^>]*)?>', caseSensitive: false);
  static final _anyTag = RegExp('<[^>]*>');
  static final _spaces = RegExp('[ \t]+');
  static final _blankLines = RegExp('\n{3,}');
  static final _numericEntity = RegExp(r'&#(x[0-9a-fA-F]+|\d+);');
  static const _namedEntities = {
    '&amp;': '&',
    '&lt;': '<',
    '&gt;': '>',
    '&quot;': '"',
    '&apos;': "'",
    '&#39;': "'",
    '&nbsp;': ' ',
    '&mdash;': '—',
    '&ndash;': '–',
    '&hellip;': '…',
    '&lsquo;': '‘',
    '&rsquo;': '’',
    '&ldquo;': '“',
    '&rdquo;': '”',
  };

  /// A service's light HTML as plain text: line and paragraph breaks kept as
  /// newlines (at most one blank line), every other tag dropped, entities
  /// decoded, runs of spaces folded. Null when nothing readable is left.
  static String? plainTextFromHtml(String html) {
    var text = html.replaceAll(_breakTag, '\n').replaceAll(_paragraphTag, '\n\n').replaceAll(_anyTag, '');
    text = _decodeEntities(text).replaceAll('\u00A0', ' ');
    text = ktLines(text).map((line) => ktTrim(line.replaceAll(_spaces, ' '))).join('\n');
    text = ktTrim(text.replaceAll(_blankLines, '\n\n'));
    return text.isEmpty ? null : text;
  }

  static String _decodeEntities(String text) {
    var out = text.replaceAllMapped(_numericEntity, (m) {
      final value = m.group(1)!;
      final code = value.startsWith('x') ? _parseInt32(value.substring(1), 16) : _parseInt32(value, 10);
      if (code == null || code < 0 || code > 0x10FFFF) return m.group(0)!;
      return _codePointString(code);
    });
    // &amp; last, so "&amp;lt;" reads as the text "&lt;", not "<".
    _namedEntities.forEach((entity, char) {
      if (entity != '&amp;') out = out.replaceAll(entity, char);
    });
    return out.replaceAll('&amp;', '&');
  }

  /// Kotlin `toIntOrNull(radix)`: null beyond a 32-bit Int.
  static int? _parseInt32(String digits, int radix) {
    final value = BigInt.tryParse(digits, radix: radix);
    if (value == null || value > BigInt.from(0x7FFFFFFF)) return null;
    return value.toInt();
  }

  /// Java's `String(Character.toChars(code))`: one UTF-16 unit below U+10000 (a lone surrogate too), else a pair.
  static String _codePointString(int code) {
    if (code < 0x10000) return String.fromCharCode(code);
    final offset = code - 0x10000;
    return String.fromCharCodes([0xD800 + (offset >> 10), 0xDC00 + (offset & 0x3FF)]);
  }
}
