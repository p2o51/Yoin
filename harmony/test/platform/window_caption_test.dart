import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/platform/window/window_caption.dart';

void main() {
  const channel = MethodChannel('yoin/window');
  late List<String> calls;

  setUp(() => calls = []);
  tearDown(
    () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, null),
  );

  Future<double> pump(WidgetTester tester, Map<String, Object> caption) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call.method);
      return call.method == 'caption' ? caption : null;
    });
    var top = -1.0;
    await tester.pumpWidget(
      MediaQuery(
        data: const MediaQueryData(size: Size(1000, 700)),
        child: Directionality(
          textDirection: TextDirection.ltr,
          child: YoinWindowCaption(
            child: Builder(
              builder: (context) {
                top = MediaQuery.paddingOf(context).top;
                return const SizedBox.expand();
              },
            ),
          ),
        ),
      ),
    );
    await tester.pump();
    return top;
  }

  testWidgets('should_padTopByCaption_when_titleBarHidden', (tester) async {
    final top = await pump(tester, {'active': true, 'height': 40, 'buttonsRight': 12, 'buttonsWidth': 120});
    expect(top, 40);
  });

  testWidgets('should_leaveLayoutAlone_when_noCaption', (tester) async {
    final top = await pump(tester, {'active': false});
    expect(top, 0);
  });

  testWidgets('should_moveWindow_when_stripDragged', (tester) async {
    await pump(tester, {'active': true, 'height': 40, 'buttonsRight': 12, 'buttonsWidth': 120});
    await tester.dragFrom(const Offset(100, 20), const Offset(80, 30));
    await tester.pump(const Duration(milliseconds: 400));
    expect(calls, contains('startMoving'));
  });

  testWidgets('should_toggleMaximize_when_stripDoubleTapped', (tester) async {
    await pump(tester, {'active': true, 'height': 40, 'buttonsRight': 12, 'buttonsWidth': 120});
    await tester.tapAt(const Offset(100, 20));
    await tester.pump(const Duration(milliseconds: 50));
    await tester.tapAt(const Offset(100, 20));
    await tester.pumpAndSettle();
    expect(calls, contains('toggleMaximize'));
  });

  testWidgets('should_passTapsThrough_when_onTheButtons', (tester) async {
    await pump(tester, {'active': true, 'height': 40, 'buttonsRight': 12, 'buttonsWidth': 120});
    // The strip stops short of the system buttons (right 12 + 120 + 8).
    await tester.dragFrom(const Offset(950, 20), const Offset(-80, 30));
    await tester.pump(const Duration(milliseconds: 400));
    expect(calls, isNot(contains('startMoving')));
  });
}
