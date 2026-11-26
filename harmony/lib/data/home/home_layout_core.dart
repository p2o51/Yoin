/// Home's layout model (docs/design.md §主页 › 就地编辑; Android PR #250 / #292), a Dart port of `:shared`
/// HomeLayout.kt plus the session rules of HomeEditRules.kt that need a layout, for parity phase 4: one
/// order and one show / hide state for both widths, the two-column pairing memory, and every edit. Answers
/// exactly as shared/src/commonTest/resources/golden/home-layout.json says
/// (test/data/home/home_layout_golden_test.dart).
///
/// Every operation returns `this` when it changes nothing, so callers can skip the write (and the Undo
/// step) by identity.
library;

import 'home_catalog.dart';
import 'home_layout_codec.dart';

const Object _unset = Object();

/// One section plus whether it currently renders, at its resolved position. [rows] is its row preset;
/// [extras] are the other keys of its persisted settings bag, kept verbatim.
final class HomeSectionState {
  const HomeSectionState(
    this.section,
    this.enabled, {
    this.rows = HomeRowPreset.defaultPreset,
    this.extras,
    this.half = false,
    this.rowsSet = false,
    this.units,
    this.entryExtras,
  });

  final HomeSection section;
  final bool enabled;
  final HomeRowPreset rows;
  final Map<String, Object?>? extras;

  /// Half of a two-column row on a large screen (`"span":"half"`). Always one of a pair the memory holds at
  /// this order; below the two-column width it stacks like any section, the flag kept.
  final bool half;

  /// [rows] was chosen, not defaulted: stored even at [HomeRowPreset.defaultPreset].
  final bool rowsSet;

  /// Two columns' unit count for a section whose row preset means something else (Lyric Cards); null = default.
  final int? units;

  /// The persisted entry's keys besides `id`, `enabled` and `config`, kept verbatim.
  final Map<String, Object?>? entryExtras;

  String get id => section.id;

  static const String rowsKey = 'rows';
  static const String spanKey = 'span';
  static const String spanHalf = 'half';
  static const String unitsKey = 'units';

  /// The settings bag to persist: [extras] plus `rows` when chosen (or not the default), `span` when half,
  /// `units` when set; null when empty.
  Map<String, Object?>? config() {
    final bag = <String, Object?>{...?extras};
    if (rowsSet || rows != HomeRowPreset.defaultPreset) bag[rowsKey] = rows.key;
    if (half) bag[spanKey] = spanHalf;
    if (units case final u?) bag[unitsKey] = u;
    return bag.isEmpty ? null : bag;
  }

  static final RegExp _int = RegExp(r'^[+-]?\d+$');

  /// [pref] as a state: a readable `rows` becomes [rows], `"span":"half"` becomes [half], a readable
  /// `units` becomes [units]; everything else stays in [extras].
  static HomeSectionState fromPref(HomeSection section, HomeSectionPref pref) {
    final config = pref.config;
    final rawRows = config?[rowsKey];
    final rows = rawRows is String ? HomeRowPreset.fromKey(rawRows) : null;
    final half = config?[spanKey] == spanHalf;
    final units = _intOf(config?[unitsKey]);
    final extras = config == null
        ? null
        : <String, Object?>{
            for (final e in config.entries)
              if (!(e.key == rowsKey && rows != null) &&
                  !(e.key == spanKey && half) &&
                  !(e.key == unitsKey && units != null))
                e.key: e.value,
          };
    return HomeSectionState(
      section,
      pref.enabled,
      rows: rows ?? HomeRowPreset.defaultPreset,
      extras: extras == null || extras.isEmpty ? null : extras,
      half: half,
      rowsSet: rows != null,
      units: units,
      entryExtras: pref.extras,
    );
  }

  // kotlinx `contentOrNull?.toIntOrNull()`: an integer literal, quoted or not, in Int range.
  static int? _intOf(Object? v) {
    final String text;
    if (v is int) {
      text = '$v';
    } else if (v is String) {
      text = v;
    } else {
      return null;
    }
    if (!_int.hasMatch(text)) return null;
    final n = int.tryParse(text);
    return n != null && n >= -0x80000000 && n <= 0x7fffffff ? n : null;
  }

