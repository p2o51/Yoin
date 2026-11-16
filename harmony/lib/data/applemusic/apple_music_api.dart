/// Apple Music API transport (Android data/remote/applemusic/AppleMusicApiClient.kt):
/// one GET per call against `https://api.music.apple.com`, the developer token
/// on every request, the Music User Token only on personal (`/v1/me/...`)
/// ones, pagination links held to the collection they came from. Knows
/// nothing about the neutral models; never puts a token, URL query or
/// response body into an error.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:http/http.dart' as http;

/// A decoded Apple Music API document.
typedef AppleMusicJson = Map<String, Object?>;

/// Why a request failed (Android AppleMusicApiFailure).
enum AppleMusicFailure {
  /// The token service gave no usable developer token.
  developerTokenUnavailable,

  /// Apple answered 401: the developer token was refused.
  developerTokenRejected,

  /// A personal request without a Music User Token.
  userAuthorizationRequired,

  /// Apple answered 403. Not proof of revocation: subscription or access
  /// restrictions answer the same (Android observed minutes-long library 403s).
  accessDenied,

  /// Apple answered 429.
  rateLimited,

  /// Any other HTTP status; see [AppleMusicApiException.status].
  http,

  /// No answer: DNS, TLS, timeout, connection refused.
  network,

  /// A 200 whose body isn't the JSON object Apple sends.
  invalidResponse,
}

final class AppleMusicApiException implements Exception {
  const AppleMusicApiException(this.failure, {this.status, this.appleCode, this.detail});
  final AppleMusicFailure failure;

  /// The HTTP status, for refusals.
  final int? status;

  /// Apple's `errors[0].code` (digits only), when it sent one.
  final String? appleCode;

  /// A fixed description; never a token, query or body.
  final String? detail;

  /// Apple may answer a request whose `include` it won't serve with a 400 or
  /// a 5xx; a token, access or rate-limit failure is never that.
  bool get mayRefuseInclude => failure == AppleMusicFailure.http && status != null && (status == 400 || status! >= 500);

  /// A failure that would fail the plain catalog request as well.
  bool get failsCatalogToo => switch (failure) {
    AppleMusicFailure.developerTokenUnavailable ||
    AppleMusicFailure.developerTokenRejected ||
    AppleMusicFailure.rateLimited ||
    AppleMusicFailure.network => true,
    _ => false,
  };

  @override
  String toString() =>
      'AppleMusicApiException(${failure.name}${status == null ? '' : ' $status'}'
      '${appleCode == null ? '' : ' code $appleCode'}${detail == null ? '' : ': $detail'})';
}

final class AppleMusicApi {
  AppleMusicApi({
    required Future<String> Function() developerToken,
    required String? Function() musicUserToken,
    required http.Client client,
    Uri? baseUrl,
    this.timeout = const Duration(seconds: 20),
    void Function()? onDeveloperTokenRejected,
  }) : _developerToken = developerToken,
       _musicUserToken = musicUserToken,
       _client = client,
       baseUrl = baseUrl ?? Uri.parse('https://api.music.apple.com/'),
       _onDeveloperTokenRejected = onDeveloperTokenRejected {
    final loopback = const {'localhost', '127.0.0.1', '::1'}.contains(this.baseUrl.host);
    if (!this.baseUrl.isScheme('https') && !loopback) throw ArgumentError.value(baseUrl, 'baseUrl', 'must be HTTPS');
  }

  final Future<String> Function() _developerToken;
  final String? Function() _musicUserToken;
  final http.Client _client;
  final void Function()? _onDeveloperTokenRejected;
  final Uri baseUrl;

  /// Android's call timeout.
  final Duration timeout;

  static final _storefrontPattern = RegExp(r'^[a-z]{2}$');

  /// The account's storefront with the languages it names things in.
  Future<AppleMusicStorefront> storefrontDetails() async {
    final response = await resourcePage(const ['v1', 'me', 'storefront'], personal: true);
    final data = response['data'];
    final first = data is List && data.isNotEmpty && data.first is Map
        ? (data.first as Map).cast<String, Object?>()
        : null;
    final id = first?['id'];
    if (id is! String || !_storefrontPattern.hasMatch(id)) {
      throw const AppleMusicApiException(
        AppleMusicFailure.invalidResponse,
        detail: 'Apple Music returned no storefront',
      );
    }
    final attributes = first!['attributes'];
    final attrs = attributes is Map ? attributes.cast<String, Object?>() : const <String, Object?>{};
    final languages = attrs['supportedLanguageTags'];
    return AppleMusicStorefront(
      id: id,
      defaultLanguage: attrs['defaultLanguageTag'] is String ? attrs['defaultLanguageTag'] as String : null,
      languages: [
        if (languages is List)
          for (final l in languages)
            if (l is String) l,
      ],
    );
  }

