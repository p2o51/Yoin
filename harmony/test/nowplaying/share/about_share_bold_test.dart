// iOS #352 / Android ShareImageTest: the About share layouts draw the AI's
// **bold** bold, as Now Playing's About page does (markdownBoldSpan), never
// as literal asterisks; and the Chinese sign has no space beside 千问.
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/ai/ai_settings.dart';
import 'package:yoin/nowplaying/share_cards.dart';
import 'package:yoin/nowplaying/share_content.dart';
import 'package:yoin/theme/theme.dart';

import 'share_fixtures.dart';

Future<void> _pump(WidgetTester tester, ShareContent content, ShareLayout layout) async {
  await tester.pumpWidget(
    ProviderScope(
      child: MaterialApp(
        theme: yoinTheme(Brightness.light),
        locale: const Locale('zh'),
        supportedLocales: const [Locale('en'), Locale('zh')],
        localizationsDelegates: GlobalMaterialLocalizations.delegates,
        home: Scaffold(
          body: SingleChildScrollView(
            child: Center(
              child: ShareImage(
                content: content,
                layout: layout,
                format: ShareFormat.card,
                tone: ShareTone.soft,
                animateTone: false,
              ),
            ),
          ),
        ),
      ),
    ),
  );
  await tester.pump();
}

/// Every piece of text on screen, rich text flattened.
List<String> _texts(WidgetTester tester) => [
  for (final w in tester.widgetList<RichText>(find.byType(RichText))) w.text.toPlainText(),
];

void main() {
  final base = shareFixture(cover: false);
  final bold = ShareContent(
    song: base.song,
    about: const ShareAbout(
      credits: [ShareCredit('制作人', '**林暐哲**', ai: true)],
      heading: '关于',
      paragraph: '这首歌收录于**《春·日光》**，2009 年。',
    ),
    aiProvider: AiProvider.qwen,
  );

  for (final layout in [ShareLayout.aboutReceipt, ShareLayout.aboutQuote, ShareLayout.aboutLiner]) {
    testWidgets('should_drawTheAisBoldBold_when_${layout.storeValue}', (tester) async {
      await _pump(tester, bold, layout);
      final texts = _texts(tester);
      expect(texts.where((t) => t.contains('**')), isEmpty, reason: '$texts');
      expect(texts, contains('这首歌收录于《春·日光》，2009 年。'));
      expect(texts, contains('林暐哲'));
      final paragraph = tester
          .widgetList<RichText>(find.byType(RichText))
          .firstWhere((w) => w.text.toPlainText() == '这首歌收录于《春·日光》，2009 年。');
      final spans = <TextSpan>[];
      paragraph.text.visitChildren((s) {
        if (s is TextSpan && s.text == '《春·日光》') spans.add(s);
        return true;
      });
      expect(spans.single.style?.fontWeight, FontWeight.w700);
    });
  }

  testWidgets('should_signWithoutSpacesBesideAChineseName', (tester) async {
    await _pump(tester, bold, ShareLayout.aboutReceipt);
    final texts = _texts(tester);
    expect(texts.where((t) => t.contains('整理')).single, '由千问整理', reason: '$texts');
  });
}
