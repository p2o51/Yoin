/// Where Yoin keeps its NeoDB account (Android NeoDBConfig + NeoDbTokenStore +
/// ExternalMapping): the instance is a plain setting, the access token lives
/// only in the encrypted [SecretStore] (Android keeps it out of backups for the
/// same reason), and the Yoin album ↔ NeoDB item map is a plain JSON document.
///
/// One NeoDB account for the app, as on Android: it isn't tied to a music
/// account (album rows still are, per profile).
library;

import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';

import '../../platform/storage/stores.dart';
import '../model/model.dart';

/// The instance and token in use; [configured] needs both.
@immutable
final class NeoDbSession {
  const NeoDbSession({required this.instance, required this.token});

  final String instance;
  final String token;
}

final class NeoDbConfigStore {
  NeoDbConfigStore(this._kv, this._secrets);

  final KeyValueStore _kv;
  final SecretStore? _secrets;

  static const defaultInstance = 'https://neodb.social';
  static const instanceKey = 'neodb.instance';
  static const tokenKey = 'neodb.access_token';

  /// The saved instance, or null before the first sign-in.
  Future<String?> storedInstance() async {
    final v = await _kv.read(instanceKey);
    return v == null || v.trim().isEmpty ? null : v;
  }

  /// The instance the settings show (the default until one is saved).
  Future<String> instance() async => await storedInstance() ?? defaultInstance;

  Future<void> setInstance(String instance) => _kv.write(instanceKey, instance);

  /// The token; null when there is none or it can't be read (a reinstall):
  /// either way the user signs in again.
  Future<String?> readToken() async {
    final secrets = _secrets;
    if (secrets == null) return null;
    try {
      final v = await secrets.read(tokenKey);
      return v == null || v.isEmpty ? null : v;
    } catch (_) {
      return null;
    }
  }

  /// Writes the token; empty signs out.
  Future<void> writeToken(String token) async => _secrets?.write(tokenKey, token.isEmpty ? null : token);

  Future<void> clearToken() async => _secrets?.write(tokenKey, null);

  /// Instance and token both set (NeoDBSyncService.currentSession).
  Future<NeoDbSession?> session() async {
    final instance = await storedInstance();
    if (instance == null) return null;
    final token = await readToken();
    if (token == null || token.trim().isEmpty) return null;
    return NeoDbSession(instance: instance, token: token);
  }
}

/// The instance a user typed, as a base URL (`https://host[:port]`): no
/// scheme means https, the path goes. Null when no host can be read.
/// [blankAsDefault] reads an empty field as neodb.social (Settings); the
/// sign-in itself treats it as invalid (NeoDBOAuthActivity).
String? normalizeNeoDbInstance(String? raw, {bool blankAsDefault = true}) {
  var trimmed = (raw ?? '').trim().replaceAll(RegExp(r'/+$'), '');
  if (trimmed.isEmpty) {
    if (!blankAsDefault) return null;
    trimmed = NeoDbConfigStore.defaultInstance;
  }
  // java.net.URI turns a space away; Uri would percent-encode it into the host.
  if (trimmed.contains(RegExp(r'\s'))) return null;
  final candidate = trimmed.contains('://') ? trimmed : 'https://$trimmed';
  final uri = Uri.tryParse(candidate);
  if (uri == null || uri.scheme.isEmpty || uri.host.isEmpty) return null;
  return '${uri.scheme}://${uri.host}${uri.hasPort ? ':${uri.port}' : ''}';
}

/// Yoin album ↔ NeoDB item uuid (Android `external_mappings`, service
/// "neodb"). Keyed by provider and album id only, so the same album under
/// two accounts maps once; several Yoin albums may point at one item.
final class NeoDbMappings {
  NeoDbMappings(this._kv);

  final KeyValueStore _kv;
  static const storeKey = 'neodb.external_mappings.v1';

  Map<String, String>? _cache;
  Future<void> _tail = Future.value();

  static String _key(MediaId albumId) => 'album:${albumId.provider}:${albumId.rawId}';

  Future<Map<String, String>> _load() async {
    if (_cache != null) return _cache!;
    final raw = await _kv.read(storeKey);
    final out = <String, String>{};
    if (raw != null && raw.isNotEmpty) {
      try {
        final decoded = jsonDecode(raw);
        if (decoded is Map) {
          decoded.forEach((k, v) {
            if (k is String && v is String) out[k] = v;
          });
        }
      } on FormatException {
        // A damaged map is rebuilt by the next searches.
      }
    }
    return _cache = out;
  }

  Future<String?> find(MediaId albumId) async {
    await _tail;
    return (await _load())[_key(albumId)];
  }

  Future<void> put(MediaId albumId, String itemUuid) {
    final done = _tail.then((_) async {
      final map = await _load();
      map[_key(albumId)] = itemUuid;
      await _kv.write(storeKey, jsonEncode(map));
    });
    _tail = done.catchError((Object _) {});
    return done;
  }
}