  HomeSectionState copyWith({
    bool? enabled,
    HomeRowPreset? rows,
    Object? extras = _unset,
    bool? half,
    bool? rowsSet,
    Object? units = _unset,
    Object? entryExtras = _unset,
  }) => HomeSectionState(
    section,
    enabled ?? this.enabled,
    rows: rows ?? this.rows,
    extras: identical(extras, _unset) ? this.extras : extras as Map<String, Object?>?,
    half: half ?? this.half,
    rowsSet: rowsSet ?? this.rowsSet,
    units: identical(units, _unset) ? this.units : units as int?,
    entryExtras: identical(entryExtras, _unset) ? this.entryExtras : entryExtras as Map<String, Object?>?,
  );

  @override
  bool operator ==(Object other) =>
      other is HomeSectionState &&
      other.section == section &&
      other.enabled == enabled &&
      other.rows == rows &&
      homeJsonEquals(other.extras, extras) &&
      other.half == half &&
      other.rowsSet == rowsSet &&
      other.units == units &&
      homeJsonEquals(other.entryExtras, entryExtras);

  @override
  int get hashCode =>
      Object.hash(section, enabled, rows, homeJsonHash(extras), half, rowsSet, units, homeJsonHash(entryExtras));

  @override
  String toString() =>
      'HomeSectionState(${section.id}, $enabled, ${rows.key}${rowsSet ? '*' : ''}${half ? ', half' : ''}'
      '${units != null ? ', units $units' : ''})';
}

/// The resolved, ordered home layout the feed renders from. [sections] is in render order; disabled
/// entries keep their slot (so "Show" returns a section home).
///
/// One order for both widths (owner 2026-11-23: 「转了竖屏以后只保留顺序即可，回到横屏再记忆哪些在左边哪些在右边」):
/// who pairs with whom in two columns is remembered apart, in [widePairs], and shown wherever the order
/// allows it ([homeWidePairsAt]). The `half` flags are that result at this order, kept in step by every
/// operation, so older builds read them as before. Order and visibility edits leave the memory alone;
/// two-column edits make it what two columns show ([rememberingShownPairs]).
///
/// [retained] holds ids this build doesn't know, [retainedWide] the memory's unreadable parts and
/// [retainedDocument] the document's other keys, all verbatim; [newSections] are the sections appended
/// hidden on this read. None of the four is part of the arrangement: compare with [sameSectionsAs].
final class HomeLayout {
  HomeLayout(
    this.sections, {
    this.retained = const [],
    this.newSections = const {},
    Map<HomeSection, HomeSection>? widePairs,
    this.retainedWide,
    this.retainedDocument,
  }) : widePairs =
           widePairs ??
           homeSpanPairs([
             for (final s in sections)
               if (s.enabled) (s.section, s.half),
           ]);

  final List<HomeSectionState> sections;
  final List<HomeSectionPref> retained;
  final Set<HomeSection> newSections;

  /// The two-column memory, left → right; pairs are disjoint.
  final Map<HomeSection, HomeSection> widePairs;
  final Map<String, Object?>? retainedWide;
  final Map<String, Object?>? retainedDocument;

  static const String widePairsKey = 'pairs';

  HomeLayout copyWith({
    List<HomeSectionState>? sections,
    List<HomeSectionPref>? retained,
    Set<HomeSection>? newSections,
    Map<HomeSection, HomeSection>? widePairs,
    Object? retainedWide = _unset,
    Object? retainedDocument = _unset,
  }) => HomeLayout(
    sections ?? this.sections,
    retained: retained ?? this.retained,
    newSections: newSections ?? this.newSections,
    widePairs: widePairs ?? this.widePairs,
    retainedWide: identical(retainedWide, _unset) ? this.retainedWide : retainedWide as Map<String, Object?>?,
    retainedDocument: identical(retainedDocument, _unset)
        ? this.retainedDocument
        : retainedDocument as Map<String, Object?>?,
  );

  List<HomeSection> get enabledSections => [
    for (final s in sections)
      if (s.enabled) s.section,
  ];

  /// Disabled sections in layout order (the tray order).
  List<HomeSection> get hiddenSections => [
    for (final s in sections)
      if (!s.enabled) s.section,
  ];

  /// The catalog arrangement, every section at its default rows, the default memory.
  bool get isDefault => sameSectionsAs(defaultLayout);

