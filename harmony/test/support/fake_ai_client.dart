import 'package:yoin/data/ai/ai_client.dart';
import 'package:yoin/data/ai/ai_settings.dart';

/// An [AiClient] that answers [reply] and returns [hits] for every search.
class FakeAiClient implements AiClient {
  FakeAiClient({this.reply = '', this.hits = const [], this.models = const []});

  String reply;
  List<AiSearchHit> hits;
  List<String> models;
  Object? error;
  final prompts = <String>[];
  final useSearch = <bool>[];
  final queries = <String>[];

  @override
  Future<String> generate(
    AiSettings ai,
    String prompt, {
    required double temperature,
    required bool useSearch,
    Duration? timeout,
  }) async {
    prompts.add(prompt);
    this.useSearch.add(useSearch);
    if (error case final e?) throw e;
    return reply;
  }

  @override
  Future<List<AiSearchHit>> search(AiSettings ai, String query) async {
    queries.add(query);
    return hits;
  }

  @override
  Future<List<String>> listModels(AiApiFormat format, String baseUrl, String apiKey) async {
    if (error case final e?) throw e;
    return models;
  }
}
