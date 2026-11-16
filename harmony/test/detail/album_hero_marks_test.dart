import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/detail/album_hero_marks.dart';

void main() {
  test('should_parseArmsInsideViewBox_when_markLoaded', () {
    for (final d in [
      'M87 437C131.456 476.373 325.523 529.493 417 551.132C380.523 608.976 329.456 678.162 417 762',
      'M10 10L20 20L10 20Z',
    ]) {
      final b = parseSvgPathData(d).getBounds();
      expect(b.left, greaterThanOrEqualTo(0));
      expect(b.right, lessThanOrEqualTo(YoinMarkArms.viewBox));
      expect(b.isEmpty, isFalse);
    }
  });

  test('should_rejectRelativeCommands_when_parsing', () {
    expect(() => parseSvgPathData('m0 0l10 10'), throwsFormatException);
  });
}
