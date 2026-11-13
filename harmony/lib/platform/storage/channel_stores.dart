/// [KeyValueStore] and [SecretStore] over the `yoin/storage` method channel
/// served by the ArkTS `YoinStoragePlugin`
/// (ohos/entry/src/main/ets/yoin/storage/): Preferences for plain values,
/// Asset Store Kit for secrets.
library;

import 'package:flutter/services.dart';

import 'stores.dart';

const _storageChannel = MethodChannel('yoin/storage');

/// Plain values in the `yoin_prefs` Preferences file.
final class ChannelKeyValueStore implements KeyValueStore {
  const ChannelKeyValueStore({MethodChannel channel = _storageChannel})
      : _channel = channel;

  final MethodChannel _channel;

  @override
  Future<String?> read(String key) =>
      _channel.invokeMethod<String>('kvRead', {'key': key});

  @override
  Future<void> write(String key, String? value) =>
      _channel.invokeMethod<void>('kvWrite', {'key': key, 'value': value});
}

/// Secrets in Asset Store Kit (alias = key), readable once the device has
/// been unlocked after boot.
final class ChannelSecretStore implements SecretStore {
  const ChannelSecretStore({MethodChannel channel = _storageChannel})
      : _channel = channel;

  final MethodChannel _channel;

  @override
  Future<String?> read(String key) =>
      _channel.invokeMethod<String>('secretRead', {'key': key});

  @override
  Future<void> write(String key, String? value) =>
      _channel.invokeMethod<void>('secretWrite', {'key': key, 'value': value});
}
