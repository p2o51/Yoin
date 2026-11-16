/// NeoDB REST client (Android NeoDBApi.kt), OpenAPI 0.14.0.5's smallest
/// subset: album search, the user's own Mark and Review on an item, others'
/// posts, and per-instance OAuth app registration and code exchange. Every
/// call carries the user's token as a Bearer header when there is one; the
/// instance is the user's choice (neodb.social by default).
///
/// No merging here: [NeoDbSyncService] reads, merges and writes back, so the
/// tags and words other clients wrote on NeoDB are never cleared.
library;

import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;

import 'neodb_models.dart';

class NeoDbApi {
  NeoDbApi(this._client, {this.timeout = const Duration(seconds: 30)});

  final http.Client _client;
  final Duration timeout;

  static const _json = 'application/json';

  // ---- OAuth ----

  Future<OAuthClientRegistration> registerOAuthApp({
    required String instance,
    required String redirectUri,
    required String clientName,
    required String website,
  }) async {
    final body = await _send(
      'POST',
      _url(instance, '/api/v1/apps'),
      form: {'client_name': clientName, 'redirect_uris': redirectUri, 'website': website},
    );
    return OAuthClientRegistration.fromJson(_object(body));
  }

  Future<OAuthTokenResponse> exchangeOAuthCode({
    required String instance,
    required String clientId,
    required String clientSecret,
    required String code,
    required String redirectUri,
  }) async {
    final body = await _send(
      'POST',
      _url(instance, '/oauth/token'),
      form: {
        'client_id': clientId,
        'client_secret': clientSecret,
        'code': code,
        'redirect_uri': redirectUri,
        'grant_type': 'authorization_code',
      },
    );
    return OAuthTokenResponse.fromJson(_object(body));
  }

  // ---- catalog ----

  /// Album search, `category=music`; an older instance that turns that
  /// category away (422 naming the category) is asked again with the legacy
  /// `category=album`.
  Future<List<CatalogItem>> searchAlbum(String instance, String token, String query) async {
    try {
      return await _searchCatalog(instance, token, query, 'music');
    } on NeoDbException catch (e) {
      final legacy = e.code == 422 && e.message.toLowerCase().contains('category');
      if (!legacy) rethrow;
      debugPrint('NeoDbApi: category=music rejected by ${_base(instance)}, retrying category=album');
      return _searchCatalog(instance, token, query, 'album');
    }
  }

  /// Album search that also works signed out (the catalog is public).
  Future<List<CatalogItem>> searchAlbumPublic(String instance, String? token, String query) =>
      _searchCatalog(instance, token, query, 'music');

  Future<List<CatalogItem>> _searchCatalog(String instance, String? token, String query, String category) async {
    final body = await _send(
      'GET',
      _url(instance, '/api/catalog/search?category=$category&query=${_encode(query)}'),
      token: token,
    );
    final data = _object(body)['data'];
    return data is List
        ? [
            for (final e in data)
              if (e is Map) CatalogItem.fromJson(e.cast<String, Object?>()),
          ]
        : const [];
  }

  // ---- the user's Mark ----

  /// The user's shelf entry for [itemUuid]; null when there is none (404).
  Future<ShelfItem?> getShelfItem(String instance, String token, String itemUuid) async {
    final body = await _send('GET', _url(instance, '/api/me/shelf/item/$itemUuid'), token: token, nullOn404: true);
    if (body == null || body.trim().isEmpty) return null;
    return ShelfItem.fromJson(_object(body));
  }

  /// Writes the whole Mark (the body is flat: MarkInSchema, no `mark` envelope).
  Future<void> postShelfMark(String instance, String token, String itemUuid, ShelfMarkRequest body) async {
    await _send('POST', _url(instance, '/api/me/shelf/item/$itemUuid'), token: token, json: body.toJson());
  }

  // ---- the user's Review (item-centred: one per user per item) ----

  /// The user's review on [itemUuid]; null when they wrote none (404).
  Future<ReviewResponse?> getMyReviewForItem(String instance, String token, String itemUuid) async {
    final body = await _send('GET', _url(instance, '/api/me/review/item/$itemUuid'), token: token, nullOn404: true);
    if (body == null || body.trim().isEmpty) return null;
    return ReviewResponse.fromJson(_object(body));
  }