  /// The large-screen default: what a never-customized profile shows in two columns.
  bool get isWideDefault => sameSectionsAs(wideDefault);

  /// Either default: a write of this clears the row back to "never customized".
  bool get isAnyDefault => isDefault || isWideDefault;

  /// What the feed shows at its width: in two columns a never-customized layout is [wideDefault].
  HomeLayout forTwoColumn(bool twoColumn) => twoColumn && isDefault ? wideDefault._carrying(this) : this;

  /// [forTwoColumn] both ways, for a draft that changes width: a default becomes the other width's default.
  HomeLayout shownAt(bool twoColumn) {
    if (twoColumn && isDefault) return wideDefault._carrying(this);
    if (!twoColumn && isWideDefault) return defaultLayout._carrying(this);
    return this;
  }

  HomeLayout _carrying(HomeLayout from) =>
      copyWith(retained: from.retained, newSections: from.newSections, retainedDocument: from.retainedDocument);

  /// The default this width resets to.
  bool isDefaultFor(bool twoColumn) => twoColumn ? isWideDefault : isDefault;

  /// The two-column memory made what two columns show now ([pairs]). Every two-column edit ends with it.
  HomeLayout rememberingShownPairs() {
    final shown = pairs;
    return homeSamePairs(shown, widePairs) ? this : copyWith(widePairs: shown);
  }

  /// The `half` flags set from the memory at this order. Order and visibility edits end with it.
  HomeLayout withRememberedPairs() {
    final shown = homeWidePairsAt(enabledSections, widePairs);
    final halves = {...shown.keys, ...shown.values};
    if (sections.every((s) => s.half == (s.enabled && halves.contains(s.section)))) return this;
    return copyWith(
      sections: [
        for (final s in sections)
          if (s.half == (s.enabled && halves.contains(s.section))) s else s.copyWith(half: !s.half),
      ],
    );
  }

  /// The pairs of the enabled order, left → right ([homeSpanPairs]).
  Map<HomeSection, HomeSection> get pairs => homeSpanPairs([
    for (final s in sections)
      if (s.enabled) (s.section, s.half),
  ]);

  /// [section]'s partner in its pair, either side; null when it is full width.
  HomeSection? partnerOf(HomeSection section) {
    final p = pairs;
    final right = p[section];
    if (right != null) return right;
    for (final e in p.entries) {
      if (e.value == section) return e.key;
    }
    return null;
  }

  /// Every half flag that isn't one of a pair cleared.
  HomeLayout normalizedSpans() {
    final paired = {
      for (final e in pairs.entries) ...[e.key, e.value],
    };
    if (!sections.any((s) => s.half && !paired.contains(s.section))) return this;
    return copyWith(
      sections: [for (final s in sections) s.half && !paired.contains(s.section) ? s.copyWith(half: false) : s],
    );
  }

  /// [section] (full width) becomes the left half of a pair with the row after it. `this` when it is
  /// already half, hidden, last, or can't share a height with the next.
  HomeLayout withHalf(HomeSection section) {
    final enabled = enabledSections;
    final index = enabled.indexOf(section);
    if (index < 0 || partnerOf(section) != null) return this;
    if (index + 1 >= enabled.length) return this;
    final next = enabled[index + 1];
    if (!homeCanPair(section, next)) return this;
    final nextPartner = pairs[next];
    return copyWith(
      sections: [
        for (final s in sections)
          if (s.section == section || s.section == next)
            s.copyWith(half: true)
          else if (s.section == nextPartner)
            s.copyWith(half: false)
          else
            s,
      ],
    ).normalizedSpans().rememberingShownPairs();
  }

  /// [section] (one of a pair) becomes full width, and so does its partner, which takes its own row right
  /// after it: a right half moves ahead of its old left partner. `this` when it is already full.
  HomeLayout withFull(HomeSection section) {
    final partner = partnerOf(section);
    if (partner == null) return this;
    final unpaired = copyWith(
      sections: [for (final s in sections) s.section == section || s.section == partner ? s.copyWith(half: false) : s],
    );
    final isRight = pairs[partner] == section;
    if (!isRight) return unpaired.rememberingShownPairs();
    final order = [...enabledSections];
    final left = order.indexOf(partner);
    order[left] = section;
    order[left + 1] = partner;
    return unpaired._withFullEnabledOrder(order).normalizedSpans().rememberingShownPairs();
  }

