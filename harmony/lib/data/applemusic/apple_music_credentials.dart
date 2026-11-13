/// The secrets of one Apple Music profile (Android data/profile/ProfileCredentials.kt,
/// `ProfileCredentials.AppleMusic`).
///
/// [ProfileCredentials] is sealed to `lib/data/profile/profile.dart`, so this
/// class can't join it from here; it carries the exact JSON the subtype will
/// use (`type: applemusic`, Android's `@SerialName`), and `docs/applemusic.md`
/// holds the patch that makes it a [ProfileCredentials] subtype. Kept in
/// [SecretStore] only, like every credential.
library;

import '../model/media_id.dart';

final class AppleMusicCredentials {
  const AppleMusicCredentials({required this.endpoint, required this.musicUserToken});

  /// JSON `type` of this subtype; Android's serial name.
  static const type = 'applemusic';

  /// The HTTPS developer-token service this account was authorised with. It
  /// answers `{"developerToken": "<ES256 JWT>"}`; the app never holds an
  /// Apple private key (Android AppleMusicDeveloperTokenProvider).
  final String endpoint;

  /// The Music User Token Apple issued when the user signed in with their
  /// Apple ID. Sent only on `/v1/me/...` requests.
  final String musicUserToken;

  String get provider => MediaId.providerAppleMusic;

  Map<String, Object?> toJson() => {'type': type, 'endpoint': endpoint, 'musicUserToken': musicUserToken};

  /// Reads [json] written by [toJson]; throws [FormatException] for anything else.
  static AppleMusicCredentials fromJson(Map<String, Object?> json) {
    if (json['type'] != type) throw FormatException('Not Apple Music credentials', json['type']);
    final endpoint = json['endpoint'];
    final token = json['musicUserToken'];
    if (endpoint is! String || token is! String) throw const FormatException('Incomplete Apple Music credentials');
    return AppleMusicCredentials(endpoint: endpoint, musicUserToken: token);
  }

  /// Never the token.
  @override
  String toString() => 'AppleMusicCredentials(endpoint: $endpoint)';
}
