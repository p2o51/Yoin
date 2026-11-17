/// Reads what other listeners on NeoDB wrote about an album (the "别人怎么说"
/// page; Android NeoDbVoicesService.kt). Works signed out: NeoDB's catalogue
/// and public posts need no token, so signed out it reads neodb.social
/// anonymously; signed in, the user's own instance with their token.
///
/// It never writes and never touches the sync mapping: the page finds its own
/// item each time it opens.
library;

import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;

import 'neodb_api.dart';
import 'neodb_config.dart';
import 'neodb_models.dart';
import 'neodb_voices.dart';

class NeoDbVoicesService {
  NeoDbVoicesService({required NeoDbApi api, required NeoDbConfigStore config, required http.Client client})
    : _api = api,
      _config = config,
      _client = client;

  final NeoDbApi _api;
  final NeoDbConfigStore _config;
  final http.Client _client;

  /// Both catalogues ask callers to name themselves.
  static const userAgent = 'Yoin/1 ( https://github.com/p2o51/Yoin )';

  /// A token only means something on its own instance.
  Future<({String instance, String? token})> _access() async {
    final session = await _config.session();
    return session == null
        ? (instance: NeoDbConfigStore.defaultInstance, token: null)
        : (instance: session.instance, token: session.token);
  }

  /// [call] with the stored token; turned away with it (401/403), once more
  /// without, on the same instance: a token revoked on NeoDB's site stays in
  /// the app, and the page reads signed out anyway.
  Future<T> _read<T>(String? token, Future<T> Function(String? token) call) async {
    try {
      return await call(token);
    } on NeoDbException catch (e) {
      if (token == null || (e.code != 401 && e.code != 403)) rethrow;
      return call(null);
    }
  }

  /// The NeoDB item for the album with its spread and outside links; null
  /// when NeoDB has nothing like it. Throws when NeoDB can't be reached.
  Future<NeoDbAlbumVoices?> findAlbum(String albumName, String? artist) async {
    final access = await _access();
    final query = NeoDbVoicesParsing.query(albumName, artist);
    if (query.trim().isEmpty) return null;
    final items = await _read(access.token, (t) => _api.searchAlbumPublic(access.instance, t, query));
    final item = NeoDbVoicesParsing.pickAlbumItem(albumName, artist, items);
    return item == null ? null : NeoDbVoicesParsing.albumVoices(access.instance, item);
  }

  /// One page (1-based, 20 posts) of short comments and reviews, newest first.
  Future<NeoDbVoicePage> voices(String itemUuid, int page) async {
    final access = await _access();
    final posts = await _read(access.token, (t) => _api.listItemPosts(access.instance, t, itemUuid, page));
    return NeoDbVoicesParsing.page(page, posts);
  }

  /// Where to search NeoDB when no item matched.
  Future<String> searchUrl(String albumName, String? artist) async =>
      NeoDbVoicesParsing.neoDbSearchUrl((await _access()).instance, albumName, artist);

  /// Scores from catalogues that publish them (Discogs, MusicBrainz), found
  /// through the links NeoDB keeps on the item. RYM and AOTY have no API, so
  /// they are links on the page only. Each source fails quietly.
  Future<List<NeoDbReferenceScore>> referenceScores(NeoDbAlbumVoices album) async {
    Future<NeoDbReferenceScore?> quiet(String source, Future<NeoDbReferenceScore?> Function() read) async {
      try {
        return await read();
      } catch (e) {
        debugPrint('NeoDbVoices: reference score from $source failed: ${e.runtimeType}');
        return null;
      }
    }

    final discogs = album.discogsUrl;
    final musicBrainz = album.musicBrainzUrl;
    final scores = await Future.wait([
      discogs == null ? Future<NeoDbReferenceScore?>.value() : quiet('discogs', () => _discogsScore(discogs)),
      musicBrainz == null
          ? Future<NeoDbReferenceScore?>.value()
          : quiet('musicbrainz', () => _musicBrainzScore(musicBrainz)),
    ]);
    return [...scores.nonNulls];
  }

  Future<NeoDbReferenceScore?> _discogsScore(String url) async {
    final id = NeoDbVoicesParsing.discogsId(url);
    if (id == null) return null;
    final (kind, number) = id;
    int? releaseId = number;
    if (kind == 'master') {
      final main = (await _getJson('https://api.discogs.com/masters/$number'))['main_release'];
      releaseId = main is num ? main.toInt() : null;
      if (releaseId == null) return null;
    }
    final community = (await _getJson('https://api.discogs.com/releases/$releaseId'))['community'];
    final rating = community is Map ? community['rating'] : null;
    if (rating is! Map) return null;
    final value = rating['average'];
    if (value is! num) return null;
    final votes = rating['count'] is num ? (rating['count'] as num).toInt() : 0;
    if (votes == 0) return null;
    return NeoDbReferenceScore(source: 'Discogs', value: value.toDouble(), scale: 5, votes: votes, url: url);
  }

  Future<NeoDbReferenceScore?> _musicBrainzScore(String url) async {
    final id = NeoDbVoicesParsing.musicBrainzId(url);
    if (id == null) return null;
    final (kind, mbid) = id;
    final Object? group = kind == 'release'
        ? (await _getJson(
            'https://musicbrainz.org/ws/2/release/$mbid?inc=release-groups+ratings&fmt=json',
          ))['release-group']
        : await _getJson('https://musicbrainz.org/ws/2/release-group/$mbid?inc=ratings&fmt=json');
    final rating = group is Map ? group['rating'] : null;
    if (rating is! Map) return null;
    final value = rating['value'];
    if (value is! num) return null;
    final votes = rating['votes-count'] is num ? (rating['votes-count'] as num).toInt() : 0;
    if (votes == 0) return null;
    return NeoDbReferenceScore(source: 'MusicBrainz', value: value.toDouble(), scale: 5, votes: votes, url: url);
  }

  Future<JsonObject> _getJson(String url) async {
    final uri = Uri.parse(url);
    final response = await _client
        .get(uri, headers: {'User-Agent': userAgent, 'Accept': 'application/json'})
        .timeout(const Duration(seconds: 30));
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw StateError('HTTP ${response.statusCode} from ${uri.host}');
    }
    final decoded = jsonDecode(utf8.decode(response.bodyBytes));
    if (decoded is! Map) throw const FormatException('not a JSON object');
    return decoded.cast<String, Object?>();
  }
}
