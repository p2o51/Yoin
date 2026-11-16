import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/ui/component/keep_in_view_above_keyboard.dart';

/// Android Modifier.keepInViewAboveKeyboard: a focused field keeps its label
/// and everything it holds in view, not only the text line.
void main() {
  late ScrollController scroll;

  Future<void> pump(WidgetTester tester, {required bool keep, double below = 0}) async {
    tester.view.physicalSize = const Size(400, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    scroll = ScrollController();
    addTearDown(scroll.dispose);
    final field = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SizedBox(key: ValueKey('label'), height: 40, child: Text('Label')),
        const TextField(key: ValueKey('box')),
        SizedBox(key: const ValueKey('below'), height: below),
      ],
    );
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ListView(
            controller: scroll,
            children: [
              const SizedBox(height: 1000),
              if (keep) KeepInViewAboveKeyboard(child: field) else field,
              const SizedBox(height: 1000),
            ],
          ),
        ),
      ),
    );
  }

  Rect rectOf(WidgetTester tester, String key) => tester.getRect(find.byKey(ValueKey(key)));

  testWidgets('should_bringTheLabelIntoView_when_theFieldTakesFocusWithItsLabelScrolledAway', (tester) async {
    for (final keep in [false, true]) {
      await pump(tester, keep: keep);
      // The label just above the top edge, the box on screen.
      scroll.jumpTo(1030);
      await tester.pump();
      expect(rectOf(tester, 'label').top, lessThan(0));
      await tester.tap(find.byKey(const ValueKey('box')));
      await tester.pumpAndSettle();
      if (keep) {
        expect(rectOf(tester, 'label').top, greaterThanOrEqualTo(0), reason: 'the whole field is in view');
      } else {
        expect(rectOf(tester, 'label').top, lessThan(0), reason: 'a bare text field only shows its text line');
      }
    }
  });

  testWidgets('should_keepTheWholeFieldAboveTheKeyboard_when_theKeyboardOpens', (tester) async {
    await pump(tester, keep: true, below: 80);
    scroll.jumpTo(500);
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('box')));
    await tester.pumpAndSettle();

    tester.view.viewInsets = const FakeViewPadding(bottom: 400);
    await tester.pumpAndSettle();
    expect(rectOf(tester, 'below').bottom, lessThanOrEqualTo(400.5), reason: 'nothing of the field under the keyboard');
    expect(rectOf(tester, 'label').top, greaterThanOrEqualTo(0));
  });
}
