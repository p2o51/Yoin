/// When an account needs its owner before it works again (Android
/// data/profile/ProfileReconnect.kt). Local rules only: nothing here asks a
/// server.
library;

import '../applemusic/apple_music_api.dart';
import '../source/music_source.dart';
import '../subsonic/subsonic_api.dart' show SubsonicHttpStatus;
import 'profile.dart';

/// Whether an account whose stored credentials read as [credentials] (null:
/// they couldn't be read — the secret is gone after a restore, or its key
/// didn't come along) needs its owner to sign in again before it works. The
/// one rule behind every "needs reconnect" Yoin shows: Settings' account
/// cards, Home's avatar dot and the card's Reconnect.
///
/// Android also counts Spotify's revoked grant and missing scopes here; the
/// HarmonyOS build has no Spotify account, and a Subsonic or Apple Music
/// account that reads is never flagged ahead of a server's answer (Android
/// `needsReconnect`'s `else -> false`).
bool credentialsNeedReconnect(ProfileCredentials? credentials) => credentials == null;

/// Subsonic's auth errors (Android SUBSONIC_AUTH_ERRORS): 40 wrong username or
/// password, 41 token auth refused, 42–44 OpenSubsonic's auth mechanism and
/// API key errors.
const subsonicAuthErrorCodes = {40, 41, 42, 43, 44};

/// The HTTP statuses a Subsonic server turns credentials down with.
const _refusingHttpStatuses = {401, 403};

/// Whether a ping that failed with [error] is the service turning the
/// account's credentials down — only its owner can fix that (its page:
/// Subsonic's credentials again, Apple Music's sign-in) — rather than a server
/// that didn't answer: offline, a timeout, a server restarting, a 5xx (Android
/// `Throwable.refusesCredentials`).
///  - Subsonic: error 40–44, or the server's HTTP 401 / 403.
///  - Apple Music: no Music User Token, or a 403 on the account's storefront.
bool refusesCredentials(Object error) => switch (error) {
  AppleMusicApiException() => _appleRefuses(error),
  SourceException(cause: final AppleMusicApiException apple) => _appleRefuses(apple),
  SourceException(cause: SubsonicHttpStatus(:final status)) => _refusingHttpStatuses.contains(status),
  SourceAuthException(:final code?) || SourceServerException(:final code?) => subsonicAuthErrorCodes.contains(code),
  _ => false,
};

bool _appleRefuses(AppleMusicApiException e) =>
    e.failure == AppleMusicFailure.userAuthorizationRequired || e.failure == AppleMusicFailure.accessDenied;
