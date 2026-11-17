/// Persistent storage backed by ArkTS on HarmonyOS (Preferences for plain
/// values, Asset Store Kit for secrets) through the `yoin/storage` channel.
library;

abstract interface class KeyValueStore {
  Future<String?> read(String key);

  /// null deletes.
  Future<void> write(String key, String? value);
}

/// Like [KeyValueStore], but encrypted at rest by the system (credentials).
abstract interface class SecretStore {
  Future<String?> read(String key);

  /// null deletes.
  Future<void> write(String key, String? value);
}

/// For tests and previews.
final class InMemoryStore implements KeyValueStore, SecretStore {
  final Map<String, String> values = {};

  @override
  Future<String?> read(String key) async => values[key];

  @override
  Future<void> write(String key, String? value) async {
    if (value == null) {
      values.remove(key);
    } else {
      values[key] = value;
    }
  }
}