  /// Upserts the review (creating and overwriting are the same call).
  Future<ReviewResponse> postReview(String instance, String token, String itemUuid, ReviewRequest body) async {
    final text = await _send(
      'POST',
      _url(instance, '/api/me/review/item/$itemUuid'),
      token: token,
      json: body.toJson(),
    );
    if (text == null || text.trim().isEmpty) return const ReviewResponse();
    final decoded = _tryDecode(text);
    return decoded is Map ? ReviewResponse.fromJson(decoded.cast<String, Object?>()) : const ReviewResponse();
  }

  Future<void> deleteReview(String instance, String token, String itemUuid) async {
    await _send('DELETE', _url(instance, '/api/me/review/item/$itemUuid'), token: token, accept: false);
  }

  // ---- others' posts ----

  /// Others' posts on an item. Anonymous works (public posts); with [token]
  /// the caller also sees what its account may see. A merged item answers
  /// 302, which is followed to the item it was merged into.
  Future<NeoDbPostPage> listItemPosts(
    String instance,
    String? token,
    String itemUuid,
    int page, {
    String types = 'comment,review',
  }) async {
    final body = await _send(
      'GET',
      _url(instance, '/api/item/$itemUuid/posts/?type=${_encode(types)}&page=$page'),
      token: token,
    );
    return NeoDbPostPage.fromJson(_object(body));
  }

  // ---- plumbing ----

  /// The body of a successful response; null for a 404 when [nullOn404].
  Future<String?> _send(
    String method,
    Uri url, {
    String? token,
    Map<String, String>? form,
    JsonObject? json,
    bool nullOn404 = false,
    bool accept = true,
  }) async {
    final request = http.Request(method, url);
    if (accept) request.headers['Accept'] = _json;
    if (token != null && token.trim().isNotEmpty) request.headers['Authorization'] = 'Bearer $token';
    if (form != null) request.bodyFields = form;
    if (json != null) {
      request.headers['Content-Type'] = '$_json; charset=utf-8';
      request.body = jsonEncode(json);
    }
    final streamed = await _client.send(request).timeout(timeout);
    final response = await http.Response.fromStream(streamed).timeout(timeout);
    final body = utf8.decode(response.bodyBytes, allowMalformed: true);
    if (nullOn404 && response.statusCode == 404) return null;
    if (response.statusCode < 200 || response.statusCode >= 300) {
      final error = NeoDbException(response.statusCode, parseErrorMessage(body) ?? _snippet(body, 200), rawBody: body);
      debugPrint(
        'NeoDbApi: request failed: $method ${url.path} code=${error.code} '
        'message=${error.message} body=${_snippet(body.replaceAll(RegExp(r'[\r\n]'), ' ').trim(), 240)}',
      );
      throw error;
    }
    return body;
  }

  JsonObject _object(String? body) {
    final decoded = _tryDecode(body ?? '');
    if (decoded is Map) return decoded.cast<String, Object?>();
    throw const FormatException('NeoDB answered with no JSON object');
  }

  static Object? _tryDecode(String body) {
    try {
      return jsonDecode(body);
    } on FormatException {
      return null;
    }
  }

  /// The server's own words for a failure: `detail` (a string, or
  /// django-ninja's list of `{loc, msg}` as "loc.path: msg | …"), else `error`.
  @visibleForTesting
  static String? parseErrorMessage(String body) {
    final root = _tryDecode(body);
    if (root is! Map) return null;
    final detail = root['detail'];
    if (detail is String) return detail;
    if (detail is List) {
      final messages = [for (final e in detail) ?_detailEntry(e)];
      if (messages.isNotEmpty) return messages.join(' | ');
    } else if (detail is Map) {
      final entry = _detailEntry(detail);
      if (entry != null) return entry;
    }
    final error = root['error'];
    return error is String ? error : null;
  }

  static String? _detailEntry(Object? entry) {
    if (entry is! Map) return entry is String ? entry : null;
    final msg = entry['msg'];
    final locList = entry['loc'];
    final loc = locList is List
        ? [
            for (final t in locList)
              if (t is String || t is num) '$t',
          ].join('.')
        : '';
    if (msg is String && msg.isNotEmpty && loc.isNotEmpty) return '$loc: $msg';
    if (msg is String && msg.isNotEmpty) return msg;
    return null;
  }

  static String _snippet(String s, int limit) => s.length <= limit ? s : s.substring(0, limit);

  static String _base(String instance) => instance.replaceAll(RegExp(r'/+$'), '');

  static Uri _url(String instance, String path) =>
      Uri.parse(_base(instance) + (path.startsWith('/') ? path : '/$path'));

  static String _encode(String value) => Uri.encodeQueryComponent(value);
}
