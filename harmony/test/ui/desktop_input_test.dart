import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/ui/component/desktop_input.dart';
import 'package:yoin/ui/component/elastic_press.dart';

Widget _host(Widget child) => MaterialApp(
  home: Scaffold(
    body: Shortcuts(
      shortcuts: contextMenuShortcuts,
      child: Center(child: child),
    ),
  ),
);

const _key = ValueKey('target');

Widget _card({VoidCallback? onTap, VoidCallback? onLongPress}) => ElasticPress(
  key: _key,
  onTap: onTap,
  onLongPress: onLongPress,
  child: const SizedBox(width: 120, height: 120, child: ColoredBox(color: Colors.blue)),
);

void main() {
  testWidgets('should_showClickCursor_when_mouseHoversTappable', (tester) async {
    await tester.pumpWidget(_host(_card(onTap: () {})));
    final mouse = await tester.createGesture(kind: PointerDeviceKind.mouse);
    addTearDown(mouse.removePointer);
    await mouse.addPointer(location: Offset.zero);
    await mouse.moveTo(tester.getCenter(find.byKey(_key)));
    await tester.pumpAndSettle();
    expect(RendererBinding.instance.mouseTracker.debugDeviceActiveCursor(1), SystemMouseCursors.click);
  });

  testWidgets('should_tintWithStateLayer_when_mouseHovers', (tester) async {
    await tester.pumpWidget(_host(_card(onTap: () {})));
    ColorFilterLayer? tint() {
      final layers = tester.layers;
      return layers.whereType<ColorFilterLayer>().firstOrNull;
    }

    expect(tint(), isNull, reason: 'no layer at rest');
    final mouse = await tester.createGesture(kind: PointerDeviceKind.mouse);
    addTearDown(mouse.removePointer);
    await mouse.addPointer(location: Offset.zero);
    await mouse.moveTo(tester.getCenter(find.byKey(_key)));
    await tester.pumpAndSettle();
    expect(tint(), isNotNull);
    await mouse.moveTo(Offset.zero);
    await tester.pumpAndSettle();
    expect(tint(), isNull, reason: 'the layer leaves with the pointer');
  });

  testWidgets('should_openMenu_when_secondaryClicked', (tester) async {
    var menus = 0;
    var taps = 0;
    await tester.pumpWidget(_host(_card(onTap: () => taps++, onLongPress: () => menus++)));
    await tester.tap(find.byKey(_key), buttons: kSecondaryMouseButton, kind: PointerDeviceKind.mouse);
    await tester.pumpAndSettle();
    expect(menus, 1);
    expect(taps, 0);
  });

  testWidgets('should_activateAndOpenMenu_when_focusedByKeyboard', (tester) async {
    var menus = 0;
    var taps = 0;
    await tester.pumpWidget(_host(_card(onTap: () => taps++, onLongPress: () => menus++)));
    await tester.sendKeyEvent(LogicalKeyboardKey.tab);
    await tester.pumpAndSettle();
    await tester.sendKeyEvent(LogicalKeyboardKey.enter);
    await tester.pumpAndSettle();
    expect(taps, 1);
    await tester.sendKeyDownEvent(LogicalKeyboardKey.shiftLeft);
    await tester.sendKeyEvent(LogicalKeyboardKey.f10);
    await tester.sendKeyUpEvent(LogicalKeyboardKey.shiftLeft);
    await tester.pumpAndSettle();
    expect(menus, 1);
  });

  testWidgets('should_notTakeFocus_when_notTappable', (tester) async {
    await tester.pumpWidget(_host(_card()));
    await tester.sendKeyEvent(LogicalKeyboardKey.tab);
    await tester.pumpAndSettle();
    final focused = FocusManager.instance.primaryFocus?.context;
    expect(focused == null || find.byKey(_key).evaluate().every((e) => !_isAncestor(e, focused)), isTrue);
  });
}

bool _isAncestor(Element ancestor, BuildContext context) {
  var found = false;
  context.visitAncestorElements((e) {
    if (e == ancestor) found = true;
    return !found;
  });
  return found;
}
