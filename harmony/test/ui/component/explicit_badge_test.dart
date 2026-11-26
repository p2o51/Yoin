import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/release/release_marks.dart';
import 'package:yoin/nowplaying/now_playing_explicit_line.dart';
import 'package:yoin/theme/theme.dart';
import 'package:yoin/ui/component/cover_band_badges.dart';
import 'package:yoin/ui/component/explicit_badge.dart';

Widget _host(Widget child, {double width = 300}) => MaterialApp(
  theme: yoinTheme(Brightness.light),
  home: Scaffold(
    body: Align(
      alignment: Alignment.topLeft,
      child: SizedBox(width: width, child: child),
    ),
  ),
);

const _style = TextStyle(fontSize: 16);

Widget _title(String text, {required bool explicit, int maxLines = 1}) => ExplicitTitle(
  text: text,
  explicit: explicit,
  style: _style,
  maxLines: maxLines,
  child: Text(text, style: _style, maxLines: maxLines, overflow: TextOverflow.ellipsis),
);

void main() {
  group('ExplicitTitle', () {
    testWidgets('should_layOutTheTitleAlone_when_notExplicit', (tester) async {
      await tester.pumpWidget(_host(Align(alignment: Alignment.topLeft, child: _title('HUMBLE.', explicit: false))));
      expect(find.byType(ExplicitSquare), findsNothing);
      expect(find.byType(ExplicitTitleLayout), findsNothing);
    });

    testWidgets('should_setTheBadgeOnTheBaselineAfterTheLastGlyph_when_explicit', (tester) async {
      await tester.pumpWidget(_host(Align(alignment: Alignment.topLeft, child: _title('HUMBLE.', explicit: true))));
      final text = tester.getRect(find.text('HUMBLE.'));
      final badge = tester.getRect(find.byType(ExplicitSquare));
      final title = tester.renderObject<RenderExplicitTitle>(find.byType(ExplicitTitleLayout));
      expect(title.badgeShown, isTrue);
      // The cap-height square (16 × 0.716), one gap (6) after the text.
      expect(badge.width, closeTo(ReleaseMarks.badgeSide(16), 0.01));
      expect(badge.left - text.right, closeTo(ReleaseMarks.badgeGap(16), 1.01));
      final paragraph = tester.renderObject<RenderParagraph>(find.text('HUMBLE.'));
      final painter = TextPainter(
        text: paragraph.text,
        textDirection: TextDirection.ltr,
        textScaler: paragraph.textScaler,
      )..layout();
      final baseline = painter.computeDistanceToActualBaseline(TextBaseline.alphabetic);
      painter.dispose();
      expect(badge.bottom, closeTo(text.top + baseline, 0.5));
    });

    testWidgets('should_followTheEllipsis_when_theTitleIsCut', (tester) async {
      const long = 'A title far too long to fit in the row, by a long way';
      await tester.pumpWidget(_host(_title(long, explicit: true), width: 200));
      final badge = tester.getRect(find.byType(ExplicitSquare));
      expect(badge.right, lessThanOrEqualTo(200.01), reason: 'the badge never falls off the end');
      expect(tester.renderObject<RenderExplicitTitle>(find.byType(ExplicitTitleLayout)).badgeShown, isTrue);
    });

    testWidgets('should_leaveTheBadgeOutButStillSayExplicit_when_theBoxIsTooNarrow', (tester) async {
      final handle = tester.ensureSemantics();
      // 20 wide: the badge (11.5 + 6) would leave the title less than half the box.
      await tester.pumpWidget(_host(_title('HUMBLE.', explicit: true), width: 20));
      expect(tester.renderObject<RenderExplicitTitle>(find.byType(ExplicitTitleLayout)).badgeShown, isFalse);
      expect(find.bySemanticsLabel('HUMBLE., Explicit'), findsOneWidget);
      handle.dispose();
    });

    testWidgets('should_followTheLastLine_when_theTitleWraps', (tester) async {
      const two = 'A title that wraps onto two lines here';
      await tester.pumpWidget(_host(_title(two, explicit: true, maxLines: 2), width: 200));
      final text = tester.getRect(find.text(two));
      final badge = tester.getRect(find.byType(ExplicitSquare));
      expect(badge.top, greaterThan(text.top + text.height / 2), reason: 'on the second line');
    });
  });

  group('CoverBandBadges', () {
    testWidgets('should_placeTheMarksOnTheBandAboveTheCover_when_theyFit', (tester) async {
      await tester.pumpWidget(
        _host(
          const SizedBox.square(
            dimension: 118,
            child: CoverBandBadges(coverFraction: 0.72, kind: 'EP', version: 'Deluxe', explicit: true),
          ),
        ),
      );
      final band = 118 * (1 - 0.72);
      final e = tester.getRect(find.byType(ExplicitSquare));
      final ep = tester.getRect(find.text('EP'));
      expect(e.bottom, lessThanOrEqualTo(band + 0.5), reason: 'never on the art');
      expect(ep.bottom, lessThanOrEqualTo(band + 0.5));
      expect(ep.left, lessThan(e.left), reason: 'kind first, E last');
    });

    testWidgets('should_drawNothing_when_thereIsNothingToMark', (tester) async {
      await tester.pumpWidget(
        _host(const SizedBox.square(dimension: 118, child: CoverBandBadges(coverFraction: 0.72))),
      );
      expect(find.byType(ExplicitSquare), findsNothing);
      expect(find.byType(BandChip), findsNothing);
    });
  });

  group('NowPlayingExplicitLine', () {
    Widget line(String text, double width) => _host(
      NowPlayingExplicitLine(
        text: text,
        style: const TextStyle(fontSize: 36),
        explicit: true,
        child: Text(text, style: const TextStyle(fontSize: 36), maxLines: 1, softWrap: false),
      ),
      width: width,
    );

    testWidgets('should_sizeTheHeroBadgeToTheXHeightCappedAt18_when_explicit', (tester) async {
      await tester.pumpWidget(line('Hol\' Up', 400));
      final badge = tester.getRect(find.byType(ExplicitSquare));
      expect(badge.width, closeTo(18, 0.01));
    });

    testWidgets('should_keepTheBadgeInsideTheRow_when_theTitleIsLongerThanTheRow', (tester) async {
      await tester.pumpWidget(line('A very long title that would scroll along', 300));
      final badge = tester.getRect(find.byType(ExplicitSquare));
      expect(badge.right, lessThanOrEqualTo(300.01));
    });

    testWidgets('should_dropTheBadge_when_theTitleWouldGetLessThanHalfTheRow', (tester) async {
      await tester.pumpWidget(line('Hol\' Up', 40));
      expect(find.byType(ExplicitSquare), findsNothing);
    });
  });
}
