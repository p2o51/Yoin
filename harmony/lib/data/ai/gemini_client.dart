import 'dart:async';
import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:http/http.dart' as http;

import 'ai_settings.dart';

/// [httpCode] is set when the service answered with an HTTP error (GeminiException).
final class GeminiException implements Exception {
  const GeminiException(this.message, {this.httpCode});

  final String message;
  final int? httpCode;

  @override
  String toString() => message;
}

/// Gemini's `generateContent` (Android AiClient.gemini) and the lyric
/// translation built on it (GeminiService.translateLyricLines): no search,
/// the model must keep the line count and phrasing.
class GeminiClient {
  GeminiClient({http.Client? client}) : _client = client ?? http.Client();

  final http.Client _client;

  static const Duration _timeout = Duration(seconds: 60);

  /// Sends [prompt]; the reply's text parts joined.
  Future<String> generate(AiSettings ai, String prompt, {double temperature = 0.8}) async {
    final uri = Uri.parse('https://generativelanguage.googleapis.com/v1beta/models/${ai.modelId}:generateContent');
    final body = {
      'contents': [
        {
          'parts': [
            {'text': prompt},
          ],
        },
      ],
      'generationConfig': {'temperature': temperature},
    };
    final http.Response response;
    try {
      response = await _client
          .post(
            uri,
            headers: {'content-type': 'application/json', 'x-goog-api-key': ai.apiKey},
            body: utf8.encode(jsonEncode(body)),
          )
          .timeout(_timeout);
    } on TimeoutException {
      throw const GeminiException('Gemini timed out');
    }
    final text = utf8.decode(response.bodyBytes, allowMalformed: true);
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw GeminiException(
        'Gemini error (${response.statusCode}): ${_errorMessage(text)}',
        httpCode: response.statusCode,
      );
    }
    final Object? root;
    try {
      root = jsonDecode(text);
    } catch (_) {
      throw const GeminiException('Unreadable response from Gemini');
    }
    final candidates = root is Map ? root['candidates'] : null;
    final first = candidates is List && candidates.isNotEmpty ? candidates.first : null;
    final content = first is Map ? first['content'] : null;
    final parts = content is Map ? content['parts'] : null;
    final joined = parts is List
        ? parts.map((p) => p is Map && p['text'] is String ? p['text'] as String : '').join()
        : '';
    if (joined.isEmpty) throw const GeminiException('No content in Gemini response');
    return joined;
  }

  /// Our line index → its translation into [targetLanguage].
  Future<Map<int, String>> translateLyricLines(
    AiSettings ai, {
    required String title,
    required String artist,
    required List<String> lines,
    required String targetLanguage,
  }) async {
    final raw = await generate(ai, lyricsTranslationPrompt(title, artist, lines, targetLanguage));
    final parsed = parseLineTranslations(raw, lines.length);
    if (parsed.isEmpty) throw const GeminiException('No usable lyric translations in AI response');
    return parsed;
  }

  static String _errorMessage(String body) {
    try {
      final root = jsonDecode(body);
      if (root is Map) {
        final error = root['error'];
        if (error is Map && error['message'] is String && (error['message'] as String).trim().isNotEmpty) {
          return error['message'] as String;
        }
        if (error is String) return error;
        final message = root['message'];
        if (message is String && message.trim().isNotEmpty) return message;
      }
    } catch (_) {}
    return body.length > 200 ? body.substring(0, 200) : body;
  }
}

/// GeminiService.buildLyricsTranslationPrompt, word for word.
String lyricsTranslationPrompt(String title, String artist, List<String> lines, String targetLanguage) {
  final indexed = [for (var i = 0; i < lines.length; i++) '[$i] ${lines[i]}'].join('\n');
  return '''
Translate these lyric lines into $targetLanguage.

Song: $title
Artist: $artist

Preserve line count and meaning. Keep imagery natural, concise, and singable.
Return strictly one tagged block per source line:

[L0]translation for line 0[/L0]
[L1]translation for line 1[/L1]

Lines:
$indexed''';
}

final RegExp _tagMarker = RegExp(r'\[\s*/?\s*[A-Z][A-Z0-9_]*\s*\]', caseSensitive: false);
final RegExp _lineTranslation = RegExp(r'\[\s*L(\d+)\s*\]([\s\S]*?)\[\s*/\s*L\1\s*\]', caseSensitive: false);
final RegExp _leadingLineMarker = RegExp(r'^(?:[-*•]\s*)?(?:(?:[\[【(]\s*\d+\s*[\]】)])(?:[.)：:、])?|\d+[.)：:、])\s*');

/// The tagged `[Ln]…[/Ln]` blocks; failing those, plain lines when there are exactly as many (parseLineTranslations).
Map<int, String> parseLineTranslations(String rawText, int lineCount) {
  final tagged = <int, String>{};
  for (final m in _lineTranslation.allMatches(rawText)) {
    final index = int.tryParse(m.group(1)!);
    final text = cleanLineTranslation(m.group(2)!);
    if (index != null && index >= 0 && index < lineCount && text.trim().isNotEmpty) tagged[index] = text;
  }
  if (tagged.isNotEmpty) return tagged;
  final plain = const LineSplitter().convert(rawText).map(cleanLineTranslation).where((l) => l.isNotEmpty).toList();
  if (plain.length != lineCount) return const {};
  return {
    for (var i = 0; i < plain.length; i++)
      if (cleanLineTranslation(plain[i]).trim().isNotEmpty) i: cleanLineTranslation(plain[i]),
  };
}

/// Leftover tag markers and leading numbering ("1.", "[2]", "•") gone (cleanLineTranslation).
String cleanLineTranslation(String text) {
  var cleaned = text.replaceAll(_tagMarker, '').trim();
  for (var i = 0; i < 3; i++) {
    cleaned = cleaned.replaceFirst(_leadingLineMarker, '').trim();
  }
  return cleaned;
}

final geminiClientProvider = Provider<GeminiClient>((ref) => GeminiClient());
