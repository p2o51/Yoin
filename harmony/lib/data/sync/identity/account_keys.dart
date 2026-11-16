import '../../profile/profile.dart';
import '../sync_database.dart';
import 'account_fingerprints.dart';

/// A music account's id as every device knows it (Android
/// identity/AccountKeys.kt): what says which account a record came from, since
/// local profile ids differ per device. Continue On matches a handed-off song's
/// account by it.
///
/// The sync id of the profile's active binding when it has one (any provider,
/// Apple Music linked by hand included); otherwise, for Subsonic, the id a
/// binding would be minted with ([AccountFingerprints.deterministicId]), worked
/// out from the stored server and username with no network. Null when neither
/// is known (Apple Music not syncing).
final class AccountKeys {
  const AccountKeys({required this.binding, required this.credentials});

  /// The profile's binding in the sync store, or null.
  final Future<SyncBindingEntity?> Function(String localProfileId) binding;

  /// The profile's stored credentials, or null.
  final Future<ProfileCredentials?> Function(Profile profile) credentials;

  Future<String?> of(Profile profile) async {
    ProfileCredentials? stored;
    try {
      stored = await credentials(profile);
    } catch (_) {
      stored = null;
    }
    final subsonic = subsonicFingerprint(stored);
    SyncBindingEntity? bound;
    try {
      bound = await binding(profile.id);
    } catch (_) {
      bound = null;
    }
    if (bound != null && bindingStillFits(bound, subsonic)) return bound.syncProfileId;
    return subsonic == null ? null : AccountFingerprints.deterministicId(subsonic);
  }

  static String? subsonicFingerprint(ProfileCredentials? credentials) => switch (credentials) {
    SubsonicCredentials(:final baseUrl, :final username) => AccountFingerprints.subsonic(baseUrl, username),
    _ => null,
  };

  /// An active binding still names the profile's account: a Subsonic profile
  /// edited to another server or user while sync was off keeps its binding
  /// active until sync next looks, so its fingerprint is checked here rather
  /// than trusted.
  static bool bindingStillFits(SyncBindingEntity binding, String? subsonicFingerprint) {
    if (binding.state != SyncBindingEntity.stateActive) return false;
    final bound = binding.fingerprintHash;
    if (bound == null) return true;
    return subsonicFingerprint == null || AccountFingerprints.fingerprintHash(subsonicFingerprint) == bound;
  }
}
