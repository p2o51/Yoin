/// Account fingerprints (fp:v1, Android identity/AccountFingerprints.kt): the
/// provider-level identity of a music account, computed locally from
/// credentials. They only MATCH accounts; a binding's sync id is minted from
/// one once ([deterministicId]) and then frozen. The v1 normalization must
/// stay byte for byte what Android computes: the same Subsonic server on an
/// Android phone and on this device has to land in one scope.
library;

import '../canonical_json.dart';

abstract final class AccountFingerprints {
  static const subsonicV1Prefix = 'subsonic:v1:';
  static const spotifyV1Prefix = 'spotify:v1:';
  static const _separator = '\u001F';
  static final _scheme = RegExp(r'^[A-Za-z][A-Za-z0-9+.-]*://');

  /// Subsonic: host (lowercased) + non-default port + path without trailing
  /// "/", then the trimmed lowercased username. Scheme, userinfo, query and
  /// fragment are dropped, so http/https or a pasted "user:pass@" never split
  /// one server. Null when the address can't be parsed or the username is blank.
  static String? subsonic(String serverUrl, String username) {
    final url = _parseServerUrl(serverUrl);
    if (url == null) return null;
    final user = username.trim().toLowerCase();
    if (user.isEmpty) return null;
    return '$subsonicV1Prefix${_hostPort(url)}${_trimTrailingSlashes(url.path)}$_separator$user';
  }

  static String spotify(String userId) => '$spotifyV1Prefix$userId';

  /// Sync id minted at first bind: "s-" + the first 24 hex chars of sha256(fingerprint).
  static String deterministicId(String fingerprint) => 's-${CanonicalJson.sha256Hex(fingerprint).substring(0, 24)}';

  /// What a binding stores (and compares) instead of the fingerprint itself.
  static String fingerprintHash(String fingerprint) => CanonicalJson.sha256Hex(fingerprint);

  /// Display hint for the account descriptor: "`username` @ `host[:port]`".
  /// No scheme, path, userinfo or query, so nothing that could carry a secret.
  static String? subsonicHint(String serverUrl, String username) {
    final url = _parseServerUrl(serverUrl);
    if (url == null) return null;
    final user = username.trim();
    if (user.isEmpty) return null;
    return '$user @ ${_hostPort(url)}';
  }

  /// OkHttp's HttpUrl accepts http and https only and needs a host.
  static Uri? _parseServerUrl(String serverUrl) {
    final trimmed = serverUrl.trim();
    if (trimmed.isEmpty) return null;
    final withScheme = _scheme.hasMatch(trimmed) ? trimmed : 'https://$trimmed';
    final Uri url;
    try {
      url = Uri.parse(withScheme);
    } on FormatException {
      return null;
    }
    final scheme = url.scheme.toLowerCase();
    if (scheme != 'http' && scheme != 'https') return null;
    if (url.host.isEmpty) return null;
    return url;
  }

  static String _hostPort(Uri url) {
    final host = url.host.toLowerCase();
    final shown = host.contains(':') ? '[$host]' : host;
    final defaultPort = url.scheme.toLowerCase() == 'https' ? 443 : 80;
    return url.port == defaultPort ? shown : '$shown:${url.port}';
  }

  /// HttpUrl's encodedPath is at least "/"; Kotlin trimEnd('/') drops every trailing slash.
  static String _trimTrailingSlashes(String path) {
    var end = path.length;
    while (end > 0 && path.codeUnitAt(end - 1) == 0x2f) {
      end--;
    }
    return path.substring(0, end);
  }
}
