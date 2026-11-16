/// [SyncKinds.account]: how each ACTIVE binding's account is described to
/// other devices (key = syncProfileId), so they can list "In your Drive, not
/// on this device" (Android adapters/AccountDescriptorSyncAdapter.kt).
/// Read-only towards this device: apply never creates accounts, it only
/// acknowledges ([SkipReason.policy]).
///
/// Only display metadata leaves the device: provider, display name, created
/// time and the sanitized Subsonic "user @ host" hint (plus Apple's
/// storefront, kept as another device published it). Never credentials,
/// never the Apple token service. A field this cycle can't recompute keeps
/// the value already published, so a descriptor neither flaps nor vanishes.
library;

import '../../model/media_id.dart';
import '../../profile/profile.dart';
import '../canonical_json.dart';
import '../identity/account_binder.dart' show CredentialsReader;
import '../identity/account_fingerprints.dart';
import '../identity/cloud_account_descriptor.dart' show AccountDescriptorFields;
import '../sync_contracts.dart';
import '../sync_database.dart';
import 'sync_projections.dart';

class AccountDescriptorSyncAdapter extends SyncAdapter {
  AccountDescriptorSyncAdapter({
    required SyncDatabase db,
    required Future<List<Profile>> Function() profiles,
    required CredentialsReader credentials,
  }) : _db = db,
       _profiles = profiles,
       _credentials = credentials;

  final SyncDatabase _db;
  final Future<List<Profile>> Function() _profiles;
  final CredentialsReader _credentials;

  @override
  String get kind => SyncKinds.account;
  @override
  int get kindVersion => 1;
  @override
  FileClass get fileClass => FileClass.state;
  @override
  bool get perAccount => false;
  @override
  bool get propagatesDeletes => false;
  @override
  ConflictPolicy get conflictPolicy => ConflictPolicy.lww;

  @override
  Future<List<DomainRow>> readAll(String? localProfileId) async {
    final byId = {for (final p in await _profiles()) p.id: p};
    final bindings = _db.bindings().where((b) => b.state == SyncBindingEntity.stateActive).toList()
      ..sort((a, b) => a.syncProfileId.compareTo(b.syncProfileId));
    final rows = <DomainRow>[];
    for (final binding in bindings) {
      final profile = byId[binding.localProfileId];
      if (profile == null) continue;
      String? hint;
      if (profile.provider == MediaId.providerSubsonic) {
        ProfileCredentials? credentials;
        try {
          credentials = await _credentials(profile);
        } catch (_) {
          credentials = null;
        }
        hint = credentials is SubsonicCredentials
            ? AccountFingerprints.subsonicHint(credentials.baseUrl, credentials.username)
            : null;
        hint ??= _publishedField(binding.syncProfileId, AccountDescriptorFields.hint);
        // Nothing published yet either: wait rather than publish a degraded descriptor.
        if (hint == null) continue;
      }
      final storefront = profile.provider == MediaId.providerAppleMusic
          ? _publishedField(binding.syncProfileId, AccountDescriptorFields.storefront)
          : null;
      rows.add(
        DomainRow(
          key: binding.syncProfileId,
          projection: _projection(profile.provider, profile.displayName, profile.createdAt, hint, storefront),
          rowTs: profile.createdAt,
        ),
      );
    }
    return rows;
  }

  @override
  JsonMap? project(JsonMap payload) {
    final provider = requiredString(payload, AccountDescriptorFields.provider);
    final displayName = requiredString(payload, AccountDescriptorFields.displayName);
    final createdAt = requiredLong(payload, AccountDescriptorFields.createdAt);
    final hint = optionalString(payload, AccountDescriptorFields.hint);
    final storefront = optionalString(payload, AccountDescriptorFields.storefront);
    if (provider == null || provider.isEmpty || displayName == null || createdAt == null) return null;
    if (hint is! FieldOk<String?> || storefront is! FieldOk<String?>) return null;
    return _projection(provider, displayName, createdAt, hint.value, storefront.value);
  }

  @override
  JsonMap decoratePayload(JsonMap payload, String deviceName) =>
      withField(payload, AccountDescriptorFields.deviceName, deviceName);

  @override
  Future<ApplyOutcome> apply(
    String? localProfileId,
    String key,
    JsonMap payload,
    int versionTs,
    String? expectedLocalHash,
  ) async => Skipped(SkipReason.policy, await _currentHash(key));

  @override
  Future<ApplyOutcome> delete(String? localProfileId, String key, String? expectedLocalHash) async =>
      Skipped(SkipReason.policy, await _currentHash(key));

  Future<String?> _currentHash(String key) async {
    for (final row in await readAll(null)) {
      if (row.key == key) return hashOrNull(row.projection);
    }
    return null;
  }

  /// A non-blank string field of the descriptor the replica holds for [syncProfileId], if any.
  String? _publishedField(String syncProfileId, String field) {
    final record = _db.getRecord(SyncKinds.account, SyncFormat.globalScope, syncProfileId);
    if (record == null || record.deleted) return null;
    final value = parseJsonObject(record.payload)?[field];
    return value is String && value.trim().isNotEmpty ? value : null;
  }

  static JsonMap _projection(String provider, String displayName, int createdAt, String? hint, String? storefront) => {
    AccountDescriptorFields.provider: provider,
    AccountDescriptorFields.displayName: displayName,
    AccountDescriptorFields.createdAt: createdAt,
    AccountDescriptorFields.hint: hint,
    AccountDescriptorFields.storefront: storefront,
  };
}
