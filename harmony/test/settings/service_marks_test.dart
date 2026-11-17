import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/media_id.dart';
import 'package:yoin/settings/account_identity.dart';
import 'package:yoin/settings/service_marks.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

void main() {
  test('should_drawAppleMusicMark_when_identityIsAppleMusic', () {
    expect(ServiceIdentity.appleMusic.glyph, const MarkGlyph(ServiceMark.appleMusic));
    expect(ServiceIdentity.forProvider(MediaId.providerAppleMusic).glyph, const MarkGlyph(ServiceMark.appleMusic));
    // Subsonic keeps its Yoin Symbol (Android has no redrawn Subsonic mark).
    expect(ServiceIdentity.subsonic.glyph, const SymbolGlyph(YoinSymbols.cloud));
  });

  test('should_keepInsideGrid_when_parsingAppleMusicMark', () {
    // Heads, stems and beam span the grid like Android's ImageVector (x 5.1–18.5, y 4.45–19.65).
    var bounds = Rect.zero;
    for (final p in ServiceMark.appleMusic.paths) {
      final b = parseMarkPath(p.data).getBounds();
      bounds = bounds == Rect.zero ? b : bounds.expandToInclude(b);
    }
    expect(bounds.left, closeTo(5.1, 0.01));
    expect(bounds.right, closeTo(18.5, 0.01));
    expect(bounds.top, closeTo(4.45, 0.01));
    expect(bounds.bottom, closeTo(19.65, 0.01));
  });

  test('should_closeShape_when_pathDataEndsWithZ', () {
    final beam = parseMarkPath('M10 6.35 L18.5 4.45 L18.5 6.7 L10 8.6 Z');
    // Inside the beam's quad, outside below it.
    expect(beam.contains(const Offset(14, 6.5)), isTrue);
    expect(beam.contains(const Offset(14, 9)), isFalse);
  });

  test('should_readRelativeAndImplicitCommands_when_parsingPathData', () {
    // m then two implicit relative linetos, then h / v.
    final path = parseMarkPath('m2 2 4 0 0 4 h-4 V2 z');
    expect(path.getBounds(), const Rect.fromLTRB(2, 2, 6, 6));
  });

  test('should_throw_when_pathDataIsMalformed', () {
    expect(() => parseMarkPath('10 10'), throwsFormatException);
    expect(() => parseMarkPath('M10'), throwsFormatException);
  });

  testWidgets('should_paintMarkAtIconSize_when_glyphIsAMark', (tester) async {
    await tester.pumpWidget(
      const Directionality(
        textDirection: TextDirection.ltr,
        child: Center(child: ServiceGlyphIcon(MarkGlyph(ServiceMark.appleMusic), size: 30, color: Color(0xFFFF0000))),
      ),
    );
    final mark = find.byType(ServiceMarkIcon);
    expect(mark, findsOneWidget);
    expect(tester.getSize(mark), const Size(30, 30));
    expect(find.byType(Icon), findsNothing);
  });

  testWidgets('should_takeIconThemeColor_when_markHasNoColor', (tester) async {
    await tester.pumpWidget(
      const Directionality(
        textDirection: TextDirection.ltr,
        child: IconTheme(
          data: IconThemeData(color: Color(0xFF00FF00), size: 20),
          child: Center(child: ServiceGlyphIcon(MarkGlyph(ServiceMark.appleMusic))),
        ),
      ),
    );
    expect(tester.getSize(find.byType(ServiceMarkIcon)), const Size(20, 20));
    final paint = tester.widget<CustomPaint>(
      find.descendant(of: find.byType(ServiceMarkIcon), matching: find.byType(CustomPaint)),
    );
    expect((paint.painter! as ServiceMarkPainter).color, const Color(0xFF00FF00));
  });

  testWidgets('should_showSymbol_when_glyphIsASymbol', (tester) async {
    await tester.pumpWidget(
      const Directionality(
        textDirection: TextDirection.ltr,
        child: Center(child: ServiceGlyphIcon(SymbolGlyph(YoinSymbols.cloud), size: 24)),
      ),
    );
    expect(find.byIcon(YoinSymbols.cloud), findsOneWidget);
  });
}
