import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/detail/album_hero_marks.dart';

void main() {
  test('should_matchGrooveGeometry_when_unrated64WithNineTracks', () {
    final g = UnratedEmblemGeometry(size: 64, trackCount: 9);
    // Android grooveGeometry(Unrated, 64dp, Bar surface).
    expect(g.rimRadius, 31.5);
    expect(g.labelRadius, closeTo(64 * 0.305 * 1.07, 1e-9));
    // band = (31.5 − 1.4) − (labelR + 1.2) ≈ 8.01 → floor(8.01 / 1.72) = 4 rings.
    expect(g.ringRadii, hasLength(4));
    expect(g.ringRadii.first, greaterThan(g.ringRadii.last));
    expect(g.labelStroke, 1.1);
    expect(g.showsWord, isTrue);
    expect(g.wordSize, 9.5);
    expect(g.showsSpindle, isFalse);
  });

  test('should_keepOneRingPerTrack_when_fewTracks', () {
    expect(UnratedEmblemGeometry(size: 64, trackCount: 2).ringRadii, hasLength(2));
    expect(UnratedEmblemGeometry(size: 64, trackCount: 0).ringRadii, hasLength(1));
  });

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
