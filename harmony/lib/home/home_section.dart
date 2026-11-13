import 'package:flutter_riverpod/flutter_riverpod.dart';

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
        activities || jumpBackIn || recentlyAdded || recentlyPlayed || yourPlaylists => true,
        rediscover || otherProfiles || lyricHighlight || memoryGrid => false,
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

/// The user's Home layout (HomeLayout). v1 is read-only: [defaultLayout].
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

/// The active layout. Read-only in v1 (edit mode is deferred); edit mode will
/// back this with a per-profile store.
final homeLayoutProvider = Provider<HomeLayout>((ref) => HomeLayout.defaultLayout);
