/// How an account is described to other devices (Android
/// identity/CloudAccountDescriptor.kt): a [SyncKinds.account] record's payload.
library;

import 'package:flutter/foundation.dart';

import '../canonical_json.dart';
import '../sync_contracts.dart';
import '../sync_database.dart';

/// Payload field names of [SyncKinds.account] records. Persisted in Drive: never rename.
abstract final class AccountDescriptorFields {
  static const provider = 'provider';
  static const displayName = 'displayName';
  static const createdAt = 'createdAt';

  /// "`username` @ `host[:port]`" for Subsonic; absent otherwise.
  static const hint = 'hint';

  /// Apple Music storefront (two-letter country), when known.
  static const storefront = 'storefront';

  /// Name of the device that wrote this version; not part of the projection.
  static const deviceName = 'deviceName';
}

/// A music account as some device described it in Drive.
@immutable
final class CloudAccountDescriptor {
  const CloudAccountDescriptor({
    required this.syncProfileId,
    required this.provider,
    required this.displayName,
    this.hint,
    this.storefront,
    this.deviceName,
  });

  final String syncProfileId;
  final String provider;
  final String displayName;
  final String? hint;
  final String? storefront;
  final String? deviceName;

  static CloudAccountDescriptor? fromPayload(String syncProfileId, JsonMap payload) {
    final provider = _string(payload, AccountDescriptorFields.provider);
    if (provider == null || provider.isEmpty) return null;
    return CloudAccountDescriptor(
      syncProfileId: syncProfileId,
      provider: provider,
      displayName: _string(payload, AccountDescriptorFields.displayName) ?? '',
      hint: _string(payload, AccountDescriptorFields.hint),
      storefront: _string(payload, AccountDescriptorFields.storefront),
      deviceName: _string(payload, AccountDescriptorFields.deviceName),
    );
  }

  /// Null for other kinds, tombstones and payloads that aren't a descriptor.
  static CloudAccountDescriptor? fromRecord(SyncRecordEntity record) {
    if (record.kind != SyncKinds.account || record.deleted || record.key.isEmpty) return null;
    final json = parseJsonObject(record.payload);
    return json == null ? null : fromPayload(record.key, json);
  }

  static List<CloudAccountDescriptor> fromRecords(Iterable<SyncRecordEntity> records) {
    final out = <String, CloudAccountDescriptor>{};
    for (final r in records) {
      final d = fromRecord(r);
      if (d != null) out.putIfAbsent(d.syncProfileId, () => d);
    }
    return out.values.toList();
  }

  static String? _string(JsonMap json, String name) {
    final v = json[name];
    return v is String ? v : null;
  }
}
