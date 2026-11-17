import 'dart:async';
import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:http/http.dart' as http;

import 'ai_settings.dart';

/// [httpCode] is set when the service answered with an HTTP error (Android GeminiException).
final class AiException implements Exception {
  const AiException(this.message, {this.httpCode});

  final String message;
  final int? httpCode;

  @override
  String toString() => message;
}

/// One page Yoin found through a search API.
final class AiSearchHit {
  const AiSearchHit({required this.title, required this.url, required this.text});

  final String title;
  final String url;
  final String text;
}

/// One text-in, text-out call to whichever service [AiSettings] names, plus
/// the search APIs Yoin calls itself (Android AiClient). Every AI feature goes
/// through this; nothing else talks to a model.
abstract interface class AiClient {
  /// Sends [prompt]. With [useSearch], Gemini and Qwen search on their own;
  /// for the other services the caller has already put [search] results in
  /// the prompt. [timeout] caps the whole call.
  Future<String> generate(
    AiSettings ai,
    String prompt, {
    required double temperature,
    required bool useSearch,
    Duration? timeout,
  });

  /// Runs one query on the search service [ai] names. Pages with up to
  /// [HttpAiClient.maxPageChars] of text each.
  Future<List<AiSearchHit>> search(AiSettings ai, String query);

  /// The model ids a Custom endpoint offers, for the 「拉取」 button.
  Future<List<String>> listModels(AiApiFormat format, String baseUrl, String apiKey);
}

/// [AiClient] over plain HTTP (dart:io; the same on HarmonyOS and Windows).
class HttpAiClient implements AiClient {
  HttpAiClient({http.Client? client}) : _client = client ?? http.Client();

  final http.Client _client;

  static const Duration defaultTimeout = Duration(seconds: 60);
  static const int searchResults = 8;
  static const int maxPagesInPrompt = 10;
  static const int maxPageChars = 2000;
  static const int _deepSeekQueryLimit = 128;
  static const int _doubaoQueryLimit = 100;
  static const String _doubaoSearchUrl = 'https://open.feedcoopapi.com/search_api/web_search';
  static const String _braveBaseUrl = 'https://api.search.brave.com/';
  static const Set<int> _braveFallbackCodes = {403, 404, 422};
  static const String _anthropicVersion = '2023-06-01';

  // Thinking counts against the cap and current Claude models think by default; a long
  // lyric translation alone runs to a few thousand tokens.
  static const int anthropicMaxTokens = 16000;

  @override
  Future<String> generate(
    AiSettings ai,
    String prompt, {
    required double temperature,
    required bool useSearch,
    Duration? timeout,
  }) {
    switch (ai.provider) {
      case AiProvider.gemini:
        return _gemini(ai, prompt, temperature, useSearch, timeout);
      case AiProvider.deepSeek:
        // About doesn't gain from thinking (2026-11-12): slower, same accuracy.
        return _openAi(
          ai,
          '${AiSettings.deepSeekBaseUrl}/chat/completions',
          prompt,
          temperature,
          timeout,
          extra: {
            'thinking': {'type': 'disabled'},
          },
        );
      case AiProvider.qwen:
        return _openAi(
          ai,
          '${AiSettings.qwenBaseUrl}/chat/completions',
          prompt,
          temperature,
          timeout,
          extra: {
            'enable_thinking': false,
            if (useSearch) ...{
              'enable_search': true,
              'search_options': {'forced_search': true},
            },
          },
        );
      // Custom sends no temperature in either format: OpenAI's reasoning models and
      // Anthropic's current models answer anything but their default with a 400.
      case AiProvider.custom:
        switch (ai.customFormat) {
          case AiApiFormat.openAi:
            final effort = reasoningEffort(ai.customThinking);
            return _openAi(
              ai,
              '${trimBase(ai.customBaseUrl)}/chat/completions',
              prompt,
              null,
              timeout,
              extra: {'reasoning_effort': ?effort},
            );
          case AiApiFormat.anthropic:
            return _anthropic(ai, prompt, timeout);
        }
    }
  }

