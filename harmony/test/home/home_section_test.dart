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
      HomeSection.recentlyPlayed,
      HomeSection.yourPlaylists,
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
}
