import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/home/home_section.dart';

void main() {
  test('should_returnDefault_when_nothingSaved', () {
    expect(HomeLayout.reconcile(null), same(HomeLayout.defaultLayout));
    expect(HomeLayout.reconcile(const []), same(HomeLayout.defaultLayout));
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
    ]);
  });

  test('should_renderOnlyV1Sections_when_default', () {
    expect(HomeLayout.defaultLayout.visibleV1Sections, [
      HomeSection.activities,
      HomeSection.jumpBackIn,
      HomeSection.recentlyAdded,
      HomeSection.rediscover,
      HomeSection.recentlyPlayed,
      HomeSection.yourPlaylists,
      HomeSection.memoryGrid,
    ]);
  });

  test('should_keepSavedOrderDropRetiredKeepUnknownAppendMissing_when_reconciling', () {
    final layout = HomeLayout.reconcile(const [
      HomeSectionPref(id: 'your_playlists', enabled: true),
      HomeSectionPref(id: 'memories', enabled: true),
      HomeSectionPref(id: 'future_thing', enabled: true),
      HomeSectionPref(id: 'activities', enabled: false, config: {'rows': 'xl'}),
      HomeSectionPref(id: 'your_playlists', enabled: false),
    ]);
    final ids = layout.sections.map((s) => s.id).toList();
    expect(ids.take(3), ['your_playlists', 'future_thing', 'activities']);
    expect(ids, isNot(contains('memories')));
    expect(ids.where((id) => id == 'your_playlists').length, 1);
    expect(layout.sections.firstWhere((s) => s.id == 'rediscover').enabled, isFalse);
    expect(layout.sections.firstWhere((s) => s.id == 'activities').rows, HomeRowPreset.xl);
    expect(layout.visibleV1Sections.first, HomeSection.yourPlaylists);
    expect(layout.visibleV1Sections, isNot(contains(HomeSection.activities)));
  });

  test('should_roundTripJson_when_persisted', () {
    final back = HomeLayout.fromJson(HomeLayout.defaultLayout.toJson());
    expect(back.sections, HomeLayout.defaultLayout.sections);
  });

  group('row presets', () {
    test('should_writeRowsAndDropTheDefault_when_settingPresets', () {
      final xl = withSectionRows(HomeLayout.defaultLayout, HomeSection.jumpBackIn, HomeRowPreset.xl);
      expect(xl.rowsOf(HomeSection.jumpBackIn), HomeRowPreset.xl);
      expect(xl.sections.firstWhere((p) => p.id == 'jump_back_in').toJson()['config'], {'rows': 'xl'});
      expect(xl.isDefault, isFalse);
      final back = withSectionRows(xl, HomeSection.jumpBackIn, HomeRowPreset.l);
      expect(back.sections.firstWhere((p) => p.id == 'jump_back_in').toJson().containsKey('config'), isFalse);
      expect(back.isDefault, isTrue);
    });

    test('should_returnTheSameLayout_when_nothingChanges', () {
      final layout = HomeLayout.defaultLayout;
      expect(identical(withSectionRows(layout, HomeSection.activities, HomeRowPreset.l), layout), isTrue);
      expect(identical(withSectionRows(layout, HomeSection.recentlyAdded, HomeRowPreset.s), layout), isTrue);
      expect(identical(withSectionEnabled(layout, HomeSection.activities, true), layout), isTrue);
      expect(HomeSection.lyricHighlight.takesPresets, isTrue);
      expect(HomeSection.recentlyAdded.takesPresets, isFalse);
    });

    test('should_keepOtherSettingsVerbatim_when_rowsChange', () {
      final layout = HomeLayout.fromJson({
        'version': 1,
        'sections': [
          {
            'id': 'activities',
            'enabled': true,
            'config': {
              'rows': 'zz',
              'density': 3,
              'future': {
                'a': [1, 2],
              },
            },
          },
        ],
      });
      // An unreadable preset reads as L and rides along until it changes.
      expect(layout.rowsOf(HomeSection.activities), HomeRowPreset.l);
      expect(identical(withSectionRows(layout, HomeSection.activities, HomeRowPreset.l), layout), isTrue);
      final m = withSectionRows(layout, HomeSection.activities, HomeRowPreset.m);
      expect(m.sections.first.config, {
        'rows': 'm',
        'density': 3,
        'future': {
          'a': [1, 2],
        },
      });
      final round = HomeLayout.fromJson(m.toJson());
      expect(round.sections.first, m.sections.first);
    });

    test('should_keepUnknownIds_when_resetting', () {
      final layout = HomeLayout.reconcile(const [
        HomeSectionPref(id: 'future_thing', enabled: true),
        HomeSectionPref(id: 'activities', enabled: false, config: {'rows': 's'}),
      ]);
      final reset = resetLayout(layout);
      expect(reset.rowsOf(HomeSection.activities), HomeRowPreset.l);
      expect(reset.visibleV1Sections, HomeLayout.defaultLayout.visibleV1Sections);
      expect(reset.sections.last.id, 'future_thing');
      expect(identical(resetLayout(HomeLayout.defaultLayout), HomeLayout.defaultLayout), isTrue);
    });

    test('should_readKnownPresetIds_when_parsing', () {
      expect(HomeRowPreset.fromId('xl'), HomeRowPreset.xl);
      expect(HomeRowPreset.fromId('xxl'), isNull);
      expect(HomeRowPreset.byId(null), HomeRowPreset.l);
    });
  });
}
