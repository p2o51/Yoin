// Android ShareExportEdgesTest: every exported image is a full rectangle —
// every edge pixel opaque — for all ten layouts in Card and Story, at the
// export sizes (Card 1440 wide, height following the content; Story 1080 ×
// 1920), light and dark, Paper too; a long pick zooms the Story out rather
// than losing a line.
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/nowplaying/lyrics_share.dart';
import 'package:yoin/nowplaying/share_cards.dart';
import 'package:yoin/nowplaying/share_content.dart';
import 'package:yoin/nowplaying/share_sheet.dart';
import 'package:yoin/theme/theme.dart';

import 'share_fixtures.dart';

Future<({int width, int height, List<String> seeThrough})> _export(
  WidgetTester tester, {
  required ShareContent content,
  required ShareLayout layout,
  required ShareFormat format,
  ShareTone tone = ShareTone.soft,
  Brightness brightness = Brightness.light,
}) async {
  final key = GlobalKey();
  await tester.pumpWidget(
    ProviderScope(
      child: MaterialApp(
        theme: yoinTheme(brightness),
        home: Scaffold(
          body: SingleChildScrollView(
            child: Center(
              child: RepaintBoundary(
                key: key,
                child: ShareImage(content: content, layout: layout, format: format, tone: tone, animateTone: false),
              ),
            ),
          ),
        ),
      ),
    ),
  );
  final url = content.song.coverArtUrl;
  if (url != null) {
    await tester.runAsync(() => precacheImage(shareCoverImage(url), tester.element(find.byType(ShareImage))));
  }
  await tester.pump();
  final boundary = key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
  late ui.Image image;
  late ByteData pixels;
  await tester.runAsync(() async {
    image = await boundary.toImage(pixelRatio: sharePixelRatio(format));
    pixels = (await image.toByteData(format: ui.ImageByteFormat.rawRgba))!;
  });
  final w = image.width;
  final h = image.height;
  final seeThrough = <String>[];
  int alpha(int x, int y) => pixels.getUint8((y * w + x) * 4 + 3);
  for (var x = 0; x < w; x++) {
    if (alpha(x, 0) != 255) seeThrough.add('($x, 0)');
    if (alpha(x, h - 1) != 255) seeThrough.add('($x, ${h - 1})');
  }
  for (var y = 0; y < h; y++) {
    if (alpha(0, y) != 255) seeThrough.add('(0, $y)');
    if (alpha(w - 1, y) != 255) seeThrough.add('(${w - 1}, $y)');
  }
  image.dispose();
  return (width: w, height: h, seeThrough: seeThrough);
}

void main() {
  for (final layout in ShareLayout.values) {
    for (final format in ShareFormat.values) {
      testWidgets('should_exportOpaqueEdges_when_${layout.storeValue}_${format.kotlinName}', (tester) async {
        final result = await _export(tester, content: shareFixture(), layout: layout, format: format);
        expect(result.seeThrough, isEmpty, reason: '${result.seeThrough.take(8)}');
        if (format == ShareFormat.card) {
          expect(result.width, YoinShareCards.cardExportWidthPx);
          expect(result.height, greaterThan(400));
        } else {
          expect(result.width, YoinShareCards.storyExportWidthPx);
          expect(result.height, YoinShareCards.storyExportHeightPx);
        }
      });
    }
  }

  testWidgets('should_exportOpaqueEdges_when_darkPaperWithoutCoverOrTranslations', (tester) async {
    for (final layout in [ShareLayout.aboutReceipt, ShareLayout.noteClipping, ShareLayout.lyricsVertical]) {
      for (final format in ShareFormat.values) {
        final result = await _export(
          tester,
          content: shareFixture(cover: false, translation: true),
          layout: layout,
          format: format,
          tone: layout.tab.role.tones().last,
          brightness: Brightness.dark,
        );
        expect(result.seeThrough, isEmpty, reason: '$layout $format ${result.seeThrough.take(8)}');
      }
    }
  });

  testWidgets('should_zoomOutTheStory_when_thePickIsLong', (tester) async {
    final long = shareFixture(lang: 'en');
    final content = ShareContent(
      song: long.song,
      lyrics: ShareLyrics(
        lines: [
          for (var i = 0; i < 15; i++)
            ShareLyricLine(startMs: i * 4000, text: 'A long line of lyrics number $i that wraps'),
        ],
      ),
    );
    await tester.pumpWidget(
      MaterialApp(
        theme: yoinTheme(Brightness.light),
        home: Center(
          child: ShareImage(
            content: content,
            layout: ShareLayout.lyricsClassic,
            format: ShareFormat.story,
            tone: ShareTone.soft,
          ),
        ),
      ),
    );
    final zoom = tester.renderObject<RenderShareZoomToFit>(find.byType(ShareZoomToFit));
    expect(zoom.zoom, lessThan(1));
    expect(zoom.zoom, greaterThanOrEqualTo(YoinShareCards.storyMinZoom));
    // Nothing cut: the card's whole height is drawn inside the frame.
    final frame = tester.getSize(find.byType(ShareZoomToFit));
    expect(frame.height, lessThanOrEqualTo(640 - 2 * YoinShareCards.storyMarginVerticalDp + 0.5));
  });

  testWidgets('should_zoomInTheStoryCard_when_thePickIsShort', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: yoinTheme(Brightness.light),
        home: Center(
          child: ShareImage(
            content: shareFixture(lang: 'en'),
            layout: ShareLayout.lyricsClassic,
            format: ShareFormat.story,
            tone: ShareTone.soft,
          ),
        ),
      ),
    );
    final zoom = tester.renderObject<RenderShareZoomToFit>(find.byType(ShareZoomToFit));
    expect(zoom.zoom, greaterThan(1));
    expect(zoom.zoom, lessThanOrEqualTo(YoinShareCards.storyMaxZoomLimit));
  });

  test('should_exportFixedPixelSizes_when_readingRatios', () {
    expect(ShareCardMetrics.cardWidth * sharePixelRatio(ShareFormat.card), YoinShareCards.cardExportWidthPx);
    expect(ShareCardMetrics.storySize * sharePixelRatio(ShareFormat.story), const Size(1080, 1920));
    expect(shareSheetIsWide(const Size(1280, 800)), isTrue);
    expect(shareSheetIsWide(const Size(800, 1280)), isFalse);
  });
}
