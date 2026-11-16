/// Subsonic / OpenSubsonic transport: token auth, URL building, one GET per
/// call, and the mapping of every failure onto [SourceException]
/// (Android: data/remote/SubsonicApiFactory.kt + SubsonicInterceptor.kt;
/// iOS: shared SubsonicClient.kt). Knows nothing about the neutral models.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:crypto/crypto.dart';
import 'package:http/http.dart' as http;

import '../profile/profile.dart';
import '../source/music_source.dart';

/// A decoded `subsonic-response` object.
typedef SubsonicJson = Map<String, Object?>;

/// One query parameter. A list of these (not a map) because Subsonic repeats
/// keys (`id=a&id=b`) and Android sends them in a fixed order.
typedef QueryParam = (String, String);

/// Token auth (Android data/remote/SubsonicAuth.kt): `t = md5(password + salt)`
/// with a 16-char lower-case hex salt.
abstract final class SubsonicAuth {
  static const _saltChars = 'abcdef0123456789';

  /// A fresh 16-char hex salt.
  static String newSalt([Random? random]) {
    final rng = random ?? Random.secure();
    return String.fromCharCodes(List.generate(16, (_) => _saltChars.codeUnitAt(rng.nextInt(_saltChars.length))));
  }

  /// `md5(password + salt)` as lower-case hex, UTF-8 input.
  static String token(String password, String salt) => md5.convert(utf8.encode(password + salt)).toString();
}

/// Error codes the Subsonic API defines that the source treats specially.
abstract final class SubsonicErrorCode {
  /// Wrong username or password.
  static const wrongCredentials = 40;

  /// The server doesn't take token auth (it wants the plain password).
  static const tokenAuthNotSupported = 41;

  /// The user may not do this operation.
  static const notAuthorized = 50;

  /// The requested data was not found.
  static const notFound = 70;

  /// Codes surfaced as [SourceAuthException]: the saved account needs fixing, not a retry.
  static const auth = {wrongCredentials, tokenAuthNotSupported, notAuthorized};
}

/// The transport for one server account. Immutable after construction: the
/// salt is drawn once, like Android's `SubsonicAuth.stableToken`, so the same
/// cover / stream URL comes out every time and image caches keep their hits.
final class SubsonicApi {
  SubsonicApi({
    required SubsonicCredentials credentials,
    required http.Client client,
    String? salt,
    this.timeout = defaultTimeout,
  }) : _credentials = credentials,
       _client = client,
       salt = salt ?? SubsonicAuth.newSalt(),
       _root = normalizeBaseUrl(credentials.baseUrl) {
    token = SubsonicAuth.token(credentials.password, this.salt);
  }

  static const apiVersion = '1.16.1';

  /// What Android sends as `c` (SubsonicInterceptor.CLIENT_NAME).
  static const clientName = 'Yoin';
  static const responseFormat = 'json';

  /// Android's Subsonic OkHttpClient keeps OkHttp's defaults: 10 s to connect
  /// and 10 s between reads. Here: 10 s until the headers arrive, then 10 s
  /// between body chunks, so a large but flowing response isn't cut off.
  static const defaultTimeout = Duration(seconds: 10);

  final SubsonicCredentials _credentials;
  final http.Client _client;
  final Uri? _root;
  final Duration timeout;

  /// The salt every request of this account is signed with.
  final String salt;

  /// `md5(password + salt)`.
  late final String token;

  String get username => _credentials.username;

  /// The server root without `/rest`, or null when [baseUrl] isn't an
  /// absolute http(s) URL. Tolerates surrounding blanks, trailing slashes
  /// and a base that already ends in `/rest` (people paste either).
  static Uri? normalizeBaseUrl(String baseUrl) {
    var text = baseUrl.trim();
    while (text.endsWith('/')) {
      text = text.substring(0, text.length - 1);
    }
    if (text.toLowerCase().endsWith('/rest')) text = text.substring(0, text.length - 5);
    while (text.endsWith('/')) {
      text = text.substring(0, text.length - 1);
    }
    final uri = Uri.tryParse(text);
    if (uri == null || !(uri.isScheme('http') || uri.isScheme('https')) || uri.host.isEmpty) return null;
    return uri;
  }

