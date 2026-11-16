import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/home/home_greeting_intro.dart';
import 'package:yoin/ui/component/yoin_mark.dart';

Widget host({bool reduced = false}) => MaterialApp(
  home: MediaQuery(
    data: MediaQueryData(disableAnimations: reduced),
    child: const Scaffold(
      body: Center(
        child: HomeIntroTitle(greeting: Text('Good morning'), title: Text('Home')),
      ),
    ),
  ),
);

void main() {
  setUp(HomeIntroLatch.reset);

  testWidgets('should_bloomMarkThenRaiseLines_when_coldStart', (tester) async {
    await tester.pumpWidget(host());
    expect(find.byType(YoinMark), findsOneWidget);
    expect(HomeIntroLatch.running.value, isTrue);
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.byType(YoinMark), findsOneWidget); // still blooming
    await tester.pumpAndSettle();
    expect(find.byType(YoinMark), findsNothing);
    expect(HomeIntroLatch.running.value, isFalse);
    expect(find.text('Home'), findsOneWidget);
  });

  testWidgets('should_notReplay_when_homeComesBack', (tester) async {
    await tester.pumpWidget(host());
    await tester.pumpAndSettle();
    await tester.pumpWidget(const SizedBox());
    await tester.pumpWidget(host());
    expect(find.byType(YoinMark), findsNothing);
    expect(HomeIntroLatch.running.value, isFalse);
  });

  testWidgets('should_showLinesAtOnce_when_reducedMotion', (tester) async {
    await tester.pumpWidget(host(reduced: true));
    await tester.pump();
    expect(find.byType(YoinMark), findsNothing);
    expect(HomeIntroLatch.running.value, isFalse);
    final opacities = tester.widgetList<Opacity>(find.ancestor(of: find.text('Home'), matching: find.byType(Opacity)));
    expect(opacities.every((o) => o.opacity == 1), isTrue);
  });

  testWidgets('should_paintAllThreeArms_when_drawn', (tester) async {
    await tester.pumpWidget(
      const Center(
        child: SizedBox.square(
          dimension: 56,
          child: YoinMark(
            transforms: [YoinArmTransform(), YoinArmTransform(), YoinArmTransform()],
            colors: [Colors.pink, Colors.purple, Colors.indigo],
            lineColor: Colors.white,
          ),
        ),
      ),
    );
    expect(tester.takeException(), isNull);
  });
}
