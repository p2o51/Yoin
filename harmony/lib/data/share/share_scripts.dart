/// The 竖排 gate's script test (port of :shared ShareScripts.kt), table-driven
/// so every platform answers alike: [ShareScriptTables] is the JVM's
/// `Character.isLetter` / `Character.UnicodeScript`, copied — Dart's RegExp
/// script classes follow another Unicode version and read
/// Script_Extensions, so they are not used.
library;

import 'share_script_tables.dart';

abstract final class ShareScripts {
  /// Whether [text] is CJK all through: at least one Han / Hiragana / Katakana
  /// / Hangul / Bopomofo letter and no letter of any other script. Digits,
  /// punctuation, symbols and spaces don't count either way. Script, not
  /// Script_Extensions — plus the Common-script letters only Japanese writes
  /// ([cjkCommonLetters]: ー, halfwidth ｰ ﾞ ﾟ, 〆), so "ラーメン" is CJK.
  static bool isCjk(String text) {
    var cjk = false;
    var i = 0;
    while (i < text.length) {
      final cp = _codePointAt(text, i);
      i += cp > 0xFFFF ? 2 : 1;
      if (!isLetter(cp)) continue;
      if (isCjkLetter(cp) || cjkCommonLetters.contains(cp)) {
        cjk = true;
      } else {
        return false;
      }
    }
    return cjk;
  }

  /// ー (U+30FC), halfwidth ｰ ﾞ ﾟ (U+FF70, U+FF9E, U+FF9F) and 〆 (U+3006):
  /// Common-script letters that read as Japanese.
  static const Set<int> cjkCommonLetters = {0x30FC, 0xFF70, 0xFF9E, 0xFF9F, 0x3006};

  /// `Character.isLetter(cp)`.
  static bool isLetter(int cp) => _inRanges(ShareScriptTables.letters, cp);

  /// A letter of the Han, Hiragana, Katakana, Hangul or Bopomofo script.
  static bool isCjkLetter(int cp) => _inRanges(ShareScriptTables.cjkLetters, cp);

  /// Whether any UTF-16 unit of [text] is a letter on its own (a surrogate half never is).
  static bool hasLetterUnit(String text) {
    for (var i = 0; i < text.length; i++) {
      if (isLetter(text.codeUnitAt(i))) return true;
    }
    return false;
  }

  /// The code point at [index], a well-formed surrogate pair read as one (as `String.codePointAt`).
  static int _codePointAt(String text, int index) {
    final high = text.codeUnitAt(index);
    if (high >= 0xD800 && high <= 0xDBFF && index + 1 < text.length) {
      final low = text.codeUnitAt(index + 1);
      if (low >= 0xDC00 && low <= 0xDFFF) return ((high - 0xD800) << 10) + (low - 0xDC00) + 0x10000;
    }
    return high;
  }

  static bool _inRanges(List<int> ranges, int cp) {
    var low = 0;
    var high = ranges.length ~/ 2 - 1;
    while (low <= high) {
      final mid = (low + high) >> 1;
      if (cp < ranges[mid * 2]) {
        high = mid - 1;
      } else if (cp > ranges[mid * 2 + 1]) {
        low = mid + 1;
      } else {
        return true;
      }
    }
    return false;
  }
}
