import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/home/home_section.dart';

/// Home's layout on HarmonyOS, on the shared core (the core itself is replayed against home-layout.json in
/// test/data/home/home_layout_golden_test.dart): the v1 helpers, the stored document, the stale `span` fix
/// and the persist rule.
void main() {
  String encode(HomeLayout layout) => layout.encode();
  Map<String, Object?> doc(HomeLayout layout) => jsonDecode(layout.encode()) as Map<String, Object?>;
  Map<String, Object?>? configOf(HomeLayout layout, String id) =>
      ((doc(layout)['sections']! as List).cast<Map<String, Object?>>().firstWhere((s) => s['id'] == id))['config']
          as Map<String, Object?>?;

  test('should_readTheDefault_when_nothingOrSomethingUnreadableIsStored', () {
    expect(decodeHomeLayout(null).isDefault, isTrue);
    expect(decodeHomeLayout('not json').isDefault, isTrue);
    expect(HomeLayout.reconcile(null), same(HomeLayout.defaultLayout));
  });

  test('should_keepEveryCatalogueSectionInOrder_when_default', () {
    expect(HomeLayout.defaultLayout.sections.map((s) => s.id), [
      'activities',
      'jump_back_in',
      'recently_added',
      'rediscover',
      'recently_played',
      'your_playlists',
      'other_profiles',
      'lyric_highlight',
      'memory_grid',
      'made_for_you',
    ]);
    expect(HomeLayout.defaultLayout.visibleV1Sections, HomeSection.values);
  });

  test('should_keepSavedOrderDropRetiredKeepUnknownAppendMissing_when_reconciling', () {
    final layout = HomeLayout.reconcile(const [
      HomeSectionPref(id: 'your_playlists', enabled: true),
      HomeSectionPref(id: 'memories', enabled: true),
      HomeSectionPref(id: 'future_thing', enabled: true),
      HomeSectionPref(id: 'activities', enabled: false, config: {'rows': 'xl'}),
      HomeSectionPref(id: 'your_playlists', enabled: false),
    ]);
    expect(layout.sections.take(2).map((s) => s.id), ['your_playlists', 'activities']);
    expect(layout.retained.map((p) => p.id), ['future_thing']);
    expect(layout.sections.firstWhere((s) => s.id == 'rediscover').enabled, isFalse);
    expect(layout.newSections, {HomeSection.rediscover});
    expect(layout.rowsOf(HomeSection.activities), HomeRowPreset.xl);
    expect(layout.visibleV1Sections.first, HomeSection.yourPlaylists);
    expect(layout.visibleV1Sections, isNot(contains(HomeSection.activities)));
    // Retained ids are written back after the known sections.
    expect((doc(layout)['sections']! as List).last, {'id': 'future_thing', 'enabled': true});
  });

  test('should_roundTripTheDocument_when_persisted', () {
    final layout = withSectionRows(HomeLayout.defaultLayout, HomeSection.jumpBackIn, HomeRowPreset.xl);
    final back = decodeHomeLayout(encode(layout));
    expect(back.sameSectionsAs(layout), isTrue);
    // The two-column memory is always written, empty or not.
    expect(doc(HomeLayout.defaultLayout).keys, ['version', 'sections', 'wide']);
  });

  group('row presets', () {
    test('should_writeRowsAndDropTheDefault_when_settingPresets', () {
      final xl = withSectionRows(HomeLayout.defaultLayout, HomeSection.jumpBackIn, HomeRowPreset.xl);
      expect(xl.rowsOf(HomeSection.jumpBackIn), HomeRowPreset.xl);
      expect(configOf(xl, 'jump_back_in'), {'rows': 'xl', 'span': 'half'});
      expect(xl.isDefault, isFalse);
      final back = withSectionRows(xl, HomeSection.jumpBackIn, HomeRowPreset.l);
      expect(configOf(back, 'jump_back_in'), {'span': 'half'});
      expect(back.isDefault, isTrue);
    });

    test('should_returnTheSameLayout_when_nothingChanges', () {
      final layout = HomeLayout.defaultLayout;
      expect(identical(withSectionRows(layout, HomeSection.activities, HomeRowPreset.l), layout), isTrue);
      expect(identical(withSectionRows(layout, HomeSection.recentlyAdded, HomeRowPreset.s), layout), isTrue);
      expect(identical(withSectionEnabled(layout, HomeSection.activities, true), layout), isTrue);
      expect(identical(withSectionMoved(layout, layout.visibleV1Sections, HomeSection.activities, 0), layout), isTrue);
      expect(HomeSection.lyricHighlight.takesPresets, isTrue);
      expect(HomeSection.recentlyAdded.takesPresets, isFalse);
    });

    test('should_keepOtherSettingsVerbatim_when_rowsChange', () {
      final layout = decodeHomeLayout(
        '{"version":1,"sections":[{"id":"activities","enabled":true,'
        '"config":{"rows":"zz","density":3,"future":{"a":[1,2]}}}]}',
      );
      // An unreadable preset reads as L and rides along until it changes.
      expect(layout.rowsOf(HomeSection.activities), HomeRowPreset.l);
      expect(identical(withSectionRows(layout, HomeSection.activities, HomeRowPreset.l), layout), isTrue);
      final m = withSectionRows(layout, HomeSection.activities, HomeRowPreset.m);
      expect(configOf(m, 'activities'), {
        'density': 3,
        'future': {
          'a': [1, 2],
        },
        'rows': 'm',
        'span': 'half',
      });
      expect(doc(decodeHomeLayout(encode(m))), doc(m));
    });

    test('should_keepUnknownIds_when_resetting', () {
      final layout = HomeLayout.reconcile(const [
        HomeSectionPref(id: 'future_thing', enabled: true),
        HomeSectionPref(id: 'activities', enabled: false, config: {'rows': 's'}),
      ]);
      final reset = resetLayout(layout);
      expect(reset.rowsOf(HomeSection.activities), HomeRowPreset.l);
      expect(reset.visibleV1Sections, HomeLayout.defaultLayout.visibleV1Sections);
      expect(reset.retained.single.id, 'future_thing');
      expect(identical(resetLayout(HomeLayout.defaultLayout), HomeLayout.defaultLayout), isTrue);
    });

    test('should_readKnownPresetIds_when_parsing', () {
      expect(HomeRowPreset.fromId('xl'), HomeRowPreset.xl);
      expect(HomeRowPreset.fromId('xxl'), isNull);
      expect(HomeRowPreset.byId(null), HomeRowPreset.l);
    });
  });

  group('the two-column flags (parity p4 C0)', () {
    // An Android document (HomeLayoutStore): the two-column memory under `wide`,
    // half spans in the configs, plus keys a newer build might add.
    const android = '''
{
  "version": 1,
  "sections": [
    {"id": "activities", "enabled": true, "config": {"span": "half", "units": 3}},
    {"id": "jump_back_in", "enabled": true, "config": {"span": "half"}, "pinned": [1, 2]},
    {"id": "recently_added", "enabled": true},
    {"id": "rediscover", "enabled": false},
    {"id": "recently_played", "enabled": true},
    {"id": "your_playlists", "enabled": true},
    {"id": "other_profiles", "enabled": true},
    {"id": "lyric_highlight", "enabled": true},
    {"id": "memory_grid", "enabled": true},
    {"id": "made_for_you", "enabled": true}
  ],
  "wide": {"pairs": [["activities", "jump_back_in"], ["future_a", "future_b"]], "note": "x"},
  "future": {"a": [1, {"b": null}]}
}
''';
    Map<String, Object?> source() => jsonDecode(android) as Map<String, Object?>;

    test('should_writeWideAndOtherKeysBack_when_reorderedAndHidden', () {
      final layout = decodeHomeLayout(android);
      final moved = withSectionMoved(layout, layout.visibleV1Sections, HomeSection.recentlyPlayed, 0);
      final hidden = withSectionEnabled(moved, HomeSection.yourPlaylists, false);
      final resized = withSectionRows(hidden, HomeSection.jumpBackIn, HomeRowPreset.m);
      final out = doc(resized);
      expect(out['version'], 1);
      expect(out['wide'], source()['wide']);
      expect(out['future'], source()['future']);
      final sections = (out['sections']! as List).cast<Map<String, Object?>>();
      expect(sections.first['id'], 'recently_played');
      expect(sections.firstWhere((s) => s['id'] == 'your_playlists')['enabled'], isFalse);
      final jbi = sections.firstWhere((s) => s['id'] == 'jump_back_in');
      expect(jbi['pinned'], [1, 2]);
      // Still next to each other: still a pair.
      expect(jbi['config'], {'rows': 'm', 'span': 'half'});
      expect(sections.firstWhere((s) => s['id'] == 'activities')['config'], {'span': 'half', 'units': 3});
    });

    test('should_dropTheStaleSpan_when_aPairIsSplitByAMove', () {
      final layout = decodeHomeLayout(android);
      final split = withSectionMoved(layout, layout.visibleV1Sections, HomeSection.jumpBackIn, 5);
      // Apart, both are full width again: no `"span":"half"` an older build would pair two by two.
      expect(configOf(split, 'activities'), {'units': 3});
      expect(configOf(split, 'jump_back_in'), isNull);
      // The memory still holds them: back together, they pair again.
      expect(doc(split)['wide'], source()['wide']);
      final together = withSectionMoved(split, split.visibleV1Sections, HomeSection.jumpBackIn, 1);
      expect(configOf(together, 'jump_back_in'), {'span': 'half'});
    });

    test('should_dropTheStaleSpan_when_halfAPairIsHiddenOrTheOrderIsCommitted', () {
      final layout = decodeHomeLayout(android);
      final hidden = withSectionEnabled(layout, HomeSection.jumpBackIn, false);
      expect(configOf(hidden, 'activities'), {'units': 3});
      expect(configOf(hidden, 'jump_back_in'), isNull);
      final order = [...layout.visibleV1Sections]..remove(HomeSection.activities);
      final committed = withSectionsOrdered(layout, layout.visibleV1Sections, [...order, HomeSection.activities]);
      expect(configOf(committed, 'activities'), {'units': 3});
      expect(configOf(committed, 'jump_back_in'), isNull);
      // A reversed pair still pairs, the one first in the order on the left.
      final reversed = withSectionMoved(layout, layout.visibleV1Sections, HomeSection.jumpBackIn, 0);
      expect(configOf(reversed, 'jump_back_in'), {'span': 'half'});
      expect(configOf(reversed, 'activities'), {'span': 'half', 'units': 3});
    });

    test('should_forgetTheMemory_when_reset', () {
      final layout = decodeHomeLayout(android);
      final reset = resetLayout(layout);
      // Back to the default memory (the large-screen default's pairs); the document's own keys stay.
      expect(doc(reset)['wide'], {
        'pairs': [
          ['activities', 'jump_back_in'],
          ['lyric_highlight', 'recently_added'],
          ['your_playlists', 'rediscover'],
        ],
      });
      expect(doc(reset)['future'], source()['future']);
    });
  });

  group('the persist rule', () {
    test('should_clearTheRow_when_theLayoutIsEitherDefault', () {
      expect(homePersistAction(HomeLayout.defaultLayout), isA<HomePersistClear>());
      expect(homePersistAction(HomeLayout.wideDefault), isA<HomePersistClear>());
      final hidden = withSectionEnabled(HomeLayout.defaultLayout, HomeSection.jumpBackIn, false);
      expect(homePersistAction(hidden), isA<HomePersistWrite>());
      expect(homePersistAction(withSectionEnabled(hidden, HomeSection.jumpBackIn, true)), isA<HomePersistClear>());
    });

    test('should_write_when_aDefaultKeepsIdsThisBuildDoesntKnow', () {
      final layout = HomeLayout.reconcile([
        for (final s in HomeSection.values) HomeSectionPref(id: s.id, enabled: true),
        const HomeSectionPref(id: 'future_thing', enabled: true),
      ]);
      expect(layout.isDefault, isTrue);
      expect(homePersistAction(layout), isA<HomePersistWrite>());
    });
  });
}
