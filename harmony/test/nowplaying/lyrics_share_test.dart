import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/nowplaying/lyrics_logic.dart';
import 'package:yoin/nowplaying/lyrics_share.dart';
import 'package:yoin/nowplaying/nowplaying_strings.dart';
import 'package:yoin/nowplaying/share_content.dart';

Future<NowPlayingStrings> _strings(WidgetTester tester, String lang) async {
  late NowPlayingStrings strings;
  await tester.pumpWidget(
    Localizations(
      locale: Locale(lang),
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
  testWidgets('should_nameFilesAsAndroid_when_namingInEitherLanguage', (tester) async {
    for (final lang in ['en', 'zh']) {
      final s = await _strings(tester, lang);
      expect(shareFileName('A/B: "C"', 42, s), 'Yoin - A B C - 42.png');
      expect(shareFileName('   ', 42, s), 'Yoin 42.png');
      expect(shareFileName('x' * 60, 1, s), 'Yoin - ${'x' * 48} - 1.png');
    }
  });

  test('should_pairLyricsTonesLikeAndroid_when_reading', () {
    final scheme = ColorScheme.fromSeed(seedColor: Colors.purple);
    final soft = shareToneColors(ShareTone.soft, ShareRole.lyrics, scheme);
    expect(soft.card, scheme.primaryContainer);
    expect(soft.backdrop, scheme.primary);
    expect(soft.secondary, scheme.onPrimaryContainer.withValues(alpha: YoinShareCards.secondaryAlpha));
    final deep = shareToneColors(ShareTone.deep, ShareRole.lyrics, scheme);
    expect(deep.card, scheme.onPrimaryContainer);
    expect(deep.content, scheme.primaryContainer);
    expect(shareToneColors(ShareTone.vivid, ShareRole.lyrics, scheme).card, scheme.tertiary);
    expect(shareToneColors(ShareTone.mist, ShareRole.lyrics, scheme).card, scheme.tertiaryContainer);
  });

  test('should_drawAboutTertiaryNotePrimaryAndPaperWhite_when_reading', () {
    for (final brightness in Brightness.values) {
      final scheme = ColorScheme.fromSeed(seedColor: Colors.teal, brightness: brightness);
      expect(shareToneColors(ShareTone.soft, ShareRole.ai, scheme).card, scheme.tertiaryContainer);
      expect(shareToneColors(ShareTone.vivid, ShareRole.ai, scheme).card, scheme.tertiary);
      expect(shareToneColors(ShareTone.soft, ShareRole.own, scheme).card, scheme.primaryContainer);
      expect(shareToneColors(ShareTone.vivid, ShareRole.own, scheme).card, scheme.primary);
      final paper = shareToneColors(ShareTone.paper, ShareRole.own, scheme);
      // White in dark mode too, the ink the role at its light tone.
      expect(paper.card, sharePaperCard);
      expect(paper.content, Color(YoinShareCards.retone(scheme.primary.toARGB32(), 40)));
      expect(paper.backdrop, Color(YoinShareCards.retone(scheme.primary.toARGB32(), 90)));
    }
  });

  test('should_freezeThePickInSongOrder_when_sharingLyrics', () {
    final pick = shareLyricsPick(
      const [LyricRow('a', startMs: 1000, translation: 'A'), LyricRow('c', startMs: 3000)],
      {1},
      showTranslation: true,
    );
    expect(pick.lines.map((l) => l.text), ['a', 'c']);
    expect(pick.lines.first.translation, 'A');
    expect(pick.passageStarts, [1]);
    expect(pick.showTranslation, isTrue);
  });
}
