import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../platform/storage/stores.dart';

/// Whether this install has been through the landing (Android LandingStore).
/// Set when the landing finishes, and at the first launch that finds an
/// account (an existing user never sees it), so deleting every account later
/// does not bring it back — Settings › Welcome guide re-runs it instead.
/// Device-local on purpose.
abstract interface class LandingStore {
  Future<bool> isDone();
  Future<void> markDone();
}

/// [LandingStore] on the app's key-value store (Android yoin_ui_hints/landing_done_v1).
final class KeyValueLandingStore implements LandingStore {
  const KeyValueLandingStore(this._values);

  static const key = 'landing_done_v1';

  final KeyValueStore _values;

  @override
  Future<bool> isDone() async => (await _values.read(key)) == 'true';

  @override
  Future<void> markDone() async {
    if (!await isDone()) await _values.write(key, 'true');
  }
}

final landingStoreProvider = Provider<LandingStore>((ref) => KeyValueLandingStore(ref.watch(keyValueStoreProvider)));
