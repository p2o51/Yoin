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
