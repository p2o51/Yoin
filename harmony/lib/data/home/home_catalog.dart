/// Home's section catalog, the two-column unit ranges and the feed's rows (docs/design.md §主页 › 就地编辑;
/// Android PR #250 / #292), a Dart port of `:shared` `com.gpo.yoin.shared.home` (HomeCatalog.kt and the
/// pure helpers at the end of HomeLayout.kt) for parity phase 4. The ids are persisted verbatim in every
/// platform's layout document and synced as is: never rename one. Every answer here is replayed against
/// `shared/src/commonTest/resources/golden/home-layout.json` (test/data/home/home_layout_golden_test.dart).
library;

/// The catalog of home-screen sections a user can show / hide / reorder
/// (`HomeSection`). [id]s are persisted: never rename one. Enum order is the
/// default (phone) order. [appendEnabled] is the visibility of a section
/// appended to an already-customized layout ("New" in the tray when false).
enum HomeSection {
  activities('activities', supportsRows: true, defaultUnits: 2, unitRange: HomeUnitRange(1, 3)),
  jumpBackIn('jump_back_in', supportsRows: true, defaultUnits: 2, unitRange: HomeUnitRange(1, 4)),
  recentlyAdded('recently_added', defaultUnits: 2),
  rediscover('rediscover', appendEnabled: false),
  recentlyPlayed('recently_played'),
  yourPlaylists('your_playlists'),
  otherProfiles('other_profiles'),
  lyricHighlight('lyric_highlight', takesPresets: true, unitsOwnKey: true, defaultUnits: 2),
  memoryGrid('memory_grid', unitRange: HomeUnitRange(1, 3)),

  // Android main (owner 2026-11-15,「为你推荐」): the playlists the service
  // makes for the account — Apple Music's personal mixes here
  // (home_made_for_you.dart). Hidden for services that have none, so on by
  // default and for customized layouts too.
  madeForYou('made_for_you');

  const HomeSection(
    this.id, {
    this.supportsRows = false,
    this.appendEnabled = true,
    bool takesPresets = false,
    this.unitsOwnKey = false,
    this.defaultUnits = 1,
    this.unitRange = const HomeUnitRange(1, 2),
  }) : _takesPresets = takesPresets;

  final String id;

  /// Takes a row preset (D1): Activities and Jump Back In.
  final bool supportsRows;

  /// Enabled when added to a saved layout that never knew it.
  final bool appendEnabled;
  final bool _takesPresets;

  /// The section keeps its two-column unit count apart from its row preset (Lyric Cards: its preset is
  /// its card count), stored as `"units"`.
  final bool unitsOwnKey;

  /// The count a section with nothing stored takes in two columns.
  final int defaultUnits;

  /// The unit counts this section can take in two columns.
  final HomeUnitRange unitRange;

  bool get defaultEnabled => true;

  /// Stores a [HomeRowPreset]: the [supportsRows] sections, plus Lyric Cards (its card count).
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
    memoryGrid ||
    madeForYou => true,
    // On every device (Android owner 2026-11-08): without Gemini Nano it shows the user's own picks.
    lyricHighlight => true,
  };

  static final Map<String, HomeSection> _byId = {for (final s in values) s.id: s};

  /// The section stored under [id]; null for an id this build doesn't know.
  static HomeSection? byId(String id) => _byId[id];

  /// [byId] (Kotlin's name).
  static HomeSection? fromId(String id) => _byId[id];

  /// Ids of sections that were removed from the catalog: dropped on read, never retained.
  static const Set<String> retiredIds = {'memory_teaser', 'memories'};
}

/// How many rows a section shows (HomeRowPreset, D1). Presets, not numbers:
/// each screen turns one into its own composition (home_row_presets.dart), so
/// the choice travels with the account across screens. [l] is the
/// composition from before presets existed — a section nobody resized stores
/// nothing and renders as it did. Two columns read S / M / L / XL as 1–4
/// grid units. Declaration order is the size order.
enum HomeRowPreset {
  s('s'),
  m('m'),
  l('l'),
  xl('xl');

