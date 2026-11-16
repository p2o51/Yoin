/// NeoDB OpenAPI 0.14.0.5 subset (Android data/integration/neodb/NeoDBModels.kt):
/// album Marks and Reviews, catalog search, others' posts, and the
/// Mastodon-style OAuth app registration. Every field is optional with a
/// default, so an older instance's payload never fails to parse.
library;

import 'package:flutter/foundation.dart';

typedef JsonObject = Map<String, Object?>;

String? _string(Object? v) => v is String ? v : null;
int? _int(Object? v) => v is num ? v.toInt() : null;
double? _double(Object? v) => v is num ? v.toDouble() : null;
List<String> _strings(Object? v) => v is List
    ? [
        for (final e in v)
          if (e is String) e,
      ]
    : const [];

/// A failed NeoDB call: the HTTP status, the server's message (its `detail`
/// or `error`, else the body's start) and the raw body.
final class NeoDbException implements Exception {
  const NeoDbException(this.code, this.message, {this.rawBody = ''});

  final int code;
  final String message;
  final String rawBody;

  @override
  String toString() => message;
}

/// A shelf entry of the signed-in user (`GET /api/me/shelf/item/{uuid}`).
@immutable
final class ShelfItem {
  const ShelfItem({
    this.uuid,
    this.shelfType,
    this.visibility = 0,
    this.item,
    this.ratingGrade,
    this.commentText,
    this.tags = const [],
  });

  final String? uuid;
  final String? shelfType;
  final int visibility;
  final CatalogItem? item;
  final int? ratingGrade;
  final String? commentText;
  final List<String> tags;

  static ShelfItem fromJson(JsonObject json) => ShelfItem(
    uuid: _string(json['uuid']),
    shelfType: _string(json['shelf_type']),
    visibility: _int(json['visibility']) ?? 0,
    item: json['item'] is Map ? CatalogItem.fromJson((json['item'] as Map).cast<String, Object?>()) : null,
    ratingGrade: _int(json['rating_grade']),
    commentText: _string(json['comment_text']),
    tags: _strings(json['tags']),
  );
}

/// A catalog item (search hit, item detail, a shelf's item; ShelfItem.Item).
@immutable
final class CatalogItem {
  const CatalogItem({
    this.uuid,
    this.displayTitle,
    this.category,
    this.title,
    this.url,
    this.artist = const [],
    this.rating,
    this.ratingCount,
    this.ratingDistribution,
    this.externalResources,
  });

  final String? uuid;
  final String? displayTitle;
  final String? category;
  final String? title;
  final String? url;
  final List<String> artist;
  final double? rating;
  final int? ratingCount;

  /// Five buckets, low to high, in percent.
  final List<int>? ratingDistribution;

  /// The item's links in other catalogues (Douban, Discogs, MusicBrainz…).
  final List<String>? externalResources;

  static CatalogItem fromJson(JsonObject json) {
    final distribution = json['rating_distribution'];
    final external = json['external_resources'];
    return CatalogItem(
      uuid: _string(json['uuid']),
      displayTitle: _string(json['display_title']),
      category: _string(json['category']),
      title: _string(json['title']),
      url: _string(json['url']),
      artist: _strings(json['artist']),
      rating: _double(json['rating']),
      ratingCount: _int(json['rating_count']),
      ratingDistribution: distribution is List
          ? [
              for (final e in distribution)
                if (e is num) e.toInt(),
            ]
          : null,
      externalResources: external is List
          ? [
              for (final e in external)
                if (e is Map) _string(e['url']) ?? '',
            ]
          : null,
    );
  }
}

/// POST body for `/api/me/shelf/item/{uuid}` (MarkInSchema). `shelf_type` and
/// `visibility` are required and always sent; the rest is left out when it
/// holds its default, as Android's `encodeDefaults = false` JSON does.
@immutable
final class ShelfMarkRequest {
  const ShelfMarkRequest({
    required this.shelfType,
    required this.visibility,
    this.ratingGrade,
    this.commentText,
    this.tags = const [],
    this.postToFediverse = false,
    this.createdTime,
  });

  final String shelfType;
  final int visibility;
  final int? ratingGrade;
  final String? commentText;
  final List<String> tags;
  final bool postToFediverse;
  final String? createdTime;

  JsonObject toJson() => {
    'shelf_type': shelfType,
    'visibility': visibility,
    'rating_grade': ?ratingGrade,
    'comment_text': ?commentText,
    if (tags.isNotEmpty) 'tags': tags,
    if (postToFediverse) 'post_to_fediverse': true,
    'created_time': ?createdTime,
  };
}

