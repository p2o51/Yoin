import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/ui/component/meta_line.dart';

void main() {
  test('should_keepTheNamesWidth_when_shortName', () {
    expect(versionTagMaxWidth(rowWidth: 200, nameWidth: 40, gap: 6), 154);
  });

  test('should_keepHalfForTheName_when_longName', () {
    expect(versionTagMaxWidth(rowWidth: 200, nameWidth: 500, gap: 6), 94);
    expect(versionTagMaxWidth(rowWidth: 4, nameWidth: 500, gap: 6), 0);
  });

  testWidgets('should_keepTheNameVisible_when_versionIsLong', (tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Material(
          child: Align(
            alignment: Alignment.topLeft,
            child: SizedBox(
              width: 160,
              child: _Title(name: 'Midnights', version: 'The Til Dawn Edition (3am Deluxe Anniversary)'),
            ),
          ),
        ),
      ),
    );
    final name = tester.getSize(find.text('Midnights'));
    final tag = tester.getSize(find.byType(AlbumVersionTag));
    expect(name.width, greaterThan(40), reason: 'the name keeps up to half the row');
    expect(tag.width, lessThanOrEqualTo(160 - name.width - 6 + 0.5));
    expect(tester.takeException(), isNull, reason: 'no overflow');
  });

  testWidgets('should_drawOnlyTheName_when_noVersion', (tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Material(child: _Title(name: 'Blue', version: null)),
      ),
    );
    expect(find.text('Blue'), findsOneWidget);
    expect(find.byType(AlbumVersionTag), findsNothing);
  });
}

class _Title extends StatelessWidget {
  const _Title({required this.name, required this.version});
  final String name;
  final String? version;

  @override
  Widget build(BuildContext context) =>
      VersionTitleRow.orTitle(name: name, nameStyle: const TextStyle(fontSize: 14), version: version);
}