  /// One page of [segments] (built by the source, never from a cover or an
  /// outside URL). A [next] link must point at the same collection: same
  /// origin, same path. It keeps [query]'s parameters it leaves out (Apple's
  /// links carry the offset but not always `limit` or `include`), and loses
  /// [excludedQueryParameters] even when it names them.
  Future<AppleMusicJson> resourcePage(
    List<String> segments, {
    required bool personal,
    Map<String, String> query = const {},
    String? next,
    Set<String> excludedQueryParameters = const {},
  }) {
    final path = '/${segments.map(Uri.encodeComponent).join('/')}';
    final Uri target;
    if (next == null) {
      target = baseUrl.replace(path: path, queryParameters: query.isEmpty ? null : query);
    } else {
      final link = baseUrl.resolve(next);
      if (link.scheme != baseUrl.scheme ||
          link.host != baseUrl.host ||
          link.port != baseUrl.port ||
          link.userInfo.isNotEmpty ||
          link.path != path) {
        throw ArgumentError('Invalid Apple Music pagination link');
      }
      final merged = <String, String>{...link.queryParameters};
      query.forEach((key, value) => merged.putIfAbsent(key, () => value));
      target = link.replace(queryParameters: merged.isEmpty ? null : merged);
    }
    final filtered = excludedQueryParameters.isEmpty
        ? target
        : target.replace(
            queryParameters: {
              for (final e in target.queryParameters.entries)
                if (!excludedQueryParameters.contains(e.key)) e.key: e.value,
            },
          );
    return _get(filtered, personal: personal);
  }

  /// A new library playlist named [name] holding [tracks] (`POST /v1/me/library/playlists`, 201):
  /// the created resource.
  Future<AppleMusicJson> createLibraryPlaylist(String name, List<AppleMusicTrackRef> tracks) async {
    if (name.trim().isEmpty) throw ArgumentError.value(name, 'name', 'A playlist needs a name');
    final body = {
      'attributes': {'name': name.trim()},
      if (tracks.isNotEmpty)
        'relationships': {
          'tracks': {'data': _trackData(tracks)},
        },
    };
    final response = await _send(
      'POST',
      baseUrl.replace(path: '/v1/me/library/playlists'),
      personal: true,
      jsonBody: body,
      expectedStatus: 201,
    );
    final data = response['data'];
    if (data is List && data.isNotEmpty && data.first is Map) return (data.first as Map).cast<String, Object?>();
    throw const AppleMusicApiException(AppleMusicFailure.invalidResponse, detail: 'Apple Music returned no playlist');
  }

  static final _libraryPlaylistId = RegExp(r'^p\.[A-Za-z0-9]+$');

  /// Appends [tracks] to library playlist [playlistId] (`p.…`), in order (`POST …/tracks`, 204).
  Future<void> addTracksToLibraryPlaylist(String playlistId, List<AppleMusicTrackRef> tracks) async {
    if (!_libraryPlaylistId.hasMatch(playlistId)) {
      throw ArgumentError.value(playlistId, 'playlistId', 'A library playlist ID is required');
    }
    if (tracks.isEmpty) return;
    await _send(
      'POST',
      baseUrl.replace(path: '/v1/me/library/playlists/$playlistId/tracks'),
      personal: true,
      jsonBody: {'data': _trackData(tracks)},
      expectedStatus: 204,
    );
  }

  /// Adds catalogue song [catalogId] to the user's library (`POST /v1/me/library?ids[songs]=…`, 202).
  /// Apple takes it and shows it in the library a little later.
  Future<void> addSongToLibrary(String catalogId) async {
    if (!_catalogSongId.hasMatch(catalogId)) {
      throw ArgumentError.value(catalogId, 'catalogId', 'A catalog song ID is required');
    }
    await _send(
      'POST',
      baseUrl.replace(path: '/v1/me/library', queryParameters: {'ids[songs]': catalogId}),
      personal: true,
      expectedStatus: 202,
      bodyless: true,
    );
  }

  /// The user's library song for catalogue song [catalogId] (`…/songs/{id}/library`), or null when it
  /// isn't in the library. Apple answers an unsaved song's library relationship with 404 (Android saw it
  /// on a subscribed account); that 404 reads as absence only after the catalogue confirms the song exists.
  Future<String?> librarySongId(String storefront, String catalogId) async {
    if (!_catalogSongId.hasMatch(catalogId)) {
      throw ArgumentError.value(catalogId, 'catalogId', 'A catalog song ID is required');
    }
    AppleMusicJson response;
    try {
      response = await resourcePage(['v1', 'catalog', storefront, 'songs', catalogId, 'library'], personal: true);
    } on AppleMusicApiException catch (e) {
      if (e.failure != AppleMusicFailure.http || e.status != 404) rethrow;
      final catalog = await resourcePage(['v1', 'catalog', storefront, 'songs', catalogId], personal: false);
      final data = catalog['data'];
      final exists =
          data is List && data.any((item) => item is Map && item['type'] == 'songs' && item['id'] == catalogId);
      if (!exists) {
        throw const AppleMusicApiException(AppleMusicFailure.invalidResponse, detail: 'No matching catalog song');
      }
      return null;
    }
    final data = response['data'];
    if (data is! List) {
      throw const AppleMusicApiException(AppleMusicFailure.invalidResponse, detail: 'No library relationship');
    }
    if (data.isEmpty) return null;
    final first = data.first;
    final id = first is Map ? first['id'] : null;
    if (id is! String || id.isEmpty || (first as Map)['type'] != 'library-songs') {
      throw const AppleMusicApiException(AppleMusicFailure.invalidResponse, detail: 'Invalid library relationship');
    }
    return id;
  }

