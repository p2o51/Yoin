import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/home/home_edit_gestures.dart';
import 'package:yoin/home/home_section.dart';

/// Home edit's hit testing (Android HomeEditGestures.kt). The machine itself is replayed against the
/// shared golden vectors in home_edit_gestures_golden_test.dart.
void main() {
  group('hit testing', () {
    const bands = [
      SectionBand(HomeSection.activities, 100, 300),
      SectionBand(HomeSection.recentlyAdded, 340, 500, bleeds: true),
    ];

    HomeEditHit hit(Offset p, {bool editing = false, HomeSection? handle}) => resolveHomeEditHit(
      position: p,
      bands: bands,
      contentLeft: 16,
      contentRight: 376,
      outset: const Size(12, 12),
      editing: editing,
      handle: handle,
    );

    test('should_resolveTheBlockInContentPx_when_insideABand', () {
      final b = hit(const Offset(50, 150)) as HomeEditHitBlock;
      expect(b.section, HomeSection.activities);
      expect(b.local, const Offset(34, 50));
      expect(b.size, const Size(360, 200));
    });

    test('should_countThePlateOutset_when_editing', () {
      expect(hit(const Offset(8, 150)), isA<HomeEditHitBlank>());
      expect(hit(const Offset(8, 150), editing: true), isA<HomeEditHitBlock>());
      expect(hit(const Offset(100, 310), editing: true), isA<HomeEditHitBlock>());
    });

    test('should_takeTheMargin_when_theShelfBleeds', () {
      expect((hit(const Offset(2, 400)) as HomeEditHitBlock).section, HomeSection.recentlyAdded);
    });

    test('should_nameTheNearest_when_blank', () {
      expect((hit(const Offset(100, 320)) as HomeEditHitBlank).nearest, HomeSection.activities);
      expect((hit(const Offset(100, 900)) as HomeEditHitBlank).nearest, HomeSection.recentlyAdded);
    });

    test('should_preferTheHandle_when_oneIsUnderTheFinger', () {
      expect(hit(const Offset(370, 105), editing: true, handle: HomeSection.activities), isA<HomeEditHitHandle>());
    });
  });
}