  @override
  Future<List<AiSearchHit>> search(AiSettings ai, String query) async {
    final key = ai.searchApiKey;
    final List<AiSearchHit> hits;
    switch (ai.effectiveSearchService) {
      case AiSearchService.deepSeek:
        final root = await _post(
          '${AiSettings.deepSeekBaseUrl}/v1/tools/search',
          {
            'query': query.length > _deepSeekQueryLimit ? query.substring(0, _deepSeekQueryLimit) : query,
            'count': searchResults,
            'need_content': true,
          },
          'DeepSeek Search',
          null,
          {'Authorization': 'Bearer $key'},
        );
        hits = [
          for (final item in _array(root, 'results'))
            AiSearchHit(
              title: _str(item, 'title'),
              url: _str(item, 'url'),
              text: _str(item, 'content').isEmpty ? _str(item, 'summary') : _str(item, 'content'),
            ),
        ];
      case AiSearchService.exa:
        final root = await _post(
          'https://api.exa.ai/search',
          {
            'query': query,
            'numResults': searchResults,
            'contents': {
              'text': {'maxCharacters': maxPageChars},
            },
          },
          'Exa',
          null,
          {'x-api-key': key},
        );
        hits = [
          for (final item in _array(root, 'results'))
            AiSearchHit(title: _str(item, 'title'), url: _str(item, 'url'), text: _str(item, 'text')),
        ];
      case AiSearchService.tavily:
        // advanced + chunks: the basic summaries missed most credits (2026-11-12).
        final root = await _post(
          'https://api.tavily.com/search',
          {'query': query, 'max_results': searchResults, 'search_depth': 'advanced', 'chunks_per_source': 3},
          'Tavily',
          null,
          {'Authorization': 'Bearer $key'},
        );
        hits = [
          for (final item in _array(root, 'results'))
            AiSearchHit(title: _str(item, 'title'), url: _str(item, 'url'), text: _str(item, 'content')),
        ];
      case AiSearchService.brave:
        hits = await _braveSearch(key, query);
      case AiSearchService.doubao:
        // 豆包搜索, Custom edition (Volcengine). NeedContent asks for each page's text.
        final root = await _post(
          _doubaoSearchUrl,
          {
            'Query': _take(query, _doubaoQueryLimit),
            'SearchType': 'web',
            'Count': searchResults,
            'Filter': {'NeedContent': true},
          },
          'Doubao',
          null,
          {'Authorization': 'Bearer $key'},
        );
        // A refused call still answers 200: Result is null and the reason is in ResponseMetadata.
        if (doubaoError(root) case final error?) throw AiException('Doubao error: $error');
        final result = root['Result'];
        hits = [
          if (result is Map<String, Object?>)
            for (final item in _array(result, 'WebResults'))
              AiSearchHit(
                title: _str(item, 'Title'),
                url: _str(item, 'Url'),
                text: _str(item, 'Content').isEmpty ? _str(item, 'Summary') : _str(item, 'Content'),
              ),
        ];
      case AiSearchService.none:
        hits = const [];
    }
    return [
      for (final hit in hits)
        if (hit.text.trim().isNotEmpty)
          AiSearchHit(title: hit.title, url: hit.url, text: _take(hit.text, maxPageChars)),
    ];
  }