  /// Known sections in order (with their settings bags), then the [retained] ids.
  List<HomeSectionPref> toPrefs() => [
    for (final s in sections)
      HomeSectionPref(id: s.section.id, enabled: s.enabled, config: s.config(), extras: s.entryExtras),
    ...retained,
  ];

  /// The two-column memory as stored, [retainedWide] merged back in. Always written, empty included.
  Map<String, Object?> wideDocument() {
    final known = [
      for (final e in widePairs.entries) [e.key.id, e.value.id],
    ];
    final keptRaw = retainedWide?[widePairsKey];
    final kept = keptRaw is List ? keptRaw : const <Object?>[];
    return {
      ...?retainedWide,
      widePairsKey: [...known, ...kept],
    };
  }

  /// What the store writes: [toPrefs], [wideDocument] and [retainedDocument].
  HomeLayoutDocument toDocument() =>
      HomeLayoutDocument(sections: toPrefs(), wide: wideDocument(), extras: retainedDocument);

  /// [section] (full width) becomes the right half beside the enabled section before it, which must be
  /// full width too (screen readers' "Place beside previous").
  HomeLayout withBesidePrevious(HomeSection section) {
    final enabled = enabledSections;
    final index = enabled.indexOf(section);
    if (index < 1 || partnerOf(section) != null) return this;
    final previous = enabled[index - 1];
    if (partnerOf(previous) != null || !homeCanPair(previous, section)) return this;
    return copyWith(
      sections: [for (final s in sections) s.section == section || s.section == previous ? s.copyWith(half: true) : s],
    ).normalizedSpans().rememberingShownPairs();
  }

  /// A two-column drag's drop: the enabled [order] with exactly [pairs] as halves, every other section
  /// full. Sections in [unseen], and their stored partners, keep their flags. `this` when nothing changes.
  HomeLayout withArrangement(
    List<HomeSection> order,
    Map<HomeSection, HomeSection> pairs, {
    Set<HomeSection> unseen = const {},
  }) {
    final paired = {
      for (final e in pairs.entries)
        if (homeCanPair(e.key, e.value)) e.key: e.value,
    };
    final halves = {...paired.keys, ...paired.values};
    final keep = {...unseen, for (final u in unseen) ?partnerOf(u)};
    final spanned = copyWith(
      sections: [
        for (final s in sections)
          if (!s.enabled || keep.contains(s.section)) s else s.copyWith(half: halves.contains(s.section)),
      ],
    );
    final result = spanned._reorderedEnabled(order, unseen).normalizedSpans();
    return homeListEqual(result.sections, sections) ? this : result.rememberingShownPairs();
  }

  /// [section]'s own unit count in two columns; its default when it is not in this layout.
  int unitsOf(HomeSection section) {
    for (final s in sections) {
      if (s.section == section) return homeSectionUnits(s);
    }
    return section.defaultUnits;
  }

  /// The unit count [row] shows in two columns: a pair takes its left half's, held in both ranges.
  int unitsOfRow(HomeFeedRow row) {
    HomeSectionState? find(HomeSection s) {
      for (final state in sections) {
        if (state.section == s) return state;
      }
      return null;
    }

    switch (row) {
      case HomeFeedRowFull(:final section):
        final s = find(section);
        return s == null ? section.defaultUnits : homeSectionUnits(s);
      case HomeFeedRowHalves(:final left, :final right):
        final l = find(left);
        if (l == null) return left.defaultUnits;
        return homeRowUnits(l, find(right));
    }
  }

  /// [section]'s row at [units] in two columns: the section and its partner (in [pairs]), held in the
  /// overlap of both ranges, stored as the row preset (Lyric Cards: `units`). `this` when nothing changes.
  HomeLayout withUnits(HomeSection section, int units, {Map<HomeSection, HomeSection>? pairs}) {
    final p = pairs ?? this.pairs;
    var partner = p[section];
    if (partner == null) {
      for (final e in p.entries) {
        if (e.value == section) {
          partner = e.key;
          break;
        }
      }
    }
    final range = homeRowUnitRange(section, partner);
    final preset = homeUnitsPreset(range.clamp(units));
    final row = {section, ?partner};
    final count = preset.units;
    HomeSectionState withCount(HomeSectionState s) =>
        s.section.unitsOwnKey ? s.copyWith(units: count) : s.copyWith(rows: preset, rowsSet: true);
    if (!sections.any((s) => row.contains(s.section) && withCount(s) != s)) return this;
    return copyWith(sections: [for (final s in sections) row.contains(s.section) ? withCount(s) : s]);
  }

