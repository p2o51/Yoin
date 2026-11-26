/// Home's stored layout document (docs/design.md §主页 › 就地编辑; Android PR #250 / #292), a Dart port of
/// `:shared` HomeLayoutCodec.kt for parity phase 4:
/// `{"version":1,"sections":[{"id":"activities","enabled":true,"config":{…}}, …],"wide":{"pairs":[…]}}`.
///
/// Read entry by entry: a document that isn't an object with a `sections` array (or whose `version` isn't
/// an integer) is unreadable — "never customized" — while a malformed entry (no string `id`, no boolean
/// `enabled`, a `config` that isn't an object or null) drops only itself. A `wide` that isn't an object
/// reads as none. Every key this build doesn't read — the document's and each entry's — is kept and written
/// back. Written compact, keys in the order `version`, `sections`, `wide`, then the kept ones; an entry
/// `id`, `enabled`, `config`, then its kept ones. The reading rules keep kotlinx's (Android's store) quirks
/// exactly, as `home-layout.json` pins them: a quoted `"version":"1"` and a quoted `"enabled":"true"` read,
/// a numeric `id` drops the entry.
library;

import 'dart:convert';

/// Deep equality of decoded JSON values: maps by key (order aside, as kotlinx's `JsonObject`), lists in order.
bool homeJsonEquals(Object? a, Object? b) {
  if (identical(a, b)) return true;
  if (a is Map && b is Map) {
    if (a.length != b.length) return false;
    for (final e in a.entries) {
      if (!b.containsKey(e.key) || !homeJsonEquals(e.value, b[e.key])) return false;
    }
    return true;
  }
  if (a is List && b is List) {
    if (a.length != b.length) return false;
    for (var i = 0; i < a.length; i++) {
      if (!homeJsonEquals(a[i], b[i])) return false;
    }
    return true;
  }
  return a == b;
}

/// A hash consistent with [homeJsonEquals].
int homeJsonHash(Object? v) {
  if (v is Map) return Object.hashAllUnordered([for (final e in v.entries) Object.hash(e.key, homeJsonHash(e.value))]);
  if (v is List) return Object.hashAll(v.map(homeJsonHash));
  return v.hashCode;
}

/// One persisted section choice: its stable [id] and whether it renders. Position in the persisted list is
/// the render order. A plain id string, so ids this build doesn't know ride along. [config] is the section's
/// settings bag (`{"rows":"xl","span":"half","units":2}`), absent at its defaults (never written as null);
/// [extras] are the entry's other keys (a newer build's), kept verbatim.
final class HomeSectionPref {
  const HomeSectionPref({required this.id, required this.enabled, this.config, this.extras});

  final String id;
  final bool enabled;
  final Map<String, Object?>? config;
  final Map<String, Object?>? extras;

  @override
  bool operator ==(Object other) =>
      other is HomeSectionPref &&
      other.id == id &&
      other.enabled == enabled &&
      homeJsonEquals(other.config, config) &&
      homeJsonEquals(other.extras, extras);

  @override
  int get hashCode => Object.hash(id, enabled, homeJsonHash(config), homeJsonHash(extras));

  @override
  String toString() => 'HomeSectionPref($id, $enabled, $config, $extras)';
}

/// The whole stored layout: [sections], [wide] (the two-column pairing memory, `{"pairs":[[l, r], …]}`,
/// opaque here) and [extras] (the document's other keys). Null [wide]: written by a build without it, so
/// the layout derives it again on read.
final class HomeLayoutDocument {
  const HomeLayoutDocument({required this.sections, this.wide, this.extras});

  final List<HomeSectionPref> sections;
  final Map<String, Object?>? wide;
  final Map<String, Object?>? extras;
}

abstract final class HomeLayoutCodec {
  static const int schemaVersion = 1;

  /// The document keys the codec reads; every other one is kept in [HomeLayoutDocument.extras].
  static const List<String> documentKeys = ['version', 'sections', 'wide'];

  /// The entry keys the codec reads; every other one is kept in [HomeSectionPref.extras].
  static const List<String> entryKeys = ['id', 'enabled', 'config'];

  static final RegExp _integer = RegExp(r'^-?\d+$');

  /// [raw] read, or null when it is unreadable (treated as "never set").
  static HomeLayoutDocument? decode(String raw) {
    final Object? root;
    try {
      root = jsonDecode(raw);
    } on FormatException {
      return null;
    }
    if (root is! Map<String, dynamic>) return null;
    if (root.containsKey('version') && !_isInt32(root['version'])) return null;
    final sections = root['sections'];
    if (sections is! List) return null;
    final wide = root['wide'];
    return HomeLayoutDocument(
      sections: [for (final entry in sections) ?decodeEntry(entry)],
      wide: wide is Map<String, dynamic> ? wide : null,
      extras: _without(root, documentKeys),
    );
  }

  // kotlinx reads an Int from a number literal or a quoted one; a fraction, null, true or an overflow fail.
  static bool _isInt32(Object? v) {
    final int? n;
    if (v is int) {
      n = v;
    } else if (v is String && _integer.hasMatch(v)) {
      n = int.tryParse(v);
    } else {
      return false;
    }
    return n != null && n >= -0x80000000 && n <= 0x7fffffff;
  }

  /// One entry read; null when it is malformed (it drops alone).
  static HomeSectionPref? decodeEntry(Object? entry) {
    if (entry is! Map<String, dynamic>) return null;
    final id = entry['id'];
    if (id is! String) return null;
    final enabled = _bool(entry['enabled']);
    if (enabled == null) return null;
    final config = entry['config'];
    if (config != null && config is! Map<String, dynamic>) return null;
    return HomeSectionPref(
      id: id,
      enabled: enabled,
      config: config as Map<String, dynamic>?,
      extras: _without(entry, entryKeys),
    );
  }

  // kotlinx's tree decoder reads a Boolean from the primitive's content: true / false, quoted or not.
  static bool? _bool(Object? v) {
    if (v is bool) return v;
    if (v is String) {
      final lower = v.toLowerCase();
      if (lower == 'true') return true;
      if (lower == 'false') return false;
    }
    return null;
  }

  /// [document] as the stored text.
  static String encode(HomeLayoutDocument document) => jsonEncode(encodeElement(document));

  /// [document] as a JSON object (what [encode] writes).
  static Map<String, Object?> encodeElement(HomeLayoutDocument document) => {
    'version': schemaVersion,
    'sections': [for (final pref in document.sections) encodeEntry(pref)],
    'wide': ?document.wide,
    if (document.extras case final extras?)
      for (final e in extras.entries)
        if (!documentKeys.contains(e.key)) e.key: e.value,
  };

  /// One entry as written.
  static Map<String, Object?> encodeEntry(HomeSectionPref pref) => {
    'id': pref.id,
    'enabled': pref.enabled,
    'config': ?pref.config,
    if (pref.extras case final extras?)
      for (final e in extras.entries)
        if (!entryKeys.contains(e.key)) e.key: e.value,
  };

  static Map<String, Object?>? _without(Map<String, dynamic> map, List<String> keys) {
    final out = <String, Object?>{
      for (final e in map.entries)
        if (!keys.contains(e.key)) e.key: e.value,
    };
    return out.isEmpty ? null : out;
  }
}