  static final _catalogSongId = RegExp(r'^[0-9]+$');

  static List<Map<String, String>> _trackData(List<AppleMusicTrackRef> tracks) => [
    for (final t in tracks) {'id': t.id, 'type': t.type},
  ];

  Future<AppleMusicJson> _get(Uri target, {required bool personal}) => _send('GET', target, personal: personal);

  Future<AppleMusicJson> _send(
    String method,
    Uri target, {
    required bool personal,
    Object? jsonBody,
    int expectedStatus = 200,
    bool bodyless = false,
  }) async {
    String? userToken;
    if (personal) {
      userToken = _musicUserToken();
      if (userToken == null || userToken.trim().isEmpty) {
        throw const AppleMusicApiException(AppleMusicFailure.userAuthorizationRequired);
      }
    }
    final developerToken = await _developerToken();
    if (developerToken.trim().isEmpty) {
      throw const AppleMusicApiException(AppleMusicFailure.developerTokenUnavailable);
    }
    final request = http.Request(method, target)
      ..followRedirects = false
      ..headers['Authorization'] = 'Bearer $developerToken'
      ..headers['Accept'] = 'application/json';
    if (userToken != null) request.headers['Music-User-Token'] = userToken;
    if (jsonBody != null) {
      request.headers['Content-Type'] = 'application/json';
      request.body = jsonEncode(jsonBody);
    }

    final http.Response response;
    try {
      response = await http.Response.fromStream(await _client.send(request).timeout(timeout)).timeout(timeout);
    } on TimeoutException {
      throw AppleMusicApiException(AppleMusicFailure.network, detail: 'Timed out after ${timeout.inSeconds} s');
    } on IOException {
      throw const AppleMusicApiException(AppleMusicFailure.network, detail: 'Apple Music unreachable');
    } on http.ClientException {
      throw const AppleMusicApiException(AppleMusicFailure.network, detail: 'Apple Music unreachable');
    }

    if (response.statusCode != expectedStatus) {
      final failure = switch (response.statusCode) {
        401 => AppleMusicFailure.developerTokenRejected,
        403 => AppleMusicFailure.accessDenied,
        429 => AppleMusicFailure.rateLimited,
        _ => AppleMusicFailure.http,
      };
      if (failure == AppleMusicFailure.developerTokenRejected) _onDeveloperTokenRejected?.call();
      throw AppleMusicApiException(
        failure,
        status: response.statusCode,
        appleCode: _appleErrorCode(response.bodyBytes),
      );
    }
    if (response.statusCode == 204 || bodyless) return const {};
    try {
      final decoded = jsonDecode(utf8.decode(response.bodyBytes, allowMalformed: true));
      if (decoded is Map) return decoded.cast<String, Object?>();
    } on FormatException {
      // Parser messages can quote the body.
    }
    throw const AppleMusicApiException(AppleMusicFailure.invalidResponse, detail: 'Apple Music returned invalid JSON');
  }

  /// Apple's own refusal code (`errors[0].code`, as "40300"): digits only, none of the prose.
  static String? _appleErrorCode(List<int> body) {
    try {
      final decoded = jsonDecode(utf8.decode(body.length > 4096 ? body.sublist(0, 4096) : body));
      final errors = decoded is Map ? decoded['errors'] : null;
      final first = errors is List && errors.isNotEmpty ? errors.first : null;
      final code = first is Map ? first['code'] : null;
      if (code is String && code.isNotEmpty && code.length <= 8 && RegExp(r'^\d+$').hasMatch(code)) return code;
    } catch (_) {
      // Not Apple's error document: no code.
    }
    return null;
  }

  void close() => _client.close();
}

/// A song to put in a library playlist: a catalogue song (`songs`, digits)
/// or a library song (`library-songs`, `i.…`).
final class AppleMusicTrackRef {
  const AppleMusicTrackRef(this.id, this.type);
  final String id;
  final String type;
}

/// See [AppleMusicApi.storefrontDetails].
final class AppleMusicStorefront {
  const AppleMusicStorefront({required this.id, this.defaultLanguage, this.languages = const []});
  final String id;
  final String? defaultLanguage;
  final List<String> languages;
}