  /// Same order, visibility and rows (and settings bags), and the same two-column memory, sides aside.
  bool sameSectionsAs(HomeLayout other) =>
      homeListEqual(sections, other.sections) && homeSamePairs(widePairs, other.widePairs);

  /// The catalog arrangement (or, [twoColumn], [wideDefault]) keeping [retained] and [retainedDocument].
  HomeLayout reset({bool twoColumn = false}) {
    if (!twoColumn) {
      return isDefault ? this : defaultLayout.copyWith(retained: retained, retainedDocument: retainedDocument);
    }
    return isWideDefault ? this : wideDefault.copyWith(retained: retained, retainedDocument: retainedDocument);
  }

  /// [section]'s row preset ([HomeRowPreset.defaultPreset] when it is not in this layout).
  HomeRowPreset rowsOf(HomeSection section) {
    for (final s in sections) {
      if (s.section == section) return s.rows;
    }
    return HomeRowPreset.defaultPreset;
  }

  /// [section] at [rows]; `this` for a section without presets, absent, or already there.
  HomeLayout withRows(HomeSection section, HomeRowPreset rows) {
    if (!section.takesPresets || !sections.any((s) => s.section == section && s.rows != rows)) return this;
    return copyWith(
      sections: [
        for (final s in sections)
          // The phone's resize back to L clears it; two columns choose through [withUnits].
          s.section == section ? s.copyWith(rows: rows, rowsSet: rows != HomeRowPreset.defaultPreset) : s,
      ],
    );
  }

  HomeLayout withEnabled(HomeSection section, bool enabled) {
    if (!sections.any((s) => s.section == section && s.enabled != enabled)) return this;
    return copyWith(
      sections: [for (final s in sections) s.section == section ? s.copyWith(enabled: enabled) : s],
    ).withRememberedPairs();
  }

  /// Refill the enabled slots in [order]; disabled entries keep their absolute index. `this` when [order]
  /// is the current one or not a permutation of [enabledSections] (less [unseen] ones, which keep their place).
  HomeLayout withEnabledOrder(List<HomeSection> order, {Set<HomeSection> unseen = const {}}) {
    final reordered = _reorderedEnabled(order, unseen);
    return identical(reordered, this) ? this : reordered.withRememberedPairs();
  }

  HomeLayout _reorderedEnabled(List<HomeSection> order, Set<HomeSection> unseen) {
    final enabled = enabledSections;
    final distinct = order.toSet();
    if (distinct.length != order.length || !enabled.toSet().containsAll(distinct)) return this;
    final missing = [
      for (final s in enabled)
        if (!distinct.contains(s)) s,
    ];
    if (!unseen.containsAll(missing)) return this;
    final List<HomeSection> full;
    if (missing.isEmpty) {
      full = order;
    } else {
      if (distinct.isEmpty) return this;
      final next = order.iterator;
      full = [
        for (final s in enabled)
          if (distinct.contains(s)) (next..moveNext()).current else s,
      ];
    }
    if (homeListEqual(full, enabled)) return this;
    return _withFullEnabledOrder(full);
  }

  HomeLayout _withFullEnabledOrder(List<HomeSection> order) {
    final byState = {
      for (final s in sections)
        if (s.enabled) s.section: s,
    };
    final next = order.iterator;
    return copyWith(sections: [for (final s in sections) s.enabled ? byState[(next..moveNext()).current]! : s]);
  }

  /// Move [section] to [toEnabledIndex] in the enabled order (clamped).
  HomeLayout moved(HomeSection section, int toEnabledIndex) {
    final enabled = enabledSections;
    final from = enabled.indexOf(section);
    if (from < 0) return this;
    final to = toEnabledIndex.clamp(0, enabled.length - 1);
    if (to == from) return this;
    return withEnabledOrder(
      [...enabled]
        ..removeAt(from)
        ..insert(to, section),
    );
  }

