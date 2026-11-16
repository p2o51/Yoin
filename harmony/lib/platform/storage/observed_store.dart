/// A [KeyValueStore] that says which keys were written, so cloud sync can
/// notice a local change (a note, a rating, a layout) without every feature
/// telling it — Android's Room invalidation tracker on the synced tables.
library;

import 'dart:async';

import 'stores.dart';

final class ObservedKeyValueStore implements KeyValueStore {
  ObservedKeyValueStore(this._inner);

  final KeyValueStore _inner;
  final _writes = StreamController<String>.broadcast();

  /// Each key once its write landed.
  Stream<String> get writes => _writes.stream;

  @override
  Future<String?> read(String key) => _inner.read(key);

  @override
  Future<void> write(String key, String? value) async {
    await _inner.write(key, value);
    if (!_writes.isClosed) _writes.add(key);
  }
}
