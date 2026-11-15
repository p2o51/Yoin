import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/nowplaying/lyrics_logic.dart';
import 'package:yoin/nowplaying/lyrics_share.dart';
import 'package:yoin/nowplaying/nowplaying_strings.dart';

Future<NowPlayingStrings> _en(WidgetTester tester) async {
  late NowPlayingStrings strings;
  await tester.pumpWidget(
    Localizations(
      locale: const Locale('en'),
      delegates: const [DefaultWidgetsLocalizations.delegate],
      child: Builder(
        builder: (context) {
          strings = NowPlayingStrings.of(context);
          return const SizedBox();
        },
      ),
    ),
  );
  return strings;
}

void main() {
  test('should_exportAndroidPixelSizes_when_readingRatios', () {
    expect(LyricsShareMetrics.cardWidth * lyricsSharePixelRatio(LyricsShareFormat.card), 1440);
    final story = LyricsShareMetrics.storySize * lyricsSharePixelRatio(LyricsShareFormat.story);
    expect(story, const Size(1080, 1920));
  });

  test('should_stepTypeDown_when_pickGrows', () {
    expect(lyricsCardLineSize(const [LyricRow('short')]), 22);
    expect(lyricsCardLineSize([for (var i = 0; i < 5; i++) const LyricRow('a few words here')]), 19);
    expect(lyricsCardLineSize([for (var i = 0; i < 15; i++) const LyricRow('a much longer line of words here')]), 17);
  });

  test('should_findLargestFittingZoom_when_storyIsLong', () {
    expect(lyricsStoryZoom((_) => true), 1);
    expect(lyricsStoryZoom((_) => false), LyricsShareMetrics.storyMinZoom);
    final zoom = lyricsStoryZoom((z) => z <= 0.7);
    expect(zoom, lessThanOrEqualTo(0.7));
    expect(zoom, greaterThan(0.68));
  });

  testWidgets('should_dropRefusedCharacters_when_namingFile', (tester) async {
    final en = await _en(tester);
    expect(lyricsShareFileName('A/B: "C"', 42, en), 'Yoin lyrics - A B C - 42.png');
    expect(lyricsShareFileName('   ', 42, en), 'Yoin lyrics 42.png');
    expect(lyricsShareFileName('x' * 60, 1, en), 'Yoin lyrics - ${'x' * 48} - 1.png');
  });

  test('should_pairToneColoursLikeAndroid_when_reading', () {
    final scheme = ColorScheme.fromSeed(seedColor: Colors.purple);
    final soft = lyricsCardColors(LyricsCardTone.soft, scheme);
    expect(soft.card, scheme.primaryContainer);
    expect(soft.backdrop, scheme.primary);
    final deep = lyricsCardColors(LyricsCardTone.deep, scheme);
    expect(deep.card, scheme.onPrimaryContainer);
    expect(deep.content, scheme.primaryContainer);
    expect(lyricsCardColors(LyricsCardTone.vivid, scheme).card, scheme.tertiary);
    expect(lyricsCardColors(LyricsCardTone.mist, scheme).card, scheme.tertiaryContainer);
  });
}