@immutable
final class ReviewResponse {
  const ReviewResponse({this.uuid, this.visibility = 0, this.title, this.body, this.item});

  final String? uuid;
  final int visibility;
  final String? title;
  final String? body;
  final CatalogItem? item;

  static ReviewResponse fromJson(JsonObject json) => ReviewResponse(
    uuid: _string(json['uuid']),
    visibility: _int(json['visibility']) ?? 0,
    title: _string(json['title']),
    body: _string(json['body']),
    item: json['item'] is Map ? CatalogItem.fromJson((json['item'] as Map).cast<String, Object?>()) : null,
  );
}

/// `POST /api/me/review/item/{item_uuid}` (ReviewInSchema); visibility is required.
@immutable
final class ReviewRequest {
  const ReviewRequest({
    required this.visibility,
    required this.title,
    required this.body,
    this.postToFediverse = false,
  });

  final int visibility;
  final String title;
  final String body;
  final bool postToFediverse;

  JsonObject toJson() => {
    'visibility': visibility,
    'title': title,
    'body': body,
    if (postToFediverse) 'post_to_fediverse': true,
  };
}

/// `GET /api/item/{uuid}/posts/`: everyone's posts on an item. Posts are
/// Mastodon-shaped; NeoDB's meaning sits in `ext_neodb.relatedWith`.
@immutable
final class NeoDbPostPage {
  const NeoDbPostPage({this.data = const [], this.pages = 0, this.count = 0});

  final List<NeoDbPost> data;
  final int pages;
  final int count;

  static NeoDbPostPage fromJson(JsonObject json) {
    final data = json['data'];
    return NeoDbPostPage(
      data: data is List
          ? [
              for (final e in data)
                if (e is Map) NeoDbPost.fromJson(e.cast<String, Object?>()),
            ]
          : const [],
      pages: _int(json['pages']) ?? 0,
      count: _int(json['count']) ?? 0,
    );
  }
}

@immutable
final class NeoDbPost {
  const NeoDbPost({
    this.id = '',
    this.url,
    this.createdAt,
    this.sensitive = false,
    this.spoilerText,
    this.account,
    this.relatedWith = const [],
  });

  final String id;
  final String? url;
  final String? createdAt;
  final bool sensitive;
  final String? spoilerText;
  final NeoDbAccount? account;
  final List<JsonObject> relatedWith;

  static NeoDbPost fromJson(JsonObject json) {
    final ext = json['ext_neodb'];
    final related = ext is Map ? ext['relatedWith'] : null;
    return NeoDbPost(
      id: _string(json['id']) ?? '',
      url: _string(json['url']),
      createdAt: _string(json['created_at']),
      sensitive: json['sensitive'] == true,
      spoilerText: _string(json['spoiler_text']),
      account: json['account'] is Map ? NeoDbAccount.fromJson((json['account'] as Map).cast<String, Object?>()) : null,
      relatedWith: related is List
          ? [
              for (final e in related)
                if (e is Map) e.cast<String, Object?>(),
            ]
          : const [],
    );
  }
}

@immutable
final class NeoDbAccount {
  const NeoDbAccount({this.acct, this.username, this.displayName, this.avatar, this.url});

  final String? acct;
  final String? username;
  final String? displayName;
  final String? avatar;
  final String? url;

  static NeoDbAccount fromJson(JsonObject json) => NeoDbAccount(
    acct: _string(json['acct']),
    username: _string(json['username']),
    displayName: _string(json['display_name']),
    avatar: _string(json['avatar']),
    url: _string(json['url']),
  );
}

/// `POST /api/v1/apps`: a client registered on one instance for one redirect.
@immutable
final class OAuthClientRegistration {
  const OAuthClientRegistration({required this.clientId, required this.clientSecret});

  final String clientId;
  final String clientSecret;

  static OAuthClientRegistration fromJson(JsonObject json) {
    final id = _string(json['client_id']);
    final secret = _string(json['client_secret']);
    if (id == null || secret == null) throw const FormatException('NeoDB returned no client');
    return OAuthClientRegistration(clientId: id, clientSecret: secret);
  }
}

@immutable
final class OAuthTokenResponse {
  const OAuthTokenResponse({required this.accessToken, this.tokenType = 'Bearer', this.scope});

  final String accessToken;
  final String tokenType;
  final String? scope;

  static OAuthTokenResponse fromJson(JsonObject json) {
    final token = _string(json['access_token']);
    if (token == null) throw const FormatException('NeoDB returned no access token');
    return OAuthTokenResponse(
      accessToken: token,
      tokenType: _string(json['token_type']) ?? 'Bearer',
      scope: _string(json['scope']),
    );
  }
}