  const HomeRowPreset(this.id);

  /// The stored key (`s` / `m` / `l` / `xl`).
  final String id;

  /// [id] (Kotlin's name).
  String get key => id;

  /// A stored row preset as units: S 1, M 2, L 3, XL 4.
  int get units => index + 1;

  static const HomeRowPreset defaultPreset = HomeRowPreset.l;

  /// The preset stored under [id]; null for an id this build doesn't know.
  static HomeRowPreset? fromId(String? id) {
    for (final p in values) {
      if (p.id == id) return p;
    }
    return null;
  }

  /// [fromId] (Kotlin's name).
  static HomeRowPreset? fromKey(String? key) => fromId(key);

  /// [fromId], or [defaultPreset] for a missing or unknown id.
  static HomeRowPreset byId(String? id) => fromId(id) ?? defaultPreset;
}

/// [units] as the preset that stores it (counts past 4 store as XL).
HomeRowPreset homeUnitsPreset(int units) {
  if (units <= 1) return HomeRowPreset.s;
  if (units == 2) return HomeRowPreset.m;
  if (units == 3) return HomeRowPreset.l;
  return HomeRowPreset.xl;
}

/// An inclusive range of unit counts (Kotlin's `IntRange` here).
final class HomeUnitRange {
  const HomeUnitRange(this.first, this.last);

  final int first;
  final int last;

  bool contains(int n) => n >= first && n <= last;

  /// [n] held in the range (`coerceIn`).
  int clamp(int n) => n < first ? first : (n > last ? last : n);

  /// The two ranges' overlap; this range's lower end alone when they don't meet.
  HomeUnitRange intersect(HomeUnitRange other) {
    final lo = first > other.first ? first : other.first;
    final hi = last < other.last ? last : other.last;
    return lo <= hi ? HomeUnitRange(lo, hi) : HomeUnitRange(lo, lo);
  }

  /// The two ranges share a count: a pair of sections with them can be one row height.
  bool meets(HomeUnitRange other) => (first > other.first ? first : other.first) <= (last < other.last ? last : other.last);

  Iterable<int> get values sync* {
    for (var n = first; n <= last; n++) {
      yield n;
    }
  }

  @override
  bool operator ==(Object other) => other is HomeUnitRange && other.first == first && other.last == last;

  @override
  int get hashCode => Object.hash(first, last);

  @override
  String toString() => '$first..$last';
}

/// [left] and [right] can be the two halves of a row (owner 2026-11-23: 「左右两边高度一致」): a pair
/// shares one unit count, at most the smaller of the two maxima, so their ranges must meet.
bool homeCanPair(HomeSection left, HomeSection right) => left.unitRange.meets(right.unitRange);

/// The range a row's handle can drag over: the section's own, or the overlap with its partner.
HomeUnitRange homeRowUnitRange(HomeSection left, HomeSection? right) =>
    right == null ? left.unitRange : left.unitRange.intersect(right.unitRange);

// ── Pairs and rows (pure helpers of HomeLayout.kt) ──────────────────────

/// Two pair maps hold exactly the same entries (Kotlin `Map.equals`: order aside).
bool homePairsEqual(Map<HomeSection, HomeSection> a, Map<HomeSection, HomeSection> b) {
  if (identical(a, b)) return true;
  if (a.length != b.length) return false;
  for (final e in a.entries) {
    if (b[e.key] != e.value) return false;
  }
  return true;
}

/// Two lists hold the same items in the same order.
bool homeListEqual<T>(List<T> a, List<T> b) {
  if (identical(a, b)) return true;
  if (a.length != b.length) return false;
  for (var i = 0; i < a.length; i++) {
    if (a[i] != b[i]) return false;
  }
  return true;
}

