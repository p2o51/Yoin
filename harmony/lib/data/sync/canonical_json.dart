/// Order-independent JSON identity for sync (Android data/sync/CanonicalJson.kt):
/// the same logical value always hashes the same, whatever key order a device
/// (or an older app version) happened to write. Hashes decide "did this
/// change" everywhere in sync, and the tie rule compares payload hashes across
/// devices, so the text must match the Kotlin side byte for byte: keys sorted
/// recursively, no whitespace, strings quoted as kotlinx.serialization prints
/// them, numbers as Kotlin prints a JSON number (`7.0` for a rating, `123` for
/// a timestamp).
library;

import 'dart:convert';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';

abstract final class CanonicalJson {
  /// Canonical text: object keys sorted recursively, no whitespace.
  static String canonical(Object? value) {
    final out = StringBuffer();
    _write(out, value);
    return out.toString();
  }

  /// Lowercase hex SHA-256 of [canonical].
  static String hash(Object? value) => sha256Hex(canonical(value));

  static String sha256Hex(String text) => sha256.convert(utf8.encode(text)).toString();

  /// Small non-negative int derived from [text], stable across devices and
  /// app versions (artifact shard routing): the first four digest bytes, the
  /// top bit cleared.
  static int stableHash(String text) {
    final d = Uint8List.fromList(sha256.convert(utf8.encode(text)).bytes);
    return ((d[0] & 0x7f) << 24) | (d[1] << 16) | (d[2] << 8) | d[3];
  }

  static void _write(StringBuffer out, Object? value) {
    switch (value) {
      case null:
        out.write('null');
      case final Map<Object?, Object?> map:
        final keys = [for (final k in map.keys) k as String]..sort(_compareUtf16);
        out.write('{');
        for (var i = 0; i < keys.length; i++) {
          if (i > 0) out.write(',');
          writeQuoted(out, keys[i]);
          out.write(':');
          _write(out, map[keys[i]]);
        }
        out.write('}');
      case final List<Object?> list:
        out.write('[');
        for (var i = 0; i < list.length; i++) {
          if (i > 0) out.write(',');
          _write(out, list[i]);
        }
        out.write(']');
      case final String s:
        writeQuoted(out, s);
      case final bool b:
        out.write(b ? 'true' : 'false');
      case final int n:
        out.write(n);
      case final double d:
        out.write(numberText(d));
      default:
        throw ArgumentError.value(value, 'value', 'Not a JSON value');
    }
  }

  /// Kotlin's String.compareTo: UTF-16 code units, which Dart's compareTo is too.
  static int _compareUtf16(String a, String b) => a.compareTo(b);

  /// A double as a JSON number. Whole values keep their `.0` (Kotlin prints
  /// `7.0f` as `7.0`); NaN and infinities never reach JSON.
  static String numberText(double d) {
    if (!d.isFinite) throw ArgumentError.value(d, 'd', 'Not a finite number');
    return d.toString();
  }

  /// kotlinx.serialization's printQuoted: `"` and `\` escaped, control
  /// characters as \b \t \n \f \r or \u00xx (lowercase hex), nothing else.
  static void writeQuoted(StringBuffer out, String s) {
    out.write('"');
    for (final unit in s.codeUnits) {
      switch (unit) {
        case 0x22:
          out.write(r'\"');
        case 0x5c:
          out.write(r'\\');
        case 0x08:
          out.write(r'\b');
        case 0x09:
          out.write(r'\t');
        case 0x0a:
          out.write(r'\n');
        case 0x0c:
          out.write(r'\f');
        case 0x0d:
          out.write(r'\r');
        default:
          if (unit < 0x20) {
            out.write(r'\u00');
            out.write(unit.toRadixString(16).padLeft(2, '0'));
          } else {
            out.writeCharCode(unit);
          }
      }
    }
    out.write('"');
  }
}

/// Compact JSON text of [value] in its own key order, as kotlinx writes it:
/// what a payload is stored and published as (never canonicalised).
String compactJson(Object? value) {
  final out = StringBuffer();
  _writeCompact(out, value);
  return out.toString();
}

void _writeCompact(StringBuffer out, Object? value) {
  switch (value) {
    case null:
      out.write('null');
    case final Map<Object?, Object?> map:
      out.write('{');
      var first = true;
      for (final e in map.entries) {
        if (!first) out.write(',');
        first = false;
        CanonicalJson.writeQuoted(out, e.key as String);
        out.write(':');
        _writeCompact(out, e.value);
      }
      out.write('}');
    case final List<Object?> list:
      out.write('[');
      for (var i = 0; i < list.length; i++) {
        if (i > 0) out.write(',');
        _writeCompact(out, list[i]);
      }
      out.write(']');
    case final String s:
      CanonicalJson.writeQuoted(out, s);
    case final bool b:
      out.write(b ? 'true' : 'false');
    case final int n:
      out.write(n);
    case final double d:
      out.write(CanonicalJson.numberText(d));
    default:
      throw ArgumentError.value(value, 'value', 'Not a JSON value');
  }
}

/// A JSON object, or null when [text] is not one.
Map<String, Object?>? parseJsonObject(String? text) {
  if (text == null) return null;
  try {
    final decoded = jsonDecode(text);
    return decoded is Map<String, Object?> ? decoded : null;
  } on FormatException {
    return null;
  }
}

/// Deep equality of two decoded JSON values (maps compared without order).
bool jsonEquals(Object? a, Object? b) => CanonicalJson.canonical(a) == CanonicalJson.canonical(b);