  /// Brave: the LLM Context endpoint, which returns page text. A plan without
  /// it (403, 404 or 422) falls back once to web search, each page then being
  /// its description and extra snippets.
  Future<List<AiSearchHit>> _braveSearch(String key, String query) async {
    Future<Map<String, Object?>> get(String path, [Map<String, String> extra = const {}]) {
      final url = Uri.parse(
        '$_braveBaseUrl$path',
      ).replace(queryParameters: {'q': query, 'count': '$searchResults', ...extra});
      return _send(
        () => _client.get(url, headers: {'X-Subscription-Token': key, 'Accept': 'application/json'}),
        'Brave',
        null,
      );
    }

    Map<String, Object?>? context;
    try {
      context = await get('res/v1/llm/context');
    } on AiException catch (error) {
      if (!_braveFallbackCodes.contains(error.httpCode)) rethrow;
    }
    if (context != null) {
      final grounding = context['grounding'];
      return [
        if (grounding is Map<String, Object?>)
          for (final item in _array(grounding, 'generic'))
            AiSearchHit(
              title: _str(item, 'title'),
              url: _str(item, 'url'),
              text: _strings(item, 'snippets').join('\n'),
            ),
      ];
    }
    final web = (await get('res/v1/web/search', {'extra_snippets': 'true'}))['web'];
    return [
      if (web is Map<String, Object?>)
        for (final item in _array(web, 'results'))
          AiSearchHit(
            title: _str(item, 'title'),
            url: _str(item, 'url'),
            text: [
              _str(item, 'description'),
              ..._strings(item, 'extra_snippets'),
            ].where((t) => t.trim().isNotEmpty).join('\n'),
          ),
    ];
  }

  @override
  Future<List<String>> listModels(AiApiFormat format, String baseUrl, String apiKey) async {
    final base = trimBase(baseUrl);
    final key = apiKey.trim();
    final (url, headers) = switch (format) {
      AiApiFormat.openAi => ('$base/models', {'Authorization': 'Bearer $key'}),
      AiApiFormat.anthropic => (
        '${anthropicBase(base)}/models',
        {'x-api-key': key, 'anthropic-version': _anthropicVersion},
      ),
    };
    final root = await _send(() => _client.get(Uri.parse(url), headers: headers), 'Models', null);
    return [
      for (final item in _array(root, 'data'))
        if (_str(item, 'id').trim().isNotEmpty) _str(item, 'id'),
    ]..sort();
  }

  Future<String> _gemini(AiSettings ai, String prompt, double temperature, bool useSearch, Duration? timeout) async {
    final root = await _post(
      'https://generativelanguage.googleapis.com/v1beta/models/${ai.modelId}:generateContent',
      {
        'contents': [
          {
            'parts': [
              {'text': prompt},
            ],
          },
        ],
        if (useSearch)
          'tools': [
            {'googleSearch': <String, Object?>{}},
          ],
        'generationConfig': {'temperature': temperature},
      },
      'Gemini',
      timeout,
      {'x-goog-api-key': ai.apiKey},
    );
    final first = _array(root, 'candidates').firstOrNull;
    final content = first?['content'];
    final joined = content is Map<String, Object?> ? _array(content, 'parts').map((p) => _str(p, 'text')).join() : '';
    if (joined.isEmpty) throw const AiException('No content in Gemini response');
    return joined;
  }

  Future<String> _openAi(
    AiSettings ai,
    String url,
    String prompt,
    double? temperature,
    Duration? timeout, {
    Map<String, Object?> extra = const {},
  }) async {
    final label = providerLabel(ai);
    final root = await _post(
      url,
      {
        'model': ai.modelId,
        'messages': [
          {'role': 'user', 'content': prompt},
        ],
        'temperature': ?temperature,
        ...extra,
      },
      label,
      timeout,
      {'Authorization': 'Bearer ${ai.apiKey}'},
    );
    final message = _array(root, 'choices').firstOrNull?['message'];
    final text = message is Map<String, Object?> ? _str(message, 'content') : '';
    if (text.isEmpty) throw AiException('No content in $label response');
    return text;
  }

