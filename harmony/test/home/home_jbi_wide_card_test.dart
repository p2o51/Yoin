import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/home/home_metrics.dart';
import 'package:yoin/home/jump_back_in.dart';
import 'package:yoin/home/widgets/home_motion.dart';

/// The lying memory card (JbiWideCardLayout, origin/main c4f5e4c29): the
/// caption runs the card's width under a short note, the cover's under a long
/// one. And Home's shelves bleed off the edge with no fade (059b0aa0a).
void main() {
  const cardWidth = 300.0;
  const cover = 128.0;
  const captionKey = Key('caption');
  const copyKey = Key('copy');

  Future<void> pumpCard(WidgetTester tester, {required double copyHeight, TextDirection dir = TextDirection.ltr}) =>
      tester.pumpWidget(
        Directionality(
          textDirection: dir,
          child: Align(
            alignment: Alignment.topLeft,
            child: SizedBox(
              width: cardWidth,
              child: JbiWideCardLayout(
                coverSide: cover,
                children: [
                  const ColoredBox(color: Color(0xFF000000)),
                  SizedBox(key: copyKey, height: copyHeight),
                  // As wide as it is let: a long title.
                  LayoutBuilder(
                    builder: (context, c) => SizedBox(key: captionKey, width: c.maxWidth, height: 40),
                  ),
                ],
              ),
            ),
          ),
        ),
      );

  test('should_runTheCaptionAcrossTheCard_when_theCopyEndsAboveIt', () {
    expect(jbiWideCaptionWidth(cardWidth: 300, coverSide: 128, copyHeight: 90, captionTop: 133), 300);
    expect(jbiWideCaptionWidth(cardWidth: 300, coverSide: 128, copyHeight: 133, captionTop: 133), 300);
    // A note running past the cover keeps the caption under the cover only.
    expect(jbiWideCaptionWidth(cardWidth: 300, coverSide: 128, copyHeight: 150, captionTop: 133), 128);
  });

  testWidgets('should_layCaptionUnderCoverAcrossTheCard_when_noteIsShort', (tester) async {
    await pumpCard(tester, copyHeight: 90);
    final caption = tester.getRect(find.byKey(captionKey));
    expect(caption.left, 0);
    expect(caption.top, cover + HomeJbiMetrics.captionGap);
    expect(caption.width, cardWidth);
    final copy = tester.getRect(find.byKey(copyKey));
    expect(copy.left, cover + HomeJbiMetrics.signalCopyGap);
    expect(copy.top, 0);
    expect(copy.width, cardWidth - cover - HomeJbiMetrics.signalCopyGap);
    expect(tester.getSize(find.byType(JbiWideCardLayout)).height, cover + HomeJbiMetrics.captionGap + 40);
  });

  testWidgets('should_keepCaptionToCoverWidth_when_noteRunsPastTheCover', (tester) async {
    await pumpCard(tester, copyHeight: 200);
    expect(tester.getRect(find.byKey(captionKey)).width, cover);
    // The card is as tall as the longer of the two.
    expect(tester.getSize(find.byType(JbiWideCardLayout)).height, 200);
  });

  testWidgets('should_mirrorTheCard_when_rightToLeft', (tester) async {
    await pumpCard(tester, copyHeight: 200, dir: TextDirection.rtl);
    expect(tester.getRect(find.byKey(captionKey)).right, cardWidth);
    expect(tester.getRect(find.byKey(copyKey)).left, 0);
  });

  testWidgets('should_notFadeTheShelfEdges_when_scrolled', (tester) async {
    await tester.pumpWidget(
      Directionality(
        textDirection: TextDirection.ltr,
        child: Align(
          alignment: Alignment.topLeft,
          child: SizedBox(
            width: 360,
            child: HomeShelf(
              height: 120,
              padding: const EdgeInsetsDirectional.only(start: 16, end: 16),
              itemCount: 12,
              itemBuilder: (context, i) => SizedBox(key: ValueKey(i), width: 120),
            ),
          ),
        ),
      ),
    );
    // At rest the first card sits on the page margin.
    expect(tester.getRect(find.byKey(const ValueKey(0))).left, 16);
    expect(find.byType(ShaderMask), findsNothing);
    await tester.drag(find.byType(HomeShelf), const Offset(-200, 0));
    await tester.pumpAndSettle();
    expect(find.byType(ShaderMask), findsNothing);
  });
}
