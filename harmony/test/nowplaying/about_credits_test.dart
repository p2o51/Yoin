import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/nowplaying/about_logic.dart';
import 'package:yoin/nowplaying/about_widgets.dart';

// About's receipt with the lyrics' credits (AboutReceipt.kt): the headline
// roles as lines, the rest behind "N more" (the first roles quieter beside
// it, no dot), copyright notices at the
// foot, and the same receipt when there is no AI key (SourceReceipt).

const _credits = LyricCredits(
  [
    LyricCredit('作词', '唐恬', headline: LyricCreditRole.lyricist),
    LyricCredit('吉他', '高飞'),
    LyricCredit('', 'Recorded at Rockfield Studio'),
    LyricCredit('混音', '周天澈'),
    LyricCredit('母带', 'Studio21A'),
  ],
  notices: ['词版权管理方：北京梦织音传媒有限公司'],
);

Future<void> _pump(WidgetTester tester, Widget child, {Locale locale = const Locale('en')}) => tester.pumpWidget(
  MaterialApp(
    locale: locale,
    supportedLocales: const [Locale('en'), Locale('zh')],
    localizationsDelegates: GlobalMaterialLocalizations.delegates,
    home: Scaffold(body: SingleChildScrollView(child: child)),
  ),
);

void main() {
  testWidgets('should_unrollTheOtherCredits_when_moreIsTapped', (tester) async {
    await _pump(tester, const AboutReceipt(rows: [ReceiptRow('Lyricist', '唐恬')], credits: _credits));
    expect(find.text('Lyricist'), findsOneWidget);
    expect(find.text('4 more'), findsOneWidget);
    expect(find.text('吉他、Note、混音…'), findsOneWidget);
    expect(find.textContaining('·'), findsNothing);
    final colors = Theme.of(tester.element(find.text('4 more'))).colorScheme;
    expect(tester.widget<Text>(find.text('4 more')).style?.color, colors.primary);
    expect(tester.widget<Text>(find.text('吉他、Note、混音…')).style?.color, colors.onSurfaceVariant);
    expect(find.text('高飞'), findsNothing);
    expect(find.text('词版权管理方：北京梦织音传媒有限公司'), findsOneWidget);

    await tester.tap(find.text('4 more'));
    await tester.pumpAndSettle();
    expect(find.text('吉他'), findsOneWidget);
    expect(find.text('高飞'), findsOneWidget);
    expect(find.text('Recorded at Rockfield Studio'), findsOneWidget);
    expect(find.text('Studio21A'), findsOneWidget);
    expect(find.text('Show less'), findsOneWidget);
    expect(find.text('吉他、Note、混音…'), findsNothing, reason: 'the roles beside it go while it reads Show less');

    await tester.tap(find.text('Show less'));
    await tester.pumpAndSettle();
    expect(find.text('高飞'), findsNothing);
    expect(find.text('4 more'), findsOneWidget);
    expect(find.text('吉他、Note、混音…'), findsOneWidget);
  });

  testWidgets('should_growOnASpring_when_theCreditsUnroll', (tester) async {
    await _pump(tester, const AboutReceipt(rows: [ReceiptRow('Lyricist', '唐恬')], credits: _credits));
    final closed = tester.getSize(find.byType(AboutReceipt)).height;
    await tester.tap(find.textContaining('4 more'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 16));
    await tester.pump(const Duration(milliseconds: 50));
    final moving = tester.getSize(find.byType(AboutReceipt)).height;
    await tester.pumpAndSettle();
    final open = tester.getSize(find.byType(AboutReceipt)).height;
    expect(open, greaterThan(closed));
    expect(moving, greaterThan(closed));
    expect(moving, lessThan(open));
  });

  testWidgets('should_foldAgain_when_theCreditsChange', (tester) async {
    await _pump(tester, const AboutReceipt(rows: [], credits: _credits));
    await tester.tap(find.textContaining('4 more'));
    await tester.pumpAndSettle();
    expect(find.text('高飞'), findsOneWidget);
    const next = LyricCredits([LyricCredit('吉他', '某人'), LyricCredit('混音', '另一人')]);
    await _pump(tester, const AboutReceipt(rows: [], credits: next));
    await tester.pumpAndSettle();
    expect(find.text('某人'), findsNothing);
    expect(find.text('2 more'), findsOneWidget);
    expect(find.text('吉他、混音…'), findsOneWidget);
  });

  testWidgets('should_sayItInChinese_when_theLocaleIsChinese', (tester) async {
    await _pump(
      tester,
      const AboutReceipt(rows: [], credits: _credits),
      locale: const Locale('zh'),
    );
    expect(find.text('更多 4 项'), findsOneWidget);
    expect(find.text('吉他、备注、混音…'), findsOneWidget);
    await tester.tap(find.textContaining('更多'));
    await tester.pumpAndSettle();
    expect(find.text('收起'), findsOneWidget);
  });

  testWidgets('should_showNothing_when_thereIsNothingToShow', (tester) async {
    await _pump(tester, const AboutReceipt(rows: []));
    expect(find.byType(DecoratedBox), findsNothing);
  });

  testWidgets('should_showTheLyricsCreditsAndFacts_when_noAiKey', (tester) async {
    await _pump(
      tester,
      SizedBox(
        height: 800,
        child: AboutPane(
          state: const AboutKeyMissing(),
          onRetry: () {},
          onOpenSettings: () {},
          credits: _credits,
          facts: const AboutSourceFacts(composer: 'Server composer', bpm: 120),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('唐恬'), findsOneWidget);
    expect(find.text('Server composer'), findsOneWidget);
    expect(find.text('120'), findsOneWidget);
    expect(find.text('4 more'), findsOneWidget);
    expect(find.text('No Gemini API key'), findsOneWidget);
  });

  testWidgets('should_carouselTheLyricsCredits_when_compact', (tester) async {
    await _pump(
      tester,
      const SizedBox(
        height: 300,
        child: SongInfoDisplay(
          state: AboutIdle(),
          onRetry: _noop,
          onOpenSettings: _noop,
          credits: _credits,
          facts: AboutSourceFacts(genre: 'Pop'),
        ),
      ),
    );
    await tester.pump();
    expect(find.text('唐恬'), findsOneWidget);
    expect(find.text('Pop'), findsOneWidget);
  });
}

void _noop() {}
