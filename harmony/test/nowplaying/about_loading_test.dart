import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/nowplaying/about_logic.dart';
import 'package:yoin/nowplaying/about_widgets.dart';
import 'package:yoin/nowplaying/note_logic.dart';
import 'package:yoin/nowplaying/stage_tabs.dart';
import 'package:yoin/ui/component/yoin_loading_indicator.dart';

// About while the AI loads over what the lyrics and the server already say
// (Android 5145074a9), and a compact page taller than its window (add8a63f5).

const _credits = LyricCredits([
  LyricCredit('作词', 'Joni Mitchell', headline: LyricCreditRole.lyricist),
  LyricCredit('制作', 'Henry Lewy', headline: LyricCreditRole.producer),
]);

const _facts = AboutSourceFacts(genre: 'Folk', year: 1971, durationSec: 200);

Widget _app(Widget child, {Size size = const Size(412, 600)}) => MaterialApp(
  home: Material(
    child: Center(
      child: SizedBox.fromSize(size: size, child: child),
    ),
  ),
);

void main() {
  group('aboutHasSourceRows', () {
    test('should_haveRows_when_lyricsCreditOrServerFactsExist', () {
      expect(aboutHasSourceRows(_credits, null), isTrue);
      expect(aboutHasSourceRows(null, _facts), isTrue);
    });

    test('should_haveNoRows_when_neitherSaysAnything', () {
      expect(aboutHasSourceRows(null, null), isFalse);
      expect(aboutHasSourceRows(const LyricCredits([]), const AboutSourceFacts()), isFalse);
    });

    test('should_countADiscTrackRow_when_itIsTheOnlyFact', () {
      expect(aboutHasSourceRows(null, const AboutSourceFacts(trackNumber: 3, discNumber: 2)), isTrue);
    });
  });

  group('expanded About', () {
    Future<void> pumpPane(WidgetTester tester, AboutUiState state, {LyricCredits? credits, AboutSourceFacts? facts}) =>
        tester.pumpWidget(
          _app(AboutPane(state: state, onRetry: () {}, onOpenSettings: () {}, credits: credits, facts: facts)),
        );

    testWidgets('should_showTheCreditsAndFacts_when_theAiIsLoading', (tester) async {
      await pumpPane(tester, const AboutLoading(), credits: _credits, facts: _facts);
      await tester.pump(const Duration(milliseconds: 500));
      expect(find.text('Joni Mitchell'), findsOneWidget);
      expect(find.text('Folk'), findsOneWidget);
      // The wait shows in the tab row, not as a centred spinner over a blank page.
      expect(find.text('Searching for song info…'), findsNothing);
      expect(find.byType(YoinLoadingIndicator), findsNothing);
    });

    testWidgets('should_showTheCreditsAndFacts_when_theAiHasNotStarted', (tester) async {
      await pumpPane(tester, const AboutIdle(), facts: _facts);
      await tester.pump(const Duration(milliseconds: 500));
      expect(find.text('Folk'), findsOneWidget);
    });

    testWidgets('should_keepTheCentredSearch_when_thereIsNothingToShow', (tester) async {
      await pumpPane(tester, const AboutLoading());
      await tester.pump(const Duration(milliseconds: 500));
      expect(find.text('Searching for song info…'), findsOneWidget);
    });
  });

  group('compact About', () {
    Future<void> pumpCompact(WidgetTester tester, AboutUiState state, {Size size = const Size(412, 240)}) =>
        tester.pumpWidget(
          _app(
            SongInfoDisplay(state: state, onRetry: () {}, onOpenSettings: () {}, credits: _credits, facts: _facts),
            size: size,
          ),
        );

    testWidgets('should_notTrailASearchLine_when_theAiLoadsOverContent', (tester) async {
      await pumpCompact(tester, const AboutLoading());
      await tester.pump(const Duration(milliseconds: 500));
      expect(find.text('Joni Mitchell'), findsOneWidget);
      expect(find.text('Searching for song info…'), findsNothing);
      expect(find.text('Load song info'), findsNothing);
    });

    testWidgets('should_keepTheRetry_when_theAiFailedUnderContent', (tester) async {
      await pumpCompact(tester, const AboutError('Quota exceeded'));
      await tester.pump(const Duration(milliseconds: 500));
      expect(find.text('Joni Mitchell'), findsOneWidget);
      expect(find.text('Quota exceeded'), findsOneWidget);
      expect(find.text('Retry'), findsOneWidget);
    });

    testWidgets('should_startAtTheTopAndFade_when_thePageIsTallerThanTheWindow', (tester) async {
      await pumpCompact(tester, const AboutReady([]), size: const Size(412, 60));
      await tester.pump(const Duration(milliseconds: 500));
      final frame = tester.renderObject<RenderAboutCarouselFrame>(find.byType(AboutCarouselFrame));
      expect(frame.overflows, isTrue);
      final top = tester.getTopLeft(find.byType(AboutCarouselFrame)).dy;
      // The first line sits at the window's top, not cut off above it.
      expect(tester.getTopLeft(find.text('Joni Mitchell')).dy, greaterThanOrEqualTo(top));
      final page = find.descendant(of: find.byType(AboutCarouselFrame), matching: find.byType(Column)).first;
      expect(tester.getTopLeft(page).dy, closeTo(top, 0.5));
      // Clipped to the window and masked out over its bottom.
      expect(tester.layers.whereType<ShaderMaskLayer>(), isNotEmpty);
      expect(tester.takeException(), isNull);
    });

    testWidgets('should_stayCentred_when_thePageFits', (tester) async {
      await pumpCompact(tester, const AboutReady([]), size: const Size(412, 400));
      await tester.pump(const Duration(milliseconds: 500));
      final frame = tester.renderObject<RenderAboutCarouselFrame>(find.byType(AboutCarouselFrame));
      expect(frame.overflows, isFalse);
      final box = tester.getRect(find.byType(AboutCarouselFrame));
      final page = tester.getRect(
        find.descendant(of: find.byType(AboutCarouselFrame), matching: find.byType(Column)).first,
      );
      expect(page.center.dy, closeTo(box.center.dy, 1));
      expect(tester.layers.whereType<ShaderMaskLayer>(), isEmpty);
    });
  });

  group('tabs', () {
    Future<void> pumpTabs(WidgetTester tester, {required bool busy, double detail = 0}) => tester.pumpWidget(
      _app(
        StageTabs(
          selected: NowPlayingDetailPage.about,
          detail: AlwaysStoppedAnimation(detail),
          onSelect: (_) {},
          aboutBusy: busy,
        ),
        size: const Size(412, 56),
      ),
    );

    for (final (name, detail) in [('text tabs', 0.0), ('tab group', 1.0)]) {
      testWidgets('should_openAnIndicatorBetweenAboutAndNote_when_aboutIsBusy ($name)', (tester) async {
        await pumpTabs(tester, busy: false, detail: detail);
        expect(find.byType(YoinLoadingIndicator), findsNothing);
        await pumpTabs(tester, busy: true, detail: detail);
        await tester.pump(const Duration(seconds: 1));
        final indicator = tester.getCenter(find.byType(YoinLoadingIndicator));
        expect(indicator.dx, greaterThan(tester.getCenter(find.text('About')).dx));
        expect(indicator.dx, lessThan(tester.getCenter(find.text('Note')).dx));
        await pumpTabs(tester, busy: false, detail: detail);
        await tester.pump(const Duration(seconds: 2));
        expect(find.byType(YoinLoadingIndicator), findsNothing);
        expect(tester.takeException(), isNull);
      });
    }

    testWidgets('should_pushTheNoteTabAlong_when_theTextTabsIndicatorOpens', (tester) async {
      await pumpTabs(tester, busy: false);
      final before = tester.getTopLeft(find.text('Note')).dx;
      await pumpTabs(tester, busy: true);
      await tester.pump(const Duration(seconds: 1));
      expect(tester.getTopLeft(find.text('Note')).dx, greaterThan(before + 20));
    });
  });
}
