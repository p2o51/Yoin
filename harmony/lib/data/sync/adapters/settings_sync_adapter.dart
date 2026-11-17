/// Global app settings as [SyncKinds.setting] records, payload `{value}`
/// (Android adapters/SettingsSyncAdapter.kt; keys [SyncSettingKeys]).
///
/// A setting is captured only when the user explicitly set it on this device
/// (a default is not an opinion). Once sync tracks a value for a setting here
/// (`tracked`), going back to the default is an explicit choice too: the
/// language reads as [AiLanguages.defaultTarget]. Otherwise the setting would
/// read as absent, the engine would drop its local state and the next apply
/// would put the old value back. Apply policies, when declined, answer
/// [SkipReason.policy] with the current local hash so the engine marks the
/// version applied without mistaking the local value for an edit:
///  - translation_language: only while there are no song About entries
///    (those are language-specific and a switch in Settings wipes them; sync
///    never does), written field-level so the tier and the rest stay.
///  - spotify_client_id: HarmonyOS has no Spotify. Never read here, declined
///    as unsupported on apply: the record is carried for Android devices.
///  - seam_top_style: live, through [SeamStyleGateway].
/// Unknown values are declined, never coerced to a default.
library;

import 'dart:convert';

import '../../../platform/storage/stores.dart';
import '../../ai/ai_settings.dart';
import '../sync_contracts.dart';
import 'sync_projections.dart';

/// Stable record keys of [SyncKinds.setting] (Android SyncSettingKeys).
abstract final class SyncSettingKeys {
  static const translationLanguage = 'translation_language';
  static const spotifyClientId = 'spotify_client_id';
  static const seamTopStyle = 'seam_top_style';
}

/// Where the Scroll edge (top seam) choice lives; a seam so tests can fake it.
abstract interface class SeamStyleGateway {
  /// The stored style key, or null when the user never chose one.
  Future<String?> storedKey();

  /// Shows and stores [key] everywhere; false if it couldn't be applied.
  Future<bool> apply(String key);
}

/// The choice on the key-value store under Android's key and values (the
/// Appearance page's `SeamTopStylePreference` reads the same key); [onApplied]
/// lets the open seams repaint.
final class KeyValueSeamStyleGateway implements SeamStyleGateway {
  KeyValueSeamStyleGateway(this._store, {this.onApplied});

  final KeyValueStore _store;
  final void Function(String key)? onApplied;

  /// Android's `seam_top_style` preference key.
  static const storeKey = 'seam_top_style';

  /// Android SeamTopStyle keys: the tide line (default), dots, cookie wave.
  static const styles = {'tide', 'dots', 'cookie'};

  @override
  Future<String?> storedKey() => _store.read(storeKey);

  @override
  Future<bool> apply(String key) async {
    if (!styles.contains(key)) return false;
    await _store.write(storeKey, key);
    onApplied?.call(key);
    return true;
  }
}

/// The translation language on the AI settings' plain preferences
/// ([AiSettingsStore.prefsKey]); a seam so tests can fake it.
abstract interface class TranslationLanguageGateway {
  /// The stored language, or null when none was ever stored.
  Future<String?> stored();

  /// Writes [language] into the stored preferences, keeping every other field.
  Future<void> write(String language);
}

final class KeyValueTranslationLanguageGateway implements TranslationLanguageGateway {
  KeyValueTranslationLanguageGateway(this._store);

  final KeyValueStore _store;

  Future<Map<String, Object?>?> _prefs() async {
    final raw = await _store.read(AiSettingsStore.prefsKey);
    if (raw == null) return null;
    final json = jsonDecode(raw);
    return json is Map ? json.cast<String, Object?>() : null;
  }

  @override
  Future<String?> stored() async {
    final language = (await _prefs())?['targetLanguage'];
    return language is String ? language : null;
  }

  @override
  Future<void> write(String language) async {
    final prefs = await _prefs() ?? <String, Object?>{};
    await _store.write(AiSettingsStore.prefsKey, jsonEncode({...prefs, 'targetLanguage': language}));
  }
}

class SettingsSyncAdapter extends SyncAdapter {
  SettingsSyncAdapter({
    required TranslationLanguageGateway language,
    required SeamStyleGateway seam,
    Future<bool> Function(String key)? tracked,
    Future<int> Function()? songAboutCount,
  }) : _language = language,
       _seam = seam,
       _tracked = tracked ?? ((_) async => false),
       _songAboutCount = songAboutCount ?? (() async => 0);

