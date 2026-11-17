/// A remote id namespaced by provider (data/model/MediaId.kt).
/// String form is `provider:rawId`; providers never contain ':'.
final class MediaId {
  MediaId(this.provider, this.rawId)
      : assert(provider.isNotEmpty && !provider.contains(':'), 'bad provider "$provider"'),
        assert(rawId.isNotEmpty, 'empty rawId');

  factory MediaId.subsonic(String rawId) => MediaId(providerSubsonic, rawId);

  /// Parses `provider:rawId`; throws [FormatException] otherwise.
  factory MediaId.parse(String value) {
    final sep = value.indexOf(':');
    if (sep <= 0 || sep >= value.length - 1) throw FormatException('Invalid MediaId', value);
    return MediaId(value.substring(0, sep), value.substring(sep + 1));
  }

  static MediaId? tryParse(String? value) {
    if (value == null || value.isEmpty) return null;
    try {
      return MediaId.parse(value);
    } on FormatException {
      return null;
    }
  }

  static const providerSubsonic = 'subsonic';
  static const providerSpotify = 'spotify';
  static const providerAppleMusic = 'applemusic';
  static const providerLocal = 'local';

  final String provider;
  final String rawId;

  @override
  bool operator ==(Object other) => other is MediaId && other.provider == provider && other.rawId == rawId;

  @override
  int get hashCode => Object.hash(provider, rawId);

  @override
  String toString() => '$provider:$rawId';
}
