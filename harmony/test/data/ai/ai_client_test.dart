import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:yoin/data/ai/ai_client.dart';
import 'package:yoin/data/ai/ai_settings.dart';

/// Android AiClient: the request each service gets, how its answer is read,
/// the search APIs, Custom's model list and the errors.
void main() {
  late List<http.Request> calls;
  late List<http.Response> replies;

  HttpAiClient client() => HttpAiClient(
    client: MockClient((request) async {
      calls.add(request);
      return replies.length > 1 ? replies.removeAt(0) : replies.single;
    }),
  );

  http.Response json(Object body, [int status = 200]) => http.Response.bytes(utf8.encode(jsonEncode(body)), status);

  Map<String, Object?> body(http.Request r) => jsonDecode(r.body) as Map<String, Object?>;

  final openAiReply = {
    'choices': [
      {
        'message': {'content': 'hi'},
      },
    ],
  };

  setUp(() {
    calls = [];
    replies = [];
  });

  group('generate', () {
    test('should_askGoogleSearch_when_geminiSearches', () async {
      replies = [
        json({
          'candidates': [
            {
              'content': {
                'parts': [
                  {'text': 'a'},
                  {'text': 'b'},
                ],
              },
            },
          ],
        }),
      ];
      final text = await client().generate(
        const AiSettings(geminiKey: 'g', tier: AiTier.best),
        'p',
        temperature: 0.8,
        useSearch: true,
      );
      expect(text, 'ab');
      final call = calls.single;
      expect(call.url.toString(), contains('/v1beta/models/${AiSettings.geminiBest}:generateContent'));
      expect(call.headers['x-goog-api-key'], 'g');
      expect(body(call)['tools'], [
        {'googleSearch': <String, Object?>{}},
      ]);

      await client().generate(const AiSettings(geminiKey: 'g'), 'p', temperature: 0.8, useSearch: false);
      expect(body(calls.last).containsKey('tools'), isFalse);
    });

    test('should_turnThinkingOff_when_deepSeekAnswers', () async {
      replies = [json(openAiReply)];
      final text = await client().generate(
        const AiSettings(provider: AiProvider.deepSeek, deepSeekKey: 'd'),
        'p',
        temperature: 0.8,
        useSearch: true,
      );
      expect(text, 'hi');
      final call = calls.single;
      expect(call.url.toString(), '${AiSettings.deepSeekBaseUrl}/chat/completions');
      expect(call.headers['Authorization'], 'Bearer d');
      expect(body(call)['model'], AiSettings.deepSeekModel);
      expect(body(call)['thinking'], {'type': 'disabled'});
      expect(body(call)['temperature'], 0.8);
    });

    test('should_forceQwensOwnSearch_when_searchIsWanted', () async {
      replies = [json(openAiReply)];
      const qwen = AiSettings(provider: AiProvider.qwen, qwenKey: 'q', tier: AiTier.best);
      await client().generate(qwen, 'p', temperature: 0.8, useSearch: true);
      expect(calls.single.url.toString(), '${AiSettings.qwenBaseUrl}/chat/completions');
      expect(body(calls.single)['model'], AiSettings.qwenModel, reason: 'Qwen has one tier');
      expect(body(calls.single)['enable_thinking'], false);
      expect(body(calls.single)['enable_search'], true);
      expect(body(calls.single)['search_options'], {'forced_search': true});

      await client().generate(qwen, 'p', temperature: 0.8, useSearch: false);
      expect(body(calls.last).containsKey('enable_search'), isFalse);
    });

    test('should_sendNoTemperature_when_customOpenAiAnswers', () async {
      replies = [json(openAiReply)];
      await client().generate(
        const AiSettings(
          provider: AiProvider.custom,
          customKey: 'c',
          customBaseUrl: ' https://gw.example.com/v1/ ',
          customModel: 'o9',
          customThinking: AiThinking.medium,
        ),
        'p',
        temperature: 0.8,
        useSearch: true,
      );
      final call = calls.single;
      expect(call.url.toString(), 'https://gw.example.com/v1/chat/completions');
      expect(body(call).containsKey('temperature'), isFalse);
      expect(body(call)['reasoning_effort'], 'medium');
      expect(body(call)['model'], 'o9');
    });

    test('should_speakMessagesApi_when_customAnthropicAnswers', () async {
      replies = [
        json({
          'content': [
            {'type': 'thinking', 'thinking': '…'},
            {'type': 'text', 'text': 'answer'},
          ],
          'stop_reason': 'end_turn',
        }),
      ];
      final text = await client().generate(
        const AiSettings(
          provider: AiProvider.custom,
          customFormat: AiApiFormat.anthropic,
          customKey: 'c',
          customBaseUrl: 'https://api.anthropic.com',
          customModel: 'claude-x',
          customThinking: AiThinking.low,
        ),
        'p',
        temperature: 0.8,
        useSearch: false,
      );
      expect(text, 'answer');
      final call = calls.single;
      expect(call.url.toString(), 'https://api.anthropic.com/v1/messages');
      expect(call.headers['x-api-key'], 'c');
      expect(call.headers['anthropic-version'], '2023-06-01');
      expect(body(call)['max_tokens'], HttpAiClient.anthropicMaxTokens);
      expect(body(call)['thinking'], {'type': 'adaptive'});
      expect(body(call)['output_config'], {'effort': 'low'});
      expect(body(call).containsKey('temperature'), isFalse);
    });

    test('should_retryWithBudget_when_adaptiveThinkingIsRejected', () async {
      replies = [
        json({
          'error': {'message': 'thinking.type: adaptive is not supported'},
        }, 400),
        json({
          'content': [
            {'type': 'text', 'text': 'ok'},
          ],
        }),
      ];
      final text = await client().generate(
        const AiSettings(
          provider: AiProvider.custom,
          customFormat: AiApiFormat.anthropic,
          customKey: 'c',
          customBaseUrl: 'https://gw.example.com/v1',
          customModel: 'claude-old',
          customThinking: AiThinking.high,
        ),
        'p',
        temperature: 0.8,
        useSearch: false,
      );
      expect(text, 'ok');
      expect(calls, hasLength(2));
      expect(calls.first.url.toString(), 'https://gw.example.com/v1/messages');
      expect(body(calls.last)['thinking'], {'type': 'enabled', 'budget_tokens': 8192});
      expect(body(calls.last).containsKey('output_config'), isFalse);
    });

    test('should_fail_when_anthropicReplyIsCutOff', () async {
      replies = [
        json({
          'content': [
            {'type': 'text', 'text': 'half'},
          ],
          'stop_reason': 'max_tokens',
        }),
      ];
      expect(
        client().generate(
          const AiSettings(
            provider: AiProvider.custom,
            customFormat: AiApiFormat.anthropic,
            customKey: 'c',
            customBaseUrl: 'https://x',
            customModel: 'm',
          ),
          'p',
          temperature: 0.8,
          useSearch: false,
        ),
        throwsA(isA<AiException>().having((e) => e.message, 'message', 'm reply was cut off at the output limit')),
      );
    });

    test('should_nameServiceAndItsMessage_when_requestFails', () async {
      replies = [
        json({
          'error': {'message': 'Insufficient Balance'},
        }, 402),
      ];
      expect(
        client().generate(
          const AiSettings(provider: AiProvider.deepSeek, deepSeekKey: 'd'),
          'p',
          temperature: 0.8,
          useSearch: false,
        ),
        throwsA(
          isA<AiException>()
              .having((e) => e.message, 'message', 'DeepSeek error (402): Insufficient Balance')
              .having((e) => e.httpCode, 'httpCode', 402),
        ),
      );
    });
  });

  group('search', () {
    test('should_readFullPages_when_deepSeekSearches', () async {
      replies = [
        json({
          'results': [
            {'title': 'A', 'url': 'u1', 'content': 'x' * 3000},
            {'title': 'B', 'url': 'u2', 'summary': 'short'},
            {'title': 'C', 'url': 'u3', 'content': '  '},
          ],
        }),
      ];
      final hits = await client().search(const AiSettings(provider: AiProvider.deepSeek, deepSeekKey: 'd'), 'q' * 200);
      expect(hits.map((h) => h.url), ['u1', 'u2'], reason: 'pages without text are dropped');
      expect(hits.first.text.length, HttpAiClient.maxPageChars);
      expect(hits[1].text, 'short');
      final call = calls.single;
      expect(call.url.toString(), '${AiSettings.deepSeekBaseUrl}/v1/tools/search');
      expect(call.headers['Authorization'], 'Bearer d');
      expect((body(call)['query'] as String).length, 128);
      expect(body(call)['need_content'], true);
    });

    test('should_sendEachServiceItsOwnKey_when_customSearches', () async {
      replies = [
        json({
          'results': [
            {'title': 'T', 'url': 'u', 'text': 'body', 'content': 'chunk'},
          ],
        }),
      ];
      const custom = AiSettings(
        provider: AiProvider.custom,
        searchService: AiSearchService.exa,
        searchKeyExa: 'ek',
        searchKeyTavily: 'tk',
      );
      final exa = await client().search(custom, 'q');
      expect(exa.single.text, 'body');
      expect(calls.last.url.toString(), 'https://api.exa.ai/search');
      expect(calls.last.headers['x-api-key'], 'ek');
      expect(body(calls.last)['contents'], {
        'text': {'maxCharacters': HttpAiClient.maxPageChars},
      });

      final tavily = await client().search(custom.copyWith(searchService: AiSearchService.tavily), 'q');
      expect(tavily.single.text, 'chunk');
      expect(calls.last.url.toString(), 'https://api.tavily.com/search');
      expect(calls.last.headers['Authorization'], 'Bearer tk');
      expect(body(calls.last)['search_depth'], 'advanced');
      expect(body(calls.last)['chunks_per_source'], 3);

      expect(await client().search(custom.copyWith(searchService: AiSearchService.none), 'q'), isEmpty);
      expect(calls, hasLength(2));
    });

    AiSettings searchWith(AiSearchService service, [String key = 's-key']) => AiSettings(
      provider: AiProvider.custom,
      customKey: 'sk',
      customBaseUrl: 'https://x/v1',
      customModel: 'm',
      searchService: service,
    ).withSearchKey(service, key);

    test('should_readDoubaosWebResults_when_itAnswers', () async {
      replies = [
        json({
          'ResponseMetadata': {'RequestId': 'r1'},
          'Result': {
            'ResultCount': 3,
            'WebResults': [
              {'Title': 'Lemon', 'Url': 'https://a', 'Content': 'x' * 2500, 'Summary': 's', 'SiteName': 'A'},
              {'Title': 'Lemon 2', 'Url': 'https://b', 'Content': '', 'Summary': 'summary only'},
              {'Title': 'Empty', 'Url': 'https://c', 'Content': '', 'Summary': ''},
            ],
          },
        }),
      ];
      final hits = await client().search(searchWith(AiSearchService.doubao, 'db-key'), 'q' * 150);
      expect(hits.map((h) => h.url), ['https://a', 'https://b']);
      expect(hits[0].text.length, HttpAiClient.maxPageChars);
      expect(hits[1].text, 'summary only');
      final call = calls.single;
      expect(call.url.toString(), 'https://open.feedcoopapi.com/search_api/web_search');
      expect(call.headers['Authorization'], 'Bearer db-key');
      expect((body(call)['Query'] as String).length, 100);
      expect(body(call)['SearchType'], 'web');
      expect(body(call)['Count'], HttpAiClient.searchResults);
      expect(body(call)['Filter'], {'NeedContent': true});
    });

    test('should_surfaceDoubaosMessageAndCode_when_itRefuses', () async {
      // A refused call still answers 200.
      replies = [
        json({
          'ResponseMetadata': {
            'RequestId': 'r2',
            'Error': {'CodeN': 10400, 'Code': 'InvalidApiKey', 'Message': 'api key is invalid'},
          },
          'Result': null,
        }),
      ];
      await expectLater(
        client().search(searchWith(AiSearchService.doubao), 'q'),
        throwsA(
          isA<AiException>().having((e) => e.message, 'message', 'Doubao error: api key is invalid (InvalidApiKey)'),
        ),
        reason: 'a refused search must not come back as no pages',
      );
    });

    test('should_surfaceDoubaosMessage_when_itRefusesWithAnHttpError', () async {
      replies = [
        json({
          'ResponseMetadata': {
            'Error': {'CodeN': 10401, 'Code': 'Unauthorized', 'Message': 'no access'},
          },
        }, 401),
      ];
      await expectLater(
        client().search(searchWith(AiSearchService.doubao), 'q'),
        throwsA(
          isA<AiException>()
              .having((e) => e.httpCode, 'httpCode', 401)
              .having((e) => e.message, 'message', contains('no access (Unauthorized)')),
        ),
      );
    });

    test('should_readBravesLlmContext_when_thePlanHasIt', () async {
      replies = [
        json({
          'grounding': {
            'generic': [
              {
                'url': 'https://a',
                'title': 'Lemon',
                'snippets': ['Lyrics by Kenshi Yonezu', 'Produced by him'],
              },
              {'url': 'https://b', 'title': 'No text', 'snippets': <String>[]},
            ],
          },
          'sources': <String, Object?>{},
        }),
      ];
      final hits = await client().search(searchWith(AiSearchService.brave, 'br-key'), 'Lemon credits');
      expect(hits.single.title, 'Lemon');
      expect(hits.single.url, 'https://a');
      expect(hits.single.text, 'Lyrics by Kenshi Yonezu\nProduced by him');
      final call = calls.single;
      expect(call.method, 'GET');
      expect(call.url.host, 'api.search.brave.com');
      expect(call.url.path, '/res/v1/llm/context');
      expect(call.url.queryParameters['q'], 'Lemon credits');
      expect(call.url.queryParameters['count'], '${HttpAiClient.searchResults}');
      expect(call.headers['X-Subscription-Token'], 'br-key');
      expect(call.headers['Accept'], 'application/json');
    });

    test('should_fallBackToBravesWebSearch_when_theLlmContextIsNotOnThePlan', () async {
      replies = [
        json({
          'type': 'ErrorResponse',
          'error': {'code': 'OPTION_NOT_IN_PLAN', 'detail': 'not in plan', 'status': 422},
        }, 422),
        json({
          'type': 'search',
          'web': {
            'results': [
              {
                'title': 'Lemon',
                'url': 'https://a',
                'description': 'A 2018 single.',
                'extra_snippets': ['Written by Yonezu.'],
              },
              {'title': 'Bare', 'url': 'https://b', 'description': ''},
            ],
          },
        }),
      ];
      final hits = await client().search(searchWith(AiSearchService.brave), 'q');
      expect(hits.single.url, 'https://a');
      expect(hits.single.text, 'A 2018 single.\nWritten by Yonezu.');
      expect(calls.first.url.path, '/res/v1/llm/context');
      expect(calls.last.url.path, '/res/v1/web/search');
      expect(calls.last.url.queryParameters['extra_snippets'], 'true');
      expect(calls.last.url.queryParameters['count'], '${HttpAiClient.searchResults}');
    });

    test('should_notFallBack_when_braveRejectsTheKey', () async {
      replies = [
        json({
          'type': 'ErrorResponse',
          'error': {'code': 'SUBSCRIPTION_TOKEN_INVALID', 'detail': 'bad token'},
        }, 401),
      ];
      await expectLater(
        client().search(searchWith(AiSearchService.brave), 'q'),
        throwsA(
          isA<AiException>()
              .having((e) => e.httpCode, 'httpCode', 401)
              .having((e) => e.message, 'message', contains('bad token')),
        ),
      );
      expect(calls, hasLength(1));
    });
  });

  group('listModels', () {
    test('should_listSortedIds_when_endpointAnswers', () async {
      replies = [
        json({
          'data': [
            {'id': 'b'},
            {'id': 'a'},
            {'id': ' '},
          ],
        }),
      ];
      expect(await client().listModels(AiApiFormat.openAi, 'https://gw.example.com/v1/', ' k '), ['a', 'b']);
      expect(calls.last.method, 'GET');
      expect(calls.last.url.toString(), 'https://gw.example.com/v1/models');
      expect(calls.last.headers['Authorization'], 'Bearer k');

      await client().listModels(AiApiFormat.anthropic, 'https://api.anthropic.com', 'k');
      expect(calls.last.url.toString(), 'https://api.anthropic.com/v1/models');
      expect(calls.last.headers['x-api-key'], 'k');
    });
  });

  group('helpers', () {
    test('should_pickCreditsWordsInTheSongsScript_when_searching', () {
      expect(HttpAiClient.creditsKeywords('봄날', 'BTS'), '작사 작곡 프로듀서');
      expect(HttpAiClient.creditsKeywords('うっせぇわ', 'Ado'), '作詞 作曲 編曲');
      expect(HttpAiClient.creditsKeywords('晴天', '周杰伦'), '作詞 作曲 製作人');
      expect(HttpAiClient.creditsKeywords('Yesterday', 'The Beatles'), 'songwriting credits producer');
    });

    test('should_numberUniquePages_when_buildingContext', () {
      final block = HttpAiClient.contextBlock(const [
        AiSearchHit(title: 'A', url: 'u1', text: 'one'),
        AiSearchHit(title: 'A again', url: 'u1', text: 'dup'),
        AiSearchHit(title: 'B', url: 'u2', text: 'two'),
      ]);
      expect(block, '[1] A (u1)\none\n\n[2] B (u2)\ntwo');
    });

    test('should_readServiceMessage_when_errorBodyIsJson', () {
      expect(HttpAiClient.errorMessage('{"error":"nope"}'), 'nope');
      expect(HttpAiClient.errorMessage('{"message":"bad key"}'), 'bad key');
      // Brave names the reason `detail`; 豆包 puts it in ResponseMetadata.
      expect(HttpAiClient.errorMessage('{"error":{"code":"X","detail":"bad token"}}'), 'bad token');
      expect(
        HttpAiClient.errorMessage('{"ResponseMetadata":{"Error":{"Code":"Unauthorized","Message":"no access"}}}'),
        'no access (Unauthorized)',
      );
      expect(HttpAiClient.errorMessage('x' * 300), 'x' * 200);
    });

    test('should_retryOnlyThinking400s_when_judgingAnError', () {
      expect(
        HttpAiClient.rejectsAdaptiveThinking(const AiException('unknown field: output_config', httpCode: 400)),
        isTrue,
      );
      expect(HttpAiClient.rejectsAdaptiveThinking(const AiException('model not found', httpCode: 400)), isFalse);
      expect(HttpAiClient.rejectsAdaptiveThinking(const AiException('thinking', httpCode: 500)), isFalse);
    });
  });
}
