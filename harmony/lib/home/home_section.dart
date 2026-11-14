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
        activities || jumpBackIn || recentlyAdded || rediscover || recentlyPlayed || yourPlaylists || memoryGrid => true,
        otherProfiles || lyricHighlight => false,
      };

  static HomeSection? byId(String id) {
    for (final s in values) {
      if (s.id == id) return s;
    }
    return null;
  }
}

/// Rows preset (HomeRowPreset): L is the default and is not written.
enum HomeRowPreset {
  s('s'),
  m('m'),
  l('l'),
  xl('xl');

  const HomeRowPreset(this.id);
  final String id;

  static HomeRowPreset byId(String? id) => values.firstWhere((p) => p.id == id, orElse: () => HomeRowPreset.l);
}

/// One slot of a layout. [id] is kept verbatim so unknown (newer) ids survive.
final class HomeSectionPref {
  const HomeSectionPref({required this.id, required this.enabled, this.config = const {}});

  final String id;
  final bool enabled;

  /// Persisted as {"rows": "s"|"m"|"xl"}; L is not written.
  final Map<String, String> config;

  HomeSection? get section => HomeSection.byId(id);
  HomeRowPreset get rows => HomeRowPreset.byId(config['rows']);

  Map<String, Object?> toJson() => {'id': id, 'enabled': enabled, if (config.isNotEmpty) 'config': config};

  factory HomeSectionPref.fromJson(Map<String, Object?> json) => HomeSectionPref(
        id: json['id']! as String,
        enabled: (json['enabled'] as bool?) ?? true,
        config: ((json['config'] as Map?) ?? const {}).map((k, v) => MapEntry('$k', '$v')),
      );

  @override
  bool operator ==(Object other) =>
      other is HomeSectionPref &&
      other.id == id &&
      other.enabled == enabled &&
      other.config.length == config.length &&
      config.entries.every((e) => other.config[e.key] == e.value);

  @override
  int get hashCode => Object.hash(id, enabled, Object.hashAllUnordered(config.entries.map((e) => '${e.key}=${e.value}')));
}

/// The user's Home layout (HomeLayout), edited in place (home_edit.dart).
final class HomeLayout {
  const HomeLayout(this.sections);

  final List<HomeSectionPref> sections;

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
    for (final s in HomeSection.values) {
      if (!seen.contains(s.id)) out.add(HomeSectionPref(id: s.id, enabled: s.appendEnabled));
    }
    return HomeLayout(out);
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

/// [layout] with [section] hidden or shown again (in its own place).
HomeLayout withSectionEnabled(HomeLayout layout, HomeSection section, bool enabled) => HomeLayout([
      for (final p in layout.sections)
        p.id == section.id ? HomeSectionPref(id: p.id, enabled: enabled, config: p.config) : p,
    ]);

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
  ]);
}

/// Home's layout per account (Android home_layout, one row per profile):
/// JSON on the key-value store, read when the account changes, written on
/// every edit. Until the read lands the default layout stands.
class HomeLayoutNotifier extends Notifier<HomeLayout> {
  static String keyFor(String provider, String profileId) => 'home_layout.v1.$provider|$profileId';

  String? _key;
  int _generation = 0;

  @override
  HomeLayout build() {
    final source = ref.watch(activeSourceProvider);
    final generation = ++_generation;
    _key = null;
    Future.microtask(() async {
      try {
        final profile = await ref.read(profileRepositoryProvider).activeProfile();
        if (!ref.mounted || generation != _generation || profile == null) return;
        final key = keyFor(source.value?.provider ?? profile.provider, profile.id);
        _key = key;
        final raw = await ref.read(keyValueStoreProvider).read(key);
        if (!ref.mounted || generation != _generation || raw == null) return;
        state = HomeLayout.fromJson((jsonDecode(raw) as Map).cast<String, Object?>());
      } catch (_) {
        // Unreadable: the default stands.
      }
    });
    return HomeLayout.defaultLayout;
  }

  /// Shows [layout] and saves it for the active account.
  void set(HomeLayout layout) {
    state = layout;
    final key = _key;
    if (key == null) return;
    ref.read(keyValueStoreProvider).write(key, jsonEncode(layout.toJson())).catchError((Object _) {});
  }
}

/// The active account's layout.
final homeLayoutProvider = NotifierProvider<HomeLayoutNotifier, HomeLayout>(HomeLayoutNotifier.new);
