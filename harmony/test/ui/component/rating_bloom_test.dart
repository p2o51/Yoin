import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/detail/album_hero_marks.dart' show albumScoreBloomTrigger;
import 'package:yoin/memories/album_score.dart';
import 'package:yoin/memories/emblem/groove_geometry.dart' show GrooveScoreKind;
import 'package:yoin/nowplaying/haptics.dart';
import 'package:yoin/platform/haptics/haptics_host.dart';
import 'package:yoin/theme/tokens.dart';
import 'package:yoin/ui/component/rating_bloom.dart';

const _badge = Key('badge');

Widget _bloom(Object? trigger, {Duration settle = RatingBloomTokens.settle, bool reduced = false}) => MediaQuery(
  data: MediaQueryData(disableAnimations: reduced),
  child: Center(
    child: RatingBloom(
      trigger: trigger,
      settle: settle,
      child: const SizedBox(key: _badge, width: 64, height: 64),
    ),
  ),
);

/// The badge's painted scale (its transform's x scale).
double _scaleOf(WidgetTester tester) {
  final box = tester.renderObject<RenderBox>(find.byKey(_badge));
  return box.getTransformTo(null).entry(0, 0);
}

void main() {
  late RecordingHapticsHost haptics;

  setUp(() {
    haptics = RecordingHapticsHost();
    YoinHaptics.host = haptics;
  });
  tearDown(() => YoinHaptics.host = const NoHapticsHost());

  testWidgets('should_stayQuiet_when_scoreResolvesInsideSettleWindow', (tester) async {
    await tester.pumpWidget(_bloom((GrooveScoreKind.none, 0.0)));
    await tester.pump(const Duration(milliseconds: 300));
    await tester.pumpWidget(_bloom((GrooveScoreKind.album, 8.0)));
    await tester.pump(const Duration(milliseconds: 16));
    expect(haptics.performed, isEmpty);
    expect(_scaleOf(tester), closeTo(1, 1e-6));
    await tester.pump(RatingBloomTokens.settle);
  });

  testWidgets('should_popWithConfirmHaptic_when_scoreChangesAfterSettle', (tester) async {
    await tester.pumpWidget(_bloom((GrooveScoreKind.album, 8.0)));
    await tester.pump(RatingBloomTokens.settle + const Duration(milliseconds: 100));
    await tester.pumpWidget(_bloom((GrooveScoreKind.album, 9.0)));
    expect(haptics.performed, [YoinHaptic.confirm]);
    await tester.pump(const Duration(milliseconds: 16));
    expect(_scaleOf(tester), lessThan(0.9), reason: 'it starts small');
    var peak = 0.0;
    for (var i = 0; i < 60; i++) {
      await tester.pump(const Duration(milliseconds: 16));
      final s = _scaleOf(tester);
      if (s > peak) peak = s;
    }
    expect(peak, greaterThan(1), reason: 'the spring overshoots');
    await tester.pumpAndSettle();
    expect(_scaleOf(tester), closeTo(1, 1e-3));
  });

  testWidgets('should_notPop_when_triggerIsUnchangedByValue', (tester) async {
    await tester.pumpWidget(_bloom((GrooveScoreKind.album, 8.0)));
    await tester.pump(RatingBloomTokens.settle + const Duration(milliseconds: 100));
    await tester.pumpWidget(_bloom((GrooveScoreKind.album, 8.0)));
    await tester.pump(const Duration(milliseconds: 16));
    expect(haptics.performed, isEmpty);
  });

  testWidgets('should_popOnFirstCommit_when_settleIsZero', (tester) async {
    await tester.pumpWidget(_bloom((GrooveScoreKind.none, 0.0), settle: Duration.zero));
    await tester.pumpWidget(_bloom((GrooveScoreKind.album, 6.0), settle: Duration.zero));
    expect(haptics.performed, [YoinHaptic.confirm]);
    await tester.pumpAndSettle();
  });

  testWidgets('should_keepHapticButStayStill_when_reducedMotion', (tester) async {
    await tester.pumpWidget(_bloom((GrooveScoreKind.album, 8.0), reduced: true));
    await tester.pump(RatingBloomTokens.settle + const Duration(milliseconds: 100));
    await tester.pumpWidget(_bloom((GrooveScoreKind.average, 7.5), reduced: true));
    await tester.pump(const Duration(milliseconds: 16));
    expect(haptics.performed, [YoinHaptic.confirm]);
    expect(_scaleOf(tester), closeTo(1, 1e-6));
  });

  test('should_compareKindAndNumber_when_buildingTheAlbumTrigger', () {
    expect(albumScoreBloomTrigger(const AlbumScore(albumRating: 8)), (GrooveScoreKind.album, 8.0));
    expect(albumScoreBloomTrigger(const AlbumScore(average: 7.5)), (GrooveScoreKind.average, 7.5));
    expect(albumScoreBloomTrigger(const AlbumScore(albumRating: 8, average: 6)), (GrooveScoreKind.album, 8.0));
    expect(albumScoreBloomTrigger(const AlbumScore()), (GrooveScoreKind.none, 0.0));
    expect(
      albumScoreBloomTrigger(const AlbumScore(albumRating: 8, review: 'a')),
      albumScoreBloomTrigger(const AlbumScore(albumRating: 8, review: 'b')),
      reason: 'a review edit is not a score commit',
    );
  });
}
