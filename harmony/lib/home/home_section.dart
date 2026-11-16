import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';

/// Home's section catalogue (Android ui/home/HomeSection.kt), ported whole so
/// edit mode can plug in later without a migration. [id]s are persisted:
/// never rename one. Enum order is the default order.
enum HomeSection {
  activities('activities', supportsRows: true),
  jumpBackIn('jump_back_in', supportsRows: true),
  recentlyAdded('recently_added'),
  rediscover('rediscover', appendEnabled: false),
  recentlyPlayed('recently_played'),
  yourPlaylists('your_playlists'),
  otherProfiles('other_profiles'),
  lyricHighlight('lyric_highlight', takesPresets: true),
  memoryGrid('memory_grid');

  const HomeSection(this.id, {this.supportsRows = false, this.appendEnabled = true, bool takesPresets = false})
      : _takesPresets = takesPresets;

  final String id;
  final bool supportsRows;

  /// Enabled when added to a saved layout that never knew it.
  final bool appendEnabled;
  final bool _takesPresets;

  bool get defaultEnabled => true;
  bool get takesPresets => supportsRows || _takesPresets;

  /// Built in the HarmonyOS v1 (the Subsonic build can fill it).
  bool get availableInV1 => switch (this) {
    activities ||
    jumpBackIn ||
    recentlyAdded ||
    rediscover ||
    recentlyPlayed ||
    yourPlaylists ||
    otherProfiles ||
    memoryGrid => true,
    // On every device (Android owner 2026-11-08): without Gemini Nano it shows the user's own picks.
    lyricHighlight => true,
  };

  static HomeSection? byId(String id) {
    for (final s in values) {
      if (s.id == id) return s;
    }
    return null;
  }
}

/// How many rows a section shows (HomeRowPreset, D1). Presets, not numbers:
/// each screen turns one into its own composition (home_row_presets.dart), so
/// the choice travels with the account across screens. [l] is the
/// composition from before presets existed — a section nobody resized stores
/// nothing and renders as it did. Declaration order is the size order.
enum HomeRowPreset {
  s('s'),
  m('m'),
  l('l'),
  xl('xl');

  const HomeRowPreset(this.id);
  final String id;

  static const HomeRowPreset defaultPreset = HomeRowPreset.l;

  /// The preset stored under [id]; null for an id this build doesn't know.
  static HomeRowPreset? fromId(String? id) {
    for (final p in values) {
      if (p.id == id) return p;
    }
    return null;
  }

  /// [fromId], or [defaultPreset] for a missing or unknown id.
  static HomeRowPreset byId(String? id) => fromId(id) ?? defaultPreset;
}

/// One slot of a layout. [id] is kept verbatim so unknown (newer) ids survive.
final class HomeSectionPref {
  const HomeSectionPref({required this.id, required this.enabled, this.config = const {}});

  final String id;
  final bool enabled;

  /// The section's settings bag, kept verbatim (keys a newer build adds ride
  /// along): {"rows": "s"|"m"|"xl"} today; L is not written.
  final Map<String, Object?> config;

  static const String rowsKey = 'rows';

  HomeSection? get section => HomeSection.byId(id);

  /// The row preset; [HomeRowPreset.defaultPreset] when unset or unreadable.
  HomeRowPreset get rows => HomeRowPreset.byId(config[rowsKey] is String ? config[rowsKey]! as String : null);

  /// This pref at [preset]: `rows` written, or dropped for the default — the
  /// rest of the bag (and an unreadable `rows` when nothing changes) kept.
  HomeSectionPref withRows(HomeRowPreset preset) {
    if (rows == preset) return this;
    final bag = {...config}..remove(rowsKey);
    if (preset != HomeRowPreset.defaultPreset) bag[rowsKey] = preset.id;
    return HomeSectionPref(id: id, enabled: enabled, config: bag);
  }

  Map<String, Object?> toJson() => {'id': id, 'enabled': enabled, if (config.isNotEmpty) 'config': config};

  factory HomeSectionPref.fromJson(Map<String, Object?> json) => HomeSectionPref(
        id: json['id']! as String,
        enabled: (json['enabled'] as bool?) ?? true,
        config: ((json['config'] as Map?) ?? const {}).map((k, v) => MapEntry('$k', v)),
      );

  @override
  bool operator ==(Object other) =>
      other is HomeSectionPref && other.id == id && other.enabled == enabled && _jsonEquals(other.config, config);