  Future<String> _anthropic(AiSettings ai, String prompt, Duration? timeout) async {
    final thinking = ai.customThinking;
    Map<String, Object?> root;
    try {
      root = await _postAnthropic(ai, anthropicBody(ai.modelId, prompt, thinking, budgetThinking: false), timeout);
    } on AiException catch (error) {
      if (thinking == AiThinking.off || !rejectsAdaptiveThinking(error)) rethrow;
      root = await _postAnthropic(ai, anthropicBody(ai.modelId, prompt, thinking, budgetThinking: true), timeout);
    }
    final label = providerLabel(ai);
    // A reply cut at the cap is not an answer: a caller would cache half a translation.
    if (_str(root, 'stop_reason') == 'max_tokens') {
      throw AiException('$label reply was cut off at the output limit');
    }
    final text = _array(root, 'content').where((b) => _str(b, 'type') == 'text').map((b) => _str(b, 'text')).join();
    if (text.isEmpty) throw AiException('No content in $label response');
    return text;
  }

  Future<Map<String, Object?>> _postAnthropic(AiSettings ai, Map<String, Object?> body, Duration? timeout) => _post(
    '${anthropicBase(trimBase(ai.customBaseUrl))}/messages',
    body,
    providerLabel(ai),
    timeout,
    {'x-api-key': ai.apiKey, 'anthropic-version': _anthropicVersion},
  );

  Future<Map<String, Object?>> _post(
    String url,
    Map<String, Object?> body,
    String label,
    Duration? timeout,
    Map<String, String> headers,
  ) => _send(
    () => _client.post(
      Uri.parse(url),
      headers: {'content-type': 'application/json', ...headers},
      body: utf8.encode(jsonEncode(body)),
    ),
    label,
    timeout,
  );

