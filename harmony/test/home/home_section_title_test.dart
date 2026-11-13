import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/home/home_header.dart';

void main() {
  testWidgets('should_addNoSpaceBelowTitle_when_usedBare', (tester) async {
    await tester.pumpWidget(const MaterialApp(
      home: Scaffold(body: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [HomeSectionTitle('Jump Back In')])),
    ));
    final title = tester.getRect(find.byType(HomeSectionTitle));
    final text = tester.getRect(find.text('Jump Back In'));
    // Only the Activities call site adds 6 dp; Jump Back In and the shelves use the bare title.
    expect(title.bottom, text.bottom);
  });
}
