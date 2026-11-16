import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/nowplaying/lyric_halftone.dart';
import 'package:yoin/theme/tokens.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

// Focus's lyric halftone (LyricHalftone.kt).

typedef _T = NowPlayingEffectTokens;

YoinWindowInfo _window(double w, double h) => YoinWindowInfo(w, h);

Future<void> _pump(WidgetTester tester, double radius) => tester.pumpWidget(
  MaterialApp(
    home: Center(
      child: LyricHalftone(radius: radius, child: const Text('a lyric line')),
    ),
  ),
);

void main() {
  test('should_beSolidOutOfFocus_when_anyLineRadius', () {
    expect(lyricDotRadius(_T.lyricDotsRest, 0), _T.lyricDotsSolid);
    expect(lyricDotRadius(_T.lyricDotsRest, 1), _T.lyricDotsRest);
    expect(lyricDotRadius(_T.lyricDotsRest, 0.5), closeTo((_T.lyricDotsSolid + _T.lyricDotsRest) / 2, 1e-9));
  });

  test('should_restFullerOnLargeWindows_when_theLineIsNotUpcoming', () {
    expect(lyricDotsRestFor(_window(400, 800)), _T.lyricDotsRest);
    expect(lyricDotsRestFor(_window(800, 1280)), _T.lyricDotsRestLarge);
    // Width alone (a landscape phone) is not a large window.
    expect(lyricDotsRestFor(_window(900, 400)), _T.lyricDotsRest);
    expect(lyricDotsRestFor(_window(400, 800), readable: true), _T.lyricDotsReadable);
  });

  test('should_quantiseTheDots_when_belowSolid', () {
    expect(lyricDotStep(_T.lyricDotsSolid), isNull);
    expect(lyricDotStep(5), isNull);
    expect(lyricDotStep(1.5), lyricDotRadiusSteps ~/ 2);
    expect(lyricDotStep(0), 0);
  });

  testWidgets('should_printSolidWithoutALayer_when_theLineIsCurrent', (tester) async {
    await _pump(tester, _T.lyricDotsSolid);
    expect(find.byType(ShaderMask), findsNothing);
    expect(find.text('a lyric line'), findsOneWidget);
  });

  testWidgets('should_printThroughDots_when_theLineRests', (tester) async {
    await _pump(tester, _T.lyricDotsRest);
    final mask = tester.widget<ShaderMask>(find.byType(ShaderMask));
    expect(mask.blendMode, BlendMode.dstIn);
  });

  testWidgets('should_showNothing_when_theDotsHaveNoSize', (tester) async {
    await _pump(tester, 0);
    expect(find.byType(ShaderMask), findsNothing);
    expect(tester.widget<Opacity>(find.byType(Opacity)).opacity, 0);
  });

  // The line just sung stays readable in focus (Android 52b72a9f9, LyricReadableLinesTest).
  List<int> readable(int current, int count) => [
    for (var i = 0; i < count; i++)
      if (isReadableLyricLine(i, current)) i,
  ];

  test('should_keepTheLineJustSungAndTheNextTwoReadable_when_aLineIsCurrent', () {
    expect(readable(5, 11), [4, 6, 7]);
  });

  test('should_notTreatTheCurrentLineAsReadable_when_itIsBeingSung', () {
    expect(isReadableLyricLine(5, 5), isFalse);
  });

  test('should_keepOnlyTheNextTwoReadable_when_theFirstLineIsCurrent', () {
    expect(readable(0, 11), [1, 2]);
  });

  test('should_keepNothingReadable_when_noLineIsCurrent', () {
    expect(readable(-1, 11), isEmpty);
  });

  test('should_readTheFirstTwoRowsAsTheTitleCardsNext_when_theIntroSeatsTheTitle', () {
    // Android's full list counts the title card as item 0: in the intro the first two lines read.
    final rows = [
      for (var i = 0; i < 6; i++)
        if (lyricRowReadable(i, -1, introOnHeader: true)) i,
    ];
    expect(rows, [0, 1]);
    expect(lyricRowReadable(0, -1), isFalse);
    expect(lyricRowReadable(4, 5, introOnHeader: true), isTrue);
  });

  test('should_letLinesTwoOrMoreAboveRestAsFineDots_when_aLineIsCurrent', () {
    expect(isReadableLyricLine(3, 5), isFalse);
    expect(isReadableLyricLine(8, 5), isFalse);
  });
}