/// The remembered pairs ([memory], disjoint) that show at [order] (the enabled
/// order), left → right as the order has them: the two right next to each other,
/// both on, and able to share a row height ([homeCanPair]). A pair the order turned
/// around still shows, swapped: the one first in the order on the left (owner
/// 2026-11-23). A pair split up or with one side hidden shows as two full rows and
/// stays remembered. Pure.
Map<HomeSection, HomeSection> homeWidePairsAt(
  List<HomeSection> order,
  Map<HomeSection, HomeSection> memory, {
  bool Function(HomeSection, HomeSection) canPair = homeCanPair,
}) {
  final shown = <HomeSection, HomeSection>{};
  for (var i = 0; i < order.length - 1; i++) {
    final left = order[i];
    final right = order[i + 1];
    final remembered = memory[left] == right || memory[right] == left;
    if (remembered && canPair(left, right)) shown[left] = right;
  }
  return shown;
}

/// [a] and [b] hold the same pairs, sides aside. Pure.
bool homeSamePairs(Map<HomeSection, HomeSection> a, Map<HomeSection, HomeSection> b) =>
    a.length == b.length && a.entries.every((e) => b[e.key] == e.value || b[e.value] == e.key);

/// The pairs of an enabled order given as (section, half): halves pair two by
/// two in order, a full section in between breaks the run, and a half left
/// over pairs with nothing. Left → right. Pure.
Map<HomeSection, HomeSection> homeSpanPairs(Iterable<(HomeSection, bool)> order) {
  final pairs = <HomeSection, HomeSection>{};
  HomeSection? open;
  for (final (section, half) in order) {
    if (!half) {
      open = null;
    } else if (open == null) {
      open = section;
    } else {
      pairs[open] = section;
      open = null;
    }
  }
  return pairs;
}

/// One row of the feed: a full-width section, or two halves side by side.
sealed class HomeFeedRow {
  const HomeFeedRow();

  /// The row's first (left) section: its key.
  HomeSection get first;

  /// The row's sections, left to right.
  List<HomeSection> get sections;
}

final class HomeFeedRowFull extends HomeFeedRow {
  const HomeFeedRowFull(this.section);

  final HomeSection section;

  @override
  HomeSection get first => section;

  @override
  List<HomeSection> get sections => [section];

  @override
  bool operator ==(Object other) => other is HomeFeedRowFull && other.section == section;

  @override
  int get hashCode => section.hashCode;

  @override
  String toString() => 'Full(${section.id})';
}

final class HomeFeedRowHalves extends HomeFeedRow {
  const HomeFeedRowHalves(this.left, this.right);

  final HomeSection left;
  final HomeSection right;

  @override
  HomeSection get first => left;

  @override
  List<HomeSection> get sections => [left, right];

  @override
  bool operator ==(Object other) => other is HomeFeedRowHalves && other.left == left && other.right == right;

  @override
  int get hashCode => Object.hash(left, right);

  @override
  String toString() => 'Halves(${left.id}, ${right.id})';
}

/// The feed's rows for the sections it [displayed], in order. In [twoColumn] a
/// left half whose partner ([pairs], left → right) is displayed right after it
/// shares its row; a half whose partner isn't shown is full width until it is
/// back. One column: every section is its own row. Pure.
List<HomeFeedRow> homeFeedRows(List<HomeSection> displayed, Map<HomeSection, HomeSection> pairs, bool twoColumn) {
  if (!twoColumn) return [for (final s in displayed) HomeFeedRowFull(s)];
  final rows = <HomeFeedRow>[];
  var i = 0;
  while (i < displayed.length) {
    final section = displayed[i];
    final right = pairs[section];
    if (right != null && i + 1 < displayed.length && displayed[i + 1] == right) {
      rows.add(HomeFeedRowHalves(section, right));
      i += 2;
    } else {
      rows.add(HomeFeedRowFull(section));
      i++;
    }
  }
  return rows;
}
