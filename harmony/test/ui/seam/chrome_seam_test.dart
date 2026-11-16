import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/settings/appearance/scroll_edge_preview.dart';
import 'package:yoin/ui/component/seam/chrome_seam.dart';
import 'package:yoin/ui/component/seam/seam_style.dart';

void main() {
  Future<ChromeSeamController> pumpList(WidgetTester tester, ScrollController controller) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [keyValueStoreProvider.overrideWithValue(InMemoryStore())],
        child: MaterialApp(
          home: ChromeSeam(
            background: const SeamBackground([Colors.white]),
            child: ListView(
              controller: controller,
              children: [for (var i = 0; i < 200; i++) SizedBox(height: 48, child: Text('row $i'))],
            ),
          ),
        ),
      ),
    );
    return ChromeSeamScope.maybeOf(tester.element(find.text('row 0')))!;
  }

  testWidgets('should_holdTheSeamStill_when_listJumpsThroughChromeSeamJump', (tester) async {
    final controller = ScrollController();
    addTearDown(controller.dispose);
    final seam = await pumpList(tester, controller);
    ChromeSeam.jump(tester.element(find.text('row 0')), () => controller.jumpTo(2000));
    await tester.pump();
    expect(seam.flow.travelPx, 0, reason: 'a fast scroller jump is no scroll: no phase');
    expect(seam.flow.speedDp, 0, reason: 'no speed, so no stretch');
    expect(seam.reveal, 1, reason: 'the seam still knows how far the list is scrolled');
  });

  testWidgets('should_followTheScroll_when_listScrolledByHand', (tester) async {
    final controller = ScrollController();
    addTearDown(controller.dispose);
    final seam = await pumpList(tester, controller);
    await tester.drag(find.byType(ListView), const Offset(0, -300));
    await tester.pump(const Duration(milliseconds: 16));
    expect(seam.flow.travelPx, greaterThan(0));
    await tester.pumpAndSettle();
  });

  testWidgets('should_keepDriftToItself_when_previewPlays', (tester) async {
    var heard = 0;
    await tester.pumpWidget(
      ProviderScope(
        overrides: [keyValueStoreProvider.overrideWithValue(InMemoryStore())],
        child: MaterialApp(
          home: NotificationListener<ScrollNotification>(
            onNotification: (_) {
              heard++;
              return false;
            },
            child: const Center(
              child: SizedBox(
                width: 300,
                height: 150,
                child: ScrollEdgePreview(style: SeamTopStyle.dots, playing: true, cornerFill: Colors.white),
              ),
            ),
          ),
        ),
      ),
    );
    for (var i = 0; i < 20; i++) {
      await tester.pump(const Duration(milliseconds: 16));
    }
    expect(heard, 0, reason: "the preview's drift must not reach the page's scroll listeners");
  });
}
