import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/home/home_catalog.dart';
import '../data/home/home_layout_codec.dart';
import '../data/home/home_layout_core.dart';

export '../data/home/home_catalog.dart';
export '../data/home/home_layout_codec.dart' show HomeLayoutCodec, HomeLayoutDocument, HomeSectionPref;
export '../data/home/home_layout_core.dart';

/// The layout a stored document reads as (Android HomeLayoutStore → HomeLayout.fromDocument): null or an
/// unreadable text is "never customized", the phone default.
HomeLayout decodeHomeLayout(String? raw) => HomeLayout.fromDocument(raw == null ? null : HomeLayoutCodec.decode(raw));

/// Home's layout on HarmonyOS (the shared core, home_layout_core.dart).
extension HomeLayoutHarmony on HomeLayout {
  /// Sections the v1 feed draws, in layout order (enabled and built).
  List<HomeSection> get visibleV1Sections => [
    for (final s in sections)
      if (s.enabled && s.section.availableInV1) s.section,
  ];

  /// Enabled sections the feed never draws here: an edit of the drawn order leaves them in their place.
  Set<HomeSection> get unseenV1Sections => {
    for (final s in sections)
      if (s.enabled && !s.section.availableInV1) s.section,
  };

  /// The stored document's text, every kept key written back.
  String encode() => HomeLayoutCodec.encode(toDocument());
}

/// [layout] with [section] hidden or shown again (in its own place), its pairs set from the memory at the
/// new order (HomeLayout.withEnabled); [layout] itself when nothing changes.
HomeLayout withSectionEnabled(HomeLayout layout, HomeSection section, bool enabled) =>
    layout.withEnabled(section, enabled);

/// [layout] with [section] at row preset [rows] (HomeLayout.withRows): [layout] itself for a section without
/// presets, one not in it, or one already there.
HomeLayout withSectionRows(HomeLayout layout, HomeSection section, HomeRowPreset rows) =>
    layout.withRows(section, rows);

/// The catalogue arrangement — rows and the two-column memory back to their defaults too — keeping the ids
/// this build doesn't know (HomeLayout.reset); [layout] itself when it already is.
HomeLayout resetLayout(HomeLayout layout) => layout.reset();

/// [layout] with [section] moved to [to] among [visible] (the sections the feed draws, in order): the
/// enabled order refilled around the ones it never draws, each section's settings with it, and the `half`
/// flags set from the two-column memory at the new order (HomeLayout.withEnabledOrder → withRememberedPairs,
/// so no stale `"span":"half"` is written).
HomeLayout withSectionMoved(HomeLayout layout, List<HomeSection> visible, HomeSection section, int to) {
  final from = visible.indexOf(section);
  if (from < 0 || to < 0 || to >= visible.length || from == to) return layout;
  final order = [...visible]
    ..removeAt(from)
    ..insert(to, section);
  return withSectionsOrdered(layout, visible, order);
}

/// [layout] with the [visible] sections refilled in [order] (a permutation of them); every other section
/// keeps its slot, and the pairs follow the memory (HomeLayout.withEnabledOrder). [layout] itself when
/// [order] is not a permutation of [visible] or changes nothing.
HomeLayout withSectionsOrdered(HomeLayout layout, List<HomeSection> visible, List<HomeSection> order) {
  if (order.length != visible.length || !order.toSet().containsAll(visible)) return layout;
  final unseen = {
    for (final s in layout.enabledSections)
      if (!visible.contains(s)) s,
  };
  return layout.withEnabledOrder(order, unseen: unseen);
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
        state = decodeHomeLayout(raw);
      } catch (_) {
        // Unreadable: the default stands.
      }
    });
    return HomeLayout.defaultLayout;
  }

  String? _updatedAtKey;

  /// Shows [layout] and saves it for the active account by the persist rule (Android HomeViewModel,
  /// [homePersistAction]): a default with no ids this build doesn't know clears the row ("never
  /// customized", so new sections follow their defaults and sync takes every device back to the default);
  /// anything else writes its document.
  void set(HomeLayout layout) {
    state = layout;
    final key = _key;
    final updatedAtKey = _updatedAtKey;
    if (key == null || updatedAtKey == null) return;
    final store = ref.read(keyValueStoreProvider);
    switch (homePersistAction(layout)) {
      case HomePersistClear():
        store.write(key, null).then((_) => store.write(updatedAtKey, null)).catchError((Object _) {});
      case HomePersistWrite(:final document):
        store
            .write(key, HomeLayoutCodec.encode(document))
            .then((_) => store.write(updatedAtKey, '${DateTime.now().millisecondsSinceEpoch}'))
            .catchError((Object _) {});
    }
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
