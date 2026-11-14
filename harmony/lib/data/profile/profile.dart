/// A configured account (data/local/Profile.kt + data/profile/ProfileCredentials.kt).
/// Several profiles per provider are allowed (two Subsonic servers).
library;

import '../model/media_id.dart';

final class Profile {
  const Profile({required this.id, required this.provider, required this.displayName});

  /// Stable local id (UUID-like); never shown.
  final String id;

  /// [MediaId] provider token.
  final String provider;
  final String displayName;

  Map<String, Object?> toJson() => {'id': id, 'provider': provider, 'displayName': displayName};

  factory Profile.fromJson(Map<String, Object?> json) => Profile(
        id: json['id']! as String,
        provider: json['provider']! as String,
        displayName: json['displayName']! as String,
      );
}

/// Secrets for one profile. Kept in [SecretStore], never in plain prefs.
sealed class ProfileCredentials {
  const ProfileCredentials();

  Map<String, Object?> toJson();

  static ProfileCredentials fromJson(Map<String, Object?> json) => switch (json['type']) {
        'subsonic' => SubsonicCredentials(
            baseUrl: json['baseUrl']! as String,
            username: json['username']! as String,
            password: json['password']! as String,
          ),
        'applemusic' => AppleMusicCredentials(
            endpoint: json['endpoint']! as String,
            musicUserToken: json['musicUserToken']! as String,
          ),
        final other => throw FormatException('Unknown credentials type', other),
      };
}

final class SubsonicCredentials extends ProfileCredentials {
  const SubsonicCredentials({required this.baseUrl, required this.username, required this.password});

  /// Server root, e.g. `https://music.example.com` (no `/rest`).
  final String baseUrl;
  final String username;

  /// Kept so token auth can salt per request (t = md5(password + salt)).
  final String password;

  String get provider => MediaId.providerSubsonic;

  @override
  Map<String, Object?> toJson() => {'type': 'subsonic', 'baseUrl': baseUrl, 'username': username, 'password': password};
}

/// One Apple Music account: the developer-token service it was authorised with
/// and the Music User Token Apple issued at sign-in (Android
/// ProfileCredentials.AppleMusic). The developer token itself is never stored;
/// it is fetched from [endpoint] at runtime. Kept in [SecretStore] only.
final class AppleMusicCredentials extends ProfileCredentials {
  const AppleMusicCredentials({required this.endpoint, required this.musicUserToken});

  /// JSON `type` of this subtype; Android's serial name.
  static const type = 'applemusic';

  /// The HTTPS developer-token service this account was authorised with. It
  /// answers `{"developerToken": "<ES256 JWT>"}`; the app never holds an Apple
  /// private key (Android AppleMusicDeveloperTokenProvider).
  final String endpoint;

  /// The Music User Token Apple issued when the user signed in with their
  /// Apple ID. Sent only on `/v1/me/...` requests.
  final String musicUserToken;

  String get provider => MediaId.providerAppleMusic;

  @override
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