  @override
  int get hashCode => Object.hash(id, enabled, Object.hashAllUnordered(config.keys));
}

/// Deep equality of decoded JSON values (maps by key, lists in order).
bool _jsonEquals(Object? a, Object? b) {
  if (a is Map && b is Map) {
    if (a.length != b.length) return false;
    for (final e in a.entries) {
      if (!b.containsKey(e.key) || !_jsonEquals(e.value, b[e.key])) return false;
    }
    return true;
  }
  if (a is List && b is List) {
    if (a.length != b.length) return false;
    for (var i = 0; i < a.length; i++) {
      if (!_jsonEquals(a[i], b[i])) return false;
    }
    return true;
  }
  return a == b;
}

/// The user's Home layout (HomeLayout), edited in place (home_edit.dart).
final class HomeLayout {
  const HomeLayout(this.sections, {this.newSections = const {}});

  final List<HomeSectionPref> sections;

  /// The sections appended hidden to a saved layout on this read
  /// ([HomeSection.appendEnabled] false): they wait in the tray as "New"
  /// (home_edit_hints.dart). Not part of the user's arrangement; edits keep it.
  final Set<HomeSection> newSections;

  static final HomeLayout defaultLayout = HomeLayout([
    for (final s in HomeSection.values) HomeSectionPref(id: s.id, enabled: s.defaultEnabled),
  ]);

  /// Ids the catalogue retired; dropped on reconcile.
  static const retiredIds = {'memory_teaser', 'memories'};

  /// Saved prefs → a layout: null / empty → default; keep saved order, flags
  /// and config for known ids (first occurrence wins), drop retired ids, keep
  /// other unknown ids verbatim, append catalogue sections the save never knew
  /// at [HomeSection.appendEnabled].
  static HomeLayout reconcile(List<HomeSectionPref>? saved) {
    if (saved == null || saved.isEmpty) return defaultLayout;
    final seen = <String>{};
    final out = <HomeSectionPref>[];
    for (final pref in saved) {
      if (retiredIds.contains(pref.id) || !seen.add(pref.id)) continue;
      out.add(pref);
    }
    final appendedHidden = <HomeSection>{};
    for (final s in HomeSection.values) {
      if (seen.contains(s.id)) continue;
      out.add(HomeSectionPref(id: s.id, enabled: s.appendEnabled));
      if (!s.appendEnabled) appendedHidden.add(s);
    }
    return HomeLayout(out, newSections: appendedHidden);
  }

  /// [section]'s row preset ([HomeRowPreset.defaultPreset] when it is not in this layout).
  HomeRowPreset rowsOf(HomeSection section) {
    for (final p in sections) {
      if (p.id == section.id) return p.rows;
    }
    return HomeRowPreset.defaultPreset;
  }

  /// The catalogue arrangement, every section at its default rows; unknown ids ignored.
  bool get isDefault {
    final known = [for (final p in sections) if (p.section != null) p];
    final defaults = defaultLayout.sections;
    if (known.length != defaults.length) return false;
    for (var i = 0; i < known.length; i++) {
      if (known[i] != defaults[i]) return false;
    }
    return true;
  }

  /// Sections the v1 feed draws, in layout order (enabled, known, built).
  List<HomeSection> get visibleV1Sections => [
        for (final pref in sections)
          if (pref.enabled && (pref.section?.availableInV1 ?? false)) pref.section!,
      ];

  /// Versioned JSON as Android's home_layout row ({version, sections}).
  Map<String, Object?> toJson() => {'version': 1, 'sections': [for (final s in sections) s.toJson()]};

  static HomeLayout fromJson(Map<String, Object?> json) => reconcile([
        for (final s in (json['sections'] as List?) ?? const []) HomeSectionPref.fromJson((s! as Map).cast<String, Object?>()),
      ]);
}

/// [layout] with [section] hidden or shown again (in its own place); [layout]
/// itself when nothing changes.
HomeLayout withSectionEnabled(HomeLayout layout, HomeSection section, bool enabled) {
  if (!layout.sections.any((p) => p.id == section.id && p.enabled != enabled)) return layout;
  return HomeLayout([
    for (final p in layout.sections)
      p.id == section.id ? HomeSectionPref(id: p.id, enabled: enabled, config: p.config) : p,
  ], newSections: layout.newSections);
}

