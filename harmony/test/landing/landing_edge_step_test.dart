import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/landing/landing_edge_step.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/settings/appearance/screen_corner_control.dart';
import 'package:yoin/settings/appearance/screen_corners.dart';
import 'package:yoin/ui/component/seam/seam_style.dart';

void main() {
  late InMemoryStore store;

  setUp(() => store = InMemoryStore());

  Future<ProviderContainer> pump(WidgetTester tester, Widget scene) async {
    tester.view.physicalSize = const Size(420, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final container = ProviderContainer(overrides: [keyValueStoreProvider.overrideWithValue(store)]);
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
          builder: (context, child) =>
              MediaQuery(data: MediaQuery.of(context).copyWith(disableAnimations: true), child: child!),
          home: Scaffold(body: scene),
        ),
      ),
    );
    await tester.pumpAndSettle();
    return container;
  }

  testWidgets('should_showChosenStyleLine_when_edgeStepOpens', (tester) async {
    await pump(tester, ScrollEdgeScene(playing: false, onSelect: (_) {}));
    expect(find.text('Tide line'), findsOneWidget);
    expect(find.text('Dots'), findsOneWidget);
    expect(find.text('Cookie wave'), findsOneWidget);
    // All three lines are laid out (one cell, no jump); only the chosen one is read out.
    expect(find.textContaining('Content sinks under two soft waves.', findRichText: true), findsOneWidget);
    expect(find.bySemanticsLabel(RegExp('Covers break into fine dots')), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('should_applyStyle_when_optionTapped', (tester) async {
    final picked = <SeamTopStyle>[];
    late ProviderContainer container;
    container = await pump(
      tester,
      Consumer(
        builder: (context, ref, _) => ScrollEdgeScene(
          playing: false,
          onSelect: (style) {
            picked.add(style);
            ref.read(seamTopStyleProvider.notifier).select(style);
          },
        ),
      ),
    );
    await tester.tap(find.text('Dots'));
    await tester.pumpAndSettle();
    expect(picked, [SeamTopStyle.dots]);
    expect(container.read(seamTopStyleProvider), SeamTopStyle.dots);
    expect(store.values['seam_top_style'], 'dots');
    expect(find.bySemanticsLabel(RegExp('Covers break into fine dots')), findsOneWidget);
  });

  testWidgets('should_traceTheWindowEdge_when_cornerBarDragged', (tester) async {
    final container = await pump(tester, const ScreenCornersScene());
    expect(find.text('Match screen'), findsWidgets);
    expect(find.text('28 dp'), findsOneWidget);
    final trace = find.byType(ScreenCornerTrace);
    expect(trace, findsOneWidget, reason: 'on the overlay, above the page');
    final bar = find.byType(ScreenCornerBar);
    final gesture = await tester.startGesture(tester.getCenter(bar));
    await gesture.moveBy(const Offset(-60, 0));
    await tester.pump();
    final painter = tester
        .widgetList<CustomPaint>(find.descendant(of: trace, matching: find.byType(CustomPaint)))
        .map((p) => p.painter)
        .whereType<ScreenCornerTracePainter>()
        .single;
    expect(painter.alpha, 1);
    expect(painter.radius, container.read(screenCornerRadiusProvider));
    expect(container.read(screenCornersProvider).following, isFalse);
    await gesture.up();
    await tester.pump();
    // It lingers a moment after the finger lifts, then fades.
    await tester.pump(const Duration(milliseconds: 1300));
    await tester.pumpAndSettle();
    final faded = tester
        .widgetList<CustomPaint>(find.descendant(of: trace, matching: find.byType(CustomPaint)))
        .map((p) => p.painter)
        .whereType<ScreenCornerTracePainter>()
        .single;
    expect(faded.alpha, 0);
    expect(store.values[ScreenCornersPreference.key], isNotNull);
  });
}