  Future<Map<String, Object?>> _send(Future<http.Response> Function() request, String label, Duration? timeout) async {
    final http.Response response;
    try {
      response = await request().timeout(timeout ?? defaultTimeout);
    } on TimeoutException {
      throw AiException('$label timed out');
    } on AiException {
      rethrow;
    } catch (e) {
      throw AiException('$e');
    }
    final text = utf8.decode(response.bodyBytes, allowMalformed: true);
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw AiException('$label error (${response.statusCode}): ${errorMessage(text)}', httpCode: response.statusCode);
    }
    if (text.isEmpty) throw AiException('Empty response from $label');
    try {
      final root = jsonDecode(text);
      if (root is Map<String, Object?>) return root;
    } catch (_) {}
    throw AiException('Unreadable response from $label');
  }

  /// The service's own message for an error body; else its first 200 characters.
  static String errorMessage(String body) {
    try {
      final root = jsonDecode(body);
      if (root is Map) {
        final error = root['error'];
        if (error is Map && error['message'] is String && (error['message'] as String).trim().isNotEmpty) {
          return error['message'] as String;
        }
        // Brave names the reason `detail`.
        if (error is Map && error['detail'] is String && (error['detail'] as String).trim().isNotEmpty) {
          return error['detail'] as String;
        }
        if (error is String) return error;
        final message = root['message'];
        if (message is String && message.trim().isNotEmpty) return message;
        if (root is Map<String, Object?>) {
          if (doubaoError(root) case final doubao?) return doubao;
        }
      }
    } catch (_) {}
    return _take(body, 200);
  }

  /// 豆包搜索's refusal, "Message (Code)", from ResponseMetadata.Error; null when there is none.
  static String? doubaoError(Map<String, Object?> root) {
    final metadata = root['ResponseMetadata'];
    final error = metadata is Map<String, Object?> ? metadata['Error'] : null;
    if (error is! Map<String, Object?>) return null;
    final message = _str(error, 'Message');
    final code = _str(error, 'Code').isEmpty ? _str(error, 'CodeN') : _str(error, 'Code');
    if (message.isEmpty && code.isEmpty) return null;
    return code.isEmpty ? message : '${message.isEmpty ? code : message} ($code)';
  }

  /// What a service is called in errors: its name, or Custom's model.
  static String providerLabel(AiSettings ai) => switch (ai.provider) {
    AiProvider.gemini => 'Gemini',
    AiProvider.deepSeek => 'DeepSeek',
    AiProvider.qwen => 'Qwen',
    AiProvider.custom => ai.modelId.trim().isEmpty ? 'Custom' : ai.modelId,
  };

  static String? reasoningEffort(AiThinking thinking) => switch (thinking) {
    AiThinking.off => null,
    AiThinking.low => 'low',
    AiThinking.medium => 'medium',
    AiThinking.high => 'high',
  };

  /// The Messages API body. No temperature: Claude Opus 4.7 and later answer it with a
  /// 400. Thinking is adaptive with an effort level; 「不思考」 leaves both out (models
  /// that always think then run at their default). [budgetThinking] is the shape
  /// models before Claude 4.6 take instead: a fixed thinking budget.
  static Map<String, Object?> anthropicBody(
    String model,
    String prompt,
    AiThinking thinking, {
    required bool budgetThinking,
  }) {
    final effort = reasoningEffort(thinking);
    return {
      'model': model,
      'max_tokens': anthropicMaxTokens,
      'messages': [
        {'role': 'user', 'content': prompt},
      ],
      if (effort != null && budgetThinking) 'thinking': {'type': 'enabled', 'budget_tokens': _thinkingBudget(thinking)},
      if (effort != null && !budgetThinking) ...{
        'thinking': {'type': 'adaptive'},
        'output_config': {'effort': effort},
      },
    };
  }

  static int _thinkingBudget(AiThinking thinking) => switch (thinking) {
    AiThinking.off => 0,
    AiThinking.low => 1024,
    AiThinking.medium => 4096,
    AiThinking.high => 8192,
  };

  static const List<String> _adaptiveThinkingWords = ['thinking', 'adaptive', 'effort', 'output_config'];

  /// A 400 that names the thinking fields: the model (Claude before 4.6) or the gateway
  /// doesn't know adaptive thinking. Any other 400 is the caller's to see as it is.
  static bool rejectsAdaptiveThinking(AiException error) {
    final message = error.message.toLowerCase();
    return error.httpCode == 400 && _adaptiveThinkingWords.any(message.contains);
  }

  /// A base URL without trailing slashes.
  static String trimBase(String base) => base.trim().replaceFirst(RegExp(r'/+$'), '');

  /// Anthropic's API lives under /v1; accept a base URL with or without it.
  static String anthropicBase(String base) => base.endsWith('/v1') ? base : '$base/v1';

  /// The words that find credits pages in the song's own language, picked
  /// from the script of its title and artist (a Latin name gets English).
  static String creditsKeywords(String title, String artist) {
    final text = title + artist;
    bool any(int from, int to) => text.runes.any((r) => r >= from && r <= to);
    if (any(0xAC00, 0xD7A3)) return '작사 작곡 프로듀서';
    if (any(0x3040, 0x30FF)) return '作詞 作曲 編曲';
    if (any(0x4E00, 0x9FFF)) return '作詞 作曲 製作人';
    return 'songwriting credits producer';
  }

  /// Pages for the prompt: deduplicated by URL, numbered, each cut to [maxPageChars].
  static String contextBlock(List<AiSearchHit> hits) {
    final seen = <String>{};
    final unique = [
      for (final hit in hits)
        if (seen.add(hit.url)) hit,
    ].take(maxPagesInPrompt).toList();
    return [
      for (var i = 0; i < unique.length; i++)
        '[${i + 1}] ${unique[i].title} (${unique[i].url})\n${_take(unique[i].text, maxPageChars)}',
    ].join('\n\n');
  }
}

String _take(String text, int n) => text.length > n ? text.substring(0, n) : text;

List<Map<String, Object?>> _array(Map<String, Object?> root, String key) {
  final value = root[key];
  return value is List ? value.whereType<Map<String, Object?>>().toList() : const [];
}

List<String> _strings(Map<String, Object?> root, String key) {
  final value = root[key];
  return value is List ? value.whereType<String>().toList() : const [];
}

String _str(Map<String, Object?> root, String key) {
  final value = root[key];
  return switch (value) {
    String() => value,
    num() || bool() => '$value',
    _ => '',
  };
}

final aiClientProvider = Provider<AiClient>((ref) => HttpAiClient());
