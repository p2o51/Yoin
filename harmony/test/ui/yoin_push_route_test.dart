import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/ui/navigation/yoin_push_route.dart';

void main() {
  testWidgets('should_settleAndPopBack_when_pushedWithSpring', (tester) async {
    final navigator = GlobalKey<NavigatorState>();
    await tester.pumpWidget(MaterialApp(navigatorKey: navigator, home: const Text('root')));

    navigator.currentState!.push(YoinPushRoute<void>(builder: (_) => const Text('detail')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 16));
    // Mid-spring: the detail is on stage but still travelling in.
    final mid = tester.widget<Transform>(
      find.ancestor(of: find.text('detail'), matching: find.byType(Transform)).first,
    );
    expect(mid.transform.getTranslation().x, greaterThan(0));

    await tester.pumpAndSettle();
    expect(find.text('root'), findsNothing); // covered and faded out
    expect(find.text('detail'), findsOneWidget);

    navigator.currentState!.pop();
    await tester.pumpAndSettle();
    expect(find.text('detail'), findsNothing);
    expect(find.text('root'), findsOneWidget);
  });
}
