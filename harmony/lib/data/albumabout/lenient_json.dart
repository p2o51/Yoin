// A JSON reader that reads what kotlinx.serialization's
// `Json { isLenient = true; allowTrailingComma = true }` reads (its
// JsonTreeReader + lexer), for the AI's [CREDITS] block: unquoted keys and
// values, trailing commas. Dart's jsonDecode is strict, so the port keeps its
// own reader. Not exported from the barrel.
//
// Values: Map<String, Object?> (objects, last duplicate key wins),
// List<Object?> (arrays), [JsonLiteral] (strings and every other literal),
// and null for an unquoted `null` (kotlinx's JsonNull).

/// A primitive as kotlinx keeps it: its text and whether it was quoted.
class JsonLiteral {
  const JsonLiteral(this.content, {required this.isString});

  final String content;
  final bool isString;
}

class LenientJsonException implements Exception {
  const LenientJsonException(this.message);

  final String message;

  @override
  String toString() => 'LenientJsonException: $message';
}

/// Parses [source] like kotlinx's lenient `parseToJsonElement`; throws
/// [LenientJsonException] where kotlinx would throw.
Object? parseLenientJson(String source) => _Reader(source).readRoot();

const int _tcOther = 0;
const int _tcString = 1;
const int _tcStringEsc = 2;
const int _tcWhitespace = 3;
const int _tcComma = 4;
const int _tcColon = 5;
const int _tcBeginObj = 6;
const int _tcEndObj = 7;
const int _tcBeginList = 8;
const int _tcEndList = 9;
const int _tcInvalid = 10;
const int _tcEof = 11;

int _tokenClass(int c) {
  switch (c) {
    case 0x09:
    case 0x0A:
    case 0x0D:
    case 0x20:
      return _tcWhitespace;
    case 0x2C:
      return _tcComma;
    case 0x3A:
      return _tcColon;
    case 0x7B:
      return _tcBeginObj;
    case 0x7D:
      return _tcEndObj;
    case 0x5B:
      return _tcBeginList;
    case 0x5D:
      return _tcEndList;
    case 0x22:
      return _tcString;
    case 0x5C:
      return _tcStringEsc;
  }
  return c < 0x20 ? _tcInvalid : _tcOther;
}

class _Reader {
  _Reader(this.s);

  final String s;
  int pos = 0;

  Never fail(String message) => throw LenientJsonException('$message at $pos');

  void skipWhitespace() {
    while (pos < s.length && _tokenClass(s.codeUnitAt(pos)) == _tcWhitespace) {
      pos++;
    }
  }

  int peekToken() {
    skipWhitespace();
    if (pos >= s.length) return _tcEof;
    return _tokenClass(s.codeUnitAt(pos));
  }

  int consumeToken() {
    final token = peekToken();
    if (token != _tcEof) pos++;
    return token;
  }

  void expectToken(int token) {
    if (consumeToken() != token) fail('Unexpected token');
  }

  /// kotlinx `canConsumeValue`: anything but `}` `]` `:` `,` or the end.
  bool canConsumeValue() {
    skipWhitespace();
    if (pos >= s.length) return false;
    final c = s[pos];
    return !(c == '}' || c == ']' || c == ':' || c == ',');
  }

  Object? readRoot() {
    final value = read();
    skipWhitespace();
    if (pos != s.length) fail('Expected EOF');
    return value;
  }

  Object? read() {
    switch (peekToken()) {
      case _tcString:
        return JsonLiteral(consumeQuoted(), isString: true);
      case _tcOther:
        final text = consumeUnquoted();
        return text == 'null' ? null : JsonLiteral(text, isString: false);
      case _tcBeginObj:
        return readObject();
      case _tcBeginList:
        return readArray();
      default:
        fail('Cannot read Json element');
    }
  }

  Map<String, Object?> readObject() {
    expectToken(_tcBeginObj);
    var last = _tcBeginObj;
    if (peekToken() == _tcComma) fail('Unexpected leading comma');
    final result = <String, Object?>{};
    while (canConsumeValue()) {
      final key = consumeLenientString();
      expectToken(_tcColon);
      result[key] = read();
      last = consumeToken();
      if (last == _tcComma) continue;
      if (last == _tcEndObj) break;
      fail('Expected end of the object or comma');
    }
    if (last == _tcBeginObj || last == _tcComma) expectToken(_tcEndObj);
    return result;
  }

  List<Object?> readArray() {
    expectToken(_tcBeginList);
    var last = _tcBeginList;
    if (peekToken() == _tcComma) fail('Unexpected leading comma');
    final result = <Object?>[];
    while (canConsumeValue()) {
      result.add(read());
      last = consumeToken();
      if (last == _tcComma) continue;
      if (last == _tcEndList) break;
      fail('Expected end of the array or comma');
    }
    if (last == _tcBeginList || last == _tcComma) expectToken(_tcEndList);
    return result;
  }

  String consumeLenientString() {
    final token = peekToken();
    if (token == _tcString) return consumeQuoted();
    if (token != _tcOther) fail('Expected beginning of the string');
    return consumeUnquoted();
  }

  String consumeUnquoted() {
    skipWhitespace();
    final start = pos;
    while (pos < s.length && _tokenClass(s.codeUnitAt(pos)) == _tcOther) {
      pos++;
    }
    if (pos == start) fail('Expected beginning of the string');
    return s.substring(start, pos);
  }

  String consumeQuoted() {
    skipWhitespace();
    if (pos >= s.length || s[pos] != '"') fail('Expected quotation mark');
    pos++;
    final out = StringBuffer();
    while (true) {
      if (pos >= s.length) fail('Unexpected EOF in a string');
      final c = s.codeUnitAt(pos);
      if (c == 0x22) {
        pos++;
        return out.toString();
      }
      if (c != 0x5C) {
        out.writeCharCode(c);
        pos++;
        continue;
      }
      pos++;
      if (pos >= s.length) fail('Unexpected EOF after escape');
      final e = s[pos++];
      switch (e) {
        case '"':
        case '\\':
        case '/':
          out.write(e);
        case 'b':
          out.write('\b');
        case 'f':
          out.write('\f');
        case 'n':
          out.write('\n');
        case 'r':
          out.write('\r');
        case 't':
          out.write('\t');
        case 'u':
          if (pos + 4 > s.length) fail('Unexpected EOF in a unicode escape');
          final code = int.tryParse(s.substring(pos, pos + 4), radix: 16);
          if (code == null || s.substring(pos, pos + 4).contains(RegExp('[^0-9a-fA-F]'))) {
            fail('Invalid unicode escape');
          }
          out.writeCharCode(code);
          pos += 4;
        default:
          fail('Invalid escaped char');
      }
    }
  }
}
