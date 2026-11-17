/// Apple Music sign-in (the Music User Token) over the `yoin/applemusic_auth`
/// method channel served by the ArkTS `YoinAppleMusicAuthPlugin`
/// (ohos/entry/src/main/ets/yoin/applemusic/).
///
/// Android deep-links into the Apple Music Android app to get the Music User
/// Token; HarmonyOS has no such app, so the ArkTS side opens an ArkWeb page
/// that runs MusicKit JS v3, calls `authorize()`, and returns the Music User
/// Token and the storefront (docs/applemusic.md "Music User Token"). The
/// developer token is fetched from [endpoint] by the ArkTS side and injected
/// into the page; it never crosses this channel and never reaches the page's
/// URL.
library;

import 'package:flutter/services.dart';

/// What an Apple Music sign-in produced.
final class AppleMusicAuthResult {
  const AppleMusicAuthResult({required this.musicUserToken, required this.storefront});

  /// The Music User Token Apple issued. Stored only in [SecretStore].
  final String musicUserToken;

  /// The account's storefront id (e.g. `jp`, `us`), from `me/storefront`.
  final String storefront;

  /// Never the token.
  @override
  String toString() => 'AppleMusicAuthResult(storefront: $storefront)';
}

/// Why an Apple Music sign-in did not finish (Android TokenError, plus the
/// web and transport cases ArkWeb adds).
enum AppleMusicAuthFailure {
  /// The developer-token service gave no usable token.
  developerTokenUnavailable,

  /// The user closed the Apple ID window without finishing.
  cancelled,

  /// Apple refused the sign-in (no subscription, bad account, MusicKit error).
  authFailed,

  /// The sign-in page could not load (ArkWeb, CSP, certificate, offline).
  pageFailed,

  /// The channel was not served, or something else went wrong on the native side.
  unavailable,
}

/// Raised when a sign-in does not produce a Music User Token. Never carries a
/// token or a page URL.
final class AppleMusicAuthException implements Exception {
  const AppleMusicAuthException(this.failure, {this.detail});

  final AppleMusicAuthFailure failure;

  /// A fixed description; never a token or a query string.
  final String? detail;

  @override
  String toString() => 'AppleMusicAuthException($failure${detail == null ? '' : ': $detail'})';
}

/// Runs the Apple Music sign-in and hands back the Music User Token.
abstract interface class AppleMusicAuth {
  /// Opens the MusicKit JS sign-in for the developer-token service at
  /// [endpoint] and returns once the user has signed in. Throws
  /// [AppleMusicAuthException] on cancel, refusal or a page/transport problem.
  Future<AppleMusicAuthResult> authorize(String endpoint);
}

const _authChannel = MethodChannel('yoin/applemusic_auth');

/// [AppleMusicAuth] over `yoin/applemusic_auth`.
final class ChannelAppleMusicAuth implements AppleMusicAuth {
  const ChannelAppleMusicAuth({MethodChannel channel = _authChannel}) : _channel = channel;

  final MethodChannel _channel;

  @override
  Future<AppleMusicAuthResult> authorize(String endpoint) async {
    final Map<Object?, Object?>? reply;
    try {
      reply = await _channel.invokeMapMethod<Object?, Object?>('authorize', {'endpoint': endpoint});
    } on PlatformException catch (e) {
      throw AppleMusicAuthException(_failureForCode(e.code), detail: _detailForCode(e.code));
    } on MissingPluginException {
      throw const AppleMusicAuthException(AppleMusicAuthFailure.unavailable, detail: 'Sign-in is not available on this build');
    }
    final mut = reply?['musicUserToken'];
    final storefront = reply?['storefront'];
    if (mut is! String || mut.isEmpty || storefront is! String || storefront.isEmpty) {
      throw const AppleMusicAuthException(AppleMusicAuthFailure.authFailed, detail: 'Sign-in returned no token');
    }
    return AppleMusicAuthResult(musicUserToken: mut, storefront: storefront);
  }

  static AppleMusicAuthFailure _failureForCode(String code) => switch (code) {
        'developer_token' => AppleMusicAuthFailure.developerTokenUnavailable,
        'cancelled' => AppleMusicAuthFailure.cancelled,
        'auth_failed' => AppleMusicAuthFailure.authFailed,
        'page_failed' => AppleMusicAuthFailure.pageFailed,
        _ => AppleMusicAuthFailure.unavailable,
      };

  // The native side keeps messages token-free; we still only surface fixed text.
  static String? _detailForCode(String code) => switch (code) {
        'developer_token' => 'The developer-token service is unavailable',
        'cancelled' => 'Sign-in was cancelled',
        'auth_failed' => 'Apple Music refused the sign-in',
        'page_failed' => 'The sign-in page could not load',
        _ => 'Sign-in could not start',
      };
}
