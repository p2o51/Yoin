import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/ui/component/corner_mask.dart';

Future<ui.Image> _render(WidgetTester tester, Widget w) async {
  final key = GlobalKey();
  await tester.pumpWidget(
    Directionality(
      textDirection: TextDirection.ltr,
      child: Center(child: RepaintBoundary(key: key, child: SizedBox(width: 100, height: 100, child: w))),
    ),
  );
  final boundary = key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
  return (await tester.runAsync(() => boundary.toImage()))!;
}

Future<Color> _px(WidgetTester tester, ui.Image img, int x, int y) async {
  final data = (await tester.runAsync(() => img.toByteData(format: ui.ImageByteFormat.rawRgba)))!;
  final i = (y * img.width + x) * 4;
  return Color.fromARGB(data.getUint8(i + 3), data.getUint8(i), data.getUint8(i + 1), data.getUint8(i + 2));
}

void main() {
  testWidgets('should_paintOnlyOutsideTheRound_when_cornersMasked', (tester) async {
    final img = await _render(
      tester,
      const CornerMask(
        radius: 40,
        topStart: true,
        bottomStart: true,
        fill: Color(0xFFFF0000),
        child: ColoredBox(color: Color(0xFF0000FF)),
      ),
    );
    Future<bool> red(int x, int y) async => (await _px(tester, img, x, y)).r > 0.9;
    expect(await red(2, 2), isTrue, reason: 'corner wedge');
    expect(await red(6, 6), isTrue, reason: 'inside the wedge, near the arc');
    expect(await red(20, 20), isFalse, reason: 'inside the round');
    expect(await red(50, 50), isFalse);
    expect(await red(2, 97), isTrue, reason: 'bottom-start wedge');
    expect(await red(20, 80), isFalse);
    expect(await red(97, 2), isFalse, reason: 'end corners untouched');
  });
}