  final TranslationLanguageGateway _language;
  final SeamStyleGateway _seam;

  /// Whether this device's local state for a setting key holds a hash, i.e. sync already tracks a local
  /// value for it. Read by [readAll] only: [apply] gets the same fact as a non-null expectedLocalHash.
  final Future<bool> Function(String key) _tracked;

  /// Song About entries kept here (another feature's rows); none while HarmonyOS has no About.
  final Future<int> Function() _songAboutCount;

  @override
  String get kind => SyncKinds.setting;
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

  static JsonMap _projection(String value) => {'value': value};

  @override
  Future<List<DomainRow>> readAll(String? localProfileId) async {
    final language = await _languageValue(await _tracked(SyncSettingKeys.translationLanguage));
    final seam = await _seam.storedKey();
    return [
      if (language != null)
        DomainRow(key: SyncSettingKeys.translationLanguage, projection: _projection(language), rowTs: null),
      if (seam != null) DomainRow(key: SyncSettingKeys.seamTopStyle, projection: _projection(seam), rowTs: null),
    ];
  }

  @override
  JsonMap? project(JsonMap payload) {
    final value = requiredString(payload, 'value');
    return value == null ? null : _projection(value);
  }

  @override
  Future<ApplyOutcome> apply(
    String? localProfileId,
    String key,
    JsonMap payload,
    int versionTs,
    String? expectedLocalHash,
  ) async {
    final value = requiredString(payload, 'value');
    // The engine passes the tracked local hash: non-null = sync tracks a value here.
    final tracked = expectedLocalHash != null;
    return switch (key) {
      SyncSettingKeys.translationLanguage => _applyLanguage(value, expectedLocalHash, tracked),
      SyncSettingKeys.seamTopStyle => _applySeam(value, expectedLocalHash),
      _ => const Skipped(SkipReason.unsupported, null),
    };
  }

  /// Settings never propagate deletes; a purge leaves them as they are.
  @override
  Future<ApplyOutcome> delete(String? localProfileId, String key, String? expectedLocalHash) async {
    final tracked = expectedLocalHash != null;
    final value = switch (key) {
      SyncSettingKeys.translationLanguage => await _languageValue(tracked),
      SyncSettingKeys.seamTopStyle => await _seam.storedKey(),
      _ => null,
    };
    return Skipped(SkipReason.policy, value == null ? null : hashOrNull(_projection(value)));
  }

  Future<ApplyOutcome> _applyLanguage(String? payloadValue, String? expectedLocalHash, bool tracked) async {
    final current = await _languageValue(tracked);
    final currentHash = current == null ? null : hashOrNull(_projection(current));
    if (payloadValue == null) return Skipped(SkipReason.unsupported, currentHash);
    if (currentHash != expectedLocalHash) return Skipped(SkipReason.changedLocally, currentHash);
    if (!AiLanguages.supported.contains(payloadValue)) return Skipped(SkipReason.policy, currentHash);
    if (await _songAboutCount() > 0) return Skipped(SkipReason.policy, currentHash);
    if (await _language.stored() != null || payloadValue != AiLanguages.defaultTarget) {
      await _language.write(payloadValue);
    }
    final after = await _languageValue(tracked);
    return Applied(after == null ? null : hashOrNull(_projection(after)));
  }

  Future<ApplyOutcome> _applySeam(String? payloadValue, String? expectedLocalHash) async {
    final current = await _seam.storedKey();
    final currentHash = current == null ? null : hashOrNull(_projection(current));
    if (payloadValue == null) return Skipped(SkipReason.unsupported, currentHash);
    if (currentHash != expectedLocalHash) return Skipped(SkipReason.changedLocally, currentHash);
    if (!KeyValueSeamStyleGateway.styles.contains(payloadValue)) return Skipped(SkipReason.policy, currentHash);
    if (!await _seam.apply(payloadValue)) return Skipped(SkipReason.policy, currentHash);
    final after = await _seam.storedKey();
    return Applied(after == null ? null : hashOrNull(_projection(after)));
  }

  /// Explicit only: a stored non-default language, or the default once sync tracks this setting.
  Future<String?> _languageValue(bool tracked) async {
    final stored = await _language.stored();
    if (stored != null && stored != AiLanguages.defaultTarget) return stored;
    return tracked ? AiLanguages.defaultTarget : null;
  }
}