  /// `<root>/rest/<endpoint>.view?<params>&u&t&s&v&c&f`, Android's shape and
  /// order; null when the base URL is unusable. No network.
  Uri? restUri(String endpoint, [List<QueryParam> params = const []]) {
    final root = _root;
    if (root == null) return null;
    final all = [
      ...params,
      ('u', username),
      ('t', token),
      ('s', salt),
      ('v', apiVersion),
      ('c', clientName),
      ('f', responseFormat),
    ];
    final query = all.map((p) => '${Uri.encodeQueryComponent(p.$1)}=${Uri.encodeQueryComponent(p.$2)}').join('&');
    return root.replace(
      pathSegments: [...root.pathSegments.where((s) => s.isNotEmpty), 'rest', '$endpoint.view'],
      query: query,
    );
  }

  /// GETs [endpoint] and returns the `subsonic-response` object when its
  /// status is ok. Throws [SourceAuthException], [SourceServerException] or
  /// [SourceNetworkException]; never lets the auth query into a message.
  Future<SubsonicJson> call(String endpoint, [List<QueryParam> params = const []]) async {
    final uri = restUri(endpoint, params);
    if (uri == null) throw SourceNetworkException('Invalid server URL: ${_credentials.baseUrl}');

    final int status;
    final List<int> bytes;
    try {
      final response = await _client.send(http.Request('GET', uri)).timeout(timeout);
      status = response.statusCode;
      bytes = await response.stream.timeout(timeout).fold<List<int>>(<int>[], (acc, chunk) => acc..addAll(chunk));
    } on TimeoutException catch (e) {
      throw SourceNetworkException(
        '$endpoint timed out after ${timeout.inSeconds} s',
        kind: NetworkFailureKind.timeout,
        cause: e,
      );
    } on IOException catch (e) {
      // SocketException, HandshakeException (a TlsException), HttpException.
      throw SourceNetworkException(redact(e.toString()), kind: networkFailureKindOf(e), cause: e);
    } on http.ClientException catch (e) {
      throw SourceNetworkException(redact(e.toString()), kind: networkFailureKindOf(e), cause: e);
    }

    final ok = status >= 200 && status < 300;
    final SubsonicJson body;
    try {
      final decoded = jsonDecode(utf8.decode(bytes, allowMalformed: true));
      body = ((decoded as Map)['subsonic-response'] as Map).cast<String, Object?>();
    } catch (_) {
      throw SourceServerException(ok ? 'Not a Subsonic response' : 'HTTP $status');
    }

    if (body['status'] != 'ok') {
      final error = body['error'];
      final code = error is Map ? _asInt(error['code']) : null;
      final message = redact(
        (error is Map ? error['message'] as String? : null) ?? 'Subsonic error${code == null ? '' : ' (code $code)'}',
      );
      if (code != null && SubsonicErrorCode.auth.contains(code)) throw SourceAuthException(message, code: code);
      throw SourceServerException(message, code: code);
    }
    if (!ok) throw SourceServerException('HTTP $status');
    return body;
  }

  /// Closes the HTTP client; calls after this fail.
  void close() => _client.close();

  static final _credentialParams = RegExp(r'([?&](?:u|t|s|p|apiKey)=)[^&#\s\],)]*');

  /// [text] with every credential query value blanked: token + salt sign in as the user.
  static String redact(String text) => text.replaceAllMapped(_credentialParams, (m) => '${m[1]}REDACTED');

  static int? _asInt(Object? value) => switch (value) {
    final int i => i,
    final num n => n.toInt(),
    final String s => int.tryParse(s),
    _ => null,
  };
}
