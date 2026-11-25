// Kotlin's String semantics the album About core relies on, so the Dart port
// answers exactly as :shared does (golden/album-about.json). Not exported from
// the barrel: only lib/data/albumabout/ reads it.

/// Kotlin `Char.isWhitespace()` on the JVM: `Character.isWhitespace(c) ||
/// Character.isSpaceChar(c)` — tabs and breaks, U+001C..U+001F, and every
/// Zs / Zl / Zp character (no-break spaces included; U+0085 and U+FEFF not).
bool ktIsWhitespace(int c) =>
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

/// Kotlin `String.trim()`.
String ktTrim(String s) {
  var start = 0;
  var end = s.length;
  while (start < end && ktIsWhitespace(s.codeUnitAt(start))) {
    start++;
  }
  while (end > start && ktIsWhitespace(s.codeUnitAt(end - 1))) {
    end--;
  }
  return s.substring(start, end);
}

/// Kotlin `String.isBlank()`.
bool ktIsBlank(String s) {
  for (var i = 0; i < s.length; i++) {
    if (!ktIsWhitespace(s.codeUnitAt(i))) return false;
  }
  return true;
}

/// Kotlin `String.trim(vararg chars)` / `trimEnd(vararg chars)`.
String ktTrimChars(String s, String chars, {bool start = true}) {
  var a = 0;
  var b = s.length;
  if (start) {
    while (a < b && chars.contains(s[a])) {
      a++;
    }
  }
  while (b > a && chars.contains(s[b - 1])) {
    b--;
  }
  return s.substring(a, b);
}

/// Kotlin `CharSequence.lines()`: split on CRLF, LF and CR.
List<String> ktLines(String s) => s.split(RegExp('\r\n|\n|\r'));

/// Java's `\s` (ASCII only), unlike Dart's Unicode `\s`.
const String javaSpace = r'[ \t\n\x0B\f\r]';

/// Kotlin `String.trimIndent()` (applied after interpolation, as Kotlin does).
String ktTrimIndent(String s) {
  final lines = ktLines(s);
  int indentWidth(String line) {
    for (var i = 0; i < line.length; i++) {
      if (!ktIsWhitespace(line.codeUnitAt(i))) return i;
    }
    return line.length;
  }

  final nonBlank = lines.where((l) => !ktIsBlank(l));
  final minIndent = nonBlank.isEmpty ? 0 : nonBlank.map(indentWidth).reduce((a, b) => a < b ? a : b);
  final out = <String>[];
  for (var i = 0; i < lines.length; i++) {
    final line = lines[i];
    if ((i == 0 || i == lines.length - 1) && ktIsBlank(line)) continue;
    out.add(line.length <= minIndent ? '' : line.substring(minIndent));
  }
  return out.join('\n');
}

/// Kotlin `List.distinct()`: first occurrence kept, order kept.
List<String> ktDistinct(Iterable<String> values) {
  final seen = <String>{};
  return [
    for (final v in values)
      if (seen.add(v)) v,
  ];
}

/// Kotlin `String.take(n)` (UTF-16 units).
String ktTake(String s, int n) => s.length <= n ? s : s.substring(0, n);