  /// The large-screen default (owner 2026-11-16): Activities | Jump Back In, All Memories, Made For You,
  /// Lyric Cards | Recently Added, Recently Played, Your Playlists | Rediscover, then the other accounts.
  static final HomeLayout wideDefault = () {
    const halves = {
      HomeSection.activities,
      HomeSection.jumpBackIn,
      HomeSection.lyricHighlight,
      HomeSection.recentlyAdded,
      HomeSection.yourPlaylists,
      HomeSection.rediscover,
    };
    const order = [
      HomeSection.activities,
      HomeSection.jumpBackIn,
      HomeSection.memoryGrid,
      HomeSection.madeForYou,
      HomeSection.lyricHighlight,
      HomeSection.recentlyAdded,
      HomeSection.recentlyPlayed,
      HomeSection.yourPlaylists,
      HomeSection.rediscover,
      HomeSection.otherProfiles,
    ];
    final ordered = [
      ...order,
      for (final s in HomeSection.values)
        if (!order.contains(s)) s,
    ];
    return HomeLayout([for (final s in ordered) HomeSectionState(s, s.defaultEnabled, half: halves.contains(s))]);
  }();

  /// Catalog defaults: every section in enum order. Its two-column memory is [wideDefault]'s pairs, so a
  /// layout first edited in one column shows them in two wherever its order allows.
  static final HomeLayout defaultLayout = HomeLayout([
    for (final s in HomeSection.values) HomeSectionState(s, s.defaultEnabled),
  ], widePairs: wideDefault.widePairs).withRememberedPairs();

  /// [reconcile] for a stored document (null = never customized), its kept keys carried along.
  static HomeLayout fromDocument(HomeLayoutDocument? document) {
    final read = reconcile(document?.sections, wide: document?.wide);
    final extras = document?.extras;
    if (extras == null) return read;
    return read.copyWith(retainedDocument: extras);
  }

  /// Merge a persisted layout with the current catalog: saved order, flags and settings for known ids
  /// (first occurrence wins); retired ids dropped, other unknown ids retained (first occurrence, in order);
  /// catalog sections the save never knew appended at [HomeSection.appendEnabled] (the hidden ones become
  /// [newSections]). Null / empty → [defaultLayout]. The memory comes from [wide]; without it, the pairs
  /// the `half` flags show, or [wideDefault]'s when they show none. A [wideDefault] pair for an appended
  /// section joins it when both are free. The flags are then set from the memory.
  static HomeLayout reconcile(List<HomeSectionPref>? prefs, {Map<String, Object?>? wide}) {
    if (prefs == null || prefs.isEmpty) return defaultLayout;
    final seen = <HomeSection>{};
    final retainedIds = <String>{};
    final ordered = <HomeSectionState>[];
    final retained = <HomeSectionPref>[];
    for (final pref in prefs) {
      final section = HomeSection.fromId(pref.id);
      if (section != null) {
        if (seen.add(section)) ordered.add(HomeSectionState.fromPref(section, pref));
      } else if (pref.id.trim().isEmpty || HomeSection.retiredIds.contains(pref.id)) {
        // dropped
      } else if (retainedIds.add(pref.id)) {
        retained.add(pref);
      }
    }
    final appended = [
      for (final s in HomeSection.values)
        if (seen.add(s)) s,
    ];
    ordered.addAll([for (final s in appended) HomeSectionState(s, s.appendEnabled)]);
    final read = HomeLayout(
      ordered,
      retained: retained,
      newSections: {
        for (final s in appended)
          if (!s.appendEnabled) s,
      },
    );
    final stored = wide == null ? null : _readWide(wide);
    final readPairs = read.pairs;
    final base = stored != null ? stored.$1 : (readPairs.isEmpty ? wideDefault.widePairs : readPairs);
    final memory = <HomeSection, HomeSection>{...base};
    final used = {...memory.keys, ...memory.values};
    for (final e in wideDefault.widePairs.entries) {
      final left = e.key, right = e.value;
      if ((appended.contains(left) || appended.contains(right)) && !used.contains(left) && !used.contains(right)) {
        memory[left] = right;
        used
          ..add(left)
          ..add(right);
      }
    }
    return read.copyWith(widePairs: memory, retainedWide: stored?.$2).withRememberedPairs();
  }

