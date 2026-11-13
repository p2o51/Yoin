import 'dart:convert';
import 'dart:math';

import '../../platform/storage/stores.dart';
import 'profile.dart';

/// Configured accounts and the active one (data/profile/ProfileManager.kt,
/// storage half). The list lives in [KeyValueStore]; each profile's
/// credentials live encrypted in [SecretStore] under its own key.
class ProfileRepository {
  ProfileRepository(this._values, this._secrets, {Random? random}) : _random = random ?? Random.secure();

  final KeyValueStore _values;
  final SecretStore _secrets;
  final Random _random;

  static const profilesKey = 'profiles.v1';
  static const activeKey = 'profiles.active.v1';
  static String credentialsKey(String profileId) => 'profile_credentials.$profileId';

  Future<List<Profile>> profiles() async {
    final raw = await _values.read(profilesKey);
    if (raw == null) return const [];
    final list = jsonDecode(raw) as List<Object?>;
    return [for (final item in list) Profile.fromJson((item! as Map).cast<String, Object?>())];
  }

  /// The active profile, falling back to the first one when the stored id is gone.
  Future<Profile?> activeProfile() async {
    final all = await profiles();
    if (all.isEmpty) return null;
    final id = await _values.read(activeKey);
    return all.firstWhere((p) => p.id == id, orElse: () => all.first);
  }

  Future<void> setActive(String profileId) => _values.write(activeKey, profileId);

  /// Saves a new profile with its credentials and makes it active.
  Future<Profile> add({required String provider, required String displayName, required ProfileCredentials credentials}) async {
    final profile = Profile(id: _newId(), provider: provider, displayName: displayName);
    // Secret first: a listed profile must always have credentials.
    await _secrets.write(credentialsKey(profile.id), jsonEncode(credentials.toJson()));
    await _saveList([...await profiles(), profile]);
    await setActive(profile.id);
    return profile;
  }

  Future<ProfileCredentials?> credentials(String profileId) async {
    final raw = await _secrets.read(credentialsKey(profileId));
    if (raw == null) return null;
    return ProfileCredentials.fromJson((jsonDecode(raw) as Map).cast<String, Object?>());
  }

  Future<void> remove(String profileId) async {
    final remaining = [for (final p in await profiles()) if (p.id != profileId) p];
    await _saveList(remaining);
    await _secrets.write(credentialsKey(profileId), null);
    if (await _values.read(activeKey) == profileId) {
      await _values.write(activeKey, remaining.isEmpty ? null : remaining.first.id);
    }
  }

  Future<void> _saveList(List<Profile> profiles) =>
      _values.write(profilesKey, jsonEncode([for (final p in profiles) p.toJson()]));

  String _newId() => List.generate(16, (_) => _random.nextInt(256).toRadixString(16).padLeft(2, '0')).join();
}