/// [layout] with [section] at row preset [rows] (HomeLayout.withRows): [layout]
/// itself for a section without presets, one not in it, or one already there.
HomeLayout withSectionRows(HomeLayout layout, HomeSection section, HomeRowPreset rows) {
  if (!section.takesPresets || !layout.sections.any((p) => p.id == section.id && p.rows != rows)) return layout;
  return HomeLayout([
    for (final p in layout.sections) p.id == section.id ? p.withRows(rows) : p,
  ], newSections: layout.newSections);
}

/// The catalogue arrangement — rows back to their defaults too — keeping the
/// ids this build doesn't know at the end (HomeLayout.reset); [layout] itself
/// when it already is.
HomeLayout resetLayout(HomeLayout layout) {
  if (layout.isDefault) return layout;
  final unknown = [for (final p in layout.sections) if (p.section == null) p];
  return unknown.isEmpty ? HomeLayout.defaultLayout : HomeLayout([...HomeLayout.defaultLayout.sections, ...unknown]);
}

/// [layout] with [section] moved to [to] among [visible] (the sections the
/// feed draws, in order): the visible slots keep their places in the saved
/// list and only their occupants permute, so hidden and unknown sections stay
/// where they were. Each section's settings go with it.
HomeLayout withSectionMoved(HomeLayout layout, List<HomeSection> visible, HomeSection section, int to) {
  final from = visible.indexOf(section);
  if (from < 0 || to < 0 || to >= visible.length || from == to) return layout;
  final order = [...visible]
    ..removeAt(from)
    ..insert(to, section);
  final prefById = {for (final p in layout.sections) p.id: p};
  final ids = {for (final s in visible) s.id};
  var next = 0;
  return HomeLayout([
    for (final p in layout.sections) ids.contains(p.id) ? prefById[order[next++].id]! : p,
  ], newSections: layout.newSections);
}

/// Home's layout per account (Android home_layout, one row per profile):
/// JSON on the key-value store, read when the account changes, written on
/// every edit. Until the read lands the default layout stands.
class HomeLayoutNotifier extends Notifier<HomeLayout> {
  static String keyFor(String provider, String profileId) => 'home_layout.v1.$provider|$profileId';

  /// When the layout was last edited here (ms), beside it: Android's
  /// home_layout.updatedAt, which cloud sync compares across devices.
  static String updatedAtKeyFor(String provider, String profileId) => 'home_layout.updatedAt.v1.$provider|$profileId';

  String? _key;
  int _generation = 0;

  @override
  HomeLayout build() {
    final source = ref.watch(activeSourceProvider);
    final generation = ++_generation;
    _key = null;
    _updatedAtKey = null;
    Future.microtask(() async {
      try {
        final profile = await ref.read(profileRepositoryProvider).activeProfile();
        if (!ref.mounted || generation != _generation || profile == null) return;
        final provider = source.value?.provider ?? profile.provider;
        final key = keyFor(provider, profile.id);
        _key = key;
        _updatedAtKey = updatedAtKeyFor(provider, profile.id);
        final raw = await ref.read(keyValueStoreProvider).read(key);
        if (!ref.mounted || generation != _generation || raw == null) return;
        state = HomeLayout.fromJson((jsonDecode(raw) as Map).cast<String, Object?>());
      } catch (_) {
        // Unreadable: the default stands.
      }
    });
    return HomeLayout.defaultLayout;
  }

  String? _updatedAtKey;

  /// Shows [layout] and saves it for the active account.
  void set(HomeLayout layout) {
    state = layout;
    final key = _key;
    final updatedAtKey = _updatedAtKey;
    if (key == null || updatedAtKey == null) return;
    final store = ref.read(keyValueStoreProvider);
    store
        .write(key, jsonEncode(layout.toJson()))
        .then((_) => store.write(updatedAtKey, '${DateTime.now().millisecondsSinceEpoch}'))
        .catchError((Object _) {});
  }

  /// Back to the default layout: the saved one is deleted (Android
  /// HomeLayoutStore.clearLayout), so cloud sync takes every device back to
  /// the default instead of pulling an old layout back.
  void clear() {
    state = HomeLayout.defaultLayout;
    final key = _key;
    final updatedAtKey = _updatedAtKey;
    if (key == null || updatedAtKey == null) return;
    final store = ref.read(keyValueStoreProvider);
    store.write(key, null).then((_) => store.write(updatedAtKey, null)).catchError((Object _) {});
  }
}

/// The active account's layout.
final homeLayoutProvider = NotifierProvider<HomeLayoutNotifier, HomeLayout>(HomeLayoutNotifier.new);