  /// A stored memory: its known pairs (the first one a section is in wins), and what this build keeps
  /// without reading (pairs naming an unknown id, other keys). Malformed pairs and pairs naming a retired
  /// section drop.
  static (Map<HomeSection, HomeSection>, Map<String, Object?>?) _readWide(Map<String, Object?> wide) {
    final memory = <HomeSection, HomeSection>{};
    final kept = <Object?>[];
    final raw = wide[widePairsKey];
    for (final entry in raw is List ? raw : const <Object?>[]) {
      if (entry is! List) continue;
      final ids = [for (final p in entry) p is String ? p : null];
      if (ids.length != 2 || ids.any((id) => id == null || id.trim().isEmpty) || ids[0] == ids[1]) continue;
      final left = HomeSection.fromId(ids[0]!);
      final right = HomeSection.fromId(ids[1]!);
      final used = {...memory.keys, ...memory.values};
      if (left != null && right != null) {
        if (!used.contains(left) && !used.contains(right)) memory[left] = right;
      } else if (ids.any((id) => HomeSection.retiredIds.contains(id))) {
        // dropped
      } else {
        kept.add(entry);
      }
    }
    final rest = <String, Object?>{
      for (final e in wide.entries)
        if (e.key != widePairsKey) e.key: e.value,
    };
    if (kept.isNotEmpty) rest[widePairsKey] = kept;
    return (memory, rest.isEmpty ? null : rest);
  }

  @override
  String toString() => 'HomeLayout($sections, pairs: ${{for (final e in widePairs.entries) e.key.id: e.value.id}})';
}

/// [state]'s own unit count in two columns: its chosen row preset as units, or its default when it never
/// chose one, held in its range.
int homeSectionUnits(HomeSectionState state) {
  final section = state.section;
  final stored = section.unitsOwnKey
      ? (state.units ?? section.defaultUnits)
      : (state.rowsSet ? state.rows.units : section.defaultUnits);
  return section.unitRange.clamp(stored);
}

/// The count a row of [left] (and [right], when paired) shows: the left one's, held in both ranges.
int homeRowUnits(HomeSectionState left, HomeSectionState? right) {
  final own = homeSectionUnits(left);
  if (right == null) return own;
  return left.section.unitRange.intersect(right.section.unitRange).clamp(own);
}

// ── The edit session (HomeEditRules.kt) ──────────────────────────────────

/// The draft after the feed changed width in edit mode: untouched (no Undo step yet) it is what entering
/// at that width would have made it, from [base]; edited, it keeps its order and two columns pair it from
/// its memory ([HomeLayout.shownAt]). Pure.
HomeLayout homeDraftAtWidth(HomeLayout draft, bool edited, HomeLayout base, bool twoColumn) =>
    !edited ? base.forTwoColumn(twoColumn) : draft.shownAt(twoColumn);

/// One edit's result: null when [changed] is [current] itself (no Undo step, no write); else [changed],
/// and in [twoColumn] with its memory made what two columns show. Pure.
HomeLayout? homeEditStep(HomeLayout current, HomeLayout changed, bool twoColumn) {
  if (identical(changed, current)) return null;
  return twoColumn ? changed.rememberingShownPairs() : changed;
}

/// The stored layout [stored] stands for the layout last [shown] in edit mode (the echo of its write has
/// arrived): the same arrangement, or the stored "never customized" while [shown] is a width's default. Pure.
bool homeLayoutEchoes(HomeLayout stored, HomeLayout shown) =>
    stored.sameSectionsAs(shown) || (stored.isDefault && shown.isAnyDefault);

/// What persisting a layout does to the stored row.
sealed class HomePersistAction {
  const HomePersistAction();
}

/// Delete the row: back to "never customized", so new sections follow their defaults again.
final class HomePersistClear extends HomePersistAction {
  const HomePersistClear();
}

/// Write [document] (skipped by the store when the row already holds it).
final class HomePersistWrite extends HomePersistAction {
  const HomePersistWrite(this.document);

  final HomeLayoutDocument document;
}

/// How [layout] is persisted: either default with no retained ids clears the row (the document's kept
/// keys go with it, as Reset clears everything on every platform); anything else writes its document. Pure.
HomePersistAction homePersistAction(HomeLayout layout) =>
    layout.isAnyDefault && layout.retained.isEmpty ? const HomePersistClear() : HomePersistWrite(layout.toDocument());
